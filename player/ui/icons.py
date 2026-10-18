"""Vector icon drawing so the UI needs no bitmap assets.

Vector glyphs for transport (play/pause/prev/next), loop, shuffle and
navigation controls, plus a neutral music-note placeholder.
"""

from __future__ import annotations

import math

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import (
    QBrush,
    QColor,
    QFont,
    QFontMetricsF,
    QPainter,
    QPainterPath,
    QPen,
    QPolygonF,
)

from .. import theme


def _pen(color: QColor, width: float, cap=Qt.PenCapStyle.RoundCap) -> QPen:
    pen = QPen(color)
    pen.setWidthF(width)
    pen.setCapStyle(cap)
    pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
    return pen


def draw_icon(painter: QPainter, name: str, rect: QRectF, color: QColor, lw: float = 1.8,
              accent: QColor | None = None) -> None:
    """Draw a named glyph centred in *rect*."""
    painter.save()
    painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
    accent = accent or color
    fn = _ICONS.get(name)
    if fn is not None:
        fn(painter, rect, color, accent, lw)
    painter.restore()


# -- transport ----------------------------------------------------------------
def _play(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    s = min(r.width(), r.height())
    cx, cy = r.center().x(), r.center().y()
    w = s * 0.34
    h = s * 0.42
    tri = QPolygonF([
        QPointF(cx - w * 0.55, cy - h),
        QPointF(cx - w * 0.55, cy + h),
        QPointF(cx + w * 0.95, cy),
    ])
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(c))
    p.drawPolygon(tri)


def _pause(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    s = min(r.width(), r.height())
    cx, cy = r.center().x(), r.center().y()
    bw = s * 0.11
    bh = s * 0.44
    gap = s * 0.10
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(c))
    p.drawRoundedRect(QRectF(cx - gap - bw, cy - bh, bw, bh * 2), bw * 0.35, bw * 0.35)
    p.drawRoundedRect(QRectF(cx + gap, cy - bh, bw, bh * 2), bw * 0.35, bw * 0.35)


def _prev(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    s = min(r.width(), r.height())
    cx, cy = r.center().x(), r.center().y()
    w = s * 0.30
    h = s * 0.32
    bar_w = s * 0.07
    tri = QPolygonF([
        QPointF(cx + w, cy - h),
        QPointF(cx + w, cy + h),
        QPointF(cx - w * 0.5, cy),
    ])
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(c))
    p.drawPolygon(tri)
    p.drawRect(QRectF(cx - w * 0.5 - bar_w, cy - h, bar_w, h * 2))


def _next(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    s = min(r.width(), r.height())
    cx, cy = r.center().x(), r.center().y()
    w = s * 0.30
    h = s * 0.32
    bar_w = s * 0.07
    tri = QPolygonF([
        QPointF(cx - w, cy - h),
        QPointF(cx - w, cy + h),
        QPointF(cx + w * 0.5, cy),
    ])
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(c))
    p.drawPolygon(tri)
    p.drawRect(QRectF(cx + w * 0.5, cy - h, bar_w, h * 2))


# -- loop / shuffle -----------------------------------------------------------
def _arc_arrow(p: QPainter, r: QRectF, color: QColor, lw: float, closed: bool) -> None:
    p.setBrush(Qt.BrushStyle.NoBrush)
    p.setPen(_pen(color, lw))
    d = min(r.width(), r.height()) * 0.86
    box = QRectF(0, 0, d, d)
    box.moveCenter(r.center())
    span = 300 if not closed else 360
    p.drawArc(box, 60 * 16, span * 16)
    # arrow head at the top-right end of the arc
    angle = math.radians(60 + (0 if not closed else 0))
    end = QPointF(box.center().x() + d / 2 * math.cos(angle),
                  box.center().y() - d / 2 * math.sin(angle))
    head = min(r.width(), r.height()) * 0.22
    tri = QPolygonF([
        QPointF(end.x() + head * 0.1, end.y() - head * 0.55),
        QPointF(end.x() + head * 0.55, end.y() + head * 0.35),
        QPointF(end.x() - head * 0.45, end.y() + head * 0.1),
    ])
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(color))
    p.drawPolygon(tri)


def _repeat(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    _arc_arrow(p, r, c, lw, closed=False)


def _repeat_one(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    _arc_arrow(p, r, c, lw, closed=False)
    f = QFont(theme.resolve_family(), int(min(r.width(), r.height()) * 0.5))
    f.setBold(True)
    p.setFont(f)
    p.setPen(QPen(c))
    p.drawText(r, Qt.AlignmentFlag.AlignCenter, "1")


def _shuffle(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    p.setPen(_pen(c, lw))
    w, h = r.width() * 0.5, r.height() * 0.32
    cx, cy = r.center().x(), r.center().y()
    p.drawLine(QPointF(cx - w, cy - h), QPointF(cx + w, cy + h))
    p.drawLine(QPointF(cx - w, cy + h), QPointF(cx + w, cy - h))
    head = w * 0.4
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(c))
    p.drawPolygon(QPolygonF([
        QPointF(cx + w, cy + h),
        QPointF(cx + w - head, cy + h - head),
        QPointF(cx + w - head * 0.2, cy + h + head * 0.4),
    ]))
    p.drawPolygon(QPolygonF([
        QPointF(cx + w, cy - h),
        QPointF(cx + w - head, cy - h + head),
        QPointF(cx + w - head * 0.2, cy - h - head * 0.4),
    ]))


# -- navigation / misc --------------------------------------------------------
def _back(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    p.setPen(_pen(c, lw))
    w, h = r.width() * 0.30, r.height() * 0.26
    cx, cy = r.center().x(), r.center().y()
    path = QPainterPath()
    path.moveTo(cx + w, cy - h * 0.9)
    path.lineTo(cx + w, cy + h * 0.3)
    path.quadTo(cx + w, cy + h, cx + w * 0.2, cy + h)
    path.lineTo(cx - w * 0.2, cy + h)
    p.drawPath(path)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(c))
    p.drawPolygon(QPolygonF([
        QPointF(cx - w, cy + h),
        QPointF(cx - w * 0.15, cy + h - h * 0.6),
        QPointF(cx - w * 0.15, cy + h + h * 0.6),
    ]))


def _list(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    p.setPen(_pen(c, lw))
    cx, cy = r.center().x(), r.center().y()
    w = r.width() * 0.34
    gap = r.height() * 0.24
    for dy in (-gap, 0, gap):
        p.drawLine(QPointF(cx - w, cy + dy), QPointF(cx + w, cy + dy))


def _folder(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    p.setPen(_pen(c, lw))
    p.setBrush(Qt.BrushStyle.NoBrush)
    w = r.width() * 0.70
    h = r.height() * 0.52
    box = QRectF(0, 0, w, h)
    box.moveCenter(r.center())
    box.translate(0, h * 0.10)
    tabw = w * 0.44
    tabh = h * 0.24
    path = QPainterPath()
    path.moveTo(box.left(), box.top() + tabh)
    path.lineTo(box.left(), box.top())
    path.lineTo(box.left() + tabw, box.top())
    path.lineTo(box.left() + tabw + tabh * 0.9, box.top() + tabh)
    path.lineTo(box.right(), box.top() + tabh)
    path.lineTo(box.right(), box.bottom())
    path.lineTo(box.left(), box.bottom())
    path.closeSubpath()
    p.drawPath(path)


def _eq(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    p.setPen(_pen(c, lw, Qt.PenCapStyle.RoundCap))
    cx, cy = r.center().x(), r.center().y()
    step = r.width() * 0.20
    hs = (0.30, 0.45, 0.22, 0.40, 0.28)
    for i, h in enumerate(hs):
        x = cx + (i - 2) * step
        p.drawLine(QPointF(x, cy - r.height() * h), QPointF(x, cy + r.height() * h))


def _toolbox(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    p.setPen(_pen(c, lw))
    w = r.width() * 0.68
    h = r.height() * 0.52
    box = QRectF(0, 0, w, h)
    box.moveCenter(r.center())
    box.translate(0, h * 0.10)
    p.drawRoundedRect(box, h * 0.12, h * 0.12)
    p.drawLine(QPointF(box.left(), box.center().y()), QPointF(box.right(), box.center().y()))
    # handle
    hw = w * 0.44
    handle = QRectF(0, 0, hw, h * 0.42)
    handle.moveCenter(QPointF(box.center().x(), box.top()))
    p.drawRoundedRect(handle.adjusted(0, 0, 0, h * 0.2), h * 0.1, h * 0.1)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(c))
    p.drawRect(QRectF(box.center().x() - w * 0.05, box.center().y() - h * 0.14, w * 0.10, h * 0.28))


def _gear(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    p.setPen(_pen(c, lw))
    p.setBrush(Qt.BrushStyle.NoBrush)
    d = min(r.width(), r.height()) * 0.62
    box = QRectF(0, 0, d, d)
    box.moveCenter(r.center())
    p.drawEllipse(box)
    inner = box.adjusted(d * 0.32, d * 0.32, -d * 0.32, -d * 0.32)
    p.drawEllipse(inner)
    p.setPen(_pen(c, lw, Qt.PenCapStyle.FlatCap))
    import math as _m
    for i in range(8):
        ang = _m.radians(i * 45)
        x1 = box.center().x() + _m.cos(ang) * d / 2
        y1 = box.center().y() + _m.sin(ang) * d / 2
        x2 = box.center().x() + _m.cos(ang) * d * 0.72
        y2 = box.center().y() + _m.sin(ang) * d * 0.72
        p.drawLine(QPointF(x1, y1), QPointF(x2, y2))


def _volume(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    p.setPen(_pen(c, lw))
    w = r.width() * 0.28
    h = r.height() * 0.24
    cx, cy = r.center().x(), r.center().y()
    body = QRectF(cx - w * 0.9, cy - h * 0.5, w * 0.9, h)
    p.setBrush(Qt.BrushStyle.NoBrush)
    p.drawRect(body)
    p.setBrush(QBrush(c))
    p.setPen(Qt.PenStyle.NoPen)
    p.drawPolygon(QPolygonF([
        QPointF(cx - w * 0.05, cy - h * 0.5),
        QPointF(cx + w * 0.35, cy - h),
        QPointF(cx + w * 0.35, cy + h),
        QPointF(cx - w * 0.05, cy + h * 0.5),
    ]))
    p.setPen(_pen(c, lw))
    p.drawArc(QRectF(cx + w * 0.2, cy - h * 1.1, w * 0.7, h * 2.2), -60 * 16, 120 * 16)
    p.drawArc(QRectF(cx + w * 0.2, cy - h * 1.6, w * 1.3, h * 3.2), -55 * 16, 110 * 16)


def _battery(p: QPainter, r: QRectF, color: QColor, level: float = 1.0) -> None:
    w = r.width() * 0.92
    h = r.height() * 0.44
    box = QRectF(0, 0, w, h)
    box.moveCenter(r.center())
    p.setPen(_pen(color, 1.2))
    p.setBrush(Qt.BrushStyle.NoBrush)
    p.drawRoundedRect(box, h * 0.22, h * 0.22)
    nub = QRectF(box.right() + 1, box.center().y() - h * 0.16, h * 0.22, h * 0.32)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QBrush(color))
    p.drawRoundedRect(nub, 1, 1)
    inner = box.adjusted(2.2, 2.2, -2.2, -2.2)
    seg = inner.width() / 3.0
    for i in range(3):
        if (i + 1) / 3.0 <= level + 1e-6:
            p.drawRoundedRect(QRectF(inner.left() + i * seg + 0.6, inner.top(),
                                     seg - 1.2, inner.height()), 0.8, 0.8)


def _note(p: QPainter, r: QRectF, c: QColor, a: QColor, lw: float) -> None:
    """Neutral music-note glyph (used for artwork placeholders)."""
    p.setPen(_pen(c, max(1.4, lw), Qt.PenCapStyle.RoundCap))
    p.setBrush(QBrush(c))
    s = min(r.width(), r.height())
    cx, cy = r.center().x(), r.center().y()
    head = s * 0.15
    # two note heads
    for dx in (-s * 0.16, s * 0.16):
        ellipse = QRectF(cx + dx - head, cy + s * 0.14, head * 2, head * 1.5)
        p.drawEllipse(ellipse)
    # stems
    p.setBrush(Qt.BrushStyle.NoBrush)
    p.setPen(_pen(c, max(1.4, s * 0.05), Qt.PenCapStyle.RoundCap))
    p.drawLine(QPointF(cx - s * 0.16 + head, cy + s * 0.20),
               QPointF(cx - s * 0.16 + head, cy - s * 0.26))
    p.drawLine(QPointF(cx + s * 0.16 + head, cy + s * 0.20),
               QPointF(cx + s * 0.16 + head, cy - s * 0.26))
    p.drawLine(QPointF(cx - s * 0.16 + head, cy - s * 0.26),
               QPointF(cx + s * 0.16 + head, cy - s * 0.26))


_ICONS = {
    "play": _play,
    "pause": _pause,
    "prev": _prev,
    "next": _next,
    "repeat": _repeat,
    "repeat_one": _repeat_one,
    "shuffle": _shuffle,
    "back": _back,
    "list": _list,
    "folder": _folder,
    "eq": _eq,
    "toolbox": _toolbox,
    "gear": _gear,
    "volume": _volume,
    "note": _note,
}
