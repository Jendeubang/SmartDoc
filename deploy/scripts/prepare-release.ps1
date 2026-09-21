param([Parameter(Mandatory=$true)][ValidatePattern('^v\d+\.\d+\.\d+$')][string]$Version)

$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Push-Location $workspace
try {
  if (git status --porcelain) { throw "Working tree is not clean. Commit reviewed changes before preparing a release." }
  git fetch --tags
  if (git tag --list $Version) { throw "Tag already exists: $Version" }
  mvn -B -s .mvn/test-settings.xml verify
  if ($LASTEXITCODE -ne 0) { throw "Backend verification failed" }
  Push-Location vue-test-app
  try { npm.cmd ci; npm.cmd test; npm.cmd run build } finally { Pop-Location }
  if ($LASTEXITCODE -ne 0) { throw "Frontend verification failed" }
  docker compose --env-file .env.example config --quiet
  if ($LASTEXITCODE -ne 0) { throw "Compose validation failed" }
  git tag -s $Version -m "SmartDoc $Version"
  Write-Host "Signed release tag created locally: $Version. Review it, then run: git push origin $Version"
} finally { Pop-Location }
