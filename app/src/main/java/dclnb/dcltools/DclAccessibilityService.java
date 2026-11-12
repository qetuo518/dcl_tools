package dclnb.dcltools;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * DCL 安全护盾无障碍服务
 */
public class DclAccessibilityService extends AccessibilityService {

    private static final String TAG = "DclAccessibilityService";
    private static final String PREFS_NAME = "dcl_prefs";
    private static final String KEY_TRIGGER_VOLUME = "trigger_volume";
    private static final String SELF_PACKAGE = "dclnb.dcltools";
    private static final String SAFE_SHIELD_PACKAGE = "dclnb.dclaqhd";
    
    /** 供外部（如 Agent）获取无障碍服务实例 */
    public static DclAccessibilityService instance;

    private volatile String currentForegroundPkg = "";
    private boolean isProcessing = false;
    private Handler pollingHandler;
    private Runnable pollingTask;

    /**
     * CONTENT_CHANGED 事件节流：高频内容变化事件最少间隔（毫秒）。
     * 无障碍回调跑在主线程，遍历整棵节点树属于重活；不加节流时主线程会被拖死，
     * 系统判定服务无响应直接解绑 → 表现为「无障碍保活不住、频繁掉线」。
     */
    private static final long CONTENT_EVENT_THROTTLE_MS = 800L;
    private long lastContentEventTime = 0L;

    // 主动轮询配置
    private static final long POLLING_INTERVAL_MS = 2000L;

    // DclToast 浮层提示
    private DclToast dclToast;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

    // 音量键连按检测
    private static final int VOLUME_PRESS_THRESHOLD = 10;
    /** 两次按键间隔超过该毫秒数则重新计数 */
    private static final long PRESS_RESET_GAP_MS = 2000L;
    private int pressCount = 0;
    private long lastPressTime = 0;

    /** 供外部查询当前音量键连按阈值 */
    public static int getVolumeThreshold() {
        return VOLUME_PRESS_THRESHOLD;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String packageName = event.getPackageName() != null 
                ? event.getPackageName().toString() : "";
            if (!packageName.equals(currentForegroundPkg)) {
                currentForegroundPkg = packageName;
                Log.i(TAG, ">>> 窗口状态变化: " + packageName);
            }
            // 检测锁机应用并触发拦截
            checkAndInterceptLockApp(packageName);
        }
        
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // 节流：内容变化事件极其高频，每次都遍历整棵节点树会拖死主线程，
            // 进而被系统判定无响应而解绑（这正是旧版「无障碍保活不住」的根因之一）
            long nowTs = System.currentTimeMillis();
            if (nowTs - lastContentEventTime < CONTENT_EVENT_THROTTLE_MS) {
                return;
            }
            lastContentEventTime = nowTs;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                findAndClickButton(root, new String[]{"允许", "同意", "授权", "OK", "确定"});
                root.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接");
        
        // 检测虚拟机/无传感器环境
        checkEnvironment();
        
        dclToast = new DclToast(this, mainHandler);
        // 设置横幅自动消失时长为 5 秒
        dclToast.setBannerAutoHideMs(5000L);

        // 启动主动轮询机制（防止虚拟机事件丢失）
        startPollingTask();

        // 需求：默认关闭。仅当防护开关开启时才拉起守护服务与保活闹钟。
        if (getSharedPreferences("dcl_prefs", MODE_PRIVATE)
                .getBoolean("protection_enabled", false)) {
            try {
                DclKeepAliveReceiver.schedule(this);
            } catch (Throwable ignored) {}
            try {
                DclCoreService.ensureStarted(this);
            } catch (Throwable ignored) {}
            // 防护开关仍开启时，确保守护服务在位
            try {
                startService(new Intent(this, GuardService.class));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 检测运行环境（虚拟机/无传感器等），给出明确提示
     */
    private void checkEnvironment() {
        StringBuilder sb = new StringBuilder();
        
        // 检测各类传感器
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) accel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (accel == null) accel = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (accel == null) accel = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sb.append("运动传感器: ").append(accel != null ? "✅" : "❌");
        
        // 检测Shizuku
        try {
            boolean shizukuReady = rikka.shizuku.Shizuku.pingBinder() 
                    && rikka.shizuku.Shizuku.checkSelfPermission() 
                            == PackageManager.PERMISSION_GRANTED;
            sb.append("  Shizuku: ").append(shizukuReady ? "✅" : "❌");
        } catch (Exception e) {
            sb.append("  Shizuku: ❌");
        }
        
        // 检测Root
        boolean hasRoot = false;
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            br.close();
            p.destroy();
            hasRoot = line != null && line.contains("uid=0");
        } catch (Exception e) {}
        sb.append("  Root: ").append(hasRoot ? "✅" : "❌");
        
        Log.i(TAG, ">>> 环境检测: " + sb.toString());
        
        // 如果没有可用传感器，显示提示（ShakeService 已自动启用屏幕滑动兜底）
        if (accel == null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (dclToast != null) {
                        dclToast.showBanner("当前设备无运动传感器，摇一摇功能已切换为屏幕滑动触发", null);
                    }
                }
            });
        }
    }

    /**
     * 启动主动轮询：每隔几秒检查前台应用是否可疑
     * 用于应对虚拟机环境无障碍事件丢失的情况
     */
    private void startPollingTask() {
        pollingHandler = new Handler(android.os.Looper.getMainLooper());
        pollingTask = new Runnable() {
            @Override
            public void run() {
                try {
                    checkForegroundApp();
                } catch (Exception e) {
                    Log.e(TAG, "轮询检查失败", e);
                }
                // 延迟后再次执行
                if (pollingHandler != null && pollingTask != null) {
                    pollingHandler.postDelayed(this, POLLING_INTERVAL_MS);
                }
            }
        };
        pollingHandler.post(pollingTask);
        Log.i(TAG, "已启动主动轮询机制，间隔 " + POLLING_INTERVAL_MS + "ms");
    }

    /**
     * 停止轮询
     */
    private void stopPollingTask() {
        if (pollingHandler != null && pollingTask != null) {
            pollingHandler.removeCallbacks(pollingTask);
            pollingTask = null;
        }
    }

    /**
     * 检查当前前台应用
     */
    private void checkForegroundApp() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            
            List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                    am.getRunningAppProcesses();
            if (processes == null || processes.isEmpty()) return;
            
            String foregroundPkg = null;
            for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    if (process.pkgList != null && process.pkgList.length > 0) {
                        foregroundPkg = process.pkgList[0];
                        break;
                    }
                }
            }
            
            if (foregroundPkg != null && !foregroundPkg.equals(currentForegroundPkg)) {
                currentForegroundPkg = foregroundPkg;
                Log.i(TAG, ">>> [轮询] 前台应用: " + foregroundPkg);
                checkAndInterceptLockApp(foregroundPkg);
            }
        } catch (Exception e) {
            Log.e(TAG, "检查前台应用失败", e);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "无障碍服务已断开");
        instance = null;
        stopPollingTask();
        if (dclToast != null) {
            dclToast.cleanup();
            dclToast = null;
        }
        return super.onUnbind(intent);
    }

    /**
     * 音量键连按识别（依赖配置 android:canRequestFilterKeyEvents="true"）。
     * 默认按10下触发拦截；每按一下显示 DclToast 计数。
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // 音量键触发未开启时直接放行
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_TRIGGER_VOLUME, true)) {
            return super.onKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.onKeyEvent(event);
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP) {
            return super.onKeyEvent(event);
        }

        long now = System.currentTimeMillis();
        if (now - lastPressTime > PRESS_RESET_GAP_MS) {
            pressCount = 1;
        } else {
            pressCount++;
        }
        lastPressTime = now;

        // 每按一下显示当前连按次数
        final int current = pressCount;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (dclToast != null) {
                    dclToast.showPill("音量键 " + current + "/" + VOLUME_PRESS_THRESHOLD, 1500L);
                }
            }
        });

        if (pressCount >= VOLUME_PRESS_THRESHOLD) {
            pressCount = 0;
            Log.w(TAG, "音量键连按10下，触发安全操作");
            // 先跳转到动画页面（抢回屏幕）
            try {
                Intent intent = new Intent(DclAccessibilityService.this, DcltoolljActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                startActivity(intent);
                Log.i(TAG, "已跳转拦截动画页");
            } catch (Exception e) {
                Log.e(TAG, "跳转动画页面失败", e);
            }
            // 显示红条
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (dclToast != null) {
                        dclToast.showBanner("音量键已连按10下，正在执行安全操作...", null);
                    }
                }
            });
            executeVolumeKeyAction();
            return true;
        }
        return false;
    }

    /**
     * 连按10下音量键的安全操作：强制停止全部第三方应用（排除自身与安全护盾）
     */
    private void executeVolumeKeyAction() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int count = 0;
                try {
                    List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
                    for (ApplicationInfo app : apps) {
                        if (app == null) continue;
                        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                         if (app.packageName.equals(SELF_PACKAGE)) continue;
                         if (app.packageName.equals(SAFE_SHIELD_PACKAGE)) continue;
                         if (Whitelist.isWhitelisted(DclAccessibilityService.this, app.packageName)) continue;
                         if (ShellExecutor.forceStopApp(app.packageName)) {
                            count++;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "音量键安全操作失败", e);
                }
                final int stopped = count;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dclToast != null) {
                            dclToast.showPill("安全操作完成，已停止 " + stopped + " 个应用", 3000L);
                        }
                    }
                });
            }
        }, "dcl-volume-action").start();
    }

    public String getForegroundPackage() {
        return currentForegroundPkg;
    }

    /**
     * 检查并拦截锁机应用
     */
    public void checkAndInterceptLockApp(final String packageName) {
        if (packageName.isEmpty() || packageName.equals(SELF_PACKAGE) || packageName.equals(SAFE_SHIELD_PACKAGE)) {
            return;
        }
        
        // 检查是否为可疑锁机应用
        if (!isSuspiciousLockApp(packageName)) {
            return;
        }
        
        Log.w(TAG, "检测到锁机应用: " + packageName + "，触发拦截");
        
        // 防止重复处理
        if (isProcessing) {
            Log.w(TAG, "正在处理中，跳过");
            return;
        }
        isProcessing = true;
        
        // 在后台线程执行拦截操作
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean stopped = false;
                try {
                    // 1. 先跳转动画页面（先抢回屏幕，避免锁机全屏覆盖挡住）
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showInterceptOverlay(packageName);
                        }
                    });
                    
                    // 2. 关闭目标应用的无障碍服务
                    disableAppAccessibility(packageName);
                    
                    // 3. 强制停止目标应用进程
                    stopped = ShellExecutor.forceStopApp(packageName);
                    if (!stopped) {
                        // 二次尝试：同步等待 Shizuku 返回码
                        stopped = ShellExecutor.execShizukuSync(
                            new String[]{"am", "force-stop", packageName});
                    }
                    Log.i(TAG, "强制停止 " + packageName + " 结果: " + stopped);
                    
                    if (!stopped) {
                        // 4. 兜底：冻结应用 + 回到桌面脱离锁机界面
                        ShellExecutor.execShizukuSync(
                            new String[]{"pm", "disable-user", "--user", "0", packageName});
                        ShellExecutor.execRoot("pm disable-user --user 0 " + packageName);
                        try {
                            performGlobalAction(GLOBAL_ACTION_HOME);
                        } catch (Throwable ignored) {}
                    }
                    
                    final boolean result = stopped;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dclToast == null) return;
                            if (result) {
                                dclToast.showPill("已拦截并停止: " + packageName, 3000L);
                            } else {
                                dclToast.showBanner("已拦截 " + packageName + "，但停止进程需要 Shizuku 或 Root 权限",
                                    new DclToast.OnClick() {
                                        @Override
                                        public void onClick() {
                                            if (dclToast != null) dclToast.hideBanner();
                                        }
                                    });
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "拦截操作失败", e);
                } finally {
                    isProcessing = false;
                }
            }
        }, "dcl-intercept").start();
    }

    /**
     * 显示拦截动画页 + 顶部横幅。无障碍服务具备后台启动 Activity 特权。
     */
    private void showInterceptOverlay(String packageName) {
        if (dclToast != null) {
            dclToast.showBanner("检测到锁机应用: " + packageName + "，正在拦截", new DclToast.OnClick() {
                @Override
                public void onClick() {
                    if (dclToast != null) dclToast.hideBanner();
                }
            });
        }
        try {
            Intent intent = new Intent(this, DcltoolljActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra("intercept_target", packageName);
            startActivity(intent);
            Log.i(TAG, "已跳转拦截动画页");
        } catch (Exception e) {
            Log.e(TAG, "跳转动画页面失败", e);
        }
    }

    /**
     * 判断是否为可疑锁机应用
     * 使用包名关键词匹配，排除DCL工具自身和安全护盾
     */
    private boolean isSuspiciousLockApp(String packageName) {
        // 排除自身和可信的安全应用
        if (packageName.equals(SELF_PACKAGE) || packageName.equals(SAFE_SHIELD_PACKAGE)) {
            return false;
        }
        
        String lowerPkg = packageName.toLowerCase();
        
        // 已知锁机应用特征关键词（扩展覆盖更多场景）
        String[] suspiciousPatterns = {
            // 常见锁机应用包名
            "com.hdd.lock",
            "com.stealth.lock", 
            "com.killapp.lockscreen",
            "com.android.backupconfirm",
            "com.fake.lock.screen",
            "com.trap.lock",
            // 包名包含锁相关关键词
            ".lock",
            "lockscreen",
            "ransom",
            "勒索",
            "锁屏",
            "super.lock",
            "evil.lock",
            // 扩展：更多锁机应用特征
            "lockout",
            "screenlock",
            "screen.lock",
            "device.lock",
            "mobile.security",
            "antivirus",
            "find.phone",
            "remote.wipe",
            "erase.data"
        };
        
        for (String pattern : suspiciousPatterns) {
            if (lowerPkg.contains(pattern.toLowerCase())) {
                Log.i(TAG, ">>> 匹配到锁机特征: [" + pattern + "] in " + packageName);
                return true;
            }
        }
        
        // 额外检查：如果包名不以常见合法包名前缀开头，可能是恶意应用
        // 常见合法包名前缀（避免误判系统应用）
        String[] safePrefixes = {
            "com.android.", "com.google.", "com.samsung.", "com.huawei.",
            "com.xiaomi.", "com.oplus.", "com.oneplus.", "com.ss.android.",
            "com.tencent.", "com.alibaba.", "com.baidu.", "com.bytedance.",
            "dclnb.", "aiden.", "moe.shizuku"
        };
        
        boolean isSafe = false;
        for (String prefix : safePrefixes) {
            if (lowerPkg.startsWith(prefix)) {
                isSafe = true;
                break;
            }
        }
        
        // 如果不是安全前缀且包含危险关键词
        if (!isSafe) {
            Log.i(TAG, ">>> 疑似非标准包名: " + packageName);
        }
        
        return false;
    }

    /**
     * 关闭指定应用的无障碍服务。
     * 只摘掉目标应用的组件，保留其它应用与自身的服务；目标组件通过
     * AccessibilityManager 已安装服务列表枚举得出（旧实现用
     * ACTION_ACCESSIBILITY_SETTINGS 查询的是设置页 Activity，
     * 永远拼不出正确组件名，导致本方法事实上从未生效）。
     */
    private void disableAppAccessibility(String packageName) {
        try {
            String currentList = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (currentList == null || currentList.isEmpty()) {
                return;
            }

            // 枚举目标应用声明的全部无障碍服务组件
            java.util.Set<String> targetComponents = new java.util.HashSet<String>();
            try {
                android.view.accessibility.AccessibilityManager am =
                        (android.view.accessibility.AccessibilityManager)
                                getSystemService(ACCESSIBILITY_SERVICE);
                if (am != null) {
                    List<android.accessibilityservice.AccessibilityServiceInfo> installed =
                            am.getInstalledAccessibilityServiceList();
                    if (installed != null) {
                        for (android.accessibilityservice.AccessibilityServiceInfo info : installed) {
                            if (info == null || info.getResolveInfo() == null) continue;
                            if (info.getResolveInfo().serviceInfo == null) continue;
                            if (!packageName.equals(info.getResolveInfo().serviceInfo.packageName)) continue;
                            targetComponents.add(packageName + "/" + info.getResolveInfo().serviceInfo.name);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 兜底：短写法组件名也可能出现在列表里
            targetComponents.add(packageName + "/.AccessibilityService");

            String[] parts = currentList.split(":");
            StringBuilder newList = new StringBuilder();
            int removed = 0;

            for (String p : parts) {
                if (p.isEmpty()) continue;
                if (targetComponents.contains(p)) {
                    // 排除目标应用的无障碍服务
                    removed++;
                    continue;
                }
                // 保留其它应用与自身的无障碍服务
                if (newList.length() > 0) newList.append(":");
                newList.append(p);
            }

            if (removed == 0) {
                return;
            }

            String result = newList.toString();
            // 优先使用 Shizuku
            if (ShellExecutor.execShizuku(new String[]{
                "settings", "put", "secure",
                "enabled_accessibility_services", result})) {
                Log.i(TAG, "已通过 Shizuku 禁用无障碍: " + packageName);
                return;
            }
            // 降级到 Root
            if (ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + result + "'")) {
                Log.i(TAG, "已通过 Root 禁用无障碍: " + packageName);
                return;
            }
            Log.w(TAG, "禁用无障碍缺少 Shizuku/Root 权限: " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "禁用无障碍服务失败", e);
        }
    }

    /**
     * 强制停止应用进程
     */
    private void forceStopApp(String packageName) {
        try {
            // 优先使用 Shizuku
            if (ShellExecutor.execShizuku(new String[]{"am", "force-stop", packageName})) {
                Log.i(TAG, "已通过 Shizuku 强制停止: " + packageName);
                return;
            }
            // 降级到 Root
            if (ShellExecutor.execRoot("am force-stop " + packageName)) {
                Log.i(TAG, "已通过 Root 强制停止: " + packageName);
                return;
            }
            // 最后尝试普通 API（forceStopPackage 是隐藏 API，需反射调用）
            android.app.ActivityManager am = (android.app.ActivityManager) 
                getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                try {
                    java.lang.reflect.Method m = am.getClass().getMethod(
                        "forceStopPackage", String.class);
                    m.invoke(am, packageName);
                    Log.i(TAG, "已通过普通 API 强制停止: " + packageName);
                } catch (Exception re) {
                    Log.e(TAG, "普通 API 强制停止不可用（需要系统权限）", re);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "强制停止应用失败", e);
        }
    }

    private void findAndClickButton(AccessibilityNodeInfo node, String[] targets) {
        if (node == null || node.getChildCount() == 0) return;
        
        if (node.getText() != null) {
            CharSequence text = node.getText();
            for (String target : targets) {
                if (text.toString().contains(target)) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.d(TAG, "已点击按钮: " + text);
                    }
                    return;
                }
            }
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findAndClickButton(child, targets);
                child.recycle();
            }
        }
    }

    /**
     * 通过无障碍服务点击屏幕指定坐标
     */
    public boolean click(int x, int y) {
        try {
            android.view.accessibility.AccessibilityNodeInfo node = getRootInActiveWindow();
            if (node == null) {
                Log.e(TAG, "click: 无法获取窗口节点");
                return false;
            }
            android.view.accessibility.AccessibilityNodeInfo target = findNodeAt(node, x, y);
            if (target != null) {
                boolean result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                target.recycle();
                node.recycle();
                Log.i(TAG, "click(" + x + "," + y + "): " + (result ? "成功" : "失败"));
                return result;
            }
            node.recycle();
            // 兜底：通过系统 input tap 命令
            return executeTapCommand(x, y);
        } catch (Exception e) {
            Log.e(TAG, "click 异常", e);
            return false;
        }
    }

    /**
     * 通过无障碍服务查找文本并点击
     */
    public boolean textClick(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.e(TAG, "textClick: 无法获取窗口节点");
                return false;
            }
            boolean result = findAndClickByText(root, text);
            root.recycle();
            Log.i(TAG, "textClick(\"" + text + "\"): " + (result ? "成功" : "失败"));
            return result;
        } catch (Exception e) {
            Log.e(TAG, "textClick 异常", e);
            return false;
        }
    }

    /**
     * 通过无障碍服务启动应用
     */
    public boolean launchApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            Log.e(TAG, "launchApp: 包名为空");
            return false;
        }
        try {
            android.content.Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.i(TAG, "launchApp(" + packageName + "): 成功");
                return true;
            } else {
                Log.e(TAG, "launchApp(" + packageName + "): 无法获取启动 Intent");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "launchApp 异常", e);
            return false;
        }
    }

    /**
     * 通过无障碍服务/Shell 在屏幕上执行滑动
     * @param startXY 起始坐标，格式 "x,y"
     * @param endXY   结束坐标，格式 "x,y"
     */
    public boolean swipe(String startXY, String endXY) {
        try {
            String[] sParts = startXY.split(",");
            String[] eParts = endXY.split(",");
            if (sParts.length < 2 || eParts.length < 2) return false;
            int sx = Integer.parseInt(sParts[0].trim());
            int sy = Integer.parseInt(sParts[1].trim());
            int ex = Integer.parseInt(eParts[0].trim());
            int ey = Integer.parseInt(eParts[1].trim());
            return execSwipeCommand(sx, sy, ex, ey);
        } catch (Exception e) {
            Log.e(TAG, "swipe 异常", e);
            return false;
        }
    }

    private boolean execSwipeCommand(int sx, int sy, int ex, int ey) {
        try {
            if (rikka.shizuku.Shizuku.pingBinder()
                    && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return ShellExecutor.execShizuku(
                        new String[]{"input", "swipe",
                                String.valueOf(sx), String.valueOf(sy),
                                String.valueOf(ex), String.valueOf(ey)});
            }
            return ShellExecutor.execRoot("input swipe " + sx + " " + sy + " " + ex + " " + ey);
        } catch (Exception e) {
            Log.e(TAG, "swipe command 失败", e);
            return false;
        }
    }

    /**
     * 获取当前屏幕所有可见文字
     * @return 文本内容，换行分隔
     */
    public String getAllText() {
        StringBuilder sb = new StringBuilder();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                collectText(root, sb);
                root.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllText 异常", e);
        }
        return sb.toString();
    }

    /**
     * 递归遍历节点收集文本
     */
    private void collectText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;

        // 收集当前节点的文本
        CharSequence text = node.getText();
        CharSequence contentDesc = node.getContentDescription();
        if (text != null && text.length() > 0) {
            sb.append(text.toString()).append("\n");
        }
        if (contentDesc != null && contentDesc.length() > 0) {
            sb.append(contentDesc.toString()).append("\n");
        }

        // 递归遍历子节点
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, sb);
                child.recycle();
            }
        }
    }

    // ========== 内部辅助方法 ==========

    private AccessibilityNodeInfo findNodeAt(AccessibilityNodeInfo root, int x, int y) {
        if (root == null) return null;
        android.graphics.Rect bounds = new android.graphics.Rect();
        root.getBoundsInScreen(bounds);
        if (bounds.contains(x, y)) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findNodeAt(child, x, y);
                if (found != null) return found;
                child.recycle();
            }
        }
        return null;
    }

    private boolean findAndClickByText(AccessibilityNodeInfo root, String text) {
        if (root == null) return false;
        CharSequence nodeText = root.getText();
        if (nodeText != null && nodeText.toString().contains(text)) {
            return root.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                if (findAndClickByText(child, text)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }

    private boolean executeTapCommand(int x, int y) {
        try {
            // 优先使用 Shizuku
            if (rikka.shizuku.Shizuku.pingBinder() 
                    && rikka.shizuku.Shizuku.checkSelfPermission() 
                            == PackageManager.PERMISSION_GRANTED) {
                return ShellExecutor.execShizuku(
                    new String[]{"input", "tap", String.valueOf(x), String.valueOf(y)});
            }
            // 降级到 Root
            return ShellExecutor.execRoot("input tap " + x + " " + y);
        } catch (Exception e) {
            Log.e(TAG, "executeTapCommand 异常", e);
            return false;
        }
    }
}