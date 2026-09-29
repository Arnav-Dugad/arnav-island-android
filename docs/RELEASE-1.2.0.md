# Arnav Island for Android 1.2.0: scan to pair, from anywhere

If you have 1.1.0, this version installs itself. Update your PC's island to 0.20.1 too.

## Fixed: the phone couldn't find the PC with a code
- **What went wrong:** the phone and the PC each kept to one public broker. When they picked different ones, the phone never heard the PC's code, however it was typed.
- **Now both stay on all three brokers at once**, so they always share one. Codes and hellos go out on every broker.
- **Quicker to connect:** IPv4 first, a few seconds for each address.
- **Nothing is lost on the way.** A public broker may drop a message now and then, and before, one lost message ended a pairing or a transfer. Now:
  - whatever isn't acknowledged in time is sent again
  - a gap is reported at once and filled
- A pairing link can open the app before it has connected. It then waits for the relay.

## Pair by QR code
- **Devices › Scan the QR code**, and point the phone at the island's **Shelf › Nearby › Pair with a code**.
- **A live viewfinder in glass:** its corners breathe and a band of light sweeps down it. On the code, they close in and glow, and a firm tap confirms it.
- **Safer, with fewer taps:** the QR code carries the PC's key fingerprint. The phone pairs only with that PC, so it says yes by itself, and you choose **Pair** once, on the PC.
- **The pairing sheet** has **Scan QR code** and **Type a code** side by side. If the camera isn't allowed, it asks, or opens Settings when Android no longer asks.
- **Pairing links:** an `arnavisland://pair/...` link opens the app and pairs.
- The camera runs only while the scanner shows. Nothing it sees is kept or sent.

## Checks
- **Unit tests:** 22 passing (16 in 1.1.0). New ones check:
  - pairing links, read in every form
  - the key fingerprint
  - the island's own QR matrix read by ZXing: as drawn, inverted, and as a camera frame with padded rows
- **Against the Windows island's engine, live over the internet:**
  - the phone on one broker and the PC on all three, then the other way round
  - a scanned link with another PC's key: refused at once, and nothing paired
  - every 4th message from the phone and every 5th from the PC dropped on purpose: pairing, the remote, presence and files both ways all complete
  - the full relay test: files both ways, the remote, notices, ring and music
- **On an emulator, against a PC reachable only over the internet:**
  - the scanner: asking for the camera, refusing it, allowing it, and the live viewfinder
  - a pairing link: *Finding your PC*, *Confirm on Travel PC* with the same six digits as the PC, then *Paired*, in about a second and a half
  - a link with the wrong key: *Not paired*, with **Scan again** and **Type the code**
  - a photo for the PC's Shelf, with the new camera permission
- **Optimised build:** installed, scanner opened, and paired over the internet.
