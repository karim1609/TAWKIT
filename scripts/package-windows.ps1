# Packaging script for Tawkit Desktop (change #13).
# Produces a real Windows installer (.exe or .msi) with a bundled JRE using
# jpackage (built into JDK 21+) — mosque staff just double-click to install.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1           # exe
#   powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1 -Type msi # msi
#
# Requirements: JDK 21+ on PATH (provides jpackage), Maven on PATH.

param(
    [ValidateSet("exe", "msi")]
    [string]$Type = "exe",
    [string]$AppName = "Tawkit",
    [string]$AppVersion = "1.0"
)

$ErrorActionPreference = "Stop"

Write-Host "==> Building the fat jar..." -ForegroundColor Cyan
mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$jar = "target/tawkit-desktop-$AppVersion-SNAPSHOT.jar"
if (-not (Test-Path $jar)) { throw "Jar not found: $jar" }

# jpackage copies every file from --input into the app folder, so stage only
# what the app needs: the jar plus the small MP3 decoder library. Hard links
# avoid duplicating the (large) jar on disk.
$stage = "target/package-input"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null
New-Item -ItemType HardLink -Path (Join-Path $stage (Split-Path $jar -Leaf)) `
    -Target (Resolve-Path $jar)
if (Test-Path "target/lib") {
    New-Item -ItemType Directory -Path (Join-Path $stage "lib") | Out-Null
    Get-ChildItem "target/lib" -File | ForEach-Object {
        New-Item -ItemType HardLink -Path (Join-Path $stage "lib" $_.Name) -Target $_.FullName
    }
}

Write-Host "==> Creating $Type installer with jpackage..." -ForegroundColor Cyan
# App jar + its lib/ folder + bundled JRE
jpackage `
    --type $Type `
    --name $AppName `
    --app-version $AppVersion `
    --input $stage `
    --main-jar $jar.Split('/')[-1] `
    --icon src/main/resources/assets/icon.png `
    --dest installer `
    --vendor "Tawkit Desktop team" `
    --description "Offline prayer times display for mosques and homes" `
    --java-options "-Dfile.encoding=UTF-8"

if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Write-Host "==> Done! Installer is in ./installer/" -ForegroundColor Green
Get-ChildItem installer | Format-Table Name, Length
