$ErrorActionPreference = "Stop"

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$ComposeFile = Join-Path $RootDir "infra/demo/docker-compose.yml"
$EnvFile = Join-Path $RootDir "infra/demo/.env"
$EnvExample = Join-Path $RootDir "infra/demo/.env.example"
$ExpectedEnvRev = "2026-05-14-demo-cicd-v3"

$RefreshEnv = $false
$SkipPortCheck = $false
$ComposeArgs = @()

foreach ($Arg in $args) {
  switch ($Arg) {
    "--refresh-env" { $RefreshEnv = $true }
    "--skip-port-check" { $SkipPortCheck = $true }
    default { $ComposeArgs += $Arg }
  }
}

if ($RefreshEnv) {
  Copy-Item $EnvExample $EnvFile -Force
} elseif (-not (Test-Path $EnvFile)) {
  Copy-Item $EnvExample $EnvFile
}

if (-not (Select-String -Path $EnvFile -Pattern "^DEMO_ENV_REV=$ExpectedEnvRev$" -Quiet)) {
  Write-Warning "infra/demo/.env does not match the current template revision."
  Write-Warning "Run .\scripts\demo-up.ps1 --refresh-env to copy the latest demo defaults, or edit infra/demo/.env manually."
}

Get-Content $EnvFile | ForEach-Object {
  if ($_ -match "^\s*#" -or $_ -notmatch "=") {
    return
  }

  $Parts = $_.Split("=", 2)
  [Environment]::SetEnvironmentVariable($Parts[0], $Parts[1], "Process")
}

function Get-PortValue($Name, $DefaultValue) {
  $Value = [Environment]::GetEnvironmentVariable($Name, "Process")
  if ([string]::IsNullOrWhiteSpace($Value)) {
    return [int]$DefaultValue
  }
  return [int]$Value
}

function Test-CurrentDemoPort($Service, $TargetPort, $HostPort) {
  $Mapped = docker compose --env-file $EnvFile -f $ComposeFile port $Service $TargetPort 2>$null
  return ($Mapped -match ":$HostPort$")
}

function Get-HostPortOwner($Port) {
  if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
    return Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
      Select-Object -First 3 LocalAddress, LocalPort, OwningProcess
  }

  $Listener = $null
  try {
    $Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    $Listener.Start()
    return $null
  } catch {
    return "Port is already in use"
  } finally {
    if ($null -ne $Listener) {
      $Listener.Stop()
    }
  }
}

function Test-DemoPorts {
  $Specs = @(
    @{ Name = "POSTGRES_HOST_PORT"; Default = 25432; Service = "postgres"; Target = 5432 },
    @{ Name = "REDIS_HOST_PORT"; Default = 26379; Service = "redis"; Target = 6379 },
    @{ Name = "KAFKA_HOST_PORT"; Default = 29092; Service = "kafka"; Target = 9092 },
    @{ Name = "KAFKA_UI_PORT"; Default = 29090; Service = "kafka-ui"; Target = 8080 },
    @{ Name = "GATEWAY_HOST_PORT"; Default = 28080; Service = "gateway"; Target = 18080 },
    @{ Name = "CATALOG_HOST_PORT"; Default = 28081; Service = "catalog-service"; Target = 18081 },
    @{ Name = "INVENTORY_HOST_PORT"; Default = 28082; Service = "inventory-service"; Target = 18082 },
    @{ Name = "ORDER_HOST_PORT"; Default = 28083; Service = "order-service"; Target = 18083 },
    @{ Name = "PAYMENT_HOST_PORT"; Default = 28084; Service = "payment-service"; Target = 18084 },
    @{ Name = "PROMOTION_HOST_PORT"; Default = 28085; Service = "promotion-service"; Target = 18085 },
    @{ Name = "FULFILLMENT_HOST_PORT"; Default = 28086; Service = "fulfillment-service"; Target = 18086 },
    @{ Name = "READ_MODEL_HOST_PORT"; Default = 28087; Service = "read-model-service"; Target = 18087 },
    @{ Name = "CUSTOMER_APP_HOST_PORT"; Default = 15173; Service = "customer-app"; Target = 8080 },
    @{ Name = "ADMIN_APP_HOST_PORT"; Default = 15174; Service = "admin-app"; Target = 8080 }
  )

  $Seen = @{}
  $Failed = $false

  foreach ($Spec in $Specs) {
    $PortText = [Environment]::GetEnvironmentVariable($Spec.Name, "Process")
    if ([string]::IsNullOrWhiteSpace($PortText)) {
      $PortText = [string]$Spec.Default
    }

    $Port = 0
    if (-not [int]::TryParse($PortText, [ref]$Port) -or $Port -lt 1 -or $Port -gt 65535) {
      Write-Error "$($Spec.Name) must be a TCP port number between 1 and 65535: $PortText"
      $Failed = $true
      continue
    }

    if ($Seen.ContainsKey($Port)) {
      Write-Error "duplicate demo host port $Port found at $($Spec.Name) and $($Seen[$Port])"
      $Failed = $true
    } else {
      $Seen[$Port] = $Spec.Name
    }

    $Owner = Get-HostPortOwner $Port
    if ($null -ne $Owner) {
      if (Test-CurrentDemoPort $Spec.Service $Spec.Target $Port) {
        Write-Host "[ok] $($Spec.Name)=$Port is already bound by the current demo stack."
        continue
      }

      Write-Error "$($Spec.Name)=$Port is already in use on this machine. Edit infra/demo/.env or run .\scripts\demo-up.ps1 --refresh-env."
      $Owner | Format-Table | Out-String | Write-Error
      $Failed = $true
    }
  }

  if ($Failed) {
    exit 1
  }
}

if (-not $SkipPortCheck) {
  Test-DemoPorts
}

docker compose --env-file $EnvFile -f $ComposeFile up -d --build @ComposeArgs
docker compose --env-file $EnvFile -f $ComposeFile ps
