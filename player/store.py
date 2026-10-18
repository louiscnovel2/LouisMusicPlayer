"""Persistent playlist + settings storage (plain JSON, no external deps)."""

from __future__ import annotations

import json
import os
import shutil
from dataclasses import dataclass, field


APP_NAME = "Louis Music Player"
LEGACY_APP_NAME = "MusicPlayer"


def config_dir() -> str:
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    path = os.path.join(base, APP_NAME)
    legacy = os.path.join(base, LEGACY_APP_NAME)
    if not os.path.isdir(path) and os.path.isdir(legacy):
        # First run after the rename: carry the old settings/playlists over
        # instead of making the user set everything up again.
        try:
            shutil.copytree(legacy, path)
        except OSError:
            pass
    os.makedirs(path, exist_ok=True)
    return path


@dataclass
class Playlist:
    name: str
    tracks: list[str] = field(default_factory=list)  # absolute file paths


class Store:
    """Loads / saves playlists and user settings."""

    def __init__(self) -> None:
        self.dir = config_dir()
        self.playlists_path = os.path.join(self.dir, "playlists.json")
        self.settings_path = os.path.join(self.dir, "settings.json")
        self.playlists: list[Playlist] = []
        self.settings: dict = {
            "volume": 70,
            "loop_mode": "all",
            "shuffle": False,
            "library_folders": [],
            "last_folder": "",
            "queue": [],
            "queue_index": 0,
            "queue_position": 0,
            "scroll": {},
            "eq": {},
            "eq_custom": {},
        }
        self.load()

    # -- io -------------------------------------------------------------------
    def load(self) -> None:
        try:
            with open(self.playlists_path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            self.playlists = [Playlist(p["name"], list(p.get("tracks", []))) for p in data]
        except (OSError, ValueError, KeyError):
            self.playlists = []

        try:
            with open(self.settings_path, "r", encoding="utf-8") as fh:
                self.settings.update(json.load(fh))
        except (OSError, ValueError):
            pass

    def save(self) -> None:
        try:
            with open(self.playlists_path, "w", encoding="utf-8") as fh:
                json.dump(
                    [{"name": p.name, "tracks": p.tracks} for p in self.playlists],
                    fh,
                    ensure_ascii=False,
                    indent=2,
                )
            with open(self.settings_path, "w", encoding="utf-8") as fh:
                json.dump(self.settings, fh, ensure_ascii=False, indent=2)
        except OSError:
            pass

    # -- playlists ------------------------------------------------------------
    def get_playlist(self, name: str) -> Playlist | None:
        for p in self.playlists:
            if p.name == name:
                return p
        return None

    def create_playlist(self, name: str, tracks: list[str] | None = None) -> Playlist:
        existing = self.get_playlist(name)
        if existing:
            return existing
        pl = Playlist(name, list(tracks or []))
        self.playlists.append(pl)
        self.save()
        return pl

    def delete_playlist(self, name: str) -> None:
        self.playlists = [p for p in self.playlists if p.name != name]
        self.save()

    def rename_playlist(self, old: str, new: str) -> None:
        pl = self.get_playlist(old)
        if pl is None or self.get_playlist(new) is not None:
            return
        pl.name = new
        self.save()

    def add_to_playlist(self, name: str, paths: list[str]) -> None:
        pl = self.create_playlist(name)
        for path in paths:
            if path not in pl.tracks:
                pl.tracks.append(path)
        self.save()

    def remove_from_playlist(self, name: str, path: str) -> None:
        pl = self.get_playlist(name)
        if pl and path in pl.tracks:
            pl.tracks.remove(path)
            self.save()

    # -- folders --------------------------------------------------------------
    def add_library_folder(self, folder: str) -> None:
        folders = self.settings.setdefault("library_folders", [])
        if folder not in folders:
            folders.append(folder)
            self.save()

    def remove_library_folder(self, folder: str) -> None:
        """Hide a folder from the player (the folder on disk is kept)."""
        folders = self.settings.setdefault("library_folders", [])
        if folder in folders:
            folders.remove(folder)
            self.save()
