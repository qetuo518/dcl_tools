package dclnb.dcltools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * DclWakeReceiver - 唤醒接收器。
 *
 * 用于接收外部唤醒广播（如保活助手发来的广播），拉起所有守护组件。
 * 必须 exported=true，否则外部应用的广播投不进来。
 */
public class DclWakeReceiver extends BroadcastReceiver {

    private static final String TAG = "DclWakeReceiver";

    /** 与保活助手侧 KeepAliveService.ACTION_WAKE_TARGET 对应 */
    public static final String ACTION_WAKE_TARGET = "dclnb.dcltools.action.WAKE_TARGET";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ACTION_WAKE_TARGET.equals(intent.getAction())) return;

        Log.d(TAG, "收到唤醒广播");

        // 拉起核心常驻服务
        try {
            DclCoreService.ensureStarted(context);
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

        // 拉起守护服务（如果防护开关仍开着）
        try {
            SharedPreferences prefs = context.getSharedPreferences("dcl_prefs", Context.MODE_PRIVATE);
            if (prefs.getBoolean("protection_enabled", false)) {
                context.startService(new Intent(context, GuardService.class));
            }
        } catch (Throwable ignored) {}

        // 续订兜底闹钟
        try {
            DclForceAlarmReceiver.schedule(context);
        } catch (Throwable ignored) {}
    }
}
