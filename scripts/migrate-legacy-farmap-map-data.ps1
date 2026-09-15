[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [string]$NormalizedJson,
    [string]$OutputSql = 'E:\FarMapTools\farmap-data-migration\normalized\legacy-map-import.sql',
    [string]$ExclusionReport = 'E:\FarMapTools\farmap-data-migration\logs\legacy-map-exclusions.json',
    [switch]$Apply,
    [string]$TargetBackupSql = 'E:\FarMapTools\farmap-data-migration\backups\target-before-legacy-map-import.sql',
    [string]$SchemaSql = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($SchemaSql)) {
    $SchemaSql = Join-Path $PSScriptRoot '..\zhgy\zhgy\src\main\resources\db\migration\V5_4_1__canonical_legacy_map_data.sql'
}

function Convert-ToSqlLiteral([AllowNull()][object]$Value) {
    if ($null -eq $Value) { return 'NULL' }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) { return 'NULL' }
    $escaped = $text.Replace('\', '\\').Replace("'", "\'")
    return "'$escaped'"
}

function Convert-ToSqlNumber([AllowNull()][object]$Value) {
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) { return 'NULL' }
    return ([double]$Value).ToString([Globalization.CultureInfo]::InvariantCulture)
}

function Test-Coordinate([AllowNull()][object]$Longitude, [AllowNull()][object]$Latitude) {
    if ($null -eq $Longitude -or $null -eq $Latitude) { return $false }
    $lon = [double]$Longitude
    $lat = [double]$Latitude
    return $lon -ge -180 -and $lon -le 180 -and $lat -ge -90 -and $lat -le 90 -and -not ($lon -eq 0 -and $lat -eq 0)
}

function Get-StablePointId([string]$SourceEntityType, [object]$LegacyId) {
    return "legacy-farmap-$SourceEntityType-$LegacyId"
}

function Get-OptionalProperty([AllowNull()][object]$Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

$source = Get-Content -LiteralPath $NormalizedJson -Raw | ConvertFrom-Json
$farmsById = @{}
foreach ($farm in @($source.farms)) {
    $farmsById[[string]$farm.sourceLegacyId] = $farm
}

$now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
$statements = [System.Collections.Generic.List[string]]::new()
$exclusions = [System.Collections.Generic.List[object]]::new()
$included = @{ farm = 0; boundary = 0; observation = 0 }

# Farm IDs are the authoritative legacy IDs and are reused as canonical farm IDs.
# The target backup is mandatory before -Apply so an existing same-ID farm can be
# reconciled or restored if its metadata differs.
foreach ($farm in @($source.farms)) {
    if ($null -eq $farm.sourceLegacyId -or [string]::IsNullOrWhiteSpace([string]$farm.name)) {
        $exclusions.Add([pscustomobject]@{ sourceEntityType = 'farm'; legacyId = $farm.sourceLegacyId; reasonCode = 'UNMAPPABLE_ENTITY' })
        continue
    }
    $farmUsername = Get-OptionalProperty $farm 'username'
    $farmValues = @(
        (Convert-ToSqlNumber $farm.sourceLegacyId),
        (Convert-ToSqlLiteral $farm.name),
        (Convert-ToSqlLiteral $farm.type),
        (Convert-ToSqlLiteral $farm.address),
        (Convert-ToSqlNumber $farm.zoom),
        (Convert-ToSqlLiteral $farm.center),
        (Convert-ToSqlLiteral $farmUsername),
        (Convert-ToSqlNumber $farm.ownerUserId)
    )
    $statements.Add("INSERT INTO farm (id,name,type,address,zoom,center,username,user_id) VALUES ($($farmValues -join ',')) ON DUPLICATE KEY UPDATE name=VALUES(name),type=VALUES(type),address=VALUES(address),zoom=VALUES(zoom),center=VALUES(center),username=COALESCE(VALUES(username),username),user_id=VALUES(user_id);")
    $included.farm++
}

foreach ($point in @($source.boundaryVertices)) {
    $farm = $farmsById[[string]$point.farmId]
    if ($null -eq $farm) {
        $exclusions.Add([pscustomobject]@{ sourceEntityType = 'location'; legacyId = $point.sourceLegacyId; reasonCode = 'MISSING_REQUIRED_RELATION' })
        continue
    }
    if (-not (Test-Coordinate $point.longitude $point.latitude)) {
        $exclusions.Add([pscustomobject]@{ sourceEntityType = 'location'; legacyId = $point.sourceLegacyId; reasonCode = 'INVALID_COORDINATE' })
        continue
    }
    $pointId = Get-StablePointId 'location' $point.sourceLegacyId
    $values = @(
        (Convert-ToSqlLiteral $pointId),
        (Convert-ToSqlNumber $point.farmId),
        (Convert-ToSqlLiteral 'BOUNDARY_VERTEX'),
        (Convert-ToSqlLiteral 'LEGACY_FARMAP'),
        (Convert-ToSqlLiteral 'location'),
        (Convert-ToSqlLiteral ([string]$point.sourceLegacyId)),
        (Convert-ToSqlNumber $point.sequence),
        (Convert-ToSqlNumber $point.longitude),
        (Convert-ToSqlNumber $point.latitude),
        (Convert-ToSqlLiteral 'GCJ-02'),
        'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL', 'NULL',
        (Convert-ToSqlNumber $farm.ownerUserId),
        (Convert-ToSqlLiteral 'ACTIVE'),
        (Convert-ToSqlLiteral $now),
        (Convert-ToSqlLiteral $now)
    )
    $statements.Add("INSERT INTO farm_map_points (point_id,farm_id,point_kind,source_system,source_entity_type,source_legacy_id,source_sequence,longitude,latitude,coordinate_reference_system,crop,observed_date,phenology,growth,impact,disease,potassium,phosphorus,nitrogen,volume,transmittance,image_url,yield_value,fruit,rate,diseases,severity,owner_user_id,status,created_at,updated_at) VALUES ($($values -join ',')) ON DUPLICATE KEY UPDATE farm_id=VALUES(farm_id),point_kind=VALUES(point_kind),source_sequence=VALUES(source_sequence),longitude=VALUES(longitude),latitude=VALUES(latitude),coordinate_reference_system=VALUES(coordinate_reference_system),owner_user_id=VALUES(owner_user_id),status=VALUES(status),updated_at=VALUES(updated_at);")
    $included.boundary++
}

foreach ($point in @($source.cropObservations)) {
    $farm = $farmsById[[string]$point.farmId]
    if ($null -eq $farm) {
        $exclusions.Add([pscustomobject]@{ sourceEntityType = 'crops'; legacyId = $point.sourceLegacyId; reasonCode = 'MISSING_REQUIRED_RELATION' })
        continue
    }
    if (-not (Test-Coordinate $point.longitude $point.latitude)) {
        $exclusions.Add([pscustomobject]@{ sourceEntityType = 'crops'; legacyId = $point.sourceLegacyId; reasonCode = 'INVALID_COORDINATE' })
        continue
    }
    $pointId = Get-StablePointId 'crops' $point.sourceLegacyId
    $values = @(
        (Convert-ToSqlLiteral $pointId),
        (Convert-ToSqlNumber $point.farmId),
        (Convert-ToSqlLiteral 'CROP_OBSERVATION'),
        (Convert-ToSqlLiteral 'LEGACY_FARMAP'),
        (Convert-ToSqlLiteral 'crops'),
        (Convert-ToSqlLiteral ([string]$point.sourceLegacyId)),
        'NULL',
        (Convert-ToSqlNumber $point.longitude),
        (Convert-ToSqlNumber $point.latitude),
        (Convert-ToSqlLiteral 'GCJ-02'),
        (Convert-ToSqlLiteral $farm.type),
        (Convert-ToSqlLiteral $point.observedDate),
        (Convert-ToSqlNumber $point.phenology),
        (Convert-ToSqlLiteral $point.growth),
        (Convert-ToSqlLiteral $point.impact),
        (Convert-ToSqlLiteral $point.disease),
        (Convert-ToSqlLiteral $point.potassium),
        (Convert-ToSqlLiteral $point.phosphorus),
        (Convert-ToSqlNumber $point.nitrogen),
        (Convert-ToSqlNumber $point.volume),
        (Convert-ToSqlNumber $point.transmittance),
        (Convert-ToSqlLiteral $point.imageUrl),
        (Convert-ToSqlNumber $point.yield),
        (Convert-ToSqlLiteral $point.fruit),
        (Convert-ToSqlNumber $point.rate),
        (Convert-ToSqlLiteral $point.diseases),
        (Convert-ToSqlLiteral $point.severity),
        (Convert-ToSqlNumber $farm.ownerUserId),
        (Convert-ToSqlLiteral 'ACTIVE'),
        (Convert-ToSqlLiteral $now),
        (Convert-ToSqlLiteral $now)
    )
    $statements.Add("INSERT INTO farm_map_points (point_id,farm_id,point_kind,source_system,source_entity_type,source_legacy_id,source_sequence,longitude,latitude,coordinate_reference_system,crop,observed_date,phenology,growth,impact,disease,potassium,phosphorus,nitrogen,volume,transmittance,image_url,yield_value,fruit,rate,diseases,severity,owner_user_id,status,created_at,updated_at) VALUES ($($values -join ',')) ON DUPLICATE KEY UPDATE farm_id=VALUES(farm_id),point_kind=VALUES(point_kind),longitude=VALUES(longitude),latitude=VALUES(latitude),coordinate_reference_system=VALUES(coordinate_reference_system),crop=VALUES(crop),observed_date=VALUES(observed_date),phenology=VALUES(phenology),growth=VALUES(growth),impact=VALUES(impact),disease=VALUES(disease),potassium=VALUES(potassium),phosphorus=VALUES(phosphorus),nitrogen=VALUES(nitrogen),volume=VALUES(volume),transmittance=VALUES(transmittance),image_url=VALUES(image_url),yield_value=VALUES(yield_value),fruit=VALUES(fruit),rate=VALUES(rate),diseases=VALUES(diseases),severity=VALUES(severity),owner_user_id=VALUES(owner_user_id),status=VALUES(status),updated_at=VALUES(updated_at);")
    $included.observation++
}

$outputDirectory = Split-Path -Parent $OutputSql
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
Set-Content -LiteralPath $OutputSql -Value (($statements -join [Environment]::NewLine) + [Environment]::NewLine) -Encoding utf8
$exclusionDirectory = Split-Path -Parent $ExclusionReport
New-Item -ItemType Directory -Force -Path $exclusionDirectory | Out-Null
([pscustomobject]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    normalizedJson = $NormalizedJson
    includedFarms = $included.farm
    includedBoundaryVertices = $included.boundary
    includedCropObservations = $included.observation
    excluded = @($exclusions)
}) | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ExclusionReport -Encoding utf8

if ($Apply) {
    if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) { throw 'mysql client is required for -Apply' }
    foreach ($name in @('FARMAP_TARGET_MYSQL_HOST', 'FARMAP_TARGET_MYSQL_DATABASE', 'FARMAP_TARGET_MYSQL_USER', 'FARMAP_TARGET_MYSQL_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "$name must be provided through the environment for -Apply" }
    }
    if (-not $PSCmdlet.ShouldProcess($OutputSql, 'backup target tables and apply idempotent farm map import')) { return }
    $backupDirectory = Split-Path -Parent $TargetBackupSql
    New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
    $mysqlArgs = @('--host=' + $env:FARMAP_TARGET_MYSQL_HOST, '--database=' + $env:FARMAP_TARGET_MYSQL_DATABASE, '--user=' + $env:FARMAP_TARGET_MYSQL_USER)
    if ($env:FARMAP_TARGET_MYSQL_PORT) { $mysqlArgs += '--port=' + $env:FARMAP_TARGET_MYSQL_PORT }
    $env:MYSQL_PWD = $env:FARMAP_TARGET_MYSQL_PASSWORD
    try {
        & mysqldump @mysqlArgs --single-transaction --skip-lock-tables --no-tablespaces --force farm farm_fields farm_map_points 1> $TargetBackupSql
        if ($LASTEXITCODE -ne 0) { throw 'target backup failed' }
        Get-Content -LiteralPath $SchemaSql -Raw | & mysql @mysqlArgs
        if ($LASTEXITCODE -ne 0) { throw 'target schema migration failed' }
        Get-Content -LiteralPath $OutputSql -Raw | & mysql @mysqlArgs
        if ($LASTEXITCODE -ne 0) { throw 'target import failed' }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

[pscustomobject]@{ outputSql = $OutputSql; exclusionReport = $ExclusionReport; includedFarms = $included.farm; includedBoundaryVertices = $included.boundary; includedCropObservations = $included.observation; excluded = $exclusions.Count }
