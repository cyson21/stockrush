$ErrorActionPreference = "Stop"
<# demo compose 스택을 중단하고 고아 컨테이너 정리를 수행합니다. #>

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$ComposeFile = Join-Path $RootDir "infra/demo/docker-compose.yml"
$EnvFile = Join-Path $RootDir "infra/demo/.env"
$EnvExample = Join-Path $RootDir "infra/demo/.env.example"

if (-not (Test-Path $EnvFile)) {
  Copy-Item $EnvExample $EnvFile
}

docker compose --env-file $EnvFile -f $ComposeFile down --remove-orphans @args
