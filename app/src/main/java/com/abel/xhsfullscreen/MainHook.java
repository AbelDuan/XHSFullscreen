package com.abel.xhsfullscreen;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

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
 * view whose LayoutParams.width was MATCH_PARENT, compared getMeasuredWidth()
 * against 98% of the screen width. During the post()/postDelayed() passes the
 * measured width of many children is still a not-yet-final intermediate value,
 * so those views were misclassified as "artificially narrowed" and got a
 * requestLayout() + setLayoutParams() forced onto them. Several of those in a
 * row produce a measurement storm: the first frame renders at the narrow
 * intermediate width and only settles (visibly "snapping") once the extra
 * layout passes finish.
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

    // Real (possibly overridden) classes whose setPadding we have already
    // hooked. Keyed by class name; bounded and never reset.
    private static final Set<String> hookedClasses = new HashSet<>();

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // 入口可观测性：LSPosed 每次把本模块注入目标进程都会经过这里。
        // 没有这条日志就无法区分「模块没被加载」和「加载了但逻辑没命中」——
        // 排查时那是两个完全不同的方向，所以保留为常规 INFO 输出。
        log(Log.INFO, TAG, "onPackageLoaded: " + param.getPackageName());
        if (!XHS.equals(param.getPackageName())) {
            return;
        }
        log(Log.INFO, TAG, "hooked into XHS, installing hooks (v1.1)");

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
                        if (l == r && l >= PAD_THRESHOLD) {
                            args.set(0, PAD_TARGET);
                            args.set(2, PAD_TARGET);
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

    private static boolean isTarget(String cn) {
        return cn.contains("CommentListView")
                || cn.contains("AsyncImageInfoView")
                || cn.contains("NoteDetailContentView");
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
                List<Object> args = chain.getArgs();
                int l = (Integer) args.get(0);
                int r = (Integer) args.get(2);
                if (l == r && l >= PAD_THRESHOLD) {
                    args.set(0, PAD_TARGET);
                    args.set(2, PAD_TARGET);
                    return chain.proceedWith(args);
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        };
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
                log(Log.INFO, TAG, "target hit: " + cn
                        + " pad=" + v.getPaddingLeft() + "/" + v.getPaddingRight()
                        + " w=" + v.getWidth());
                // Hook the real class's setters so XHS's override (and async
                // re-sets) are intercepted. Done once per class.
                synchronized (hookedClasses) {
                    if (!hookedClasses.contains(cn)) {
                        hookedClasses.add(cn);
                        hookPaddingSetters(v.getClass());
                    }
                }
                clampPadding(v);
                // Self-terminating guard: keeps clamping while XHS re-sets the
                // padding after comments load, then removes itself so it stops
                // driving layout once the value has settled.
                attachLayoutGuard(v);
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
     * Idempotent clamp: writes only when the value actually needs changing, so
     * a settled layout is never disturbed.
     */
    private static void clampPadding(View v) {
        try {
            int l = v.getPaddingLeft();
            int r = v.getPaddingRight();
            if (l == r && l >= PAD_THRESHOLD) {
                v.setPadding(PAD_TARGET, v.getPaddingTop(), PAD_TARGET, v.getPaddingBottom());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Re-clamp on layout passes caused by XHS's own async padding re-set, and
     * detach once the padding has stayed correct for a while. v1 kept the
     * listener alive forever, so every layout pass wrote padding again.
     */
    private static void attachLayoutGuard(final View v) {
        try {
            final int[] settled = {0};
            final ViewTreeObserver.OnGlobalLayoutListener[] holder = new ViewTreeObserver.OnGlobalLayoutListener[1];
            ViewTreeObserver.OnGlobalLayoutListener l = new ViewTreeObserver.OnGlobalLayoutListener() {
                private boolean busy = false;

                @Override
                public void onGlobalLayout() {
                    if (busy) {
                        return;
                    }
                    busy = true;
                    try {
                        int cur = v.getPaddingLeft();
                        if (cur == v.getPaddingRight() && cur >= PAD_THRESHOLD) {
                            v.setPadding(PAD_TARGET, v.getPaddingTop(),
                                    PAD_TARGET, v.getPaddingBottom());
                            settled[0] = 0;
                        } else if (cur == PAD_TARGET) {
                            // Correct and stable: stop listening after a few
                            // consecutive clean passes.
                            if (++settled[0] >= 3) {
                                try {
                                    v.getViewTreeObserver().removeOnGlobalLayoutListener(holder[0]);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    busy = false;
                }
            };
            holder[0] = l;
            v.getViewTreeObserver().addOnGlobalLayoutListener(l);
        } catch (Throwable ignored) {
        }
    }
}
