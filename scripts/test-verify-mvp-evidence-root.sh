#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate=${1:-}
[ -n "$candidate" ] || { echo "usage: $0 <candidate-sha>" >&2; exit 2; }
candidate=$(git -C "$repo_root" rev-parse --verify "$candidate^{commit}") || { echo "FAIL: candidate is not a commit: $candidate" >&2; exit 1; }

probe=$(mktemp -d /private/tmp/narae-task30-evidence-probe.XXXXXX)
probe_marker="$probe/.task30-evidence-probe-owned"
printf '%s\n' "$candidate" > "$probe_marker"
candidate_root="$probe/candidate"
fake_bin="$probe/fake-bin"
ledger="$probe/docker-ledger.log"
mkdir "$fake_bin"

cleanup() {
    status=$?
    trap - EXIT HUP INT TERM
    set +e
    worktree_removed=false
    probe_removed=false
    if [ -n "${candidate_root:-}" ] && git -C "$repo_root" worktree remove "$candidate_root" >/dev/null 2>&1; then
        worktree_removed=true
    fi
    if [ -f "$probe_marker" ] && [ "$(sed -n '1p' "$probe_marker")" = "$candidate" ]; then
        case "$probe" in
            /private/tmp/narae-task30-evidence-probe.*) rm -rf -- "$probe" && probe_removed=true;;
        esac
    fi
    if [ "$status" -eq 0 ] && [ "$worktree_removed" = true ] && [ "$probe_removed" = true ]; then
        echo "CLEANUP: linked_worktree_removed=true marked_probe_removed=true"
    elif [ "$status" -eq 0 ]; then
        echo "FAIL: cleanup proof failed" >&2
        status=1
    fi
    exit "$status"
}
trap 'cleanup' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

cat > "$fake_bin/docker" <<'EOF'
#!/bin/sh
set -eu
args=$*
printf 'docker' >> "$FAKE_DOCKER_LEDGER"
for arg
do
    printf ' %s' "$arg" >> "$FAKE_DOCKER_LEDGER"
done
printf '\n' >> "$FAKE_DOCKER_LEDGER"
case "$args" in
    'ps -q --filter publish=28083') exit 0;;
    'compose version') echo 'Docker Compose version fake'; exit 0;;
    'ps -aq --filter label=com.docker.compose.project='*) exit 0;;
    'network ls -q --filter label=com.docker.compose.project='*) exit 0;;
    'volume ls -q --filter label=com.docker.compose.project='*) exit 0;;
    'ps -q --filter publish=18083') exit 0;;
    *) echo "unexpected fake docker command: $args" >&2; exit 99;;
esac
EOF
chmod 700 "$fake_bin/docker"
: > "$ledger"

git -C "$repo_root" worktree add --detach "$candidate_root" "$candidate" >/dev/null
[ -z "$(git -C "$candidate_root" status --porcelain=v1)" ] || { echo "FAIL: candidate worktree is dirty" >&2; exit 1; }
[ ! -e "$candidate_root/.omo/evidence/task30" ] || { echo "FAIL: candidate already has Task30 evidence root" >&2; exit 1; }

node22_bin=/Users/lapis0875/.nvm/versions/node/v22.23.2/bin
[ "$(PATH="$node22_bin:$PATH" node -p 'process.versions.node.split(".")[0]')" = 22 ] || { echo "FAIL: Node 22 is unavailable" >&2; exit 1; }
run_path="$fake_bin:$node22_bin:$PATH"
output="$probe/verify-output.log"
export FAKE_DOCKER_LEDGER="$ledger"
set +e
(
    cd "$candidate_root"
    PATH="$run_path" \
    TASK30_DOCKER_AUTHORITY=approved \
    TASK30_CANDIDATE_SHA="$candidate" \
    TASK30_FRONTEND_PORT=28083 \
    TASK30_NETWORK_OCTET=31 \
    ./scripts/verify-mvp.sh --execute
) > "$output" 2>&1
verify_status=$?
set -e
[ "$verify_status" -ne 0 ] || { echo "FAIL: execute unexpectedly passed" >&2; exit 1; }
grep -F 'FAIL: expected exactly one retained 18083 container' "$output" >/dev/null || {
    echo "FAIL: downstream anchor sentinel missing" >&2
    sed 's/^/VERIFY /' "$output" >&2
    sed 's/^/DOCKER /' "$ledger" >&2
    exit 1
}

evidence_root="$candidate_root/.omo/evidence/task30"
leaf_count=$(find "$evidence_root" -mindepth 1 -maxdepth 1 -type d -name 'execute-*' | wc -l | tr -d ' ')
[ "$leaf_count" -eq 1 ] || { echo "FAIL: expected one execute evidence leaf, found $leaf_count" >&2; exit 1; }
leaf=$(find "$evidence_root" -mindepth 1 -maxdepth 1 -type d -name 'execute-*' -print)
leaf_mode=$(stat -f '%Lp' "$leaf")
[ "$leaf_mode" = 700 ] || { echo "FAIL: evidence leaf mode is $leaf_mode, expected 700" >&2; exit 1; }
run_id=${leaf##*/execute-}

expected_calls=0
anchor_calls=0
while IFS= read -r call
do
    [ -n "$call" ] || continue
    expected_calls=$((expected_calls + 1))
    case "$call" in
        'docker ps -q --filter publish=28083'|'docker compose version'|'docker ps -aq --filter label=com.docker.compose.project='*|'docker network ls -q --filter label=com.docker.compose.project='*|'docker volume ls -q --filter label=com.docker.compose.project='*|'docker ps -q --filter publish=18083')
            case "$call" in 'docker ps -q --filter publish=18083') anchor_calls=$((anchor_calls + 1));; esac;;
        *) echo "FAIL: unexpected fake docker call: $call" >&2; exit 1;;
    esac
done < "$ledger"
[ "$anchor_calls" -eq 1 ] || { echo "FAIL: expected one anchor call, found $anchor_calls" >&2; exit 1; }
[ "$expected_calls" -gt 1 ] || { echo "FAIL: pre-anchor docker ledger is empty" >&2; exit 1; }
if grep -Eq 'docker (compose (up|down|rm|kill|stop|start)|stop|start|rm|kill|run|exec)' "$ledger"; then
    echo "FAIL: fake Docker lifecycle command was logged" >&2
    exit 1
fi

owned_markers=0
for marker in /private/tmp/narae-task30-verify.*/.task30-owned /private/tmp/narae-task30-data.*/.task30-owned
do
    if [ -f "$marker" ] && grep -Fqx "$run_id" "$marker"; then
        owned_markers=$((owned_markers + 1))
    fi
done
[ "$owned_markers" -eq 0 ] || { echo "FAIL: Task30 owned marker remains for $run_id" >&2; exit 1; }

echo "PASS: candidate=$candidate exit=$verify_status sentinel=retained-18083-anchor"
echo "PASS: evidence_leaf=$leaf mode=$leaf_mode run_id=$run_id"
echo "PASS: fake_docker_calls=$expected_calls anchor_calls=$anchor_calls lifecycle_calls=0"
echo "PASS: owned_markers_remaining=0 real_docker_executed=false"
echo 'FAKE_DOCKER_LEDGER_BEGIN'
sed 's/^/  /' "$ledger"
echo 'FAKE_DOCKER_LEDGER_END'
exit 0
