#!/bin/bash
# Downloads the pinned scrcpy release (it includes adb) for this Mac or for Windows, checks it
# against scrcpy's published SHA256SUMS and unpacks it into src-tauri/resources/scrcpy/.
#   scripts/fetch-scrcpy.sh                 this Mac (arm64 or x86_64)
#   scripts/fetch-scrcpy.sh macos-x86_64    a specific platform: macos-aarch64, macos-x86_64, win64
set -euo pipefail
VERSION=v5.0.1
cd "$(dirname "$0")/.."
platform=${1:-}
if [[ -z $platform ]]; then
  [[ $(uname -m) == arm64 ]] && platform=macos-aarch64 || platform=macos-x86_64
fi
case $platform in
  win64) file=scrcpy-win64-$VERSION.zip ;;
  macos-*) file=scrcpy-$platform-$VERSION.tar.gz ;;
  *) echo "unknown platform $platform" >&2; exit 2 ;;
esac
base=https://github.com/Genymobile/scrcpy/releases/download/$VERSION
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/$file" "$base/$file"
curl -fsSL -o "$tmp/SHA256SUMS.txt" "$base/SHA256SUMS.txt"
if command -v sha256sum >/dev/null; then sum=(sha256sum); else sum=(shasum -a 256); fi
(cd "$tmp" && grep " $file\$" SHA256SUMS.txt | "${sum[@]}" -c -) >&2
dest=src-tauri/resources/scrcpy
find "$dest" -mindepth 1 ! -name .keep -exec rm -rf {} +
if [[ $file == *.zip ]]; then
  unzip -q "$tmp/$file" -d "$tmp/x"
else
  mkdir "$tmp/x" && tar xzf "$tmp/$file" -C "$tmp/x"
fi
cp -R "$tmp"/x/scrcpy-*/. "$dest/"
rm -f "$dest"/open_a_terminal_here.bat "$dest"/scrcpy-noconsole.vbs "$dest"/scrcpy.1
echo "scrcpy $VERSION ($platform) in $dest"
