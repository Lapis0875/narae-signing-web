#!/bin/sh
set -eu

[ "${1:-}" = resolve ] && [ "$#" -eq 2 ] || exit 64
tag=$2
case "$tag" in *[!A-Za-z0-9._-]*|'') exit 64 ;; esac

git status --porcelain | grep -q . && clean=false || clean=true
local_type=$(git cat-file -t "refs/tags/$tag" 2>/dev/null || true)
[ "$local_type" = tag ] || exit 1
local_object=$(git rev-parse --verify "refs/tags/$tag" 2>/dev/null) || exit 1
local_commit=$(git rev-parse --verify "refs/tags/$tag^{commit}" 2>/dev/null) || exit 1

remote=$(git ls-remote --exit-code origin "refs/tags/$tag" "refs/tags/$tag^{}" 2>/dev/null) || exit 1
remote_object=$(printf '%s\n' "$remote" | awk -v ref="refs/tags/$tag" '$2 == ref { print $1 }')
remote_commit=$(printf '%s\n' "$remote" | awk -v ref="refs/tags/$tag^{}" '$2 == ref { print $1 }')
[ "$(printf '%s\n' "$remote_object" | wc -l | tr -d ' ')" -eq 1 ] || exit 1
[ "$(printf '%s\n' "$remote_commit" | wc -l | tr -d ' ')" -eq 1 ] || exit 1
if [ "$local_object" = "$remote_object" ] && [ "$local_commit" = "$remote_commit" ]; then
  local_match=true
else
  local_match=false
fi

printf 'TAG_OBJECT=%s\nPEELED_COMMIT=%s\nWORKTREE_CLEAN=%s\nLOCAL_MATCH=%s\n' \
  "$remote_object" "$remote_commit" "$clean" "$local_match"
