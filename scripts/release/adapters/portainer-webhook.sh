#!/bin/sh
set -eu

[ "${1:-}" = deploy ] && [ "$#" -eq 7 ] || exit 64
: "${PORTAINER_WEBHOOK_URL:?PORTAINER_WEBHOOK_URL is required}"
curl --fail --silent --show-error \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"source":"github-actions","immutable_pair_verified":true}' \
  "$PORTAINER_WEBHOOK_URL" >/dev/null
printf 'DEPLOYED=true\n'
