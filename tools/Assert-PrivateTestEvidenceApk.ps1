param(
    [Parameter(Mandatory)][string]$AdbPath,
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [ValidateSet('com.idvb.android.test', 'com.idvb.android.verification.test')]
    [string]$TestPackage = 'com.idvb.android.test'
)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/Test-MediaStorageBoundary.ps1" | Out-Null
$paths = & $AdbPath -s $Serial shell pm path $TestPackage
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the installed test APK.' }
$baseApks = @($paths | Where-Object { $_ -match '^package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk$' })
if ($baseApks.Count -ne 1) { throw 'No unambiguous installed test APK was found; refusing device tests.' }
$baseApk = $baseApks[0].Substring('package:'.Length)
# Read the packaged marker directly, without installing APKs or staging files on the device.
$contract = & $AdbPath -s $Serial shell unzip -p $baseApk assets/private-test-evidence.contract 2>&1
if ($LASTEXITCODE -ne 0 -or ($contract -join "`n").Trim() -ne 'IDVB private test evidence v1') {
    throw 'Installed test APK does not declare private evidence storage. Build with the media boundary check and use an isolated test environment before running it.'
}
Write-Output 'PASS: installed test APK declares the private evidence storage contract.'
