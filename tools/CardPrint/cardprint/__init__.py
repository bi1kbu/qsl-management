"""Local source-layout compatibility package.

Allows running ``python -m cardprint.cli`` from repository root
without setting PYTHONPATH or installing editable package.
"""

from __future__ import annotations

from pathlib import Path
from pkgutil import extend_path

__path__ = extend_path(__path__, __name__)  # type: ignore[name-defined]

_repo_root = Path(__file__).resolve().parent.parent
_src_pkg = _repo_root / "src" / "cardprint"
if _src_pkg.exists():
    src_pkg_str = str(_src_pkg)
    if src_pkg_str not in __path__:
        __path__.append(src_pkg_str)

