param(
    [string]$Profile = "",
    [string]$JavaTempDir = "D:\farmap-runtime-temp",
    [ValidateSet("qwen", "template")]
    [string]$GenerationMode = "qwen"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"
$backendRoot = Join-Path $repoRoot "zhgy\zhgy"
$bundledMaven = Join-Path $repoRoot ".tools\apache-maven-3.9.16\bin\mvn.cmd"
$mavenCommand = if (Test-Path -LiteralPath $bundledMaven -PathType Leaf) { $bundledMaven } else { "mvn" }

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Missing local environment file: $envFile"
}

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

# The current local .env uses APIKEY/BASEURL. Map aliases in-process only.
if ($env:APIKEY -and -not $env:BAILIAN_API_KEY -and -not $env:DASHSCOPE_API_KEY) {
    $env:BAILIAN_API_KEY = $env:APIKEY
}
if ($env:BASEURL -and -not $env:BAILIAN_API_BASE_URL) {
    $env:BAILIAN_API_BASE_URL = $env:BASEURL
}
$env:RAG_GENERATION_MODE = $GenerationMode
New-Item -ItemType Directory -Force -Path $JavaTempDir | Out-Null
$env:TEMP = $JavaTempDir
$env:TMP = $JavaTempDir
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$JavaTempDir"
if (-not $env:LOCAL_AI_BASE_URL) { $env:LOCAL_AI_BASE_URL = "http://127.0.0.1:8001" }
if (-not $env:MILVUS_HOST) { $env:MILVUS_HOST = "127.0.0.1" }
if (-not $env:MILVUS_PORT) { $env:MILVUS_PORT = "19530" }
if (-not $env:SPRING_DATA_MONGODB_HOST) { $env:SPRING_DATA_MONGODB_HOST = "127.0.0.1" }
if (-not $env:SPRING_DATASOURCE_URL) { $env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" }
if (-not $env:FARMAP_CASE_INGESTION_ENABLED) { $env:FARMAP_CASE_INGESTION_ENABLED = "false" }

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
