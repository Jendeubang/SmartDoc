param(
  [Parameter(Mandatory = $true)] [string] $JMeterBin,
  [Parameter(Mandatory = $true)] [string] $Token,
  [string] $HostName = 'localhost',
  [int] $Port = 8080,
  [int] $Threads = 10,
  [int] $RampUpSeconds = 30,
  [int] $Loops = 100,
  [int] $ThinkTimeMs = 800,
  [string] $DataFile = '',
  [string] $Path = '/api/ai/rag/search/hybrid/rerank',
  [string] $Strategy = 'HYBRID'
)

$evaluationRoot = Split-Path -Parent $PSScriptRoot
$plan = Join-Path $PSScriptRoot 'smartdoc-rag-search.jmx'
if ([string]::IsNullOrWhiteSpace($DataFile)) {
  $DataFile = Join-Path $evaluationRoot 'datasets\rag_questions.csv'
}

if (-not (Test-Path -LiteralPath $JMeterBin)) { throw "JMeter executable not found: $JMeterBin" }
if (-not (Test-Path -LiteralPath $plan)) { throw "JMeter plan not found: $plan" }
if (-not (Test-Path -LiteralPath $DataFile)) { throw "CSV data file not found: $DataFile" }

$csvRows = @(Get-Content -LiteralPath $DataFile | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($csvRows.Count -lt 2) {
  throw "CSV data file has no executable rows: $DataFile"
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$resultDirectory = Join-Path $evaluationRoot 'results'
$reportDirectory = Join-Path $evaluationRoot ("reports\rag-search-" + $timestamp)
$resultFile = Join-Path $resultDirectory ("rag-search-" + $timestamp + '.jtl')
New-Item -ItemType Directory -Force -Path $resultDirectory, $reportDirectory | Out-Null

$jmeterArgs = @(
  '-n',
  '-t', $plan,
  '-l', $resultFile,
  '-e',
  '-o', $reportDirectory,
  ("-Jtoken=" + $Token),
  ("-Jhost=" + $HostName),
  ("-Jport=" + $Port),
  ("-Jthreads=" + $Threads),
  ("-JrampUpSeconds=" + $RampUpSeconds),
  ("-Jloops=" + $Loops),
  ("-JthinkTimeMs=" + $ThinkTimeMs),
  ("-JdataFile=" + $DataFile),
  ("-Jpath=" + $Path),
  ("-Jstrategy=" + $Strategy)
)

& $JMeterBin @jmeterArgs
if ($LASTEXITCODE -ne 0) { throw "JMeter failed with exit code: $LASTEXITCODE" }

Write-Host "Raw result: $resultFile"
Write-Host "HTML report: $reportDirectory\index.html"
