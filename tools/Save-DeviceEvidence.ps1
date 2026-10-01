#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$AdbPath,
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [Parameter(Mandatory)][ValidateSet('Screenshot', 'TestArtifacts')][string]$Kind,
    [ValidateSet('com.idvb.android.test', 'com.idvb.android')][string]$EvidencePackage = 'com.idvb.android.test',
    [Parameter(Mandatory)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$verification = Join-Path $repository '.verify'
$destination = [IO.Path]::GetFullPath($OutputPath, $repository)
if (!$destination.StartsWith($verification + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Device evidence must stay inside this repository''s .verify directory.'
}
# A linked directory could redirect a seemingly local output into a photo library.
$ancestor = [IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($destination))
while ($null -ne $ancestor) {
    if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Device evidence destination crosses a linked directory: $($ancestor.FullName)"
    }
    $ancestor = $ancestor.Parent
}
if (Test-Path -LiteralPath $destination) {
    if ((Get-Item -LiteralPath $destination -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Device evidence must not overwrite a linked file.'
    }
}
$adb = (Get-Item -LiteralPath $AdbPath).FullName
& "$PSScriptRoot/Test-MediaStorageBoundary.ps1" | Out-Null
$arguments = @('-s', $Serial, 'exec-out') + $(if ($Kind -eq 'Screenshot') {
    # Stream PNG bytes directly to the host; no screenshot file is created on the device.
    @('screencap', '-p')
} else {
    # Only private test evidence is exported. Target-UID instrumentation can use the app's internal directory.
    @('run-as', $EvidencePackage, 'tar', '-cf', '-', '-C', 'files', 'test-evidence')
})
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
$temporary = $destination + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
$process = [Diagnostics.Process]::new()
$process.StartInfo = [Diagnostics.ProcessStartInfo]::new($adb)
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.CreateNoWindow = $true
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.RedirectStandardError = $true
foreach ($argument in $arguments) { $process.StartInfo.ArgumentList.Add($argument) }
try {
    $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        if (!$process.Start()) { throw 'Cannot start the ADB evidence client.' }
        $stderr = $process.StandardError.ReadToEndAsync()
        $copy = $process.StandardOutput.BaseStream.CopyToAsync($stream)
        if (!$process.WaitForExit(30000)) {
            $process.Kill()
            $process.WaitForExit()
            throw 'ADB evidence capture timed out.'
        }
        $copy.GetAwaiter().GetResult() | Out-Null
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "ADB evidence capture failed: $errorText" }
        if ($stream.Length -eq 0) { throw 'ADB returned empty evidence.' }
    } finally {
        $stream.Dispose()
    }
    if ($Kind -eq 'Screenshot') {
        $signature = [IO.File]::OpenRead($temporary)
        try {
            $bytes = [byte[]]::new(8)
            if ($signature.Read($bytes, 0, 8) -ne 8 -or
                [BitConverter]::ToString($bytes) -ne '89-50-4E-47-0D-0A-1A-0A') {
                throw 'ADB did not return a PNG screenshot.'
            }
        } finally { $signature.Dispose() }
    }
    Move-Item -LiteralPath $temporary -Destination $destination -Force
    Write-Output "Saved $Kind evidence to $destination"
} finally {
    $process.Dispose()
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
