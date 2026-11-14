package dclnb.dcltools;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * DclRestartService - 重启守护服务（参照弑神者 GodRestartService）。
 *
 * 职责：每5秒检查核心服务是否运行，未运行则强制重启。
 * 与弑神者相同：专门守护 DclCoreService。
 */
public class DclRestartService extends Service {

    private static final String TAG = "DclRestart";
    private static final long CHECK_INTERVAL_MS = 5000L;

    private Handler handler;
    private Runnable checkTask;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());

        // 需求：防护默认关闭，只有用户手动开启时才运行。
        // 防护关闭时，即使服务被拉起也不启动任何任务。
        if (!getSharedPreferences("dcl_prefs", MODE_PRIVATE)
                .getBoolean("protection_enabled", false)) {
            Log.d(TAG, "防护未开启，DclRestartService 不启动任何任务");
            stopSelf();
            return;
        }

        startCheckTask();
        Log.d(TAG, "DclRestartService 已启动");
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
        // 需求：仅当防护开启时才自启，防护关闭时不重启
        if (getSharedPreferences("dcl_prefs", MODE_PRIVATE)
                .getBoolean("protection_enabled", false)) {
            try {
                startService(new Intent(this, DclRestartService.class));
            } catch (Exception ignored) {}
        }
        Log.d(TAG, "DclRestartService 已停止");
    }

    public static void ensureStarted(Context context) {
        if (context == null) return;
        try {
            context.startService(new Intent(context, DclRestartService.class));
        } catch (Exception e) {
            Log.w(TAG, "启动重启守护失败", e);
        }
    }

    private void startCheckTask() {
        stopCheckTask();
        checkTask = new Runnable() {
            @Override
            public void run() {
                try {
                    checkAndRestart();
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

    private void checkAndRestart() {
        if (!DclCoreService.isRunning(this)) {
            Log.d(TAG, "核心服务未运行，尝试重启");
            DclCoreService.ensureStarted(this);
        }
    }
}