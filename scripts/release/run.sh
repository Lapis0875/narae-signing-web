#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
. "$script_dir/lib.sh"

tag=
dry_run=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --tag)
      [ "$#" -ge 2 ] || release_fail "--tag requires a value"
      tag=$2
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    *) release_fail "unknown argument: $1" ;;
  esac
done
[ -n "$tag" ] || release_fail "--tag is required"
validate_release_tag "$tag"

git_adapter=${GIT_ADAPTER:-$script_dir/adapters/git-origin.sh}
registry_adapter=${REGISTRY_ADAPTER:-$script_dir/adapters/registry-ghcr.sh}
portainer_adapter=${PORTAINER_ADAPTER:-$script_dir/adapters/portainer-webhook.sh}
image_prefix=${RELEASE_IMAGE_PREFIX:-ghcr.io/example/narae-signing}
frontend_image="${image_prefix}-frontend"
backend_image="${image_prefix}-backend"

run_root=$(mktemp -d)
trap 'rm -rf "$run_root"' EXIT HUP INT TERM

resolve_release_tag "$tag" "$run_root/git-first" "$git_adapter"
first_object=$tag_object
first_commit=$peeled_commit
resolve_release_tag "$tag" "$run_root/git-confirm" "$git_adapter"
[ "$tag_object" = "$first_object" ] && [ "$peeled_commit" = "$first_commit" ] || \
  release_fail "tag moved between immutable preflight reads"

if [ -n "${EXPECTED_RELEASE_COMMIT:-}" ] && [ "$first_commit" != "$EXPECTED_RELEASE_COMMIT" ]; then
  release_fail "peeled tag does not match authorized workflow commit"
fi

if [ "$dry_run" = true ]; then
  echo "DRY_RUN=true"
  echo "RELEASE_TAG=$tag"
  echo "RELEASE_COMMIT=$first_commit"
  echo "EXTERNAL_CALLS=0"
  exit 0
fi

run_captured "$run_root/frontend" "$registry_adapter" build frontend "$frontend_image" "$tag" "$first_commit"
[ "$(wc -l <"$run_root/frontend" | tr -d ' ')" -eq 2 ] || release_fail "unexpected frontend registry output"
frontend_digest=$(read_field DIGEST "$run_root/frontend")
frontend_revision=$(read_field REVISION "$run_root/frontend")
is_digest "$frontend_digest" || release_fail "frontend digest is invalid"
[ "$frontend_revision" = "$first_commit" ] || release_fail "frontend revision does not match peeled commit"

run_captured "$run_root/backend" "$registry_adapter" build backend "$backend_image" "$tag" "$first_commit"
[ "$(wc -l <"$run_root/backend" | tr -d ' ')" -eq 2 ] || release_fail "unexpected backend registry output"
backend_digest=$(read_field DIGEST "$run_root/backend")
backend_revision=$(read_field REVISION "$run_root/backend")
is_digest "$backend_digest" || release_fail "backend digest is invalid"
[ "$backend_revision" = "$first_commit" ] || release_fail "backend revision does not match peeled commit"
[ "$frontend_revision" = "$backend_revision" ] || release_fail "image revisions do not match"
[ "$frontend_digest" != "$backend_digest" ] || release_fail "frontend and backend digests unexpectedly match"

resolve_release_tag "$tag" "$run_root/git-final" "$git_adapter"
[ "$tag_object" = "$first_object" ] && [ "$peeled_commit" = "$first_commit" ] || \
  release_fail "tag moved during image publication"

run_captured "$run_root/portainer" "$portainer_adapter" deploy "$tag" "$first_commit" \
  "$frontend_image" "$frontend_digest" "$backend_image" "$backend_digest"
[ "$(wc -l <"$run_root/portainer" | tr -d ' ')" -eq 1 ] || release_fail "unexpected Portainer adapter output"
[ "$(read_field DEPLOYED "$run_root/portainer")" = true ] || release_fail "Portainer did not confirm deployment"

echo "RELEASE_TAG=$tag"
echo "RELEASE_COMMIT=$first_commit"
echo "FRONTEND_DIGEST=$frontend_digest"
echo "BACKEND_DIGEST=$backend_digest"
echo "PORTAINER=invoked-last"
