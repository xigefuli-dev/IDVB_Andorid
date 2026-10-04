param([switch]$Download, [string]$RepositoryRoot = (Join-Path $PSScriptRoot '..'))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$toolchains = Join-Path $root '.verify/toolchains'
$ndk = Join-Path $toolchains 'android-ndk-r27d'
if (Test-Path -LiteralPath (Join-Path $ndk 'source.properties')) {
    $properties = Get-Content -LiteralPath (Join-Path $ndk 'source.properties') -Raw
    if ($properties -notmatch 'Pkg.Revision\s*=\s*27\.3\.13750724') { throw 'Unexpected native toolchain version' }
    Write-Output $ndk
    return
}
if (!$Download) { throw 'Native toolchain missing. Run tools/Initialize-NativeToolchain.ps1 -Download or set idvbNdkPath to NDK 27.3.13750724.' }
New-Item -ItemType Directory -Path $toolchains -Force | Out-Null
$archive = Join-Path $toolchains 'android-ndk-r27d-windows.zip'
if (!(Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/android-ndk-r27d-windows.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA1).Hash -ne '56607cbccd3642d4a1991f6bb3114a00f884f426') { throw 'Native toolchain archive checksum mismatch' }
Expand-Archive -LiteralPath $archive -DestinationPath $toolchains
if (!(Test-Path -LiteralPath (Join-Path $ndk 'ndk-build.cmd'))) { throw 'Native toolchain extraction failed' }
Write-Output $ndk
