"""The screen surface: a full-bleed dark display.

The gold device shell has been removed so the player fills the whole window;
this widget keeps only the screen painting plus the wheel / volume overlay.
"""

from __future__ import annotations

from PySide6.QtCore import QRect, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QBrush, QColor, QLinearGradient, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import QWidget

from .. import theme
from . import icons


class VolumeOverlay(QWidget):
    """Transient pill shown when the volume changes via the mouse wheel."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._value = 70
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, True)
        self.hide()

    def set_value(self, value: int) -> None:
        self._value = value
        self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        rect = QRectF(self.rect())

        bg = QPainterPath()
        bg.addRoundedRect(rect, 10, 10)
        p.fillPath(bg, QBrush(QColor(255, 255, 255, 235)))

        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QBrush(QColor("#B9BEC4")))
        track = QRectF(rect.left() + 26, rect.center().y() - 1.5, rect.width() - 34, 3)
        p.drawRoundedRect(track, 1.5, 1.5)

        p.setBrush(QBrush(QColor("#1B1B1B")))
        filled = QRectF(
            track.left(),
            track.top(),
            track.width() * max(0, min(100, self._value)) / 100.0,
            track.height(),
        )
        p.drawRoundedRect(filled, 1.5, 1.5)

        icons.draw_icon(
            p,
            "volume",
            QRectF(rect.left() + 5, rect.center().y() - 8, 16, 16),
            QColor("#1B1B1B"),
            1.3,
        )
        p.end()


class DeviceFrame(QWidget):
    volumeStepRequested = Signal(int)
    playToggleRequested = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumSize(360, 640)
        self._content = None
        self._wheel_volume = False
        self._overlay = VolumeOverlay(self)
        self._vol_timer = QTimer(self)
        self._vol_timer.setSingleShot(True)
        self._vol_timer.setInterval(1200)
        self._vol_timer.timeout.connect(self._overlay.hide)

    # -- content ----------------------------------------------------------
    def attach(self, content):
        self._content = content
        content.setParent(self)
        content.raise_()
        self._layout_content()
        self._overlay.raise_()

    def screen_rect(self) -> QRect:
        return self.rect()

    def _layout_content(self):
        if self._content is not None:
            self._content.setGeometry(self.rect())
        self._place_overlay()

    def _place_overlay(self):
        w = int(self.width() * 0.42)
        h = 28
        x = (self.width() - w) // 2
        y = int(self.height() * 0.02) + 8
        self._overlay.setGeometry(x, y, w, h)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._layout_content()

    # -- volume -----------------------------------------------------------
    def set_wheel_volume_enabled(self, enabled: bool) -> None:
        """Allow the mouse wheel to change volume (now-playing screen only)."""
        self._wheel_volume = bool(enabled)

    def flash_volume(self, value: int):
        self._overlay.set_value(value)
        self._place_overlay()
        self._overlay.raise_()
        self._overlay.show()
        self._vol_timer.start()

    def wheelEvent(self, event):
        if not self._wheel_volume:
            event.ignore()
            return
        delta = event.angleDelta().y()
        if delta:
            self.volumeStepRequested.emit(2 if delta > 0 else -2)
            event.accept()

    # -- painting ---------------------------------------------------------
    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        rect = QRectF(self.rect())
        grad = QLinearGradient(rect.topLeft(), rect.bottomLeft())
        grad.setColorAt(0.0, theme.SCREEN_BG_TOP)
        grad.setColorAt(1.0, theme.SCREEN_BG_BOTTOM)
        p.fillRect(rect, QBrush(grad))
        p.setPen(QPen(Qt.PenStyle.NoPen))
        p.end()
