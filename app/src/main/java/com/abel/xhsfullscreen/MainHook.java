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

    // 逐帧观测开关：记录每帧绘制时 padding 的真实值，用于验证
    // 「打开帖子前边距就已定好」。定稿后可关掉。
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
    //   tree=1        打印详情页视图树（定位用）
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
        log(Log.INFO, TAG, "onPackageLoaded: " + param.getPackageName());
        if (!XHS.equals(param.getPackageName())) {
            return;
        }
        log(Log.INFO, TAG, "hooked into XHS, installing hooks (v1.9.0)");
        sModule = this;
        loadConf();

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
                            log(Log.INFO, TAG, "pre-hooked setters: " + cn);
                        }
                    }
                } catch (Throwable t) {
                    log(Log.INFO, TAG, "pre-hook skipped " + cn + ": " + t);
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
                log(Log.INFO, TAG, "hooked wj9.c0.y0 (source-level normalize)");
            } catch (Throwable t) {
                // 这个方法名属于 9.50~9.54 的混淆名，9.55.0 已经不叫这个名字
                // （NoSuchMethodException）。它是「提速」而不是「必需」：
                // 真正保证首帧正确的还是 setter 预注册 + PreDraw 守卫，
                // 所以这里降级为 INFO，不再当错误刷日志。
                log(Log.INFO, TAG, "wj9.c0.y0 not present in this version: " + t);
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
                                        m.log(Log.INFO, TAG, "CTOR " + cn
                                                + " initialPad=" + pl);
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
                    log(Log.INFO, TAG, "hooked ctors: " + cn);
                } catch (Throwable t) {
                    log(Log.INFO, TAG, "ctor hook skipped " + cn + ": " + t);
                }
            }
        }
    }

    private void hookActivityLifecycle() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                try {
                    scheduleFix((Activity) chain.getThisObject());
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
                    scheduleFix((Activity) chain.getThisObject());
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
                    int want = isOuterContainer(cn) ? 0 : sPad;
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

    /** 完整调用栈，用于定位真正的赋值入口（不做任何裁剪）。 */
    private static String fullStack() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement e : st) {
                sb.append("      ").append(e.getClassName()).append('.')
                  .append(e.getMethodName()).append(':').append(e.getLineNumber()).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "      <stack failed>";
        }
    }

    /** 只保留与小红书自身业务相关的调用帧，最多 8 层，避免日志刷屏。 */
    private static String shortStack() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                // 跳过框架与自身
                if (c.startsWith("android.") || c.startsWith("java.")
                        || c.startsWith("com.android.") || c.startsWith("io.github.libxposed")
                        || c.startsWith(MainHook.class.getName())
                        || c.startsWith("LSPosed") || c.contains("XposedBridge")
                        || c.contains("$Proxy") || c.contains("LSP"))
                    continue;
                sb.append("    ").append(c).append('.').append(e.getMethodName())
                  .append(':').append(e.getLineNumber()).append('\n');
                if (++n >= 8) break;
            }
            return sb.length() == 0 ? "    (no app frames)" : sb.toString();
        } catch (Throwable t) {
            return "    <stack failed>";
        }
    }

    /**
     * 探测某个类的方法签名，用于把混淆名映射到真实签名。
     * 只打印名字里带 "y0"/"c0" 嫌疑的类，控制日志量。
     */
    private void probeClass(String className, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            log(Log.INFO, TAG, "PROBE " + className + " methods:");
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder();
                sb.append("    ").append(m.getReturnType().getSimpleName())
                  .append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(')');
                log(Log.INFO, TAG, sb.toString());
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "probe " + className + " failed: " + t);
        }
    }

    private void scheduleFix(final Activity a) {
        try {
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
                    log(Log.INFO, TAG, "guard on (fixView): " + cn);
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
                    : (needsGutter(cn) ? sPad : v.getPaddingLeft());
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
            m.log(Log.INFO, TAG, "TREE " + why
                    + " screenShort=" + currentShortSidePx()
                    + " v=" + v.getWidth() + "x" + v.getHeight());
            dumpNode(root, 0, m, lines);
            m.log(Log.INFO, TAG, "TREE end lines=" + lines[0]);
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
    private static void fitGallery(View display) {
        if (!sFill || !(display instanceof ViewGroup)) {
            return;
        }
        try {
            fitGalleryIn((ViewGroup) display, display, 0);
        } catch (Throwable t) {
            XposedModule m = sModule;
            if (m != null) {
                m.log(Log.WARN, TAG, "FIT failed: " + t);
            }
        }
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
                fitOneGallery(c, display);
            } else if (c instanceof ViewGroup && c.getVisibility() == View.VISIBLE) {
                fitGalleryIn((ViewGroup) c, display, depth + 1);
            }
        }
    }

    /** 缓存在 View 上的自适应结果 {w, h, 屏幕短边, 原生宽高比*10000}。 */
    private static final int TAG_FIT = 0x584853_46;   // 'XHSF'

    private static void fitOneGallery(View gal, View display) {
        ViewGroup.LayoutParams lp = gal.getLayoutParams();
        if (lp == null) {
            return;
        }
        int shortSide = currentShortSidePx();
        Object cached = gal.getTag(TAG_FIT);
        int[] box = cached instanceof int[] ? (int[]) cached : null;

        float ratio;                     // 宽 / 高
        if (box != null && lp.width == box[0] && lp.height == box[1]) {
            // lp 还停在我们自己写进去的那一对 → 这是同一张图，沿用最初的原生比例。
            if (box[2] == shortSide) {
                return;                  // 屏没变、尺寸已是目标值，什么都不做
            }
            ratio = box[3] / 10000f;
        } else if (lp.width > 0 && lp.height > 0) {
            // 小红书按图片原生比例算出的宽高（这就是比例的来源）
            ratio = (float) lp.width / (float) lp.height;
        } else if (gal.getWidth() > 0 && gal.getHeight() > 0) {
            ratio = (float) gal.getWidth() / (float) gal.getHeight();
        } else {
            return;
        }
        if (ratio <= 0.05f || ratio > 20f) {
            return;
        }

        int margin = sMargin;
        int availW = display.getWidth() - margin * 2;
        int availH = availableHeight(gal) - sBottom - margin * 2;
        if (availW <= 0 || availH <= 0) {
            return;
        }
        int w = availW;
        int h = Math.round(w / ratio);
        if (h > availH) {
            h = availH;
            w = Math.round(h * ratio);
        }
        gal.setTag(TAG_FIT, new int[]{w, h, shortSide, Math.round(ratio * 10000f)});
        if (lp.width == w && lp.height == h) {
            return;
        }
        lp.width = w;
        lp.height = h;
        gal.setLayoutParams(lp);
        XposedModule m = sModule;
        if (m != null) {
            m.log(Log.INFO, TAG, "FIT gallery -> " + w + "x" + h
                    + " ratio=" + ratio + " avail=" + availW + "x" + availH);
        }
    }

    /**
     * 这张图片实际能占的高度：从图片当前位置到可滚动视口的底边。
     *
     * 之前用「滚动容器总高」当上限，比真实可用空间多了「图片顶到视口顶」的那段
     * （外屏实测多出 135px），竖图就顶出了屏幕。改成按屏幕坐标算：
     *   视口底边 = 最近 NestedScrollLayout 祖先的 bottom（屏幕坐标）
     *   可用高   = 视口底边 - 图片当前 top（屏幕坐标）
     * 结果只在「比例变化 / 换屏」时重算并缓存在 View 上，
     * 滚动过程中不会因为 top 变化而反复改尺寸。
     */
    private static int availableHeight(View gal) {
        try {
            int[] loc = new int[2];
            gal.getLocationOnScreen(loc);
            int bottom = viewportBottom(gal);
            int h = bottom - loc[1];
            if (h > 0) {
                return h;
            }
        } catch (Throwable ignored) {
        }
        android.content.Context ctx = currentContext();
        if (ctx != null) {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return (int) (dm.heightPixels * 0.80f);
        }
        return 0;
    }

    private static int viewportBottom(View from) {
        View v = from;
        for (int i = 0; i < 8 && v != null; i++) {
            if (v.getClass().getName().contains("NestedScrollLayout") && v.getHeight() > 0) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                return loc[1] + v.getHeight();
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        android.content.Context ctx = currentContext();
        if (ctx != null) {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return dm.heightPixels - 130;
        }
        return 0;
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
            sb.append(v.getClass().getSimpleName())
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
            m.log(Log.INFO, TAG, sb.toString());
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
            final int want = outer ? 0 : (gutter ? sPad : -1);
            final ViewTreeObserver.OnPreDrawListener l =
                    new ViewTreeObserver.OnPreDrawListener() {
                private int frame = 0;

                @Override
                public boolean onPreDraw() {
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
                        if (sFill && display) {
                            fitGallery(v);
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
                                        m.log(Log.INFO, TAG, "PARENT "
                                                + v.getClass().getSimpleName()
                                                + " ph=" + pv.getHeight()
                                                + " pw=" + pv.getWidth());
                                    }
                                } catch (Throwable ignored) {
                                }
                                int sc = -1;
                                try {
                                    sc = v.getScrollY();
                                } catch (Throwable ignored) {
                                }
                                m.log(Log.INFO, TAG, "FRAME " + frame + " "
                                        + v.getClass().getSimpleName()
                                        + " pad=" + v.getPaddingLeft()
                                        + " w=" + v.getWidth()
                                        + " h=" + v.getHeight()
                                        + " top=" + top
                                        + " scrollY=" + sc
                                        + " vis=" + v.getVisibility());
                            }
                            frame++;
                        }
                    } catch (Throwable ignored) {
                    }
                    return true; // 不取消绘制
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
    private static final String[] STORE_HINTS = {"p7e.h", "q3e.h", "s3e.h", "r3e.h", "t3e.h"};

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
                        m.log(Log.INFO, TAG, "scale class found by scan: " + cand.getName());
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
                        m.log(Log.INFO, TAG, "store class found by scan: " + cand.getName());
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
                mod.log(Log.INFO, TAG, "divider=" + sDivider + " (" + lo + "~" + hi + ")");
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
    private static int screenTag(ClassLoader cl) {
        int shortSide = currentShortSidePx();
        int divider = divider(cl);
        return divider > 0 && shortSide >= divider ? 1 : 0;
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
                log(Log.INFO, TAG, "conf absent, defaults tree=" + sTree + " fill=" + sFill
                        + " pad=" + sPad + " margin=" + sMargin);
                return;
            }
            java.util.Properties p = new java.util.Properties();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
            sTree = boolConf(p, "tree", DEBUG_TREE);
            sFill = boolConf(p, "fill", true);
            sPad = intConf(p, "pad", PAD_TARGET);
            sMargin = intConf(p, "margin", 22);
            sBottom = intConf(p, "bottom", 110);
            log(Log.INFO, TAG, "conf tree=" + sTree + " fill=" + sFill
                    + " pad=" + sPad + " margin=" + sMargin);
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
                log(Log.INFO, TAG, "hooked SP read j(String,int)");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook SP read failed: " + t);
            }

            // 写入：u(String, int) -> void
            try {
                Method u = h.getDeclaredMethod("u", String.class, int.class);
                u.setAccessible(true);
                hook(u).intercept(chain -> {
                    List<Object> orig = chain.getArgs();
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
                log(Log.INFO, TAG, "hooked SP write u(String,int)");
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
                    int tag = screenTag(cl);
                    if (tag != sCachedTag) {
                        sCachedTag = tag;
                        int v = readScaleForScreen(cl);
                        try {
                            fd.setInt(null, v);
                        } catch (Throwable ignored) {
                        }
                        log(Log.INFO, TAG, "switch screen -> reload scale, tag="
                                + (tag == 1 ? "inner" : "outer") + " value=" + v);
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "hooked scale a() for per-screen scale refresh");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook scale a() failed: " + t);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "installSplitScreenScale failed: " + t);
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
