$ErrorActionPreference = "Stop"

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$ComposeFile = Join-Path $RootDir "infra/demo/docker-compose.yml"
$ImageComposeFile = Join-Path $RootDir "infra/demo/docker-compose.images.yml"
$EnvFile = Join-Path $RootDir "infra/demo/.env"
$EnvExample = Join-Path $RootDir "infra/demo/.env.example"
$TokenFile = if ($env:STOCKRUSH_GITHUB_TOKEN_FILE) { $env:STOCKRUSH_GITHUB_TOKEN_FILE } else { Join-Path $HOME ".config/stockrush/github-token" }

$ImageRegistry = "ghcr.io"
$ImageOwner = "cyson21"
$ImageTag = "latest-demo"
$RefreshEnv = $false
$SkipPull = $false
$SkipSmoke = $false
$Login = $false

function Show-Usage {
  Write-Host "Usage: .\scripts\deploy-local.ps1 [options]"
  Write-Host ""
  Write-Host "Options:"
  Write-Host "  --tag <tag>        Image tag to deploy. Default: latest-demo"
  Write-Host "  --owner <owner>    GHCR owner or org. Default: cyson21"
  Write-Host "  --registry <host>  Registry host. Default: ghcr.io"
  Write-Host "  --login            Run docker login using ~/.config/stockrush/github-token"
  Write-Host "  --refresh-env      Replace infra/demo/.env from infra/demo/.env.example"
  Write-Host "  --skip-pull        Do not pull images before up"
  Write-Host "  --skip-smoke       Do not run demo-smoke after up"
}

for ($Index = 0; $Index -lt $args.Count; $Index++) {
  switch ($args[$Index]) {
    "--tag" {
      $Index++
      if ($Index -ge $args.Count) { throw "--tag requires a value" }
      $ImageTag = $args[$Index]
    }
    "--owner" {
      $Index++
      if ($Index -ge $args.Count) { throw "--owner requires a value" }
      $ImageOwner = $args[$Index]
    }
    "--registry" {
      $Index++
      if ($Index -ge $args.Count) { throw "--registry requires a value" }
      $ImageRegistry = $args[$Index]
    }
    "--login" { $Login = $true }
    "--refresh-env" { $RefreshEnv = $true }
    "--skip-pull" { $SkipPull = $true }
    "--skip-smoke" { $SkipSmoke = $true }
    "--help" {
      Show-Usage
      exit 0
    }
    "-h" {
      Show-Usage
      exit 0
    }
    default {
      throw "Unknown option: $($args[$Index])"
    }
  }
}

if ($RefreshEnv) {
  Copy-Item $EnvExample $EnvFile -Force
} elseif (-not (Test-Path $EnvFile)) {
  Copy-Item $EnvExample $EnvFile
}

if ($Login) {
  if (-not (Test-Path $TokenFile)) {
    throw "Token file not found: $TokenFile"
  }
  Get-Content $TokenFile -Raw | docker login $ImageRegistry -u $ImageOwner --password-stdin
}

[Environment]::SetEnvironmentVariable("STOCKRUSH_IMAGE_REGISTRY", $ImageRegistry, "Process")
[Environment]::SetEnvironmentVariable("STOCKRUSH_IMAGE_OWNER", $ImageOwner, "Process")
[Environment]::SetEnvironmentVariable("STOCKRUSH_IMAGE_TAG", $ImageTag, "Process")

Write-Host "[deploy] registry=$ImageRegistry owner=$ImageOwner tag=$ImageTag"

if (-not $SkipPull) {
  docker compose --env-file $EnvFile -f $ComposeFile -f $ImageComposeFile pull
}

docker compose --env-file $EnvFile -f $ComposeFile -f $ImageComposeFile up -d --no-build
docker compose --env-file $EnvFile -f $ComposeFile -f $ImageComposeFile ps

if (-not $SkipSmoke) {
  & (Join-Path $RootDir "scripts/demo-smoke.ps1")
}
