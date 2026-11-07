package dclnb.dcltools;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * DclCoreService - 核心常驻服务（普通 Service，不依赖无障碍）。
 *
 * 背景：大部分防护功能原先挂在无障碍服务 DclAccessibilityService 上，
 * 无障碍一旦被关闭，进程直接消失，连基础防护也跟着停摆。
 *
 * 本服务只做「不需要无障碍」的那部分：
 *   1. 自身无障碍是否掉线的兜底检查（写回 enabled_accessibility_services）
 *
 * 参考 xtsafe-main 的 XTSafeCoreService 设计：
 *   - 用普通 startService（非 startForegroundService），不需要 foregroundServiceType
 *   - 返回 START_STICKY：被系统杀死后尽量自动重建
 *
 * ===== 拉起途径（多入口，任一存活即可）=====
 *   · App.onCreate
 *   · BootReceiver（开机广播）
 *   · DclKeepAliveReceiver（60 秒兜底闹钟，始终续订）
 */
public class DclCoreService extends Service {

    private static final String TAG = "DclCoreService";
    private static final long ACC_CHECK_INTERVAL_MS = 30_000L;
    private static final String SELF_PACKAGE = "dclnb.dcltools";
    private static final String ACC_SERVICE_CLASS = "dclnb.dcltools.DclAccessibilityService";
    private static final String COMPONENT_STD = SELF_PACKAGE + "/" + ACC_SERVICE_CLASS;
    private static final String COMPONENT_SHORT = SELF_PACKAGE + "/.DclAccessibilityService";

    private Handler handler;
    private Runnable accCheckTask;

    public static boolean isRunning(Context context) {
        if (context == null) return false;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (android.app.ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
                if (info != null && DclCoreService.class.getName().equals(info.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "检查服务状态失败", e);
        }
        return false;
    }

    public static void ensureStarted(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(context, DclCoreService.class);
            context.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "startService 失败，等待其他入口拉起", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        startAccCheckTask();
        Log.d(TAG, "DclCoreService onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "DclCoreService onDestroy");
        stopAccCheckTask();
        try {
            Intent i = new Intent(this, DclCoreService.class);
            startService(i);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAccCheckTask() {
        stopAccCheckTask();
        accCheckTask = new Runnable() {
            @Override
            public void run() {
                try {
                    checkAccessibilityAlive();
                } catch (Throwable ignored) {}
                finally {
                    if (accCheckTask != null && handler != null) {
                        handler.postDelayed(this, ACC_CHECK_INTERVAL_MS);
                    }
                }
            }
        };
        handler.postDelayed(accCheckTask, ACC_CHECK_INTERVAL_MS);
    }

    private void stopAccCheckTask() {
        if (accCheckTask != null && handler != null) {
            handler.removeCallbacks(accCheckTask);
            accCheckTask = null;
        }
    }

    private void checkAccessibilityAlive() {
        try {
            String list = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) list = "";
            boolean inList = false;
            for (String item : list.split(":")) {
                if (item.equals(COMPONENT_STD) || item.equals(COMPONENT_SHORT)) {
                    inList = true;
                    break;
                }
            }

            boolean running = false;
            try {
                android.view.accessibility.AccessibilityManager am =
                        (android.view.accessibility.AccessibilityManager)
                                getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (am != null) {
                    java.util.List<android.accessibilityservice.AccessibilityServiceInfo> enabled =
                            am.getEnabledAccessibilityServiceList(
                                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                    if (enabled != null) {
                        for (android.accessibilityservice.AccessibilityServiceInfo info : enabled) {
                            String id = info.getId();
                            if (COMPONENT_STD.equals(id) || COMPONENT_SHORT.equals(id)) {
                                running = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            if (inList && running) return;

            String clean = removeSelf(list);
            String target = clean.isEmpty() ? COMPONENT_STD : clean + ":" + COMPONENT_STD;
            boolean sOk = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure",
                    "enabled_accessibility_services", target});
            boolean rOk = ShellExecutor.execRoot(
                    "settings put secure enabled_accessibility_services '" + target + "'");
            Log.d(TAG, "无障碍掉线写回拉活 inList=" + inList + " shizuku=" + sOk + " root=" + rOk);
        } catch (Throwable ignored) {}
    }

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
}