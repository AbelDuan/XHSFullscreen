package com.abel.xhsfullscreen;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * XHS Fullscreen — LSPosed module.
 *
 * Forces 小红书 (com.xingin.xhs) to fill the foldable inner screen on the
 * note DETAIL page (image/text AND video), WITHOUT changing the system
 * density (kept at 345 + 125% by the user) and WITHOUT breaking the
 * multi-column feed on the first level.
 *
 * Root cause of the left/right blank on the detail page: XHS gives the
 * comment area (CommentListView) and the image/text area (AsyncImageInfoView)
 * a large symmetric L/R padding (189px on the foldable inner screen) and
 * RE-SETS it after the comment list loads asynchronously. We clamp that
 * symmetric gutter down to PAD_TARGET (45px) by:
 *   1. hooking the real (possibly overridden) setPadding / setPaddingRelative
 *      of those containers, and
 *   2. re-clamping the targeted containers on resume / config change.
 *
 * ---------------------------------------------------------------------------
 * v2 — fixes the "opens narrow, then snaps to the configured width" flicker.
 * ---------------------------------------------------------------------------
 * v1 additionally walked the WHOLE content tree (depth <= 6) and, for every
 * view whose LayoutParams width was MATCH_PARENT, compared its *measured* width
 * against 98% of the screen width. During the post()/postDelayed() passes that
 * measured value is still a not-yet-final intermediate for many children, so
 * those views were misclassified as "artificially narrowed" and got a
 * requestLayout() + setLayoutParams() forced onto them. Several of those in a
 * row produce a measurement storm: the first frame renders at the narrow
 * intermediate width and only settles (visibly "snapping") once the extra
 * layout passes finish.
 *
 * 真正的抖动主因在 v3 才确认：小红书每隔约 1.5 秒把 padding 重设为 277/189，
 * 而 v1/v2 都在布局「之后」（OnGlobalLayout）才改回，那一帧的窄版已经画上屏了。
 * v3 改为 OnPreDraw（绘制之前）归一化，窄版永远不会被绘制出来。
 *
 * The same pass also clamped symmetric L/R *margins* and reflected into a
 * "maxWidth" field on arbitrary LayoutParams objects, which silently fails on
 * most subclasses. All of that is gone in v2:
 *
 *   - Only the explicitly targeted classes are ever touched.
 *   - Every clamp is idempotent: if the value is already correct we do not
 *     write it and do not requestLayout(), so no layout ping-pong.
 *   - The global-layout listener removes itself once the clamp has settled.
 *   - The ImageView/timer-driven depth walk and the maxWidth reflection are
 *     removed entirely.
 *
 * ---------------------------------------------------------------------------
 * v4 — 内外屏「信息流排数档位」独立记忆
 * ---------------------------------------------------------------------------
 * 小红书的手势缩放档位只存在一个 key 里：
 *     "screen_scale_sp_key" -> ms.n$c { ZOOM_IN=0, NORMAL=1, ZOOM_OUT=2 }
 * 且只在静态字段 ms.n.d 缓存一次：
 *     <clinit> : d = h.j("screen_scale_sp_key", NORMAL)
 *     a()      : return d
 *     m(i, f)  : d = g(i, f); h.u("screen_scale_sp_key", d)
 * 档位本身不分屏，所以内屏调好的排数会被带到外屏。
 *
 * 本模块不改动它的档位体系（枚举、DynamicColumnFitWidth 算法、列数计算全部原样），
 * 只在落盘这一步按屏区分 key：screen_scale_sp_key_inner / _outer，
 * 并在取档位时（a()）发现屏幕变了就按当前屏重读一次写回 d。
 *
 * 两个必须同时满足的实现细节（都是实测踩出来的坑）：
 *   1. chain.getArgs() 返回不可变列表，不能原地 set()，否则抛
 *      UnsupportedOperationException —— key 换不掉，且每次调用都刷异常。
 *   2. 不能用 chain.proceedWith()：它要求 thisObject 精确等于声明类 q3e.h，
 *      而实际实例是 MMKV 包装的子类，会抛 IllegalArgumentException，
 *      连带 ms.n.<clinit> 失败 → 小红书启动即闪退。改为反射在原实例上调用。
 */

public class MainHook extends XposedModule {

    private static final String TAG = "XHSFullscreen";
    private static final String XHS = "com.xingin.xhs";
    private static final int MAX_DEPTH = 6;

    // Symmetric L/R padding at/above this (px) is treated as the detail-page
    // content inset and reduced to PAD_TARGET. 189px was observed on the
    // foldable inner screen; 64px leaves normal small paddings intact.
    private static final int PAD_THRESHOLD = 64;
    // Keep this much left/right gutter (px) instead of clearing to 0.
    private static final int PAD_TARGET = 45;

    // PreDraw 守卫连续观察到的稳定帧数；达到后自行摘除监听。
    // 稳定后每帧的回调只剩开销没有收益，摘掉可省掉持续的对象分配与检查。
    // 取 30 帧（约 0.25~0.5 秒），足够覆盖小红书异步重设边距的窗口。

    // Real (possibly overridden) classes whose setPadding we have already
    // hooked. Keyed by class name; bounded and never reset.
    private static final Set<String> hookedClasses = new HashSet<>();

    // 标记「这个 View 已挂过 PreDraw 守卫」，避免重复挂载导致监听器堆积。
    // View 的 tag 只有 int 键版本（setTag(int,Object) / getTag(int) 自 API 21 起）。
    // 用一个大到不会与 XHS 冲突的冷门 key。
    private static final int TAG_GUARD = 0x58_48_53_47; // 'XHSG'

    // 开启后打印混淆类的方法签名。定位阶段用，定稿后置 false 以免刷日志。
    private static final boolean PROBE = false;

    // 目标容器的全限定类名（来自运行时日志取证，名字稳定、不随混淆变化）。
    // 进程启动时就按这些名字预注册 setter hook，避免首帧漏网。
    private static final String[] TARGET_CLASSES = {
            "com.xingin.comment.consumer.list.CommentListView",
            "com.xingin.matrix.notedetail.imagecontent.imageinfo.AsyncImageInfoView",
            "com.xingin.matrix.notedetail.notecontent.NoteDetailContentView",
            // 图片展示区（图片画廊的父容器）。9.55 起图片卡片的宽高由它的子类
            // ImageGalleryView 用显式像素 LayoutParams 定死，这才是「图片变小」的源头。
            "com.xingin.matrix.notedetail.imagecontent.imagedisplay.AsyncImageDisplayView",
    };

    /** 图片画廊的类名特征（宽度被写死的就是它）。 */
    private static final String GALLERY_CLASS_HINT = "ImageGalleryView";

    /** 画廊类的完整类名（dex 里查到的）。 */
    private static final String GALLERY_FQN =
            "com.xingin.matrix.notedetail.imagecontent.imagegallery.ImageGalleryView";

    /** 图片展示区（画廊的容器，它的 onMeasure 是我们「测量前定尺寸」的入口）。 */
    private static final String DISPLAY_CLASS =
            "com.xingin.matrix.notedetail.imagecontent.imagedisplay.AsyncImageDisplayView";

    /**
     * 调试日志总开关（正式版 = false）。
     *
     * 所有 Log.INFO 都包在 `if (sDebug)` 里：关掉时既不写 logcat，也不拼接
     * 消息字符串 —— 热点路径（PreDraw、setter 拦截、换屏回调）上一次判断的代价。
     * 需要排查时不用重编：`echo debug=1 >> /data/local/tmp/xhsfullscreen.conf`
     * 再 am force-stop com.xingin.xhs 重开即可。
     * Log.WARN / Log.ERROR 不走这个开关 —— 它们只在真的出问题时出现。
     */
    private static volatile boolean sDebug = false;

    // 逐帧观测开关：记录每帧绘制时 padding 的真实值，用于验证
    // 「打开帖子前边距就已定好」。正式版恒为 false（编译期常量，整块不生成字节码）。
    private static final boolean VERBOSE = false;

    // 视图层级诊断开关：把详情页整棵树的 类名/宽高/内边距/LayoutParams 打进
    // logcat，用来定位「这一版图片为什么变小、到底窄在哪一层」。
    // 只在定位阶段打开（打开后每 4 秒最多 dump 一次，单次不超过 DUMP_MAX_LINES 行）。
    private static final boolean DEBUG_TREE = false;
    private static final int DUMP_MAX_LINES = 400;

    // ==================== 运行期开关（免重编调查参数） ====================
    //
    // LSPosed 在开机时把模块 dex 读进内存，之后换 APK 必须软重启才会生效。
    // 为了不因为「调 30px 还是 22px 边距」这种小事反复重启，这几个参数改成
    // 从配置文件读：改文件 → am force-stop com.xingin.xhs → 重开即生效。
    //
    //   debug=1       打开调试日志（默认关，正式版零 logcat 输出）
    //   tree=1        打印详情页视图树（定位用，默认关）
    //   fill=1        把详情页图片区强制撑到满宽（本次新增功能）
    //   pad=45        内容区左右内边距（px）
    //   margin=22     图片区左右留白（px），fill 生效时的目标边距
    //
    // 文件不存在时用下面的默认值，与硬编码行为一致。
    private static final String CONF_PATH = "/data/local/tmp/xhsfullscreen.conf";

    private static volatile boolean sTree = DEBUG_TREE;
    private static volatile boolean sFill = true;
    private static volatile int sMargin = 22;
    private static volatile int sPad = PAD_TARGET;
    // 底部预留：评论输入条是「浮在内容上」的（不是布局的一部分），
    // 所以滚动视口一直延伸到屏幕最底。图片要「不出屏」，就得把这条的高度让出来。
    private static volatile int sBottom = 110;
    // 标题预留：图片下方是标题/正文块（在 CommentListView 里），
    // 想「图片看全 + 标题也看全」，就得把标题那几行的高度一起让出来。
    private static volatile int sTitle = 150;
    // 换屏后强制把信息流列数改成「当前屏应有的列数」。
    // 小红书自己不会在折叠换屏后重算 spanCount（列表继续用旧值），
    // 这就是「内外屏列数强制同步」的真正原因。
    private static volatile boolean sSpanForce = true;

    // ===== 按「屏 + 方向」生效的四个组合 =====
    //
    // 组合名: inner_portrait / inner_landscape / outer_portrait / outer_landscape
    // 任何参数都可以只对某一个组合单独覆盖,写法是 `键_组合名`,例如:
    //     title=150                     ← 所有组合的默认值
    //     title_outer_portrait=110      ← 只改外屏竖屏
    //     fill_inner_landscape=0        ← 内屏横屏不做图片自适应
    // 缺省:fill 只有竖屏开(横屏交还小红书),其余参数四个组合共用。
    private static java.util.Properties sConf = new java.util.Properties();

    // 当前组合实际生效的值(换屏/换向时重算一次,热路径只读字段)
    private static volatile int fPad = PAD_TARGET;
    private static volatile int fMargin = 22;
    private static volatile int fBottom = 110;
    private static volatile int fTitle = 150;
    private static volatile boolean fFill = true;

    /**
     * 是否允许把小红书原本较小的图片放大。
     *
     * 默认 **false**：默认只做「收」——图片超出屏幕时才缩回屏幕内，本来就放得下的
     * 一律不动。原因是小红书会在打开后把尺寸改回它自己的值（宽度上限 1086px），
     * 我们一放大就必然出现一次可见的尺寸变化，而放大本身属于「锦上添花」。
     * 想恢复放大：conf 里写 `enlarge=1`（可只对某个组合，如 enlarge_outer_portrait=1）。
     */
    private static volatile boolean fEnlarge = true;

    /**
     * 详情页图片尺寸适配总开关。
     *
     * 默认 **false**：v2.6.0 起默认完全不碰图片尺寸。原因是取证已经确认小红书在
     * 图片打开后会把 LayoutParams 改回它自己的值（宽度上限 1086px），而它改写既不走
     * View.setLayoutParams（全链路探针零命中）也不走 View.requestLayout（零命中），
     * 显示层抢不回来，所以「放大」必然留下一次可见的尺寸变化。
     *
     * 关掉之后模块对图片尺寸是**完全不参与**：不写 lp、不跳过绘制帧，与没有这个模块
     * 一样。需要放大时 conf 写 `fit=1`（可再配 `enlarge=1`）。
     */
    private static volatile boolean fFit = true;
    private static volatile boolean fSpanForce = true;

    private static String comboKey() {
        boolean inner = sDivider > 0 ? sScreenShort >= sDivider : sScreenShort >= 1400;
        return (inner ? "inner_" : "outer_") + (sPortrait ? "portrait" : "landscape");
    }

    /** 先找 `键_组合名`,没有再退回 `键`,最后用默认值。 */
    private static String confValue(String key, String def) {
        String combo = key;
        try {
            combo = key + "_" + comboKey();
        } catch (Throwable ignored) {
        }
        String v = sConf.getProperty(combo);
        if (v == null) {
            v = sConf.getProperty(key);
        }
        return v == null ? def : v;
    }

    private static int confInt(String key, int def) {
        try {
            String v = confValue(key, null);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean confBool(String key, boolean def) {
        String v = confValue(key, null);
        if (v == null) {
            return def;
        }
        v = v.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes");
    }

    /** 换屏/换向或重读配置后,把当前组合的参数算好存到字段里。 */
    private static void recomputeCombo() {
        fPad = confInt("pad", PAD_TARGET);
        fMargin = confInt("margin", 22);
        fBottom = confInt("bottom", 110);
        fTitle = confInt("title", 150);
        // 横屏默认不接管图片尺寸(交还小红书):fill 只在竖屏默认开
        fFill = confBool("fill", sPortrait);
        fEnlarge = confBool("enlarge", true);
        fFit = confBool("fit", true);
        if (!fFit) {
            // 总开关关掉时把 fill 也压掉：下面所有改写尺寸的分支都以 fFill 为前提，
            // 于是这一版对图片尺寸是完全惰性的。
            fFill = false;
        }
        XposedModule mCfg = sModule;
        if (mCfg != null) {
            mCfg.log(Log.INFO, TAG, "FIT mode: fit=" + fFit + " fill=" + fFill
                    + " enlarge=" + fEnlarge + " (fit=0 表示完全不改图片尺寸)");
        }
        fSpanForce = confBool("spanforce", true);
    }

    // 对照实验开关：true = 只观测不改布局。
    //
    // 2026-10-20 用它做过一次对照，结论（同一短正文帖子）：
    //   模块干预   : AsyncImageInfoView h=0 -> 630，CommentListView top 41 -> 2153
    //   模块不干预 : AsyncImageInfoView h=0 -> 276，CommentListView top 41 -> 1523
    // 两种情况都从 h=0 开始撑开 —— 说明「打开时闪一下」是小红书图片
    // 异步布局的固有行为，并非本模块引入。模块只是把图片放大后，
    // 同样的撑开在像素上更明显。正常使用保持 false。
    private static final boolean OBSERVE_ONLY = false;

    // 静态上下文（PreDraw 监听器）里的日志出口。XposedModule.log 是实例方法，
    // 静态内部类拿不到，这里留一个引用。
    private static XposedModule sModule;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // 入口可观测性：LSPosed 每次把本模块注入目标进程都会经过这里。
        // 没有这条日志就无法区分「模块没被加载」和「加载了但逻辑没命中」——
        // 排查时那是两个完全不同的方向，所以保留为常规 INFO 输出。
        if (sDebug) {
            log(Log.INFO, TAG, "onPackageLoaded: " + param.getPackageName());
        }
        if (!XHS.equals(param.getPackageName())) {
            return;
        }
        if (sDebug) {
            log(Log.INFO, TAG, "hooked into XHS, installing hooks (v1.9.0)");
        }
        sModule = this;
        loadConf();
        // ★ 这个调用以前漏了：hookActivityLifecycle() 只定义、从没被调用过，
        // 所以 onResume/onConfigurationChanged 的 hook 从来没装上，
        // refreshScreenShort / scheduleFix 也就从来没跑过。
        // 折叠换屏时正是靠 onConfigurationChanged 才能拿到「新屏」的 DisplayMetrics。
        hookActivityLifecycle();
        installMeasureFit(param.getDefaultClassLoader());
        installGalleryLpHook(param.getDefaultClassLoader());

        // ★ 主动预注册：不等实例出现，直接用全限定名把目标类的 setter hook 好。
        //
        // 逐帧观测证明：onAttachedToWindow/fixView 都是「实例出现后」才注册，
        // 而 XHS 在视图挂窗口的同时就调 setPadding(277)，那一次必然漏网，
        // 于是首帧仍以 277 绘制一次（FRAME 1 pad=277），肉眼就是「先窄后宽」。
        //
        // 类名是稳定的（不随混淆变化），这里在进程启动阶段就完成注册，
        // 使第一次 setPadding 调用即被改写 —— 277 从头到尾不存在。
        ClassLoader cl0 = null;
        try {
            cl0 = param.getDefaultClassLoader();
        } catch (Throwable ignored) {
        }
        if (cl0 != null) {
            for (String cn : TARGET_CLASSES) {
                try {
                    Class<?> c = Class.forName(cn, false, cl0);
                    synchronized (hookedClasses) {
                        if (!hookedClasses.contains(cn)) {
                            hookedClasses.add(cn);
                            hookPaddingSetters(c);
                            if (sDebug) {
                                log(Log.INFO, TAG, "pre-hooked setters: " + cn);
                            }
                        }
                    }
                } catch (Throwable t) {
                    if (sDebug) {
                        log(Log.INFO, TAG, "pre-hook skipped " + cn + ": " + t);
                    }
                }
            }

            // ★ 从源头改：wj9.c0.y0(boolean,boolean) 是小红书「计算并应用详情页
            // 边距」的核心方法（调用栈取证：wj9.c0.y0:140 -> View.setPadding）。
            // 它在自己的逻辑里算出 277 并立刻应用；我们在它返回后马上把结果
            // 归一为 45 —— 这是同一调用栈内的修正，早于任何测量/布局/绘制，
            // 因此首帧画出来时值就已经是 45，不存在中间态。
            try {
                Class<?> c0 = Class.forName("wj9.c0", false, cl0);
                Method y0 = c0.getDeclaredMethod("y0", boolean.class, boolean.class);
                y0.setAccessible(true);
                hook(y0).intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof View) {
                            normalizeTree((View) self);
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                });
                if (sDebug) {
                    log(Log.INFO, TAG, "hooked wj9.c0.y0 (source-level normalize)");
                }
            } catch (Throwable t) {
                // 这个方法名属于 9.50~9.54 的混淆名，9.55.0 已经不叫这个名字
                // （NoSuchMethodException）。它是「提速」而不是「必需」：
                // 真正保证首帧正确的还是 setter 预注册 + PreDraw 守卫，
                // 所以这里降级为 INFO，不再当错误刷日志。
                if (sDebug) {
                    log(Log.INFO, TAG, "wj9.c0.y0 not present in this version: " + t);
                }
            }
        }

        // ================= 内外屏档位独立记忆（v4） =================
        //
        // 原理（来自 dexdump 取证，classes18.dex）：
        //   小红书的信息流"排数档位"存在 SharedPreferences 里，key 固定为
        //   "screen_scale_sp_key"，值取自枚举 ms.n$c {NORMAL, ZOOM_IN, ZOOM_OUT}，
        //   并被写入静态字段 ms.n.d。
        //     <clinit> : d = h.j("screen_scale_sp_key", NORMAL.ordinal())
        //     m(IF)I   : d = g(i,f); h.u("screen_scale_sp_key", d)
        //   两处都用同一个 key、同一个静态字段 —— 所以内屏设的档位会带到外屏。
        //
        // 修法：只在"落盘"这一步按屏区分。读/写 key 时根据当前屏幕追加后缀，
        // 档位体系本身（枚举、算法 DynamicColumnFitWidth、列数计算）完全不动。
        if (cl0 != null) {
            installSplitScreenScale(cl0);
            installFeedSpanForce(cl0);
        }

        // 注：曾在这里 hook NestedScrollLayout 的滚动偏移 B，想消除「打开时
        // 评论区弹一下」。实测 B 全程为 0、从未被改动 —— 改错了对象，已删除。
        // 同一现象在未安装本模块的手机上同样存在，属小红书自身的加载行为，
        // 模块不再尝试干预位置，只负责宽度。

        // 判定 277 是否来自 XML 布局的初始值：在目标类的构造函数返回时
        // 读一次 padding。构造完成即非 0，说明是布局初始值（Java 层改不到源头），
        // 那就必须把「归位」提前到 attach 之前、甚至构造之后立即执行。
        if (cl0 != null) {
            for (String cn : TARGET_CLASSES) {
                try {
                    Class<?> c = Class.forName(cn, false, cl0);
                    for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                        ctor.setAccessible(true);
                        hook(ctor).intercept(chain -> {
                            Object r = chain.proceed();
                            try {
                                if (r instanceof View) {
                                    View v = (View) r;
                                    int pl = v.getPaddingLeft();
                                    XposedModule m = sModule;
                                    if (m != null) {
                                        if (sDebug) {
                                            m.log(Log.INFO, TAG, "CTOR " + cn
                                                    + " initialPad=" + pl);
                                        }
                                    }
                                    // ⚠ 构造期不写 padding。
                                    // CommentListView 可能是在 RecyclerView 复用 /
                                    // 布局过程中被创建的，此时 setPadding 会触发
                                    // requestLayout，把底部露出一半的评论重排一次 ——
                                    // 就是「刚打开时底部闪一下」。写入交给
                                    // onAttachedToWindow（挂窗口时）与 PreDraw。
                                    ensureGuard(v);
                                }
                            } catch (Throwable ignored) {
                            }
                            return r;
                        });
                    }
                    if (sDebug) {
                        log(Log.INFO, TAG, "hooked ctors: " + cn);
                    }
                } catch (Throwable t) {
                    if (sDebug) {
                        log(Log.INFO, TAG, "ctor hook skipped " + cn + ": " + t);
                    }
                }
            }
        }
    }

    private void hookActivityLifecycle() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                try {
                    Activity act = (Activity) chain.getThisObject();
                    refreshScreenShort(act);
                    scheduleFix(act);
                    applyFeedSpan(act.getClass().getClassLoader(), "onResume", false);
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook onResume failed: " + t);
        }
        try {
            Method onCfg = Activity.class.getDeclaredMethod(
                    "onConfigurationChanged", Configuration.class);
            hook(onCfg).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Activity act = (Activity) chain.getThisObject();
                    Object cfg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (cfg instanceof Configuration) {
                        updateFromConfig((Configuration) cfg);
                    }
                    refreshScreenShort(act);
                    scheduleFix(act);
                    applyFeedSpan(act.getClass().getClassLoader(), "onConfigurationChanged", true);
                } catch (Throwable ignored) {
                }
                return r;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook onConfigurationChanged failed: " + t);
        }
    }

    /**
     * View.onMeasure 上挂着一个 hook，会被每次测量回调 —— 也就是滑动时
     * 每秒上万次。这里用 Class 身份缓存把判断压到一次 HashMap 查表：
     * 第一次见到的 Class 才做字符串匹配，之后直接查 Cache。
     * 未命中缓存的非目标类会被记为 FALSE，不会重复做字符串匹配。
     */
    private static final ClassValue<Boolean> TARGET_CACHE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return isTarget(type.getName()) ? Boolean.TRUE : Boolean.FALSE;
        }
    };

    /** 热路径版本：Class 缓存，无字符串分配。 */
    private static boolean isTargetClass(Class<?> cls) {
        return TARGET_CACHE.get(cls);
    }

    private static boolean isTarget(String cn) {
        return cn.contains("CommentListView")
                || cn.contains("AsyncImageInfoView")
                || cn.contains("NoteDetailContentView")
                || cn.contains("AsyncImageDisplayView");
    }

    /**
     * 需要「收窄内边距」的容器。
     *
     * 关键区分（实测宽度关系得出）：
     *   NoteDetailContentView  w=1672  ← 满宽「外层容器」，它自己不该有内边距，
     *                                     否则会把子视图挤窄 90px（45*2）
     *   CommentListView        w=1582  ← 真正需要 45px 边距的内容区
     *   AsyncImageInfoView     w=1582  ← 同上
     * 早先对三者一律设 45，导致外层也吃 90px，总留白翻倍（180px），
     * 内容反而更窄 —— 这是「看起来仍很窄」的直接原因。
     */
    private static boolean needsGutter(String cn) {
        return (cn.contains("CommentListView") || cn.contains("AsyncImageInfoView"))
                && !cn.contains("NoteDetailContentView");
    }

    /** 外层容器：必须保持 0 内边距，否则会挤压子视图。 */
    private static boolean isOuterContainer(String cn) {
        return cn.contains("NoteDetailContentView");
    }

    /**
     * 遍历一棵子树，把命中目标类的节点边距归一到 PAD_TARGET。
     * 用于在 XHS 自己的计算逻辑（wj9.c0.y0）返回后立刻修正结果，
     * 此时尚未测量/布局/绘制，所以不会产生可见的中间态。
     */
    private static void normalizeTree(View root) {
        normalizeNode(root, 0);
    }

    private static void normalizeNode(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return;
        }
        try {
            if (isTarget(v.getClass().getName())) {
                clampPadding(v);
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    normalizeNode(vg.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hook the REAL class's setPadding / setPaddingRelative. XHS overrides
     * setPadding on its comment/image containers, so hooking only View.setPadding
     * (the superclass declaration) would miss those calls. We hook the override
     * on the actual runtime class (and walk up the hierarchy to be safe).
     */
    private void hookPaddingSetters(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                final Method sp = c.getDeclaredMethod(
                        "setPadding", int.class, int.class, int.class, int.class);
                sp.setAccessible(true);
                hook(sp).intercept(clampHookFor());
            } catch (Throwable ignored) {
            }
            try {
                final Method spr = c.getDeclaredMethod(
                        "setPaddingRelative", int.class, int.class, int.class, int.class);
                spr.setAccessible(true);
                hook(spr).intercept(clampHookFor());
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
    }

    private XposedInterface.Hooker clampHookFor() {
        return chain -> {
            try {
                Object thisObj = chain.getThisObject();
                String cn = thisObj instanceof View
                        ? thisObj.getClass().getName() : "";
                List<Object> args = chain.getArgs();
                int l = (Integer) args.get(0);
                int r = (Integer) args.get(2);
                if (isTarget(cn)) {
                    // 内容区收成 45；外层容器清成 0（否则会挤压子视图 90px）。
                    // 不再要求 l == r 且 >= PAD_THRESHOLD —— 小红书偶尔会写
                    // 不对称值或小于阈值的值，旧条件放行后边距就再也拉不回来，
                    // 表现为「偶发某条帖子的评论区宽度不对」。
                    int want = isOuterContainer(cn) ? 0 : fPad;
                    if (l != want || r != want) {
                        // 复制后改写：chain.getArgs() 不保证可变，
                        // 原地 set 在个别通道会抛 UnsupportedOperationException。
                        List<Object> patched = new java.util.ArrayList<>(args);
                        patched.set(0, want);
                        patched.set(2, want);
                        return chain.proceedWith(patched);
                    }
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        };
    }



    private void scheduleFix(final Activity a) {
        try {
            refreshScreenShort(a);
            final View decor = a.getWindow().getDecorView();
            if (decor == null) {
                return;
            }
            decor.post(() -> fixContent(a));
            // The comment container is created AFTER onResume, so a single pass
            // would miss it and XHS would reset the padding. Two later passes
            // cover the async comment load.
            decor.postDelayed(() -> fixContent(a), 300);
            decor.postDelayed(() -> fixContent(a), 800);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "scheduleFix error: " + t);
        }
    }

    /** 用 Activity 自己的 DisplayMetrics 记录当前屏短边（换屏时 onConfigurationChanged 也会进来）。 */
    private static void refreshScreenShort(Activity a) {
        try {
            android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
            if (dm == null || dm.widthPixels <= 0) {
                return;
            }
            // 一律以 Configuration 为准：Activity 的 DisplayMetrics 在
            // onConfigurationChanged 里可能还没刷新，会把横屏当竖屏，
            // 于是横屏也被强制成竖屏的列数（实测就是这样把横屏锁成 3 列的）。
            // 只认 Configuration 这一份。曾经这里还用 DisplayMetrics 再写一遍，
            // 两边的舍入差 1px(1671 vs 1672) 会让「屏变了」被反复判定成立：
            // 每次 onResume 都重读档位、重算列数、重设 spanCount —— 这就是
            // 进软件时列表闪一下的来源。
            updateFromConfig(a.getResources().getConfiguration());
        } catch (Throwable ignored) {
        }
    }

    private void fixContent(Activity a) {
        try {
            View root = a.findViewById(android.R.id.content);
            if (!(root instanceof ViewGroup)) {
                return;
            }
            ViewGroup content = (ViewGroup) root;
            for (int i = 0; i < content.getChildCount(); i++) {
                fixView(content.getChildAt(i), 0, a);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "fixContent error: " + t);
        }
    }

    /**
     * Walk the tree looking ONLY for the targeted container classes. Everything
     * else is left completely untouched — that is what keeps the layout stable.
     */
    private void fixView(View v, int depth, Activity a) {
        if (v == null || depth > MAX_DEPTH) {
            return;
        }
        try {
            String cn = v.getClass().getName();
            if (isTarget(cn)) {
                // Hook the real class's setters so XHS's override (and async
                // re-sets) are intercepted. Done once per class.
                synchronized (hookedClasses) {
                    if (!hookedClasses.contains(cn)) {
                        hookedClasses.add(cn);
                        hookPaddingSetters(v.getClass());
                    }
                }
                clampPadding(v);
                boolean fresh = v.getTag(TAG_GUARD) == null;
                ensureGuard(v);
                if (fresh) {
                    if (sDebug) {
                        log(Log.INFO, TAG, "guard on (fixView): " + cn);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (depth < MAX_DEPTH && v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                fixView(vg.getChildAt(i), depth + 1, a);
            }
        }
    }

    /**
     * 挂 PreDraw 守卫（幂等）。onAttachedToWindow 与 fixView 两条路径都会调，
     * 用 View tag 去重，避免同一个 View 上堆多个监听器。
     */
    @SuppressWarnings("unchecked")
    private static void ensureGuard(View v) {
        try {
            if (v.getTag(TAG_GUARD) != null) {
                return;
            }
            v.setTag(TAG_GUARD, Boolean.TRUE);
            attachLayoutGuard(v);
            // 首帧的 dump 里子视图还没量好（标题块高度为 0），
            // 这里再补一次「布局稳定后」的全树快照，用于定位标题块的真实位置。
            if (sTree && v.getClass().getName().contains("NoteDetailContentView")) {
                v.postDelayed(() -> dumpTreeNow(v, "settled"), 1500L);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Idempotent clamp：内容区设 45px 边距，外层容器保持 0。
     * 只在数值真的不对时写入，避免每帧触发重新布局。
     */
    private static void clampPadding(View v) {
        if (OBSERVE_ONLY) {
            return;
        }
        try {
            String cn = v.getClass().getName();
            int want = isOuterContainer(cn) ? 0
                    : (needsGutter(cn) ? fPad : v.getPaddingLeft());
            int l = v.getPaddingLeft();
            int r = v.getPaddingRight();
            if (l != want || r != want) {
                v.setPadding(want, v.getPaddingTop(), want, v.getPaddingBottom());
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 视图层级诊断（DEBUG_TREE） ====================

    private static volatile long sLastTreeDump = 0L;

    /**
     * 把整棵视图树打出来：每行的含义
     *   类名 w=宽 h=高 pl/pr=左右内边距 lp=LayoutParams 的宽x高
     *   ImageView 追加 st=scaleType dw/dh=图片固有尺寸
     *
     * 找一个「图片比预期小」的问题，唯一可靠的办法就是看实际数字：
     * 是容器被限宽、是 padding 吃掉了宽度、还是图片本身按比例缩了。
     */
    private static void dumpTreeNow(View v, String why) {
        XposedModule m = sModule;
        if (m == null) {
            return;
        }
        try {
            View root = v.getRootView();
            int[] lines = new int[]{0};
            if (sDebug) {
                m.log(Log.INFO, TAG, "TREE " + why
                        + " screenShort=" + currentShortSidePx()
                        + " v=" + v.getWidth() + "x" + v.getHeight());
            }
            dumpNode(root, 0, m, lines);
            if (sDebug) {
                m.log(Log.INFO, TAG, "TREE end lines=" + lines[0]);
            }
        } catch (Throwable t) {
            m.log(Log.WARN, TAG, "dumpTree failed: " + t);
        }
    }

    private static void dumpTreeIfDue(View v, String why) {
        if (!sTree) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sLastTreeDump < 4000L) {
            return;
        }
        sLastTreeDump = now;
        XposedModule m = sModule;
        if (m == null) {
            return;
        }
        try {
            View root = v.getRootView();
            int[] lines = new int[]{0};
            if (sDebug) {
                m.log(Log.INFO, TAG, "TREE " + why
                        + " screenShort=" + currentShortSidePx()
                        + " v=" + v.getWidth() + "x" + v.getHeight());
            }
            dumpNode(root, 0, m, lines);
            if (sDebug) {
                m.log(Log.INFO, TAG, "TREE end lines=" + lines[0]);
            }
        } catch (Throwable t) {
            m.log(Log.WARN, TAG, "dumpTree failed: " + t);
        }
    }

    /**
     * 详情页图片区尺寸重算（本次新增功能）。
     *
     * 取证结论（v1.9 外屏 1168×1712 实测视图树）：
     *   AsyncImageDisplayView  w=1168 h=1012        ← 满宽，正常
     *     ImageGalleryView     w=759  h=1012  lp=759x1012   ← ★ 宽度被写死成 759
     *       MatrixHorizontalRecyclerView / AsyncPhotoNoteItemView 也都是 759
     *
     * 也就是说图片变小**不是**内边距问题（那里已经是 0），而是小红书按图片比例
     * 先把高度定成 1012（高度的上限），再用宽高比 759/1012=0.75 反推出宽度。
     * 高度上限一变，宽度就跟着缩水 —— 这就是「比之前小很多」的机制。
     *
     * 修法：不去猜它的上限，直接按「能放多大放多大、但不出屏」重算：
     *   可用宽 = 展示区宽 - 2 * margin
     *   可用高 = 滚动视口高 - 2 * margin
     *   宽高比沿用小红书自己算出来的（lp.width / lp.height，就是这么来的）
     *   先按满宽算高；高超过可用高就按可用高回算宽。
     * 这样竖图会顶到「一屏放得下的最大尺寸」，横图则铺满宽度，两边都留 margin。
     */
    private static volatile long sLastFitLog = 0L;

    /**
     * 在图片展示区里找到图片画廊并重算尺寸。
     * 画廊不是 display 的直接子节点（层级是 display → LinearLayout → ImageGalleryView），
     * 所以这里按类名下潜找，最多 3 层。
     */
    /** 第一次找到的画廊 View，缓存到展示区的 tag 上，避免每帧递归下潜。 */
    private static final int TAG_FIT_GAL = 0x584853_47;   // 'XHSG'

    /**
     * 图片自适应只在竖屏做。
     *
     * 横屏如果也按「一屏放得下」算，可用高就是 1672 那条边，竖图的宽会被压到
     * 800 多（屏幕宽 2364），看起来「突然变得很小」；而且换向时先按竖屏算一次、
     * 再按横屏算一次，中间那一帧就是肉眼看到的闪。横屏一律把尺寸还给小红书自己。
     */
    /** 同 fitGallery，但返回「这一帧要不要先跳过」。 */
    /**
     * 是不是 Live Photo（实况/动态照片）。
     *
     * 这类帖子的小红书侧是一直在刷新的播放器，它每个布局周期都会重新给尺寸，
     * 我们改写就会被无限改回去 —— 表现就是「严重闪烁」。
     * 判断依据：画廊里的条目标签带 livephoto（dex 里存在
     * NoteDetailLivePhotoItemPresenter / LocalLivePhotoPlayer / *LivePhotoItemView 这些类）。
     */
    private static boolean hasLivePhotoItem(View gal) {
        try {
            if (!(gal instanceof ViewGroup)) {
                return false;
            }
            ViewGroup g = (ViewGroup) gal;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = g.getChildAt(i);
                if (c == null) {
                    continue;
                }
                String cn = c.getClass().getName().toLowerCase(java.util.Locale.ROOT);
                if (cn.contains("livephoto") || cn.contains("live_photo")) {
                    return true;
                }
                if (c instanceof ViewGroup) {
                    ViewGroup cc = (ViewGroup) c;
                    for (int k = 0; k < cc.getChildCount(); k++) {
                        View gc = cc.getChildAt(k);
                        if (gc != null && gc.getClass().getName()
                                .toLowerCase(java.util.Locale.ROOT).contains("livephoto")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 已判定为实况照片的画廊：从此完全不参与尺寸改写（tag 打在画廊上，跟着 View 走）。 */
    private static final int TAG_LIVE = 0x58485348;

    private static boolean isLiveGallery(View gal) {
        return gal != null && gal.getTag(TAG_LIVE) != null;
    }

    private static boolean fitGalleryAndMaybeSkip(View display) {
        if (!fFill || !(display instanceof ViewGroup)) {
            return false;
        }
        try {
            Object cachedGal = display.getTag(TAG_FIT_GAL);
            if (cachedGal instanceof View) {
                View gal = (View) cachedGal;
                if (gal.getParent() != null) {
                    if (isLiveGallery(gal)) {
                        return false;             // 实况照片：已判定过，永久放行
                    }
                    if (hasLivePhotoItem(gal)) {
                        gal.setTag(TAG_LIVE, Boolean.TRUE);
                        releaseFit(display);      // 撤回我们可能写过的那一次
                        return false;
                    }
                    return fitOneGallery(gal, display);
                }
                display.setTag(TAG_FIT_GAL, null);
            }
            fitGalleryIn((ViewGroup) display, display, 0);
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) {
                m.log(Log.WARN, TAG, "FIT failed: " + t);
            }
        }
        return false;
    }

    private static void fitGallery(View display) {
        if (!fFill || !(display instanceof ViewGroup)) {
            return;
        }
        try {
            if (!isPortrait()) {
                releaseFit(display);
                return;
            }
            Object cachedGal = display.getTag(TAG_FIT_GAL);
            if (cachedGal instanceof View) {
                View gal = (View) cachedGal;
                if (gal.getParent() != null) {
                    if (isLiveGallery(gal)) {
                        return;
                    }
                    if (hasLivePhotoItem(gal)) {
                        gal.setTag(TAG_LIVE, Boolean.TRUE);
                        releaseFit(display);
                        return;
                    }
                    fitOneGallery(gal, display);
                    return;
                }
                display.setTag(TAG_FIT_GAL, null);
            }
            fitGalleryIn((ViewGroup) display, display, 0);
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) {
                m.log(Log.WARN, TAG, "FIT failed: " + t);
            }
        }
    }

    /** 退回小红书自己的尺寸（横屏时用），只撤回我们自己写过的那一次。 */
    private static void releaseFit(View display) {
        Object cachedGal = display.getTag(TAG_FIT_GAL);
        if (!(cachedGal instanceof View)) {
            return;
        }
        View gal = (View) cachedGal;
        Object cached = gal.getTag(TAG_FIT);
        if (cached instanceof int[] && ((int[]) cached).length >= 7) {
            int[] box = (int[]) cached;
            ViewGroup.LayoutParams lp = gal.getLayoutParams();
            if (lp != null && lp.width == box[0] && lp.height == box[1]
                    && box[5] > 0 && box[6] > 0) {
                lp.width = box[5];
                lp.height = box[6];
                gal.setLayoutParams(lp);
            }
        }
        gal.setTag(TAG_FIT, null);
        display.setTag(TAG_FIT_GAL, null);
    }

    private static void fitGalleryIn(ViewGroup parent, View display, int depth) {
        if (depth > 3) {
            return;
        }
        int n = parent.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = parent.getChildAt(i);
            if (c == null) {
                continue;
            }
            if (c.getClass().getName().contains(GALLERY_CLASS_HINT)) {
                if (isLiveGallery(c)) {
                    continue;
                }
                if (hasLivePhotoItem(c)) {
                    c.setTag(TAG_LIVE, Boolean.TRUE);
                    continue;
                }
                display.setTag(TAG_FIT_GAL, c);
                fitOneGallery(c, display);
            } else if (c instanceof ViewGroup && c.getVisibility() == View.VISIBLE) {
                fitGalleryIn((ViewGroup) c, display, depth + 1);
            }
        }
    }

    /**
     * 缓存在画廊 View 上的自适应结果：
     *   [0] 我们写进去的宽  [1] 高  [2] 屏短边  [3] 原始宽高比*10000
     *   [4] 屏宽px        [5] 小红书原本的 lp.width  [6] 原本的 lp.height
     */
    private static final int TAG_FIT = 0x584853_46;   // 'XHSF'


    /** 诊断用：只保留小红书自己的调用帧（framework / androidx 全丢掉，否则 10 条就被占满）。 */
    private static String appStack() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (!c.startsWith("com.xingin")) {
                    continue;
                }
                sb.append("    ").append(c).append('.').append(e.getMethodName())
                  .append(':').append(e.getLineNumber()).append('\n');
                if (++n >= 12) {
                    break;
                }
            }
            if (sb.length() == 0) {
                sb.append("    <no com.xingin frames>\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "    <stack failed>";
        }
    }

    /** 只用于诊断：本次进程里写过多少次 lp。 */
    private static int sFitWrites = 0;

    /**
     * 图片目标尺寸：只用「当前屏」的信息算，不看视图当下的位置/尺寸。
     *
     * 为什么要这么死板：点进帖子的**第一帧**里，展示区和画廊的位置都还是 0，
     * 只有屏幕尺寸是确定的。如果测量阶段和绘制前的两处算法不同（一处用屏幕、
     * 一处用「图片顶到视口底」），第一帧会先按 A 画、再按 B 改一次 —— 这就是
     * 「点进帖子图片闪一下」。现在两条路径都调这一个函数，结果完全一致，
     * 尺寸只在进入时定一次，不会再改第二次。
     */
    private static int[] fitTargetBox(float ratio, int topOnScreen) {
        int margin = fMargin;
        int screenW = currentWidthPx();
        int screenH = currentScreenHeightPx();
        if (screenW <= 0 || screenH <= 0) {
            return null;
        }
        int availW = screenW - margin * 2;
        // 图片不是永远贴在屏幕顶上（上方可能有作者栏/多图分页），
        // 所以可用高必须扣掉它自己的顶边，否则往下挪一点就顶出下边界。
        int top = Math.max(topOnScreen, 0);
        int availH = screenH - top - fBottom - fTitle - margin * 2;
        if (availW <= 0 || availH <= 0) {
            return null;
        }
        int w = availW;
        int h = Math.round(w / ratio);
        if (h > availH) {
            h = availH;
            w = Math.round(h * ratio);
        }
        return new int[]{w, h, availW, availH};
    }

    /** 从画廊自己的 lp / 已有缓存里拿到图片原生宽高比。 */
    private static float ratioOf(View gal, ViewGroup.LayoutParams lp, int[] box) {
        if (box != null && box.length >= 7 && box[3] > 0) {
            return box[3] / 10000f;
        }
        if (lp != null && lp.width > 0 && lp.height > 0) {
            return (float) lp.width / (float) lp.height;
        }
        if (gal.getWidth() > 0 && gal.getHeight() > 0) {
            return (float) gal.getWidth() / (float) gal.getHeight();
        }
        return -1f;
    }

    /**
     * 按当前屏把画廊尺寸定好。测量阶段与绘制前都调它，幂等。
     * @return 是否真的写入了 lp
     */
    private static boolean applyFit(View gal, String why) {
        ViewGroup.LayoutParams lp = gal.getLayoutParams();
        if (lp == null) {
            return false;
        }
        int shortSide = currentShortSidePx();
        int width = currentWidthPx();
        Object cached = gal.getTag(TAG_FIT);
        int[] box = cached instanceof int[] && ((int[]) cached).length >= 7 ? (int[]) cached : null;
        float ratio = ratioOf(gal, lp, box);
        if (ratio <= 0.05f || ratio > 20f) {
            return false;
        }

        int w, h;
        boolean first;
        int ratioKey = Math.round(ratio * 10000f);
        if (box != null && box[2] == shortSide && box[4] == width && box[3] == ratioKey) {
            // ★ 一旦为这张图定过尺寸，就永远复用这一组数字。
            //
            // 之前这里每次都按「图片当前所在位置」重算可用高度，而改尺寸本身
            // 又会改变它所在的位置 —— 于是目标值每帧都在变：
            //   1337x1782 → 1211x1615 → 1399x1865 → 1243x1657 …
            // 这不是小红书在改，是模块自己在跟自己打架（日志里 from 就是上一轮的目标值）。
            // 现在只有「屏变了 / 屏宽变了 / 比例变了」才重算，其余一律复用。
            w = box[0];
            h = box[1];
            first = false;
        } else {
            int top;
            if (gal.isLaidOut()) {
                int[] loc = new int[2];
                try {
                    gal.getLocationOnScreen(loc);
                } catch (Throwable ignored) {
                }
                top = Math.max(loc[1], 0);
            } else {
                // 还是测量阶段，位置拿不到（getLocationOnScreen 这时给 0 或上一次的值）。
                //
                // 旧代码在这里 return false 等下一帧，于是「第一次点开」的目标尺寸
                // 只能在 PreDraw 里写，比小红书画出自己尺寸晚一步 —— 用户看到的
                // 那个生硬跳变就是这么来的；后面几次因为已经有缓存、走的是复用分支，
                // 不需要等布局，所以显得「好一点」。
                //
                // 现在改成用状态栏高度当图片顶边的估计值，目标尺寸就能在**同一趟
                // measure/layout** 里生效，第一帧画出来就是终态，没有跳变。
                // 图片页的图片本来就贴在状态栏下面，误差只剩页面顶部那点 padding。
                top = statusBarHeightPx();
            }
            int[] target = fitTargetBox(ratio, top);
            if (target == null) {
                return false;
            }
            w = target[0];
            h = target[1];
            first = true;
        }

        if (!fEnlarge) {
            // 只收不放：当前尺寸已经没有超出可用框的话，什么都不做。
            boolean fitsNow = lp.width > 0 && lp.height > 0
                    && lp.width <= w && lp.height <= h;
            if (fitsNow) {
                if (first) {
                    gal.setTag(TAG_FIT, new int[]{w, h, shortSide, ratioKey, width,
                            lp.width, lp.height});
                    XposedModule m0 = sModule;
                    if (m0 != null && sDebug) {
                        m0.log(Log.INFO, TAG, "FIT keep -> 原尺寸 " + lp.width + "x" + lp.height
                                + " 本来就在 " + w + "x" + h + " 之内");
                    }
                }
                return false;
            }
        }
        if (lp.width == w && lp.height == h) {
            if (first) {
                gal.setTag(TAG_FIT, new int[]{w, h, shortSide, ratioKey, width, lp.width, lp.height});
            }
            return false;
        }

        int fromW = lp.width, fromH = lp.height;
        android.view.ViewGroup.LayoutParams fresh = cloneLp(lp, w, h);
        gal.setLayoutParams(fresh);
        if (first) {
            gal.setTag(TAG_FIT, new int[]{w, h, shortSide, ratioKey, width, fromW, fromH});
            // v2.6.2：这里**不再**自己叠一层 scaleX/scaleY 过渡。
            //
            // 原因：小红书自己就有一段「缩略图 → 大图」的过场动画（它把图片从列表
            // 位置放大到目标位置）。我们在它上面再补一个 160ms 的 0.81→1 缩放，
            // 两段运动叠加，第一次点开时就表现为「先硬跳一下再缩放」——也就是用户
            // 说的「非常生硬」。后面几次因为图片已经缓存在目标尺寸，它的过场变得很
            // 快，只剩我们的缩放，看起来就「好一点」。
            //
            // 现在的原则：尺寸只在**绘制之前**就位（fitBeforeMeasure 里写 lp，
            // 同一次布局生效；PreDraw 兜底并跳过那一帧），运动完全交给小红书的
            // 过场动画，模块不再制造第二段运动。
        }
        XposedModule m = sModule;
        if (m != null && sDebug) {
            m.log(Log.INFO, TAG, "FIT " + why + " -> " + w + "x" + h
                    + " from=" + fromW + "x" + fromH + (first ? " first" : " reassert"));
        }
        return true;
    }

    /**
     * 绘制前校正，返回 true 表示「这一帧先别画」。
     *
     * 我们在 PreDraw 里改了 LayoutParams，但画面还是上一次测量的尺寸 ——
     * 如果照常绘制，用户就会看到「小红书尺寸的那一帧」。返回 false 取消这次绘制，
     * 上一帧的画面会保留着（不是黑屏），下一帧就是我们想要的尺寸了。
     * 每个画廊最多跳过 2 帧，避免万一陷入来回改写时把页面卡住。
     */
    private static boolean fitOneGallery(View gal, View display) {
        try {
            if (galleryWrites(gal) >= 2) {
                return false;      // 已经改写过两次还在被改回去：彻底放手，避免无限抢尺寸
            }
            boolean wrote = applyFit(gal, "draw");
            if (!wrote) {
                return false;
            }
            if (galleryWrites(gal) >= 2) {
                return false;      // 已经跳过两帧，别再跳过，防止页面卡住
            }
            bumpGalleryWrites(gal);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final int TAG_FIT_SKIP = 0x584853_4A;   // 'XHSJ'

    private static int galleryWrites(View gal) {
        Object v = gal.getTag(TAG_FIT_SKIP);
        return v instanceof Integer ? (Integer) v : 0;
    }

    private static void bumpGalleryWrites(View gal) {
        gal.setTag(TAG_FIT_SKIP, galleryWrites(gal) + 1);
    }

        /**
     * 测量前先把尺寸定好：这时候父容器还没给子视图算过尺寸，
     * 我们现在写的 lp 会在**同一次布局**里生效，因此第一帧就是最终大小，不会闪。
     */
    private static void fitBeforeMeasure(View display) {
        if (!fFill || !isPortrait() || !(display instanceof ViewGroup)) {
            return;
        }
        try {
            Object cachedGal = display.getTag(TAG_FIT_GAL);
            if (cachedGal instanceof View) {
                View gal = (View) cachedGal;
                if (gal.getParent() != null) {
                    applyFit(gal, "measure");
                    return;
                }
                display.setTag(TAG_FIT_GAL, null);
            }
            fitGalleryIn((ViewGroup) display, display, 0);
        } catch (Throwable ignored) {
        }
    }

    private static volatile Class<?> sGalleryClass = null;

    /** 状态栏高度（首次定位图片顶边时用，作为「还没布局好」时的估计值）。 */
    private static int statusBarHeightPx() {
        try {
            android.content.Context ctx = currentContext();
            if (ctx != null) {
                android.content.res.Resources r = ctx.getResources();
                int id = r.getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) {
                    return r.getDimensionPixelSize(id);
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int topEstimate(View v) {
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (v.isLaidOut() && loc[1] > 0) {
                return loc[1];
            }
        } catch (Throwable ignored) {
        }
        return statusBarHeightPx();
    }

    /**
     * 在**写 LayoutParams 的那一刻**就把宽高改成目标值。
     *
     * 这是唯一能消掉「先按小红书尺寸画一帧」的位置：小红书是先 setLayoutParams、
     * 再测量、再布局、再绘制，我们在它写进去的同一瞬间改掉，它测量的就已经是
     * 最终尺寸，第一帧（包括打开动画里的那些帧）就是对的。
     *
     * 全局挂 `View.setLayoutParams`，但只用一次 instanceof 判断把无关的调用放过去。
     */
    private void installGalleryLpHook(ClassLoader cl) {
        try {
            sGalleryClass = Class.forName(GALLERY_FQN, false, cl);
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) {
                m.log(Log.WARN, TAG, "gallery class not found: " + t);
            }
            return;
        }
        int hooked = 0;
        // 沿继承链逐个挂：ImageGalleryView 自己不声明 setLayoutParams，
        // 但它继承的类是重写过的（RecyclerView 就重写了），只挂 View 的声明会漏掉。
        for (Class<?> c = sGalleryClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                final Method slp = c.getDeclaredMethod(
                        "setLayoutParams", android.view.ViewGroup.LayoutParams.class);
                slp.setAccessible(true);
                hook(slp).intercept(chain -> {
                    try {
                        Class<?> g = sGalleryClass;
                        Object self = chain.getThisObject();
                        if (g != null && fFill && isPortrait() && g.isInstance(self)) {
                            Object lp = chain.getArgs().get(0);
                            if (lp instanceof android.view.ViewGroup.LayoutParams) {
                                rewriteGalleryLp((View) self,
                                        (android.view.ViewGroup.LayoutParams) lp);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        XposedModule m = sModule;
        if (m != null && sDebug) {
            m.log(Log.INFO, TAG, "hooked setLayoutParams on " + hooked + " classes in the chain");
        }
        if (hooked == 0 && m != null) {
            m.log(Log.WARN, TAG, "setLayoutParams not hookable on gallery chain");
        }
    }

    /**
     * 诊断（只在 debug 打开时安装）：抓 gallery 触发 requestLayout 时的调用栈，
     * 用来定位小红书是在哪个方法里算好尺寸并写进 LayoutParams 的。
     * requestLayout 是超热方法，所以正式版（sDebug=false）根本不安装。
     */

    private static android.view.ViewGroup.LayoutParams cloneLp(
            android.view.ViewGroup.LayoutParams src, int w, int h) {
        try {
            java.lang.reflect.Constructor<?> c = src.getClass().getConstructor(int.class, int.class);
            Object o = c.newInstance(w, h);
            for (String f : new String[]{"gravity", "weight", "leftMargin", "rightMargin",
                    "topMargin", "bottomMargin"}) {
                copyField(src, o, f);
            }
            return (android.view.ViewGroup.LayoutParams) o;
        } catch (Throwable t) {
            src.width = w;
            src.height = h;
            return src;
        }
    }

    private static void copyField(Object from, Object to, String name) {
        try {
            java.lang.reflect.Field f = from.getClass().getField(name);
            f.set(to, f.get(from));
        } catch (Throwable ignored) {
        }
    }

    private static void rewriteGalleryLp(View gal, android.view.ViewGroup.LayoutParams lp) {
        float ratio = ratioOf(gal, lp, null);
        if (ratio <= 0.05f || ratio > 20f) {
            return;
        }
        // ★ 先看缓存：这张图的尺寸一旦定过，任何路径都只能复用那一组数字。
        // 之前这里又按「当前位置」重算了一遍，于是同一条帖子在
        // 1337x1782 与 1326x1767 之间来回横跳（差 20px 的顶边就能换出另一个目标值）。
        Object cached = gal.getTag(TAG_FIT);
        if (cached instanceof int[] && ((int[]) cached).length >= 7) {
            int[] box = (int[]) cached;
            if (box[0] > 0 && box[1] > 0) {
                if (lp.width != box[0] || lp.height != box[1]) {
                    lp.width = box[0];
                    lp.height = box[1];
                }
                return;
            }
        }
        int[] target = fitTargetBox(ratio, topEstimate(gal));
        if (target == null) {
            return;
        }
        int w = target[0], h = target[1];
        if (lp.width == w && lp.height == h) {
            return;
        }
        int fromW = lp.width, fromH = lp.height;
        lp.width = w;
        lp.height = h;
        gal.setTag(TAG_FIT, new int[]{w, h, currentShortSidePx(),
                Math.round(ratio * 10000f), currentWidthPx(), fromW, fromH});
        XposedModule m = sModule;
        if (m != null && sDebug) {
            m.log(Log.INFO, TAG, "LP rewrite -> " + w + "x" + h + " from=" + fromW + "x" + fromH);
        }
    }

    /** 装上测量前的兜口：AsyncImageDisplayView.onMeasure 一进来就把图片尺寸定好。 */
    private void installMeasureFit(ClassLoader cl) {
        try {
            Class<?> display = Class.forName(DISPLAY_CLASS, false, cl);
            Method om = display.getDeclaredMethod("onMeasure", int.class, int.class);
            om.setAccessible(true);
            hook(om).intercept(chain -> {
                try {
                    fitBeforeMeasure((View) chain.getThisObject());
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            XposedModule m = sModule;
            if (m != null && sDebug) {
                m.log(Log.INFO, TAG, "hooked onMeasure for pre-measure fit");
            }
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) {
                m.log(Log.WARN, TAG, "hook onMeasure failed: " + t);
            }
        }
    }

        private static void dumpNode(View v, int depth, XposedModule m, int[] lines) {
        if (v == null || depth > 16 || lines[0] >= DUMP_MAX_LINES) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(96);
            for (int i = 0; i < depth; i++) {
                sb.append(' ');
            }
            int[] scr = new int[2];
            v.getLocationOnScreen(scr);
            sb.append(v.getClass().getSimpleName())
              .append(" y=").append(scr[1])
              .append(" w=").append(v.getWidth())
              .append(" h=").append(v.getHeight())
              .append(" pl=").append(v.getPaddingLeft())
              .append(" pr=").append(v.getPaddingRight());
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                sb.append(" lp=").append(lp.width).append('x').append(lp.height);
            }
            if (v instanceof android.widget.ImageView) {
                android.widget.ImageView iv = (android.widget.ImageView) v;
                android.graphics.drawable.Drawable d = iv.getDrawable();
                sb.append(" st=").append(iv.getScaleType())
                  .append(" dw=").append(d == null ? -1 : d.getIntrinsicWidth())
                  .append(" dh=").append(d == null ? -1 : d.getIntrinsicHeight());
            }
            if (v.getVisibility() != View.VISIBLE) {
                sb.append(" vis=").append(v.getVisibility());
            }
            if (sDebug) {
                m.log(Log.INFO, TAG, sb.toString());
            }
            lines[0]++;
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                int n = vg.getChildCount();
                for (int i = 0; i < n && lines[0] < DUMP_MAX_LINES; i++) {
                    dumpNode(vg.getChildAt(i), depth + 1, m, lines);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 每帧绘制前把目标容器「拉满宽度」并设定固定内边距。
     *
     * 为什么改宽度而不是只改 padding（逐帧取证后的结论）：
     *   NoteDetailContentView  pad=45  w=1672   ← 满宽容器
     *   CommentListView        pad=45  w=1582   ← 子视图比满宽少 90px
     *   AsyncImageInfoView     pad=45  w=1582
     * 小红书把「左右留白」做成了子视图宽度（1672-90=1582），而 padding 是
     * 额外一层内边距。只改 padding 会与它的宽度计算叠加，总留白变成
     * 90+90=180px，看上去依然偏窄、且首帧数值仍在 277/45 之间跳。
     *
     * 这里直接把宽度钉成 MATCH_PARENT：容器满宽 + 固定 45px 内边距
     * = 内容区 1582px，左右各 45px，正是期望效果，且不再随它的计算漂移。
     * 归一化只在数值真的不对时写入，避免每帧触发重新布局。
     */
    private static void attachLayoutGuard(final View v) {
        try {
            final String cn = v.getClass().getName();
            final boolean outer = isOuterContainer(cn);
            final boolean gutter = needsGutter(cn);
            final boolean display = cn.contains("AsyncImageDisplayView");
            final int want = outer ? 0 : (gutter ? fPad : -1);
            final ViewTreeObserver.OnPreDrawListener l =
                    new ViewTreeObserver.OnPreDrawListener() {
                private int frame = 0;

                @Override
                public boolean onPreDraw() {
                    boolean skipThisFrame = false;
                    try {
                        // want < 0 表示这既不是外层也不是内容区，无需干预
                        // 守卫常驻：只做一次整数比较，开销可忽略。
                        // 早先在稳定 30 帧后自行摘除，之后小红书再异步改一次
                        // 内边距就无人纠正 —— 评论区是异步加载的，触发时机随机，
                        // 这正是「偶发某条帖子宽度不对、重进又正常」的原因。
                        if (want >= 0) {
                            if (v.getPaddingLeft() != want || v.getPaddingRight() != want) {
                                v.setPadding(want, v.getPaddingTop(),
                                        want, v.getPaddingBottom());
                            }
                        }

                        // 诊断：只在外层容器绘制前 dump 一次整棵树，避免多份重复。
                        if (sTree && outer) {
                            dumpTreeIfDue(v, "detail");
                        }
                        if (display) {
                            // 竖屏:按可用空间放大;横屏:把尺寸还给小红书(见 fitGallery)
                            if (fFill) {
                                skipThisFrame = fitGalleryAndMaybeSkip(v);
                            } else {
                                releaseFit(v);
                            }
                        }

                        if (VERBOSE && frame < 20) {
                            XposedModule m = sModule;
                            if (m != null) {
                                // 正文短时评论区贴底，关键看这几个量：
                                // h（自身高度）、top（相对父级位置）、
                                // ph（父级高度）、scrollY（滚动位置）。
                                int top = 0;
                                try {
                                    View pv = (View) v.getParent();
                                    top = v.getTop();
                                    if (pv != null && pv.getHeight() > 0) {
                                        // 记录父级高度，判断内容是否撑满
                                        if (sDebug) {
                                            m.log(Log.INFO, TAG, "PARENT "
                                                    + v.getClass().getSimpleName()
                                                    + " ph=" + pv.getHeight()
                                                    + " pw=" + pv.getWidth());
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                int sc = -1;
                                try {
                                    sc = v.getScrollY();
                                } catch (Throwable ignored) {
                                }
                                if (sDebug) {
                                    m.log(Log.INFO, TAG, "FRAME " + frame + " "
                                            + v.getClass().getSimpleName()
                                            + " pad=" + v.getPaddingLeft()
                                            + " w=" + v.getWidth()
                                            + " h=" + v.getHeight()
                                            + " top=" + top
                                            + " scrollY=" + sc
                                            + " vis=" + v.getVisibility());
                                }
                            }
                            frame++;
                        }
                    } catch (Throwable ignored) {
                    }
                    return !skipThisFrame; // false = 这一帧先不画（尺寸刚要变）
                }
            };
            v.getViewTreeObserver().addOnPreDrawListener(l);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 内外屏档位独立记忆 ====================

    private static final String SP_KEY = "screen_scale_sp_key";

    /** 已发现的存储类，避免重复扫描 DexFile。 */
    private static volatile Class<?> sStoreClass = null;

    /** 候选类名缓存：已知的混淆名，按优先级尝试，命中即用。 */
    /**
     * 已知的混淆名，命中即用；未命中再按特征扫描。
     *
     * 9.50~9.54 是 q3e.h / s3e.h，9.55.0 换成 p7e.h（classes14.dex）。
     * 名字每次大版本都可能变，写在这里只是为了「快」——真正的兜底是
     * scanDexForStore 的结构匹配（静态 g() 返回自身 + j(String,int)I + u(String,int)V）。
     */
    // 已知的存储封装类名，按版本从新到旧。混淆名会随版本变：
    //   9.54.0 q3e.h / 9.54.1 s3e.h / 9.55.0 p7e.h / 9.56.0 cce.h
    // 新版本若又改名，用下面的方法找（已验证可复现）：
    //   1) 在 dex 里搜字符串 "screen_scale_sp_key"，命中类就是档位控制类 ms.n；
    //   2) 反编译 ms.n.<clinit>() 与 ms.n.m(I,F)I，里面会 `invoke-static Lcce/h;->g()`
    //      与 `invoke-virtual Lcce/h;->u(Ljava/lang/String;I)V`；
    //   3) 该类即存储封装类，结构恒为：static g()返回自身 + j(String,int)I + u(String,int)V。
    private static final String[] STORE_HINTS = {"cce.h", "p7e.h", "q3e.h", "s3e.h", "r3e.h", "t3e.h"};

    /**
     * 找到小红书的键值存储封装类。
     *
     * 这个类每次 App 更新都可能换混淆名（9.54.0 是 q3e.h，9.54.1 变成 s3e.h），
     * 所以不能写死。先按已知名快查，失败再按结构特征全网扫描：
     *   静态 g() 返回自身 + 实例方法 j(String,int):int 和 u(String,int):void
     *   —— 同时具备这三个方法的类极少，足以唯一定位。
     */
    private static Class<?> findStoreClass(ClassLoader cl) throws ClassNotFoundException {
        Class<?> cached = sStoreClass;
        if (cached != null) {
            return cached;
        }
        for (String n : STORE_HINTS) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                if (matchesStore(c)) {
                    sStoreClass = c;
                    return c;
                }
            } catch (Throwable ignored) {
            }
        }
        // 按特征扫描：从宿主 ClassLoader 的所有 dex 里枚举类名，逐个结构判定。
        Class<?> found = scanDexForStore(cl);
        if (found != null) {
            sStoreClass = found;
            return found;
        }
        throw new ClassNotFoundException("store class not found by signature");
    }

    /** 已发现的档位控制类（原 ms.n），及其内部枚举（原 ms.n$c）。 */
    private static volatile Class<?> sScaleClass = null;
    private static volatile Class<?> sScaleEnum = null;

    /** 已知的混淆名，命中即用；未命中再按特征扫描。 */
    private static final String[] SCALE_HINTS = {"ms.n"};

    /**
     * 找到档位控制类（9.54.1 里仍是 ms.n，但不能指望它永远不变）。
     *
     * 特征：静态 int 字段 + 静态 boolean 字段 + 无参实例方法 a() 返回 int。
     * 同时满足这三条、且内部含一个名字形如 X$c 的枚举的类极少。
     */
    private static Class<?> findScaleClass(ClassLoader cl) throws ClassNotFoundException {
        Class<?> cached = sScaleClass;
        if (cached != null) {
            return cached;
        }
        for (String n : SCALE_HINTS) {
            try {
                Class<?> c = Class.forName(n, false, cl);
                if (matchesScale(c)) {
                    sScaleClass = c;
                    sScaleEnum = findScaleEnum(c, cl);
                    return c;
                }
            } catch (Throwable ignored) {
            }
        }
        Class<?> found = scanDexForScale(cl);
        if (found != null) {
            sScaleClass = found;
            sScaleEnum = findScaleEnum(found, cl);
            return found;
        }
        throw new ClassNotFoundException("scale class not found by signature");
    }

    /** 结构判定：静态 int + 静态 boolean + 实例 a()I。 */
    private static boolean matchesScale(Class<?> c) {
        try {
            boolean hasInt = false;
            boolean hasBool = false;
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType() == int.class) {
                    hasInt = true;
                } else if (f.getType() == boolean.class) {
                    hasBool = true;
                }
            }
            if (!hasInt || !hasBool) {
                return false;
            }
            java.lang.reflect.Method a = c.getDeclaredMethod("a");
            return a.getReturnType() == int.class
                    && !java.lang.reflect.Modifier.isStatic(a.getModifiers());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取内部那个档位枚举类（含 NORMAL 常量）。 */
    private static Class<?> findScaleEnum(Class<?> scaleCls, ClassLoader cl) {
        try {
            for (Class<?> inner : scaleCls.getDeclaredClasses()) {
                if (!inner.isEnum()) {
                    continue;
                }
                try {
                    inner.getField("NORMAL");
                    return inner;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 遍历 dex 找档位控制类。 */
    private static Class<?> scanDexForScale(ClassLoader cl) {
        for (Class<?> cand : iterateDexClasses(cl)) {
            try {
                if (matchesScale(cand)) {
                    XposedModule m = sModule;
                    if (m != null) {
                        if (sDebug) {
                            m.log(Log.INFO, TAG, "scale class found by scan: " + cand.getName());
                        }
                    }
                    return cand;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 结构判定：静态单例 g() + j(String,int)I + u(String,int)V。 */
    private static boolean matchesStore(Class<?> c) {
        try {
            java.lang.reflect.Method g = c.getDeclaredMethod("g");
            if (!java.lang.reflect.Modifier.isStatic(g.getModifiers())) {
                return false;
            }
            if (g.getReturnType() != c) {
                return false;
            }
            c.getDeclaredMethod("j", String.class, int.class);
            c.getDeclaredMethod("u", String.class, int.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 遍历宿主 ClassLoader 的所有 dex，返回其中能被 Class.forName 加载的类。
     * 走 BaseDexClassLoader → pathList → dexElements → DexFile.entries()，
     * 只读类名表，不执行任何应用代码。
     */
    private static java.util.List<Class<?>> iterateDexClasses(ClassLoader cl) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        try {
            java.lang.reflect.Field pf = null;
            Class<?> c = cl.getClass();
            while (c != null) {
                try {
                    pf = c.getDeclaredField("pathList");
                    break;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (pf == null) {
                return out;
            }
            pf.setAccessible(true);
            Object pathList = pf.get(cl);
            java.lang.reflect.Field ef = pathList.getClass().getDeclaredField("dexElements");
            ef.setAccessible(true);
            Object[] elements = (Object[]) ef.get(pathList);
            for (Object el : elements) {
                java.lang.reflect.Field df = el.getClass().getDeclaredField("dexFile");
                df.setAccessible(true);
                Object dexFile = df.get(el);
                if (dexFile == null) {
                    continue;
                }
                java.lang.reflect.Method entries = dexFile.getClass()
                        .getDeclaredMethod("entries");
                entries.setAccessible(true);
                java.util.Enumeration<?> en = (java.util.Enumeration<?>) entries.invoke(dexFile);
                while (en.hasMoreElements()) {
                    try {
                        String name = String.valueOf(en.nextElement());
                        // 描述符形如 "La/b/C;"。9.55.0 的部分 dex 里混进了长度不足 3 的
                        // 条目（如 "L"），substring(1, len-1) 会抛
                        // StringIndexOutOfBoundsException: begin 1, end 0 —— 这个异常
                        // 在循环里没有兜住，直接把整个遍历打断，后面所有 dex 的类都没被
                        // 检查过，于是「按特征扫描」永远找不到存储类。
                        // 两处都要修：短条目跳过 + 单条目异常不外溢。
                        if (name.length() < 3 || name.charAt(0) != 'L' || !name.endsWith(";")) {
                            continue;
                        }
                        if (name.startsWith("Ljava/") || name.startsWith("Lkotlin/")
                                || name.startsWith("Landroid/") || name.startsWith("Landroidx/")) {
                            continue;
                        }
                        String cn = name.substring(1, name.length() - 1).replace('/', '.');
                        if (cn.indexOf('$') >= 0) {
                            continue;
                        }
                        try {
                            out.add(Class.forName(cn, false, cl));
                        } catch (Throwable ignored) {
                        }
                    } catch (Throwable ignored) {
                        // 单个条目的任何异常都不能中断整轮扫描。
                    }
                }
            }
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) m.log(Log.WARN, TAG, "iterateDexClasses failed: " + t);
        }
        return out;
    }

    /** 遍历 dex 找存储类。 */
    private static Class<?> scanDexForStore(ClassLoader cl) {
        for (Class<?> cand : iterateDexClasses(cl)) {
            try {
                if (matchesStore(cand)) {
                    XposedModule m = sModule;
                    if (m != null) {
                        if (sDebug) {
                            m.log(Log.INFO, TAG, "store class found by scan: " + cand.getName());
                        }
                    }
                    return cand;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }



    /** 内部改写 key 后自我调用时的递归保护，防止 hook 重入死循环。 */
    private static final ThreadLocal<Boolean> sInSpHook =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 当前所在屏的短边像素。
     *
     * 实测（v2.0.4 日志）：Application/Resources.getSystem() 的 DisplayMetrics 在折叠换屏后
     * 不一定会刷新，导致内屏也被判成 outer —— 于是两边读写同一个 key，表现就是「列数强制同步」。
     * Activity 的 Resources 是跟着它自己的显示配置走的，所以在 onResume/onConfigurationChanged
     * 里用 Activity 的 metrics 记一次，判定优先用它。
     */
    private static volatile int sScreenShort = 0;

    /** 当前屏的宽度像素（横竖屏不同）。小红书算列数用的是它，不是短边。 */
    private static volatile int sScreenWidth = 0;

    /** 当前屏的高度像素（横屏时是短边）。 */
    private static volatile int sScreenHeight = 0;

    /**
     * 当前是否竖屏。
     *
     * 用户的要求：竖屏各自记忆列数，横屏不要被模块影响，交给小红书自己默认走。
     * 所以所有「按屏改写档位 / 强制列数」的动作都只在竖屏下做。
     */
    private static volatile boolean sPortrait = true;

    /** 上次读到档位时所处的屏幕；-1 表示尚未读过。 */
    private static volatile int sCachedTag = -1;

    /** 内/外屏短边的分界值（最窄与最宽屏的中点）；0 表示尚未算出。 */
    private static volatile int sDivider = 0;

    /**
     * 用两块内置屏的真实短边算出分界值：最窄的是外屏、最宽的是内屏，取两者中点。
     * 不写死任何尺寸，换机型或改分辨率都自动适应。
     *
     * Context 取自 q3e.h 单例的 e 字段 —— 模块启动时还没有 Activity，
     * 但小红书的存储封装类自己就持有 Application Context。
     *
     * @return 分界值；拿不到两块屏时返回 0
     */
    private static int divider(ClassLoader cl) {
        int cached = sDivider;
        if (cached > 0) {
            return cached;
        }
        try {
            Class<?> h = findStoreClass(cl);
            Object inst = h.getMethod("g").invoke(null);
            // Context 字段：9.54 是实例字段 e，9.55.0 改成了 **静态** 字段
            //   public static e Landroid/content/Context;
            // 静态字段取值时 target 必须是 null，传实例会抛
            // IllegalArgumentException，整个 divider 失败 → 分界值恒为 0 →
            // screenTag 恒判定为「外屏」→ 内外屏档位又合并成一份。
            android.content.Context ctx = null;
            try {
                java.lang.reflect.Field fc = h.getDeclaredField("e");
                fc.setAccessible(true);
                Object target = java.lang.reflect.Modifier.isStatic(fc.getModifiers()) ? null : inst;
                ctx = (android.content.Context) fc.get(target);
            } catch (Throwable t0) {
                XposedModule mod0 = sModule;
                if (mod0 != null) mod0.log(Log.WARN, TAG, "store context field failed: " + t0);
            }
            if (ctx == null) {
                ctx = currentContext();
            }
            if (ctx == null) {
                return 0;
            }

            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(android.content.Context.DISPLAY_SERVICE);
            int lo = Integer.MAX_VALUE;
            int hi = 0;
            // getDisplays() 只返回当前可见的屏（折叠屏上通常只有 1 块），
            // 必须用 ALL_INCLUDING_DISABLED 才能同时看到内屏和外屏。
            android.view.Display[] all =
                    // 该常量是 @hide，公开 SDK 里没有，直接用它的字符串值。
                    dm.getDisplays("android.hardware.display.category.ALL_INCLUDING_DISABLED");
            for (android.view.Display d : all) {
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                d.getRealMetrics(m);
                int shortSide = Math.min(m.widthPixels, m.heightPixels);
                lo = Math.min(lo, shortSide);
                hi = Math.max(hi, shortSide);
            }
            if (lo >= hi) {
                return 0;   // 只有一块屏，无从比较
            }
            sDivider = lo + (hi - lo) / 2;
            XposedModule mod = sModule;
            if (mod != null) {
                if (sDebug) {
                    mod.log(Log.INFO, TAG, "divider=" + sDivider + " (" + lo + "~" + hi + ")");
                }
            }
            return sDivider;
        } catch (Throwable t) {
            XposedModule mod = sModule;
            if (mod != null) mod.log(Log.ERROR, TAG, "divider failed: " + t);
            return 0;
        }
    }

    /**
     * 判定当前处于内屏还是外屏。
     *
     * 不能用 sw/dp：DPI 压到 350 后外屏 sw=534dp、内屏 sw=764dp，两屏都跨过 600dp，
     * 小红书因此判成同一块 —— 这才是「内外屏排版被拉平」的根源。
     * 改用屏幕短边像素与真实分界值比较，与 DPI 取值无关。
     *
     * @return 1 = 内屏，0 = 外屏
     */
    private static volatile int sLastTag = -1;
    private static volatile int sLastSpan = -1;
    private static volatile int sSpanTag = -1;

    private static int screenTag(ClassLoader cl) {
        int shortSide = currentShortSidePx();
        int divider = divider(cl);
        int tag = divider > 0 && shortSide >= divider ? 1 : 0;
        if (tag != sLastTag) {
            sLastTag = tag;
            XposedModule m = sModule;
            if (m != null) {
                if (sDebug) {
                    m.log(Log.INFO, TAG, "screenTag -> " + (tag == 1 ? "inner" : "outer")
                            + " shortSide=" + shortSide + " divider=" + divider);
                }
            }
        }
        return tag;
    }

    /**
     * 当前所在一屏的短边像素。
     *
     * 优先用「当前进程的 Resources」的 DisplayMetrics —— 折叠屏换屏时系统会刷新
     * 应用的 Configuration，这个值跟着当前 Display 走。Resources.getSystem() 拿到的是
     * 系统资源的度量，在部分 ROM 上不随换屏刷新，会一直停在主屏（内屏）的尺寸，
     * 那样切到外屏也判成内屏。两级都有兜底，都是为了得到「当前屏」而不是「开机那屏」。
     */
    private static int currentShortSidePx() {
        int fromActivity = sScreenShort;
        if (fromActivity > 0) {
            return fromActivity;
        }
        android.content.Context ctx = currentContext();
        if (ctx != null) {
            try {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                if (dm != null && dm.widthPixels > 0) {
                    return Math.min(dm.widthPixels, dm.heightPixels);
                }
            } catch (Throwable ignored) {
            }
        }
        android.util.DisplayMetrics sys =
                android.content.res.Resources.getSystem().getDisplayMetrics();
        return Math.min(sys.widthPixels, sys.heightPixels);
    }

    /** 读 /data/local/tmp/xhsfullscreen.conf（key=value，一行一条，缺项用默认值）。 */
    private void loadConf() {
        try {
            java.io.File f = new java.io.File(CONF_PATH);
            if (!f.exists()) {
                recomputeCombo();
                if (sDebug) {
                    log(Log.INFO, TAG, "conf absent, defaults tree=" + sTree + " fill=" + sFill
                            + " pad=" + sPad + " margin=" + sMargin);
                }
                return;
            }
            java.util.Properties p = new java.util.Properties();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
            sConf = p;
            sTree = boolConf(p, "tree", DEBUG_TREE);
            sFill = boolConf(p, "fill", true);
            sPad = intConf(p, "pad", PAD_TARGET);
            sMargin = intConf(p, "margin", 22);
            sBottom = intConf(p, "bottom", 110);
            sTitle = intConf(p, "title", 150);
            sSpanForce = boolConf(p, "spanforce", true);
            sDebug = boolConf(p, "debug", false);
            recomputeCombo();
            if (sDebug) {
                log(Log.INFO, TAG, "conf tree=" + sTree + " fill=" + sFill
                        + " pad=" + sPad + " margin=" + sMargin);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "loadConf failed: " + t);
        }
    }

    private static boolean boolConf(java.util.Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        if (v == null) {
            return def;
        }
        v = v.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static int intConf(java.util.Properties p, String k, int def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 用 Configuration 更新「当前在哪块屏、横还是竖、宽度多少」。
     *
     * Configuration.screenWidthDp/screenHeightDp 已经按当前方向给值
     * （横屏时 screenWidthDp > screenHeightDp），densityDpi 换 px 与 DPI 设置无关，
     * 所以这三个量在任何时机都是可信的 —— 不像 DisplayMetrics 会滞后一帧。
     */
    private static void updateFromConfig(Configuration cfg) {
        if (cfg == null) {
            return;
        }
        try {
            float density = cfg.densityDpi / 160f;
            if (density <= 0f) {
                return;
            }
            int wDp = cfg.screenWidthDp;
            int hDp = cfg.screenHeightDp;
            boolean portrait = cfg.orientation == Configuration.ORIENTATION_PORTRAIT;
            if (wDp > 0 && hDp > 0 && wDp != hDp) {
                portrait = hDp >= wDp;
            }
            int widthPx = Math.round((portrait ? Math.min(wDp, hDp) : Math.max(wDp, hDp)) * density);
            int heightPx = Math.round((portrait ? Math.max(wDp, hDp) : Math.min(wDp, hDp)) * density);
            int shortPx = Math.round(Math.min(wDp, hDp) * density);
            boolean changed = portrait != sPortrait || shortPx != sScreenShort
                    || widthPx != sScreenWidth;
            sPortrait = portrait;
            sScreenWidth = widthPx;
            sScreenHeight = heightPx;
            sScreenShort = shortPx;
            if (changed) {
                sCachedTag = -1;    // 换屏/换方向后重新按当前屏读一次档位
            }
            recomputeCombo();       // 组合参数按当前屏/方向取一次(几个 Properties 查表,便宜)
            if (changed) {
                XposedModule m = sModule;
                if (m != null) {
                    if (sDebug) {
                        m.log(Log.INFO, TAG, "screen state -> short=" + shortPx
                                + " width=" + widthPx
                                + " " + (portrait ? "portrait" : "landscape")
                                + " (cfg " + wDp + "x" + hDp + " @" + cfg.densityDpi + ")");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前屏宽度像素（横屏时是大的一边）。 */
    private static int currentWidthPx() {
        int fromActivity = sScreenWidth;
        if (fromActivity > 0) {
            return fromActivity;
        }
        android.content.Context ctx = currentContext();
        if (ctx != null) {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return dm.widthPixels;
        }
        return currentShortSidePx();
    }

    /** 当前屏高度像素（横屏时是短边）。 */
    private static int currentScreenHeightPx() {
        int fromActivity = sScreenHeight;
        if (fromActivity > 0) {
            return fromActivity;
        }
        android.content.Context ctx = currentContext();
        if (ctx != null) {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return dm.heightPixels;
        }
        return 0;
    }

    private static boolean isPortrait() {
        if (sScreenWidth > 0) {
            return sPortrait;
        }
        android.content.Context ctx = currentContext();
        if (ctx != null) {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return dm.heightPixels >= dm.widthPixels;
        }
        return true;
    }

    /** 当前进程的 Application Context（模块启动时还没有 Activity）。 */
    private static android.content.Context sAppContext = null;

    private static android.content.Context currentContext() {
        android.content.Context cached = sAppContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cur = at.getDeclaredMethod("currentApplication");
            cur.setAccessible(true);
            Object app = cur.invoke(null);
            if (app instanceof android.content.Context) {
                sAppContext = (android.content.Context) app;
                return sAppContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 小红书原始的档位 key → 按屏区分后的 key。其它 key 原样返回。 */
    private static String taggedKey(String key, ClassLoader cl) {
        if (!SP_KEY.equals(key)) {
            return key;
        }
        // 横屏用原 key，让小红书走它自己那套默认值，不做按屏区分。
        if (!isPortrait()) {
            return key;
        }
        return SP_KEY + (screenTag(cl) == 1 ? "_inner" : "_outer");
    }

    /**
     * h.j(String,int)I 读取 / h.u(String,int)V 写入 —— 都走这里按屏换 key。
     *
     * 两处都不能用 chain.proceedWith()：它要求 thisObject 精确等于声明类 q3e.h，
     * 而实际实例是 MMKV 包装的子类，会抛 IllegalArgumentException
     * → 连带 ms.n.<clinit> 失败 → 小红书启动即闪退。
     * 因此改在同一个实例上用反射重放。
     */
    private void installSplitScreenScale(ClassLoader cl) {
        try {
            Class<?> h = findStoreClass(cl);

            // 读取：j(String, int) -> int
            try {
                Method j = h.getDeclaredMethod("j", String.class, int.class);
                j.setAccessible(true);
                hook(j).intercept(chain -> {
                    List<Object> orig = chain.getArgs();
                    String nk = taggedKey((String) orig.get(0), cl);
                    if (nk.equals(orig.get(0)) || Boolean.TRUE.equals(sInSpHook.get())) {
                        return chain.proceed();
                    }
                    sInSpHook.set(Boolean.TRUE);
                    try {
                        return j.invoke(chain.getThisObject(), nk, orig.get(1));
                    } finally {
                        sInSpHook.set(Boolean.FALSE);
                    }
                });
                if (sDebug) {
                    log(Log.INFO, TAG, "hooked SP read j(String,int)");
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook SP read failed: " + t);
            }

            // 写入：u(String, int) -> void
            try {
                Method u = h.getDeclaredMethod("u", String.class, int.class);
                u.setAccessible(true);
                hook(u).intercept(chain -> {
                    List<Object> orig = chain.getArgs();
                    if (sTree) {
                        // 只记和「排数/缩放」相关的写入，否则登录态、版本号之类
                        // 的键会把日志刷满，真正有用的那行找不到。
                        String k = String.valueOf(orig.get(0)).toLowerCase(java.util.Locale.ROOT);
                        if (k.contains("scale") || k.contains("span") || k.contains("column")
                                || k.contains("grid") || k.contains("feed_layout")
                                || k.contains("refresh")) {
                            XposedModule mod = sModule;
                            if (mod != null) {
                                if (sDebug) {
                                    mod.log(Log.INFO, TAG, "SPW " + orig.get(0) + " = " + orig.get(1));
                                }
                            }
                        }
                    }
                    String nk = taggedKey((String) orig.get(0), cl);
                    if (nk.equals(orig.get(0)) || Boolean.TRUE.equals(sInSpHook.get())) {
                        return chain.proceed();
                    }
                    sInSpHook.set(Boolean.TRUE);
                    try {
                        u.invoke(chain.getThisObject(), nk, orig.get(1));
                    } finally {
                        sInSpHook.set(Boolean.FALSE);
                    }
                    return null;
                });
                if (sDebug) {
                    log(Log.INFO, TAG, "hooked SP write u(String,int)");
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook SP write failed: " + t);
            }

            // 静态字段 ms.n.d 只在 <clinit> 读一次并缓存，切屏不会重读。
            // 这里 hook a()：发现「当前屏」与「缓存所属屏」不一致，就按当前屏
            // 重新从 SP 读一次写回 d —— 走它自己的存储路径，没有额外状态。
            try {
                final Class<?> msn = findScaleClass(cl);
                final java.lang.reflect.Field fd = msn.getDeclaredField("d");
                fd.setAccessible(true);
                Method a = msn.getDeclaredMethod("a");
                a.setAccessible(true);
                hook(a).intercept(chain -> {
                    if (!isPortrait()) {
                        // 横屏：用小红书自己的那份档位（plain key）。
                        //
                        // 之前这里只是「不干预」，但静态字段 d 还留着上一个屏的档位，
                        // 于是第一次转进横屏会拿着外屏的档位去算列数（实测 3 列），
                        // 转两次才对 —— 因为中途竖屏把 d 换回了内屏的值。
                        // 横屏必须显式把 d 还原成 plain key 的值，结果才与来源屏无关。
                        int plain = readScalePlain(cl);
                        try {
                            if (fd.getInt(null) != plain) {
                                fd.setInt(null, plain);
                            }
                        } catch (Throwable ignored) {
                        }
                        sCachedTag = -1;
                        return chain.proceed();
                    }
                    int tag = screenTag(cl);
                    if (tag != sCachedTag) {
                        sCachedTag = tag;
                        int v = readScaleForScreen(cl);
                        try {
                            fd.setInt(null, v);
                        } catch (Throwable ignored) {
                        }
                        if (sDebug) {
                            log(Log.INFO, TAG, "switch screen -> reload scale, tag="
                                    + (tag == 1 ? "inner" : "outer") + " value=" + v);
                        }
                        applyFeedSpan(cl, "scale a() tag change", false);
                    }
                    return chain.proceed();
                });
                if (sDebug) {
                    log(Log.INFO, TAG, "hooked scale a() for per-screen scale refresh");
                }

                // 诊断：spanCount 的真实计算入口 f(Context)I，把它每次的返回值打出来，
                // 就能直接看到「这一屏 XHS 认为是几列」，不用再猜。
                try {
                    Method f = msn.getDeclaredMethod("f", android.content.Context.class);
                    f.setAccessible(true);
                    hook(f).intercept(chain -> {
                        Object r = chain.proceed();
                        if (r instanceof Integer) {
                            int span = (Integer) r;
                            int tag = screenTag(cl);
                            // 只在「屏幕/列数」真的变化时打一行，避免刷屏
                            if (span != sLastSpan || tag != sSpanTag) {
                                sLastSpan = span;
                                sSpanTag = tag;
                                if (sDebug) {
                                    log(Log.INFO, TAG, "SPAN screen="
                                            + (tag == 1 ? "inner" : "outer")
                                            + " scale=" + safeScale(cl)
                                            + " spanCount=" + span);
                                }
                            }
                        }
                        return r;
                    });
                    if (sDebug) {
                        log(Log.INFO, TAG, "hooked ms.n.f(Context) for spanCount log");
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "hook spanCount f() failed: " + t);
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook scale a() failed: " + t);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "installSplitScreenScale failed: " + t);
        }
    }

    /** 读取当前缓存的档位（只读静态字段 d，不触发 hook 递归）。 */
    private static int safeScale(ClassLoader cl) {
        try {
            Class<?> c = sScaleClass;
            if (c == null) {
                c = findScaleClass(cl);
            }
            java.lang.reflect.Field fd = c.getDeclaredField("d");
            fd.setAccessible(true);
            return fd.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ==================== 换屏后重算信息流列数 ====================

    /** 信息流用的 LayoutManager（弱引用，避免拖住 Activity）。 */
    private static final java.util.List<java.lang.ref.WeakReference<Object>> sFeedLms =
            new java.util.ArrayList<>();

    private static void rememberLm(Object lm) {
        if (lm == null) {
            return;
        }
        String cn = lm.getClass().getName();
        if (!cn.contains("GridLayoutManager") && !cn.contains("StaggeredGridLayoutManager")) {
            return;
        }
        synchronized (sFeedLms) {
            for (java.lang.ref.WeakReference<Object> ref : sFeedLms) {
                if (ref.get() == lm) {
                    return;
                }
            }
            sFeedLms.add(new java.lang.ref.WeakReference<>(lm));
        }
    }

    private void installFeedSpanForce(ClassLoader cl) {
        // 记录所有被装到 RecyclerView 上的网格布局管理器
        try {
            Class<?> rv = Class.forName("androidx.recyclerview.widget.RecyclerView", false, cl);
            Class<?> lmCls = Class.forName("androidx.recyclerview.widget.RecyclerView$LayoutManager", false, cl);
            Method slm = rv.getDeclaredMethod("setLayoutManager", lmCls);
            slm.setAccessible(true);
            hook(slm).intercept(chain -> {
                // 刚设进来的那个 LayoutManager 就是参数，不必再反射 getLayoutManager()
                // （每次 setLayoutManager 都做一次反射查找，纯浪费）。
                try {
                    rememberLm(chain.getArgs().get(0));
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            if (sDebug) {
                log(Log.INFO, TAG, "hooked RecyclerView.setLayoutManager (feed span tracking)");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook setLayoutManager failed: " + t);
        }

        // 兜底：setSpanCount 被调用时也记一下（有的列表不经过 setLayoutManager）
        for (String cn : new String[]{
                "androidx.recyclerview.widget.GridLayoutManager",
                "androidx.recyclerview.widget.StaggeredGridLayoutManager"}) {
            try {
                Class<?> c = Class.forName(cn, false, cl);
                Method m = c.getDeclaredMethod("setSpanCount", int.class);
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    rememberLm(chain.getThisObject());
                    return chain.proceed();
                });
                if (sDebug) {
                    log(Log.INFO, TAG, "hooked " + cn + ".setSpanCount");
                }
            } catch (Throwable t) {
                if (sDebug) {
                    log(Log.INFO, TAG, "skip " + cn + ": " + t);
                }
            }
        }
    }

    /**
     * 临时摘掉 RecyclerView 的条目动画，返回「装回去」的 Runnable。
     * 模块编译时没有 androidx 依赖，全部走反射。
     */
    private static Runnable suppressItemAnimations(Object lm) {
        try {
            Object rv = null;
            Class<?> c = lm.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("mRecyclerView");
                    f.setAccessible(true);
                    rv = f.get(lm);
                    break;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (rv == null) {
                return null;
            }
            final Object rvF = rv;
            Method getAnim = rv.getClass().getMethod("getItemAnimator");
            final Object anim = getAnim.invoke(rv);
            if (anim == null) {
                return null;
            }
            for (Method m : rv.getClass().getMethods()) {
                if (m.getName().equals("setItemAnimator") && m.getParameterTypes().length == 1) {
                    m.invoke(rv, new Object[]{null});
                    break;
                }
            }
            return () -> {
                try {
                    for (Method m : rvF.getClass().getMethods()) {
                        if (m.getName().equals("setItemAnimator") && m.getParameterTypes().length == 1) {
                            m.invoke(rvF, anim);
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前屏「应有」的列数：直接借小红书自己的 e(屏宽px) 算，不自己编映射。 */
    private static int desiredSpan(ClassLoader cl) {
        try {
            Class<?> msn = findScaleClass(cl);
            Object inst = msn.getField("a").get(null);
            Method e = msn.getDeclaredMethod("e", int.class);
            e.setAccessible(true);
            Object v = e.invoke(inst, currentWidthPx());
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) {
                m.log(Log.WARN, TAG, "desiredSpan failed: " + t);
            }
            return -1;
        }
    }

    /**
     * 换屏后调用：把记下来的网格 LayoutManager 的列数改成当前屏应有的值。
     * 延迟 400ms，等新窗口布局完成后再改，避免和正在进行的测量打架。
     */
    private static final android.os.Handler sMain =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * 换屏后把信息流列数改成当前屏应有的值。
     *
     * ⚠ 时机很关键：v2.1.1 是延迟 400ms 再改，结果新屏先按旧列数画了一帧、
     * 再"啪"一下换成正确列数 —— 肉眼就是切屏时闪一下。
     * 现在改成在 `onConfigurationChanged` 里**同步**改（此时新窗口还没布局），
     * 第一帧就是对的；再补一次 150ms 后的幂等复检，防止列表随后被重建。
     */
    private static void applyFeedSpan(final ClassLoader cl, final String why, final boolean immediate) {
        if (!fSpanForce) {
            return;
        }
        // 横屏交给小红书自己：它按真实宽度分档，我们不去覆盖。
        if (!isPortrait()) {
            return;
        }
        final int want;
        try {
            want = desiredSpan(cl);
        } catch (Throwable t) {
            return;
        }
        if (want <= 0) {
            return;
        }
        final Runnable task = () -> {
            int changed = 0;
            synchronized (sFeedLms) {
                for (java.util.Iterator<java.lang.ref.WeakReference<Object>> it =
                     sFeedLms.iterator(); it.hasNext(); ) {
                    Object lm = it.next().get();
                    if (lm == null) {
                        it.remove();
                        continue;
                    }
                    try {
                        Method get = lm.getClass().getMethod("getSpanCount");
                        int cur = (Integer) get.invoke(lm);
                        if (cur == want) {
                            continue;
                        }
                        // 改列数会让列表重排，如果开着条目动画，肉眼看就是"闪一下"。
                        // 改之前把 ItemAnimator 摘掉，布局稳定后再装回去。
                        Runnable restore = suppressItemAnimations(lm);
                        Method set = lm.getClass().getMethod("setSpanCount", int.class);
                        set.invoke(lm, want);
                        if (restore != null) {
                            sMain.postDelayed(restore, 250L);
                        }
                        changed++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            XposedModule m = sModule;
            if (m != null && changed > 0) {
                if (sDebug) {
                    m.log(Log.INFO, TAG, "SPAN forced " + changed + " list(s) -> " + want
                            + " (" + why + (immediate ? ", sync" : ", posted") + ")");
                }
            }
        };
        try {
            if (immediate && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                task.run();               // 在 onConfigurationChanged 里同步改，赶在新布局之前
            } else {
                sMain.post(task);
            }
            sMain.postDelayed(task, 150L); // 幂等复检：值已经对了就什么都不做
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) {
                m.log(Log.WARN, TAG, "applyFeedSpan failed: " + t);
            }
        }
    }

    /** 读小红书自己那份档位（plain key），横屏时用它。 */
    private static int readScalePlain(ClassLoader cl) {
        try {
            Class<?> h = findStoreClass(cl);
            Object inst = h.getMethod("g").invoke(null);
            Method j = h.getDeclaredMethod("j", String.class, int.class);
            j.setAccessible(true);
            findScaleClass(cl);
            Object normal = sScaleEnum.getField("NORMAL").get(null);
            Object v = j.invoke(inst, SP_KEY, ((Enum<?>) normal).ordinal());
            return ((Integer) v).intValue();
        } catch (Throwable t) {
            return 1;   // NORMAL
        }
    }

    /**
     * 按当前屏从 SP 读档位。
     *
     * 默认值取 ms.n$c.NORMAL，实测 ordinal = 1（ZOOM_IN=0, NORMAL=1, ZOOM_OUT=2）。
     * 运行时读枚举而不写死，免得小红书调整枚举顺序后取错档位。
     */
    private static int readScaleForScreen(ClassLoader cl) {
        try {
            Class<?> h = findStoreClass(cl);
            Object inst = h.getMethod("g").invoke(null);
            Method j = h.getDeclaredMethod("j", String.class, int.class);
            j.setAccessible(true);
            findScaleClass(cl);
            Object normal = sScaleEnum.getField("NORMAL").get(null);
            Object v = j.invoke(inst, taggedKey(SP_KEY, cl), ((Enum<?>) normal).ordinal());
            return ((Integer) v).intValue();
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) m.log(Log.ERROR, TAG, "readScaleForScreen failed: " + t);
            return 0;
        }
    }
}
