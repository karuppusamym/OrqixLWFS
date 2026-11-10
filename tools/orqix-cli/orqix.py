"""Run the CLI from a checkout without installing it: python tools/orqix-cli/orqix.py <command> ..."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from orqix_cli.commands import main  # noqa: E402

sys.exit(main())
