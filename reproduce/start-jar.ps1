param(
    [Parameter(Mandatory=$true)]
    [string]$Module,
    [int]$Port = 8080
)

$ProjectRoot = "F:\DocAI\DocAI-main"
$EnvFile = Join-Path $ProjectRoot "reproduce\.env.local"

# Load environment variables from .env.local
Get-Content $EnvFile | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

# Additional env vars to disable Nacos and set local addresses
[Environment]::SetEnvironmentVariable('spring_cloud_nacos_discovery_enabled', 'false', 'Process')
[Environment]::SetEnvironmentVariable('spring_cloud_nacos_config_enabled', 'false', 'Process')
[Environment]::SetEnvironmentVariable('spring_cloud_nacos_discovery_server_addr', '127.0.0.1:8848', 'Process')
[Environment]::SetEnvironmentVariable('SERVER_PORT', $Port.ToString(), 'Process')
[Environment]::SetEnvironmentVariable('MYSQL_HOST', 'localhost', 'Process')
[Environment]::SetEnvironmentVariable('MYSQL_PORT', '3306', 'Process')
[Environment]::SetEnvironmentVariable('REDIS_HOST', 'localhost', 'Process')
[Environment]::SetEnvironmentVariable('RABBITMQ_HOST', 'localhost', 'Process')

$JarDir = Join-Path (Join-Path $ProjectRoot $Module) "target"
$JarFile = Get-ChildItem -Path $JarDir -Filter "*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1

Write-Output "Starting $Module on port $Port ..."
Write-Output "JAR: $($JarFile.FullName)"

java -jar $JarFile.FullName
