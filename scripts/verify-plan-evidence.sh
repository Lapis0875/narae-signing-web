#!/bin/sh
# allow: SIZE_OK — one fail-closed provenance audit transaction
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export SCRIPT_DIR

python3 - "$@" <<'PY'
from __future__ import annotations

import argparse
import csv
import fnmatch
import hashlib
import json
import struct
import subprocess
import sys
import os
import re
from pathlib import Path

SCHEMA = b"narae-signing-evidence-row-v1\n"


def stop(message: str) -> None:
    raise SystemExit(f"plan evidence invalid: {message}")


def digest_row(filename: str, headers: list[str], row: dict[str, str], prior: bytes) -> str:
    payload = bytearray(SCHEMA)
    encoded_name = filename.encode()
    payload.extend(struct.pack(">I", len(encoded_name)))
    payload.extend(encoded_name)
    payload.extend(struct.pack(">I", len(headers)))
    for header in headers:
        for value in (header, row[header]):
            encoded = value.encode()
            payload.extend(struct.pack(">I", len(encoded)))
            payload.extend(encoded)
    payload.extend(prior)
    return hashlib.sha256(payload).hexdigest()


def read_tsv(path: Path, evidence_dir: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source, delimiter="\t")
        if reader.fieldnames is None or reader.fieldnames[-2:] != ["evidence_sha256", "row_digest"]:
            stop(f"{path.name}: invalid digest columns")
        rows = list(reader)
        headers = reader.fieldnames[:-1]
    prior = bytes(32)
    for row in rows:
        if None in row or any(any(ord(character) < 32 for character in value) for value in row.values()):
            stop(f"{path.name}: malformed field")
        evidence = Path(row["evidence_path"])
        resolved = evidence.resolve(strict=True)
        if not evidence.is_absolute() or resolved == evidence_dir or evidence_dir not in resolved.parents or not resolved.is_file() or evidence.is_symlink():
            stop(f"{path.name}: invalid evidence path")
        actual_evidence = hashlib.sha256(evidence.read_bytes()).hexdigest()
        if actual_evidence != row["evidence_sha256"]:
            stop(f"{path.name}: evidence was modified")
        expected = digest_row(path.name, headers, row, prior)
        if row["row_digest"] != expected:
            stop(f"{path.name}: row digest mismatch")
        prior = bytes.fromhex(expected)
    return rows


def git(*arguments: str) -> str:
    result = subprocess.run(["git", *arguments], check=False, capture_output=True, text=True)
    if result.returncode != 0:
        stop(f"git {' '.join(arguments)} failed")
    return result.stdout.rstrip("\n")


def line_intervals(content: str, selector: str) -> tuple[int, int]:
    lines = content.splitlines()
    if ".." in selector:
        start, end = selector.split("..", 1)
        if lines.count(start) != 1 or lines.count(end) != 1 or lines.index(start) >= lines.index(end):
            stop("marker selector is missing, duplicated, or reversed")
        return lines.index(start) + 2, lines.index(end)
    matches = [index for index, line in enumerate(lines) if line == selector]
    if len(matches) != 1 or not selector.startswith("#"):
        stop("heading selector is missing or ambiguous")
    start = matches[0]
    level = len(selector) - len(selector.lstrip("#"))
    end = len(lines)
    for index, line in enumerate(lines[start + 1 :], start + 1):
        match = re.match(r"^(#+) ", line)
        if match and len(match.group(1)) <= level:
            end = index
            break
    return start + 1, end


def changed_lines(parent: str, commit: str, path: str) -> tuple[list[int], list[int]]:
    patch = git("diff", "--unified=0", parent, commit, "--", path)
    old: list[int] = []
    new: list[int] = []
    for line in patch.splitlines():
        match = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
        if match:
            old.extend(range(int(match.group(1)), int(match.group(1)) + int(match.group(2) or "1")))
            new.extend(range(int(match.group(3)), int(match.group(3)) + int(match.group(4) or "1")))
    return old, new


def verify_selected_hunk(parent: str, commit: str, value: str) -> None:
    path, selector = value.split("::", 1)
    before = git("show", f"{parent}:{path}")
    after = git("show", f"{commit}:{path}")
    if ".." in selector:
        start, end = selector.split("..", 1)
        if before.splitlines().count(start) != 1 or after.splitlines().count(start) != 1 or before.splitlines().count(end) != 1 or after.splitlines().count(end) != 1:
            stop("marker boundaries must preexist and remain unique")
    old_range = line_intervals(before, selector)
    new_range = line_intervals(after, selector)
    old, new = changed_lines(parent, commit, path)
    if any(line < old_range[0] or line > old_range[1] for line in old) or any(line < new_range[0] or line > new_range[1] for line in new):
        stop("document hunk escaped its selector")


def verify_route_hunk(parent: str, commit: str, route: dict[str, str]) -> None:
    path = route["path"]
    start = route["start_marker"]
    end = route["end_marker"]
    verify_selected_hunk(parent, commit, f"{path}::{start}..{end}")


if sys.argv[1:2] == ["--probe-hunk"]:
    if len(sys.argv) != 6:
        stop("probe-hunk requires parent, commit, selector, and route JSON")
    probe_parent, probe_commit, probe_selector, probe_route = sys.argv[2:]
    if probe_route == "null":
        verify_selected_hunk(probe_parent, probe_commit, probe_selector)
    else:
        verify_route_hunk(probe_parent, probe_commit, json.loads(probe_route))
    print("hunk_valid=true")
    raise SystemExit(0)


def compact_json(raw: str, expected_type: type) -> object:
    parsed = json.loads(raw)
    if type(parsed) is not expected_type or json.dumps(parsed, ensure_ascii=False, separators=(",", ":")) != raw:
        stop("contract JSON is not compact or has the wrong type")
    return parsed


def path_allowed(row: dict[str, str], path: str) -> bool:
    ordinary = compact_json(row["ordinary_pathspecs_json"], list)
    documents = [item.split("::", 1)[0] for item in compact_json(row["document_selectors_json"], list)]
    routes = [item["path"] for item in compact_json(row["route_registration_hunks_json"], list)]
    return any(fnmatch.fnmatch(path, pattern) for pattern in ordinary) or path in documents + routes


parser = argparse.ArgumentParser()
normal_mode = "--probe-path" not in sys.argv
parser.add_argument("--plan", required=normal_mode, type=Path)
parser.add_argument("--ledger", required=normal_mode, type=Path)
parser.add_argument("--branch-starts", required=normal_mode, type=Path)
parser.add_argument("--branch-merges", required=normal_mode, type=Path)
parser.add_argument("--baseline", required=normal_mode, type=Path)
parser.add_argument("--head", required=normal_mode)
parser.add_argument("--candidate", action="store_true")
parser.add_argument("--probe-path", nargs=2)
arguments = parser.parse_args()

contract_path = Path(os.environ["SCRIPT_DIR"]) / "plan-evidence-contract.tsv"
with contract_path.open(newline="", encoding="utf-8") as source:
    contract_rows = list(csv.DictReader(source, delimiter="\t"))
if len(contract_rows) != 29 or [int(row["todo"]) for row in contract_rows] != list(range(1, 30)):
    stop("contract must contain Todos 1-29 in order")
contract: dict[int, dict[str, str]] = {}
for row in contract_rows:
    ordinary = compact_json(row["ordinary_pathspecs_json"], list)
    selectors = compact_json(row["document_selectors_json"], list)
    routes = compact_json(row["route_registration_hunks_json"], list)
    if not ordinary or len(set(ordinary)) != len(ordinary) or any(type(item) is not str or not item or any(ord(character) < 32 for character in item) for item in ordinary):
        stop("ordinary pathspec arrays require nonempty strings")
    if len(set(selectors)) != len(selectors) or any(type(item) is not str or "::" not in item or any(ord(character) < 32 for character in item) for item in selectors):
        stop("invalid document selector")
    if any(type(item) is not dict or list(item) != ["path", "start_marker", "end_marker"] for item in routes):
        stop("invalid route registration hunk")
    contract[int(row["todo"])] = row
orders = [int(row["commit_order"]) for row in contract_rows]
if any(order <= 0 for order in orders) or len(set(orders)) != len(orders) or not int(contract[7]["commit_order"]) < int(contract[9]["commit_order"]) < int(contract[8]["commit_order"]) < int(contract[10]["commit_order"]):
    stop("contract commit order is not dependency-topological")
route_names = [item[key] for row in contract_rows for item in compact_json(row["route_registration_hunks_json"], list) for key in ("start_marker", "end_marker")]
if len(route_names) != 8 or len(set(route_names)) != 8:
    stop("route marker intervals are missing or overlapping")
todo19_marker = "docs/plan/IMPLEMENTATION_PLAN.md::<!-- NARAE_TODO19_BACKGROUND_ADOPTION_START -->..<!-- NARAE_TODO19_BACKGROUND_ADOPTION_END -->"
if compact_json(contract[19]["document_selectors_json"], list).count(todo19_marker) != 1:
    stop("Todo 19 future marker selector is not predeclared exactly once")
if sys.argv[1:2] == ["--probe-path"]:
    if len(sys.argv) != 4 or not path_allowed(contract[int(sys.argv[2])], sys.argv[3]):
        stop("path is outside the Todo contract")
    print("path_valid=true")
    raise SystemExit(0)

evidence_dir = arguments.baseline.resolve().parent
primary_root = evidence_dir.parents[1]
reader = Path(os.environ["SCRIPT_DIR"]) / "read-plan-baseline.py"
validated_baseline = subprocess.run([str(reader), "--primary-root", str(primary_root), "--field", "baseline_sha"], capture_output=True, text=True)
if validated_baseline.returncode != 0 or validated_baseline.stdout.count("\n") != 1:
    stop("baseline parser rejected the retained root")
ledger = read_tsv(arguments.ledger, evidence_dir)
starts = read_tsv(arguments.branch_starts, evidence_dir)
merges = read_tsv(arguments.branch_merges, evidence_dir)
required_todos = set(range(1, 30 if arguments.candidate else 31))
actual_todos = [int(row["todo"]) for row in ledger]
if set(actual_todos) != required_todos or len(actual_todos) != len(required_todos):
    stop("missing or duplicate terminal row")
release_ready = True
start_by_branch = {row["branch"]: row for row in starts}
merge_by_branch = {row["branch"]: row for row in merges}
if len(start_by_branch) != len(starts) or len(merge_by_branch) != len(merges):
    stop("duplicate branch start or merge row")

baseline_values = {}
for line in arguments.baseline.read_text(encoding="ascii").splitlines():
    key, separator, value = line.partition("=")
    if separator != "=":
        stop("invalid baseline")
    baseline_values[key] = value
baseline_sha = baseline_values.get("baseline_sha", "")
if validated_baseline.stdout.rstrip("\n") != baseline_sha:
    stop("baseline parser returned a different SHA")

commits: set[str] = set()
for row in ledger:
    todo = int(row["todo"])
    if todo < 30 and row["branch"] != contract[todo]["branch"]:
        stop(f"Todo {todo}: wrong branch")
    if row["status"] not in {"complete", "blocked"}:
        stop("unknown terminal status")
    if row["status"] == "blocked":
        if not row["blocker"]:
            stop("blocked row requires blocker")
        release_ready = False
        continue
    if row["blocker"]:
        stop("complete row cannot carry blocker")
    if todo == 30:
        if any(row[field] for field in ("base_sha", "commit_sha", "commit_subject")):
            stop("Todo 30 cannot have commit fields")
        continue
    expected = contract[todo]
    if not row["acceptance_command"] or not row["commit_sha"] or not row["commit_subject"] or not row["base_sha"]:
        stop(f"Todo {todo}: complete row has a blank required field")
    start = start_by_branch.get(row["branch"])
    merge = merge_by_branch.get(row["branch"])
    if start is None or start["start_condition"] != expected["start_condition"] or row["base_sha"] != start["base_sha"]:
        stop(f"Todo {todo}: wrong branch start")
    if merge is None:
        stop(f"Todo {todo}: missing merge record")
    commit = row["commit_sha"]
    if not commit or commit in commits:
        stop(f"Todo {todo}: blank or duplicate commit")
    commits.add(commit)
    subject = git("show", "-s", "--format=%s", commit)
    if subject != row["commit_subject"]:
        stop(f"Todo {todo}: commit subject mismatch")
    parents = git("rev-list", "--parents", "-n", "1", commit).split()
    if len(parents) != 2:
        stop(f"Todo {todo}: source commit must have one parent")
    parent = parents[1]
    changed = git("diff", "--name-only", parent, commit).splitlines()
    allowed = compact_json(expected["ordinary_pathspecs_json"], list)
    selected_paths = [item.split("::", 1)[0] for item in compact_json(expected["document_selectors_json"], list)]
    route_paths = [item["path"] for item in compact_json(expected["route_registration_hunks_json"], list)]
    if any(not path_allowed(expected, path) for path in changed):
        stop(f"Todo {todo}: changed path outside contract")
    for selector in compact_json(expected["document_selectors_json"], list):
        if selector.split("::", 1)[0] in changed:
            verify_selected_hunk(parent, commit, selector)
    for route in compact_json(expected["route_registration_hunks_json"], list):
        if route["path"] in changed:
            verify_route_hunk(parent, commit, route)
    if todo == 16:
        document = "docs/plan/IMPLEMENTATION_PLAN.md"
        before = git("show", f"{parent}:{document}")
        after = git("show", f"{commit}:{document}")
        marker_start = "<!-- NARAE_TODO19_BACKGROUND_ADOPTION_START -->"
        marker_end = "<!-- NARAE_TODO19_BACKGROUND_ADOPTION_END -->"
        if any(before.count(marker) != 0 or after.count(marker) != 1 for marker in (marker_start, marker_end)) or after.index(marker_start) >= after.index(marker_end):
            stop("Todo 16 must create the exact ordered Todo 19 marker pair")
    for ancestor, descendant in ((row["base_sha"], commit), (commit, merge["branch_tip_sha"]), (merge["merge_sha"], arguments.head)):
        if subprocess.run(["git", "merge-base", "--is-ancestor", ancestor, descendant]).returncode != 0:
            stop(f"Todo {todo}: commit reachability failure")

for branch, merge in merge_by_branch.items():
    if branch not in start_by_branch or not any(row["branch"] == branch and row["status"] == "complete" for row in ledger):
        stop(f"{branch}: merge has no matching start or complete ledger row")
    parents = git("rev-list", "--parents", "-n", "1", merge["merge_sha"]).split()
    if parents != [merge["merge_sha"], merge["main_parent_sha"], merge["branch_tip_sha"]]:
        stop(f"{branch}: merge is not the recorded two-parent no-ff merge")
    branch_hash = hashlib.sha256(branch.encode()).hexdigest()
    intent_path = evidence_dir / "attempts" / baseline_values["attempt_id"] / f"merge-intent-branch-{branch_hash}.json"
    if not intent_path.is_file():
        stop(f"{branch}: missing sealed merge intent")
    intent = json.loads(intent_path.read_text(encoding="utf-8"))
    if intent.get("branch") != branch or intent.get("candidate_merge_sha") != merge["merge_sha"] or intent.get("parents") != [merge["main_parent_sha"], merge["branch_tip_sha"]] or intent.get("candidate_tree_sha") != git("show", "-s", "--format=%T", merge["merge_sha"]):
        stop(f"{branch}: merge intent mismatch")
    acceptance = Path(intent.get("acceptance_evidence_path", ""))
    if not acceptance.is_file() or hashlib.sha256(acceptance.read_bytes()).hexdigest() != intent.get("acceptance_evidence_sha256"):
        stop(f"{branch}: merge acceptance evidence mismatch")
    branch_rows = [row for row in ledger if row["branch"] == branch and row["status"] == "complete"]
    ordered = [row["commit_sha"] for row in sorted(branch_rows, key=lambda item: int(contract[int(item["todo"])]["commit_order"]))]
    if git("rev-list", "--reverse", f"{start_by_branch[branch]['base_sha']}..{merge['branch_tip_sha']}").splitlines() != ordered:
        stop(f"{branch}: branch interval contains an unledgered or misordered commit")

if baseline_sha:
    integrated = set(git("rev-list", f"{baseline_sha}..{arguments.head}").splitlines())
    expected_integrated = commits | {row["merge_sha"] for row in merges}
    if integrated != expected_integrated:
        stop("integrated history contains an unrecorded or missing commit")
wave_branches = {"feat/realtime-fullview", "feat/render-deletion", "ci/delivery"}
if wave_branches <= set(merge_by_branch):
    proof = evidence_dir / "attempts" / baseline_values["attempt_id"] / "merge-proof-wave-5a-narae-signing-web-mvp.json"
    if not proof.is_file():
        stop("missing Wave 5A merge proof")
    value = json.loads(proof.read_text(encoding="utf-8"))
    required = {"schema_version", "base_sha", "branch_tips", "temporary_merge_shas", "commands", "results", "ownership_checks"}
    if set(value) != required or set(value["branch_tips"]) != wave_branches:
        stop("invalid Wave 5A merge proof schema")
    if any(value["branch_tips"][branch] != merge_by_branch[branch]["branch_tip_sha"] for branch in wave_branches):
        stop("forged Wave 5A branch tip")
    if any(command.get("exit") != 0 or not re.fullmatch(r"[0-9a-f]{64}", command.get("digest", "")) for command in value["commands"]):
        stop("failed or malformed Wave 5A command")
    if any(not check.get("pass") for check in value["ownership_checks"].values()):
        stop("Wave 5A ownership proof failed")
if arguments.candidate:
    receipt = evidence_dir / "candidates" / arguments.head / "candidate-receipt.json"
    gate = evidence_dir / "candidates" / arguments.head / "candidate-gate.json"
    todo29 = next(row for row in ledger if row["todo"] == "29")
    if todo29["status"] != "complete" or Path(todo29["evidence_path"]).resolve() != receipt.resolve() or not gate.is_file():
        stop("candidate mode requires the sealed Todo 29 receipt and gate")
    gate_value = json.loads(gate.read_text(encoding="utf-8"))
    if gate_value.get("candidate_sha") != arguments.head or gate_value.get("receipt_sha256") != todo29["evidence_sha256"] or gate_value.get("state") != "passed":
        stop("candidate receipt linkage mismatch")
print(f"release_ready={'true' if release_ready else 'false'}")
PY
