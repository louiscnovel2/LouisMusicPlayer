"""Real-time audio DSP: a 10-band graphic equalizer plus bass boost.

The equalizer is implemented in the frequency domain (weighted overlap-add) so
it needs no extra native dependencies beyond numpy. It provides:

* 10 graphic bands (31 Hz .. 16 kHz)
* a low-shelf style "Bass Boost"
* a master pre-amp
* factory presets (rock / pop / jazz / live / vocal ...)

The :class:`Processor` runs inside the audio callback; changing the settings
from the UI thread only swaps a pre-computed response curve, which keeps the
audio thread lock-free.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

# -- bands --------------------------------------------------------------------

BANDS = [31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0]
BAND_LABELS = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"]

GAIN_MIN = -12.0
GAIN_MAX = 12.0
BASS_MAX = 15.0
PREAMP_MIN = -12.0
PREAMP_MAX = 6.0

FLAT = [0.0] * len(BANDS)

# Factory presets: gain (dB) for each of the 10 bands.
PRESETS: dict[str, list[float]] = {
    "flat": FLAT,
    "rock": [5.0, 4.0, -1.0, -3.0, -1.0, 2.0, 4.0, 5.0, 5.0, 5.0],
    "pop": [-1.0, 2.0, 4.0, 4.0, 2.0, 0.0, -1.0, -1.0, 1.0, 2.0],
    "jazz": [3.0, 2.0, 1.0, 2.0, -2.0, -2.0, 0.0, 1.0, 2.0, 3.0],
    "classical": [4.0, 3.0, 2.0, 0.0, 0.0, 0.0, -1.0, -1.0, -2.0, -3.0],
    "dance": [6.0, 5.0, 3.0, 0.0, 0.0, -3.0, -4.0, -4.0, 0.0, 0.0],
    "live": [-2.0, 0.0, 2.0, 3.0, 3.0, 3.0, 2.0, 1.0, 0.0, -1.0],
    "vocal": [-3.0, -2.0, 0.0, 3.0, 5.0, 5.0, 4.0, 2.0, 0.0, -1.0],
    "bass": [9.0, 7.0, 5.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    "treble": [0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 3.0, 5.0, 6.0, 7.0],
}

PRESET_LABELS: dict[str, str] = {
    "flat": "フラット",
    "rock": "ロック",
    "pop": "ポップ",
    "jazz": "ジャズ",
    "classical": "クラシック",
    "dance": "ダンス",
    "live": "ライブ",
    "vocal": "ボーカル強調",
    "bass": "低音強調",
    "treble": "高音強調",
}


@dataclass
class EqSettings:
    """User-tunable equalizer state."""

    gains: list[float] = field(default_factory=lambda: list(FLAT))
    bass: float = 0.0
    preamp: float = 0.0
    preset: str = "flat"
    normalize: bool = True
    enabled: bool = True

    def copy(self) -> "EqSettings":
        return EqSettings(
            gains=list(self.gains),
            bass=self.bass,
            preamp=self.preamp,
            preset=self.preset,
            normalize=self.normalize,
            enabled=self.enabled,
        )

    def to_dict(self) -> dict:
        return {
            "gains": list(self.gains),
            "bass": self.bass,
            "preamp": self.preamp,
            "preset": self.preset,
            "normalize": self.normalize,
            "enabled": self.enabled,
        }

    @staticmethod
    def from_dict(data: dict | None) -> "EqSettings":
        s = EqSettings()
        if not isinstance(data, dict):
            return s
        gains = data.get("gains")
        if isinstance(gains, (list, tuple)) and len(gains) == len(BANDS):
            s.gains = [float(x) for x in gains]
        s.bass = float(data.get("bass", s.bass))
        s.preamp = float(data.get("preamp", s.preamp))
        s.preset = str(data.get("preset", s.preset))
        s.normalize = bool(data.get("normalize", s.normalize))
        s.enabled = bool(data.get("enabled", s.enabled))
        return s

    def is_flat(self) -> bool:
        return (
            not self.enabled
            or (
                all(abs(g) < 0.05 for g in self.gains)
                and abs(self.bass) < 0.05
                and abs(self.preamp) < 0.05
            )
        )


def apply_preset(settings: EqSettings, name: str) -> EqSettings:
    gains = PRESETS.get(name)
    if gains is not None:
        settings.gains = list(gains)
        settings.preset = name
    return settings


# -- response curve -----------------------------------------------------------

def build_response(settings: EqSettings, n_fft: int, rate: int) -> np.ndarray:
    """Magnitude response (linear) for every rfft bin."""
    freqs = np.fft.rfftfreq(n_fft, 1.0 / rate)
    if settings.is_flat():
        return np.ones(freqs.shape[0], dtype=np.float32)

    f = np.maximum(freqs, 1e-6)
    lf = np.log2(f)
    interp = np.interp(lf, np.log2(np.asarray(BANDS)), np.asarray(settings.gains, dtype=float))

    if abs(settings.bass) > 0.01:
        # Low-shelf weight: full below 80 Hz, fading out by 400 Hz.
        w = (np.log10(400.0) - np.log10(f)) / (np.log10(400.0) - np.log10(80.0))
        interp = interp + settings.bass * np.clip(w, 0.0, 1.0)

    interp = interp + settings.preamp
    return np.power(10.0, interp / 20.0).astype(np.float32)


# -- processing ---------------------------------------------------------------

class Processor:
    """Weighted overlap-add frequency-domain equalizer.

    ``process`` consumes float32 blocks of shape ``(frames, channels)`` and
    returns blocks of the same shape. Settings can be swapped at any time from
    another thread.
    """

    def __init__(self, rate: int = 48000, channels: int = 2, n_fft: int = 2048) -> None:
        self._rate = int(rate)
        self._ch = int(channels)
        self._n = int(n_fft)
        self._hop = self._n // 2
        self._settings = EqSettings()
        self._window = self._make_window()
        self._response = np.ones(self._n // 2 + 1, dtype=np.float32)
        self._in = np.zeros((self._n, self._ch), dtype=np.float32)
        self._accum = np.zeros((self._n, self._ch), dtype=np.float32)
        self.rebuild()

    # -- configuration --------------------------------------------------------
    def _make_window(self) -> np.ndarray:
        win = np.sqrt(np.hanning(self._n + 1)[:-1]).astype(np.float32)
        return win[:, None]

    @property
    def settings(self) -> EqSettings:
        return self._settings

    def set_settings(self, settings: EqSettings) -> None:
        self._settings = settings.copy()
        self.rebuild()

    def rebuild(self) -> None:
        resp = build_response(self._settings, self._n, self._rate)
        self._response = resp[:, None]

    def set_rate(self, rate: int, channels: int | None = None) -> None:
        rate = int(rate)
        ch = int(channels) if channels else self._ch
        if rate == self._rate and ch == self._ch:
            return
        self._rate = rate
        self._ch = ch
        self._in = np.zeros((self._n, ch), dtype=np.float32)
        self._accum = np.zeros((self._n, ch), dtype=np.float32)
        self.rebuild()

    def reset(self) -> None:
        self._in.fill(0.0)
        self._accum.fill(0.0)

    # -- processing -----------------------------------------------------------
    def process(self, block: np.ndarray) -> np.ndarray:
        if self._settings.is_flat():
            self.reset()
            return block
        if block.shape[1] != self._ch:
            self.set_rate(self._rate, block.shape[1])

        self._in = np.concatenate([self._in, block], axis=0)
        out_chunks = []
        while self._in.shape[0] >= self._n:
            seg = self._in[: self._n]
            spec = np.fft.rfft(seg * self._window, axis=0)
            spec *= self._response
            y = np.fft.irfft(spec, n=self._n, axis=0).astype(np.float32) * self._window
            self._accum += y
            out_chunks.append(self._accum[: self._hop].copy())
            self._accum = np.concatenate(
                [self._accum[self._hop:], np.zeros((self._hop, self._ch), np.float32)],
                axis=0,
            )
            self._in = self._in[self._hop:]

        if out_chunks:
            produced = np.concatenate(out_chunks, axis=0)
            return produced[: block.shape[0]]
        return np.zeros((block.shape[0], self._ch), dtype=np.float32)


# -- loudness normalisation ---------------------------------------------------

def rms_db(samples: np.ndarray) -> float:
    """RMS level in dBFS for a mono / multi-channel float block."""
    if samples.size == 0:
        return -90.0
    rms = float(np.sqrt(np.mean(np.square(samples, dtype=np.float64))))
    if rms < 1e-6:
        return -90.0
    return 20.0 * float(np.log10(rms))


def normalization_gain_db(track_rms_db: float, target_db: float = -18.0,
                          max_boost: float = 9.0, max_cut: float = 15.0) -> float:
    """Gain (dB) that moves a track towards the target loudness."""
    gain = target_db - track_rms_db
    return max(-max_cut, min(max_boost, gain))
