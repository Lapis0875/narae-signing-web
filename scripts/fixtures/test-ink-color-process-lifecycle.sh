#!/usr/bin/env bash
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
python3 - "$root" "$@" <<'PY'
import ast
import json
import os
import pathlib
import secrets
import select
import signal
import stat
import subprocess
import sys
import time

root = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)
source = (pathlib.Path(sys.argv[3]) if len(sys.argv) > 3 else root / "scripts/fixtures/run-ink-color-qa.sh").read_text()
registry_code = source.split("docker_registry_action() {", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
tree = ast.parse(registry_code)
validator = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "validate_local_intents")
# Execute the actual local-intent loop; root/Docker ownership is covered by its existing harness.
loop = next(node for node in validator.body if isinstance(node, ast.For))
module = ast.Module(body=[loop], type_ignores=[])
compiled = compile(ast.fix_missing_locations(module), "launcher-local-intent-loop", "exec")
run_id = secrets.token_hex(32)
lifecycle = output / "playwright-process-lifecycle.jsonl"
registered = {"event": "registered", "runId": run_id, "identity": str(lifecycle)}
lifecycle.write_text(json.dumps(registered) + "\n")
lifecycle.chmod(0o600)
namespace = {"pathlib": pathlib, "json": json, "stat": stat, "os": os,
             "temporary_root": output, "run_id": run_id}
checks = 0

def validate(action: str, identity: str) -> None:
    namespace.update(action=action, keys=[("local", "process-intent", identity)])
    exec(compiled, namespace)

def reject(action: str, identity: str) -> None:
    global checks
    try:
        validate(action, identity)
    except (ValueError, OSError):
        checks += 1
    else:
        raise AssertionError(f"accepted invalid lifecycle during {action}")

# Given existing non-Playwright declarations, when validating, then preserve their contract.
for identity in ("admin-bootstrap", "schema-preflight", "restore-smoke", "cleanup-self-test", "cleanup-self-test-local-only"):
    validate("bind", identity)
    checks += 1
# Given registered ownership, when preparing the stack, then permit a not-yet-started child.
for action in ("bind", "seal", "validate-ports", "verify"):
    validate(action, str(lifecycle))
    checks += 1
# Given generic or incomplete evidence, when cleaning up, then reject it.
reject("validate-temp", "playwright")
for action in ("plan-cleanup", "validate-cleanup-plan", "validate-temp"):
    reject(action, str(lifecycle))

environment = os.environ.copy()
environment.update(TASK30_LIFECYCLE_FILE=str(lifecycle), TASK30_RUN_ID=run_id)
result = subprocess.run([sys.executable, str(root / "scripts/fixtures/task30-live-run.py"),
                         "--timeout", "5", sys.executable, "-c", "pass"],
                        env=environment, timeout=25, check=True)
events = [json.loads(line) for line in lifecycle.read_text().splitlines()]
assert [event["event"] for event in events] == ["registered", "started", "exited", "cleanup-requested", "absence"]
assert all(event["runId"] == run_id for event in events)
pid, pgid = events[1]["pid"], events[1]["pgid"]
assert type(pid) is int and pid > 1 and pid == pgid
assert events[-1]["processGone"] is True and events[-1]["groupGone"] is True
for query, identity in ((os.kill, pid), (os.killpg, pgid)):
    try:
        query(identity, 0)
    except ProcessLookupError:
        checks += 1
    else:
        raise AssertionError("inert child or group survived")
for action in ("plan-cleanup", "validate-cleanup-plan", "validate-temp"):
    validate(action, str(lifecycle))
    checks += 1
valid_content = lifecycle.read_text()
try:
    events[2]["runId"] = "stale-run"
    lifecycle.write_text("\n".join(json.dumps(event) for event in events) + "\n")
    reject("validate-temp", str(lifecycle))
    lifecycle.write_text("{malformed\n")
    reject("validate-temp", str(lifecycle))
    lifecycle.write_text(valid_content)
    lifecycle.chmod(0o644)
    reject("validate-temp", str(lifecycle))
    lifecycle.chmod(0o600)
    events = [json.loads(line) for line in valid_content.splitlines()]
    events[-1]["groupGone"] = False
    lifecycle.write_text("\n".join(json.dumps(event) for event in events) + "\n")
    reject("validate-temp", str(lifecycle))
finally:
    lifecycle.write_text(valid_content)
    lifecycle.chmod(0o600)

# Given a hung child or external cancellation, when supervised, then prove exact cleanup.
runner = root / "scripts/fixtures/task30-live-run.py"
for scenario in ("timeout", "term"):
    evidence = output / f"{scenario}-lifecycle.jsonl"
    environment["TASK30_LIFECYCLE_FILE"] = str(evidence)
    command = [sys.executable, str(runner), "--timeout", "1" if scenario == "timeout" else "10",
               sys.executable, str(runner), "--self-test-signal-target"]
    with subprocess.Popen(command, env=environment, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, start_new_session=True) as supervisor:
        try:
            assert supervisor.stdout is not None
            assert select.select([supervisor.stdout], [], [], 5)[0], "signal target did not become ready"
            ready = os.read(supervisor.stdout.fileno(), 4096).decode().split()
            assert ready[0] == "READY" and len(ready) == 5
            if scenario == "term":
                os.kill(supervisor.pid, signal.SIGTERM)
            _, stderr = supervisor.communicate(timeout=25)
        finally:
            if supervisor.poll() is None:
                supervisor.kill()
                supervisor.wait(timeout=5)
        assert supervisor.returncode != 0
        if scenario == "term":
            assert supervisor.returncode == 143
        else:
            assert b"COMMAND_TIMEOUT" in stderr
        interrupted = [json.loads(line) for line in evidence.read_text().splitlines()]
        assert interrupted[-1]["processGone"] is True and interrupted[-1]["groupGone"] is True
        for child_pid in (supervisor.pid, interrupted[0]["pid"], int(ready[1]), int(ready[3])):
            try:
                os.kill(child_pid, 0)
            except ProcessLookupError:
                checks += 1
            else:
                raise AssertionError(f"{scenario} left an owned process")
        for child_pgid in (supervisor.pid, interrupted[0]["pgid"], int(ready[2])):
            try:
                os.killpg(child_pgid, 0)
            except ProcessLookupError:
                checks += 1
            else:
                raise AssertionError(f"{scenario} left an owned group")
        print(json.dumps({"scenario": scenario, "supervisorPid": supervisor.pid,
                          "tree": [int(value) for value in ready[1:]],
                          "exitCode": supervisor.returncode, "processGone": True, "groupGone": True}))
# Given a real metadata write failure, when supervision unwinds, then owned groups are gone.
writer_driver = r'''
import importlib.util
import json
import os
import pathlib
import sys
runner, failed_event, evidence, run_id = sys.argv[1:]
spec = importlib.util.spec_from_file_location("owned_runner", runner)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
append = module.append_lifecycle
def fail_writer(event, **fields):
    if event == failed_event:
        print(json.dumps({"event": "writer-failure-injected", "failedEvent": event,
                          "runId": run_id, "supervisorPid": os.getpid(),
                          "pid": fields["pid"], "pgid": fields["pgid"]}), flush=True)
        pathlib.Path(evidence).chmod(0o644)
        try:
            append(event, **fields)
        finally:
            pathlib.Path(evidence).chmod(0o600)
    else:
        append(event, **fields)
module.append_lifecycle = fail_writer
child = "import os,time; fd=os.open(os.devnull,os.O_WRONLY); os.dup2(fd,1); os.dup2(fd,2); time.sleep(60)"
if failed_event == "cleanup-requested":
    child = ("import json,subprocess,sys; p=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],"
             "stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);"
             "print(json.dumps({'descendantPid':p.pid}),flush=True)")
sys.argv = [runner, "--timeout", "5", sys.executable, "-c", child]
raise SystemExit(module.supervise())
'''
writer_survivors = []
for failed_event in ("started", "cleanup-requested"):
    evidence = output / f"writer-failure-{failed_event}.jsonl"
    evidence.write_text(json.dumps({"event": "registered", "identity": str(evidence), "runId": run_id}) + "\n")
    evidence.chmod(0o600)
    environment["TASK30_LIFECYCLE_FILE"] = str(evidence)
    failed = subprocess.run([sys.executable, "-c", writer_driver, str(runner), failed_event, str(evidence), run_id],
                            env=environment, capture_output=True, text=True, timeout=25, start_new_session=True)
    records = [json.loads(line) for line in failed.stdout.splitlines()]
    boundary = next(record for record in records if record.get("event") == "writer-failure-injected")
    owned_pid, owned_group = boundary["pid"], boundary["pgid"]
    owned_pids = [boundary["supervisorPid"], owned_pid] + [record["descendantPid"] for record in records if "descendantPid" in record]
    owned_groups = [boundary["supervisorPid"], owned_group]
    observed = []
    try:
        for kind, identity, query in [("pid", value, os.kill) for value in owned_pids] + [("pgid", value, os.killpg) for value in owned_groups]:
            try:
                query(identity, 0)
            except ProcessLookupError:
                observed.append({"kind": kind, "identity": identity, "absent": True})
            else:
                observed.append({"kind": kind, "identity": identity, "absent": False})
        emitted = [json.loads(line) for line in evidence.read_text().splitlines()]
        print(json.dumps({"scenario": "writer-failure", "runId": run_id, "failedEvent": failed_event,
                          "exitCode": failed.returncode, "checks": observed,
                          "error": failed.stderr.splitlines()[-1], "lifecycle": emitted}), flush=True)
        assert failed.returncode != 0 and "LIFECYCLE_FILE_METADATA_INVALID" in failed.stderr
        if not all(record["absent"] for record in observed):
            writer_survivors.append(failed_event)
        else:
            expected = ["registered", "cleanup-requested", "absence"] if failed_event == "started" else ["registered", "started", "exited", "absence"]
            assert [event["event"] for event in emitted] == expected
            assert all(event["runId"] == run_id for event in emitted)
            assert emitted[-1] == {"event": "absence", "runId": run_id, "pid": owned_pid,
                                   "pgid": owned_group, "processGone": True, "groupGone": True}
    finally:
        # A failing-first run must clean only the exact group it just proved was leaked.
        for requested_signal in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(owned_group, requested_signal)
            except ProcessLookupError:
                break
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    os.killpg(owned_group, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            else:
                continue
            break
        for kind, identity, query in [("pid", value, os.kill) for value in owned_pids] + [("pgid", value, os.killpg) for value in owned_groups]:
            try:
                query(identity, 0)
            except ProcessLookupError:
                print(json.dumps({"cleanup": failed_event, "kind": kind, "identity": identity, "absent": True}))
            else:
                raise AssertionError("writer failure probe cleanup leaked")
    checks += len(observed)
assert not writer_survivors, f"writer failures bypassed cleanup: {writer_survivors}"
print(json.dumps({"status": "PASS", "checks": checks, "runId": run_id,
                  "lifecycle": str(lifecycle), "pid": pid, "pgid": pgid,
                  "processGone": True, "groupGone": True}))
PY
