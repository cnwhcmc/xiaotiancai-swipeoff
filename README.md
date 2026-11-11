# XiaoTianCai SwipeOff

适用于小天才 Z6 电话手表（Android 8.1 / msm8909w / armeabi-v7a）的 Xposed 模块，用于调整系统级交互行为：

- 屏蔽窗口层的「右滑退出」手势，使应用在该手势下不再退出；
- 重映射电源键：单击返回上一级，双击返回桌面，长按保持系统原生关机菜单；
- 熄屏状态下将电源键交还系统，保留点亮屏幕与长按弹出关机菜单的能力。

## 适用环境

| 依赖 | 要求 |
|---|---|
| root | 已解锁 Bootloader 并安装 Magisk，`su -c id` 返回 `uid=0` |
| Xposed 框架 | EdXposed 或 LSPosed，框架状态为 active |
| 模块启用 | 安装后需在框架管理器中勾选，并重启设备 |

以上任一条件不满足，模块不会被加载，日志中也不会出现相关记录。

## 功能

| 操作 | 行为 |
|---|---|
| 右滑 | 不退出应用 |
| 电源键单击 | 返回上一级 |
| 电源键双击 | 返回桌面 |
| 电源键长按 | 系统原生关机 / 重启菜单 |
| 熄屏按电源键 | 点亮屏幕，事件交还系统 |
| 熄屏长按电源键 | 点亮后弹出系统原生关机 / 重启菜单 |

短按不再熄屏。系统原始短按行为即为返回桌面，未提供独立熄屏入口，因此不属于功能缺失。

## 版本说明

| 版本 | 状态 | 说明 |
|---|---|---|
| v1 | 存档 | 拦截调用位于 `ACTION_DOWN` 派发之前，被 `ViewGroup.resetTouchState()` 清除，右滑不生效 |
| v2 | 可用 | 将拦截设置移至 DOWN 派发完成后，右滑生效，实机确认 |
| v3 | 预发布 | 增加 Dialog 与窗口回退分支。首次安装未生效、重装后正常，原因未确认 |
| v3-final | 可用 | 右滑恢复为 v2 实现，保留电源键部分的稳定性修复 |
| v4 | 推荐 | 在 v3-final 基础上修正熄屏行为 |

各版本共用包名与签名（`com.swipeoff` / versionCode 1），可直接覆盖安装。

覆盖安装后若模块无响应，需重启设备。同包名模块在覆盖安装时，system_server 仍映射原 APK，不会重新加载新代码。

## 实现方式

### 右滑退出

系统采用 AOSP 的 `windowSwipeToDismiss` 机制，窗口在 `onInterceptTouchEvent` 中检测横向滑动。子视图调用 `requestDisallowInterceptTouchEvent(true)` 后，父级不再收到拦截回调，手势随之失效。

生效关键在于调用时机。`ViewGroup.dispatchTouchEvent()` 处理 `ACTION_DOWN` 时会调用 `resetTouchState()` 清除该标志，因此在 DOWN 派发之前设置无效，必须在派发结束后补充设置一次。

### 电源键

电源键事件由 system_server 的 `PhoneWindowManager.interceptKeyBeforeQueueing()` 消费，应用层无法接收。模块在该方法中按按下与抬起时序区分单击、双击与长按：

- 单击：`InputManager.injectInputEvent` 注入 `KEYCODE_BACK`
- 双击：注入 `KEYCODE_HOME`
- 长按：反射调用 `mWindowManagerFuncs.showGlobalActions()`

熄屏状态下不做拦截，事件交还系统执行 `wakeUpFromPowerKey`，同时启动补偿计时。系统原生的长按计时仅在 `interactive` 为真时启动，熄屏路径不会进入，因此由模块在屏幕进入 interactive 后调用同一入口弹出菜单。

屏幕状态优先读取 `mScreenOnFully`，其次 `mScreenOnEarly`，最后回退 `PowerManager.isInteractive()`；全部失败时按熄屏处理，将事件交还系统，避免出现无法点亮的情况。

## 构建

不依赖 Android Studio 与 Gradle。工具链为 `aapt2`、`ecj`（本机无 javac 时替代）、`d8`、`zipalign`、`apksigner`；依赖 platform-28 的 `android.jar`、build-tools 28.0.3 与 Xposed API 82。

```
./module/build.sh
```

`assets/xposed_init` 内容为 `com.swipeoff.MainHook`。`AndroidManifest.xml` 需包含三项 meta-data：`xposedmodule`、`xposeddescription`、`xposedminversion=82`。

## 安装与验证

```
adb install -r SwipeOff-v4.apk
adb shell su -c reboot
```

验证模块是否加载：

```
adb logcat -s Xposed | grep SwipeOff
```

正常输出应包含 `SwipeOff power hook v4b`，以及各应用进程的加载记录。无任何输出说明框架未激活或模块未启用。

卸载：在框架管理器中取消勾选后重启，或执行 `adb uninstall com.swipeoff`。

## 已知限制

- 长按关机依赖反射调用 `showGlobalActions`，个别定制 ROM 可能改写该入口；
- 同包名覆盖安装后需重启设备才会重新加载模块代码；
- 仅在 Z6（Android 8.1）上实机验证，其他机型未测试。

## 免责声明

仅限用于自有设备。root 与刷机存在风险，可能导致设备损坏或失去保修。本项目仅调整手势与按键映射，不涉及定位、通信或家长端功能。

---

**注**：本文档与部分代码由 AI 辅助编写，所有版本行为均经实机验证。中国人不骗中国人。
