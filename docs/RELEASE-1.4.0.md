# Arnav Island for Android 1.4.0: the whole island, in your hand

If you have 1.3.0, this version installs itself. Update your PC's island to 0.22 too.

## The Island tab
A new tab between Remote and Send controls everything on your PC's island:
- **Its numbers, live, every second:**
  - processor, graphics and memory in rings, and a graph of the processor that flows as it updates
  - downloads and uploads
  - tap for more: graphs of the processor, graphics and network, the processor and graphics card by name, memory, disk, battery and time left, how long it's been on, the PC's model and Windows version
- **Controls:** Wi-Fi, Bluetooth, airplane mode, dark mode, the microphone and mute as glass tiles (a small ring while one is changing on the PC), and brightness and volume sliders.
- **Focus:** the island's clock, counting here as it does there. Start 15, 25 or 45 minutes or a break, pause, reset, or the stopwatch.
- **Run on your PC:** the island's command bar. Type an app, a file, a setting, "timer 10" or "100 usd in eur", and tap a result. Anything the island asks about first (restart, shut down, empty the recycle bin) asks here.
- **Power:** lock, sleep, restart and shut down (each after asking), and emptying the recycle bin.
- **Where the sound comes from:** pick an output (with the island's *Direct output switching* on; the tab offers to turn it on).
- **Show on your PC:** open any of the island's pages on the PC, or close the island.
- **Island settings:** every setting, section by section, as the PC's Settings has it: switches, sliders, choices, steppers, colours and buttons. Turning off something that would cut this phone off asks first.
- **Tapping the remote's CPU chip** opens the tab.

## Your hotspot, on the island
- **Devices › Your hotspot:** type its name and password once, as your hotspot settings show them. Android doesn't tell apps, which is why you type them.
- **While the hotspot is on,** your PC's island offers to join it in one tap.
- **The name and password go** only to your paired PCs, sealed end to end, and only while the hotspot is on.

## Your battery, forecast
- **Devices › Your battery** says how long it lasts ("lasts until 11 pm"), or when it's full while charging. Your PC's island shows it by this phone too.
- **The forecast learns from this phone's own history** (two weeks, kept on the phone):
  - at first it follows the pace of the last hour or so
  - then more and more the pace you usually keep at each hour of the day

## Widgets in the cover's colours
- **Both widgets take the colours of what plays on your PC,** as the app does:
  - deep glass in the cover's colour, lit by its two brightest
  - the PC's name in the cover's colour
- **Each is drawn at its own size,** so its corners stay round.

## Checks
- **Unit tests:** 25 passing. They include seven against the island's engine, over the network and live over the internet:
  - the whole island: numbers, settings and their changes, controls, the command bar with a yes asked first, outputs and pages
  - the hotspot on and off
  - the direct path and the relay
- **The battery forecast** is tested on five days of the same routine (it lasts until the hour the routine says) and on a faster day (sooner, but not as soon as the fast pace alone).
- **On an emulator,** against a made-up PC:
  - every control, each kind of setting, the command bar with the keyboard up, and the focus clock counting down
  - the stats sheet and the page tiles
  - the widget in the cover's colours
  - the emulator's own hotspot turned on and off, reaching the PC's engine both times
- **Optimised build:** installed, paired, the Island tab, and no crash.
