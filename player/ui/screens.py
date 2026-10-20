"""Screen pages: Now Playing, list/browse pages and the bottom nav bar."""

from __future__ import annotations

import os
from dataclasses import dataclass, field

from PySide6.QtCore import QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QBrush, QColor, QFont, QPainter, QPen, QPixmap
from PySide6.QtWidgets import (
    QBoxLayout,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QPushButton,
    QSizePolicy,
    QStyle,
    QStyledItemDelegate,
    QVBoxLayout,
    QWidget,
)

from .. import theme
from ..library import Track
from . import icons
from .widgets import CoverArt, ElidedLabel, SeekBar, StatusBar, TransportButton, _GlyphButton

_ART_CACHE: dict[str, QPixmap | None] = {}

_LIVE_BTN_QSS = """
QPushButton {
    background: rgba(255,255,255,16);
    color: #E9E9EC;
    border: 1px solid rgba(255,255,255,40);
    border-radius: 3px;
    padding: 3px 9px;
}
QPushButton:hover { background: rgba(255,255,255,34); }
QPushButton:disabled { color: #6C727A; border-color: rgba(255,255,255,18); }
QPushButton:checked {
    background: rgba(203,173,119,85);
    border-color: #CBAD77;
    color: #FFFFFF;
}
"""


def cover_to_pixmap(track: Track) -> QPixmap | None:
    """Convert (and cache) a track's embedded art into a QPixmap."""
    if not track.cover:
        return None
    key = track.path
    cached = _ART_CACHE.get(key)
    if cached is not None:
        return cached
    pix = QPixmap()
    if pix.loadFromData(track.cover):
        _ART_CACHE[key] = pix
        return pix
    return None


def clear_art_cache() -> None:
    _ART_CACHE.clear()


def fmt_time(ms: int) -> str:
    if ms < 0:
        ms = 0
    total = int(ms // 1000)
    return f"{total // 60:02d}:{total % 60:02d}"


class Badge(QWidget):
    """Small outlined badge used for the 'HR' hi-res mark."""

    def __init__(self, text: str, color: QColor = theme.HR_GOLD, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._text = text
        self._color = color
        self.setFixedHeight(16)
        self.setMinimumWidth(26)

    def set_text(self, text: str) -> None:
        if self._text != text:
            self._text = text
            self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        pen = QPen(self._color)
        pen.setWidthF(1.0)
        p.setPen(pen)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawRoundedRect(r, 2.5, 2.5)
        p.setFont(theme.font(7, QFont.Weight.Bold))
        p.setPen(QPen(self._color))
        p.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, self._text)
        p.end()


class Glyph(QWidget):
    """A tiny inline icon (non-interactive)."""

    def __init__(self, name: str, size: int = 18, color: QColor = theme.TEXT_SECONDARY,
                 parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._name = name
        self._color = color
        self.setFixedSize(size, size)

    def set_name(self, name: str) -> None:
        self._name = name
        self.update()

    def set_color(self, color: QColor) -> None:
        self._color = color
        self.update()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        icons.draw_icon(p, self._name, QRectF(self.rect()).adjusted(1, 1, -1, -1), self._color, 1.5)
        p.end()


class NowPlayingPage(QWidget):
    """The 'now playing' screen."""

    seekRequested = Signal(int)
    prevClicked = Signal()
    nextClicked = Signal()
    toggleClicked = Signal()
    repeatClicked = Signal()
    shuffleClicked = Signal()
    detailsClicked = Signal()
    liveToggled = Signal(bool)
    cheerClicked = Signal()

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._duration = 0
        self._dragging = False

        root = QVBoxLayout(self)
        root.setContentsMargins(14, 10, 14, 6)
        root.setSpacing(6)

        self.status = StatusBar(self)
        root.addWidget(self.status)

        self.osd = QLabel(self)
        self.osd.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.osd.setStyleSheet(
            "QLabel{background:rgba(245,245,245,235);color:#14171C;"
            "border-radius:8px;padding:8px 16px;}"
        )
        self.osd.setFont(theme.font(11, QFont.Weight.Medium))
        self.osd.hide()
        self._osd_timer = QTimer(self)
        self._osd_timer.setSingleShot(True)
        self._osd_timer.setInterval(1300)
        self._osd_timer.timeout.connect(self.osd.hide)

        self.cover = CoverArt(self)
        self.cover.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)

        # time row
        self._time_box = QWidget(self)
        time_row = QHBoxLayout(self._time_box)
        time_row.setContentsMargins(2, 0, 2, 0)
        self.time_now = ElidedLabel("00:00", 9, color=theme.TEXT_SECONDARY,
                                    align=Qt.AlignmentFlag.AlignLeft)
        self.time_mid = ElidedLabel("---", 9, color=theme.TEXT_SECONDARY,
                                    align=Qt.AlignmentFlag.AlignHCenter)
        self.time_total = ElidedLabel("00:00", 9, color=theme.TEXT_SECONDARY,
                                      align=Qt.AlignmentFlag.AlignRight)
        for lbl in (self.time_now, self.time_mid, self.time_total):
            time_row.addWidget(lbl, 1)

        self.seek = SeekBar(self)
        self.seek.previewChanged.connect(self._on_preview)
        self.seek.seekRequested.connect(self._on_seek)

        # text block
        self.title = ElidedLabel("No Track", 15, QFont.Weight.Normal, theme.TEXT_PRIMARY,
                                 Qt.AlignmentFlag.AlignHCenter, self)
        self.artist = ElidedLabel("", 11, QFont.Weight.Normal, theme.TEXT_SECONDARY,
                                  Qt.AlignmentFlag.AlignHCenter, self)

        self._album_box = QWidget(self)
        album_row = QHBoxLayout(self._album_box)
        album_row.setContentsMargins(0, 0, 0, 0)
        album_row.setSpacing(6)
        album_row.addStretch(1)
        self.spec_icon = Glyph("list", 18, theme.TEXT_SECONDARY, self)
        self.album = ElidedLabel("", 10, QFont.Weight.Normal, theme.TEXT_MUTED,
                                 Qt.AlignmentFlag.AlignHCenter, self)
        self.album.setMinimumWidth(120)
        self.hr = Badge("HR", theme.HR_GOLD, self)
        self.hr.setVisible(False)
        album_row.addWidget(self.spec_icon)
        album_row.addWidget(self.album)
        album_row.addWidget(self.hr)
        album_row.addStretch(1)

        # transport row
        self._transport_box = QWidget(self)
        transport = QHBoxLayout(self._transport_box)
        transport.setContentsMargins(0, 0, 0, 0)
        transport.setSpacing(18)
        self.repeat_btn = _GlyphButton("repeat", 30, self)
        self.shuffle_btn = _GlyphButton("shuffle", 30, self)
        self.prev_btn = TransportButton("prev", 46, self)
        self.toggle_btn = TransportButton("play", 58, self)
        self.next_btn = TransportButton("next", 46, self)

        self.repeat_btn.clicked.connect(self.repeatClicked)
        self.shuffle_btn.clicked.connect(self.shuffleClicked)
        self.prev_btn.clicked.connect(self.prevClicked)
        self.toggle_btn.clicked.connect(self.toggleClicked)
        self.next_btn.clicked.connect(self.nextClicked)

        transport.addStretch(1)
        transport.addWidget(self.shuffle_btn)
        transport.addWidget(self.prev_btn)
        transport.addWidget(self.toggle_btn)
        transport.addWidget(self.next_btn)
        transport.addWidget(self.repeat_btn)
        transport.addStretch(1)

        # live effect row
        self._live_box = QWidget(self)
        live_row = QHBoxLayout(self._live_box)
        live_row.setContentsMargins(0, 0, 0, 0)
        live_row.setSpacing(8)
        self.live_btn = QPushButton("LIVE")
        self.live_btn.setCheckable(True)
        self.live_btn.setStyleSheet(_LIVE_BTN_QSS)
        self.live_btn.setFont(theme.font(9, QFont.Weight.Medium))
        self.live_btn.setToolTip("Live Effect（会場の響き＋観客の歓声）")
        self.live_btn.clicked.connect(self.liveToggled)
        self.live_venue = ElidedLabel("", 8, color=theme.TEXT_MUTED,
                                      align=Qt.AlignmentFlag.AlignLeft, parent=self)
        self.cheer_btn = QPushButton("歓声を追加")
        self.cheer_btn.setStyleSheet(_LIVE_BTN_QSS)
        self.cheer_btn.setFont(theme.font(9))
        self.cheer_btn.setToolTip("いま鳴っている位置に観客の歓声を追加します")
        self.cheer_btn.clicked.connect(self.cheerClicked)
        self.cheer_btn.setEnabled(False)
        live_row.addWidget(self.live_btn)
        live_row.addWidget(self.live_venue, 1)
        live_row.addWidget(self.cheer_btn)

        # portrait: cover above details / landscape: cover left of details
        self._middle = QBoxLayout(QBoxLayout.Direction.TopToBottom)
        self._middle.setContentsMargins(0, 0, 0, 0)
        self._middle.setSpacing(6)
        root.addLayout(self._middle, 1)

        self._details = QVBoxLayout()
        self._details.setContentsMargins(0, 0, 0, 0)
        self._details.setSpacing(6)
        self._middle.addWidget(self.cover, 0, Qt.AlignmentFlag.AlignHCenter)
        self._middle.addLayout(self._details, 1)

        self._landscape: bool | None = None
        self._apply_orientation(False)

    # -- orientation ----------------------------------------------------------
    def _apply_orientation(self, landscape: bool) -> None:
        if self._landscape == landscape:
            return
        self._landscape = landscape

        while self._details.count():
            self._details.takeAt(0)

        if landscape:
            self._middle.setDirection(QBoxLayout.Direction.LeftToRight)
            self._middle.setSpacing(24)
            self._middle.setAlignment(self.cover, Qt.AlignmentFlag.AlignVCenter)
            self.title.set_font_size(20)
            self.artist.set_font_size(14)
            self._details.setSpacing(6)
            self._details.addStretch(1)
            for widget in (self.title, self.artist, self._album_box):
                self._details.addWidget(widget)
            self._details.addSpacing(8)
            self._details.addWidget(self._time_box)
            self._details.addWidget(self.seek)
            self._details.addWidget(self._live_box)
            self._details.addSpacing(4)
            self._details.addWidget(self._transport_box)
            self._details.addStretch(1)
        else:
            self._middle.setDirection(QBoxLayout.Direction.TopToBottom)
            self._middle.setSpacing(6)
            self._middle.setAlignment(self.cover, Qt.AlignmentFlag.AlignHCenter)
            self.title.set_font_size(15)
            self.artist.set_font_size(11)
            self._details.setSpacing(6)
            for widget in (self._time_box, self.seek, self.title, self.artist,
                           self._album_box, self._transport_box, self._live_box):
                self._details.addWidget(widget)
            self._details.addStretch(1)
        self._update_cover_size()

    def _update_cover_size(self) -> None:
        if self.width() <= 0 or self.height() <= 0:
            return
        if self._landscape:
            side = min(self.height() - 64, int(self.width() * 0.42))
        else:
            side = min(self.width() - 28, int(self.height() * 0.46))
        side = max(120, side)
        self.cover.setFixedSize(side, side)

    # -- live effect ----------------------------------------------------------
    def set_live(self, enabled: bool, venue: str = "") -> None:
        self.live_btn.blockSignals(True)
        self.live_btn.setChecked(bool(enabled))
        self.live_btn.blockSignals(False)
        self.cheer_btn.setEnabled(bool(enabled))
        self.live_venue.set_text(venue if enabled else "")

    # -- sizing ---------------------------------------------------------------
    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._apply_orientation(self.width() > self.height())
        self._update_cover_size()
        self._center_osd()

    def _center_osd(self) -> None:
        self.osd.adjustSize()
        x = (self.width() - self.osd.width()) // 2
        y = int(self.height() * 0.18)
        self.osd.move(max(6, x), max(6, y))

    def show_osd(self, text: str) -> None:
        """Briefly show an on-screen indicator (e.g. loop / shuffle mode)."""
        if not text:
            return
        self.osd.setText(text)
        self.osd.adjustSize()
        self._center_osd()
        self.osd.raise_()
        self.osd.show()
        self._osd_timer.start()

    # -- state updates --------------------------------------------------------
    def set_track(self, index: int, track: Track | None, total: int) -> None:
        if track is None:
            self.title.set_text("No Track")
            self.artist.set_text("")
            self.album.set_text("")
            self.hr.setVisible(False)
            self.cover.set_cover(None)
            self.time_mid.set_text("---")
            return
        self.title.set_text(track.display_title)
        self.artist.set_text(track.display_artist)
        self.album.set_text(track.display_album)
        self.hr.setVisible(track.is_hires)
        self.cover.set_cover(cover_to_pixmap(track))
        self._update_mid(index, total)

    def _update_mid(self, index: int | None = None, total: int | None = None) -> None:
        if index is None:
            return
        self.time_mid.set_text(f"{index + 1:03d}/{total:03d}" if total else f"{index + 1:03d}")

    def set_index(self, index: int, total: int) -> None:
        self._update_mid(index, total)

    def set_duration(self, ms: int) -> None:
        self._duration = max(0, ms)
        self.seek.setRange(self._duration)
        self.time_total.set_text(fmt_time(self._duration))

    def set_position(self, ms: int) -> None:
        if not self._dragging:
            self.seek.setValue(ms)
            self.time_now.set_text(fmt_time(ms))

    def set_playing(self, playing: bool) -> None:
        self.toggle_btn.set_icon("pause" if playing else "play")

    def set_loop_mode(self, mode: str) -> None:
        self.repeat_btn.set_icon("repeat_one" if mode == "one" else "repeat")
        self.repeat_btn.set_active(mode in ("one", "all"))

    def set_shuffle(self, enabled: bool) -> None:
        self.shuffle_btn.set_active(enabled)

    def _on_preview(self, ms: int) -> None:
        self._dragging = True
        self.time_now.set_text(fmt_time(ms))

    def _on_seek(self, ms: int) -> None:
        self._dragging = False
        self.seekRequested.emit(ms)


class BottomBar(QWidget):
    """The four-icon strip along the bottom of the screen."""

    backClicked = Signal()
    foldersClicked = Signal()
    playlistsClicked = Signal()
    settingsClicked = Signal()

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setFixedHeight(40)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(18, 2, 18, 6)
        lay.setSpacing(0)

        self.back_btn = _GlyphButton("back", 30, self)
        self.folder_btn = _GlyphButton("folder", 30, self)
        self.list_btn = _GlyphButton("list", 30, self)
        self.tool_btn = _GlyphButton("toolbox", 30, self)

        self.back_btn.clicked.connect(self.backClicked)
        self.folder_btn.clicked.connect(self.foldersClicked)
        self.list_btn.clicked.connect(self.playlistsClicked)
        self.tool_btn.clicked.connect(self.settingsClicked)

        for btn in (self.back_btn, self.folder_btn, self.list_btn, self.tool_btn):
            lay.addWidget(btn)
            lay.addStretch(1)
        lay.addSpacing(-8)

    def set_active(self, which: str | None) -> None:
        self.back_btn.set_active(which == "back")
        self.folder_btn.set_active(which == "folders")
        self.list_btn.set_active(which == "playlists")
        self.tool_btn.set_active(which == "settings")


@dataclass
class Row:
    kind: str                      # 'folder' | 'track' | 'playlist' | 'action' | 'info'
    label: str
    sub: str = ""
    badge: str = ""
    icon: str = "folder"
    payload: object = None
    playing: bool = False
    fields: dict = field(default_factory=dict)


class RowDelegate(QStyledItemDelegate):
    def paint(self, painter: QPainter, option, index) -> None:
        row: Row = index.data(Qt.ItemDataRole.UserRole)
        if row is None:
            super().paint(painter, option, index)
            return
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        rect = QRectF(option.rect)

        if option.state & QStyle.StateFlag.State_Selected:
            painter.fillRect(rect, QColor(255, 255, 255, 30))
        elif option.state & QStyle.StateFlag.State_MouseOver:
            painter.fillRect(rect, QColor(255, 255, 255, 14))

        icon_color = theme.ACCENT if row.playing else theme.TEXT_SECONDARY
        icons.draw_icon(painter, row.icon, QRectF(rect.left() + 8, rect.top() + 8, 22, 22),
                        icon_color, 1.6)

        x = rect.left() + 40
        right_pad = 10
        badge_w = 0
        if row.badge:
            badge_w = 34
            br = QRectF(rect.right() - badge_w - right_pad, rect.top() + 8, 30, 15)
            pen = QPen(theme.HR_GOLD)
            pen.setWidthF(1.0)
            painter.setPen(pen)
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.drawRoundedRect(br, 2.5, 2.5)
            painter.setFont(theme.font(7, QFont.Weight.Bold))
            painter.setPen(QPen(theme.HR_GOLD))
            painter.drawText(br, Qt.AlignmentFlag.AlignCenter, row.badge)

        text_right = rect.right() - right_pad - badge_w
        primary = QRectF(x, rect.top() + 4, text_right - x, rect.height() * 0.5)
        painter.setFont(theme.font(10, QFont.Weight.Medium if row.kind != "track" else QFont.Weight.Normal))
        painter.setPen(QPen(theme.ACCENT if row.playing else theme.TEXT_PRIMARY))
        fm = painter.fontMetrics()
        painter.drawText(primary, Qt.AlignmentFlag.AlignVCenter,
                         fm.elidedText(row.label, Qt.TextElideMode.ElideRight, int(primary.width())))

        if row.sub:
            secondary = QRectF(x, rect.top() + rect.height() * 0.48, text_right - x, rect.height() * 0.48)
            painter.setFont(theme.font(8))
            painter.setPen(QPen(theme.TEXT_MUTED))
            fm2 = painter.fontMetrics()
            painter.drawText(secondary, Qt.AlignmentFlag.AlignVCenter,
                             fm2.elidedText(row.sub, Qt.TextElideMode.ElideRight, int(secondary.width())))

        painter.setPen(QPen(QColor(255, 255, 255, 20)))
        painter.drawLine(int(rect.left()), int(rect.bottom()), int(rect.right()), int(rect.bottom()))
        painter.restore()

    def sizeHint(self, option, index):
        from PySide6.QtCore import QSize
        return QSize(option.rect.width(), 36)


class ListPage(QWidget):
    """Generic scrollable list page (folders / playlists / settings)."""

    activated = Signal(object)
    backClicked = Signal()

    def __init__(self, title: str = "", parent: QWidget | None = None) -> None:
        super().__init__(parent)
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 8, 0, 0)
        root.setSpacing(4)

        header = QHBoxLayout()
        header.setContentsMargins(6, 0, 14, 0)
        self.back_btn = _GlyphButton("back", 26, self)
        self.back_btn.clicked.connect(self.backClicked)
        self.title = ElidedLabel(title, 11, QFont.Weight.Medium, theme.TEXT_PRIMARY,
                                 Qt.AlignmentFlag.AlignVCenter, self)
        header.addWidget(self.back_btn)
        header.addWidget(self.title, 1)
        root.addLayout(header)

        self.list = QListWidget(self)
        self.list.setItemDelegate(RowDelegate(self.list))
        self.list.setMouseTracking(True)
        self.list.viewport().setMouseTracking(True)
        self.list.viewport().setAttribute(Qt.WidgetAttribute.WA_Hover, True)
        self.list.setUniformItemSizes(True)
        self.list.setVerticalScrollMode(QListWidget.ScrollMode.ScrollPerPixel)
        self.list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.list.setFrameShape(QListWidget.Shape.NoFrame)
        self.list.setStyleSheet(
            "QListWidget{background:transparent;border:none;outline:none;}"
            "QListWidget::item{border:none;}"
            "QScrollBar:vertical{background:transparent;width:6px;margin:2px;}"
            "QScrollBar::handle:vertical{background:rgba(255,255,255,60);border-radius:3px;min-height:24px;}"
            "QScrollBar::add-line,QScrollBar::sub-line{height:0;}"
            "QScrollBar::add-page,QScrollBar::sub-page{background:transparent;}"
        )
        self.list.itemActivated.connect(self._on_item)
        self.list.itemClicked.connect(self._on_item)
        self.list.verticalScrollBar().valueChanged.connect(self._on_scroll_changed)
        root.addWidget(self.list, 1)

        self._scroll_target = 0
        self._scroll_token = 0
        self._scroll_tries = 0
        self._internal = False

        self.hint = ElidedLabel("", 8, color=theme.TEXT_MUTED, align=Qt.AlignmentFlag.AlignHCenter, parent=self)
        root.addWidget(self.hint)

    def set_header(self, title: str, hint: str = "") -> None:
        self.title.set_text(title)
        self.hint.set_text(hint)

    def set_rows(self, rows: list[Row], keep_scroll: bool = True) -> None:
        """Replace the rows, keeping the current scroll offset when asked."""
        value = self._scroll_target if keep_scroll else 0
        self._internal = True
        try:
            self.list.clear()
            for row in rows:
                item = QListWidgetItem()
                item.setData(Qt.ItemDataRole.UserRole, row)
                item.setSizeHint(self._size_hint())
                self.list.addItem(item)
        finally:
            self._internal = False
        self.restore_scroll(value)

    # -- scroll persistence ---------------------------------------------------
    def scroll_value(self) -> int:
        """Offset the widget is showing right now."""
        return self.list.verticalScrollBar().value()

    def scroll_target(self) -> int:
        """Offset the list *should* be showing (survives a row rebuild)."""
        return self._scroll_target

    def _on_scroll_changed(self, value: int) -> None:
        # Ignore the resets/updates we cause ourselves while rebuilding rows.
        if self._internal or self._scroll_tries:
            return
        self._scroll_target = value

    def restore_scroll(self, value: int) -> None:
        """Scroll to *value*, re-applying until the new rows have been laid out.

        A QListWidget only knows its scroll range after the freshly added items
        have been laid out, so a single attempt is not enough.
        """
        value = max(0, int(value or 0))
        self._scroll_target = value
        self._scroll_token = getattr(self, "_scroll_token", 0) + 1
        token = self._scroll_token
        self._scroll_tries = 1
        bar = self.list.verticalScrollBar()
        if value:
            bar.setValue(value)

        def apply() -> None:
            if token != self._scroll_token:
                return
            maximum = bar.maximum()
            target = min(value, maximum)
            if bar.value() != target:
                bar.setValue(target)
            if maximum < value and self._scroll_tries < 40:
                self._scroll_tries += 1
                QTimer.singleShot(10, apply)
            else:
                self._scroll_tries = 0  # settled: user scrolling wins again

        QTimer.singleShot(0, apply)

    def _size_hint(self):
        from PySide6.QtCore import QSize
        return QSize(self.list.width() or 300, 38)

    def _on_item(self, item: QListWidgetItem) -> None:
        row: Row = item.data(Qt.ItemDataRole.UserRole)
        if row is not None:
            self.activated.emit(row)
