param(
  [Parameter(Mandatory=$true)][string]$Destination,
  [string]$ComposeFile = "docker-compose.yml",
  [int]$RetentionDays = 30
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:MYSQL_ROOT_PASSWORD)) { throw "MYSQL_ROOT_PASSWORD is required" }
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$composePath = Join-Path $workspace $ComposeFile
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $destinationPath | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupPath = Join-Path $destinationPath "smartdoc-$timestamp"
New-Item -ItemType Directory -Path $backupPath | Out-Null

docker compose -f $composePath exec -T mysql mysqldump -uroot "-p$env:MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers doc_ai | Set-Content -Encoding utf8 (Join-Path $backupPath "doc_ai.sql")
if ($LASTEXITCODE -ne 0) { throw "MySQL backup failed" }

docker run --rm --volumes-from docai-minio -v "${backupPath}:/backup" alpine:3.20 sh -c "cd /data && tar -czf /backup/minio-data.tar.gz ."
if ($LASTEXITCODE -ne 0) { throw "MinIO backup failed" }

docker run --rm --volumes-from docai-redis -v "${backupPath}:/backup" alpine:3.20 sh -c "cd /data && tar -czf /backup/redis-data.tar.gz ."
if ($LASTEXITCODE -ne 0) { throw "Redis backup failed" }

Get-FileHash (Get-ChildItem -LiteralPath $backupPath -File) -Algorithm SHA256 |
  ForEach-Object { "$($_.Hash)  $([System.IO.Path]::GetFileName($_.Path))" } |
  Set-Content -Encoding ascii (Join-Path $backupPath "SHA256SUMS")

$cutoff = (Get-Date).AddDays(-[Math]::Abs($RetentionDays))
Get-ChildItem -LiteralPath $destinationPath -Directory -Filter "smartdoc-*" |
  Where-Object { $_.CreationTime -lt $cutoff } |
  ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }

Write-Host "Backup completed: $backupPath"
