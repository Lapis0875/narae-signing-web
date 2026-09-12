#!/usr/bin/env bash
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
mode=""
evidence_dir=""
project=""
compose_file=""
owner_file=""
temporary_root=""
registry_file=""
registry_receipt=""
registry_receipt_checksum=""
run_id=""
run_started_epoch=""
cleanup_required=false
cleanup_started=false
signal_received=false
signal_exit_code=0
cleanup_retain=false
destructive_action_count=0
temporary_root_removed=false
terminal_signal_finalizing=false
active_cleanup_child_pid=""
root_executor_pid=""
root_executor_registry=""
root_commit_receipt=""
root_delete_request=""
root_executor_state=""
schema_failures=0
recovery_root=""
recovery_source_registry=""
recovery_expected_digest=""
recovery_source_revision=""
recovery_profile=""

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

register_recovery_only() {
    umask 077
    exec python3 - "$root" "$recovery_root" "$recovery_source_registry" \
        "$recovery_expected_digest" "$recovery_source_revision" "$recovery_profile" "$evidence_dir/recovery-registration.json" <<'PY'
import hashlib
import json
import os
import pathlib
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time

cancelled_signal = 0

def interrupt_registration(signum, _frame):
    global cancelled_signal
    if not cancelled_signal:
        cancelled_signal = signum

def check_cancelled():
    if cancelled_signal:
        raise InterruptedError(f"recovery registration cancelled by signal {cancelled_signal}")

signal.signal(signal.SIGTERM, interrupt_registration)
signal.signal(signal.SIGINT, interrupt_registration)

if os.environ.get("INK_QA_RECOVERY_INTERRUPT_STAGE") == "pause-before-validation":
    ready = pathlib.Path(os.environ["INK_QA_RECOVERY_SIGNAL_READY"])
    ready.touch(mode=0o600, exist_ok=False)
    deadline = time.monotonic() + 15
    while not cancelled_signal and time.monotonic() < deadline:
        time.sleep(0.01)
    if cancelled_signal:
        raise SystemExit(128 + cancelled_signal)

worktree, root_arg, registry_arg, expected_digest, source_revision, profile, record_arg = sys.argv[1:]
worktree = pathlib.Path(worktree)
root = pathlib.Path(root_arg)
registry = pathlib.Path(registry_arg)
record = pathlib.Path(record_arg)
uid = os.getuid()
historical = {
    "root": "/private/tmp/narae-ink-color-qa.7338ba83ee9e2d65",
    "sourceRevision": "301ccd02889e016ee5523aa2ce65eb42a0bd653b",
    "registryDigest": "fa603bb6dfe33a58a6216712e36dcf7f66f1b1d1f016a1629a1ee733aed3d5a6",
    "rootDevice": 16777232,
    "rootInode": 91905431,
    "rootBirthtimeMs": 1789062489304,
    "rootMtimeMs": 1789062489891,
    "markerDevice": 16777232,
    "markerInode": 91905432,
    "markerBirthtimeMs": 1789062489305,
    "markerMtimeMs": 1789062489305,
    "registryDevice": 16777232,
    "registryInode": 91905473,
    "parentDevice": 16777232,
    "parentInode": 53537484,
}

if not re.fullmatch(r"/(?:private/)?tmp/narae-ink-color-qa\.[0-9a-f]{16}", root_arg):
    raise SystemExit("recovery root is outside the exact task namespace")
if root_arg != str(root.absolute()) or ".." in root.parts:
    raise SystemExit("recovery root is not one explicit absolute literal path")
if registry_arg != str(registry.absolute()) or registry.name != "ownership-registry.json":
    raise SystemExit("source registry must be one explicit ownership-registry path")
if registry.parent.resolve(strict=True) != registry.parent:
    raise SystemExit("source registry ancestry contains a path alias or symlink")
if not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
    raise SystemExit("expected source registry digest is invalid")
if not re.fullmatch(r"[0-9a-f]{40}", source_revision):
    raise SystemExit("source evidence revision is invalid")
current_head = subprocess.run(
    ["git", "-C", str(worktree), "rev-parse", "HEAD"], check=True, capture_output=True, text=True, timeout=10
).stdout.strip()
if profile == "historical-r2":
    if root_arg != historical["root"] or source_revision != historical["sourceRevision"] or expected_digest != historical["registryDigest"]:
        raise SystemExit("historical r2 recovery provenance does not match the fixed identity")
elif profile == "synthetic-test":
    docker_host = os.environ.get("DOCKER_HOST", "")
    if os.environ.get("INK_QA_RECOVERY_SYNTHETIC_TEST") != "1" or not docker_host.endswith("/host-docker-must-not-exist.sock") or pathlib.Path(docker_host.removeprefix("unix://")).exists():
        raise SystemExit("synthetic recovery profile requires the isolated nonexistent fake-engine boundary")
    if source_revision != current_head or root_arg == historical["root"]:
        raise SystemExit("synthetic recovery provenance is stale or targets the historical root")
else:
    raise SystemExit("recovery profile is invalid")

record_parent_info = record.parent.lstat()
if record.parent.is_symlink() or not stat.S_ISDIR(record_parent_info.st_mode) or record_parent_info.st_uid != uid or stat.S_IMODE(record_parent_info.st_mode) != 0o700:
    raise SystemExit("recovery output parent is not an owned existing directory")
try:
    record.parent.resolve(strict=True).relative_to(root.resolve(strict=True))
except ValueError:
    pass
else:
    raise SystemExit("recovery output parent must remain outside the retained root")

def open_regular(path, mode):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != mode or info.st_uid != uid:
        os.close(fd)
        raise ValueError(f"{path.name} is not an owned private regular file")
    return fd, info

def stable_identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid, info.st_size, info.st_mtime_ns, info.st_ctime_ns)

registry_fd, registry_info = open_regular(registry, 0o600)
root_fd = -1
marker_fd = -1
temporary = None
try:
    check_cancelled()
    source_bytes = os.read(registry_fd, 1024 * 1024 + 1)
    if len(source_bytes) > 1024 * 1024 or hashlib.sha256(source_bytes).hexdigest() != expected_digest:
        raise ValueError("source registry digest changed")
    source = json.loads(source_bytes)
    required = {"schemaVersion", "runId", "project", "ownerLabel", "createdAtEpoch", "sealed", "intents", "resources"}
    if not isinstance(source, dict) or set(source) != required or source["schemaVersion"] != 2:
        raise ValueError("source registry schema is invalid")
    run_id = source["runId"]
    project = source["project"]
    if not re.fullmatch(r"[0-9a-f]{64}", run_id) or not isinstance(project, str) or not re.fullmatch(r"ink-color-qa-[0-9a-f]{12}", project):
        raise ValueError("source owner identity is invalid")
    if source["ownerLabel"] != {"key": "com.naraemedia.qa.owner", "value": run_id}:
        raise ValueError("source owner label is invalid")
    if source["sealed"] is not False or source["resources"] != []:
        raise ValueError("source registry is not the unchanged partial-startup state")
    if not isinstance(source["createdAtEpoch"], int) or source["createdAtEpoch"] > int(time.time()) + 5:
        raise ValueError("source registry time is invalid")
    intents = source["intents"]
    if not isinstance(intents, list):
        raise ValueError("source intents are invalid")
    keys = []
    for intent in intents:
        if not isinstance(intent, dict) or set(intent) != {"scope", "kind", "identity"}:
            raise ValueError("source intent schema is invalid")
        key = (intent["scope"], intent["kind"], intent["identity"])
        if intent["scope"] not in {"local", "docker"} or not all(isinstance(value, str) and value for value in key):
            raise ValueError("source intent value is invalid")
        keys.append(key)
    if len(keys) != len(set(keys)) or keys.count(("local", "temporary-root", root_arg)) != 1:
        raise ValueError("source intents do not name one exact recovery root")
    launcher_pids = [identity for scope, kind, identity in keys if scope == "local" and kind == "launcher-pid"]
    if len(launcher_pids) != 1 or not launcher_pids[0].isdigit():
        raise ValueError("source launcher identity is invalid")

    def assert_quiescent():
        try:
            os.kill(int(launcher_pids[0]), 0)
        except ProcessLookupError:
            pass
        except PermissionError as error:
            raise ValueError("source launcher quiescence cannot be proven") from error
        else:
            raise ValueError("source launcher is not quiescent")
        rows = subprocess.run(
            ["ps", "-axo", "pid=,ppid=,command="], check=True, capture_output=True, text=True, timeout=10
        ).stdout.splitlines()
        parsed = {}
        for row in rows:
            match = re.match(r"\s*(\d+)\s+(\d+)\s+(.*)", row)
            if match:
                parsed[int(match.group(1))] = (int(match.group(2)), match.group(3))
        ancestors = set()
        candidate = os.getpid()
        while candidate and candidate not in ancestors:
            ancestors.add(candidate)
            candidate = parsed.get(candidate, (0, ""))[0]
        tokens = (root_arg, run_id, project)
        if any(pid not in ancestors and any(token in command for token in tokens) for pid, (_, command) in parsed.items()):
            raise ValueError("another process still carries the recovery identity")

    assert_quiescent()

    parent = root.parent
    parent_info = parent.lstat()
    allowed_parent = str(parent) in {"/private/tmp", "/tmp"}
    if parent.is_symlink() or not stat.S_ISDIR(parent_info.st_mode) or not allowed_parent:
        raise ValueError("recovery parent identity is invalid")
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    root_info = os.fstat(root_fd)
    if not stat.S_ISDIR(root_info.st_mode) or stat.S_IMODE(root_info.st_mode) != 0o700 or root_info.st_uid != uid or root_info.st_dev != parent_info.st_dev:
        raise ValueError("recovery root metadata is invalid")
    marker_fd = os.open(".ink-color-qa-owned", os.O_RDONLY | os.O_NOFOLLOW, dir_fd=root_fd)
    marker_info = os.fstat(marker_fd)
    marker_bytes = os.read(marker_fd, 129)
    if not stat.S_ISREG(marker_info.st_mode) or stat.S_IMODE(marker_info.st_mode) != 0o600 or marker_info.st_uid != uid:
        raise ValueError("recovery marker metadata is invalid")
    if marker_bytes != (run_id + "\n").encode() or len(marker_bytes) > 65:
        raise ValueError("recovery marker does not match the source owner")
    if profile == "historical-r2" and (
        root_info.st_dev != historical["rootDevice"] or root_info.st_ino != historical["rootInode"]
        or int(root_info.st_birthtime * 1000) != historical["rootBirthtimeMs"] or int(root_info.st_mtime * 1000) != historical["rootMtimeMs"]
        or marker_info.st_dev != historical["markerDevice"] or marker_info.st_ino != historical["markerInode"]
        or int(marker_info.st_birthtime * 1000) != historical["markerBirthtimeMs"] or int(marker_info.st_mtime * 1000) != historical["markerMtimeMs"]
        or registry_info.st_dev != historical["registryDevice"] or registry_info.st_ino != historical["registryInode"]
        or parent_info.st_dev != historical["parentDevice"] or parent_info.st_ino != historical["parentInode"]
        or stat.S_IMODE(parent_info.st_mode) != 0o1777 or parent_info.st_uid != 0 or parent_info.st_gid != 0
        or root_info.st_gid != 0 or marker_info.st_gid != 0 or registry_info.st_gid != 20
    ):
        raise ValueError("historical r2 immutable identity changed")

    def scan_subtree():
        snapshot = []
        for directory, names, files in os.walk(root, topdown=True, followlinks=False):
            directory_path = pathlib.Path(directory)
            for name in sorted([*names, *files]):
                path = directory_path / name
                info = path.lstat()
                if stat.S_ISLNK(info.st_mode) or info.st_dev != root_info.st_dev or info.st_uid != uid:
                    raise ValueError("recovery subtree has an ownership or mount boundary")
                if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)) or stat.S_IMODE(info.st_mode) & 0o077:
                    raise ValueError("recovery subtree has an unsafe type or mode")
                snapshot.append((str(path.relative_to(root)), stable_identity(info)))
        return snapshot

    descendant_snapshot = scan_subtree()

    query_observations = []
    selectors = (
        ("owner-only", f"label=com.naraemedia.qa.owner={run_id}"),
        ("project-only", f"label=com.naraemedia.qa.project={project}"),
    )
    for kind in ("container", "network", "volume", "image"):
        for selector, filter_value in selectors:
            check_cancelled()
            argv = ["docker", kind, "ls"]
            if kind == "container":
                argv.append("-a")
            argv.extend(["-q", "--filter", filter_value])
            try:
                completed = subprocess.run(argv, check=True, capture_output=True, text=True, timeout=15)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                raise ValueError(f"fresh {kind} {selector} query failed") from error
            if completed.stderr or completed.stdout.split():
                raise ValueError(f"fresh {kind} {selector} query was not empty")
            query_observations.append({"kind": kind, "selector": selector, "candidateCount": 0, "exitCode": 0})
            check_cancelled()

    os.lseek(registry_fd, 0, os.SEEK_SET)
    if hashlib.sha256(os.read(registry_fd, 1024 * 1024 + 1)).hexdigest() != expected_digest:
        raise ValueError("source registry changed during recovery validation")
    if stable_identity(os.fstat(registry_fd)) != stable_identity(registry_info) or stable_identity(os.fstat(root_fd)) != stable_identity(root_info) or stable_identity(os.fstat(marker_fd)) != stable_identity(marker_info):
        raise ValueError("recovery metadata changed during validation")
    if scan_subtree() != descendant_snapshot:
        raise ValueError("recovery subtree metadata changed during validation")
    assert_quiescent()
    if not re.fullmatch(r"[0-9a-f]{40}", current_head):
        raise ValueError("current source revision is invalid")
    docker_intents = [intent for intent in intents if intent["scope"] == "docker"]
    payload = {
        "schemaVersion": 1,
        "recordType": "ink-color-recovery-registration",
        "scope": "registration-only",
        "profile": profile,
        "sealed": True,
        "recordId": hashlib.sha256(os.urandom(32)).hexdigest(),
        "sourceRevision": source_revision,
        "implementationRevision": current_head,
        "sourceRegistry": {
            "path": registry_arg,
            "sha256": expected_digest,
            "schemaVersion": 2,
            "sealed": False,
        },
        "owner": {"runId": run_id, "project": project},
        "originalIntents": intents,
        "unfulfilledDockerIntents": docker_intents,
        "root": {
            "path": root_arg,
            "device": root_info.st_dev,
            "inode": root_info.st_ino,
            "uid": root_info.st_uid,
            "gid": root_info.st_gid,
            "mode": stat.S_IMODE(root_info.st_mode),
            "ctimeNs": root_info.st_ctime_ns,
            "birthtimeNs": round(getattr(root_info, "st_birthtime", root_info.st_ctime) * 1_000_000_000),
            "markerDevice": marker_info.st_dev,
            "markerInode": marker_info.st_ino,
            "markerMatchesOwner": True,
            "descendantCount": len(descendant_snapshot),
        },
        "quiescence": {"launcherPid": launcher_pids[0], "state": "not-running"},
        "dockerObservation": {"candidateCount": 0, "queries": query_observations},
        "actions": {"dockerMutationCount": 0, "rootMutationCount": 0},
        "registeredAtEpoch": int(time.time()),
    }
    if record.exists() or record.is_symlink():
        raise ValueError("recovery registration already exists")
    check_cancelled()
    try:
        fd, temporary = tempfile.mkstemp(prefix=".recovery-registration.", dir=record.parent)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, separators=(",", ":"), sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        if os.environ.get("INK_QA_RECOVERY_INTERRUPT_STAGE") == "before-publish":
            raise InterruptedError("recovery registration interrupted before publish")
        if os.environ.get("INK_QA_RECOVERY_INTERRUPT_STAGE") == "pause-before-publish":
            deadline = time.monotonic() + 30
            while not cancelled_signal and time.monotonic() < deadline:
                time.sleep(0.01)
        # Block delivery across the cancellation decision and atomic publication.
        # A concurrent signal is acknowledged after this interval and may retain
        # a complete sealed record; wall-clock arrival does not order the commit.
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGTERM, signal.SIGINT})
        try:
            check_cancelled()
            os.link(temporary, record, follow_symlinks=False)
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        directory_fd = os.open(record.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        if os.environ.get("INK_QA_RECOVERY_INTERRUPT_STAGE") == "after-publish":
            raise InterruptedError("recovery registration interrupted after atomic publish")
        if os.environ.get("INK_QA_RECOVERY_INTERRUPT_STAGE") == "pause-after-publish":
            deadline = time.monotonic() + 30
            while not cancelled_signal and time.monotonic() < deadline:
                time.sleep(0.01)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)
finally:
    for fd in (marker_fd, root_fd, registry_fd):
        if fd >= 0:
            os.close(fd)
if cancelled_signal:
    raise SystemExit(128 + cancelled_signal)
print("PASS: sealed registration-only recovery record published without destructive action")
PY
}

run_bounded() {
    local seconds=$1
    shift
    python3 "$root/scripts/fixtures/task30-live-run.py" --timeout "$seconds" "$@"
}

file_permissions() {
    stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"
}

remove_temporary_root() {
    [[ -n "$temporary_root" ]] || return 1
    [[ ! -e "$temporary_root" ]] && return 0
    [[ ! -L "$temporary_root" ]] || return 1
    [[ -f "$temporary_root/.ink-color-qa-owned" ]] || return 1
    case "$temporary_root" in
        /private/tmp/narae-ink-color-qa.*|/tmp/narae-ink-color-qa.*)
            authorize_temporary_root_removal || return 143
            record_root_removal_intent
            cleanup_boundary root-after-authorization || return 143
            start_root_removal_executor || return 1
            cleanup_boundary root-removal-before-commit || return 143
            commit_root_removal_handoff || return 1
            cleanup_boundary root-after-commit-before-request || return 143
            printf '{"runId":"%s","executorPid":%s,"request":"delete-root"}\n' \
                "$run_id" "$root_executor_pid" > "$root_delete_request"
            chmod 600 "$root_delete_request"
            local find_status=0
            wait "$root_executor_pid" || find_status=$?
            active_cleanup_child_pid=""
            [[ $find_status -eq 0 ]]
            ;;
        *) return 1 ;;
    esac
}

authorize_temporary_root_removal() {
    [[ "$cleanup_retain" == false && "$signal_received" == false ]] || return 143
    printf '{"event":"root-authorized","ownerPid":%s,"executorIssued":false}\n' "$$" \
        >> "$evidence_dir/root-removal-lifecycle.jsonl"
}

record_root_removal_intent() {
    printf '{"event":"root-intent","ownerPid":%s,"executorIssued":false}\n' "$$" \
        >> "$evidence_dir/root-removal-lifecycle.jsonl"
}

start_root_removal_executor() {
    root_executor_registry="$evidence_dir/root-executor-registry.json"
    root_commit_receipt="$evidence_dir/root-delete-commit.json"
    root_delete_request="$evidence_dir/root-delete-request.json"
    root_executor_state="$evidence_dir/root-executor-state.json"
    export INK_QA_ROOT_EXECUTOR_REGISTRY="$root_executor_registry"
    python3 - "$temporary_root" "$temporary_root/.ink-color-qa-owned" "$run_id" "$project" \
        "$root_executor_registry" "$root_commit_receipt" "$root_delete_request" \
        "$root_executor_state" "$evidence_dir/root-removal-lifecycle.jsonl" <<'PY' &
import json
import os
import pathlib
import stat
import sys
import tempfile
import time

root = pathlib.Path(sys.argv[1])
marker = pathlib.Path(sys.argv[2])
run_id = sys.argv[3]
project = sys.argv[4]
registry, commit, request, state, trace = map(pathlib.Path, sys.argv[5:])
os.setpgid(0, 0)

def atomic(path, payload):
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, separators=(",", ":"), sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

pid = os.getpid()
atomic(state, {"executorPid": pid, "state": "waiting", "destructiveCommandIssued": False})
deadline = time.monotonic() + 30
while not request.exists():
    if time.monotonic() >= deadline:
        atomic(state, {"executorPid": pid, "state": "wait-timeout", "destructiveCommandIssued": False})
        raise SystemExit(124)
    time.sleep(0.02)
registry_value = json.loads(registry.read_text(encoding="utf-8"))
commit_value = json.loads(commit.read_text(encoding="utf-8"))
request_value = json.loads(request.read_text(encoding="utf-8"))
if not (
    stat.S_IMODE(registry.stat().st_mode) == 0o600
    and stat.S_IMODE(commit.stat().st_mode) == 0o600
    and registry_value == {"executorPid": pid, "ownerPid": os.getppid(), "project": project, "root": str(root), "runId": run_id, "sealed": True, "state": "waiting"}
    and commit_value == {"committed": True, "executorPid": pid, "issuedActionCount": 1, "project": project, "root": str(root), "runId": run_id, "sealed": True}
    and request_value == {"executorPid": pid, "request": "delete-root", "runId": run_id}
    and root.is_dir()
    and marker.read_text(encoding="utf-8").strip() == run_id
):
    atomic(state, {"executorPid": pid, "state": "validation-failed", "destructiveCommandIssued": False})
    raise SystemExit(97)
atomic(state, {"executorPid": pid, "state": "dispatching", "destructiveCommandIssued": True})
with trace.open("a", encoding="utf-8") as stream:
    stream.write(json.dumps({"event":"root-executor-command","ownerPid":int(registry_value["ownerPid"]),"executorPid":pid,"executorIssued":True}, separators=(",", ":")) + "\n")
os.execvp("find", ["find", str(root), "-depth", "-delete"])
PY
    root_executor_pid=$!
    active_cleanup_child_pid=$root_executor_pid
    local ready=false
    for _ in $(seq 1 200); do
        if python3 - "$root_executor_state" "$root_executor_pid" <<'PY'
import json
import pathlib
import stat
import sys

state = pathlib.Path(sys.argv[1])
expected = {
    "destructiveCommandIssued": False,
    "executorPid": int(sys.argv[2]),
    "state": "waiting",
}
try:
    value = json.loads(state.read_text(encoding="utf-8"))
except (FileNotFoundError, json.JSONDecodeError):
    raise SystemExit(1)
raise SystemExit(0 if stat.S_IMODE(state.stat().st_mode) == 0o600 and value == expected else 1)
PY
        then
            ready=true
            break
        fi
        kill -0 "$root_executor_pid" 2>/dev/null || break
        sleep 0.02
    done
    if [[ "$ready" != true ]]; then
        stop_root_removal_executor
        return 1
    fi
    printf '{"executorPid":%s,"ownerPid":%s,"project":"%s","root":"%s","runId":"%s","sealed":true,"state":"waiting"}\n' \
        "$root_executor_pid" "$$" "$project" "$temporary_root" "$run_id" > "$root_executor_registry"
    chmod 600 "$root_executor_registry"
    printf '{"event":"root-executor-ready","ownerPid":%s,"executorPid":%s,"executorIssued":false}\n' \
        "$$" "$root_executor_pid" >> "$evidence_dir/root-removal-lifecycle.jsonl"
}

stop_root_removal_executor() {
    local pid=${root_executor_pid:-}
    [[ -n "$pid" ]] || return 0
    terminate_exact_root_executor "$pid" || return 1
    active_cleanup_child_pid=""
    root_executor_pid=""
}

terminate_exact_root_executor() {
    local pid=$1 attempt=0
    [[ -n "$pid" ]] || return 0
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null && [[ $attempt -lt 100 ]]; do
        sleep 0.02
        attempt=$((attempt + 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
        kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
        attempt=0
        while kill -0 "$pid" 2>/dev/null && [[ $attempt -lt 100 ]]; do
            sleep 0.02
            attempt=$((attempt + 1))
        done
    fi
    wait "$pid" 2>/dev/null || true
    ! kill -0 "$pid" 2>/dev/null
}

reconcile_committed_root_handoff() {
    local count
    count=$(python3 - "$root_commit_receipt" "$evidence_dir/destructive-actions.jsonl" \
        "$run_id" "$project" "$temporary_root" "$root_executor_pid" <<'PY'
import json
import os
import pathlib
import tempfile
import sys

receipt_path, ledger_path = map(pathlib.Path, sys.argv[1:3])
run_id, project, root, executor_pid = sys.argv[3:]
expected = {
    "committed": True,
    "executorPid": int(executor_pid),
    "issuedActionCount": 1,
    "project": project,
    "root": root,
    "runId": run_id,
    "sealed": True,
}
if json.loads(receipt_path.read_text(encoding="utf-8")) != expected:
    raise SystemExit("ROOT_COMMIT_RECEIPT_INVALID")
rows = []
if ledger_path.exists():
    for line in ledger_path.read_text(encoding="utf-8").splitlines():
        if line:
            value = json.loads(line)
            if not isinstance(value, dict):
                raise SystemExit("ROOT_ACTION_LEDGER_INVALID")
            rows.append(value)
identity = f"executor:{executor_pid}"
matches = [row for row in rows if row.get("action") == "root-delete-committed-handoff"]
if any(row.get("identity") != identity for row in matches) or len(matches) > 1:
    raise SystemExit("ROOT_ACTION_LEDGER_CONFLICT")
if not matches:
    rows.append({"sequence": len(rows) + 1, "action": "root-delete-committed-handoff", "identity": identity})
else:
    matches[0]["sequence"] = rows.index(matches[0]) + 1
fd, temporary = tempfile.mkstemp(prefix=f".{ledger_path.name}.", dir=ledger_path.parent)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        for index, row in enumerate(rows, start=1):
            row["sequence"] = index
            stream.write(json.dumps(row, separators=(",", ":")) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, ledger_path)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
print(len(rows))
PY
)
    destructive_action_count=$count
}

commit_root_removal_handoff() {
    [[ "$cleanup_retain" == false && "$signal_received" == false ]] || return 143
    kill -0 "$root_executor_pid" 2>/dev/null || return 1
    python3 - "$root_executor_registry" "$root_executor_state" "$run_id" "$project" "$temporary_root" "$root_executor_pid" "$$" <<'PY'
import json
import os
import pathlib
import stat
import sys

registry, state = map(pathlib.Path, sys.argv[1:3])
run_id, project, root, executor_pid, owner_pid = sys.argv[3:]
expected_registry = {
    "executorPid": int(executor_pid), "ownerPid": int(owner_pid), "project": project,
    "root": root, "runId": run_id, "sealed": True, "state": "waiting",
}
expected_state = {"executorPid": int(executor_pid), "state": "waiting", "destructiveCommandIssued": False}
try:
    registry_value = json.loads(registry.read_text(encoding="utf-8"))
    state_value = json.loads(state.read_text(encoding="utf-8"))
except (FileNotFoundError, json.JSONDecodeError):
    raise SystemExit("ROOT_EXECUTOR_PID_VALIDATION_FAILED")
if not (
    stat.S_IMODE(registry.stat().st_mode) == 0o600
    and stat.S_IMODE(state.stat().st_mode) == 0o600
    and registry_value == expected_registry
    and state_value == expected_state
):
    raise SystemExit("ROOT_EXECUTOR_PID_VALIDATION_FAILED")
PY
    python3 - "$root_commit_receipt" "$run_id" "$project" "$temporary_root" "$root_executor_pid" <<'PY'
import json
import os
import pathlib
import tempfile
import sys

path = pathlib.Path(sys.argv[1])
payload = {"committed": True, "executorPid": int(sys.argv[5]), "issuedActionCount": 1, "project": sys.argv[3], "root": sys.argv[4], "runId": sys.argv[2], "sealed": True}
fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(payload, stream, separators=(",", ":"), sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY
    cleanup_boundary root-after-receipt-before-ledger || return 143
    reconcile_committed_root_handoff
    printf '{"event":"root-delete-committed","ownerPid":%s,"executorPid":%s,"executorIssued":true}\n' \
        "$$" "$root_executor_pid" >> "$evidence_dir/root-removal-lifecycle.jsonl"
}

record_destructive_action() {
    local action=$1 identity=$2
    destructive_action_count=$((destructive_action_count + 1))
    printf '{"sequence":%s,"action":"%s","identity":"%s"}\n' \
        "$destructive_action_count" "$action" "$identity" >> "$evidence_dir/destructive-actions.jsonl"
}

record_destructive_action_json() {
    local description=$1
    destructive_action_count=$((destructive_action_count + 1))
    python3 - "$description" "$destructive_action_count" "$evidence_dir/destructive-actions.jsonl" <<'PY'
import json
import pathlib
import sys

description = json.loads(sys.argv[1])
if (
    not isinstance(description, dict)
    or set(description) != {"action", "argv", "identity", "kind"}
    or description["action"] != "docker-delete"
    or not isinstance(description["argv"], list)
    or not all(isinstance(item, str) and item for item in description["argv"])
):
    raise SystemExit("exact destructive action description is invalid")
description["sequence"] = int(sys.argv[2])
with pathlib.Path(sys.argv[3]).open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(description, separators=(",", ":"), sort_keys=True) + "\n")
PY
}

docker_registry_action() {
    local action=$1
    shift
    umask 077
    python3 - "$action" "$registry_file" "$project" "$run_id" "$run_started_epoch" \
        "$evidence_dir/cleanup-docker.log" "$temporary_root" "$@" <<'PY'
import json
import os
import pathlib
import re
import stat
import subprocess
import socket
import sys
import tempfile
import time

action, registry_arg, project, run_id, started_arg, log_arg, temporary_root_arg, *extra = sys.argv[1:]
registry = pathlib.Path(registry_arg)
log_path = pathlib.Path(log_arg)
temporary_root = pathlib.Path(temporary_root_arg)
owner_key = "com.naraemedia.qa.owner"
project_key = "com.naraemedia.qa.project"
started = int(started_arg)
id_patterns = {
    "container": re.compile(r"[0-9a-f]{64}\Z"),
    "network": re.compile(r"[0-9a-f]{64}\Z"),
    "image": re.compile(r"sha256:[0-9a-f]{64}\Z"),
}
list_commands = {
    "container": ["docker", "container", "ls", "-a", "--no-trunc"],
    "network": ["docker", "network", "ls", "--no-trunc"],
    "volume": ["docker", "volume", "ls"],
    "image": ["docker", "image", "ls", "--no-trunc"],
}

def run(argv):
    with log_path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(argv, separators=(",", ":")) + "\n")
    return subprocess.run(argv, check=True, capture_output=True, text=True, timeout=30).stdout

def atomic_write(payload):
    registry.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".ink-color-ownership.", dir=registry.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, separators=(",", ":"), sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, registry)
        directory_fd = os.open(registry.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

def load_registry():
    info = registry.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError("registry is not a private mode-600 regular file")
    payload = json.loads(registry.read_text(encoding="utf-8"))
    required = {"schemaVersion", "runId", "project", "ownerLabel", "createdAtEpoch", "sealed", "intents", "resources"}
    if not isinstance(payload, dict) or set(payload) != required:
        raise ValueError("registry schema keys do not match v2")
    if payload["schemaVersion"] != 2:
        raise ValueError("registry version is invalid")
    if payload["runId"] != run_id or not re.fullmatch(r"[0-9a-f]{64}", run_id):
        raise ValueError("registry run ID is stale or invalid")
    if payload["project"] != project or payload["ownerLabel"] != {"key": owner_key, "value": run_id}:
        raise ValueError("registry project/owner is stale or invalid")
    created_epoch = payload["createdAtEpoch"]
    if not isinstance(created_epoch, int) or created_epoch < started or created_epoch > int(time.time()) + 5:
        raise ValueError("registry creation epoch is stale or invalid")
    if not isinstance(payload["intents"], list) or not isinstance(payload["resources"], list):
        raise ValueError("registry intents/resources are invalid")
    return payload

def parse_listing(kind):
    argv = list_commands[kind] + [
        "--filter", f"label={owner_key}={run_id}",
        "--filter", f"label={project_key}={project}",
        "--format", "{{json .}}",
    ]
    rows = []
    output = run(argv)
    for line in output.splitlines():
        value = json.loads(line)
        if not isinstance(value, dict):
            raise ValueError(f"{kind} listing row is not an object")
        rows.append(value)
    return rows

def listing_identity(kind, row):
    if kind == "volume":
        value = row.get("Name")
        if not isinstance(value, str) or not value:
            raise ValueError("volume listing has no exact Name")
        return value
    value = row.get("ID") or row.get("Id")
    if not isinstance(value, str) or not id_patterns[kind].fullmatch(value):
        raise ValueError(f"{kind} listing has no full immutable ID")
    return value

def inspect(kind, identity):
    value = json.loads(run(["docker", kind, "inspect", identity]))
    if not isinstance(value, list) or len(value) != 1 or not isinstance(value[0], dict):
        raise ValueError(f"{kind} inspect returned an unexpected shape")
    return value[0]

def labels_for(kind, value):
    labels = value.get("Labels") if kind in {"network", "volume"} else value.get("Config", {}).get("Labels")
    if not isinstance(labels, dict):
        raise ValueError(f"{kind} labels are missing")
    if labels.get(owner_key) != run_id or labels.get(project_key) != project:
        raise ValueError(f"{kind} ownership labels do not match")
    return {owner_key: run_id, project_key: project}

def created_for(kind, value):
    key = "CreatedAt" if kind == "volume" else "Created"
    created = value.get(key)
    if not isinstance(created, str) or not created:
        raise ValueError(f"{kind} creation identity is missing")
    return created

def inspected_identity(kind, value):
    identity = value.get("Name") if kind == "volume" else value.get("Id")
    if kind != "volume" and (not isinstance(identity, str) or not id_patterns[kind].fullmatch(identity)):
        raise ValueError(f"{kind} inspect has no full immutable ID")
    if kind == "volume" and (not isinstance(identity, str) or not identity):
        raise ValueError("volume inspect has no exact Name")
    return identity

def inspected_name(kind, value):
    if kind == "container":
        name = value.get("Name")
        return name.lstrip("/") if isinstance(name, str) else None
    if kind in {"network", "volume"}:
        name = value.get("Name")
        return name if isinstance(name, str) else None
    tags = value.get("RepoTags")
    return tags if isinstance(tags, list) and all(isinstance(tag, str) for tag in tags) else None

def discover():
    discovered = []
    for kind in ("container", "network", "volume", "image"):
        for row in parse_listing(kind):
            identity = listing_identity(kind, row)
            value = inspect(kind, identity)
            if inspected_identity(kind, value) != identity:
                raise ValueError(f"{kind} listing/inspection identity mismatch")
            resource = {
                "kind": kind,
                "createdAt": created_for(kind, value),
                "labels": labels_for(kind, value),
                "declaredName": inspected_name(kind, value),
            }
            resource["name" if kind == "volume" else "id"] = identity
            discovered.append(resource)
    return discovered

def intent_key(intent):
    if not isinstance(intent, dict) or set(intent) != {"scope", "kind", "identity"}:
        raise ValueError("registry intent schema is invalid")
    if intent["scope"] not in {"docker", "local"} or not isinstance(intent["kind"], str) or not isinstance(intent["identity"], str) or not intent["identity"]:
        raise ValueError("registry intent identity is invalid")
    return (intent["scope"], intent["kind"], intent["identity"])

def validate_local_intents(payload, allow_missing_root=False):
    keys = [intent_key(intent) for intent in payload["intents"]]
    if len(keys) != len(set(keys)):
        raise ValueError("duplicate registry intent")
    root_key = ("local", "temporary-root", str(temporary_root))
    if root_key not in keys:
        raise ValueError("temporary root intent is missing")
    if not re.fullmatch(r"/(?:private/)?tmp/narae-ink-color-qa\.[0-9a-f]{16}", str(temporary_root)):
        raise ValueError("temporary root path is outside the exact task namespace")
    if temporary_root.exists():
        root_info = temporary_root.lstat()
        marker = temporary_root / ".ink-color-qa-owned"
        if not stat.S_ISDIR(root_info.st_mode) or stat.S_IMODE(root_info.st_mode) != 0o700 or temporary_root.is_symlink():
            raise ValueError("temporary root ownership metadata is invalid")
        if not marker.is_file() or marker.is_symlink() or marker.read_text(encoding="utf-8").strip() != run_id:
            raise ValueError("temporary root ownership marker is invalid")
    elif not allow_missing_root:
        raise ValueError("registered temporary root is missing")
    for scope, kind, identity in keys:
        if scope != "local" or kind == "temporary-root":
            continue
        if kind == "credential-file":
            path = pathlib.Path(identity)
            try:
                resolved_root = temporary_root.resolve(strict=True)
                resolved_path = path.resolve(strict=True)
                resolved_path.relative_to(resolved_root)
            except (FileNotFoundError, RuntimeError, ValueError):
                raise ValueError("credential file escapes its registered temporary root")
            if resolved_path != path.absolute() or not path.is_file() or path.is_symlink() or stat.S_IMODE(path.stat().st_mode) != 0o600:
                raise ValueError("credential file ownership metadata is invalid")
        elif kind == "browser-profile":
            path = pathlib.Path(identity)
            if path.parent != temporary_root or not path.is_dir() or path.is_symlink() or stat.S_IMODE(path.stat().st_mode) != 0o700:
                raise ValueError("browser profile ownership metadata is invalid")
        elif kind == "port":
            if not identity.isdigit() or not 1 <= int(identity) <= 65535:
                raise ValueError("registered port is invalid")
        elif kind in {"process-intent", "browser-context", "launcher-pid"}:
            if not identity:
                raise ValueError("registered process/browser identity is invalid")
        else:
            raise ValueError(f"unsupported local intent kind: {kind}")

def validate_registry():
    payload = load_registry()
    if payload["sealed"] is not True:
        raise ValueError("registry is not sealed")
    validate_local_intents(payload)
    resources = payload["resources"]
    registered = {}
    expected_labels = {owner_key: run_id, project_key: project}
    for resource in resources:
        if not isinstance(resource, dict):
            raise ValueError("registry resource is not an object")
        kind = resource.get("kind")
        expected_keys = {"kind", "createdAt", "labels", "declaredName", "name" if kind == "volume" else "id"}
        if kind not in list_commands or set(resource) != expected_keys or resource.get("labels") != expected_labels:
            raise ValueError("registry resource schema/labels are invalid")
        identity = resource.get("name" if kind == "volume" else "id")
        if kind == "volume":
            if not isinstance(identity, str) or not identity:
                raise ValueError("registered volume Name is invalid")
        elif not isinstance(identity, str) or not id_patterns[kind].fullmatch(identity):
            raise ValueError(f"registered {kind} ID is not full and immutable")
        key = (kind, identity)
        if key in registered:
            raise ValueError("duplicate registry identity")
        value = inspect(kind, identity)
        if inspected_identity(kind, value) != identity or labels_for(kind, value) != expected_labels:
            raise ValueError("fresh inspection identity/ownership mismatch")
        if created_for(kind, value) != resource.get("createdAt"):
            raise ValueError("fresh inspection creation identity mismatch")
        if inspected_name(kind, value) != resource.get("declaredName"):
            raise ValueError("fresh inspection declared name mismatch")
        registered[key] = resource
    fresh = set()
    for kind in ("container", "network", "volume", "image"):
        for row in parse_listing(kind):
            fresh.add((kind, listing_identity(kind, row)))
    if fresh != set(registered):
        raise ValueError("fresh exact-label candidate set differs from sealed registry")
    docker_intents = {(kind, identity) for scope, kind, identity in map(intent_key, payload["intents"]) if scope == "docker"}
    bound_names = set()
    for resource in resources:
        names = resource["declaredName"] if resource["kind"] == "image" else [resource["declaredName"]]
        matching = {(resource["kind"], name) for name in names if (resource["kind"], name) in docker_intents}
        if len(matching) != 1:
            raise ValueError("bound Docker resource does not map to exactly one declared intent")
        bound_names.update(matching)
    if bound_names != docker_intents:
        raise ValueError("sealed Docker intent set differs from bound resources")
    return resources

if action == "init":
    if registry.exists():
        raise ValueError("ownership registry already exists")
    atomic_write({
        "schemaVersion": 2,
        "runId": run_id,
        "project": project,
        "ownerLabel": {"key": owner_key, "value": run_id},
        "createdAtEpoch": int(time.time()),
        "sealed": False,
        "intents": [
            {"scope": "local", "kind": "temporary-root", "identity": str(temporary_root)},
            {"scope": "local", "kind": "launcher-pid", "identity": extra[0]},
        ],
        "resources": [],
    })
elif action == "declare":
    if len(extra) != 3:
        raise ValueError("declare requires scope, kind, and identity")
    payload = load_registry()
    if payload["sealed"] is True:
        raise ValueError("cannot declare after registry seal")
    intent = {"scope": extra[0], "kind": extra[1], "identity": extra[2]}
    intent_key(intent)
    if intent_key(intent) in {intent_key(item) for item in payload["intents"]}:
        raise ValueError("duplicate registry intent")
    payload["intents"].append(intent)
    atomic_write(payload)
elif action == "bind":
    payload = load_registry()
    if payload["sealed"] is True:
        raise ValueError("cannot bind after registry seal")
    validate_local_intents(payload)
    docker_intents = {(kind, identity) for scope, kind, identity in map(intent_key, payload["intents"]) if scope == "docker"}
    discovered = discover()
    for resource in discovered:
        names = resource["declaredName"] if resource["kind"] == "image" else [resource["declaredName"]]
        if not any((resource["kind"], name) in docker_intents for name in names):
            raise ValueError("discovered Docker resource has no exact declared intent")
    payload["resources"] = discovered
    atomic_write(payload)
elif action == "seal":
    payload = load_registry()
    validate_local_intents(payload)
    payload["sealed"] = True
    atomic_write(payload)
    validate_registry()
elif action == "plan-cleanup":
    resources = validate_registry()
    plan = pathlib.Path(extra[0])
    fd, temporary = tempfile.mkstemp(prefix=".ink-color-cleanup-plan.", dir=plan.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(resources, stream, separators=(",", ":"), sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, plan)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
elif action == "validate-cleanup-plan":
    resources = validate_registry()
    plan = pathlib.Path(extra[0])
    plan_info = plan.lstat()
    planned = json.loads(plan.read_text(encoding="utf-8"))
    if not stat.S_ISREG(plan_info.st_mode) or stat.S_IMODE(plan_info.st_mode) != 0o600 or planned != resources:
        raise ValueError("cleanup plan does not exactly match the revalidated registry")
elif action in {"describe-cleanup-item", "delete-cleanup-item"}:
    payload = load_registry()
    if payload["sealed"] is not True:
        raise ValueError("cleanup deletion requires a sealed registry")
    plan = pathlib.Path(extra[0])
    plan_info = plan.lstat()
    planned = json.loads(plan.read_text(encoding="utf-8"))
    if not stat.S_ISREG(plan_info.st_mode) or stat.S_IMODE(plan_info.st_mode) != 0o600 or planned != payload["resources"]:
        raise ValueError("cleanup deletion plan differs from the sealed registry")
    order = {"container": 0, "network": 1, "volume": 2, "image": 3}
    resources = sorted(planned, key=lambda item: order[item["kind"]])
    if not resources:
        raise ValueError("cleanup deletion plan is empty")
    resource = resources[0]
    kind = resource["kind"]
    identity = resource.get("name", resource.get("id"))
    argv = ["docker", kind, "rm"]
    if kind in {"container", "volume"}:
        argv.append("-f")
    argv.append(identity)
    if action == "describe-cleanup-item":
        print(json.dumps({"action": "docker-delete", "argv": argv, "identity": identity, "kind": kind}, separators=(",", ":"), sort_keys=True))
        raise SystemExit
    run(argv)
    payload["resources"].remove(resource)
    names = resource["declaredName"] if kind == "image" else [resource["declaredName"]]
    matching = [item for item in payload["intents"] if item.get("scope") == "docker" and item.get("kind") == kind and item.get("identity") in names]
    if len(matching) != 1:
        raise ValueError("deleted resource does not map to exactly one registry intent")
    payload["intents"].remove(matching[0])
    atomic_write(payload)
    remaining = resources[1:]
    fd, temporary = tempfile.mkstemp(prefix=".ink-color-cleanup-plan.", dir=plan.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(remaining, stream, separators=(",", ":"), sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, plan)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
elif action == "verify":
    validate_registry()
elif action == "validate-ports":
    payload = load_registry()
    if payload["sealed"] is not True:
        raise ValueError("port validation requires a sealed ownership registry")
    validate_local_intents(payload)
    ports = sorted(int(identity) for scope, kind, identity in map(intent_key, payload["intents"]) if scope == "local" and kind == "port")
    interceptor_arg = os.environ.get("INK_QA_SOCKET_INTERCEPT_LOG")
    if interceptor_arg:
        interceptor = pathlib.Path(interceptor_arg)
        for port in ports:
            with interceptor.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps({"event": "port-allocation-intercepted", "port": port, "realBind": False, "registrySealed": True}, separators=(",", ":"), sort_keys=True) + "\n")
    else:
        for port in ports:
            probe = socket.socket()
            try:
                probe.bind(("127.0.0.1", port))
            finally:
                probe.close()
    report = pathlib.Path(extra[0])
    report.write_text(json.dumps({"registrySealed": True, "validatedPortCount": len(ports), "ports": ports}, separators=(",", ":"), sort_keys=True) + "\n", encoding="utf-8")
elif action == "validate-temp":
    payload = load_registry()
    validate_local_intents(payload)
elif action == "report":
    report = pathlib.Path(extra[0])
    try:
        rows = discover()
        payload = {"project": project, "runId": run_id, "status": "exact-query-complete", "exactLabelCandidates": rows}
    except Exception as error:
        payload = {"project": project, "runId": run_id, "status": "bounded-query-failed", "errorType": type(error).__name__, "exactLabelCandidates": []}
    report.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
else:
    raise SystemExit("unknown registry action")
PY
}

registry_declare() {
    docker_registry_action declare "$1" "$2" "$3"
}

register_docker_intents() {
    local kind=$1 identity
    shift
    for identity in "$@"; do
        registry_declare docker "$kind" "$identity"
    done
}

seal_cleanup_registry() {
    docker_registry_action seal
    [[ "$(file_permissions "$registry_file")" == 600 ]] || fail "cleanup registry mode is not 600"
    registry_receipt="$evidence_dir/docker-cleanup-registry-receipt.json"
    registry_receipt_checksum="$evidence_dir/docker-cleanup-registry-receipt.sha256"
    python3 - "$registry_file" "$registry_receipt" "$registry_receipt_checksum" \
        "$run_id" "$project" <<'PY'
import hashlib
import json
import os
import pathlib
import tempfile
import sys

source = pathlib.Path(sys.argv[1])
receipt = pathlib.Path(sys.argv[2])
checksum = pathlib.Path(sys.argv[3])
run_id = sys.argv[4]
project = sys.argv[5]
data = source.read_bytes()
value = json.loads(data)
if not (
    value.get("schemaVersion") == 2
    and value.get("sealed") is True
    and value.get("runId") == run_id
    and value.get("project") == project
):
    raise SystemExit("SEALED_REGISTRY_RECEIPT_INVALID")

def atomic_private_write(target, content):
    fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

digest = hashlib.sha256(data).hexdigest()
atomic_private_write(receipt, data)
atomic_private_write(checksum, f"{digest}  {receipt.name}\n".encode())
PY
    [[ "$(file_permissions "$registry_receipt")" == 600 ]] \
        || fail "cleanup registry receipt mode is not 600"
    [[ "$(file_permissions "$registry_receipt_checksum")" == 600 ]] \
        || fail "cleanup registry receipt checksum mode is not 600"
}

write_owner_override() {
    local service
    : > "$owner_file"
    printf 'services:\n' >> "$owner_file"
    for service in "$@"; do
        cat >> "$owner_file" <<EOF
  $service:
    labels:
      com.naraemedia.qa.owner: "$run_id"
      com.naraemedia.qa.project: "$project"
EOF
    done
}

add_network_owner_labels() {
    local network
    printf 'networks:\n' >> "$owner_file"
    for network in "$@"; do
        cat >> "$owner_file" <<EOF
  $network:
    labels:
      com.naraemedia.qa.owner: "$run_id"
      com.naraemedia.qa.project: "$project"
EOF
    done
}

add_volume_owner_labels() {
    local volume
    printf 'volumes:\n' >> "$owner_file"
    for volume in "$@"; do
        cat >> "$owner_file" <<EOF
  $volume:
    labels:
      com.naraemedia.qa.owner: "$run_id"
      com.naraemedia.qa.project: "$project"
EOF
    done
}

write_browser_owner_override() {
    local service
    : > "$owner_file"
    printf 'services:\n' >> "$owner_file"
    for service in frontend backend; do
        cat >> "$owner_file" <<EOF
  $service:
    labels:
      com.naraemedia.qa.owner: "$run_id"
      com.naraemedia.qa.project: "$project"
    build:
      labels:
        com.naraemedia.qa.owner: "$run_id"
        com.naraemedia.qa.project: "$project"
EOF
    done
    for service in postgres minio minio-init; do
        cat >> "$owner_file" <<EOF
  $service:
    labels:
      com.naraemedia.qa.owner: "$run_id"
      com.naraemedia.qa.project: "$project"
EOF
    done
    add_network_owner_labels ingress private
}

run_signal_aware_registry_action() {
    local child_pid child_status=0
    docker_registry_action "$@" &
    child_pid=$!
    active_cleanup_child_pid=$child_pid
    wait "$child_pid" || child_status=$?
    active_cleanup_child_pid=""
    if [[ "$signal_received" == true ]]; then
        kill -TERM "$child_pid" 2>/dev/null || true
        wait "$child_pid" 2>/dev/null || true
        return 143
    fi
    return "$child_status"
}

cleanup_boundary() {
    local stage=$1 fixture=${INK_QA_CLEANUP_SELF_TEST_FIXTURE:-} attempts=0 helper_pid helper_status=0
    printf '{"stage":"%s","retain":%s,"destructiveActions":%s}\n' \
        "$stage" "$cleanup_retain" "$destructive_action_count" >> "$evidence_dir/cleanup-boundaries.jsonl"
    if [[ "$fixture" == "signal-$stage" ]]; then
        attempts=1
    elif [[ "$fixture" == "signal-$stage-double" ]]; then
        attempts=2
    fi
    if [[ $attempts -gt 0 ]]; then
        python3 - "$registry_file" "$stage" "$attempts" "${FAKE_SIGNAL_LOG:-$evidence_dir/cleanup-signals.jsonl}" <<'PY' &
import json
import os
import pathlib
import signal
import sys

registry = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
stage = sys.argv[2]
attempts = int(sys.argv[3])
signal_log = pathlib.Path(sys.argv[4])
pids = [item["identity"] for item in registry["intents"] if item.get("scope") == "local" and item.get("kind") == "launcher-pid"]
if len(pids) != 1:
    raise SystemExit("exact registered launcher PID is unavailable")
target = int(pids[0])
for attempt in range(1, attempts + 1):
    with signal_log.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({"attempt": attempt, "stage": stage, "targetPid": target, "signal": "TERM"}, separators=(",", ":")) + "\n")
    try:
        os.kill(target, signal.SIGTERM)
    except ProcessLookupError:
        pass
PY
        helper_pid=$!
        wait "$helper_pid" || helper_status=$?
        [[ $helper_status -eq 0 ]] || cleanup_retain=true
    fi
    [[ "$signal_received" == false && "$cleanup_retain" == false ]]
}

retain_cleanup() {
    cleanup_retain=true
    if [[ "$cleanup_failed" == false ]]; then
        cleanup_failed=true
        cleanup_reason=$1
    fi
}

cleanup() {
    local incoming=$?
    local cleanup_failed=false
    local cleanup_reason=none
    local cleanup_plan="$evidence_dir/docker-cleanup-plan.json"
    local cleanup_count=0
    local next_action=""
    if [[ "$cleanup_started" == true ]]; then
        return
    fi
    cleanup_started=true
    set +e
    : > "$evidence_dir/destructive-actions.jsonl"
    : > "$evidence_dir/cleanup-boundaries.jsonl"
    : > "$evidence_dir/root-removal-lifecycle.jsonl"
    if [[ "$signal_received" == true ]]; then
        retain_cleanup launcher-signal-before-cleanup
    elif [[ "$cleanup_required" == true && -n "$project" ]]; then
        if ! run_signal_aware_registry_action plan-cleanup "$cleanup_plan" >> "$evidence_dir/cleanup-docker.stdout" \
            2> "$evidence_dir/cleanup-docker.stderr"; then
            retain_cleanup registry-validation-or-bounded-mutation-failed
        fi
        if [[ "$signal_received" == true ]]; then
            retain_cleanup launcher-signal-during-cleanup-validation
        elif [[ "$cleanup_failed" == false ]]; then
            while true; do
                cleanup_count=$(python3 -c 'import json,sys; value=json.load(open(sys.argv[1], encoding="utf-8")); print(len(value))' "$cleanup_plan")
                if [[ $cleanup_count -eq 0 ]]; then
                    cleanup_boundary final-empty-plan || retain_cleanup launcher-signal-at-final-empty-plan
                    break
                fi
                if [[ "$signal_received" == true ]]; then
                    retain_cleanup launcher-signal-before-exact-mutation
                    break
                fi
                if ! run_signal_aware_registry_action validate-cleanup-plan "$cleanup_plan" \
                    >> "$evidence_dir/cleanup-docker.stdout" 2>> "$evidence_dir/cleanup-docker.stderr"; then
                    retain_cleanup registry-revalidation-or-signal-before-exact-mutation
                    break
                fi
                if [[ "$signal_received" == true ]]; then
                    retain_cleanup launcher-signal-after-revalidation-before-exact-mutation
                    break
                fi
                if ! next_action=$(run_signal_aware_registry_action describe-cleanup-item "$cleanup_plan" \
                    2>> "$evidence_dir/cleanup-docker.stderr"); then
                    retain_cleanup exact-action-description-failed
                    break
                fi
                if [[ -z "$next_action" ]]; then
                    retain_cleanup empty-exact-action-description
                    break
                fi
                if ! record_destructive_action_json "$next_action"; then
                    retain_cleanup exact-action-journal-failed
                    break
                fi
                if ! run_signal_aware_registry_action delete-cleanup-item "$cleanup_plan" \
                    >> "$evidence_dir/cleanup-docker.stdout" 2>> "$evidence_dir/cleanup-docker.stderr"; then
                    retain_cleanup bounded-exact-mutation-failed
                    break
                fi
                if [[ "$signal_received" == true ]]; then
                    retain_cleanup launcher-signal-during-exact-mutation
                    break
                fi
            done
        fi
    fi
    if [[ "$cleanup_failed" == false && -n "$temporary_root" ]]; then
        cleanup_boundary local-validation-entry || retain_cleanup launcher-signal-at-local-validation-entry
        if [[ "$cleanup_retain" == false ]]; then
            if ! run_signal_aware_registry_action validate-temp >> "$evidence_dir/cleanup-docker.stdout" \
                2>> "$evidence_dir/cleanup-docker.stderr"; then
                retain_cleanup temporary-root-registry-validation-or-signal-failed
            fi
        fi
        if [[ "$cleanup_retain" == false ]]; then
            cleanup_boundary after-local-validation || retain_cleanup launcher-signal-after-local-validation
        fi
        if [[ "$cleanup_retain" == false ]]; then
            cleanup_boundary root-removal-entry || retain_cleanup launcher-signal-at-root-removal-entry
        fi
        if [[ "$cleanup_retain" == false ]]; then
            cleanup_boundary before-success-publication || retain_cleanup launcher-signal-before-success-publication
        fi
        if [[ "$cleanup_retain" == false ]]; then
            if remove_temporary_root; then
                temporary_root_removed=true
            else
                retain_cleanup temporary-root-removal-uncertain
            fi
        fi
        if [[ "$signal_received" == true ]]; then
            retain_cleanup launcher-signal-during-or-after-root-removal
        fi
    fi
    if [[ "$cleanup_failed" == true ]]; then
        docker_registry_action report "$evidence_dir/owned-residue.json" \
            >> "$evidence_dir/cleanup-docker.stdout" 2>> "$evidence_dir/cleanup-docker.stderr" || true
        local cleanup_verdict=owned-residue
        [[ $destructive_action_count -gt 0 ]] && cleanup_verdict=interrupted-partial
        printf 'cleanup=%s\nproject=%s\nreason=%s\ndestructive_actions_issued=%s\nfurther_docker_mutation_after_failure=none\nregistry_retained=true\nregistry=%s\nregistry_receipt=%s\nregistry_receipt_checksum=%s\ntemporary_root_removed=%s\nresidue_report=%s\n' \
            "$cleanup_verdict" "$project" "$cleanup_reason" "$destructive_action_count" "$registry_file" \
            "$registry_receipt" "$registry_receipt_checksum" "$temporary_root_removed" \
            "$evidence_dir/owned-residue.json" \
            > "$evidence_dir/cleanup.md"
        trap - EXIT HUP INT TERM
        [[ $signal_exit_code -ne 0 ]] && exit "$signal_exit_code"
        [[ $incoming -ne 0 ]] && exit "$incoming"
        exit 97
    fi
    printf 'cleanup=passed\nproject=%s\nregistered_owned_resources=0\ndestructive_actions_issued=%s\nregistry_receipt=%s\nregistry_receipt_checksum=%s\ntemporary_root_removed=true\n' \
        "$project" "$destructive_action_count" "$registry_receipt" "$registry_receipt_checksum" \
        > "$evidence_dir/cleanup.md"
    trap - EXIT HUP INT TERM
    [[ $signal_exit_code -ne 0 ]] && exit "$signal_exit_code"
    exit "$incoming"
}

on_signal() {
    local signal_number=$1
    signal_received=true
    cleanup_retain=true
    signal_exit_code=$((128 + signal_number))
    if [[ "$terminal_signal_finalizing" == true ]]; then
        return
    fi
    if [[ "$cleanup_started" == true ]]; then
        finalize_terminal_signal
    fi
    exit "$signal_exit_code"
}

finalize_terminal_signal() {
    if [[ "$terminal_signal_finalizing" == true ]]; then
        return
    fi
    terminal_signal_finalizing=true
    cleanup_retain=true
    signal_received=true
    local interrupted_executor_pid=${root_executor_pid:-$active_cleanup_child_pid}
    local executor_contained=true
    if [[ -n "$interrupted_executor_pid" ]]; then
        terminate_exact_root_executor "$interrupted_executor_pid" || executor_contained=false
    fi
    active_cleanup_child_pid=""
    local root_commit_issued=false
    if [[ -n "$root_commit_receipt" && -f "$root_commit_receipt" ]]; then
        if python3 - "$root_commit_receipt" "$run_id" "$project" "$temporary_root" \
            "$interrupted_executor_pid" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = {
    "committed": True,
    "executorPid": int(sys.argv[5]),
    "issuedActionCount": 1,
    "project": sys.argv[3],
    "root": sys.argv[4],
    "runId": sys.argv[2],
    "sealed": True,
}
raise SystemExit(0 if value == expected else 1)
PY
        then
            root_commit_issued=true
            reconcile_committed_root_handoff
        fi
    fi
    local root_state=unknown executor_alive=false
    if [[ -d "$temporary_root" && -f "$temporary_root/.ink-color-qa-owned" ]] \
        && [[ "$(cat "$temporary_root/.ink-color-qa-owned" 2>/dev/null)" == "$run_id" ]]; then
        root_state=retained
    elif [[ ! -e "$temporary_root" ]]; then
        root_state=removed
        temporary_root_removed=true
    fi
    if [[ "$executor_contained" != true ]] || { [[ -n "$interrupted_executor_pid" ]] && kill -0 "$interrupted_executor_pid" 2>/dev/null; }; then
        executor_alive=true
    fi
    umask 077
    printf '{"project":"%s","runId":"%s","status":"terminal-signal-retained","destructiveActionsIssued":%s,"rootCommitIssued":%s,"rootState":"%s","executorPid":"%s","executorAlive":%s}\n' \
        "$project" "$run_id" "$destructive_action_count" "$root_commit_issued" "$root_state" \
        "$interrupted_executor_pid" "$executor_alive" > "$evidence_dir/owned-residue.json"
    local cleanup_verdict=owned-residue
    if [[ "$root_commit_issued" == true ]]; then
        cleanup_verdict=interrupted-uncertain
    elif [[ $destructive_action_count -gt 0 ]]; then
        cleanup_verdict=interrupted-partial
    fi
    printf 'cleanup=%s\nproject=%s\nreason=absorbing-terminal-signal\ndestructive_actions_issued=%s\nfurther_mutation_after_signal=none\nregistry_retained=true\nregistry=%s\ntemporary_root_removed=%s\nroot_state=%s\nroot_commit_issued=%s\nroot_executor_pid=%s\nroot_executor_alive=%s\nresidue_report=%s\n' \
        "$cleanup_verdict" "$project" "$destructive_action_count" "$registry_file" \
        "$temporary_root_removed" "$root_state" "$root_commit_issued" "$interrupted_executor_pid" \
        "$executor_alive" "$evidence_dir/owned-residue.json" > "$evidence_dir/cleanup.md"
    trap - EXIT HUP INT TERM
    exit "$signal_exit_code"
}

choose_port() {
    local candidate
    for _ in 1 2 3 4 5; do
        candidate=$((16#$(openssl rand -hex 2) % 40000 + 20000))
        registry_declare local port "$candidate"
        if [[ -z "$(docker ps -q --filter "publish=$candidate")" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    fail "could not allocate an unused frontend port"
}

create_run() {
    local temporary_base
    run_id=$(openssl rand -hex 32)
    run_started_epoch=$(date +%s)
    project="ink-color-qa-${run_id:0:12}"
    if [[ -d /private/tmp ]]; then
        temporary_base=/private/tmp
    else
        temporary_base=/tmp
    fi
    temporary_root="$temporary_base/narae-ink-color-qa.${run_id:0:16}"
    compose_file="$temporary_root/compose.yml"
    owner_file="$temporary_root/owner.yml"
    registry_file="$evidence_dir/ownership-registry.json"
    export INK_QA_OWNERSHIP_REGISTRY="$registry_file"
    cleanup_required=true
    trap cleanup EXIT
    trap 'on_signal 1' HUP
    trap 'on_signal 2' INT
    trap 'on_signal 15' TERM
    docker_registry_action init "$$"
    mkdir -m 700 "$temporary_root"
    umask 077
    printf '%s\n' "$run_id" > "$temporary_root/.ink-color-qa-owned"
}

registered_compose_up() {
    local wait_seconds=$1 build_images=$2
    if [[ "$build_images" == true ]]; then
        run_bounded 1200 docker compose -p "$project" -f "$compose_file" -f "$owner_file" build \
            > "$evidence_dir/compose-build.log" 2>&1
        docker_registry_action bind
    fi
    run_bounded 300 docker compose -p "$project" -f "$compose_file" -f "$owner_file" create --no-build \
        > "$evidence_dir/compose-create.log" 2>&1
    if [[ "${INK_QA_CLEANUP_SELF_TEST_FIXTURE:-}" == interrupt-after-create-before-bind ]]; then
        fail "controlled interruption after Compose creation before immutable binding"
    fi
    docker_registry_action bind
    seal_cleanup_registry
    docker_registry_action validate-ports "$evidence_dir/port-validation.json"
    run_bounded "$wait_seconds" docker compose -p "$project" -f "$compose_file" -f "$owner_file" up -d --no-build --wait \
        > "$evidence_dir/compose-up.log" 2>&1
    docker_registry_action verify
}

write_service_file() {
    local name=$1
    local database=$2
    local target="$temporary_root/$name.pg_service.conf"
    umask 077
    printf '[%s]\nhost=postgres\nport=5432\ndbname=%s\nuser=%s\npassword=%s\n' \
        "$name" "$database" "$POSTGRES_USER" "$POSTGRES_PASSWORD" > "$target"
    chmod 600 "$target"
}

guard_in_container() {
    local service=$1
    local target=$2
    run_bounded 30 docker compose -p "$project" -f "$compose_file" exec -T \
        -e "PGSERVICEFILE=/qa/$service.pg_service.conf" -e "PGSERVICE=$service" \
        postgres sh /repo/scripts/release/check-ink-color-schema.sh "$target"
}

expect_guard() {
    local scenario=$1
    local expected=$2
    local service=$3
    local target=$4
    local output="$evidence_dir/$scenario.txt"
    local result=0
    guard_in_container "$service" "$target" > "$output" 2>&1 || result=$?
    if [[ "$expected" == pass ]]; then
        if [[ $result -ne 0 ]]; then
            printf 'FAIL: %s unexpectedly failed\n' "$scenario" >&2
            schema_failures=$((schema_failures + 1))
        elif [[ "$(cat "$output")" != "COMPATIBLE: $target" ]]; then
            printf 'FAIL: %s returned an ambiguous verdict\n' "$scenario" >&2
            schema_failures=$((schema_failures + 1))
        fi
    else
        if [[ $result -eq 0 ]]; then
            printf 'FAIL: %s unexpectedly passed\n' "$scenario" >&2
            schema_failures=$((schema_failures + 1))
        fi
    fi
    printf '%s status=%s expected=%s\n' "$scenario" "$result" "$expected" \
        >> "$evidence_dir/schema-summary.txt"
}

run_schema_preflight_tests() {
    create_run
    export POSTGRES_USER="inkqa_$(openssl rand -hex 4)"
    export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
    cat > "$compose_file" <<EOF
services:
  postgres:
    image: postgres:17.6-alpine
    environment:
      POSTGRES_DB: postgres
      POSTGRES_USER: \${POSTGRES_USER}
      POSTGRES_PASSWORD: \${POSTGRES_PASSWORD}
    volumes:
      - "$temporary_root:/qa:ro"
      - "$root:/repo:ro"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \$\$POSTGRES_USER -d postgres"]
      interval: 1s
      timeout: 2s
      retries: 30
EOF
    write_owner_override postgres
    add_network_owner_labels default
    register_docker_intents container "${project}-postgres-1"
    register_docker_intents network "${project}_default"
    registry_declare local process-intent schema-preflight
    local service_file
    for service_file in legacy new mixed uppercase extra broken; do
        registry_declare local credential-file "$temporary_root/$service_file.pg_service.conf"
    done
    write_service_file legacy legacy_catalog
    write_service_file new new_catalog
    write_service_file mixed mixed_catalog
    write_service_file uppercase uppercase_catalog
    write_service_file extra extra_catalog
    umask 077
    printf '[broken]\nhost=127.0.0.1\nport=1\ndbname=postgres\nuser=nobody\npassword=synthetic\nconnect_timeout=1\n' \
        > "$temporary_root/broken.pg_service.conf"
    chmod 600 "$temporary_root/broken.pg_service.conf"
    cat > "$evidence_dir/resources.md" <<EOF
project=$project
container=${project}-postgres-1
network=${project}_default
volume=none
image=postgres:17.6-alpine (reused, not owned)
port=none
temporary_root=$temporary_root
credential_files=legacy/new/mixed/uppercase/extra/broken pg_service.conf (mode 600)
server_pid=none
browser_context=none
EOF
    registered_compose_up 180 false

    for database in legacy_catalog new_catalog mixed_catalog uppercase_catalog extra_catalog; do
        run_bounded 30 docker compose -p "$project" -f "$compose_file" exec -T postgres \
            psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
            -c "CREATE DATABASE $database" >/dev/null
    done
    printf '%s\n' \
        'CREATE TABLE board (id bigint PRIMARY KEY);' \
        'CREATE TABLE signature_slot (id bigint PRIMARY KEY, background_color varchar(32));' |
        run_bounded 30 docker compose -p "$project" -f "$compose_file" exec -T postgres \
            psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d legacy_catalog
    printf '%s\n' \
        "CREATE TABLE board (id bigint PRIMARY KEY, signature_ink_color varchar(5) NOT NULL DEFAULT 'black' CHECK (signature_ink_color IN ('black', 'white')));" \
        'CREATE TABLE signature_slot (id bigint PRIMARY KEY);' |
        run_bounded 30 docker compose -p "$project" -f "$compose_file" exec -T postgres \
            psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d new_catalog
    printf '%s\n' \
        "CREATE TABLE board (id bigint PRIMARY KEY, signature_ink_color varchar(5) NOT NULL DEFAULT 'black' CHECK (signature_ink_color IN ('black', 'white')));" \
        'CREATE TABLE signature_slot (id bigint PRIMARY KEY, background_color varchar(32));' |
        run_bounded 30 docker compose -p "$project" -f "$compose_file" exec -T postgres \
            psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d mixed_catalog
    printf '%s\n' \
        "CREATE TABLE board (id bigint PRIMARY KEY, signature_ink_color varchar(5) NOT NULL DEFAULT 'BLACK' CHECK (signature_ink_color IN ('BLACK', 'WHITE')));" \
        'CREATE TABLE signature_slot (id bigint PRIMARY KEY);' |
        run_bounded 30 docker compose -p "$project" -f "$compose_file" exec -T postgres \
            psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d uppercase_catalog
    printf '%s\n' \
        "CREATE TABLE board (id bigint PRIMARY KEY, signature_ink_color varchar(5) NOT NULL DEFAULT 'black' CHECK (signature_ink_color IN ('black', 'white', 'red')));" \
        'CREATE TABLE signature_slot (id bigint PRIMARY KEY);' |
        run_bounded 30 docker compose -p "$project" -f "$compose_file" exec -T postgres \
            psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d extra_catalog

    : > "$evidence_dir/schema-summary.txt"
    expect_guard legacy-success pass legacy legacy
    expect_guard legacy-repeat pass legacy legacy
    expect_guard board-ink-success pass new board-ink
    expect_guard new-as-legacy-failure fail new legacy
    expect_guard mixed-board-ink-failure fail mixed board-ink
    expect_guard uppercase-board-ink-failure fail uppercase board-ink
    expect_guard extra-literal-board-ink-failure fail extra board-ink
    expect_guard connection-failure fail broken legacy
    expect_guard invalid-target-failure fail legacy invalid
    [[ $schema_failures -eq 0 ]] || fail "$schema_failures schema preflight expectations failed"
    printf '{"applicable":true,"catalogs":["legacy","new","mixed","uppercase","extra"],"outcome":"passed","scenarios":9}\n' \
        > "$evidence_dir/schema-result.json"
    printf 'PASS: 9 schema preflight scenarios\n'
}

run_browser_suite() {
    local suite_mode=$1
    local -a suite_files
    if [[ "$suite_mode" == full ]]; then
        suite_files=(
            e2e/board-signature-ink-color.spec.ts
            e2e/mobile-slot-label.spec.ts
            e2e/public-display-real-stack.spec.ts
        )
    elif [[ "$suite_mode" == public-baseline ]]; then
        suite_files=(e2e/public-display-real-stack.spec.ts)
    else
        suite_files=(e2e/ink-color-real-smoke.spec.ts)
    fi
    if [[ "$suite_mode" != smoke ]]; then
        local suite_file
        for suite_file in "${suite_files[@]}"; do
            [[ -f "$root/frontend/$suite_file" ]] || fail "required real-suite spec is missing: $suite_file"
        done
    fi
    create_run
    local frontend_port network_second network_third network_prefix
    frontend_port=$(choose_port)
    network_second=$((16#$(openssl rand -hex 1) % 200 + 20))
    network_third=$((16#$(openssl rand -hex 1) % 200 + 20))
    network_prefix="10.$network_second.$network_third"
    sed \
        -e "s|context: ../..$|context: $root|" \
        -e "s|context: ../../backend$|context: $root/backend|" \
        -e "s/172\\.30\\.0\\./$network_prefix./g" \
        "$root/infra/compose/compose.yml" > "$compose_file"
    write_browser_owner_override
    local credential_file browser_profile
    credential_file="$temporary_root/browser-credentials.json"
    browser_profile="$temporary_root/browser-profile"
    registry_declare local credential-file "$credential_file"
    registry_declare local credential-file "$temporary_root/secrets/master.key"
    registry_declare local browser-profile "$browser_profile"
    registry_declare local browser-context "playwright-$run_id"
    registry_declare local process-intent admin-bootstrap
    registry_declare local process-intent playwright
    register_docker_intents container \
        "${project}-frontend-1" "${project}-backend-1" "${project}-postgres-1" \
        "${project}-minio-1" "${project}-minio-init-1"
    register_docker_intents network "${project}_ingress" "${project}_private"
    register_docker_intents image "${project}-frontend:latest" "${project}-backend:latest"
    mkdir -m 700 "$temporary_root/secrets" "$temporary_root/postgres" "$temporary_root/minio" "$browser_profile"
    umask 077
    openssl rand 32 > "$temporary_root/secrets/master.key"

    export FRONTEND_PORT="$frontend_port" NARAE_DATA_ROOT="$temporary_root"
    export POSTGRES_DB=ink_color_qa POSTGRES_USER="inkqa_$(openssl rand -hex 4)"
    export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
    export MINIO_ROOT_USER="inkqa$(openssl rand -hex 4)"
    export MINIO_ROOT_PASSWORD="$(openssl rand -hex 24)"
    export APP_MINIO_BUCKET=ink-color-qa APP_CRYPTO_KEY_VERSION=1
    export APP_PUBLIC_ORIGIN=https://qa.example.invalid
    local admin_email admin_password
    admin_email="ink-color-$(openssl rand -hex 5)@example.invalid"
    admin_password="$(openssl rand -hex 24)Aa1!"
    printf '{"email":"%s","password":"%s"}\n' "$admin_email" "$admin_password" > "$credential_file"
    chmod 600 "$credential_file"
    [[ "$(file_permissions "$credential_file")" == 600 ]] || fail "credential file mode is not 600"

    cat > "$evidence_dir/resources.md" <<EOF
project=$project
containers=${project}-{frontend,backend,postgres,minio,minio-init}-1
networks=${project}_{ingress,private}
volumes=none (task-local bind directories only)
image_tags=${project}-frontend,${project}-backend
reused_images=postgres:17.6-alpine,minio/minio,minio/mc
port=$frontend_port
temporary_root=$temporary_root
credential_file=$credential_file (mode 600)
server_pid=compose-owned
browser_context=Playwright-owned
suite_mode=$suite_mode
EOF
    registered_compose_up 1200 true
    INK_QA_EMAIL="$admin_email" INK_QA_PASSWORD="$admin_password" \
        run_bounded 240 python3 - "$root/scripts/fixtures/task30-live-run.py" \
        "$compose_file" "$project" > "$temporary_root/admin-bootstrap.log" 2>&1 <<'PY'
import importlib.util
import os
import pathlib
import sys

runner_path = pathlib.Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("task30_live_runner", runner_path)
if spec is None or spec.loader is None:
    raise SystemExit("RUNNER_IMPORT_FAILED")
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)
runner.bootstrap(
    pathlib.Path(sys.argv[2]),
    runner.ProjectName(sys.argv[3]),
    os.environ["INK_QA_EMAIL"],
    os.environ["INK_QA_PASSWORD"],
)
PY

    export TMPDIR="$browser_profile"
    export TASK8_REAL_MODE=real TASK8_BASE_URL="http://127.0.0.1:$frontend_port"
    export TASK8_CREDENTIAL_FILE="$credential_file" TASK8_EVIDENCE_DIR="$evidence_dir"
    export TASK8_SUITE_MODE="$suite_mode"
    if [[ "$suite_mode" == smoke ]]; then
        run_bounded 180 bash -c 'cd "$1" && npx playwright test e2e/ink-color-real-smoke.spec.ts --config e2e/ink-color.real.config.ts --project=chromium --workers=1 --reporter=line' \
            ink-color-smoke "$root/frontend" > "$evidence_dir/playwright-real.log" 2>&1
        [[ -s "$evidence_dir/smoke.png" ]] || fail "real smoke screenshot is missing"
        [[ -s "$evidence_dir/smoke-result.json" ]] || fail "real smoke result is missing"
        python3 - "$evidence_dir/smoke-result.json" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text())
if value != {
    "browser": "chromium",
    "executed": 1,
    "nginxBacked": True,
    "outcome": "passed",
    "scenario": "login-and-create-synthetic-board",
    "skipped": 0,
}:
    raise SystemExit("SMOKE_RESULT_INVALID")
PY
        grep -Eq '1 passed' "$evidence_dir/playwright-real.log" || fail "Playwright did not report one passing test"
        printf 'command=bash scripts/fixtures/run-ink-color-qa.sh --smoke --evidence-dir <evidence>\nstatus=0\nexecuted=1\nskipped=0\nnginx_backed=true\nport=%s\n' \
            "$frontend_port" > "$evidence_dir/smoke-command.txt"
        printf 'PASS: real Chromium login and board creation through nginx\n'
        return
    fi

    if [[ "$suite_mode" == public-baseline ]]; then
        run_bounded 60 bash -c 'cd "$1" && npx playwright test e2e/public-display-real-stack.spec.ts --config e2e/ink-color.real.config.ts --project=chromium --workers=1 --list' \
            ink-color-public-list "$root/frontend" > "$evidence_dir/playwright-list.log" 2>&1
        local public_tests
        public_tests=$(python3 - "$evidence_dir/playwright-list.log" <<'PY'
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text()
match = re.search(r"Total: ([0-9]+) tests? in 1 file", text)
if match is None or "public-display-real-stack.spec.ts" not in text:
    raise SystemExit("PUBLIC_BASELINE_DISCOVERY_INVALID")
print(int(match.group(1)))
PY
)
        run_bounded 420 bash -c 'cd "$1" && npx playwright test e2e/public-display-real-stack.spec.ts --config e2e/ink-color.real.config.ts --project=chromium --workers=1 --reporter=line' \
            ink-color-public "$root/frontend" > "$evidence_dir/playwright-real.log" 2>&1
        python3 - "$evidence_dir/playwright-real.log" "$evidence_dir/public-baseline-result.json" "$public_tests" <<'PY'
import json
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text()
expected = int(sys.argv[3])
passed_matches = re.findall(r"([0-9]+) passed", text)
skipped_matches = re.findall(r"([0-9]+) skipped", text)
passed = int(passed_matches[-1]) if passed_matches else 0
skipped = int(skipped_matches[-1]) if skipped_matches else 0
if passed != expected or skipped != 0:
    raise SystemExit("PUBLIC_BASELINE_RESULT_INVALID")
pathlib.Path(sys.argv[2]).write_text(json.dumps({
    "browser": "chromium",
    "discovered": expected,
    "executed": passed,
    "nginxBacked": True,
    "outcome": "passed",
    "skipped": skipped,
    "suite": "public-display-real-stack",
}, indent=2, sort_keys=True) + "\n")
PY
        printf 'PASS: %s real Chromium public-display baseline tests\n' "$public_tests"
        return
    fi

    run_bounded 60 bash -c 'cd "$1" && npx playwright test e2e/board-signature-ink-color.spec.ts e2e/mobile-slot-label.spec.ts e2e/public-display-real-stack.spec.ts --config e2e/ink-color.real.config.ts --project=chromium --workers=1 --list' \
        ink-color-full-list "$root/frontend" > "$evidence_dir/playwright-list.log" 2>&1
    local expected_tests
    expected_tests=$(python3 - "$evidence_dir/playwright-list.log" "$evidence_dir/suite-plan.json" <<'PY'
import json
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text()
match = re.search(r"Total: ([0-9]+) tests? in ([0-9]+) files?", text)
required = [
    "board-signature-ink-color.spec.ts",
    "mobile-slot-label.spec.ts",
    "public-display-real-stack.spec.ts",
]
discovered = sorted(set(re.findall(r"[› ]([a-z0-9-]+\.spec\.ts):[0-9]+:[0-9]+", text)))
if match is None or discovered != required:
    raise SystemExit("REAL_SUITE_DISCOVERY_INVALID")
tests = int(match.group(1))
files = int(match.group(2))
if tests < len(required) or files != len(required):
    raise SystemExit("REAL_SUITE_DISCOVERY_COUNT_INVALID")
pathlib.Path(sys.argv[2]).write_text(json.dumps({
    "discoveredFiles": required,
    "discoveredTests": tests,
    "outcome": "ready",
}, indent=2, sort_keys=True) + "\n")
print(tests)
PY
)
    run_bounded 420 bash -c 'cd "$1" && npx playwright test e2e/board-signature-ink-color.spec.ts e2e/mobile-slot-label.spec.ts e2e/public-display-real-stack.spec.ts --config e2e/ink-color.real.config.ts --project=chromium --workers=1 --reporter=line' \
        ink-color-full "$root/frontend" > "$evidence_dir/playwright-real.log" 2>&1
    python3 - "$evidence_dir/playwright-real.log" "$evidence_dir/task8-real-result.json" "$expected_tests" <<'PY'
import json
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text()
expected = int(sys.argv[3])
passed_matches = re.findall(r"([0-9]+) passed", text)
skipped_matches = re.findall(r"([0-9]+) skipped", text)
passed = int(passed_matches[-1]) if passed_matches else 0
skipped = int(skipped_matches[-1]) if skipped_matches else 0
if passed != expected or skipped != 0:
    raise SystemExit("REAL_SUITE_RESULT_INVALID")
pathlib.Path(sys.argv[2]).write_text(json.dumps({
    "browser": "chromium",
    "discovered": expected,
    "executed": passed,
    "nginxBacked": True,
    "outcome": "passed",
    "skipped": skipped,
    "suites": ["board-signature-ink-color", "mobile-slot-label", "public-display-real-stack"],
}, indent=2, sort_keys=True) + "\n")
PY
    find "$evidence_dir" -type f -name '*.png' -size +0 -print -quit | grep -q . \
        || fail "full real suite produced no screenshot evidence"
    printf 'command=bash scripts/fixtures/run-ink-color-qa.sh --evidence-dir <evidence>\nstatus=0\nexecuted=%s\nskipped=0\nnginx_backed=true\nport=%s\n' \
        "$expected_tests" "$frontend_port" > "$evidence_dir/task8-command.txt"
    printf 'PASS: %s real Chromium tests across Task 8, mobile label, and public display suites\n' "$expected_tests"
}

run_restore_smoke() {
    create_run
    mkdir -m 700 "$temporary_root/postgres" "$temporary_root/minio" \
        "$temporary_root/backup" "$temporary_root/backup/db" "$temporary_root/backup/objects"
    export POSTGRES_DB=restore_catalog POSTGRES_USER="inkqa_$(openssl rand -hex 4)"
    export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
    export MINIO_ROOT_USER="inkqa$(openssl rand -hex 4)"
    export MINIO_ROOT_PASSWORD="$(openssl rand -hex 24)"
    export APP_MINIO_BUCKET=ink-color-restore
    registry_declare local credential-file "$temporary_root/restore.pg_service.conf"
    registry_declare local process-intent restore-smoke
    write_service_file restore "$POSTGRES_DB"
    cat > "$compose_file" <<EOF
services:
  postgres:
    image: postgres:17.6-alpine
    environment:
      POSTGRES_DB: \${POSTGRES_DB}
      POSTGRES_USER: \${POSTGRES_USER}
      POSTGRES_PASSWORD: \${POSTGRES_PASSWORD}
    volumes:
      - "$temporary_root/postgres:/var/lib/postgresql/data"
      - "$temporary_root:/qa"
      - "$root:/repo:ro"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \$\$POSTGRES_USER -d \$\$POSTGRES_DB"]
      interval: 1s
      timeout: 2s
      retries: 30
  minio:
    image: minio/minio:RELEASE.2025-09-07T16-13-09Z
    command: server /data
    environment:
      MINIO_ROOT_USER: \${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: \${MINIO_ROOT_PASSWORD}
    volumes:
      - "$temporary_root/minio:/data"
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:9000/minio/health/ready"]
      interval: 1s
      timeout: 2s
      retries: 30
  minio-client:
    image: minio/mc:RELEASE.2025-08-13T08-35-41Z
    entrypoint: ["/bin/sh", "-ec"]
    command: ["while :; do sleep 3600; done"]
    environment:
      MINIO_ROOT_USER: \${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: \${MINIO_ROOT_PASSWORD}
      APP_MINIO_BUCKET: \${APP_MINIO_BUCKET}
    volumes:
      - "$temporary_root:/qa"
    depends_on:
      minio:
        condition: service_healthy
EOF
    write_owner_override postgres minio minio-client
    add_network_owner_labels default
    register_docker_intents container \
        "${project}-postgres-1" "${project}-minio-1" "${project}-minio-client-1"
    register_docker_intents network "${project}_default"
    cat > "$evidence_dir/resources.md" <<EOF
project=$project
containers=${project}-{postgres,minio,minio-client}-1
network=${project}_default
volumes=none (task-local bind directories only)
image_tags=none (published PostgreSQL/MinIO images reused)
port=none
temporary_root=$temporary_root
credential_file=$temporary_root/restore.pg_service.conf (mode 600)
server_pid=compose-owned
browser_context=none
backup_scope=synthetic-disposable-v5-database-and-object-bucket
EOF
    registered_compose_up 180 false
    run_bounded 420 bash "$root/scripts/fixtures/ink-color-restore-smoke.sh" \
        "$root" "$compose_file" "$project" "$temporary_root" "$evidence_dir" \
        > "$evidence_dir/restore-run.log" 2>&1
    [[ -s "$evidence_dir/restore-result.json" ]] || fail "restore result is missing"
    [[ -s "$evidence_dir/restore-transcript.txt" ]] || fail "restore transcript is missing"
    python3 - "$evidence_dir/restore-result.json" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text())
if not (
    value["backup"]["quiesced"] is True
    and value["v6"] == {"boardInkGuardExit": 0, "legacyGuardExit": 1}
    and value["oldBinaryProbe"]["guardExit"] == 1
    and value["oldBinaryProbe"]["processStarted"] is False
    and value["oldBinaryProbe"]["catalogRemainedBoardInkStatus"] == 0
    and value["restored"]["legacyGuardExit"] == 0
    and value["restored"]["boardInkGuardExit"] == 1
    and value["beforeMigration"]["rowCounts"] == value["restored"]["rowCounts"]
    and value["beforeMigration"]["ciphertextSha256"] == value["restored"]["ciphertextSha256"]
    and value["beforeMigration"]["objectSha256"] == value["restored"]["objectSha256"]
):
    raise SystemExit("RESTORE_RESULT_INVALID")
PY
    printf 'PASS: disposable V5 to V6 release and same-point DB/object restore rehearsal\n'
}

run_cleanup_self_test() {
    create_run
    local fixture=${INK_QA_CLEANUP_SELF_TEST_FIXTURE:-valid}
    case "$fixture" in
        signal-local-validation-entry|signal-local-validation-entry-double|signal-after-local-validation|signal-after-local-validation-double|signal-final-empty-plan|signal-final-empty-plan-double|signal-root-removal-entry|signal-root-removal-entry-double|signal-before-success-publication|signal-before-success-publication-double|signal-root-after-receipt-before-ledger|signal-root-after-receipt-before-ledger-double|local-boundary-debug)
            local nested_credential="$temporary_root/secrets/master.key"
            registry_declare local process-intent cleanup-self-test-local-only
            registry_declare local credential-file "$nested_credential"
            mkdir -m 700 "$temporary_root/secrets"
            umask 077
            printf 'synthetic-local-boundary\n' > "$nested_credential"
            chmod 600 "$nested_credential"
            seal_cleanup_registry
            cat > "$evidence_dir/resources.md" <<EOF
project=$project
container=none
network=none
volume=none
image=none
port=none
temporary_root=$temporary_root
credential_file=$nested_credential
server_pid=none
browser_context=none
EOF
            cp "$registry_file" "$evidence_dir/registry-before-cleanup.json"
            chmod 600 "$evidence_dir/registry-before-cleanup.json"
            fail "controlled empty sealed local cleanup boundary probe"
            ;;
    esac
    export POSTGRES_DB=cleanup_probe POSTGRES_USER="inkqa_$(openssl rand -hex 4)"
    export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
    cat > "$compose_file" <<EOF
services:
  postgres:
    image: postgres:17.6-alpine
    environment:
      POSTGRES_DB: \${POSTGRES_DB}
      POSTGRES_USER: \${POSTGRES_USER}
      POSTGRES_PASSWORD: \${POSTGRES_PASSWORD}
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \$\$POSTGRES_USER -d \$\$POSTGRES_DB"]
      interval: 1s
      timeout: 2s
      retries: 30
    volumes:
      - probe-data:/var/lib/postgresql/data
volumes:
  probe-data:
EOF
    write_owner_override postgres
    add_network_owner_labels default
    add_volume_owner_labels probe-data
    register_docker_intents container "${project}-postgres-1"
    register_docker_intents network "${project}_default"
    register_docker_intents volume "${project}_probe-data"
    registry_declare local process-intent cleanup-self-test
    if [[ "$fixture" == local-contract ]]; then
        local probe_port nested_credential
        probe_port=$(choose_port)
        nested_credential="$temporary_root/secrets/master.key"
        registry_declare local credential-file "$nested_credential"
        mkdir -m 700 "$temporary_root/secrets"
        umask 077
        printf 'synthetic-local-contract\n' > "$nested_credential"
        chmod 600 "$nested_credential"
    fi
    cat > "$evidence_dir/resources.md" <<EOF
project=$project
container=${project}-postgres-1
network=${project}_default
volume=${project}_probe-data
image=postgres:17.6-alpine (reused, not owned)
port=none
temporary_root=$temporary_root
credential_file=none
server_pid=compose-owned
browser_context=none
EOF
    if [[ "$fixture" == recovery-build-failure ]]; then
        registered_compose_up 180 true
    else
        registered_compose_up 180 false
    fi
    case "$fixture" in
        valid|local-contract|resource-replaced|type-mismatch|polluted|cross-project|hung|misleading-prose|empty-query|deletion-failure|interrupt-after-create-before-bind|recovery-build-failure) ;;
        missing) find "$registry_file" -delete ;;
        corrupt) printf '{"schemaVersion":2,"resources":[' > "$registry_file" ;;
        wrong-version|unsealed|stale)
            python3 - "$registry_file" "${INK_QA_CLEANUP_SELF_TEST_FIXTURE}" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
fixture = sys.argv[2]
value = json.loads(path.read_text(encoding="utf-8"))
if fixture == "wrong-version":
            value["schemaVersion"] = 3
elif fixture == "unsealed":
    value["sealed"] = False
else:
    value["runId"] = "c" * 64
path.write_text(json.dumps(value, separators=(",", ":")) + "\n", encoding="utf-8")
PY
            ;;
        *) fail "unknown cleanup self-test fixture" ;;
    esac
    if [[ -f "$registry_file" ]]; then
        cp "$registry_file" "$evidence_dir/registry-before-cleanup.json"
        chmod 600 "$evidence_dir/registry-before-cleanup.json"
    else
        printf 'registry=missing\n' > "$evidence_dir/registry-before-cleanup.txt"
    fi
    find "$compose_file" -delete
    fail "controlled startup failure after owned compose file loss"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --schema-preflight-tests|--smoke|--restore-smoke|--full|--public-baseline|--cleanup-self-test|--recovery-registration)
            [[ -z "$mode" ]] || fail "choose exactly one mode"
            mode=$1
            shift
            ;;
        --evidence-dir)
            [[ $# -ge 2 ]] || fail "--evidence-dir requires a path"
            evidence_dir=$2
            shift 2
            ;;
        --recovery-root)
            [[ $# -ge 2 && -z "$recovery_root" ]] || fail "--recovery-root requires exactly one path"
            recovery_root=$2
            shift 2
            ;;
        --source-registry)
            [[ $# -ge 2 && -z "$recovery_source_registry" ]] || fail "--source-registry requires exactly one path"
            recovery_source_registry=$2
            shift 2
            ;;
        --expected-registry-sha256)
            [[ $# -ge 2 && -z "$recovery_expected_digest" ]] || fail "--expected-registry-sha256 requires exactly one digest"
            recovery_expected_digest=$2
            shift 2
            ;;
        --source-revision)
            [[ $# -ge 2 && -z "$recovery_source_revision" ]] || fail "--source-revision requires exactly one revision"
            recovery_source_revision=$2
            shift 2
            ;;
        --recovery-profile)
            [[ $# -ge 2 && -z "$recovery_profile" ]] || fail "--recovery-profile requires exactly one profile"
            recovery_profile=$2
            shift 2
            ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[[ -n "$mode" ]] || mode=--full
[[ -n "$evidence_dir" ]] || fail "--evidence-dir is required"
if [[ "$mode" == --recovery-registration ]]; then
    [[ -n "$recovery_root" && -n "$recovery_source_registry" && -n "$recovery_expected_digest" && -n "$recovery_source_revision" && -n "$recovery_profile" ]] \
        || fail "recovery registration requires exact root, source registry, digest, source revision, and profile"
    [[ "$recovery_profile" != synthetic-mount-test ]] || fail "recovery profile is disabled: synthetic-mount-test"
    [[ -d "$evidence_dir" && ! -L "$evidence_dir" ]] || fail "recovery evidence directory must already exist and must not be a symlink"
    evidence_dir=$(CDPATH= cd -- "$evidence_dir" && pwd)
else
    [[ -z "$recovery_root" && -z "$recovery_source_registry" && -z "$recovery_expected_digest" && -z "$recovery_source_revision" && -z "$recovery_profile" ]] \
        || fail "recovery arguments require --recovery-registration"
    evidence_dir=$(mkdir -p "$evidence_dir" && CDPATH= cd -- "$evidence_dir" && pwd)
fi
for command in docker openssl python3; do
    command -v "$command" >/dev/null 2>&1 || fail "required command not found: $command"
done
run_bounded 30 docker info >/dev/null 2>&1 || fail "Docker engine is unavailable"

case "$mode" in
    --schema-preflight-tests) run_schema_preflight_tests ;;
    --smoke) run_browser_suite smoke ;;
    --full) run_browser_suite full ;;
    --public-baseline) run_browser_suite public-baseline ;;
    --restore-smoke) run_restore_smoke ;;
    --cleanup-self-test) run_cleanup_self_test ;;
    --recovery-registration) register_recovery_only ;;
esac
