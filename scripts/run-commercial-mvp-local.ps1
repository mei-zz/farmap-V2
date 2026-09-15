param(
    [int]$LocalAiPort = 8001,
    [int]$VisionPort = 5000,
    [switch]$StartBackend
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = "D:\anaconda\envs\llm\python.exe"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Python runtime not found: $python" }

$envFile = Join-Path $repoRoot ".env"
if (Test-Path -LiteralPath $envFile -PathType Leaf) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*#' -or $line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { continue }
        $name = $matches[1]; $value = $matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) { $value = $value.Substring(1, $value.Length - 2) }
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

# Local-only convenience: v1 remains authenticated in deployed environments.
if (-not $env:FARMAP_INTELLIGENCE_SERVICE_TOKEN) { $env:FARMAP_INTELLIGENCE_ALLOW_UNAUTHENTICATED_LOCAL = "true" }

$local = Start-Process -FilePath $python -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -ArgumentList @("-m", "uvicorn", "app:app", "--app-dir", "local-ai", "--host", "127.0.0.1", "--port", "$LocalAiPort")
$visionRoot = Join-Path $repoRoot "zhgy"
$env:PORT = "$VisionPort"
$vision = Start-Process -FilePath $python -WorkingDirectory $visionRoot -WindowStyle Hidden -PassThru -ArgumentList @("app.py")

$result = [ordered]@{ localAiPid = $local.Id; visionPid = $vision.Id; localAiUrl = "http://127.0.0.1:$LocalAiPort"; visionUrl = "http://127.0.0.1:$VisionPort" }
if ($StartBackend) {
    $backendScript = Join-Path $repoRoot "scripts\run-backend-local.ps1"
    $backend = Start-Process -FilePath "powershell.exe" -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $backendScript)
    $result.backendPid = $backend.Id
}
$result | ConvertTo-Json
