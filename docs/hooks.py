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
    text = ug_src.read_text(encoding="utf-8")
    # GitHub renders userguide.md from repo root (docs/assets/...); MkDocs serves from docs/ (assets/...).
    text = text.replace("](docs/assets/", "](assets/")
    text = text.replace('src="docs/assets/', 'src="assets/')
    text = text.replace("src='docs/assets/", "src='assets/")
    ug_dst.write_text(text, encoding="utf-8")
    print(f"  [hook] Copied userguide.md -> docs/index.md (adjusted asset paths)")

    pp_src = repo_root / "privacypolicy.md"
    pp_dst = docs_dir / "privacy-policy.md"
    if not pp_src.exists():
        raise FileNotFoundError(f"privacypolicy.md not found at {pp_src}")
    pptext = pp_src.read_text(encoding="utf-8")
    pptext = pptext.replace("](docs/assets/", "](assets/")
    pptext = pptext.replace('src="docs/assets/', 'src="assets/')
    pptext = pptext.replace("src='docs/assets/", "src='assets/")
    pp_dst.write_text(pptext, encoding="utf-8")
    print(f"  [hook] Copied privacypolicy.md -> docs/privacy-policy.md (adjusted asset paths)")
