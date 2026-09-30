# Arnav Island for Android 1.6.0: screens, both ways

If you have 1.5.0, this version installs itself. Update your PC's island to 0.24 too.

## Your PC's screen, here
**Open Remote › *your PC*'s screen.** The PC's screen fills this phone, as sharp and smooth as the connection allows:
- **On the same Wi-Fi:** up to 1440p at 60 frames a second.
- **Directly over the internet:** up to 1080p at 60.
- **Through the relay:** up to 720p at 20.

**Touch it like a touchscreen:**
- a tap clicks, a long press right-clicks (with a little buzz), and a drag drags
- two fingers scroll
- pinching zooms in here, and the button at the top fits it again

**Typing:** the keyboard button types on the PC, with keys for Esc, Tab, the arrows and shortcuts.

**It turns sideways** for a wide screen. The rotate button keeps it upright.

**The bar at the top** shows how the PC is reached, the picture's size, frames a second and bit rate. It tucks away after a few seconds; tap the handle to bring it back.

**If the connection drops,** it says so and offers Try again, instead of freezing on the last picture.

The PC needs *My phone can control this PC* on (in its island's Settings › Privacy & productivity).

## This phone's screen on your PC
**Open Remote › This phone there.** Android asks first, each time.
- **Your screen opens in a floating window on the PC.** It keeps the phone's shape and turns when you turn the phone.
- **A notification with Stop** shows while it's shared, and Android's own recording chip stays in the status bar.
- **The tile turns into Stop showing** while it lasts.
- **Its picture is sized and paced for the connection.** It gets sharper when there's room and lighter when there isn't. If the PC falls behind, it skips ahead to a fresh picture rather than lagging.

**Use this phone from the PC:** turn on **Settings › Accessibility › Control from your PC**. Then, while the screen is shown, the PC's clicks tap here, dragging swipes, its right button goes back, the middle one goes home, and its typing goes into the field you're in. It does nothing at other times, and reads nothing on the screen but the field you're typing into.

## Checks
- **Unit tests:** 26 passing, eight of them against the island's engine, over the network and live over the internet. They cover:
  - the PC's screen as made-up moving pictures: whole frames with their decoder setup, numbered in order, a key frame when asked, and touches arriving on the PC as points and clicks
  - this phone's screen at a phone's shape, upright and then turned: all 135 frames decoded on the PC
  - the PC's screen through the relay
  - everything from 1.5
- **On an emulator**, against a made-up PC:
  - the PC's screen (a made-up picture) decoding smoothly, its taps and drags reaching the PC, the handle and buttons not clicking through, and the sideways turn
  - the emulator's screen on the PC: 829 frames with 1,100 inputs sent while it played
  - "example.com" typed from the PC, then Back, Home and a swipe to scroll
  - three turns each way
- **Fixed while testing:**
  - a tap on the PC's screen closed it: its input went out on the main thread, which Android doesn't allow
  - a wait that ran out halfway through a picture lost its place in the stream
  - typing from the PC dropped letters (each rebuilt the field from an older copy)
  - an encoder that refused one setting (headers on each key frame) stopped the app; now each try asks for less
- **Optimised build:** installed and paired through the relay. The PC's screen showed directly at 44 frames a second, and this phone's screen showed on the PC (886 frames). No crash.
