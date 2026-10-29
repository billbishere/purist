# Builds Purist's installer (spec §12): the jar, a jpackage app image with its own runtime, then
# target\dist\Purist-<version>.msi from packaging\Purist.wxs with WiX 7.
# Usage: scripts\package.ps1 [-Version 0.1.0] [-SkipBuild]
param(
    [string]$Version = "0.1.0",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $SkipBuild) {
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
}

# jpackage puts every jar of --input on the class path: the app jar and its runtime dependencies.
$stage = Join-Path $root "target\jpackage-input"
$dist = Join-Path $root "target\dist"
foreach ($d in $stage, $dist) {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
    New-Item -ItemType Directory $d | Out-Null
}
Copy-Item "target\purist-0.1.0-SNAPSHOT.jar", "target\lib\*.jar" $stage

# The icon is drawn by AppIcon, the same code as the window icon; Purist.exe carries it to the shortcut and the MSI.
$icon = Join-Path $root "target\purist.ico"
java -cp "target\purist-0.1.0-SNAPSHOT.jar" purist.ui.theme.AppIcon $icon
if ($LASTEXITCODE -ne 0) { throw "icon failed" }

jpackage --type app-image --name Purist --app-version $Version --vendor Purist --icon $icon `
    --input $stage --main-jar purist-0.1.0-SNAPSHOT.jar --main-class purist.app.Main `
    --java-options "-XX:+UseZGC" --java-options "--enable-native-access=ALL-UNNAMED" `
    --dest $dist
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

$msi = Join-Path $dist "Purist-$Version.msi"
wix build "packaging\Purist.wxs" -arch x64 -d "Version=$Version" -d "AppDir=$dist\Purist" -o $msi
if ($LASTEXITCODE -ne 0) { throw "wix build failed" }
Get-Item $msi | Select-Object FullName, Length
