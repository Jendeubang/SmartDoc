[CmdletBinding()]
param(
    [switch]$Help,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Host 'SmartDoc Docker Compose quickstart'
    Write-Host 'Usage: .\deploy\quickstart.ps1 [-NoBuild]'
    Write-Host '  -NoBuild  Reuse existing images instead of rebuilding them.'
    exit 0
}

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker command not found. Install Docker Desktop or Docker Engine first.'
}

if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
    $createdEnv = $true
    Write-Host 'Created .env from .env.example'
} else {
    $createdEnv = $false
    Write-Host 'Using existing .env; non-empty values will be preserved.'
}

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Get-EnvValue([string]$key) {
    $line = Get-Content -LiteralPath '.env' | Where-Object { $_ -match "^$([regex]::Escape($key))=" } | Select-Object -First 1
    if ($null -eq $line) { return $null }
    return $line.Substring($key.Length + 1)
}

function Set-EnvValue([string]$key, [string]$value, [bool]$force = $false) {
    $lines = @(Get-Content -LiteralPath '.env')
    $pattern = "^$([regex]::Escape($key))="
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) { $index = $i; break }
    }

    if ($index -ge 0) {
        $current = $lines[$index].Substring($key.Length + 1)
        $placeholder = [string]::IsNullOrWhiteSpace($current) -or
            $current.StartsWith('replace-with-', [System.StringComparison]::OrdinalIgnoreCase) -or
            $current.StartsWith('change-me', [System.StringComparison]::OrdinalIgnoreCase) -or
            $current.Contains('replace-with-', [System.StringComparison]::OrdinalIgnoreCase)
        if (-not $force -and -not $placeholder) { return }
        $lines[$index] = "$key=$value"
    } else {
        $lines += "$key=$value"
    }

    $utf8NoBom = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false
    [System.IO.File]::WriteAllLines((Join-Path $root '.env'), $lines, $utf8NoBom)
}

$webPort = Get-EnvValue 'SMARTDOC_WEB_PORT'
if ([string]::IsNullOrWhiteSpace($webPort)) { $webPort = '8088' }

$mysqlPassword = Get-EnvValue 'MYSQL_PASSWORD'
if ([string]::IsNullOrWhiteSpace($mysqlPassword) -or $mysqlPassword.StartsWith('replace-with-')) { $mysqlPassword = New-RandomSecret }
$rootPassword = Get-EnvValue 'MYSQL_ROOT_PASSWORD'
if ([string]::IsNullOrWhiteSpace($rootPassword) -or $rootPassword.StartsWith('replace-with-')) { $rootPassword = New-RandomSecret }
$rabbitPassword = Get-EnvValue 'RABBITMQ_PASSWORD'
if ([string]::IsNullOrWhiteSpace($rabbitPassword) -or $rabbitPassword.StartsWith('replace-with-')) { $rabbitPassword = New-RandomSecret }
$minioPassword = Get-EnvValue 'MINIO_SECRET_KEY'
if ([string]::IsNullOrWhiteSpace($minioPassword) -or $minioPassword.StartsWith('replace-with-')) { $minioPassword = New-RandomSecret }
$adminPassword = Get-EnvValue 'SMARTDOC_BOOTSTRAP_ADMIN_PASSWORD'
$adminPasswordGenerated = $false
if ([string]::IsNullOrWhiteSpace($adminPassword) -or $adminPassword.StartsWith('replace-with-')) {
    $adminPassword = New-RandomSecret
    $adminPasswordGenerated = $true
}

Set-EnvValue 'SMARTDOC_WEB_PORT' $webPort $createdEnv
Set-EnvValue 'MYSQL_HOST' 'mysql' $createdEnv
Set-EnvValue 'MYSQL_USERNAME' 'smartdoc' $createdEnv
Set-EnvValue 'MYSQL_PASSWORD' $mysqlPassword
Set-EnvValue 'MYSQL_ROOT_PASSWORD' $rootPassword
Set-EnvValue 'REDIS_HOST' 'redis' $createdEnv
Set-EnvValue 'RABBITMQ_HOST' 'rabbitmq' $createdEnv
Set-EnvValue 'RABBITMQ_USERNAME' 'smartdoc' $createdEnv
Set-EnvValue 'RABBITMQ_PASSWORD' $rabbitPassword
Set-EnvValue 'RABBITMQ_DEFAULT_USER' 'smartdoc' $createdEnv
Set-EnvValue 'RABBITMQ_DEFAULT_PASS' $rabbitPassword
Set-EnvValue 'MINIO_ENDPOINT' 'http://minio:9000' $createdEnv
Set-EnvValue 'MINIO_ACCESS_KEY' 'smartdoc' $createdEnv
Set-EnvValue 'MINIO_SECRET_KEY' $minioPassword
Set-EnvValue 'MINIO_ROOT_USER' 'smartdoc' $createdEnv
Set-EnvValue 'MINIO_ROOT_PASSWORD' $minioPassword
Set-EnvValue 'JWT_KEYS' "current:$(New-RandomSecret)"
Set-EnvValue 'JWT_ACTIVE_KID' 'current' $createdEnv
Set-EnvValue 'INTERNAL_SERVICE_TOKEN' (New-RandomSecret)
$quickstartOrigin = "http://localhost:$webPort"
$corsOrigins = Get-EnvValue 'CORS_ALLOWED_ORIGINS'
if ([string]::IsNullOrWhiteSpace($corsOrigins)) {
    Set-EnvValue 'CORS_ALLOWED_ORIGINS' $quickstartOrigin $true
} elseif (-not (($corsOrigins -split ',') | ForEach-Object { $_.Trim() } | Where-Object { $_ -eq $quickstartOrigin })) {
    Set-EnvValue 'CORS_ALLOWED_ORIGINS' "$corsOrigins,$quickstartOrigin" $true
}
Set-EnvValue 'SMARTDOC_BOOTSTRAP_ADMIN_USERNAME' 'admin' $createdEnv
Set-EnvValue 'SMARTDOC_BOOTSTRAP_ADMIN_PASSWORD' $adminPassword
Set-EnvValue 'SMARTDOC_BOOTSTRAP_ADMIN_EMAIL' 'admin@example.com' $createdEnv
Set-EnvValue 'SMARTDOC_BOOTSTRAP_ADMIN_PHONE' '13800000000' $createdEnv
Set-EnvValue 'SMARTDOC_AI_CREDENTIAL_MASTER_KEY' (New-RandomSecret)

$composeArgs = @('--env-file', '.env', '-f', 'docker-compose.yml', '-f', 'docker-compose.quickstart.yml', 'up', '-d')
if (-not $NoBuild) { $composeArgs += '--build' }
Write-Host 'Starting SmartDoc services. The first build may take several minutes.'
& docker compose @composeArgs
if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed with exit code $LASTEXITCODE" }

& docker compose --env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml ps
Write-Host "SmartDoc URL: http://localhost:$webPort"
Write-Host 'Administrator username: admin'
if ($adminPasswordGenerated) { Write-Host "Generated administrator password: $adminPassword" }
Write-Host 'To follow logs: docker compose --env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml logs -f'
