#!/usr/bin/env bash
# 编译桩 TTS 引擎：不依赖 Gradle，只要 build-tools + JDK。
#
# 用途：给「没有语音合成引擎的镜像」造一个能应答的 TTS，
# 让 TextSpeaker → VoiceArbiter 这条链路真的跑起来，
# 从而验证「朗读中被长按打断」「焦点请求类型」这类仲裁行为。
# 它不出声（吐静音 PCM），别拿它验音质/语速。
#
# 用法：
#   ANDROID_HOME=/path/to/sdk bash build.sh       # 产出 ./tts.apk
#   adb install -r -g tts.apk
#   adb shell settings put secure tts_default_synth com.beichen.debug.tts
#   adb shell settings put secure tts_default_rate 100
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
BT=${BUILD_TOOLS:-${ANDROID_HOME:?需要 ANDROID_HOME}/build-tools/34.0.0}
AJ=${ANDROID_JAR:-${ANDROID_HOME}/platforms/android-34/android.jar}
KS=${KS:-/tmp/stub-debug.jks}

cd "$HERE"
rm -rf build && mkdir -p build/classes build/res

"$BT/aapt2" compile --dir res -o build/res/compiled.zip

"$BT/aapt2" link -o build/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml --java build/gen \
  -R build/res/compiled.zip \
  --min-sdk-version 26 --target-sdk-version 28

javac --release 8 -classpath "$AJ" -d build/classes \
  src/com/beichen/debug/tts/*.java

"$BT/d8" --lib "$AJ" --min-api 26 --output build build/classes/com/beichen/debug/tts/*.class

cp build/base.apk build/unsigned.apk
( cd build && zip -q -j unsigned.apk classes.dex )
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias tts -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=stub-tts" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out tts.apk build/aligned.apk

"$BT/apksigner" verify tts.apk
ls -l tts.apk
