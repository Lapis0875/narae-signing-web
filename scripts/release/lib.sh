#!/bin/sh

release_fail() {
  echo "release gate rejected: $*" >&2
  exit 1
}

validate_release_tag() {
  printf '%s\n' "$1" | grep -Eq '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' || \
    release_fail "tag must match vX.Y.Z"
}

reject_sensitive_output() {
  file=$1
  if grep -Eqi '(password|secret|token|api[_-]?key)[[:space:]]*[:=]' "$file"; then
    release_fail "adapter output contained secret-like data (redacted)"
  fi
}

run_captured() {
  output_file=$1
  shift
  if "$@" >"$output_file" 2>&1; then
    reject_sensitive_output "$output_file"
    return 0
  fi
  reject_sensitive_output "$output_file"
  release_fail "adapter failed"
}

read_field() {
  field=$1
  file=$2
  value=$(sed -n "s/^${field}=//p" "$file")
  [ -n "$value" ] || release_fail "adapter omitted $field"
  [ "$(printf '%s\n' "$value" | wc -l | tr -d ' ')" -eq 1 ] || release_fail "adapter repeated $field"
  printf '%s\n' "$value"
}

is_sha40() {
  printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{40}$'
}

is_digest() {
  printf '%s\n' "$1" | grep -Eq '^sha256:[0-9a-f]{64}$'
}

resolve_release_tag() {
  tag=$1
  output=$2
  adapter=$3
  run_captured "$output" "$adapter" resolve "$tag"
  [ "$(wc -l <"$output" | tr -d ' ')" -eq 4 ] || release_fail "unexpected Git adapter output"
  tag_object=$(read_field TAG_OBJECT "$output")
  peeled_commit=$(read_field PEELED_COMMIT "$output")
  worktree_clean=$(read_field WORKTREE_CLEAN "$output")
  local_match=$(read_field LOCAL_MATCH "$output")
  is_sha40 "$tag_object" || release_fail "tag object is not an immutable SHA"
  is_sha40 "$peeled_commit" || release_fail "peeled commit is not an immutable SHA"
  [ "$tag_object" != "$peeled_commit" ] || release_fail "release tag must be annotated and peel to a commit"
  [ "$worktree_clean" = true ] || release_fail "worktree or index is dirty"
  [ "$local_match" = true ] || release_fail "local tag is stale relative to origin"
}
