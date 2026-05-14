$ErrorActionPreference = "Stop"

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$ComposeFile = Join-Path $RootDir "infra/demo/docker-compose.yml"
$EnvFile = Join-Path $RootDir "infra/demo/.env"
$EnvExample = Join-Path $RootDir "infra/demo/.env.example"
$SkipBurst = $false
$KafkaOutage = $false

function Show-Usage {
  Write-Host "Usage: .\scripts\demo-smoke.ps1 [options]"
  Write-Host ""
  Write-Host "Options:"
  Write-Host "  --skip-burst    Run only health checks and demo-order-flow"
  Write-Host "  --kafka-outage  Run opt-in Kafka recovery smoke using docker compose pause kafka / unpause kafka"
  Write-Host "  -h, --help      Show this help"
}

foreach ($Arg in $args) {
  switch ($Arg) {
    "--skip-burst" { $SkipBurst = $true }
    "--kafka-outage" { $KafkaOutage = $true }
    "-h" {
      Show-Usage
      exit 0
    }
    "--help" {
      Show-Usage
      exit 0
    }
    default {
      Write-Error "Unknown option: $Arg"
      Show-Usage
      exit 2
    }
  }
}

if (-not (Test-Path $EnvFile)) {
  Copy-Item $EnvExample $EnvFile
}

Get-Content $EnvFile | ForEach-Object {
  if ($_ -match "^\s*#" -or $_ -notmatch "=") {
    return
  }

  $parts = $_.Split("=", 2)
  [Environment]::SetEnvironmentVariable($parts[0], $parts[1], "Process")
}

function Get-PortValue($Name, $DefaultValue) {
  $value = [Environment]::GetEnvironmentVariable($Name, "Process")
  if ([string]::IsNullOrWhiteSpace($value)) {
    return $DefaultValue
  }
  return $value
}

function Test-Url($Name, $Url) {
  try {
    Invoke-WebRequest -Uri $Url -TimeoutSec 10 -UseBasicParsing | Out-Null
    Write-Host "[ok] $Name $Url"
  } catch {
    Write-Error "[fail] $Name $Url $($_.Exception.Message)"
    docker compose --env-file $EnvFile -f $ComposeFile ps
    exit 1
  }
}

function Test-ActuatorEndpoints($Name, $BaseUrl) {
  Test-Url "$Name-health" "$BaseUrl/actuator/health"
  Test-Url "$Name-info" "$BaseUrl/actuator/info"
  Test-Url "$Name-metrics" "$BaseUrl/actuator/metrics"
}

$GatewayPort = Get-PortValue "GATEWAY_HOST_PORT" "28080"
$CatalogPort = Get-PortValue "CATALOG_HOST_PORT" "28081"
$InventoryPort = Get-PortValue "INVENTORY_HOST_PORT" "28082"
$OrderPort = Get-PortValue "ORDER_HOST_PORT" "28083"
$PaymentPort = Get-PortValue "PAYMENT_HOST_PORT" "28084"
$PromotionPort = Get-PortValue "PROMOTION_HOST_PORT" "28085"
$FulfillmentPort = Get-PortValue "FULFILLMENT_HOST_PORT" "28086"
$ReadModelPort = Get-PortValue "READ_MODEL_HOST_PORT" "28087"
$CustomerAppPort = Get-PortValue "CUSTOMER_APP_HOST_PORT" "15173"
$AdminAppPort = Get-PortValue "ADMIN_APP_HOST_PORT" "15174"

Test-ActuatorEndpoints "gateway" "http://localhost:$GatewayPort"
Test-ActuatorEndpoints "catalog" "http://localhost:$CatalogPort"
Test-ActuatorEndpoints "inventory" "http://localhost:$InventoryPort"
Test-ActuatorEndpoints "order" "http://localhost:$OrderPort"
Test-ActuatorEndpoints "payment" "http://localhost:$PaymentPort"
Test-ActuatorEndpoints "promotion" "http://localhost:$PromotionPort"
Test-ActuatorEndpoints "fulfillment" "http://localhost:$FulfillmentPort"
Test-ActuatorEndpoints "read-model" "http://localhost:$ReadModelPort"
Test-Url "customer-app" "http://localhost:$CustomerAppPort/"
Test-Url "admin-app" "http://localhost:$AdminAppPort/"
Test-Url "catalog-products" "http://localhost:$CatalogPort/api/products?status=ON_SALE"
Test-Url "inventory-stocks" "http://localhost:$InventoryPort/api/stocks"
Test-Url "gateway-read-model" "http://localhost:$GatewayPort/api/read-model/admin/orders?page=0&size=1"

python (Join-Path $RootDir "tools/local-e2e/local-e2e") demo-order-flow `
  --catalog-url "http://localhost:$CatalogPort" `
  --inventory-url "http://localhost:$InventoryPort" `
  --order-url "http://localhost:$OrderPort" `
  --order-api-url "http://localhost:$GatewayPort" `
  --outbox-api-url "http://localhost:$GatewayPort" `
  --payment-url "http://localhost:$PaymentPort" `
  --promotion-url "http://localhost:$PromotionPort" `
  --relay-mode automatic `
  --orders 3 `
  --initial-stock 20 `
  --quantity 1 `
  --max-attempts 30 `
  --wait-seconds 1

if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

if (-not $SkipBurst) {
  python (Join-Path $RootDir "tools/local-e2e/local-e2e") burst-idempotency `
    --catalog-url "http://localhost:$CatalogPort" `
    --inventory-url "http://localhost:$InventoryPort" `
    --order-url "http://localhost:$OrderPort" `
    --order-api-url "http://localhost:$GatewayPort" `
    --outbox-api-url "http://localhost:$GatewayPort" `
    --payment-url "http://localhost:$PaymentPort" `
    --promotion-url "http://localhost:$PromotionPort" `
    --relay-mode automatic `
    --orders 12 `
    --initial-stock 4 `
    --quantity 1 `
    --idempotency-replays 3 `
    --relay-workers 4 `
    --stability-waves 2 `
    --max-attempts 30 `
    --wait-seconds 1

  if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
  }
}

if ($KafkaOutage) {
  python (Join-Path $RootDir "tools/local-e2e/local-e2e") kafka-outage-recovery `
    --compose-file $ComposeFile `
    --env-file $EnvFile `
    --kafka-service kafka `
    --catalog-url "http://localhost:$CatalogPort" `
    --inventory-url "http://localhost:$InventoryPort" `
    --order-url "http://localhost:$OrderPort" `
    --order-api-url "http://localhost:$GatewayPort" `
    --outbox-api-url "http://localhost:$GatewayPort" `
    --payment-url "http://localhost:$PaymentPort" `
    --promotion-url "http://localhost:$PromotionPort" `
    --relay-mode automatic `
    --orders 1 `
    --initial-stock 3 `
    --quantity 1 `
    --outage-observation-seconds 2 `
    --max-attempts 30 `
    --wait-seconds 1

  if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
  }
}
