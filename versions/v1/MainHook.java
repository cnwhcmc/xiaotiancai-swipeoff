package com.swipeoff;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * v1 — 首个版本：右滑拦截时机错误，实测右滑仍然退出。
 * 问题：disallow 只在 ACTION_DOWN 派发【之前】调用，
 * ViewGroup.dispatchTouchEvent 收到 DOWN 时会 resetTouchState() 把标志清掉。
 * 电源键部分正常。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final long DOUBLE_MS = 400L;
    private static final long LONG_MS = 700L;

    private static Handler h;
    private static Runnable longTask;
    private static Runnable singleTask;
    private static long downTime;
    private static long upTime;
    private static boolean longPressed;
    private static Object policy;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) throws Throwable {
        if (lp.packageName.equals("android")) hookPower(lp);
        else hookSwipe();
    }

    private void hookSwipe() {
        XposedBridge.log("SwipeOff swipe hook");
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                MotionEvent e = (MotionEvent) param.args[0];
                if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                View v = ((Activity) param.thisObject).findViewById(android.R.id.content);
                if (v instanceof ViewGroup) ((ViewGroup) v).requestDisallowInterceptTouchEvent(true);
            }
        });
    }

    private void hookPower(LoadPackageParam lp) {
        XposedBridge.log("SwipeOff power hook");
        Class<?> pwm = XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", lp.classLoader);
        XposedHelpers.findAndHookMethod(pwm, "interceptKeyBeforeQueueing", KeyEvent.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        KeyEvent ev = (KeyEvent) param.args[0];
                        if (ev.getKeyCode() != KeyEvent.KEYCODE_POWER) return;
                        if (policy == null) policy = param.thisObject;
                        if (h == null) init();
                        handleKey(ev, param);
                    }
                });
    }

    private static void init() {
        h = new Handler(Looper.getMainLooper());
        longTask = new Runnable() {
            public void run() {
                longPressed = true;
                showGlobal();
            }
        };
        singleTask = new Runnable() {
            public void run() {
                inject(KeyEvent.KEYCODE_BACK);
            }
        };
    }

    private static void handleKey(KeyEvent ev, XC_MethodHook.MethodHookParam param) {
        long now = SystemClock.uptimeMillis();
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (upTime != 0 && now - upTime < DOUBLE_MS) {
                upTime = 0;
                h.removeCallbacks(longTask);
                h.removeCallbacks(singleTask);
                inject(KeyEvent.KEYCODE_HOME);
            } else {
                downTime = now;
                longPressed = false;
                h.removeCallbacks(singleTask);
                h.postDelayed(longTask, LONG_MS);
            }
        } else if (ev.getAction() == KeyEvent.ACTION_UP) {
            h.removeCallbacks(longTask);
            upTime = now;
            if (!longPressed && now - downTime < LONG_MS) {
                h.postDelayed(singleTask, DOUBLE_MS);
            }
            longPressed = false;
        }
        param.setResult(0);
    }

    private static void inject(int code) {
        Object im = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.hardware.input.InputManager", null), "getInstance");
        long t = SystemClock.uptimeMillis();
        XposedHelpers.callMethod(im, "injectInputEvent", new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0), 0);
        XposedHelpers.callMethod(im, "injectInputEvent", new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0), 0);
    }

    private static void showGlobal() {
        try {
            Object funcs = XposedHelpers.getObjectField(policy, "mWindowManagerFuncs");
            XposedHelpers.callMethod(funcs, "showGlobalActions");
        } catch (Throwable t) {
            XposedBridge.log("SwipeOff showGlobal fail " + t);
        }
    }
}
