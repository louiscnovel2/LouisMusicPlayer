@echo off
rem Build a standalone Windows executable (dist\Louis Music Player.exe).
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo [!] Virtual environment not found. Run install.bat first.
    pause
    exit /b 1
)

echo [*] Ensuring PyInstaller is installed...
".venv\Scripts\python.exe" -m pip install --upgrade pyinstaller >nul

echo [*] Building Louis Music Player.exe ...
".venv\Scripts\python.exe" -m PyInstaller --noconfirm --clean --onefile --windowed ^
    --name "Louis Music Player" ^
    --icon "assets\icon.ico" ^
    --version-file "version_info.txt" ^
    --add-data "assets;assets" ^
    --additional-hooks-dir "pyinstaller_hooks" ^
    --collect-data "_sounddevice_data" ^
    "main.py"

if %errorlevel% neq 0 (
    echo [!] Build failed.
    pause
    exit /b 1
)

echo.
echo [OK] Built: dist\Louis Music Player.exe
echo     This single .exe can be distributed and run on Windows 10/11 (64-bit).
pause
