param(
    [ValidateSet("real", "mock")]
    [string]$RagMode = "real",
    [ValidateSet("real", "mock")]
    [string]$AgentMode = "real"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"
$deploymentEnvFile = Join-Path $repoRoot "deployment\.env"
$frontendRoot = Join-Path $repoRoot "farmap-frontend-dev"

if (Test-Path -LiteralPath $envFile -PathType Leaf) {
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
}

# The browser SDK requires a Vite-prefixed variable. Prefer an explicit value,
# then the local provider value, then the public browser key in deployment/.env.
if ([string]::IsNullOrWhiteSpace($env:VITE_GAODE_API_KEY) -and -not [string]::IsNullOrWhiteSpace($env:GAODE_API_KEY)) {
    $env:VITE_GAODE_API_KEY = $env:GAODE_API_KEY
}
if ([string]::IsNullOrWhiteSpace($env:VITE_GAODE_API_KEY) -and (Test-Path -LiteralPath $deploymentEnvFile -PathType Leaf)) {
    foreach ($line in Get-Content -LiteralPath $deploymentEnvFile) {
        if ($line -notmatch '^\s*GAODE_API_KEY\s*=\s*(.*)$') { continue }
        $value = $matches[1].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $env:VITE_GAODE_API_KEY = $value
            break
        }
    }
}

$env:VITE_RAG_MODE = $RagMode
$env:VITE_AGENT_MODE = $AgentMode
Push-Location $frontendRoot
try {
    & pnpm dev
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
