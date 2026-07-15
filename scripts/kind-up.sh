#!/usr/bin/env bash
# kind 클러스터 생성/이미지 반영 및 배포 전 점검을 일괄 수행합니다.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_NAME="${STOCKRUSH_KIND_CLUSTER:-stockrush}"
NAMESPACE="${STOCKRUSH_K8S_NAMESPACE:-stockrush}"
IMAGE_TAG="${STOCKRUSH_IMAGE_TAG:-latest-demo}"
GHCR_USERNAME="${STOCKRUSH_GHCR_USERNAME:-cyson21}"
GHCR_TOKEN_FILE="${STOCKRUSH_GHCR_TOKEN_FILE:-$HOME/.config/stockrush/github-token}"
LOAD_LOCAL_IMAGES="${STOCKRUSH_KIND_LOAD_LOCAL_IMAGES:-false}"

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

render_file() {
  local file="$1"
  sed "s|:latest-demo|:${IMAGE_TAG}|g" "$file"
}

apply_namespaced_file() {
  local file="$1"
  render_file "$file" | kubectl -n "$NAMESPACE" apply -f -
}

load_image_if_present() {
  local image="$1"

  if docker image inspect "$image" >/dev/null 2>&1; then
    printf '[kind] loading local image %s\n' "$image"
    if ! kind load docker-image "$image" --name "$CLUSTER_NAME"; then
      printf '[warn] could not load local image %s. The cluster will pull it instead.\n' "$image" >&2
    fi
  else
    printf '[kind] local image not found, cluster will pull %s\n' "$image"
  fi
}

load_local_images_if_available() {
  if [[ "$LOAD_LOCAL_IMAGES" != "true" ]]; then
    return
  fi

  local image
  for image in \
    "postgres:16-alpine" \
    "redis:7-alpine" \
    "apache/kafka:4.2.0" \
    "quay.io/keycloak/keycloak:26.6.1" \
    "ghcr.io/cyson21/stockrush-catalog-service:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-inventory-service:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-order-service:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-payment-service:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-promotion-service:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-fulfillment-service:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-read-model-service:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-gateway:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-customer-app:${IMAGE_TAG}" \
    "ghcr.io/cyson21/stockrush-admin-app:${IMAGE_TAG}"; do
    load_image_if_present "$image"
  done
}

wait_for_default_serviceaccount() {
  for _ in $(seq 1 30); do
    if kubectl -n "$NAMESPACE" get serviceaccount default >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done

  printf '[fail] default serviceaccount was not created in namespace %s.\n' "$NAMESPACE" >&2
  exit 1
}

create_ghcr_pull_secret_from_token() {
  local token
  local auth
  local docker_config_file

  token="$(tr -d '\r\n' < "$GHCR_TOKEN_FILE")"
  if [[ -z "$token" ]]; then
    printf '[fail] GHCR token file is empty: %s\n' "$GHCR_TOKEN_FILE" >&2
    exit 1
  fi

  auth="$(printf '%s:%s' "$GHCR_USERNAME" "$token" | base64 | tr -d '\n')"
  docker_config_file="$(mktemp)"
  trap "rm -f '$docker_config_file'" RETURN
  printf '{"auths":{"ghcr.io":{"username":"%s","password":"%s","auth":"%s"}}}\n' \
    "$GHCR_USERNAME" "$token" "$auth" >"$docker_config_file"

  kubectl -n "$NAMESPACE" create secret generic ghcr-pull-secret \
    --from-file=.dockerconfigjson="$docker_config_file" \
    --type=kubernetes.io/dockerconfigjson \
    --dry-run=client -o yaml | kubectl apply -f -
  rm -f "$docker_config_file"
  trap - RETURN
}

patch_default_serviceaccount_for_pull_secret() {
  kubectl -n "$NAMESPACE" patch serviceaccount default \
    --type merge \
    -p '{"imagePullSecrets":[{"name":"ghcr-pull-secret"}]}' >/dev/null
}

apply_ghcr_pull_secret_if_available() {
  if [[ -s "$GHCR_TOKEN_FILE" ]]; then
    create_ghcr_pull_secret_from_token
    patch_default_serviceaccount_for_pull_secret
    return
  fi

  if [[ ! -s "$HOME/.docker/config.json" ]]; then
    printf '[warn] GHCR token file and Docker config not found. Private GHCR images may fail to pull.\n' >&2
    return
  fi

  kubectl -n "$NAMESPACE" create secret generic ghcr-pull-secret \
    --from-file=.dockerconfigjson="$HOME/.docker/config.json" \
    --type=kubernetes.io/dockerconfigjson \
    --dry-run=client -o yaml | kubectl apply -f -

  patch_default_serviceaccount_for_pull_secret
}

apply_file_configmaps() {
  kubectl -n "$NAMESPACE" create configmap postgres-init-schemas \
    --from-file=01-create-schemas.sql="$ROOT_DIR/infra/k8s/base/files/01-create-schemas.sql" \
    --dry-run=client -o yaml | kubectl apply -f -

  kubectl -n "$NAMESPACE" create configmap kafka-topics-script \
    --from-file=topics.sh="$ROOT_DIR/infra/k8s/base/files/topics.sh" \
    --dry-run=client -o yaml | kubectl apply -f -

  kubectl -n "$NAMESPACE" create configmap keycloak-realm \
    --from-file=stockrush-realm.json="$ROOT_DIR/infra/k8s/base/files/stockrush-realm.json" \
    --dry-run=client -o yaml | kubectl apply -f -
}

apply_infrastructure() {
  kubectl apply -f "$ROOT_DIR/infra/k8s/base/namespace.yaml"
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/configmap.yaml"
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/secret.yaml"
  apply_file_configmaps
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/postgres.yaml"
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/redis.yaml"
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/kafka.yaml"
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/keycloak.yaml"
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/kafka-init.yaml"
}

apply_applications() {
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/backend.yaml"
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/gateway.yaml"
  apply_namespaced_file "$ROOT_DIR/infra/k8s/base/web.yaml"
}

wait_for_deployment() {
  local name="$1"
  local timeout="${2:-900s}"

  printf '[kind] waiting for deployment/%s\n' "$name"
  if ! kubectl -n "$NAMESPACE" rollout status "deployment/${name}" --timeout="$timeout"; then
    kubectl -n "$NAMESPACE" describe "deployment/${name}" || true
    kubectl -n "$NAMESPACE" logs "deployment/${name}" --tail=200 || true
    exit 1
  fi
}

wait_for_all_deployments() {
  local name

  for name in $(kubectl -n "$NAMESPACE" get deployment -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort); do
    wait_for_deployment "$name"
  done
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

load_local_images_if_available
kubectl config use-context "kind-${CLUSTER_NAME}" >/dev/null
kubectl apply -f "$ROOT_DIR/infra/k8s/base/namespace.yaml"
wait_for_default_serviceaccount
apply_ghcr_pull_secret_if_available
kubectl -n "$NAMESPACE" delete job kafka-init --ignore-not-found >/dev/null

printf '[kind] applying StockRush infrastructure manifests with image tag %s\n' "$IMAGE_TAG"
apply_infrastructure

wait_for_deployment postgres
wait_for_deployment redis
wait_for_deployment kafka

printf '[kind] waiting for kafka-init job\n'
if ! kubectl -n "$NAMESPACE" wait --for=condition=complete job/kafka-init --timeout=900s; then
  kubectl -n "$NAMESPACE" logs job/kafka-init --tail=200 || true
  exit 1
fi

wait_for_deployment keycloak

printf '[kind] applying StockRush application manifests with image tag %s\n' "$IMAGE_TAG"
apply_applications

printf '[kind] waiting for deployments\n'
wait_for_all_deployments
kubectl -n "$NAMESPACE" get pods
