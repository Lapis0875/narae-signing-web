#!/bin/sh
set -eu
# allow: SIZE_OK — one fail-safe state machine owns suspension, isolated verification, and exact restoration.

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
base=905014d0a78f12060e69872fd1fc21bed38e8453
candidate_base=4a48dc56428ff620b41d187b69ebe7d80d3a7ce9
repair_fixture=d4649dbf72532382541ace39f9285e7ff3b611c4
repair_snapshot=14477473410c38dd8778405978fed8dd3b81f649
mode=${1:---dry-run}
compose_source="$root/infra/compose/compose.yml"
fixture="$root/scripts/fixtures/mvp-roster.csv"
playwright_template="$root/scripts/fixtures/playwright.real.config.mjs.template"
live_runner="$root/scripts/fixtures/task30-live-run.py"
work= data_root= evidence= project= compose_file= original_ids= original_running_ids= run_id= playwright_dir=
compose_cleanup_required=false

fail() { echo "FAIL: $*" >&2; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"; }
run_bounded() {
    seconds=$1
    shift
    python3 "$live_runner" --timeout "$seconds" "$@"
}

validate_tracked_secrets() {
    scan_log=$(mktemp "${TMPDIR:-/tmp}/narae-task30-scan.XXXXXX")
    trace_enabled=false
    case $- in *x*) trace_enabled=true; set +x;; esac
    if run_bounded 120 "$root/scripts/scan-tracked-secrets.sh" > "$scan_log" 2>&1; then
        scan_status=0
    else
        scan_status=$?
    fi
    [ "$trace_enabled" = false ] || set -x
    if [ "$scan_status" -eq 0 ]; then
        echo "CHECK: tracked-secret scan is clean"
    else
        [ "$scan_status" -eq 1 ] || fail "tracked-secret scanner failed with status $scan_status"
        [ "$(sed -n '1p' "$scan_log")" = "potential tracked secret detected (values redacted):" ] \
            || fail "tracked-secret scanner returned an unknown error"
        findings=$(sed '1d' "$scan_log")
        [ -n "$findings" ] || fail "tracked-secret scanner failed without filenames"
        printf '%s\n' "$findings" | while IFS= read -r finding; do
            git -C "$root" ls-files --error-unmatch "$finding" >/dev/null 2>&1 \
                || fail "tracked-secret scanner returned a non-file finding"
            git -C "$root" diff --quiet "$base" -- "$finding" \
                || fail "tracked-secret finding is new or modified: $finding"
        done
        echo "CHECK: tracked-secret findings are unchanged from the coordinator baseline"
    fi
    rm -f "$scan_log"
}

validate_static() {
    for command in awk git grep node npm python3 sed; do require_command "$command"; done
    [ -x "$root/backend/gradlew" ] || fail "backend/gradlew is not executable"
    for input in "$compose_source" "$fixture" "$playwright_template" "$live_runner"; do
        [ -f "$input" ] || fail "required input is missing: $input"
    done
    [ "$(sed -n '1p' "$fixture")" = "organization,job,name" ] || fail "fixture header is invalid"
    [ "$(awk -F, 'NR > 1 && NF == 3 { count++ } END { print count + 0 }' "$fixture")" -gt 0 ] \
        || fail "fixture has no three-column synthetic row"
    if grep -Eiq '(password|secret|token|api[_-]?key|@gmail\.|@naver\.|@daum\.)' "$fixture"; then
        fail "fixture contains a credential marker or personal email domain"
    fi
    [ "$(node -p "process.versions.node.split('.')[0]")" = 22 ] || fail "Node 22 is required"
    python3 -c 'import pathlib,sys; compile(pathlib.Path(sys.argv[1]).read_bytes(), sys.argv[1], "exec")' "$live_runner"
    python3 "$live_runner" --self-test >/dev/null
    validate_tracked_secrets
}

project_resources() {
    if ! project_resource_containers=$(run_bounded 30 docker ps -aq --filter "label=com.docker.compose.project=$project"); then
        return 1
    fi
    if ! project_resource_networks=$(run_bounded 30 docker network ls -q --filter "label=com.docker.compose.project=$project"); then
        return 1
    fi
    if ! project_resource_volumes=$(run_bounded 30 docker volume ls -q --filter "label=com.docker.compose.project=$project"); then
        return 1
    fi
    printf '%s\n%s\n%s\n' "$project_resource_containers" "$project_resource_networks" "$project_resource_volumes"
}

require_project_absent() {
    if ! project_resource_facts=$(project_resources); then
        fail "isolated Compose project resource query failed"
    fi
    [ -z "$project_resource_facts" ] || fail "isolated Compose project already has resources"
}

health_facts() {
    target=$1
    if ! listener=$(run_bounded 30 docker ps -q --filter publish=18083); then
        return 1
    fi
    [ "$(printf '%s\n' "$listener" | awk 'NF { count++ } END { print count + 0 }')" -eq 1 ] || return 1
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:18083/health) || return 1
    [ "$code" = 200 ] || return 1
    printf '%s\t%s\t%s\n' "$listener" "http://127.0.0.1:18083/health" "$code" > "$target"
}

snapshot_originals() {
    target=$1
    raw="$work/original-inspect.json"
    if ! run_bounded 60 docker inspect $original_ids > "$raw"; then
        return 1
    fi
    if ! run_bounded 30 node -e '
const crypto = require("node:crypto"); let raw=""; process.stdin.on("data", c => raw += c); process.stdin.on("end", () => {
  const hash = value => crypto.createHash("sha256").update(value).digest("hex");
  const rows = JSON.parse(raw).map(c => ({
    health: c.State.Health?.Status ?? "none", id: c.Id, imageId: c.Image,
    mounts: c.Mounts.map(m => ({ destination: m.Destination, mode: m.Mode, rw: m.RW,
      sourceHash: hash(m.Source), type: m.Type })).sort((a,b) => a.destination.localeCompare(b.destination)),
    name: c.Name, networks: Object.values(c.NetworkSettings.Networks).map(n => ({
      ip: n.IPAddress, networkId: n.NetworkID
    })).sort((a,b) => a.networkId.localeCompare(b.networkId)),
    running: c.State.Running
  })).sort((a,b) => a.id.localeCompare(b.id)); process.stdout.write(JSON.stringify(rows) + "\n");
});' < "$raw" > "$target"
    then
        return 1
    fi
}

testcontainers_snapshot() {
    target=$1
    if ! testcontainers_containers=$(run_bounded 30 docker ps -aq --filter label=org.testcontainers=true); then
        return 1
    fi
    if ! testcontainers_networks=$(run_bounded 30 docker network ls -q --filter label=org.testcontainers=true); then
        return 1
    fi
    if ! testcontainers_volumes=$(run_bounded 30 docker volume ls -q --filter label=org.testcontainers=true); then
        return 1
    fi
    printf '%s\n%s\n%s\n' "$testcontainers_containers" "$testcontainers_networks" "$testcontainers_volumes" | sort > "$target"
}

scan_retained_evidence() {
    target=${1:-$evidence}
    [ -e "$target" ] || return 0
    run_bounded 60 python3 -c '
import os, pathlib, sys
target = pathlib.Path(sys.argv[1])
needles = [os.environ[name].encode() for name in (
    "POSTGRES_USER", "POSTGRES_PASSWORD", "MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD"
) if os.environ.get(name)]
needles += [value.encode() for value in (
    "XSRF-TOKEN", "shareToken", "Set-Cookie:", "Password:",
    "synthetic-password-phrase", "synthetic-task30-share", "synthetic-csrf", "task30@example.invalid"
)]
for path in (target,) if target.is_file() else target.rglob("*"):
    if path.is_file():
        data = path.read_bytes()
        if any(needle in data for needle in needles):
            raise SystemExit(1)
' "$target"
}

validate_backend_receipt() {
    [ -f "$backend_receipt" ] || fail "MISSING_CANDIDATE_RECEIPT"
    [ ! -L "$backend_receipt" ] || fail "STALE_AGGREGATION"
    [ "$(wc -c < "$backend_receipt" | tr -d ' ')" -eq 76 ] \
        || fail "STALE_AGGREGATION"
    [ "$(cat "$backend_receipt")" = "candidate=$candidate
attempt=$run_id" ] || fail "STALE_AGGREGATION"
}

restore_and_cleanup() {
    incoming=$1
    trap - EXIT
    trap '' HUP INT TERM
    set +e
    cleanup_failed=false
    compose_clean=true
    if [ "$compose_cleanup_required" = true ] && [ -n "$project" ] && [ -n "$compose_file" ]; then
        run_bounded 300 docker compose -p "$project" -f "$compose_file" down --volumes --remove-orphans \
            > "${evidence:-/dev/null}/compose-down.log" 2>&1
        down_status=$?
        if [ "$down_status" -eq 0 ]; then
            if ! remaining_project_resources=$(project_resources); then
                cleanup_failed=true
                compose_clean=false
            elif [ -n "$remaining_project_resources" ]; then
                cleanup_failed=true
                compose_clean=false
            fi
        else
            cleanup_failed=true
            compose_clean=false
        fi
    fi
    if [ -n "$original_ids" ]; then
        for id in $original_running_ids; do
            run_bounded 120 docker start "$id" >/dev/null 2>&1 || cleanup_failed=true
        done
        recovered=false attempt=0
        while [ "$attempt" -lt 30 ]; do
            if health_facts "$work/original-after-health.tsv"; then recovered=true; break; fi
            attempt=$((attempt + 1)); sleep 2
        done
        [ "$recovered" = true ] || cleanup_failed=true
        snapshot_originals "$work/original-after.json" || cleanup_failed=true
        cmp -s "$work/original-before.json" "$work/original-after.json" || cleanup_failed=true
        cmp -s "$work/original-before-health.tsv" "$work/original-after-health.tsv" || cleanup_failed=true
        if [ "$cleanup_failed" = false ]; then
            cp "$work/original-after.json" "$evidence/original-after.json"
            cp "$work/original-after-health.tsv" "$evidence/original-after-health.tsv"
            echo "PASS: exact original snapshot and HTTP 200 health restored" > "$evidence/restore.log"
        fi
    fi
    scan_retained_evidence || cleanup_failed=true
    if [ -n "$playwright_dir" ]; then
        case "$playwright_dir" in "$root"/frontend/node_modules/.cache/task30-*)
            [ -f "$playwright_dir/.task30-owned" ] \
                && [ "$(sed -n '1p' "$playwright_dir/.task30-owned")" = "$run_id" ] \
                && rm -rf -- "$playwright_dir" || cleanup_failed=true;;
        *) cleanup_failed=true;; esac
    fi
    if [ "$cleanup_failed" = false ] && [ "$compose_clean" = true ] \
            && [ -n "$data_root" ] && [ ! -L "$data_root" ] && [ -f "$data_root/.task30-owned" ] \
            && [ "$(sed -n '1p' "$data_root/.task30-owned")" = "$run_id" ]; then
        case "$data_root" in /private/tmp/narae-task30-data.*) rm -rf -- "$data_root";; *) cleanup_failed=true;; esac
    fi
    if [ "$cleanup_failed" = false ] && [ -n "$work" ] && [ ! -L "$work" ] && [ -f "$work/.task30-owned" ]; then
        case "$work" in /private/tmp/narae-task30-verify.*) rm -rf -- "$work";; *) cleanup_failed=true;; esac
    fi
    if [ "$cleanup_failed" = true ]; then
        [ "$incoming" -ne 0 ] && exit "$incoming"
        exit 97
    fi
    if [ "$incoming" -eq 0 ]; then
        echo "PASS: full Task30 matrix, isolated cleanup, exact restoration, final redaction, and /health HTTP 200"
    fi
    exit "$incoming"
}

validate_execute() {
    [ "${TASK30_DOCKER_AUTHORITY:-}" = approved ] || fail "set TASK30_DOCKER_AUTHORITY=approved for --execute"
    for command in curl docker openssl; do require_command "$command"; done
    candidate=${TASK30_CANDIDATE_SHA:-}
    [ "${#candidate}" -eq 40 ] || fail "candidate SHA must be a full lowercase commit SHA"
    case "$candidate" in *[!0-9a-f]*) fail "candidate SHA must be a full lowercase commit SHA";; esac
    git -C "$root" cat-file -e "$candidate^{commit}" 2>/dev/null || fail "candidate SHA is not a commit"
    [ "$(git -C "$root" rev-parse --verify HEAD^{commit})" = "$candidate" ] || fail "candidate SHA does not match HEAD"
    main_candidate=$(git -C "$root" rev-parse --verify refs/heads/main^{commit} 2>/dev/null) \
        || fail "refs/heads/main is not a commit"
    [ "$main_candidate" = "$candidate" ] || fail "candidate is not refs/heads/main"
    git -C "$root" merge-base --is-ancestor "$repair_fixture" "$candidate" \
        || fail "approved fixture repair is not an ancestor"
    git -C "$root" merge-base --is-ancestor "$repair_snapshot" "$candidate" \
        || fail "approved snapshot repair is not an ancestor"
    candidate_parents=$(git -C "$root" rev-list --parents -n 1 "$candidate")
    set -- $candidate_parents
    [ "$#" -eq 3 ] || fail "candidate must have exactly two parents"
    [ "$2" = "$candidate_base" ] || fail "candidate first parent is not the coordinator base"
    [ -z "$(git -C "$root" status --porcelain=v1)" ] || fail "candidate worktree must be clean"
    frontend_port=${TASK30_FRONTEND_PORT:-}
    case "$frontend_port" in ''|*[!0-9]*) fail "TASK30_FRONTEND_PORT must be numeric";; esac
    [ "$frontend_port" -ge 1024 ] && [ "$frontend_port" -le 65535 ] || fail "TASK30_FRONTEND_PORT is invalid"
    [ "$frontend_port" -ne 18083 ] || fail "port 18083 is retained"
    network_octet=${TASK30_NETWORK_OCTET:-}
    case "$network_octet" in ''|*[!0-9]*) fail "TASK30_NETWORK_OCTET must be numeric";; esac
    [ "$network_octet" -ge 31 ] && [ "$network_octet" -le 223 ] && [ "$network_octet" -ne 172 ] \
        || fail "TASK30_NETWORK_OCTET is invalid or retained"
    if ! frontend_listener=$(run_bounded 30 docker ps -q --filter "publish=$frontend_port"); then
        fail "candidate frontend port query failed"
    fi
    [ -z "$frontend_listener" ] || fail "TASK30_FRONTEND_PORT is already published"
    if ! run_bounded 30 docker compose version >/dev/null; then
        fail "Docker Compose query failed"
    fi
}

case "$mode:$-" in --execute:*x*) fail "xtrace is forbidden for --execute";; esac
validate_static
case "$mode" in
    --dry-run)
        [ "$#" -eq 1 ] || fail "usage: $0 [--dry-run|--execute]"
        echo "PASS: static inputs, Node 22, Python runner, fixture, and tracked-secret scan"
        echo "DRY_RUN: no Docker command executed"
        echo "DRY_RUN: authorized phase binds exact clean candidate and creates unique owned roots"
        echo "DRY_RUN: full frontend/backend gates precede isolated real Chromium/WebKit flow"
        echo "DRY_RUN: cleanup removes only isolated resources, restores exact originals, and proves /health HTTP 200"
        exit 0
        ;;
    --execute) [ "$#" -eq 1 ] || fail "usage: $0 [--dry-run|--execute]";;
    *) fail "usage: $0 [--dry-run|--execute]";;
esac

validate_execute
trap 'restore_and_cleanup $?' EXIT
trap 'restore_and_cleanup 129' HUP
trap 'restore_and_cleanup 130' INT
trap 'restore_and_cleanup 143' TERM
run_id=$(openssl rand -hex 8)
project="narae-task30-$run_id"
work=$(mktemp -d /private/tmp/narae-task30-verify.XXXXXX)
printf '%s\n' "$run_id" > "$work/.task30-owned"
data_root=$(mktemp -d /private/tmp/narae-task30-data.XXXXXX)
printf '%s\n' "$run_id" > "$data_root/.task30-owned"
case "$data_root" in *'..'*|*//*|*/./*) fail "generated data root is not canonical";; esac
[ ! -L "$data_root" ] || fail "generated data root must not be a symlink"
evidence="$root/.omo/evidence/task30/execute-$run_id"
[ ! -e "$evidence" ] || fail "Task30 evidence root already exists"
mkdir -p -m 700 "$evidence"
record_phase() {
    case "$1" in
        isolated-project-resource-query|retained-listener-discovery|retained-project-member-discovery|retained-snapshot-health|retained-suspension|testcontainers-before-backend-snapshot|backend-gradle|frontend-gates|compose-workflow-release|browser-flow) ;;
        *) fail "invalid phase label" ;;
    esac
    printf 'phase=%s\n' "$1" > "$evidence/phase.log"
}
record_phase isolated-project-resource-query
require_project_absent

compose_file="$work/compose.yml"
sed "s/172\.30\.0\./10\.${network_octet}.30\./g" "$compose_source" > "$compose_file"
mkdir -m 700 "$data_root/secrets" "$data_root/postgres" "$data_root/minio"
umask 077
openssl rand 32 > "$data_root/secrets/master.key"
export COMPOSE_PROJECT_NAME=$project FRONTEND_PORT=$frontend_port NARAE_DATA_ROOT=$data_root
export POSTGRES_DB=task30_mvp POSTGRES_USER="task30_$(openssl rand -hex 6)"
export POSTGRES_PASSWORD="$(openssl rand -hex 24)" MINIO_ROOT_USER="task30$(openssl rand -hex 6)"
export MINIO_ROOT_PASSWORD="$(openssl rand -hex 24)" APP_MINIO_BUCKET=task30-mvp
export APP_PUBLIC_ORIGIN="http://127.0.0.1:$frontend_port" APP_CRYPTO_KEY_VERSION=1

record_phase retained-listener-discovery
if ! anchor_ids=$(run_bounded 30 docker ps -q --filter publish=18083); then
    fail "retained listener discovery failed"
fi
[ "$(printf '%s\n' "$anchor_ids" | awk 'NF { count++ } END { print count + 0 }')" -eq 1 ] \
    || fail "expected exactly one retained 18083 container"
anchor=$(printf '%s\n' "$anchor_ids" | sed -n '1p')
record_phase retained-project-member-discovery
if ! retained_project=$(run_bounded 30 docker inspect \
    --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$anchor"); then
    fail "retained project discovery failed"
fi
if [ -n "$retained_project" ] && [ "$retained_project" != '<no value>' ]; then
    if ! original_ids=$(run_bounded 30 docker ps -aq --filter "label=com.docker.compose.project=$retained_project"); then
        fail "retained project member discovery failed"
    fi
    if ! original_running_ids=$(run_bounded 30 docker ps -q --filter "label=com.docker.compose.project=$retained_project"); then
        fail "retained project member discovery failed"
    fi
else
    original_ids=$anchor
    original_running_ids=$anchor
fi
[ -n "$original_ids" ] || fail "retained container set is empty"
record_phase retained-snapshot-health
snapshot_originals "$work/original-before.json" \
    || fail "retained snapshot failed"
health_facts "$work/original-before-health.tsv" \
    || fail "retained health check failed"
cp "$work/original-before.json" "$evidence/original-before.json"
cp "$work/original-before-health.tsv" "$evidence/original-before-health.tsv"
record_phase retained-suspension
for id in $original_running_ids; do
    if ! run_bounded 120 docker stop "$id" >/dev/null; then
        fail "retained suspension failed"
    fi
done

record_phase testcontainers-before-backend-snapshot
testcontainers_snapshot "$work/testcontainers-before.txt" \
    || fail "pre-backend Testcontainers snapshot failed"
record_phase backend-gradle
set +e
backend_log="$work/backend-clean-check.log"
backend_receipt="$work/backend-candidate-receipt"
backend_receipt_init="$work/backend-receipt.init.gradle"
cat > "$backend_receipt_init" <<'EOF'
def receipt = System.getenv("TASK30_BACKEND_RECEIPT")
def candidate = System.getenv("TASK30_CANDIDATE_SHA")
def attempt = System.getenv("TASK30_BACKEND_ATTEMPT")
if (receipt == null || candidate == null || attempt == null) {
    throw new GradleException("TASK30_BACKEND_RECEIPT_CONFIGURATION_INVALID")
}
gradle.buildFinished {
    new File(receipt).text = "candidate=${candidate}\nattempt=${attempt}\n"
}
EOF
TASK30_BACKEND_RECEIPT="$backend_receipt" TASK30_BACKEND_ATTEMPT="$run_id" \
    run_bounded 1800 sh -c 'cd "$1" && ./gradlew --init-script "$2" clean check integrationTest' task30 "$root/backend" "$backend_receipt_init" \
    > "$backend_log" 2>&1
gradle_status=$?
set -e
scan_retained_evidence "$backend_log" || fail "backend log scan failed"
validate_backend_receipt
if [ -d "$root/backend/build/test-results" ]; then
    python3 - "$root/backend/build/test-results" <<'PY' > "$work/background-upload-statuses"
import re
import sys
import xml.etree.ElementTree as element_tree
from pathlib import Path

marker = re.compile(r"background-upload-status=")
status = re.compile(r"(?<![A-Za-z0-9_-])background-upload-status=([0-9]{3})(?![A-Za-z0-9_-])")

def statuses(value):
    markers = [match.start() for match in marker.finditer(value)]
    matches = list(status.finditer(value))
    if markers != [match.start() for match in matches]:
        raise SystemExit("invalid background upload status marker")
    return [match.group(1) for match in matches]

for report in Path(sys.argv[1]).rglob("*.xml"):
    for element in element_tree.parse(report).getroot().iter():
        failure = element.tag.rsplit("}", 1)[-1] == "failure"
        message_values = []
        for name, value in element.attrib.items():
            values = statuses(value)
            if values and not (failure and name == "message"):
                raise SystemExit("unexpected background upload status marker")
            if failure and name == "message":
                message_values = values
            for value in values:
                print(f"background-upload-status={value}")
        text_values = statuses(element.text or "")
        if text_values and (not failure or text_values != message_values):
            raise SystemExit("unexpected background upload status marker")
        if element.tail and statuses(element.tail):
            raise SystemExit("unexpected background upload status marker")
PY
    if [ -s "$work/background-upload-statuses" ]; then
        LC_ALL=C sort -u "$work/background-upload-statuses" | awk '
            /^background-upload-status=[0-9][0-9][0-9]$/ { print; next }
            { exit 1 }
        ' > "$evidence/background-upload-status-summary.log" || fail "background upload status summary is invalid"
    fi
fi
rm -f "$work/background-upload-statuses" "$backend_log" "$backend_receipt" "$backend_receipt_init"
scan_retained_evidence || fail "retained evidence scan failed"
[ "$gradle_status" -eq 0 ] || exit "$gradle_status"
testcontainers_snapshot "$work/testcontainers-after.txt" \
    || fail "post-backend Testcontainers snapshot failed"
cmp -s "$work/testcontainers-before.txt" "$work/testcontainers-after.txt" \
    || fail "Testcontainers resources remain after Gradle completion"

record_phase frontend-gates
run_bounded 900 sh -c 'cd "$1" && npm ci' task30 "$root/frontend" > "$evidence/frontend-install.log" 2>&1
run_bounded 300 sh -c 'cd "$1" && npm run lint' task30 "$root/frontend" > "$evidence/frontend-lint.log" 2>&1
run_bounded 300 sh -c 'cd "$1" && npm run typecheck' task30 "$root/frontend" > "$evidence/frontend-typecheck.log" 2>&1
run_bounded 900 sh -c 'cd "$1" && npm run test' task30 "$root/frontend" > "$evidence/frontend-test.log" 2>&1
run_bounded 900 sh -c 'cd "$1" && npm run build' task30 "$root/frontend" > "$evidence/frontend-build.log" 2>&1
run_bounded 1200 sh -c 'cd "$1" && PLAYWRIGHT_OUTPUT_DIR="$2" \
    npx playwright test --project=chromium --project=webkit --workers=1 --trace=off' \
    task30 "$root/frontend" "$work/playwright-mock-output" \
    > "$evidence/frontend-playwright.log" 2>&1
record_phase compose-workflow-release
compose_cleanup_required=true
run_bounded 900 docker compose -p "$project" -f "$compose_file" up -d --wait \
    > "$evidence/compose-up.log" 2>&1
code=$(run_bounded 30 curl -sS -o "$work/isolated-health-body" -w '%{http_code}' --max-time 5 \
    "http://127.0.0.1:$frontend_port/health")
[ "$code" = 200 ] || fail "isolated frontend health did not return HTTP 200"
run_bounded 600 env COMPOSE_FILE=$compose_file COMPOSE_PROJECT_NAME=$project \
    "$root/scripts/test-compose-resources.sh" \
    > "$evidence/compose-resources.log" 2>&1
run_bounded 600 "$root/scripts/verify-workflows.sh" "$root/.github/workflows/ci.yml" \
    > "$evidence/workflows.log" 2>&1
run_bounded 600 "$root/scripts/test-release-delivery.sh" > "$evidence/release-fixtures.log" 2>&1
validate_tracked_secrets > "$evidence/tracked-secrets.log" 2>&1

playwright_dir="$root/frontend/node_modules/.cache/task30-$run_id"
mkdir -p "$playwright_dir"
printf '%s\n' "$run_id" > "$playwright_dir/.task30-owned"
playwright_config="$playwright_dir/playwright.config.mjs"
cp "$playwright_template" "$playwright_config"
export TASK30_E2E_DIR="$root/frontend/e2e" TASK30_PLAYWRIGHT_OUTPUT="$work/playwright-output"
record_phase browser-flow
run_bounded 900 python3 "$live_runner" "$compose_file" "$project" "http://127.0.0.1:$frontend_port" \
    "$playwright_config" "$work" > "$evidence/playwright-real.log" 2>&1
exit 0
