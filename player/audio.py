"""Audio transport: PyAV decoding -> numpy DSP -> sounddevice output.

The engine owns the play queue plus the loop / shuffle rules requested by the
user:

* ``off``  – play the queue once, then stop
* ``all``  – loop the whole folder / playlist ("folder loop")
* ``one``  – repeat the current single track ("1-song loop")

Because the samples pass through numpy before they reach the speakers, the same
pipeline can apply the 10-band equalizer, the bass boost and the per-track
loudness normalisation (see :mod:`player.dsp`).
"""

from __future__ import annotations

import collections
import random
import threading
import time

import numpy as np
from PySide6.QtCore import QObject, QTimer, Signal

from . import dsp

try:  # sounddevice bundles PortAudio with its wheels
    import sounddevice as sd
except Exception as exc:  # pragma: no cover - only when the backend is missing
    sd = None
    _SD_IMPORT_ERROR = str(exc)
else:
    _SD_IMPORT_ERROR = ""

try:
    import av
except Exception as exc:  # pragma: no cover - only when the backend is missing
    av = None
    _AV_IMPORT_ERROR = str(exc)
else:
    _AV_IMPORT_ERROR = ""

from .library import Track
from .loudness import LoudnessCache

LOOP_OFF = "off"
LOOP_ALL = "all"
LOOP_ONE = "one"

DEFAULT_RATE = 48000
CHANNELS = 2
HOP = 1024
N_FFT = 2048


class AudioEngine(QObject):
    trackChanged = Signal(int, object)          # index, Track
    positionChanged = Signal(int)               # ms
    durationChanged = Signal(int)               # ms
    playingChanged = Signal(bool)
    volumeChanged = Signal(int)                 # 0..100
    loopChanged = Signal(str)
    shuffleChanged = Signal(bool)
    queueChanged = Signal()
    errorOccurred = Signal(str)
    eqChanged = Signal()                        # settings / normalisation applied

    def __init__(self, parent: QObject | None = None) -> None:
        super().__init__(parent)

        self._queue: list[Track] = []
        self._order: list[int] = []
        self._pos_in_order: int = -1
        self._current: int = -1

        self._loop_mode = LOOP_ALL
        self._shuffle = False
        self._volume = 70

        self._rate = DEFAULT_RATE
        self._eq = dsp.Processor(self._rate, CHANNELS, N_FFT)
        self._normalize = True
        self._norm_gain = 1.0
        self._gain = self._volume / 100.0

        # Audio state shared with the callback / decoder threads.
        self._lock = threading.Lock()
        self._buffers: collections.deque[np.ndarray] = collections.deque()
        self._buf_frames = 0
        self._playing = False
        self._pos = 0.0                     # seconds into the current track
        self._duration_ms = 0

        # Decoder control.
        self._track_serial = 0
        self._eof_serial = -1
        self._seek_sec: float | None = None
        self._quit = False

        self._stream = None
        self._loudness = LoudnessCache()
        self._analysing: set[str] = set()

        self._thread = threading.Thread(target=self._decode_loop, daemon=True)
        self._thread.start()

        self._timer = QTimer(self)
        self._timer.setInterval(120)
        self._timer.timeout.connect(self._on_tick)
        self._timer.start()

    # -- query ----------------------------------------------------------------
    @property
    def queue(self) -> list[Track]:
        return self._queue

    @property
    def current_index(self) -> int:
        return self._current

    @property
    def current_track(self) -> Track | None:
        if 0 <= self._current < len(self._queue):
            return self._queue[self._current]
        return None

    @property
    def loop_mode(self) -> str:
        return self._loop_mode

    @property
    def shuffle(self) -> bool:
        return self._shuffle

    @property
    def volume(self) -> int:
        return self._volume

    @property
    def equalizer(self) -> dsp.Processor:
        return self._eq

    @property
    def eq_settings(self) -> dsp.EqSettings:
        return self._eq.settings

    def is_playing(self) -> bool:
        return self._playing

    def position(self) -> int:
        return int(self._pos * 1000)

    def duration(self) -> int:
        return self._duration_ms

    # -- equalizer ------------------------------------------------------------
    def set_eq_settings(self, settings: dsp.EqSettings) -> None:
        self._eq.set_settings(settings)
        self._normalize = settings.normalize
        self._update_gain()
        self._refresh_normalization()
        self.eqChanged.emit()

    def set_normalize(self, enabled: bool) -> None:
        self._normalize = bool(enabled)
        self._refresh_normalization()
        self.eqChanged.emit()

    # -- queue management -----------------------------------------------------
    def set_queue(self, tracks: list[Track], start_index: int = 0) -> None:
        self._queue = list(tracks)
        self._rebuild_order(keep=start_index)
        self.queueChanged.emit()
        if self._queue:
            self.play_index(max(0, min(start_index, len(self._queue) - 1)))
        else:
            self.stop()

    def load_queue_paused(self, tracks: list[Track], start_index: int = 0,
                          position_ms: int = 0) -> None:
        """Restore the previous session without starting playback."""
        if not tracks:
            return
        # Same rationale as play_index: fix the device rate before decoding.
        self._resolve_rate()
        self._queue = list(tracks)
        self._rebuild_order(keep=start_index)
        self.queueChanged.emit()
        index = max(0, min(start_index, len(self._queue) - 1))
        self._current = index
        try:
            self._pos_in_order = self._order.index(index)
        except ValueError:
            self._pos_in_order = 0
        self._pos = max(0, int(position_ms)) / 1000.0
        self._clear_buffer()
        self._eq.reset()
        self._seek_sec = self._pos
        self._track_serial += 1
        self._eof_serial = -1
        self._refresh_normalization()
        self.trackChanged.emit(index, self._queue[index])

    def append_to_queue(self, tracks: list[Track]) -> None:
        had = bool(self._queue)
        self._queue.extend(tracks)
        self._rebuild_order(keep=self._pos_in_order if had else 0)
        self.queueChanged.emit()
        if not had and self._queue:
            self.play_index(0)

    def _rebuild_order(self, keep: int | None = None) -> None:
        n = len(self._queue)
        if self._shuffle and n > 1:
            indices = list(range(n))
            random.shuffle(indices)
            if keep is not None and 0 <= keep < n:
                indices.remove(keep)
                indices.insert(0, keep)
            self._order = indices
        else:
            self._order = list(range(n))

        if keep is not None and 0 <= keep < n:
            try:
                self._pos_in_order = self._order.index(keep)
            except ValueError:
                self._pos_in_order = 0
        elif self._pos_in_order >= len(self._order):
            self._pos_in_order = 0 if self._order else -1

    # -- transport ------------------------------------------------------------
    def play_index(self, index: int) -> None:
        if not (0 <= index < len(self._queue)):
            return
        # Resolve the output device rate *before* the decoder is woken up: the
        # resampler and the stream must run at the same rate, otherwise the
        # track plays back at the wrong pitch.
        self._resolve_rate()
        ready = self._ensure_stream()
        self._current = index
        try:
            self._pos_in_order = self._order.index(index)
        except ValueError:
            self._pos_in_order = 0
        self._pos = 0.0
        self._duration_ms = 0
        self._clear_buffer()
        self._eq.reset()
        self._seek_sec = 0.0
        self._track_serial += 1
        self._eof_serial = -1
        self._refresh_normalization()
        self.trackChanged.emit(index, self._queue[index])
        if ready:
            self._playing = True
            self.playingChanged.emit(True)

    def play(self) -> None:
        if self._current < 0 and self._queue:
            self.play_index(0)
            return
        if self._current < 0:
            return
        if not self._ensure_stream():
            return
        if self._eof_serial == self._track_serial and self._buf_frames == 0:
            self.play_index(self._current)
            return
        if not self._playing:
            self._playing = True
            self.playingChanged.emit(True)

    def pause(self) -> None:
        if self._playing:
            self._playing = False
            self.playingChanged.emit(False)

    def toggle(self) -> None:
        if self._playing:
            self.pause()
        else:
            self.play()

    def stop(self) -> None:
        was = self._playing
        self._playing = False
        self._clear_buffer()
        self._eof_serial = self._track_serial
        if was:
            self.playingChanged.emit(False)

    def seek(self, ms: int) -> None:
        self._pos = max(0, int(ms)) / 1000.0
        self._eq.reset()
        self._seek_sec = self._pos
        self._clear_buffer()

    def next(self) -> None:
        if not self._order:
            return
        nxt = self._pos_in_order + 1
        if nxt >= len(self._order):
            if self._loop_mode == LOOP_ALL:
                nxt = 0
            else:
                self.stop()
                return
        self.play_index(self._order[nxt])

    def previous(self) -> None:
        if not self._order:
            return
        # restart the track first (typical portable-player behaviour)
        if self._pos > 3.0:
            self.seek(0)
            return
        prv = self._pos_in_order - 1
        if prv < 0:
            prv = len(self._order) - 1 if self._loop_mode == LOOP_ALL else 0
        self.play_index(self._order[prv])

    # -- modes ----------------------------------------------------------------
    def set_loop_mode(self, mode: str) -> None:
        if mode not in (LOOP_OFF, LOOP_ALL, LOOP_ONE):
            return
        self._loop_mode = mode
        self.loopChanged.emit(mode)

    def cycle_loop_mode(self) -> str:
        order = [LOOP_OFF, LOOP_ALL, LOOP_ONE]
        self.set_loop_mode(order[(order.index(self._loop_mode) + 1) % len(order)])
        return self._loop_mode

    def set_shuffle(self, enabled: bool) -> None:
        self._shuffle = enabled
        self._rebuild_order(keep=self._current if self._current >= 0 else None)
        self.shuffleChanged.emit(enabled)

    def toggle_shuffle(self) -> bool:
        self.set_shuffle(not self._shuffle)
        return self._shuffle

    # -- volume ---------------------------------------------------------------
    def set_volume(self, value: int) -> None:
        self._volume = max(0, min(100, int(value)))
        self._update_gain()
        self.volumeChanged.emit(self._volume)

    def change_volume(self, delta: int) -> None:
        self.set_volume(self._volume + delta)

    def _update_gain(self) -> None:
        self._gain = (self._volume / 100.0) * self._norm_gain

    # -- normalisation --------------------------------------------------------
    def _refresh_normalization(self) -> None:
        track = self.current_track
        if track is None or not self._normalize:
            self._norm_gain = 1.0
            self._update_gain()
            return
        db = self._loudness.get(track.path)
        if db is None:
            self._norm_gain = 1.0
            self._update_gain()
            self._analyze_async(track)
        else:
            self._norm_gain = 10.0 ** (dsp.normalization_gain_db(db) / 20.0)
            self._update_gain()

    def _analyze_async(self, track: Track) -> None:
        if track.path in self._analysing:
            return
        self._analysing.add(track.path)

        def worker() -> None:
            db = self._measure_loudness(track.path)
            self._analysing.discard(track.path)
            if db is None:
                return
            self._loudness.set(track.path, db)
            if self.current_track is not None and self.current_track.path == track.path:
                self._norm_gain = 10.0 ** (dsp.normalization_gain_db(db) / 20.0)
                self._update_gain()

        threading.Thread(target=worker, daemon=True).start()

    def _measure_loudness(self, path: str, limit_sec: float = 90.0) -> float | None:
        if av is None:
            return None
        try:
            container = av.open(path)
        except Exception:
            return None
        try:
            stream = container.streams.audio[0]
            resampler = av.AudioResampler(format="flt", layout="mono", rate=8000)
            chunks: list[np.ndarray] = []
            total = 0
            for frame in container.decode(stream):
                for rf in resampler.resample(frame):
                    arr = np.asarray(rf.to_ndarray(), dtype=np.float32).reshape(-1)
                    chunks.append(arr)
                    total += arr.shape[0]
                if total > limit_sec * 8000:
                    break
            for rf in resampler.resample(None):
                arr = np.asarray(rf.to_ndarray(), dtype=np.float32).reshape(-1)
                chunks.append(arr)
            if not chunks:
                return None
            data = np.concatenate(chunks)
            return dsp.rms_db(data)
        except Exception:
            return None
        finally:
            try:
                container.close()
            except Exception:
                pass

    # -- internal -------------------------------------------------------------
    def _resolve_rate(self) -> int:
        """Output sample rate the decoder must resample to.

        Queried before a track is decoded (and before the stream exists) so the
        resampler and the stream can never disagree about the rate - a mismatch
        changes the playback speed / pitch.
        """
        if self._stream is not None:
            return self._rate
        if sd is None:
            return self._rate
        rate = DEFAULT_RATE
        try:
            dev = sd.query_devices(kind="output")
            rate = int(dev.get("default_samplerate") or DEFAULT_RATE)
        except Exception:
            rate = DEFAULT_RATE
        if rate <= 0:
            rate = DEFAULT_RATE
        if rate != self._rate:
            self._rate = rate
            self._eq.set_rate(rate, CHANNELS)
        return self._rate

    def _ensure_stream(self) -> bool:
        if self._stream is not None:
            return True
        if sd is None or av is None:
            detail = _SD_IMPORT_ERROR or _AV_IMPORT_ERROR
            self.errorOccurred.emit(
                "オーディオバックエンドを読み込めませんでした" + (f"（{detail}）" if detail else "")
            )
            return False
        rate = self._resolve_rate()
        try:
            self._stream = sd.OutputStream(
                samplerate=rate,
                channels=CHANNELS,
                dtype="float32",
                blocksize=HOP,
                latency="high",
                callback=self._callback,
            )
            self._stream.start()
            return True
        except Exception as exc:  # pragma: no cover - depends on the sound card
            self._stream = None
            self.errorOccurred.emit(f"オーディオ出力を開けません: {exc}")
            return False

    def _clear_buffer(self) -> None:
        with self._lock:
            self._buffers.clear()
            self._buf_frames = 0

    def _take(self, frames: int) -> np.ndarray:
        parts: list[np.ndarray] = []
        need = frames
        with self._lock:
            while need > 0 and self._buffers:
                block = self._buffers[0]
                if block.shape[0] <= need:
                    parts.append(block)
                    self._buffers.popleft()
                    need -= block.shape[0]
                    self._buf_frames -= block.shape[0]
                else:
                    parts.append(block[:need])
                    self._buffers[0] = block[need:]
                    self._buf_frames -= need
                    need = 0
        if not parts:
            return np.zeros((0, CHANNELS), dtype=np.float32)
        return np.concatenate(parts, axis=0)

    # -- audio thread ---------------------------------------------------------
    def _callback(self, outdata, frames, time_info, status) -> None:
        outdata.fill(0.0)
        if not self._playing:
            return
        data = self._take(frames)
        n = data.shape[0]
        if n == 0:
            return
        processed = self._eq.process(data)
        if processed.shape[0] < n:
            processed = np.pad(processed, ((0, n - processed.shape[0]), (0, 0)))
        gain = self._gain
        if gain != 1.0:
            processed = processed * gain
        np.clip(processed, -1.0, 1.0, out=processed)
        outdata[:n] = processed
        self._pos += n / self._rate

    # -- decoder thread -------------------------------------------------------
    def _decode_loop(self) -> None:
        last_serial = -1
        while not self._quit:
            serial = self._track_serial
            seek = self._seek_sec
            if serial != last_serial or seek is not None:
                with self._lock:
                    if self._seek_sec is not None:
                        self._seek_sec = None
                    self._buffers.clear()
                    self._buf_frames = 0
                last_serial = serial
                self._eof_serial = -1
                try:
                    self._decode_file(serial, seek)
                except Exception as exc:
                    self.errorOccurred.emit(str(exc))
                self._eof_serial = serial
                continue
            time.sleep(0.03)

    def _decode_file(self, serial: int, seek_sec: float | None) -> None:
        if av is None or not (0 <= self._current < len(self._queue)):
            return
        path = self._queue[self._current].path
        container = av.open(path)
        try:
            stream = container.streams.audio[0]
            tb = float(stream.time_base) if stream.time_base else 1.0 / 1000.0

            duration_s = None
            if container.duration:
                duration_s = container.duration / av.time_base
            elif stream.duration:
                duration_s = stream.duration * tb
            if duration_s and duration_s > 0:
                self._duration_ms = int(duration_s * 1000)
                self.durationChanged.emit(self._duration_ms)

            resampler = av.AudioResampler(format="fltp", layout="stereo", rate=self._rate)
            skip_until: float | None = None
            if seek_sec:
                try:
                    container.seek(int(seek_sec / tb), backward=True)
                    skip_until = float(seek_sec)
                except Exception:
                    skip_until = None

            while self._buf_frames > self._rate:      # back-pressure (~1 s buffer)
                if self._quit or serial != self._track_serial or self._seek_sec is not None:
                    return
                time.sleep(0.01)

            for frame in container.decode(stream):
                if self._quit or serial != self._track_serial or self._seek_sec is not None:
                    return
                if skip_until is not None:
                    ft = float(frame.pts * tb) if frame.pts is not None else None
                    fdur = (frame.duration or 0) * tb
                    if ft is not None and ft + fdur <= skip_until:
                        continue
                    skip_until = None
                for rf in resampler.resample(frame):
                    self._push(rf)
                while self._buf_frames > self._rate:
                    if self._quit or serial != self._track_serial or self._seek_sec is not None:
                        return
                    time.sleep(0.01)

            for rf in resampler.resample(None):
                if self._quit or serial != self._track_serial or self._seek_sec is not None:
                    return
                self._push(rf)
        finally:
            try:
                container.close()
            except Exception:
                pass

    def _push(self, resampled) -> None:
        arr = np.asarray(resampled.to_ndarray(), dtype=np.float32)   # (channels, samples)
        if arr.ndim == 1:
            arr = arr.reshape(1, -1)
        if arr.shape[0] == 1:
            arr = np.repeat(arr, CHANNELS, axis=0)
        elif arr.shape[0] > CHANNELS:
            arr = arr[:CHANNELS]
        data = np.ascontiguousarray(arr.T)                           # (samples, channels)
        with self._lock:
            self._buffers.append(data)
            self._buf_frames += data.shape[0]

    # -- ui timer -------------------------------------------------------------
    def _on_tick(self) -> None:
        self.positionChanged.emit(int(self._pos * 1000))
        if not self._playing:
            return
        if self._eof_serial == self._track_serial and self._buf_frames == 0:
            self._on_end_of_media()

    def _on_end_of_media(self) -> None:
        if self._loop_mode == LOOP_ONE and self._current >= 0:
            self.play_index(self._current)
            return
        if self._loop_mode == LOOP_OFF and self._pos_in_order >= len(self._order) - 1:
            self.stop()
            return
        self.next()

    # -- teardown -------------------------------------------------------------
    def close(self) -> None:
        self._quit = True
        self._playing = False
        self._timer.stop()
        stream, self._stream = self._stream, None
        if stream is not None:
            try:
                stream.stop()
                stream.close()
            except Exception:
                pass
        self._loudness.save()
