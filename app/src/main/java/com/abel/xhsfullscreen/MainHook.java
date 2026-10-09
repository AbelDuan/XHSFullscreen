package com.abel.xhsfullscreen;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
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
 *   2. re-clamping on every layout pass (belt & braces).
 *
 * The first-level feed is untouched (no maxWidth / no density spoof), so the
 * multi-column layout is preserved.
 */
public class MainHook extends XposedModule {

    private static final String TAG = "XHSFullscreen";
    private static final String XHS = "com.xingin.xhs";
    private static final int MATCH_PARENT = -1;
    private static final int MAX_DEPTH = 6;

    // Symmetric L/R padding at/above this (px) is treated as the detail-page
    // content inset and reduced to PAD_TARGET. 189px was observed on the
    // foldable inner screen; 64px leaves normal small paddings intact.
    private static final int PAD_THRESHOLD = 64;
    // Keep this much left/right gutter (px) instead of clearing to 0.
    private static final int PAD_TARGET = 45;
    // A MATCH_PARENT container whose measured width is below this fraction of
    // the screen is considered artificially narrowed and gets maxWidth cleared.
    private static final float FILL_THRESHOLD = 0.98f;

    // Real (possibly overridden) classes whose setPadding we have already
    // hooked. Keyed by class name; bounded and never reset.
    private static final Set<String> hookedClasses = new HashSet<>();

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!XHS.equals(param.getPackageName())) {
            return;
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
                hook(sp).intercept(chain -> {
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
                });
            } catch (Throwable ignored) {
            }
            try {
                final Method spr = c.getDeclaredMethod(
                        "setPaddingRelative", int.class, int.class, int.class, int.class);
                spr.setAccessible(true);
                hook(spr).intercept(chain -> {
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
                });
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
    }

    private void scheduleFix(final Activity a) {
        try {
            final View decor = a.getWindow().getDecorView();
            if (decor == null) {
                return;
            }
            // Run now...
            decor.post(() -> fixContent(a));
            // ...and again after the comment list has had time to load
            // asynchronously (the comment container is created AFTER onResume,
            // so a single pass would miss it and XHS would reset the padding).
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
                // Belt & braces: re-clamp on every layout pass. When XHS sets
                // 189 again, a re-layout fires and we clamp back to PAD_TARGET.
                final View fv = v;
                fv.getViewTreeObserver().addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            private boolean busy = false;
                            @Override
                            public void onGlobalLayout() {
                                if (busy) {
                                    return;
                                }
                                busy = true;
                                try {
                                    int l = fv.getPaddingLeft();
                                    int r = fv.getPaddingRight();
                                    if (l == r && l >= PAD_THRESHOLD) {
                                        fv.setPadding(PAD_TARGET, fv.getPaddingTop(),
                                                PAD_TARGET, fv.getPaddingBottom());
                                    }
                                } catch (Throwable ignored) {
                                }
                                busy = false;
                            }
                        });
            }

            // One-shot clamp for any symmetric large L/R padding on this view.
            int pl = v.getPaddingLeft();
            int pr = v.getPaddingRight();
            if (pl == pr && pl >= PAD_THRESHOLD) {
                v.setPadding(PAD_TARGET, v.getPaddingTop(), PAD_TARGET, v.getPaddingBottom());
            }

            // Also clamp symmetric large L/R margins (some layouts use margins).
            ViewGroup.LayoutParams lp0 = v.getLayoutParams();
            if (lp0 instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp0;
                int ml = mlp.leftMargin, mr = mlp.rightMargin;
                if (ml == mr && ml >= PAD_THRESHOLD) {
                    mlp.leftMargin = PAD_TARGET;
                    mlp.rightMargin = PAD_TARGET;
                    v.setLayoutParams(mlp);
                }
            }

            // Fallback: clear maxWidth on any MATCH_PARENT container that is
            // still measured narrower than the screen.
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.width == MATCH_PARENT) {
                int screenW = a.getResources().getDisplayMetrics().widthPixels;
                int measured = v.getMeasuredWidth();
                if (measured > 0 && measured < screenW * FILL_THRESHOLD) {
                    clearMaxWidth(lp);
                    lp.width = MATCH_PARENT;
                    v.setLayoutParams(lp);
                    v.requestLayout();
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

    private void clearMaxWidth(ViewGroup.LayoutParams lp) {
        try {
            Field f = lp.getClass().getDeclaredField("maxWidth");
            f.setAccessible(true);
            f.set(lp, Integer.MAX_VALUE);
        } catch (Throwable ignored) {
        }
    }
}
