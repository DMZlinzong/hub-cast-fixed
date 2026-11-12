#!/bin/bash
# HUD 投屏 1.35 手工构建链（aapt2 link → kotlinc → d8 → zip → zipalign → apksigner）
set -e
SDK=/opt/android-sdk
BT=$SDK/build-tools/35.0.0
AJ=$SDK/platforms/android-35/android.jar
KOTLINC=/opt/kotlinc/bin/kotlinc
SRC=/workspace/hud-cast/app/src/main/java/cn/hud/cast
B=/tmp/apkbuild137

rm -rf $B
mkdir -p $B/classes $B/dex
cd $B

echo "=== 1. aapt2 link（manifest + 既有编译资源） ==="
$BT/aapt2 link -o base.apk \
  -I $AJ \
  --manifest /tmp/apkbuild/AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 38 --version-name 1.37 \
  /tmp/apkbuild/res.zip

echo "=== 2. kotlinc ==="
$KOTLINC -classpath $AJ -nowarn -jvm-target 17 -d classes $SRC/*.kt

echo "=== 3. classes.jar ==="
jar cf classes.jar -C classes .

echo "=== 4. d8（含 kotlin-stdlib，min-api 26） ==="
$BT/d8 --release --min-api 26 --lib $AJ \
  --output dex classes.jar /opt/kotlinc/lib/kotlin-stdlib.jar
ls -la dex/

echo "=== 5. 打包 dex ==="
cp base.apk unsigned.apk
zip -j -q unsigned.apk dex/classes.dex

echo "=== 6. zipalign ==="
$BT/zipalign -f -p 4 unsigned.apk aligned.apk

echo "=== 7. apksigner ==="
$BT/apksigner sign --ks /tmp/apkbuild/hud.jks --ks-key-alias hud \
  --ks-pass pass:hudcast --out /workspace/HUD投屏-1.37.apk aligned.apk
$BT/apksigner verify --print-certs /workspace/HUD投屏-1.37.apk | head -4

echo "=== 完成 ==="
ls -la /workspace/HUD投屏-1.37.apk
