#!/usr/bin/env bash
set -euo pipefail
worktree=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
[[ $# == 2 ]] || exit 2
exec /usr/bin/python3 - "$worktree" "$1" "$2" <<'PY'
import json, os, pathlib, plistlib, re, stat, subprocess, sys

worktree, root_arg, receipt_arg = sys.argv[1:]
receipt = pathlib.Path(receipt_arg)
evidence = pathlib.Path(worktree) / ".omo/evidence/board-signature-ink-color"
if not receipt.is_absolute() or receipt.resolve(strict=True) != receipt:
    raise ValueError("synthetic mount receipt path is not canonical")
receipt.relative_to(evidence)
fd = os.open(receipt, os.O_RDONLY | os.O_NOFOLLOW)
try:
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError("synthetic mount receipt is not private and owned")
    value = json.loads(os.read(fd, 65537))
finally:
    os.close(fd)
required = {"schemaVersion", "root", "mountpoint", "mountIdentity", "image", "imageIdentity", "device", "partition"}
if set(value) != required or value["schemaVersion"] != 1 or value["root"] != root_arg:
    raise ValueError("synthetic mount receipt root/schema mismatch")
mountpoint = pathlib.Path(value["mountpoint"])
image = pathlib.Path(value["image"])
root = pathlib.Path(root_arg)
if not re.fullmatch(r"/private/tmp/narae-ink-color-qa\.[0-9a-f]{16}/mount-probe", str(mountpoint)) or root != mountpoint / "recovery-fixture":
    raise ValueError("synthetic mount namespace mismatch")
if mountpoint.resolve(strict=True) != mountpoint or root.resolve(strict=True) != root or image.resolve(strict=True) != image:
    raise ValueError("synthetic mount identity contains a path alias")
image.relative_to(evidence)
if image.name != "probe.dmg" or not re.fullmatch(r"/dev/disk[0-9]+", value["device"]) or not re.fullmatch(re.escape(value["device"]) + r"s[0-9]+", value["partition"]):
    raise ValueError("synthetic mount image/device namespace mismatch")
def identity(path, directory=False):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | (os.O_DIRECTORY if directory else 0))
    try:
        info = os.fstat(fd)
        if info.st_uid != os.getuid() or (not directory and not stat.S_ISREG(info.st_mode)):
            raise ValueError("synthetic mount object owner/type mismatch")
        return [info.st_dev, info.st_ino, info.st_uid, stat.S_IMODE(info.st_mode)]
    finally:
        os.close(fd)
if identity(mountpoint, True) != value["mountIdentity"] or identity(image) != value["imageIdentity"]:
    raise ValueError("synthetic mount descriptor identity mismatch")
root_identity = identity(root, True)
if root_identity[0] != value["mountIdentity"][0] or root_identity[3] != 0o700 or mountpoint.stat().st_dev == mountpoint.parent.stat().st_dev:
    raise ValueError("synthetic mount device boundary missing")
completed = subprocess.run(["/usr/bin/hdiutil", "info", "-plist"], capture_output=True, check=True, timeout=15)
entries = [item for item in plistlib.loads(completed.stdout).get("images", []) if item.get("image-path") == str(image)]
if len(entries) != 1:
    raise ValueError("synthetic mount image association missing/ambiguous")
entities = entries[0].get("system-entities", [])
if not any(item.get("dev-entry") == value["device"] for item in entities) or not any(item.get("dev-entry") == value["partition"] and item.get("mount-point") == str(mountpoint) for item in entities):
    raise ValueError("synthetic mount device/mountpoint association mismatch")
print(json.dumps(value, sort_keys=True))
PY
