# DCL安全工具 (DCL Tools)

Android 安全防护工具，专注于锁机应用检测与防护、无障碍服务保活。

## 项目概述

DCL安全工具是一款 Android 安全防护应用，主要功能包括：

- **锁机应用拦截**：通过无障碍服务检测锁机/勒索应用并自动拦截
- **无障碍保活**：多层守护机制确保无障碍服务持续运行
- **音量键安全操作**：连按音量键10下触发安全清理
- **摇一摇触发**：摇动设备触发防护
- **悬浮窗防护**：状态栏红条提示
- **病毒扫描**：基于规则的特征检测
- **勒索病毒诱捕**：主动诱捕勒索病毒应用

---

## 技术架构

### 保活架构（四层兜底）

本项目的核心亮点是完善的无障碍服务保活机制，参考了多个成熟开源项目的实现：

```
┌─────────────────────────────────────────────────────────────┐
│  persistent="true" 应用（系统级保护）                       │
├─────────────────────────────────────────────────────────────┤
│  独立进程守护  │  DclGuardianProcess  │ 每3s检查主进程       │
│  高频守护层    │  DclGuardianService  │ 每500ms检查所有组件   │
│  重启守护层    │  DclRestartService   │ 每5s检查核心服务     │
│  无障碍兜底层  │  DclCoreService      │ 每30s检查无障碍      │
│              │  DclKeepAliveReceiver│ 系统闹钟60s/5s       │
│  功能守护层    │  GuardService        │ 每10s轮询保活        │
│              │  DclAccessibilityService│ 事件监听与拦截     │
└─────────────────────────────────────────────────────────────┘
```

### 保活策略详解

| 层级 | 组件 | 间隔 | 职责 |
|------|------|------|------|
| 独立进程 | `DclGuardianProcess` | 3秒 | 在独立进程中运行，主进程死亡时尝试重启 |
| 高频守护 | `DclGuardianService` | 500ms | 检查所有守护组件并重起 |
| 重启守护 | `DclRestartService` | 5秒 | 专门守护 `DclCoreService` |
| 无障碍兜底 | `DclCoreService` | 30秒 | 无障碍掉线时写回系统设置 |
| 闹钟兜底 | `DclKeepAliveReceiver` | 60秒(常规)/5秒(强保活) | 系统调度，独立于进程存活 |
| 功能守护 | `GuardService` | 10秒(常规)/1秒(强保活) | 无障碍保活 + 悬浮窗红条 |

### 无障碍保活逻辑

**问题**：旧版每0.5秒暴力写入 `enabled_accessibility_services`，会覆盖其他应用的无障碍服务。

**解决方案**：
1. 先用 `AccessibilityManager` 检查服务是否真正运行
2. 仅在掉线时写回，保留其他应用的服务
3. 写回策略：清除自身残留 → 追加标准写法

```java
// 清理自身两种写法（标准/短），保留其他服务
private static String removeSelf(String list) {
    if (list == null) return "";
    StringBuilder sb = new StringBuilder();
    for (String p : list.split(":")) {
        if (p.equals(COMPONENT_STD) || p.equals(COMPONENT_SHORT)) continue;
        if (sb.length() > 0) sb.append(":");
        sb.append(p);
    }
    return sb.toString();
}
```

### 事件节流

无障碍回调运行在主线程，`TYPE_WINDOW_CONTENT_CHANGED` 事件极其高频。
旧版每次都遍历整棵节点树，导致主线程拖死，被系统判定无响应解绑。

**解决方案**：
```java
private static final long CONTENT_EVENT_THROTTLE_MS = 800L;
private long lastContentEventTime = 0L;

if (nowTs - lastContentEventTime < CONTENT_EVENT_THROTTLE_MS) {
    return; // 节流
}
lastContentEventTime = nowTs;
// 执行节点树遍历...
```

---

## 核心组件

### DclApp
Application 入口，进程启动时拉起所有守护组件：
- `DclKeepAliveReceiver.schedule()` - 调度闹钟
- `DclCoreService.ensureStarted()` - 核心常驻服务
- `DclGuardianService.ensureStarted()` - 守护守护服务
- `DclRestartService.ensureStarted()` - 重启守护服务
- `DclGuardianProcess.ensureStarted()` - 独立进程守护

### DclCoreService
不依赖无障碍的核心服务，每30秒检查无障碍是否掉线。
采用 `START_STICKY`，被系统杀死后自动重建。

### DclKeepAliveReceiver
系统闹钟接收器，由 `AlarmManager` 调度，独立于进程存活。
每次触发时检查无障碍状态并重起核心服务。

### GuardService
守护服务，管理防护状态和悬浮窗红条。
包含无障碍保活定时器（可配置常规/强保活模式）。

### DclAccessibilityService
无障碍服务核心：
- 锁机应用检测与拦截
- 音量键连按识别
- 自动点击授权按钮
- 禁用恶意应用的无障碍服务

---

## 功能模块

### 锁机应用检测
通过包名关键词匹配检测可疑锁机应用：
```java
String[] suspiciousPatterns = {
    ".lock", "lockscreen", "ransom",
    "勒索", "锁屏", "super.lock", "evil.lock"
};
```

### 拦截流程
1. 检测到锁机应用 → 跳转动画页（抢回屏幕）
2. 禁用目标应用的无障碍服务
3. 强制停止目标应用进程
4. 兜底：冻结应用 + 回到桌面

### 音量键安全操作
连按音量键10下触发：
- 跳转拦截动画页
- 强制停止所有第三方应用（排除自身和安全护盾）
- 显示操作结果提示

---

## 权限需求

| 权限 | 用途 |
|------|------|
| `INTERNET` | API调用 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `FOREGROUND_SERVICE` | 前台服务 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗红条 |
| `QUERY_ALL_PACKAGES` | 应用列表查询 |
| `SCHEDULE_EXACT_ALARM` | 闹钟调度 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 忽略电池优化 |
| `moe.shizuku.manager.permission.API_V23` | Shizuku API |

---

##致谢

https://github.com/xiaote-xt/xtsafe 参考了进程守护，病毒扫描

## 技术栈

- **语言**：Java 8
- **最小 SDK**：16 (Android 4.1)
- **目标 SDK**：26 (Android 8.0)
- **编译 SDK**：30
- **UI方案**：原生 View + WebView
- **第三方库**：Shizuku (rikka.shizuku)

---

## 构建说明

### AIDE 环境
1. 导入项目目录 `/storage/emulated/0/AideProjects/DCL工具`
2. 确保 libs 目录下有 Shizuku 相关 aar/jar
3. 构建并运行

### Gradle 构建
```bash
./gradlew assembleDebug
```

---

## 注意事项

1. **无障碍服务必须手动开启**：首次使用需在系统设置中授权
2. **Shizuku 需要 Root 或 ADB 授权**：部分功能依赖 Shizuku 提供的高权限
3. **部分 ROM 有额外限制**：MIUI、EMUI 等可能需要手动允许后台运行
4. **闹钟权限**：Android 12+ 需要 `SCHEDULE_EXACT_ALARM` 权限

---

## License

MIT License