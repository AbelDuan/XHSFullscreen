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
    private static final int GUARD_STABLE_FRAMES = 30;

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
    };

    // 逐帧观测开关：记录每帧绘制时 padding 的真实值，用于验证
    // 「打开帖子前边距就已定好」。定稿后可关掉。
    private static final boolean VERBOSE = false;

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
        log(Log.INFO, TAG, "hooked into XHS, installing hooks (v3)");
        sModule = this;

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
                log(Log.ERROR, TAG, "hook wj9.c0.y0 failed: " + t);
            }
        }

        // 探针：把调用栈里出现的混淆类的方法签名打出来，
        // 便于把「wj9.c0.y0」映射到真实签名（后续要 hook 它的返回值）。
        // 类名来自滑动窗口取证：CLAMP 调用栈里的 wj9/c0.y0 是计算边距的核心。
        if (PROBE) {
            // 注意：必须用目标 App 的 ClassLoader，模块自己的加载器看不到 App 的类。
            ClassLoader cl = null;
            try {
                cl = param.getDefaultClassLoader();
            } catch (Throwable ignored) {
            }
            log(Log.INFO, TAG, "PROBE classloader = " + cl);
            probeClass("wj9.c0", cl);
            probeClass("wj9.t", cl);
        }

        // Reschedule the fix on every resume / config change (e.g. fold/unfold).
        hookActivityLifecycle();

        // Clamp any direct setPadding / setPaddingRelative on the comment and
        // image containers. This catches XHS's async re-set of the padding.
        XposedInterface.Hooker clampHook = chain -> {
            try {
                Object thisObj = chain.getThisObject();
                if (thisObj instanceof View) {
                    String cn = thisObj.getClass().getName();
                    if (isTarget(cn)) {
                        List<Object> args = chain.getArgs();
                        int l = (Integer) args.get(0);
                        int r = (Integer) args.get(2);
                        // 内容区：把大边距收成 45；外层容器：清成 0
                        if (l == r && l >= PAD_THRESHOLD) {
                            int want = isOuterContainer(cn) ? 0 : PAD_TARGET;
                            args.set(0, want);
                            args.set(2, want);
                            return chain.proceedWith(args);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        };
        try {
            Method setPadding = View.class.getDeclaredMethod(
                    "setPadding", int.class, int.class, int.class, int.class);
            hook(setPadding).intercept(clampHook);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook setPadding failed: " + t);
        }
        try {
            Method setPaddingRel = View.class.getDeclaredMethod(
                    "setPaddingRelative", int.class, int.class, int.class, int.class);
            hook(setPaddingRel).intercept(clampHook);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook setPaddingRelative failed: " + t);
        }

        // ★ v3 关键：在视图挂上窗口的瞬间就接管。
        //
        // onAttachedToWindow 早于任何 measure / layout / draw，也早于
        // onResume 之后那几次 post() 补救。在这里立刻把 padding 归一成
        // PAD_TARGET，并挂上 PreDraw 守卫 —— 页面第一帧画出来就是最终宽度，
        // 不存在「先窄后宽」的中间态。
        //
        // 这解决了 v1/v2 的根本时序缺陷：那两版都要等 onResume 后
        // 0/300/800ms 的延时任务，而延时期间 XHS 早已用 277 完成首帧绘制。
        try {
            Method attach = View.class.getDeclaredMethod("onAttachedToWindow");
            hook(attach).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof View) {
                        View v = (View) thisObj;
                        String cn = v.getClass().getName();
                        if (isTarget(cn)) {
                            synchronized (hookedClasses) {
                                if (!hookedClasses.contains(cn)) {
                                    hookedClasses.add(cn);
                                    hookPaddingSetters(v.getClass());
                                }
                            }
                            clampPadding(v);
                            ensureGuard(v);
                        }
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
            log(Log.INFO, TAG, "onAttachedToWindow hook installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook onAttachedToWindow failed: " + t);
        }

        // 注：曾经在这儿 hook View.onMeasure 强制改宽度。
        // 实测日志证明它从未被调用 —— 三个目标类都重写了 onMeasure，
        // 拿到的 View.onMeasure 被子类实现绕过，那段代码是死代码，已删除。
        // 宽度本身在 PreDraw 阶段通过 clampPadding 的实测验证是正确的。

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
                || cn.contains("NoteDetailContentView");
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
                if (isTarget(cn) && l == r && l >= PAD_THRESHOLD) {
                    // 内容区收成 45；外层容器清成 0（否则会挤压子视图 90px）
                    int want = isOuterContainer(cn) ? 0 : PAD_TARGET;
                    args.set(0, want);
                    args.set(2, want);
                    return chain.proceedWith(args);
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
                    : (needsGutter(cn) ? PAD_TARGET : v.getPaddingLeft());
            int l = v.getPaddingLeft();
            int r = v.getPaddingRight();
            if (l != want || r != want) {
                v.setPadding(want, v.getPaddingTop(), want, v.getPaddingBottom());
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
            final int want = outer ? 0 : (gutter ? PAD_TARGET : -1);
            final ViewTreeObserver.OnPreDrawListener l =
                    new ViewTreeObserver.OnPreDrawListener() {
                private int frame = 0;
                private int stable = 0;

                @Override
                public boolean onPreDraw() {
                    try {
                        // want < 0 表示这既不是外层也不是内容区，无需干预
                        if (want >= 0) {
                            int cur = v.getPaddingLeft();
                            if (cur != want || v.getPaddingRight() != want) {
                                v.setPadding(want, v.getPaddingTop(),
                                        want, v.getPaddingBottom());
                                stable = 0;
                            } else if (++stable >= GUARD_STABLE_FRAMES) {
                                // 稳定后自行摘除：此后不再每帧回调，
                                // 避免持续的对象分配与检查开销。
                                v.getViewTreeObserver().removeOnPreDrawListener(this);
                                v.setTag(TAG_GUARD, null);
                            }
                        } else {
                            v.getViewTreeObserver().removeOnPreDrawListener(this);
                            v.setTag(TAG_GUARD, null);
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
}
