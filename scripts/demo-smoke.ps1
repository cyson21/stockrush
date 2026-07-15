$ErrorActionPreference = "Stop"
<# 데모 환경에서 주요 엔드포인트 health/smoke 시나리오를 점검합니다. #>

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

function Test-Url {
  param(
    [string]$Name,
    [string]$Url,
    [hashtable]$Headers = @{}
  )

  try {
    Invoke-WebRequest -Uri $Url -Headers $Headers -TimeoutSec 10 -UseBasicParsing | Out-Null
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
$GatewayBaseUrl = "http://localhost:$GatewayPort"
$CustomerAppPort = Get-PortValue "CUSTOMER_APP_HOST_PORT" "15173"
$AdminAppPort = Get-PortValue "ADMIN_APP_HOST_PORT" "15174"
$KeycloakPort = Get-PortValue "KEYCLOAK_HOST_PORT" "28088"
$KeycloakClientId = Get-PortValue "KEYCLOAK_SMOKE_CLIENT_ID" "stockrush-demo-smoke"

$KeycloakAdminUsername = Get-PortValue "KEYCLOAK_SMOKE_ADMIN_USERNAME" "admin.demo@stockrush.local"
$KeycloakAdminPassword = Get-PortValue "KEYCLOAK_SMOKE_ADMIN_PASSWORD" "demo-admin-pass"
$KeycloakCustomerUsername = Get-PortValue "KEYCLOAK_SMOKE_CUSTOMER_USERNAME" "customer.demo@stockrush.local"
$KeycloakCustomerPassword = Get-PortValue "KEYCLOAK_SMOKE_CUSTOMER_PASSWORD" "demo-customer-pass"

function Wait-KeycloakReady($Port) {
  $maxAttempts = 120
  $url = "http://localhost:$Port/realms/stockrush/.well-known/openid-configuration"
  for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
    try {
      Invoke-WebRequest -Uri $url -TimeoutSec 5 -UseBasicParsing | Out-Null
      Write-Host "[ok] keycloak realm ready"
      return
    } catch {
      if ($attempt -lt $maxAttempts) {
        Write-Host "[wait] keycloak ready attempt $attempt/$maxAttempts"
        Start-Sleep -Seconds 1
      }
    }
  }

  Write-Error "[fail] keycloak realm did not become ready at $url"
  docker compose --env-file $EnvFile -f $ComposeFile ps
  exit 1
}

function Get-KeycloakToken($Port, $ClientId, $Username, $Password) {
  $TokenUrl = "http://localhost:$Port/realms/stockrush/protocol/openid-connect/token"
  try {
    $response = Invoke-RestMethod -Method Post -Uri $TokenUrl -Body @{
      grant_type = 'password'
      client_id = $ClientId
      username = $Username
      password = $Password
    } -ContentType 'application/x-www-form-urlencoded'
    return $response.access_token
  } catch {
    Write-Error "[fail] keycloak token request failed for user $Username. $($_.Exception.Message)"
    exit 1
  }
}

function Invoke-LocalE2E {
  param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
  )

  $Runner = Join-Path $RootDir "tools/local-e2e/local-e2e"
  if (Get-Command python -ErrorAction SilentlyContinue) {
    & python $Runner @Arguments
    return
  }
  if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 $Runner @Arguments
    return
  }

  Write-Error "Python 3 is required. Install Python, or make either python or py available on PATH."
  exit 1
}

Test-ActuatorEndpoints "gateway" $GatewayBaseUrl
Test-Url "customer-app" "http://localhost:$CustomerAppPort/"
Test-Url "admin-app" "http://localhost:$AdminAppPort/"
Test-Url "gateway-products" "$GatewayBaseUrl/api/products?status=ON_SALE"
Test-Url "gateway-stocks" "$GatewayBaseUrl/api/stocks"

Wait-KeycloakReady $KeycloakPort

$AdminBearerToken = Get-KeycloakToken `
  $KeycloakPort `
  $KeycloakClientId `
  $KeycloakAdminUsername `
  $KeycloakAdminPassword

$CustomerBearerToken = Get-KeycloakToken `
  $KeycloakPort `
  $KeycloakClientId `
  $KeycloakCustomerUsername `
  $KeycloakCustomerPassword

if (-not $AdminBearerToken -or -not $CustomerBearerToken) {
  Write-Error "[fail] failed to obtain admin/customer keycloak tokens"
  exit 1
}

Test-Url "gateway-read-model" "$GatewayBaseUrl/api/read-model/admin/orders?page=0&size=1" @{Authorization = "Bearer $AdminBearerToken"}

Invoke-LocalE2E demo-order-flow `
  --public-api-url $GatewayBaseUrl `
  --admin-api-url $GatewayBaseUrl `
  --order-api-url $GatewayBaseUrl `
  --admin-bearer-token "$AdminBearerToken" `
  --customer-bearer-token "$CustomerBearerToken" `
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
  Invoke-LocalE2E burst-idempotency `
    --public-api-url $GatewayBaseUrl `
    --admin-api-url $GatewayBaseUrl `
    --order-api-url $GatewayBaseUrl `
    --admin-bearer-token "$AdminBearerToken" `
    --customer-bearer-token "$CustomerBearerToken" `
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
  Invoke-LocalE2E kafka-outage-recovery `
    --compose-file $ComposeFile `
    --env-file $EnvFile `
    --kafka-service kafka `
    --public-api-url $GatewayBaseUrl `
    --admin-api-url $GatewayBaseUrl `
    --order-api-url $GatewayBaseUrl `
    --admin-bearer-token "$AdminBearerToken" `
    --customer-bearer-token "$CustomerBearerToken" `
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
