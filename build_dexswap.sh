#!/usr/bin/env bash
# 本地构建 XHSFullscreen（dexswap 方案）—— 不需要 Gradle、不需要 aapt2、不需要联网。
#
# 原理
# ----
# 本机容器里没有可用的 aapt2（SDK 自带的是 Android 目标二进制，proot 里报
# "bad machine"；Debian 版 aapt2 2.19 又解析不了 SDK 36 的 resources.arsc）。
# 但本模块的资源/清单/版本号极少变动，所以复用一份已构建好的 BASE apk 的
# 资源部分，只重新编译 classes.dex 并替换进去 —— 这与完整构建等价，
# 因为变化的只有 Java 代码。
#
# 工具链（本机已有）
#   JDK 17      /usr/lib/jvm/java-17-openjdk-arm64
#   android.jar /root/LSP&KSU/work/sdk/platforms/android-36/android.jar
#   d8          /root/LSP&KSU/work/tools/r8.jar
#   libxposed   app/libs/libxposed-api-102.jar（compileOnly，不打包）
#
# 用法
#   ./build_dexswap.sh [BASE.apk]
#   默认 BASE = base/XHSFullscreen-base.apk
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/app/src/main/java"
OUT="$HERE/build_local"

JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

ANDROID_JAR="/root/LSP&KSU/work/sdk/platforms/android-36/android.jar"
R8_JAR="/root/LSP&KSU/work/tools/r8.jar"
LIBXP="$HERE/app/libs/libxposed-api-102.jar"
KS="$HERE/debug.keystore"

BASE="${1:-$HERE/base/XHSFullscreen-base.apk}"

for f in "$ANDROID_JAR" "$R8_JAR" "$LIBXP" "$BASE"; do
  [ -e "$f" ] || { echo "缺少必要文件: $f" >&2; exit 1; }
done

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/4] javac 编译 ..."
find "$SRC" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -nowarn \
  -cp "$ANDROID_JAR:$LIBXP" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" 2>&1 \
  | grep -vE "^warning|bootstrap class|source value 8|target value 8|^Note|deprecat" || true

echo "[2/4] d8 转 dex ..."
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 27 --release --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "[3/4] dexswap 组装（复用 BASE 的资源，只换 dex）..."
python3 - "$BASE" "$OUT/dex/classes.dex" "$OUT/app-unsigned.apk" <<'PY'
import sys, zipfile
base, dex, out = sys.argv[1:4]
drop = ('classes.dex', 'classes2.dex', 'META-INF/MANIFEST.MF')
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, 'w') as zout:
    for it in zin.infolist():
        n = it.filename
        if n in drop:
            continue
        # 丢掉 BASE 的旧签名，稍后重新签
        if n.startswith('META-INF/') and n.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')):
            continue
        zi = zipfile.ZipInfo(n, date_time=it.date_time)
        zi.compress_type = it.compress_type   # resources.arsc 必须保持 STORED
        zi.external_attr = it.external_attr
        zout.writestr(zi, zin.read(n))
    zi = zipfile.ZipInfo('classes.dex', date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    zout.writestr(zi, open(dex, 'rb').read())
print("  组装完成:", out)
PY

echo "[4/4] 签名 ..."
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storetype JKS \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -alias androiddebugkey -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
APK="$OUT/XHSFullscreen.apk"
cp "$OUT/app-unsigned.apk" "$APK"
# 本机无 apksigner，用 jarsigner（v1 签名）；设备侧核心破解已放行校验。
jarsigner -keystore "$KS" -storepass android -keypass android \
  -sigalg SHA256withRSA -digestalg SHA-256 "$APK" androiddebugkey >/dev/null

echo
echo "构建完成: $APK"
ls -la "$APK"
echo "--- 校验 ---"
python3 - "$APK" <<'PY'
import zipfile, sys
z = zipfile.ZipFile(sys.argv[1])
names = z.namelist()
dex = [n for n in names if n.endswith('.dex')]
xp  = [n for n in names if n.startswith('META-INF/xposed')]
print("dex:", dex, "| xposed:", len(xp), "个")
if len(dex) != 1:
    print("!! 不是单 dex，LSPosed 可能加载失败")
d = z.read('classes.dex')
for s in ('MainHook', 'clampHookFor', 'attachLayoutGuard', 'onPackageLoaded'):
    print(f"  {s}: {'有' if s.encode() in d else '无'}")
for bad in ('FILL_THRESHOLD', 'clearMaxWidth', 'getMeasuredWidth'):
    print(f"  [应无] {bad}: {'有（回归！）' if bad.encode() in d else '无'}")
PY
