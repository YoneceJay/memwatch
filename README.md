# 内存监视器（com.dsh.memwatch）

实时监控系统与各进程内存占用，可挂在悬浮窗上长期显示。零第三方依赖、无网络权限、无广告，
APK 约 41 KB，常驻内存只有十几 MB 量级。

## 功能

**悬浮窗（自绘 Canvas，不是布局）**

- 顶行：占用百分比 + 已用/总量，超过告警阈值整块变红
- 进度条 + 最近 90 个采样点的占用曲线
- 第二行：本应用占用、进程数、本帧采样耗时
- 展开后显示占用最高的 N 个应用（4/6/8/12/20/全部可选）
- 拖动移动（松手自动贴边）、点按展开/收起、长按关闭
- 背景不透明度 4 档、字号 5 档、位置记忆

**采样**

- 刷新间隔：0.5 / 1 / 2 / 3 / 5 / 10 / 30 / 60 秒，随时可改，立即生效
- 两种数据源：
  - 快速模式：`ps -A -o PID,RSS,NAME`，一次拿到全部进程的 RSS
  - 精确模式：`dumpsys meminfo` 的 "Total PSS by process"，按包名合并多进程
- 精确模式自动降频（默认约 8 秒一次），避免拖慢机器
- 息屏自动停止采样，亮屏恢复（可关）
- 单采样线程 + 对象池复用，刷新不产生垃圾；悬浮窗每帧只画一次 Canvas

**主界面**

- 系统总览：总量 / 已用 / 可用 / 低内存阈值 / 低内存状态
- 本应用明细：PSS、Java heap、Native heap、Code、Stack、Graphics、Swap
- 应用内存排行（多进程自动合并，标注「N 个进程」）
- 全部设置项持久化；悬浮窗开关、行数、告警阈值、透明度、字号、进度条/曲线开关

## 权限

| 权限 | 用途 | 时机 |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 | 首次开启悬浮窗时引导授权 |
| `FOREGROUND_SERVICE` | 悬浮窗保活 | 静态声明 |
| `POST_NOTIFICATIONS` | 前台服务通知 / 内存告警 | Android 13+ 启动时询问 |
| `QUERY_ALL_PACKAGES` | 读取应用名与系统应用标记 | 静态声明 |

不需要网络、位置、存储、无障碍、短信等任何权限。

## 关于「所有应用的内存」

Android 8（API 26）起，系统不再向普通应用开放其它进程的 `/proc` 与
`ActivityManager.getRunningAppProcesses()` 全量数据，第三方应用只能拿到自己。
这是系统限制，所以本应用提供三条通道，按优先级自动选择：

| 通道 | 前提 | 能力 | 代价 |
|---|---|---|---|
| ROOT | 设备已 root 且授权 su | 全部进程 RSS + 按需 PSS | 需 root |
| **Shizuku** | 装 Shizuku，用无线调试激活一次，授权本应用 | **同 ROOT**（借 shell 身份跑 `ps` / `dumpsys meminfo`） | 无需 root；重启后需重新激活 Shizuku |
| 受限 | 无 | 系统总览 + 本应用明细 | 看不到别人 |

### Shizuku 接入

1. 安装 Shizuku（shizuku.rikka.app/download，或应用商店/F-Droid）
2. 打开手机「开发者选项 → 无线调试」，在 Shizuku 里点「通过无线调试启动」，输入配对码
3. 回到本应用，点「授予 Shizuku 权限」，在弹窗里点允许
4. 列表立刻从 1 行变成全部应用，通道名显示 `shizuku/ps`（开启精确 PSS 后是 `shizuku/PSS`）

技术实现：官方 `dev.rikka.shizuku:api` 的 aar 不含 AIDL 生成类，本工程用 `aidl` 编译器把
`moe.shizuku.server.IShizukuService/IRemoteProcess/...` 生成到 `src-gen/`，再配合
`libs/` 里的三个官方 jar 一起编译进 dex。命令执行走 `newProcess`（v13 里它是私有方法，
用反射调用；协议是 AIDL 事务号 7，与服务端一致）。

## 兼容性

- Android 5.0（API 21）～ Android 16（SDK 36）
- targetSdk 33，避开 Android 14+ 前台服务类型强制要求，老系统也不报错
- 悬浮窗类型按版本切换：API 26+ 用 `TYPE_APPLICATION_OVERLAY`，以下用 `TYPE_PHONE`
- 所有 su 调用带超时，拿不到权限 3 秒内降级，绝不卡住界面
- `ps` 输出格式不兼容时自动退回受限模式

## 构建

```bash
bash "/sdcard/DeepSeek Harness/内存监视器/build.sh"
```

产物：项目根目录 `memwatch.apk`。工具链随目录带齐，不需要 Gradle 与网络。

## 结构

```
src/com/dsh/memwatch/
├── MainActivity.java    主界面：总览卡片 + 全部设置 + 应用排行
├── OverlayService.java  前台服务：悬浮窗生命周期、拖动、点按、告警通知
├── OverlayView.java     悬浮窗自绘视图（Paint + Canvas，无子 View）
├── Sampler.java         采样引擎：单线程、对象池、inFlight 闸门、息屏节流
├── RootReader.java      su 探测与带超时的命令执行
├── Parser.java          ps / dumpsys 输出解析（纯 Java，可离线测试）
├── Proc.java            进程条目 + 包名推断
├── ProcPool.java        对象池
├── Snap.java            采样快照 + 占用率环形缓冲
├── Prefs.java           全部设置项
└── Fmt.java             轻量格式化（无 String.format 开销）
```


## 1.5 更新

- **固定应用子悬浮窗**：主界面长按任意应用即可固定，右侧会出现一个更小的独立悬浮窗只显示固定项，可单独拖动、点击回到主界面
- **6 套主题**：深色 / 浅色 / 蓝色科技 / 绿色护眼 / 橙色提醒（提醒色为橙色）/ 高对比
- **透明度拆成两项**：文字透明度、背景透明度，各五档（20/40/60/80/100）+ 自定义输入；字号、告警阈值、刷新间隔等同样支持自定义
- **独立设置页**：主题、透明度、悬浮窗参数、数据源、Shizuku、固定列表都移入设置页；主界面只留悬浮窗开关与 6 个常用开关
- **修复重复行 bug**：`Sampler.aggregate()` 的 `grouped` 列表之前每轮只增不清，导致同一应用重复 N 行、列表越长越卡、内存持续上涨
- **降低内存**：悬浮窗内容未变化时不重排不重绘；固定集合每帧只算一次；应用名走 LruCache

## 仓库

https://github.com/lichengwei2010/memwatch

工具链不进仓库（41MB 且 `/sdcard` 是 noexec），按 README 的构建说明把 `toolchain/` 放到项目目录即可。
`libs/` 里是 Shizuku 官方发布的 3 个 aar 解出的 jar（Apache-2.0），`src-gen/` 由 `scripts/gen-aidl.sh` 从 `aidl/` 生成。

## 已知边界

- 无 ROOT 且未接 Shizuku 时看不到其它应用，这是 Android 的平台限制
- Shizuku 通道需要 Shizuku 服务在运行（设备重启后要重新激活）
- `dumpsys meminfo` 在低配机上单次约 0.5~2 秒，默认已自动降频
- PSS 与 RSS 口径不同：PSS 是「实际分摊」，RSS 会把共享库重复计算，数值偏大属正常
