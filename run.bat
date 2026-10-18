@echo off
rem Launch Music Player for Windows 11 (no console window).
setlocal
cd /d "%~dp0"
set "QT_LOGGING_RULES=qt.multimedia.ffmpeg*=false"

if exist ".venv\Scripts\pythonw.exe" (
    start "" ".venv\Scripts\pythonw.exe" -m player
    exit /b 0
)

where pythonw >nul 2>nul
if %errorlevel%==0 (
    start "" pythonw -m player
    exit /b 0
)

echo [!] Python environment not found. Run install.bat first.
pause
