$ErrorActionPreference = "Stop"

$package = Get-AppxPackage -Name "LouisMusicPlayer.Desktop"
if (-not $package) {
    Write-Host "Louis Music Player はインストールされていません。"
    exit 0
}

$package | Remove-AppxPackage
Write-Host "アンインストールしました。"
