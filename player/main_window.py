"""Main window: assembles the device, pages and playback logic."""

from __future__ import annotations

import os

from PySide6.QtCore import Qt, QThread, QUrl, Signal
from PySide6.QtGui import QDesktopServices, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QApplication,
    QFileDialog,
    QInputDialog,
    QMenu,
    QMessageBox,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from . import __version__, dsp, library, live
from .audio import LOOP_ALL, LOOP_OFF, LOOP_ONE, AudioEngine
from .library import Track
from .store import Store
from .ui.device import DeviceFrame
from .ui.eq_page import EqPage
from .ui.live_page import LivePage
from .ui.screens import BottomBar, ListPage, NowPlayingPage, Row, cover_to_pixmap, fmt_time


class TrackLoader(QThread):
    """Reads tags/artwork off the UI thread."""

    loaded = Signal(str, str, list)  # token, source, tracks

    def __init__(self, token: str, source: str, paths: list[str]) -> None:
        super().__init__()
        self.token = token
        self.source = source
        self.paths = paths

    def run(self) -> None:
        tracks: list[Track] = []
        for path in self.paths:
            try:
                tracks.append(library.read_track(path, with_cover=True))
            except Exception:
                continue
        self.loaded.emit(self.token, self.source, tracks)


class MainWindow(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Louis Music Player")
        self.setAcceptDrops(True)
        self.resize(470, 790)

        self.store = Store()
        self.engine = AudioEngine(self)

        self._loaders: set[TrackLoader] = set()
        self._active_tokens: set[str] = set()
        self._folder_cache: dict[str, list[Track]] = {}
        self._playlist_cache: dict[str, list[Track]] = {}
        self._nav: list[tuple] = []
        self._view: tuple = ("folders_root",)
        self._playlist_for_queue: str = ""
        self._scroll_key: str | None = None
        self._restoring = False
        self._eq_settings = dsp.EqSettings()
        self._live_settings = live.LiveSettings()

        self._build_ui()
        self._connect()
        self._restore_settings()
        self._render()
        self._restore_queue()

    # -- construction ---------------------------------------------------------
    def _build_ui(self) -> None:
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)

        self.frame = DeviceFrame(self)
        root.addWidget(self.frame)

        screen = QWidget(self.frame)
        screen.setStyleSheet("background: transparent;")
        slay = QVBoxLayout(screen)
        slay.setContentsMargins(0, 0, 0, 0)
        slay.setSpacing(0)

        self.stack = QStackedWidget(screen)
        self.now_page = NowPlayingPage(self.stack)
        self.list_page = ListPage("", self.stack)
        self.eq_page = EqPage(self.stack)
        self.live_page = LivePage(self.stack)
        self.stack.addWidget(self.now_page)
        self.stack.addWidget(self.list_page)
        self.stack.addWidget(self.eq_page)
        self.stack.addWidget(self.live_page)

        self.bottom = BottomBar(screen)
        slay.addWidget(self.stack, 1)
        slay.addWidget(self.bottom, 0)

        self.frame.attach(screen)

        # neutral placeholder shown before a track is selected
        self.now_page.title.set_text("Louis Music Player")
        self.now_page.artist.set_text("曲を選んでください")
        self.now_page.album.set_text("Portable Digital Audio")

    def _connect(self) -> None:
        eng = self.engine
        eng.trackChanged.connect(self._on_track_changed)
        eng.positionChanged.connect(self.now_page.set_position)
        eng.positionChanged.connect(self._on_position)
        eng.durationChanged.connect(self._on_duration)
        eng.playingChanged.connect(self._on_playing)
        eng.loopChanged.connect(self._on_loop)
        eng.shuffleChanged.connect(self._on_shuffle)
        eng.volumeChanged.connect(self._on_volume)
        eng.errorOccurred.connect(self._on_error)
        eng.queueChanged.connect(self._render)
        eng.liveChanged.connect(self._on_live_changed)
        eng.liveAnalysisChanged.connect(self._on_live_analysis)

        self.now_page.prevClicked.connect(eng.previous)
        self.now_page.nextClicked.connect(eng.next)
        self.now_page.toggleClicked.connect(eng.toggle)
        self.now_page.repeatClicked.connect(self._cycle_loop)
        self.now_page.shuffleClicked.connect(self._toggle_shuffle)
        self.now_page.seekRequested.connect(eng.seek)
        self.now_page.liveToggled.connect(self._toggle_live)
        self.now_page.cheerClicked.connect(self._add_cheer_at_position)

        self.eq_page.settingsChanged.connect(self._on_eq_changed)
        self.eq_page.presetSaved.connect(self._on_eq_save_preset)
        self.eq_page.backClicked.connect(self._on_back)

        self.live_page.settingsChanged.connect(self._on_live_settings)
        self.live_page.cheerAdded.connect(self._on_cheer_added)
        self.live_page.cheerRemoved.connect(self._on_cheer_removed)
        self.live_page.cheersCleared.connect(self._on_cheers_cleared)
        self.live_page.seekRequested.connect(self.engine.seek)
        self.live_page.backClicked.connect(self._on_back)

        self.bottom.backClicked.connect(self._on_back)
        self.bottom.foldersClicked.connect(lambda: self._goto(("folders_root",), reset=True, active="folders"))
        self.bottom.playlistsClicked.connect(lambda: self._goto(("playlists_root",), reset=True, active="playlists"))
        self.bottom.settingsClicked.connect(lambda: self._goto(("settings",), reset=True, active="settings"))

        self.list_page.activated.connect(self._on_row_activated)
        self.list_page.backClicked.connect(self._on_back)
        self.list_page.list.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.list_page.list.customContextMenuRequested.connect(self._on_context_menu)

        self.frame.volumeStepRequested.connect(self._change_volume)

        self._shortcut("Space", self.engine.toggle)
        self._shortcut("Right", lambda: self.engine.seek(self.engine.position() + 5000))
        self._shortcut("Left", lambda: self.engine.seek(self.engine.position() - 5000))
        self._shortcut("Ctrl+Right", self.engine.next)
        self._shortcut("Ctrl+Left", self.engine.previous)
        self._shortcut("Up", lambda: self._change_volume(2))
        self._shortcut("Down", lambda: self._change_volume(-2))
        self._shortcut("L", self._cycle_loop)
        self._shortcut("S", self._toggle_shuffle)
        self._shortcut("Escape", self._on_back)
        self._shortcut("F", lambda: self._goto(("folders_root",), reset=True, active="folders"))
        self._shortcut("P", lambda: self._goto(("playlists_root",), reset=True, active="playlists"))

    def _shortcut(self, sequence: str, slot) -> None:
        sc = QShortcut(QKeySequence(sequence), self)
        sc.activated.connect(slot)
    def _restore_settings(self) -> None:
        s = self.store.settings
        self.engine.set_loop_mode(s.get("loop_mode", LOOP_ALL))
        self.engine.set_shuffle(bool(s.get("shuffle", False)))
        self.engine.set_volume(int(s.get("volume", 70)))
        self.now_page.set_loop_mode(self.engine.loop_mode)
        self.now_page.set_shuffle(self.engine.shuffle)
        self._eq_settings = dsp.EqSettings.from_dict(s.get("eq"))
        self.engine.set_eq_settings(self._eq_settings)
        self._live_settings = live.LiveSettings.from_dict(s.get("live"))
        self.engine.load_live_manual(s.get("live_manual"))
        self.engine.set_live_settings(self._live_settings)
        self._sync_live_ui()

    # -- live effect ----------------------------------------------------------
    def _toggle_live(self, enabled: bool) -> None:
        settings = self._live_settings.copy()
        settings.enabled = bool(enabled)
        self._on_live_settings(settings)
        self.now_page.show_osd(f"Live Effect: {'オン' if enabled else 'オフ'}")

    def _on_live_settings(self, settings) -> None:
        self._live_settings = settings.copy()
        self.engine.set_live_settings(self._live_settings)
        self.store.settings["live"] = self._live_settings.to_dict()
        self.store.save()
        self._sync_live_ui()
        if self._view[0] == "live":
            self._render_live()

    def _on_live_changed(self) -> None:
        self._sync_live_ui()
        if self._view[0] == "live":
            self._render_live()

    def _on_live_analysis(self, path: str) -> None:
        self._sync_live_ui()
        if self._view[0] == "live":
            self._render_live()

    def _sync_live_ui(self) -> None:
        s = self.engine.live_settings
        self.now_page.set_live(s.enabled, live.venue_label(s.venue))

    def _on_position(self, ms: int) -> None:
        if self.stack.currentWidget() is self.live_page:
            self.live_page.set_position(ms)

    def _save_live_manual(self) -> None:
        self.store.settings["live_manual"] = self.engine.live_manual_map()
        self.store.save()

    def _add_cheer(self, position: int | None = None) -> None:
        if self.engine.current_track is None:
            self.list_page.hint.set_text("先に曲を選んでください")
            return
        pos = self.engine.position() if position is None else max(0, int(position))
        if not self.engine.add_live_cheer(pos, "cheer", 0.8):
            return
        self._save_live_manual()
        self.now_page.show_osd(f"歓声を追加: {fmt_time(pos)}")
        if self._view[0] == "live":
            self._render_live()

    def _add_cheer_at_position(self) -> None:
        self._add_cheer(None)

    def _on_cheer_added(self, position: int) -> None:
        self._add_cheer(None if int(position) < 0 else int(position))

    def _on_cheer_removed(self, index: int) -> None:
        track = self.engine.current_track
        if track is None:
            return
        self.engine.remove_live_cheer(track.path, int(index))
        self._save_live_manual()
        self._render_live()

    def _on_cheers_cleared(self) -> None:
        track = self.engine.current_track
        if track is None:
            return
        self.engine.clear_live_cheers(track.path)
        self._save_live_manual()
        self._render_live()

    def _render_live(self) -> None:
        track = self.engine.current_track
        if self.engine.live_analysing():
            status = "自動演出: 解析中…"
        elif track is not None and self.engine.live_analysis_ready(track.path):
            count = len(self.engine.live_auto_events(track.path))
            status = f"自動演出: {count} 箇所（解析済み）"
        elif track is not None:
            status = "自動演出: 未解析（再生すると解析します）"
        else:
            status = "自動演出: 曲を選ぶと解析します"
        self.live_page.load_state(
            self._live_settings, self.engine.live_manual(), status,
            track.display_title if track is not None else "",
        )
        self.live_page.set_position(self.engine.position())

    def _live_summary(self) -> str:
        s = self._live_settings
        if not s.enabled:
            return "オフ"
        return (f"{live.venue_label(s.venue)} ・ 強さ {int(s.intensity * 100)}%"
                f" ・ 歓声 {int(s.crowd * 100)}%")

    # -- equalizer ------------------------------------------------------------
    def _on_eq_changed(self, settings) -> None:
        self._eq_settings = settings
        self.engine.set_eq_settings(settings)
        self.store.settings["eq"] = settings.to_dict()
        self.store.save()
        if self._view[0] == "settings":
            self._render_settings()

    def _on_eq_save_preset(self, name: str, gains) -> None:
        custom = self.store.settings.setdefault("eq_custom", {})
        custom[name] = list(gains)
        self.store.save()
        self.eq_page.set_presets(custom)
        self.eq_page.load_settings(self.eq_page.settings())

    # -- queue persistence ----------------------------------------------------
    def _restore_queue(self) -> None:
        """Reload the queue from the previous session (paused, at last position)."""
        s = self.store.settings
        paths = s.get("queue") or []
        if not paths:
            return
        tracks = [library.read_track(p, with_cover=False) for p in paths if os.path.exists(p)]
        if not tracks:
            return
        index = int(s.get("queue_index", 0) or 0)
        index = max(0, min(index, len(tracks) - 1))
        position = int(s.get("queue_position", 0) or 0)
        self._restoring = True
        try:
            self.engine.load_queue_paused(tracks, index, position)
        finally:
            self._restoring = False
        self.now_page.set_playing(False)
        self.stack.setCurrentWidget(self.now_page)
        self._update_status()

    def _save_queue(self) -> None:
        if self._restoring:
            return
        s = self.store.settings
        s["queue"] = [t.path for t in self.engine.queue]
        s["queue_index"] = max(0, self.engine.current_index)
        s["queue_position"] = self.engine.position()
        self.store.save()

    # -- engine slots ---------------------------------------------------------
    def _on_track_changed(self, index: int, track: Track) -> None:
        self.now_page.set_track(index, track, len(self.engine.queue))
        self.now_page.set_playing(self.engine.is_playing())
        self._update_status()
        self._render()
        self._save_queue()

    def _on_duration(self, ms: int) -> None:
        self.now_page.set_duration(ms)
        track = self.engine.current_track
        if track and ms and not track.duration:
            track.duration = ms / 1000.0

    def _on_playing(self, playing: bool) -> None:
        self.now_page.set_playing(playing)
        self._update_status()

    def _on_loop(self, mode: str) -> None:
        self.now_page.set_loop_mode(mode)
        self.store.settings["loop_mode"] = mode
        self.store.save()
        self._update_status()
        self._render()

    def _on_shuffle(self, enabled: bool) -> None:
        self.now_page.set_shuffle(enabled)
        self.store.settings["shuffle"] = enabled
        self.store.save()
        self._update_status()

    def _on_volume(self, value: int) -> None:
        self.store.settings["volume"] = value
        self.frame.flash_volume(value)

    def _on_error(self, message: str) -> None:
        self.list_page.hint.set_text(f"再生エラー: {message}")

    def _change_volume(self, delta: int) -> None:
        self.engine.change_volume(delta)
        self.store.save()

    _LOOP_LABEL = {LOOP_OFF: "オフ", LOOP_ALL: "全曲（フォルダ）", LOOP_ONE: "1曲"}

    def _cycle_loop(self) -> None:
        mode = self.engine.cycle_loop_mode()
        self.now_page.show_osd(f"リピート再生: {self._LOOP_LABEL.get(mode, mode)}")

    def _toggle_shuffle(self) -> None:
        on = self.engine.toggle_shuffle()
        self.now_page.show_osd(f"シャッフル再生: {'オン' if on else 'オフ'}")

    def _update_status(self) -> None:
        total = len(self.engine.queue)
        index = self.engine.current_index
        self.now_page.status.set_state(
            self.engine.is_playing(), index + 1 if index >= 0 else 0, total,
            self.engine.loop_mode, self.engine.shuffle,
        )

    # -- playback helpers -----------------------------------------------------
    def _play_tracks(self, tracks: list[Track], start: int = 0) -> None:
        if not tracks:
            return
        self.engine.set_queue(tracks, start)
        self.stack.setCurrentWidget(self.now_page)
        self.bottom.set_active(None)

    def _load_and_play_folder(self, folder: str, start_path: str | None = None,
                              loop_all: bool = False) -> None:
        paths = library.scan_folder(folder, recursive=True)
        if not paths:
            self.list_page.hint.set_text("対応する音楽ファイルが見つかりません")
            return
        tracks = library.sort_by_track_number(
            [library.read_track(p, with_cover=False) for p in paths]
        )
        start = 0
        if start_path:
            start = next((i for i, t in enumerate(tracks) if t.path == start_path), 0)
        self._play_tracks(tracks, start)
        if loop_all:
            # "このフォルダをループ再生" must override e.g. a leftover
            # single-track repeat, otherwise the folder would never advance.
            self.engine.set_loop_mode(LOOP_ALL)
            self.now_page.show_osd(f"リピート再生: {self._LOOP_LABEL.get(LOOP_ALL)}")
        self._load_covers_async(folder, paths)

    def _load_covers_async(self, source: str, paths: list[str]) -> None:
        loader = TrackLoader(f"covers:{source}", source, paths)
        loader.loaded.connect(self._on_covers_loaded)
        self._track_loader(loader)

    def _on_covers_loaded(self, token: str, source: str, tracks: list[Track]) -> None:
        by_path = {t.path: t for t in tracks}
        for t in self.engine.queue:
            full = by_path.get(t.path)
            if full is not None:
                t.cover = full.cover
                t.sample_rate = t.sample_rate or full.sample_rate
                t.bits = t.bits or full.bits
        current = self.engine.current_track
        if current is not None:
            from .ui.screens import _ART_CACHE

            _ART_CACHE.pop(current.path, None)
            self.now_page.cover.set_cover(cover_to_pixmap(current))
            self.now_page.hr.setVisible(current.is_hires)
        self._render()

    # -- navigation -----------------------------------------------------------
    def _goto(self, view: tuple, reset: bool = False, active: str | None = None) -> None:
        if reset:
            self._nav.clear()
        else:
            self._nav.append(self._view)
        self._view = view
        if view[0] == "eq":
            self.eq_page.set_presets(self.store.settings.get("eq_custom", {}))
            self.stack.setCurrentWidget(self.eq_page)
        elif view[0] == "live":
            self.stack.setCurrentWidget(self.live_page)
        else:
            self.stack.setCurrentWidget(self.list_page)
        self.bottom.set_active(active)
        self._render()

    def _on_back(self) -> None:
        if self.stack.currentWidget() in (self.list_page, self.eq_page, self.live_page) and self._nav:
            self._view = self._nav.pop()
            self._render()
            return
        self.stack.setCurrentWidget(self.now_page)
        self.bottom.set_active(None)

    # -- rendering ------------------------------------------------------------
    def _render(self) -> None:
        view = self._view
        kind = view[0]
        key = self._view_key()
        same_view = key == self._scroll_key
        # Re-rendering the same list (track change, EQ tweak, ...) keeps the
        # user where they are; a *different* list starts at its saved offset.
        if not same_view:
            self._store_scroll(self._scroll_key)

        if kind == "folders_root":
            self._render_folders_root()
        elif kind == "folder":
            self._render_folder(view[1])
        elif kind == "playlists_root":
            self._render_playlists_root()
        elif kind == "playlist":
            self._render_playlist(view[1])
        elif kind == "settings":
            self._render_settings()
        elif kind == "licenses":
            self._render_licenses()
        elif kind == "eq":
            self.eq_page.load_settings(self._eq_settings)
            return
        elif kind == "live":
            self._render_live()
            return

        if not same_view:
            self.list_page.restore_scroll(self._stored_scroll(key))
        self._scroll_key = key

    def _view_key(self) -> str:
        view = self._view
        if not view:
            return ""
        if view[0] in ("folder", "playlist"):
            return f"{view[0]}:{view[1]}"
        return str(view[0])

    def _stored_scroll(self, key: str) -> int:
        table = self.store.settings.get("scroll")
        if not isinstance(table, dict):
            return 0
        try:
            return int(table.get(key, 0) or 0)
        except (TypeError, ValueError):
            return 0

    def _store_scroll(self, key: str | None) -> None:
        if not key:
            return
        value = self.list_page.scroll_target()
        table = self.store.settings.get("scroll")
        if not isinstance(table, dict):
            table = {}
            self.store.settings["scroll"] = table
        if table.get(key) != value:
            table[key] = value
            self.store.save()

    def _render_folders_root(self) -> None:
        rows = [Row("action", "＋ フォルダを追加", "音楽フォルダを選択", icon="folder",
                    payload=("add_folder", None))]
        for folder in self.store.settings.get("library_folders", []):
            exists = os.path.isdir(folder)
            rows.append(Row("folder", os.path.basename(folder) or folder,
                            folder if exists else "（見つかりません）",
                            icon="folder", payload=("open_folder", folder)))
        hint = "フォルダを右クリック → 「一覧から削除」（本体は残ります）" if len(rows) > 1 else self._format_hint()
        self.list_page.set_header("フォルダ", hint)
        self.list_page.set_rows(rows)

    def _render_folder(self, folder: str) -> None:
        rows: list[Row] = []
        if os.path.isdir(folder):
            rows.append(Row("action", "▶ このフォルダをループ再生", "サブフォルダを含む全曲",
                            icon="play", payload=("play_folder_all", folder)))
            try:
                entries = sorted(os.listdir(folder), key=lambda s: s.lower())
            except OSError:
                entries = []
            subdirs = [e for e in entries if os.path.isdir(os.path.join(folder, e))]
            files = [e for e in entries
                     if os.path.splitext(e)[1].lower() in library.AUDIO_EXTS]
            for name in subdirs:
                rows.append(Row("folder", name, icon="folder",
                                payload=("open_folder", os.path.join(folder, name))))
            cached = self._folder_cache.get(folder)
            if cached is not None:
                for track in cached:
                    rows.append(self._track_row(track))
            elif files:
                for name in files:
                    rows.append(Row("track", name, "読み込み中...", icon="play",
                                    payload=("play_file", os.path.join(folder, name))))
                self._ensure_folder(folder, files)
            if not subdirs and not files:
                rows.append(Row("info", "音楽ファイルがありません", "対応: MP3 / M4A / FLAC ほか"))
        else:
            rows.append(Row("info", "フォルダが見つかりません", folder))
        self.list_page.set_header(os.path.basename(folder) or folder, self._format_hint())
        self.list_page.set_rows(rows)

    def _render_playlists_root(self) -> None:
        rows = [Row("action", "＋ 新規プレイリスト", "現在の曲／フォルダから作成",
                    icon="list", payload=("new_playlist", None))]
        for pl in self.store.playlists:
            rows.append(Row("playlist", pl.name, f"{len(pl.tracks)} 曲",
                            icon="list", payload=("open_playlist", pl.name)))
        if len(rows) == 1:
            rows.append(Row("info", "プレイリストはまだありません", "右上の ＋ から作成"))
        self.list_page.set_header("プレイリスト", self._format_hint())
        self.list_page.set_rows(rows)

    def _render_playlist(self, name: str) -> None:
        pl = self.store.get_playlist(name)
        rows: list[Row] = []
        if pl is None:
            rows.append(Row("info", "プレイリストが見つかりません", name))
        else:
            rows.append(Row("action", "▶ 全曲を再生", f"{len(pl.tracks)} 曲",
                            icon="play", payload=("play_playlist", name)))
            rows.append(Row("action", "🗑 このプレイリストを削除", "", icon="back",
                            payload=("delete_playlist", name)))
            cached = self._playlist_cache.get(name)
            if cached is not None:
                for track in cached:
                    rows.append(self._track_row(track))
            elif pl.tracks:
                for path in pl.tracks:
                    rows.append(Row("track", os.path.basename(path), "読み込み中...",
                                    icon="play", payload=("play_file", path)))
                self._ensure_playlist(name, pl.tracks)
            else:
                rows.append(Row("info", "曲がありません", "フォルダ画面から追加できます"))
        self.list_page.set_header(name, self._format_hint())
        self.list_page.set_rows(rows)

    def _render_settings(self) -> None:
        loop_labels = {LOOP_OFF: "オフ", LOOP_ALL: "フォルダ（全曲）", LOOP_ONE: "1曲"}
        rows = [
            Row("action", "ループ再生", f"現在: {loop_labels.get(self.engine.loop_mode, '')}",
                icon="repeat", payload=("cycle_loop", None)),
            Row("action", "シャッフル", "オン" if self.engine.shuffle else "オフ",
                icon="shuffle", payload=("toggle_shuffle", None)),
            Row("action", "音質調整（イコライザー）", self._eq_summary(),
                icon="eq", payload=("open_eq", None)),
            Row("action", "ライブ演出（Live Effect）", self._live_summary(),
                icon="volume", payload=("open_live", None)),
            Row("action", "ライセンス", "効果音クレジット / オープンソース",
                icon="list", payload=("open_licenses", None)),
            Row("info", "音量", f"{self.engine.volume} / 100", icon="volume"),
            Row("action", "ライブラリフォルダを追加", "フォルダを選択",
                icon="folder", payload=("add_folder", None)),
            Row("action", "設定フォルダを開く", self.store.dir, icon="folder",
                payload=("open_config", None)),
            Row("info", "対応フォーマット", self._format_hint(), icon="list"),
            Row("info", "ハイレゾ再生", "FLAC/WAV/AIFF 最大 24bit・384kHz (HR)", icon="list"),
            Row("info", "フォント", f"SST JP 相当: {self._font_name()}", icon="list"),
            Row("info", "バージョン", __version__, icon="list"),
            Row("info", "操作", "Space 再生 / Ctrl+←→ 曲送り / ↑↓ 音量", icon="back"),
        ]
        self.list_page.set_header("設定", "Louis Music Player for Windows 11")
        self.list_page.set_rows(rows)

    def _render_licenses(self) -> None:
        rows = [
            Row("action", "OtoLogic", "歓声・拍手の効果音 (CC BY 4.0)",
                icon="list", payload=("open_url", "https://otologic.jp/")),
            Row("action", "OtoLogic 利用規約", "クレジット表記で無料利用",
                icon="list", payload=("open_url", "https://otologic.jp/free/license.html")),
            Row("action", "CC BY 4.0", "クリエイティブ・コモンズ 表示 4.0",
                icon="list",
                payload=("open_url", "https://creativecommons.org/licenses/by/4.0/deed.ja")),
            Row("info", "会場の残響・環境音", "アプリ内で合成 (Louis Music Player)", icon="list"),
            Row("action", "PySide6 / Qt", "LGPLv3",
                icon="list", payload=("open_url", "https://www.qt.io/qt-6/lgpl/")),
            Row("action", "NumPy", "BSD 3-Clause",
                icon="list", payload=("open_url", "https://numpy.org/doc/stable/license.html")),
            Row("action", "PyAV (FFmpeg)", "BSD 3-Clause",
                icon="list",
                payload=("open_url", "https://github.com/PyAV-Org/PyAV/blob/main/LICENSE.txt")),
            Row("action", "sounddevice", "MIT License",
                icon="list",
                payload=("open_url", "https://github.com/spatialaudio/python-sounddevice/blob/master/LICENSE")),
            Row("action", "Mutagen", "GPL-2.0-or-later",
                icon="list", payload=("open_url", "https://github.com/quodlibet/mutagen/blob/main/COPYING")),
            Row("action", "Python", "PSF License",
                icon="list", payload=("open_url", "https://docs.python.org/3/license.html")),
        ]
        self.list_page.set_header("ライセンス", "クリックで該当ページを開きます")
        self.list_page.set_rows(rows)

    def _track_row(self, track: Track) -> Row:
        playing = self.engine.current_track is not None and self.engine.current_track.path == track.path
        sub = f"{track.display_artist}  ·  {track.spec_text}"
        if track.duration:
            sub += f"  ·  {fmt_time(int(track.duration * 1000))}"
        return Row("track", track.display_title, sub,
                   badge="HR" if track.is_hires else "",
                   icon="play", payload=("play_track", track), playing=playing)

    def _ensure_folder(self, folder: str, files: list[str]) -> None:
        if folder in self._folder_cache:
            return
        token = f"folder:{folder}"
        if token in self._active_tokens:
            return
        self._start_loader(token, folder, [os.path.join(folder, f) for f in files])

    def _ensure_playlist(self, name: str, paths: list[str]) -> None:
        if name in self._playlist_cache:
            return
        token = f"playlist:{name}"
        if token in self._active_tokens:
            return
        self._start_loader(token, name, paths)

    def _start_loader(self, token: str, source: str, paths: list[str]) -> None:
        loader = TrackLoader(token, source, paths)
        loader.loaded.connect(self._on_rows_loaded)
        self._track_loader(loader)

    def _track_loader(self, loader: "TrackLoader") -> None:
        """Start a loader and keep a strong reference until it finishes."""
        self._loaders.add(loader)
        self._active_tokens.add(loader.token)

        def _done() -> None:
            self._loaders.discard(loader)
            self._active_tokens.discard(loader.token)
            loader.deleteLater()

        loader.finished.connect(_done)
        loader.start()

    def _on_rows_loaded(self, token: str, source: str, tracks: list[Track]) -> None:
        if token.startswith("folder:"):
            self._folder_cache[source] = library.sort_by_track_number(tracks)
        else:
            self._playlist_cache[source] = tracks
        self._render()

    # -- row activation -------------------------------------------------------
    def _on_row_activated(self, row: Row) -> None:
        payload = row.payload or ()
        if not payload:
            return
        action, data = payload[0], (payload[1] if len(payload) > 1 else None)
        if action == "add_folder":
            self._choose_folder()
        elif action == "open_folder":
            self._goto(("folder", data), active="folders")
        elif action == "play_folder_all":
            self._load_and_play_folder(data, loop_all=True)
        elif action == "play_file":
            self._play_single(data)
        elif action == "play_track":
            self._play_track_from_view(data)
        elif action == "new_playlist":
            self._new_playlist()
        elif action == "open_playlist":
            self._goto(("playlist", data), active="playlists")
        elif action == "play_playlist":
            self._play_playlist(data)
        elif action == "delete_playlist":
            self._delete_playlist(data)
        elif action == "open_eq":
            self._goto(("eq",), active="settings")
        elif action == "open_live":
            self._goto(("live",), active="settings")
        elif action == "open_licenses":
            self._goto(("licenses",), active="settings")
        elif action == "open_url":
            QDesktopServices.openUrl(QUrl(str(data)))
        elif action == "cycle_loop":
            self._cycle_loop()
            self._render()
        elif action == "toggle_shuffle":
            self._toggle_shuffle()
            self._render()
        elif action == "open_config":
            os.startfile(self.store.dir)  # noqa: S606 - open explorer
        elif action == "remove_folder":
            self._remove_folder(data)

    def _choose_folder(self) -> None:
        folder = QFileDialog.getExistingDirectory(self, "音楽フォルダを選択")
        if folder:
            self.store.add_library_folder(folder)
            self._goto(("folder", folder), reset=True, active="folders")

    def _play_single(self, path: str) -> None:
        parent = os.path.dirname(path)
        paths = library.scan_folder(parent, recursive=False)
        if path not in paths:
            paths.append(path)
        tracks = library.sort_by_track_number(
            [library.read_track(p, with_cover=False) for p in paths]
        )
        start = next((i for i, t in enumerate(tracks) if t.path == path), 0)
        self._play_tracks(tracks, start)
        self._load_covers_async(parent, paths)

    def _play_track_from_view(self, track: Track) -> None:
        view = self._view
        if view[0] == "folder":
            cached = self._folder_cache.get(view[1], [])
            tracks = list(cached)
            start = next((i for i, t in enumerate(tracks) if t.path == track.path), 0)
            self._play_tracks(tracks, start)
        elif view[0] == "playlist":
            self._play_playlist(view[1], start_path=track.path)
        else:
            self._play_tracks([track], 0)

    def _play_playlist(self, name: str, start_path: str | None = None) -> None:
        pl = self.store.get_playlist(name)
        if not pl:
            return
        paths = [p for p in pl.tracks if os.path.exists(p)]
        if not paths:
            self.list_page.hint.set_text("再生できる曲がありません")
            return
        tracks = [library.read_track(p, with_cover=False) for p in paths]
        start = paths.index(start_path) if start_path in paths else 0
        self._play_tracks(tracks, start)
        self._load_covers_async(f"playlist:{name}", paths)

    # -- playlist editing -----------------------------------------------------
    def _new_playlist(self) -> None:
        name, ok = QInputDialog.getText(self, "新規プレイリスト", "プレイリスト名:")
        name = name.strip()
        if ok and name:
            self.store.create_playlist(name, self._current_queue_paths())
            self._playlist_cache.pop(name, None)
            self._render()

    def _current_queue_paths(self) -> list[str]:
        return [t.path for t in self.engine.queue]

    def _delete_playlist(self, name: str) -> None:
        confirm = QMessageBox.question(self, "削除", f"「{name}」を削除しますか？")
        if confirm == QMessageBox.StandardButton.Yes:
            self.store.delete_playlist(name)
            self._playlist_cache.pop(name, None)
            self._on_back()

    def _on_context_menu(self, pos) -> None:
        item = self.list_page.list.itemAt(pos)
        if item is None:
            return
        row: Row = item.data(Qt.ItemDataRole.UserRole)
        if row is None:
            return

        if row.kind == "folder" and self._view[0] == "folders_root":
            folder = row.payload[1] if row.payload else None
            menu = QMenu(self)
            menu.addAction("▶ このフォルダを開く", lambda: self._goto(("folder", folder), active="folders"))
            menu.addAction("このフォルダを一覧から削除", lambda: self._remove_folder(folder))
            menu.exec(self.list_page.list.mapToGlobal(pos))
            return

        if row.kind != "track":
            return
        menu = QMenu(self)
        add_menu = menu.addMenu("プレイリストに追加")
        for pl in self.store.playlists:
            add_menu.addAction(pl.name, lambda n=pl.name: self._add_to_playlist(n, row))
        add_menu.addSeparator()
        add_menu.addAction("新しいプレイリスト...", lambda: self._add_to_new_playlist(row))
        if row.kind == "track" and self._view[0] == "playlist":
            menu.addAction("この曲をリストから削除",
                           lambda: self._remove_from_current_playlist(row))
        menu.exec(self.list_page.list.mapToGlobal(pos))

    def _remove_folder(self, folder: str) -> None:
        if not folder:
            return
        name = os.path.basename(folder) or folder
        confirm = QMessageBox.question(
            self,
            "フォルダを一覧から削除",
            f"「{name}」を一覧から削除しますか？\n\n"
            "※ プレイヤーの一覧から見えなくなるだけで、\n"
            "　 パソコン上のフォルダと音楽ファイルは削除されません。",
        )
        if confirm == QMessageBox.StandardButton.Yes:
            self.store.remove_library_folder(folder)
            self._folder_cache.pop(folder, None)
            self.list_page.hint.set_text(f"「{name}」を一覧から削除しました")
            self._render()

    def _row_path(self, row: Row) -> str | None:
        track = row.payload[1] if row.payload else None
        if isinstance(track, Track):
            return track.path
        if isinstance(track, str):
            return track
        return None

    def _add_to_playlist(self, name: str, row: Row) -> None:
        path = self._row_path(row)
        if path:
            self.store.add_to_playlist(name, [path])
            self._playlist_cache.pop(name, None)
            self.list_page.hint.set_text(f"「{name}」に追加しました")

    def _add_to_new_playlist(self, row: Row) -> None:
        name, ok = QInputDialog.getText(self, "新規プレイリスト", "プレイリスト名:")
        name = name.strip()
        if ok and name:
            path = self._row_path(row)
            self.store.create_playlist(name, [path] if path else [])
            self._playlist_cache.pop(name, None)
            self.list_page.hint.set_text(f"「{name}」を作成しました")

    def _remove_from_current_playlist(self, row: Row) -> None:
        if self._view[0] != "playlist":
            return
        name = self._view[1]
        path = self._row_path(row)
        if path:
            self.store.remove_from_playlist(name, path)
            self._playlist_cache.pop(name, None)
            self._render()

    # -- misc -----------------------------------------------------------------
    def _eq_summary(self) -> str:
        s = self._eq_settings
        if not s.enabled:
            return "オフ"
        label = dsp.PRESET_LABELS.get(s.preset, "カスタム")
        extra = []
        if abs(s.bass) > 0.05:
            extra.append(f"Bass {s.bass:+.1f}dB")
        if s.normalize:
            extra.append("ノーマライズ")
        tail = (" ・ " + " ・ ".join(extra)) if extra else ""
        return f"10バンド ・ {label}{tail}"

    def _format_hint(self) -> str:
        return "対応: MP3 / M4A / AAC / FLAC / WAV / AIFF / OGG / Opus / WMA / ALAC / APE / DSF"

    def _font_name(self) -> str:
        from . import theme

        return theme.resolve_family()

    # -- drag & drop ----------------------------------------------------------
    def dragEnterEvent(self, event) -> None:
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event) -> None:
        paths: list[str] = []
        folders: list[str] = []
        for url in event.mimeData().urls():
            local = url.toLocalFile()
            if os.path.isdir(local):
                folders.append(local)
            elif os.path.splitext(local)[1].lower() in library.AUDIO_EXTS:
                paths.append(local)
        if folders:
            self.store.add_library_folder(folders[0])
            self._load_and_play_folder(folders[0])
            event.acceptProposedAction()
        elif paths:
            tracks = library.sort_by_track_number(
                [library.read_track(p, with_cover=False) for p in paths]
            )
            self._play_tracks(tracks, 0)
            self._load_covers_async("drop", paths)
            event.acceptProposedAction()

    def closeEvent(self, event) -> None:
        self._store_scroll(self._scroll_key)
        self.store.settings["volume"] = self.engine.volume
        self.store.settings["loop_mode"] = self.engine.loop_mode
        self.store.settings["shuffle"] = self.engine.shuffle
        self.store.settings["queue"] = [t.path for t in self.engine.queue]
        self.store.settings["queue_index"] = max(0, self.engine.current_index)
        self.store.settings["queue_position"] = self.engine.position()
        self.store.save()
        self.engine.close()
        super().closeEvent(event)


def run() -> int:
    app = QApplication.instance() or QApplication([])
    from PySide6.QtGui import QIcon

    from . import theme
    from .resources import resource_path

    app.setApplicationName("Louis Music Player")
    app.setApplicationDisplayName("Louis Music Player")
    app.setFont(theme.font(10))

    icon = QIcon()
    for size in (16, 32, 64, 128, 256, 512):
        path = resource_path(f"assets/icon_{size}.png")
        if os.path.exists(path):
            icon.addFile(path)
    if not icon.isNull():
        app.setWindowIcon(icon)

    window = MainWindow()
    window.show()

    # Headless smoke test used to verify a frozen build.
    selftest = os.environ.get("MUSICPLAYER_SELFTEST")
    if selftest:
        import json
        import sys

        from PySide6.QtCore import QTimer

        info: dict = {
            "platform": app.platformName(),
            "frozen": bool(getattr(sys, "frozen", False)),
        }
        media = os.environ.get("MUSICPLAYER_SELFTEST_FILE")
        if media:
            window.engine.durationChanged.connect(lambda ms: info.__setitem__("duration_ms", ms))
            window.engine.errorOccurred.connect(lambda m: info.__setitem__("error", m))

            def _load() -> None:
                tracks = [library.read_track(media)]
                info["hires"] = tracks[0].is_hires
                info["spec"] = tracks[0].spec_text
                window.engine.set_queue(tracks, 0)

            QTimer.singleShot(400, _load)

        def _finish() -> None:
            info["playing"] = window.engine.is_playing()
            info["position_ms"] = window.engine.position()
            with open(selftest, "w", encoding="utf-8") as fh:
                json.dump(info, fh, ensure_ascii=False, indent=2)
            app.quit()

        QTimer.singleShot(3000, _finish)

    return app.exec()
