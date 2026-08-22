# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# How to run: python3 task30-live-run.py <compose> <project> <base-url> <playwright-config> <fifo-root>
# allow: SIZE_OK — one secret-safe process-owner state machine and its inert lifecycle proof stay together.

from __future__ import annotations

import errno
import json
import os
import pty
import secrets
import select
import signal
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable, Generator
from contextlib import contextmanager
from pathlib import Path
from types import FrameType
from typing import Final, NewType, NoReturn

ProjectName = NewType("ProjectName", str)
PROMPT: Final = b"Password: "
TIMEOUT_SECONDS: Final = 180
TERMINATE_GRACE_SECONDS: Final = 3
SUPERVISED_GRACE_SECONDS: Final = TERMINATE_GRACE_SECONDS * 2 + 1
POLL_SECONDS: Final = 0.05
HANDLED_SIGNALS: Final = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)


class Task30RunnerError(RuntimeError):
    pass


def group_exists(pgid: int) -> bool:
    try:
        os.killpg(pgid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def signal_owned_group(pgid: int, requested_signal: signal.Signals) -> None:
    if pgid == os.getpgrp():
        raise Task30RunnerError("OWNED_GROUP_MATCHES_RUNNER")
    try:
        os.killpg(pgid, requested_signal)
    except ProcessLookupError:
        pass


def terminate_owned_group(
    pgid: int,
    reap_direct_child: Callable[[], bool],
    grace_seconds: float = TERMINATE_GRACE_SECONDS,
) -> None:
    signal_owned_group(pgid, signal.SIGTERM)
    deadline = time.monotonic() + grace_seconds
    while time.monotonic() < deadline:
        reaped = reap_direct_child()
        if reaped and not group_exists(pgid):
            return
        time.sleep(POLL_SECONDS)
    signal_owned_group(pgid, signal.SIGKILL)
    deadline = time.monotonic() + grace_seconds
    while time.monotonic() < deadline:
        reaped = reap_direct_child()
        if reaped and not group_exists(pgid):
            return
        time.sleep(POLL_SECONDS)
    raise Task30RunnerError("OWNED_PROCESS_GROUP_CLEANUP_FAILED")


def terminate_process(
    process: subprocess.Popen[bytes],
    grace_seconds: float = TERMINATE_GRACE_SECONDS,
) -> None:
    terminate_owned_group(process.pid, lambda: process.poll() is not None, grace_seconds)


def terminate_pty(child: int, terminal: int) -> None:
    try:
        os.close(terminal)
    except OSError as error:
        if error.errno != errno.EBADF:
            raise

    def reap() -> bool:
        try:
            waited, _ = os.waitpid(child, os.WNOHANG)
        except ChildProcessError:
            return True
        return waited == child

    terminate_owned_group(child, reap)


@contextmanager
def cleanup_on_signals(cleanup: Callable[[], None]) -> Generator[None, None, None]:
    previous = {handled: signal.getsignal(handled) for handled in HANDLED_SIGNALS}

    def interrupted(signum: int, _frame: FrameType | None) -> NoReturn:
        for handled in HANDLED_SIGNALS:
            _ = signal.signal(handled, signal.SIG_IGN)
        cleanup()
        raise SystemExit(128 + signum)

    for handled in HANDLED_SIGNALS:
        _ = signal.signal(handled, interrupted)
    try:
        yield
    finally:
        for handled, handler in previous.items():
            _ = signal.signal(handled, handler)


def bootstrap(compose: Path, project: ProjectName, email: str, password: str) -> None:
    command = [
        "docker", "compose", "-p", project, "-f", str(compose), "exec", "-it", "backend",
        "sh", "-lc",
        " ".join((
            "exec java -Dloader.main=com.naraesigning.admin.AdminCommandApplication",
            "-cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher",
            f"create-admin {email}",
        )),
    ]
    child, terminal = pty.fork()
    if child == 0:
        os.execvp(command[0], command)
    output = bytearray()
    sent = False
    exit_status: int | None = None
    terminal_open = True
    deadline = time.monotonic() + TIMEOUT_SECONDS
    cleanup = lambda: terminate_pty(child, terminal)
    try:
        with cleanup_on_signals(cleanup):
            while time.monotonic() < deadline:
                if terminal_open:
                    readable, _, _ = select.select([terminal], [], [], 0.1)
                    if readable:
                        try:
                            chunk = os.read(terminal, 4096)
                        except OSError as error:
                            if error.errno != errno.EIO:
                                raise
                            terminal_open = False
                        else:
                            if not chunk:
                                terminal_open = False
                            else:
                                output.extend(chunk)
                                if not sent and PROMPT in output:
                                    _ = os.write(terminal, password.encode() + b"\n")
                                    sent = True
                else:
                    time.sleep(POLL_SECONDS)
                waited, status = os.waitpid(child, os.WNOHANG)
                if waited == child:
                    exit_status = os.waitstatus_to_exitcode(status)
                    break
            if exit_status is None:
                raise Task30RunnerError("ADMIN_BOOTSTRAP_TIMEOUT")
    finally:
        cleanup()
    if not sent or exit_status != 0:
        raise Task30RunnerError("ADMIN_BOOTSTRAP_FAILED")
    if password.encode() in output:
        raise Task30RunnerError("ADMIN_SECRET_ECHO_DETECTED")
    if f"Administrator created: {email}".encode() not in output:
        raise Task30RunnerError("ADMIN_BOOTSTRAP_AMBIGUOUS")


def send_credentials(
    fifo: Path,
    process: subprocess.Popen[bytes],
    email: str,
    password: str,
    timeout_seconds: float = TIMEOUT_SECONDS,
) -> None:
    payload = json.dumps({"email": email, "password": password}).encode()
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise Task30RunnerError("PLAYWRIGHT_EXITED_BEFORE_CREDENTIAL_READ")
        try:
            descriptor = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
        except OSError as error:
            if error.errno != errno.ENXIO:
                raise
            time.sleep(POLL_SECONDS)
            continue
        with os.fdopen(descriptor, "wb", closefd=True) as stream:
            _ = stream.write(payload)
        return
    raise Task30RunnerError("PLAYWRIGHT_CREDENTIAL_READ_TIMEOUT")


def wait_bounded(process: subprocess.Popen[bytes], seconds: float, timeout_code: str) -> int:
    try:
        return process.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        terminate_process(process)
        raise Task30RunnerError(timeout_code) from None


def deliver_credentials_and_wait(
    fifo: Path,
    process: subprocess.Popen[bytes],
    email: str,
    password: str,
    timeout_seconds: float,
) -> int:
    cleanup = lambda: terminate_process(process)
    try:
        with cleanup_on_signals(cleanup):
            send_credentials(fifo, process, email, password, timeout_seconds)
            return wait_bounded(process, timeout_seconds, "PLAYWRIGHT_TIMEOUT")
    finally:
        cleanup()


def create_fifo(root: Path, name: str) -> Path:
    fifo = root / f"browser-credentials-{name}.fifo"
    if fifo.exists():
        raise Task30RunnerError("CREDENTIAL_FIFO_ALREADY_EXISTS")
    os.mkfifo(fifo, 0o600)
    os.chmod(fifo, 0o600)
    if fifo.stat().st_mode & 0o777 != 0o600:
        raise Task30RunnerError("CREDENTIAL_FIFO_MODE_INVALID")
    return fifo


def process_exists(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def parse_tree_identity(line: str) -> tuple[int, int, int, int]:
    parts = line.strip().split()
    if len(parts) != 4:
        raise Task30RunnerError("SELF_TEST_IDENTITY_INVALID")
    return int(parts[0]), int(parts[1]), int(parts[2]), int(parts[3])


def read_process_line(process: subprocess.Popen[bytes]) -> str:
    if process.stdout is None:
        raise Task30RunnerError("SELF_TEST_STDOUT_MISSING")
    readable, _, _ = select.select([process.stdout.fileno()], [], [], 5)
    if not readable:
        raise Task30RunnerError("SELF_TEST_IDENTITY_TIMEOUT")
    return os.read(process.stdout.fileno(), 4096).decode()


def read_pty_line(terminal: int) -> str:
    output = bytearray()
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        readable, _, _ = select.select([terminal], [], [], POLL_SECONDS)
        if readable:
            output.extend(os.read(terminal, 4096))
            if b"\n" in output:
                return output.decode().strip()
    raise Task30RunnerError("SELF_TEST_PTY_IDENTITY_TIMEOUT")


def inert_tree() -> subprocess.Popen[bytes]:
    program = (
        "import os,subprocess,sys,time;"
        "descendant=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)']);"
        "print(os.getpid(),os.getpgrp(),descendant.pid,os.getpgid(descendant.pid),flush=True);"
        "time.sleep(60)"
    )
    return subprocess.Popen(
        [sys.executable, "-c", program],
        stdout=subprocess.PIPE,
        start_new_session=True,
    )


def inert_pty_tree() -> tuple[int, int, tuple[int, int, int, int]]:
    _ = sys.stdout.flush()
    child, terminal = pty.fork()
    if child == 0:
        descendant = os.fork()
        if descendant == 0:
            time.sleep(60)
            os._exit(0)
        print(os.getpid(), os.getpgrp(), descendant, os.getpgid(descendant), flush=True)
        time.sleep(60)
        os._exit(0)
    return child, terminal, parse_tree_identity(read_pty_line(terminal))


def assert_tree_gone(identity: tuple[int, int, int, int], scenario: str) -> None:
    child_pid, child_pgid, descendant_pid, descendant_pgid = identity
    if child_pgid != descendant_pgid:
        raise Task30RunnerError(f"{scenario}_PROCESS_GROUP_MISMATCH")
    if group_exists(child_pgid) or process_exists(child_pid) or process_exists(descendant_pid):
        raise Task30RunnerError(f"{scenario}_CHILD_SURVIVED")


def self_test_signal_target() -> int:
    child, terminal, identity = inert_pty_tree()
    cleanup = lambda: terminate_pty(child, terminal)
    try:
        with cleanup_on_signals(cleanup):
            print("READY", *identity, flush=True)
            while True:
                signal.pause()
    finally:
        cleanup()


def run_project(
    project_name: str,
    playwright_config: Path,
    fifo_root: Path,
    base_url: str,
    email: str,
    password: str,
) -> int:
    fifo = create_fifo(fifo_root, project_name)
    environment = os.environ.copy()
    environment.update({
        "TASK30_CREDENTIAL_FIFO": str(fifo),
        "TASK30_MVP_BASE_URL": base_url,
        "TASK30_MVP_MODE": "real",
        "TASK30_PLAYWRIGHT_OUTPUT": str(fifo_root / f"playwright-{project_name}"),
    })
    command = [
        "npx", "playwright", "test", "e2e/mvp-flow.spec.ts", "--config", str(playwright_config),
        f"--project={project_name}", "--workers=1", "--trace=off", "--reporter=line",
    ]
    try:
        process = subprocess.Popen(
            command,
            cwd=playwright_config.parents[3],
            env=environment,
            start_new_session=True,
        )
        return deliver_credentials_and_wait(fifo, process, email, password, TIMEOUT_SECONDS)
    finally:
        fifo.unlink(missing_ok=True)


def self_test() -> int:
    reader = (
        "import json,os,pathlib; "
        "value=json.loads(pathlib.Path(os.environ['TASK30_CREDENTIAL_FIFO']).read_text()); "
        "raise SystemExit(0 if sorted(value)==['email','password'] else 3)"
    )
    with tempfile.TemporaryDirectory(prefix="narae-task30-runner-test.", dir="/private/tmp") as temporary:
        root = Path(temporary)
        try:
            signal_owned_group(os.getpgrp(), signal.SIGTERM)
        except Task30RunnerError as error:
            if str(error) != "OWNED_GROUP_MATCHES_RUNNER":
                raise
        else:
            raise Task30RunnerError("SELF_TEST_OWN_GROUP_NOT_REJECTED")
        print("OWN_PROCESS_GROUP", f"runner_pgid={os.getpgrp()}", "rejected=true")

        for project_name in ("chromium", "webkit"):
            fifo = create_fifo(root, project_name)
            environment = os.environ.copy()
            environment["TASK30_CREDENTIAL_FIFO"] = str(fifo)
            try:
                process = subprocess.Popen(
                    [sys.executable, "-c", reader],
                    env=environment,
                    start_new_session=True,
                )
                if deliver_credentials_and_wait(
                    fifo,
                    process,
                    f"self-{secrets.token_hex(4)}@example.invalid",
                    secrets.token_urlsafe(24),
                    10,
                ) != 0:
                    raise Task30RunnerError("SELF_TEST_CONSUMER_FAILED")
            finally:
                fifo.unlink(missing_ok=True)

        fifo = create_fifo(root, "no-reader")
        process = inert_tree()
        no_reader_identity = parse_tree_identity(read_process_line(process))
        try:
            _ = deliver_credentials_and_wait(
                fifo,
                process,
                f"self-{secrets.token_hex(4)}@example.invalid",
                secrets.token_urlsafe(24),
                0.2,
            )
            raise Task30RunnerError("SELF_TEST_NO_READER_TIMEOUT_NOT_ENFORCED")
        except Task30RunnerError as error:
            if str(error) != "PLAYWRIGHT_CREDENTIAL_READ_TIMEOUT":
                raise
        finally:
            terminate_process(process)
            fifo.unlink(missing_ok=True)
        assert_tree_gone(no_reader_identity, "SELF_TEST_NO_READER")
        print("PLAYWRIGHT_NO_READER_TIMEOUT", *no_reader_identity, "alive_after=false")

        child, terminal, pty_identity = inert_pty_tree()
        try:
            time.sleep(0.2)
        finally:
            terminate_pty(child, terminal)
        assert_tree_gone(pty_identity, "SELF_TEST_PTY_TIMEOUT")
        print("PTY_TIMEOUT", *pty_identity, "alive_after=false")

        target = subprocess.Popen(
            [
                sys.executable,
                str(Path(__file__).resolve()),
                "--timeout",
                "30",
                sys.executable,
                str(Path(__file__).resolve()),
                "--self-test-signal-target",
            ],
            stdout=subprocess.PIPE,
            start_new_session=True,
        )
        target_identity_line = read_process_line(target).strip().split()
        if len(target_identity_line) != 5 or target_identity_line[0] != "READY":
            terminate_process(target)
            raise Task30RunnerError("SELF_TEST_SIGNAL_TARGET_INVALID")
        term_identity = parse_tree_identity(" ".join(target_identity_line[1:]))
        os.kill(target.pid, signal.SIGTERM)
        target_status = target.wait(timeout=10)
        if target_status != 128 + signal.SIGTERM:
            terminate_process(target)
            raise Task30RunnerError("SELF_TEST_SIGNAL_STATUS_INVALID")
        assert_tree_gone(term_identity, "SELF_TEST_SIGNAL")
        print("PTY_OUTER_TERM", *term_identity, f"supervisor_status={target_status}", "alive_after=false")
    print("PASS: engine FIFOs and Playwright/PTY timeout/TERM cleanup leave no children")
    return 0


def supervise() -> int:
    if len(sys.argv) < 4:
        raise Task30RunnerError("INVALID_TIMEOUT_ARGUMENTS")
    try:
        seconds = int(sys.argv[2])
    except ValueError:
        raise Task30RunnerError("INVALID_TIMEOUT_SECONDS") from None
    if seconds < 1:
        raise Task30RunnerError("INVALID_TIMEOUT_SECONDS")
    process = subprocess.Popen(sys.argv[3:], start_new_session=True)
    cleanup = lambda: terminate_process(process, SUPERVISED_GRACE_SECONDS)
    try:
        with cleanup_on_signals(cleanup):
            return wait_bounded(process, seconds, "COMMAND_TIMEOUT")
    finally:
        cleanup()


def run_live() -> int:
    if len(sys.argv) != 6:
        raise Task30RunnerError("INVALID_RUNNER_ARGUMENTS")
    compose = Path(sys.argv[1]).resolve(strict=True)
    project = ProjectName(sys.argv[2])
    base_url = sys.argv[3]
    playwright_config = Path(sys.argv[4]).resolve(strict=True)
    fifo_root = Path(sys.argv[5]).resolve(strict=True)
    if not str(fifo_root).startswith("/private/tmp/narae-task30-"):
        raise Task30RunnerError("INVALID_FIFO_ROOT")
    email = f"task30-{secrets.token_hex(8)}@example.invalid"
    credential = secrets.token_urlsafe(32) + "Aa1!"
    bootstrap(compose, project, email, credential)
    for project_name in ("chromium", "webkit"):
        status = run_project(project_name, playwright_config, fifo_root, base_url, email, credential)
        if status != 0:
            return status
    return 0


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test-signal-target":
        return self_test_signal_target()
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        return self_test()
    if len(sys.argv) >= 2 and sys.argv[1] == "--timeout":
        return supervise()
    return run_live()


if __name__ == "__main__":
    raise SystemExit(main())
