# ============================================================
# DocAI One-Click Start Script (PowerShell)
# ============================================================

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  DocAI One-Click Start" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check prerequisites
$hasDocker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $hasDocker) {
    Write-Host "[ERROR] Docker not found. Please install Docker Desktop first." -ForegroundColor Red
    exit 1
}
$hasMvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $hasMvn) {
    Write-Host "[ERROR] Maven not found. Please install Maven 3.8+ first." -ForegroundColor Red
    exit 1
}
Write-Host ""

# Step 1: Maven build
Write-Host "[Step 1/4] Maven building (first time ~5-10 min)..." -ForegroundColor Yellow
Write-Host "  Running: mvn clean package -DskipTests -q" -ForegroundColor Gray
$result = Invoke-Expression "mvn clean package -DskipTests -q 2>&1"
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Maven build failed." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Maven build succeeded" -ForegroundColor Green
Write-Host ""

# Step 2: Build Docker images
Write-Host "[Step 2/4] Building Docker images..." -ForegroundColor Yellow
docker compose build --parallel
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Docker build failed." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Docker images built" -ForegroundColor Green
Write-Host ""

# Step 3: Start all services
Write-Host "[Step 3/4] Starting all services..." -ForegroundColor Yellow
docker compose up -d
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Docker compose up failed." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] All services started" -ForegroundColor Green
Write-Host ""

# Step 4: Wait for services to be ready
Write-Host "[Step 4/4] Waiting for services (about 60s)..." -ForegroundColor Yellow
$services = @("docai-gateway", "docai-user", "docai-file", "docai-ai", "docai-document")
foreach ($svc in $services) {
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        $status = docker compose ps --format json $svc 2>$null | ConvertFrom-Json | Select-Object -ExpandProperty State
        if ($status -eq "running") {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if ($ready) {
        Write-Host "  [OK] $svc is running" -ForegroundColor Green
    } else {
        Write-Host "  [WARN] $svc may not be ready, check logs" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  DocAI is ready!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Frontend:    http://localhost:5173" -ForegroundColor White
Write-Host "  API Gateway: http://localhost:8080" -ForegroundColor White
Write-Host "  MinIO:       http://localhost:9001 (minioadmin/minioadmin123)" -ForegroundColor White
Write-Host "  RabbitMQ:    http://localhost:15672 (admin/admin123)" -ForegroundColor White
Write-Host ""
Write-Host "  Login:       admin / 123456" -ForegroundColor Green
Write-Host ""
Write-Host "  Commands:" -ForegroundColor Gray
Write-Host "  View logs:   docker compose logs -f" -ForegroundColor Gray
Write-Host "  Stop all:    docker compose down" -ForegroundColor Gray
Write-Host "========================================" -ForegroundColor Cyan
