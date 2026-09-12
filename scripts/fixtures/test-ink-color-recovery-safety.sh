#!/usr/bin/env bash
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
[[ $# == 2 && "$1" == --evidence-dir ]] || { printf 'usage: %s --evidence-dir PATH\n' "$0" >&2; exit 2; }
exec python3 - "$root" "$2" <<'PY'
import hashlib
import json
import os
import pathlib
import secrets
import signal
import stat
import subprocess
import sys
import time

worktree = pathlib.Path(sys.argv[1])
evidence = pathlib.Path(sys.argv[2]).resolve()
evidence.mkdir(mode=0o700, parents=True, exist_ok=True)
os.chmod(evidence, 0o700)
launcher = worktree / "scripts/fixtures/run-ink-color-qa.sh"
guardrail = worktree / "scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh"
revision = subprocess.run(["git", "-C", str(worktree), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
journal = evidence / "resource-journal.jsonl"
def event(action, **data):
    with journal.open("a") as stream:
        stream.write(json.dumps({"action": action, **data}, sort_keys=True) + "\n")
        stream.flush()
        os.fsync(stream.fileno())

def snapshot(path):
    info = path.lstat()
    return [info.st_dev, info.st_ino, info.st_mode, info.st_uid,
            hashlib.sha256(path.read_bytes()).hexdigest() if stat.S_ISREG(info.st_mode) else None]

def helper(name, args):
    section = guardrail.read_text().split(name + "() {", 1)[1].split("\n}\n", 1)[0]
    body = section.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
    previous = sys.argv
    try:
        sys.argv = [name, *map(str, args), *([str(worktree)] if name == "safe_remove_recovery_root" else [])]
        exec(compile(body, name, "exec"), {"__name__": "__main__"})
    finally:
        sys.argv = previous

receipt_path = os.environ.get("INK_QA_RECOVERY_MOUNT_RECEIPT")
if not receipt_path:
    raise SystemExit("safety fixture requires the registered disposable mount supervisor")
receipt = json.loads(pathlib.Path(receipt_path).read_text())
root = pathlib.Path(receipt["root"])
subprocess.run(["bash", str(worktree / "scripts/fixtures/verify-ink-color-recovery-mount.sh"), str(root), receipt_path], check=True, capture_output=True, timeout=20)
if list(root.iterdir()):
    raise SystemExit("mounted safety fixture root must be empty")
held = root.with_name(root.name + ".r3-held")
staged = root.with_name(root.name + ".r3-staged")
event("fixture-intent", root=str(root), held=str(held), staged=str(staged), ports=[], controllerPid=os.getpid())
owner = secrets.token_hex(32)
project = "ink-color-qa-" + secrets.token_hex(6)
marker = root / ".ink-color-qa-owned"
marker.write_text(owner + "\n")
marker.chmod(0o600)
nested = root / "nested"
nested.mkdir(mode=0o700)
original_sentinel = nested / "original.txt"
original_sentinel.write_text("original must survive uncertainty\n")
original_sentinel.chmod(0o600)
original_manifest = {".": snapshot(root), ".ink-color-qa-owned": snapshot(marker), "nested": snapshot(nested), "nested/original.txt": snapshot(original_sentinel)}
source = evidence / "ownership-registry.json"
source.write_text(json.dumps({"schemaVersion": 2, "runId": owner, "project": project,
    "ownerLabel": {"key": "com.naraemedia.qa.owner", "value": owner},
    "createdAtEpoch": int(time.time()), "sealed": False,
    "intents": [{"scope": "local", "kind": "temporary-root", "identity": str(root)},
                {"scope": "local", "kind": "launcher-pid", "identity": "999999"}], "resources": []}) + "\n")
source.chmod(0o600)
source_before = snapshot(source)
identity = evidence / "cleanup-identity.json"
helper("capture_recovery_root_identity", [root, source, identity])
results = []
processes = []

def clean_enumerated(directory, manifest):
    for relative, expected in manifest.items():
        target = directory if relative == "." else directory / relative
        if not target.exists() or snapshot(target) != expected:
            raise RuntimeError("fixture disposal identity mismatch: " + str(target))
    destination = directory.with_name(directory.name + ".replacement-observation")
    if destination.exists():
        raise RuntimeError("retained fixture destination exists")
    event("fixture-retain", path=str(directory), destination=str(destination), disposal="supervisor exact image detach")
    directory.rename(destination)

wrapper = r'''#!/usr/bin/python3
import json, os, pathlib, signal, stat, sys, tempfile, time
if len(sys.argv) < 2 or sys.argv[1] != "-":
    os.execv("/usr/bin/python3", ["/usr/bin/python3", *sys.argv[1:]])
sys.argv = sys.argv[1:]
source = sys.stdin.read()
control = pathlib.Path(os.environ["R3_CONTROL"])
stage = os.environ["R3_STAGE"]
seen = set()
signal_count = 0
real_signal = signal.signal
def install_signal(signum, handler):
    def observed_handler(received, frame):
        global signal_count
        handler(received, frame)
        signal_count += 1
        with (control / "signal-events.jsonl").open("a") as stream:
            stream.write(json.dumps({"sequence": signal_count, "signal": received, "publishedAtAcknowledgment": pathlib.Path(sys.argv[-1]).exists()}) + "\n")
        (control / "signal-count").write_text(str(signal_count))
    return real_signal(signum, observed_handler)
signal.signal = install_signal
def barrier(phase):
    if phase in seen:
        return
    seen.add(phase)
    (control / (phase + ".ready")).write_text(json.dumps({"pid": os.getpid(), "phase": phase}))
    deadline = time.monotonic() + 15
    while not (control / (phase + ".release")).exists():
        if time.monotonic() >= deadline:
            raise RuntimeError("test barrier timed out: " + phase)
        time.sleep(.005)
lines = source.splitlines()
def trace(frame, event, arg):
    if event == "line" and frame.f_code.co_filename == "registration-under-test":
        line = lines[frame.f_lineno - 1].strip()
        if stage == "before-allocation" and line.startswith("fd, temporary = tempfile.mkstemp"):
            barrier("target")
        if stage in {"ready", "repeated-before", "outer-shell"} and line.startswith('if os.environ.get("INK_QA_RECOVERY_INTERRUPT_STAGE") == "before-publish":'):
            barrier("target")
    return trace
real_mkstemp = tempfile.mkstemp
def allocation(*args, **kwargs):
    allocated = real_mkstemp(*args, **kwargs)
    if stage == "allocation-return" and kwargs.get("prefix") == ".recovery-registration.":
        barrier("target")
    return allocated
tempfile.mkstemp = allocation
real_link = os.link
def link(*args, **kwargs):
    if stage == "ordinary-error-finalizer":
        raise OSError("test ordinary failure before publication")
    if stage == "concurrent-commit":
        barrier("target")
    result = real_link(*args, **kwargs)
    if stage in {"published", "repeated-after"}:
        barrier("target")
    return result
os.link = link
real_fsync = os.fsync
def fsync(fd):
    result = real_fsync(fd)
    if stage == "after-directory-fsync" and stat.S_ISDIR(os.fstat(fd).st_mode):
        barrier("target")
    return result
os.fsync = fsync
real_unlink = os.unlink
def unlink(path, *args, **kwargs):
    if stage in {"repeated-before", "repeated-after", "ordinary-error-finalizer"} and pathlib.Path(path).name.startswith(".recovery-registration."):
        barrier("finalizer")
    return real_unlink(path, *args, **kwargs)
os.unlink = unlink
sys.settrace(trace)
exec(compile(source, "registration-under-test", "exec"), {"__name__": "__main__"})
'''

bindir = evidence / "bin"
bindir.mkdir(mode=0o700)
(bindir / "python3").write_text(wrapper)
(bindir / "python3").chmod(0o700)
(bindir / "docker").write_text('#!/bin/sh\ncase "$1 $2" in "info "|"container ls"|"network ls"|"volume ls"|"image ls") exit 0;; *) printf "unexpected mutation\\n" >&2; exit 81;; esac\n')
(bindir / "docker").chmod(0o700)

def wait_path(path, process):
    deadline = time.monotonic() + 15
    while not path.exists():
        if process.poll() is not None or time.monotonic() >= deadline:
            return None
        time.sleep(.01)
    return json.loads(path.read_text())

def alive(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True

def acknowledge(case, count, pid):
    deadline = time.monotonic() + 1
    while alive(pid) and time.monotonic() < deadline:
        try:
            if int((case / "signal-count").read_text()) >= count:
                return True
        except (FileNotFoundError, ValueError):
            pass
        time.sleep(.005)
    return False

try:
    # Swap at the real bottom-up traversal, after both committed preflight scans.
    # New fail-closed helper still performs this read-only audit boundary.
    staged.mkdir(mode=0o700)
    (staged / "nested").mkdir(mode=0o700)
    for path in (staged / "sentinel.txt", staged / "nested" / "sentinel.txt"):
        path.write_text("nonempty replacement must survive\n")
        path.chmod(0o600)
    replacement_manifest = {".": snapshot(staged), "nested": snapshot(staged / "nested"), "sentinel.txt": snapshot(staged / "sentinel.txt"), "nested/sentinel.txt": snapshot(staged / "nested/sentinel.txt")}
    real_fwalk = os.fwalk
    switched = False
    def swapped_walk(*args, **kwargs):
        global switched
        if kwargs.get("topdown") is False and not switched:
            event("replacement-intent", original=str(root), held=str(held), staged=str(staged))
            root.rename(held)
            staged.rename(root)
            switched = True
        yield from real_fwalk(*args, **kwargs)
    os.fwalk = swapped_walk
    refusal = None
    try:
        helper("safe_remove_recovery_root", [root, identity])
    except (ValueError, OSError) as error:
        refusal = str(error)
    finally:
        os.fwalk = real_fwalk
    replacement_survived = switched and all((root / key).exists() and snapshot(root / key) == value for key, value in replacement_manifest.items())
    original_survived = switched and all((held / key).exists() and snapshot(held / key) == value for key, value in original_manifest.items())
    results.append({"case": "post-validation-root-replacement", "pass": bool(refusal and replacement_survived and original_survived), "refusal": refusal, "switched": switched, "replacementSurvived": replacement_survived, "originalSurvived": original_survived})
    # Preserve the complete observed failure in evidence, then dispose only known
    # surviving test-created entries after the synchronous helper has returned.
    replacement_remaining = {key: value for key, value in replacement_manifest.items() if (root / key).exists()}
    clean_enumerated(root, replacement_remaining)
    held.rename(root)
    for attack in ("root-during-traversal", "descendant-before-traversal"):
        target = root if attack == "root-during-traversal" else root / "nested"
        attack_stage = root.parent / (attack + "-staged")
        attack_held = root.parent / (attack + "-held")
        attack_observed = root.parent / (attack + "-observed")
        event("replacement-fixture-intent", stage=str(attack_stage), held=str(attack_held), target=str(target), observed=str(attack_observed))
        attack_stage.mkdir(mode=0o700)
        (attack_stage / "nested").mkdir(mode=0o700)
        for path in (attack_stage / "sentinel.txt", attack_stage / "nested/sentinel.txt"):
            path.write_text("replacement during traversal must survive\n")
            path.chmod(0o600)
        attack_manifest = {key: snapshot(attack_stage / key) for key in (".", "nested", "sentinel.txt", "nested/sentinel.txt")}
        original_attack = original_manifest if target == root else {".": original_manifest["nested"], "original.txt": original_manifest["nested/original.txt"]}
        attacked = False
        def swap_target():
            global attacked
            target.rename(attack_held)
            attack_stage.rename(target)
            attacked = True
        def during_walk(*args, **kwargs):
            if kwargs.get("topdown") is False and attack == "descendant-before-traversal" and not attacked:
                swap_target()
            for entry in real_fwalk(*args, **kwargs):
                yield entry
                if kwargs.get("topdown") is False and not attacked:
                    swap_target()
        os.fwalk = during_walk
        attack_refusal = None
        try:
            helper("safe_remove_recovery_root", [root, identity])
        except (ValueError, OSError) as error:
            attack_refusal = str(error)
        finally:
            os.fwalk = real_fwalk
        replacement_ok = attacked and all((target / key).exists() and snapshot(target / key) == value for key, value in attack_manifest.items())
        original_ok = attacked and all((attack_held / key).exists() and snapshot(attack_held / key) == value for key, value in original_attack.items())
        results.append({"case": attack, "pass": bool(attack_refusal and replacement_ok and original_ok), "refusal": attack_refusal, "replacementSurvived": replacement_ok, "originalSurvived": original_ok})
        if not attacked:
            raise RuntimeError("replacement test did not reach actual traversal")
        target.rename(attack_observed)
        attack_held.rename(target)
    for stage in ("before-allocation", "allocation-return", "ready", "published", "after-directory-fsync", "repeated-before", "repeated-after", "ordinary-error-finalizer", "outer-shell", "concurrent-commit"):
        case = evidence / stage
        event("case-intent", stage=stage, output=str(case), source=str(source))
        case.mkdir(mode=0o700)
        environment = dict(os.environ, PATH=str(bindir) + ":/usr/bin:/bin:/usr/sbin:/sbin", DOCKER_HOST="unix://" + str(evidence / "host-docker-must-not-exist.sock"), INK_QA_RECOVERY_SYNTHETIC_TEST="1", R3_CONTROL=str(case), R3_STAGE=stage)
        argv = ["bash", str(launcher), "--recovery-registration", "--recovery-root", str(root), "--source-registry", str(source), "--expected-registry-sha256", source_before[-1], "--source-revision", revision, "--recovery-profile", "synthetic-mount-test", "--evidence-dir", str(case)]
        event("child-intent", stage=stage, argv=argv)
        with (case / "stdout").open("wb") as stdout, (case / "stderr").open("wb") as stderr:
            process = subprocess.Popen(argv, env=environment, stdout=stdout, stderr=stderr)
            processes.append(process)
            event("child-started", stage=stage, pid=process.pid)
            phase = "finalizer" if stage == "ordinary-error-finalizer" else "target"
            observed = wait_path(case / (phase + ".ready"), process)
            target_pid = observed["pid"] if observed else None
            sent = []
            if observed:
                receiver = process.pid if stage == "outer-shell" else target_pid
                event("signal-intent", stage=stage, targetPid=receiver, signal="TERM")
                os.kill(receiver, signal.SIGTERM)
                sent.append(receiver)
                acknowledge(case, len(sent), target_pid)
                if stage == "ordinary-error-finalizer":
                    for _ in range(2):
                        if alive(target_pid):
                            os.kill(target_pid, signal.SIGTERM)
                            sent.append(target_pid)
                            acknowledge(case, len(sent), target_pid)
                (case / (phase + ".release")).touch()
                if stage.startswith("repeated-"):
                    finalizer = wait_path(case / "finalizer.ready", process)
                    if finalizer:
                        for _ in range(2):
                            if alive(target_pid):
                                event("signal-intent", stage=stage, targetPid=target_pid, signal="TERM")
                                os.kill(target_pid, signal.SIGTERM)
                                sent.append(target_pid)
                                acknowledge(case, len(sent), target_pid)
                        (case / "finalizer.release").touch()
            try:
                rc = process.wait(timeout=18)
            except subprocess.TimeoutExpired:
                process.kill()
                rc = process.wait(timeout=5)
            orphan = bool(target_pid and target_pid != process.pid and alive(target_pid))
            if orphan:
                # The failed baseline shell case may leave a child; dispose only
                # this barrier-observed PID, never scan for arbitrary processes.
                event("orphan-stop-intent", pid=target_pid)
                os.kill(target_pid, signal.SIGTERM)
                for phase_name in ("target", "finalizer"):
                    (case / (phase_name + ".release")).touch()
                deadline = time.monotonic() + 5
                while alive(target_pid) and time.monotonic() < deadline:
                    time.sleep(.01)
                if alive(target_pid):
                    raise RuntimeError("baseline child did not exit; retain fixture")
            record = case / "recovery-registration.json"
            complete = False
            if record.exists():
                value = json.loads(record.read_text())
                complete = value.get("sealed") is True and value.get("scope") == "registration-only" and value.get("actions") == {"dockerMutationCount": 0, "rootMutationCount": 0} and len(value.get("dockerObservation", {}).get("queries", [])) == 8 and value.get("sourceRegistry", {}).get("sha256") == source_before[-1] and value.get("root", {}).get("inode") == original_manifest["."][1] and stat.S_IMODE(record.stat().st_mode) == 0o600
            partials = sorted(item.name for item in case.iterdir() if item.name.startswith(".recovery-registration."))
            expected_record = stage in {"published", "after-directory-fsync", "repeated-after"}
            record_ok = (not record.exists() or complete) if stage == "concurrent-commit" else (complete if expected_record else not record.exists())
            unchanged = snapshot(source) == source_before and all(snapshot(root / key) == value for key, value in original_manifest.items())
            success_prose = "PASS:" in (case / "stdout").read_text()
            acknowledged = int((case / "signal-count").read_text()) if (case / "signal-count").exists() else 0
            acknowledgments = [json.loads(line) for line in (case / "signal-events.jsonl").read_text().splitlines()] if (case / "signal-events.jsonl").exists() else []
            repeated_ok = acknowledged == 3 if stage in {"repeated-before", "repeated-after", "ordinary-error-finalizer"} else acknowledged == 1
            commit_ack_ok = all(item["publishedAtAcknowledgment"] for item in acknowledgments) if stage == "concurrent-commit" else True
            passed = bool(observed and rc != 0 and process.pid == target_pid and not orphan and not partials and record_ok and unchanged and not success_prose and repeated_ok and commit_ack_ok)
            results.append({"case": stage, "pass": passed, "exitCode": rc, "launcherPid": process.pid, "registrationPid": target_pid, "pidContinuous": process.pid == target_pid, "orphanObserved": orphan, "signalCount": len(sent), "acknowledgedSignals": acknowledged, "signalAcknowledgments": acknowledgments, "temporaryResidue": partials, "recordExists": record.exists(), "completeSealedRecord": complete, "sourceAndRootUnchanged": unchanged, "successProse": success_prose})
            event("child-joined", stage=stage, pid=process.pid, exitCode=rc, registrationPid=target_pid, registrationAlive=bool(target_pid and alive(target_pid)))
finally:
    (evidence / "results.json").write_text(json.dumps({"baseRevision": revision, "sourceHashes": {str(path.relative_to(worktree)): hashlib.sha256(path.read_bytes()).hexdigest() for path in (launcher, guardrail)}, "cases": results}, indent=2, sort_keys=True) + "\n")
    if all(process.poll() is not None for process in processes) and root.exists() and not held.exists() and not staged.exists():
        event("fixture-retained", root=str(root), liveChildren=[], disposal="supervisor exact image detach")
for result in results:
    print(("PASS" if result["pass"] else "FAIL") + ": " + result["case"] + " " + json.dumps(result, sort_keys=True))
raise SystemExit(0 if len(results) == 13 and all(result["pass"] for result in results) else 1)
PY
