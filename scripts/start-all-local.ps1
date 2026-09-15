param(
    [switch]$SkipDocker,
    [switch]$SkipLocalAi,
    [switch]$SkipVision,
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$Wait
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $repoRoot ".runtime\local-services"
$python = "D:\anaconda\envs\llm\python.exe"
$milvusCompose = Join-Path $repoRoot "zhgy\zhgy\docker-compose-milvus.yml"

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

function Import-EnvFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*#' -or $line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { continue }
        $name = $matches[1]
        $value = $matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, "Process"))) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

Import-EnvFile (Join-Path $repoRoot "deployment\.env")
Import-EnvFile (Join-Path $repoRoot ".env")
if (-not $env:API_KEYS -and $env:APIKEY) { $env:API_KEYS = $env:APIKEY }
if (-not $env:FARMAP_INTELLIGENCE_SERVICE_TOKEN) { $env:FARMAP_INTELLIGENCE_ALLOW_UNAUTHENTICATED_LOCAL = "true" }
# The local launcher uses the host-side vision process. The packaged Docker
# compose defaults (tree-analyzer:5000) are only valid inside the compose
# network, so set the local endpoints before spawning the backend child.
if (-not $env:MODEL_API_URL) { $env:MODEL_API_URL = "http://127.0.0.1:5000/analyze" }
if (-not $env:MODEL_API_V1_URL) { $env:MODEL_API_V1_URL = "http://127.0.0.1:5000/v1/vision/analyze" }
# Local defense/demo profile: fixtures are idempotent and carry explicit
# SYNTHETIC_PRESENTATION provenance in the database and technical details.
if (-not $env:FARMAP_PRESENTATION_DATA_ENABLED) { $env:FARMAP_PRESENTATION_DATA_ENABLED = "true" }

function Test-Port([int]$port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Start-PowershellService([string]$name, [string]$scriptPath, [int]$port, [object[]]$arguments = @()) {
    $stdout = Join-Path $runtimeDir ($name + ".out.log")
    $stderr = Join-Path $runtimeDir ($name + ".err.log")
    if (Test-Port $port) {
        return [ordered]@{ name = $name; status = "ALREADY_LISTENING"; port = $port; log = $stdout }
    }
    $argumentList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $scriptPath) + $arguments
    $process = Start-Process -FilePath "powershell.exe" -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
        -ArgumentList $argumentList -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    return [ordered]@{ name = $name; status = "STARTED"; pid = $process.Id; port = $port; log = $stdout }
}

function Start-PythonService([string]$name, [string]$workingDirectory, [string[]]$arguments, [int]$port) {
    $stdout = Join-Path $runtimeDir ($name + ".out.log")
    $stderr = Join-Path $runtimeDir ($name + ".err.log")
    if (Test-Port $port) {
        return [ordered]@{ name = $name; status = "ALREADY_LISTENING"; port = $port; log = $stdout }
    }
    if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Python runtime not found: $python" }
    $process = Start-Process -FilePath $python -WorkingDirectory $workingDirectory -WindowStyle Hidden -PassThru `
        -ArgumentList $arguments -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    return [ordered]@{ name = $name; status = "STARTED"; pid = $process.Id; port = $port; log = $stdout }
}

$services = [System.Collections.Generic.List[object]]::new()

if (-not $SkipDocker) {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker CLI was not found on PATH." }
    if (-not (Test-Path -LiteralPath $milvusCompose -PathType Leaf)) { throw "Milvus compose file not found: $milvusCompose" }
    if (Test-Port 19530) {
        $dockerStatus = "MILVUS_ALREADY_LISTENING"
    } else {
        & docker compose -f $milvusCompose up -d
        if ($LASTEXITCODE -ne 0) { throw "Milvus Docker services failed to start." }
        $dockerStatus = "MILVUS_COMPOSE_UP"
    }
} else {
    $dockerStatus = "SKIPPED"
}

if (-not $SkipLocalAi) {
    $services.Add((Start-PowershellService "local-ai" (Join-Path $repoRoot "scripts\run-local-ai.ps1") 8001 @("-Port", "8001")))
}
if (-not $SkipVision) {
    $env:PORT = "5000"
    $services.Add((Start-PythonService "vision" (Join-Path $repoRoot "zhgy") @("app.py") 5000))
}
if (-not $SkipBackend) {
    $services.Add((Start-PowershellService "backend" (Join-Path $repoRoot "scripts\run-backend-local.ps1") 8080))
}
if (-not $SkipFrontend) {
    $services.Add((Start-PowershellService "frontend" (Join-Path $repoRoot "scripts\run-frontend-local.ps1") 5173 @("-RagMode", "real", "-AgentMode", "real")))
}

if ($Wait) {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $requiredPorts = @()
        if (-not $SkipLocalAi) { $requiredPorts += 8001 }
        if (-not $SkipVision) { $requiredPorts += 5000 }
        if (-not $SkipBackend) { $requiredPorts += 8080 }
        if (-not $SkipFrontend) { $requiredPorts += 5173 }
        if (($requiredPorts | Where-Object { -not (Test-Port $_) }).Count -eq 0) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
}

$summary = [ordered]@{
    docker = $dockerStatus
    services = @($services)
    urls = [ordered]@{
        frontend = "http://127.0.0.1:5173/farmap/"
        backendHealth = "http://127.0.0.1:8080/api/runtime/health"
        localAiHealth = "http://127.0.0.1:8001/health"
        visionHealth = "http://127.0.0.1:5000/health"
    }
    logs = $runtimeDir
}
$summary | ConvertTo-Json -Depth 5
