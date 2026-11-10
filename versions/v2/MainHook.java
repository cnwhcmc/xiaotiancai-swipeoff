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
 * v2 — 在 DOWN 派发【结束之后】再补一次 disallow，右滑生效。
 * 这是本项目唯一被实机确认「右滑不退出」的版本。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final long DOUBLE_MS = 350L;
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
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                setDisallow(param);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                MotionEvent e = (MotionEvent) param.args[0];
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) setDisallow(param);
            }
        });
    }

    private static void setDisallow(XC_MethodHook.MethodHookParam param) {
        try {
            View v = ((Activity) param.thisObject).findViewById(android.R.id.content);
            if (v instanceof ViewGroup) ((ViewGroup) v).requestDisallowInterceptTouchEvent(true);
        } catch (Throwable ignored) {
        }
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
