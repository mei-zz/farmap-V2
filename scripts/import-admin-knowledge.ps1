[CmdletBinding(DefaultParameterSetName='File')]
param(
  [Parameter(Mandatory=$true, ParameterSetName='File')][string]$File,
  [Parameter(Mandatory=$true, ParameterSetName='File')][string]$SourceName,
  [Parameter(Mandatory=$true, ParameterSetName='Directory')][string]$Directory,
  [switch]$Recurse,
  [string]$Title,
  [string]$BaseUrl = $(if ($env:FARMAP_BASE_URL) { $env:FARMAP_BASE_URL } else { 'http://127.0.0.1:8080' }),
  [string]$Token = $env:FARMAP_ADMIN_TOKEN,
  [string]$Manifest = 'docs/admin-knowledge-corpus-import-manifest.json',
  [switch]$DryRun
)

$supported = @('.pdf','.txt','.md','.markdown','.docx')
$base = $BaseUrl.TrimEnd('/')

function Require-Token {
  if ([string]::IsNullOrWhiteSpace($Token) -and -not $DryRun) { throw 'Set FARMAP_ADMIN_TOKEN to an authenticated administrator JWT.' }
}

function Add-ManifestEntry([System.Collections.Generic.List[object]]$entries, [string]$relativePath, [string]$classification, [string]$hash, [string]$status, $payload, [string]$errorCode) {
  $entry = [ordered]@{
    relativePath = $relativePath
    classification = $classification
    fileHash = $hash
    sourceId = if ($payload) { $payload.sourceId } else { $null }
    documentId = if ($payload) { $payload.documentId } else { $null }
    versionId = if ($payload) { $payload.versionId } else { $null }
    ingestionId = if ($payload) { $payload.ingestionId } else { $null }
    ingestionStatus = $status
    chunkCount = if ($payload) { $payload.chunkCount } else { 0 }
    embeddingModel = if ($payload) { $payload.embeddingModel } else { $null }
    embeddingDimension = if ($payload) { $payload.embeddingDimension } else { $null }
    collection = if ($payload) { $payload.indexCollection } else { $null }
    errorCode = $errorCode
  }
  $entries.Add([pscustomobject]$entry)
}

function Send-MultipartJson([string]$uri, [string]$token, [hashtable]$fields, [System.IO.FileInfo]$item) {
  $client = [System.Net.Http.HttpClient]::new()
  $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
  $content = [System.Net.Http.MultipartFormDataContent]::new()
  $stream = $null
  try {
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    foreach ($key in $fields.Keys) {
      if ($null -ne $fields[$key]) {
        $content.Add([System.Net.Http.StringContent]::new([string]$fields[$key]), $key)
      }
    }
    # Open the authoritative corpus file read-only. This avoids PowerShell's
    # default -Form path handling, which can reject ReadOnly source attributes.
    $stream = [System.IO.File]::Open($item.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $fileContent = [System.Net.Http.StreamContent]::new($stream)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/octet-stream')
    $content.Add($fileContent, 'file', $item.Name)
    $request.Content = $content
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    [pscustomobject]@{ http = [int]$response.StatusCode; body = $body }
  } finally {
    if ($stream) { $stream.Dispose() }
    $request.Dispose()
    $client.Dispose()
  }
}

function Parse-ApiBody([string]$body) {
  if ([string]::IsNullOrWhiteSpace($body)) { return $null }
  try { return $body | ConvertFrom-Json } catch { return $null }
}

function Import-One([System.IO.FileInfo]$item, [string]$root, [bool]$corpusMode, [System.Collections.Generic.List[object]]$entries, [int]$position, [int]$total) {
  $extension = $item.Extension.ToLowerInvariant()
  $relative = if ($corpusMode) { [IO.Path]::GetRelativePath($root, $item.FullName).Replace('\','/') } else { $item.Name }
  $hash = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($supported -notcontains $extension) {
    Write-Host "[$position/$total] UNSUPPORTED $relative"
    Add-ManifestEntry $entries $relative 'ADMIN_CURATED' $hash 'UNSUPPORTED' $null 'UNSUPPORTED_FORMAT'
    return
  }
  $limit = if ($corpusMode) { 64MB } else { 10MB }
  if ($item.Length -gt $limit) {
    Write-Host "[$position/$total] FAILED $relative (FILE_TOO_LARGE)"
    Add-ManifestEntry $entries $relative 'ADMIN_CURATED' $hash 'FAILED' $null 'FILE_TOO_LARGE'
    return
  }
  if ($extension -eq '.docx') {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try {
      $zip = [System.IO.Compression.ZipFile]::OpenRead($item.FullName)
      $zip.Dispose()
    } catch {
      Write-Host "[$position/$total] UNSUPPORTED $relative"
      Add-ManifestEntry $entries $relative 'ADMIN_CURATED' $hash 'UNSUPPORTED' $null 'CORRUPT_DOCX'
      return
    }
  }
  if ($DryRun) {
    Write-Host "[$position/$total] DRY_RUN $relative"
    Add-ManifestEntry $entries $relative 'ADMIN_CURATED' $hash 'DRY_RUN' $null $null
    return
  }
  $category = if ($corpusMode) {
    $slash = $relative.IndexOf('/')
    if ($slash -gt 0) { $relative.Substring(0, $slash) } else { 'FarMap 管理员农业知识库' }
  } else { $SourceName }
  $source = if ($corpusMode) { "FarMap 管理员农业知识库 / $category" } else { $SourceName }
  $form = @{ sourceName = $source }
  if ($corpusMode) {
    $form.relativePath = $relative
    $form.title = [IO.Path]::GetFileNameWithoutExtension($item.Name)
    $uri = "$base/api/admin/knowledge/import-corpus"
  } else {
    if (-not [string]::IsNullOrWhiteSpace($Title)) { $form.title = $Title }
    $uri = "$base/api/admin/knowledge/import"
  }
  try {
    $wire = Send-MultipartJson $uri $Token $form $item
    $response = Parse-ApiBody $wire.body
    if ($wire.http -lt 200 -or $wire.http -ge 300) {
      $errorCode = if ($response -and $response.code) { [string]$response.code } else { "HTTP_$($wire.http)" }
      $unsupported = $errorCode -eq 'KNOWLEDGE_TEXT_EXTRACTION_EMPTY'
      $displayStatus = if ($unsupported) { 'UNSUPPORTED' } else { 'FAILED' }
      Write-Host "[$position/$total] $displayStatus $relative ($errorCode)"
      Add-ManifestEntry $entries $relative 'ADMIN_CURATED' $hash $displayStatus $null $errorCode
      return
    }
    $payload = if ($response) { $response.data } else { $null }
    $status = if ($payload.ingestionOutcome -eq 'SKIPPED_IDEMPOTENT') { 'SKIPPED_IDEMPOTENT' } else { [string]$payload.status }
    Write-Host "[$position/$total] $status $relative"
    Add-ManifestEntry $entries $relative 'ADMIN_CURATED' $hash $status $payload $payload.failureCode
  } catch {
    Write-Host "[$position/$total] FAILED $relative (CLIENT_ERROR)"
    Add-ManifestEntry $entries $relative 'ADMIN_CURATED' $hash 'FAILED' $null 'CLIENT_ERROR'
  }
}

Require-Token
$entries = [System.Collections.Generic.List[object]]::new()
if ($PSCmdlet.ParameterSetName -eq 'File') {
  $resolved = (Resolve-Path -LiteralPath $File -ErrorAction Stop).Path
  $item = Get-Item -LiteralPath $resolved -ErrorAction Stop
  if (-not $item.PSIsContainer) { Import-One $item $item.DirectoryName $false $entries 1 1 }
} else {
  $root = (Resolve-Path -LiteralPath $Directory -ErrorAction Stop).Path
  $items = if ($Recurse) { @(Get-ChildItem -LiteralPath $root -File -Recurse -Force) } else { @(Get-ChildItem -LiteralPath $root -File -Force) }
  $ordered = @($items | Sort-Object FullName)
  for ($i = 0; $i -lt $ordered.Count; $i++) { Import-One $ordered[$i] $root $true $entries ($i + 1) $ordered.Count }
}

$manifestPath = [IO.Path]::GetFullPath($Manifest)
$parent = Split-Path -Parent $manifestPath
if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
$entries | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Host "Manifest: $manifestPath"
