#!/bin/sh
# PhoneBox 无 Gradle 构建脚本
# 依赖：JDK 17、Android SDK（platform 34 + build-tools 34.0.0）
#
# 用法：
#   sh build.sh
#   VER=0.2.0 VC=200 sh build.sh
#   ANDROID_SDK=/path/to/sdk JAVA_HOME=/path/to/jdk17 sh build.sh
#
# 说明：
# - 首次构建会在 keystore/phonebox.jks 生成自用签名，请备份，后续覆盖安装必须同签名。
# - 也可以显式指定 KEYSTORE / KEYSTORE_PASS / KEY_ALIAS / KEY_PASS。
set -e

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    KEYTOOL="$JAVA_HOME/bin/keytool"
    export PATH="$JAVA_HOME/bin:$PATH"
else
    JAVAC="$(command -v javac || true)"
    KEYTOOL="$(command -v keytool || true)"
fi

# 找 platform：优先 ANDROID_PLATFORM，其次 platforms/android-*/android.jar 中最新的
if [ -n "$ANDROID_PLATFORM" ]; then
    PLATFORM="$ANDROID_PLATFORM"
else
    PLATFORM="$(ls -1 "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
fi

# 找 build-tools：优先 ANDROID_BUILD_TOOLS，其次 build-tools/*/aapt 中最新的
if [ -n "$ANDROID_BUILD_TOOLS" ]; then
    BT="$ANDROID_BUILD_TOOLS"
else
    BT="$(dirname "$(ls -1 "$SDK"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1 || true)" 2>/dev/null || true)"
fi

APP=com.zhoufla.phonebox
DEF_VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
DEF_VC=$(grep -o 'versionCode="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
VER="${VER:-$DEF_VER}"
VC="${VC:-$DEF_VC}"

KEYSTORE="${KEYSTORE:-keystore/phonebox.jks}"
KEYSTORE_PASS="${KEYSTORE_PASS:-phonebox}"
KEY_ALIAS="${KEY_ALIAS:-phonebox}"
KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"

for f in "$JAVAC" "$KEYTOOL" "$PLATFORM" "$BT/aapt" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
    if [ -z "$f" ] || [ ! -e "$f" ]; then
        echo "缺少构建工具：$f" >&2
        echo "请设置 ANDROID_SDK / ANDROID_PLATFORM / ANDROID_BUILD_TOOLS / JAVA_HOME" >&2
        echo "需要：platforms;android-34、build-tools;34.0.0、JDK 17" >&2
        exit 1
    fi
done

OUT=dist
rm -rf build "$OUT"
mkdir -p build/classes build/dex "$OUT"

echo "== 1/6 生成未签名骨架 version=$VER ($VC)"
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER\"/" \
    AndroidManifest.xml > build/AndroidManifest.xml
"$BT/aapt" package -f -M build/AndroidManifest.xml -A assets -S res -I "$PLATFORM" \
    -F build/base.apk --min-sdk-version 26 --target-sdk-version 33

echo "== 2/6 编译 Java"
find src -name '*.java' | sort > build/sources.txt
if [ ! -s build/sources.txt ]; then
    echo "src 下没有 Java 源文件" >&2
    exit 1
fi
if ! "$JAVAC" -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options \
        -bootclasspath "$PLATFORM" -d build/classes @build/sources.txt; then
    echo "编译失败" >&2
    exit 1
fi

echo "== 3/6 生成 dex"
find build/classes -name '*.class' | sort > build/classes.txt
"$BT/d8" --min-api 26 --lib "$PLATFORM" --output build/dex @build/classes.txt

echo "== 4/6 打包"
cp build/base.apk build/unsigned.apk
python3 - <<'PY'
import zipfile
z = zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('build/dex/classes.dex', 'classes.dex')
z.close()
PY

echo "== 5/6 对齐"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

echo "== 6/6 签名"
if [ ! -e "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -storepass "$KEYSTORE_PASS" -keypass "$KEY_PASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=PhoneBox, O=PhoneBox, C=CN" >/dev/null 2>&1
    echo "   已生成 $KEYSTORE，请备份（密码 $KEYSTORE_PASS）"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEY_PASS" --out "$OUT/$APP-$VER.apk" build/aligned.apk

"$BT/apksigner" verify --print-certs "$OUT/$APP-$VER.apk" | head -5
ls -lh "$OUT/$APP-$VER.apk"

SIZE=$(wc -c < "$OUT/$APP-$VER.apk" | tr -d ' ')
SHA=$(sha256sum "$OUT/$APP-$VER.apk" | cut -d' ' -f1)
cat > "$OUT/latest.json" <<EOF
{
  "version": "$VER",
  "code": $VC,
  "apk": "$APP-$VER.apk",
  "size": $SIZE,
  "sha256": "$SHA"
}
EOF

echo "完成：$OUT/$APP-$VER.apk"
echo "sha256=$SHA"
