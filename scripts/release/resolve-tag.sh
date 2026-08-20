#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
. "$script_dir/lib.sh"
[ "$#" -eq 1 ] || release_fail "usage: resolve-tag.sh vX.Y.Z"
validate_release_tag "$1"
output=$(mktemp)
trap 'rm -f "$output"' EXIT HUP INT TERM
resolve_release_tag "$1" "$output" "${GIT_ADAPTER:-$script_dir/adapters/git-origin.sh}"
printf '%s\n' "$peeled_commit"
