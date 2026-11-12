package dclnb.dcltools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * 开机广播接收器。
 *
 * 需求：安全护盾默认关闭，只有用户手动开启时才启动守护/保活进程。
 * 开机后仅当用户之前手动开启过防护（protection_enabled=true）时，
 * 才恢复守护服务，否则不拉起任何守护/保活进程。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    private static final String SELF_PACKAGE = "dclnb.dcltools";
    private static final String ACC_SERVICE_CLASS = "dclnb.dcltools.DclAccessibilityService";
    private static final String COMPONENT_STD = SELF_PACKAGE + "/" + ACC_SERVICE_CLASS;
    private static final String COMPONENT_SHORT = SELF_PACKAGE + "/.DclAccessibilityService";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) &&
            !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(intent.getAction())) return;
        Log.i(TAG, "收到开机广播");

        // 延迟3秒执行（与弑神者一致，避免系统还没就绪）
        final Context ctx = context;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // 需求：默认不启动任何守护/保活进程。
                // 仅当用户之前手动开启过防护时才恢复。
                SharedPreferences prefs = ctx.getSharedPreferences("dcl_prefs", Context.MODE_PRIVATE);
                boolean protectionEnabled = prefs.getBoolean("protection_enabled", false);

                if (!protectionEnabled) {
                    Log.d(TAG, "防护未开启，开机不拉起任何守护/保活进程");
                    return;
                }

                // 1. 提权开启无障碍
                enableAccessibilityByShell(ctx);

                // 2. 防护已开启：拉起守护服务
                try { DclCoreService.ensureStarted(ctx); } catch (Throwable ignored) {}
                try { DclGuardianService.ensureStarted(ctx); } catch (Throwable ignored) {}
                try { DclRestartService.ensureStarted(ctx); } catch (Throwable ignored) {}
                try { DclGuardianProcess.ensureStarted(ctx); } catch (Throwable ignored) {}
                try { DclKeepAliveReceiver.schedule(ctx); } catch (Throwable ignored) {}
                try { DclForceAlarmReceiver.schedule(ctx); } catch (Throwable ignored) {}
                try { ctx.startService(new Intent(ctx, GuardService.class)); } catch (Throwable ignored) {}
            }
        }, 3000);
    }

    /**
     * 通过 Shizuku / Root 把本应用无障碍写入系统设置（不动其它应用的服务）。
     * 返回是否至少有一条路径成功。
     */
    private boolean enableAccessibilityByShell(Context context) {
        // 只移除本应用的两种写法，保留其它应用，再追加标准写法
        String current = android.provider.Settings.Secure.getString(
                context.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (current == null) current = "";

        StringBuilder sb = new StringBuilder();
        for (String p : current.split(":")) {
            if (p.isEmpty()) continue;
            if (p.equals(COMPONENT_STD) || p.equals(COMPONENT_SHORT)) continue;
            if (sb.length() > 0) sb.append(":");
            sb.append(p);
        }
        if (sb.length() > 0) sb.append(":");
        sb.append(COMPONENT_STD);
        final String target = sb.toString();

        boolean ok = false;
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShellExecutor.execShizuku(new String[]{"settings", "put", "secure",
                        "enabled_accessibility_services", target});
                ShellExecutor.execShizuku(new String[]{"settings", "put", "secure",
                        "accessibility_enabled", "1"});
                ok = true;
            }
        } catch (Throwable ignored) {}

        if (!ok) {
            try {
                ok = ShellExecutor.execRoot("settings put secure enabled_accessibility_services '"
                        + target + "' && settings put secure accessibility_enabled 1");
            } catch (Throwable ignored) {}
        }

        Log.d(TAG, "开机提权无障碍: target=" + target + " ok=" + ok);
        return ok;
    }
}