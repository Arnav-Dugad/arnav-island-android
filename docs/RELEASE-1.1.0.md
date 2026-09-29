# Arnav Island for Android 1.1.0: anywhere, and so much more

If you have 1.0.1, this version installs itself. For everything new here, update your PC's island to 0.20 too.

## Anywhere
- **Your phone and your PC now reach each other on any network:** another Wi-Fi, mobile data, anywhere. It's free and needs no account. On the same Wi-Fi they still talk directly.
- **Pair from anywhere with a code:**
  1. On the PC, open the island's Shelf › Nearby › Pair with a code.
  2. Type the code on the phone.
  3. Check that both show the same six digits.
- Devices you have already paired find each other anywhere on their own.
- End-to-end encrypted: the relay (a free public MQTT broker over TLS) carries only sealed bytes. **Devices › Reach my PCs anywhere** turns it off.
- When the phone changes networks, it reconnects at once.

## New
- **Lyrics:** tap the cover and it turns over to the PC's lyrics, lit word by word. Tap a line to play from there.
- **Trackpad and keyboard:** move, click, scroll, right-click and drag on the PC. Type straight into it, with Esc, Tab, the arrows and shortcuts.
- **Find my PC:** the island chimes and lights up.
- **Camera to Shelf:** a photo taken here lands on the island's Shelf a moment later. The island can also ask for one.
- **Reply from the island:** answer messages from the PC's keyboard, or mark them read. The card leaves the island when the notification goes.
- **Calls on the island:** see who's calling, and decline from the PC.
- **Your phone's details on the island:** battery, temperature, storage, memory, network, sound and more.
- **Universal clipboard:** copy on one, paste on the other.
- **Your PC's music on the lock screen** and in quick settings, with its controls.
- **Widgets:** the PC's music, and quick actions (lock, ring, send, camera, paste).
- **Shortcuts** on the app icon: Photo to Shelf, Trackpad, Find my PC, Paste on PC.

## Design
- **Liquid morphing:** tap the island at the top and it melts open into the song's card.
- **Cover depth:** the cover lifts off its card over a shadow that slides as you tilt the phone.
- **Haptic scrubbing:** a soft click at each lyric line as you scrub.
- **A volume dial** with a click every 5%, a glowing edge, and mute in its middle.
- **Files in flight** stream through the island as light.
- **Weather on the glass:** rain, snow, fog or a storm's flash when that's the weather where your PC is.
- **Ring radar:** the find-my-phone screen pulses with the vibration, and warms as the phone is picked up.

## Faster glass, same look
- The rim of light is drawn in its own layer, so tilting the phone no longer redraws the refraction beneath.
- Cards no longer blur what is already a soft field of light.
- The light behind the glass stands still while the app is hidden, a sheet covers it, or the battery saver is on.
- Measured on the same screen:
  - the median frame is about 18% quicker
  - frames over budget fell from 98% to 35–49%
- Prefer it plain? **Devices › Appearance › Liquid glass** switches to solid surfaces.

## Checks
- **Unit tests:** 16 passing. They include the full protocol against the Windows island's sharing engine, on one network and over the internet relay.
- **On an emulator, against the island's engine:**
  - pairing with a code over the internet
  - the remote, lyrics and trackpad over the relay
  - reply, mark as read, and declining a call
  - the universal clipboard both ways
  - camera to Shelf
  - widgets, shortcuts, and the lock-screen player
- **Optimised build:** launch-tested and paired over the internet.
