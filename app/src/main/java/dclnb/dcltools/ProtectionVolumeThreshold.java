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
 * 轮询 + ContentObserver 双通道读音量，超过阈值时：
 * 1. 自动将音量恢复为 0（防篡改）
 * 2. 弹出悬浮窗弹窗，询问用户"是否拦截"
 *    - 选"否"：仅恢复音量，不拦截
 *    - 选"是"：执行正常拦截操作（跳动画页 + 强制停止第三方应用）
 *
 * 设计要点：
 *   - 音量超阈值 → 压低到 0 + 弹窗（冷却时间内不重复弹）
 *   - 启动时延时 3 秒才开始检测，避免服务初始化时误判
 *   - 使用绝对音量值（current > thresholdValue）直接比较，避免百分比精度问题
 *
 * 本类只依赖 Context，可以在普通 Service（DclCoreService）里运行。
 */
public class ProtectionVolumeThreshold {

    private static final String TAG = "ProtectionVolumeThreshold";
    private static final String KEY_LAST_TRIGGER = "vol_last_trigger_time";

    /** 触发冷却（毫秒） */
    private static final long TRIGGER_COOLDOWN_MS = 5000L;
    /** 启动延时（毫秒）：避免初始化时误判 */
    private static final long STARTUP_DELAY_MS = 3000L;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences prefs;

    private Runnable volumeCheckTask;
    private android.database.ContentObserver volumeObserver;
    private boolean isIntercepting = false; // 弹窗显示中，跳过检测
    private boolean wasOverThreshold = false; // 上次检测时是否超标（用于边沿检测）

    public ProtectionVolumeThreshold(Context context, Handler handler, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.handler = (handler != null) ? handler : new Handler(Looper.getMainLooper());
        this.prefs = prefs;
        // 初始化时检查当前音量状态
        this.wasOverThreshold = checkCurrentVolumeStatus();
    }

    /** 启动轮询任务与音量变化监听 */
    public void start() {
        // 启动延时：等服务稳定后再开始检测，避免初始化时误判
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startCheckTask();
                registerObserver();
            }
        }, STARTUP_DELAY_MS);
        Log.d(TAG, "音量阈值检测将在 " + STARTUP_DELAY_MS + "ms 后启动");
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
        Log.d(TAG, "轮询检测已启动，间隔 " + prefs.getInt("volume_check_interval", 1) + "秒");
    }

    private void stopCheckTask() {
        if (volumeCheckTask != null && handler != null) {
            handler.removeCallbacks(volumeCheckTask);
            volumeCheckTask = null;
        }
    }
    /** 核心：读音量 → 边沿检测（只有从正常→超标才触发）→ 压低音量 + 弹窗 */
    private void checkVolumeAndReduce() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return;

            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int threshold = getThreshold();
            int thresholdValue = max * threshold / 100; // 将百分比阈值转换为绝对音量值

            boolean isOver = current > thresholdValue;

            // 边沿检测：只有从"未超标"变为"超标"时才触发
            if (isOver && !wasOverThreshold) {
                Log.d(TAG, "音量超标（边沿触发）: " + current + "/" + max
                        + " (" + (current * 100 / max) + "%) > "
                        + threshold + "% 阈值(" + thresholdValue + ")，已压低音量至 0");
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);

                // 弹窗显示中，跳过（防止重复弹）
                if (isIntercepting) {
                    wasOverThreshold = true;
                    return;
                }

                // 冷却时间控制：只有冷却时间过后才弹窗
                long now = System.currentTimeMillis();
                long lastTrigger = prefs.getLong(KEY_LAST_TRIGGER, 0);
                if (now - lastTrigger > TRIGGER_COOLDOWN_MS) {
                    // 冷却时间已过，记录当前时间，准备弹窗
                    prefs.edit().putLong(KEY_LAST_TRIGGER, now).apply();
                    wasOverThreshold = true; // 标记已触发
                    isIntercepting = true;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            showInterceptDialog();
                        }
                    });
                } else {
                    Log.d(TAG, "冷却时间内(" + (TRIGGER_COOLDOWN_MS - (now - lastTrigger)) + "ms)，跳过弹窗");
                }
            } else if (!isOver) {
                // 音量回落至正常，重置边沿检测标记
                if (wasOverThreshold) {
                    Log.d(TAG, "音量已恢复至正常范围: " + current + "/" + max + " (" + (current * 100 / max) + "%)，重新武装");
                }
                wasOverThreshold = false;
            }
            // 如果 isOver && wasOverThreshold，说明已经触发过了，忽略
        } catch (Throwable ignored) {
            Log.w(TAG, "音量检测失败", ignored);
        }
    }

    /**
     * 显示悬浮窗拦截确认弹窗
     * 用户选择"否"仅恢复音量，选择"是"执行完整拦截
     */
    private void showInterceptDialog() {
        // 注册弹窗关闭回调
        DclInterceptDialog.setOnDismissCallback(new Runnable() {
            @Override
            public void run() {
                isIntercepting = false;
                // KEY_WAS_OVER 保持 true，只有音量真正回落才重置
                // 弹窗关闭后冷却时间重新计时
                prefs.edit().putLong(KEY_LAST_TRIGGER, System.currentTimeMillis()).apply();
                Log.d(TAG, "弹窗已关闭，冷却时间重新计时，等待音量回落再重新武装");
            }
        });
        DclInterceptDialog.show(context);
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

    /**
     * 获取音量阈值（带范围限制 5~50，默认 50）
     */
    private int getThreshold() {
        int threshold = prefs.getInt("volume_threshold", 50);
        if (threshold < 5) threshold = 5;
        if (threshold > 50) threshold = 50;
        return threshold;
    }
/**
     * 检查当前音量状态，用于初始化时判断是否已超标
     */
    private boolean checkCurrentVolumeStatus() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return false;
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int threshold = getThreshold();
            int thresholdValue = max * threshold / 100;
            boolean isOver = current > thresholdValue;
            if (isOver) {
                Log.d(TAG, "初始化检测：当前音量超标 " + current + "/" + max + " (" + (current * 100 / max) + "%) > " + threshold + "%");
            }
            return isOver;
        } catch (Throwable ignored) {
            return false;
        }
    }
}