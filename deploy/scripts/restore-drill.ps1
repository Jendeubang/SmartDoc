param(
  [Parameter(Mandatory=$true)][string]$BackupDirectory,
  [string]$ComposeFile = "docker-compose.yml"
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:MYSQL_ROOT_PASSWORD)) { throw "MYSQL_ROOT_PASSWORD is required" }
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$composePath = Join-Path $workspace $ComposeFile
$backupPath = (Resolve-Path -LiteralPath $BackupDirectory).Path
$sql = Join-Path $backupPath "doc_ai.sql"
if (-not (Test-Path -LiteralPath $sql)) { throw "doc_ai.sql is missing" }

Push-Location $backupPath
try {
  Get-Content SHA256SUMS | ForEach-Object {
    $parts = $_ -split '\s+', 2
    if ($parts.Count -eq 2) {
      $actual = (Get-FileHash -LiteralPath $parts[1].Trim() -Algorithm SHA256).Hash
      if ($actual -ne $parts[0]) { throw "Checksum mismatch: $($parts[1])" }
    }
  }
} finally { Pop-Location }

docker compose -f $composePath exec -T mysql mysql -uroot "-p$env:MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS smartdoc_restore_drill; CREATE DATABASE smartdoc_restore_drill CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
if ($LASTEXITCODE -ne 0) { throw "Unable to create restore drill database" }
Get-Content -Raw -LiteralPath $sql | docker compose -f $composePath exec -T mysql mysql -uroot "-p$env:MYSQL_ROOT_PASSWORD" smartdoc_restore_drill
if ($LASTEXITCODE -ne 0) { throw "Restore drill import failed" }
$tables = docker compose -f $composePath exec -T mysql mysql -N -uroot "-p$env:MYSQL_ROOT_PASSWORD" smartdoc_restore_drill -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='smartdoc_restore_drill'"
if ([int](($tables | Select-Object -Last 1).Trim()) -lt 5) { throw "Restore drill validation failed: too few tables" }
docker compose -f $composePath exec -T mysql mysql -uroot "-p$env:MYSQL_ROOT_PASSWORD" -e "DROP DATABASE smartdoc_restore_drill"

$drillSuffix = (Get-Date -Format "yyyyMMddHHmmss")
$minioVolume = "smartdoc_restore_minio_$drillSuffix"
$redisVolume = "smartdoc_restore_redis_$drillSuffix"
try {
  docker volume create $minioVolume | Out-Null
  docker volume create $redisVolume | Out-Null
  docker run --rm -v "${minioVolume}:/restore" -v "${backupPath}:/backup:ro" alpine:3.20 sh -c "cd /restore && tar -xzf /backup/minio-data.tar.gz"
  if ($LASTEXITCODE -ne 0) { throw "MinIO restore drill failed" }
  docker run --rm -v "${redisVolume}:/restore" -v "${backupPath}:/backup:ro" alpine:3.20 sh -c "cd /restore && tar -xzf /backup/redis-data.tar.gz"
  if ($LASTEXITCODE -ne 0) { throw "Redis restore drill failed" }
  $minioFiles = docker run --rm -v "${minioVolume}:/restore:ro" alpine:3.20 sh -c "find /restore -type f | wc -l"
  $redisFiles = docker run --rm -v "${redisVolume}:/restore:ro" alpine:3.20 sh -c "find /restore -type f | wc -l"
  if ([int](($minioFiles | Select-Object -Last 1).Trim()) -lt 1) { throw "MinIO restore drill produced no files" }
  if ([int](($redisFiles | Select-Object -Last 1).Trim()) -lt 1) { throw "Redis restore drill produced no files" }
} finally {
  docker volume rm $minioVolume $redisVolume 2>$null | Out-Null
}
Write-Host "Restore drill passed for MySQL, MinIO and Redis. Production data was not modified."
