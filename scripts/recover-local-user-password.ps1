<#
.SYNOPSIS
    One-time LOCAL DEVELOPMENT recovery for user_id=1.

.DESCRIPTION
    The current FarMap authentication implementation stores and compares the
    password value directly; this utility therefore follows that existing
    contract. It does not create a user, change role or ownership, or write a
    password into a file. The password is supplied interactively or through
    FARMAP_LOCAL_RESET_PASSWORD and is held only in process memory.

    Do not use this utility against a production database.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$targetUserId = 1
$repoRoot = Split-Path -Parent $PSScriptRoot
$credentialFile = Join-Path $repoRoot "mysql.txt"

if (-not (Test-Path -LiteralPath $credentialFile)) {
    throw "Local MySQL credential file not found: $credentialFile"
}

$mysqlExe = (Get-Command mysql.exe -ErrorAction SilentlyContinue).Source
if (-not $mysqlExe) {
    $mysqlCandidates = @(
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
        "C:\xampp\mysql\bin\mysql.exe"
    )
    $mysqlExe = $mysqlCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $mysqlExe) {
    throw "mysql.exe was not found on PATH or in the standard local locations"
}

$credentialLines = Get-Content -LiteralPath $credentialFile
$dbUserLine = $credentialLines | Where-Object { $_ -match "^user=" } | Select-Object -First 1
$dbPasswordLine = $credentialLines | Where-Object { $_ -match "^psw=" } | Select-Object -First 1
if (-not $dbUserLine -or -not $dbPasswordLine) {
    throw "mysql.txt must contain user= and psw= entries"
}
$dbUser = $dbUserLine -replace "^user=", ""
$dbPassword = $dbPasswordLine -replace "^psw=", ""

function Invoke-LocalMySql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Query
    )

    $previousMysqlPassword = $env:MYSQL_PWD
    $env:MYSQL_PWD = $dbPassword
    try {
        $result = @(& $mysqlExe `
            --host=127.0.0.1 `
            --port=3306 `
            --user=$dbUser `
            --database=zhgy `
            --batch `
            --raw `
            --skip-column-names `
            --execute=$Query 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "MySQL command failed: $($result -join ' ')"
        }
        return $result
    }
    finally {
        if ($null -eq $previousMysqlPassword) {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }
        else {
            $env:MYSQL_PWD = $previousMysqlPassword
        }
    }
}

function Get-UserIdentity {
    $rows = @(Invoke-LocalMySql "SELECT id,username,role FROM ``user`` WHERE id=$targetUserId;")
    if ($rows.Count -ne 1) {
        throw "Expected exactly one row for user_id=$targetUserId"
    }
    $parts = $rows[0] -split "`t", -1
    if ($parts.Count -ne 3) {
        throw "Unexpected user identity response"
    }
    return [pscustomobject]@{
        Id = $parts[0]
        Username = $parts[1]
        Role = $parts[2]
    }
}

function Get-FarmIds {
    return @(Invoke-LocalMySql "SELECT id FROM farm WHERE user_id=$targetUserId ORDER BY id;" | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ })
}

$chosenPassword = $env:FARMAP_LOCAL_RESET_PASSWORD
$securePassword = $null
$passwordPointer = [IntPtr]::Zero
try {
    if ([string]::IsNullOrEmpty($chosenPassword)) {
        $securePassword = Read-Host "Choose a LOCAL password for user_id=1 (input is hidden)" -AsSecureString
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $chosenPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    }

    if ([string]::IsNullOrEmpty($chosenPassword)) {
        throw "A non-empty local password is required"
    }
    if ($chosenPassword.IndexOf([char]0) -ge 0) {
        throw "The local password cannot contain a NUL character"
    }

    $beforeUser = Get-UserIdentity
    $beforeFarmIds = @(Get-FarmIds)
    Write-Host "Target user_id=$($beforeUser.Id), login=$($beforeUser.Username), role=$($beforeUser.Role)"
    Write-Host "Existing owned farm IDs: [$($beforeFarmIds -join ',')]"

    $passwordBytes = [Text.Encoding]::UTF8.GetBytes($chosenPassword)
    $passwordHex = [BitConverter]::ToString($passwordBytes).Replace("-", "")
    $updateQuery = @"
START TRANSACTION;
UPDATE ``user`` SET password=CONVERT(0x$passwordHex USING utf8mb4) WHERE id=$targetUserId;
SELECT ROW_COUNT();
COMMIT;
"@
    $updateResult = @(Invoke-LocalMySql $updateQuery)
    if (-not ($updateResult -contains "1")) {
        throw "The password update did not affect exactly one user row"
    }

    $afterUser = Get-UserIdentity
    $afterFarmIds = @(Get-FarmIds)
    if ($afterUser.Id -ne $beforeUser.Id -or $afterUser.Username -cne $beforeUser.Username -or $afterUser.Role -cne $beforeUser.Role) {
        throw "User identity changed unexpectedly; password recovery verification failed"
    }
    if (($afterFarmIds -join ",") -cne ($beforeFarmIds -join ",")) {
        throw "Farm ownership changed unexpectedly; password recovery verification failed"
    }

    Write-Host "LOCAL password credential updated for user_id=1; username, role, and farm ownership are unchanged."
}
finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ($securePassword) {
        $securePassword.Dispose()
    }
    $chosenPassword = $null
    $passwordBytes = $null
    $passwordHex = $null
    if (Test-Path Env:FARMAP_LOCAL_RESET_PASSWORD) {
        Remove-Item Env:FARMAP_LOCAL_RESET_PASSWORD -ErrorAction SilentlyContinue
    }
}
