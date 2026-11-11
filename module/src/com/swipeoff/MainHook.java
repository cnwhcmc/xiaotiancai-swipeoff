package com.swipeoff;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final long DOUBLE_MS = 350L;
    private static final long LONG_MS = 520L;

    private static volatile Handler h;
    private static volatile Runnable longTask;
    private static volatile Runnable singleTask;
    private static long downTime;
    private static long upTime;
    private static volatile boolean longPressed;
    private static boolean doubleHit;
    private static volatile Object policy;

    private static Field fScreen;
    private static boolean screenResolved;
    private static volatile boolean offSeq;
    private static volatile boolean offLong;
    private static volatile int offRetry;
    private static long offSeqStart;

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
        XposedBridge.log("SwipeOff power hook v4b");
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

                // 熄屏序列：事件全部交还原生（点亮、原生长按都由系统自己走），
                // 另外挂一个 520ms 计时当补偿，8.1 原生熄屏长按不会启动自己的计时器
                if (offSeq) {
                    // 超时保护：只按下没抬起时，3 秒后强制复位，避免电源键被永久交还原生
                    if (offSeqStart != 0 && SystemClock.uptimeMillis() - offSeqStart > 3000L) {
                        offSeq = false;
                        offLong = false;
                        offRetry = 0;
                        offSeqStart = 0;
                        if (h != null) h.removeCallbacks(longTask);
                    } else {
                        if (ev.getAction() == KeyEvent.ACTION_UP) {
                            offSeq = false;
                            offLong = false;
                            offRetry = 0;
                            offSeqStart = 0;
                            if (h != null) h.removeCallbacks(longTask);
                        } else if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                            offLong = true;
                            offRetry = 0;
                            if (h == null) init();
                            h.removeCallbacks(longTask);
                            h.postDelayed(longTask, LONG_MS);
                        }
                        return;
                    }
                }
                if (isScreenOff(policy)) {
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                        offSeq = true;
                        offLong = true;
                        offRetry = 0;
                        offSeqStart = SystemClock.uptimeMillis();
                        if (h == null) init();
                        h.removeCallbacks(longTask);
                        h.postDelayed(longTask, LONG_MS);
                        XposedBridge.log("SwipeOff off-down, timer armed");
                    }
                    return;
                }

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

    private static boolean isScreenOff(Object policy) {
        if (!screenResolved) {
            screenResolved = true;
            try {
                fScreen = XposedHelpers.findFieldIfExists(policy.getClass(), "mScreenOnFully");
                if (fScreen == null) {
                    fScreen = XposedHelpers.findFieldIfExists(policy.getClass(), "mScreenOnEarly");
                }
                if (fScreen == null) XposedBridge.log("SwipeOff no mScreenOn field");
            } catch (Throwable t) {
                XposedBridge.log("SwipeOff screen field fail " + t);
            }
        }
        if (fScreen != null) {
            try {
                return !((Boolean) fScreen.get(policy));
            } catch (Throwable t) {
                XposedBridge.log("SwipeOff screen read fail " + t);
            }
        }
        try {
            Object pm = XposedHelpers.getObjectField(policy, "mPowerManager");
            Object r = XposedHelpers.callMethod(pm, "isInteractive");
            if (r instanceof Boolean) return !((Boolean) r);
        } catch (Throwable t) {
            XposedBridge.log("SwipeOff isInteractive fail " + t);
        }
        // 判断不出来就按熄屏处理：宁可交还原生，也不能留下屏幕点不亮的可能
        return true;
    }

    private static boolean interactiveNow(Object policy) {
        try {
            Object pm = XposedHelpers.getObjectField(policy, "mPowerManager");
            Object r = XposedHelpers.callMethod(pm, "isInteractive");
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable t) {
            XposedBridge.log("SwipeOff isInteractive probe fail " + t);
        }
        return !isScreenOff(policy);
    }

    private static void init() {
        h = new Handler(Looper.getMainLooper());
        longTask = new Runnable() {
            public void run() {
                if (offLong) {
                    // 熄屏长按：等屏幕进入 interactive 再弹（完全点亮要更久，弹窗会被冻结期吞掉）
                    if (!interactiveNow(policy) && offRetry < 13) {
                        offRetry++;
                        h.postDelayed(this, 150);
                        return;
                    }
                    offLong = false;
                    offRetry = 0;
                    XposedBridge.log("SwipeOff off-long fired, interactive=" + interactiveNow(policy));
                }
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
                    h.removeCallbacks(longTask);
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
