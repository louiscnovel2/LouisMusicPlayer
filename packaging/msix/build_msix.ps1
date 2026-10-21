param(
    [string]$Version = "",
    [string]$IdentityName = "LouisMusicPlayer.Desktop",
    [string]$Publisher = "CN=Louis Music Player",
    [string]$PublisherDisplayName = "Louis C Novel",
    [string]$Architecture = "x64",
    [string]$OutputName = "",
    [string]$CertificateOutputName = "LouisMusicPlayer-msix",
    [switch]$NoSign
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$exe = Join-Path $root "dist\Louis Music Player.exe"
$outDir = Join-Path $root "dist"
$stage = Join-Path $root "build\msix\stage"
$manifestSource = Join-Path $PSScriptRoot "Package.appxmanifest"
$iconSource = Join-Path $root "assets\icon_512.png"

if (-not (Test-Path -LiteralPath $exe)) {
    throw "dist\Louis Music Player.exe がありません。先に build_exe.bat を実行してください。"
}

if (-not $Version) {
    $init = Get-Content -LiteralPath (Join-Path $root "player\__init__.py") -Raw -Encoding UTF8
    if ($init -match '__version__\s*=\s*"([^"]+)"') {
        $Version = $Matches[1]
    } else {
        throw "player\__init__.py からバージョンを取得できませんでした。"
    }
}
if ($Version -match '^\d+\.\d+\.\d+$') {
    $Version = "$Version.0"
}
if ($Version -notmatch '^\d+\.\d+\.\d+\.\d+$') {
    throw "バージョンは 1.4.3.0 のような 4 桁形式で指定してください: $Version"
}

function Find-SdkTool {
    param([string]$Name)
    $roots = @(
        (Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"),
        (Join-Path $env:ProgramFiles "Windows Kits\10\bin")
    )
    $tools = foreach ($toolRoot in $roots) {
        if (Test-Path -LiteralPath $toolRoot) {
            Get-ChildItem -LiteralPath $toolRoot -Recurse -Filter "$Name.exe" -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match "\\$Architecture\\" }
        }
    }
    $tool = $tools | Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $tool) {
        throw "$Name.exe が見つかりません。Windows SDK をインストールしてください。"
    }
    return $tool.FullName
}

$makeappx = Find-SdkTool "makeappx"
$signtool = Find-SdkTool "signtool"

if (Test-Path -LiteralPath $stage) {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
$assets = Join-Path $stage "Assets"
New-Item -ItemType Directory -Path $assets -Force | Out-Null

Copy-Item -LiteralPath $exe -Destination (Join-Path $stage "Louis Music Player.exe")

Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile($iconSource)
$background = [System.Drawing.Color]::FromArgb(255, 11, 14, 18)

function New-Logo {
    param(
        [int]$Width,
        [int]$Height,
        [string]$Name,
        [bool]$WithBackground = $false
    )
    $bitmap = New-Object System.Drawing.Bitmap($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        if ($WithBackground) {
            $graphics.Clear($background)
        } else {
            $graphics.Clear([System.Drawing.Color]::Transparent)
        }
        $pad = [Math]::Max(2, [int]([Math]::Min($Width, $Height) * 0.06))
        $scale = [Math]::Min(
            ($Width - 2 * $pad) / $source.Width,
            ($Height - 2 * $pad) / $source.Height
        )
        $drawWidth = [int]($source.Width * $scale)
        $drawHeight = [int]($source.Height * $scale)
        $x = [int](($Width - $drawWidth) / 2)
        $y = [int](($Height - $drawHeight) / 2)
        $graphics.DrawImage($source, $x, $y, $drawWidth, $drawHeight)
    } finally {
        $graphics.Dispose()
    }
    try {
        $bitmap.Save((Join-Path $assets $Name), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
}

function New-LogoSet {
    param(
        [string]$BaseName,
        [int]$Width,
        [int]$Height,
        [int[]]$Scales,
        [bool]$WithBackground = $false
    )
    foreach ($scale in $Scales) {
        $suffix = if ($scale -eq 100) { "" } else { ".scale-$scale" }
        $scaledWidth = [int][Math]::Round($Width * $scale / 100)
        $scaledHeight = [int][Math]::Round($Height * $scale / 100)
        New-Logo $scaledWidth $scaledHeight "$BaseName$suffix.png" $WithBackground
    }
}

New-LogoSet "Square44x44Logo" 44 44 @(100, 125, 150, 200, 400)
New-LogoSet "Square150x150Logo" 150 150 @(100, 125, 150, 200, 400)
New-LogoSet "Square310x310Logo" 310 310 @(100, 200)
New-LogoSet "Wide310x150Logo" 310 150 @(100, 200) $true
New-LogoSet "StoreLogo" 50 50 @(100, 125, 150, 200)
New-LogoSet "SplashScreen" 620 300 @(100, 200) $true
$source.Dispose()

$manifest = [System.IO.File]::ReadAllText($manifestSource, [System.Text.Encoding]::UTF8)
$manifest = $manifest -replace '(<Identity\b[^>]*\bName=")[^"]*(")', "`${1}$IdentityName`${2}"
$manifest = $manifest -replace '(<Identity\b[^>]*\bVersion=")[^"]*(")', "`${1}$Version`${2}"
$manifest = $manifest -replace '(<Identity\b[^>]*\bPublisher=")[^"]*(")', "`${1}$Publisher`${2}"
$manifest = $manifest -replace '(<Identity\b[^>]*\bProcessorArchitecture=")[^"]*(")', "`${1}$Architecture`${2}"
$manifest = $manifest -replace '(<PublisherDisplayName>)[^<]*(</PublisherDisplayName>)', "`${1}$PublisherDisplayName`${2}"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $stage "AppxManifest.xml"), $manifest, $utf8)

if (-not $OutputName) {
    $OutputName = "LouisMusicPlayer-$Version-$Architecture"
}
$out = Join-Path $outDir "$OutputName.msix"
if (Test-Path -LiteralPath $out) {
    Remove-Item -LiteralPath $out -Force
}
& $makeappx pack /d $stage /p $out /o
if ($LASTEXITCODE -ne 0) {
    throw "makeappx によるパッケージ作成に失敗しました。"
}

if (-not $NoSign) {
    $cert = Get-ChildItem Cert:\CurrentUser\My |
        Where-Object { $_.Subject -eq $Publisher -and $_.HasPrivateKey } |
        Sort-Object NotAfter -Descending |
        Select-Object -First 1
    if (-not $cert) {
        $cert = New-SelfSignedCertificate `
            -Type CodeSigningCert `
            -Subject $Publisher `
            -KeyUsage DigitalSignature `
            -FriendlyName "Louis Music Player MSIX" `
            -CertStoreLocation "Cert:\CurrentUser\My" `
            -NotAfter (Get-Date).AddYears(5)
    }
    Export-Certificate -Cert $cert -FilePath (Join-Path $outDir "$CertificateOutputName.cer") -Force | Out-Null
    & $signtool sign /fd SHA256 /sha1 $cert.Thumbprint /d "Louis Music Player" $out
    if ($LASTEXITCODE -ne 0) {
        throw "signtool による署名に失敗しました。"
    }
}

Write-Host ""
Write-Host "MSIX: $out"
if (-not $NoSign) {
    Write-Host "証明書: $(Join-Path $outDir "$CertificateOutputName.cer")"
    Write-Host "インストール: packaging\msix\install_msix.ps1 を実行してください。"
}
