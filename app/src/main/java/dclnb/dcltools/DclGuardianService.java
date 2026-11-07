package dclnb.dcltools;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * DclGuardianService - 守护守护服务（参照弑神者 GodCoreGuardianService）。
 *
 * 职责：每500ms高频检查所有守护组件是否运行，被杀则立即重启。
 * 与弑神者的相同逻辑：self-check 防止自己也被杀漏掉。
 */
public class DclGuardianService extends Service {

    private static final String TAG = "DclGuardian";
    private static final long CHECK_INTERVAL_MS = 500L;

    private Handler handler;
    private Runnable checkTask;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        startCheckTask();
        Log.d(TAG, "DclGuardianService 已启动，监听所有守护组件");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopCheckTask();
        try {
            startService(new Intent(this, DclGuardianService.class));
        } catch (Exception ignored) {}
        Log.d(TAG, "DclGuardianService 已停止");
    }

    public static void ensureStarted(Context context) {
        if (context == null) return;
        try {
            context.startService(new Intent(context, DclGuardianService.class));
        } catch (Exception e) {
            Log.w(TAG, "启动守护守护失败", e);
        }
    }

    private void startCheckTask() {
        stopCheckTask();
        checkTask = new Runnable() {
            @Override
            public void run() {
                try {
                    checkAndRestartAll();
                } catch (Exception ignored) {}
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        handler.postDelayed(checkTask, CHECK_INTERVAL_MS);
    }

    private void stopCheckTask() {
        if (checkTask != null && handler != null) {
            handler.removeCallbacks(checkTask);
            checkTask = null;
        }
    }

    private void checkAndRestartAll() {
        // 检查 DclCoreService
        if (!isServiceRunning(DclCoreService.class)) {
            Log.d(TAG, "DclCoreService 未运行，尝试重启");
            DclCoreService.ensureStarted(this);
        }
        // 检查 GuardService
        if (!isServiceRunning(GuardService.class)) {
            Log.d(TAG, "GuardService 未运行，尝试重启");
            try {
                startService(new Intent(this, GuardService.class));
            } catch (Exception ignored) {}
        }
        // 检查 DclRestartService
        if (!isServiceRunning(DclRestartService.class)) {
            Log.d(TAG, "DclRestartService 未运行，尝试重启");
            DclRestartService.ensureStarted(this);
        }
        // 检查自身
        if (!isServiceRunning(DclGuardianService.class)) {
            Log.d(TAG, "DclGuardianService 未运行，尝试重启");
            startService(new Intent(this, DclGuardianService.class));
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
            if (info.service.getClassName().equals(serviceClass.getName())) {
                return true;
            }
        }
        return false;
    }
}