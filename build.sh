#!/bin/bash
# Builds jisha.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
export STORE_PW KEY_PW
cd "$(dirname "$0")"

# Needs JDK 17+ and the Android SDK with build-tools 34.0.0 and platform android-34.
# Set ANDROID_SDK_ROOT (or ANDROID_HOME) and JAVA_HOME, or keep the defaults below.
B="${JISHA_TOOLS:-$HOME/firetv/buildtools}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$B/sdk}}"
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(ls -d "$B"/jdk-17*/Contents/Home 2>/dev/null | head -1)"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
[ -f "$JAR" ] || { echo "Android SDK not found at $SDK (set ANDROID_SDK_ROOT)"; exit 1; }

rm -rf build && mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 22 --target-sdk-version 22 \
    --java build/gen -o build/unsigned.apk build/res.zip

javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 \
    -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')

"$BT/d8" --min-api 22 --release --lib "$JAR" --output build/dex \
    $(find build/classes -name '*.class')

(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

# Signing key: kept out of the repo. On first build a key with a random password is created;
# the password lives in keystore.properties (git-ignored, readable only by you).
if [ ! -f keystore.properties ]; then
    PW="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
    printf 'storePassword=%s\nkeyPassword=%s\n' "$PW" "$PW" > keystore.properties
    chmod 600 keystore.properties
    rm -f jisha.keystore
fi
STORE_PW="$(grep '^storePassword=' keystore.properties | cut -d= -f2-)"
KEY_PW="$(grep '^keyPassword=' keystore.properties | cut -d= -f2-)"
[ -f jisha.keystore ] || keytool -genkeypair -keystore jisha.keystore -storepass "$STORE_PW" \
    -keypass "$KEY_PW" -alias jisha -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Jisha Launcher" >/dev/null 2>&1

"$BT/apksigner" sign --ks jisha.keystore --ks-pass env:STORE_PW --key-pass env:KEY_PW \
    --out jisha.apk build/aligned.apk
ls -la jisha.apk
