package dclnb.dcltools;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * GuardService - 守护服务
 * 管理防护状态、悬浮窗（红条）和无障碍保活。
 *
 * 无障碍保活策略（参照 xtsafe-main，修复旧版问题）：
 *   1. 不再暴力覆盖整个 enabled_accessibility_services（旧版会顺带杀掉
 *      其它应用的无障碍服务），改为「读当前列表 → 只清理自身两种写法残留
 *      → 追加标准写法」，保留其它服务。
 *   2. 不再盲目高频写入：先用 AccessibilityManager 检查服务是否真正在运行，
 *      列表在且运行中就跳过写操作；只在掉线时写回，触发系统重建服务。
 *   3. 状态从持久化存储读取，进程被杀重建后防护自动恢复（旧版 onCreate
 *      硬编码 isProtected=false，重启后即使开关还开着也不会显示红条/保活）。
 *   4. 兜底层级：本服务（30 秒轮询）+ DclCoreService（独立进程外服务）
 *      + DclKeepAliveReceiver（60 秒系统闹钟，独立于进程存活）。
 */
public class GuardService extends Service {

    private static final String TAG = "GuardService";
    private static final String PREFS_NAME = "dcl_prefs";
    private static final String KEY_PROTECTION_ENABLED = "protection_enabled";
    private static final String KEY_STRONG_KEEP_ALIVE = "strong_keep_alive";

    private static final String SELF_PACKAGE = "dclnb.dcltools";
    private static final String ACC_SERVICE_CLASS = "dclnb.dcltools.DclAccessibilityService";
    private static final String COMPONENT_STD = SELF_PACKAGE + "/" + ACC_SERVICE_CLASS;
    private static final String COMPONENT_SHORT = SELF_PACKAGE + "/.DclAccessibilityService";

    /** 静态引用，供 Activity 访问 */
    private static GuardService instance;

    private WindowManager wm;
    private View statusBarDot;
    private Handler handler;
    private boolean isProtected = false;
    private Runnable autoHideRunnable;

    // 无障碍保活定时器
    private Runnable accKeepAliveRunnable;
    private static final long ACC_KEEP_ALIVE_INTERVAL_MS = 10_000L;  // 常规 10 秒
    private static final long ACC_KEEP_ALIVE_STRONG_MS = 1_000L;     // 强保活 1 秒

    // 悬浮窗参数
    private static final int DOT_WIDTH_DP = 8;
    private static final int DOT_HEIGHT_DP = 40;
    private static final int DOT_COLOR = 0xCCFF0000; // 红色

    // 红条自动消失时间（毫秒）
    private static final long AUTO_HIDE_DELAY_MS = 5000L;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(android.os.Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        isProtected = false;
        autoHideRunnable = null;
        accKeepAliveRunnable = null;

        // 需求：防护默认关闭，只有用户手动开启时才运行。
        // 防护关闭时，即使服务被拉起也不启动任何功能。
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_PROTECTION_ENABLED, false)) {
            Log.w(TAG, "防护未开启，GuardService 不启动任何功能");
            stopSelf();
            return;
        }

        // 状态持久化恢复：进程被杀重建后，若防护开关仍开着则自动恢复防护
        startProtection();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次拉起都确保状态与开关一致（幂等）
        if (isProtected) {
            createStatusBarDot();
            startAccKeepAlive();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        removeAutoHideCallback();
        stopAccKeepAlive();
        removeStatusBarDot();
    }

    /**
     * 获取单例
     */
    public static GuardService getInstance() {
        return instance;
    }

    /**
     * 开启防护（创建红条，永久显示直到手动关闭，启动无障碍保活）
     */
    public void startProtection() {
        isProtected = true;
        removeAutoHideCallback();
        createStatusBarDot();
        startAccKeepAlive(); // 启动无障碍保活
    }

    /**
     * 关闭防护（移除红条，停止无障碍保活）
     */
    public void stopProtection() {
        isProtected = false;
        removeAutoHideCallback();
        stopAccKeepAlive(); // 停止无障碍保活
        removeStatusBarDot();
    }

    /**
     * 触发防护（红条显示5秒后自动消失）
     */
    public void triggerProtection() {
        // 先移除之前的定时任务
        removeAutoHideCallback();

        // 创建红条
        createStatusBarDot();

        // 设置5秒后自动消失
        autoHideRunnable = new Runnable() {
            @Override
            public void run() {
                if (isProtected && statusBarDot != null) {
                    removeStatusBarDot();
                }
            }
        };
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS);
    }

    /**
     * 判断是否处于防护状态
     */
    public boolean isProtected() {
        return isProtected;
    }

    // ==================== 无障碍保活 ====================

    /**
     * 启动无障碍保活轮询。
     * 旧版每 0.5 秒无条件覆盖写系统设置；新版：
     *   - 常规模式 10 秒一轮，强保活模式 1 秒一轮
     *   - 每轮先检查「在列表 + 真在运行」，正常时零写操作
     *   - 掉线时按「保留他人 + 追加自身」写回，触发系统重建
     */
    private void startAccKeepAlive() {
        stopAccKeepAlive(); // 先停止旧的

        accKeepAliveRunnable = new Runnable() {
            @Override
            public void run() {
                if (isProtected) {
                    // shell 调用可能阻塞，放到工作线程执行
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            checkAndRecoverAccessibility();
                        }
                    }, "dcl-acc-keepalive").start();
                }
                // 继续下一轮
                if (isProtected) {
                    handler.postDelayed(this, getKeepAliveInterval());
                }
            }
        };

        // 立即执行第一次
        new Thread(new Runnable() {
            @Override
            public void run() {
                checkAndRecoverAccessibility();
            }
        }, "dcl-acc-keepalive").start();
        // 开始定时任务
        handler.postDelayed(accKeepAliveRunnable, getKeepAliveInterval());
    }

    /**
     * 停止无障碍保活
     */
    private void stopAccKeepAlive() {
        if (accKeepAliveRunnable != null) {
            handler.removeCallbacks(accKeepAliveRunnable);
            accKeepAliveRunnable = null;
        }
    }

    /** 保活轮询间隔（强保活模式 1 秒，常规 10 秒） */
    private long getKeepAliveInterval() {
        boolean strong = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_STRONG_KEEP_ALIVE, false);
        return strong ? ACC_KEEP_ALIVE_STRONG_MS : ACC_KEEP_ALIVE_INTERVAL_MS;
    }

    /**
     * 检查无障碍服务是否在列表且真正运行，掉线则写回拉活。
     * 写回策略：保留其它应用的服务的写法，只清理自身的两种写法残留后追加标准写法。
     */
    private void checkAndRecoverAccessibility() {
        try {
            String list = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) list = "";

            boolean inList = false;
            for (String item : list.split(":")) {
                if (item.equals(COMPONENT_STD) || item.equals(COMPONENT_SHORT)) { inList = true; break; }
            }

            boolean running = false;
            try {
                android.view.accessibility.AccessibilityManager am =
                        (android.view.accessibility.AccessibilityManager)
                                getSystemService(ACCESSIBILITY_SERVICE);
                if (am != null) {
                    java.util.List<android.accessibilityservice.AccessibilityServiceInfo> enabled =
                            am.getEnabledAccessibilityServiceList(
                                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                    if (enabled != null) {
                        for (android.accessibilityservice.AccessibilityServiceInfo info : enabled) {
                            String id = info.getId();
                            if (COMPONENT_STD.equals(id) || COMPONENT_SHORT.equals(id)) { running = true; break; }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 列表在且运行中：无需任何写操作
            if (inList && running) return;

            // 掉线：清理自身残留后追加，保留其它应用的服务
            String clean = removeSelf(list);
            String target = clean.isEmpty() ? COMPONENT_STD : clean + ":" + COMPONENT_STD;
            boolean sOk = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure",
                    "enabled_accessibility_services", target});
            boolean rOk = ShellExecutor.execRoot(
                    "settings put secure enabled_accessibility_services '" + target + "'");
            android.util.Log.d(TAG, "无障碍掉线，写回拉活 inList=" + inList
                    + " shizuku=" + sOk + " root=" + rOk);
        } catch (Throwable ignored) {}
    }

    /** 从无障碍列表字符串中移除本服务的两种写法（标准/短），返回剩余列表 */
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

    // ==================== 悬浮窗红条 ====================

    /**
     * 创建顶部状态栏红条
     */
    private void createStatusBarDot() {
        // 先移除旧的
        if (statusBarDot != null) {
            removeStatusBarDot();
        }

        try {
            // 创建红条视图
            View dot = new View(this);
            dot.setBackgroundColor(DOT_COLOR);
            
            // 添加点击事件：点击跳转到安全防护界面
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    android.util.Log.i(TAG, ">>> 悬浮窗红条被点击");
                    try {
                        Intent intent = new Intent(GuardService.this, DclaqActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        startActivity(intent);
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "跳转失败", e);
                    }
                }
            });

            // 计算尺寸
            int width = dpToPx(DOT_WIDTH_DP);
            int height = dpToPx(DOT_HEIGHT_DP);

            // 设置布局参数
            // 说明：TYPE_ACCESSIBILITY_OVERLAY 严格来说只保证无障碍服务可用，
            // 普通服务上下文在部分系统上会抛 BadTokenException，捕获后
            // 降级为 TYPE_APPLICATION_OVERLAY / TYPE_SYSTEM_OVERLAY 重试
            int widthPx = width;
            int heightPx = height;
            WindowManager.LayoutParams params = buildDotParams(widthPx, heightPx);

            // 添加视图
            wm.addView(dot, params);
            statusBarDot = dot;
            android.util.Log.i(TAG, ">>> 红条已创建，点击可跳转防护界面");
        } catch (Exception e) {
            android.util.Log.w(TAG, "创建红条失败（ACCESSIBILITY_OVERLAY），降级重试", e);
            try {
                View dot = new View(this);
                dot.setBackgroundColor(DOT_COLOR);
                
                // 添加点击事件
                dot.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        android.util.Log.i(TAG, ">>> 悬浮窗红条被点击");
                        try {
                            Intent intent = new Intent(GuardService.this, DclaqActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                            startActivity(intent);
                        } catch (Exception e) {
                            android.util.Log.e(TAG, "跳转失败", e);
                        }
                    }
                });
                
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        dpToPx(DOT_WIDTH_DP),
                        dpToPx(DOT_HEIGHT_DP),
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.START;
                wm.addView(dot, params);
                statusBarDot = dot;
                android.util.Log.i(TAG, ">>> 红条已创建（降级模式）");
            } catch (Exception e2) {
                android.util.Log.e(TAG, "创建红条失败（降级后仍失败）", e2);
            }
        }
    }

    /** 构建红条窗口参数（优先无障碍层） */
    private WindowManager.LayoutParams buildDotParams(int width, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    /**
     * 移除顶部状态栏红条
     */
    private void removeStatusBarDot() {
        if (statusBarDot != null) {
            try {
                wm.removeView(statusBarDot);
            } catch (Exception e) {
                // ignore
            }
            statusBarDot = null;
        }
    }

    /**
     * 移除自动消失的定时任务
     */
    private void removeAutoHideCallback() {
        if (autoHideRunnable != null) {
            handler.removeCallbacks(autoHideRunnable);
            autoHideRunnable = null;
        }
    }

    /**
     * dp 转 px
     */
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}