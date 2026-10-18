"""Colors, fonts and shared painting helpers for the player UI.

The palette and typography are tuned to a champagne-gold portable audio player
with a near-black screen and SST JP like typography.
"""

from __future__ import annotations

import os

from PySide6.QtGui import QColor, QFont, QFontDatabase

# --- Palette -----------------------------------------------------------------
GOLD_TOP = QColor("#F5EFE2")
GOLD_MID = QColor("#E4D8BF")
GOLD_BOTTOM = QColor("#C9B896")
GOLD_EDGE = QColor("#9C8968")
GOLD_EDGE_LIGHT = QColor("#FBF7EE")
GOLD_TEXT = QColor("#6E5C3C")

SCREEN_BG_TOP = QColor("#0B0F14")
SCREEN_BG_BOTTOM = QColor("#04060A")
SCREEN_EDGE = QColor("#000000")

TEXT_PRIMARY = QColor("#F4F4F5")
TEXT_SECONDARY = QColor("#B9BEC4")
TEXT_MUTED = QColor("#7E858D")
ACCENT = QColor("#FFFFFF")
HR_GOLD = QColor("#CBAD77")

TRACK_FILL = QColor("#EDEDED")
TRACK_REST = QColor("#4A4F55")

FONT_CANDIDATES = [
    "SST JP",
    "SST W1G",
    "SST",
    "SST Medium",
    "Yu Gothic UI",
    "Yu Gothic",
    "Noto Sans JP",
    "Meiryo UI",
    "Meiryo",
    "Segoe UI",
    "Arial",
]

_BASE_FAMILY: str | None = None


def _install_bundled_fonts() -> None:
    """Register bundled / system JP fonts so the stack is available."""
    from .resources import resource_path

    candidates = [
        resource_path("assets/NotoSansJP-VF.ttf"),
        r"C:\Windows\Fonts\NotoSansJP-VF.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            QFontDatabase.addApplicationFont(path)


def resolve_family() -> str:
    """Return the first available preferred family."""
    global _BASE_FAMILY
    if _BASE_FAMILY is not None:
        return _BASE_FAMILY

    _install_bundled_fonts()
    available = set(QFontDatabase.families())
    for family in FONT_CANDIDATES:
        if family in available:
            _BASE_FAMILY = family
            return family
    _BASE_FAMILY = "Sans Serif"
    return _BASE_FAMILY


def _coerce_weight(weight) -> QFont.Weight:
    if isinstance(weight, QFont.Weight):
        return weight
    try:
        return QFont.Weight(int(weight))
    except (TypeError, ValueError):
        return QFont.Weight.Normal


def font(size: int, weight=QFont.Weight.Normal, spacing: float = 0.0) -> QFont:
    """Build an app font with the resolved SST-like family."""
    f = QFont(resolve_family(), size)
    f.setWeight(_coerce_weight(weight))
    if spacing:
        f.setLetterSpacing(QFont.SpacingType.PercentageSpacing, 100.0 + spacing)
    f.setStyleStrategy(QFont.StyleStrategy.PreferAntialias)
    return f
