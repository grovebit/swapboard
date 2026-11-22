# ClipSync

Text clipboard sync between a Mac and an Android phone. An Alfred workflow controls it.

- **Mac → phone is automatic.** Copy anything on the Mac and it's on the phone a moment later.
- **Phone → Mac takes one tap**, or happens automatically if you want.
  - One tap: the Quick Settings tile, the notification button, Share, or "Send to Mac" in the text-selection menu.
  - Automatic: switch on **Automatic phone → Mac** in the app. It's off by default and needs [Shizuku](https://github.com/RikkaApps/Shizuku), because since Android 10 normal apps can't read the clipboard in the background.
- **Tailscale only.** It works wherever both devices are on your tailnet, and never over Wi-Fi/LAN directly.
  - The Mac agent listens only on its Tailscale address.
  - The app binds its connection to the Tailscale VPN network and doesn't even hold Android's local-network permission.
  - On top of Tailscale's encryption, every clip is end-to-end encrypted with a key created when you pair. Nothing goes through a cloud service.

```
Alfred workflow ──► clipsync CLI ──unix socket──► clipsync agent (LaunchAgent) ◄══ WebSocket, AES-GCM ══► ClipSync Android app
```

| Part | Where | What it is |
|------|-------|------------|
| Alfred workflow | `workflow/` | `clip` keyword, hotkey, Universal Action, notifications |
| Mac agent + CLI | `mac/` | Swift, no third-party dependencies; one binary `clipsync` |
| Android app | `android/` | Kotlin + Compose; foreground service, tile, share target |
| Protocol | `protocol/` | `PROTOCOL.md` spec and `vectors.json`, which both test suites must pass |

## Install

### Mac
Requirements: macOS 13+, Alfred 5 with Powerpack, Xcode command-line tools.

```sh
make dev-install      # builds clipsync and links workflow/ into Alfred (edits take effect live)
# or
make package          # builds dist/ClipSync.alfredworkflow; double-click it to install a copy
```

### Android
Requirements: Android 14+. Build with Android Studio, or use the Gradle wrapper with the phone connected over adb:

```sh
make install-android  # ./gradlew installDebug
```

or install `android/app/build/outputs/apk/debug/app-debug.apk` directly.

Then on the phone:
1. Open **ClipSync** and work through its checklist:
   - allow **notifications**
   - allow **ignoring battery optimizations**
2. Samsung only: open **Settings → Apps → ClipSync → Battery** and choose **Unrestricted**. Do the same for **Tailscale**. Also add both apps to **Never sleeping apps** in Settings → Battery → Background usage limits.
3. Add the **Send to Mac** tile: pull down Quick Settings → ✎ (edit) → drag the tile in.

### Pair
1. In Alfred, type `clip` and choose **Start agent**. The agent then also starts automatically at login.
2. Choose **Pair a phone**. A QR code opens in Quick Look.
3. In the app, tap **Pair with a Mac** → **Scan QR code**, then confirm the Mac's name and addresses. Samsung Camera may also open the code directly.

The QR code holds the encryption key and expires after 10 minutes. If you can't scan it, run `clipsync pair-link` and get the link to the phone some other way.

## Use

| Where | What |
|-------|------|
| Mac | Copy as usual. It syncs automatically unless paused. |
| Alfred `clip` | Status, Send clipboard to phone, Pause/Resume auto-sync, **Last from phone** (↩ copy, ⌘↩ paste), Pair, Unpair, Start/Stop agent, Show log |
| Alfred hotkey | Unassigned by default. Set it in the workflow to send the Mac clipboard on demand. |
| Alfred Universal Action | **Send to phone clipboard** on any selected text, or on an entry in Alfred's clipboard history |
| Phone | Quick Settings tile **Send to Mac**, the notification's **Send clipboard** / **Send as sensitive** buttons, long-press the app icon → **Send clipboard**, **Share → Send to Mac** (or **Send to Mac (sensitive)**), or select text → **Send to Mac** |

Workflow settings (Alfred → Workflows → ClipSync → Configure): keyword, notifications on receive, port, max clip size, ignored apps.

### Automatic phone → Mac (optional, off by default)
1. Install [Shizuku](https://github.com/RikkaApps/Shizuku) and start it. Without root, Shizuku has to be started again after every reboot unless your Shizuku build can start itself on boot.
2. In ClipSync, turn on **Automatic phone → Mac**. Allow ClipSync when Shizuku asks.
3. The card shows **✓ On: watching the clipboard**. Every copy on the phone now goes to the Mac.

How it works: a small helper (`ClipWatcherService`) runs through Shizuku as Android's shell user, which is allowed to read the clipboard in the background. It listens for clipboard changes and hands new text to the app.

What it skips:
- items marked sensitive (password managers)
- clips that just arrived from the Mac
- repeats of the same text

Turning the switch off stops the helper.

## Privacy and security

- **Encryption.** Each pairing creates a random 256-bit key. Every connection derives fresh session keys (HKDF-SHA256), and every message is AES-256-GCM encrypted and authenticated, with replay protection. Devices that haven't paired are disconnected with close code 4001. See `protocol/PROTOCOL.md`.
- **Password managers.** Items they mark as concealed (`org.nspasteboard.ConcealedType`, Transient, AutoGenerated) are **never auto-synced**. Copies made while an *ignored app* is in front aren't either.
- **Explicit sends of sensitive items.** These are flagged `sensitive`. The phone hides their preview (`EXTRA_IS_SENSITIVE`), and the Mac marks them concealed, so Alfred's clipboard history skips them.
- **Logs.** Logs never contain clipboard text.
- **Automatic phone → Mac** has the helper only read the clipboard, and only while the switch is on. It never sends sensitive items. Shizuku's own app lists and revokes the access.
- **Where the Mac keeps its data.** Keys live in `~/Library/Application Support/Alfred/Workflow Data/com.clipsync.alfred/config.json` (mode 0600). `clip → Unpair` removes a phone; the app's **Unpair** removes the Mac on the phone side.

## Troubleshooting

- **Alfred says the agent is stopped.** Choose `clip → Start agent`. If it doesn't stay up, check `clip → Show log`.
- **"Waiting for phone…"**
  - Open the app.
  - Check that Tailscale is connected on both devices; ClipSync never falls back to Wi-Fi.
  - If `clip` says **Tailscale is off on this Mac**, connect Tailscale and the agent starts listening again on its own.
- **Phone says "re-pair needed".** The Mac no longer knows the key, for example after Unpair. Pair again.
- **macOS prompts the first time.**
  - **Automation:** "clipsync wants to control Alfred" is needed for notification banners.
  - **Paste from other apps:** if macOS asks, allow it.
  - Rebuilding with an ad-hoc signature can make these prompts reappear. Build with `make mac SIGN_ID="Apple Development: …"` to avoid that.
- **Clips don't arrive while the phone sleeps (Samsung).** Recheck the battery settings above for both ClipSync and Tailscale.

## Development

```sh
make test          # Swift + Kotlin unit tests (both run protocol/vectors.json)
make vectors       # regenerate protocol/vectors.json with Node's crypto (independent reference)
make mac           # release build → workflow/clipsync (ad-hoc signed unless SIGN_ID is set)
make android       # debug APK
```

`clipsync debug-client` acts as a fake phone, so you can test the Mac side without a device. Example with an isolated data dir:

```sh
export CLIPSYNC_DATA_DIR=/tmp/clipsync-test
mkdir -p $CLIPSYNC_DATA_DIR && echo '{"settings":{"port":48699}}' > $CLIPSYNC_DATA_DIR/config.json
clipsync agent &                         # foreground agent
clipsync pair --no-show                  # creates a pairing without showing the QR
clipsync debug-client --exit-after 10    # prints clips the Mac sends
clipsync debug-client --send "hello"     # sends a clip to the Mac
clipsync debug-client --wrong-key        # expect: closed code=4001
```
