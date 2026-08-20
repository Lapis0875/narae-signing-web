#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
runner="$repo_root/scripts/release/run.sh"
rollback="$repo_root/scripts/release/validate-rollback.sh"
fake_root="$repo_root/scripts/release/test/fakes"
fixture_root=$(mktemp -d)
trap 'rm -rf "$fixture_root"' EXIT HUP INT TERM
evidence_dir=${EVIDENCE_DIR:-$fixture_root/evidence}
mkdir -p "$evidence_dir"
success_log="$evidence_dir/task-29-release-delivery.log"
error_log="$evidence_dir/task-29-release-delivery-error.log"
: >"$success_log"
: >"$error_log"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_no_delivery() {
  ledger=$1
  ! grep -q '^portainer\.deploy ' "$ledger" || fail "webhook called after rejected fixture"
}

run_fixture() {
  ledger=$1
  state=$2
  shift 2
  FAKE_LEDGER="$ledger" \
    FAKE_STATE_DIR="$state" \
    GIT_ADAPTER="$fake_root/git-adapter.sh" \
    REGISTRY_ADAPTER="$fake_root/registry-adapter.sh" \
    PORTAINER_ADAPTER="$fake_root/portainer-adapter.sh" \
    "$runner" "$@"
}

new_case() {
  case_name=$1
  case_dir="$fixture_root/$case_name"
  mkdir -p "$case_dir/state"
  ledger="$case_dir/ledger"
  : >"$ledger"
}

expect_rejected() {
  case_name=$1
  git_mode=$2
  registry_mode=$3
  tag=$4
  new_case "$case_name"
  output="$case_dir/output"
  if FAKE_GIT_MODE="$git_mode" FAKE_REGISTRY_MODE="$registry_mode" \
      run_fixture "$ledger" "$case_dir/state" --tag "$tag" >"$output" 2>&1; then
    fail "$case_name unexpectedly passed"
  fi
  assert_no_delivery "$ledger"
  if grep -Eqi '(fixture-secret-value|password[=:]|secret[=:]|token[=:])' "$output"; then
    fail "$case_name leaked secret-like output"
  fi
  printf 'PASS negative=%s exit=nonzero webhook=0 output=redacted\n' "$case_name" >>"$error_log"
}

[ -x "$runner" ] || fail "release runner missing"
[ -x "$rollback" ] || fail "rollback validator missing"

new_case success
run_fixture "$ledger" "$case_dir/state" --tag v0.1.0 >"$case_dir/output" 2>&1
expected="$case_dir/expected"
printf '%s\n' \
  'git.resolve v0.1.0' \
  'git.resolve v0.1.0' \
  'registry.build frontend ghcr.io/example/narae-signing-frontend v0.1.0 1111111111111111111111111111111111111111' \
  'registry.build backend ghcr.io/example/narae-signing-backend v0.1.0 1111111111111111111111111111111111111111' \
  'git.resolve v0.1.0' \
  'portainer.deploy v0.1.0 1111111111111111111111111111111111111111 ghcr.io/example/narae-signing-frontend sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ghcr.io/example/narae-signing-backend sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
  >"$expected"
cmp "$expected" "$ledger" || fail "ordered delivery ledger mismatch"
tail -n 1 "$ledger" | grep -q '^portainer\.deploy ' || fail "webhook was not last"
printf 'PASS tag=v0.1.0 peeled_sha=1111111111111111111111111111111111111111 revisions=match webhook=last\n' >>"$success_log"
sed 's/^/LEDGER /' "$ledger" >>"$success_log"

new_case dry_run
run_fixture "$ledger" "$case_dir/state" --tag v0.1.0 --dry-run >"$case_dir/output" 2>&1
grep -q '^git\.resolve ' "$ledger" || fail "dry run skipped tag validation"
if grep -Eq '^(registry|portainer)\.' "$ledger"; then
  fail "dry run made external registry or webhook call"
fi
printf 'PASS dry_run=true registry_calls=0 webhook_calls=0\n' >>"$success_log"

new_case rollback
FAKE_LEDGER="$ledger" FAKE_STATE_DIR="$case_dir/state" GIT_ADAPTER="$fake_root/git-adapter.sh" \
  "$rollback" v0.2.0 v0.1.0 >"$case_dir/output" 2>&1
grep -q 'ROLLBACK_TARGET=1111111111111111111111111111111111111111' "$case_dir/output" || fail "rollback target not peeled"
printf 'PASS rollback current=v0.2.0 selected=v0.1.0 selection=immutable-and-lower\n' >>"$success_log"

expect_rejected non_v good good release-1
expect_rejected missing missing good v0.1.0
expect_rejected unpeeled lightweight good v0.1.0
expect_rejected moved moved good v0.1.0
expect_rejected stale stale good v0.1.0
expect_rejected dirty dirty good v0.1.0
expect_rejected frontend_failure good fail_frontend v0.1.0
expect_rejected backend_failure good fail_backend v0.1.0
expect_rejected malformed_digest good bad_digest v0.1.0
expect_rejected digest_mismatch good same_digest v0.1.0
expect_rejected revision_mismatch good bad_revision v0.1.0
expect_rejected secret_output good secret v0.1.0
expect_rejected misleading_success good misleading v0.1.0

new_case rollback_higher
if FAKE_LEDGER="$ledger" FAKE_STATE_DIR="$case_dir/state" GIT_ADAPTER="$fake_root/git-adapter.sh" \
    "$rollback" v0.1.0 v0.2.0 >"$case_dir/output" 2>&1; then
  fail "higher rollback tag unexpectedly passed"
fi
printf 'PASS negative=rollback_not_lower exit=nonzero\n' >>"$error_log"

workflow="$repo_root/.github/workflows/release.yml"
verifier="$repo_root/scripts/verify-workflows.sh"
malformed_workflow="$fixture_root/actionlint-malformed.yml"
printf '%s\n' \
  'name: malformed actionlint fixture' \
  'on: [push]' \
  'jobs:' \
  '  red:' \
  '    runs-on: ubuntu-latest' \
  '    steps:' \
  '      - run: echo "${{ definitely_not_a_context.value }}"' \
  >"$malformed_workflow"
red_stdout="$fixture_root/actionlint-red.stdout"
red_stderr="$fixture_root/actionlint-red.stderr"
if (CDPATH= cd -- "$fixture_root" && "$verifier" actionlint-malformed.yml) >"$red_stdout" 2>"$red_stderr"; then
  fail "actionlint accepted malformed disposable workflow"
else
  red_exit=$?
fi

green_stdout="$fixture_root/actionlint-green.stdout"
green_stderr="$fixture_root/actionlint-green.stderr"
if (CDPATH= cd -- "$repo_root" && ./scripts/verify-workflows.sh .github/workflows/release.yml) >"$green_stdout" 2>"$green_stderr"; then
  green_exit=0
else
  green_exit=$?
  fail "repository workflow verifier rejected release workflow (exit $green_exit)"
fi
workflow_lint_log="$evidence_dir/task-29-workflow-lint.log"
{
  echo 'RED_INVOCATION=(cd <fixture-root> && scripts/verify-workflows.sh actionlint-malformed.yml)'
  echo "RED_EXIT=$red_exit"
  echo 'RED_STDOUT_BEGIN'
  sed 's/^/  /' "$red_stdout"
  echo 'RED_STDOUT_END'
  echo 'RED_STDERR_BEGIN'
  sed 's/^/  /' "$red_stderr"
  echo 'RED_STDERR_END'
  echo 'GREEN_INVOCATION=./scripts/verify-workflows.sh .github/workflows/release.yml'
  echo "GREEN_EXIT=$green_exit"
  echo 'GREEN_STDOUT_BEGIN'
  sed 's/^/  /' "$green_stdout"
  echo 'GREEN_STDOUT_END'
  echo 'GREEN_STDERR_BEGIN'
  sed 's/^/  /' "$green_stderr"
  echo 'GREEN_STDERR_END'
} >"$workflow_lint_log"

ruby -e 'require "yaml"; YAML.safe_load(File.read(ARGV.fetch(0)), permitted_classes: [], aliases: true)' "$workflow"
ruby -e 'require "yaml"; data=YAML.safe_load(File.read(ARGV.fetch(0)), aliases: true); abort "missing compose services" unless data.fetch("services").key?("frontend") && data.fetch("services").key?("backend")' "$repo_root/infra/compose/compose.yml"
uses_count=$(grep -Ec '^[[:space:]]+uses:' "$workflow")
pinned_count=$(grep -Ec '^[[:space:]]+uses:[[:space:]]+[^[:space:]@]+@[0-9a-f]{40}([[:space:]]|$)' "$workflow")
[ "$uses_count" -eq "$pinned_count" ] || fail "workflow contains unpinned third-party action"
grep -q '^  workflow_dispatch:' "$workflow" || fail "workflow is not manual dispatch"
grep -q '^  contents: read$' "$workflow" || fail "workflow lacks minimum contents permission"
grep -q '^      packages: write$' "$workflow" || fail "release job lacks scoped package permission"
grep -q 'dry_run' "$workflow" || fail "workflow lacks no-push dry-run"
grep -q 'PORTAINER_WEBHOOK_URL' "$workflow" || fail "workflow lacks protected webhook binding"
proxy_guide="$repo_root/docs/REVERSE_PROXY_SETUP_GUIDE.md"
tablet_checklist="$repo_root/docs/qa/MVP_TABLET_RELEASE_CHECKLIST.md"
portainer_contract="$repo_root/infra/portainer/README.md"
grep -q 'proxy_request_buffering on;' "$proxy_guide" || fail "proxy guide disables mutation request buffering"
grep -q 'X-Accel-Buffering: no' "$proxy_guide" || fail "proxy guide lacks SSE no-buffer validation"
grep -q 'Secure; HttpOnly; SameSite=Lax' "$proxy_guide" || fail "proxy guide lacks Secure cookie validation"
grep -q 'iPadOS 16+ Safari' "$tablet_checklist" || fail "tablet checklist lacks iPadOS physical gate"
grep -q 'Android 12+ Chrome' "$tablet_checklist" || fail "tablet checklist lacks Android physical gate"
grep -q 'Keep both GHCR packages private' "$portainer_contract" || fail "Portainer contract lacks private package gate"
grep -q 'no down migration' "$portainer_contract" || fail "Portainer contract lacks rollback migration boundary"
printf 'PASS workflow_verifier=actionlint red_exit=%s green_exit=0 artifact=%s\n' "$red_exit" "$workflow_lint_log" >>"$success_log"
printf 'PASS workflow_supplement=yaml-and-pinned-actions compose_config=parse-only docker_network_created=0\n' >>"$success_log"
printf 'PASS docs=https-forwarded-headers-secure-cookie-sse physical-tablet-matrix rollback=forward-only\n' >>"$success_log"

echo "release delivery fixture suite: PASS"
echo "success evidence: $success_log"
echo "negative evidence: $error_log"
