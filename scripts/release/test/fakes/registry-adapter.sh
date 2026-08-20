#!/bin/sh
set -eu

[ "${1:-}" = build ] && [ "$#" -eq 5 ] || exit 64
component=$2
image=$3
tag=$4
revision=$5
printf 'registry.build %s %s %s %s\n' "$component" "$image" "$tag" "$revision" >>"$FAKE_LEDGER"

case "${FAKE_REGISTRY_MODE:-good}" in
  "fail_$component") exit 1 ;;
  secret)
    printf '%s%s\n' 'to' 'ken=fixture-secret-value'
    exit 1
    ;;
  misleading)
    echo 'DEPLOYED=true'
    exit 0
    ;;
esac

case "$component" in
  frontend) digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;
  backend) digest=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ;;
  *) exit 64 ;;
esac

if [ "${FAKE_REGISTRY_MODE:-good}" = bad_digest ] && [ "$component" = backend ]; then
  digest=not-a-digest
fi
if [ "${FAKE_REGISTRY_MODE:-good}" = same_digest ] && [ "$component" = backend ]; then
  digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
fi
if [ "${FAKE_REGISTRY_MODE:-good}" = bad_revision ] && [ "$component" = backend ]; then
  revision=2222222222222222222222222222222222222222
fi
printf 'DIGEST=sha256:%s\nREVISION=%s\n' "$digest" "$revision"
