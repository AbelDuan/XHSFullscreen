# XHSFullscreen

LSPosed 模块,让**小红书 (com.xingin.xhs)** 在折叠屏内屏的笔记**详情页**(图文 / 视频)铺满左右,而**不改变系统 density**,也**不破坏一级信息流的多列排版**。

> **v1.2 新增:内外屏「排数档位」独立记忆** —— 内屏和外屏各自记自己的手势缩放档位,
> 互不影响。详见下方「内外屏排数档位独立记忆」一节。

## 问题原因

在自定义密度(如 density=345 + 字体 125%)的折叠屏上,小红书笔记详情页左右留有大片空白。
根因**不是** `maxWidth` 限宽容器——整棵内容树本来就是满宽的。真正原因是小红书给
评论区(`CommentListView`)和图文区(`AsyncImageInfoView`)加了一个**很大的左右对称内边距**,
并且在**评论异步加载完成后会反复重新设置**这个内边距。

在内屏实测(Xiaomi 折叠屏,density 345)中,该值会在 **277 / 189** 之间反复出现,
**约每 1.5 秒重设一次**(见下方「抖动问题」)。

## 模块做了什么

小红书把「左右留白」做成了**两层**:外层容器 `NoteDetailContentView` 的内边距,
加上子视图 `CommentListView` / `AsyncImageInfoView` 自己的内边距。两者叠加时
总留白翻倍,内容看起来依然很窄。

模块按**层级分别处理**:

- **外层容器 `NoteDetailContentView`**:内边距**清零**。它本身是满宽容器(1672px),
  给它加内边距会直接把子视图挤窄 `45×2=90px`。
- **内容区 `CommentListView` / `AsyncImageInfoView`**:内边距固定为 `PAD_TARGET`(45px)。

生效手段(三层,逐层提前):

| 层 | 时机 | 作用 |
|---|---|---|
| `onMeasure` | 布局**之前** | 强制宽度满屏 + 归一内边距,**首帧即正确** |
| `OnPreDrawListener` | 绘制**之前** | 拦掉中途被重设的 277/189,绝不落到屏幕上 |
| `onAttachedToWindow` / 构造函数 | 视图**创建时** | 尽早接管,缩小窗口期 |

- 一级信息流、顶部栏、底部栏、视频评论区均不受影响,多列排版与正常布局保持不变。
- **不会**修改系统 density、`smallestScreenWidthDp` 或任何全局设置。

## 抖动问题(v2 修复)

**症状**:打开帖子时内容先是明显变窄,随后「弹」回设定宽度。

**真因有两层**:

1. **时序**:小红书在详情页创建后会把 padding 重设为 277/189。早期实现在
   布局**完成之后**(`OnGlobalLayoutListener`)才改回 45——那一帧的窄版
   **已经绘制到屏幕上了**,于是每次拉锯都是一次可见的「变窄 → 弹回」。
2. **层级**(真正的元凶):早期实现把三个类一视同仁地设成 45px,
   包括本该保持 0 的外层容器 `NoteDetailContentView`。实测宽度关系:

```
NoteDetailContentView  pad=45  w=1672   ← 满宽容器,却被塞了 45px 内边距
CommentListView        pad=45  w=1582   ← 被挤窄 90px,又叠 45px
AsyncImageInfoView     pad=45  w=1582
```

总留白 `45+45+45+45=180px`,是预期(90px)的两倍——**这才是「看起来还是很窄」
的真正原因**。只调 padding 数值永远调不出期望效果。

修正后实测(逐帧日志,连续多帧):

```
F0  NoteDetailContentView  pad->0   w=1672
F0  CommentListView        pad->45  w=1672   ← 首帧即最终宽度
F1  CommentListView        pad->45  w=1672
F2  CommentListView        pad->45  w=1672
```

连续两张截图**字节完全相同**,证明画面已完全静止。

> **补充事实**:`setPadding` / `setPaddingRelative` 的 hook 在这个场景下命中数为 0,
> 说明小红书并非经由公开 setter 设置该值(内部字段级写入),因此**必须**在
> measure / preDraw 阶段拦截,单纯 hook setter 无法解决。



## 内外屏排数档位独立记忆（v1.2 新增）

### 问题

DPI 调低到 350 后，外屏是 534dp、内屏是 764dp，**两块屏都跨过了 600dp 这条「平板布局」分界线**。
小红书据此把内外屏判成同一块屏，于是内屏用手势缩放调好的排数，会原封不动带到外屏 ——
内屏舒适，外屏就小得看不清。

### 根因（dexdump 取证，classes18.dex）

小红书的手势缩放档位只有一份：

```
ms.n$c { ZOOM_IN=0, NORMAL=1, ZOOM_OUT=2 }        // 缩放档位
ms.n$d { TINNY=0, SMALL=1, MIDDLE=2, BIG=3, LAGE=4 }  // 宽度分档

<clinit> : d = q3e.h.g().j("screen_scale_sp_key", NORMAL)
a()      : return d                                    // 取档位
m(i, f)  : d = g(i, f); q3e.h.g().u("screen_scale_sp_key", d)
```

- key 固定为 `screen_scale_sp_key`，**只有一份**；
- 只在静态字段 `ms.n.d` 里缓存一次，切屏不会重读。

### 做法

**不动它自己的档位体系**（枚举、`DynamicColumnFitWidth` 算法、列数计算全部保持原样），
只在“落盘”这一步按屏拆 key：

| 屏幕 | 实际使用的 key |
|---|---|
| 内屏 | `screen_scale_sp_key_inner` |
| 外屏 | `screen_scale_sp_key_outer` |

并在 `a()` 被调用时检查屏幕是否变了，变了就按当前屏重读一次写回 `ms.n.d`。

### 屏幕判定

不能用 `sw`/`dp` 判定（正是它们被 DPI 抹平才出的问题）。改用**屏幕短边像素**：
外屏 1168、内屏 1672，与 DPI 取值完全无关。分界值优先从 `DisplayManager`
动态算出（换机型自适应），算不出时退回本机实测的中点。

### 两个实测踩出来的坑

| # | 坑 | 后果 |
|---|---|---|
| 1 | `chain.getArgs()` 返回**不可变列表**，原地 `set()` 抛 `UnsupportedOperationException` | key 换不掉，档位全写回旧 key，且每次调用刷异常 |
| 2 | `chain.proceedWith()` 要求 `thisObject` **精确等于声明类** `q3e.h`，实际实例是 MMKV 包装的子类 | 抛 `IllegalArgumentException` → `ms.n.<clinit>` 失败 → **小红书启动即闪退** |

修法：第 1 点复制列表后再改；第 2 点改为**反射在原实例上调用**，并加 `ThreadLocal` 重入保护。

### 效果

| 场景 | 结果 |
|---|---|
| 外屏调到 2 列 → 展开内屏调到 4 列 → 合回外屏 | 外屏保持 2 列，内屏保持 4 列 |

### 日志

```bash
logcat -d | grep XHSFullscreen
# hooked SP read j(String,int)                      读取拦截就位
# hooked SP write u(String,int)                     写入拦截就位
# hooked ms.n.a() for per-screen scale refresh      切屏重载就位
# switch screen -> reload scale, tag=outer value=2  切到外屏，恢复档位 2
```

## 构建

### 方式一:本地 dexswap(推荐,无需联网/无需 Gradle)

```bash
./build_dexswap.sh          # 产物 build_local/XHSFullscreen.apk
```

原理:资源/清单极少变动,复用 `base/XHSFullscreen-base.apk` 的资源部分,
只重新编译 `classes.dex` 并替换。与本机容器环境兼容
(SDK 自带的 aapt2 是 Android 目标二进制,proot 内无法执行;
Debian 版 aapt2 2.19 解析不了 SDK 36 的 `resources.arsc`)。

工具链(本机已就位):

| 工具 | 路径 |
|---|---|
| JDK 17 | `/usr/lib/jvm/java-17-openjdk-arm64` |
| android.jar | `/root/LSP&KSU/work/sdk/platforms/android-36/android.jar` |
| d8 | `/root/LSP&KSU/work/tools/r8.jar` |
| libxposed api | `app/libs/libxposed-api-102.jar` |

### 方式二:GitHub Actions

推送到 `main` 即触发,产物在 Actions 的 `XHSFullscreen-apk`。

### ⚠️ APK 必须按 4 字节对齐(v1.7.1 修复)

Android R+ (targetSdk >= 30) 在安装阶段硬性要求:
`resources.arsc` 必须**不压缩**,且数据起点**按 4 字节对齐**。否则安装直接失败:

```
Install Failure 1#-124 [-124: Failed parse during installPackageLI:
Targeting R+ (version 30 and above) requires the resources.arsc of installed
APKs to be stored uncompressed and aligned on a 4-byte boundary]
```

v1.7.0 及更早的 **Release 附件**就是这个缺陷 —— 手机上装不上。

| 产物来源 | 对齐 | 说明 |
|---|---|---|
| GitHub Actions (Gradle/AGP) | ✅ | AGP 自动对齐,没问题 |
| `build_dexswap.sh` (v1.7.0 及更早) | ❌ | `zipfile.writestr` 整包重写,不做任何填充 |
| `build_dexswap.sh` (v1.7.1 起) | ✅ | 自己写 ZIP 结构,逐条用 extra field 补齐 |

**两个关键点**(v1.7.1 踩出来的):

1. **打包**必须自己拼 ZIP 结构。`zipfile.writestr` 不会做对齐填充;
   改为逐条写本地文件头/中央目录,用 alignment padding extra field (id `0xd935`)
   把每个条目的数据起点补到 4 的倍数。
2. **签名必须用 `apksigner`(v2/v3)**,不能用 `jarsigner`。
   jarsigner 走 v1 方案,会把 `MANIFEST.MF` 塞回包里**重新写一遍 zip**,
   对齐当场丢失(实测 `resources.arsc` 从 `mod4=0` 变回 `mod1`)。
   apksigner 只在文件末尾追加签名块,原 zip 结构原样保留。

脚本末尾的校验段会直接读原始字节复核 `resources.arsc` 的压缩方式与对齐,
不通过就直接 `exit 1`,不会产出坏包。

### ⚠️ dexswap 产物必须自己把版本号写进清单(v1.7.1 修复)

dexswap 方案**复用 BASE APK 的资源与清单**,只替换 `classes.dex`。
所以 `app/build.gradle` 里的 `versionCode` / `versionName` **根本不会进入产物** ——
清单永远是 BASE 那一份。v1.7.1 第一次发布就栽在这里:包已经装上了,
`versionName` 还显示 `1.7`。

v1.7.1 起,`build_dexswap.sh` 直接从 `app/build.gradle` 读取版本号,
改写 BASE 的**二进制** `AndroidManifest.xml`:

- `versionCode` 是属性区的整数立即数,原地改 4 字节;
- `versionName` 是字符串池里的条目,改长度通常不同,必须**重排整个字符串池**
  并平移其后的所有块。

**两个必须知道的细节**:

1. 字符串池里条目之间**只有 2 字节间隙**。`"1.7"` → `"1.7.1"` 需要多 4 字节,
   原地改写会覆盖下一项的前 2 字节 → 清单解析失败,
   装包报 `INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION`。
2. 二进制 XML 的 UTF-16 串编码是
   **`u16 长度` + 内容 + `u16 NUL 终止符`**(宽度为奇数时再补一个 NUL)。
   少了终止符,apksig 与 PackageManager 都会直接报
   `UTF-16 encoded form of string not NULL terminated`。

脚本会在写出前做结构自检(块链首尾相接、文件头长度、条目不重叠),
并且 `[4/4]` 之后还会从成品 APK 里回读版本号,对不上就 `exit 1`。

## 安装

### ⚠️ 用 `cp` 覆盖,不要反复 `pm install`

LSPosed 把模块 APK 的**绝对路径**登记在 `/data/adb/lspd/config/modules_config.db` 的
`apk_path` 字段,**首次收录后永不自动更新**。而 `pm install` **每次都会分配新的 codePath**,
导致 `apk_path` 指向失效路径 —— 表现为「模块看着已启用,但代码根本没加载」,
且**日志里一片空白**,极难排查。

正确做法(路径不变,只需重启目标 App):

```bash
APK=$(pm path com.abel.xhsfullscreen | sed 's/package://')
cp -f 新版本.apk "$APK" && chmod 644 "$APK"
am force-stop com.xingin.xhs    # 唯一必需的生效动作
```

如果已经 `pm install` 导致路径漂移,改数据库即可(**只 cp 数据库文件,不要停 lspd**):

```python
import sqlite3
REAL = "/data/app/.../base.apk"   # pm path 的真实值
db = sqlite3.connect("modules_config.db")
db.execute("UPDATE modules SET apk_path=? WHERE module_pkg_name='com.abel.xhsfullscreen'", (REAL,))
db.commit()
```

排查是否踩坑,比对三者是否一致:

```bash
pm path com.abel.xhsfullscreen                           # 真实路径
grep -ao "/data/app/[^/]*/com.abel.xhsfullscreen[^/]*/base.apk" \
     /data/adb/lspd/config/modules_config.db             # 登记路径
```

### 激活

1. 在 **LSPosed Manager → 模块** 中启用 **XHS Fullscreen**。
2. 在该模块的**作用域**中勾选 **小红书 (com.xingin.xhs)**。
3. 强制停止小红书后打开任意笔记即可。

## 诊断

模块会打印两条常规日志,用于区分「没加载」和「加载了但没命中」——
这两种情况的排查方向完全不同:

```
I XHSFullscreen: onPackageLoaded: com.xingin.xhs     ← 模块确实被注入
I XHSFullscreen: hooked into XHS, installing hooks   ← 进入了小红书分支
I XHSFullscreen: guard on: ...CommentListView         ← 命中目标容器
```

```bash
logcat -d | grep XHSFullscreen
```

## 说明

- 测试环境:折叠屏、Android 17、KSU + Zygisk + LSPosed 2.2.1 (API 102)、小红书 9.50.0。
- 边距宽度 `PAD_TARGET`(px)与阈值 `PAD_THRESHOLD`(低于此值的对称内边距不处理)可在
  `MainHook.java` 顶部调整。
