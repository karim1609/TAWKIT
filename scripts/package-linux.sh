# Packaging script for Tawkit Desktop on Linux (change #13).
# Produces a .deb (or .rpm) installer with a bundled JRE via jpackage.
#
# Usage:
#   ./scripts/package-linux.sh            # deb
#   ./scripts/package-linux.sh rpm        # rpm

#!/usr/bin/env bash
set -euo pipefail

TYPE="${1:-deb}"
VERSION="1.0"

echo "==> Building the jar..."
mvn -q clean package -DskipTests

# jpackage copies every file from --input into the app folder, so stage only
# what the app needs: the jar plus the small MP3 decoder library. Hard links
# avoid duplicating the (large) jar on disk.
STAGE="target/package-input"
rm -rf "$STAGE"
mkdir -p "$STAGE/lib"
ln "target/tawkit-desktop-$VERSION-SNAPSHOT.jar" "$STAGE/tawkit-desktop-$VERSION-SNAPSHOT.jar"
cp -l target/lib/*.jar "$STAGE/lib/" 2>/dev/null || cp target/lib/*.jar "$STAGE/lib/"

echo "==> Creating $TYPE installer with jpackage..."
jpackage \
    --type "$TYPE" \
    --name Tawkit \
    --app-version "$VERSION" \
    --input "$STAGE" \
    --main-jar "tawkit-desktop-$VERSION-SNAPSHOT.jar" \
    --icon src/main/resources/assets/icon.png \
    --dest installer \
    --vendor "Tawkit Desktop team" \
    --description "Offline prayer times display for mosques and homes" \
    --linux-shortcut

echo "==> Done! Installer is in ./installer/"
ls -la installer/
