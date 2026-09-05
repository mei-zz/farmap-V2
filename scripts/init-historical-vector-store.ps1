param(
    [switch]$Execute,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'zhgy\zhgy'
$maven = Join-Path $projectRoot '.tools\apache-maven-3.9.16\bin\mvn.cmd'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$runtimeTemp = if ($env:FARMAP_RUNTIME_TEMP) { $env:FARMAP_RUNTIME_TEMP } else { Join-Path $projectRoot '.runtime\maven-temp' }
New-Item -ItemType Directory -Force $runtimeTemp | Out-Null
$env:TEMP = $runtimeTemp
$env:TMP = $runtimeTemp
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$runtimeTemp"
$classpathFile = Join-Path $runtimeTemp 'farmap-milvus-classpath.txt'

if (-not (Test-Path $maven)) { throw "Maven not found: $maven" }

Push-Location $backendRoot
try {
    & $maven -q -DskipTests compile
    & $maven -q dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    $classpath = "$(Join-Path $backendRoot 'target\classes');$(Get-Content $classpathFile -Raw)"
    $mode = if ($Execute) { '--execute' } else { '--dry-run' }
    & $java -cp $classpath com.mei.zhgy.service.historical.HistoricalVectorStoreInitializerCli $mode
    if ($LASTEXITCODE -ne 0) { throw "Historical vector store initializer failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
