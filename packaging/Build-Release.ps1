# Builds, tests, signs and checksums a release of Arnav Island for Android:
#   out\ArnavIsland-<version>.apk and out\ArnavIsland-<version>.apk.sha256 (what the app's updater downloads and checks).
# Set ARNAV_SHARE_PEER to the Windows island's share_peer.exe to run the phone-against-Windows interop test as well.
param([string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = $JavaHome
Push-Location $root
try {
    $version = (Select-String -Path 'app\build.gradle.kts' -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
    # Gradle writes warnings to stderr; Windows PowerShell would stop on them, so only its exit code decides.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE; $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw 'The build or its tests failed' }
    $unsigned = Get-ChildItem 'app\build\outputs\apk\release\*.apk' | Select-Object -First 1
    New-Item -ItemType Directory -Force -Path 'out' | Out-Null
    $apk = Join-Path $root "out\ArnavIsland-$version.apk"
    & (Join-Path $PSScriptRoot 'Sign-Android.ps1') -UnsignedApk $unsigned.FullName -OutputApk $apk -JavaHome $JavaHome
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLower()
    [IO.File]::WriteAllText("$apk.sha256", "$hash  ArnavIsland-$version.apk`n", (New-Object Text.UTF8Encoding($false)))
    Get-Item $apk, "$apk.sha256" | Select-Object Name, Length
    "SHA-256 $hash"
} finally { Pop-Location }
