@echo off
rem Launch Music Player with a console so logs / errors are visible.
setlocal
cd /d "%~dp0"
set "QT_LOGGING_RULES=qt.multimedia.ffmpeg*=false"

if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" -m player
    pause
    exit /b 0
)

where python >nul 2>nul
if %errorlevel%==0 (
    python -m player
    pause
    exit /b 0
)

echo [!] Python environment not found. Run install.bat first.
pause
