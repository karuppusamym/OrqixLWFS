"""Where the tool keeps its settings and sign-in: one JSON file, readable by its owner only."""
from __future__ import annotations

import json
import os
import stat
from pathlib import Path
from typing import Any, Dict

DEFAULT_SERVER = "http://localhost:8080"
DEFAULT_ISSUER = "http://localhost:8180/realms/orqix-dev"
DEFAULT_CLIENT_ID = "orqix-cli"


def config_path() -> Path:
    """ORQIX_CONFIG, or ~/.orqix/config.json."""
    configured = os.environ.get("ORQIX_CONFIG")
    if configured:
        return Path(configured)
    return Path.home() / ".orqix" / "config.json"


def load() -> Dict[str, Any]:
    path = config_path()
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def save(data: Dict[str, Any]) -> None:
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    try:
        os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:  # pragma: no cover - Windows ignores POSIX modes
        pass
    os.replace(tmp, path)
