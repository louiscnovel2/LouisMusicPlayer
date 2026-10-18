"""PyInstaller / direct-run entry point for Louis Music Player."""

import os

# Keep the FFmpeg multimedia backend quiet on the console.
os.environ.setdefault("QT_LOGGING_RULES", "qt.multimedia.ffmpeg*=false")

from player.main_window import run

if __name__ == "__main__":
    raise SystemExit(run())
