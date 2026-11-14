#!/usr/bin/env bash
# 编译「另一个应用」替身（放音乐 / 占麦克风）：不依赖 Gradle。
#
# 用途：给 P12-6（ducking）、P12-7（他应用占用麦克风）造一个可观测的对端，
# 因为没有它就没法验证「音乐被压低」「麦克风被占用」这两条的**对端**行为。
#
# 用法：
#   ANDROID_HOME=/path/to/sdk bash build.sh
#   adb install -r -g audio.apk
#   adb shell am start -n com.beichen.debug.audio/.MainActivity --es mode music
#   adb shell am start -n com.beichen.debug.audio/.MainActivity --es mode record
#   adb logcat -s DuckDemo
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
BT=${BUILD_TOOLS:-${ANDROID_HOME:?需要 ANDROID_HOME}/build-tools/34.0.0}
AJ=${ANDROID_JAR:-${ANDROID_HOME}/platforms/android-34/android.jar}
KS=${KS:-/tmp/stub-debug.jks}

cd "$HERE"
rm -rf build && mkdir -p build/classes

"$BT/aapt2" link -o build/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 26 --target-sdk-version 28

javac --release 8 -classpath "$AJ" -d build/classes \
  src/com/beichen/debug/audio/*.java

"$BT/d8" --lib "$AJ" --min-api 26 --output build build/classes/com/beichen/debug/audio/*.class

cp build/base.apk build/unsigned.apk
( cd build && zip -q -j unsigned.apk classes.dex )
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias stub -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=stub" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out audio.apk build/aligned.apk

"$BT/apksigner" verify audio.apk
ls -l audio.apk
