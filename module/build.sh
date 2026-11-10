#!/bin/bash
# 无 Studio / 无 Gradle 的命令行构建脚本（在 Linux 上验证通过）
# 依赖：JDK（只需 JRE + ecj.jar）、android.jar(platform-28)、build-tools 28.0.3
set -e

BT=${BT:-build-tools/28.0.3}      # aapt2 d8 zipalign apksigner
PLATFORM=${PLATFORM:-platforms/android-28/android.jar}
XPOSED=${XPOSED:-api-82.jar}
ECJ=${ECJ:-ecj.jar}
KS=${KS:-ks.jks}
KS_PASS=${KS_PASS:-android}

rm -rf out dex
mkdir -p out dex

# 1. 编译（没有 javac 时用 ecj）
java -jar "$ECJ" -source 8 -target 8 -proc:none \
  -bootclasspath "$PLATFORM" -cp "$XPOSED" \
  -d out src/com/swipeoff/MainHook.java

# 2. dex
"$BT/d8" --release --min-api 21 --lib "$PLATFORM" --classpath "$XPOSED" \
  --output dex out/com/swipeoff/*.class

# 3. 打包资源与 manifest
"$BT/aapt2" link -o base.apk -I "$PLATFORM" --manifest src/AndroidManifest.xml -A src/assets
cp base.apk unsigned.apk
(cd dex && zip -q -X ../unsigned.apk classes.dex)

# 4. 对齐 + 签名
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" \
  --out SwipeOff.apk aligned.apk
"$BT/apksigner" verify SwipeOff.apk
echo BUILD_OK
