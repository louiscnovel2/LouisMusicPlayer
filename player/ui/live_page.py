"""Live Effect page: venue, intensity and manual crowd reactions."""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import (
    QCheckBox,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QPushButton,
    QSlider,
    QVBoxLayout,
    QWidget,
)

from .. import live, theme
from .eq_page import _BTN_QSS, _SLIDER_QSS
from .screens import ElidedLabel, fmt_time, _GlyphButton

_LIVE_QSS = """
QPushButton {
    background: rgba(255,255,255,16);
    color: #E9E9EC;
    border: 1px solid rgba(255,255,255,40);
    border-radius: 3px;
    padding: 4px 8px;
}
QPushButton:hover { background: rgba(255,255,255,34); }
QPushButton:checked {
    background: rgba(203,173,119,80);
    border-color: #CBAD77;
    color: #FFFFFF;
}
"""


class LivePage(QWidget):
    settingsChanged = Signal(object)   # live.LiveSettings
    cheerAdded = Signal(int)           # position ms
    cheerRemoved = Signal(int)         # index
    cheersCleared = Signal()
    seekRequested = Signal(int)        # preview a cheer position
    backClicked = Signal()

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._loading = False
        self._settings = live.LiveSettings()
        self._positions: list[int] = []

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 8, 0, 6)
        root.setSpacing(4)

        header = QHBoxLayout()
        header.setContentsMargins(6, 0, 14, 0)
        self.back_btn = _GlyphButton("back", 26, self)
        self.back_btn.clicked.connect(self.backClicked)
        self.title = ElidedLabel("ライブ演出", 11, QFont.Weight.Medium,
                                 theme.TEXT_PRIMARY, Qt.AlignmentFlag.AlignVCenter, self)
        header.addWidget(self.back_btn)
        header.addWidget(self.title, 1)
        root.addLayout(header)

        self.subtitle = ElidedLabel("", 8, color=theme.TEXT_MUTED,
                                    align=Qt.AlignmentFlag.AlignHCenter, parent=self)
        root.addWidget(self.subtitle)

        self.enabled = QCheckBox("Live Effect を有効にする（会場の響き＋観客の歓声）")
        self.enabled.setFont(theme.font(9))
        self.enabled.setStyleSheet(f"color:{theme.TEXT_SECONDARY.name()};")
        self.enabled.toggled.connect(self._on_change)
        root.addWidget(self.enabled)

        venue_label = QLabel("会場")
        venue_label.setFont(theme.font(8))
        venue_label.setStyleSheet(f"color:{theme.TEXT_MUTED.name()};")
        venue_label.setContentsMargins(10, 2, 0, 0)
        root.addWidget(venue_label)

        self._venue_grid = QGridLayout()
        self._venue_grid.setContentsMargins(10, 0, 10, 2)
        self._venue_grid.setSpacing(4)
        self._venue_buttons: dict[str, QPushButton] = {}
        for i, key in enumerate(live.VENUE_ORDER):
            btn = QPushButton(live.venue_label(key))
            btn.setCheckable(True)
            btn.setStyleSheet(_BTN_QSS)
            btn.setFont(theme.font(8))
            btn.clicked.connect(lambda _=False, k=key: self._pick_venue(k))
            self._venue_grid.addWidget(btn, i // 3, i % 3)
            self._venue_buttons[key] = btn
        root.addLayout(self._venue_grid)

        self.intensity = self._named_slider(root, "演出の強さ", 0, 100, "{} %")
        self.crowd = self._named_slider(root, "歓声の音量", 0, 100, "{} %")
        self.width = self._named_slider(root, "立体感（広がり）", 0, 100, "{} %")

        self.auto_show = QCheckBox("自動演出（曲を解析して拍手・歓声を入れる）")
        self.auto_show.setFont(theme.font(9))
        self.auto_show.setStyleSheet(f"color:{theme.TEXT_SECONDARY.name()};")
        self.auto_show.toggled.connect(self._on_change)
        root.addWidget(self.auto_show)

        self.status = ElidedLabel("", 8, color=theme.TEXT_MUTED,
                                  align=Qt.AlignmentFlag.AlignLeft, parent=self)
        self.status.setContentsMargins(10, 0, 10, 0)
        root.addWidget(self.status)
        self.position = ElidedLabel("", 8, color=theme.TEXT_MUTED,
                                    align=Qt.AlignmentFlag.AlignLeft, parent=self)
        self.position.setContentsMargins(10, 0, 10, 0)
        root.addWidget(self.position)

        manual_head = QHBoxLayout()
        manual_head.setContentsMargins(10, 2, 10, 0)
        self.manual_label = QLabel("この曲の歓声")
        self.manual_label.setFont(theme.font(9))
        self.manual_label.setStyleSheet(f"color:{theme.TEXT_SECONDARY.name()};")
        add_btn = QPushButton("＋ 現在位置に追加")
        add_btn.setStyleSheet(_BTN_QSS)
        add_btn.setFont(theme.font(8))
        add_btn.clicked.connect(self._add_at_current)
        clear_btn = QPushButton("全消去")
        clear_btn.setStyleSheet(_BTN_QSS)
        clear_btn.setFont(theme.font(8))
        clear_btn.clicked.connect(self.cheersCleared)
        manual_head.addWidget(self.manual_label, 1)
        manual_head.addWidget(add_btn)
        manual_head.addWidget(clear_btn)
        root.addLayout(manual_head)

        self.manual_list = QListWidget(self)
        self.manual_list.setFont(theme.font(9))
        self.manual_list.setStyleSheet(
            "QListWidget{background:rgba(255,255,255,8);border:1px solid "
            "rgba(255,255,255,26);color:#E9E9EC;}"
            "QListWidget::item{height:26px;}"
            "QListWidget::item:selected{background:rgba(203,173,119,90);color:#FFFFFF;}"
        )
        self.manual_list.setFixedHeight(112)
        self.manual_list.itemDoubleClicked.connect(self._on_item_activated)
        root.addWidget(self.manual_list)

        remove_btn = QPushButton("選択した歓声を削除")
        remove_btn.setStyleSheet(_BTN_QSS)
        remove_btn.setFont(theme.font(8))
        remove_btn.clicked.connect(self._remove_selected)
        root.addWidget(remove_btn, 0, Qt.AlignmentFlag.AlignHCenter)

        note = QLabel(
            "再生画面の「歓声を追加」ボタンでも、いま鳴っている位置に追加できます。\n"
            "自動演出の内容: 開始（拍手）／ サビ前（盛り上げ）／ サビ入り（歓声）／ 曲末（大歓声）\n"
            "会場の響きは原音と別に合成するため、元の音はそのまま残ります。"
        )
        note.setFont(theme.font(8))
        note.setWordWrap(True)
        note.setStyleSheet(f"color:{theme.TEXT_MUTED.name()};")
        note.setContentsMargins(12, 4, 12, 0)
        root.addWidget(note)

        root.addStretch(1)

    # -- construction helpers -------------------------------------------------
    def _named_slider(self, root: QVBoxLayout, label: str, lo: int, hi: int,
                      fmt: str) -> QSlider:
        row = QHBoxLayout()
        row.setContentsMargins(10, 0, 10, 0)
        row.setSpacing(6)
        name = QLabel(label)
        name.setFont(theme.font(9))
        name.setStyleSheet(f"color:{theme.TEXT_SECONDARY.name()};")
        name.setMinimumWidth(96)
        slider = QSlider(Qt.Orientation.Horizontal)
        slider.setRange(lo, hi)
        slider.setValue(0)
        slider.setStyleSheet(_SLIDER_QSS)
        value = QLabel(fmt.format(0))
        value.setFont(theme.font(8))
        value.setStyleSheet(f"color:{theme.TEXT_MUTED.name()};")
        value.setMinimumWidth(52)
        value.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        slider.setProperty("fmt", fmt)
        slider.setProperty("value_label", value)
        slider.valueChanged.connect(self._on_change)
        row.addWidget(name)
        row.addWidget(slider, 1)
        row.addWidget(value)
        root.addLayout(row)
        return slider

    def _refresh_named(self) -> None:
        for slider in (self.intensity, self.crowd, self.width):
            fmt = slider.property("fmt")
            label = slider.property("value_label")
            if fmt and label is not None:
                label.setText(fmt.format(slider.value()))

    # -- public ---------------------------------------------------------------
    def load_state(self, settings: live.LiveSettings, entries: list[dict],
                   status: str, track_title: str = "") -> None:
        self._loading = True
        try:
            self._settings = settings.copy()
            self.enabled.setChecked(settings.enabled)
            self.auto_show.setChecked(settings.auto_show)
            self.intensity.setValue(int(round(settings.intensity * 100)))
            self.crowd.setValue(int(round(settings.crowd * 100)))
            self.width.setValue(int(round(settings.width * 100)))
            for key, btn in self._venue_buttons.items():
                btn.setChecked(key == settings.venue)
            self._refresh_named()
        finally:
            self._loading = False

        self.subtitle.set_text(
            f"{live.venue_label(settings.venue)}  ・  会場の響きと観客の歓声を合成"
        )
        self.status.set_text(status)
        self.manual_label.setText(
            f"この曲の歓声 ({len(entries)})" if track_title else "この曲の歓声"
        )
        self.manual_list.clear()
        self._positions = []
        for i, entry in enumerate(entries):
            pos = int(entry.get("pos", 0) or 0)
            kind = str(entry.get("kind", "cheer"))
            gain = float(entry.get("gain", 0.8) or 0.8)
            label = live.EVENT_LABELS.get(kind, kind)
            item = QListWidgetItem(f"  {fmt_time(pos)}  {label}   音量 {int(gain * 100)}%")
            item.setData(Qt.ItemDataRole.UserRole, i)
            self.manual_list.addItem(item)
            self._positions.append(pos)

    def set_position(self, position_ms: int) -> None:
        self.position.set_text(
            f"現在位置 {fmt_time(position_ms)}（「現在位置に追加」はここに入ります）"
        )

    def settings(self) -> live.LiveSettings:
        return live.LiveSettings(
            enabled=self.enabled.isChecked(),
            venue=self._settings.venue,
            intensity=self.intensity.value() / 100.0,
            crowd=self.crowd.value() / 100.0,
            width=self.width.value() / 100.0,
            auto_show=self.auto_show.isChecked(),
        )

    # -- internals ------------------------------------------------------------
    def _pick_venue(self, key: str) -> None:
        self._settings.venue = key
        for k, btn in self._venue_buttons.items():
            btn.setChecked(k == key)
        self.subtitle.set_text(
            f"{live.venue_label(key)}  ・  プリディレイ / RT60 / 観客ノイズを合成"
        )
        self._on_change()

    def _on_change(self, *_) -> None:
        if self._loading:
            return
        self._refresh_named()
        self._settings = self.settings()
        self.settingsChanged.emit(self._settings)

    def _add_at_current(self) -> None:
        self.cheerAdded.emit(-1)      # -1 = "use the current position"

    def _remove_selected(self) -> None:
        item = self.manual_list.currentItem()
        if item is None:
            return
        index = item.data(Qt.ItemDataRole.UserRole)
        if isinstance(index, int):
            self.cheerRemoved.emit(index)

    def _on_item_activated(self, item: QListWidgetItem) -> None:
        index = item.data(Qt.ItemDataRole.UserRole)
        if isinstance(index, int) and 0 <= index < len(self._positions):
            self.seekRequested.emit(self._positions[index])
