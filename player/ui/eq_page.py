"""Equalizer / sound-shaping page (10-band EQ, bass boost, normaliser)."""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import (
    QCheckBox,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSlider,
    QVBoxLayout,
    QWidget,
)

from .. import dsp, theme
from .screens import ElidedLabel, _GlyphButton

_SLIDER_QSS = """
QSlider::groove:vertical {
    background: rgba(255,255,255,28);
    width: 3px;
    border-radius: 1px;
}
QSlider::sub-page:vertical { background: rgba(255,255,255,28); }
QSlider::add-page:vertical { background: rgba(255,255,255,28); }
QSlider::handle:vertical {
    background: #EDEDED;
    border: 1px solid #9C8968;
    height: 12px;
    width: 12px;
    margin: 0 -5px;
    border-radius: 2px;
}
QSlider::groove:horizontal {
    background: rgba(255,255,255,28);
    height: 3px;
    border-radius: 1px;
}
QSlider::sub-page:horizontal { background: #CBAD77; }
QSlider::add-page:horizontal { background: rgba(255,255,255,28); }
QSlider::handle:horizontal {
    background: #EDEDED;
    border: 1px solid #9C8968;
    width: 12px;
    height: 12px;
    margin: -5px 0;
    border-radius: 2px;
}
"""

_BTN_QSS = """
QPushButton {
    background: rgba(255,255,255,16);
    color: #E9E9EC;
    border: 1px solid rgba(255,255,255,40);
    border-radius: 3px;
    padding: 4px 6px;
}
QPushButton:hover { background: rgba(255,255,255,34); }
QPushButton:checked { background: rgba(203,173,119,70); border-color: #CBAD77; color: #FFFFFF; }
"""


class EqPage(QWidget):
    settingsChanged = Signal(object)        # dsp.EqSettings
    presetSaved = Signal(str, object)       # name, list[float]
    backClicked = Signal()

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._loading = False
        self._custom_presets: dict[str, list[float]] = {}

        root = QVBoxLayout(self)
        root.setContentsMargins(0, 8, 0, 6)
        root.setSpacing(4)

        # -- header
        header = QHBoxLayout()
        header.setContentsMargins(6, 0, 14, 0)
        self.back_btn = _GlyphButton("back", 26, self)
        self.back_btn.clicked.connect(self.backClicked)
        self.title = ElidedLabel("イコライザー", 11, QFont.Weight.Medium,
                                 theme.TEXT_PRIMARY, Qt.AlignmentFlag.AlignVCenter, self)
        header.addWidget(self.back_btn)
        header.addWidget(self.title, 1)
        root.addLayout(header)

        self.subtitle = ElidedLabel("", 8, color=theme.TEXT_MUTED,
                                    align=Qt.AlignmentFlag.AlignHCenter, parent=self)
        root.addWidget(self.subtitle)

        # -- presets
        self._preset_grid = QGridLayout()
        self._preset_grid.setContentsMargins(10, 2, 10, 2)
        self._preset_grid.setSpacing(4)
        root.addLayout(self._preset_grid)

        # -- band sliders
        bands = QHBoxLayout()
        bands.setContentsMargins(10, 6, 10, 0)
        bands.setSpacing(2)
        self._sliders: list[QSlider] = []
        self._values: list[QLabel] = []
        for freq_label in dsp.BAND_LABELS:
            col = QVBoxLayout()
            col.setSpacing(1)
            value = QLabel("0.0")
            value.setAlignment(Qt.AlignmentFlag.AlignHCenter)
            value.setFont(theme.font(7))
            value.setStyleSheet(f"color:{theme.TEXT_SECONDARY.name()};")
            value.setFixedHeight(12)
            slider = QSlider(Qt.Orientation.Vertical)
            slider.setRange(-120, 120)
            slider.setValue(0)
            slider.setStyleSheet(_SLIDER_QSS)
            slider.setMinimumHeight(150)
            slider.valueChanged.connect(self._on_slider)
            name = QLabel(freq_label)
            name.setAlignment(Qt.AlignmentFlag.AlignHCenter)
            name.setFont(theme.font(7))
            name.setStyleSheet(f"color:{theme.TEXT_MUTED.name()};")
            col.addWidget(value)
            col.addWidget(slider, 1)
            col.addWidget(name)
            bands.addLayout(col)
            self._sliders.append(slider)
            self._values.append(value)
        root.addLayout(bands, 1)

        # -- bass / preamp
        self.bass = self._named_slider(root, "Bass Boost", 0, 150, "{:+.1f} dB")
        self.preamp = self._named_slider(root, "プリセット音量", -120, 60, "{:+.1f} dB")

        # -- switches
        self.enabled = QCheckBox("イコライザーを有効にする")
        self.enabled.setFont(theme.font(9))
        self.enabled.setStyleSheet(f"color:{theme.TEXT_SECONDARY.name()};")
        self.enabled.toggled.connect(self._on_slider)
        root.addWidget(self.enabled)

        self.normalize = QCheckBox("音量を自動調整（ダイナミックノーマライザー）")
        self.normalize.setFont(theme.font(9))
        self.normalize.setStyleSheet(f"color:{theme.TEXT_SECONDARY.name()};")
        self.normalize.toggled.connect(self._on_slider)
        root.addWidget(self.normalize)

        # -- actions
        actions = QHBoxLayout()
        actions.setContentsMargins(10, 2, 10, 2)
        actions.setSpacing(6)
        flat = QPushButton("フラットに戻す")
        flat.setStyleSheet(_BTN_QSS)
        flat.setFont(theme.font(9))
        flat.clicked.connect(self._reset_flat)
        save = QPushButton("カスタム設定を保存")
        save.setStyleSheet(_BTN_QSS)
        save.setFont(theme.font(9))
        save.clicked.connect(self._save_preset)
        actions.addWidget(flat)
        actions.addWidget(save)
        root.addLayout(actions)

    # -- construction helpers -------------------------------------------------
    def _named_slider(self, root: QVBoxLayout, label: str, lo: int, hi: int,
                      fmt: str) -> QSlider:
        row = QHBoxLayout()
        row.setContentsMargins(10, 0, 10, 0)
        row.setSpacing(6)
        name = QLabel(label)
        name.setFont(theme.font(9))
        name.setStyleSheet(f"color:{theme.TEXT_SECONDARY.name()};")
        name.setMinimumWidth(90)
        slider = QSlider(Qt.Orientation.Horizontal)
        slider.setRange(lo, hi)
        slider.setValue(0)
        slider.setStyleSheet(_SLIDER_QSS)
        value = QLabel(fmt.format(0.0))
        value.setFont(theme.font(8))
        value.setStyleSheet(f"color:{theme.TEXT_MUTED.name()};")
        value.setMinimumWidth(56)
        value.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        slider.setProperty("fmt", fmt)
        slider.setProperty("value_label", value)
        slider.valueChanged.connect(self._on_slider)
        row.addWidget(name)
        row.addWidget(slider, 1)
        row.addWidget(value)
        root.addLayout(row)
        return slider

    def _refresh_named(self, slider: QSlider) -> None:
        fmt = slider.property("fmt")
        label = slider.property("value_label")
        if fmt and label is not None:
            label.setText(fmt.format(slider.value() / 10.0))

    # -- public ---------------------------------------------------------------
    def set_presets(self, custom: dict[str, list[float]]) -> None:
        self._custom_presets = dict(custom)
        while self._preset_grid.count():
            item = self._preset_grid.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()

        entries: list[tuple[str, list[float]]] = [
            (dsp.PRESET_LABELS[k], dsp.PRESETS[k]) for k in dsp.PRESETS
        ]
        entries += [(name, gains) for name, gains in self._custom_presets.items()]

        columns = 4
        for i, (label, gains) in enumerate(entries):
            btn = QPushButton(label)
            btn.setStyleSheet(_BTN_QSS)
            btn.setFont(theme.font(8))
            btn.clicked.connect(lambda _=False, g=gains, n=label: self._apply_gains(g, n))
            self._preset_grid.addWidget(btn, i // columns, i % columns)

    def load_settings(self, settings: dsp.EqSettings) -> None:
        self._loading = True
        try:
            for slider, gain in zip(self._sliders, settings.gains):
                slider.setValue(int(round(gain * 10)))
            self.bass.setValue(int(round(settings.bass * 10)))
            self.preamp.setValue(int(round(settings.preamp * 10)))
            self.enabled.setChecked(settings.enabled)
            self.normalize.setChecked(settings.normalize)
            self._update_labels()
        finally:
            self._loading = False
        self._update_subtitle(settings.preset)

    def settings(self) -> dsp.EqSettings:
        return dsp.EqSettings(
            gains=[s.value() / 10.0 for s in self._sliders],
            bass=self.bass.value() / 10.0,
            preamp=self.preamp.value() / 10.0,
            preset=self._current_preset,
            normalize=self.normalize.isChecked(),
            enabled=self.enabled.isChecked(),
        )

    # -- internals ------------------------------------------------------------
    _current_preset = "flat"

    def _update_labels(self) -> None:
        for slider, label in zip(self._sliders, self._values):
            label.setText(f"{slider.value() / 10.0:+.1f}")
        self._refresh_named(self.bass)
        self._refresh_named(self.preamp)

    def _update_subtitle(self, preset: str) -> None:
        label = dsp.PRESET_LABELS.get(preset, preset)
        self.subtitle.set_text(
            f"プリセット: {label}  ・  10バンド EQ / Bass Boost / ノーマライザー"
        )

    def _apply_gains(self, gains: list[float], preset: str) -> None:
        self._loading = True
        try:
            for slider, gain in zip(self._sliders, gains):
                slider.setValue(int(round(gain * 10)))
            self._current_preset = preset
            self._update_labels()
        finally:
            self._loading = False
        self._update_subtitle(preset)
        self._emit()

    def _on_slider(self, *_) -> None:
        if self._loading:
            return
        self._current_preset = "custom"
        self._update_labels()
        self._update_subtitle("custom")
        self._emit()

    def _emit(self) -> None:
        self.settingsChanged.emit(self.settings())

    def _reset_flat(self) -> None:
        self.bass.setValue(0)
        self.preamp.setValue(0)
        self._apply_gains(list(dsp.FLAT), "flat")

    def _save_preset(self) -> None:
        from PySide6.QtWidgets import QInputDialog

        name, ok = QInputDialog.getText(self, "カスタム設定を保存", "プリセット名:")
        name = (name or "").strip()
        if not ok or not name:
            return
        gains = [s.value() / 10.0 for s in self._sliders]
        self.presetSaved.emit(name, gains)
        self._current_preset = name
        self._update_subtitle(name)
