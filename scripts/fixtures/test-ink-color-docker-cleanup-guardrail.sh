#!/usr/bin/env bash
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
mode=""
evidence_dir=""
fake_root=""
recovery_root=""
recovery_cleanup_identity=""
quiescence_probe_pid=""
recovery_signal_shell_pid=""
recovery_signal_child_pid=""
scenario_filter="${INK_QA_GUARDRAIL_SCENARIO:-}"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
capture_recovery_root_identity() {
    local owned_root=$1 registry=$2 output=$3
    python3 - "$owned_root" "$registry" "$output" <<'PY'
import hashlib, json, os, pathlib, stat, sys, tempfile

root_arg, registry_arg, output_arg = sys.argv[1:]
root = pathlib.Path(root_arg); registry = pathlib.Path(registry_arg); output = pathlib.Path(output_arg)
if not root_arg.startswith(("/private/tmp/narae-ink-color-qa.", "/tmp/narae-ink-color-qa.")):
    raise SystemExit("cleanup identity root is outside the synthetic namespace")
uid = os.getuid()
root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
marker_fd = registry_fd = -1
try:
    root_info = os.fstat(root_fd)
    marker_fd = os.open(".ink-color-qa-owned", os.O_RDONLY | os.O_NOFOLLOW, dir_fd=root_fd)
    marker_info = os.fstat(marker_fd); marker = os.read(marker_fd, 129)
    registry_fd = os.open(registry, os.O_RDONLY | os.O_NOFOLLOW)
    registry_info = os.fstat(registry_fd); registry_bytes = os.read(registry_fd, 1024 * 1024 + 1)
    if not stat.S_ISDIR(root_info.st_mode) or stat.S_IMODE(root_info.st_mode) != 0o700 or root_info.st_uid != uid:
        raise SystemExit("cleanup root identity is unsafe")
    if not stat.S_ISREG(marker_info.st_mode) or stat.S_IMODE(marker_info.st_mode) != 0o600 or marker_info.st_uid != uid:
        raise SystemExit("cleanup marker identity is unsafe")
    source = json.loads(registry_bytes)
    if marker != (source["runId"] + "\n").encode():
        raise SystemExit("cleanup marker does not match registry")
    payload = {
        "root": root_arg,
        "rootIdentity": [root_info.st_dev, root_info.st_ino, root_info.st_uid, root_info.st_gid, stat.S_IMODE(root_info.st_mode)],
        "markerIdentity": [marker_info.st_dev, marker_info.st_ino, marker_info.st_uid, marker_info.st_gid, stat.S_IMODE(marker_info.st_mode)],
        "markerSha256": hashlib.sha256(marker).hexdigest(),
        "registry": registry_arg,
        "registryIdentity": [registry_info.st_dev, registry_info.st_ino, registry_info.st_uid, registry_info.st_gid, stat.S_IMODE(registry_info.st_mode)],
        "registrySha256": hashlib.sha256(registry_bytes).hexdigest(),
    }
    fd, temporary = tempfile.mkstemp(prefix=".recovery-cleanup-identity.", dir=output.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, separators=(",", ":"), sort_keys=True); stream.write("\n"); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
finally:
    for fd in (registry_fd, marker_fd, root_fd):
        if fd >= 0: os.close(fd)
PY
}

safe_remove_recovery_root() {
    local owned_root=$1 identity_file=$2
    python3 - "$owned_root" "$identity_file" <<'PY'
import hashlib, json, os, pathlib, re, stat, sys

root_arg, identity_arg = sys.argv[1:]
root = pathlib.Path(root_arg); identity_path = pathlib.Path(identity_arg); uid = os.getuid()
identity_fd = os.open(identity_path, os.O_RDONLY | os.O_NOFOLLOW)
try:
    identity_info = os.fstat(identity_fd)
    if not stat.S_ISREG(identity_info.st_mode) or stat.S_IMODE(identity_info.st_mode) != 0o600 or identity_info.st_uid != uid:
        raise ValueError("cleanup identity record is unsafe")
    identity = json.loads(os.read(identity_fd, 1024 * 1024 + 1))
finally:
    os.close(identity_fd)
if identity.get("root") != root_arg or not re.fullmatch(r"/(?:private/)?tmp/narae-ink-color-qa\.[0-9a-f]{16}", root_arg) or root_arg == "/private/tmp/narae-ink-color-qa.7338ba83ee9e2d65" or root.parent.resolve(strict=True) != root.parent:
    raise ValueError("cleanup root provenance changed")
parent_fd = os.open(root.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
root_fd = os.open(root.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
marker_fd = registry_fd = -1
try:
    root_info = os.fstat(root_fd)
    actual_root = [root_info.st_dev, root_info.st_ino, root_info.st_uid, root_info.st_gid, stat.S_IMODE(root_info.st_mode)]
    if actual_root != identity["rootIdentity"] or root_info.st_uid != uid or root_info.st_dev != root.parent.lstat().st_dev:
        raise ValueError("cleanup root identity changed")
    marker_fd = os.open(".ink-color-qa-owned", os.O_RDONLY | os.O_NOFOLLOW, dir_fd=root_fd)
    marker_info = os.fstat(marker_fd); marker = os.read(marker_fd, 129)
    actual_marker = [marker_info.st_dev, marker_info.st_ino, marker_info.st_uid, marker_info.st_gid, stat.S_IMODE(marker_info.st_mode)]
    if actual_marker != identity["markerIdentity"] or hashlib.sha256(marker).hexdigest() != identity["markerSha256"]:
        raise ValueError("cleanup marker identity changed")
    registry = pathlib.Path(identity["registry"])
    registry_fd = os.open(registry, os.O_RDONLY | os.O_NOFOLLOW)
    registry_info = os.fstat(registry_fd); registry_bytes = os.read(registry_fd, 1024 * 1024 + 1)
    actual_registry = [registry_info.st_dev, registry_info.st_ino, registry_info.st_uid, registry_info.st_gid, stat.S_IMODE(registry_info.st_mode)]
    if actual_registry != identity["registryIdentity"] or hashlib.sha256(registry_bytes).hexdigest() != identity["registrySha256"]:
        raise ValueError("cleanup registry identity changed")
    def subtree_identity(topdown=True):
        result = []
        for directory, names, files, directory_fd in os.fwalk(".", dir_fd=root_fd, topdown=topdown, follow_symlinks=False):
            directory_path = pathlib.Path(directory)
            directory_info = os.fstat(directory_fd)
            if directory_info.st_dev != root_info.st_dev or directory_info.st_uid != uid or stat.S_IMODE(directory_info.st_mode) & 0o077:
                raise ValueError("cleanup crossed an ownership, mode, or mount boundary")
            for name in sorted([*files, *names]):
                info = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
                if stat.S_ISLNK(info.st_mode) or info.st_dev != root_info.st_dev or info.st_uid != uid or stat.S_IMODE(info.st_mode) & 0o077 or not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
                    raise ValueError("cleanup descendant identity is unsafe")
                result.append((str(directory_path / name), info.st_dev, info.st_ino, info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)))
        return sorted(result)
    expected_subtree = subtree_identity()
    if subtree_identity() != expected_subtree:
        raise ValueError("cleanup subtree changed before teardown")
    if subtree_identity(topdown=False) != expected_subtree:
        raise ValueError("cleanup subtree changed during anchored audit; retained")
    current = os.stat(root.name, dir_fd=parent_fd, follow_symlinks=False)
    if [current.st_dev, current.st_ino, current.st_uid, current.st_gid, stat.S_IMODE(current.st_mode)] != identity["rootIdentity"]:
        raise ValueError("cleanup root changed before final removal")
    # dir_fd binds directory lookup, but unlink/rmdir cannot compare-and-remove
    # an expected inode. This same-UID namespace has no enforceable exclusion.
    # Even an unchanged audit therefore authorizes retention, never deletion.
    print(json.dumps({"outcome": "retained", "root": root_arg, "rootIdentity": actual_root, "rootMutationCount": 0, "reason": "namespace-exclusion-unavailable"}, sort_keys=True))
    raise ValueError("cleanup namespace exclusion unavailable; root retained without mutation")
finally:
    for fd in (registry_fd, marker_fd, root_fd, parent_fd):
        if fd >= 0: os.close(fd)
PY
}

cleanup() {
    local incoming=$?
    trap - EXIT HUP INT TERM
    if [[ -n "$recovery_signal_child_pid" ]]; then kill "$recovery_signal_child_pid" 2>/dev/null || true; wait "$recovery_signal_child_pid" 2>/dev/null || true; fi
    if [[ -n "$recovery_signal_shell_pid" ]]; then kill "$recovery_signal_shell_pid" 2>/dev/null || true; wait "$recovery_signal_shell_pid" 2>/dev/null || true; fi
    if [[ -n "$quiescence_probe_pid" ]]; then kill "$quiescence_probe_pid" 2>/dev/null || true; wait "$quiescence_probe_pid" 2>/dev/null || true; fi
    if [[ -n "$fake_root" && -d "$fake_root" ]]; then find "$fake_root" -depth -delete; fi
    exit "$incoming"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --red|--r2-red|--r4-red|--r5-red|--r6-red|--r7-red|--r8-red|--r9-red|--r10-red|--recovery-registration-red|--recovery-registration-tests|--recovery-registration-demo|--disabled-profile-test|--green) [[ -z "$mode" ]] || fail "choose exactly one mode"; mode=$1; shift ;;
        --evidence-dir) [[ $# -ge 2 ]] || fail "--evidence-dir requires a path"; evidence_dir=$2; shift 2 ;;
        *) fail "unknown argument: $1" ;;
    esac
done
[[ "$mode" == --red || "$mode" == --r2-red || "$mode" == --r4-red || "$mode" == --r5-red || "$mode" == --r6-red || "$mode" == --r7-red || "$mode" == --r8-red || "$mode" == --r9-red || "$mode" == --r10-red || "$mode" == --recovery-registration-red || "$mode" == --recovery-registration-tests || "$mode" == --recovery-registration-demo || "$mode" == --disabled-profile-test || "$mode" == --green ]] \
    || fail "a supported test mode is required"
[[ -n "$evidence_dir" ]] || fail "--evidence-dir is required"
evidence_dir=$(mkdir -p "$evidence_dir" && CDPATH= cd -- "$evidence_dir" && pwd)
if [[ "$mode" == --disabled-profile-test ]]; then
    disabled_root=/private/tmp/narae-ink-color-qa.0000000000000000/recovery-fixture
    disabled_source=/private/tmp/narae-ink-color-qa.0000000000000000/absent/ownership-registry.json
    disabled_bin="$evidence_dir/disabled-profile-bin"
    disabled_boundary_log="$evidence_dir/disabled-profile-boundary.log"
    disabled_docker_log="$evidence_dir/disabled-profile-docker.log"
    printf '{"action":"fixture-intent","root":"%s","creatingRoot":false,"mount":false,"image":false,"docker":false}\n' "$disabled_root" > "$evidence_dir/resource-journal.jsonl"
    mkdir -m 700 "$disabled_bin"
    printf '%s\n' '#!/bin/sh' \
        'printf '\''%s\n'\'' "$@" > "$DISABLED_PROFILE_BOUNDARY_LOG"' \
        'printf '\''{"mountpoint":"/private/tmp/narae-ink-color-qa.0000000000000000"}\n'\''' \
        'exit 0' > "$disabled_bin/bash"
    printf '%s\n' '#!/bin/sh' 'printf '\''docker-invoked\n'\'' > "$DISABLED_PROFILE_DOCKER_LOG"' 'exit 0' > "$disabled_bin/docker"
    chmod 700 "$disabled_bin/bash" "$disabled_bin/docker"
    set +e
    PATH="$disabled_bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    DISABLED_PROFILE_BOUNDARY_LOG="$disabled_boundary_log" \
    DISABLED_PROFILE_DOCKER_LOG="$disabled_docker_log" \
        /bin/bash "$root/scripts/fixtures/run-ink-color-qa.sh" --recovery-registration \
        --recovery-root "$disabled_root" --source-registry "$disabled_source" \
        --expected-registry-sha256 "$(printf '0%.0s' {1..64})" \
        --source-revision "$(git -C "$root" rev-parse HEAD)" \
        --recovery-profile synthetic-mount-test --evidence-dir "$evidence_dir" \
        > "$evidence_dir/disabled-profile.stdout" 2> "$evidence_dir/disabled-profile.stderr"
    disabled_rc=$?
    set -e
    printf '%s\n' "$disabled_rc" > "$evidence_dir/disabled-profile.exit-code"
    python3 - "$disabled_bin" <<'PY'
import pathlib
import sys

directory = pathlib.Path(sys.argv[1])
for name in ("bash", "docker"):
    (directory / name).unlink()
directory.rmdir()
PY
    [[ $disabled_rc -ne 0 ]] || fail "disabled recovery profile reported success"
    grep -Fxq 'FAIL: recovery profile is disabled: synthetic-mount-test' "$evidence_dir/disabled-profile.stderr" \
        || fail "disabled recovery profile did not return its public CLI error category"
    [[ ! -e "$disabled_root" && ! -e "$evidence_dir/recovery-registration.json" ]] \
        || fail "disabled recovery profile created a root or registration record"
    [[ ! -e "$disabled_boundary_log" && ! -e "$disabled_docker_log" ]] \
        || fail "disabled recovery profile reached mount verification or Docker"
    printf 'PASS: synthetic-mount-test rejected before root, receipt, mount, or Docker work\n'
    exit 0
fi
if [[ "$mode" == --recovery-registration-tests || "$mode" == --recovery-registration-demo ]]; then
    reduced_root=/private/tmp/narae-ink-color-qa.1111111111111111
    reduced_bin="$evidence_dir/reduced-recovery-bin"
    reduced_docker_log="$evidence_dir/docker-argv.tsv"
    reduced_mv_log="$evidence_dir/mv-argv.tsv"
    [[ ! -e "$reduced_root" ]] || fail "reduced recovery root must remain nonexistent"
    printf '{"action":"reduced-recovery-intent","externalRoot":false,"mount":false,"image":false,"dockerMutation":false}\n' \
        > "$evidence_dir/resource-journal.jsonl"
    mkdir -m 700 "$reduced_bin"
    printf '%s\n' '#!/bin/sh' \
        'printf '\''%s\t%s\n'\'' "${1:-}" "${2:-}" >> "$REDUCED_DOCKER_LOG"' \
        '[ "${1:-}" = info ] && exit 0' \
        'exit 92' > "$reduced_bin/docker"
    printf '%s\n' '#!/bin/sh' \
        'printf '\''%s\n'\'' "$*" >> "$REDUCED_MV_LOG"' \
        'exit 93' > "$reduced_bin/mv"
    chmod 700 "$reduced_bin/docker" "$reduced_bin/mv"

    reduced_invoke() {
        local label=$1 profile=$2 output rc=0
        output="$evidence_dir/$label"
        mkdir -m 700 "$output"
        PATH="$reduced_bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        REDUCED_DOCKER_LOG="$reduced_docker_log" REDUCED_MV_LOG="$reduced_mv_log" \
            /bin/bash "$root/scripts/fixtures/run-ink-color-qa.sh" --recovery-registration \
            --recovery-root "$reduced_root" --source-registry "$evidence_dir/ownership-registry.json" \
            --expected-registry-sha256 "$(printf '0%.0s' {1..64})" \
            --source-revision "$(git -C "$root" rev-parse HEAD)" \
            --recovery-profile "$profile" --evidence-dir "$output" \
            > "$output/stdout" 2> "$output/stderr" || rc=$?
        printf '%s\n' "$rc" > "$output/exit-code"
        [[ $rc -ne 0 && ! -e "$output/recovery-registration.json" ]] \
            || fail "$label did not fail closed without a record"
    }

    reduced_invoke normal-profile synthetic-test
    grep -Fxq 'synthetic recovery profile requires the isolated nonexistent fake-engine boundary' "$evidence_dir/normal-profile/stderr" \
        || fail "normal recovery profile lost its strict synthetic boundary"
    reduced_invoke malformed-profile $'synthetic-test\nignored'
    grep -Fxq 'recovery profile is invalid' "$evidence_dir/malformed-profile/stderr" \
        || fail "malformed recovery profile was not rejected"
    reduced_invoke historical-profile historical-r2
    grep -Fxq 'historical r2 recovery provenance does not match the fixed identity' "$evidence_dir/historical-profile/stderr" \
        || fail "historical recovery profile was not rejected before root access"
    reduced_invoke disabled-profile synthetic-mount-test
    grep -Fxq 'FAIL: recovery profile is disabled: synthetic-mount-test' "$evidence_dir/disabled-profile/stderr" \
        || fail "disabled recovery profile was not rejected"

    retention_fixture="$evidence_dir/retention-fixture"
    retention_identity="$evidence_dir/retention-identity.json"
    mkdir -m 700 "$retention_fixture"
    printf '{}\n' > "$retention_identity"
    chmod 600 "$retention_identity"
    set +e
    safe_remove_recovery_root "$retention_fixture" "$retention_identity" \
        > "$evidence_dir/retention.stdout" 2> "$evidence_dir/retention.stderr"
    retention_rc=$?
    set -e
    printf '%s\n' "$retention_rc" > "$evidence_dir/retention.exit-code"
    [[ $retention_rc -ne 0 && -d "$retention_fixture" ]] \
        || fail "generic recovery teardown did not retain under uncertain provenance"

    : > "$evidence_dir/signal-matrix.tsv"
    for signal_case in signal signal-repeat; do
        signal_output="$evidence_dir/$signal_case"
        signal_ready="$signal_output/ready"
        mkdir -m 700 "$signal_output"
        PATH="$reduced_bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        REDUCED_DOCKER_LOG="$reduced_docker_log" REDUCED_MV_LOG="$reduced_mv_log" \
        INK_QA_RECOVERY_INTERRUPT_STAGE=pause-before-validation \
        INK_QA_RECOVERY_SIGNAL_READY="$signal_ready" \
            /bin/bash "$root/scripts/fixtures/run-ink-color-qa.sh" --recovery-registration \
            --recovery-root "$reduced_root" --source-registry "$evidence_dir/ownership-registry.json" \
            --expected-registry-sha256 "$(printf '0%.0s' {1..64})" \
            --source-revision "$(git -C "$root" rev-parse HEAD)" \
            --recovery-profile synthetic-test --evidence-dir "$signal_output" \
            > "$signal_output/stdout" 2> "$signal_output/stderr" &
        reduced_signal_pid=$!
        for _ in $(seq 1 300); do
            [[ -e "$signal_ready" ]] && break
            kill -0 "$reduced_signal_pid" 2>/dev/null || break
            sleep 0.01
        done
        [[ -e "$signal_ready" ]] || fail "$signal_case did not reach the non-creating signal boundary"
        kill -TERM "$reduced_signal_pid"
        set +e
        wait "$reduced_signal_pid"
        signal_rc=$?
        set -e
        printf '%s\n' "$signal_rc" > "$signal_output/exit-code"
        [[ $signal_rc -eq 143 && ! -e "$signal_output/recovery-registration.json" ]] \
            || fail "$signal_case did not acknowledge TERM without a record"
        printf '%s\tTERM_ACKNOWLEDGED\tno-record\n' "$signal_case" >> "$evidence_dir/signal-matrix.tsv"
    done

    [[ ! -e "$reduced_root" && ! -s "$reduced_mv_log" ]] \
        || fail "reduced recovery path created or relocated an external root"
    python3 - "$reduced_bin" <<'PY'
import pathlib
import sys

directory = pathlib.Path(sys.argv[1])
for name in ("docker", "mv"):
    (directory / name).unlink()
directory.rmdir()
PY
    printf '{"externalRootCreated":false,"archiveOrRelocationCount":0,"rootTeardownCount":0,"mountMutationCount":0,"imageMutationCount":0,"dockerMutationCount":0}\n' \
        > "$evidence_dir/cleanup-receipt.json"
    printf 'PASS: reduced recovery validation, retention, and signal matrix; no external root or relocation\n'
    exit 0
fi
fake_root=$(mktemp -d "${TMPDIR:-/tmp}/ink-color-cleanup-guardrail.XXXXXX")
chmod 700 "$fake_root"
trap cleanup EXIT HUP INT TERM
printf '%s\n' "$fake_root" > "$evidence_dir/fake-root-path.txt"
mkdir -m 700 "$fake_root/bin" "$fake_root/state"

cat > "$fake_root/bin/docker" <<'PY'
#!/usr/bin/env python3
import json, os, pathlib, re, signal, stat, sys, time

argv = sys.argv[1:]
scenario = os.environ["FAKE_DOCKER_SCENARIO"]
state_dir = pathlib.Path(os.environ["FAKE_DOCKER_STATE"])
state_path = state_dir / "state.json"
argv_log = pathlib.Path(os.environ["FAKE_DOCKER_ARGV_LOG"])
mutation_log = pathlib.Path(os.environ["FAKE_DOCKER_MUTATION_LOG"])
spawn_log = pathlib.Path(os.environ["FAKE_DOCKER_SPAWN_LOG"])
with argv_log.open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(argv, separators=(",", ":")) + "\n")
state = json.loads(state_path.read_text()) if state_path.exists() else {"created": [], "started": False}
container_id, network_id = "a" * 64, "b" * 64
image_id = "sha256:" + "d" * 64
created = {"container":"2026-09-09T00:00:01Z","network":"2026-09-09T00:00:02Z","volume":"2026-09-09T00:00:03Z","image":"2026-09-09T00:00:04Z"}
def save(): state_path.write_text(json.dumps(state), encoding="utf-8")
def labels(project=None):
    return {"com.naraemedia.qa.owner":state["runId"],"com.naraemedia.qa.project":project or state["project"]}
def signal_launcher(stage, repeated=False):
    registry_value = json.loads(pathlib.Path(state["registry"]).read_text(encoding="utf-8"))
    launcher_pids = [item["identity"] for item in registry_value["intents"] if item.get("scope") == "local" and item.get("kind") == "launcher-pid"]
    if len(launcher_pids) != 1:
        raise SystemExit("exact registered launcher PID is unavailable")
    launcher = int(launcher_pids[0])
    attempts = 2 if repeated else 1
    for attempt in range(1, attempts + 1):
        with pathlib.Path(os.environ["FAKE_SIGNAL_LOG"]).open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"attempt":attempt,"stage":stage,"targetPid":launcher,"signal":"TERM"}, separators=(",", ":")) + "\n")
        try: os.kill(launcher, signal.SIGTERM)
        except ProcessLookupError: pass

registry_env = pathlib.Path(os.environ.get("INK_QA_OWNERSHIP_REGISTRY", "/nonexistent"))
if "project" not in state and registry_env.exists():
    registry_value = json.loads(registry_env.read_text(encoding="utf-8"))
    state.update(
        runId=registry_value["runId"],
        project=registry_value["project"],
        registry=str(registry_env),
        compose=str(state_dir / "local-only-compose-does-not-exist.yml"),
        started=True,
    )
    save()

if argv == ["info"]:
    print("synthetic fake engine"); raise SystemExit
if argv and argv[0] == "ps":
    raise SystemExit
if argv and argv[0] == "compose":
    if any(command in argv for command in ("build", "create", "up")):
        paths = [argv[i + 1] for i, value in enumerate(argv[:-1]) if value == "-f"]
        text = pathlib.Path(paths[-1]).read_text(encoding="utf-8")
        run_match = re.search(r'com\.naraemedia\.qa\.owner: "([0-9a-f]{64})"', text)
        project_match = re.search(r'com\.naraemedia\.qa\.project: "([^"]+)"', text)
        if not run_match or not project_match: raise SystemExit("owner override missing exact labels: " + repr(text))
        registry_path = pathlib.Path(os.environ.get(
            "INK_QA_OWNERSHIP_REGISTRY",
            str(pathlib.Path(paths[0]).parent / "docker-cleanup-registry.json"),
        ))
        if not registry_path.exists():
            with mutation_log.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(["UNREGISTERED_COMPOSE_MUTATION", *argv], separators=(",", ":")) + "\n")
            raise SystemExit("ownership registry missing before Compose mutation")
        registry_value = json.loads(registry_path.read_text(encoding="utf-8"))
        if stat.S_IMODE(registry_path.stat().st_mode) != 0o600:
            raise SystemExit("ownership registry is not mode 600 before Compose mutation")
        if not isinstance(registry_value.get("intents"), list) or not registry_value["intents"]:
            raise SystemExit("ownership registry has no typed intents before Compose mutation")
        state.update(runId=run_match.group(1), project=project_match.group(1), registry=str(registry_path), compose=paths[0])
        pathlib.Path(os.environ["FAKE_DOCKER_OWNER_SNAPSHOT"]).write_text(text, encoding="utf-8")
        snapshot = pathlib.Path(os.environ["FAKE_DOCKER_REGISTRY_SNAPSHOT"])
        if not snapshot.exists():
            snapshot.write_bytes(registry_path.read_bytes())
            os.chmod(snapshot, 0o600)
        with spawn_log.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(argv, separators=(",", ":")) + "\n")
        if "build" in argv:
            if scenario == "recovery-build-failure":
                save()
                raise SystemExit(42)
            state["created"] = sorted(set([*state.get("created", []), "image"]))
        elif "create" in argv:
            state["created"] = sorted(set([*state.get("created", []), "container", "network", "volume"]))
            save()
            if scenario == "signal-pre-bind": signal_launcher("pre-bind", repeated=True)
        elif "up" in argv:
            if registry_value.get("sealed") is not True:
                raise SystemExit("Compose start occurred before registry seal")
            if scenario == "missing-network-intent":
                registry_value["intents"] = [item for item in registry_value["intents"] if not (item.get("scope") == "docker" and item.get("kind") == "network")]
                registry_path.write_text(json.dumps(registry_value, separators=(",", ":")) + "\n", encoding="utf-8")
                os.chmod(registry_path, 0o600)
            if scenario == "credential-escape":
                for item in registry_value["intents"]:
                    if item.get("scope") == "local" and item.get("kind") == "credential-file":
                        item["identity"] = "/tmp/not-owned/master.key"
                registry_path.write_text(json.dumps(registry_value, separators=(",", ":")) + "\n", encoding="utf-8")
                os.chmod(registry_path, 0o600)
            if scenario == "signal-pre-start": signal_launcher("pre-start", repeated=True)
            state["started"] = True
        save()
    print("PASS cleanup Task 8 synthetic-success-prose cafe1234 similar-project"); raise SystemExit
if len(argv) >= 2 and argv[1] == "ls":
    kind = argv[0]
    filters = [argv[index + 1] for index, value in enumerate(argv[:-1]) if value == "--filter"]
    owner_only = len(filters) == 1 and filters[0].startswith("label=com.naraemedia.qa.owner=")
    project_only = len(filters) == 1 and filters[0].startswith("label=com.naraemedia.qa.project=")
    if scenario == "recovery-query-error" and kind == "container": raise SystemExit(70)
    if scenario == "recovery-query-hung" and kind == "container": time.sleep(60)
    if scenario == "recovery-metadata-race" and kind == "container":
        pathlib.Path(os.environ["FAKE_RECOVERY_RACE_FILE"]).chmod(0o644)
    if scenario == "recovery-candidate" and kind == "container":
        print("a" * 64); raise SystemExit
    if scenario == "recovery-owner-only-candidate" and kind == "container" and owner_only:
        print("a" * 64); raise SystemExit
    if scenario == "recovery-project-only-candidate" and kind == "container" and project_only:
        print("a" * 64); raise SystemExit
    cleanup_phase = state.get("started", False) and not pathlib.Path(state.get("compose", ".")).exists()
    if cleanup_phase and kind == "container":
        state["cleanupValidationPass"] = state.get("cleanupValidationPass", 0) + 1
        save()
        if state["cleanupValidationPass"] == 2 and scenario in {"signal-execute-single", "signal-execute-double"}:
            signal_launcher("execute-revalidation", repeated=scenario == "signal-execute-double")
    if cleanup_phase and scenario == "misleading-prose" and kind == "container":
        print("PASS cleanup Task 8 cafe1234 not-json"); raise SystemExit
    if cleanup_phase and scenario == "empty-query" and kind == "container": raise SystemExit
    if cleanup_phase and scenario == "signal-cleanup" and kind == "container" and not state.get("cleanupSignalSent", False):
        state["cleanupSignalSent"] = True
        save()
        signal_launcher("cleanup", repeated=True)
    rows = {"container":[{"ID":container_id}],"network":[{"ID":network_id}],"volume":[{"Name":state["project"]+"_probe-data"}],"image":[{"ID":image_id}]}[kind] if kind in state.get("created", []) else []
    if cleanup_phase and scenario == "polluted" and kind == "container": rows.append({"ID":"e" * 64})
    for row in rows: print(json.dumps(row, separators=(",", ":")))
    raise SystemExit
if len(argv) == 3 and argv[1] == "inspect":
    kind, identity = argv[0], argv[2]
    cleanup_phase = state.get("started", False) and not pathlib.Path(state.get("compose", ".")).exists()
    registry_path = pathlib.Path(state["registry"])
    if registry_path.exists():
        snapshot = pathlib.Path(os.environ["FAKE_DOCKER_REGISTRY_SNAPSHOT"])
        if not snapshot.exists(): snapshot.write_bytes(registry_path.read_bytes()); os.chmod(snapshot, 0o600)
    if cleanup_phase and scenario == "hung" and kind == "container": time.sleep(60)
    project = state["project"] + "-other" if cleanup_phase and scenario == "cross-project" and kind == "container" else state["project"]
    actual_created = "2026-09-09T00:10:01Z" if cleanup_phase and scenario == "resource-replaced" and kind == "container" else created[kind]
    actual_id = "f" * 64 if cleanup_phase and scenario == "type-mismatch" and kind == "container" else identity
    if kind == "container": value={"Id":actual_id,"Name":"/"+state["project"]+"-postgres-1","Created":actual_created,"Config":{"Labels":labels(project)}}
    elif kind == "network": value={"Id":actual_id,"Name":state["project"]+"_default","Created":actual_created,"Labels":labels(project)}
    elif kind == "volume": value={"Name":actual_id,"CreatedAt":actual_created,"Labels":labels(project)}
    else: value={"Id":actual_id,"RepoTags":[state["project"]+"-frontend:latest"],"Created":actual_created,"Config":{"Labels":labels(project)}}
    print(json.dumps([value], separators=(",", ":"))); raise SystemExit
if len(argv) >= 3 and argv[1] == "rm":
    with mutation_log.open("a", encoding="utf-8") as stream: stream.write(json.dumps(argv, separators=(",", ":")) + "\n")
    if scenario == "deletion-failure" and argv[0] == "container":
        raise SystemExit(65)
    state["created"] = [kind for kind in state.get("created", []) if kind != argv[0]]
    save()
    if scenario in {"signal-after-first-delete", "signal-after-first-delete-double"}:
        signal_launcher("after-first-delete", repeated=scenario.endswith("-double"))
    print(argv[-1]); raise SystemExit
raise SystemExit("unexpected fake Docker argv: " + repr(argv))
PY
chmod 700 "$fake_root/bin/docker"

cat > "$fake_root/bin/find" <<'PY'
#!/usr/bin/env python3
import json, os, pathlib, signal, sys, time

scenario = os.environ.get("FAKE_DOCKER_SCENARIO", "")
if scenario in {"r9-post-wait", "r9-post-wait-double"}:
    registry = json.loads(pathlib.Path(os.environ["INK_QA_OWNERSHIP_REGISTRY"]).read_text())
    launcher = [item["identity"] for item in registry["intents"] if item.get("scope") == "local" and item.get("kind") == "launcher-pid"]
    executor_registry = pathlib.Path(os.environ["INK_QA_ROOT_EXECUTOR_REGISTRY"])
    executor_pid = json.loads(executor_registry.read_text()).get("executorPid")
    with pathlib.Path(os.environ["FAKE_ROOT_EXECUTOR_LOG"]).open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({"event":"fake-find-post-wait","launcherPid":int(launcher[0]),"executorPid":executor_pid,"findPid":os.getpid(),"findPgid":os.getpgrp(),"realFindIssued":False}, separators=(",", ":")) + "\n")
    raise SystemExit(0)
if scenario in {"r7-executor-transition", "r7-executor-transition-double", "r8-resistant-find", "r8-resistant-find-double"}:
    registry = json.loads(pathlib.Path(os.environ["INK_QA_OWNERSHIP_REGISTRY"]).read_text())
    launcher = [item["identity"] for item in registry["intents"] if item.get("scope") == "local" and item.get("kind") == "launcher-pid"]
    if len(launcher) != 1:
        raise SystemExit("exact launcher PID missing at fake find transition")
    executor_pid = None
    executor_registry = pathlib.Path(os.environ.get("INK_QA_ROOT_EXECUTOR_REGISTRY", "/nonexistent"))
    if executor_registry.exists():
        executor_pid = json.loads(executor_registry.read_text()).get("executorPid")
    attempts = 2 if scenario.endswith("-double") else 1
    try:
        os.kill(int(launcher[0]), 0)
    except ProcessLookupError:
        pass
    else:
        log = pathlib.Path(os.environ["FAKE_ROOT_EXECUTOR_LOG"])
        with log.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"event":"fake-find-transition","launcherPid":int(launcher[0]),"executorPid":executor_pid,"findPid":os.getpid(),"findPgid":os.getpgrp(),"realFindIssued":False}, separators=(",", ":")) + "\n")
        with pathlib.Path(os.environ["FAKE_SIGNAL_LOG"]).open("a", encoding="utf-8") as stream:
            for attempt in range(1, attempts + 1):
                stream.write(json.dumps({"attempt":attempt,"stage":"executor-transition","targetPid":int(launcher[0]),"signal":"TERM"}, separators=(",", ":")) + "\n")
        for _ in range(attempts):
            try: os.kill(int(launcher[0]), signal.SIGTERM)
            except ProcessLookupError: pass
        if scenario.startswith("r8-resistant-find"):
            def ignored_term(signum, frame):
                with log.open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps({"event":"fake-find-ignored-term","findPid":os.getpid(),"findPgid":os.getpgrp(),"signal":"TERM"}, separators=(",", ":")) + "\n")
            signal.signal(signal.SIGTERM, ignored_term)
            while True: time.sleep(1)
        time.sleep(2)
os.execv("/usr/bin/find", ["find", *sys.argv[1:]])
PY
chmod 700 "$fake_root/bin/find"

cat > "$fake_root/debug-hook.sh" <<'BASH'
set -T
__ink_qa_debug_active=false
__ink_qa_debug_hook() {
    [[ "$__ink_qa_debug_active" == false ]] || return 0
    local matches=false
    case "${FAKE_DEBUG_SIGNAL_STAGE:-}" in
        before-root-call) [[ "$BASH_COMMAND" == remove_temporary_root ]] && matches=true ;;
        before-root-find) [[ "$BASH_COMMAND" == find*'-depth -delete'* ]] && matches=true ;;
        before-authorization-record) [[ "$BASH_COMMAND" == 'record_destructive_action temporary-root-remove "$temporary_root"' || "$BASH_COMMAND" == record_root_removal_intent ]] && matches=true ;;
        after-authorization) [[ "$BASH_COMMAND" == 'cleanup_boundary root-after-authorization' ]] && matches=true ;;
        before-root-command) [[ "$BASH_COMMAND" == find*'-depth -delete'* ]] && matches=true ;;
        after-root-dispatch) [[ "$BASH_COMMAND" == 'cleanup_boundary root-after-dispatch' ]] && matches=true ;;
        before-root-commit) [[ "$BASH_COMMAND" == commit_root_removal_handoff ]] && matches=true ;;
        after-root-commit) [[ "$BASH_COMMAND" == 'cleanup_boundary root-after-commit-before-request' ]] && matches=true ;;
        after-root-executor-wait) [[ "$BASH_COMMAND" == '[[ $find_status -eq 0 ]]' ]] && matches=true ;;
    esac
    [[ "$matches" == true ]] || return 0
    __ink_qa_debug_active=true
    local target
    target=$(python3 - "$INK_QA_OWNERSHIP_REGISTRY" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if value.get("sealed") is not True:
    raise SystemExit("registry is not sealed at DEBUG signal boundary")
pids = [item["identity"] for item in value["intents"] if item.get("scope") == "local" and item.get("kind") == "launcher-pid"]
if len(pids) != 1:
    raise SystemExit("exact registered launcher PID is unavailable")
print(pids[0])
PY
)
    local attempts=${FAKE_DEBUG_SIGNAL_ATTEMPTS:-1}
    python3 - "$FAKE_SIGNAL_LOG" "$attempts" "$FAKE_DEBUG_SIGNAL_STAGE" "$target" "$BASH_COMMAND" <<'PY'
import json
import os
import pathlib
import signal
import sys

attempts = int(sys.argv[2])
target = int(sys.argv[4])
with pathlib.Path(sys.argv[1]).open("a", encoding="utf-8") as stream:
    for attempt in range(1, attempts + 1):
        stream.write(json.dumps({"attempt": attempt, "stage": sys.argv[3], "targetPid": target, "signal": "TERM", "bashCommand": sys.argv[5]}, separators=(",", ":")) + "\n")
for _ in range(attempts):
    try:
        os.kill(target, signal.SIGTERM)
    except ProcessLookupError:
        pass
PY
}
trap '__ink_qa_debug_hook' DEBUG
BASH
chmod 600 "$fake_root/debug-hook.sh"

run_scenario() {
    local scenario=$1 scenario_dir="$evidence_dir/$1" status=0 fixture=$1 debug_env="" debug_stage=""
    if [[ "$scenario" == signal-cleanup || "$scenario" == signal-execute-single || "$scenario" == signal-execute-double || "$scenario" == signal-after-first-delete || "$scenario" == signal-after-first-delete-double || "$scenario" == missing-network-intent ]]; then
        fixture=valid
    elif [[ "$scenario" == credential-escape ]]; then
        fixture=local-contract
    elif [[ "$scenario" == debug-before-root-call || "$scenario" == debug-before-root-find ]]; then
        fixture=valid
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=${scenario#debug-}
    elif [[ "$scenario" == signal-root-removal-entry ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=before-root-call
    elif [[ "$scenario" == signal-root-removal-entry-double ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=before-root-commit
    elif [[ "$scenario" == r6-before-authorization-record || "$scenario" == r6-before-authorization-record-double ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=before-authorization-record
    elif [[ "$scenario" == r6-after-authorization || "$scenario" == r6-after-authorization-double ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=after-authorization
    elif [[ "$scenario" == r6-before-root-command || "$scenario" == r6-before-root-command-double ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=before-root-command
    elif [[ "$scenario" == r6-after-root-dispatch || "$scenario" == r6-after-root-dispatch-double ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=after-root-dispatch
    elif [[ "$scenario" == r7-before-commit || "$scenario" == r7-before-commit-double ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=before-root-commit
    elif [[ "$scenario" == r7-after-commit || "$scenario" == r7-after-commit-double ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=after-root-commit
    elif [[ "$scenario" == r7-executor-transition || "$scenario" == r7-executor-transition-double ]]; then
        fixture=local-boundary-debug
    elif [[ "$scenario" == r8-increment || "$scenario" == r8-increment-double ]]; then
        fixture=signal-root-after-receipt-before-ledger
        [[ "$scenario" == *-double ]] && fixture+=-double
    elif [[ "$scenario" == r8-resistant-find || "$scenario" == r8-resistant-find-double ]]; then
        fixture=local-boundary-debug
    elif [[ "$scenario" == r9-post-wait || "$scenario" == r9-post-wait-double ]]; then
        fixture=local-boundary-debug
        debug_env="$fake_root/debug-hook.sh"
        debug_stage=after-root-executor-wait
    fi
    mkdir -p -m 700 "$scenario_dir"
    : > "$scenario_dir/fake-docker-argv.log"
    : > "$scenario_dir/fake-docker-mutations.log"
    : > "$scenario_dir/fake-docker-spawns.log"
    : > "$scenario_dir/fake-signal.log"
    : > "$scenario_dir/port-order.log"
    : > "$scenario_dir/socket-interceptor.jsonl"
    : > "$scenario_dir/fake-root-executor.jsonl"
    find "$fake_root/state" -type f -delete
    PATH="$fake_root/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    DOCKER_HOST="unix://$fake_root/host-docker-must-not-exist.sock" \
    FAKE_DOCKER_SCENARIO="$scenario" FAKE_DOCKER_STATE="$fake_root/state" \
    FAKE_DOCKER_ARGV_LOG="$scenario_dir/fake-docker-argv.log" \
    FAKE_DOCKER_MUTATION_LOG="$scenario_dir/fake-docker-mutations.log" \
    FAKE_DOCKER_SPAWN_LOG="$scenario_dir/fake-docker-spawns.log" \
    FAKE_SIGNAL_LOG="$scenario_dir/fake-signal.log" \
    FAKE_PORT_ORDER_LOG="$scenario_dir/port-order.log" \
    FAKE_DOCKER_REGISTRY_SNAPSHOT="$scenario_dir/synthetic-registry.json" \
    FAKE_DOCKER_OWNER_SNAPSHOT="$scenario_dir/owner-overlay.yml" \
    FAKE_ROOT_EXECUTOR_LOG="$scenario_dir/fake-root-executor.jsonl" \
    FAKE_DEBUG_SIGNAL_STAGE="$debug_stage" FAKE_DEBUG_SIGNAL_ATTEMPTS="$([[ "$scenario" == *-double ]] && echo 2 || echo 1)" \
    BASH_ENV="$debug_env" INK_QA_SOCKET_INTERCEPT_LOG="$scenario_dir/socket-interceptor.jsonl" \
    INK_QA_CLEANUP_SELF_TEST_FIXTURE="$fixture" \
        bash "$root/scripts/fixtures/run-ink-color-qa.sh" --cleanup-self-test --evidence-dir "$scenario_dir/launcher" \
        > "$scenario_dir/launcher.stdout" 2> "$scenario_dir/launcher.stderr" || status=$?
    printf '%s\n' "$status" > "$scenario_dir/launcher.exit-code"
    [[ $status -ne 0 ]] || fail "$scenario launcher unexpectedly returned zero"
    [[ ! -e "$fake_root/host-docker-must-not-exist.sock" ]] || fail "$scenario created the forbidden Docker socket"
    local launcher_root
    launcher_root=$(sed -n 's/^temporary_root=//p' "$scenario_dir/launcher/resources.md")
    if [[ "$scenario" == valid || "$scenario" == local-contract ]]; then
        [[ $status -eq 1 ]] || fail "$scenario cleanup returned unexpected status $status"
        [[ ! -e "$launcher_root" ]] || fail "valid cleanup retained its temporary root"
        printf 'temporary_root_after_launcher=removed\n' > "$scenario_dir/temporary-root-state.txt"
        if [[ "$scenario" == local-contract ]]; then
            python3 - "$scenario_dir/launcher/docker-cleanup-registry-receipt.json" "$scenario_dir/launcher/port-validation.json" <<'PY'
import json
import pathlib
import sys

registry = json.loads(pathlib.Path(sys.argv[1]).read_text())
report = json.loads(pathlib.Path(sys.argv[2]).read_text())
ports = [item["identity"] for item in registry["intents"] if item["scope"] == "local" and item["kind"] == "port"]
credentials = [item["identity"] for item in registry["intents"] if item["scope"] == "local" and item["kind"] == "credential-file"]
if registry.get("sealed") is not True or report.get("registrySealed") is not True or report.get("validatedPortCount") != 1 or report.get("ports") != [int(ports[0])]:
    raise SystemExit("port was not validated from an exact intent in a sealed registry")
if len(credentials) != 1 or not credentials[0].endswith("/secrets/master.key"):
    raise SystemExit("nested credential intent was not retained in the sealed registry")
PY
            python3 - "$scenario_dir/socket-interceptor.jsonl" "$scenario_dir/launcher/docker-cleanup-registry-receipt.json" <<'PY'
import json
import pathlib
import sys

events = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line]
registry = json.loads(pathlib.Path(sys.argv[2]).read_text())
ports = [int(item["identity"]) for item in registry["intents"] if item["scope"] == "local" and item["kind"] == "port"]
if len(events) != 1 or events[0].get("realBind") is not False or events[0].get("registrySealed") is not True or [events[0].get("port")] != ports:
    raise SystemExit("socket interception did not prove sealed intent with realBind=false")
PY
        fi
    else
        if [[ "$scenario" == r6-after-root-dispatch || "$scenario" == r6-after-root-dispatch-double \
            || "$scenario" == r7-after-commit || "$scenario" == r7-after-commit-double \
            || "$scenario" == r7-executor-transition || "$scenario" == r7-executor-transition-double ]]; then
            printf 'temporary_root_after_launcher=%s\n' "$([[ -d "$launcher_root" ]] && echo retained || echo removed-after-issued-action)" \
                > "$scenario_dir/temporary-root-state.txt"
        else
            [[ -d "$launcher_root" ]] || fail "$scenario removed the temporary root after cleanup failure"
            [[ "$(cat "$launcher_root/.ink-color-qa-owned")" =~ ^[0-9a-f]{64}$ ]] \
                || fail "$scenario retained a temporary root with an invalid marker"
            printf 'temporary_root_after_launcher=retained\n' > "$scenario_dir/temporary-root-state.txt"
        fi
        [[ -s "$scenario_dir/launcher/owned-residue.json" ]] \
            || fail "$scenario retained resources without a sanitized residue report"
        [[ -s "$scenario_dir/launcher/cleanup.md" ]] \
            || fail "$scenario retained resources without a cleanup receipt"
        if [[ "$scenario" != deletion-failure && "$scenario" != signal-after-first-delete && "$scenario" != signal-after-first-delete-double ]]; then
            [[ ! -s "$scenario_dir/fake-docker-mutations.log" ]] \
                || fail "$scenario reached deletion mutation after an ownership failure"
        fi
        if [[ "$scenario" == signal-* || "$scenario" == r6-* || "$scenario" == r7-* || "$scenario" == r8-* ]]; then
            local expected_signals=1
            case "$scenario" in
                *-double|signal-pre-bind|signal-pre-start|signal-cleanup) expected_signals=2 ;;
            esac
            python3 - "$scenario_dir/launcher/ownership-registry.json" "$scenario_dir/fake-signal.log" "$expected_signals" <<'PY'
import json
import pathlib
import sys

registry = json.loads(pathlib.Path(sys.argv[1]).read_text())
signals = [json.loads(line) for line in pathlib.Path(sys.argv[2]).read_text().splitlines() if line]
expected = int(sys.argv[3])
launcher_pids = [item["identity"] for item in registry["intents"] if item["scope"] == "local" and item["kind"] == "launcher-pid"]
if len(launcher_pids) != 1 or len(signals) != expected or any(str(item.get("targetPid")) != launcher_pids[0] or item.get("signal") != "TERM" for item in signals):
    raise SystemExit("signal probe did not target the exact registered launcher PID")
PY
        fi
        case "$scenario" in
            r6-before-authorization-record*|r6-after-authorization*|r6-before-root-command*)
                [[ ! -s "$scenario_dir/launcher/destructive-actions.jsonl" ]] \
                    || fail "$scenario recorded an executor action before root dispatch"
                grep -Fq 'reason=absorbing-terminal-signal' "$scenario_dir/launcher/cleanup.md" \
                    || fail "$scenario did not enter terminal signal finalization"
                ;;
            r6-after-root-dispatch*)
                [[ "$(wc -l < "$scenario_dir/launcher/destructive-actions.jsonl" | tr -d ' ')" == 1 ]] \
                    || fail "$scenario did not retain exactly one issued root action"
                grep -Fq 'cleanup=interrupted-partial' "$scenario_dir/launcher/cleanup.md" \
                    || fail "$scenario did not report partial/interrupted cleanup"
                ;;
            r7-before-commit*)
                [[ ! -s "$scenario_dir/launcher/destructive-actions.jsonl" ]] \
                    || fail "$scenario recorded an issued action before commit"
                grep -Fq 'cleanup=owned-residue' "$scenario_dir/launcher/cleanup.md" \
                    || fail "$scenario did not report retained pre-commit interruption"
                ;;
            r7-after-commit*|r7-executor-transition*)
                python3 - "$scenario_dir/launcher/destructive-actions.jsonl" \
                    "$scenario_dir/launcher/root-delete-commit.json" <<'PY'
import json
import pathlib
import sys

actions = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line]
commit = json.loads(pathlib.Path(sys.argv[2]).read_text())
if len(actions) != 1 or actions[0].get("action") != "root-delete-committed-handoff":
    raise SystemExit(f"post-commit action ledger mismatch: {actions!r}")
if commit.get("committed") is not True or commit.get("issuedActionCount") != 1 or commit.get("sealed") is not True:
    raise SystemExit(f"durable commit receipt mismatch: {commit!r}")
PY
                grep -Fq 'cleanup=interrupted-uncertain' "$scenario_dir/launcher/cleanup.md" \
                    || fail "$scenario did not report interrupted-uncertain"
                ;;
            r8-increment*|r8-resistant-find*)
                python3 - "$scenario_dir/launcher/destructive-actions.jsonl" \
                    "$scenario_dir/launcher/root-delete-commit.json" "$scenario_dir/launcher/cleanup.md" <<'PY'
import json
import pathlib
import sys

actions = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line]
commit = json.loads(pathlib.Path(sys.argv[2]).read_text())
cleanup = pathlib.Path(sys.argv[3]).read_text()
if len(actions) != 1 or actions[0].get("action") != "root-delete-committed-handoff":
    raise SystemExit(f"committed receipt was not reconciled to one action: {actions!r}")
if commit.get("sealed") is not True or commit.get("issuedActionCount") != 1:
    raise SystemExit(f"sealed committed receipt mismatch: {commit!r}")
if "cleanup=interrupted-uncertain" not in cleanup or "cleanup=passed" in cleanup:
    raise SystemExit("post-commit terminal cleanup verdict is not truthful")
PY
                ;;
            r9-post-wait*)
                python3 - "$scenario_dir/launcher/destructive-actions.jsonl" \
                    "$scenario_dir/launcher/root-delete-commit.json" "$scenario_dir/launcher/cleanup.md" "$mode" <<'PY'
import json
import pathlib
import sys

actions = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line]
commit = json.loads(pathlib.Path(sys.argv[2]).read_text())
cleanup = pathlib.Path(sys.argv[3]).read_text()
if len(actions) != 1 or actions[0] != {"sequence": 1, "action": "root-delete-committed-handoff", "identity": f"executor:{commit['executorPid']}"}:
    raise SystemExit(f"post-wait action ledger mismatch: {actions!r}")
if commit.get("sealed") is not True or commit.get("issuedActionCount") != 1:
    raise SystemExit(f"post-wait sealed receipt mismatch: {commit!r}")
if sys.argv[4] == "--r9-red":
    if "cleanup=interrupted-uncertain" in cleanup:
        raise SystemExit("r9 RED unexpectedly retained the committed executor identity")
elif "cleanup=interrupted-uncertain" not in cleanup or "cleanup=passed" in cleanup:
    raise SystemExit("post-wait terminal cleanup verdict is not truthful")
PY
                ;;
        esac
        if [[ "$scenario" == credential-escape ]]; then
            python3 - "$scenario_dir/socket-interceptor.jsonl" <<'PY'
import json
import pathlib
import sys

events = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line]
if len(events) != 1 or events[0].get("realBind") is not False or events[0].get("registrySealed") is not True:
    raise SystemExit("credential-escape reached a real or unsealed port allocation")
PY
        fi
        if [[ "$scenario" == signal-* ]]; then
            grep -Fq 'cleanup=passed' "$scenario_dir/launcher/cleanup.md" \
                && fail "$scenario falsely reported cleanup=passed"
        fi
        case "$scenario" in
            signal-local-validation-entry*|signal-after-local-validation*|signal-final-empty-plan*|signal-root-removal-entry*|signal-before-success-publication*)
                [[ ! -s "$scenario_dir/launcher/destructive-actions.jsonl" ]] \
                    || fail "$scenario issued a destructive action before honoring TERM"
                [[ -f "$launcher_root/secrets/master.key" ]] \
                    || fail "$scenario removed its registered nested credential"
                ;;
            signal-after-first-delete*)
                python3 - "$scenario_dir/fake-docker-mutations.log" "$scenario_dir/launcher/destructive-actions.jsonl" <<'PY'
import json
import pathlib
import sys

mutations = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line]
actions = [json.loads(line) for line in pathlib.Path(sys.argv[2]).read_text().splitlines() if line]
if mutations != [["container", "rm", "-f", "a" * 64]]:
    raise SystemExit(f"late TERM did not stop after one irreversible fake deletion: {mutations!r}")
if len(actions) != 1 or actions[0].get("action") != "docker-delete":
    raise SystemExit(f"late TERM action ledger is not truthful: {actions!r}")
PY
                grep -Fq 'cleanup=interrupted-partial' "$scenario_dir/launcher/cleanup.md" \
                    || fail "$scenario did not report truthful interrupted-partial cleanup"
                ;;
        esac
        python3 - "$launcher_root" "$scenario_dir/launcher/ownership-registry.json" \
            "$scenario_dir/launcher/owned-residue.json" "$scenario_dir/launcher/cleanup.md" \
            "$scenario_dir/launcher/destructive-actions.jsonl" "$scenario_dir/fake-docker-mutations.log" \
            "$scenario_dir/launcher/root-executor-registry.json" \
            "$scenario_dir/launcher/root-delete-commit.json" \
            "$scenario_dir/launcher/root-executor-state.json" \
            "$scenario_dir/retained-observation.json" <<'PY'
import json
import os
import pathlib
import stat
import sys

root, registry, residue, cleanup, actions, mutations, executor_registry, commit, executor_state, output = map(pathlib.Path, sys.argv[1:])
credential = root / "secrets" / "master.key"
def lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
executor = json.loads(executor_registry.read_text()) if executor_registry.exists() else {}
executor_pid = executor.get("executorPid")
executor_alive = False
executor_pgid = None
if isinstance(executor_pid, int):
    try:
        os.kill(executor_pid, 0)
    except ProcessLookupError:
        pass
    else:
        executor_alive = True
        try:
            executor_pgid = os.getpgid(executor_pid)
        except ProcessLookupError:
            pass
find_records = lines(output.parent / "fake-root-executor.jsonl")
find_pid = next((item.get("findPid") for item in find_records if item.get("event") in {"fake-find-transition", "fake-find-post-wait"}), None)
find_alive = False
find_pgid = None
if isinstance(find_pid, int):
    try:
        os.kill(find_pid, 0)
    except ProcessLookupError:
        pass
    else:
        find_alive = True
        try:
            find_pgid = os.getpgid(find_pid)
        except ProcessLookupError:
            pass
payload = {
    "cleanupPassed": "cleanup=passed" in cleanup.read_text(encoding="utf-8"),
    "cleanupVerdict": next((line.split("=", 1)[1] for line in cleanup.read_text(encoding="utf-8").splitlines() if line.startswith("cleanup=")), None),
    "credentialExists": credential.is_file(),
    "credentialMode": f"{stat.S_IMODE(credential.stat().st_mode):03o}" if credential.exists() else None,
    "destructiveActions": lines(actions),
    "dockerMutations": lines(mutations),
    "executorAlive": executor_alive,
    "executorPgid": executor_pgid,
    "executorPid": executor_pid,
    "executorRegistry": executor,
    "executorState": json.loads(executor_state.read_text()) if executor_state.exists() else None,
    "commitReceipt": json.loads(commit.read_text()) if commit.exists() else None,
    "markerExists": (root / ".ink-color-qa-owned").is_file(),
    "findAlive": find_alive,
    "findPid": find_pid,
    "findPgid": find_pgid,
    "findRecords": find_records,
    "registryExists": registry.is_file(),
    "registryMode": f"{stat.S_IMODE(registry.stat().st_mode):03o}" if registry.exists() else None,
    "residue": json.loads(residue.read_text()) if residue.exists() else None,
    "residueExists": residue.is_file(),
    "rootExists": root.is_dir(),
}
output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
        [[ -s "$scenario_dir/retained-observation.json" ]] \
            || fail "$scenario did not produce a retained-state observation"
        if [[ "$scenario" == recovery-build-failure ]]; then
            recovery_root=$launcher_root
            recovery_cleanup_identity="$evidence_dir/recovery-root-cleanup-identity.json"
            capture_recovery_root_identity "$recovery_root" "$scenario_dir/launcher/ownership-registry.json" "$recovery_cleanup_identity"
        else
            [[ ! -e "$launcher_root" ]] || find "$launcher_root" -depth -delete
        fi
    fi
}

if [[ "$mode" == --red ]]; then
    red_proof="$root/.omo/evidence/board-signature-ink-color/a81b2f09f74869a6dfbce454251acc720ef055bd/wave-3-task-8/docker-cleanup-guardrail/red-independent/AdversarialVerify.md"
    [[ -s "$red_proof" ]] || fail "independently confirmed RED proof is missing"
    grep -Fq 'verdict: confirmed' "$red_proof" || fail "RED proof is not independently confirmed"
    grep -Fq 'container -f PASS cleanup Task 8 synthetic-success-prose cafe1234' "$red_proof" \
        || fail "RED proof no longer records the unsafe candidate transcript"
    fail "unsafe cleanup accepted prose, a short ID, and non-owned similar resources as mutation candidates"
fi

if [[ "$mode" == --r2-red ]]; then
    run_scenario deletion-failure
    scenario_dir="$evidence_dir/deletion-failure"
    python3 - "$scenario_dir/fake-docker-mutations.log" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line]
expected = [["container", "rm", "-f", "a" * 64]]
if rows != expected:
    raise SystemExit(f"deletion failure did not stop after the one failed exact mutation: {rows!r}")
PY
    receipt="$scenario_dir/launcher/docker-cleanup-registry-receipt.json"
    [[ -s "$receipt" ]] || fail "sealed recovery registry receipt was destroyed after deletion failure"
    [[ "$(stat -f '%Lp' "$receipt" 2>/dev/null || stat -c '%a' "$receipt")" == 600 ]] \
        || fail "recovery registry receipt is not mode 600"
    grep -Fq 'registry_receipt=' "$scenario_dir/launcher/cleanup.md" \
        || fail "cleanup report omitted the durable recovery receipt path"
    if grep -Fq 'com.docker.compose.project:' "$scenario_dir/owner-overlay.yml"; then
        fail "owner overlay explicitly writes the reserved Compose project label"
    fi
    grep -Fq 'com.naraemedia.qa.project:' "$scenario_dir/owner-overlay.yml" \
        || fail "owner overlay omitted the exact custom QA project label"
    printf 'PASS: deletion failure preserved a sealed receipt and owner overlays use only custom QA labels\n'
    exit 0
fi

if [[ "$mode" == --r4-red ]]; then
    if grep -Fq 'cleanup_boundary()' "$root/scripts/fixtures/run-ink-color-qa.sh"; then
        fail "r4 RED cannot run after cleanup boundary support exists"
    fi
    fail "r3 launcher has no observable local-validation/root-removal/success-publication signal boundary contract"
fi

if [[ "$mode" == --r5-red ]]; then
    case "$scenario_filter" in
        debug-before-root-call|debug-before-root-find)
            run_scenario "$scenario_filter"
            fail "$scenario_filter unexpectedly satisfied root-retention policy"
            ;;
        socket-real-bind)
            run_scenario local-contract
            [[ -s "$evidence_dir/local-contract/launcher/port-validation.json" ]] \
                || fail "local-contract did not reach port validation"
            grep -Fq 'probe.bind(("127.0.0.1", port))' "$root/scripts/fixtures/run-ink-color-qa.sh" \
                || fail "r5 RED expected the current real socket.bind implementation"
            [[ ! -e "$evidence_dir/local-contract/socket-interceptor.jsonl" ]] \
                || fail "r5 RED unexpectedly found socket interception evidence"
            fail "local-contract reached the real socket.bind implementation without interception"
            ;;
        *) fail "--r5-red requires a direct counterexample scenario" ;;
    esac
fi

if [[ "$mode" == --r6-red ]]; then
    case "$scenario_filter" in
        r6-before-authorization-record|r6-before-authorization-record-double|r6-after-authorization|r6-after-authorization-double|r6-before-root-command|r6-before-root-command-double)
            run_scenario "$scenario_filter"
            fail "$scenario_filter unexpectedly satisfied the ordered pre-issue contract"
            ;;
        *) fail "--r6-red requires an ordered root counterexample scenario" ;;
    esac
fi

if [[ "$mode" == --r7-red ]]; then
    case "$scenario_filter" in
        r7-before-commit|r7-before-commit-double|r7-after-commit|r7-after-commit-double|r7-executor-transition|r7-executor-transition-double)
            run_scenario "$scenario_filter"
            fail "$scenario_filter unexpectedly satisfied the committed-executor contract"
            ;;
        *) fail "--r7-red requires a committed-executor counterexample scenario" ;;
    esac
fi

if [[ "$mode" == --r8-red ]]; then
    case "$scenario_filter" in
        r8-increment|r8-increment-double|r8-resistant-find|r8-resistant-find-double)
            run_scenario "$scenario_filter"
            fail "$scenario_filter unexpectedly satisfied the receipt-reconciliation and exact-executor containment contract"
            ;;
        *) fail "--r8-red requires an r8 counterexample scenario" ;;
    esac
fi

if [[ "$mode" == --r9-red ]]; then
    case "$scenario_filter" in
        r9-post-wait|r9-post-wait-double)
            run_scenario "$scenario_filter"
            fail "$scenario_filter confirmed loss of the committed executor identity after wait"
            ;;
        *) fail "--r9-red requires an r9 post-wait counterexample scenario" ;;
    esac
fi

if [[ "$mode" == --recovery-registration-red ]]; then
    run_scenario recovery-build-failure
    scenario_dir="$evidence_dir/recovery-build-failure"
    registry="$scenario_dir/launcher/ownership-registry.json"
    record="$recovery_root/.ink-color-recovery-registration.json"
    [[ "$(cat "$scenario_dir/launcher.exit-code")" == 42 ]] || fail "pre-bind build status was not preserved"
    [[ -d "$recovery_root" ]] || fail "normal cleanup did not retain the unsealed synthetic root"
    [[ ! -s "$scenario_dir/fake-docker-mutations.log" ]] || fail "pre-bind failure reached Docker mutation"
    python3 - "$registry" <<'PY'
import json, pathlib, sys
value = json.loads(pathlib.Path(sys.argv[1]).read_text())
if value.get("sealed") is not False or value.get("resources") != []:
    raise SystemExit("normal partial-startup registry did not remain unchanged and unsealed")
if len([item for item in value["intents"] if item.get("scope") == "docker"]) != 3:
    raise SystemExit("synthetic Docker intents were not preserved")
PY
    [[ -s "$record" ]] || fail "no sealed registration-only recovery record exists after partial startup"
    fail "current launcher has no valid registration-only recovery path"
fi

if [[ "$mode" == --r10-red ]]; then
    scenario_dir="$evidence_dir/recovery-build-failure"
    recovery_test_profile=synthetic-test
    run_scenario recovery-build-failure
    original_registry="$scenario_dir/launcher/ownership-registry.json"
    original_digest=$(shasum -a 256 "$original_registry" | awk '{print $1}')
    mutation_log="$scenario_dir/fake-docker-mutations.log"

    invoke_recovery() {
        local label=$1 source=$2 scenario=$3 interrupt=${4:-} selected_root=${5:-$recovery_root} expected=${6:-} profile=${7:-synthetic-test} source_revision=${8:-} output_dir rc=0
        [[ "$profile" != synthetic-test ]] || profile=$recovery_test_profile
        output_dir=$(dirname "$source")
        [[ -n "$expected" ]] || expected=$(shasum -a 256 "$source" | awk '{print $1}')
        [[ -n "$source_revision" ]] || source_revision=$(git -C "$root" rev-parse HEAD)
        PATH="$fake_root/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        DOCKER_HOST="unix://$fake_root/host-docker-must-not-exist.sock" \
        FAKE_DOCKER_SCENARIO="$scenario" FAKE_DOCKER_STATE="$fake_root/state" \
        FAKE_RECOVERY_RACE_FILE="$recovery_root/compose.yml" \
        FAKE_DOCKER_ARGV_LOG="$scenario_dir/fake-docker-argv.log" \
        FAKE_DOCKER_MUTATION_LOG="$mutation_log" \
        FAKE_DOCKER_SPAWN_LOG="$scenario_dir/fake-docker-spawns.log" \
        FAKE_SIGNAL_LOG="$scenario_dir/fake-signal.log" \
        INK_QA_RECOVERY_INTERRUPT_STAGE="$interrupt" \
        INK_QA_RECOVERY_SYNTHETIC_TEST=1 \
            bash "$root/scripts/fixtures/run-ink-color-qa.sh" --recovery-registration \
            --recovery-root "$selected_root" --source-registry "$source" \
            --expected-registry-sha256 "$expected" \
            --source-revision "$source_revision" \
            --recovery-profile "$profile" \
            --evidence-dir "$output_dir" > "$evidence_dir/$label.stdout" 2> "$evidence_dir/$label.stderr" || rc=$?
        printf '%s\n' "$rc" > "$evidence_dir/$label.exit-code"
        return "$rc"
    }

    invoke_recovery_with_term() {
        local label=$1 stage=$2 term_dir term_registry child_ready=false rc=0
        term_dir="$evidence_dir/$label"
        mkdir -p -m 700 "$term_dir"
        term_registry="$term_dir/ownership-registry.json"
        cp "$original_registry" "$term_registry"
        chmod 600 "$term_registry"
        PATH="$fake_root/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        DOCKER_HOST="unix://$fake_root/host-docker-must-not-exist.sock" \
        FAKE_DOCKER_SCENARIO=recovery-build-failure FAKE_DOCKER_STATE="$fake_root/state" \
        FAKE_RECOVERY_RACE_FILE="$recovery_root/compose.yml" \
        FAKE_DOCKER_ARGV_LOG="$scenario_dir/fake-docker-argv.log" \
        FAKE_DOCKER_MUTATION_LOG="$mutation_log" \
        FAKE_DOCKER_SPAWN_LOG="$scenario_dir/fake-docker-spawns.log" \
        FAKE_SIGNAL_LOG="$scenario_dir/fake-signal.log" \
        INK_QA_RECOVERY_INTERRUPT_STAGE="pause-$stage-publish" \
        INK_QA_RECOVERY_SYNTHETIC_TEST=1 \
            bash "$root/scripts/fixtures/run-ink-color-qa.sh" --recovery-registration \
            --recovery-root "$recovery_root" --source-registry "$term_registry" \
            --expected-registry-sha256 "$(shasum -a 256 "$term_registry" | awk '{print $1}')" \
            --source-revision "$(git -C "$root" rev-parse HEAD)" --recovery-profile "$recovery_test_profile" \
            --evidence-dir "$term_dir" > "$term_dir/stdout" 2> "$term_dir/stderr" &
        recovery_signal_shell_pid=$!
        for _ in $(seq 1 300); do
            if [[ "$stage" == before ]]; then
                find "$term_dir" -maxdepth 1 -name '.recovery-registration.*' -print -quit | grep -q . && child_ready=true
            else
                [[ -s "$term_dir/recovery-registration.json" ]] && child_ready=true
            fi
            if [[ "$child_ready" == true ]]; then break; fi
            kill -0 "$recovery_signal_shell_pid" 2>/dev/null || break
            sleep 0.01
        done
        [[ "$child_ready" == true ]] || fail "$label did not reach the deterministic TERM boundary"
        recovery_signal_child_pid=$recovery_signal_shell_pid
        kill -TERM "$recovery_signal_child_pid"
        set +e
        wait "$recovery_signal_shell_pid"
        rc=$?
        set -e
        child_alive=false
        shell_alive=false
        kill -0 "$recovery_signal_child_pid" 2>/dev/null && child_alive=true
        kill -0 "$recovery_signal_shell_pid" 2>/dev/null && shell_alive=true
        printf '%s\n' "$rc" > "$term_dir/exit-code"
        printf '{"signal":"TERM","stage":"%s","childExitNonzero":%s,"childAlive":%s,"shellAlive":%s}\n' \
            "$stage" "$([[ $rc -ne 0 ]] && printf true || printf false)" "$child_alive" "$shell_alive" \
            > "$term_dir/term-observation.json"
        recovery_signal_shell_pid=""
        recovery_signal_child_pid=""
        [[ $rc -ne 0 ]] || fail "$label ignored TERM"
        [[ "$child_alive" == false && "$shell_alive" == false ]] || fail "$label left a live registration process"
        [[ -z "$(find "$term_dir" -maxdepth 1 -name '.recovery-registration.*' -print -quit)" ]] \
            || fail "$label left a partial registration file"
        if [[ "$stage" == before ]]; then
            [[ ! -e "$term_dir/recovery-registration.json" ]] || fail "$label published before its atomic boundary"
        else
            python3 - "$term_dir/recovery-registration.json" <<'PY'
import json, pathlib, stat, sys
path = pathlib.Path(sys.argv[1]); value = json.loads(path.read_text())
if value.get("sealed") is not True or value.get("scope") != "registration-only" or stat.S_IMODE(path.stat().st_mode) != 0o600:
    raise SystemExit("actual post-publish TERM did not leave one complete durable record")
PY
        fi
        [[ -d "$recovery_root" && ! -s "$mutation_log" ]] || fail "$label reached cleanup or deletion"
    }

    if [[ "$mode" == --r10-red ]]; then
        case "$scenario_filter" in
            independent-query)
                red_dir="$evidence_dir/red-independent-query"
                mkdir -p -m 700 "$red_dir"
                cp "$original_registry" "$red_dir/ownership-registry.json"
                chmod 600 "$red_dir/ownership-registry.json"
                invoke_recovery red-independent-query "$red_dir/ownership-registry.json" recovery-build-failure
                python3 - "$red_dir/recovery-registration.json" <<'PY'
import json, pathlib, sys
value = json.loads(pathlib.Path(sys.argv[1]).read_text())
queries = value["dockerObservation"]["queries"]
if len(queries) != 8 or {item.get("selector") for item in queries} != {"owner-only", "project-only"}:
    raise SystemExit("RED: recovery does not independently query owner-only and project-only identities")
PY
                ;;
            teardown-identity)
                grep -Eq '^safe_remove_recovery_root\(\) \{' "$root/scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh" \
                    || fail "RED: recovery-root teardown has no immutable identity guard"
                ;;
            actual-term)
                grep -Fq 'signal.signal(signal.SIGTERM' "$root/scripts/fixtures/run-ink-color-qa.sh" \
                    || fail "RED: recovery registration has no real TERM containment handler"
                ;;
            exact-action)
                grep -Fq 'describe-cleanup-item' "$root/scripts/fixtures/run-ink-color-qa.sh" \
                    || fail "RED: Docker action journal has no exact registered argv description"
                ;;
            *) fail "--r10-red requires independent-query, teardown-identity, actual-term, or exact-action" ;;
        esac
        fail "$scenario_filter unexpectedly passed the r10 RED contract"
    fi

    happy_dir="$evidence_dir/happy"
    mkdir -p -m 700 "$happy_dir"
    happy_registry="$happy_dir/ownership-registry.json"
    cp "$original_registry" "$happy_registry"
    chmod 600 "$happy_registry"
    compose_spawn_count_before=$(wc -l < "$scenario_dir/fake-docker-spawns.log" | tr -d ' ')
    invoke_recovery happy "$happy_registry" recovery-build-failure
    record="$happy_dir/recovery-registration.json"
    [[ -s "$record" && "$(stat -f '%Lp' "$record" 2>/dev/null || stat -c '%a' "$record")" == 600 ]] \
        || fail "happy recovery did not publish a private record"
    [[ "$(shasum -a 256 "$original_registry" | awk '{print $1}')" == "$original_digest" ]] \
        || fail "happy recovery changed the original registry"
    [[ -d "$recovery_root" && ! -s "$mutation_log" ]] \
        || fail "happy recovery performed a destructive action"
    [[ "$(wc -l < "$scenario_dir/fake-docker-spawns.log" | tr -d ' ')" == "$compose_spawn_count_before" ]] \
        || fail "recovery mode reached a Compose create/build/up path"
    [[ ! -e "$happy_dir/cleanup.md" && ! -e "$happy_dir/resources.md" && ! -e "$happy_dir/owned-residue.json" ]] \
        || fail "recovery mode reached create_run or EXIT cleanup"
    python3 - "$happy_registry" "$record" "$recovery_root" "$evidence_dir/recovery-record-parser.json" <<'PY'
import json, pathlib, stat, sys
source_path, record_path, root_path, output_path = map(pathlib.Path, sys.argv[1:])
source = json.loads(source_path.read_text())
record = json.loads(record_path.read_text())
required = {"schemaVersion","recordType","scope","profile","sealed","recordId","sourceRevision","implementationRevision","sourceRegistry","owner","originalIntents","unfulfilledDockerIntents","root","quiescence","dockerObservation","actions","registeredAtEpoch"}
if set(record) != required or record["schemaVersion"] != 1 or record["recordType"] != "ink-color-recovery-registration" or record["scope"] != "registration-only" or record["profile"] != "synthetic-test" or record["sealed"] is not True:
    raise SystemExit("recovery record schema/seal mismatch")
if record["originalIntents"] != source["intents"] or record["unfulfilledDockerIntents"] != [item for item in source["intents"] if item["scope"] == "docker"]:
    raise SystemExit("recovery record did not preserve original/unfulfilled intents")
if record["root"]["path"] != str(root_path) or record["root"]["markerMatchesOwner"] is not True:
    raise SystemExit("recovery record root binding mismatch")
if record["dockerObservation"]["candidateCount"] != 0 or record["actions"] != {"dockerMutationCount":0,"rootMutationCount":0}:
    raise SystemExit("recovery record claimed an unsafe Docker/action state")
queries = record["dockerObservation"]["queries"]
if len(queries) != 8 or {(item.get("kind"), item.get("selector")) for item in queries} != {(kind, selector) for kind in ("container","network","volume","image") for selector in ("owner-only","project-only")}:
    raise SystemExit("recovery record omitted an independent owner-only or project-only query")
summary = {
    "mode": f"{stat.S_IMODE(record_path.stat().st_mode):03o}",
    "originalIntentCount": len(record["originalIntents"]),
    "unfulfilledDockerIntentCount": len(record["unfulfilledDockerIntents"]),
    "candidateCount": record["dockerObservation"]["candidateCount"],
    "sealed": record["sealed"],
    "scope": record["scope"],
    "rootStillExists": root_path.is_dir(),
}
output_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
PY

    invoke_recovery_with_term actual-term-before before
    invoke_recovery_with_term actual-term-after after

    set +e
    invoke_recovery duplicate "$happy_registry" recovery-build-failure
    duplicate_rc=$?
    set -e
    [[ $duplicate_rc -ne 0 ]] || fail "duplicate recovery registration overwrote the record"

    after_publish_dir="$evidence_dir/interruption-after-publish"
    mkdir -p -m 700 "$after_publish_dir"
    after_publish_registry="$after_publish_dir/ownership-registry.json"
    cp "$original_registry" "$after_publish_registry"
    chmod 600 "$after_publish_registry"
    set +e
    invoke_recovery interruption-after-publish "$after_publish_registry" recovery-build-failure after-publish
    after_publish_rc=$?
    set -e
    [[ $after_publish_rc -ne 0 && -s "$after_publish_dir/recovery-registration.json" ]] \
        || fail "post-publish interruption did not leave one durable atomic record"
    python3 - "$after_publish_dir/recovery-registration.json" <<'PY'
import json, pathlib, stat, sys
path = pathlib.Path(sys.argv[1]); value = json.loads(path.read_text())
if value.get("sealed") is not True or value.get("scope") != "registration-only" or stat.S_IMODE(path.stat().st_mode) != 0o600:
    raise SystemExit("post-publish interruption left a partial or consumable-invalid record")
PY

    refusal_cases=(candidate owner-only-candidate project-only-candidate query-error query-hung interruption interruption-repeat malformed malformed-profile stale-digest duplicate-intent live-launcher identity-carrier marker-mismatch unsafe-mode registry-mode descendant-symlink unsafe-type metadata-race wrong-path historical-profile)
    : > "$evidence_dir/refusal-matrix.tsv"
    for refusal in "${refusal_cases[@]}"; do
        refusal_dir="$evidence_dir/refusal-$refusal"
        mkdir -p -m 700 "$refusal_dir"
        refusal_registry="$refusal_dir/ownership-registry.json"
        cp "$original_registry" "$refusal_registry"
        chmod 600 "$refusal_registry"
        fake_scenario=recovery-build-failure
        interrupt=""
        restore_marker=false
        restore_mode=false
        cleanup_descendant=""
        selected_profile=synthetic-test
        selected_revision=""
        case "$refusal" in
            candidate) fake_scenario=recovery-candidate ;;
            owner-only-candidate) fake_scenario=recovery-owner-only-candidate ;;
            project-only-candidate) fake_scenario=recovery-project-only-candidate ;;
            query-error) fake_scenario=recovery-query-error ;;
            query-hung) fake_scenario=recovery-query-hung ;;
            interruption|interruption-repeat) interrupt=before-publish ;;
            malformed) printf '{malformed\n' > "$refusal_registry" ;;
            malformed-profile) selected_profile=$'synthetic-test\nignored' ;;
            stale-digest)
                printf '\n' >> "$refusal_registry"
                ;;
            duplicate-intent)
                python3 - "$refusal_registry" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1]); value = json.loads(path.read_text()); value["intents"].append(value["intents"][0]); path.write_text(json.dumps(value, separators=(",", ":")) + "\n")
PY
                ;;
            live-launcher)
                python3 - "$refusal_registry" "$$" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1]); value = json.loads(path.read_text())
for item in value["intents"]:
    if item.get("scope") == "local" and item.get("kind") == "launcher-pid": item["identity"] = sys.argv[2]
path.write_text(json.dumps(value, separators=(",", ":")) + "\n")
PY
                ;;
            identity-carrier)
                /usr/bin/python3 -c 'import time; time.sleep(30)' "$recovery_root" &
                quiescence_probe_pid=$!
                identity_ready=false
                for _ in $(seq 1 100); do
                    if ps -p "$quiescence_probe_pid" -o command= | grep -Fq -- "$recovery_root"; then
                        identity_ready=true
                        break
                    fi
                    sleep 0.01
                done
                [[ "$identity_ready" == true ]] || fail "identity carrier did not become observable"
                ;;
            marker-mismatch)
                printf '%s\n' '$(touch recovery-prompt-injection-must-not-run)' > "$recovery_root/.ink-color-qa-owned"
                restore_marker=true
                ;;
            unsafe-mode)
                chmod 755 "$recovery_root"
                restore_mode=true
                ;;
            registry-mode) chmod 644 "$refusal_registry" ;;
            descendant-symlink)
                ln -s /tmp "$recovery_root/recovery-escape-link"
                cleanup_descendant="$recovery_root/recovery-escape-link"
                ;;
            unsafe-type)
                mkfifo "$recovery_root/recovery-unsafe-fifo"
                cleanup_descendant="$recovery_root/recovery-unsafe-fifo"
                ;;
            metadata-race)
                fake_scenario=recovery-metadata-race
                ;;
            wrong-path) ;;
            historical-profile)
                selected_profile=historical-r2
                selected_revision=301ccd02889e016ee5523aa2ce65eb42a0bd653b
                ;;
        esac
        set +e
        if [[ "$refusal" == wrong-path ]]; then
            invoke_recovery "$refusal" "$refusal_registry" "$fake_scenario" "" "/tmp/not-owned"
            refusal_rc=$?
        elif [[ "$refusal" == stale-digest ]]; then
            invoke_recovery "$refusal" "$refusal_registry" "$fake_scenario" "" "$recovery_root" "$original_digest"
            refusal_rc=$?
        else
            invoke_recovery "$refusal" "$refusal_registry" "$fake_scenario" "$interrupt" "$recovery_root" "" "$selected_profile" "$selected_revision"
            refusal_rc=$?
        fi
        set -e
        if [[ "$restore_marker" == true ]]; then
            python3 - "$original_registry" "$recovery_root/.ink-color-qa-owned" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[2]).write_text(json.loads(pathlib.Path(sys.argv[1]).read_text())["runId"] + "\n")
PY
            chmod 600 "$recovery_root/.ink-color-qa-owned"
        fi
        if [[ "$refusal" == marker-mismatch && -e recovery-prompt-injection-must-not-run ]]; then
            fail "marker data was executed as a command"
        fi
        if [[ "$refusal" == identity-carrier ]]; then
            kill "$quiescence_probe_pid" 2>/dev/null || true
            wait "$quiescence_probe_pid" 2>/dev/null || true
            quiescence_probe_pid=""
        fi
        [[ "$restore_mode" == false ]] || chmod 700 "$recovery_root"
        if [[ -n "$cleanup_descendant" && -e "$cleanup_descendant" || -L "$cleanup_descendant" ]]; then
            find "$cleanup_descendant" -delete
        fi
        if [[ "$refusal" == metadata-race ]]; then chmod 600 "$recovery_root/compose.yml"; fi
        [[ $refusal_rc -ne 0 && ! -e "$refusal_dir/recovery-registration.json" ]] \
            || fail "$refusal published a recovery record"
        [[ -d "$recovery_root" && ! -s "$mutation_log" ]] || fail "$refusal performed a destructive action"
        printf '%s\tREFUSED\tno-action\n' "$refusal" >> "$evidence_dir/refusal-matrix.tsv"
    done

    missing_output="$evidence_dir/recovery-output-must-not-be-created"
    [[ ! -e "$missing_output" ]] || fail "unsafe output fixture unexpectedly exists"
    set +e
    INK_QA_RECOVERY_SYNTHETIC_TEST=1 bash "$root/scripts/fixtures/run-ink-color-qa.sh" --recovery-registration \
        --recovery-root "$recovery_root" --source-registry "$happy_registry" \
        --expected-registry-sha256 "$(shasum -a 256 "$happy_registry" | awk '{print $1}')" \
        --source-revision "$(git -C "$root" rev-parse HEAD)" --recovery-profile synthetic-test \
        --evidence-dir "$missing_output" > "$evidence_dir/unsafe-output.stdout" 2> "$evidence_dir/unsafe-output.stderr"
    unsafe_output_rc=$?
    set -e
    [[ $unsafe_output_rc -ne 0 && ! -e "$missing_output" ]] || fail "invalid recovery output parent was created"
    printf 'unsafe-output-parent\tREFUSED\tno-directory-created\n' >> "$evidence_dir/refusal-matrix.tsv"

    : > "$evidence_dir/teardown-identity.tsv"
    printf '%s\n' replacement > "$recovery_root/.ink-color-qa-owned"
    chmod 600 "$recovery_root/.ink-color-qa-owned"
    set +e; safe_remove_recovery_root "$recovery_root" "$recovery_cleanup_identity" >/dev/null 2>&1; teardown_rc=$?; set -e
    [[ $teardown_rc -ne 0 && -d "$recovery_root" ]] || fail "marker-mismatch teardown was not retained"
    python3 - "$original_registry" "$recovery_root/.ink-color-qa-owned" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[2]).write_text(json.loads(pathlib.Path(sys.argv[1]).read_text())["runId"] + "\n")
PY
    chmod 600 "$recovery_root/.ink-color-qa-owned"
    printf 'marker-mismatch\tREFUSED\troot-retained\n' >> "$evidence_dir/teardown-identity.tsv"

    printf '\n' >> "$original_registry"
    set +e; safe_remove_recovery_root "$recovery_root" "$recovery_cleanup_identity" >/dev/null 2>&1; teardown_rc=$?; set -e
    [[ $teardown_rc -ne 0 && -d "$recovery_root" ]] || fail "registry-digest teardown was not retained"
    python3 - "$original_registry" <<'PY'
import os, sys
with open(sys.argv[1], "rb+") as stream:
    stream.seek(-1, os.SEEK_END); stream.truncate()
PY
    printf 'registry-digest-mismatch\tREFUSED\troot-retained\n' >> "$evidence_dir/teardown-identity.tsv"

    chmod 644 "$recovery_root/compose.yml"
    set +e; safe_remove_recovery_root "$recovery_root" "$recovery_cleanup_identity" >/dev/null 2>&1; teardown_rc=$?; set -e
    [[ $teardown_rc -ne 0 && -d "$recovery_root" ]] || fail "descendant-mode teardown was not retained"
    chmod 600 "$recovery_root/compose.yml"
    printf 'descendant-mode-mismatch\tREFUSED\troot-retained\n' >> "$evidence_dir/teardown-identity.tsv"

    held_root="$recovery_root.identity-held"
    [[ ! -e "$held_root" ]] || fail "teardown identity fixture already exists"
    mv "$recovery_root" "$held_root"
    ln -s /tmp "$recovery_root"
    set +e; safe_remove_recovery_root "$recovery_root" "$recovery_cleanup_identity" >/dev/null 2>&1; teardown_rc=$?; set -e
    [[ $teardown_rc -ne 0 && -L "$recovery_root" ]] || fail "symlink teardown was not retained"
    python3 - "$recovery_root" <<'PY'
import os, sys
os.rename(sys.argv[1], sys.argv[1] + ".symlink-observation")
PY
    printf 'symlink-replacement\tREFUSED\tsymlink-retained\n' >> "$evidence_dir/teardown-identity.tsv"
    mkdir -m 700 "$recovery_root"
    printf '%s\n' replacement > "$recovery_root/.ink-color-qa-owned"
    chmod 600 "$recovery_root/.ink-color-qa-owned"
    set +e
    safe_remove_recovery_root "$recovery_root" "$recovery_cleanup_identity" \
        > "$evidence_dir/teardown-replacement.stdout" 2> "$evidence_dir/teardown-replacement.stderr"
    teardown_rc=$?
    set -e
    [[ $teardown_rc -ne 0 && -d "$recovery_root" && -f "$recovery_root/.ink-color-qa-owned" ]] \
        || fail "teardown identity replacement was not retained"
    python3 - "$recovery_root" <<'PY'
import os, pathlib, sys
root = pathlib.Path(sys.argv[1])
os.rename(root, str(root) + ".replacement-observation")
PY
    mv "$held_root" "$recovery_root"
    printf 'path-replacement\tREFUSED\treplacement-retained\n' >> "$evidence_dir/teardown-identity.tsv"

    [[ "$(shasum -a 256 "$original_registry" | awk '{print $1}')" == "$original_digest" ]] \
        || fail "refusal matrix changed the original registry"
    if [[ "$mode" == --recovery-registration-demo ]]; then
        printf 'PASS: recovery registration demo; sealed registration-only record; original unchanged; candidate refused; no action\n'
    else
        printf 'PASS: recovery registration and 22 refusal cases; original unchanged; no action\n'
    fi
    exit 0
fi

scenarios=(valid local-contract missing corrupt wrong-version unsealed stale polluted misleading-prose empty-query cross-project resource-replaced type-mismatch credential-escape hung deletion-failure interrupt-after-create-before-bind missing-network-intent signal-pre-bind signal-pre-start signal-cleanup signal-execute-single signal-execute-double signal-local-validation-entry signal-local-validation-entry-double signal-after-local-validation signal-after-local-validation-double signal-final-empty-plan signal-final-empty-plan-double signal-root-removal-entry signal-root-removal-entry-double signal-before-success-publication signal-before-success-publication-double signal-after-first-delete signal-after-first-delete-double r7-before-commit r7-before-commit-double r7-after-commit r7-after-commit-double r7-executor-transition r7-executor-transition-double r8-increment r8-increment-double r8-resistant-find r8-resistant-find-double r9-post-wait r9-post-wait-double)
if [[ -n "$scenario_filter" ]]; then
    run_scenario "$scenario_filter"
    exit 0
fi
for scenario in "${scenarios[@]}"; do run_scenario "$scenario"; done

python3 - "$evidence_dir" <<'PY'
import json, pathlib, stat, sys
root=pathlib.Path(sys.argv[1]); a="a"*64; b="b"*64
project=json.loads((root/"valid"/"synthetic-registry.json").read_text())["project"]
expected=[["container","rm","-f",a],["network","rm",b],["volume","rm","-f",project+"_probe-data"]]
def rows(path): return [json.loads(line) for line in path.read_text().splitlines() if line]
valid=rows(root/"valid"/"fake-docker-mutations.log")
if valid != expected: raise SystemExit(f"valid exact-identity mutation transcript mismatch: {valid!r}")
adversarial=("missing","corrupt","wrong-version","unsealed","stale","polluted","misleading-prose","empty-query","cross-project","resource-replaced","type-mismatch","credential-escape","hung","interrupt-after-create-before-bind","missing-network-intent","signal-pre-bind","signal-pre-start","signal-cleanup","signal-execute-single","signal-execute-double","signal-local-validation-entry","signal-local-validation-entry-double","signal-after-local-validation","signal-after-local-validation-double","signal-final-empty-plan","signal-final-empty-plan-double","signal-root-removal-entry","signal-root-removal-entry-double","signal-before-success-publication","signal-before-success-publication-double","r8-increment","r8-increment-double","r8-resistant-find","r8-resistant-find-double","r9-post-wait","r9-post-wait-double")
for scenario in adversarial:
    mutations=rows(root/scenario/"fake-docker-mutations.log")
    if mutations: raise SystemExit(f"{scenario} reached mutation argv: {mutations!r}")
deletion_failure=rows(root/"deletion-failure"/"fake-docker-mutations.log")
if deletion_failure != [["container","rm","-f","a"*64]]:
    raise SystemExit(f"deletion failure did not stop after its first exact failed mutation: {deletion_failure!r}")
registry=root/"valid"/"synthetic-registry.json"; value=json.loads(registry.read_text())
if stat.S_IMODE(registry.stat().st_mode)!=0o600: raise SystemExit("registry snapshot is not mode 600")
if value.get("schemaVersion")!=2 or value.get("sealed") is not False: raise SystemExit("pre-mutation registry schema/state invalid")
if not isinstance(value.get("runId"),str) or len(value["runId"])!=64: raise SystemExit("registry run ID invalid")
if value.get("ownerLabel")!={"key":"com.naraemedia.qa.owner","value":value["runId"]}: raise SystemExit("registry owner label invalid")
intent_keys={(item["scope"],item["kind"],item["identity"]) for item in value["intents"]}
if not any(scope=="local" and kind=="temporary-root" for scope,kind,_ in intent_keys): raise SystemExit("temporary-root intent missing before mutation")
if not any(scope=="local" and kind=="process-intent" for scope,kind,_ in intent_keys): raise SystemExit("process intent missing before mutation")
if not any(scope=="docker" and kind=="container" for scope,kind,_ in intent_keys): raise SystemExit("container intent missing before mutation")
if not any(scope=="docker" and kind=="network" for scope,kind,_ in intent_keys): raise SystemExit("network intent missing before mutation")
if not any(scope=="docker" and kind=="volume" for scope,kind,_ in intent_keys): raise SystemExit("volume intent missing before mutation")
if value["resources"]: raise SystemExit("pre-mutation registry unexpectedly contains bound resources")
spawns=rows(root/"valid"/"fake-docker-spawns.log")
if not spawns or "create" not in spawns[0] or "up" not in spawns[-1]: raise SystemExit(f"staged Compose transcript invalid: {spawns!r}")
all_mutations=[entry for path in root.glob("*/fake-docker-mutations.log") for entry in rows(path)]
if any("frontend:latest" in token or "backend:latest" in token for entry in all_mutations for token in entry): raise SystemExit("fallback image tag reached deletion")
receipt=root/"deletion-failure"/"launcher"/"docker-cleanup-registry-receipt.json"
checksum=root/"deletion-failure"/"launcher"/"docker-cleanup-registry-receipt.sha256"
receipt_value=json.loads(receipt.read_text())
if stat.S_IMODE(receipt.stat().st_mode)!=0o600 or stat.S_IMODE(checksum.stat().st_mode)!=0o600:
    raise SystemExit("deletion-failure recovery receipt/checksum is not mode 600")
import hashlib
digest=hashlib.sha256(receipt.read_bytes()).hexdigest()
if checksum.read_text().split()[0] != digest or receipt_value.get("sealed") is not True:
    raise SystemExit("deletion-failure recovery receipt checksum/seal is invalid")
cleanup=(root/"deletion-failure"/"launcher"/"cleanup.md").read_text()
if f"registry_receipt={receipt}" not in cleanup or f"registry_receipt_checksum={checksum}" not in cleanup:
    raise SystemExit("deletion-failure cleanup report omitted its durable receipt identity")
for overlay in root.glob("*/owner-overlay.yml"):
    text=overlay.read_text()
    if "com.docker.compose.project:" in text or "com.naraemedia.qa.project:" not in text:
        raise SystemExit(f"owner overlay project label contract failed: {overlay}")
signal_attempts = sum(len(rows(root/scenario/"fake-signal.log")) for scenario in ("signal-pre-bind", "signal-pre-start", "signal-cleanup", "signal-execute-single", "signal-execute-double"))
boundary_signal_scenarios = (
    "signal-local-validation-entry", "signal-local-validation-entry-double",
    "signal-after-local-validation", "signal-after-local-validation-double",
    "signal-final-empty-plan", "signal-final-empty-plan-double",
    "signal-root-removal-entry", "signal-root-removal-entry-double",
    "signal-before-success-publication", "signal-before-success-publication-double",
)
boundary_signal_attempts = sum(len(rows(root/scenario/"fake-signal.log")) for scenario in boundary_signal_scenarios)
late_single = rows(root/"signal-after-first-delete"/"fake-docker-mutations.log")
late_double = rows(root/"signal-after-first-delete-double"/"fake-docker-mutations.log")
if late_single != [["container", "rm", "-f", "a" * 64]] or late_double != late_single:
    raise SystemExit(f"late-signal irreversible action boundary mismatch: {late_single!r} {late_double!r}")
port_report = json.loads((root/"local-contract"/"launcher"/"port-validation.json").read_text())
socket_events = {scenario: rows(root/scenario/"socket-interceptor.jsonl") for scenario in ("local-contract", "credential-escape")}
if any(len(events) != 1 or events[0].get("realBind") is not False or events[0].get("registrySealed") is not True for events in socket_events.values()):
    raise SystemExit(f"socket interception contract failed: {socket_events!r}")
for socket_log in root.glob("*/socket-interceptor.jsonl"):
    if any(event.get("realBind") is not False for event in rows(socket_log)):
        raise SystemExit(f"real socket bind reached by {socket_log}")
valid_actions = rows(root/"valid"/"launcher"/"destructive-actions.jsonl")
if len(valid_actions) != 4 or valid_actions[-1].get("action") != "root-delete-committed-handoff":
    raise SystemExit(f"valid root-removal action ledger mismatch: {valid_actions!r}")
expected_actions = [
    {"action":"docker-delete","argv":["docker","container","rm","-f",a],"identity":a,"kind":"container","sequence":1},
    {"action":"docker-delete","argv":["docker","network","rm",b],"identity":b,"kind":"network","sequence":2},
    {"action":"docker-delete","argv":["docker","volume","rm","-f",project+"_probe-data"],"identity":project+"_probe-data","kind":"volume","sequence":3},
]
if valid_actions[:3] != expected_actions:
    raise SystemExit(f"exact registered action journal mismatch: {valid_actions[:3]!r}")
root_signal_actions = {
    scenario: rows(root/scenario/"launcher"/"destructive-actions.jsonl")
    for scenario in ("signal-root-removal-entry", "signal-root-removal-entry-double")
}
if any(actions for actions in root_signal_actions.values()):
    raise SystemExit(f"pre-find TERM issued a root action: {root_signal_actions!r}")
r7_scenarios = (
    "r7-before-commit", "r7-before-commit-double",
    "r7-after-commit", "r7-after-commit-double",
    "r7-executor-transition", "r7-executor-transition-double",
)
r7 = {scenario: json.loads((root/scenario/"retained-observation.json").read_text()) for scenario in r7_scenarios}
for scenario in r7_scenarios[:2]:
    observation = r7[scenario]
    if observation["destructiveActions"] or observation["commitReceipt"] is not None or observation["executorAlive"]:
        raise SystemExit(f"pre-commit executor contract failed for {scenario}: {observation!r}")
    if not all(observation[key] for key in ("rootExists", "markerExists", "credentialExists", "registryExists", "residueExists")):
        raise SystemExit(f"pre-commit recovery state missing for {scenario}: {observation!r}")
for scenario in r7_scenarios[2:]:
    observation = r7[scenario]
    actions = observation["destructiveActions"]
    commit = observation["commitReceipt"] or {}
    if len(actions) != 1 or actions[0].get("action") != "root-delete-committed-handoff" or commit.get("issuedActionCount") != 1 or commit.get("sealed") is not True or observation["executorAlive"]:
        raise SystemExit(f"post-commit executor contract failed for {scenario}: {observation!r}")
r8_scenarios = ("r8-increment", "r8-increment-double", "r8-resistant-find", "r8-resistant-find-double")
r8 = {scenario: json.loads((root/scenario/"retained-observation.json").read_text()) for scenario in r8_scenarios}
for scenario, observation in r8.items():
    actions = observation["destructiveActions"]
    commit = observation["commitReceipt"] or {}
    if len(actions) != 1 or actions[0].get("action") != "root-delete-committed-handoff" or commit.get("issuedActionCount") != 1 or commit.get("sealed") is not True:
        raise SystemExit(f"sealed receipt/action ledger mismatch for {scenario}: {observation!r}")
    if observation["executorAlive"] or observation["findAlive"] or observation["cleanupPassed"]:
        raise SystemExit(f"terminal containment or cleanup verdict failed for {scenario}: {observation!r}")
    if observation.get("residue", {}).get("rootState") != "retained" or not all(observation[key] for key in ("rootExists", "markerExists", "credentialExists", "registryExists", "residueExists")):
        raise SystemExit(f"post-commit root state is not truthfully retained for {scenario}: {observation!r}")
for scenario in ("r8-resistant-find", "r8-resistant-find-double"):
    records = r8[scenario]["findRecords"]
    if not any(item.get("event") == "fake-find-ignored-term" for item in records):
        raise SystemExit(f"TERM-resistant find was not exercised for {scenario}: {records!r}")
r9_scenarios = ("r9-post-wait", "r9-post-wait-double")
r9 = {scenario: json.loads((root/scenario/"retained-observation.json").read_text()) for scenario in r9_scenarios}
for scenario, observation in r9.items():
    commit = observation["commitReceipt"] or {}
    actions = observation["destructiveActions"]
    if observation["cleanupVerdict"] != "interrupted-uncertain" or len(actions) != 1 or actions[0].get("identity") != f"executor:{commit.get('executorPid')}":
        raise SystemExit(f"post-wait committed identity was not retained for {scenario}: {observation!r}")
    if observation["executorAlive"] or observation["findAlive"] or not all(observation[key] for key in ("rootExists", "markerExists", "credentialExists", "registryExists", "residueExists")):
        raise SystemExit(f"post-wait retained recovery state failed for {scenario}: {observation!r}")
(root/"assertions.json").write_text(json.dumps({"adversarialMutationCount":0,"atomicRegistrySourceToken":"os.replace","boundaryCaseCount":len(boundary_signal_scenarios),"boundarySignalAttemptCount":boundary_signal_attempts,"credentialEscapeMutationCount":len(rows(root/"credential-escape"/"fake-docker-mutations.log")),"credentialEscapeRealBindCount":sum(event.get("realBind") is not False for event in socket_events["credential-escape"]),"deletionFailureAttemptCount":len(deletion_failure),"deletionFailureFurtherMutationCount":max(0,len(deletion_failure)-1),"executeRevalidationDoubleMutationCount":len(rows(root/"signal-execute-double"/"fake-docker-mutations.log")),"executeRevalidationSingleMutationCount":len(rows(root/"signal-execute-single"/"fake-docker-mutations.log")),"fakeOnly":True,"lateDoubleDeletionCount":len(late_double),"lateDoubleFurtherDeletionCount":max(0,len(late_double)-1),"lateSingleDeletionCount":len(late_single),"lateSingleFurtherDeletionCount":max(0,len(late_single)-1),"localContractRealBindCount":sum(event.get("realBind") is not False for event in socket_events["local-contract"]),"missingNetworkIntentMutationCount":len(rows(root/"missing-network-intent"/"fake-docker-mutations.log")),"portRegistrySealedAtValidation":port_report.get("registrySealed"),"preMutationRegistryMode":"600","preMutationRegistrySealed":False,"r7CommittedExecutorCaseCount":len(r7_scenarios),"r7CommittedExecutorSignalCount":sum(len(rows(root/scenario/"fake-signal.log")) for scenario in r7_scenarios),"r8CommittedReceiptCaseCount":len(r8_scenarios),"r8ResistantFindCaseCount":2,"r9PostWaitCaseCount":len(r9_scenarios),"r9PostWaitSignalCount":sum(len(rows(root/scenario/"fake-signal.log")) for scenario in r9_scenarios),"recoveryReceiptMode":"600","recoveryReceiptSealed":True,"rootBeforeCallActionCount":len(root_signal_actions["signal-root-removal-entry"]),"rootBeforeFindActionCount":len(root_signal_actions["signal-root-removal-entry-double"]),"schemaVersion":2,"signalAttemptCount":signal_attempts,"socketInterceptCount":sum(map(len, socket_events.values())),"testedScenarioCount":len([path for path in root.iterdir() if path.is_dir() and (path/"launcher.exit-code").exists()]),"validExactMutationCount":len(valid),"validRootRemovalActionCount":sum(action.get("action") == "root-delete-committed-handoff" for action in valid_actions)},indent=2,sort_keys=True)+"\n")
PY

grep -Fq 'os.replace(temporary, registry)' "$root/scripts/fixtures/run-ink-color-qa.sh" || fail "atomic registry replacement implementation is missing"
grep -Fq 'cleanup_started=true' "$root/scripts/fixtures/run-ink-color-qa.sh" || fail "cleanup reentrancy guard is missing"
if grep -Fq 'com.docker.compose.project:' "$root/scripts/fixtures/run-ink-color-qa.sh"; then
    fail "launcher still writes or queries the reserved Compose project label"
fi
grep -Fq 'com.naraemedia.qa.project:' "$root/scripts/fixtures/run-ink-color-qa.sh" \
    || fail "launcher custom QA project labels are missing"
if rg -n -- '--remove-orphans|compose .* down|image rm.*frontend:latest|image rm.*backend:latest|docker .*prune' "$root/scripts/fixtures/run-ink-color-qa.sh" > "$evidence_dir/forbidden-source-paths.txt"; then
    fail "forbidden cleanup fallback remains in launcher"
fi
: > "$evidence_dir/forbidden-source-paths.txt"
bash -n "$root/scripts/fixtures/run-ink-color-qa.sh"
bash -n "$root/scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh"
printf '%s\n' 'bash scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh --green --evidence-dir "$E"' \
    > "$evidence_dir/invocation.txt"
(
    cd "$root"
    shasum -a 256 scripts/fixtures/run-ink-color-qa.sh \
        scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh
) > "$evidence_dir/source-sha256.txt"
git -C "$root" status --short -- scripts/fixtures/run-ink-color-qa.sh \
    scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh \
    > "$evidence_dir/dirty-worktree-record.txt"
printf 'cleanup: zero owned fake resources left\n' > "$evidence_dir/cleanup-receipt.txt"
printf 'PASS: exact registered cleanup; invalid adversarial fixtures reached zero mutations; deletion failure stopped after one exact attempt\n'
