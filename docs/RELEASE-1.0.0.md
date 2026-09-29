# Arnav Island for Android 1.0.0: your island, in your hand

The first release of the phone companion for [Arnav Island](https://github.com/Arnav-Dugad/arnav-island). It needs Arnav Island **0.19** or later on your PC, with **Settings › Sharing › Share with my PCs** on.

## The remote
- What plays on your PC, with its cover: scrub, play and pause, skip, volume and mute.
- The app takes the song's colours: the light behind the glass and the accent ease from one cover to the next.
- **Quick actions:**
  - paste your clipboard on the PC
  - copy the PC's clipboard here
  - open a link on the PC
  - lock the PC
- The PC's battery, CPU and weather.

## Files, both ways
- Photos, videos and files, at full quality and any size, from the app or from **Share › Send to PC** in any app.
- Files from your PC land in **Downloads › Arnav Island**, with progress, speed and time left as they come.
- Take anything from your PC's **Shelf** with a tap.

## Music handoff
- Music your PC continues on this phone plays from where the PC was. The song's file comes over when the PC plays one; otherwise it opens in your phone's music app.
- Send it back to the PC with one tap.

## On your PC's island
- Your phone's notifications, with each app's icon.
- Your phone's battery on its Nearby row, and a card when it runs low.
- **Find my phone:** *Ring* rings the phone loudly, even on silent.

## Liquid glass
- Refraction, blur and saturation behind every surface.
- A rim of light that follows the phone's tilt.
- A glass tab bar with a drop that follows your swipes.
- An island at the top that widens for transfers and drops open for news.
- Springs throughout. Dark and light themes.

## Updates
- The app checks its GitHub releases twice a day and installs new versions itself. Each download is checked against its SHA-256 and against the app's signing certificate.
- **Devices › Release history** lists every version and its notes.

## Checks
- **Protocol:** the phone engine was tested against the real Windows sharing engine (`share_peer`) on every feature:
  - pairing codes match
  - files both ways, including a folder
  - Shelf list and take
  - the remote: status, cover once, then *unchanged*; media, volume, clipboard both ways; lock refused when not allowed
  - battery and notification notices
  - find my phone
  - music both ways, with the song's file
- **Unit tests:** 8 passing (link, interop, updater).
- **On an emulator (Android 16) against that engine:**
  - pairing, the remote live with position and volume, and copy from the PC
  - receiving a file, and taking from the Shelf
  - music with its file, playing here, and sending it back
  - find my phone
  - notification mirroring with an app icon
  - share from another app
  - dark and light themes
- **Optimised build:** R8 on, launch-tested on the emulator (every tab and the pairing sheet, no crashes), and installed on a Galaxy S23+ (Android 16) for a crash check.
