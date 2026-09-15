param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$VisionUrl = "http://127.0.0.1:5000",
    [string]$LocalAiUrl = "http://127.0.0.1:8001"
)

$ErrorActionPreference = "Continue"
function Get-Health($name, $url) {
    try {
        $headers = @{}
        if ($env:FARMAP_INTELLIGENCE_SERVICE_TOKEN) { $headers.Authorization = "Bearer $env:FARMAP_INTELLIGENCE_SERVICE_TOKEN" }
        $value = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 30
        [ordered]@{ name = $name; status = "REACHABLE"; payload = $value }
    } catch {
        [ordered]@{ name = $name; status = "UNAVAILABLE"; error = $_.Exception.GetType().Name }
    }
}

@(
    (Get-Health "vision" "$VisionUrl/v1/health"),
    (Get-Health "local-ai" "$LocalAiUrl/v1/health"),
    (Get-Health "spring" "$BackendUrl/api/runtime/health")
) | ConvertTo-Json -Depth 8
