param(
    [string]$BaseUrl = $(if ($env:NOVEL_AGENT_BASE_URL) { $env:NOVEL_AGENT_BASE_URL } else { 'http://localhost:8080' })
)

$BaseUrl = $BaseUrl.TrimEnd('/')
$checks = @(
    @{ Name = 'health'; Path = '/api/v1/novel/health' },
    @{ Name = 'cost-summary'; Path = '/api/admin/cost/summary' }
)

$failed = $false
foreach ($check in $checks) {
    try {
        $response = Invoke-RestMethod -Method Get -Uri ($BaseUrl + $check.Path)
        Write-Host ("[PASS] {0}: {1}" -f $check.Name, ($response | ConvertTo-Json -Compress))
    } catch {
        $failed = $true
        Write-Error ("[FAIL] {0}: {1}" -f $check.Name, $_.Exception.Message)
    }
}

if ($failed) {
    exit 1
}
