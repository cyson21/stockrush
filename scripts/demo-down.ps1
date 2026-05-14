$ErrorActionPreference = "Stop"

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$ComposeFile = Join-Path $RootDir "infra/demo/docker-compose.yml"
$EnvFile = Join-Path $RootDir "infra/demo/.env"
$EnvExample = Join-Path $RootDir "infra/demo/.env.example"

if (-not (Test-Path $EnvFile)) {
  Copy-Item $EnvExample $EnvFile
}

docker compose --env-file $EnvFile -f $ComposeFile down --remove-orphans @args
