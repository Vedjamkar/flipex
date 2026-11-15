#!/bin/bash
# Samsung DeX desktop for the Flip 5, shown on this Mac through scrcpy.
#   ./dex-on-mac.sh            start over USB (turns on desktop mode, opens the DeX window)
#   ./dex-on-mac.sh --wifi     same, over Wi-Fi (phone: Developer options → Wireless debugging,
#                              same Wi-Fi as the Mac, paired once with this Mac)
#   ./dex-on-mac.sh --dark     also switch the phone's own screen off while it runs (combines with --wifi)
#   ./dex-on-mac.sh --shizuku  (re)start Shizuku, e.g. after the phone rebooted (USB or --wifi)
#   ./dex-on-mac.sh --off      put the desktop settings back to stock
#   ./dex-on-mac.sh --pictures only the picture copy-paste below, for a DeX window already open
# While the window is open: copy a picture on the Mac (screenshot, Copy Image, a file in Finder)
# and Cmd+V pastes it on the phone; drop files on the window to put them in Download.
# Optional: DEX_SIZE=1280x720/160  DEX_FPS=60  DEX_BITRATE=20M  (defaults keep the phone cooler)
# (FlipEx draws DeX at Full HD at most; a bigger DEX_SIZE makes scrcpy show only a corner.)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
export ADB
SIZE="${DEX_SIZE:-1920x1080/160}"
FPS="${DEX_FPS:-30}"
BITRATE="${DEX_BITRATE:-8M}"

mode=start wifi=0 dark=0
for arg in "$@"; do
  case "$arg" in
    --wifi) wifi=1 ;;
    --dark) dark=1 ;;
    --off) mode=off ;;
    --shizuku) mode=shizuku ;;
    --pictures) mode=pictures ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

# Runs a macOS dns-sd query for a few seconds and prints what it found (dns-sd never exits).
dnssd() {
  local out; out=$(mktemp)
  dns-sd "$@" >"$out" 2>&1 & local pid=$!
  sleep 3; kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null || true
  cat "$out"; rm -f "$out"
}

# Finds the phone's wireless-debugging service with Bonjour and connects ADB to it.
connect_wifi() {
  local serial
  serial=$("$ADB" devices | awk '$2=="device" && $1 ~ /^[0-9.]+:[0-9]+$/ {print $1; exit}')
  if [[ -z "$serial" ]]; then
    local name hostport host ip
    name=$(dnssd -B _adb-tls-connect._tcp local. | awk '/ Add / {print $NF; exit}')
    if [[ -z "$name" ]]; then
      echo "No phone found on Wi-Fi. Turn on Wireless debugging, on the same Wi-Fi as this Mac." >&2
      echo "First time: tap \"Pair device with pairing code\" and run: $ADB pair IP:PORT CODE" >&2
      exit 1
    fi
    hostport=$(dnssd -L "$name" _adb-tls-connect._tcp local. | grep -oE 'reached at [^ ]+:[0-9]+' | head -1 | sed 's/reached at //')
    host=${hostport%:*}
    ip=$(dnssd -G v4 "$host" | grep -oE '([0-9]+\.){3}[0-9]+' | grep -v '^0\.' | head -1)
    serial="$ip:${hostport##*:}"
    if ! "$ADB" connect "$serial" | grep -q '^connected'; then
      # An ADB server started before macOS allowed it on the local network gets "No route to host".
      "$ADB" kill-server >/dev/null 2>&1 || true
      "$ADB" connect "$serial" | grep -q '^connected' || { echo "Could not connect to $serial" >&2; exit 1; }
    fi
  fi
  export ANDROID_SERIAL="$serial"
  echo "Connected over Wi-Fi: $serial"
}

if [[ $wifi == 1 ]]; then
  connect_wifi
elif [[ -z "${ANDROID_SERIAL:-}" ]]; then
  # Prefer the USB connection when the phone is also reachable over Wi-Fi.
  usb=$("$ADB" devices | awk '$2=="device" && $1 !~ /:/ && $1 !~ /_adb-tls/ {print $1; exit}')
  [[ -n "$usb" ]] && export ANDROID_SERIAL="$usb"
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "No phone found. Plug it in, unlock it and allow USB debugging (or use --wifi)." >&2
  exit 1
fi

settings() { "$ADB" shell settings put global "$1" "$2"; }

case "$mode" in
  off)
    settings enable_freeform_support 0
    settings force_desktop_mode_on_external_displays 0
    settings enable_non_resizable_multi_window 0
    "$ADB" shell settings delete global overlay_display_devices >/dev/null
    echo "Desktop settings restored to stock. (FlipEx's \"Restore stock settings\" also resets its DeX extras.)"
    exit 0
    ;;
  shizuku)
    dir=$("$ADB" shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed 's/^package://; s#/base.apk##')
    "$ADB" shell "$dir/lib/arm64/libshizuku.so"
    exit 0
    ;;
esac

if [[ $mode == start ]]; then
  settings enable_freeform_support 1
  settings force_desktop_mode_on_external_displays 1
  settings enable_non_resizable_multi_window 1
  # Opening FlipEx starts its session (DeX features, taskbar desk, keyboard, mouse, lock cover).
  "$ADB" shell am start -n dev.ved.flipdex/.MainActivity >/dev/null 2>&1 || true
fi

extra=()
[[ $dark == 1 ]] && extra+=(--turn-screen-off)

# Pictures copied on the Mac go to the phone's clipboard (scrcpy itself only carries text),
# through FlipEx's MacClipProvider. Needs a FlipEx build that has it.
CLIP=content://dev.ved.flipdex.clip
send_picture() {  # file mime textfile
  local file=$1 mime=$2 small
  # Camera photos are often 5-10 MB: over Wi-Fi that takes seconds, and a Cmd+V pressed before
  # it lands pastes the file name instead. Anything over 1.5 MB is sent as a 2560 px JPEG.
  if [[ $mime != image/gif && $(wc -c <"$file") -gt 1500000 ]]; then
    small="${file%.*}-small.jpg"
    if sips -Z 2560 -s format jpeg -s formatOptions 85 "$file" --out "$small" >/dev/null 2>&1; then
      rm -f "$file"; file=$small; mime=image/jpeg
    fi
  fi
  if "$ADB" exec-in content write --uri "$CLIP/incoming" <"$file" \
      && "$ADB" exec-in content write --uri "$CLIP/text" <"$3" \
      && "$ADB" shell content call --uri "$CLIP" --method paste --arg "$mime" | grep -q 'result='; then
    echo "Picture copied to the phone ($(( $(wc -c <"$file") / 1024 )) KB). Paste with Cmd+V."
    osascript -e 'display notification "Paste it in DeX with Cmd+V" with title "Picture ready on the phone"' 2>/dev/null
  else
    echo "Could not copy the picture to the phone." >&2
    osascript -e 'display notification "Could not send it to the phone" with title "FlipEx"' 2>/dev/null
  fi
  rm -f "$file" "$3"
}
watch_pictures() {
  set +e  # one failed upload (Wi-Fi hiccup) must not stop the watcher
  osascript -l JavaScript "$HERE/mac-clipboard.js" "$clipdir" |
    while IFS=$'\t' read -r file mime text; do send_picture "$file" "$mime" "$text"; done
}
watcher=
clipdir=$(mktemp -d -t flipdex-clip)
cleanup() {
  if [[ -n "$watcher" ]]; then pkill -P "$watcher" 2>/dev/null; kill "$watcher" 2>/dev/null; fi
  rm -rf "$clipdir"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
if "$ADB" shell content gettype --uri "$CLIP/img/0.png" 2>/dev/null | grep -q image/; then
  if [[ $mode == pictures ]]; then
    echo "Watching the Mac clipboard for pictures (Ctrl+C to stop)."
    watch_pictures; exit 0
  fi
  watch_pictures & watcher=$!
else
  echo "Picture pasting is off: install the latest FlipEx on the phone." >&2
  [[ $mode == pictures ]] && exit 1
fi

# --new-display: scrcpy's own display, no preview on the phone; Android starts the DeX home on it.
# --mouse=sdk: the Mac cursor is the pointer (Android leaves its own cursor out of captures).
# --mouse-bind=++++:bhsn: every click goes to the phone; Shift+click gives back/home/etc.
# --no-vd-destroy-content: closing the window moves open apps back to the phone.
# Dropping a file on the window puts it in the phone's Download folder (an .apk is installed).
# ${extra[@]+...}: macOS bash 3.2 treats an empty array as unbound under set -u.
scrcpy --new-display="$SIZE" --no-vd-destroy-content \
  --window-title "Flip5 DeX" \
  --mouse=sdk --mouse-bind=++++:bhsn --keyboard=uhid \
  --max-fps "$FPS" --video-bit-rate="$BITRATE" --stay-awake --no-audio ${extra[@]+"${extra[@]}"}
