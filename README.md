# XHSFullscreen

LSPosed 模块,让**小红书 (com.xingin.xhs)** 在折叠屏内屏的笔记**详情页**(图文 / 视频)铺满左右,而**不改变系统 density**,也**不破坏一级信息流的多列排版**。

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
