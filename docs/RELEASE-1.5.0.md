# Arnav Island for Android 1.5.0: at a glance

If you have 1.4.0, this version installs itself. Update your PC's island to 0.23 too.

## Your phone on the island
- **The island's new Phone page** shows this phone live while it's open:
  - its battery: level, how long it lasts, the power going in or out
  - what plays on it, with its cover (with notification access on)
  - its network, storage, memory and more
- **It refreshes every two seconds,** only while that page shows.
- **Fixed: the hotspot card now shows** every time you turn your hotspot on.

## The Island tab
- **Swipe between your PCs.** With more than one, their numbers sit side by side in a glass carousel. Swipe to another and its rings move to its numbers. Everything below follows it.
- **A bar for each processor core.** A busy core shimmers.
- **Scrub the graphs.** In the numbers' sheet, touch a graph and drag: a line marks that moment, with its value and how long ago it was. The graphs keep up to five minutes while the tab shows.
- **It warms when the PC works hard.** After half a minute past 55%, the numbers' card and the processor's ring glow amber, and say so.
- **The PC's battery,** with island 0.23:
  - its level, time left or to full, and the power going in or out
  - health (and its change this week), cycles, temperature, capacity and voltage
  - its last day, green where it charged
- **Volume − and +** beside the slider (5% a step; hold to keep going).

## The Remote tab
- **Volume − and +** beside the dial (5% a step; hold to keep going).

## Widgets
- **Your PC's numbers:** processor, graphics and memory in rings. They update every 5 s while the screen is on, and not at all when it's off.
- **Focus on your PC:** the PC's focus clock counts down in the widget on its own, with pause and resume and a 25-minute start. It's also allowed on the lock screen, where the phone supports widgets there.
- **Widgets in Material You** (Devices › Appearance, Android 12 and later): all four widgets in your wallpaper's colours, light or dark with the phone.

## The focus clock on your lock screen
- **When your PC's focus clock starts,** a notification counts down on the lock screen and in the shade, with pause and resume. On Android 16 it asks to be a live update.
- **When it's done, it says so** for a few minutes.
- **Its state is kept** if the app restarts.

## Checks
- **Unit tests:** 25 passing, seven of them against the island's engine, over the network and live over the internet. They cover:
  - the PC's battery and cores
  - the PC asking this phone for its readings, twice on one kept connection
  - the PC telling it the focus clock, and the Phone page opened on the PC
  - everything from 1.4
- **On an emulator,** against two made-up PCs (one on the network, one through the relay):
  - the carousel between them, the battery card, the core bars shimmering, scrubbing, and the volume buttons
  - the focus clock told over the internet: its notification counting down, and its widget surviving a restart
  - the stats widget refreshing, and the Material You widgets
- **Fixed while testing:**
  - the focus clock's notification shared its number with the PC's music player's, so each replaced the other
  - after the app restarted, the PC took 15 s to notice its old connection had gone
- **Optimised build:** installed, paired, the Island tab, and no crash.
