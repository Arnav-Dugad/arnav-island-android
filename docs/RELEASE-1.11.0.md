# Arnav Island for Android 1.11.0: the whole PC, at a glance

If you have 1.10.0, this version installs itself. Update your PC's island to 0.28 too (it now comes with an installer, Arnav-Island-Setup): everything below about your PC needs it.

## Talk to your PC: PC Voice
**Gemini doesn't run Arnav Island's actions itself:** Google lets Gemini call only the apps it has approved. What Gemini can do is open an app by name, so the app has a new icon on your home screen, **PC Voice**:
- **"Hey Google, open PC Voice"**, then say what you want: it listens at once (on the phone where Android can), shows your words as they come, does it on your PC and says how it went. Restart and shut down ask first.
- Also from its **Quick Settings tile**, and by touching and holding the app's icon.
- **New things to say:** "open Spotify" (any app on your PC), "what's using my PC's memory?" (or its processor), and "mute Discord" (one app's sound). Everything from before still works: music, volume, lock, sleep, find, paste, open a site, how's my PC.
- **Settings › Use with Gemini** lists them.

## Your PC, at a glance (the Island tab)
- **Battery health:** how much the battery still holds against new, its cycles, design and full charge, chemistry and maker.
- **Storage:** each drive's used and free, and for the Windows drive a bar of what fills it (apps, system, documents, downloads, pictures, videos, music, desktop, recycle bin). Measure again any time.
- **Startup apps:** what starts with Windows, with a switch for each of yours (the PC-wide ones show a lock).
- **Network:** the Wi-Fi's name, signal, band, channel and security, the addresses, gateway, DNS, MAC and speed; other adapters fold away.
- **Temperatures over the last hour:** the processor's and graphics card's (and the fan, when the PC says), kept even with the app closed, a graph you can scrub.
- **Windows Update:** up to date, updates waiting with their kind and size, or a restart needed; Check now, Open on PC, and Restart now.
- **Top apps:** what's using the most processor or memory, read only.
- Every new section folds, moves and pins like the others (Edit).

## Open your PC's apps
**Apps on your PC:** every app in its Start menu, with its icon and a search; tap one and it opens on the PC. From the Remote's quick actions and the Island tab.

## The trackpad, like Windows'
**Three fingers** left or right switch desktops, up opens Task View, down shows the desktop; **four fingers** show the desktop.

## A performance widget
**CPU and GPU, and their temperatures,** on your home screen, small or large, in the app's colours or Material You.

## Checks
- **Unit tests, with the phone against the island 0.28's engine over the network:** 44, 0 failures (5 skipped), among them every new answer from the PC, and what PC Voice makes of what you say ("mute Discord" isn't the whole PC, "start a timer" isn't an app).
- **On an Android 16 emulator, against the test PC:** every new Island section, PC Voice listening (and giving up kindly on silence), and the voice actions reaching the PC: Spotify opened, Edge muted, the top apps read.
- **Not yet tried:** three- and four-finger swipes on a real phone (the emulator has no multi-touch), the widget on a home screen, and PC Voice's speech recognition with a real voice.
