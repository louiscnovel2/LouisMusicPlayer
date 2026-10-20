"""Custom painted widgets that make up the player screen."""

from __future__ import annotations

from PySide6.QtCore import QPointF, QRectF, QSize, Qt, Signal
from PySide6.QtGui import (
    QBrush,
    QColor,
    QLinearGradient,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
)
from PySide6.QtWidgets import QWidget

from .. import theme
from . import icons


class SeekBar(QWidget):
    """Thin draggable progress bar with a round knob."""

    seekRequested = Signal(int)   # ms, on release
    previewChanged = Signal(int)  # ms, while dragging

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setMinimumHeight(22)
        self.setMouseTracking(True)
        self._max = 1
        self._value = 0
        self._dragging = False
        self._hover = False

    def setRange(self, maximum: int) -> None:
        self._max = max(1, int(maximum))
        self.update()

    def setValue(self, value: int) -> None:
        if self._dragging:
            return
        self._value = max(0, min(int(value), self._max))
        self.update()

    def value(self) -> int:
        return self._value

    # -- interaction ----------------------------------------------------------
    def _pos_to_value(self, x: float) -> int:
        track = self._track_rect()
        rel = (x - track.left()) / max(1.0, track.width())
        return int(max(0.0, min(1.0, rel)) * self._max)

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            self._dragging = True
            self._value = self._pos_to_value(event.position().x())
            self.previewChanged.emit(self._value)
            self.update()

    def mouseMoveEvent(self, event) -> None:
        if self._dragging:
            self._value = self._pos_to_value(event.position().x())
            self.previewChanged.emit(self._value)
            self.update()
        else:
            was = self._hover
            self._hover = self.rect().adjusted(0, -6, 0, 6).contains(event.position().toPoint())
            if was != self._hover:
                self.update()

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton and self._dragging:
            self._dragging = False
            self.seekRequested.emit(self._value)
            self.update()

    def enterEvent(self, event) -> None:
        self._hover = True
        self.update()

    def leaveEvent(self, event) -> None:
        self._hover = False
        self.update()

    def _track_rect(self) -> QRectF:
        m = 6.0
        return QRectF(m, self.height() / 2 - 0.75, self.width() - 2 * m, 1.5)

    # -- paint ----------------------------------------------------------------
    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        track = self._track_rect()
        ratio = self._value / self._max
        p.setPen(Qt.PenStyle.NoPen)

        p.setBrush(QBrush(theme.TRACK_REST))
        p.drawRoundedRect(track, 1, 1)

        filled = QRectF(track.left(), track.top(), track.width() * ratio, track.height())
        p.setBrush(QBrush(theme.TRACK_FILL))
        p.drawRoundedRect(filled, 1, 1)

        r = 5.0 if (self._hover or self._dragging) else 3.6
        cx = track.left() + track.width() * ratio
        p.setBrush(QBrush(theme.ACCENT))
        p.drawEllipse(QPointF(cx, track.center().y()), r, r)
        p.end()


class _GlyphButton(QWidget):
    """Base for the icon-only buttons on the screen."""

    clicked = Signal()

    def __init__(self, icon: str, size: int = 26, parent: QWidget | None = None,
                 line_width: float = 1.8) -> None:
        super().__init__(parent)
        self.icon = icon
        self._size = size
        self._lw = line_width
        self._active = False
        self._pressed = False
        self._hover = False
        self.setFixedSize(size, size)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setAttribute(Qt.WidgetAttribute.WA_Hover, True)

    def set_icon(self, icon: str) -> None:
        self.icon = icon
        self.update()

    def set_active(self, active: bool) -> None:
        if self._active != active:
            self._active = active
            self.update()

    def sizeHint(self) -> QSize:
        return QSize(self._size, self._size)

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            self._pressed = True
            self.update()

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton and self._pressed:
            self._pressed = False
            if self.rect().contains(event.position().toPoint()):
                self.clicked.emit()
            self.update()

    def enterEvent(self, event) -> None:
        self._hover = True
        self.update()

    def leaveEvent(self, event) -> None:
        self._hover = False
        self._pressed = False
        self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        rect = QRectF(self.rect())
        color = QColor(theme.TEXT_PRIMARY) if (self._hover or self._active) else theme.TEXT_SECONDARY
        if self._pressed:
            color = color.darker(120)
        if self._active:
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(QBrush(QColor(255, 255, 255, 64)))
            p.drawEllipse(rect.adjusted(1, 1, -1, -1))
            pen = QPen(QColor(255, 255, 255, 150))
            pen.setWidthF(1.2)
            p.setPen(pen)
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.drawEllipse(rect.adjusted(1.6, 1.6, -1.6, -1.6))
        icons.draw_icon(p, self.icon, rect.adjusted(4, 4, -4, -4), color, self._lw)
        p.end()


class TransportButton(QWidget):
    """Large circular transport button (previous / play-pause / next)."""

    clicked = Signal()

    def __init__(self, icon: str, diameter: int = 52, parent: QWidget | None = None,
                 show_ring: bool = True) -> None:
        super().__init__(parent)
        self.icon = icon
        self._d = diameter
        self._ring = show_ring
        self._pressed = False
        self._hover = False
        self.setFixedSize(diameter, diameter)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setAttribute(Qt.WidgetAttribute.WA_Hover, True)

    def set_icon(self, icon: str) -> None:
        if self.icon != icon:
            self.icon = icon
            self.update()

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            self._pressed = True
            self.update()

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton and self._pressed:
            self._pressed = False
            if self.rect().contains(event.position().toPoint()):
                self.clicked.emit()
            self.update()

    def enterEvent(self, event) -> None:
        self._hover = True
        self.update()

    def leaveEvent(self, event) -> None:
        self._hover = False
        self._pressed = False
        self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        rect = QRectF(self.rect()).adjusted(1.5, 1.5, -1.5, -1.5)
        color = QColor("#FFFFFF")
        if self._ring:
            pen = QPen(QColor(255, 255, 255, 210))
            pen.setWidthF(1.6)
            p.setPen(pen)
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.drawEllipse(rect)
        if self._hover or self._pressed:
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(QBrush(QColor(255, 255, 255, 46 if not self._pressed else 70)))
            p.drawEllipse(rect)
        icons.draw_icon(p, self.icon, rect.adjusted(rect.width() * 0.24, rect.height() * 0.24,
                                                    -rect.width() * 0.24, -rect.height() * 0.24),
                        color, 1.8)
        p.end()


class CoverArt(QWidget):
    """Square album artwork with rounded corners and a graceful placeholder."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._pixmap: QPixmap | None = None
        self.setMinimumSize(120, 120)

    def set_cover(self, pixmap: QPixmap | None) -> None:
        self._pixmap = pixmap
        self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
        rect = QRectF(self.rect())
        radius = 4.0
        path = QPainterPath()
        path.addRoundedRect(rect, radius, radius)
        p.setClipPath(path)

        if self._pixmap and not self._pixmap.isNull():
            scaled = self._pixmap.scaled(
                self.size(), Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                Qt.TransformationMode.SmoothTransformation,
            )
            x = (scaled.width() - self.width()) / 2
            y = (scaled.height() - self.height()) / 2
            p.drawPixmap(int(-x), int(-y), scaled)
        else:
            grad = QLinearGradient(0, 0, 0, rect.height())
            grad.setColorAt(0.0, QColor("#1B1F24"))
            grad.setColorAt(1.0, QColor("#0C0F12"))
            p.fillRect(rect, QBrush(grad))
            side = min(rect.width(), rect.height()) * 0.34
            icons.draw_icon(
                p, "note",
                QRectF(rect.center().x() - side / 2, rect.center().y() - side / 2, side, side),
                QColor(255, 255, 255, 46), 1.8,
            )
        p.setClipping(False)
        pen = QPen(QColor(255, 255, 255, 26))
        pen.setWidthF(1.0)
        p.setPen(pen)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawRoundedRect(rect.adjusted(0.5, 0.5, -0.5, -0.5), radius, radius)
        p.end()


class StatusBar(QWidget):
    """Top status strip: play glyph, track counter and loop / shuffle markers."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setFixedHeight(22)
        self._playing = False
        self._index = 0
        self._total = 0
        self._loop = "all"
        self._shuffle = False

    def set_state(self, playing: bool, index: int, total: int,
                  loop: str, shuffle: bool) -> None:
        self._playing = playing
        self._index = index
        self._total = total
        self._loop = loop
        self._shuffle = shuffle
        self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        h = self.height()
        color = QColor(theme.TEXT_SECONDARY)

        # left: play / pause indicator
        if self._playing:
            icons.draw_icon(p, "play", QRectF(2, 0, h, h), color, 1.6)
        else:
            icons.draw_icon(p, "pause", QRectF(2, 0, h, h), color, 1.6)

        # centre: track counter "042 / 307"
        p.setFont(theme.font(10))
        p.setPen(QPen(color))
        text = f"{self._index:03d}" if self._total else "---"
        num_rect = QRectF(0, 0, self.width(), h)
        p.drawText(num_rect, Qt.AlignmentFlag.AlignCenter, text)
        if self._total:
            p.setFont(theme.font(9))
            p.setPen(QPen(theme.TEXT_MUTED))
            p.drawText(num_rect.adjusted(self.width() * 0.11, 0, 0, 0),
                       Qt.AlignmentFlag.AlignCenter, f"/{self._total:03d}")

        # right: loop / shuffle markers
        x = self.width() - h - 3
        loop_name = "repeat_one" if self._loop == "one" else "repeat"
        loop_color = theme.ACCENT if self._loop in ("one", "all") else theme.TEXT_MUTED
        icons.draw_icon(p, loop_name, QRectF(x, 3, h - 4, h - 6), loop_color, 1.4)

        if self._shuffle:
            icons.draw_icon(p, "shuffle", QRectF(x - h + 2, 3, h - 4, h - 6), theme.ACCENT, 1.4)
        p.end()


class ElidedLabel(QWidget):
    """Single-line label that fades/elides text which is too wide."""

    def __init__(self, text: str = "", size: int = 12, weight=400,
                 color: QColor | None = None, align=Qt.AlignmentFlag.AlignHCenter,
                 parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._text = text
        self._font = theme.font(size, weight)
        self._color = color or theme.TEXT_PRIMARY
        self._align = align
        self.setMinimumHeight(self._font.pointSize() + 10)

    def set_text(self, text: str) -> None:
        if self._text != text:
            self._text = text or ""
            self.update()

    def set_color(self, color: QColor) -> None:
        self._color = color
        self.update()

    def set_font_size(self, size: int) -> None:
        if self._font.pointSize() == size:
            return
        self._font.setPointSize(size)
        self.setMinimumHeight(self._font.pointSize() + 10)
        self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.TextAntialiasing, True)
        p.setFont(self._font)
        p.setPen(QPen(self._color))
        metrics = p.fontMetrics()
        text = metrics.elidedText(self._text, Qt.TextElideMode.ElideRight, self.width())
        p.drawText(self.rect(), self._align | Qt.AlignmentFlag.AlignVCenter, text)
        p.end()
