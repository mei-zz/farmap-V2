param(
    [string]$Profile = "",
    [string]$JavaTempDir = "D:\farmap-runtime-temp",
    [ValidateSet("qwen", "template")]
    [string]$GenerationMode = "qwen"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"
$fallbackEnvFile = Join-Path $repoRoot "deployment\.env"
$backendRoot = Join-Path $repoRoot "zhgy\zhgy"
$bundledMaven = Join-Path $repoRoot ".tools\apache-maven-3.9.16\bin\mvn.cmd"
$mavenCommand = if (Test-Path -LiteralPath $bundledMaven -PathType Leaf) { $bundledMaven } else { "mvn" }

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Missing local environment file: $envFile"
}

foreach ($candidateEnvFile in @($envFile, $fallbackEnvFile)) {
    if (-not (Test-Path -LiteralPath $candidateEnvFile -PathType Leaf)) { continue }
    foreach ($line in Get-Content -LiteralPath $candidateEnvFile) {
        if ($line -match '^\s*#' -or $line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            continue
        }
        $name = $matches[1]
        $value = $matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        # Root .env wins; deployment/.env supplies local provider settings that
        # are intentionally kept out of the repository root env file.
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, "Process"))) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

# The current local .env uses APIKEY/BASEURL. Map aliases in-process only.
# Prefer the explicitly loaded root .env values over stale aliases inherited
# from an older Hunyuan/Bailian session; otherwise Java may silently call the
# right Qwen endpoint with a different account key.
if ($env:APIKEY) {
    $env:BAILIAN_API_KEY = $env:APIKEY
}
if ($env:BASEURL) {
    $env:BAILIAN_API_BASE_URL = $env:BASEURL
}
$env:RAG_GENERATION_MODE = $GenerationMode
New-Item -ItemType Directory -Force -Path $JavaTempDir | Out-Null
$env:TEMP = $JavaTempDir
$env:TMP = $JavaTempDir
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$JavaTempDir"
if (-not $env:LOCAL_AI_BASE_URL) { $env:LOCAL_AI_BASE_URL = "http://127.0.0.1:8001" }
# The Docker default is valid only inside the compose network. These legacy
# adapter endpoints are retained for compatibility with old APIs; production
# Copilot/Diagnosis requests use the Qwen Model Studio gateway instead.
if (-not $env:MODEL_API_URL) { $env:MODEL_API_URL = "http://127.0.0.1:5000/analyze" }
if (-not $env:MODEL_API_V1_URL) { $env:MODEL_API_V1_URL = "http://127.0.0.1:5000/v1/vision/analyze" }
# Commercial MVP local runtime uses the verified Java/Milvus IPv6 loopback and
# the production Qwen/GTE knowledge path. Keep both values environment
# overridable so Docker/other deployments can provide their own endpoints.
if (-not $env:MILVUS_HOST) { $env:MILVUS_HOST = "::1" }
if (-not $env:MILVUS_PORT) { $env:MILVUS_PORT = "19530" }
if (-not $env:SPRING_DATA_MONGODB_HOST) { $env:SPRING_DATA_MONGODB_HOST = "127.0.0.1" }
if (-not $env:SPRING_DATASOURCE_URL) { $env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" }
if (-not $env:ZHGY_UPLOAD_DIR) { $env:ZHGY_UPLOAD_DIR = (Join-Path $repoRoot ".runtime\uploads") }
if (-not $env:ZHGY_UPLOAD_BASE_URL) { $env:ZHGY_UPLOAD_BASE_URL = "http://127.0.0.1:8080/uploads" }
New-Item -ItemType Directory -Force -Path $env:ZHGY_UPLOAD_DIR | Out-Null

# Resolve the existing local MySQL credential file in-process. Never echo the
# values: this keeps the reproducible local launcher compatible with the
# authoritative database used by the current handoff without committing a
# password or requiring users to copy secrets into the repository.
$credentialFile = Join-Path $repoRoot "mysql.txt"
if (Test-Path -LiteralPath $credentialFile -PathType Leaf) {
    $localMysqlUser = $null
    $localMysqlPassword = $null
    foreach ($line in Get-Content -LiteralPath $credentialFile) {
        if ($line -match '^\s*user\s*=\s*(.*)$') { $localMysqlUser = $matches[1].Trim() }
        if ($line -match '^\s*psw\s*=\s*(.*)$') { $localMysqlPassword = $matches[1].Trim() }
    }
    if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_USERNAME) -and -not [string]::IsNullOrWhiteSpace($localMysqlUser)) {
        $env:SPRING_DATASOURCE_USERNAME = $localMysqlUser
    }
    if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_PASSWORD) -and $null -ne $localMysqlPassword) {
        $env:SPRING_DATASOURCE_PASSWORD = $localMysqlPassword
    }
}
if (-not $env:FARMAP_CASE_INGESTION_ENABLED) { $env:FARMAP_CASE_INGESTION_ENABLED = "false" }
if (-not $env:FARMAP_KNOWLEDGE_EMBEDDING_PROVIDER) { $env:FARMAP_KNOWLEDGE_EMBEDDING_PROVIDER = "BAILIAN" }
if (-not $env:FARMAP_AGENT_PERSISTENCE) { $env:FARMAP_AGENT_PERSISTENCE = "mysql" }
# Keep the local defense/demo runtime complete for farms whose legacy source
# contains the real boundary and camera topology but no crop-observation rows.
# Production deployments can explicitly set this flag to false.
if (-not $env:FARMAP_PRESENTATION_DATA_ENABLED) { $env:FARMAP_PRESENTATION_DATA_ENABLED = "true" }

Push-Location $backendRoot
try {
    if ([string]::IsNullOrWhiteSpace($Profile)) {
        & $mavenCommand spring-boot:run
    } else {
        & $mavenCommand spring-boot:run "-Dspring-boot.run.profiles=$Profile"
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
