#!/bin/sh
set -eu

[ "${1:-}" = deploy ] && [ "$#" -eq 7 ] || exit 64
printf 'portainer.deploy %s %s %s %s %s %s\n' "$2" "$3" "$4" "$5" "$6" "$7" >>"$FAKE_LEDGER"
printf 'DEPLOYED=true\n'
