# BT Receiver

Turns a rooted Android phone into a Bluetooth audio receiver (A2DP sink) for a PC or another phone.
Everything is in the APK: install it, grant root, and it sets itself up.

## Install

1. Download the APK from [Releases](https://github.com/JeremyDoege89/BtReceiver/releases/latest) and install it.
2. Open BT Receiver and allow root in your root manager (KernelSU, Magisk or APatch), then reopen the app.
3. Tap **Speaker**, then pair your PC or phone to this phone and play audio to it.

- **Speaker / Regular** toggle. On first launch the app installs its bundled
  `assets/btmode.sh` to `/data/adb/btreceiver/` plus a boot hook
  `/data/adb/post-fs-data.d/btreceiver.sh` that reapplies speaker mode at boot. Speaker mode turns
  A2DP source off and sink + AVRCP controller on, sets the class of device to loudspeaker, and
  restarts the Bluetooth process. Regular mode restores the phone's original values (saved on the first switch).
- **Media controls** (previous / play-pause / next / stop) and now-playing info go through the
  Bluetooth stack's AVRCP-controller media session, so they control the connected source.
  Falls back to `cmd media_session dispatch` as root.
- **Volume**: the phone's local music volume.
- **Remove from system**: back to regular mode, deletes the script and boot hook. Do this before uninstalling.

Requirements: root (KernelSU, Magisk or APatch), Android 13+ (the `bluetooth.profile.*` switches).
Not tied to a chip vendor. Tested on moto g 2026 (MediaTek MT6835, Android 16, KernelSU). Speaker mode also
turns off A2DP hardware offload (common on Qualcomm), which only handles sending.

Notes:
- A source has to pair (or re-pair) while the phone is in speaker mode.
- The stack can't run A2DP source and sink together, so headphones don't work in speaker mode.
- If the older standalone `btspeaker` KernelSU module is present, setup disables it (delete
  `/data/adb/modules/btspeaker/disable` to undo).

## Build

`./gradlew assembleDebug`. Release builds are signed with a key from a local, git-ignored
`keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
