#!/bin/sh
set -eu

[ "${1:-}" = build ] && [ "$#" -eq 5 ] || exit 64
component=$2
image=$(printf '%s' "$3" | tr '[:upper:]' '[:lower:]')
tag=$4
revision=$5
build_root=$(mktemp -d)
trap 'rm -rf "$build_root"' EXIT HUP INT TERM
case "$component" in
  frontend)
    dockerfile=$build_root/frontend.Dockerfile
    sed 's#^COPY \.\./infra/#COPY infra/#' frontend/Dockerfile >"$dockerfile"
    context=.
    ;;
  backend) dockerfile=backend/Dockerfile; context=backend ;;
  *) exit 64 ;;
esac

metadata=$build_root/metadata.json
docker buildx build \
  --file "$dockerfile" \
  --label "org.opencontainers.image.revision=$revision" \
  --label "org.opencontainers.image.version=$tag" \
  --tag "$image:$tag" \
  --push \
  --metadata-file "$metadata" \
  "$context" >/dev/null 2>&1
digest=$(sed -n 's/.*"containerimage.digest"[[:space:]]*:[[:space:]]*"\(sha256:[0-9a-f]*\)".*/\1/p' "$metadata")
printf 'DIGEST=%s\nREVISION=%s\n' "$digest" "$revision"
