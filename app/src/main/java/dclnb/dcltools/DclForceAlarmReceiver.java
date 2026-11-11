package dclnb.dcltools;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * 强制兜底闹钟：
 * 由系统 Alarm 定时调度，独立于服务进程存活。服务被系统冻结/杀死后，
 * 闹钟仍会拉起本接收器，检查无障碍服务是否在列表且运行，不在则写回，
 * 触发系统重启服务。
 *
 * 性能策略：
 *  - 60 秒一次，Doze 兼容（setAndAllowWhileIdle）
 *  - 始终续订下一个闹钟，闹钟本身长期存活以兜底
 */
public class DclForceAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION = "dclnb.dcltools.FORCE_ALARM";
    private static final long INTERVAL_MS = 60_000L;
    private static final String TAG = "DclForceAlarm";

    private static final String SELF_PACKAGE = "dclnb.dcltools";
    private static final String ACC_SERVICE_CLASS = "dclnb.dcltools.DclAccessibilityService";
    private static final String COMPONENT_STD = SELF_PACKAGE + "/" + ACC_SERVICE_CLASS;
    private static final String COMPONENT_SHORT = SELF_PACKAGE + "/.DclAccessibilityService";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            // 拉起核心常驻服务（不依赖无障碍）
            try {
                DclCoreService.ensureStarted(context);
            } catch (Throwable ignored) {}

            // 拉起守护服务（如果防护开关仍开着）
            try {
                SharedPreferences prefs = context.getSharedPreferences("dcl_prefs", Context.MODE_PRIVATE);
                if (prefs.getBoolean("protection_enabled", false)) {
                    context.startService(new Intent(context, GuardService.class));
                }
            } catch (Throwable ignored) {}

            // 拉起守护守护服务
            try {
                DclGuardianService.ensureStarted(context);
            } catch (Throwable ignored) {}

            // 拉起重启守护服务
            try {
                DclRestartService.ensureStarted(context);
            } catch (Throwable ignored) {}

            // 拉起独立进程守护
            try {
                DclGuardianProcess.ensureStarted(context);
            } catch (Throwable ignored) {}

            // 检查无障碍是否需要拉活
            checkAndRecover(context);

            // 始终续订下一个闹钟
            schedule(context);
        } catch (Exception ignored) {}
    }

    /** 检查无障碍服务是否在列表且真正运行，不在则写回触发系统重启服务 */
    private void checkAndRecover(Context context) {
        try {
            String list = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean inList = false;
            if (list != null) {
                for (String item : list.split(":")) {
                    if (item.equals(COMPONENT_STD) || item.equals(COMPONENT_SHORT)) {
                        inList = true;
                        break;
                    }
                }
            }

            boolean running = false;
            try {
                AccessibilityManager am = (AccessibilityManager) context
                        .getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (am != null) {
                    List<android.accessibilityservice.AccessibilityServiceInfo> enabled =
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
            } catch (Exception ignored) {}

            if (!running) {
                String clean = removeSelf(list);
                String target = clean.isEmpty() ? COMPONENT_STD : clean + ":" + COMPONENT_STD;
                boolean sOk = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure",
                        "enabled_accessibility_services", target});
                boolean rOk = ShellExecutor.execRoot(
                        "settings put secure enabled_accessibility_services '" + target + "'");
                android.util.Log.d(TAG, "alarm: 服务未运行，写回拉活 inList=" + inList
                        + " shizuku=" + sOk + " root=" + rOk);
            }
        } catch (Exception ignored) {}
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

    /** 调度下一次兜底闹钟（Doze 兼容） */
    public static void schedule(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context, DclForceAlarmReceiver.class);
            i.setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long next = SystemClock.elapsedRealtime() + INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi);
            }
        } catch (Exception ignored) {}
    }
}
