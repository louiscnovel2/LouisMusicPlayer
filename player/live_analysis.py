"""Song analysis for the Live Effect: where should the crowd react?

The track is decoded to mono and analysed with plain numpy:

* an RMS envelope + onset strength give a tempo and a beat grid,
* the smoothed energy locates the "quiet" / "loud" (verse / chorus) sections,
* the section boundaries (snapped to the beat) become reaction events:

  ``build``     a couple of beats before a chorus
  ``cheer``     when the first chorus lands
  ``applause``  at every further chorus / section change
  ``intro``     light applause as the song starts
  ``big_cheer`` a long hype cheer at the end (plus its tail)

Results are cached in ``live_analysis.json`` keyed by path + mtime, mirroring
:mod:`player.loudness`.
"""

from __future__ import annotations

import json
import os
import threading

import numpy as np

from .live import LiveEvent
from .store import config_dir

try:
    import av
except Exception:  # pragma: no cover - backend missing
    av = None

ANALYSIS_RATE = 22050
HOP = 1024
MAX_SECONDS = 420.0
MIN_GAP = 7.0
MAX_EVENTS = 24

_VERSION = 3


def _provisional(duration_ms: int) -> list[LiveEvent]:
    """Events used before (or instead of) the analysis."""
    dur = max(0, int(duration_ms)) / 1000.0
    events = [LiveEvent(0.9, "applause", 0.45, "auto")]
    if dur > 20:
        events.append(LiveEvent(max(1.0, dur - 4.0), "cheer", 0.6, "auto"))
        events.append(LiveEvent(max(1.0, dur - 0.2), "big_cheer", 0.9, "auto"))
    events.extend(_auto_bed(dur))
    return events


def _auto_bed(duration: float) -> list[LiveEvent]:
    """A couple of reactions spread across long quiet stretches."""
    if duration <= 90:
        return []
    out: list[LiveEvent] = []
    for frac in (0.35, 0.62):
        t = duration * frac
        if t > 8:
            out.append(LiveEvent(t, "cheer", 0.35, "auto"))
    return out


# -- decoding -----------------------------------------------------------------


def _decode_mono(path: str) -> tuple[np.ndarray, int] | None:
    if av is None:
        return None
    try:
        container = av.open(path)
    except Exception:
        return None
    try:
        stream = container.streams.audio[0]
        resampler = av.AudioResampler(format="flt", layout="mono", rate=ANALYSIS_RATE)
        chunks: list[np.ndarray] = []
        total = 0
        limit = int(MAX_SECONDS * ANALYSIS_RATE)
        for frame in container.decode(stream):
            for rf in resampler.resample(frame):
                arr = np.asarray(rf.to_ndarray(), dtype=np.float32).reshape(-1)
                chunks.append(arr)
                total += arr.shape[0]
            if total > limit:
                break
        for rf in resampler.resample(None):
            arr = np.asarray(rf.to_ndarray(), dtype=np.float32).reshape(-1)
            chunks.append(arr)
        if not chunks:
            return None
        data = np.concatenate(chunks)
        if data.shape[0] < ANALYSIS_RATE:
            return None
        return data.astype(np.float32), ANALYSIS_RATE
    except Exception:
        return None
    finally:
        try:
            container.close()
        except Exception:
            pass


# -- feature extraction -------------------------------------------------------


def _frames_of(x: np.ndarray, hop: int) -> np.ndarray:
    n = (x.shape[0] // hop) * hop
    if n <= hop:
        return x.reshape(1, -1)
    return x[:n].reshape(-1, hop)


def _envelopes(x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(RMS envelope, onset strength), both per hop."""
    frames = _frames_of(x, HOP)
    rms = np.sqrt(np.mean(np.square(frames, dtype=np.float64), axis=1) + 1e-12)
    energy = np.log(rms + 1e-6)
    # crude low / high split for a spectral-flux like onset curve
    diff = np.diff(frames, axis=1, prepend=frames[:, :1])
    hf = np.sqrt(np.mean(np.square(diff, dtype=np.float64), axis=1) + 1e-12)
    onset = np.maximum(0.0, np.diff(np.log(hf + 1e-6), prepend=0.0))
    return energy, onset


def _smooth(x: np.ndarray, win: int) -> np.ndarray:
    win = max(1, int(win))
    if win <= 1 or x.shape[0] <= win:
        return x.astype(np.float64)
    ker = np.ones(win, dtype=np.float64) / win
    return np.convolve(x, ker, mode="same")


def _tempo(onset: np.ndarray, rate: float) -> tuple[float, float]:
    """Return (period in seconds, confidence) from the onset envelope."""
    hop_rate = rate / HOP
    x = onset - onset.mean()
    if not np.any(np.abs(x) > 1e-9):
        return 0.5, 0.0
    n = x.shape[0]
    spec = np.fft.rfft(x, n=2 * n)
    acf = np.fft.irfft(spec * np.conj(spec), n=2 * n)[:n]
    if acf[0] > 0:
        acf = acf / acf[0]
    lo = max(1, int(hop_rate * 60.0 / 190.0))     # 190 BPM
    hi = min(n - 1, int(hop_rate * 60.0 / 55.0))  # 55 BPM
    if hi <= lo + 1:
        return 0.5, 0.0
    segment = acf[lo:hi]
    idx = int(np.argmax(segment)) + lo
    conf = float(segment.max())
    # prefer a strong harmonic (double period) when it is nearly as strong
    if 2 * idx < hi and acf[2 * idx] > conf * 0.82:
        idx = 2 * idx
        conf = float(acf[idx])
    return idx / hop_rate, conf


def _beat_phase(onset: np.ndarray, period: float, rate: float) -> float:
    hop_rate = rate / HOP
    steps = max(2, int(round(period * hop_rate)))
    if steps <= 1:
        return 0.0
    scores = []
    for phase in range(steps):
        idx = np.arange(phase, onset.shape[0], steps)
        scores.append(float(onset[idx].sum()) if idx.size else 0.0)
    best = int(np.argmax(scores))
    return best / hop_rate


def _snap(t: float, period: float, phase: float) -> float:
    if period <= 0.05:
        return t
    k = round((t - phase) / period)
    snapped = phase + k * period
    return float(max(0.0, snapped))


def _sections(energy: np.ndarray, rate: float) -> list[tuple[float, float, float]]:
    """Split into (start, end, level) chunks of roughly constant loudness.

    ``level`` is 0 for the quietest parts of the song and ~1 for the loudest,
    so the caller can look for "loud section right after a quiet one".
    """
    hop_rate = rate / HOP
    smooth = _smooth(energy, int(hop_rate * 2.0))
    if smooth.shape[0] < 8:
        return []
    lo = float(np.percentile(smooth, 12.0))
    hi = float(np.percentile(smooth, 92.0))
    span = max(0.2, hi - lo)
    if hi - lo < 0.5:
        # barely any dynamics (a DJ set / wall of sound): no "choruses"
        return [(0.0, smooth.shape[0] / hop_rate, 0.0)]
    threshold = lo + 0.45 * (hi - lo)
    loud = smooth > threshold
    chunks: list[tuple[int, int, float]] = []
    start = 0
    for i in range(1, loud.shape[0] + 1):
        if i == loud.shape[0] or loud[i] != loud[start]:
            if i - start >= int(hop_rate * 1.5):
                level = (float(np.mean(smooth[start:i])) - lo) / span
                chunks.append((start, i, max(0.0, min(1.0, level))))
            start = i
    return [(a / hop_rate, b / hop_rate, level) for a, b, level in chunks]


def analyze_array(x: np.ndarray, rate: int) -> list[LiveEvent]:
    duration = x.shape[0] / rate
    energy, onset = _envelopes(x)
    period, conf = _tempo(onset, rate)
    phase = _beat_phase(onset, period, rate) if conf > 0.05 else 0.0
    sections = _sections(energy, rate)

    candidates: list[tuple[float, str, float]] = []
    loud = [s for s in sections if s[2] > 0.5]
    quiet_after = False
    prev_level = 0.0
    chorus_seen = 0
    for start, end, level in sections:
        # a chorus = a loud section that follows something quieter
        if level > 0.5 and (prev_level <= 0.5 or len(loud) == 1):
            t = _snap(start, period, phase)
            chorus_seen += 1
            candidates.append((t, "cheer", 0.75 if chorus_seen == 1 else 0.6))
            candidates.append((max(0.0, t - max(1.0, period * 3.0)), "build", 0.4))
        elif level <= 0.25 and (end - start) > 6.0:
            quiet_after = True
        prev_level = level

    # one extra reaction in a long quiet stretch (mid song)
    for frac in (0.4, 0.68):
        t = _snap(duration * frac, period, phase)
        if 8.0 < t < duration - 10.0:
            candidates.append((t, "cheer", 0.35))

    # the ending
    end_t = _snap(max(0.0, duration - 3.0), period, phase)
    candidates.append((end_t, "applause", 0.6))
    candidates.append((max(0.0, duration - 0.15), "big_cheer", 0.95))
    if quiet_after and duration > 40:
        candidates.append((_snap(duration * 0.5, period, phase), "applause", 0.3))

    # de-duplicate: keep the loudest reaction inside each MIN_GAP window.
    # "build" is a short riser that belongs right before its chorus, so it is
    # handled separately (it may sit close to the cheer it introduces).
    builds = [(t, kind, gain) for t, kind, gain in candidates if kind == "build"]
    mains = [(t, kind, gain) for t, kind, gain in candidates if kind != "build"]
    mains.sort(key=lambda c: c[0])
    events: list[LiveEvent] = []
    if duration > 1.5:
        # the "the band walks on stage" applause is always there
        events.append(LiveEvent(min(0.9, duration - 0.1), "applause", 0.45, "auto"))
    for t, kind, gain in mains:
        if duration > 0:
            t = max(0.0, min(t, duration - 0.05))
        if events and t - events[-1].time < MIN_GAP:
            if gain > events[-1].gain and t - events[-1].time > 1.5:
                events[-1] = LiveEvent(t, kind, gain, "auto")
            continue
        events.append(LiveEvent(t, kind, gain, "auto"))
    for t, kind, gain in builds:
        if duration <= 0 or t < 1.0:
            continue
        t = max(0.0, min(t, duration - 0.05))
        if any(abs(t - e.time) < 0.4 for e in events):
            continue
        events.append(LiveEvent(t, kind, gain, "auto"))
    events.sort(key=lambda e: e.time)
    events = _thin(events)
    return events


def _thin(events: list[LiveEvent]) -> list[LiveEvent]:
    """Cap the number of reactions, keeping the intro and the finale."""
    if len(events) <= MAX_EVENTS:
        return events
    head, tail = events[:2], events[-3:]
    middle = events[2:-3]
    step = max(1, len(middle) // max(1, MAX_EVENTS - 5))
    return head + middle[::step] + tail


def analyze(path: str) -> list[LiveEvent] | None:
    decoded = _decode_mono(path)
    if decoded is None:
        return None
    data, rate = decoded
    try:
        return analyze_array(data, rate)
    except Exception:
        return None


# -- cache --------------------------------------------------------------------


class LiveAnalysisCache:
    """JSON cache of the automatic reaction lists."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._path = os.path.join(config_dir(), "live_analysis.json")
        self._data: dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        try:
            with open(self._path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            if isinstance(data, dict):
                self._data = data
        except (OSError, ValueError):
            self._data = {}

    @staticmethod
    def _mtime(path: str) -> int:
        try:
            return int(os.path.getmtime(path))
        except OSError:
            return 0

    def get(self, path: str) -> list[LiveEvent] | None:
        with self._lock:
            rec = self._data.get(path)
        if not isinstance(rec, dict):
            return None
        if rec.get("mtime") != self._mtime(path):
            return None
        if rec.get("v") != _VERSION:
            return None
        raw = rec.get("events")
        if not isinstance(raw, list):
            return None
        events = [e for e in (LiveEvent.from_dict(d) for d in raw) if e is not None]
        return events

    def set(self, path: str, events: list[LiveEvent]) -> None:
        with self._lock:
            self._data[path] = {
                "mtime": self._mtime(path),
                "v": _VERSION,
                "events": [e.to_dict() for e in events],
            }
        self.save()

    def save(self) -> None:
        with self._lock:
            data = dict(self._data)
        try:
            tmp = self._path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False)
            os.replace(tmp, self._path)
        except OSError:
            pass


def provisional(duration_ms: int) -> list[LiveEvent]:
    return _provisional(duration_ms)
