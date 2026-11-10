# XiaoTianCai SwipeOff · 小天才手表手势定制模块

> 小天才电话手表（Z6 / Android 8.1）的 Xposed 模块：**禁用窗口层的「右滑退出」**，并把电源键改造为「**单击返回 / 双击回桌面**」，长按电源键仍然弹原生的关机菜单。
>
> An Xposed module for the XiaoTianCai (okii) Z6 kids smartwatch that disables the window-level swipe-to-dismiss gesture and remaps the power key (single = back, double = home, long = power menu).

中国人不骗中国人：下面每个版本的行为、每个已知缺陷，都是实机测出来的，没有一条是编的。

---

## 前置条件（必须先有这些，否则模块装了也不会生效）

| 依赖 | 要求 |
|---|---|
| root | 已解锁 Bootloader 并刷入 Magisk，`su -c id` 返回 `uid=0` |
| Xposed 框架 | EdXposed（Riru）或 LSPosed，**框架状态必须是 active**；`Xposed Installer` 首页显示「未激活」时模块一律不生效 |
| 模块开关 | 安装本 APK 后，必须在框架管理器（Modules 页）里勾选启用，然后**重启设备** |
| 系统版本 | Android 7.1 / 8.1（本项目在 Z6 / 8.1、msm8909w、armeabi-v7a 上验证） |

模块本身不提供 root、不刷框架、不修改系统分区。

---

## 功能

| 操作 | 行为 | 说明 |
|---|---|---|
| 右滑 | 不退出 | 覆盖所有 Activity 窗口 |
| 单击电源键 | 返回上一级 | 约 350 ms 判定延迟（在等双击） |
| 双击电源键 | 回到桌面 | 原机短按的功能保留到这里 |
| 长按电源键 | 关机菜单 | 反射调用系统原生 `showGlobalActions`，功能不丢 |

副作用：短按电源键**不再熄屏**（该键已被占用为返回键）。原机短按本来就是回桌面，没有独立的熄屏入口，所以不算功能缺失。

---

## 版本历史

| 版本 | 状态 | 说明 |
|---|---|---|
| v1 | 归档 | 首个版本。`requestDisallowInterceptTouchEvent(true)` 只在 DOWN 派发**之前**调用，被 `ViewGroup.resetTouchState()` 清掉，右滑完全不生效；电源键部分正常 |
| v2 | 稳定 | 改为在 DOWN 派发**结束之后**再补一次 disallow，右滑生效 |
| v3 | 作废 | 额外增加 Dialog 与窗口兜底分支，右滑反而重新失效（实机验证） |
| final | 推荐 | 右滑代码逐字回退到 v2，电源键逻辑保留 v3 的稳定性修复 |

### final 相对 v2 的修复

- 双击回桌面后不再多注入一次 BACK
- 长按结束后 350 ms 内再单击不再被误判为双击
- 按住时的 repeat 事件不再漏给系统（避免原生逻辑与模块重复触发）
- 方法签名不匹配时自动回退：`interceptKeyBeforeQueueing` → `interceptKeyBeforeDispatching`
- 按方法实际返回类型给 `0` / `0L`，避免 `setResult` 抛异常导致 hook 静默失效
- 全部异常兜底，模块不会把 system_server 或应用进程拖崩

---

## 技术原理

### 右滑退出（窗口层手势）

小天才沿用了 AOSP 的 `windowSwipeToDismiss` / `SwipeDismissLayout`：窗口在 `onInterceptTouchEvent` 里判断横向滑动，超过阈值就 dismiss。

子视图调用 `requestDisallowInterceptTouchEvent(true)` 后，父级不再收到 `onInterceptTouchEvent`，手势自然失效。MT 管理器的图片查看器之所以「右滑不退出」，就是因为里面的 ViewPager 在按下时做了这件事。

关键点在于时机：`ViewGroup.dispatchTouchEvent()` 收到 `ACTION_DOWN` 时会调用 `resetTouchState()`，把 `FLAG_DISALLOW_INTERCEPT` 清掉。所以在 DOWN 派发**前**设置无效（v1 的坑），必须在 DOWN 派发**结束后**再设置（v2 的修法）。

模块实现：

```java
XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class,
    new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) { setDisallow(p); }
        @Override protected void afterHookedMethod(MethodHookParam p) {
            if (((MotionEvent) p.args[0]).getActionMasked() == MotionEvent.ACTION_DOWN) setDisallow(p);
        }
    });
```

### 电源键

电源键在 `system_server` 的 `PhoneWindowManager.interceptKeyBeforeQueueing()` 里被消费，应用层收不到。模块 hook 该处并按短按/双击/长按自行分流：

- 单击：注入 `KEYCODE_BACK`
- 双击：注入 `KEYCODE_HOME`
- 长按：反射 `mWindowManagerFuncs.showGlobalActions()` 弹系统原生关机菜单

注入走 `InputManager.injectInputEvent`；`interceptKeyBeforeQueueing` 的 LONG/INT 返回值按实际方法签名适配。

---

## 构建

不依赖 Android Studio / Gradle，用命令行工具链即可（已验证）：

```
aapt2 link  → base.apk（manifest + assets）
ecj         → MainHook.class（JRE + ecj.jar，无需 javac）
d8          → classes.dex
zip         → 合并 dex
zipalign    → 4 字节对齐
apksigner   → 签名
```

依赖：`android.jar`（platform-28）、`build-tools 28.0.3`（aapt2 / d8 / zipalign / apksigner）、`de.robv.android.xposed:api:82`、`ecj`。

`xposed_init` 只有一行：

```
com.swipeoff.MainHook
```

AndroidManifest 需要三个 meta-data：`xposedmodule`、`xposeddescription`、`xposedminversion=82`。

---

## 发行版本（Releases）

| 版本 | 文件 | SHA-256 |
|---|---|---|
| v1 | `SwipeOff-v1.apk` | `b84a78d6dbb000a0237d715ee52ae8dcd8fa61eb584d9149a97e5fc6fe8d5725` |
| v2 | `SwipeOff-v2.apk` | `59b8bbd814d86ac366e40cd2eff20968a701c645f73993c5f820f880a73a349c` |
| final | `SwipeOff-final.apk` | `878c22c361887225fef0f9e2e2774585f86d253e2325ff82f6ec8c84bf7fe15c` |

三个版本共用同一个包名与签名（`com.swipeoff` / versionCode 1），可直接覆盖安装，无需卸载。

直达下载：

- [v1](https://github.com/cnwhcmc/xiaotiancai-swipeoff/releases/tag/v1)（右滑无效，仅存档）
- [v2](https://github.com/cnwhcmc/xiaotiancai-swipeoff/releases/tag/v2)（右滑生效版）
- [v3-final](https://github.com/cnwhcmc/xiaotiancai-swipeoff/releases/tag/v3-final)（推荐）

## 安装

```
adb install -r SwipeOff-final.apk
adb shell su -c reboot
```

验证模块是否真的被加载：

```
adb logcat -s Xposed | grep SwipeOff
```

应看到 `SwipeOff power hook` 以及每个应用进程各一条加载日志。只有「装上了」但日志为空，说明框架未激活或模块没勾选。

卸载回滚：框架管理器取消勾选 + 重启，或 `adb uninstall com.swipeoff`。

---

## 免责声明

- 仅用于你**自己拥有**的设备；刷机、root、框架安装与使用风险自负，可能导致变砖、失去保修。
- 本项目只做**手势层与按键映射**的定制，不涉及、不提供任何绕过家长监护、定位、通信或厂商安全机制的功能。
- 请遵守当地法律与设备厂商条款。

---

## 说明

文档与部分代码由 AI 辅助创作，构建、签名与版本行为均已实机验证。
