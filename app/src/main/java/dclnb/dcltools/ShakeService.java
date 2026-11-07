package dclnb.dcltools;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

/**
 * 摇一摇检测服务（参照 xtsafe-main ShakeDetector）。
 *
 * 关键改进：
 *   1. 使用加速度变化量(delta)而非绝对值，避免重力干扰
 *   2. 阈值合理：标准档 4.0，轻摇 1.5，用力 8.0，猛摇 14.0
 *   3. 首次注册时建立基线，避免第一帧误判
 *   4. 持续摇动至少 250ms 才算一次有效摇晃
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
    private Sensor accelerometer;
    private long lastShakeTime = 0;
    private long shakeStartTime = 0;
    private float lastX, lastY, lastZ;
    private float threshold;
    private boolean baselineInitialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i(TAG, "摇一摇检测服务已启动");
        } else {
            Log.w(TAG, "设备不支持加速度传感器");
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
        if (sensorManager != null && accelerometer != null) {
            sensorManager.unregisterListener(this);
        }
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
}