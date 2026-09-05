param([int]$Port = 8001)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = "D:\anaconda\envs\llm\python.exe"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Python runtime not found: $python" }
Push-Location $repoRoot
try {
    & $python -m uvicorn app:app --app-dir local-ai --host 127.0.0.1 --port $Port
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
