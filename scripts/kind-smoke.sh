#!/usr/bin/env bash
# kind 클러스터 포트포워딩과 기본 헬스체크를 통해 데모 런타임 상태를 확인합니다.
set -euo pipefail

CLUSTER_NAME="${STOCKRUSH_KIND_CLUSTER:-stockrush}"
NAMESPACE="${STOCKRUSH_K8S_NAMESPACE:-stockrush}"

PIDS=""

usage() {
  cat <<'EOF'
Usage: ./scripts/kind-smoke.sh [options]

Options:
  --cluster <name>  kind cluster name. Default: stockrush
  -h, --help        Show this help
EOF
}

while (($#)); do
  case "$1" in
    --cluster)
      CLUSTER_NAME="${2:?--cluster requires a value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

cleanup() {
  for pid in $PIDS; do
    kill "$pid" >/dev/null 2>&1 || true
  done
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '[fail] %s is required for Kubernetes smoke.\n' "$1" >&2
    exit 1
  fi
}

wait_for_url() {
  local name="$1"
  local url="$2"

  for attempt in $(seq 1 60); do
    if curl -fsS --max-time 5 "$url" >/dev/null 2>&1; then
      printf '[ok] %s %s\n' "$name" "$url"
      return
    fi
    sleep 1
  done

  printf '[fail] %s %s\n' "$name" "$url" >&2
  exit 1
}

start_port_forward() {
  local name="$1"
  local mapping="$2"
  local log_file="/tmp/stockrush-kind-${name}.log"

  kubectl -n "$NAMESPACE" port-forward "svc/${name}" "$mapping" >"$log_file" 2>&1 &
  PIDS="$PIDS $!"
}

wait_for_deployment() {
  local name="$1"
  local timeout="${2:-600s}"

  kubectl -n "$NAMESPACE" rollout status "deployment/${name}" --timeout="$timeout"
}

wait_for_all_deployments() {
  local name

  for name in $(kubectl -n "$NAMESPACE" get deployment -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort); do
    wait_for_deployment "$name"
  done
}

trap cleanup EXIT

require_command kind
require_command kubectl
require_command curl

if ! kind get clusters | grep -qx "$CLUSTER_NAME"; then
  printf '[fail] kind cluster %s does not exist. Run ./scripts/kind-up.sh first.\n' "$CLUSTER_NAME" >&2
  exit 1
fi

kubectl config use-context "kind-${CLUSTER_NAME}" >/dev/null
kubectl get namespace "$NAMESPACE" >/dev/null

kubectl -n "$NAMESPACE" wait --for=condition=complete job/kafka-init --timeout=300s
wait_for_all_deployments

start_port_forward gateway 38080:18080
start_port_forward customer-app 35173:8080
start_port_forward admin-app 35174:8080
start_port_forward keycloak 38088:8080

wait_for_url gateway-health http://localhost:38080/actuator/health
wait_for_url customer-app http://localhost:35173/
wait_for_url admin-app http://localhost:35174/
wait_for_url keycloak-realm http://localhost:38088/realms/stockrush/.well-known/openid-configuration

printf '[ok] kind smoke passed\n'
