package com.swipeoff;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final long DOUBLE_MS = 350L;
    private static final long LONG_MS = 520L;

    private static Handler h;
    private static Runnable longTask;
    private static Runnable singleTask;
    private static long downTime;
    private static long upTime;
    private static volatile boolean longPressed;
    private static boolean doubleHit;
    private static Object policy;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) throws Throwable {
        try {
            if ("android".equals(lp.packageName)) {
                hookPower(lp);
            } else {
                hookSwipe();
            }
        } catch (Throwable t) {
            XposedBridge.log("SwipeOff load fail " + t);
        }
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
        Class<?> pwm;
        try {
            pwm = XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", lp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("SwipeOff no PhoneWindowManager " + t);
            return;
        }
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                KeyEvent ev = null;
                for (Object a : param.args) {
                    if (a instanceof KeyEvent) {
                        ev = (KeyEvent) a;
                        break;
                    }
                }
                if (ev == null || ev.getKeyCode() != KeyEvent.KEYCODE_POWER) return;
                if (policy == null) policy = param.thisObject;
                if (h == null) init();
                handleKey(ev, param);
            }
        };
        Set<?> set = null;
        try {
            set = XposedBridge.hookAllMethods(pwm, "interceptKeyBeforeQueueing", cb);
        } catch (Throwable t) {
            XposedBridge.log("SwipeOff queueing hook fail " + t);
        }
        if (set == null || set.isEmpty()) {
            try {
                Set<?> s2 = XposedBridge.hookAllMethods(pwm, "interceptKeyBeforeDispatching", cb);
                if (s2 != null && !s2.isEmpty()) XposedBridge.log("SwipeOff hooked dispatching");
                else XposedBridge.log("SwipeOff no power method");
            } catch (Throwable t) {
                XposedBridge.log("SwipeOff dispatching hook fail " + t);
            }
        }
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
        int action = ev.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (ev.getRepeatCount() == 0) {
                if (upTime != 0 && now - upTime < DOUBLE_MS) {
                    upTime = 0;
                    doubleHit = true;
                    h.removeCallbacks(longTask);
                    h.removeCallbacks(singleTask);
                    inject(KeyEvent.KEYCODE_HOME);
                } else {
                    doubleHit = false;
                    downTime = now;
                    longPressed = false;
                    h.postDelayed(longTask, LONG_MS);
                }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            h.removeCallbacks(longTask);
            if (doubleHit) {
                doubleHit = false;
                upTime = 0;
            } else if (longPressed) {
                longPressed = false;
                upTime = 0;
            } else if (now - downTime < LONG_MS) {
                upTime = now;
                h.removeCallbacks(singleTask);
                h.postDelayed(singleTask, DOUBLE_MS);
            } else {
                upTime = 0;
            }
        }
        Class<?> rt = ((java.lang.reflect.Method) param.method).getReturnType();
        if (rt == long.class) param.setResult(0L);
        else if (rt == int.class) param.setResult(0);
        else param.setResult(null);
    }

    private static void inject(int code) {
        try {
            Object im = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.hardware.input.InputManager", null), "getInstance");
            long t = SystemClock.uptimeMillis();
            Object ok = XposedHelpers.callMethod(im, "injectInputEvent",
                    new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0), 0);
            XposedHelpers.callMethod(im, "injectInputEvent",
                    new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0), 0);
            if (ok instanceof Boolean && !((Boolean) ok)) XposedBridge.log("SwipeOff inject rejected");
        } catch (Throwable t) {
            XposedBridge.log("SwipeOff inject fail " + t);
        }
    }

    private static void showGlobal() {
        try {
            Object funcs = XposedHelpers.getObjectField(policy, "mWindowManagerFuncs");
            XposedHelpers.callMethod(funcs, "showGlobalActions");
        } catch (Throwable t) {
            XposedBridge.log("SwipeOff showGlobal fail " + t);
            try {
                XposedHelpers.callMethod(policy, "powerLongPress", SystemClock.uptimeMillis());
            } catch (Throwable t2) {
                XposedBridge.log("SwipeOff powerLongPress fail " + t2);
            }
        }
    }
}
