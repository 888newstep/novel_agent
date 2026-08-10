param(
    [string]$BaseUrl = $(if ($env:NOVEL_AGENT_BASE_URL) { $env:NOVEL_AGENT_BASE_URL } else { 'http://localhost:8080' }),
    [long]$NovelId = 0,
    [ValidateRange(1, 50)]
    [int]$TopK = 5,
    [ValidateNotNullOrEmpty()]
    [string]$Profile = 'writing-default-v1',
    [string]$OutputPath
)

$BaseUrl = $BaseUrl.TrimEnd('/')
$encodedProfile = [System.Uri]::EscapeDataString($Profile.Trim())
$uri = '{0}/api/v1/novel/evaluate/segments?novelId={1}&topK={2}&profile={3}' -f $BaseUrl, $NovelId, $TopK, $encodedProfile

try {
    $report = Invoke-RestMethod -Method Post -Uri $uri
} catch {
    Write-Error ("RAG evaluation request failed: {0}" -f $_.Exception.Message)
    exit 1
}

$summary = [ordered]@{
    datasetVersion = $report.datasetVersion
    profileName = $report.profileName
    reason = $report.reason
    queryCount = $report.queryCount
    topK = $report.topK
    recallAtK = $report.recallAtK
    precisionAtK = $report.precisionAtK
    mrr = $report.mrr
    keywordCoverage = $report.keywordCoverage
    avgLatencyMs = $report.avgLatencyMs
    p95LatencyMs = $report.p95LatencyMs
    p99LatencyMs = $report.p99LatencyMs
    avgRetrievedContextChars = $report.avgRetrievedContextChars
    avgRetrievedContextTokens = $report.avgRetrievedContextTokens
}

Write-Host ($summary | ConvertTo-Json -Compress)

if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($OutputPath),
        ($report | ConvertTo-Json -Depth 20),
        $utf8NoBom
    )
    Write-Host ("Full report written to {0}" -f [System.IO.Path]::GetFullPath($OutputPath))
}
