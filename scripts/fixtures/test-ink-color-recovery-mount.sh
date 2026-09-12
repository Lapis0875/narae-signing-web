#!/usr/bin/env bash
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
[[ $# == 2 && "$1" == --evidence-dir ]] || { printf 'usage: %s --evidence-dir PATH\n' "$0" >&2; exit 2; }
exec python3 - "$root" "$2" <<'PY'
import hashlib
import json
import os
import pathlib
import plistlib
import re
import secrets
import signal
import stat
import subprocess
import sys
import time

worktree = pathlib.Path(sys.argv[1])
evidence = pathlib.Path(sys.argv[2]).absolute()
evidence.mkdir(mode=0o700, parents=True, exist_ok=True)
os.chmod(evidence, 0o700)
journal = evidence / "resource-journal.jsonl"
cancelled = False

def event(action, **values):
    with journal.open("a") as stream:
        stream.write(json.dumps({"action": action, **values}, sort_keys=True) + "\n")
        stream.flush()
        os.fsync(stream.fileno())

def on_signal(signum, _frame):
    global cancelled
    cancelled = True

signal.signal(signal.SIGTERM, on_signal)
signal.signal(signal.SIGINT, on_signal)
token = secrets.token_hex(8)
root = pathlib.Path("/private/tmp") / ("narae-ink-color-qa." + token)
mountpoint = root / "mount-probe"
image = evidence / "probe.dmg"
source = evidence / "ownership-registry.json"
identity = evidence / "root-identity.json"
bindir = evidence / "bin"
output = evidence / "registration"
whole_device = None
partition = None
mounted = False
image_identity = None
root_identity = None
mount_identity = None
created = []
event("intent", root=str(root), mountpoint=str(mountpoint), image=str(image),
      device="bind exact attach receipt before mounting", controllerPid=os.getpid(), ports=[])

def run(label, argv, *, env=None, timeout=40):
    event("command-intent", label=label, argv=argv)
    child = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    event("child-started", label=label, pid=child.pid)
    try:
        stdout, stderr = child.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        child.terminate()
        stdout, stderr = child.communicate(timeout=15)
        event("child-timeout", label=label, pid=child.pid)
    (evidence / (label + ".stdout")).write_bytes(stdout)
    (evidence / (label + ".stderr")).write_bytes(stderr)
    (evidence / (label + ".exit")).write_text(str(child.returncode) + "\n")
    event("child-joined", label=label, pid=child.pid, exitCode=child.returncode)
    return child.returncode, stdout, stderr

def task_attachment():
    result = subprocess.run(["hdiutil", "info", "-plist"], capture_output=True, check=True, timeout=15)
    entries = [item for item in plistlib.loads(result.stdout).get("images", [])
               if item.get("image-path") == str(image)]
    if len(entries) > 1:
        raise RuntimeError("ambiguous task-image association; retain")
    return entries[0] if entries else None

def inode(path):
    info = path.lstat()
    return [info.st_dev, info.st_ino, info.st_uid, stat.S_IMODE(info.st_mode)]

def helper(function, argv):
    text = (worktree / "scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh").read_text()
    section = text.split(function + "() {", 1)[1].split("\n}\n", 1)[0]
    body = section.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
    event("helper-source", function=function, sha256=hashlib.sha256(body.encode()).hexdigest())
    old_argv = sys.argv
    try:
        sys.argv = [function, *map(str, argv), *([str(worktree)] if function == "safe_remove_recovery_root" else [])]
        exec(compile(body, function, "exec"), {"__name__": "__main__"})
    finally:
        sys.argv = old_argv

result = {"verdict": "BLOCKED", "actualMount": False, "detached": False}
try:
    if sys.platform != "darwin" or image.exists() or root.exists():
        raise RuntimeError("requires macOS and fresh exact task paths")
    root.mkdir(mode=0o700)
    root_identity = inode(root)
    mountpoint.mkdir(mode=0o700)
    mount_identity = inode(mountpoint)
    run_id = secrets.token_hex(32)
    project = "ink-color-qa-" + secrets.token_hex(6)
    launcher_pid = 999999
    try:
        os.kill(launcher_pid, 0)
    except ProcessLookupError:
        pass
    else:
        raise RuntimeError("synthetic absent PID is occupied")
    registry = {"schemaVersion": 2, "runId": run_id, "project": project,
                "ownerLabel": {"key": "com.naraemedia.qa.owner", "value": run_id},
                "createdAtEpoch": int(time.time()), "sealed": False,
                "intents": [{"scope": "local", "kind": "temporary-root", "identity": str(root)},
                            {"scope": "local", "kind": "launcher-pid", "identity": str(launcher_pid)}], "resources": []}
    source.write_text(json.dumps(registry) + "\n")
    source.chmod(0o600)
    marker = root / ".ink-color-qa-owned"
    marker.write_text(run_id + "\n")
    marker.chmod(0o600)
    marker_identity = inode(marker)
    helper("capture_recovery_root_identity", [root, source, identity])
    original_source = source.read_bytes()
    bindir.mkdir(mode=0o700)
    docker = bindir / "docker"
    docker.write_text('#!/bin/sh\ncase "$1 $2" in "info "|"container ls"|"network ls"|"volume ls"|"image ls") exit 0;; *) printf "unexpected Docker call\\n" >&2; exit 81;; esac\n')
    docker.chmod(0o700)
    output.mkdir(mode=0o700)
    rc, _, _ = run("create", ["hdiutil", "create", "-size", "16m", "-fs", "HFS+", "-volname", "InkMountProbe", "-type", "UDIF", "-nospotlight", str(image)])
    if rc:
        raise RuntimeError("actual image creation unavailable")
    image_identity = inode(image)
    if cancelled:
        raise RuntimeError("cancelled before attachment")
    rc, attached, _ = run("attach", ["hdiutil", "attach", "-nomount", "-noautoopen", "-plist", str(image)])
    association = task_attachment()
    if not association:
        raise RuntimeError("actual task-image attachment unavailable")
    entities = association.get("system-entities", [])
    devices = [item.get("dev-entry", "") for item in entities]
    roots = [device for device in devices if re.fullmatch(r"/dev/disk[0-9]+", device)]
    attach_entities = plistlib.loads(attached).get("system-entities", []) if not rc else []
    parts = [item.get("dev-entry") for item in attach_entities if item.get("content-hint") == "Apple_HFS" and item.get("dev-entry") in devices]
    if rc or len(roots) != 1 or len(parts) != 1:
        raise RuntimeError("attach receipt cannot resolve exact image device and HFS partition")
    whole_device, partition = roots[0], parts[0]
    event("device-registered", image=str(image), wholeDevice=whole_device, partition=partition, mountpoint=str(mountpoint))
    if cancelled:
        raise RuntimeError("cancelled before mount")
    rc, _, _ = run("mount", ["diskutil", "mount", "-mountPoint", str(mountpoint), partition])
    mounted = mountpoint.stat().st_dev != root.stat().st_dev
    if rc or not mounted:
        raise RuntimeError("actual task-owned mount unavailable")
    association = task_attachment()
    if not association or not any(item.get("dev-entry") == partition and item.get("mount-point") == str(mountpoint) for item in association.get("system-entities", [])):
        raise RuntimeError("mounted partition association mismatch")
    result["actualMount"] = True
    mountpoint.chmod(0o700)
    sentinel = mountpoint / "must-survive.txt"
    sentinel.write_text("task-owned actual mount sentinel\n")
    sentinel.chmod(0o600)
    before = inode(sentinel) + [hashlib.sha256(sentinel.read_bytes()).hexdigest()]
    environment = dict(os.environ, PATH=str(bindir) + ":/usr/bin:/bin:/usr/sbin:/sbin",
                       DOCKER_HOST="unix://" + str(evidence / "host-docker-must-not-exist.sock"), INK_QA_RECOVERY_SYNTHETIC_TEST="1")
    revision = subprocess.run(["git", "-C", str(worktree), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    rc, _, err = run("registration-refusal", ["bash", str(worktree / "scripts/fixtures/run-ink-color-qa.sh"), "--recovery-registration", "--recovery-root", str(root), "--source-registry", str(source), "--expected-registry-sha256", hashlib.sha256(original_source).hexdigest(), "--source-revision", revision, "--recovery-profile", "synthetic-test", "--evidence-dir", str(output)], env=environment)
    if not rc or b"ownership or mount boundary" not in err or list(output.iterdir()):
        raise RuntimeError("registration did not explicitly refuse actual mount")
    try:
        helper("safe_remove_recovery_root", [root, identity])
    except ValueError as error:
        (evidence / "teardown-refusal.txt").write_text(str(error) + "\n")
    else:
        raise RuntimeError("teardown did not refuse actual mount")
    after = inode(sentinel) + [hashlib.sha256(sentinel.read_bytes()).hexdigest()]
    if before != after or source.read_bytes() != original_source or inode(marker) != marker_identity:
        raise RuntimeError("mount refusal changed task-owned sentinel/source/marker")
    result.update(verdict="PASS", sentinelBefore=before, sentinelAfter=after,
                  registrationExit=rc, rootDevice=root.stat().st_dev, mountDevice=mountpoint.stat().st_dev)
    fixture = mountpoint / "recovery-fixture"
    fixture.mkdir(mode=0o700)
    receipt = evidence / "mount-identity.json"
    receipt.write_text(json.dumps({"schemaVersion": 1, "root": str(fixture), "mountpoint": str(mountpoint), "mountIdentity": inode(mountpoint), "image": str(image), "imageIdentity": image_identity, "device": whole_device, "partition": partition}) + "\n")
    receipt.chmod(0o600)
    mounted_registry = dict(registry, intents=[{"scope": "local", "kind": "temporary-root", "identity": str(fixture)}, {"scope": "local", "kind": "launcher-pid", "identity": str(launcher_pid)}])
    mounted_source = evidence / "mounted-source" / "ownership-registry.json"
    mounted_source.parent.mkdir(mode=0o700)
    mounted_source.write_text(json.dumps(mounted_registry) + "\n")
    mounted_source.chmod(0o600)
    (fixture / ".ink-color-qa-owned").write_text(run_id + "\n")
    (fixture / ".ink-color-qa-owned").chmod(0o600)
    environment["INK_QA_RECOVERY_MOUNT_RECEIPT"] = str(receipt)
    malformed_receipt = evidence / "malformed-mount.json"
    malformed_receipt.write_text("{malformed\n")
    malformed_receipt.chmod(0o600)
    mismatching_receipt = evidence / "mismatching-mount.json"
    mismatching = json.loads(receipt.read_text())
    mismatching["mountIdentity"][1] += 1
    mismatching_receipt.write_text(json.dumps(mismatching) + "\n")
    mismatching_receipt.chmod(0o600)
    profile_results = []
    for profile_case, selected_profile, selected_receipt in (("normal-rejects-mounted-root", "synthetic-test", receipt), ("missing-receipt", "synthetic-mount-test", evidence / "missing.json"), ("malformed-receipt", "synthetic-mount-test", malformed_receipt), ("mismatching-receipt", "synthetic-mount-test", mismatching_receipt), ("matching-receipt", "synthetic-mount-test", receipt)):
        case_output = evidence / profile_case
        case_output.mkdir(mode=0o700)
        case_env = dict(environment, INK_QA_RECOVERY_MOUNT_RECEIPT=str(selected_receipt))
        argv = ["bash", str(worktree / "scripts/fixtures/run-ink-color-qa.sh"), "--recovery-registration", "--recovery-root", str(fixture), "--source-registry", str(mounted_source), "--expected-registry-sha256", hashlib.sha256(mounted_source.read_bytes()).hexdigest(), "--source-revision", revision, "--recovery-profile", selected_profile, "--evidence-dir", str(case_output)]
        rc, _, _ = run(profile_case, argv, env=case_env)
        published = case_output / "recovery-registration.json"
        passed = bool(rc and not published.exists())
        if profile_case == "matching-receipt":
            value = json.loads(published.read_text()) if published.exists() else {}
            passed = rc == 0 and value.get("sealed") is True and value.get("actions") == {"dockerMutationCount": 0, "rootMutationCount": 0}
        profile_results.append({"case": profile_case, "pass": passed, "exitCode": rc})
    (evidence / "mount-profile-results.json").write_text(json.dumps(profile_results, indent=2) + "\n")
    if not all(item["pass"] for item in profile_results):
        raise RuntimeError("mounted synthetic profile regression failed")
    fixture.rename(mountpoint / "profile-observation")
    fixture.mkdir(mode=0o700)
    rc, _, _ = run("safety-matrix", ["bash", str(worktree / "scripts/fixtures/test-ink-color-recovery-safety.sh"), "--evidence-dir", str(evidence / "safety")], env=environment, timeout=120)
    if rc:
        raise RuntimeError("mounted safety matrix failed")
    fixture.rename(mountpoint / "safety-observation")
    fixture.with_name(fixture.name + ".replacement-observation").rename(mountpoint / "safety-replacement-observation")
    fixture.mkdir(mode=0o700)
    recovery_evidence = evidence / "recovery"
    rc, _, _ = run("recovery-demo", ["bash", str(worktree / "scripts/fixtures/test-ink-color-docker-cleanup-guardrail.sh"), "--recovery-registration-demo", "--evidence-dir", str(recovery_evidence)], env=environment, timeout=180)
    teardown = json.loads((recovery_evidence / "recovery-teardown.json").read_text())
    demo_record = json.loads((recovery_evidence / "happy/recovery-registration.json").read_text())
    if rc != 1 or teardown.get("outcome") != "retained" or teardown.get("rootMutationCount") != 0 or demo_record.get("sealed") is not True or demo_record.get("actions") != {"dockerMutationCount": 0, "rootMutationCount": 0}:
        raise RuntimeError("mounted demo did not retain exact fixture after successful zero-action registration")
    result["demo"] = {"innerExit": rc, "sealed": True, "registrationOnly": True, "rootMutationCount": 0, "retainedUntilDetach": fixture.exists()}
except BaseException as error:
    result["verdict"] = "BLOCKED"
    result["error"] = str(error)
finally:
    try:
        association = task_attachment() if image.exists() else None
        if association:
            devices = [item.get("dev-entry", "") for item in association.get("system-entities", [])]
            candidates = [device for device in devices if re.fullmatch(r"/dev/disk[0-9]+", device)]
            if len(candidates) != 1 or (whole_device and candidates != [whole_device]):
                raise RuntimeError("ambiguous detach identity; retain")
            whole_device = candidates[0]
            event("detach-registered", image=str(image), wholeDevice=whole_device)
            rc, _, _ = run("detach", ["hdiutil", "detach", whole_device])
            if rc or task_attachment() is not None or pathlib.Path(whole_device).exists():
                raise RuntimeError("exact device detach not verified; retain")
        result["detached"] = True
        if image.exists():
            if image_identity is None or inode(image) != image_identity:
                raise RuntimeError("image identity uncertain; retain")
            event("remove-owned-image", path=str(image), identity=image_identity)
            image.unlink()
        if mountpoint.exists():
            if inode(mountpoint) != mount_identity or list(mountpoint.iterdir()):
                raise RuntimeError("underlying mountpoint identity uncertain; retain")
            mountpoint.rmdir()
        if root.exists():
            if inode(root) != root_identity or set(item.name for item in root.iterdir()) != {".ink-color-qa-owned"}:
                raise RuntimeError("synthetic root identity uncertain; retain")
            if inode(root / ".ink-color-qa-owned") != marker_identity:
                raise RuntimeError("synthetic marker identity uncertain; retain")
            (root / ".ink-color-qa-owned").unlink()
            root.rmdir()
        result.update(imageResidue=image.exists(), mountpointResidue=mountpoint.exists(), rootResidue=root.exists())
        event("cleanup-verified", imageResidue=image.exists(), rootResidue=root.exists(), device=whole_device, deviceResidue=bool(whole_device and pathlib.Path(whole_device).exists()))
    except BaseException as error:
        result.update(verdict="BLOCKED", cleanupError=str(error))
    (evidence / "mount-result.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
print(json.dumps(result, sort_keys=True))
raise SystemExit(0 if result["verdict"] == "PASS" else 1)
PY
