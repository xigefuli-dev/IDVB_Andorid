param([string]$RepositoryRoot = (Join-Path $PSScriptRoot '..'))

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$patterns = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'media-storage-boundary.rules') |
    ForEach-Object { $_.Trim() } | Where-Object { $_ -and !$_.StartsWith('#') })
if ($patterns.Count -eq 0) { throw 'Media storage boundary rules must not be empty.' }
$violations = [Collections.Generic.List[string]]::new()
$scopes = @('app/src', 'tools', '.verify', 'app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradlew', 'gradlew.bat')
foreach ($scope in $scopes) {
    $directory = Join-Path $root $scope
    if (!(Test-Path -LiteralPath $directory)) { continue }
    # Include ignored local scripts; exclude generated build trees and linked Gradle caches.
    if (Test-Path -LiteralPath $directory -PathType Leaf) {
        $paths = @($directory)
    } elseif (Get-Command rg -ErrorAction SilentlyContinue) {
        $paths = & rg --files --hidden --no-ignore $directory -g '*.kt' -g '*.java' -g '*.cpp' -g '*.h' -g '*.mk' -g '*.xml' -g '*.ps1' -g '*.py' -g '*.sh' -g '*.bat' -g '*.cmd' -g '*.js' -g '*.mjs' -g '*.gradle' -g '*.kts' -g '!**/build/**' -g '!**/gradle*/**' -g '!**/.gradle/**' -g '!**/node_modules/**'
        if ($LASTEXITCODE -gt 1) { throw "Cannot inventory media storage scope: $scope" }
    } else {
        $allowedExt = @('.kt', '.java', '.cpp', '.h', '.mk', '.xml', '.ps1', '.py', '.sh', '.bat', '.cmd', '.js', '.mjs', '.gradle', '.kts')
        $paths = @(Get-ChildItem -LiteralPath $directory -Recurse -File -Force -ErrorAction SilentlyContinue | Where-Object {
            $ext = $_.Extension.ToLowerInvariant()
            $full = $_.FullName.Replace('\', '/')
            ($ext -in $allowedExt) -and
            ($full -notmatch '/(build|gradle[^/]*|\.gradle|node_modules)/')
        } | ForEach-Object { $_.FullName })
    }
    foreach ($path in $paths) {
        if ([IO.Path]::GetFullPath($path) -eq $PSCommandPath) { continue }
        # Runtime source and executable verification scripts, never captured XML data.
        if ($scope -eq '.verify' -and [IO.Path]::GetExtension($path) -notin @('.ps1', '.py', '.sh', '.bat', '.cmd', '.js', '.mjs', '.gradle', '.kts')) { continue }
        $lineNumber = 0
        foreach ($line in [IO.File]::ReadLines($path)) {
            $lineNumber++
            foreach ($pattern in $patterns) {
                if ($line -match $pattern) {
                    $relative = [IO.Path]::GetRelativePath($root, $path)
                    $violations.Add("${relative}:${lineNumber}: $($line.Trim())")
                    break
                }
            }
        }
    }
}
if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Output $_ }
    throw "Media storage boundary failed: $($violations.Count) potential shared-media paths require review."
}
Write-Output 'PASS: app source, test source, build scripts and local verification scripts have no shared-media output APIs or paths.'
