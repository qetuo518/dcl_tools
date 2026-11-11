package dclnb.dcltools;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 摇一摇检测服务（参照 xtsafe-main ShakeDetector）。
 *
 * 关键改进：
 *   1. 使用加速度变化量(delta)而非绝对值，避免重力干扰
 *   2. 阈值合理：标准档 4.0，轻摇 1.5，用力 8.0，猛摇 14.0
 *   3. 首次注册时建立基线，避免第一帧误判
 *   4. 持续摇动至少 250ms 才算一次有效摇晃
 *   5. 虚拟机无加速传感器时，自动 fallback 到重力/线性加速度/陀螺仪
 *   6. 全传感器缺失时，启用屏幕快速滑动作为兜底触发
 */
public class ShakeService extends Service implements SensorEventListener {

    private static final String TAG = "ShakeService";
    private static final long SHAKE_TIMEOUT = 600L;
    private static final int SHAKE_MIN_DURATION = 250; // 持续摇动 250ms 才算一次

    /** 4 档阈值：与 xtsafe 保持一致 */
    private static final float THRESHOLD_LIGHT    = 1.5f;
    private static final float THRESHOLD_STANDARD = 4.0f;
    private static final float THRESHOLD_HARD     = 8.0f;
    private static final float THRESHOLD_VIOLENT  = 14.0f;

    private SensorManager sensorManager;
    private Sensor primarySensor;
    private long lastShakeTime = 0;
    private long shakeStartTime = 0;
    private float lastX, lastY, lastZ;
    private float threshold;
    private boolean baselineInitialized = false;
    
    /** 是否处于虚拟机/无传感器模式 */
    private boolean isVirtualMode = false;
    
    /** 屏幕滑动兜底相关 */
    private View overlayView;
    private float lastTouchX, lastTouchY;
    private long lastTouchTime = 0;
    private static final float TOUCH_SWIPE_THRESHOLD = 150.0f; // px
    private static final long TOUCH_SWIPE_TIMEOUT = 300L;      // ms

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        
        // 优先级：加速度 > 线性加速度 > 重力 > 陀螺仪
        primarySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (primarySensor == null) {
            primarySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        }
        if (primarySensor == null) {
            primarySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }
        if (primarySensor == null) {
            primarySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        
        if (primarySensor != null) {
            sensorManager.registerListener(this, primarySensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i(TAG, "摇一摇检测服务已启动，使用传感器: " + primarySensor.getType());
        } else {
            Log.w(TAG, "警告：设备不支持任何运动传感器，启用屏幕滑动兜底模式");
            isVirtualMode = true;
            startTouchFallback();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int sensitivity = intent.getIntExtra("sensitivity", 1);
            setThreshold(sensitivity);
            Log.i(TAG, "摇一摇服务启动，灵敏度档位: " + sensitivity + "，阈值: " + threshold);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null && primarySensor != null) {
            sensorManager.unregisterListener(this);
        }
        stopTouchFallback();
        super.onDestroy();
        Log.i(TAG, "摇一摇服务已停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        // 首帧只写入基线，不做判定，避免把当前重力值当成摇动
        if (!baselineInitialized) {
            lastX = x;
            lastY = y;
            lastZ = z;
            baselineInitialized = true;
            return;
        }

        float deltaX = Math.abs(x - lastX);
        float deltaY = Math.abs(y - lastY);
        float deltaZ = Math.abs(z - lastZ);

        lastX = x;
        lastY = y;
        lastZ = z;

        long now = System.currentTimeMillis();

        // 检测是否有足够的加速度变化
        if (deltaX + deltaY + deltaZ > threshold) {
            if (shakeStartTime == 0) {
                shakeStartTime = now;
            }

            // 检查持续时间是否足够
            if (now - shakeStartTime >= SHAKE_MIN_DURATION) {
                // 检查是否在超时时间内
                if (now - lastShakeTime > SHAKE_TIMEOUT) {
                    Log.i(TAG, "检测到摇一摇动作: delta=" + (deltaX + deltaY + deltaZ));
                    broadcastShakeDetected();
                    lastShakeTime = now;
                    shakeStartTime = 0;
                }
            }
        } else {
            shakeStartTime = 0;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理
    }

    /** 设置灵敏度档位 */
    private void setThreshold(int sensitivity) {
        switch (sensitivity) {
            case 0: threshold = THRESHOLD_LIGHT; break;
            case 1: threshold = THRESHOLD_STANDARD; break;
            case 2: threshold = THRESHOLD_HARD; break;
            case 3: threshold = THRESHOLD_VIOLENT; break;
            default: threshold = THRESHOLD_STANDARD; break;
        }
    }

    private void broadcastShakeDetected() {
        Intent intent = new Intent("dcl_shake_detected");
        sendBroadcast(intent);
    }

    // ==================== 屏幕滑动兜底 ====================

    private void startTouchFallback() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlayView = new View(this);
            overlayView.setBackgroundColor(0x00000000); // 完全透明
            
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT
            );
            
            wm.addView(overlayView, params);
            
            overlayView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        long now = System.currentTimeMillis();
                        
                        // 快速滑动检测
                        if (Math.abs(dx) + Math.abs(dy) > TOUCH_SWIPE_THRESHOLD 
                                && now - lastTouchTime < TOUCH_SWIPE_TIMEOUT) {
                            Log.i(TAG, "检测到屏幕滑动（虚拟机模式）: dx=" + dx + ", dy=" + dy);
                            broadcastShakeDetected();
                        }
                        
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        lastTouchTime = now;
                    }
                    return true;
                }
            });
            
            Log.i(TAG, "屏幕滑动兜底已启用");
        } catch (Exception e) {
            Log.e(TAG, "启用屏幕滑动兜底失败", e);
        }
    }

    private void stopTouchFallback() {
        if (overlayView != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                wm.removeView(overlayView);
            } catch (Exception e) {
                // ignore
            }
            overlayView = null;
        }
    }
}