param(
    [string]$OutputPath = 'artifacts/cost-governance-benchmark.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
$parent = Split-Path -Parent $fullOutputPath
if ($parent -and -not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}

$mavenArgs = @(
    "-Dtest=CostGovernanceBenchmarkTest",
    "-Dcost.benchmark.output=$fullOutputPath",
    'test',
    '-DskipITs',
    '-B'
)

try {
    & mvn @mavenArgs
    $exitCode = $LASTEXITCODE
} catch {
    Write-Error ("Cost governance benchmark failed to start: {0}" -f $_.Exception.Message)
    exit 1
}

if ($exitCode -ne 0) {
    Write-Error ("Cost governance benchmark failed with Maven exit code {0}." -f $exitCode)
    exit $exitCode
}

if (-not (Test-Path -LiteralPath $fullOutputPath -PathType Leaf)) {
    Write-Error ("Benchmark completed without producing {0}." -f $fullOutputPath)
    exit 1
}

Write-Host (Get-Content -LiteralPath $fullOutputPath -Raw)
Write-Host ("Benchmark report written to {0}" -f $fullOutputPath)
