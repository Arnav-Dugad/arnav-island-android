# Arnav Island for Android 1.10.0: clean

If you have 1.9.0, this version installs itself. Update your PC's island to 0.27.1 too: it fixes the island closing whenever your phone's camera started, and makes the phone a camera other apps can pick.

## Solid, after Apple's own apps
- **The glass is gone.** Grouped cards on Apple's grey (black in the dark), as Settings draws them, with no blur, bending or light on their edges.
- **Clean buttons and boxes:** flat fills, a solid colour for the main action, small tinted buttons inside rows, Apple's segmented controls and search field, and icons on flat colour.
- **A full-width tab bar,** opaque with a hairline on top, the chosen tab in the colour of what plays.
- **Rings, the volume dial and the graphs** in solid colour, with no glow.
- **Settings › Appearance** no longer has a glass switch.

## Less to read
Short labels and short messages throughout: the explanations under most rows, the footnotes under most sections and the longer captions are gone.

## The Island tab holds still
- **Each core's box** no longer disappears and comes back, pushing everything under it: when a reading leaves out the cores, the last ones stay.
- **The session's numbers** (peaks, averages, data moved) are there from the start, showing "—" until they have numbers, so the sections under them don't jump down a few seconds in.
- **Rows for what your PC can't do yet** (its apps, its files, a screenshot) appear once your PC says it can, instead of showing and then vanishing.

## Crashes
- **The connection dropping no longer takes the app with it.** When your PC went away mid-way (as it did whenever its island crashed on the camera starting), setting a timeout on the closed connection threw, on the app's own connection threads: that closed the app. It's ignored now and the transfer, remote or screen ends normally.
- **Background work can't close the app:** a failure in any of the app's background tasks (the widgets, the live updates, the alerts, live activities, Gemini) or in handling what arrives from your PC now ends that task only.
- **Updating over shaky Wi-Fi** no longer closes the app when the download stalls.
- **A missing number from your PC** (an unknown temperature or reading) no longer closes the Island tab, the stats widget or the scrubber.
- **Holding a graph** while your PC changes, a folder listing the same file twice, a notification from another app the phone can't read, and the camera or microphone turned off and straight back on: all fixed.

**When the app crashes now, it leaves a report:** Settings › About › Last crash shares it (the version, Android's, and where it happened; nothing you sent or played).

## Checks
- **Unit tests, with the phone against the island's engine over the network:** 31, 0 failures (5 skipped).
- **On an Android 16 emulator, against the test PC:** pairing, the Remote in both themes, lyrics, sound with each app's volume, the Island tab staying still while its numbers arrive, Send, Devices, Settings and the trackpad; every tab opened with no crash.
- **The phone as a camera on a real Windows 11 PC** (with island 0.27.1): a test picture through "Arnav Island Phone Camera", read back as an app reads a camera.
- **Not yet tried:** a crash report from a real phone (this version is the first to keep one).
