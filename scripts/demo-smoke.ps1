$ErrorActionPreference = "Stop"

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$ComposeFile = Join-Path $RootDir "infra/demo/docker-compose.yml"
$EnvFile = Join-Path $RootDir "infra/demo/.env"
$EnvExample = Join-Path $RootDir "infra/demo/.env.example"

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

$GatewayPort = Get-PortValue "GATEWAY_HOST_PORT" "18080"
$CatalogPort = Get-PortValue "CATALOG_HOST_PORT" "18081"
$InventoryPort = Get-PortValue "INVENTORY_HOST_PORT" "18082"
$OrderPort = Get-PortValue "ORDER_HOST_PORT" "18083"
$PaymentPort = Get-PortValue "PAYMENT_HOST_PORT" "18084"
$PromotionPort = Get-PortValue "PROMOTION_HOST_PORT" "18085"
$FulfillmentPort = Get-PortValue "FULFILLMENT_HOST_PORT" "18086"
$ReadModelPort = Get-PortValue "READ_MODEL_HOST_PORT" "18087"
$CustomerAppPort = Get-PortValue "CUSTOMER_APP_HOST_PORT" "5173"
$AdminAppPort = Get-PortValue "ADMIN_APP_HOST_PORT" "5174"

Test-Url "gateway-health" "http://localhost:$GatewayPort/actuator/health"
Test-Url "catalog-health" "http://localhost:$CatalogPort/actuator/health"
Test-Url "inventory-health" "http://localhost:$InventoryPort/actuator/health"
Test-Url "order-health" "http://localhost:$OrderPort/actuator/health"
Test-Url "payment-health" "http://localhost:$PaymentPort/actuator/health"
Test-Url "promotion-health" "http://localhost:$PromotionPort/actuator/health"
Test-Url "fulfillment-health" "http://localhost:$FulfillmentPort/actuator/health"
Test-Url "read-model-health" "http://localhost:$ReadModelPort/actuator/health"
Test-Url "customer-app" "http://localhost:$CustomerAppPort/"
Test-Url "admin-app" "http://localhost:$AdminAppPort/"
Test-Url "catalog-products" "http://localhost:$CatalogPort/api/products?status=ON_SALE"
Test-Url "inventory-stocks" "http://localhost:$InventoryPort/api/stocks"
Test-Url "gateway-read-model" "http://localhost:$GatewayPort/api/read-model/admin/orders?page=0&size=1"
