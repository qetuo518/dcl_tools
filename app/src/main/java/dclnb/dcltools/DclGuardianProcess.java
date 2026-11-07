package dclnb.dcltools;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * DclGuardianProcess - 独立进程的守护服务（参照弑神者 GodGuardianProcess）。
 *
 * 关键点：在 AndroidManifest.xml 中声明 android:process=":dclguardian"，
 * 这样它在独立进程中运行。主进程被杀后，本服务仍能存活（只要系统还没杀这个进程），
 * 存活期间会尝试重启主进程。
 *
 * 同时被主进程中的 DclGuardianService 守护，形成双向守护。
 */
public class DclGuardianProcess extends Service {

    private static final String TAG = "DclGuardianProc";
    private static final long CHECK_INTERVAL_MS = 3000L;

    private static volatile boolean sIsRunning = false;
    private Handler handler;
    private Runnable monitorTask;

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = true;
        handler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "独立守护进程已启动");
        startMonitorTask();
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
        sIsRunning = false;
        stopMonitorTask();
        try {
            startService(new Intent(this, DclGuardianProcess.class));
        } catch (Exception ignored) {}
        Log.d(TAG, "独立守护进程已停止");
    }

    public static void ensureStarted(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, DclGuardianProcess.class);
            intent.setPackage(context.getPackageName());
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "启动独立守护进程失败", e);
        }
    }

    private void startMonitorTask() {
        stopMonitorTask();
        monitorTask = new Runnable() {
            @Override
            public void run() {
                try {
                    checkAndRestart();
                } catch (Exception ignored) {}
                if (sIsRunning) {
                    handler.postDelayed(this, CHECK_INTERVAL_MS);
                }
            }
        };
        handler.postDelayed(monitorTask, CHECK_INTERVAL_MS);
    }

    private void stopMonitorTask() {
        if (monitorTask != null && handler != null) {
            handler.removeCallbacks(monitorTask);
            monitorTask = null;
        }
    }

    private void checkAndRestart() {
        // 检查主进程是否还在
        if (!isMainProcessRunning()) {
            Log.d(TAG, "主进程已停止，尝试重启");
            restartMainProcess();
        }
        // 同时检查主进程中的守护服务
        if (!isServiceRunning(DclGuardianService.class)) {
            Log.d(TAG, "DclGuardianService 未运行，尝试重启");
            DclGuardianService.ensureStarted(this);
        }
    }

    /** 检查主进程是否在运行 */
    private boolean isMainProcessRunning() {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                am.getRunningAppProcesses();
        if (processes == null) return false;
        String selfPkg = getPackageName();
        for (android.app.ActivityManager.RunningAppProcessInfo info : processes) {
            if (info.processName.equals(selfPkg) || info.processName.equals(selfPkg + ":core")) {
                return true;
            }
        }
        return false;
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

    private void restartMainProcess() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            Log.d(TAG, "主进程重启成功");
        } catch (Exception e) {
            Log.e(TAG, "主进程重启失败", e);
        }
    }
}