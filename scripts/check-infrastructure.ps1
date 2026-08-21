[CmdletBinding()]
param(
    [string]$EnvFilePath = $(Join-Path (Get-Location) '.env'),
    [string]$MySqlHost,
    [ValidateRange(1, 65535)]
    [int]$MySqlPort = 3306,
    [string]$MilvusHost,
    [ValidateRange(1, 65535)]
    [int]$MilvusPort = 19530,
    [string]$EmbeddingBaseUrl,
    [switch]$SkipEmbedding,
    [switch]$CheckApplication,
    [string]$AppBaseUrl = $(if ($env:NOVEL_AGENT_BASE_URL) { $env:NOVEL_AGENT_BASE_URL } else { 'http://localhost:8080' }),
    [ValidateRange(200, 10000)]
    [int]$TimeoutMs = 1500,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-DotEnv {
    param([string]$Path)

    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $values
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        if ($line -match '^\s*(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.*)\s*$') {
            $value = $matches.value.Trim()
            if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$matches.name] = $value
        }
    }

    return $values
}

function Resolve-ConfigValue {
    param(
        [string]$ExplicitValue,
        [string]$Name,
        [string]$Fallback,
        [hashtable]$DotEnvValues
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitValue)) {
        return $ExplicitValue.Trim()
    }
    if ($DotEnvValues.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace([string]$DotEnvValues[$Name])) {
        return ([string]$DotEnvValues[$Name]).Trim()
    }
    $processValue = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        return $processValue.Trim()
    }
    return $Fallback
}

function Test-TcpPort {
    param(
        [string]$TargetHost,
        [int]$Port,
        [int]$ConnectTimeoutMs
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $client = $null
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connectTask = $client.ConnectAsync($TargetHost, $Port)
        if (-not $connectTask.Wait($ConnectTimeoutMs)) {
            return [pscustomobject]@{
                Reachable = $false
                DurationMs = $stopwatch.ElapsedMilliseconds
                Error = "connection timed out after ${ConnectTimeoutMs}ms"
            }
        }
        if (-not $client.Connected) {
            return [pscustomobject]@{
                Reachable = $false
                DurationMs = $stopwatch.ElapsedMilliseconds
                Error = 'TCP connection was not established'
            }
        }
        return [pscustomobject]@{
            Reachable = $true
            DurationMs = $stopwatch.ElapsedMilliseconds
            Error = $null
        }
    } catch {
        return [pscustomobject]@{
            Reachable = $false
            DurationMs = $stopwatch.ElapsedMilliseconds
            Error = $_.Exception.Message
        }
    } finally {
        $stopwatch.Stop()
        if ($null -ne $client) {
            $client.Dispose()
        }
    }
}

# Embedding endpoint verifies network reachability only; 401/404/405 does not imply model authorization.
function Test-HttpEndpoint {
    param(
        [string]$Uri,
        [int]$RequestTimeoutMs
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $timeoutSeconds = [math]::Max(1, [math]::Ceiling($RequestTimeoutMs / 1000.0))
    try {
        $response = Invoke-WebRequest -Method Head -Uri $Uri -UseBasicParsing -TimeoutSec $timeoutSeconds
        return [pscustomobject]@{
            Reachable = $true
            StatusCode = [int]$response.StatusCode
            DurationMs = $stopwatch.ElapsedMilliseconds
            Error = $null
        }
    } catch {
        $statusCode = $null
        if ($null -ne $_.Exception.Response) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            } catch {
                $statusCode = $null
            }
        }
        if ($null -ne $statusCode) {
            return [pscustomobject]@{
                Reachable = $true
                StatusCode = $statusCode
                DurationMs = $stopwatch.ElapsedMilliseconds
                Error = "HTTP $statusCode"
            }
        }
        return [pscustomobject]@{
            Reachable = $false
            StatusCode = $null
            DurationMs = $stopwatch.ElapsedMilliseconds
            Error = $_.Exception.Message
        }
    } finally {
        $stopwatch.Stop()
    }
}

function Test-ApplicationHealth {
    param(
        [string]$Uri,
        [int]$RequestTimeoutMs
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $timeoutSeconds = [math]::Max(1, [math]::Ceiling($RequestTimeoutMs / 1000.0))
    try {
        $response = Invoke-RestMethod -Method Get -Uri $Uri -UseBasicParsing -TimeoutSec $timeoutSeconds
        $status = if ($null -ne $response -and $response.PSObject.Properties.Name -contains 'status') {
            ([string]$response.status).Trim()
        } else {
            ''
        }
        $healthy = $status -eq 'UP'
        return [pscustomobject]@{
            Healthy = $healthy
            StatusCode = 200
            DurationMs = $stopwatch.ElapsedMilliseconds
            Error = if ($healthy) { $null } else { "application health status was '$status'" }
        }
    } catch {
        $statusCode = $null
        if ($null -ne $_.Exception.Response) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            } catch {
                $statusCode = $null
            }
        }
        return [pscustomobject]@{
            Healthy = $false
            StatusCode = $statusCode
            DurationMs = $stopwatch.ElapsedMilliseconds
            Error = if ($null -ne $statusCode) { "HTTP $statusCode" } else { $_.Exception.Message }
        }
    } finally {
        $stopwatch.Stop()
    }
}

function Add-SkippedCheck {
    param(
        [string]$Name,
        [string]$Component,
        [string]$Reason
    )

    $script:results += [ordered]@{
        name = $Name
        component = $Component
        required = $false
        status = 'SKIP'
        target = $null
        latencyMs = $null
        detail = $Reason
    }
}

function Add-TcpCheck {
    param(
        [string]$Name,
        [string]$Component,
        [string]$TargetHost,
        [int]$Port,
        [bool]$Required
    )

    $probe = Test-TcpPort -TargetHost $TargetHost -Port $Port -ConnectTimeoutMs $TimeoutMs
    $script:results += [ordered]@{
        name = $Name
        component = $Component
        required = $Required
        status = if ($probe.Reachable) { 'PASS' } else { 'FAIL' }
        target = "${TargetHost}:${Port}"
        latencyMs = $probe.DurationMs
        detail = if ($probe.Reachable) { 'TCP connection established' } else { $probe.Error }
    }
}

function Add-HttpCheck {
    param(
        [string]$Name,
        [string]$Component,
        [string]$Uri,
        [bool]$Required
    )

    $probe = Test-HttpEndpoint -Uri $Uri -RequestTimeoutMs $TimeoutMs
    $script:results += [ordered]@{
        name = $Name
        component = $Component
        required = $Required
        status = if ($probe.Reachable) { 'PASS' } else { 'FAIL' }
        target = $Uri
        latencyMs = $probe.DurationMs
        detail = if ($probe.Reachable) { "HTTP endpoint reachable (status $($probe.StatusCode))" } else { $probe.Error }
    }
}

function Add-ApplicationHealthCheck {
    param([string]$Uri)

    $probe = Test-ApplicationHealth -Uri $Uri -RequestTimeoutMs $TimeoutMs
    $script:results += [ordered]@{
        name = 'application-health'
        component = 'application'
        required = $true
        status = if ($probe.Healthy) { 'PASS' } else { 'FAIL' }
        target = $Uri
        latencyMs = $probe.DurationMs
        detail = if ($probe.Healthy) { 'application health status is UP' } else { $probe.Error }
    }
}

$dotEnvValues = Read-DotEnv -Path $EnvFilePath
$MySqlHost = Resolve-ConfigValue -ExplicitValue $MySqlHost -Name 'MYSQL_HOST' -Fallback 'localhost' -DotEnvValues $dotEnvValues
$MilvusHost = Resolve-ConfigValue -ExplicitValue $MilvusHost -Name 'MILVUS_HOST' -Fallback '127.0.0.1' -DotEnvValues $dotEnvValues
$EmbeddingBaseUrl = Resolve-ConfigValue -ExplicitValue $EmbeddingBaseUrl -Name 'EMBEDDING_BASE_URL' -Fallback 'https://api.siliconflow.cn/v1' -DotEnvValues $dotEnvValues

$script:results = @()
Add-TcpCheck -Name 'mysql' -Component 'required' -TargetHost $MySqlHost -Port $MySqlPort -Required $true
Add-TcpCheck -Name 'milvus' -Component 'required' -TargetHost $MilvusHost -Port $MilvusPort -Required $true

if ($SkipEmbedding) {
    Add-SkippedCheck -Name 'embedding-provider' -Component 'required-for-rag/import' -Reason 'skipped by -SkipEmbedding'
} else {
    Add-HttpCheck -Name 'embedding-provider' -Component 'required-for-rag/import' -Uri $EmbeddingBaseUrl -Required $true
}

if ($CheckApplication) {
    $healthUri = '{0}/api/v1/novel/health' -f $AppBaseUrl.TrimEnd('/')
    Add-ApplicationHealthCheck -Uri $healthUri
} else {
    Add-SkippedCheck -Name 'application-health' -Component 'application' -Reason 'skipped; add -CheckApplication after the app starts'
}

$requiredFailures = @($script:results | Where-Object { $_.required -and $_.status -eq 'FAIL' }).Count
$optionalFailures = @($script:results | Where-Object { (-not $_.required) -and $_.status -eq 'FAIL' }).Count
$overallStatus = if ($requiredFailures -gt 0) { 'FAIL' } elseif ($optionalFailures -gt 0) { 'PASS_WITH_WARNINGS' } else { 'PASS' }

foreach ($result in $script:results) {
    $suffix = if ($null -ne $result.target) { " [$($result.target)]" } else { '' }
    $detail = if ($null -ne $result.detail) { ": $($result.detail)" } else { '' }
    Write-Host ("[{0}] {1}{2}{3}" -f $result.status, $result.name, $suffix, $detail)
}
Write-Host ("Overall: {0} (required failures: {1}, optional failures: {2})" -f $overallStatus, $requiredFailures, $optionalFailures)

if ($OutputPath) {
    $report = [ordered]@{
        checkedAt = [DateTimeOffset]::Now.ToString('o')
        envFile = [System.IO.Path]::GetFileName($EnvFilePath)
        timeoutMs = $TimeoutMs
        overallStatus = $overallStatus
        requiredFailures = $requiredFailures
        optionalFailures = $optionalFailures
        checks = $script:results
    }
    $fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $fullOutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($fullOutputPath, ($report | ConvertTo-Json -Depth 10), $utf8NoBom)
    Write-Host ("Report written to {0}" -f $fullOutputPath)
}

if ($requiredFailures -gt 0) {
    exit 1
}
