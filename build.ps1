$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# JDK 25 is required. Prefer an existing JAVA_HOME; otherwise fall back to the
# Android Studio JBR that is already on this machine.
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Android\Android Studio\jbr')) {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
}

$wrapper = Join-Path $PSScriptRoot 'gradlew.bat'
if (Test-Path $wrapper) {
    # The repo ships a wrapper, so no local Gradle installation is needed.
    & $wrapper --no-daemon clean jar
} else {
    $gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $gradle) { throw 'neither gradlew.bat nor a local Gradle installation was found' }
    & $gradle --no-daemon clean jar
}
if ($LASTEXITCODE -ne 0) { throw "gradle failed with exit code $LASTEXITCODE" }

$version = (Select-String -Path 'build.gradle.kts' -Pattern '^version\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
Write-Host "built: build/libs/sakana-prism-$version.jar"
