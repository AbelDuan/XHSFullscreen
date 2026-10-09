# XHSFullscreen

LSPosed 模块,让**小红书 (com.xingin.xhs)** 在折叠屏内屏的笔记**详情页**(图文 / 视频)铺满左右,而**不改变系统 density**,也**不破坏一级信息流的多列排版**。

## 问题原因

在自定义密度(如 density=345 + 字体 125%)的折叠屏上,小红书笔记详情页左右留有大片空白。
根因**不是** `maxWidth` 限宽容器——整棵内容树本来就是满宽的。真正原因是小红书给
评论区(`CommentListView`)和图文区(`AsyncImageInfoView`)加了一个**很大的左右对称内边距**
(内屏约 189px),并且在**评论异步加载完成后还会重新设置**这个内边距。

## 模块做了什么

- 把评论区 / 图文区的左右对称内边距从约 189px **收到 45px**(`PAD_TARGET` 可改)。
- Hook 这些容器**真实类**(含其重写的)`setPadding` / `setPaddingRelative`,并在每次布局时
  再次纠正,因此评论异步加载后重新设置的内边距也会被拦下来。
- 一级信息流、顶部栏、底部栏、视频评论区均不受影响,多列排版与正常布局保持不变。
- **不会**修改系统 density、`smallestScreenWidthDp` 或任何全局设置。

## 构建

需要 Android SDK(在 `local.properties` 里设置 `sdk.dir`)与 JDK 17。

```
gradle assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装与激活

1. 安装 APK。
2. 在 **LSPosed Manager → 模块** 中启用 **XHS Fullscreen**。
3. 在该模块的**作用域**中勾选 **小红书 (com.xingin.xhs)**。
4. 重启(或强制停止小红书)后打开任意笔记即可。

## 说明

- 测试环境:折叠屏、Android 15/16、KSU + Zygisk + LSPosed。
- 边距宽度 `PAD_TARGET`(px)与阈值 `PAD_THRESHOLD`(低于此值的对称内边距不处理)可在
  `MainHook.java` 顶部调整。
