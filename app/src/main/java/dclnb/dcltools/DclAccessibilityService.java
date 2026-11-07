package dclnb.dcltools;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
    private static final String SELF_PACKAGE = "dclnb.dcltools";
    private static final String SAFE_SHIELD_PACKAGE = "dclnb.dclaqhd";
    
    private volatile String currentForegroundPkg = "";
    private boolean isProcessing = false;

    /**
     * CONTENT_CHANGED 事件节流：高频内容变化事件最少间隔（毫秒）。
     * 无障碍回调跑在主线程，遍历整棵节点树属于重活；不加节流时主线程会被拖死，
     * 系统判定服务无响应直接解绑 → 表现为「无障碍保活不住、频繁掉线」。
     */
    private static final long CONTENT_EVENT_THROTTLE_MS = 800L;
    private long lastContentEventTime = 0L;

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
                Log.d(TAG, "窗口变化: " + packageName);
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
        Log.i(TAG, "无障碍服务已连接");
        dclToast = new DclToast(this, mainHandler);
        // 设置横幅自动消失时长为 5 秒
        dclToast.setBannerAutoHideMs(5000L);

        // 服务连接成功 = 无障碍通道恢复，立即调度下一轮保活闹钟并拉起核心服务，
        // 确保后续掉线时有兜底（闹钟独立于进程存活，由系统调度）
        try {
            DclKeepAliveReceiver.schedule(this);
        } catch (Throwable ignored) {}
        try {
            DclCoreService.ensureStarted(this);
        } catch (Throwable ignored) {}
        // 防护开关仍开启时，确保守护服务在位
        try {
            if (getSharedPreferences("dcl_prefs", MODE_PRIVATE)
                    .getBoolean("protection_enabled", false)) {
                startService(new Intent(this, GuardService.class));
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "无障碍服务已断开");
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
        
        // 已知锁机应用特征关键词
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
            "evil.lock"
        };
        
        for (String pattern : suspiciousPatterns) {
            if (lowerPkg.contains(pattern.toLowerCase())) {
                Log.d(TAG, "匹配到锁机特征: " + pattern + " in " + packageName);
                return true;
            }
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
}