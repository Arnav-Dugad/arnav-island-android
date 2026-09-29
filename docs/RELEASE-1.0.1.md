# Arnav Island for Android 1.0.1: notes that read well

If you have 1.0.0, this version installs itself. It's the first update that arrives on its own.

## Fixed
- **Release notes read as written.** In *What's new* and the release history:
  - links show their words, not the web address
  - nested points are indented under the point they belong to
  - numbered steps keep their numbers
  - the release's own title is no longer repeated under the sheet's

## Checks
- **Unit tests:** 8 passing (link, interop, updater).
- **Optimised build:** launch-tested on an emulator.
- **The update path:** 0.9.9 → 1.0.0 was installed by the app itself on an emulator: downloaded from GitHub, checked against its SHA-256 and signing certificate, and installed without a prompt.
