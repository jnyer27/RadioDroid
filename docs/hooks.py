"""
MkDocs build hook — single source of truth for documentation.

- userguide.md (repo root) -> docs/index.md
- privacypolicy.md (repo root) -> docs/privacy-policy.md

Never edit the generated files under docs/ directly; edit the repo-root sources.
"""

import shutil
from pathlib import Path


def on_pre_build(config, **kwargs):
    repo_root = Path(config["docs_dir"]).parent
    docs_dir = Path(config["docs_dir"])

    ug_src = repo_root / "userguide.md"
    ug_dst = docs_dir / "index.md"
    if not ug_src.exists():
        raise FileNotFoundError(f"userguide.md not found at {ug_src}")
    shutil.copy2(ug_src, ug_dst)
    print(f"  [hook] Copied userguide.md -> docs/index.md")

    pp_src = repo_root / "privacypolicy.md"
    pp_dst = docs_dir / "privacy-policy.md"
    if not pp_src.exists():
        raise FileNotFoundError(f"privacypolicy.md not found at {pp_src}")
    shutil.copy2(pp_src, pp_dst)
    print(f"  [hook] Copied privacypolicy.md -> docs/privacy-policy.md")
