#!/usr/bin/env python3
# /// script
# requires-python = ">=3.9"
# dependencies = []
# ///

# ─── How to run ───
# 1. Install uv (if installed Python is older than 3.9):
#      curl -LsSf https://astral.sh/uv/install.sh | sh
# 2. Run directly:
#      uv run scripts/read-plan-baseline.py --primary-root /retained/root --field baseline_sha
# 3. Or make executable and run:
#      chmod +x scripts/read-plan-baseline.py && ./scripts/read-plan-baseline.py --help
# ──────────────────

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import os
import re
import stat
import struct
import sys
from pathlib import Path

FIELDS = ("schema_version", "primary_root", "evidence_dir", "baseline_sha", "attempt_id")
SCHEMA = "narae-signing-baseline-v1"


class BaselineError(RuntimeError):
    pass


def read_regular_at(directory_fd: int, name: str, mode: int | None = 0o600) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(name, flags, dir_fd=directory_fd)
    try:
        details = os.fstat(fd)
        if not stat.S_ISREG(details.st_mode):
            raise BaselineError(f"{name}: expected regular file")
        if mode is not None and stat.S_IMODE(details.st_mode) != mode:
            raise BaselineError(f"{name}: expected mode {mode:04o}")
        chunks: list[bytes] = []
        while chunk := os.read(fd, 65536):
            chunks.append(chunk)
        return b"".join(chunks)
    finally:
        os.close(fd)


def parse_record(raw: bytes, primary_root: Path) -> dict[str, str]:
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError as error:
        raise BaselineError("baseline must be ASCII") from error
    if any(ord(character) < 32 and character not in "\n" for character in text):
        raise BaselineError("baseline contains a control byte")
    lines = text.splitlines()
    if len(lines) != len(FIELDS) or not text.endswith("\n"):
        raise BaselineError("baseline must contain exactly five ordered lines")
    values: dict[str, str] = {}
    for expected, line in zip(FIELDS, lines):
        key, separator, value = line.partition("=")
        if separator != "=" or key != expected or not value:
            raise BaselineError(f"expected field {expected}")
        values[key] = value
    expected_root = str(primary_root)
    if values["schema_version"] != SCHEMA:
        raise BaselineError("unsupported baseline schema")
    if values["primary_root"] != expected_root:
        raise BaselineError("primary_root mismatch")
    if values["evidence_dir"] != str(primary_root / ".omo" / "evidence"):
        raise BaselineError("evidence_dir mismatch")
    if len(values["baseline_sha"]) != 40 or any(c not in "0123456789abcdef" for c in values["baseline_sha"]):
        raise BaselineError("invalid baseline_sha")
    if re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", values["attempt_id"]) is None:
        raise BaselineError("invalid attempt_id")
    return values


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--primary-root", required=True, type=Path)
    parser.add_argument("--field", required=True, choices=FIELDS)
    arguments = parser.parse_args()
    root = arguments.primary_root.resolve(strict=True)
    if arguments.primary_root.absolute() != root:
        raise BaselineError("primary root must be canonical")
    directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    root_fd = os.open(root, directory_flags)
    try:
        omo_fd = os.open(".omo", directory_flags, dir_fd=root_fd)
        evidence_fd = os.open("evidence", directory_flags, dir_fd=omo_fd)
        record = read_regular_at(evidence_fd, "baseline-narae-signing-web-mvp.env")
        sidecar = read_regular_at(evidence_fd, "baseline-narae-signing-web-mvp.env.sha256")
        starts = read_regular_at(evidence_fd, "narae-signing-web-mvp-branch-starts.tsv", None)
    finally:
        for descriptor in (locals().get("evidence_fd"), locals().get("omo_fd"), root_fd):
            if isinstance(descriptor, int):
                os.close(descriptor)
    digest = hashlib.sha256(record).hexdigest()
    expected_sidecar = f"{digest}  baseline-narae-signing-web-mvp.env\n".encode("ascii")
    if sidecar != expected_sidecar:
        raise BaselineError("baseline sidecar digest mismatch")
    values = parse_record(record, root)
    evidence = root / ".omo" / "evidence"
    reader = csv.DictReader(io.StringIO(starts.decode("utf-8")), delimiter="\t")
    expected_header = ["branch", "start_condition", "base_sha", "evidence_path", "evidence_sha256", "row_digest"]
    rows = list(reader)
    if reader.fieldnames != expected_header:
        raise BaselineError("invalid branch-start header")
    foundation_rows = [row for row in rows if row["branch"] == "chore/foundation"]
    if len(foundation_rows) != 1:
        raise BaselineError("foundation bootstrap is not sealed")
    foundation_row = foundation_rows[0]
    foundation = Path(foundation_row["evidence_path"])
    expected_foundation = evidence / "attempts" / values["attempt_id"] / "task-1-narae-signing-web-mvp.md"
    if foundation != expected_foundation or foundation.is_symlink():
        raise BaselineError("invalid foundation evidence path")
    content_bytes = foundation.read_bytes()
    if hashlib.sha256(content_bytes).hexdigest() != foundation_row["evidence_sha256"]:
        raise BaselineError("foundation evidence digest mismatch")
    content = content_bytes.decode("utf-8")
    sidecar_digest = hashlib.sha256(sidecar).hexdigest()
    if digest not in content or sidecar_digest not in content:
        raise BaselineError("foundation evidence does not seal baseline digests")
    payload = bytearray(b"narae-signing-evidence-row-v1\n")
    filename = b"narae-signing-web-mvp-branch-starts.tsv"
    payload.extend(struct.pack(">I", len(filename)))
    payload.extend(filename)
    payload.extend(struct.pack(">I", len(expected_header) - 1))
    for name in expected_header[:-1]:
        for item in (name, foundation_row[name]):
            encoded = item.encode()
            payload.extend(struct.pack(">I", len(encoded)))
            payload.extend(encoded)
    payload.extend(bytes(32))
    if hashlib.sha256(payload).hexdigest() != foundation_row["row_digest"]:
        raise BaselineError("foundation bootstrap row digest mismatch")
    print(values[arguments.field])
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (BaselineError, OSError) as error:
        print(f"baseline error: {error}", file=sys.stderr)
        sys.exit(2)
