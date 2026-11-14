#!/usr/bin/env bash
# 编译桩识别服务：不依赖 Gradle，只要 build-tools + JDK。
#
# 用途：给「没有 RecognitionService 的镜像」造一个能应答的识别服务，
# 用来验 App 侧的调用链（起引擎 / 回调 / 文案映射 / 收尾 / 租约释放）。
# 它不认识任何话，也不碰麦克风 —— 别拿它验识别质量。
#
# 用法：
#   ANDROID_HOME=/path/to/sdk bash build.sh          # 产出 ./stt.apk
#   adb install -r -g stt.apk
#   adb shell settings put secure voice_recognition_service com.beichen.debug.stt/.StubRecognizer
#   adb shell setprop debug.stt.mode result          # result|nomatch|speech_timeout|client|busy|hang
#   adb shell setprop debug.stt.text 现在几点
#   adb shell setprop debug.stt.delay 1500
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
BT=${BUILD_TOOLS:-${ANDROID_HOME:?需要 ANDROID_HOME}/build-tools/34.0.0}
AJ=${ANDROID_JAR:-${ANDROID_HOME}/platforms/android-34/android.jar}
KS=${KS:-$HERE/debug.jks}

cd "$HERE"
rm -rf build && mkdir -p build/classes

"$BT/aapt2" link -o build/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 26 --target-sdk-version 28

javac --release 8 -classpath "$AJ" -d build/classes \
  src/com/beichen/debug/stt/StubRecognizer.java

"$BT/d8" --lib "$AJ" --min-api 26 --output build build/classes/com/beichen/debug/stt/*.class

cp build/base.apk build/unsigned.apk
( cd build && zip -q -j unsigned.apk classes.dex )
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias stt -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=stub-stt" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out stt.apk build/aligned.apk

"$BT/apksigner" verify stt.apk
ls -l stt.apk
