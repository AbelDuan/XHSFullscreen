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
# ---- 先把版本号写进 BASE 的二进制清单 ----
# dexswap 复用 BASE 的资源与清单，build.gradle 里的 versionCode/versionName
# **不会**进入产物（清单是 BASE 的）。v1.7.1 首次发布就栽在这里：
# 装上去 versionName 还是 1.7。这里直接改二进制清单，改动最小：
#   versionCode  → 属性区里的整数立即数，原地改
#   versionName  → 字符串池里的条目，原地改写（消耗池尾填充余量）
VCODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]\+\([0-9]\+\).*/\1/p' app/build.gradle | head -1)"
VNAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\(.*\)".*/\1/p' app/build.gradle | head -1)"
if [ -z "$VCODE" ] || [ -z "$VNAME" ]; then
  echo "读不到 app/build.gradle 里的 versionCode / versionName" >&2
  exit 1
fi
echo "  写入版本: versionCode=$VCODE versionName=$VNAME"
python3 - "$BASE" "$OUT/AndroidManifest.xml" "$VCODE" "$VNAME" <<'PY'
# 把版本号写进 BASE 的二进制清单。
# 关键点：二进制 XML 的 UTF-16 串编码是
#     u16 长度 + 内容 + u16 NUL 终止符   （宽度为奇数时再补一个 NUL）
# 少了终止符，apksig / PackageManager 会直接报
#     UTF-16 encoded form of string not NULL terminated
# 池里条目之间只有 2 字节间隙，所以版本号变长时不能原地改写，
# 这里重排整个字符串池并平移其后所有块。
import sys, struct, zipfile

RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102
INT_DEC = 0x10
UTF8_FLAG = 0x100


def read_pool(m, off):
    hsize = struct.unpack('<H', m[off + 2:off + 4])[0]
    size = struct.unpack('<I', m[off + 4:off + 8])[0]
    count, style_count, flags, sstart, stystart = struct.unpack('<IIIII', m[off + 8:off + 28])
    if flags & UTF8_FLAG:
        raise SystemExit('只支持 UTF-16 字符串池')
    offsets = list(struct.unpack('<%dI' % count, m[off + hsize:off + hsize + 4 * count]))
    data = off + sstart
    strs = []
    for i in range(count):
        p = data + offsets[i]
        n = struct.unpack('<H', m[p:p + 2])[0]
        strs.append(m[p + 2:p + 2 + n * 2].decode('utf-16-le'))
    return dict(off=off, hsize=hsize, size=size, count=count, style_count=style_count,
                flags=flags, sstart=sstart, stystart=stystart, strings=strs)


def build_pool(off, pool):
    """按 2 字节对齐重排所有条目，返回新的池块字节。"""
    body = bytearray()
    offsets = []
    for s in pool['strings']:
        offsets.append(len(body))
        # 二进制 XML 的 UTF-16 串编码：u16 长度 + 内容 + **u16 NUL 终止符**。
        # 走 ARSC 风格时宽度还需是偶数（奇数则再补一个 NUL）。缺终止符会让
        # apksig / PackageManager 直接报 "UTF-16 encoded form of string not NULL terminated"。
        body += struct.pack('<H', len(s)) + s.encode('utf-16-le') + b'\x00\x00'
        if len(s) % 2:
            body += b'\x00\x00'
    if len(body) % 4:
        body += b'\x00' * (4 - len(body) % 4)
    hsize = 28
    sstart = hsize + 4 * pool['count']
    size = sstart + len(body)
    hdr = struct.pack('<HHIIIIII', RES_STRING_POOL_TYPE, hsize, size, pool['count'],
                      0, pool['flags'] & ~UTF8_FLAG, sstart, 0)
    return hdr + struct.pack('<%dI' % pool['count'], *offsets) + bytes(body)


def manifest_attrs(m, pool, total):
    found = {}
    pos = pool['off'] + pool['size']
    while pos < total:
        ctyp, chdr, csz = struct.unpack('<HHI', m[pos:pos + 8])
        if ctyp == RES_XML_START_ELEMENT_TYPE:
            tag = pool['strings'][struct.unpack('<I', m[pos + 20:pos + 24])[0]]
            if tag == 'manifest':
                ast, asz, ac = struct.unpack('<HHH', m[pos + 24:pos + 30])
                for k in range(ac):
                    a = pos + chdr + ast + k * asz
                    found[pool['strings'][struct.unpack('<I', m[a + 4:a + 8])[0]]] = a
                break
        pos += csz
    return found


def patch(m, vcode, vname):
    total = struct.unpack('<I', m[4:8])[0]
    if total != len(m):
        raise SystemExit('清单长度与文件头不一致 %d != %d' % (total, len(m)))
    pool = read_pool(m, 8)
    attrs = manifest_attrs(m, pool, total)

    # versionCode：属性区原地改
    a = attrs.get('versionCode')
    if a is None or m[a + 15] != INT_DEC:
        raise SystemExit('找不到整数型 versionCode 属性')
    m = bytearray(m)
    struct.pack_into('<I', m, a + 16, vcode)

    # versionName：改池下标对应条目
    a = attrs.get('versionName')
    if a is None:
        raise SystemExit('找不到 versionName 属性')
    idx = struct.unpack('<I', m[a + 8:a + 12])[0]
    old = pool['strings'][idx]
    strings = list(pool['strings'])
    strings[idx] = vname

    # 重建池并平移后续块
    new_pool = build_pool(8, dict(pool, strings=strings))
    delta = len(new_pool) - pool['size']
    tail = bytes(m[pool['off'] + pool['size']:])
    out = bytearray(m[:8]) + bytearray(new_pool) + bytearray(tail)
    struct.pack_into('<I', out, 4, len(out))

    # 平移后属性区移位，versionName 的 rawValue 索引不变（池下标没变）
    # 但 versionCode 已在上面直接写入，需要重新定位并确认
    npool = read_pool(out, 8)
    nattrs = manifest_attrs(out, npool, len(out))
    ca = nattrs.get('versionCode')
    if ca is not None:
        struct.pack_into('<I', out, ca + 16, vcode)

    print('   versionName %r -> %r' % (old, vname))
    print('   versionCode -> %d' % vcode)
    print('   字符串池 %d -> %d 字节 (delta %+d)' % (pool['size'], len(new_pool), delta))
    return bytes(out), npool, nattrs

apk, out_path, vcode, vname = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
m = zipfile.ZipFile(apk).read('AndroidManifest.xml')
patched, npool, nattrs = patch(m, vcode, vname)

# 结构自检：块链必须首尾相接覆盖全文
pos = 8 + struct.unpack('<I', patched[12:16])[0]
while pos < len(patched):
    t, h, sz = struct.unpack('<HHI', patched[pos:pos + 8])
    if sz < 8 or pos + sz > len(patched):
        raise SystemExit('清单块链损坏 @%d size=%d' % (pos, sz))
    pos += sz
if pos != len(patched):
    raise SystemExit('清单块链未覆盖全文 %d != %d' % (pos, len(patched)))
if struct.unpack('<I', patched[4:8])[0] != len(patched):
    raise SystemExit('清单文件头长度不符')

open(out_path, 'wb').write(patched)
print('  清单: versionCode=%d versionName=%r' % (vcode, vname))
PY

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
python3 - "$BASE" "$OUT/dex/classes.dex" "$OUT/app-unsigned.apk" "$OUT/AndroidManifest.xml" <<'PY'
import sys, zipfile, struct, zlib

base, dex, out, manifest = sys.argv[1:5]
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
        # 清单用上一步写入了版本号的那份替换（其余内容完全一致）
        if n == 'AndroidManifest.xml':
            items.append((n, open(manifest, 'rb').read(), it.compress_type, it.date_time))
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
python3 - "$APK" "$VCODE" "$VNAME" <<'PY'
import zipfile, sys, struct
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

# 版本号：dexswap 复用 BASE 的清单，必须确认补丁真的写进去了。
want_code, want_name = sys.argv[2], sys.argv[3]
man = z.read("AndroidManifest.xml")
tot = struct.unpack("<I", man[4:8])[0]
psize = struct.unpack("<I", man[12:16])[0]
cnt = struct.unpack("<I", man[16:20])[0]
sstart = struct.unpack("<I", man[28:32])[0]
sof = struct.unpack("<%dI" % cnt, man[36:36 + 4 * cnt])
sbase = 8 + sstart


def _s(i):
    q = sbase + sof[i]
    ln = struct.unpack("<H", man[q:q + 2])[0]
    return man[q + 2:q + 2 + ln * 2].decode("utf-16-le")


got = {}
q = 8 + psize
while q < tot:
    t2, h2, z2 = struct.unpack("<HHI", man[q:q + 8])
    if t2 == 0x0102 and _s(struct.unpack("<I", man[q + 20:q + 24])[0]) == "manifest":
        ast, asz, ac = struct.unpack("<HHH", man[q + 24:q + 30])
        for k in range(ac):
            a2 = q + h2 + ast + k * asz
            nm = _s(struct.unpack("<I", man[a2 + 4:a2 + 8])[0])
            dt2 = man[a2 + 15]
            dv = struct.unpack("<I", man[a2 + 16:a2 + 20])[0]
            if nm in ("versionCode", "versionName"):
                got[nm] = str(dv) if dt2 == 0x10 else _s(dv)
        break
    q += z2
print(f"  版本: versionCode={got.get('versionCode')} versionName={got.get('versionName')}")
if got.get("versionCode") != want_code or got.get("versionName") != want_name:
    print(f"!! 清单版本号不对：期望 {want_code}/{want_name}，"
          f"实际 {got.get('versionCode')}/{got.get('versionName')}")
    sys.exit(1)
PY
