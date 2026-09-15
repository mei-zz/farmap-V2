param(
    [string]$JavaTempDir = "D:\farmap-runtime-temp"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"
$backendRoot = Join-Path $repoRoot "zhgy\zhgy"
$bundledMaven = Join-Path $repoRoot ".tools\apache-maven-3.9.16\bin\mvn.cmd"
$mavenCommand = if (Test-Path -LiteralPath $bundledMaven -PathType Leaf) { $bundledMaven } else { "mvn" }

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Missing local environment file: $envFile"
}

foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*#' -or $line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        continue
    }
    $name = $matches[1]
    $value = $matches[2].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($name, $value, "Process")
}

# Existing local aliases are mapped in-process only; no .env file is changed.
if ($env:APIKEY -and -not $env:BAILIAN_API_KEY -and -not $env:DASHSCOPE_API_KEY) {
    $env:BAILIAN_API_KEY = $env:APIKEY
}
if ($env:BASEURL -and -not $env:BAILIAN_API_BASE_URL) {
    $env:BAILIAN_API_BASE_URL = $env:BASEURL
}

New-Item -ItemType Directory -Force -Path $JavaTempDir | Out-Null
$env:FARMAP_RUN_REAL_INTEGRATION = "true"
$env:TEMP = $JavaTempDir
$env:TMP = $JavaTempDir
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$JavaTempDir"

Push-Location $backendRoot
try {
    & $mavenCommand "-Dtest=DiagnosisRagIntegrationTest" "test"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
