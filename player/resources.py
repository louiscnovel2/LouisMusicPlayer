"""Locate bundled resources both in normal runs and in a PyInstaller bundle."""

from __future__ import annotations

import os
import sys


def base_dir() -> str:
    """Directory that contains the ``assets`` folder."""
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return sys._MEIPASS  # type: ignore[attr-defined]
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def resource_path(rel: str) -> str:
    """Return an absolute path for a resource, preferring the bundle."""
    return os.path.join(base_dir(), rel.replace("/", os.sep))
