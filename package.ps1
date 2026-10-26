#Requires -Version 5.1
<#
    Packages Sakana Prism and installs it into Salt Player for Windows.

    The normal release run is one command:

        .\package.ps1 -Bump

      1. -Bump increments the patch version in build.gradle.kts + plugin.properties
      2. rebuilds build\libs\sakana-prism-<version>.jar
      3. writes <OutputRoot>\<version>\ with .spmod + source zip + cover + README
      4. copies the newest .spmod + cover up into <OutputRoot>
      5. unpacks it into Salt Player's workshop folder and restarts the player

    Switches:
        -Version 0.4.6   package an explicit version instead of bumping
        -NoBuild         reuse the jar that is already in build\libs
        -NoInstall       only produce the release folder
        -NoRestart       install the files but leave Salt Player alone

    <OutputRoot> defaults to <repo>\dist. Point it somewhere else with -OutputRoot, or
    drop a local.settings.ps1 next to this script (git-ignored) containing:

        $OutputRoot = 'D:\some\release\folder'

    The .spmod is a zip containing one top-level folder, `classes`, which the host
    loads as the plugin classpath. The jar's own manifest travels inside `classes`;
    the top-level manifest is a plain stub.
#>
[CmdletBinding()]
param(
    [string]$Version = '',
    [string]$OutputRoot = '',
    [switch]$Bump,
    [switch]$NoBuild,
    [switch]$NoInstall,
    [switch]$NoRestart,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$PLAYER = 'Salt Player for Windows'
$PLUGIN_ID = 'dev.sakana.spw.prism'
$PLAYER_DIR = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows'

if (-not $OutputRoot) {
    # Local, uncommitted settings win over the default so the repo stays portable.
    $localSettings = Join-Path $PSScriptRoot 'local.settings.ps1'
    if (Test-Path $localSettings) { . $localSettings }
}
if (-not $OutputRoot) { $OutputRoot = Join-Path $PSScriptRoot 'dist' }

function Read-Version {
    (Select-String -Path 'build.gradle.kts' -Pattern '^version\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Set-Version([string]$Value) {
    $gradlePath = Join-Path $PSScriptRoot 'build.gradle.kts'
    $gradle = [System.IO.File]::ReadAllText($gradlePath, [System.Text.Encoding]::UTF8)
    $gradle = [regex]::Replace($gradle, '(?m)^version\s*=\s*".*"', "version = `"$Value`"")
    Write-Utf8NoBom $gradlePath $gradle

    $propsPath = Join-Path $PSScriptRoot 'src\main\resources\META-INF\plugin.properties'
    $properties = [System.IO.File]::ReadAllText($propsPath, [System.Text.Encoding]::UTF8)
    $properties = [regex]::Replace($properties, '(?m)^plugin\.version\s*=.*$', "plugin.version=$Value")
    Write-Utf8NoBom $propsPath $properties
}

function Format-Size([long]$Bytes) {
    if ($Bytes -ge 1MB) { '{0:N2} MB' -f ($Bytes / 1MB) } else { '{0:N1} KB' -f ($Bytes / 1KB) }
}

function Add-ZipFile {
    param($Zip, [string]$EntryName, $SourceStream)
    $entry = $Zip.CreateEntry($EntryName, [System.IO.Compression.CompressionLevel]::Optimal)
    $target = $entry.Open()
    try { $SourceStream.CopyTo($target) } finally { $target.Dispose() }
}

function Install-Plugin([string]$Spmod) {
    $target = Join-Path $env:APPDATA "Salt Player for Windows\workshop\plugins\$PLUGIN_ID"
    $classes = Join-Path $target 'classes'
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Spmod)
    try {
        foreach ($item in $zip.Entries) {
            if (-not $item.FullName.StartsWith('classes/') -or $item.FullName.EndsWith('/')) { continue }
            $relative = $item.FullName.Substring('classes/'.Length).Replace('/', '\')
            $destination = Join-Path $classes $relative
            New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($item, $destination, $true)
        }
    } finally { $zip.Dispose() }
    Write-Host "installed -> $classes"
}

function Restart-Player {
    $appId = '3009140'
    $appIdFile = Join-Path $PLAYER_DIR 'steam_appid.txt'
    if (Test-Path $appIdFile) { $appId = (Get-Content $appIdFile -Raw).Trim() }

    $running = @(Get-Process -Name $PLAYER -ErrorAction SilentlyContinue)
    if ($running.Count -gt 0) {
        Write-Host "restarting $PLAYER ..."
        $main = $running | Where-Object { $_.MainWindowTitle } | Select-Object -First 1
        if ($main) { $null = $main.CloseMainWindow() } else { $running | Stop-Process -Force }
        $deadline = (Get-Date).AddSeconds(10)
        while ((Get-Date) -lt $deadline -and @(Get-Process -Name $PLAYER -ErrorAction SilentlyContinue).Count -gt 0) {
            Start-Sleep -Milliseconds 300
        }
        Get-Process -Name $PLAYER -ErrorAction SilentlyContinue | Stop-Process -Force
        Start-Sleep -Seconds 2
    }
    Start-Process "steam://rungameid/$appId"
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline -and @(Get-Process -Name $PLAYER -ErrorAction SilentlyContinue).Count -eq 0) {
        Start-Sleep -Milliseconds 500
    }
    $alive = @(Get-Process -Name $PLAYER -ErrorAction SilentlyContinue)
    if ($alive.Count -eq 0) {
        Write-Warning "$PLAYER did not come back up - open it manually."
    } else {
        Write-Host ("{0} is running (pid {1}) with the freshly installed plugin" -f $PLAYER, ($alive | Select-Object -First 1).Id)
    }
}

# --- version ------------------------------------------------------------------
if ($Bump -and $Version) { throw 'use either -Bump or -Version, not both' }
if ($Bump) {
    $current = Read-Version
    $parts = @($current.Split('.'))
    if ($parts.Count -lt 3) { throw "unexpected version '$current'" }
    $parts[2] = ([int]$parts[2] + 1).ToString()
    $Version = $parts -join '.'
    Set-Version $Version
    Write-Host "version: $current -> $Version"
} elseif (-not $Version) {
    $Version = Read-Version
}
if (-not $Version) { throw 'cannot read version from build.gradle.kts' }

# --- build --------------------------------------------------------------------
$jar = Join-Path $PSScriptRoot "build\libs\sakana-prism-$Version.jar"
if (-not $NoBuild) {
    & (Join-Path $PSScriptRoot 'build.ps1')
    if (-not (Test-Path $jar)) { throw "build.ps1 finished but $jar is missing" }
} elseif (-not (Test-Path $jar)) {
    throw "missing $jar - run .\build.ps1 first"
}

$properties = Get-Content 'src\main\resources\META-INF\plugin.properties' -Raw
if ($properties -notmatch "plugin\.version\s*=\s*$([regex]::Escape($Version))\b") {
    throw 'src\main\resources\META-INF\plugin.properties still declares another version'
}

# --- plugin package -----------------------------------------------------------
$release = Join-Path $OutputRoot $Version
New-Item -ItemType Directory -Force -Path $release | Out-Null
$spmod = Join-Path $release "Sakana-Prism-v$Version.spmod"
$source = Join-Path $release "Sakana-Prism-v$Version-source.zip"
foreach ($path in @($spmod, $source)) {
    if (Test-Path $path) { Remove-Item $path -Force }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$stub = [System.Text.Encoding]::ASCII.GetBytes("Manifest-Version: 1.0`r`n`r`n")
$count = 0
$zip = [System.IO.Compression.ZipFile]::Open($spmod, 'Create')
try {
    $entry = $zip.CreateEntry('META-INF/MANIFEST.MF', [System.IO.Compression.CompressionLevel]::Optimal)
    $stream = $entry.Open()
    try { $stream.Write($stub, 0, $stub.Length) } finally { $stream.Dispose() }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($item in $archive.Entries) {
            if ($item.FullName.EndsWith('/')) { continue }
            $input = $item.Open()
            try { Add-ZipFile -Zip $zip -EntryName "classes/$($item.FullName)" -SourceStream $input } finally { $input.Dispose() }
            $count++
        }
    } finally { $archive.Dispose() }
} finally { $zip.Dispose() }

# --- source package -----------------------------------------------------------
$files = @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'build.ps1', 'package.ps1', 'README.md', 'THIRD-PARTY.md')
$trees = @('src', 'tools', 'assets')
$zip = [System.IO.Compression.ZipFile]::Open($source, 'Create')
try {
    foreach ($name in $files) {
        $path = Join-Path $PSScriptRoot $name
        if (-not (Test-Path $path)) { continue }
        $input = [System.IO.File]::OpenRead($path)
        try { Add-ZipFile -Zip $zip -EntryName $name -SourceStream $input } finally { $input.Dispose() }
    }
    foreach ($tree in $trees) {
        $root = Join-Path $PSScriptRoot $tree
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem $root -Recurse -File | ForEach-Object {
            $relative = $_.FullName.Substring($PSScriptRoot.Length + 1).Replace('\', '/')
            $input = [System.IO.File]::OpenRead($_.FullName)
            try { Add-ZipFile -Zip $zip -EntryName $relative -SourceStream $input } finally { $input.Dispose() }
        }
    }
} finally { $zip.Dispose() }

# --- release extras -----------------------------------------------------------
foreach ($name in @('README.md', 'THIRD-PARTY.md', 'assets\steam-workshop.md', 'assets\Sakana-Prism-workshop-cover.png')) {
    $path = Join-Path $PSScriptRoot $name
    if (Test-Path $path) {
        Copy-Item $path (Join-Path $release (Split-Path $name -Leaf)) -Force
    }
}

# Keep a "latest" copy next to the version folders so the current build is one click
# away; <OutputRoot>\<version>\ stays the archive for that release. The root only ever
# holds the newest package, so older root copies are removed.
Get-ChildItem $OutputRoot -File -Filter 'Sakana-Prism-v*.spmod' -ErrorAction SilentlyContinue | Remove-Item -Force
foreach ($file in @($spmod, (Join-Path $release 'Sakana-Prism-workshop-cover.png'))) {
    if (Test-Path $file) {
        Copy-Item $file (Join-Path $OutputRoot (Split-Path $file -Leaf)) -Force
    }
}

Write-Host ("release: {0}" -f $release)
Write-Host ("spmod  : {0}  ({1} classes files, {2})" -f (Split-Path $spmod -Leaf), $count, (Format-Size (Get-Item $spmod).Length))
Write-Host ("source : {0}  ({1})" -f (Split-Path $source -Leaf), (Format-Size (Get-Item $source).Length))

# --- install into Salt Player -------------------------------------------------
if (-not $NoInstall) {
    Install-Plugin $spmod
    if (-not $NoRestart) { Restart-Player }
}
