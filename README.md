# Swapboard

**One clipboard for your Mac and your Android phone.**

- **Mac → phone:** automatic. Just copy.
- **Phone → Mac:** one tap.
- **Private:** your text only travels between your own devices, encrypted.
- **Free.**

## What you need

- A Mac with macOS 12 or newer
- An Android phone with Android 10 or newer
- **Tailscale**, a free app that privately connects your devices. Swapboard helps you set it up.

## Setup (about 10 minutes)

### On your Mac

1. Download **Swapboard-mac.zip** from the [latest release](../../releases/latest).
2. Open **Swapboard**. If it asks, click **Move to Applications**.
3. Follow the window. It helps you set up Tailscale, then shows a code.

### On your phone

1. Open your phone's **Camera**, point it at the code on your Mac, and tap the link. The app downloads.
2. Tap the download to install it, then open **Swapboard**.
3. Follow the steps. When the phone asks you to scan, click **My phone has the app** on your Mac and scan the new code.

**Done!** Copy something on your Mac, then paste it on your phone.

### Seeing a warning?

Swapboard isn't in the App Store or on Google Play, so your devices ask you to confirm once:

- **Mac: "Swapboard" can't be opened.** Open **System Settings → Privacy & Security**, scroll down and click **Open Anyway**. Then open Swapboard again.
- **Phone: the browser warns about the download.** Choose to download it anyway.
- **Phone: you can't install unknown apps.** Tap **Settings**, turn on **Allow from this source**, then go back and tap **Install**.
- **Phone: Play Protect warns you.** Tap **More details → Install anyway**.

## How to use it

- **Mac → phone:** copy as usual, then paste on your phone.
- **Phone → Mac:** copy, then tap **Send to Mac**. You'll find it:
  - in Quick Settings (swipe down from the top of the screen),
  - in the **Share** menu,
  - in the menu that pops up when you select text.

  Then paste on your Mac with **⌘V**.

On your Mac, Swapboard sits in the menu bar at the top of the screen. Look for **⇄**, and click it to pause, send or set up another phone.

Passwords from password managers are never sent automatically.

## Something not working?

- **Nothing arrives:** check that Tailscale is on, on both devices, with the same account.
- **Text only arrives after you open the app:** your phone is putting apps to sleep. In Swapboard on your phone, tap **Advanced options** for tips for your phone's brand.
- **Can't find ⇄:** open **Swapboard** from your Applications folder.
- **Still stuck?** Read the [full guide](docs/GUIDE.md) or [ask for help](../../issues).

## Privacy

- Your text goes straight from one of your devices to the other, encrypted. No cloud, no account, no tracking.
- Swapboard never uses your ordinary Wi-Fi network. It only talks through Tailscale.
- It doesn't keep a history of what you copy.

## More

- [Full guide](docs/GUIDE.md): automatic sending from the phone, Alfred, settings, troubleshooting and uninstalling
- [For developers](docs/DEVELOPMENT.md)
- License: [MIT](LICENSE)
