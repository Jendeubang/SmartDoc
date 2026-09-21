param(
  [string]$ComposeFile = "docker-compose.yml",
  [string]$MigrationDirectory = "init-db/migrations"
)

$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$migrationPath = Join-Path $workspace $MigrationDirectory
if (-not (Test-Path -LiteralPath $migrationPath)) { throw "Migration directory not found: $migrationPath" }
if ([string]::IsNullOrWhiteSpace($env:MYSQL_ROOT_PASSWORD)) { throw "MYSQL_ROOT_PASSWORD is required" }

$composePath = Join-Path $workspace $ComposeFile
docker compose -f $composePath exec -T mysql mysql -uroot "-p$env:MYSQL_ROOT_PASSWORD" doc_ai -e "CREATE TABLE IF NOT EXISTS smartdoc_schema_history(version VARCHAR(120) PRIMARY KEY, applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"
if ($LASTEXITCODE -ne 0) { throw "Unable to initialize schema history" }

Get-ChildItem -LiteralPath $migrationPath -Filter "V*.sql" | Sort-Object Name | ForEach-Object {
  $version = $_.BaseName.Replace("'", "''")
  $applied = docker compose -f $composePath exec -T mysql mysql -N -uroot "-p$env:MYSQL_ROOT_PASSWORD" doc_ai -e "SELECT COUNT(*) FROM smartdoc_schema_history WHERE version='$version'"
  if (($applied | Select-Object -Last 1).Trim() -eq "0") {
    Get-Content -Raw -LiteralPath $_.FullName | docker compose -f $composePath exec -T mysql mysql -uroot "-p$env:MYSQL_ROOT_PASSWORD" doc_ai
    if ($LASTEXITCODE -ne 0) { throw "Migration failed: $($_.Name)" }
    docker compose -f $composePath exec -T mysql mysql -uroot "-p$env:MYSQL_ROOT_PASSWORD" doc_ai -e "INSERT INTO smartdoc_schema_history(version) VALUES('$version')"
    if ($LASTEXITCODE -ne 0) { throw "Unable to record migration: $($_.Name)" }
    Write-Host "Applied $($_.Name)"
  } else {
    Write-Host "Skipped $($_.Name) (already applied)"
  }
}
