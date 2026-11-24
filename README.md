# XHSFullscreen

LSPosed 模块,让**小红书 (com.xingin.xhs)** 在折叠屏内屏的笔记**详情页**(图文 / 视频)铺满左右,而**不改变系统 density**,也**不破坏一级信息流的多列排版**。

> **v1.2 新增:内外屏「排数档位」独立记忆** —— 内屏和外屏各自记自己的手势缩放档位,
> 互不影响。详见下方「内外屏排数档位独立记忆」一节。

> **v2.0.1 新增:详情页图片按比例自适应放大** —— 图片区不再被小红书写死的宽高卡住,
> 按图片原始宽高比把图放大到「一屏放得下、四周留 22px」的最大尺寸。详见下方
> 「详情页图片自适应(v2.0)」。
>
> **v2.0.1 修复:小红书 9.55.0 起内外屏档位独立记忆失效** —— 存储类混淆名从
> `s3e.h` 换成 `p7e.h`,且 Context 字段从实例字段变成**静态**字段;另外模块的
> dex 遍历遇到短条目会抛 `StringIndexOutOfBoundsException`,把整轮扫描打断。
> 三处都修了,详见「9.55.0 适配」。
>
> **⚠️ 升级方式变了**:v2 起必须 `pm install -r` 覆盖安装(不要再用 `cp` 覆盖
> `base.apk`),然后重启目标 App。原因见「安装」一节。

> **v2.1.1 修复:内外屏列数强制同步** —— 换屏后小红书**根本不会重算 spanCount**
> (列表继续用上一个屏算出来的列数),而且模块的 `hookActivityLifecycle()` 只定义、
> 从没被调用过,换屏时拿不到新屏尺寸。两处都修了,详见「列数为什么会同步」。
>
> **v2.1.1 修复:详情页图片顶出下边缘、标题被切** —— 可用高度里补上了标题块的预留
> (`title`, 默认 150px)。
>
> **v2.1.6 修复:切屏/旋转闪一下 + 横屏列数被锁** —— 三条:
> ①列数改成**换屏时同步改**(原来延迟 400ms,新屏先按旧列数画一帧再跳,肉眼就是闪),
> 改之前临时摘掉 `ItemAnimator`,布局稳定再装回;
> ②横竖屏判定改用 `Configuration`(Activity 的 `DisplayMetrics` 在
> `onConfigurationChanged` 里会滞后一帧,把横屏当竖屏 → 横屏被锁成竖屏的列数);
> ③**横屏完全不干预**:列数、档位 key 全交还给小红书自己,并显式把静态档位 `d`
> 还原成 plain key 的值(否则第一次转进横屏会拿着上一个屏的档位算列数)。
> 详见「横竖屏与按屏记忆」一节。

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

## 详情页图片自适应(v2.0)

### 症状

小红书 9.55 起,图文/视频详情页的图片比之前小了一大圈(外屏 1168 宽的屏上,
图片卡片只有 752 宽,左右各空出 208px)。

### 根因(视图树取证,外屏 1168×1712)

```
AsyncImageDisplayView  w=1168 h=1012                     ← 满宽,没问题
  ImageGalleryView     w=759  h=1012  lp=759x1012        ← ★ 宽度被写死成 759
    MatrixHorizontalRecyclerView / AsyncPhotoNoteItemView 也都是 759
```

不是 padding 问题(那里已经是 0),而是小红书**先把图片区高度定成上限 1012,
再用图片宽高比 759/1012=0.75 反推出宽度**。高度上限一变,宽度跟着缩水 ——
这就是「图片变小」的机制。

### 做法

不去猜它的上限,直接按「一屏放得下、四周留一点边距」重算:

```
可用宽 = 展示区宽 - 2 × margin
可用高 = (滚动视口底边 - 图片当前 top) - bottom - 2 × margin
宽高比 = 沿用小红书自己算的 lp.width / lp.height
先按满宽算高;高超过可用高就按可用高回算宽
```

- `margin` 默认 22px(约 8dp),`bottom` 默认 110px —— 底部评论输入条是**浮在内容上**
  的,滚动视口一直到屏幕最底,不让出这 110px 竖图就会钻到输入条下面(这就是
  v2.0.0 「有一部分图片超过全屏」的原因)。
- 结果缓存在 View 的 tag 上:**滚动时不重算**(否则 top 一变图就跟着伸缩),
  只在「图片比例变化 / 换屏」时重算。
- 方图会顶到满宽(1168 → 内容 1124),竖图受高度限制(width 由比值回算),
  横图铺满宽度。都不会超出一屏。

### 运行期调参(不用重编、不用重装)

```bash
# /data/local/tmp/xhsfullscreen.conf, key=value 一行一条
fill=1        # 1=启用图片自适应, 0=关闭
tree=0        # 1=打印详情页视图树(定位用)
pad=45        # 内容区左右内边距(px)
margin=22     # 图片四周留白(px)
bottom=110    # 底部让给评论输入条的高度(px)

am force-stop com.xingin.xhs   # 改完重启目标 App 即生效
```

### 日志

```bash
logcat -d | grep XHSFullscreen | grep FIT
# FIT gallery -> 1124x1124 ratio=1.0 avail=1124x1420
```

---

## 列数为什么会同步(v2.1.1)

### 链路(9.55.0 字节码)

```
f(Context)                              ← 真正的 spanCount
  ├ b(静态布尔:是否平板/横向折叠) 为真才往下走
  ├ c(Lazy<Boolean>) 为真 → e(屏宽px) 按 280/480/680/960dp 分档
  └ 再用 a() 读到的缩放档位在该档里选列数
m(I,F)  →  d = g(...);  h.u("screen_scale_sp_key", d)     单一个 key
a()     →  if (b) return d; else return NORMAL             只在 b 为真时看 d
```

配置文件里的实测映射(外屏 1168px = SMALL 档):`scale=2 → 2 列`、`scale=1 → 3 列`、
`scale=0 → 4 列`。

### 两个真因

1. **换屏后小红书不重算**:切屏时 `f(Context)` 根本不会被再调一次,RecyclerView 的
   GridLayoutManager 继续用上一个屏的 spanCount —— 现象就是「两块屏列数强制同步」。
   日志证据:内屏期间完全没有 `SPAN screen=inner` 行。
2. **模块没接上换屏时机**:`hookActivityLifecycle()` 只定义、从来没被调用,
   `onResume`/`onConfigurationChanged` 的 hook 全没装上,`refreshScreenShort()`
   也就没跑过;而 `Application`/`Resources.getSystem()` 的 DisplayMetrics 在折叠换屏后
   不一定刷新,内屏会被判成 `outer`,于是两边读写同一个 key。

### 修法

- `onPackageLoaded` 里真正调用 `hookActivityLifecycle()`;
  `onResume` / `onConfigurationChanged` 里用 **Activity 自己的 DisplayMetrics**
  记录当前屏短边(`refreshScreenShort`);
- 换屏时(`onConfigurationChanged` 与 `a()` 里 tag 变化两处)调用 `applyFeedSpan()`:
  用小红书自己的 `ms.n.e(当前屏宽px)` 算出「这一屏应该几列」,再对记录下来的
  GridLayoutManager / StaggeredGridLayoutManager 调 `setSpanCount()`,延迟 400ms
  等新窗口布局完成后再改。
- 列数的映射完全不自己编,一律借 `e(int)`。

日志:

```bash
logcat -d | grep XHSFullscreen | grep -E "screen state|screenTag|SPAN"
# screen short side -> 1168 (1168x1712)
# screenTag -> outer shortSide=1168 divider=1420
# switch screen -> reload scale, tag=outer value=2
# SPAN screen=outer scale=2 spanCount=2
# SPAN forced 1 list(s) -> 4 (onConfigurationChanged)
```

---

## 9.55.0 适配(内外屏档位独立记忆修复)

小红书 9.55.0 换了一批混淆名,原来的实现有三处会失效:

| # | 变化 | 后果 | 修法 |
|---|---|---|---|
| 1 | 存储封装类 `s3e.h` → `p7e.h` | 按名字快查全部落空,走特征扫描 | 提示表加 `p7e.h` |
| 2 | dex 遍历里 `name.substring(1, len-1)` 遇到长度 1 的条目抛 `StringIndexOutOfBoundsException: begin 1, end 0` | 异常在循环里没兜住,**整轮扫描被打断**,特征扫描永远找不到类 | 短条目跳过 + 单条目异常不外溢 |
| 3 | Context 字段 `e` 从**实例**字段变成 **`static`** 字段 | 静态字段取值时传实例会抛 `IllegalArgumentException` → 分界值恒为 0 → 内外屏被判成同一块 | 按 `Modifier.isStatic` 决定 target,并加了 ActivityThread 兜底 |

另外 `screenTag()` 改成优先用**当前进程 Resources** 的 DisplayMetrics
(`Resources.getSystem()` 在部分 ROM 上不随换屏刷新),分界值仍按两块屏的短边动态算。

---

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

### ✅ 正确姿势:`pm install -r` 覆盖安装 + 重启目标 App

```bash
cp 新版本.apk /data/local/tmp/xhs.apk
pm install -r -d /data/local/tmp/xhs.apk
dumpsys package com.abel.xhsfullscreen | grep versionName   # 确认版本真的变了
am force-stop com.xingin.xhs                                 # 唯一必需的生效动作
```

要点:

- **必须确认版本号真的变了**。`pm install` 成功后 `PM` 立刻更新版本号;
  如果还是旧版本,说明装的是旧包,别再往下试。
- **重启目标 App 就够**,不用重启手机。模块代码在目标进程启动时加载。
- `pm install` 会让 codePath 变成新的,但这不影响使用 —— lspd 会用
  PackageManager 里的当前路径;旧路径失效只是历史遗留。

### ❌ 不要再 `cp -f` 覆盖 `base.apk`

v1.7 及更早的文档写的是「`cp` 覆盖 base.apk + force-stop」,这在 **9.55.0 上不生效**:
`cp` 换了文件内容,但 PackageManager 的登记、ART 的 dex/vdex 产物仍是旧的,
新进程拿到的还是旧代码 —— 现象是「改了代码、重启目标 App、日志里连新版本号都不出现」。
实测:覆盖后删掉 `oat/arm64/base.{odex,vdex}` 也没用,只有 `pm install -r` 才真正刷新。

排查是否加载了新代码,看这行日志里的版本串:

```bash
logcat -d | grep XHSFullscreen | grep "hooked into XHS"
# hooked into XHS, installing hooks (v2.0.1)   ← 版本串对不上就是旧代码
```

### 参考:LSPosed 登记的 apk_path 与 pm path 不一致时

```bash
pm path com.abel.xhsfullscreen                           # 真实路径
grep -ao "/data/app/[^/]*com.abel.xhsfullscreen[^/]*base.apk" \
     /data/adb/lspd/config/modules_config.db             # 登记路径
```

两者不一致且模块确实加载失败时,改数据库(**只 cp 数据库文件,不要停 lspd**)。

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

## 横竖屏与按屏记忆(v2.1.6)

用户要的行为:**竖屏时两块屏各自记忆列数,横屏不受模块影响、跟随小红书默认。**

### 三个坑

1. **延迟改列数会闪**。v2.1.1 是 `postDelayed(400)`,新屏会先按旧列数画一帧,
   再"啪"一下换成正确列数。改成在 `onConfigurationChanged` 里**同步**改
   (此时新窗口还没布局),第一帧就是对的;再补一次 150ms 后的**幂等**复检
   (值已经对了就什么都不做)。改之前顺手把 `ItemAnimator` 摘掉(250ms 后装回),
   否则列表重排会带条目动画,看起来还是"闪"。
2. **`DisplayMetrics` 在换屏/旋转的那一帧是滞后的**。实测 `onConfigurationChanged`
   里读 Activity 的 metrics,横屏仍报竖屏尺寸,于是 `isPortrait()` 判成竖屏,
   横屏被强制成竖屏的列数(用户看到"横屏也是 3 列")。
   现在屏状态(`sScreenShort` / `sScreenWidth` / `sPortrait`)统一由
   `updateFromConfig(Configuration)` 更新:`screenWidthDp`/`screenHeightDp`
   已按当前方向给值,`densityDpi` 换算 px 与 DPI 设置无关,任何时机都可信。
3. **横屏"不干预"还不够**。列数是 `e(屏宽px)` 配 `a()` 的档位算的,而档位存在
   静态字段 `d` 里 —— 展开进横屏时 `d` 还留着上一个屏的值,于是横屏拿外屏的档位算列数
   (实测 3 列),转两次才对。现在横屏分支会先把 `d` 还原成 plain key 的值
   (`readScalePlain`),结果与来源屏无关;用户横屏捏合写的也是 plain key,所以一致。

### 现在的规则

| 方向 | 档位来源 | 列数由谁定 |
| --- | --- | --- |
| 竖屏 | `screen_scale_sp_key_inner` / `_outer`(各记各的) | 模块按该屏 `e(宽px)` 同步 `setSpanCount` |
| 横屏 | `screen_scale_sp_key`(小红书原值) | 小红书自己 `f()` 算,模块完全不碰 |

日志里能直接看出走了哪条:

```
screen state -> short=1671 width=2365 landscape (cfg 1081x764 @350)   ← 横屏,放行
SPAN screen=inner scale=1 spanCount=4
screen state -> short=1168 width=1168 portrait (cfg 534x783 @350)     ← 竖屏,接管
SPAN forced 4 list(s) -> 2 (onConfigurationChanged, sync)
```
