#!/bin/bash
# 通用 Android 骨架一键构建（免 Gradle 的手工构建链）
#
# 用法:
#   bash build.sh                        # 自动安装工具链并构建
#   ES_TOOLCHAIN=/opt/tc bash build.sh   # 使用已就绪的工具链目录
#   ES_WORK=/tmp/mybld bash build.sh     # 指定中间产物目录
#
# 说明:
#   1. /sdcard 是 noexec 挂载，工具链必须先复制到可执行目录才能运行，
#      本脚本会自动把 ./toolchain 复制到 ~/.cache/dsh-app-toolchain。
#      注意：run 之前不要直接在 /sdcard 上执行 toolchain/bin/*。
#   2. 项目路径含空格和中文，所以源文件/类文件都用数组逐个传参，
#      中间产物放在无空格的临时目录，避免 javac/d8 的 @argfile 解析问题；
#      同时强制 UTF-8 locale，否则 JVM 默认 ASCII 编码读不了中文路径。
set -eu

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

PROJ="$(cd "$(dirname "$0")" && pwd)"
TOOL="${ES_TOOLCHAIN:-$HOME/.cache/dsh-app-toolchain}"
WORK="${ES_WORK:-${TMPDIR:-/tmp}/dsh-app-build}"
NAME="memwatch"

# ------------------------------------------------------------------ 工具链
ensure_toolchain() {
    if [ -x "$TOOL/bin/aapt" ] && [ -x "$TOOL/bin/d8" ] && [ -f "$TOOL/android.jar" ]; then
        return 0
    fi
    SRC="$PROJ/toolchain"
    if [ ! -d "$SRC" ]; then
        echo "找不到工具链目录: $SRC" >&2
        exit 1
    fi
    echo "==> 安装工具链到 $TOOL"
    rm -rf "$TOOL"
    mkdir -p "$TOOL"
    cp -r "$SRC/." "$TOOL/"
    chmod -R u+rwX "$TOOL" 2>/dev/null || true
    chmod +x "$TOOL/bin/"* "$TOOL/real/"* 2>/dev/null || true
}

ensure_toolchain

AAPT="$TOOL/bin/aapt"
ZIPALIGN="$TOOL/bin/zipalign"
D8="$TOOL/bin/d8"
APKSIGNER="$TOOL/bin/apksigner"
ANDROID_JAR="$TOOL/android.jar"

echo "==> 0. 环境检查"
for f in "$AAPT" "$ZIPALIGN" "$D8" "$APKSIGNER" "$ANDROID_JAR"; do
    [ -e "$f" ] || { echo "缺少: $f" >&2; exit 1; }
done
[ -x "$AAPT" ] || { echo "不可执行: $AAPT" >&2; exit 1; }
command -v java    >/dev/null || { echo "缺少 java"    >&2; exit 1; }
command -v javac   >/dev/null || { echo "缺少 javac"   >&2; exit 1; }
command -v keytool >/dev/null || { echo "缺少 keytool" >&2; exit 1; }

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex"

# ------------------------------------------------------------------ 1. 编译
echo "==> 1. javac 编译"
SRCS=()
while IFS= read -r -d '' f; do SRCS+=("$f"); done \
    < <(find "$PROJ/src" "$PROJ/src-gen" -name '*.java' -print0)
if [ ${#SRCS[@]} -eq 0 ]; then
    echo "在 $PROJ/src 下没有找到 .java 文件" >&2
    exit 1
fi
javac -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -cp "$PROJ/libs/*" \
    -encoding UTF-8 \
    -Xlint:-options \
    -d "$WORK/classes" \
    "${SRCS[@]}"

# ------------------------------------------------------------------ 2. dex
echo "==> 2. d8 生成 dex"
CLASSES=()
while IFS= read -r -d '' f; do CLASSES+=("$f"); done \
    < <(find "$WORK/classes" -name '*.class' -print0)
LIBS=()
while IFS= read -r -d '' f; do LIBS+=("$f"); done \
    < <(find "$PROJ/libs" -name '*.jar' -print0)

"$D8" --release --min-api 21 --lib "$ANDROID_JAR" \
    --output "$WORK/dex" "${CLASSES[@]}" "${LIBS[@]}"

# ------------------------------------------------------------------ 3. 资源
echo "==> 3. aapt 打包资源与清单"
"$AAPT" package -f \
    -M "$PROJ/AndroidManifest.xml" \
    -S "$PROJ/res" \
    -I "$ANDROID_JAR" \
    -F "$WORK/base.apk"

echo "==> 4. 写入 classes.dex"
( cd "$WORK/dex" && "$AAPT" add "$WORK/base.apk" classes.dex )

echo "==> 5. zipalign 对齐"
"$ZIPALIGN" -f 4 "$WORK/base.apk" "$WORK/aligned.apk"

# ------------------------------------------------------------------ 4. 签名
echo "==> 6. 签名"
KS="$PROJ/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=DSH App,O=DSH,C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$PROJ/$NAME.apk" "$WORK/aligned.apk"

echo "==> 7. 校验"
"$APKSIGNER" verify --print-certs "$PROJ/$NAME.apk" | head -3

echo "==> 完成: $PROJ/$NAME.apk"
ls -lh "$PROJ/$NAME.apk"
