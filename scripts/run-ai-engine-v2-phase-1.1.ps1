$ErrorActionPreference = "Stop"
$maven = "C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.12\59fe215c0ad6947fea90184bf7add084544567b927287592651fda3782e0e798\bin\mvn.cmd"
$project = Join-Path $PSScriptRoot "..\zhgy\zhgy"
& $maven -q "-Dtest=AgentV2Phase11EvaluationTest" test
if ($LASTEXITCODE -ne 0) { throw "Phase 1.1 deterministic evaluation failed" }
Write-Output "Evaluation artifacts: $project\target\evaluation-results\agent-v2-phase-1.1.json and agent-v2-phase-1.1.md"
