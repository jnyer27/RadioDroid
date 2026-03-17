"""
MkDocs build hook — single source of truth for documentation.

userguide.md (repo root) is the canonical source.
This hook copies it to docs/index.md before every build so MkDocs
always serves the current version without any manual sync step.

docs/index.md is git-ignored; never edit it directly.
"""

import shutil
from pathlib import Path


def on_pre_build(config, **kwargs):
    repo_root = Path(config["docs_dir"]).parent
    src = repo_root / "userguide.md"
    dst = Path(config["docs_dir"]) / "index.md"

    if not src.exists():
        raise FileNotFoundError(f"userguide.md not found at {src}")

    shutil.copy2(src, dst)
    print(f"  [hook] Copied userguide.md -> docs/index.md")
