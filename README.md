# FlipEx

**Samsung DeX on the Galaxy Z Flip, no root.**

The Galaxy Z Flip has no DeX. Plug it into a monitor and you only get a mirror of the phone
screen. FlipEx changes that: plug in a monitor and you get the real Samsung DeX desktop, with
the DeX home screen, wallpaper, the DeX taskbar listing your open apps, resizable app windows and
a mouse pointer. It all runs on your phone's own software, so there's no custom firmware, no
unlocked bootloader and no root.

Fold the phone and its cover screen becomes a trackpad and keyboard for the desktop.

> FlipEx is an independent project. It is not made by, endorsed by or affiliated with Samsung.
> It changes system and developer settings on your phone; **Restore stock settings** in the app
> undoes them. Use it at your own risk.

---

## Contents

- [What you get](#what-you-get)
- [Requirements](#requirements)
- [Setup](#setup)
  - [1. Turn on Developer options](#1-turn-on-developer-options)
  - [2. Install and start Shizuku](#2-install-and-start-shizuku)
  - [3. Install FlipEx](#3-install-flipex)
  - [4. First run](#4-first-run)
  - [5. Cover-screen trackpad (optional)](#5-cover-screen-trackpad-optional)
- [Using FlipEx](#using-flipex)
- [Updates](#updates)
- [After a restart](#after-a-restart)
- [DeX on a Mac or Windows PC](#dex-on-a-mac-or-windows-pc)
- [What FlipEx changes](#what-flipex-changes)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [How it works](#how-it-works)
- [Building from source](#building-from-source)

---

## What you get

| | |
|---|---|
| **DeX desktop on any monitor or TV** | Samsung's DeX home screen, wallpaper and taskbar, as soon as you plug in |
| **Taskbar with your open apps** | Click to switch, just like DeX |
| **Real app windows** | Apps open as resizable windows instead of full screen |
| **Mouse and keyboard** | USB or Bluetooth mice go to the monitor, not the phone |
| **No giant phone keyboard** | The on-screen keyboard stays off the monitor; type on a real keyboard or the cover screen |
| **Cover-screen trackpad** | Fold the phone: the cover screen becomes a trackpad with scrolling, gestures, left/right click, Back, a keyboard and an open-apps panel |
| **Trackpad + keyboard on the main screen** | Or use the phone open, like a laptop deck: trackpad on top, keyboard below (FlipEx → **Trackpad on this screen**, then ⌨) |
| **Lock screen cover** | Lock the phone and the monitor is covered until you unlock |
| **DeX on a Mac or PC** | Optional: the FlipEx desktop app opens DeX in a window on your Mac or Windows PC, over USB or Wi‑Fi |

## Requirements

- **A Galaxy Z Flip on One UI 8 / Android 16.** Developed and tested on the **Galaxy Z Flip 5**
  (SM‑F731N). Other Flips on One UI 8 should work but are untested; please report back.
- **A USB‑C hub, dock or cable with HDMI or DisplayPort**, and a monitor or TV.
- **A mouse** (USB or Bluetooth), or the cover-screen trackpad.
- **[Shizuku](https://shizuku.rikka.app/)**, free. It gives FlipEx the same access a computer's ADB
  connection would have. This is how FlipEx works without root.
- **Wi‑Fi**, only while starting Shizuku (Wireless debugging needs a Wi‑Fi network). You don't
  need a computer.

## Setup

### 1. Turn on Developer options

1. Open **Settings → About phone → Software information**.
2. Tap **Build number** seven times, and enter your PIN if asked.
3. **Developer options** now appears at the bottom of Settings.

### 2. Install and start Shizuku

1. Install **Shizuku** from the [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
   or from its [GitHub releases](https://github.com/RikkaApps/Shizuku/releases).
2. Connect the phone to **Wi‑Fi**.
3. Open Shizuku and find **Start via Wireless debugging**. Tap **Pairing**. Shizuku will tell you to
   open the Wireless debugging settings and will put a notification on screen.
4. Go to **Settings → Developer options → Wireless debugging** and turn it **on**. Tap
   **Allow** for this network (tick *Always allow on this network*).
5. In Wireless debugging, tap **Pair device with pairing code**. A 6‑digit code appears.
6. Pull down the notification shade and type that code into **Shizuku's notification**. It
   should say *Pairing successful*. (Split screen or a pop‑up window makes this easier.)
7. Back in Shizuku, tap **Start** under *Start via Wireless debugging*. The top of Shizuku should
   now say **Shizuku is running**.

You only pair once. After a phone restart you just tap **Start** again (see
[After a restart](#after-a-restart)).

<details>
<summary>Have a computer? Start Shizuku over USB instead</summary>

Turn on **USB debugging** in Developer options, plug the phone into the computer, allow the
prompt on the phone, then run:

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

or, from this repository, `scripts/dex-on-mac.sh --shizuku` (macOS/Linux).
</details>

### 3. Install FlipEx

Download the latest `FlipEx.apk` from [Releases](../../releases) and open it on the phone. Android
will ask you to allow installs from your browser or file manager the first time.

> **Coming from 0.1.0 or 0.2.0?** Those were signed with a development key. From 0.3.0 FlipEx is
> signed with its release key, so **uninstall the old version first** (Android won't install over
> it). You'll need to allow *Display over other apps* and *Connect Shizuku* again.
>
> Release key SHA‑256 fingerprint:
> `E5:9B:0A:4B:1A:1D:C7:26:AE:32:76:30:AA:21:4F:36:5C:DD:4D:BD:A5:1C:FD:71:EF:05:EA:A6:D6:3A:04:D5`

### 4. First run

Open **FlipEx**. A **Setup** card lists anything still missing:

1. **Display over other apps**: tap the button and allow it. FlipEx needs this to show the
   cover‑screen trackpad and the lock cover.
2. **Connect Shizuku**: tap it and choose **Allow** (*Allow all the time*).

That's it. FlipEx then grants itself the settings permission it needs, through Shizuku. The
Setup card disappears when everything is ready.

### 5. Cover-screen trackpad (optional)

Samsung only lets approved apps run on the Flip's cover screen. To allow FlipEx:

1. Install **Good Lock** from the Galaxy Store, then the **MultiStar** module inside it.
2. In MultiStar, open **I ♡ Galaxy Foldable → Launcher Widget** (the name varies a little by
   version) and turn on **FlipEx**.

Good Lock isn't available in every region. Everything except the cover-screen trackpad works
without it.

## Using FlipEx

1. In FlipEx, make sure **DeX on monitors** is on. You can also use the **DeX desktop** tile in
   quick settings.
2. Plug in the monitor. DeX appears on it within a couple of seconds.
3. Use a mouse and keyboard, or **fold the phone** to use the cover screen as a trackpad.

If a monitor ever shows a mirror of the phone instead of DeX, tap **Start DeX on the monitor** in
FlipEx.

**Cover-screen trackpad:**

| Gesture | Action |
|---|---|
| One finger | Move the pointer |
| Tap | Click |
| Hold, then move | Drag |
| Two fingers | Scroll |
| Two-finger tap | Right click |
| Strip on the right edge | Scroll |
| Button bar | Back · ▦ open apps · ⌨ keyboard · L / R (hold to press) |
| Back gesture | Leave the trackpad |

**Settings in FlipEx:**

- **Samsung DeX features**: wallpaper, full taskbar with running apps, no giant keyboard. Leave on.
- **FlipEx apps strip**: an extra open-apps bar above the taskbar. Off by default, since the
  taskbar already lists open apps.
- **Haptics**: Strong, Light or Off for the trackpad.
- **Dim phone screen**: turns the phone's own screen into a black trackpad while DeX keeps running.
- **Restore stock settings**: stops FlipEx and puts every setting it changed back.

## Updates

FlipEx checks its GitHub releases about once a day. When there's a new version, an **Update**
card appears at the top: tap it once and FlipEx downloads the update, checks it against GitHub's
published SHA‑256 and FlipEx's signing key, and installs it (through Shizuku, with no prompt;
without Shizuku, Android asks you to confirm). FlipEx never installs on its own, because an
install restarts it and closes the cover‑screen trackpad. **More → Check for updates** checks
right away.

This is the only thing FlipEx uses the internet for. It only talks to GitHub.

Updates only work between copies signed with the same key. A test build, or a 0.1/0.2
release, needs one manual uninstall and reinstall of the latest release first.

## After a restart

Android turns Wireless debugging off when the phone restarts, which stops Shizuku. To start it
again:

1. Connect to Wi‑Fi.
2. Open **Shizuku** and tap **Start** under *Start via Wireless debugging*. You don't need to pair
   again.
3. Open **FlipEx** once.

## DeX on a Mac or Windows PC

You can also run DeX in a window on a computer, with no monitor. Download **FlipEx Desktop** from
[Releases](../../releases): `FlipEx-Desktop-mac-AppleSilicon.zip`, `FlipEx-Desktop-mac-Intel.zip`
or `FlipEx-Desktop-Windows-setup.exe`. It's a small window that finds your phone and opens DeX:

1. **Connect the phone.** Over USB (USB debugging on, tap *Allow* on the phone) or Wi‑Fi: in
   FlipEx Desktop choose **Pair a new phone over Wi‑Fi** and scan the QR code from the phone's
   *Developer options → Wireless debugging → Pair device with QR code* (or type the pairing code).
   You pair once; after that the phone shows up under **Wi‑Fi** with a **Connect** button.
2. **Start DeX.** The DeX desktop opens in its own window. Pick the window size, and optionally
   turn the phone's screen off while it runs.
3. **Start Shizuku** from the same window after the phone restarts.

scrcpy and adb are built in, so there's nothing else to install. The app uses its own adb server
(port 5038), so it doesn't disturb Android Studio or another adb.

- **macOS:** the app isn't notarized. The first time, right‑click it → **Open** → **Open**. Allow
  *Local Network* access when asked; that's how it finds the phone on Wi‑Fi.
- **Windows:** SmartScreen may warn about an unknown publisher (**More info → Run anyway**). Over
  USB, Windows may need Samsung's USB driver; Wi‑Fi works without it.

### Mac script (picture paste)

The older Mac script does the same from a terminal, plus copy‑paste of pictures. It needs
[scrcpy](https://github.com/Genymobile/scrcpy) and `adb` (`brew install scrcpy android-platform-tools`).

```bash
scripts/dex-on-mac.sh            # over USB
scripts/dex-on-mac.sh --wifi     # over Wi-Fi (phone in Wireless debugging, paired once with the Mac)
scripts/dex-on-mac.sh --dark     # also turn the phone's screen off while it runs
scripts/dex-on-mac.sh --off      # put the desktop settings back to stock
```

While the window is open:

- **Text** copy‑paste works both ways.
- **Pictures** copied on the Mac (a screenshot, *Copy Image*, an image file in Finder) paste into
  DeX apps with **Cmd+V**. Wait for the Mac notification *Picture ready on the phone*. This works
  in apps that accept pasted pictures, such as Messages, Samsung Notes and WhatsApp.
- **Files** dragged onto the window are saved in the phone's **Download** folder. An `.apk` is
  installed instead.

For Wi‑Fi, pair the Mac once: on the phone open *Wireless debugging → Pair device with pairing
code*, then run `adb pair IP:PORT CODE` with the numbers it shows.

## What FlipEx changes

All of it is undone by **Restore stock settings**.

| Setting | Why |
|---|---|
| `force_desktop_mode_on_external_displays`, `enable_freeform_support`, `enable_non_resizable_multi_window` | Android's own desktop mode on external screens (Developer options) |
| `dex_on_external_display` | What real DeX sets: wallpaper and full DeX taskbar |
| `taskbar_recent_apps_enabled`, `taskbar_max_recent_count` | Open apps on the DeX taskbar |
| Per-monitor: system decorations, density 160 dpi, Full HD size on bigger monitors, keyboard hidden | A real DeX desktop on the monitor; see [How it works](#how-it-works) |
| Mouse ↔ monitor pairing | Mice move the pointer on the monitor |
| `mirror_built_in_display` (One UI 8.5 only) | Set to "own desktop" if it was set to mirror, the same as choosing *Extend* in Samsung's Connected display settings; put back when you turn DeX on monitors off |

## Troubleshooting

**Reporting a problem.** In FlipEx tap **More → Share diagnostics**. With the monitor plugged in, it
saves a text file in *Download* with your phone and One UI version, FlipEx's settings, the connected
screens and recent log lines about desktop mode and DeX. Look through it, then attach it to a
[GitHub issue](../../issues).

**The monitor shows a mirror of the phone.** Tap **Start DeX on the monitor** in FlipEx. If it says
Android kept the monitor mirroring, check that Shizuku is running and the Setup card is gone, then
send diagnostics. While a monitor mirrors, FlipEx leaves the mouse on the phone, so the mirror stays
usable as a remote control.

**Android asks to choose between *One UI Home* and *System UI*.** Pick **One UI Home → Always**.
*System UI* is the cover screen's home and doesn't work on a monitor.

**Nothing happens when I plug in.** The dock must output video. If the monitor says *No signal*,
try another hub or cable. Then check **DeX on monitors** is on.

**The mouse moves the pointer on the phone.** Unplug and replug the mouse, or toggle **Session**
off and on in FlipEx.

**Apps open on the phone instead of the monitor.** This can happen right after Android's System
UI restarts. FlipEx repairs it within a few seconds; replugging the monitor also fixes it.

**Shizuku stopped.** See [After a restart](#after-a-restart).

**I want everything back to normal.** FlipEx → **Restore stock settings**, then uninstall.

## Known limitations

- **Shizuku must be started after every restart.** Android turns Wireless debugging off on purpose.
- **The cover-screen trackpad needs Good Lock MultiStar**, which isn't available in every region.
- **This isn't Samsung's DeX service.** It's Android's desktop mode with Samsung's DeX launcher on
  top, so some DeX extras (DeX-only settings, keyboard shortcuts tied to the DeX service) are
  missing.
- **Tested on one phone so far** (Galaxy Z Flip 5, One UI 8). Reports from other Flips are welcome.
- **One UI 8.5 (experimental):** One UI 8.5 is built on Android 16 QPR2, which dropped the call
  FlipEx uses on One UI 8 to give a monitor its own desktop. There, Android decides mirror or
  desktop from its *mirror built-in display* setting. From FlipEx 0.3.1, on One UI 8.5 it sets that setting to
  "own desktop" if it was set to mirror, and puts it back when you turn off *DeX on monitors*.
  One UI 8 and earlier work exactly as before. This hasn't been tested on a One UI 8.5 phone yet,
  so if the monitor still mirrors, please send diagnostics.

## How it works

The Flip ships with all of DeX's parts: Samsung's DeX launcher (`SecondaryLauncher`) and taskbar,
and Android 16's desktop windowing. Only the switch that starts DeX on a wired monitor is missing.
FlipEx puts those parts together through Shizuku, using only Android's own shell commands and
system APIs:

1. **Desktop mode** for external screens: the Developer options setting.
2. **Its own desktop for the monitor.** On this phone Android connects an HDMI monitor in mirror
   mode. FlipEx gives it *system decorations*, the same flag a desktop screen gets, so Android
   starts Samsung's DeX home and wallpaper on it.
3. **DeX density.** The monitor is set to 160 dpi, the density Samsung uses for DeX. At the
   monitor's native density, Android's navigation bar is taller than the DeX taskbar and its
   buttons show above it. At 160 dpi the taskbar covers it exactly, as in real DeX. Monitors
   bigger than 1080p are drawn at Full HD and scaled up, like DeX's default, so the desktop isn't
   tiny on a 1440p or 4K screen.
4. **DeX settings** that Samsung's launcher reads: `dex_on_external_display` and the taskbar's
   recent‑apps settings.
5. **A desk** (Android 16 window shell) for screens that don't get one, so the taskbar can list
   open windows. FlipEx only removes desks it created itself, and only empty ones.
6. **Keyboard policy** per screen, so the phone's on-screen keyboard stays off the monitor.
7. **Mouse pairing**: each physical mouse is tied to the monitor by its input port.

The cover-screen trackpad is a virtual USB mouse (`/dev/uhid`) driven from a full-screen activity
on the cover display.

## Building from source

You need JDK 17+ and Gradle 9 (no wrapper is included).

```bash
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The package name is `dev.ved.flipdex`.

**FlipEx Desktop** (`desktop/`, Tauri 2) needs Rust and Node.js:

```bash
cd desktop
npm ci
scripts/fetch-scrcpy.sh        # pinned scrcpy + adb for this Mac, checksum-verified (win64 for Windows)
npx tauri build --bundles app  # or: npx tauri dev
```

GitHub Actions builds the Mac (Apple Silicon and Intel) and Windows versions and attaches them to
each published release (`.github/workflows/desktop.yml`).
