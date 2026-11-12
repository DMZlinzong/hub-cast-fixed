#!/bin/bash
# HUD 投屏 1.82 手工构建链（aapt2 link → kotlinc → d8 → zip → zipalign → apksigner）
# 1.82：新增 zxing core（二维码）依赖；manifest 增加 INTERNET + usesCleartextTraffic
set -e
SDK=/opt/android-sdk
BT=$SDK/build-tools/35.0.0
AJ=$SDK/platforms/android-35/android.jar
KOTLINC=/opt/kotlinc/bin/kotlinc
SRC=/workspace/hud-cast/app/src/main/java/cn/hud/cast
ZXING=/tmp/zxing-core-3.4.1.jar
B=/tmp/apkbuild182

rm -rf $B
mkdir -p $B/classes $B/dex
cd $B

echo "=== 1. aapt2 link（manifest + 既有编译资源） ==="
$BT/aapt2 link -o base.apk \
  -I $AJ \
  --manifest /tmp/apkbuild/AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 83 --version-name 1.82 \
  /tmp/apkbuild/res.zip

echo "=== 2. kotlinc ==="
$KOTLINC -classpath "$AJ:$ZXING" -nowarn -jvm-target 17 -d classes $SRC/*.kt

echo "=== 3. classes.jar ==="
jar cf classes.jar -C classes .

echo "=== 4. d8（含 kotlin-stdlib + zxing core，min-api 26） ==="
$BT/d8 --release --min-api 26 --lib $AJ \
  --output dex classes.jar /opt/kotlinc/lib/kotlin-stdlib.jar $ZXING
ls -la dex/

echo "=== 5. 打包 dex ==="
cp base.apk unsigned.apk
zip -j -q unsigned.apk dex/classes.dex

echo "=== 6. zipalign ==="
$BT/zipalign -f -p 4 unsigned.apk aligned.apk

echo "=== 7. apksigner ==="
$BT/apksigner sign --ks /tmp/apkbuild/hud.jks --ks-key-alias hud \
  --ks-pass pass:hudcast --out /workspace/HUD投屏-1.82.apk aligned.apk
$BT/apksigner verify --print-certs /workspace/HUD投屏-1.82.apk | head -4

echo "=== 完成 ==="
ls -la /workspace/HUD投屏-1.82.apk
