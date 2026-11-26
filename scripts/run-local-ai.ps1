param([int]$Port = 8001)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = @($env:FARMAP_LOCAL_AI_PYTHON, "E:\anaconda\envs\llm\python.exe", "D:\anaconda\envs\llm\python.exe") |
    Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
if (-not $python) { throw "找不到本地 AI Python，请在 FARMAP_LOCAL_AI_PYTHON 指定已安装依赖的 Python 路径。" }
Push-Location $repoRoot
try {
    & $python -m uvicorn app:app --app-dir local-ai --host 127.0.0.1 --port $Port
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
