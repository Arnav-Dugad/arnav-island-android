# Releasing

The app updates itself from this repository's releases. A release needs three things:
- a tag `v<version>` (for example `v1.0.1`)
- an asset `ArnavIsland-<version>.apk`, signed with the release key
- an asset `ArnavIsland-<version>.apk.sha256`

The app installs a release only when all of these hold:
- it is newer than the installed version (by `versionCode`)
- its SHA-256 matches the `.sha256` file
- it is signed with the same certificate as the installed app

## Steps

1. In `app/build.gradle.kts`, raise `versionCode` (by at least 1) and set `versionName`.
2. Write `docs/RELEASE-<version>.md`.
3. Build, test, sign and checksum:

   ```powershell
   $env:ARNAV_SHARE_PEER = 'C:\path\to\arnav-island\build\share_peer.exe'   # optional: the Windows interop test
   .\packaging\Build-Release.ps1
   ```

   This writes `out\ArnavIsland-<version>.apk` and its `.sha256`, and prints the signing certificate's SHA-256. The certificate must be `0b672d715cf12b52ded4d9467646d558549004813f7ed313dbf71f763b5aa1ca`.
4. Launch-test the optimised build on an emulator. R8 can strip classes that are only reached by reflection.
5. Publish:

   ```powershell
   gh release create v<version> out\ArnavIsland-<version>.apk out\ArnavIsland-<version>.apk.sha256 --title "Arnav Island for Android <version>" --notes-file docs\RELEASE-<version>.md
   ```

## The release key

`packaging/Sign-Android.ps1` makes the key on first use and keeps it in `%LOCALAPPDATA%\ArnavIslandAndroid\Signing`:
- `release.jks`, the keystore
- `password.dpapi`, its password, sealed with Windows DPAPI for the publishing account

**Back up both together.** Without them, no update can be signed, and people have to uninstall and reinstall the app by hand.
