#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate=
probe=
probe_marker=
candidate_root=
probe_acquired=false
worktree_state=absent
self_child=
self_fifo=
self_fifo_dir=
self_log=
self_candidate=
self_stage=
self_owned_stage=
self_owned_probe=
self_owned_root=
self_reported_stage=
self_reported_pid=
self_reported_candidate=
self_reported_probe=
self_reported_root=
self_report_validated=false
self_dirty_retained=false
guard_fixture_root=

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

worktree_add_checkpoint() {
    [ "${TASK30_WORKTREE_ADD_CHECKPOINT:-}" = 1 ] || return 0
    printf 'READY: boundary=worktree-add pid=%s candidate=%s probe=%s root=%s state=%s\n' "$$" "$candidate" "$probe" "$candidate_root" "$worktree_state"
    kill -STOP "$$"
}

cleanup_self_regression() {
    self_cleanup_ok=true

    if [ -n "$self_child" ] && kill -0 "$self_child" 2>/dev/null; then
        kill -TERM "$self_child" 2>/dev/null || self_cleanup_ok=false
        kill -CONT "$self_child" 2>/dev/null || self_cleanup_ok=false
        wait "$self_child" 2>/dev/null || :
    fi
    self_child=

    if [ -n "$self_owned_probe" ]; then
        case "$self_owned_stage" in
            probe-ready)
                if [ -e "$self_owned_probe" ] && { [ -e "$self_owned_probe/.task30-evidence-probe-owned" ] || ! rmdir -- "$self_owned_probe"; }; then
                    self_cleanup_ok=false
                fi;;
            worktree-ready|worktree-ready-dirty)
                if [ -e "$self_owned_probe" ]; then
                    if ! marker_binds_candidate "$self_owned_probe/.task30-evidence-probe-owned" "$self_candidate"; then
                        self_cleanup_ok=false
                    elif self_worktree_registered "$self_owned_root"; then
                        if [ "$self_dirty_retained" = true ]; then
                            git -C "$repo_root" worktree remove --force "$self_owned_root" >/dev/null 2>&1 || self_cleanup_ok=false
                        else
                            git -C "$repo_root" worktree remove "$self_owned_root" >/dev/null 2>&1 || self_cleanup_ok=false
                        fi
                    fi
                    if self_worktree_registered "$self_owned_root" || ! marker_binds_candidate "$self_owned_probe/.task30-evidence-probe-owned" "$self_candidate"; then
                        self_cleanup_ok=false
                    elif ! rm -rf -- "$self_owned_probe"; then
                        self_cleanup_ok=false
                    fi
                fi;;
            *) self_cleanup_ok=false;;
        esac
        if self_worktree_registered "$self_owned_root" || [ -e "$self_owned_probe" ]; then
            self_cleanup_ok=false
        else
            self_owned_stage=
            self_owned_probe=
            self_owned_root=
            self_report_validated=false
        fi
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

    if [ -n "$guard_fixture_root" ]; then
        case "$guard_fixture_root" in
            /private/tmp/narae-task30-candidate-guard.*) rm -rf -- "$guard_fixture_root";;
            *) self_cleanup_ok=false;;
        esac
        guard_fixture_root=
    fi

    case "$worktree_state" in
        absent) :;;
        creating|owned)
            linked_worktree_removed=false
            if owned_probe_path "$probe" && [ "$candidate_root" = "$probe/candidate" ] && marker_binds_candidate "$probe_marker" "$candidate"; then
                if self_worktree_registered "$candidate_root"; then
                    if [ "$worktree_state" = creating ]; then
                        git -C "$repo_root" worktree unlock "$candidate_root" >/dev/null 2>&1 || :
                        git -C "$repo_root" worktree remove --force "$candidate_root" >/dev/null 2>&1 || :
                    else
                        git -C "$repo_root" worktree remove "$candidate_root" >/dev/null 2>&1 || :
                    fi
                fi
                self_worktree_registered "$candidate_root" || linked_worktree_removed=true
            fi;;
        *) linked_worktree_removed=false;;
    esac

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
    probe=$3
    candidate_root=$4

    owned_probe_path "$probe" || { echo "FAIL: cleanup child probe is not a canonical owned path" >&2; exit 1; }
    [ -d "$probe" ] || { echo "FAIL: cleanup child probe does not exist" >&2; exit 1; }
    [ "$candidate_root" = "$probe/candidate" ] || { echo "FAIL: cleanup child candidate root is not under probe" >&2; exit 1; }
    probe_acquired=true
    probe_marker="$probe/.task30-evidence-probe-owned"

    case "$stage" in
        probe-ready)
            [ ! -e "$probe_marker" ] || { echo "FAIL: cleanup child probe-ready marker exists" >&2; exit 1; };;
        worktree-ready|worktree-ready-dirty)
            marker_binds_candidate "$probe_marker" "$candidate" || { echo "FAIL: cleanup child marker does not bind candidate" >&2; exit 1; }
            worktree_state=creating
            git -C "$repo_root" worktree add --detach "$candidate_root" "$candidate" >/dev/null
            worktree_add_checkpoint
            worktree_state=owned;;
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
    self_owned_stage=
    self_owned_probe=
    self_owned_root=
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
        self_owned_stage=$self_stage
        self_owned_probe=$(mktemp -d /private/tmp/narae-task30-evidence-probe.XXXXXX)
        owned_probe_path "$self_owned_probe" || self_fail "parent-owned probe is not a canonical owned path"
        self_owned_root="$self_owned_probe/candidate"
        case "$self_stage" in
            probe-ready)
                [ ! -e "$self_owned_probe/.task30-evidence-probe-owned" ] || self_fail "parent-owned probe-ready marker exists";;
            worktree-ready|worktree-ready-dirty)
                printf '%s\n' "$self_candidate" > "$self_owned_probe/.task30-evidence-probe-owned"
                marker_binds_candidate "$self_owned_probe/.task30-evidence-probe-owned" "$self_candidate" || self_fail "parent-owned marker does not bind candidate";;
        esac
        self_log="$self_fifo.$self_stage.log"
        "$0" --task30-cleanup-child "$self_stage" "$self_candidate" "$self_fifo" "$self_owned_probe" "$self_owned_root" > "$self_log" 2>&1 &
        self_child=$!

        if [ "${TASK30_SELF_INTERRUPT_CHECKPOINT:-}" = 1 ] && [ "${TASK30_SELF_INTERRUPT_STAGE:-probe-ready}" = "$self_stage" ]; then
            printf 'READY: boundary=prevalidation parent=%s stage=%s child=%s fifo=%s probe=%s root=%s validated=%s\n' "$$" "$self_stage" "$self_child" "$self_fifo" "$self_owned_probe" "$self_owned_root" "$self_report_validated"
            kill -STOP "$$"
        fi
        IFS="$(printf '\t')" read -r reported_stage reported_pid reported_candidate reported_probe reported_root < "$self_fifo" || self_fail "child did not report readiness"
        [ "$reported_stage" = "$self_stage" ] || self_fail "reported stage mismatch"
        [ "$reported_pid" = "$self_child" ] || self_fail "reported child PID mismatch"
        [ "$reported_candidate" = "$self_candidate" ] || self_fail "reported candidate mismatch"
        [ "$reported_probe" = "$self_owned_probe" ] || self_fail "reported probe does not match parent ownership"
        [ "$reported_root" = "$self_owned_root" ] || self_fail "reported candidate root does not match parent ownership"
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
        self_worktree_registered "$self_owned_root" && self_fail "parent-owned worktree registration remains"
        [ ! -e "$self_owned_probe" ] || self_fail "parent-owned probe remains"
        self_owned_stage=
        self_owned_probe=
        self_owned_root=
        self_report_validated=false
    done
    unlink -- "$self_fifo"
    [ ! -e "$self_fifo" ] || self_fail "readiness FIFO remains"
    self_fifo=
    rmdir -- "$self_fifo_dir" || self_fail "readiness directory remains"
    self_fifo_dir=
}

run_candidate_guard_regression() {
    candidate_base=5352744330c1e4d45272b9c2bf382b8f56f23745
    repair_fixture=d4649dbf72532382541ace39f9285e7ff3b611c4
    source_head=$(git -C "$repo_root" rev-parse HEAD)
    guard_fixture_root=$(mktemp -d /private/tmp/narae-task30-candidate-guard.XXXXXX)
    fixture_repo="$guard_fixture_root/repo"
    git clone --quiet --no-hardlinks "$repo_root" "$fixture_repo"
    git -C "$fixture_repo" config user.name 'Task30 Guard Fixture'
    git -C "$fixture_repo" config user.email 'task30-guard@example.invalid'
    git -C "$fixture_repo" switch --quiet -c successor "$source_head"
    cp "$repo_root/scripts/verify-mvp.sh" "$fixture_repo/scripts/verify-mvp.sh"
    if ! git -C "$fixture_repo" diff --quiet -- scripts/verify-mvp.sh; then
        git -C "$fixture_repo" add scripts/verify-mvp.sh
        git -C "$fixture_repo" commit --quiet -m 'test: stage candidate guard under test'
    fi
    successor=$(git -C "$fixture_repo" rev-parse HEAD)

    git -C "$fixture_repo" switch --quiet -c missing-second "$repair_fixture"
    cp "$repo_root/scripts/verify-mvp.sh" "$fixture_repo/scripts/verify-mvp.sh"
    git -C "$fixture_repo" add scripts/verify-mvp.sh
    git -C "$fixture_repo" commit --quiet -m 'test: stage guard without snapshot repair'
    missing_second=$(git -C "$fixture_repo" rev-parse HEAD)
    git -C "$fixture_repo" switch --quiet -c extra-parent "$candidate_base"
    git -C "$fixture_repo" commit --quiet --allow-empty -m 'test: extra coordinator parent'
    extra_parent=$(git -C "$fixture_repo" rev-parse HEAD)

    git -C "$fixture_repo" switch --quiet -C main "$candidate_base"
    git -C "$fixture_repo" merge --quiet --no-ff -m 'test: coordinator candidate' "$successor"
    coordinator=$(git -C "$fixture_repo" rev-parse HEAD)
    git -C "$fixture_repo" switch --quiet -c missing-candidate "$candidate_base"
    git -C "$fixture_repo" merge --quiet --no-ff -m 'test: missing repair candidate' "$missing_second"
    missing_candidate=$(git -C "$fixture_repo" rev-parse HEAD)
    git -C "$fixture_repo" switch --quiet -c topology-candidate "$candidate_base"
    git -C "$fixture_repo" merge --quiet --no-ff -m 'test: three-parent candidate' "$successor" "$extra_parent"
    topology_candidate=$(git -C "$fixture_repo" rev-parse HEAD)

    fake_bin="$guard_fixture_root/fake-bin"
    ledger="$guard_fixture_root/docker-ledger.log"
    mkdir "$fake_bin"
    cat > "$fake_bin/docker" <<'EOF'
#!/bin/sh
set -eu
printf 'docker' >> "$FAKE_DOCKER_LEDGER"
for arg; do printf ' %s' "$arg" >> "$FAKE_DOCKER_LEDGER"; done
printf '\n' >> "$FAKE_DOCKER_LEDGER"
case "$*" in
    'ps -q --filter publish=28083') exit 0;;
    'compose version') echo 'Docker Compose version fake'; exit 0;;
    'ps -aq --filter label=com.docker.compose.project='*) exit 0;;
    'network ls -q --filter label=com.docker.compose.project='*) exit 0;;
    'volume ls -q --filter label=com.docker.compose.project='*) exit 0;;
    'ps -q --filter publish=18083') exit 0;;
    *) echo "unexpected fake docker command: $*" >&2; exit 99;;
esac
EOF
    chmod 700 "$fake_bin/docker"
    node22_bin=/Users/lapis0875/.nvm/versions/node/v22.23.2/bin
    run_path="$fake_bin:$node22_bin:$PATH"
    export FAKE_DOCKER_LEDGER="$ledger"

    run_guard_case() {
        case_name=$1 expected=$2 head=$3 main=$4 supplied=$5 dirty=$6 misleading=$7
        git -C "$fixture_repo" switch --quiet --detach --force "$head"
        git -C "$fixture_repo" update-ref refs/heads/main "$main"
        dirty_path="$fixture_repo/.task30-candidate-dirty"
        [ "$dirty" = false ] || : > "$dirty_path"
        : > "$ledger"
        output="$guard_fixture_root/$case_name.log"
        : > "$output"
        [ "$misleading" = false ] || echo 'PASS: stale cached output must not decide this case' >> "$output"
        set +e
        (cd "$fixture_repo" && PATH="$run_path" TASK30_DOCKER_AUTHORITY=approved \
            TASK30_CANDIDATE_SHA="$supplied" TASK30_FRONTEND_PORT=28083 TASK30_NETWORK_OCTET=31 \
            ./scripts/verify-mvp.sh --execute) >> "$output" 2>&1
        case_status=$?
        set -e
        [ "$case_status" -ne 0 ] || self_fail "$case_name unexpectedly passed"
        grep -F "$expected" "$output" >/dev/null || self_fail "$case_name missed expected failure: $expected"
        if [ "$case_name" = positive ]; then
            grep -F 'docker ps -q --filter publish=18083' "$ledger" >/dev/null || self_fail 'positive missed retained anchor call'
            grep -F 'FAIL: expected exactly one retained 18083 container' "$output" >/dev/null || self_fail 'positive missed retained anchor sentinel'
            rm -rf -- "$fixture_repo/.omo/evidence/task30"
        elif [ -s "$ledger" ]; then
            self_fail "$case_name reached fake Docker"
        fi
        [ "$dirty" = false ] || unlink -- "$dirty_path"
        echo "PASS: guard_case=$case_name exit=$case_status fake_docker_ledger=$([ -s "$ledger" ] && echo reached-anchor || echo empty)"
    }

    run_guard_case positive 'FAIL: expected exactly one retained 18083 container' "$coordinator" "$coordinator" "$coordinator" false false
    run_guard_case malformed 'FAIL: candidate SHA must be a full lowercase commit SHA' "$coordinator" "$coordinator" not-a-sha false false
    run_guard_case ref 'FAIL: candidate SHA must be a full lowercase commit SHA' "$coordinator" "$coordinator" main false false
    abbreviated=$(printf '%s' "$coordinator" | cut -c1-12)
    run_guard_case abbreviated 'FAIL: candidate SHA must be a full lowercase commit SHA' "$coordinator" "$coordinator" "$abbreviated" false false
    run_guard_case stale_state 'FAIL: candidate SHA does not match HEAD' "$successor" "$successor" "$coordinator" false false
    run_guard_case source_tip 'FAIL: candidate must have exactly two parents' "$successor" "$successor" "$successor" false false
    run_guard_case off_main 'FAIL: candidate is not refs/heads/main' "$coordinator" "$candidate_base" "$coordinator" false true
    run_guard_case missing_repair 'FAIL: approved snapshot repair is not an ancestor' "$missing_candidate" "$missing_candidate" "$missing_candidate" false false
    run_guard_case dirty_worktree 'FAIL: candidate worktree must be clean' "$coordinator" "$coordinator" "$coordinator" true false
    run_guard_case topology_mismatch 'FAIL: candidate must have exactly two parents' "$topology_candidate" "$topology_candidate" "$topology_candidate" false false
    echo "PASS: candidate=$coordinator topology=two-parent first-parent=$candidate_base detached=true real_docker_executed=false"
}

case ${1:-} in
    --task30-candidate-guard-regression)
        [ "$#" -eq 1 ] || { echo "usage: $0 --task30-candidate-guard-regression" >&2; exit 2; }
        run_candidate_guard_regression
        exit 0;;
    --task30-cleanup-child)
        [ "$#" -eq 6 ] || { echo "usage: $0 --task30-cleanup-child <stage> <candidate-sha> <fifo> <probe> <candidate-root>" >&2; exit 2; }
        candidate=$(git -C "$repo_root" rev-parse --verify "$3^{commit}") || { echo "FAIL: candidate is not a commit: $3" >&2; exit 1; }
        cleanup_child "$2" "$4" "$5" "$6"
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

worktree_state=creating
git -C "$repo_root" worktree add --detach "$candidate_root" "$candidate" >/dev/null
worktree_add_checkpoint
worktree_state=owned
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
