$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

$gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $gradle) { throw 'gradle.bat not found under %USERPROFILE%\.gradle\wrapper\dists' }

Set-Location $PSScriptRoot
& $gradle --no-daemon clean jar
if ($LASTEXITCODE -ne 0) { throw "gradle failed with exit code $LASTEXITCODE" }

$version = (Select-String -Path 'build.gradle.kts' -Pattern '^version\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
Write-Host "built: build/libs/sakana-prism-$version.jar"
