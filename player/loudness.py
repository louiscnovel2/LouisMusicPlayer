"""JSON cache of per-track loudness (used by the volume normaliser).

Measurements are keyed by file path and invalidated when the file mtime
changes, so editing the tags of a file re-analyses it.
"""

from __future__ import annotations

import json
import os
import threading

from .store import config_dir


class LoudnessCache:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._path = os.path.join(config_dir(), "loudness.json")
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

    def get(self, path: str) -> float | None:
        with self._lock:
            rec = self._data.get(path)
        if not isinstance(rec, dict):
            return None
        if rec.get("mtime") != self._mtime(path):
            return None
        db = rec.get("db")
        return float(db) if isinstance(db, (int, float)) else None

    def set(self, path: str, db: float) -> None:
        with self._lock:
            self._data[path] = {"db": float(db), "mtime": self._mtime(path)}
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
