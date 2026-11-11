package dclnb.dcltools;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * DCL引擎 - 音量阈值检测。
 *
 * 轮询 + ContentObserver 双通道读音量，超过阈值时自动将音量恢复为 2。
 *
 * 本类只依赖 Context，可以在普通 Service（DclCoreService）里运行——
 * 无障碍被关闭后，音量防护依旧工作。
 */
public class ProtectionVolumeThreshold {

    private static final String TAG = "ProtectionVolumeThreshold";
    
    /** 触发冷却（毫秒） */
    private static final long TRIGGER_COOLDOWN_MS = 5000L;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences prefs;

    private Runnable volumeCheckTask;
    private android.database.ContentObserver volumeObserver;
    private final Object volumeTriggerLock = new Object();
    private boolean pendingVolumeTrigger = false;

    public ProtectionVolumeThreshold(Context context, Handler handler, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.handler = (handler != null) ? handler : new Handler(Looper.getMainLooper());
        this.prefs = prefs;
    }

    /** 启动轮询任务与音量变化监听 */
    public void start() {
        startCheckTask();
        registerObserver();
        Log.d(TAG, "音量阈值检测已启动");
    }

    /** 停止轮询任务与音量变化监听 */
    public void stop() {
        stopCheckTask();
        unregisterObserver();
        Log.d(TAG, "音量阈值检测已停止");
    }

    // ==================== 轮询 ====================

    private void startCheckTask() {
        stopCheckTask();
        volumeCheckTask = new Runnable() {
            @Override
            public void run() {
                if (handler == null) return;
                checkVolumeAndReduce();
                int volInterval = prefs.getInt("volume_check_interval", 1);
                handler.postDelayed(volumeCheckTask, Math.max(100, volInterval * 100L));
            }
        };
        handler.postDelayed(volumeCheckTask, Math.max(100, prefs.getInt("volume_check_interval", 1) * 100L));
    }

    private void stopCheckTask() {
        if (volumeCheckTask != null && handler != null) {
            handler.removeCallbacks(volumeCheckTask);
            volumeCheckTask = null;
        }
    }

    /** 核心：读音量 → 防篡改 */
    private void checkVolumeAndReduce() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return;
            
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int threshold = prefs.getInt("volume_threshold", 80);
            
            // 计算当前音量百分比
            int volumePercent = current * 100 / max;
            
            // 防音量恶意修改：超过阈值即恢复音量到 2
            if (volumePercent > threshold) {
                Log.d(TAG, "音量异常: " + volumePercent + "% > " + threshold + "%，已压低音量至 2");
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0);
            }
        } catch (Throwable ignored) {
            Log.w(TAG, "音量检测失败", ignored);
        }
    }

    // ==================== 音量变化事件监听 ====================

    /** 注册音量变化 ContentObserver（事件驱动，弥补纯轮询窗口期） */
    private void registerObserver() {
        if (volumeObserver != null) return;
        try {
            volumeObserver = new android.database.ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    checkVolumeAndReduce();
                }
            };
            context.getContentResolver().registerContentObserver(
                    android.provider.Settings.System.CONTENT_URI, true, volumeObserver);
            Log.d(TAG, "音量变化监听已注册");
        } catch (Throwable ignored) {
            Log.w(TAG, "注册音量监听失败", ignored);
        }
    }

    private void unregisterObserver() {
        try {
            if (volumeObserver != null) {
                context.getContentResolver().unregisterContentObserver(volumeObserver);
                volumeObserver = null;
                Log.d(TAG, "音量变化监听已注销");
            }
        } catch (Throwable ignored) {}
    }
}
