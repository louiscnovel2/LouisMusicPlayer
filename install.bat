@echo off
rem One-time setup: create .venv and install dependencies.
setlocal
cd /d "%~dp0"

set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY (
    where python >nul 2>nul && set "PY=python"
)
if not defined PY (
    echo [!] Python 3.10+ was not found. Install it from https://www.python.org/downloads/
    pause
    exit /b 1
)

echo [*] Using: %PY%
if not exist ".venv\Scripts\python.exe" (
    echo [*] Creating virtual environment...
    %PY% -m venv .venv
)

echo [*] Installing dependencies (PySide6, mutagen)...
".venv\Scripts\python.exe" -m pip install --upgrade pip
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if %errorlevel% neq 0 (
    echo [!] Installation failed.
    pause
    exit /b 1
)

echo.
echo [OK] Setup complete. Double-click run.bat to start Music Player.
pause
