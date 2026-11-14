"""Connector package archives (contracts/connector-package-v1.schema.json, docs/build/S133 section 3).

A source folder (manifest.json, connections/, fixtures/, ...) is packed the way the server reads it: every file with
LF line endings, checksums.sha256 listing each file, a ZIP with fixed entry times so the same files give the same
bytes. The archive is unsigned; a contract test does not need a trusted signature (signing stays the publisher's).
"""
from __future__ import annotations

import hashlib
import io
import json
import zipfile
from pathlib import Path
from typing import Dict, List

FOLDERS = ("connections", "triggers", "templates", "fixtures", "docs")
ENTRY_TIME = (2026, 1, 1, 0, 0, 0)


def sources(folder: Path) -> Dict[str, bytes]:
    files: Dict[str, bytes] = {}
    for path in sorted(folder.rglob("*")):
        if not path.is_file():
            continue
        name = path.relative_to(folder).as_posix()
        if name in ("checksums.sha256", "signature.jws") or name.startswith("."):
            continue
        if name != "manifest.json" and name.split("/", 1)[0] not in FOLDERS:
            continue
        files[name] = path.read_bytes().replace(b"\r\n", b"\n")
    if "manifest.json" not in files:
        raise ValueError(f"{folder} has no manifest.json.")
    return files


def build(files: Dict[str, bytes]) -> bytes:
    checksums = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items()))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, date_time=ENTRY_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, files[name])
        info = zipfile.ZipInfo("checksums.sha256", date_time=ENTRY_TIME)
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, checksums.encode("utf-8"))
    return out.getvalue()


def load(path: Path) -> bytes:
    """An .orqpkg file as it is, or a source folder packed."""
    if path.is_dir():
        return build(sources(path))
    return path.read_bytes()


def read_files(archive: bytes) -> Dict[str, bytes]:
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        return {n: z.read(n) for n in z.namelist() if not n.endswith("/")}


def read_only_operations(archive: bytes) -> List[Dict[str, str]]:
    """Read-only operations of the package's connections: [{connection, operation}] (the only ones a smoke run calls)."""
    found = []
    for name, data in sorted(read_files(archive).items()):
        if not name.startswith("connections/") or not name.endswith(".json"):
            continue
        definition = json.loads(data.decode("utf-8"))
        operations = definition.get("operations") or {}
        for key in sorted(operations):
            if (operations[key] or {}).get("effectClass") == "readOnly":
                found.append({"connection": definition.get("key"), "operation": key})
    return found
