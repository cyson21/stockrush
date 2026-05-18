#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_NAME="${STOCKRUSH_KIND_CLUSTER:-stockrush}"
NAMESPACE="${STOCKRUSH_K8S_NAMESPACE:-stockrush}"
IMAGE_TAG="${STOCKRUSH_IMAGE_TAG:-latest-demo}"

usage() {
  cat <<'EOF'
Usage: ./scripts/kind-up.sh [options]

Options:
  --cluster <name>  kind cluster name. Default: stockrush
  --tag <tag>       StockRush GHCR image tag. Default: latest-demo
  -h, --help        Show this help
EOF
}

while (($#)); do
  case "$1" in
    --cluster)
      CLUSTER_NAME="${2:?--cluster requires a value}"
      shift 2
      ;;
    --tag)
      IMAGE_TAG="${2:?--tag requires a value}"
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

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '[fail] %s is required for the kind demo runtime.\n' "$1" >&2
    exit 1
  fi
}

render_manifest() {
  kubectl kustomize "$ROOT_DIR/infra/k8s/kind" | sed "s|:latest-demo|:${IMAGE_TAG}|g"
}

apply_ghcr_pull_secret_if_available() {
  if [[ ! -s "$HOME/.docker/config.json" ]]; then
    printf '[warn] ~/.docker/config.json not found. Private GHCR images may fail to pull.\n' >&2
    return
  fi

  kubectl -n "$NAMESPACE" create secret generic ghcr-pull-secret \
    --from-file=.dockerconfigjson="$HOME/.docker/config.json" \
    --type=kubernetes.io/dockerconfigjson \
    --dry-run=client -o yaml | kubectl apply -f -

  kubectl -n "$NAMESPACE" patch serviceaccount default \
    --type merge \
    -p '{"imagePullSecrets":[{"name":"ghcr-pull-secret"}]}' >/dev/null
}

require_command docker
require_command kind
require_command kubectl

if ! docker info >/dev/null 2>&1; then
  printf '[fail] Docker is not running. Start Docker Desktop first.\n' >&2
  exit 1
fi

if ! kind get clusters | grep -qx "$CLUSTER_NAME"; then
  printf '[kind] creating cluster %s\n' "$CLUSTER_NAME"
  kind create cluster --name "$CLUSTER_NAME"
fi

kubectl config use-context "kind-${CLUSTER_NAME}" >/dev/null
kubectl apply -f "$ROOT_DIR/infra/k8s/base/namespace.yaml"
apply_ghcr_pull_secret_if_available

printf '[kind] applying StockRush manifests with image tag %s\n' "$IMAGE_TAG"
render_manifest | kubectl apply -f -

printf '[kind] waiting for kafka-init job\n'
if ! kubectl -n "$NAMESPACE" wait --for=condition=complete job/kafka-init --timeout=300s; then
  kubectl -n "$NAMESPACE" logs job/kafka-init --tail=200 || true
  exit 1
fi

printf '[kind] waiting for deployments\n'
kubectl -n "$NAMESPACE" rollout status deployment --all --timeout=600s
kubectl -n "$NAMESPACE" get pods
