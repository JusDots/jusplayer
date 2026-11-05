#!/usr/bin/env bash
#
# Builds the Linux desktop app.
#
#   ./scripts/package-desktop.sh            # app image + .tar.gz
#   ./scripts/package-desktop.sh --deb      # also .deb / .rpm when the tools exist
#
# jpackage needs dpkg-deb + fakeroot (.deb) or rpmbuild (.rpm); without them
# it refuses the format with "Invalid or unsupported type". The app image and
# the tarball always work.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Gradle needs a JDK it understands; the system one may be too new.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in "$HOME/.local/share/mise/installs/java"/*21* "$HOME/.sdkman/candidates/java"/*21*; do
    [[ -x "$candidate/bin/java" ]] && JAVA_HOME="$candidate" && break
  done
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "Set JAVA_HOME to a JDK 21 before running this script." >&2
  exit 1
fi
export JAVA_HOME

./gradlew :linux:createDistributable --console=plain

APP="linux/build/compose/binaries/main/app/jusplayer"
if [[ ! -x "$APP/bin/jusplayer" ]]; then
  # The launcher is named after packageName; find it if the casing changed.
  APP="$(dirname "$(find linux/build/compose/binaries/main/app -maxdepth 3 -type f -path '*/bin/*' | head -n 1)")/.."
  APP="$(cd "$APP" && pwd)"
fi

mkdir -p dist
tar -C "$(dirname "$APP")" -czf dist/jusplayer-linux-x64.tar.gz "$(basename "$APP")"
echo "→ dist/jusplayer-linux-x64.tar.gz"

if [[ "${1:-}" == "--deb" ]]; then
  if ! command -v dpkg-deb >/dev/null 2>&1; then
    echo "Skipping .deb: install dpkg (and fakeroot) first." >&2
  else
    ./gradlew :linux:packageDeb --console=plain || true
  fi
  if ! command -v rpmbuild >/dev/null 2>&1; then
    echo "Skipping .rpm: install rpm-tools first." >&2
  else
    ./gradlew :linux:packageRpm --console=plain || true
  fi
  find linux/build/compose/binaries/main -maxdepth 2 \( -name '*.deb' -o -name '*.rpm' \) -exec cp {} dist/ \; 2>/dev/null || true
fi

cat <<'EOF'

Run it with:
  ./linux/build/compose/binaries/main/app/jusplayer/bin/jusplayer

Runtime dependencies:
  ffmpeg   (required — decodes the audio)
  yt-dlp   (optional — whole tracks when YouTube truncates the direct URL)
  mpv      (optional — only the terminal client uses it)

Windows packages must be built on Windows:
  .\gradlew.bat :windows:createDistributable
  .\gradlew.bat :windows:packageMsi
EOF
