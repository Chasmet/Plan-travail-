#!/usr/bin/env sh
set -eu
GRADLE_VERSION=8.7
BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CACHE_DIR="$BASE_DIR/.gradle-bootstrap"
ZIP="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
DIST="$CACHE_DIR/gradle-$GRADLE_VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$CACHE_DIR"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -L --fail --retry 3 -o "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    else
      echo "curl ou wget est requis pour télécharger Gradle." >&2
      exit 1
    fi
  fi
  rm -rf "$DIST"
  unzip -q "$ZIP" -d "$CACHE_DIR"
fi
exec "$DIST/bin/gradle" "$@"
