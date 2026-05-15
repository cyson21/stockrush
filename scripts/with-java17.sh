#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./scripts/with-java17.sh <command> [args...]

Runs a command with JAVA_HOME set to a Java 17 JDK.

Override detection with:
  STOCKRUSH_JAVA17_HOME=/path/to/jdk-17 ./scripts/with-java17.sh mvn test
EOF
}

if [[ $# -eq 0 ]]; then
  usage >&2
  exit 2
fi

is_java17_home() {
  local candidate="${1:-}"
  [[ -x "$candidate/bin/java" ]] || return 1
  "$candidate/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "17\.'
}

find_java17_home() {
  if is_java17_home "${STOCKRUSH_JAVA17_HOME:-}"; then
    printf '%s\n' "$STOCKRUSH_JAVA17_HOME"
    return
  fi

  if is_java17_home "${JAVA_HOME:-}"; then
    printf '%s\n' "$JAVA_HOME"
    return
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local mac_java_home
    if mac_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null)" && is_java17_home "$mac_java_home"; then
      printf '%s\n' "$mac_java_home"
      return
    fi
  fi

  for candidate in \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/temurin-17-jdk-amd64 \
    /usr/lib/jvm/msopenjdk-17-amd64
  do
    if is_java17_home "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  done

  return 1
}

JAVA17_HOME="$(find_java17_home)" || {
  printf 'Java 17 JDK not found.\n' >&2
  printf 'Set STOCKRUSH_JAVA17_HOME to the installed JDK 17 path and retry.\n' >&2
  exit 1
}

export JAVA_HOME="$JAVA17_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

exec "$@"
