param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$FilePath,
    [string]$BaseUrl = $(if ($env:NOVEL_AGENT_BASE_URL) { $env:NOVEL_AGENT_BASE_URL } else { 'http://localhost:8080' }),
    [long]$NovelId = 0,
    [ValidateRange(1, 60)]
    [int]$PollIntervalSeconds = 2,
    [ValidateRange(10, 86400)]
    [int]$TimeoutSeconds = 3600,
    [string]$OutputPath,
    [switch]$Cleanup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$resolvedFilePath = (Resolve-Path -LiteralPath $FilePath).Path
$adminHeaders = @{}
if (-not [string]::IsNullOrWhiteSpace($env:NOVEL_AGENT_ADMIN_API_KEY)) {
    $adminHeaders['X-Admin-Api-Key'] = $env:NOVEL_AGENT_ADMIN_API_KEY
}

# 0 is the historical shared corpus. A benchmark gets an isolated id by default.
if ($NovelId -eq 0) {
    $NovelId = 900000000 + ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() % 100000000)
    Write-Warning ("NovelId was not supplied; using isolated benchmark id {0}." -f $NovelId)
}

$encodedFilePath = [uri]::EscapeDataString($resolvedFilePath)
$startUri = '{0}/api/import/training-data/{1}?filePath={2}' -f $BaseUrl, $NovelId, $encodedFilePath
$statusUri = '{0}/api/import/status' -f $BaseUrl
$startedAt = Get-Date

try {
    $startResponse = Invoke-RestMethod -Method Post -Uri $startUri -Headers $adminHeaders
} catch {
    Write-Error ("Import benchmark could not start: {0}" -f $_.Exception.Message)
    exit 1
}

if (-not $startResponse.success) {
    Write-Error ("Import benchmark was rejected: {0}" -f $startResponse.message)
    exit 1
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$status = $null
do {
    Start-Sleep -Seconds $PollIntervalSeconds
    try {
        $status = Invoke-RestMethod -Method Get -Uri $statusUri -Headers $adminHeaders
    } catch {
        Write-Error ("Import status request failed: {0}" -f $_.Exception.Message)
        exit 1
    }

    if ((Get-Date) -gt $deadline) {
        Write-Error ("Import benchmark timed out after {0} seconds." -f $TimeoutSeconds)
        exit 1
    }
} while ($status.running)

$finishedAt = Get-Date
$wallClockDurationMs = [math]::Max(1, [long](($finishedAt - $startedAt).TotalMilliseconds))
$summary = [ordered]@{
    novelId = $NovelId
    filePath = $resolvedFilePath
    stage = $status.stage
    format = $status.format
    sourceRecordCount = $status.sourceRecordCount
    importedRecordCount = $status.importedRecordCount
    processedRecords = $status.processedRecords
    segmentCount = $status.successCount
    failureCount = $status.failureCount
    durationMs = $status.durationMs
    wallClockDurationMs = $wallClockDurationMs
    recordsPerSecond = $status.recordsPerSecond
    segmentsPerSecond = $status.segmentsPerSecond
    batchCount = $status.batchCount
    flushCount = $status.flushCount
    retryCount = $status.retryCount
    checkpointExists = $status.checkpointExists
    lastError = $status.lastError
    cleanupRequested = [bool]$Cleanup
    cleanupSucceeded = $false
}

if ($Cleanup -and $status.stage -eq 'completed') {
    $cleanupUri = '{0}/api/v1/novel/admin/milvus/novel/{1}' -f $BaseUrl, $NovelId
    try {
        Invoke-RestMethod -Method Delete -Uri $cleanupUri -Headers $adminHeaders | Out-Null
        $summary.cleanupSucceeded = $true
    } catch {
        $summary.lastError = "cleanup failed: $($_.Exception.Message)"
        Write-Warning $summary.lastError
    }
}

$json = $summary | ConvertTo-Json -Depth 10
Write-Host ($json -replace "`r?`n", ' ')

if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($OutputPath),
        $json,
        $utf8NoBom
    )
    Write-Host ("Benchmark report written to {0}" -f [System.IO.Path]::GetFullPath($OutputPath))
}

if ($status.stage -ne 'completed' -or ($Cleanup -and -not $summary.cleanupSucceeded)) {
    exit 1
}
