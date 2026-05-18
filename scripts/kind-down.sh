#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="${STOCKRUSH_KIND_CLUSTER:-stockrush}"

usage() {
  cat <<'EOF'
Usage: ./scripts/kind-down.sh [options]

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

if ! command -v kind >/dev/null 2>&1; then
  printf '[fail] kind is required to delete the cluster.\n' >&2
  exit 1
fi

if kind get clusters | grep -qx "$CLUSTER_NAME"; then
  kind delete cluster --name "$CLUSTER_NAME"
else
  printf '[kind] cluster %s does not exist\n' "$CLUSTER_NAME"
fi
