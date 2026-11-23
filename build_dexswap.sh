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
# ⚠ 必须自己写 zip 结构，不能用 zipfile.writestr 直接整包重写。
#   Android R+ (targetSdk >= 30) 在 instalPackageLI 阶段硬性要求
#   resources.arsc「不压缩 + 数据起点按 4 字节对齐」，否则安装直接失败：
#       Install Failure 1#-124 [-124: Failed parse during installPackageLI:
#       Targeting R+ (version 30 and above) requires the resources.arsc of
#       installed APKs to be stored uncompressed and aligned on a 4-byte boundary]
#   zipfile.writestr 不做任何对齐填充，产出的包必然踩这条（v1.7.0 Release 就是）。
#   Gradle/AGP 会自动对齐，所以 CI 产物没这个问题 —— 只有本脚本产物有。
#   下面按 ZIP 规范逐条写本地文件头/中央目录，用 extra field 把每个条目
#   的数据起点补齐到 4 字节边界。
python3 - "$BASE" "$OUT/dex/classes.dex" "$OUT/app-unsigned.apk" <<'PY'
import sys, zipfile, struct, zlib

base, dex, out = sys.argv[1:4]
drop = ('classes.dex', 'classes2.dex', 'META-INF/MANIFEST.MF')

items = []
with zipfile.ZipFile(base) as zin:
    for it in zin.infolist():
        n = it.filename
        if n in drop:
            continue
        # 丢掉 BASE 的旧签名，稍后重新签
        if n.startswith('META-INF/') and n.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')):
            continue
        items.append((n, zin.read(n), it.compress_type, it.date_time))
items.append(('classes.dex', open(dex, 'rb').read(), zipfile.ZIP_DEFLATED,
              (1980, 1, 1, 0, 0, 0)))

f = open(out, 'wb')
recs = []
for name_s, data, ctype, dt in items:
    name = name_s.encode('utf-8')
    if name_s == 'resources.arsc':
        ctype = zipfile.ZIP_STORED          # R+ 强制 STORED
    if ctype == zipfile.ZIP_DEFLATED:
        co = zlib.compressobj(9, zlib.DEFLATED, -15)
        body = co.compress(data) + co.flush()
    else:
        body = data
    crc = zlib.crc32(data) & 0xffffffff

    off = f.tell()
    # 数据起点 = 本地头(30) + 文件名 + extra，要让它落在 4 的倍数上。
    # extra 里优先用标准的 alignment padding 字段 (id=0xd935，长度不含自身 4 字节)，
    # 不足 4 字节时用裸字节填充（数据描述符位不置，不影响解析）。
    need = (4 - (off + 30 + len(name)) % 4) % 4
    if need == 0:
        extra = b''
    elif need >= 4:
        extra = struct.pack('<HH', 0xd935, need - 4) + b'\x00' * (need - 4)
    else:
        extra = b'\x00' * need

    f.write(struct.pack(
        '<IHHHHHIIIHH', 0x04034b50, 20, 0x0800, ctype,
        (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2),      # dos time
        ((dt[0] - 1980) << 9) | (dt[1] << 5) | dt[2],     # dos date
        crc, len(body), len(data), len(name), len(extra)))
    f.write(name)
    f.write(extra)
    assert f.tell() % 4 == 0, (name_s, f.tell())
    f.write(body)
    recs.append(dict(name=name, ctype=ctype, crc=crc, csize=len(body), usize=len(data),
                     extra=extra, off=off, dt=dt))

cd_off = f.tell()
for r in recs:
    dt = r['dt']
    f.write(struct.pack('<I', 0x02014b50))
    f.write(struct.pack('<HHH', 20, 20, 0x0800))
    f.write(struct.pack('<HHH', r['ctype'],
                        (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2),
                        ((dt[0] - 1980) << 9) | (dt[1] << 5) | dt[2]))
    f.write(struct.pack('<III', r['crc'], r['csize'], r['usize']))
    f.write(struct.pack('<HHH', len(r['name']), len(r['extra']), 0))
    f.write(struct.pack('<HH', 0, 0))
    f.write(struct.pack('<I', 0))
    f.write(struct.pack('<I', r['off']))
    f.write(r['name'])
    f.write(r['extra'])
cd_size = f.tell() - cd_off
f.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(recs), len(recs),
                    cd_size, cd_off, 0))
f.close()
print("  组装完成:", out, f"({len(recs)} 条目)")
PY

echo "[4/4] 签名 ..."
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storetype JKS \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -alias androiddebugkey -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
APK="$OUT/XHSFullscreen.apk"
# 用 apksigner 出 v2/v3 签名：它只在文件末尾追加签名块，不重新打包 zip，
# 因此 [3/4] 做好的 4 字节对齐会被完整保留。
# （jarsigner 走 v1 方案，会把 MANIFEST.MF 塞回包里重新写一遍 zip，
#   对齐当场丢失 —— 实测 resources.arsc 从 mod4=0 变成 mod1 —— 所以不用它。）
APKSIGNER="${APKSIGNER:-apksigner}"
"$APKSIGNER" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/app-unsigned.apk"
"$APKSIGNER" verify "$APK" >/dev/null

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

# R+ 安装硬性要求：resources.arsc 必须 STORED 且数据起点 4 字节对齐。
# 这里直接读原始字节复核，不依赖 zipfile（它不暴露数据起点）。
import struct
raw = open(sys.argv[1], 'rb').read()
zoff = raw.rfind(b'PK\x05\x06')
cnt = struct.unpack('<H', raw[zoff + 10:zoff + 12])[0]
cdoff = struct.unpack('<I', raw[zoff + 16:zoff + 20])[0]
p = cdoff
found = None
for _ in range(cnt):
    ch = raw[p:p + 46]
    nl, el, cl = struct.unpack('<HHH', ch[28:34])
    lho = struct.unpack('<I', ch[42:46])[0]
    p += 46 + nl + el + cl
    nl2, el2 = struct.unpack('<HH', raw[lho + 26:lho + 30])
    name = raw[lho + 30:lho + 30 + nl2].decode()
    if name == 'resources.arsc':
        found = (struct.unpack('<H', ch[10:12])[0], lho + 30 + nl2 + el2)
if found is None:
    print("!! APK 里没有 resources.arsc")
    sys.exit(1)
method, data_off = found
if method != 0:
    print("!! resources.arsc 被压缩了，Android R+ 会拒绝安装")
    sys.exit(1)
if data_off % 4:
    print(f"!! resources.arsc 数据起点 {data_off} 未按 4 字节对齐，Android R+ 会拒绝安装")
    sys.exit(1)
print(f"  对齐: resources.arsc STORED @ {data_off} (mod 4 = 0) OK")
PY
