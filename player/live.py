"""Live Effect: venue acoustics + crowd reactions.

The effect makes a studio recording sound like a live show:

* **Venue reverb** – a synthesised impulse response (velvet noise shaped by an
  exponential decay + early reflections) is convolved with the music using a
  *uniform partitioned FFT convolver*, so no per-sample Python loop is needed.
* **Crowd** – applause / cheers / a low ambient murmur are rendered offline as
  stereo textures and mixed on top at the positions the song "asks" for.
* **3D** – the reverb is decorrelated between the channels and every crowd
  voice is panned inside the stereo field; ``width`` scales the spread.

Crowd reactions use the free OtoLogic applause / cheer recordings (CC BY 4.0,
https://otologic.jp/). Venue reverb, the ambient bed and the riser are still
generated inside the app. The realtime part only does numpy work on
``(frames, 2)`` blocks, mirroring the equalizer so it can run inside the
sounddevice callback.
"""

from __future__ import annotations

import hashlib
import math
import os
import threading
import wave
from dataclasses import dataclass

import numpy as np

from .resources import resource_path
from .store import config_dir

IR_VERSION = 4
TEXTURE_VERSION = 5
SAMPLE_VERSION = 1

# Impulse responses are cached on disk per venue / sample rate.
_CACHE_DIRNAME = "live"

# -- venues -------------------------------------------------------------------

VENUES: dict[str, dict] = {
    "arena": dict(
        label="アリーナ", pre_delay=0.012, rt60=1.6, damping=0.42,
        wet=0.30, size=1.0, crowd=0.55,
    ),
    "dome": dict(
        label="ドーム", pre_delay=0.026, rt60=2.7, damping=0.28,
        wet=0.36, size=1.4, crowd=0.68,
    ),
    "hall": dict(
        label="コンサートホール", pre_delay=0.019, rt60=2.1, damping=0.58,
        wet=0.30, size=1.2, crowd=0.30,
    ),
    "livehouse": dict(
        label="ライブハウス", pre_delay=0.006, rt60=0.9, damping=0.34,
        wet=0.34, size=0.7, crowd=0.78,
    ),
    "festival": dict(
        label="野外フェス", pre_delay=0.030, rt60=1.8, damping=0.24,
        wet=0.26, size=1.6, crowd=0.88,
    ),
    "stadium": dict(
        label="スタジアム", pre_delay=0.042, rt60=3.0, damping=0.20,
        wet=0.34, size=1.8, crowd=1.0,
    ),
}
VENUE_ORDER = ["arena", "livehouse", "hall", "dome", "festival", "stadium"]

EVENT_KINDS = ["applause", "cheer", "big_cheer", "build"]
EVENT_LABELS = {
    "applause": "拍手",
    "cheer": "歓声",
    "big_cheer": "大歓声",
    "build": "盛り上げ",
}
# Base loudness of each reaction (scaled again by intensity / crowd).
EVENT_GAIN = {
    "applause": 0.55,
    "cheer": 0.72,
    "big_cheer": 0.95,
    "build": 0.45,
}
# How wide the reaction is placed in the stereo field (1 = hard pan allowed).
EVENT_PAN = {
    "applause": 0.8,
    "cheer": 0.45,
    "big_cheer": 0.0,
    "build": 0.0,
}

TEXTURE_KINDS = ["bed", "applause", "cheer", "big_cheer", "build"]

# Crowd reactions are real recordings from OtoLogic (CC BY 4.0). Each kind has
# one or more takes; the engine picks one at random for every reaction.
SAMPLE_FILES = {
    "applause": [
        "assets/otologic/applause_01.wav",
        "assets/otologic/applause_02.wav",
    ],
    "cheer": [
        "assets/otologic/cheer_01.wav",
        "assets/otologic/cheer_02.wav",
    ],
    "big_cheer": [
        "assets/otologic/big_cheer_01.wav",
    ],
}

# Approximate texture lengths (seconds) - used to keep the tail playing until
# the last reaction has finished ringing out.
EVENT_LENGTH = {
    "applause": 8.0,
    "cheer": 6.5,
    "big_cheer": 8.5,
    "build": 1.9,
}

# Post-roll after the last sample of a track: fade the crowd in, keep it going
# long enough to feel like a real audience, then fade it out before the next
# track starts. The big cheer sample is 8.5 s, so the fades leave ~4.5 s at full
# level (>= the 4-5 s the show should sound natural for).
OUTRO_FADE_IN = 1.2
OUTRO_FADE_OUT = 2.8
OUTRO_SECONDS = 8.7
OUTRO_PAD = 0.35


def venue_label(key: str) -> str:
    return VENUES.get(key, VENUES["arena"])["label"]


# -- settings -----------------------------------------------------------------


@dataclass
class LiveSettings:
    """User-tunable Live Effect state."""

    enabled: bool = False
    venue: str = "arena"
    intensity: float = 0.6      # reverb amount + reaction loudness
    crowd: float = 0.5          # crowd / applause volume
    width: float = 0.6          # stereo / 3D spread
    auto_show: bool = True      # automatic reactions from the analysis

    def copy(self) -> "LiveSettings":
        return LiveSettings(
            enabled=self.enabled,
            venue=self.venue,
            intensity=self.intensity,
            crowd=self.crowd,
            width=self.width,
            auto_show=self.auto_show,
        )

    def to_dict(self) -> dict:
        return {
            "enabled": self.enabled,
            "venue": self.venue,
            "intensity": self.intensity,
            "crowd": self.crowd,
            "width": self.width,
            "auto_show": self.auto_show,
        }

    @staticmethod
    def from_dict(data: dict | None) -> "LiveSettings":
        s = LiveSettings()
        if not isinstance(data, dict):
            return s
        s.enabled = bool(data.get("enabled", s.enabled))
        venue = str(data.get("venue", s.venue))
        s.venue = venue if venue in VENUES else "arena"
        s.intensity = _clamp01(data.get("intensity", s.intensity))
        s.crowd = _clamp01(data.get("crowd", s.crowd))
        s.width = _clamp01(data.get("width", s.width))
        s.auto_show = bool(data.get("auto_show", s.auto_show))
        return s

    def is_neutral(self) -> bool:
        return not self.enabled


def _clamp01(value) -> float:
    try:
        v = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(1.0, v))


@dataclass
class LiveEvent:
    """A single crowd reaction at ``time`` (seconds) in the current track."""

    time: float
    kind: str = "cheer"
    gain: float = 0.8
    source: str = "auto"        # "auto" (analysis) or "manual" (user)

    def to_dict(self) -> dict:
        return {"time": float(self.time), "kind": self.kind,
                "gain": float(self.gain), "source": self.source}

    @staticmethod
    def from_dict(data: dict) -> "LiveEvent | None":
        if not isinstance(data, dict):
            return None
        try:
            t = float(data.get("time", data.get("pos", 0)) or 0)
        except (TypeError, ValueError):
            return None
        kind = str(data.get("kind", "cheer"))
        if kind not in EVENT_KINDS:
            kind = "cheer"
        try:
            gain = float(data.get("gain", 0.8))
        except (TypeError, ValueError):
            gain = 0.8
        source = "manual" if str(data.get("source", "auto")) == "manual" else "auto"
        return LiveEvent(time=max(0.0, t), kind=kind,
                         gain=max(0.0, min(1.0, gain)), source=source)


# -- impulse response ---------------------------------------------------------


def cache_dir() -> str:
    path = os.path.join(config_dir(), _CACHE_DIRNAME)
    os.makedirs(path, exist_ok=True)
    return path


def _velvet(n_imp: int, length: int, rng) -> np.ndarray:
    """Velvet noise: one signed impulse per (random) slot."""
    out = np.zeros(length, dtype=np.float64)
    if n_imp <= 0 or length <= 0:
        return out
    n_imp = min(n_imp, length)
    step = length / n_imp
    pos = ((np.arange(n_imp) + rng.random(n_imp)) * step).astype(np.int64)
    np.clip(pos, 0, length - 1, out=pos)
    out[pos] += rng.choice(np.array([-1.0, 1.0]), n_imp)
    return out


def _damp(x: np.ndarray, rate: int, damping: float) -> np.ndarray:
    """One-pole low pass; more damping = darker tail (air absorption)."""
    fc = max(600.0, 6500.0 - 5200.0 * float(np.clip(damping, 0.0, 1.0)))
    a = math.exp(-2.0 * math.pi * fc / rate)
    k = int(min(4096, max(8, 6.0 * rate / (2.0 * math.pi * fc))))
    ker = (1.0 - a) * np.power(a, np.arange(k + 1))
    ker /= ker.sum()
    return np.convolve(x, ker)[: x.shape[0]].astype(np.float32)


def build_ir(rate: int, venue: str) -> np.ndarray:
    """Synthesise a stereo impulse response for *venue* at *rate*.

    Returns a ``(samples, 2)`` float32 array normalised to unit energy (so the
    wet level stays roughly constant between venues with different RT60).
    """
    cfg = VENUES.get(venue) or VENUES["arena"]
    rt60 = float(cfg["rt60"])
    pre = float(cfg["pre_delay"])
    decay = min(rt60 * 0.95, 3.2)
    body = int(decay * rate)
    pre_n = max(1, int(pre * rate))
    total = pre_n + body

    seed = int(hashlib.md5(f"{venue}:{IR_VERSION}".encode()).hexdigest()[:8], 16)
    rng = np.random.default_rng(seed)

    t = np.arange(body) / rate
    env = np.power(10.0, -3.0 * t / rt60).astype(np.float32)   # -60 dB @ rt60

    density = 14000.0
    n_imp = int(density * decay)
    chans: list[np.ndarray] = []
    for _ in range(2):
        noise = _velvet(n_imp, body, rng) * env
        chans.append(_damp(noise, rate, cfg["damping"]))

    ir = np.zeros((total, 2), dtype=np.float32)
    ir[pre_n:, 0] = chans[0]
    ir[pre_n:, 1] = chans[1]

    # discrete early reflections (a handful of taps right after the pre-delay)
    taps = rng.uniform(0.002, min(0.055, decay * 0.4), size=9)
    gains = np.power(10.0, -3.0 * taps / rt60) * rng.uniform(0.35, 0.75, size=9)
    for tap, gain in zip(taps, gains):
        idx = pre_n + int(tap * rate)
        if 0 <= idx < total:
            side = 1.0 if rng.random() < 0.5 else -1.0
            ir[idx, 0] += float(gain) * (0.5 + 0.5 * cfg["size"] * 0.4)
            ir[idx, 1] += float(gain) * (0.5 + 0.5 * cfg["size"] * 0.4) * (0.75 * side)

    energy = float(np.sqrt(np.sum(ir.astype(np.float64) ** 2)))
    if energy > 1e-9:
        ir /= energy
    return ir.astype(np.float32)


def _ir_cache_path(rate: int, venue: str) -> str:
    return os.path.join(cache_dir(), f"ir_{venue}_{rate}_{IR_VERSION}.npy")


def load_ir(rate: int, venue: str) -> np.ndarray:
    """Build (and cache) the venue IR for *rate*."""
    path = _ir_cache_path(rate, venue)
    try:
        ir = np.load(path)
        if ir.ndim == 2 and ir.shape[1] == 2:
            return ir.astype(np.float32)
    except (OSError, ValueError):
        pass
    ir = build_ir(rate, venue)
    try:
        np.save(path, ir)
    except OSError:
        pass
    return ir


# -- partitioned convolution --------------------------------------------------


class PartitionedConvolver:
    """Uniform partitioned overlap-save FFT convolution (numpy only)."""

    def __init__(self, ir: np.ndarray, block: int) -> None:
        self.hop = int(block)
        self.nfft = 2 * self.hop
        self.ch = int(ir.shape[1])
        n = int(ir.shape[0])
        nparts = max(1, int(math.ceil(n / self.hop)))
        padded = np.zeros((nparts * self.hop, self.ch), dtype=np.float32)
        padded[:n] = ir
        parts = padded.reshape(nparts, self.hop, self.ch)
        frame = np.concatenate([parts, np.zeros_like(parts)], axis=1)
        self.H = np.fft.rfft(frame.astype(np.float32), n=self.nfft, axis=1)
        self.nparts = nparts
        self.freqs = self.H.shape[1]
        self.reset()

    def reset(self) -> None:
        self._frames = np.zeros((self.nparts, self.freqs, self.ch), dtype=np.complex64)
        self._prev = np.zeros((self.hop, self.ch), dtype=np.float32)
        self._idx = 0
        self._in = np.zeros((0, self.ch), dtype=np.float32)
        self._out = np.zeros((0, self.ch), dtype=np.float32)

    def _frame(self, block: np.ndarray) -> np.ndarray:
        frame = np.concatenate([self._prev, block], axis=0)
        spec = np.fft.rfft(frame, n=self.nfft, axis=0).astype(np.complex64)
        self._frames[self._idx] = spec
        acc = np.zeros((self.freqs, self.ch), dtype=np.complex64)
        for p in range(self.nparts):
            j = self._idx - p
            if j < 0:
                j += self.nparts
            acc += self.H[p] * self._frames[j]
        y = np.fft.irfft(acc.astype(np.complex128), n=self.nfft, axis=0)
        self._prev = block
        self._idx = (self._idx + 1) % self.nparts
        return y[-self.hop:].astype(np.float32)

    def process(self, block: np.ndarray) -> np.ndarray:
        self._in = np.concatenate([self._in, block], axis=0)
        chunks: list[np.ndarray] = []
        while self._in.shape[0] >= self.hop:
            chunks.append(self._frame(self._in[: self.hop]))
            self._in = self._in[self.hop:]
        if chunks:
            self._out = np.concatenate([self._out, *chunks], axis=0)
        need = block.shape[0]
        if self._out.shape[0] >= need:
            out = self._out[:need]
            self._out = self._out[need:]
            return out
        pad = need - self._out.shape[0]
        out = np.concatenate([self._out, np.zeros((pad, self.ch), np.float32)], axis=0)
        self._out = np.zeros((0, self.ch), dtype=np.float32)
        return out


# -- crowd texture synthesis --------------------------------------------------


def _noise(n: int, rng) -> np.ndarray:
    return rng.standard_normal(n).astype(np.float32)


def _spectral(x: np.ndarray, rate: int, shape) -> np.ndarray:
    n = x.shape[0]
    spec = np.fft.rfft(x.astype(np.float64))
    freqs = np.fft.rfftfreq(n, 1.0 / rate)
    spec *= shape(freqs)
    return np.fft.irfft(spec, n=n).astype(np.float32)


def _peak_normalize(x: np.ndarray, peak: float = 0.9) -> np.ndarray:
    m = float(np.max(np.abs(x))) if x.size else 0.0
    if m < 1e-9:
        return x.astype(np.float32)
    return (x * (peak / m)).astype(np.float32)


def _attack_release(n: int, rate: int, attack: float, release: float,
                    hold: float = 0.0) -> np.ndarray:
    a = max(1, int(attack * rate))
    h = max(0, int(hold * rate))
    r = max(1, n - a - h)
    env = np.concatenate([
        np.linspace(0.0, 1.0, a, endpoint=False),
        np.ones(h, dtype=np.float64),
        np.power(np.linspace(1.0, 0.0, r), 1.4),
    ])
    if env.shape[0] > n:
        env = env[:n]
    elif env.shape[0] < n:
        env = np.concatenate([env, np.zeros(n - env.shape[0])])
    return env.astype(np.float32)


def _fade_envelope(n: int, rate: int, fade_in: float, fade_out: float) -> np.ndarray:
    """Smooth equal-power fade in / fade out for the post-roll crowd."""
    env = np.ones(max(0, int(n)), dtype=np.float32)
    if env.size == 0:
        return env
    a = min(env.size, max(0, int(fade_in * rate)))
    if a > 1:
        env[:a] = np.sin(np.linspace(0.0, np.pi / 2.0, a, dtype=np.float32))
    r = min(env.size - a, max(0, int(fade_out * rate)))
    if r > 1:
        env[-r:] = np.cos(np.linspace(0.0, np.pi / 2.0, r, dtype=np.float32))
    return env


def _band_mask(freqs: np.ndarray, lo: float, hi: float, slope: float = 2.0) -> np.ndarray:
    lo_w = 1.0 / (1.0 + (lo / np.maximum(freqs, 1e-6)) ** slope)
    hi_w = 1.0 / (1.0 + (freqs / hi) ** slope)
    return lo_w * hi_w


def _claps(n: int, rate: int, rng, count: int, gain: float = 0.7) -> np.ndarray:
    env = np.zeros(n, dtype=np.float32)
    for _ in range(count):
        ln = int(rng.uniform(0.004, 0.018) * rate)
        if ln + 1 >= n:
            continue
        p = int(rng.integers(0, n - ln))
        amp = float(rng.uniform(0.35, 1.0)) * gain
        env[p:p + ln] += amp * np.exp(-np.arange(ln) / (0.0035 * rate)).astype(np.float32)
    hp = _spectral(_noise(n, rng), rate,
                   lambda f: (f / 1100.0) ** 2 / (1.0 + (f / 1100.0) ** 2))
    return hp * env


def _decode_wav(path: str) -> tuple[np.ndarray, int]:
    """Read a PCM WAV file as ``(samples, 2)`` float32 plus its sample rate."""
    with wave.open(path, "rb") as src:
        channels = int(src.getnchannels())
        width = int(src.getsampwidth())
        rate = int(src.getframerate())
        raw = src.readframes(src.getnframes())
    if width == 2:
        data = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif width == 1:
        data = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif width == 3:
        buf = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
        ints = (buf[:, 0].astype(np.int32)
                | (buf[:, 1].astype(np.int32) << 8)
                | (buf[:, 2].astype(np.int32) << 16))
        ints = np.where(ints & 0x800000, ints - 0x1000000, ints)
        data = ints.astype(np.float32) / 8388608.0
    elif width == 4:
        data = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    else:
        raise ValueError(f"unsupported WAV sample width: {width}")

    if channels <= 0:
        raise ValueError("WAV has no channels")
    data = data.reshape(-1, channels)
    if channels == 1:
        data = np.repeat(data, 2, axis=1)
    elif channels > 2:
        data = data[:, :2]
    return data.astype(np.float32), rate


def _resample(x: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    if src_rate == dst_rate or x.shape[0] == 0:
        return x.astype(np.float32)
    n_out = max(1, int(round(x.shape[0] * dst_rate / src_rate)))
    src_pos = np.arange(n_out, dtype=np.float64) * (src_rate / dst_rate)
    idx = np.arange(x.shape[0], dtype=np.float64)
    out = np.empty((n_out, x.shape[1]), dtype=np.float32)
    for c in range(x.shape[1]):
        out[:, c] = np.interp(src_pos, idx, x[:, c])
    return out


def _standardize(x: np.ndarray) -> np.ndarray:
    """Match the perceived level of all OtoLogic takes without clipping."""
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    rms = float(np.sqrt(np.mean(x.astype(np.float64) ** 2))) if x.size else 0.0
    if peak < 1e-9 or rms < 1e-9:
        return x.astype(np.float32)
    gain = min(0.12 / rms, 0.98 / peak)
    return (x * gain).astype(np.float32)


def build_sample(rel: str, rate: int) -> np.ndarray:
    """Load one OtoLogic WAV and resample / normalise it for the engine."""
    data, src_rate = _decode_wav(resource_path(rel))
    return _standardize(_resample(data, src_rate, rate))


def build_texture(kind: str, rate: int) -> np.ndarray:
    """Synthesise one stereo crowd texture (``(samples, 2)`` float32)."""
    seed = int(hashlib.md5(f"{kind}:{TEXTURE_VERSION}".encode()).hexdigest()[:8], 16)
    rng = np.random.default_rng(seed)

    if kind == "bed":
        n = int(11.0 * rate)
        chans = []
        for _ in range(2):
            x = _spectral(_noise(n, rng), rate,
                          lambda f: _band_mask(f, 90.0, 900.0, 1.6))
            slow = 0.65 + 0.35 * np.sin(2.0 * np.pi * 0.11 * np.arange(n) / rate
                                        + rng.uniform(0.0, 6.28))
            med = 0.8 + 0.2 * np.sin(2.0 * np.pi * 0.37 * np.arange(n) / rate
                                    + rng.uniform(0.0, 6.28))
            chans.append(_peak_normalize(x * (slow * med).astype(np.float32), 0.75))
        return _seamless(np.stack(chans, axis=1), rate, 0.35)

    if kind == "applause":
        n = int(5.0 * rate)
        env = _attack_release(n, rate, 0.12, 4.4, hold=0.2)
        chans = []
        for _ in range(2):
            base = _spectral(_noise(n, rng), rate,
                             lambda f: _band_mask(f, 700.0, 5200.0, 1.4))
            base = base * env
            clar = _claps(n, rate, rng, 420)
            chans.append(_peak_normalize(base + clar, 0.85))
        return np.stack(chans, axis=1).astype(np.float32)

    if kind == "cheer":
        n = int(4.6 * rate)
        env = _attack_release(n, rate, 0.45, 2.8, hold=0.35)
        chans = []
        for _ in range(2):
            x = _spectral(_noise(n, rng), rate, _voice_shape)
            x = x * env
            x += _claps(n, rate, rng, 90, gain=0.35)
            chans.append(_peak_normalize(x, 0.85))
        return np.stack(chans, axis=1).astype(np.float32)

    if kind == "big_cheer":
        n = int(5.5 * rate)
        env = _attack_release(n, rate, 0.9, 4.2, hold=0.4)
        roar_env = _attack_release(n, rate, 1.3, 4.3, hold=0.5)
        chans = []
        for _ in range(2):
            x = _spectral(_noise(n, rng), rate, _voice_shape) * env
            roar = _spectral(_noise(n, rng), rate,
                             lambda f: _band_mask(f, 70.0, 420.0, 1.4)) * roar_env * 0.7
            x = x + roar
            x += _claps(n, rate, rng, 260, gain=0.5)
            chans.append(_peak_normalize(x, 0.9))
        tex = np.stack(chans, axis=1).astype(np.float32)
        # extra width: mix in the channels of an independent take
        return tex

    # "build": rising filtered noise, used right before a chorus
    n = int(1.9 * rate)
    edges = [180.0, 360.0, 720.0, 1440.0, 2880.0, 5760.0, 11000.0]
    chans = []
    for _ in range(2):
        base = _noise(n, rng)
        out = np.zeros(n, dtype=np.float32)
        spec = np.fft.rfft(base.astype(np.float64))
        freqs = np.fft.rfftfreq(n, 1.0 / rate)
        for i in range(len(edges) - 1):
            band = np.fft.irfft(spec * _band_mask(freqs, edges[i], edges[i + 1], 3.0),
                                n=n).astype(np.float32)
            start = i / len(edges) * 0.55
            curve = np.clip((np.arange(n) / rate - start) / 0.6, 0.0, 1.0)
            out += band * curve.astype(np.float32)
        out *= _attack_release(n, rate, 0.25, 1.4, hold=0.1)
        chans.append(_peak_normalize(out, 0.85))
    return np.stack(chans, axis=1).astype(np.float32)


def _voice_shape(freqs: np.ndarray) -> np.ndarray:
    """Vowel-ish formant envelope for crowd voices."""
    peaks = [(620.0, 1.0, 260.0), (1150.0, 0.8, 340.0),
             (2050.0, 0.55, 520.0), (3200.0, 0.3, 900.0)]
    out = np.zeros_like(freqs)
    for centre, gain, width in peaks:
        out += gain * np.exp(-0.5 * ((freqs - centre) / width) ** 2)
    out += 0.12 * _band_mask(freqs, 120.0, 6000.0, 1.2)
    return out


def _seamless(x: np.ndarray, rate: int, fade: float) -> np.ndarray:
    """Cross-fade the tail into the head so the loop has no click."""
    m = int(fade * rate)
    if m <= 0 or x.shape[0] <= 2 * m:
        return x
    head = x[:m].copy()
    tail = x[-m:].copy()
    ramp = np.linspace(0.0, 1.0, m, dtype=np.float32)[:, None]
    x[:m] = head * ramp + tail * (1.0 - ramp)
    return x[: x.shape[0] - m]


# -- caching ------------------------------------------------------------------


def _texture_path(kind: str, rate: int) -> str:
    return os.path.join(cache_dir(), f"crowd_{kind}_{rate}_{TEXTURE_VERSION}.npy")


def load_texture(kind: str, rate: int) -> np.ndarray:
    path = _texture_path(kind, rate)
    try:
        data = np.load(path)
        if data.ndim == 2 and data.shape[1] == 2:
            return data.astype(np.float32)
    except (OSError, ValueError):
        pass
    data = build_texture(kind, rate)
    try:
        np.save(path, data)
    except OSError:
        pass
    return data


def _sample_cache_path(rel: str, rate: int) -> str:
    stem = os.path.splitext(os.path.basename(rel))[0]
    return os.path.join(cache_dir(), f"sample_{stem}_{rate}_{SAMPLE_VERSION}.npy")


def load_sample(rel: str, rate: int) -> np.ndarray:
    path = _sample_cache_path(rel, rate)
    try:
        data = np.load(path)
        if data.ndim == 2 and data.shape[1] == 2:
            return data.astype(np.float32)
    except (OSError, ValueError):
        pass
    data = build_sample(rel, rate)
    try:
        np.save(path, data)
    except OSError:
        pass
    return data


def load_texture_set(kind: str, rate: int) -> list[np.ndarray]:
    """All takes for one crowd kind (sample based, with synth fallback)."""
    files = SAMPLE_FILES.get(kind)
    if files:
        try:
            return [load_sample(rel, rate) for rel in files]
        except (OSError, ValueError, wave.Error):
            pass
    return [load_texture(kind, rate)]


# -- realtime engine ----------------------------------------------------------


@dataclass
class _State:
    gen: int
    enabled: bool
    venue: str
    wet: float
    width: float
    crowd: float
    intensity: float
    events: tuple[LiveEvent, ...]
    duration: float
    outro_start: float
    tail_end: float
    bed_gain: float
    outro: bool


class _Voice:
    __slots__ = ("data", "pos", "n", "gain", "gl", "gr", "offset", "loop", "env")

    def __init__(self, data: np.ndarray, gain: float, pan: float, offset: int = 0,
                 loop: bool = False, env: np.ndarray | None = None) -> None:
        self.data = data
        self.pos = 0
        self.n = data.shape[0]
        self.gain = gain
        theta = (float(np.clip(pan, -1.0, 1.0)) + 1.0) * (math.pi / 4.0)
        self.gl = math.cos(theta)
        self.gr = math.sin(theta)
        self.offset = max(0, int(offset))
        self.loop = loop
        self.env = env


class LiveEngine:
    """Applies the venue reverb + crowd reactions to ``(frames, 2)`` blocks."""

    def __init__(self, rate: int = 48000, channels: int = 2, hop: int = 1024) -> None:
        self._rate = int(rate)
        self._ch = int(channels)
        self._hop = int(hop)
        self._settings = LiveSettings()
        self._state: _State | None = None
        self._outro_at_ms = 0
        self._gen = 0
        self._counter = 0
        self._conv: PartitionedConvolver | None = None
        self._textures: dict[str, list[np.ndarray]] = {}
        self._rt: dict = {}
        self._prepare_token = 0
        self._preparing = False
        self._pending_idx = 0
        self._voices: list[_Voice] = []
        self._bed: _Voice | None = None
        self._outro_spawned = False

    # -- configuration --------------------------------------------------------
    @property
    def settings(self) -> LiveSettings:
        return self._settings

    @property
    def active(self) -> bool:
        st = self._state
        return bool(st is not None and st.enabled and self._conv is not None)

    @property
    def ready(self) -> bool:
        return self._conv is not None

    def set_rate(self, rate: int, channels: int | None = None) -> None:
        rate = int(rate)
        ch = int(channels) if channels else self._ch
        if rate == self._rate and ch == self._ch:
            return
        self._rate = rate
        self._ch = ch
        self._conv = None
        self._textures = {}
        self._schedule_prepare()

    def set_settings(self, settings: LiveSettings) -> None:
        self._settings = settings.copy()
        self._compile()
        if settings.enabled:
            self._schedule_prepare()

    def compile_track(self, duration_ms: int, events: list[LiveEvent],
                      outro_at_ms: int | None = None) -> None:
        """Set the reactions and the end-of-song crowd start."""
        self._events = sorted(events, key=lambda e: e.time)
        self._duration_ms = max(0, int(duration_ms))
        if outro_at_ms is None:
            self._outro_at_ms = self._duration_ms
        else:
            self._outro_at_ms = max(0, min(int(outro_at_ms), self._duration_ms))
        self._compile()

    def reset(self, t: float = 0.0) -> None:
        """Drop the runtime state after a seek / track change."""
        self._voices = []
        self._pending_idx = 0
        if self._conv is not None:
            self._conv.reset()
        if self._bed is not None:
            self._bed.pos = 0
            self._voices = [self._bed]

    def ringing(self, t: float) -> bool:
        st = self._state
        if st is None or not st.enabled or self._conv is None:
            return False
        if st.duration <= 0:
            return False
        return float(t) < st.tail_end

    # -- preparation ----------------------------------------------------------
    def _schedule_prepare(self) -> None:
        if self._preparing:
            return
        self._preparing = True
        self._prepare_token += 1
        token = self._prepare_token
        rate, venue = self._rate, self._settings.venue

        def worker() -> None:
            try:
                ir = load_ir(rate, venue)
                conv = PartitionedConvolver(ir, self._hop)
                textures = {k: load_texture_set(k, rate) for k in TEXTURE_KINDS}
            except Exception:
                self._preparing = False
                return
            if token == self._prepare_token:
                self._conv = conv
                self._textures = textures
                self._gen = -1          # force a runtime reset
            self._preparing = False

        threading.Thread(target=worker, daemon=True).start()

    # -- state ----------------------------------------------------------------
    def _compile(self) -> None:
        s = self._settings
        cfg = VENUES.get(s.venue) or VENUES["arena"]
        events = tuple(getattr(self, "_events", ()))
        duration = getattr(self, "_duration_ms", 0) / 1000.0
        intensity = _clamp01(s.intensity)
        crowd = _clamp01(s.crowd)
        wet = float(cfg["wet"]) * (0.35 + 1.15 * intensity)
        wet = min(0.65, wet)
        outro_at = self._outro_at_ms / 1000.0
        if duration > 0 and duration - outro_at <= 0.75:
            outro_at = duration
        outro_at = max(0.0, min(outro_at, duration))
        outro = bool(s.enabled and duration > 0 and crowd > 0.001)
        if outro:
            # The end of the song is covered by the post-roll instead of a
            # reaction placed a fraction of a second before the last sample
            # (which used to be cut off when the next track started).
            cutoff = outro_at - 0.9
            events = tuple(
                ev for ev in events
                if not (ev.source == "auto" and ev.time >= cutoff)
            )
        tail = min(2.5, float(cfg["rt60"])) + 0.5
        tail_end = duration + tail if duration > 0 else 0.0
        for ev in events:
            end = ev.time + EVENT_LENGTH.get(ev.kind, 4.0) + 0.4
            if end > tail_end:
                tail_end = end
        if outro:
            tail_end = max(tail_end, outro_at + OUTRO_SECONDS + OUTRO_PAD)
        if duration > 0:
            limit = max(duration + 10.0, outro_at + OUTRO_SECONDS + OUTRO_PAD)
            tail_end = min(tail_end, limit)
        self._counter += 1
        self._state = _State(
            gen=self._counter,
            enabled=bool(s.enabled),
            venue=s.venue,
            wet=wet if s.enabled else 0.0,
            width=_clamp01(s.width),
            crowd=crowd,
            intensity=intensity,
            events=events,
            duration=duration,
            outro_start=outro_at,
            tail_end=tail_end,
            bed_gain=0.16 * crowd,
            outro=outro,
        )

    def _reset_runtime(self, st: _State) -> None:
        self._pending_idx = 0
        self._voices = []
        self._bed = None
        self._outro_spawned = False
        if self._conv is not None:
            self._conv.reset()
        bed = (self._textures.get("bed") or [None])[0]
        if bed is not None and st.bed_gain > 0.001:
            self._bed = _Voice(bed, st.bed_gain, 0.0, loop=True)
            self._voices = [self._bed]
        self._gen = st.gen

    # -- realtime -------------------------------------------------------------
    def process(self, block: np.ndarray, t: float) -> np.ndarray:
        st = self._state
        if st is None or not st.enabled:
            return block
        conv = self._conv
        if conv is None:
            return block
        if st.gen != self._gen:
            self._reset_runtime(st)

        n = block.shape[0]
        if n == 0:
            return block
        if st.wet > 0.001:
            dry = block * (1.0 - 0.3 * st.wet)
            rev = conv.process(block)
            if st.width < 0.999:
                mono = rev.mean(axis=1, keepdims=True)
                rev = mono + (rev - mono) * st.width
            out = dry + rev * st.wet
        else:
            out = block

        # reactions
        rate = float(self._rate)
        tt = float(t)
        for i in range(self._pending_idx, len(st.events)):
            ev = st.events[i]
            if ev.time > tt + n / rate:
                break
            self._spawn(ev, st, tt)
            self._pending_idx = i + 1

        if st.outro and not self._outro_spawned and tt + n / rate >= st.outro_start:
            self._spawn_outro(st, max(0.0, st.outro_start - tt))
            self._outro_spawned = True

        if self._voices:
            if out is block:
                out = np.array(block, dtype=np.float32, copy=True)
            tail_fade = None
            if st.outro and tt + n / rate > st.tail_end - 0.8:
                idx = np.arange(n, dtype=np.float32)
                tail_fade = np.clip(
                    (st.tail_end - (tt + idx / rate)) / 0.8, 0.0, 1.0
                )
            self._mix(out, n, tail_fade)
        return out

    def _spawn(self, ev: LiveEvent, st: _State, t: float) -> None:
        variants = self._textures.get(ev.kind)
        if not variants or st.crowd <= 0.001:
            return
        base = EVENT_GAIN.get(ev.kind, 0.7)
        gain = base * ev.gain * st.crowd * (0.45 + 0.95 * st.intensity)
        spread = EVENT_PAN.get(ev.kind, 0.5) * (0.25 + 0.85 * st.width)
        rng = _thread_rng()
        pan = float(rng.uniform(-1.0, 1.0)) * spread
        offset = int(max(0.0, (ev.time - t)) * self._rate)
        tex = variants[int(rng.integers(len(variants)))]
        self._voices.append(_Voice(tex, gain, pan, offset=offset))

    def _spawn_outro(self, st: _State, offset: float) -> None:
        """Crowd that starts after the track has ended (fade in / hold / out)."""
        if st.crowd <= 0.001:
            return
        rng = _thread_rng()
        scale = st.crowd * (0.45 + 0.95 * st.intensity)
        layers = (
            ("big_cheer", EVENT_GAIN["big_cheer"], 0.0, OUTRO_FADE_IN, OUTRO_FADE_OUT),
            ("applause", EVENT_GAIN["applause"], 0.55, 0.9, 2.4),
        )
        for kind, base, delay, fade_in, fade_out in layers:
            variants = self._textures.get(kind)
            if not variants:
                continue
            tex = variants[int(rng.integers(len(variants)))]
            env = _fade_envelope(tex.shape[0], self._rate, fade_in, fade_out)
            spread = EVENT_PAN.get(kind, 0.5) * (0.25 + 0.85 * st.width)
            pan = float(rng.uniform(-1.0, 1.0)) * spread
            gain = base * scale
            start = int(max(0.0, offset + delay) * self._rate)
            self._voices.append(
                _Voice(tex, gain, pan, offset=start, env=env)
            )

    def _mix(self, out: np.ndarray, n: int,
             tail_fade: np.ndarray | None = None) -> None:
        keep: list[_Voice] = []
        for v in self._voices:
            a = v.offset
            v.offset = 0
            if a < n:
                m = min(n - a, v.n - v.pos)
                if m > 0:
                    seg = v.data[v.pos:v.pos + m]
                    if v.env is not None:
                        seg = seg * v.env[v.pos:v.pos + m, None]
                    if tail_fade is not None:
                        seg = seg * tail_fade[a:a + m, None]
                    out[a:a + m, 0] += seg[:, 0] * (v.gain * v.gl)
                    out[a:a + m, 1] += seg[:, 1] * (v.gain * v.gr)
                    v.pos += m
            if v.loop:
                if v.pos >= v.n:
                    v.pos = 0
                keep.append(v)
            elif v.pos < v.n:
                keep.append(v)
        self._voices = keep


_RNG = np.random.default_rng()


def _thread_rng():
    return _RNG


def warm_cache(rate: int = 44100, venue: str = "arena") -> None:
    """Build the venue IR + crowd textures ahead of time (UI thread)."""
    load_ir(rate, venue)
    for kind in TEXTURE_KINDS:
        load_texture_set(kind, rate)
