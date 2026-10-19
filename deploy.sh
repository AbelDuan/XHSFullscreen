#!/usr/bin/env bash
# 构建 + 部署 + 修正 LSPosed 路径 + 重启小红书（一条龙）
#
# 为什么要修正路径：LSPosed 把模块 APK 的绝对路径登记在 modules_config.db，
# 首次收录后永不更新；而 pm install 每次都分配新 codePath → 模块静默不加载。
# 本脚本装完立刻同步 apk_path，避免"装了但没生效"。
#
# 用法： ./deploy.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=com.abel.xhsfullscreen
SHARE=/sdcard/Download
DB_LOCAL=/tmp/lsp_deploy.db

echo "== 1/5 本地构建 =="
"$HERE/build_dexswap.sh" >/tmp/deploy_build.log 2>&1 || { tail -20 /tmp/deploy_build.log; exit 1; }
APK="$HERE/build_local/XHSFullscreen.apk"
[ -f "$APK" ] || { echo "构建产物缺失"; exit 1; }
echo "   $(ls -la "$APK" | awk '{print $5" bytes"}')"

echo "== 2/5 推送到设备 =="
cp -f "$APK" "$SHARE/xhs-deploy.apk"
dsh-native shell --reason "安装最新构建的XHS模块" -- "
cp -f $SHARE/xhs-deploy.apk /data/local/tmp/xhs_install/deploy.apk
chmod 644 /data/local/tmp/xhs_install/deploy.apk
pm install -r -d /data/local/tmp/xhs_install/deploy.apk
" 2>&1 | tail -3

echo "== 3/5 取真实路径 =="
REAL=$(dsh-native shell --reason "获取模块APK真实安装路径" -- \
  "pm path $PKG | sed 's/package://'" 2>/dev/null \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['stdout'].strip())" 2>/dev/null)
[ -n "$REAL" ] || { echo "取路径失败"; exit 1; }
echo "   $REAL"

echo "== 4/5 同步 LSPosed apk_path =="
dsh-native shell --reason "导出LSPosed数据库以同步模块路径" -- \
  "cp -f /data/adb/lspd/config/modules_config.db $SHARE/lsp_deploy.db && chmod 644 $SHARE/lsp_deploy.db" >/dev/null 2>&1
cp -f "$SHARE/lsp_deploy.db" "$DB_LOCAL"
python3 - "$DB_LOCAL" "$PKG" "$REAL" <<'PY'
import sqlite3, sys
path, pkg, real = sys.argv[1:4]
db = sqlite3.connect(path); cur = db.cursor()
cur.execute("SELECT apk_path FROM modules WHERE module_pkg_name=?", (pkg,))
old = cur.fetchone()
print("   旧:", old[0] if old else "(无记录)")
cur.execute("UPDATE modules SET apk_path=? WHERE module_pkg_name=?", (real, pkg))
if cur.rowcount == 0:
    cur.execute("INSERT INTO modules(module_pkg_name, apk_path) VALUES(?,?)", (pkg, real))
db.commit()
cur.execute("SELECT apk_path FROM modules WHERE module_pkg_name=?", (pkg,))
print("   新:", cur.fetchone()[0])
db.close()
PY
cp -f "$DB_LOCAL" "$SHARE/lsp_deploy.db"
dsh-native shell --reason "写回同步后的LSPosed数据库" -- "
cp -f $SHARE/lsp_deploy.db /data/adb/lspd/config/modules_config.db
chmod 600 /data/adb/lspd/config/modules_config.db
echo \"   登记: \$(grep -ao '/data/app/[^/]*/com.abel.xhsfullscreen[^/]*/base.apk' /data/adb/lspd/config/modules_config.db | sort -u)\"
echo \"   lspd: \$(pidof lspd)\"
" 2>&1 | tail -3

echo "== 5/5 重启小红书 =="
dsh-native shell --reason "重启小红书加载新模块" -- "
logcat -c 2>/dev/null
am force-stop com.xingin.xhs; sleep 2
for p in \$(ps -A -o PID,NAME 2>/dev/null | grep xingin | awk '{print \$1}'); do kill -9 \$p 2>/dev/null; done
sleep 3
am start -n com.xingin.xhs/.index.v2.IndexActivityV2 >/dev/null 2>&1
sleep 9
logcat -d 2>/dev/null | grep 'XHSFullscreen' | grep -E 'onPackageLoaded|installing|onAttached' | head -4
" 2>&1 | tail -8

echo
echo "部署完成。进帖子页后用以下命令取证："
echo "   dsh-native shell --reason 取证 -- \"logcat -d | grep -A8 'CLAMP'\""
