package dclnb.dcltools;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * 勒索病毒诱捕守护服务。
 */
public class RansomwareTrapService extends Service {

    private static final String TAG = "RansomwareTrap";
    private static final String PREFS_KEY = "ransomware_trap_enabled";
    private static final long CHECK_INTERVAL_MS = 1000L;

    private Handler handler;
    private Runnable checkTask;
    private AtomicBoolean running = new AtomicBoolean(false);

    public static void start(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startService(new Intent(ctx, RansomwareTrapService.class));
        } catch (Throwable ignored) {}
    }

    public static void stop(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, RansomwareTrapService.class));
        } catch (Throwable ignored) {}
    }

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            SharedPreferences p = ctx.getSharedPreferences("dot_config", Context.MODE_PRIVATE);
            return p.getBoolean(PREFS_KEY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) {
            Log.d(TAG, "已停用，停止服务");
            stopSelf();
            return START_NOT_STICKY;
        }
        postNotification();
        startCheckLoop();
        ensureBaitFile();
        try {
            startForeground(0x8801, getNotification());
        } catch (Throwable ignored) {}
        Log.d(TAG, "onStartCommand 开始监控");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopCheckLoop();
        running.set(false);
        try {
            if (isEnabled(this)) {
                startService(new Intent(this, RansomwareTrapService.class));
            }
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startCheckLoop() {
        stopCheckLoop();
        running.set(true);
        checkTask = new Runnable() {
            @Override
            public void run() {
                if (!running.get()) return;
                try {
                    checkBaitFile();
                } catch (Throwable t) {
                    Log.w(TAG, "checkBaitFile error", t);
                } finally {
                    if (running.get() && handler != null) {
                        handler.postDelayed(this, CHECK_INTERVAL_MS);
                    }
                }
            }
        };
        handler.postDelayed(checkTask, CHECK_INTERVAL_MS);
    }

    private void stopCheckLoop() {
        if (checkTask != null && handler != null) {
            handler.removeCallbacks(checkTask);
            checkTask = null;
        }
        running.set(false);
    }

    private void checkBaitFile() {
        File baitFile = getBaitFile();
        boolean fileMissing = (baitFile == null || !baitFile.exists());
        boolean contentTampered = false;
        String triggerReason = "";

        if (fileMissing) {
            triggerReason = "诱饵文件不存在（可能已被删除）";
        } else {
            try {
                BufferedReader br = new BufferedReader(new FileReader(baitFile), 1024);
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[256];
                int n;
                while ((n = br.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
                br.close();
                String content = sb.toString().trim();
                if (!"勒索病毒诱捕".equals(content)) {
                    contentTampered = true;
                    triggerReason = "诱饵文件内容被篡改，内容: " + content;
                    Log.w(TAG, triggerReason + "，触发防护");
                }
            } catch (IOException e) {
                triggerReason = "诱饵文件读取异常（可能被删除或破坏）：" + e.getMessage();
                Log.w(TAG, triggerReason);
            }
        }

        if (fileMissing || contentTampered) {
            Log.w(TAG, triggerReason + "，触发防护");
            forceStopNonSystemApps();
            ensureBaitFile();
            String dialogMsg;
            if (triggerReason.contains("不存在") || triggerReason.contains("读取异常")) {
                dialogMsg = "诱饵文件 /sdcard/DCL安全/dclnb.txt 已被删除或无法读取。\n请尽快检查设备安全状态。";
            } else {
                dialogMsg = "诱饵文件 /sdcard/DCL安全/dclnb.txt 内容被篡改。\n请尽快检查设备安全状态。";
            }
            showToastDialog(dialogMsg);
        }
    }

    private void showToastDialog(final String fileStatusMsg) {
        try {
            final String msgFinal = "我们似乎发现了勒索软件，已自动停止除系统外的所有应用。\n\n"
                    + fileStatusMsg;
            final RansomwareTrapService svc = this;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(svc, RansomwareTrapActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(intent);

                        new android.app.AlertDialog.Builder(svc)
                                .setTitle("⚠ 检测到勒索病毒")
                                .setMessage(msgFinal)
                                .setPositiveButton("查看防护设置", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                })
                                .setNegativeButton("我知道了", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                })
                                .setCancelable(false)
                                .show();
                    } catch (Throwable t) {
                        Log.e(TAG, "显示对话框失败", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "post 到主线程失败", t);
        }
    }

    private void ensureBaitFile() {
        try {
            File dir = new File("/sdcard/DCL安全");
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.w(TAG, "无法创建目录");
                    return;
                }
            }
            File file = new File(dir, "dclnb.txt");
            if (!file.exists()) {
                file.createNewFile();
            }
            FileWriter fw = new FileWriter(file, false);
            fw.write("勒索病毒诱捕");
            fw.close();
            Log.d(TAG, "诱饵文件已写入/更新");
        } catch (IOException e) {
            Log.w(TAG, "写入诱饵文件失败", e);
        }
    }

    private void forceStopNonSystemApps() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            List<android.content.pm.ApplicationInfo> apps =
                    pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
            String self = getPackageName();
            for (android.content.pm.ApplicationInfo ai : apps) {
                if (ai == null) continue;
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (self.equals(ai.packageName)) continue;
                if (Whitelist.isWhitelisted(this, ai.packageName)) continue;
                stopApp(ai.packageName);
            }
            Log.d(TAG, "forceStopNonSystemApps 完成");
        } catch (Throwable t) {
            Log.e(TAG, "forceStopNonSystemApps 异常", t);
        }
    }

    private void stopApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                boolean ok = ShellExecutor.execShizuku(new String[]{"am", "force-stop", pkg});
                if (ok) return;
            } catch (Throwable ignored) {}
        }
        try {
            boolean ok = ShellExecutor.execRoot("am force-stop " + pkg);
            if (ok) return;
        } catch (Throwable ignored) {}
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(pkg);
            }
        } catch (Throwable ignored) {}
    }

    private void postNotification() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(0x8801, getNotification());
        } catch (Throwable ignored) {}
    }

    private android.app.Notification getNotification() {
        android.content.Intent open = new android.content.Intent(this, RansomwareTrapActivity.class);
        open.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 0x8801, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, "ransomware_trap")
                : new android.app.Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("勒索病毒诱捕")
                .setContentText("监控中，检测到篡改将停止应用")
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(android.app.Notification.PRIORITY_LOW)
                .setWhen(0)
                .setShowWhen(false)
                .build();
    }

    private File getBaitFile() {
        try {
            File dir = new File("/sdcard/DCL安全");
            if (!dir.exists()) return null;
            return new File(dir, "dclnb.txt");
        } catch (Throwable t) {
            return null;
        }
    }
}