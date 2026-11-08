# Arnav Island for Android 1.13.0: your phone, from your PC

If you have 1.12.0, this version installs itself. Update your PC's island to 0.30 too: everything below needs it.

Each new thing is off until you turn it on, in Settings › **On your PC's island**.

## Your phone's files, on your PC
- **Browse this phone from your PC.** Your internal storage, Downloads, Camera, Pictures, Documents, Music, Movies and any memory card, sorted by name, date, size or kind, with small previews of your photos.
- **Take a file or a whole folder.** It comes to your PC's Downloads without asking there.
- **Look, never change.** Your PC can't delete, rename or add anything here. Hidden files stay hidden.
- Turn on **Files on my PCs**. Android then asks you to allow the app all files access.

## Your text messages, on your PC
- **Read your conversations** on your PC, with your contacts' names and faces, and the photos people send.
- **Reply from your PC.** Your phone sends the message, as if you had typed it here.
- Turn on **Text messages on my PCs**. Android then asks you to allow SMS (and, for names and faces, your contacts).
- Marking a conversation read works only in your messages app, so do that on your phone.

## Unlock your PC with your fingerprint
- **Set it up once** from your PC's island: your phone asks, and you confirm with your fingerprint.
- **Then unlock your PC from your phone.** When your PC asks, a notification shows here. Tap it and touch the sensor, and your PC unlocks. Tap **Not now** or ignore it, and nothing happens.
- **Your password stays safe.** Your PC keeps it locked with a key that only your phone holds, and your phone gives that key only after your fingerprint, for that one request, on the same Wi-Fi. If you add or remove a fingerprint, set it up again.
- You can see your set-up PCs, and forget one, under **Unlock my PC**.

## Calls
- **A call stays on your PC's island while you're on it,** not just while it rings: who you're talking to, their photo and how long it's been. Hang up, Mute and Speaker are on the call in the island's side panel.
- The sound stays on your phone: Windows lets only Microsoft's Phone Link carry call audio.

## Checks
- Unit tests, including a fixed unlock test vector made independently of both apps, which this phone reproduces byte for byte, the new layouts for files, messages, unlocking and calls, and the unlock key sealed and opened as your PC opens it: all passing.
- Against the PC's own engine (island 0.30) over the network: browsing and taking files and folders, reading and sending messages, setting up and answering unlock requests, and a call under way.
