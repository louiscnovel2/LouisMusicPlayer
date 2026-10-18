# Music Player for Windows 11 - PowerShell launcher
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot
$env:QT_LOGGING_RULES = "qt.multimedia.ffmpeg*=false"

$venv = Join-Path $PSScriptRoot ".venv\Scripts\pythonw.exe"
if (Test-Path -LiteralPath $venv) {
    Start-Process -FilePath $venv -ArgumentList "-m", "player"
    return
}

$py = Get-Command pythonw -ErrorAction SilentlyContinue
if ($py) {
    Start-Process -FilePath $py.Source -ArgumentList "-m", "player"
    return
}

Write-Host "Python environment not found. Run install.bat first." -ForegroundColor Red
