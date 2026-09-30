# Arnav Island for Android 1.7.0: handed over

If you have 1.6.0, this version installs itself. Update your PC's island to 0.25 too.

## Send it, as AirDrop does
- **Share anything to your PC.** What you're sending shows as a picture (a fanned stack for several), with its name and size.
- **Each of your PCs is a glass bubble.** One tap sends it to that PC's Shelf. A ring fills round the bubble as it goes and turns into a tick when it's there.
- **Your PCs are in Android's own share sheet too,** so a share can go straight to one.
- **On the PC, the island shows its picture while it arrives,** then "On your Shelf".

## The photo you just took
**Turn on Devices › Photos you take.** Android asks for photo access once. Then each photo or screenshot you take shows on your PC's island, with Paste and Shelf:
- **Paste** puts the photo into the window you're in on the PC.
- **Shelf** puts it on the island's Shelf.

Only a small picture of it goes at first. The photo itself goes only when you choose, and only in the ten minutes after it was taken. Other apps' saved images (downloads, messages) are never sent.

## Pages, where you were
- **From your PC:** its Phone page's Page button, or "send page" in its command bar. The page opens in the app's reader, scrolled to where you were on the PC. A notification lets you open it later.
- **From here:** open a link in the reader (share a link to the app and choose Read here), then Continue on your PC. It opens there at the same place.
- **The reader** is a page in glass: back, where it's from, and open in your browser.

## Quick Settings
Two new tiles:
- **PC's screen:** opens your PC's screen here in one tap.
- **Phone on PC:** shows this phone on your PC. It's lit while shown; tap it again to stop.

## Liquid glass, lighter
**The same glass, drawn with about half the work.** On an emulator (much slower than a phone at this), frames went from 142–157 ms to 52–72 ms, and scrolling from about 8 frames a second to 17–23. Three changes, none visible:
- **Shadows are drawn once and kept.** They used to be blurred again on every frame the glass redrew, which is every frame while the light behind moves.
- **The light behind holds still while your finger is on the screen** (and for a moment after a fling), so scrolling has all the drawing to itself.
- **Its pace follows the phone.** It drifts at up to 20 steps a second and eases down (to 15, 10 or 7) if the phone starts to drop frames. Motion this slow looks the same.

## Checks
- **Unit tests:** 27 passing, nine of them against the island's engine, over the network and live over the internet. The new one covers:
  - an offer with its picture, into the Shelf
  - a photo just taken, told to the PC, asked for by it and sent with its ask
  - pages both ways, with where they were scrolled to
- **On an emulator**, against a made-up PC:
  - a picture shared with its preview, its ring filling to a tick, onto the Shelf
  - a photo just taken, announced to the PC
  - a link read here and handed to the PC with its scroll
  - both tiles
  - the glass before and after, frame by frame
- **Optimised build:** installed and paired through the relay. A picture shared with its preview reached the PC's Shelf, and a link opened in the reader. No crash.
