#!/bin/sh
set -eu

commit=HEAD
if [ "${1:-}" = "--commit" ] && [ "$#" -eq 2 ]; then
  commit=$2
elif [ "$#" -ne 0 ]; then
  echo "usage: $0 [--commit <sha>]" >&2
  exit 2
fi

git rev-parse --verify "${commit}^{commit}" >/dev/null
matches=$(git grep -I -l -E '(-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|(password|secret|token|api[_-]?key)[[:space:]]*[:=][[:space:]]*[A-Za-z0-9+/_.-]{12,})' "$commit" -- . ':(exclude)docs/**' ':(exclude).omo/**' || true)
if [ -n "$matches" ]; then
  echo "potential tracked secret detected (values redacted):" >&2
  printf '%s\n' "$matches" | sed "s#^${commit}:##" >&2
  exit 1
fi
echo "tracked secret scan: clean"
