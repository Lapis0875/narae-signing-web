#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
. "$script_dir/lib.sh"
[ "$#" -eq 2 ] || release_fail "usage: validate-rollback.sh CURRENT_TAG ROLLBACK_TAG"
current=$1
selected=$2
validate_release_tag "$current"
validate_release_tag "$selected"
[ "$current" != "$selected" ] || release_fail "rollback tag must differ from current tag"

version_key() {
  printf '%s\n' "$1" | sed 's/^v//' | awk -F. '{ printf "%012d%012d%012d\n", $1, $2, $3 }'
}
[ "$(version_key "$selected")" \< "$(version_key "$current")" ] || release_fail "rollback tag must be lower than current tag"

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT HUP INT TERM
adapter=${GIT_ADAPTER:-$script_dir/adapters/git-origin.sh}
resolve_release_tag "$current" "$root/current" "$adapter"
current_commit=$peeled_commit
resolve_release_tag "$selected" "$root/selected" "$adapter"
selected_commit=$peeled_commit
[ "$current_commit" != "$selected_commit" ] || release_fail "rollback tag must select a different commit"
echo "ROLLBACK_TAG=$selected"
echo "ROLLBACK_TARGET=$selected_commit"
