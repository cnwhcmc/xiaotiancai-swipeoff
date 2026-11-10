# 小天才 Z6 手势改造模块（SwipeOff）

装在小天才电话手表上的一个 Xposed 模块，干两件事：让所有应用的右滑退出失效，把电源键改成返回键。

机器是 Z6，Android 8.1，msm8909w，armeabi-v7a。别的机型没测过，别拿这个当通用方案。

## 用之前

- 手表得 root（Magisk），`su -c id` 能看到 `uid=0`
- 得装 Xposed 框架，EdXposed 或者 LSPosed，状态必须是 active
- 模块装完要在框架管理器里勾上，然后重启一次

这三样缺一样，模块就是个摆设，日志里一条都不会有。

## 改完是什么样

| 操作 | 结果 |
|---|---|
| 右滑 | 不退出 |
| 电源键单击 | 返回上一级（等 350ms 判是不是双击） |
| 电源键双击 | 回桌面 |
| 电源键长按 | 关机菜单，原生的，没动 |

代价是短按不再熄屏。原机短按本来就是回桌面，没有独立的熄屏入口，所以不算丢功能。

## 三个版本

**v1**：右滑没效果。`requestDisallowInterceptTouchEvent` 设在 `ACTION_DOWN` 派发之前，`ViewGroup.dispatchTouchEvent` 里 `resetTouchState()` 会把这个标志清掉。电源键那部分倒是正常。只留作记录，别装。

**v2**：把设置挪到 DOWN 派发结束之后补一次，右滑生效了，实机确认。这是确定能用的版本。

**v3**：在 v2 基础上加了 Dialog 分支和窗口兜底，装完第一次没反应，第二次装才正常。第二次能用，但我不能确定第一次失灵是代码问题还是模块缓存没刷新，所以这版的右滑状态存疑。装完先重启一次再测，右滑还退就换 v2。

**v3-final（推荐）**：右滑代码原样退回 v2，电源键那边留着 v3 的修复——双击后不再补一次 BACK、长按后 350ms 内单击不再误判成双击、按住产生的 repeat 事件不再漏给系统、方法签名对不上时自动从 `interceptKeyBeforeQueueing` 退到 `interceptKeyBeforeDispatching`。

三个包名签名都一样，直接覆盖装，不用卸。

覆盖装完没反应别急着判死，先重启一次或者再装一遍。同包名的模块有时候要等 system_server 重新加载才认。

## 原理

小天才这套右滑退出就是 AOSP 的 `windowSwipeToDismiss`，窗口在 `onInterceptTouchEvent` 里判断横向滑动。子 View 调 `requestDisallowInterceptTouchEvent(true)`，父级就不再收到拦截回调，手势废掉。MT 管理器的图片查看器能免疫，就是因为里面 ViewPager 按下的那一刻干了这件事。

坑在时机。DOWN 派发前设的标志会被 `resetTouchState()` 清掉，必须在派发之后再设。

电源键在 system_server 的 `PhoneWindowManager.interceptKeyBeforeQueueing` 里被吃掉，应用层根本收不到，只能 hook 这里自己分短按、双击、长按。长按弹菜单靠反射 `mWindowManagerFuncs.showGlobalActions()`。

## 自己编

没用 Studio，也没用 Gradle，这套命令能跑通：

```
aapt2 link    打资源包
ecj           编 MainHook.class（机器上没有 javac，用 ecj 顶）
d8            出 classes.dex
zip           塞进 apk
zipalign      4 字节对齐
apksigner     签名
```

`module/build.sh` 里都写好了，改改路径就能用。要 platform-28 的 android.jar、build-tools 28.0.3、Xposed api 82 的 jar。

`assets/xposed_init` 里只有一行 `com.swipeoff.MainHook`。Manifest 里三个 meta-data 不能少：`xposedmodule`、`xposeddescription`、`xposedminversion=82`。

## 装

```
adb install -r SwipeOff-final.apk
adb shell su -c reboot
```

看有没有真进来：

```
adb logcat -s Xposed | grep SwipeOff
```

出现 `SwipeOff power hook` 才算加载了。日志一片空，说明框架没激活或者模块没勾。

不想要了：框架管理器取消勾选，重启；或者 `adb uninstall com.swipeoff`。

## 免责

自己设备自己负责，刷机 root 有变砖风险。这模块只动手势和按键，不碰定位、通信、家长端那些东西。

---

**注**：此模块使用了**AI 辅助编写**，但是**每个版本都在实机上装过**，中国人不骗中国人，不行就退回 v2。
