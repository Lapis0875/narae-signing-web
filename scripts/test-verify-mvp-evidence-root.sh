#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate=
probe=
probe_marker=
candidate_root=
probe_acquired=false
worktree_acquired=false
self_child=
self_fifo=
self_fifo_dir=
self_log=
self_candidate=
self_stage=
self_reported_stage=
self_reported_pid=
self_reported_candidate=
self_reported_probe=
self_reported_root=
self_report_validated=false
self_dirty_retained=false

marker_binds_candidate() {
    marker_path=$1
    expected_candidate=$2
    [ -f "$marker_path" ] || return 1
    [ "$(wc -c < "$marker_path" | tr -d ' ')" -eq "$(( ${#expected_candidate} + 1 ))" ] || return 1
    IFS= read -r marker_candidate < "$marker_path" && [ "$marker_candidate" = "$expected_candidate" ]
}

owned_probe_path() {
    owned_probe=$(CDPATH= cd -- "$1" && pwd -P) || return 1
    [ "$owned_probe" = "$1" ] || return 1
    case "$owned_probe" in
        /private/tmp/narae-task30-evidence-probe.*) return 0;;
        *) return 1;;
    esac
}

self_worktree_registered() {
    git -C "$repo_root" worktree list --porcelain | grep -Fqx "worktree $1"
}

cleanup_self_regression() {
    self_cleanup_ok=true

    if [ -n "$self_child" ] && kill -0 "$self_child" 2>/dev/null; then
        kill -TERM "$self_child" 2>/dev/null || self_cleanup_ok=false
        kill -CONT "$self_child" 2>/dev/null || self_cleanup_ok=false
        wait "$self_child" 2>/dev/null || :
    fi
    self_child=

    if [ "$self_report_validated" = true ]; then
        case "$self_reported_stage" in
            probe-ready)
                if [ -e "$self_reported_probe" ] && { [ -e "$self_reported_probe/.task30-evidence-probe-owned" ] || ! rmdir -- "$self_reported_probe"; }; then
                    self_cleanup_ok=false
                fi;;
            worktree-ready|worktree-ready-dirty)
                if [ -e "$self_reported_probe" ]; then
                    if ! marker_binds_candidate "$self_reported_probe/.task30-evidence-probe-owned" "$self_candidate"; then
                        self_cleanup_ok=false
                    elif self_worktree_registered "$self_reported_root"; then
                        if [ "$self_dirty_retained" = true ]; then
                            git -C "$repo_root" worktree remove --force "$self_reported_root" >/dev/null 2>&1 || self_cleanup_ok=false
                        else
                            git -C "$repo_root" worktree remove "$self_reported_root" >/dev/null 2>&1 || self_cleanup_ok=false
                        fi
                    fi
                    if self_worktree_registered "$self_reported_root" || ! marker_binds_candidate "$self_reported_probe/.task30-evidence-probe-owned" "$self_candidate"; then
                        self_cleanup_ok=false
                    elif ! rm -rf -- "$self_reported_probe"; then
                        self_cleanup_ok=false
                    fi
                fi;;
            *) self_cleanup_ok=false;;
        esac
    fi

    if [ -n "$self_log" ] && [ -e "$self_log" ]; then
        [ "$self_log" = "$self_fifo.$self_stage.log" ] && unlink -- "$self_log" || self_cleanup_ok=false
    fi
    self_log=
    if [ -n "$self_fifo" ] && [ -e "$self_fifo" ]; then
        [ "$self_fifo" = "$self_fifo_dir/ready" ] && unlink -- "$self_fifo" || self_cleanup_ok=false
    fi
    self_fifo=
    if [ -n "$self_fifo_dir" ] && [ -e "$self_fifo_dir" ]; then
        rmdir -- "$self_fifo_dir" || self_cleanup_ok=false
    fi
    self_fifo_dir=

    [ "$self_cleanup_ok" = true ]
}

cleanup() {
    status=$?
    trap - EXIT HUP INT TERM
    set +e
    self_cleanup_ok=true
    cleanup_self_regression || self_cleanup_ok=false
    linked_worktree_removed=true
    marked_probe_removed=true

    if [ "$worktree_acquired" = true ]; then
        linked_worktree_removed=false
        if [ -n "$candidate_root" ] && git -C "$repo_root" worktree remove "$candidate_root" >/dev/null 2>&1; then
            linked_worktree_removed=true
        fi
    fi

    if [ "$probe_acquired" = true ]; then
        marked_probe_removed=false
        if [ ! -e "$probe_marker" ]; then
            rmdir -- "$probe" >/dev/null 2>&1 && marked_probe_removed=true
        elif [ "$linked_worktree_removed" = true ] && marker_binds_candidate "$probe_marker" "$candidate"; then
            case "$probe" in
                /private/tmp/narae-task30-evidence-probe.*) rm -rf -- "$probe" >/dev/null 2>&1 && marked_probe_removed=true;;
            esac
        fi
    fi

    if [ "$self_cleanup_ok" = true ] && [ "$linked_worktree_removed" = true ] && [ "$marked_probe_removed" = true ]; then
        echo "CLEANUP: linked_worktree_removed=true marked_probe_removed=true"
        exit "$status"
    fi

    echo "FAIL: cleanup proof failed incoming_status=$status linked_worktree_removed=$linked_worktree_removed marked_probe_removed=$marked_probe_removed" >&2
    [ "$self_cleanup_ok" = true ] || echo 'FAIL: cleanup self-regression parent recovery failed' >&2
    exit 1
}
trap 'cleanup' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

cleanup_child() {
    stage=$1
    fifo=$2

    probe=$(mktemp -d /private/tmp/narae-task30-evidence-probe.XXXXXX)
    probe_acquired=true
    probe_marker="$probe/.task30-evidence-probe-owned"
    candidate_root="$probe/candidate"

    case "$stage" in
        probe-ready) ;;
        worktree-ready|worktree-ready-dirty)
            printf '%s\n' "$candidate" > "$probe_marker"
            git -C "$repo_root" worktree add --detach "$candidate_root" "$candidate" >/dev/null
            worktree_acquired=true;;
        *) echo "FAIL: unknown cleanup child stage: $stage" >&2; exit 2;;
    esac

    printf '%s\t%s\t%s\t%s\t%s\n' "$stage" "$$" "$candidate" "$probe" "$candidate_root" > "$fifo"
    kill -STOP "$$"
    exit 0
}

self_fail() {
    echo "FAIL: cleanup self-regression: $1" >&2
    exit 1
}

run_cleanup_self_regression() {
    self_candidate=$1
    self_child=
    self_fifo=
    self_fifo_dir=
    self_log=
    self_stage=
    self_reported_stage=
    self_reported_pid=
    self_reported_candidate=
    self_reported_probe=
    self_reported_root=
    self_report_validated=false
    self_dirty_retained=false
    self_fifo_dir=$(mktemp -d /private/tmp/narae-task30-evidence-cleanup-ready.XXXXXX)
    chmod 700 "$self_fifo_dir" || self_fail "cannot secure readiness directory"
    self_fifo="$self_fifo_dir/ready"
    (umask 077 && mkfifo "$self_fifo") || self_fail "cannot create readiness FIFO"
    chmod 600 "$self_fifo" || self_fail "cannot secure readiness FIFO"

    for self_stage in probe-ready worktree-ready worktree-ready-dirty
    do
        self_log="$self_fifo.$self_stage.log"
        "$0" --task30-cleanup-child "$self_stage" "$self_candidate" "$self_fifo" > "$self_log" 2>&1 &
        self_child=$!

        IFS="$(printf '\t')" read -r reported_stage reported_pid reported_candidate reported_probe reported_root < "$self_fifo" || self_fail "child did not report readiness"
        [ "$reported_stage" = "$self_stage" ] || self_fail "reported stage mismatch"
        [ "$reported_pid" = "$self_child" ] || self_fail "reported child PID mismatch"
        [ "$reported_candidate" = "$self_candidate" ] || self_fail "reported candidate mismatch"
        owned_probe_path "$reported_probe" || self_fail "reported probe is not a canonical owned path"
        [ -d "$reported_probe" ] || self_fail "reported probe does not exist"
        [ "$reported_root" = "$reported_probe/candidate" ] || self_fail "reported candidate root is not under probe"

        case "$self_stage" in
            probe-ready)
                [ ! -e "$reported_probe/.task30-evidence-probe-owned" ] || self_fail "probe-ready unexpectedly has a marker"
                [ ! -e "$reported_root" ] || self_fail "probe-ready unexpectedly has a worktree";;
            worktree-ready|worktree-ready-dirty)
                [ -d "$reported_root" ] || self_fail "worktree-ready candidate root is missing"
                [ -f "$reported_probe/.task30-evidence-probe-owned" ] || self_fail "worktree-ready marker is missing"
                marker_binds_candidate "$reported_probe/.task30-evidence-probe-owned" "$self_candidate" || self_fail "worktree-ready marker does not bind candidate"
                git -C "$repo_root" worktree list --porcelain | grep -Fqx "worktree $reported_root" || self_fail "worktree-ready registration is missing";;
        esac

        self_reported_stage=$reported_stage
        self_reported_pid=$reported_pid
        self_reported_candidate=$reported_candidate
        self_reported_probe=$reported_probe
        self_reported_root=$reported_root
        self_report_validated=true

        if [ "$self_stage" = worktree-ready-dirty ]; then
            : > "$reported_root/.task30-untracked-blocker"
            self_dirty_retained=true
        fi
        if [ "${TASK30_SELF_INTERRUPT_CHECKPOINT:-}" = 1 ] && [ "${TASK30_SELF_INTERRUPT_STAGE:-probe-ready}" = "$self_stage" ]; then
            printf 'READY: stage=%s child=%s fifo=%s probe=%s root=%s\n' "$self_stage" "$self_child" "$self_fifo" "$reported_probe" "$reported_root"
            kill -STOP "$$"
        fi
        kill -TERM "$self_child"
        kill -CONT "$self_child"
        set +e
        wait "$self_child"
        self_status=$?
        set -e
        self_child=

        case "$self_stage" in
            probe-ready)
                [ "$self_status" -eq 143 ] || self_fail "probe-ready child status is $self_status, expected 143"
                [ ! -e "$reported_probe" ] || self_fail "probe-ready probe remains"
                echo 'PASS: cleanup_interruption_probe_ready=removed exit=143';;
            worktree-ready)
                [ "$self_status" -eq 143 ] || self_fail "worktree-ready child status is $self_status, expected 143"
                [ ! -e "$reported_probe" ] || self_fail "worktree-ready probe remains"
                git -C "$repo_root" worktree list --porcelain | grep -Fqx "worktree $reported_root" && self_fail "worktree-ready registration remains"
                echo 'PASS: cleanup_interruption_worktree_ready=removed exit=143';;
            worktree-ready-dirty)
                [ "$self_status" -eq 1 ] || self_fail "dirty child status is $self_status, expected 1"
                grep -Fqx 'FAIL: cleanup proof failed incoming_status=143 linked_worktree_removed=false marked_probe_removed=false' "$self_log" || self_fail "dirty cleanup failure proof is missing"
                [ -d "$reported_root" ] || self_fail "dirty worktree disappeared before validated recovery"
                git -C "$repo_root" worktree list --porcelain | grep -Fqx "worktree $reported_root" || self_fail "dirty registration disappeared before validated recovery"
                git -C "$repo_root" worktree remove --force "$reported_root"
                [ -f "$reported_probe/.task30-evidence-probe-owned" ] || self_fail "dirty marker is missing during recovery"
                marker_binds_candidate "$reported_probe/.task30-evidence-probe-owned" "$self_candidate" || self_fail "dirty marker changed during recovery"
                case "$reported_probe" in /private/tmp/narae-task30-evidence-probe.*) rm -rf -- "$reported_probe";; *) self_fail "dirty recovery probe is outside the owned prefix";; esac
                [ ! -e "$reported_probe" ] || self_fail "dirty probe remains after recovery"
                git -C "$repo_root" worktree list --porcelain | grep -Fqx "worktree $reported_root" && self_fail "dirty registration remains after recovery"
                self_dirty_retained=false
                echo 'PASS: cleanup_failure_dirty_worktree=validated-recovery exit=1 incoming_status=143 linked_worktree_removed=false marked_probe_removed=false';;
        esac

        unlink -- "$self_log"
        self_log=
    done
    unlink -- "$self_fifo"
    [ ! -e "$self_fifo" ] || self_fail "readiness FIFO remains"
    self_fifo=
    rmdir -- "$self_fifo_dir" || self_fail "readiness directory remains"
    self_fifo_dir=
}

case ${1:-} in
    --task30-cleanup-child)
        [ "$#" -eq 4 ] || { echo "usage: $0 --task30-cleanup-child <stage> <candidate-sha> <fifo>" >&2; exit 2; }
        candidate=$(git -C "$repo_root" rev-parse --verify "$3^{commit}") || { echo "FAIL: candidate is not a commit: $3" >&2; exit 1; }
        cleanup_child "$2" "$4"
        exit 0;;
    --task30-cleanup-self-regression)
        [ "$#" -eq 2 ] || { echo "usage: $0 --task30-cleanup-self-regression <candidate-sha>" >&2; exit 2; }
        candidate=$(git -C "$repo_root" rev-parse --verify "$2^{commit}") || { echo "FAIL: candidate is not a commit: $2" >&2; exit 1; }
        run_cleanup_self_regression "$candidate"
        exit 0;;
esac

candidate=${1:-}
[ -n "$candidate" ] || { echo "usage: $0 <candidate-sha>" >&2; exit 2; }
candidate=$(git -C "$repo_root" rev-parse --verify "$candidate^{commit}") || { echo "FAIL: candidate is not a commit: $candidate" >&2; exit 1; }

probe=$(mktemp -d /private/tmp/narae-task30-evidence-probe.XXXXXX)
probe_acquired=true
probe_marker="$probe/.task30-evidence-probe-owned"
printf '%s\n' "$candidate" > "$probe_marker"
candidate_root="$probe/candidate"
fake_bin="$probe/fake-bin"
ledger="$probe/docker-ledger.log"
mkdir "$fake_bin"

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
worktree_acquired=true
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
