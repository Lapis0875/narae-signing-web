#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export SCRIPT_DIR

python3 - "$@" <<'PY'
from __future__ import annotations

import fcntl
import csv
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import re
from pathlib import Path

SCHEMA = b"narae-signing-evidence-row-v1\n"
HEADERS = {
    "start": ["branch", "start_condition", "base_sha", "evidence_path", "evidence_sha256", "row_digest"],
    "terminal": ["todo", "branch", "base_sha", "commit_sha", "commit_subject", "acceptance_command", "evidence_path", "status", "blocker", "evidence_sha256", "row_digest"],
    "merge": ["branch", "branch_tip_sha", "main_parent_sha", "merge_sha", "evidence_path", "evidence_sha256", "row_digest"],
}
FILES = {
    "start": "narae-signing-web-mvp-branch-starts.tsv",
    "terminal": "narae-signing-web-mvp-ledger.tsv",
    "merge": "narae-signing-web-mvp-branch-merges.tsv",
}


def fail(message: str) -> None:
    raise SystemExit(f"record-plan-evidence: {message}")


def parse_options(arguments: list[str]) -> tuple[str, dict[str, str]]:
    if not arguments:
        fail("missing command")
    command = arguments[0]
    values: dict[str, str] = {}
    index = 1
    while index < len(arguments):
        option = arguments[index]
        if not option.startswith("--") or index + 1 >= len(arguments):
            fail(f"invalid option: {option}")
        values[option[2:].replace("-", "_")] = arguments[index + 1]
        index += 2
    return command, values


def check_text(value: str, name: str) -> None:
    if not value or any(ord(character) < 32 for character in value):
        fail(f"{name} is empty or contains a control byte")


def fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_write(path: Path, content: bytes, mode: int = 0o600) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            if os.environ.get("NARAE_TEST_FAILPOINT") == "file_fsync":
                fail("injected file fsync failure")
            os.fsync(output.fileno())
        if os.environ.get("NARAE_TEST_FAILPOINT") == "after_file_fsync":
            fail("injected failure after file fsync")
        os.replace(temporary, path)
        if os.environ.get("NARAE_TEST_FAILPOINT") == "after_rename_before_directory_fsync":
            fail("injected failure before directory fsync")
        fsync_directory(path.parent)
    finally:
        if temporary.exists():
            temporary.unlink()


def evidence_digest(path_value: str, evidence_dir: Path) -> tuple[Path, str]:
    path = Path(path_value)
    if not path.is_absolute():
        fail("evidence path must be absolute")
    resolved = path.resolve(strict=True)
    if resolved == evidence_dir or evidence_dir not in resolved.parents:
        fail("evidence path is outside retained NARAE_EVIDENCE_DIR")
    details = resolved.stat()
    if not resolved.is_file() or resolved.is_symlink():
        fail("evidence path must be a regular non-symlink file")
    descriptor = os.open(resolved, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    fsync_directory(resolved.parent)
    return resolved, hashlib.sha256(resolved.read_bytes()).hexdigest()


def row_hash(filename: str, columns: list[str], row: dict[str, str], prior: bytes) -> str:
    payload = bytearray(SCHEMA)
    name = filename.encode()
    payload.extend(struct.pack(">I", len(name)))
    payload.extend(name)
    payload.extend(struct.pack(">I", len(columns)))
    for column in columns:
        for item in (column, row[column]):
            encoded = item.encode()
            payload.extend(struct.pack(">I", len(encoded)))
            payload.extend(encoded)
    payload.extend(prior)
    return hashlib.sha256(payload).hexdigest()


def sealed_rows(kind: str, evidence_dir: Path) -> list[dict[str, str]]:
    path = evidence_dir / FILES[kind]
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source, delimiter="\t")
        if reader.fieldnames != HEADERS[kind]:
            fail(f"invalid prerequisite header in {path.name}")
        rows = list(reader)
    prior = bytes(32)
    for row in rows:
        if None in row or any(any(ord(character) < 32 for character in value) for value in row.values()):
            fail(f"malformed prerequisite row in {path.name}")
        evidence = Path(row["evidence_path"])
        if not evidence.is_absolute() or evidence.resolve(strict=True) != evidence or evidence_dir not in evidence.parents or evidence.is_symlink() or not evidence.is_file():
            fail(f"unsealed prerequisite evidence in {path.name}")
        if hashlib.sha256(evidence.read_bytes()).hexdigest() != row["evidence_sha256"]:
            fail(f"mutated prerequisite evidence in {path.name}")
        expected = row_hash(path.name, HEADERS[kind][:-1], row, prior)
        if row["row_digest"] != expected:
            fail(f"prerequisite row digest mismatch in {path.name}")
        prior = bytes.fromhex(expected)
    return rows


def validate_start_order(row: dict[str, str], evidence_dir: Path) -> None:
    starts = sealed_rows("start", evidence_dir)
    terminals = sealed_rows("terminal", evidence_dir)
    merges = sealed_rows("merge", evidence_dir)
    if row["branch"] == "chore/foundation":
        if terminals or merges or any(item["branch"] != "chore/foundation" for item in starts):
            fail("foundation bootstrap must be the first provenance row")
        return
    requirements = {
        "feat/security-auth": (["chore/foundation"], tuple(range(1, 7)), "chore/foundation"),
        "feat/board-domain": (["feat/security-auth"], tuple(range(7, 13)), "feat/security-auth"),
        "feat/admin-editor": (["feat/board-domain"], tuple(range(13, 17)), "feat/board-domain"),
        "feat/signing-core": (["feat/admin-editor"], tuple(range(17, 19)), "feat/admin-editor"),
        "feat/realtime-fullview": (["feat/signing-core"], tuple(range(19, 23)), "feat/signing-core"),
        "feat/render-deletion": (["feat/signing-core"], tuple(range(19, 23)), "feat/signing-core"),
        "ci/delivery": (["chore/foundation"], tuple(range(1, 7)), "chore/foundation"),
        "feat/public-signer-ui": (["feat/realtime-fullview", "feat/render-deletion", "ci/delivery"], (23, 25, 26, 27, 28), "ci/delivery"),
        "test/release-verification": (["feat/public-signer-ui"], (24,), "feat/public-signer-ui"),
    }
    predecessor_branches, predecessor_todos, base_branch = requirements[row["branch"]]
    start_branches = {item["branch"] for item in starts}
    merge_by_branch = {item["branch"]: item for item in merges}
    terminal_by_todo = {int(item["todo"]): item for item in terminals}
    if len(terminal_by_todo) != len(terminals) or len(merge_by_branch) != len(merges):
        fail("duplicate prerequisite provenance row")
    if any(branch not in start_branches or branch not in merge_by_branch for branch in predecessor_branches) or any(todo not in terminal_by_todo or terminal_by_todo[todo]["status"] != "complete" for todo in predecessor_todos):
        fail("branch prerequisite provenance is incomplete")
    with (Path(os.environ["SCRIPT_DIR"]) / "plan-evidence-contract.tsv").open(newline="", encoding="utf-8") as source:
        contract = {int(item["todo"]): item for item in csv.DictReader(source, delimiter="\t")}
    prerequisite_commits = [terminal_by_todo[todo]["commit_sha"] for todo in predecessor_todos]
    if len(set(prerequisite_commits)) != len(prerequisite_commits) or any(terminal_by_todo[todo]["branch"] != contract[todo]["branch"] for todo in predecessor_todos):
        fail("prerequisite terminal commits are duplicated or on the wrong branch")
    start_by_branch = {item["branch"]: item for item in starts}
    for branch in predecessor_branches:
        merge = merge_by_branch[branch]
        parents = subprocess.run(["git", "rev-list", "--parents", "-n", "1", merge["merge_sha"]], check=True, capture_output=True, text=True).stdout.split()
        if parents != [merge["merge_sha"], merge["main_parent_sha"], merge["branch_tip_sha"]]:
            fail("prerequisite merge object does not match its sealed row")
        expected = [item["commit_sha"] for item in sorted((item for item in terminals if item["status"] == "complete" and item["branch"] == branch), key=lambda item: int(contract[int(item["todo"])]["commit_order"]))]
        actual = subprocess.run(["git", "rev-list", "--reverse", f"{start_by_branch[branch]['base_sha']}..{merge['branch_tip_sha']}"], check=True, capture_output=True, text=True).stdout.splitlines()
        if actual != expected:
            fail("prerequisite branch sequence is not fully sealed")
    if row["base_sha"] != merge_by_branch[base_branch]["merge_sha"]:
        fail("branch base is not the prerequisite integration SHA")
    head = subprocess.run(["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
    status = subprocess.run(["git", "status", "--porcelain=v1", "-z"], check=True, capture_output=True).stdout
    if head != row["base_sha"] or status:
        fail("non-foundation start requires a clean managed worktree at its base SHA")


def append_row(kind: str, supplied: dict[str, str], evidence_dir: Path) -> str:
    filename = FILES[kind]
    headers = HEADERS[kind]
    columns = headers[:-1]
    unknown = set(supplied) - set(columns)
    if unknown:
        fail(f"unknown fields: {sorted(unknown)}")
    row = {column: supplied.get(column, "") for column in columns}
    required = {
        "start": {"branch", "start_condition", "base_sha", "evidence_path"},
        "terminal": {"todo", "branch", "evidence_path", "status"},
        "merge": {"branch", "branch_tip_sha", "main_parent_sha", "merge_sha", "evidence_path"},
    }[kind]
    for column in required:
        check_text(row[column], column)
    if kind == "terminal" and row["status"] not in {"complete", "blocked"}:
        fail("terminal status must be complete or blocked")
    if kind == "start":
        with (Path(os.environ["SCRIPT_DIR"]) / "plan-evidence-contract.tsv").open(newline="", encoding="utf-8") as source:
            matches = [item for item in csv.DictReader(source, delimiter="\t") if item["branch"] == row["branch"]]
        if not matches or any(item["start_condition"] != row["start_condition"] for item in matches):
            fail("branch start packet does not match contract")
    if kind == "terminal":
        try:
            todo = int(row["todo"])
        except ValueError:
            fail("todo must be an integer")
        if todo not in range(1, 31):
            fail("todo is outside 1-30")
        if row["status"] == "blocked" and not row["blocker"]:
            fail("blocked terminal requires blocker")
        if row["status"] == "complete" and row["blocker"]:
            fail("complete terminal cannot carry blocker")
        if todo < 30:
            with (Path(os.environ["SCRIPT_DIR"]) / "plan-evidence-contract.tsv").open(newline="", encoding="utf-8") as source:
                expected = next(item for item in csv.DictReader(source, delimiter="\t") if item["todo"] == row["todo"])
            if row["branch"] != expected["branch"] or row["status"] == "complete" and any(not row[field] for field in ("base_sha", "commit_sha", "commit_subject", "acceptance_command")):
                fail("terminal packet does not match contract")
        elif any(row[field] for field in ("base_sha", "commit_sha", "commit_subject")):
            fail("Todo 30 cannot carry commit fields")
    sha_fields = {"start": ("base_sha",), "terminal": ("base_sha", "commit_sha"), "merge": ("branch_tip_sha", "main_parent_sha", "merge_sha")}[kind]
    for field in sha_fields:
        if row[field] and not re.fullmatch(r"[0-9a-f]{40}", row[field]):
            fail(f"{field} must be a lowercase Git SHA")
    evidence, digest = evidence_digest(row["evidence_path"], evidence_dir)
    row["evidence_path"] = str(evidence)
    row["evidence_sha256"] = digest
    if kind == "start":
        validate_start_order(row, evidence_dir)
    if kind == "merge":
        branch_hash = hashlib.sha256(row["branch"].encode()).hexdigest()
        intent_path = evidence_dir / "attempts" / os.environ.get("NARAE_ATTEMPT_ID", "") / f"merge-intent-branch-{branch_hash}.json"
        if not intent_path.is_file():
            fail("matching merge intent is required")
        intent = json.loads(intent_path.read_text(encoding="utf-8"))
        head = subprocess.run(["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
        parents = subprocess.run(["git", "rev-list", "--parents", "-n", "1", head], check=True, capture_output=True, text=True).stdout.split()
        tree = subprocess.run(["git", "show", "-s", "--format=%T", head], check=True, capture_output=True, text=True).stdout.strip()
        acceptance = Path(intent.get("acceptance_evidence_path", ""))
        if head != row["merge_sha"] or parents != [head, row["main_parent_sha"], row["branch_tip_sha"]] or intent.get("branch") != row["branch"] or intent.get("candidate_merge_sha") != head or intent.get("candidate_tree_sha") != tree or intent.get("parents") != parents[1:] or not acceptance.is_file() or hashlib.sha256(acceptance.read_bytes()).hexdigest() != intent.get("acceptance_evidence_sha256"):
            fail("fresh verifier or merge intent mismatch")
    target = evidence_dir / filename
    expected_header = "\t".join(headers)
    lines = target.read_text(encoding="utf-8").splitlines() if target.exists() else []
    if lines and lines[0] != expected_header:
        fail(f"unexpected header in {filename}")
    keys = {"start": ["branch"], "terminal": ["todo"], "merge": ["branch"]}[kind]
    for existing in lines[1:]:
        values = dict(zip(headers, existing.split("\t")))
        if all(values[key] == row[key] for key in keys):
            if all(values[column] == row[column] for column in columns):
                fsync_directory(target.parent)
                return values["row_digest"]
            fail(f"conflicting duplicate {kind} row")
    prior = bytes.fromhex(lines[-1].split("\t")[-1]) if len(lines) > 1 else bytes(32)
    row["row_digest"] = row_hash(filename, columns, row, prior)
    body = [expected_header] if not lines else lines
    body.append("\t".join(row[column] for column in headers))
    atomic_write(target, ("\n".join(body) + "\n").encode())
    return row["row_digest"]


command, options = parse_options(sys.argv[1:])
evidence_value = os.environ.get("NARAE_EVIDENCE_DIR")
if evidence_value is None:
    fail("NARAE_EVIDENCE_DIR is required")
evidence_dir = Path(evidence_value).resolve(strict=True)
lock_path = evidence_dir / "narae-signing-web-mvp-ledger.lock"
with lock_path.open("a+b") as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    if command in {"write-start", "write-terminal", "write-merge"}:
        kind = command.removeprefix("write-")
        print(append_row(kind, options, evidence_dir))
    elif command == "write-json":
        source = Path(options.get("input", ""))
        output = Path(options.get("output", ""))
        if output.suffix != ".json" or not output.is_absolute() or evidence_dir not in output.parent.resolve().parents and output.parent.resolve() != evidence_dir:
            fail("JSON output must be an absolute path inside the evidence directory")
        if any(word in output.name for word in ("receipt", "deployment")):
            fail("release receipts and deployment evidence are not supported")
        parsed = json.loads(source.read_text(encoding="utf-8"))
        content = (json.dumps(parsed, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()
        atomic_write(output, content)
        print(hashlib.sha256(content).hexdigest())
    elif command == "write-merge-intent":
        required = {"branch", "branch_tip_sha", "main_parent_sha", "candidate_merge_sha", "candidate_tree_sha", "parents_json", "acceptance_commands_json", "acceptance_evidence"}
        if set(options) != required:
            fail("merge intent requires its exact packet fields")
        branch = options["branch"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9._/-]*", branch) or ".." in branch or "//" in branch:
            fail("unsafe branch name")
        branch_hash = hashlib.sha256(branch.encode()).hexdigest()
        output = evidence_dir / "attempts" / os.environ.get("NARAE_ATTEMPT_ID", "") / f"merge-intent-branch-{branch_hash}.json"
        if output.parent.parent.parent != evidence_dir or not output.parent.is_dir():
            fail("NARAE_ATTEMPT_ID must name an existing retained attempt")
        parents = json.loads(options["parents_json"])
        commands = json.loads(options["acceptance_commands_json"])
        evidence, evidence_sha = evidence_digest(options["acceptance_evidence"], evidence_dir)
        if parents != [options["main_parent_sha"], options["branch_tip_sha"]] or not isinstance(commands, list) or not commands:
            fail("invalid merge intent parents or commands")
        if any(set(item) != {"id", "digest", "exit"} or item["exit"] != 0 or not re.fullmatch(r"[0-9a-f]{64}", item["digest"]) for item in commands):
            fail("invalid merge intent acceptance command")
        value = {"schema_version": "narae-signing-merge-intent-v1", **{key: options[key] for key in ("branch", "branch_tip_sha", "main_parent_sha", "candidate_merge_sha", "candidate_tree_sha")}, "parents": parents, "acceptance_commands": commands, "acceptance_evidence_path": str(evidence), "acceptance_evidence_sha256": evidence_sha}
        content = (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()
        if output.exists() and output.read_bytes() != content:
            fail("conflicting merge intent")
        atomic_write(output, content)
        print(hashlib.sha256(content).hexdigest())
    else:
        fail(f"unknown command: {command}")
PY
