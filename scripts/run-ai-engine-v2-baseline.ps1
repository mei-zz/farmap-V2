param(
    [string]$MavenCommand = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repoRoot "zhgy\zhgy"

if ([string]::IsNullOrWhiteSpace($MavenCommand)) {
    $candidates = @(
        (Join-Path $repoRoot ".tools\apache-maven-3.9.16\bin\mvn.cmd"),
        "mvn.cmd",
        "mvn"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -eq "mvn.cmd" -or $candidate -eq "mvn" -or (Test-Path -LiteralPath $candidate)) {
            $MavenCommand = $candidate
            break
        }
    }
}

if ([string]::IsNullOrWhiteSpace($MavenCommand)) {
    throw "Maven executable not found. Pass -MavenCommand or install Maven on PATH."
}

Push-Location $backend
try {
    & $MavenCommand -Dtest=AiEngineBaselineEvaluationTest test
    if ($LASTEXITCODE -ne 0) { throw "Deterministic AI Engine baseline failed with exit code $LASTEXITCODE" }
    Write-Host "Baseline JSON: $backend\target\evaluation-results\baseline-v1.json"
    Write-Host "Baseline Markdown: $backend\target\evaluation-results\baseline-v1.md"
} finally {
    Pop-Location
}
