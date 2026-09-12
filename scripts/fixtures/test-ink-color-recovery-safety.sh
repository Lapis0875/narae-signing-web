#!/usr/bin/env bash
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
[[ $# == 2 && "$1" == --evidence-dir ]] || { printf 'usage: %s --evidence-dir PATH\n' "$0" >&2; exit 2; }
exec bash "$root/scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh" \
    --recovery-registration-tests --evidence-dir "$2"
