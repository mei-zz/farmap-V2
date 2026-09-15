param(
    [string]$UserName = "farmap_admin_all",
    [Parameter(Mandatory = $true)]
    [string]$Password
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$credentialFile = Join-Path $repoRoot "mysql.txt"

if (-not (Test-Path -LiteralPath $credentialFile -PathType Leaf)) {
    throw "Missing local MySQL credential file: $credentialFile"
}

$mysql = Get-Command mysql -ErrorAction SilentlyContinue
$python = "D:\anaconda\envs\llm\python.exe"
$hasPyMySql = $false
if (Test-Path -LiteralPath $python -PathType Leaf) {
    $probe = & $python -c "import importlib.util; print('yes' if importlib.util.find_spec('pymysql') else 'no')" 2>$null
    $hasPyMySql = ($LASTEXITCODE -eq 0 -and ($probe -join "").Trim() -eq "yes")
}
if (-not $mysql -and -not $hasPyMySql) {
    throw "Neither a usable mysql client nor the bundled Python pymysql driver was found."
}

if ($UserName -notmatch '^[A-Za-z0-9_.-]{3,64}$') {
    throw "UserName must contain 3-64 letters, digits, '.', '_' or '-'."
}
if ([string]::IsNullOrWhiteSpace($Password) -or $Password.Length -lt 10) {
    throw "Password must contain at least 10 characters."
}

$credential = @{}
foreach ($line in Get-Content -LiteralPath $credentialFile) {
    if ($line -match '^\s*([^=]+)=(.*)$') {
        $credential[$matches[1].Trim()] = $matches[2].Trim()
    }
}
if ([string]::IsNullOrWhiteSpace($credential["user"]) -or $null -eq $credential["psw"]) {
    throw "mysql.txt must contain user= and psw= entries."
}

function SqlQuote([string]$value) {
    return "'" + $value.Replace("'", "''") + "'"
}

$result = $null
if ($hasPyMySql) {
    # Keep database credentials and the new password in the child process
    # environment, never in a command-line argument or repository file.
    $env:FARMAP_PROVISION_DB_USER = $credential["user"]
    $env:FARMAP_PROVISION_DB_PASSWORD = $credential["psw"]
    $env:FARMAP_PROVISION_USERNAME = $UserName
    $env:FARMAP_PROVISION_PASSWORD = $Password
    $pythonCode = @'
import os
import pymysql

connection = pymysql.connect(
    host="127.0.0.1",
    port=3306,
    user=os.environ["FARMAP_PROVISION_DB_USER"],
    password=os.environ["FARMAP_PROVISION_DB_PASSWORD"],
    database="zhgy",
    autocommit=False,
    charset="utf8mb4",
)
try:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT id FROM `user` WHERE username=%s ORDER BY id FOR UPDATE",
            (os.environ["FARMAP_PROVISION_USERNAME"],),
        )
        rows = cursor.fetchall()
        if rows:
            keep_id = rows[0][0]
            cursor.execute(
                "UPDATE `user` SET password=%s,role='admin' WHERE id=%s",
                (os.environ["FARMAP_PROVISION_PASSWORD"], keep_id),
            )
            if len(rows) > 1:
                cursor.execute(
                    "DELETE FROM `user` WHERE username=%s AND id<>%s",
                    (os.environ["FARMAP_PROVISION_USERNAME"], keep_id),
                )
        else:
            cursor.execute(
                "INSERT INTO `user` (username,password,role) VALUES (%s,%s,'admin')",
                (os.environ["FARMAP_PROVISION_USERNAME"], os.environ["FARMAP_PROVISION_PASSWORD"]),
            )
            keep_id = cursor.lastrowid
        cursor.execute(
            "SELECT id,username,role FROM `user` WHERE id=%s",
            (keep_id,),
        )
        row = cursor.fetchone()
    connection.commit()
    if not row:
        raise RuntimeError("account was not found after provisioning")
    print("%s\t%s\t%s" % row)
finally:
    connection.close()
'@
    $pythonFile = Join-Path ([IO.Path]::GetTempPath()) ("farmap-provision-" + [guid]::NewGuid().ToString("N") + ".py")
    Set-Content -LiteralPath $pythonFile -Value $pythonCode -Encoding utf8
    try {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $result = @(& $python $pythonFile 2>&1)
        $pythonExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
    } finally {
        Remove-Item -LiteralPath $pythonFile -Force -ErrorAction SilentlyContinue
        Remove-Item Env:FARMAP_PROVISION_DB_USER,Env:FARMAP_PROVISION_DB_PASSWORD,Env:FARMAP_PROVISION_USERNAME,Env:FARMAP_PROVISION_PASSWORD -ErrorAction SilentlyContinue
    }
    if ($pythonExitCode -ne 0) {
        throw ("MySQL account provisioning failed: " + ($result -join "\n"))
    }
} else {
    $tempDefaults = Join-Path ([IO.Path]::GetTempPath()) ("farmap-mysql-" + [guid]::NewGuid().ToString("N") + ".cnf")
    try {
        @(
            "[client]",
            "host=127.0.0.1",
            "port=3306",
            "user=$($credential['user'])",
            "password=$($credential['psw'])",
            "database=zhgy"
        ) | Set-Content -LiteralPath $tempDefaults -Encoding ascii
        $safeUser = SqlQuote $UserName
        $safePassword = SqlQuote $Password
        $sql = "INSERT INTO ``user`` (username,password,role) VALUES ($safeUser,$safePassword,'admin') ON DUPLICATE KEY UPDATE password=VALUES(password),role='admin'; SELECT id,username,role FROM ``user`` WHERE username=$safeUser;"
        $result = & $mysql.Source "--defaults-extra-file=$tempDefaults" --batch --skip-column-names "--execute=$sql" 2>&1
    } finally {
        Remove-Item -LiteralPath $tempDefaults -Force -ErrorAction SilentlyContinue
    }
    if ($LASTEXITCODE -ne 0) {
        throw ("MySQL account provisioning failed: " + ($result -join "\n"))
    }
}

$row = @($result | Where-Object { $_ -match '^\d+\t' } | Select-Object -Last 1)
if ($row.Count -ne 1) { throw "The account was not found after provisioning." }
Write-Output ("Provisioned full-access admin account: {0}" -f $UserName)
Write-Output ("Database row: {0}" -f $row[0])
Write-Output "Role is admin; the account is authorized by the application to list all farms and farm-scoped data."
