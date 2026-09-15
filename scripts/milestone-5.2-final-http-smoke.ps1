param(
    [string]$BaseUrl = $(if ($env:FARMAP_BASE_URL) { $env:FARMAP_BASE_URL } else { 'http://127.0.0.1:8080' }),
    [string]$UserToken = $env:FARMAP_USER_TOKEN,
    [string]$SecondUserToken = $env:FARMAP_USER_TOKEN_B
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Invoke-Api {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body)
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; UseBasicParsing = $true }
    if ($null -ne $Body) { $params.ContentType = 'application/json'; $params.Body = ($Body | ConvertTo-Json -Depth 20 -Compress) }
    $response = $null
    $rawContent = $null
    $statusCode = $null
    try {
        $response = Invoke-WebRequest @params
        $rawContent = $response.Content
        $statusCode = [int]$response.StatusCode
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $errorResponse = $_.Exception.Response
        $statusCode = [int]$errorResponse.StatusCode
        $reader = New-Object System.IO.StreamReader($errorResponse.GetResponseStream())
        $rawContent = $reader.ReadToEnd()
        $reader.Dispose()
    }
    $json = $null
    if ($rawContent) { try { $json = $rawContent | ConvertFrom-Json } catch { } }
    [pscustomobject]@{ Status = $statusCode; Json = $json; Raw = $rawContent }
}

function Assert-Status($Response, [int[]]$Expected, [string]$Name) {
    if ($Expected -notcontains $Response.Status) { throw "$Name expected HTTP $($Expected -join '/') but received $($Response.Status): $($Response.Raw)" }
    Write-Host "PASS $Name ($($Response.Status))"
}

function Remove-Fixtures {
    $ids = @($script:candidateId, $script:reviewId, $script:taskId, $script:runId, $script:diagnosisId, $script:sourceId, $script:doc1Id, $script:doc2Id, $script:v1Id, $script:v2Id, $script:v2UserId) | Where-Object { $_ -and $_ -match '^[A-Za-z0-9_-]+$' }
    if ($ids.Count -eq 0 -or [string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_URL)) { return }
    try {
        $dbUri = [Uri](($env:SPRING_DATASOURCE_URL -replace '^jdbc:', ''))
        $database = $dbUri.AbsolutePath.Trim('/')
        if ([string]::IsNullOrWhiteSpace($database)) { return }
        $mysql = if ($env:MYSQL_CLIENT) { $env:MYSQL_CLIENT } else { 'D:\mysql-8.0.36-winx64\mysql-8.0.36-winx64\bin\mysql.exe' }
        if (-not (Test-Path $mysql)) { return }
        $q = @()
        foreach ($id in @($script:candidateId,$script:reviewId)) { if ($id) { $q += "DELETE FROM expert_review_events WHERE review_id='$id';" } }
        if ($script:candidateId) { $q += "DELETE FROM historical_case_candidates WHERE candidate_id='$script:candidateId';" }
        if ($script:reviewId) { $q += "DELETE FROM agent_expert_reviews_v2 WHERE review_id='$script:reviewId';" }
        if ($script:taskId) { $q += "DELETE FROM operations_task_events WHERE task_id='$script:taskId'; DELETE FROM agent_operation_outcomes WHERE task_id='$script:taskId'; DELETE FROM agent_operations_tasks WHERE task_id='$script:taskId';" }
        if ($script:runId) { $q += "DELETE FROM agent_approvals WHERE run_id='$script:runId'; DELETE FROM agent_runs WHERE run_id='$script:runId';" }
        if ($script:diagnosisId) { $q += "DELETE FROM agent_diagnoses WHERE diagnosis_id='$script:diagnosisId';" }
        foreach ($doc in @($script:doc1Id,$script:doc2Id)) { if ($doc) { $q += "DELETE FROM knowledge_events WHERE entity_id='$doc'; DELETE FROM knowledge_versions WHERE document_id='$doc'; DELETE FROM knowledge_documents WHERE document_id='$doc';" } }
        foreach ($version in @($script:v1Id,$script:v2Id,$script:v2UserId)) { if ($version) { $q += "DELETE FROM knowledge_events WHERE entity_id='$version';" } }
        if ($script:sourceId) { $q += "DELETE FROM knowledge_events WHERE entity_id='$script:sourceId'; DELETE FROM knowledge_sources WHERE source_id='$script:sourceId';" }
        $oldPwd = $env:MYSQL_PWD; if (-not $env:MYSQL_PWD -and $env:SPRING_DATASOURCE_PASSWORD) { $env:MYSQL_PWD = $env:SPRING_DATASOURCE_PASSWORD }
        $dbUser = if ($env:SPRING_DATASOURCE_USERNAME) { $env:SPRING_DATASOURCE_USERNAME } else { 'root' }
        $dbPort = if ($dbUri.Port -gt 0) { $dbUri.Port } else { 3306 }
        if ($q.Count -gt 0) {
            $previousErrorAction = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            & $mysql '--protocol=tcp' "--user=$dbUser" "--host=$($dbUri.Host)" "--port=$dbPort" "--database=$database" "--execute=$($q -join ' ')" 2>&1 | Out-Null
            $cleanupExitCode = $LASTEXITCODE
            $ErrorActionPreference = $previousErrorAction
            if ($cleanupExitCode -ne 0) { Write-Warning "Fixture cleanup mysql command failed (exit $cleanupExitCode)." }
        }
        if ($null -eq $oldPwd) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue } else { $env:MYSQL_PWD = $oldPwd }
    } catch { Write-Warning "Fixture cleanup skipped ($($_.Exception.GetType().Name))." }
}

trap { $err = $_; Remove-Fixtures; throw $err }

if ([string]::IsNullOrWhiteSpace($UserToken) -or [string]::IsNullOrWhiteSpace($SecondUserToken)) {
    throw 'Set FARMAP_USER_TOKEN and FARMAP_USER_TOKEN_B to authenticated tokens for two different users.'
}
$a = @{ authentication = $UserToken }
$b = @{ authentication = $SecondUserToken }
$token = [guid]::NewGuid().ToString()

Assert-Status (Invoke-Api GET '/api/knowledge/health') @(401) 'unauthenticated knowledge health'

$sourceA = (Invoke-Api POST '/api/knowledge/sources' $a @{ name = "final-http-$token" }).Json.data
$script:sourceId = [string]$sourceA.sourceId
if ([string]::IsNullOrWhiteSpace($sourceId)) { throw 'Source creation did not return sourceId.' }

Assert-Status (Invoke-Api GET "/api/knowledge/sources/$sourceId" $b) @(403,404) 'cross-user source read'
Assert-Status (Invoke-Api POST "/api/knowledge/sources/$sourceId/documents" $b @{ title = 'forbidden' }) @(403,404) 'cross-user document create'
Assert-Status (Invoke-Api POST "/api/knowledge/sources/$sourceId/disable" $b) @(403,404) 'cross-user source mutation'

$doc1 = (Invoke-Api POST "/api/knowledge/sources/$sourceId/documents" $a @{ title = "final-http-document-$token" }).Json.data
$script:doc1Id = [string]$doc1.documentId
$doc2 = (Invoke-Api POST "/api/knowledge/sources/$sourceId/documents" $a @{ title = "final-http-document-2-$token" }).Json.data
$script:doc2Id = [string]$doc2.documentId
$v1 = (Invoke-Api POST "/api/knowledge/documents/$doc1Id/versions" $a @{ content = "final-http-v1-${token}: check drainage and leaf yellowing." }).Json.data
$v2 = (Invoke-Api POST "/api/knowledge/documents/$doc2Id/versions" $a @{ content = "final-http-v2-${token}: unrelated document." }).Json.data
$script:v1Id = [string]$v1.versionId; $script:v2Id = [string]$v2.versionId
Assert-Status (Invoke-Api POST "/api/knowledge/documents/$doc1Id/versions/$v2Id/activate" $a) @(409,422) 'cross-document version activation'
Assert-Status (Invoke-Api POST "/api/knowledge/documents/$doc1Id/versions/$v1Id/activate" $a) @(200) 'activate knowledge V1'

$diagnosisBody = @{ fieldId = "final-http-field-$token"; crop = 'maize'; query = "final-http-v1-$token"; imageUrls = @('fixture://final-http-image'); weather = @{ rainfall14d = 58; summary = 'test'; observedAt = (Get-Date).ToUniversalTime().ToString('o') }; enabledModalities = @('camera','weather','knowledge') }
$dxResponse = Invoke-Api POST '/diagnosis/analyze' $a $diagnosisBody
Assert-Status $dxResponse @(200) 'diagnosis with Knowledge V1'
$dx = $dxResponse.Json.data; $script:diagnosisId = [string]$dx.diagnosisId
$e1 = @($dx.evidence | Where-Object { $_.modality -eq 'knowledge' -and $_.source.documentId -eq $doc1Id }) | Select-Object -First 1
if ($null -eq $e1 -or [string]$e1.source.versionId -ne $v1Id) { throw "Diagnosis did not retain Knowledge V1 provenance: $($dxResponse.Raw)" }

$v2User = (Invoke-Api POST "/api/knowledge/documents/$doc1Id/versions" $a @{ content = "final-http-v1-$token updated: add water-level check." }).Json.data
$script:v2UserId = [string]$v2User.versionId
Assert-Status (Invoke-Api POST "/api/knowledge/documents/$doc1Id/versions/$v2UserId/activate" $a) @(200) 'activate Knowledge V2'
$reloaded = (Invoke-Api GET "/api/diagnoses/$diagnosisId" $a).Json.data
$old = @($reloaded.evidence | Where-Object { $_.id -eq $e1.id }) | Select-Object -First 1
if ($null -eq $old -or [string]$old.source.versionId -ne $v1Id) { throw "Diagnosis snapshot changed after Knowledge activation: $($reloaded | ConvertTo-Json -Depth 20)" }
Write-Host 'PASS Knowledge V1->V2 diagnosis snapshot gate'

$recommendationId = [string](@($dx.recommendations) | Select-Object -First 1).id
$run = (Invoke-Api POST '/api/agent/runs' $a @{ goal = 'Final HTTP product loop'; mode = 'analysis'; context = @{ fieldId = $diagnosisBody.fieldId; crop = 'maize'; diagnosisId = $diagnosisId; sourceRecommendationId = $recommendationId } }).Json
$script:runId = [string]$run.runId
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 300
    $run = (Invoke-Api GET "/api/agent/runs/$runId" $a).Json
    if ($run.status -eq 'WAITING_FOR_APPROVAL') { break }
}
if ($run.status -ne 'WAITING_FOR_APPROVAL') { throw "Agent did not reach approval gate: $($run | ConvertTo-Json -Depth 20)" }
$script:actionId = [string]$run.actions[0].id
$approved = (Invoke-Api POST "/api/agent/runs/$runId/actions/$actionId/approve" $a).Json
$script:taskId = [string]$approved.output.task.taskId
if ([string]::IsNullOrWhiteSpace($taskId)) { $taskId = [string]((Invoke-Api GET '/api/operations/tasks' $a).Json.data.items | Where-Object { $_.actionId -eq $actionId } | Select-Object -First 1).taskId }
if ([string]::IsNullOrWhiteSpace($taskId)) { throw 'Approved Agent action did not produce taskId.' }
Assert-Status (Invoke-Api POST "/api/operations/tasks/$taskId/assign" $a @{ assignee = 'final-http-worker' }) @(200) 'assign task'
Assert-Status (Invoke-Api POST "/api/operations/tasks/$taskId/start" $a) @(200) 'start task'
$completion = (Invoke-Api POST "/api/operations/tasks/$taskId/complete" $a @{ summary = 'final HTTP execution'; executionEvidence = 'fixture://evidence' })
Assert-Status $completion @(200) 'complete task'
$script:outcomeId = [string]$completion.Json.data.outcome.outcomeId
$review = (Invoke-Api POST '/api/expert-reviews' $a @{ diagnosisId = $diagnosisId; taskId = $taskId; outcomeId = $outcomeId; recommendationId = $recommendationId; diagnosisJudgment = 'CORRECT'; recommendationJudgment = 'APPROPRIATE'; outcomeJudgment = 'SUCCESSFUL'; expertConfidence = 0.9; notes = 'final http' }).Json.data
$script:reviewId = [string]$review.reviewId
$candidate = (Invoke-Api POST "/api/expert-reviews/$reviewId/candidate" $a).Json.data
$script:candidateId = [string]$candidate.candidateId
if ([string]::IsNullOrWhiteSpace($candidateId)) { throw 'Candidate creation did not return candidateId.' }
Assert-Status (Invoke-Api POST "/api/expert-reviews/$reviewId/candidate" $a) @(200) 'duplicate candidate idempotency'
Assert-Status (Invoke-Api POST "/api/agent/runs/$runId/actions/$actionId/approve" $a) @(200) 'duplicate approve idempotency'
Assert-Status (Invoke-Api POST "/api/operations/tasks/$taskId/complete" $a @{ summary = 'retry'; executionEvidence = 'retry' }) @(200) 'duplicate completion idempotency'

Assert-Status (Invoke-Api GET "/api/operations/tasks/$taskId" $b) @(403,404) 'cross-user task read'
Assert-Status (Invoke-Api GET "/api/expert-reviews/$reviewId" $b) @(403,404) 'cross-user review read'
Assert-Status (Invoke-Api GET "/api/historical-case-candidates/$($candidate.candidateId)" $b) @(403,404) 'cross-user candidate read'

if ($env:FARMAP_UNAVAILABLE_BASE_URL) {
    $oldBase = $BaseUrl; $BaseUrl = $env:FARMAP_UNAVAILABLE_BASE_URL.TrimEnd('/')
    Assert-Status (Invoke-Api GET '/api/knowledge/health' $a) @(503) 'database unavailable knowledge health'
    $BaseUrl = $oldBase
} else {
    Write-Host 'SKIP database unavailable HTTP scenario (set FARMAP_UNAVAILABLE_BASE_URL to an isolated instance with an unavailable datasource).'
}

Write-Host "PASS Milestone 5.2 Final HTTP smoke; diagnosis=$diagnosisId task=$taskId outcome=$outcomeId review=$reviewId candidate=$($candidate.candidateId)"
Remove-Fixtures
