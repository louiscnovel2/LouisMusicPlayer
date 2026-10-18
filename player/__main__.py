"""Entry point: ``python -m player``."""

import os

# Keep the FFmpeg multimedia backend from spamming the console.
os.environ.setdefault("QT_LOGGING_RULES", "qt.multimedia.ffmpeg*=false")

from .main_window import run

if __name__ == "__main__":
    raise SystemExit(run())
