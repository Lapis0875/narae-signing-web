#!/bin/sh
# allow: SIZE_OK — one disposable provenance matrix with shared teardown
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture=$(mktemp -d "${TMPDIR:-/tmp}/narae-plan-evidence.XXXXXX")
trap 'rm -rf "$fixture"' EXIT HUP INT TERM
evidence="$fixture/evidence"
mkdir -p "$evidence/attempts/test-attempt"
task_evidence="$evidence/attempts/test-attempt/task-1.md"
printf '%s\n' 'synthetic foundation evidence' > "$task_evidence"
fresh="$fixture/fresh-evidence"
mkdir -p "$fresh/attempts/test-attempt"
printf '%s\n' premature > "$fresh/attempts/test-attempt/premature.md"
if NARAE_EVIDENCE_DIR="$fresh" "$repo_root/scripts/record-plan-evidence.sh" write-start --branch feat/security-auth --start-condition 'chore/foundation merged' --base-sha dc55e7a1f8916dd95575667324f6cb04be547cc1 --evidence-path "$fresh/attempts/test-attempt/premature.md" >/dev/null 2>&1; then
  echo "fresh non-foundation start unexpectedly accepted" >&2
  exit 1
fi
if [ -e "$fresh/narae-signing-web-mvp-branch-starts.tsv" ]; then
  echo "rejected fresh start mutated the branch-start TSV" >&2
  exit 1
fi

digest=$(NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-start \
  --branch chore/foundation \
  --start-condition 'baseline commit exists' \
  --base-sha dc55e7a1f8916dd95575667324f6cb04be547cc1 \
  --evidence-path "$task_evidence")

python3 - "$evidence/narae-signing-web-mvp-branch-starts.tsv" "$digest" <<'PY'
import csv
import hashlib
import struct
import sys

path, reported = sys.argv[1:]
with open(path, newline="", encoding="utf-8") as source:
    reader = csv.DictReader(source, delimiter="\t")
    row = next(reader)
headers = reader.fieldnames[:-1]
name = path.rsplit("/", 1)[-1].encode()
payload = bytearray(b"narae-signing-evidence-row-v1\n")
payload.extend(struct.pack(">I", len(name)))
payload.extend(name)
payload.extend(struct.pack(">I", len(headers)))
for header in headers:
    for value in (header, row[header]):
        encoded = value.encode()
        payload.extend(struct.pack(">I", len(encoded)))
        payload.extend(encoded)
payload.extend(bytes(32))
expected = hashlib.sha256(payload).hexdigest()
assert expected == reported == row["row_digest"]
assert hashlib.sha256(payload[:-32] + bytes.fromhex("01" * 32)).hexdigest() != expected
assert hashlib.sha256(payload.replace(struct.pack(">I", len(name)), struct.pack("<I", len(name)), 1)).hexdigest() != expected
mutated = dict(row)
mutated["base_sha"] = "0" * 40
assert mutated["base_sha"] != row["base_sha"]
PY

second_digest=$(NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-start \
  --branch chore/foundation --start-condition 'baseline commit exists' \
  --base-sha dc55e7a1f8916dd95575667324f6cb04be547cc1 --evidence-path "$task_evidence")
if [ "$second_digest" != "$digest" ] || [ "$(wc -l < "$evidence/narae-signing-web-mvp-branch-starts.tsv")" -ne 2 ]; then
  echo "idempotent foundation retry changed the row" >&2
  exit 1
fi
if NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-start \
  --branch chore/foundation --start-condition wrong \
  --base-sha dc55e7a1f8916dd95575667324f6cb04be547cc1 --evidence-path "$task_evidence" >/dev/null 2>&1; then
  echo "conflicting foundation start unexpectedly accepted" >&2
  exit 1
fi
if NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-start \
  --branch feat/security-auth --start-condition 'chore/foundation merged' \
  --base-sha dc55e7a1f8916dd95575667324f6cb04be547cc1 --evidence-path relative.md >/dev/null 2>&1; then
  echo "relative evidence unexpectedly accepted" >&2
  exit 1
fi

printf '%s\n' '{"fixture":true}' > "$fixture/input.json"
if NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-json \
  --input "$fixture/input.json" --output "$evidence/direct.tsv" >/dev/null 2>&1; then
  echo "direct TSV JSON write unexpectedly accepted" >&2
  exit 1
fi
if NARAE_EVIDENCE_DIR="$evidence" NARAE_TEST_FAILPOINT=file_fsync "$repo_root/scripts/record-plan-evidence.sh" write-json \
  --input "$fixture/input.json" --output "$evidence/fsync.json" >/dev/null 2>&1; then
  echo "injected fsync failure unexpectedly succeeded" >&2
  exit 1
fi

printf '%s\n' first > "$evidence/attempts/test-attempt/first.md"
printf '%s\n' second > "$evidence/attempts/test-attempt/second.md"
for premature in \
  "feat/security-auth|chore/foundation merged|$evidence/attempts/test-attempt/first.md" \
  "feat/board-domain|feat/security-auth merged|$evidence/attempts/test-attempt/second.md"; do
  branch=${premature%%|*}
  remainder=${premature#*|}
  condition=${remainder%%|*}
  path=${remainder#*|}
  if NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-start --branch "$branch" --start-condition "$condition" --base-sha dc55e7a1f8916dd95575667324f6cb04be547cc1 --evidence-path "$path" >/dev/null 2>&1; then
    echo "premature non-foundation start unexpectedly accepted" >&2
    exit 1
  fi
done
if [ "$(wc -l < "$evidence/narae-signing-web-mvp-branch-starts.tsv")" -ne 2 ]; then
  echo "premature start mutated the TSV" >&2
  exit 1
fi
NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-json --input "$fixture/input.json" --output "$evidence/concurrent.json" > "$fixture/concurrent-1" &
first_pid=$!
NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-json --input "$fixture/input.json" --output "$evidence/concurrent.json" > "$fixture/concurrent-2" &
second_pid=$!
wait "$first_pid"
wait "$second_pid"
cmp "$fixture/concurrent-1" "$fixture/concurrent-2"
if NARAE_EVIDENCE_DIR="$evidence" NARAE_TEST_FAILPOINT=after_rename_before_directory_fsync "$repo_root/scripts/record-plan-evidence.sh" write-json --input "$fixture/input.json" --output "$evidence/recovery.json" >/dev/null 2>&1; then
  echo "post-rename failpoint unexpectedly succeeded" >&2
  exit 1
fi
NARAE_EVIDENCE_DIR="$evidence" "$repo_root/scripts/record-plan-evidence.sh" write-json --input "$fixture/input.json" --output "$evidence/recovery.json" >/dev/null
cmp "$evidence/concurrent.json" "$evidence/recovery.json"

python3 - "$repo_root/scripts/plan-evidence-contract.tsv" <<'PY'
import csv
import json
import sys

with open(sys.argv[1], newline="", encoding="utf-8") as source:
    rows = list(csv.DictReader(source, delimiter="\t"))
assert len(rows) == 29
assert [int(row["todo"]) for row in rows] == list(range(1, 30))
for row in rows:
    for name in ("ordinary_pathspecs_json", "document_selectors_json", "route_registration_hunks_json"):
        parsed = json.loads(row[name])
        assert json.dumps(parsed, ensure_ascii=False, separators=(",", ":")) == row[name]
orders = {int(row["todo"]): int(row["commit_order"]) for row in rows}
assert orders[7] < orders[9] < orders[8] < orders[10]
assert "<!-- NARAE_TODO19_BACKGROUND_ADOPTION_START -->" in rows[18]["document_selectors_json"]
assert "### 5.4 서명 획 처리" in rows[20]["document_selectors_json"]
assert sum(bool(json.loads(row["route_registration_hunks_json"])) for row in rows) == 4
PY
for pair in '1 README.md' '14 backend/build.gradle.kts' '5 frontend/package.json' '19 frontend/src/features/boards/editor/BackgroundPanel.test.tsx' '29 README.md'; do
  set -- $pair
  "$repo_root/scripts/verify-plan-evidence.sh" --probe-path "$1" "$2" | grep -q 'path_valid=true'
done
if "$repo_root/scripts/verify-plan-evidence.sh" --probe-path 1 docs/plan/IMPLEMENTATION_PLAN.md >/dev/null 2>&1; then
  echo "ordinary path violation unexpectedly accepted" >&2
  exit 1
fi

python3 - "$repo_root/scripts/read-plan-baseline.py" "$fixture" <<'PY'
import csv
import hashlib
import os
import shutil
import struct
import subprocess
import sys
from pathlib import Path

reader = Path(sys.argv[1])
fixture = Path(sys.argv[2]).resolve()
root = fixture / "retained"
evidence = root / ".omo" / "evidence"
attempt = "01a01d9e-019c-7079-9656-0a30779cff79"
task = evidence / "attempts" / attempt / "task-1-narae-signing-web-mvp.md"
task.parent.mkdir(parents=True)
baseline_sha = "d" * 40
record = f"schema_version=narae-signing-baseline-v1\nprimary_root={root}\nevidence_dir={evidence}\nbaseline_sha={baseline_sha}\nattempt_id={attempt}\n".encode()
sidecar = f"{hashlib.sha256(record).hexdigest()}  baseline-narae-signing-web-mvp.env\n".encode()
task.write_text(f"{hashlib.sha256(record).hexdigest()}\n{hashlib.sha256(sidecar).hexdigest()}\n")
task_digest = hashlib.sha256(task.read_bytes()).hexdigest()
headers = ["branch", "start_condition", "base_sha", "evidence_path", "evidence_sha256", "row_digest"]
row = ["chore/foundation", "baseline commit exists", baseline_sha, str(task), task_digest]
payload = bytearray(b"narae-signing-evidence-row-v1\n")
name = b"narae-signing-web-mvp-branch-starts.tsv"
payload.extend(struct.pack(">I", len(name)) + name + struct.pack(">I", 5))
for key, value in zip(headers[:-1], row):
    for item in (key, value):
        encoded = item.encode()
        payload.extend(struct.pack(">I", len(encoded)) + encoded)
payload.extend(bytes(32))
row.append(hashlib.sha256(payload).hexdigest())
(evidence / "baseline-narae-signing-web-mvp.env").write_bytes(record)
(evidence / "baseline-narae-signing-web-mvp.env.sha256").write_bytes(sidecar)
(evidence / "narae-signing-web-mvp-branch-starts.tsv").write_text("\t".join(headers) + "\n" + "\t".join(row) + "\n")
os.chmod(evidence / "baseline-narae-signing-web-mvp.env", 0o600)
os.chmod(evidence / "baseline-narae-signing-web-mvp.env.sha256", 0o600)
valid = subprocess.run([reader, "--primary-root", root, "--field", "baseline_sha"], capture_output=True, text=True)
assert valid.returncode == 0, valid.stderr
assert valid.stdout == baseline_sha + "\n"

def rejected(name, mutate):
    target = fixture / name
    shutil.copytree(root, target)
    mutate(target)
    assert subprocess.run([reader, "--primary-root", target, "--field", "baseline_sha"], capture_output=True).returncode != 0

rejected("bad-sidecar", lambda path: (path / ".omo/evidence/baseline-narae-signing-web-mvp.env.sha256").write_text("0" * 64 + "  baseline-narae-signing-web-mvp.env\n"))
rejected("bad-mode", lambda path: os.chmod(path / ".omo/evidence/baseline-narae-signing-web-mvp.env", 0o644))
rejected("bad-order", lambda path: (path / ".omo/evidence/baseline-narae-signing-web-mvp.env").write_bytes(record.replace(b"schema_version", b"primary_root", 1)))
rejected("bad-root", lambda path: None)
link_root = fixture / "link-root"
shutil.copytree(root, link_root)
record_path = link_root / ".omo/evidence/baseline-narae-signing-web-mvp.env"
record_path.unlink()
record_path.symlink_to(root / ".omo/evidence/baseline-narae-signing-web-mvp.env")
assert subprocess.run([reader, "--primary-root", link_root, "--field", "baseline_sha"], capture_output=True).returncode != 0
PY

history="$fixture/history"
retained="$fixture/history-retained"
history_attempt=01a01d9e-019c-7079-9656-0a30779cff79
mkdir -p "$history" "$retained/.omo/evidence/attempts/$history_attempt"
git -C "$history" init -q -b main
printf '%s\n' baseline > "$history/BASELINE"
git -C "$history" add BASELINE
git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm baseline
baseline_commit=$(git -C "$history" rev-parse HEAD)
git -C "$history" switch -qc chore/foundation
printf '%s\n' '# Fixture' > "$history/README.md"
mkdir -p "$history/scripts"
cp "$repo_root/scripts/read-plan-baseline.py" "$history/scripts/read-plan-baseline.py"
chmod +x "$history/scripts/read-plan-baseline.py"
git -C "$history" add README.md scripts/read-plan-baseline.py
git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm 'chore(bootstrap): initialize frontend and backend toolchains'
source_commit=$(git -C "$history" rev-parse HEAD)
mkdir -p "$history/backend" "$history/infra" "$history/frontend"
printf '%s\n' todo2 > "$history/backend/todo2"
git -C "$history" add backend/todo2
git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm 'feat(platform): add backend health and API error contract'
commit2=$(git -C "$history" rev-parse HEAD)
printf '%s\n' todo3 > "$history/backend/todo3"
git -C "$history" add backend/todo3
git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm 'feat(data): add Flyway-owned schema and integration harness'
commit3=$(git -C "$history" rev-parse HEAD)
printf '%s\n' todo4 > "$history/infra/todo4"
git -C "$history" add infra/todo4
git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm 'feat(infra): add private compose topology and frontend proxy'
commit4=$(git -C "$history" rev-parse HEAD)
printf '%s\n' todo5 > "$history/frontend/todo5"
git -C "$history" add frontend/todo5
git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm 'feat(frontend): add routes query client and shared states'
commit5=$(git -C "$history" rev-parse HEAD)
printf '%s\n' '## Verification' >> "$history/README.md"
git -C "$history" add README.md
git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm 'ci: verify frontend backend migrations and compose'
commit6=$(git -C "$history" rev-parse HEAD)
foundation_tip=$commit6
git -C "$history" switch -q main
git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid merge -q --no-ff chore/foundation -m 'merge foundation'
merge_commit=$(git -C "$history" rev-parse HEAD)
merge_tree=$(git -C "$history" show -s --format=%T HEAD)
retained=$(realpath "$retained")
retained_evidence="$retained/.omo/evidence"
record="$retained_evidence/baseline-narae-signing-web-mvp.env"
sidecar="$record.sha256"
printf 'schema_version=narae-signing-baseline-v1\nprimary_root=%s\nevidence_dir=%s\nbaseline_sha=%s\nattempt_id=%s\n' "$retained" "$retained_evidence" "$baseline_commit" "$history_attempt" > "$record"
record_sha=$(shasum -a 256 "$record" | awk '{print $1}')
printf '%s  baseline-narae-signing-web-mvp.env\n' "$record_sha" > "$sidecar"
chmod 600 "$record" "$sidecar"
history_task="$retained_evidence/attempts/$history_attempt/task-1-narae-signing-web-mvp.md"
sidecar_sha=$(shasum -a 256 "$sidecar" | awk '{print $1}')
printf '%s\n%s\n' "$record_sha" "$sidecar_sha" > "$history_task"
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-start --branch chore/foundation --start-condition 'baseline commit exists' --base-sha "$baseline_commit" --evidence-path "$history_task" >/dev/null)
if git -C "$history" cat-file -e "$baseline_commit:scripts/read-plan-baseline.py" 2>/dev/null; then
  echo "baseline unexpectedly contains the reader" >&2
  exit 1
fi
git -C "$history" show "$source_commit:scripts/read-plan-baseline.py" > "$fixture/current-reader.py"
chmod +x "$fixture/current-reader.py"
"$fixture/current-reader.py" --primary-root "$retained" --field baseline_sha | grep -qx "$baseline_commit"
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-terminal --todo 1 --branch chore/foundation --base-sha "$baseline_commit" --commit-sha "$source_commit" --commit-subject 'chore(bootstrap): initialize frontend and backend toolchains' --acceptance-command fixture --evidence-path "$history_task" --status complete --blocker '' >/dev/null)
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-terminal --todo 2 --branch chore/foundation --base-sha "$baseline_commit" --commit-sha "$commit2" --commit-subject 'feat(platform): add backend health and API error contract' --acceptance-command fixture --evidence-path "$history_task" --status complete --blocker '' >/dev/null)
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-terminal --todo 3 --branch chore/foundation --base-sha "$baseline_commit" --commit-sha "$commit3" --commit-subject 'feat(data): add Flyway-owned schema and integration harness' --acceptance-command fixture --evidence-path "$history_task" --status complete --blocker '' >/dev/null)
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-terminal --todo 4 --branch chore/foundation --base-sha "$baseline_commit" --commit-sha "$commit4" --commit-subject 'feat(infra): add private compose topology and frontend proxy' --acceptance-command fixture --evidence-path "$history_task" --status complete --blocker '' >/dev/null)
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-terminal --todo 5 --branch chore/foundation --base-sha "$baseline_commit" --commit-sha "$commit5" --commit-subject 'feat(frontend): add routes query client and shared states' --acceptance-command fixture --evidence-path "$history_task" --status complete --blocker '' >/dev/null)
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-terminal --todo 6 --branch chore/foundation --base-sha "$baseline_commit" --commit-sha "$commit6" --commit-subject 'ci: verify frontend backend migrations and compose' --acceptance-command fixture --evidence-path "$history_task" --status complete --blocker '' >/dev/null)
todo=7
while [ "$todo" -le 30 ]; do
  if [ "$todo" -lt 30 ]; then
    branch=$(awk -F '\t' -v todo="$todo" 'NR > 1 && $1 == todo { print $2 }' "$repo_root/scripts/plan-evidence-contract.tsv")
  else
    branch=external/tablet-release-gate
  fi
  (cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-terminal --todo "$todo" --branch "$branch" --base-sha '' --commit-sha '' --commit-subject '' --acceptance-command '' --evidence-path "$history_task" --status blocked --blocker 'synthetic fixture blocker' >/dev/null)
  todo=$((todo + 1))
done
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" NARAE_ATTEMPT_ID="$history_attempt" "$repo_root/scripts/record-plan-evidence.sh" write-merge-intent --branch chore/foundation --branch-tip-sha "$foundation_tip" --main-parent-sha "$baseline_commit" --candidate-merge-sha "$merge_commit" --candidate-tree-sha "$merge_tree" --parents-json "[\"$baseline_commit\",\"$foundation_tip\"]" --acceptance-commands-json '[{"id":"fixture","digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","exit":0}]' --acceptance-evidence "$history_task" >/dev/null)
branch_hash=$(printf %s chore/foundation | shasum -a 256 | awk '{print $1}')
intent="$retained_evidence/attempts/$history_attempt/merge-intent-branch-$branch_hash.json"
test -s "$intent"
if (cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" NARAE_ATTEMPT_ID="$history_attempt" "$repo_root/scripts/record-plan-evidence.sh" write-merge-intent --branch '../unsafe' --branch-tip-sha "$foundation_tip" --main-parent-sha "$baseline_commit" --candidate-merge-sha "$merge_commit" --candidate-tree-sha "$merge_tree" --parents-json "[\"$baseline_commit\",\"$foundation_tip\"]" --acceptance-commands-json '[{"id":"fixture","digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","exit":0}]' --acceptance-evidence "$history_task" >/dev/null 2>&1); then
  echo "unsafe branch encoding unexpectedly accepted" >&2
  exit 1
fi
git -C "$history" switch -q --detach "$baseline_commit"
if (cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" NARAE_ATTEMPT_ID="$history_attempt" "$repo_root/scripts/record-plan-evidence.sh" write-merge --branch chore/foundation --branch-tip-sha "$foundation_tip" --main-parent-sha "$baseline_commit" --merge-sha "$merge_commit" --evidence-path "$history_task" >/dev/null 2>&1); then
  echo "pre-CAS merge publication unexpectedly accepted" >&2
  exit 1
fi
git -C "$history" switch -q --detach "$merge_commit"
merge_digest=$(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" NARAE_ATTEMPT_ID="$history_attempt" "$repo_root/scripts/record-plan-evidence.sh" write-merge --branch chore/foundation --branch-tip-sha "$foundation_tip" --main-parent-sha "$baseline_commit" --merge-sha "$merge_commit" --evidence-path "$history_task")
resumed_digest=$(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" NARAE_ATTEMPT_ID="$history_attempt" "$repo_root/scripts/record-plan-evidence.sh" write-merge --branch chore/foundation --branch-tip-sha "$foundation_tip" --main-parent-sha "$baseline_commit" --merge-sha "$merge_commit" --evidence-path "$history_task")
if [ "$merge_digest" != "$resumed_digest" ] || [ "$(wc -l < "$retained_evidence/narae-signing-web-mvp-branch-merges.tsv")" -ne 2 ]; then
  echo "post-CAS merge resume was not idempotent" >&2
  exit 1
fi
(cd "$history" && NARAE_EVIDENCE_DIR="$retained_evidence" "$repo_root/scripts/record-plan-evidence.sh" write-start --branch feat/security-auth --start-condition 'chore/foundation merged' --base-sha "$merge_commit" --evidence-path "$history_task" >/dev/null)
if [ "$(wc -l < "$retained_evidence/narae-signing-web-mvp-branch-starts.tsv")" -ne 3 ]; then
  echo "post-prerequisite security start was not recorded exactly once" >&2
  exit 1
fi
printf '%s\n' fixture > "$history/plan.md"
(cd "$history" && "$repo_root/scripts/verify-plan-evidence.sh" --plan plan.md --ledger "$retained_evidence/narae-signing-web-mvp-ledger.tsv" --branch-starts "$retained_evidence/narae-signing-web-mvp-branch-starts.tsv" --branch-merges "$retained_evidence/narae-signing-web-mvp-branch-merges.tsv" --baseline "$record" --head "$merge_commit") | grep -q 'release_ready=false'
mv "$intent" "$intent.saved"
if (cd "$history" && "$repo_root/scripts/verify-plan-evidence.sh" --plan plan.md --ledger "$retained_evidence/narae-signing-web-mvp-ledger.tsv" --branch-starts "$retained_evidence/narae-signing-web-mvp-branch-starts.tsv" --branch-merges "$retained_evidence/narae-signing-web-mvp-branch-merges.tsv" --baseline "$record" --head "$merge_commit" >/dev/null 2>&1); then
  echo "missing merge intent unexpectedly accepted" >&2
  exit 1
fi
mv "$intent.saved" "$intent"
if (cd "$history" && "$repo_root/scripts/verify-plan-evidence.sh" --candidate --plan plan.md --ledger "$retained_evidence/narae-signing-web-mvp-ledger.tsv" --branch-starts "$retained_evidence/narae-signing-web-mvp-branch-starts.tsv" --branch-merges "$retained_evidence/narae-signing-web-mvp-branch-merges.tsv" --baseline "$record" --head "$merge_commit" >/dev/null 2>&1); then
  echo "candidate mode accepted incomplete Todos 1-29" >&2
  exit 1
fi

source_tree=$(git -C "$history" show -s --format=%T "$source_commit")
unrelated_commit=$(printf '%s\n' 'chore(bootstrap): initialize frontend and backend toolchains' | git -C "$history" -c user.name=Other -c user.email=other@example.invalid commit-tree "$source_tree" -p "$baseline_commit")
direct_commit=$(printf '%s\n' direct | git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit-tree "$merge_tree" -p "$merge_commit")
unrecorded_merge=$(printf '%s\n' merge | git -C "$history" -c user.name=Fixture -c user.email=fixture@example.invalid commit-tree "$merge_tree" -p "$merge_commit" -p "$source_commit")
for bad_head in "$direct_commit" "$unrecorded_merge"; do
  if (cd "$history" && "$repo_root/scripts/verify-plan-evidence.sh" --plan plan.md --ledger "$retained_evidence/narae-signing-web-mvp-ledger.tsv" --branch-starts "$retained_evidence/narae-signing-web-mvp-branch-starts.tsv" --branch-merges "$retained_evidence/narae-signing-web-mvp-branch-merges.tsv" --baseline "$record" --head "$bad_head" >/dev/null 2>&1); then
    echo "unrecorded integrated commit unexpectedly accepted" >&2
    exit 1
  fi
done

python3 - "$retained_evidence/narae-signing-web-mvp-ledger.tsv" "$history" "$repo_root/scripts/verify-plan-evidence.sh" "$retained_evidence" "$record" "$merge_commit" "$unrelated_commit" <<'PY'
import csv
import hashlib
import io
import struct
import subprocess
import sys
from pathlib import Path

ledger, history, verifier, evidence, baseline, head, unrelated = sys.argv[1:]
path = Path(ledger)
original = path.read_text()

def reject(change):
    reader = csv.DictReader(io.StringIO(original), delimiter="\t")
    rows = list(reader)
    change(rows)
    prior = bytes(32)
    headers = reader.fieldnames
    for row in rows:
        payload = bytearray(b"narae-signing-evidence-row-v1\n")
        name = path.name.encode()
        payload.extend(struct.pack(">I", len(name)) + name + struct.pack(">I", len(headers) - 1))
        for key in headers[:-1]:
            for item in (key, row[key]):
                encoded = item.encode()
                payload.extend(struct.pack(">I", len(encoded)) + encoded)
        payload.extend(prior)
        row["row_digest"] = hashlib.sha256(payload).hexdigest()
        prior = bytes.fromhex(row["row_digest"])
    with path.open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=headers, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    result = subprocess.run([verifier, "--plan", "plan.md", "--ledger", ledger, "--branch-starts", f"{evidence}/narae-signing-web-mvp-branch-starts.tsv", "--branch-merges", f"{evidence}/narae-signing-web-mvp-branch-merges.tsv", "--baseline", baseline, "--head", head], cwd=history, capture_output=True)
    assert result.returncode != 0
    path.write_text(original)

reject(lambda rows: rows[0].update(branch="wrong/branch"))
reject(lambda rows: rows[0].update(base_sha="0" * 40))
reject(lambda rows: rows[0].update(commit_sha=unrelated))
reject(lambda rows: rows[0].update(commit_subject="same-looking but wrong"))
reject(lambda rows: rows[6].update(base_sha=head, commit_sha=rows[0]["commit_sha"], commit_subject=rows[0]["commit_subject"], acceptance_command="fixture", status="complete", blocker=""))
PY

python3 - "$retained_evidence/narae-signing-web-mvp-ledger.tsv" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
original = path.read_bytes()
path.write_bytes(original.replace(b"chore/foundation", b"wrong/foundation", 1))
PY
if (cd "$history" && "$repo_root/scripts/verify-plan-evidence.sh" --plan plan.md --ledger "$retained_evidence/narae-signing-web-mvp-ledger.tsv" --branch-starts "$retained_evidence/narae-signing-web-mvp-branch-starts.tsv" --branch-merges "$retained_evidence/narae-signing-web-mvp-branch-merges.tsv" --baseline "$record" --head "$merge_commit" >/dev/null 2>&1); then
  echo "mutated ledger field unexpectedly accepted" >&2
  exit 1
fi

git_fixture="$fixture/git"
git init -q "$git_fixture"
printf '%s%s\n' 'sample=AKIA12345678' '90ABCDEF' > "$git_fixture/credential.txt"
git -C "$git_fixture" add credential.txt
git -C "$git_fixture" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm fixture
if (cd "$git_fixture" && "$repo_root/scripts/scan-tracked-secrets.sh" --commit HEAD >/dev/null 2>&1); then
  echo "synthetic secret unexpectedly accepted" >&2
  exit 1
fi

hunks="$fixture/hunks"
git init -q -b main "$hunks"
mkdir -p "$hunks/frontend/src/routes" "$hunks/docs/plan"
printf '%s\n' '## Root' 'outside' '### Target' 'inside' '## Next' 'outside' > "$hunks/docs/plan/doc.md"
printf '%s\n' '### 7.3 관리자 보드 API' '<!-- NARAE_TODO19_BACKGROUND_ADOPTION_START -->' 'inside' '<!-- NARAE_TODO19_BACKGROUND_ADOPTION_END -->' '### 7.4 공개 서명 API' > "$hunks/docs/plan/IMPLEMENTATION_PLAN.md"
printf '%s\n' '// TODO11_AUTH_ROUTES_START' 'auth' '// TODO11_AUTH_ROUTES_END' '// TODO17_ADMIN_BOARD_ROUTES_START' 'admin' '// TODO17_ADMIN_BOARD_ROUTES_END' '// TODO23_FULL_VIEW_ROUTE_START' 'full' '// TODO23_FULL_VIEW_ROUTE_END' '// TODO24_PUBLIC_SIGN_ROUTE_START' 'sign' '// TODO24_PUBLIC_SIGN_ROUTE_END' > "$hunks/frontend/src/routes/AppRouter.tsx"
git -C "$hunks" add .
git -C "$hunks" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm baseline
hunk_base=$(git -C "$hunks" rev-parse HEAD)
perl -0pi -e 's/\ninside\n## Next/\nchanged inside\n## Next/' "$hunks/docs/plan/doc.md"
perl -0pi -e 's/\ninside\n<!-- NARAE/\nchanged inside\n<!-- NARAE/' "$hunks/docs/plan/IMPLEMENTATION_PLAN.md"
perl -0pi -e 's/\nauth\n/\nchanged auth\n/' "$hunks/frontend/src/routes/AppRouter.tsx"
git -C "$hunks" add .
git -C "$hunks" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm good
hunk_good=$(git -C "$hunks" rev-parse HEAD)
(cd "$hunks" && "$repo_root/scripts/verify-plan-evidence.sh" --probe-hunk "$hunk_base" "$hunk_good" 'docs/plan/doc.md::### Target' null) | grep -q 'hunk_valid=true'
(cd "$hunks" && "$repo_root/scripts/verify-plan-evidence.sh" --probe-hunk "$hunk_base" "$hunk_good" 'docs/plan/IMPLEMENTATION_PLAN.md::<!-- NARAE_TODO19_BACKGROUND_ADOPTION_START -->..<!-- NARAE_TODO19_BACKGROUND_ADOPTION_END -->' null) | grep -q 'hunk_valid=true'
(cd "$hunks" && "$repo_root/scripts/verify-plan-evidence.sh" --probe-hunk "$hunk_base" "$hunk_good" ignored '{"path":"frontend/src/routes/AppRouter.tsx","start_marker":"// TODO11_AUTH_ROUTES_START","end_marker":"// TODO11_AUTH_ROUTES_END"}') | grep -q 'hunk_valid=true'

git -C "$hunks" switch -q --detach "$hunk_base"
perl -0pi -e 's/\noutside\n### Target/\nescaped\n### Target/' "$hunks/docs/plan/doc.md"
perl -0pi -e 's/TODO19_BACKGROUND_ADOPTION_START/TODO19_BACKGROUND_ADOPTION_CHANGED/' "$hunks/docs/plan/IMPLEMENTATION_PLAN.md"
perl -0pi -e 's/\nauth\n/\nchanged auth\n/' "$hunks/frontend/src/routes/AppRouter.tsx"
git -C "$hunks" add .
git -C "$hunks" -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm bad
hunk_bad=$(git -C "$hunks" rev-parse HEAD)
if (cd "$hunks" && "$repo_root/scripts/verify-plan-evidence.sh" --probe-hunk "$hunk_base" "$hunk_bad" 'docs/plan/doc.md::### Target' null >/dev/null 2>&1); then
  echo "heading escape unexpectedly accepted" >&2
  exit 1
fi
if (cd "$hunks" && "$repo_root/scripts/verify-plan-evidence.sh" --probe-hunk "$hunk_base" "$hunk_bad" 'docs/plan/IMPLEMENTATION_PLAN.md::<!-- NARAE_TODO19_BACKGROUND_ADOPTION_START -->..<!-- NARAE_TODO19_BACKGROUND_ADOPTION_END -->' null >/dev/null 2>&1); then
  echo "marker boundary change unexpectedly accepted" >&2
  exit 1
fi
for route in \
  '{"path":"frontend/src/routes/AppRouter.tsx","start_marker":"// TODO17_ADMIN_BOARD_ROUTES_START","end_marker":"// TODO17_ADMIN_BOARD_ROUTES_END"}' \
  '{"path":"frontend/src/routes/AppRouter.tsx","start_marker":"// TODO23_FULL_VIEW_ROUTE_START","end_marker":"// TODO23_FULL_VIEW_ROUTE_END"}' \
  '{"path":"frontend/src/routes/AppRouter.tsx","start_marker":"// TODO24_PUBLIC_SIGN_ROUTE_START","end_marker":"// TODO24_PUBLIC_SIGN_ROUTE_END"}'; do
  if (cd "$hunks" && "$repo_root/scripts/verify-plan-evidence.sh" --probe-hunk "$hunk_base" "$hunk_bad" ignored "$route" >/dev/null 2>&1); then
    echo "adjacent route-hunk edit unexpectedly accepted" >&2
    exit 1
  fi
done

echo "plan evidence fixtures: pass"
