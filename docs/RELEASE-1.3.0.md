# Arnav Island for Android 1.3.0: straight there

If you have 1.2.0, this version installs itself. Update your PC's island to 0.21 too.

## Much faster on any network
- **A direct path first.** When the phone and your PC are on different networks, they now try to reach each other directly:
  - over IPv6 (Jio's mobile network is all IPv6, and most Indian broadband has it)
  - over IPv4, by punching through both routers (free STUN servers say where each one appears from)

  They fall back to the relay when neither works, and carry on there if the direct path goes quiet, even mid-transfer.
- **How much faster:** measured against the island's engine, a remote command takes 1–2 ms directly, against about 340–500 ms through the relay (its brokers are in Europe).
- **One round trip a command.** The connection to your PC stays open, so the remote, the volume and the skip buttons answer in one round trip with no new handshake. Your notifications and battery reach the island the same way.
- Still free, and sealed end to end with your pair's own key.

## The connection's quality ring
- A thin ring round your PC shows how it's reached:
  - whole and green directly or on this Wi-Fi
  - amber through the relay
  - a short red arc when it's slow
- It sweeps and changes colour as the connection changes. Devices shows how the PC is reached, its round trip and how many relays carry it.
- The island at the top shows the ring too, and the remote's title says "direct" or "relay".

## Checks
- **Unit tests:** 23 passing. They include six live tests against the island's engine over the internet:
  - a direct path found within seconds of pairing, the remote in 1–2 ms a command, and 3 MB each way over it
  - the path going quiet: back on the relay within the minute, the open remote connection carried over
  - the relay on one broker (either side), a wrong key refused, and messages dropped on purpose both ways
  - the full relay test
- **On an emulator:** paired with a PC that's only reachable over the internet, from a pairing link. It found a direct path by itself (**Direct · 6 ms**), and the PC sees the phone directly too. The ring, the remote's title and the top island all show it.
- **Optimised build:** installed, paired, direct, and no crash.
