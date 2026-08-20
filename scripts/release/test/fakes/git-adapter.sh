#!/bin/sh
set -eu

[ "${1:-}" = resolve ] && [ "$#" -eq 2 ] || exit 64
tag=$2
printf 'git.resolve %s\n' "$tag" >>"$FAKE_LEDGER"

case "${FAKE_GIT_MODE:-good}" in
  good)
    if [ "$tag" = v0.2.0 ]; then
      object=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
      commit=2222222222222222222222222222222222222222
    else
      object=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
      commit=1111111111111111111111111111111111111111
    fi
    ;;
  missing) exit 1 ;;
  lightweight)
    object=1111111111111111111111111111111111111111
    commit=$object
    ;;
  dirty)
    object=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    commit=1111111111111111111111111111111111111111
    clean=false
    ;;
  stale)
    object=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    commit=1111111111111111111111111111111111111111
    local_match=false
    ;;
  moved)
    count_file=${FAKE_STATE_DIR:?}/git-count
    count=0
    [ ! -f "$count_file" ] || count=$(sed -n '1p' "$count_file")
    count=$((count + 1))
    printf '%s\n' "$count" >"$count_file"
    if [ "$count" -eq 1 ]; then
      object=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
      commit=1111111111111111111111111111111111111111
    else
      object=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
      commit=2222222222222222222222222222222222222222
    fi
    ;;
  *) exit 64 ;;
esac

printf 'TAG_OBJECT=%s\nPEELED_COMMIT=%s\nWORKTREE_CLEAN=%s\nLOCAL_MATCH=%s\n' \
  "$object" "$commit" "${clean:-true}" "${local_match:-true}"
