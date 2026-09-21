$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeBase = Join-Path $repoRoot 'docker-compose.yml'
$composeProd = Join-Path $repoRoot 'docker-compose.prod.yml'
$json = & docker compose -f $composeBase -f $composeProd config --format json
if ($LASTEXITCODE -ne 0) {
    throw 'docker compose config failed'
}

$config = $json | ConvertFrom-Json
$violations = @()
foreach ($service in $config.services.PSObject.Properties) {
    foreach ($port in @($service.Value.ports)) {
        if ($null -eq $port) {
            continue
        }
        $published = [int]$port.published
        if ($published -notin @(80, 443)) {
            $violations += "$($service.Name):$published"
        }
    }
}

if ($violations.Count -gt 0) {
    throw "Unexpected production ports: $($violations -join ', ')"
}

Write-Host 'Production port boundary verified: only 80/443 are published.'
