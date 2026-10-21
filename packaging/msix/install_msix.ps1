param(
    [string]$PackagePath = ""
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")

if (-not $PackagePath) {
    $PackagePath = Get-ChildItem -LiteralPath (Join-Path $root "dist") -Filter "LouisMusicPlayer-*-x64.msix" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

if (-not $PackagePath -or -not (Test-Path -LiteralPath $PackagePath)) {
    throw "MSIX ファイルが見つかりません。先に build_msix.ps1 を実行してください。"
}

$PackagePath = (Resolve-Path -LiteralPath $PackagePath).Path

$signature = Get-AuthenticodeSignature -LiteralPath $PackagePath
if (-not $signature.SignerCertificate) {
    throw "MSIX に署名がありません。build_msix.ps1 で署名済みのパッケージを作成してください。"
}

$thumbprint = $signature.SignerCertificate.Thumbprint
$trustedPath = "Cert:\LocalMachine\TrustedPeople\$thumbprint"
$isTrusted = Test-Path -LiteralPath $trustedPath

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isTrusted -and -not $isAdmin) {
    Write-Host "署名証明書を LocalMachine\TrustedPeople に登録するため、管理者権限で再実行します。"
    $hostExe = (Get-Process -Id $PID).Path
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -PackagePath `"$PackagePath`""
    Start-Process -FilePath $hostExe -Verb RunAs -ArgumentList $arguments
    exit
}

if (-not $isTrusted) {
    $tempCert = Join-Path $env:TEMP ("LouisMusicPlayer-msix-" + [Guid]::NewGuid().ToString("N") + ".cer")
    try {
        Export-Certificate -Cert $signature.SignerCertificate -FilePath $tempCert -Force | Out-Null
        Import-Certificate -FilePath $tempCert -CertStoreLocation "Cert:\LocalMachine\TrustedPeople" | Out-Null
    } finally {
        Remove-Item -LiteralPath $tempCert -Force -ErrorAction SilentlyContinue
    }
}

Add-AppxPackage -Path $PackagePath -ForceUpdateFromAnyVersion

$package = Get-AppxPackage -Name "LouisMusicPlayer.Desktop"
Write-Host "インストールしました: $($package.PackageFullName)"
Write-Host "スタートメニューの「Louis Music Player」から起動できます。"
