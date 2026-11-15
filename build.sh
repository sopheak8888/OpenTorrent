#!/bin/bash
# OpenTorrent — build without Gradle, using the Android SDK tools directly.
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"
JL="${JL:-$PROJ/libs}"
OUT="$PROJ/build"
LT_VER="2.1.0-39"
MAVEN="https://repo1.maven.org/maven2/org/libtorrent4j"

echo "build-tools : $BT"
echo "platform    : $PLATFORM"

# ---- 0. engine jars (fetched once, not committed) ---------------------------
mkdir -p "$JL"
fetch() { [ -f "$1" ] || curl -fsSL -o "$1" "$2"; }
fetch "$JL/libtorrent4j-$LT_VER.jar" \
  "$MAVEN/libtorrent4j/$LT_VER/libtorrent4j-$LT_VER.jar"
fetch "$JL/libtorrent4j-android-arm64-$LT_VER.jar" \
  "$MAVEN/libtorrent4j-android-arm64/$LT_VER/libtorrent4j-android-arm64-$LT_VER.jar"

rm -rf "$OUT"
mkdir -p "$OUT"/{res,gen,classes,dex}

# ---- 1. icon ----------------------------------------------------------------
python3 "$PROJ/make_icon.py"

# ---- 2. resources -----------------------------------------------------------
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  --no-version-vectors

# ---- 3. java ----------------------------------------------------------------
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" \
  -classpath "$JL/libtorrent4j-2.1.0-39.jar" \
  -d "$OUT/classes" \
  $(find "$PROJ/src" "$OUT/gen" -name '*.java')

# ---- 4. dex -----------------------------------------------------------------
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  @<(cat "$OUT/classes.txt"; echo "$JL/libtorrent4j-2.1.0-39.jar")

# ---- 5. package -------------------------------------------------------------
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT"
rm -rf stage && mkdir -p stage/lib/arm64-v8a
cp dex/classes.dex stage/
unzip -o -j "$JL/libtorrent4j-android-arm64-2.1.0-39.jar" \
  lib/arm64-v8a/libtorrent4j.so -d stage/lib/arm64-v8a >/dev/null
(cd stage && zip -q -r ../unsigned.apk classes.dex lib)

# ---- 6. align + sign --------------------------------------------------------
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk

KS="${OPENTORRENT_KEYSTORE:-$PROJ/keystore.jks}"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass opentorrent -keypass opentorrent \
    -alias opentorrent -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=OpenTorrent, O=Personal, C=KH" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass pass:opentorrent --key-pass pass:opentorrent \
  --out "$PROJ/OpenTorrent.apk" aligned.apk

"$BT/apksigner" verify "$PROJ/OpenTorrent.apk"

echo
echo "OK -> $PROJ/OpenTorrent.apk"
ls -lh "$PROJ/OpenTorrent.apk"