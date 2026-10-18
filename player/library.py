"""Music library scanning, tag reading and hi-res detection.

Tag reading uses ``mutagen`` when available and falls back to filename parsing
so the player stays usable even for exotic containers.  Artwork is returned as
raw bytes and rendered by the UI layer.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Iterable

try:  # mutagen is optional at runtime
    from mutagen import File as MutagenFile
except Exception:  # pragma: no cover - mutagen missing
    MutagenFile = None


# Extensions the player can open.  The bundled FFmpeg backend decodes all of
# these; mutagen reads tags/quality info for most of them.
AUDIO_EXTS = {
    ".mp3", ".mp2", ".m4a", ".m4b", ".m4r", ".m4p", ".mp4", ".aac", ".alac",
    ".flac", ".wav", ".wave", ".aiff", ".aif", ".aifc", ".ogg", ".oga", ".opus",
    ".wma", ".asf", ".ape", ".wv", ".dsf", ".dff", ".mka", ".mpc", ".ac3",
    ".tta", ".spx", ".caf", ".amr", ".3gp", ".w64", ".rf64",
}

EXT_LABEL = {
    ".flac": "FLAC", ".wav": "WAV", ".wave": "WAV", ".m4a": "M4A", ".mp3": "MP3",
    ".dsf": "DSF", ".dff": "DFF", ".ogg": "OGG", ".opus": "OPUS", ".aac": "AAC",
    ".wma": "WMA", ".aiff": "AIFF", ".aif": "AIFF", ".ape": "APE", ".wv": "WV",
}


@dataclass(slots=True)
class Track:
    path: str
    title: str = ""
    artist: str = ""
    album: str = ""
    track_no: int = 0
    year: str = ""
    duration: float = 0.0
    sample_rate: int = 0
    bits: int = 0
    channels: int = 0
    bitrate: int = 0
    cover: bytes | None = None
    _loaded: bool = field(default=False, repr=False)

    @property
    def ext(self) -> str:
        return os.path.splitext(self.path)[1].lower()

    @property
    def ext_label(self) -> str:
        return EXT_LABEL.get(self.ext, self.ext.lstrip(".").upper())

    @property
    def is_hires(self) -> bool:
        """Heuristic matching the 'HR' (hi-res) badge."""
        if self.ext in {".dsf", ".dff"}:
            return True
        if self.bits and self.bits > 16:
            return True
        if self.sample_rate and self.sample_rate > 48000:
            return True
        return False

    @property
    def spec_text(self) -> str:
        """Compact quality string, e.g. ``FLAC 24bit/96kHz``."""
        parts = [self.ext_label]
        if self.bits and self.ext in {".flac", ".wav", ".wave", ".aiff", ".aif", ".alac", ".m4a", ".dsf", ".dff"}:
            parts.append(f"{self.bits}bit")
        if self.sample_rate:
            khz = self.sample_rate / 1000.0
            parts.append(f"{khz:g}kHz")
        elif self.bitrate:
            parts.append(f"{self.bitrate // 1000}kbps")
        return " / ".join(parts)

    @property
    def display_title(self) -> str:
        return self.title or os.path.splitext(os.path.basename(self.path))[0]

    @property
    def display_artist(self) -> str:
        return self.artist or "Unknown Artist"

    @property
    def display_album(self) -> str:
        return self.album or "Unknown Album"


def sort_by_track_number(tracks):
    """Order tracks by their track-number tag (ascending), then title.

    Tracks without a number go last, matching the Android player.
    """
    def key(t):
        has_no = 1 if t.track_no and t.track_no > 0 else 0
        return (0 if has_no else 1, t.track_no if has_no else 0, t.display_title.lower())

    return sorted(tracks, key=key)


def _first(value) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        return str(value[0]).strip() if value else ""
    return str(value).strip()


def _extract_cover(audio) -> bytes | None:
    """Best-effort cover art extraction across container types."""
    try:
        tags = audio.tags
        if not tags:
            return None
    except Exception:
        return None

    # ID3 (MP3/WAV/AIFF) APIC frames
    try:
        apics = tags.getall("APIC")
        if apics:
            return bytes(apics[0].data)
    except Exception:
        pass

    # MP4 / M4A
    try:
        covr = tags.get("covr")
        if covr:
            return bytes(covr[0])
    except Exception:
        pass

    # FLAC pictures
    try:
        if getattr(audio, "pictures", None):
            return bytes(audio.pictures[0].data)
    except Exception:
        pass
    try:
        pics = tags.get("metadata_block_picture")
        if pics:
            import base64

            from mutagen.flac import Picture

            return bytes(Picture(base64.b64decode(pics[0])).data)
    except Exception:
        pass

    # ASF / WMA
    try:
        for key in ("WM/Picture", "WM/AlbumCoverURL"):
            if key in tags:
                pic = tags[key][0]
                data = getattr(pic, "value", None)
                if isinstance(data, bytes):
                    return data
    except Exception:
        pass

    # Ogg / Vorbis base64 picture
    try:
        pic = tags.get("coverart") or tags.get("picture")
        if pic:
            import base64

            return base64.b64decode(pic[0])
    except Exception:
        pass

    return None


def _parse_track_no(value: str) -> int:
    try:
        return int(str(value).split("/")[0])
    except (ValueError, AttributeError):
        return 0


def read_track(path: str, with_cover: bool = True) -> Track:
    """Read metadata for a single file, degrading gracefully."""
    track = Track(path=path)
    if MutagenFile is None:
        return track
    try:
        audio = MutagenFile(path, easy=True)
    except Exception:
        audio = None

    if audio is None:
        return track

    try:
        tags = audio.tags or {}
        track.title = _first(tags.get("title"))
        track.artist = _first(tags.get("artist")) or _first(tags.get("albumartist"))
        track.album = _first(tags.get("album"))
        track.track_no = _parse_track_no(_first(tags.get("tracknumber")))
        track.year = _first(tags.get("date"))[:4]
    except Exception:
        pass

    try:
        info = audio.info
        track.duration = float(getattr(info, "length", 0.0) or 0.0)
        track.sample_rate = int(getattr(info, "sample_rate", 0) or 0)
        track.bits = int(getattr(info, "bits_per_sample", 0) or 0)
        track.channels = int(getattr(info, "channels", 0) or 0)
        track.bitrate = int(getattr(info, "bitrate", 0) or 0)
    except Exception:
        pass

    if with_cover:
        try:
            audio_full = MutagenFile(path)
            if audio_full is not None:
                track.cover = _extract_cover(audio_full)
                if not track.duration:
                    track.duration = float(getattr(audio_full.info, "length", 0.0) or 0.0)
        except Exception:
            pass

    return track


def scan_folder(folder: str, recursive: bool = True) -> list[str]:
    """Return a sorted list of audio file paths inside *folder*."""
    results: list[str] = []
    if recursive:
        for root, _dirs, files in os.walk(folder):
            for name in files:
                if os.path.splitext(name)[1].lower() in AUDIO_EXTS:
                    results.append(os.path.join(root, name))
    else:
        for name in os.listdir(folder):
            full = os.path.join(folder, name)
            if os.path.isfile(full) and os.path.splitext(name)[1].lower() in AUDIO_EXTS:
                results.append(full)
    results.sort(key=lambda p: (os.path.dirname(p).lower(), os.path.basename(p).lower()))
    return results


def natural_sort_tracks(tracks: Iterable[Track]) -> list[Track]:
    import re

    def key(t: Track):
        name = os.path.basename(t.path)
        return [int(c) if c.isdigit() else c.lower() for c in re.split(r"(\d+)", name)]

    return sorted(tracks, key=key)
