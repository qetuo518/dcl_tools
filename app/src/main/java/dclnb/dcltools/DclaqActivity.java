package dclnb.dcltools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import rikka.shizuku.Shizuku;

import java.util.ArrayList;
import java.util.List;

public class DclaqActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "dcl_prefs";
    private static final String KEY_PROTECTION_ENABLED = "protection_enabled";
    private static final String KEY_TRIGGER_VOLUME = "trigger_volume";
    private static final String KEY_TRIGGER_FLOAT = "trigger_float";
    private static final String KEY_TRIGGER_SHAKE = "trigger_shake";
    private static final String KEY_SENSITIVITY = "shake_sensitivity";
    private static final String KEY_VOLUME_KEY = "volume_key_type";

    private static final int SHIZUKU_REQUEST_CODE = 1;

    private SwitchCompat switchProtection;
    private SwitchCompat switchVolume;
    private SwitchCompat switchFloat;
    private SwitchCompat switchShake;
    private Spinner spinnerSensitivity;
    private Spinner spinnerVolumeKey;
    private boolean isProtected = false;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == SHIZUKU_REQUEST_CODE) {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            showToast("Shizuku 已授权");
                            if (!switchProtection.isChecked()) {
                                switchProtection.setChecked(true);
                            }
                        } else {
                            showToast("Shizuku 权限被拒绝");
                        }
                    }
                }
            };
    
    private BroadcastReceiver shakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("dcl_shake_detected".equals(intent.getAction())) {
                triggerProtection();
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.activity_dclaq);
        
        switchProtection = (SwitchCompat) findViewById(R.id.switchProtection);
        switchVolume = (SwitchCompat) findViewById(R.id.switchVolume);
        switchFloat = (SwitchCompat) findViewById(R.id.switchFloat);
        switchShake = (SwitchCompat) findViewById(R.id.switchShake);
        spinnerSensitivity = (Spinner) findViewById(R.id.spinnerSensitivity);
        spinnerVolumeKey = (Spinner) findViewById(R.id.spinnerVolumeKey);
        
        // 加载保存的状态
        switchProtection.setChecked(getPrefsBoolean(KEY_PROTECTION_ENABLED, false));
        switchVolume.setChecked(getPrefsBoolean(KEY_TRIGGER_VOLUME, true));
        switchFloat.setChecked(getPrefsBoolean(KEY_TRIGGER_FLOAT, false));
        switchShake.setChecked(getPrefsBoolean(KEY_TRIGGER_SHAKE, true));
        
        int sensitivityPos = getPrefsInt(KEY_SENSITIVITY, 1);
        spinnerSensitivity.setSelection(sensitivityPos);
        
        int volumeKeyPos = getPrefsInt(KEY_VOLUME_KEY, 0);
        spinnerVolumeKey.setSelection(volumeKeyPos);
        
        // 防护总开关
        switchProtection.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                savePrefsBoolean(KEY_PROTECTION_ENABLED, isChecked);
                if (isChecked) {
                    List<String> missing = checkRequiredPermissions();
                    if (!missing.isEmpty()) {
                        showPermissionDialog(missing);
                        switchProtection.setChecked(false);
                        savePrefsBoolean(KEY_PROTECTION_ENABLED, false);
                        return;
                    }
                    isProtected = true;
                    startGuardService();
                    startShakeService();
                    DclKeepAliveReceiver.schedule(DclaqActivity.this);
                    DclCoreService.ensureStarted(DclaqActivity.this);
                    showToast("防护已开启");
                } else {
                    isProtected = false;
                    stopGuardService();
                    stopShakeService();
                    showToast("防护已关闭");
                }
            }
        });
        
        // 音量键触发
        switchVolume.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                savePrefsBoolean(KEY_TRIGGER_VOLUME, isChecked);
            }
        });
        
        // 悬浮窗触发
        switchFloat.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                savePrefsBoolean(KEY_TRIGGER_FLOAT, isChecked);
            }
        });
        
        // 摇一摇触发
        switchShake.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                savePrefsBoolean(KEY_TRIGGER_SHAKE, isChecked);
                if (isProtected) {
                    if (isChecked) {
                        startShakeService();
                    } else {
                        stopShakeService();
                    }
                }
            }
        });
        
        // 灵敏度选择
        spinnerSensitivity.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                savePrefsInt(KEY_SENSITIVITY, position);
                restartShakeService();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        
        // 音量键类型选择
        spinnerVolumeKey.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                savePrefsInt(KEY_VOLUME_KEY, position);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        
        // 注册摇一摇广播接收器
        IntentFilter filter = new IntentFilter("dcl_shake_detected");
        registerReceiver(shakeReceiver, filter);

        // 注册 Shizuku 授权结果监听器
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);

        autoRequestShizukuIfNeeded();
        autoCheckPermissionsOnStartup();
    }
    
    private void autoCheckPermissionsOnStartup() {
        List<String> missing = checkRequiredPermissions();
        
        if (!missing.isEmpty()) {
            switchProtection.setChecked(false);
            savePrefsBoolean(KEY_PROTECTION_ENABLED, false);
            isProtected = false;
            
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    showToast("权限不足，防护已自动关闭");
                }
            }, 300);
        } else {
            switchProtection.setChecked(true);
            savePrefsBoolean(KEY_PROTECTION_ENABLED, true);
            isProtected = true;
            startGuardService();
            startShakeService();
            DclKeepAliveReceiver.schedule(this);
            DclCoreService.ensureStarted(this);
            showToast("所有权限已就绪，防护已自动开启");
        }
    }
    
    private void autoRequestShizukuIfNeeded() {
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 触发防护（摇一摇触发）
     * 与音量键触发逻辑一致：跳转动画页 + 强制停止第三方应用
     */
    private void triggerProtection() {
        // 1. 跳转到拦截动画页（抢回屏幕）
        try {
            Intent intent = new Intent(this, DcltoolljActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // 2. 调用 GuardService 触发防护
        if (GuardService.getInstance() != null) {
            GuardService.getInstance().triggerProtection();
        }
        
        // 3. 强制停止第三方应用
        executeShakeSafetyAction();
        
        // 4. 显示提示
        Toast.makeText(this, "检测到摇一摇，触发安全操作", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 执行摇一摇安全操作：强制停止所有第三方应用
     */
    private void executeShakeSafetyAction() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int count = 0;
                try {
                    List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
                    for (ApplicationInfo app : apps) {
                        if (app == null) continue;
                        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                        if (app.packageName.equals(getPackageName())) continue;
                        if (app.packageName.equals("dclnb.dclaqhd")) continue;
                        if (ShellExecutor.forceStopApp(app.packageName)) {
                            count++;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                final int stopped = count;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(DclaqActivity.this, 
                                "安全操作完成，已停止 " + stopped + " 个应用", 
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "shake-safety").start();
    }
    
    private void restartShakeService() {
        if (isProtected && switchShake.isChecked()) {
            stopShakeService();
            startShakeService();
        }
    }
    
    private void startGuardService() {
        try {
            Intent intent = new Intent(this, GuardService.class);
            startService(intent);
            if (GuardService.getInstance() != null) {
                GuardService.getInstance().startProtection();
            }
        } catch (Exception e) {
            showToast("启动服务失败: " + e.getMessage());
        }
    }
    
    private void stopGuardService() {
        if (GuardService.getInstance() != null) {
            GuardService.getInstance().stopProtection();
        }
        try {
            Intent intent = new Intent(this, GuardService.class);
            stopService(intent);
        } catch (Exception e) {
            // ignore
        }
    }
    
    private void startShakeService() {
        try {
            Intent intent = new Intent(this, ShakeService.class);
            intent.putExtra("sensitivity", getPrefsInt(KEY_SENSITIVITY, 1));
            startService(intent);
        } catch (Exception e) {
            showToast("启动摇一摇服务失败: " + e.getMessage());
        }
    }
    
    private void stopShakeService() {
        try {
            Intent intent = new Intent(this, ShakeService.class);
            stopService(intent);
        } catch (Exception e) {
            // ignore
        }
    }
    
    private void savePrefsBoolean(String key, boolean value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }
    
    private boolean getPrefsBoolean(String key, boolean defValue) {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(key, defValue);
    }
    
    private void savePrefsInt(String key, int value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(key, value).apply();
    }
    
    private int getPrefsInt(String key, int defValue) {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(key, defValue);
    }
    
    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        switchProtection.setChecked(getPrefsBoolean(KEY_PROTECTION_ENABLED, false));
        switchVolume.setChecked(getPrefsBoolean(KEY_TRIGGER_VOLUME, true));
        switchFloat.setChecked(getPrefsBoolean(KEY_TRIGGER_FLOAT, false));
        switchShake.setChecked(getPrefsBoolean(KEY_TRIGGER_SHAKE, true));

        if (!isProtected) {
            List<String> missing = checkRequiredPermissions();
            if (missing.isEmpty()) {
                isProtected = true;
                switchProtection.setChecked(true);
                startGuardService();
                startShakeService();
                DclKeepAliveReceiver.schedule(this);
                DclCoreService.ensureStarted(this);
                showToast("所有权限已就绪，防护已自动开启");
            }
        }
    }
    
    private List<String> checkRequiredPermissions() {
        List<String> missing = new ArrayList<>();
        
        if (!PermissionChecker.isAccessibilityEnabled(this)) {
            missing.add("无障碍服务");
        }
        
        if (!PermissionChecker.canDrawOverlays(this)) {
            missing.add("悬浮窗权限");
        }
        
        if (!PermissionChecker.hasUsageStatsPermission(this)) {
            missing.add("应用使用情况");
        }
        
        if (!PermissionChecker.isShizukuAvailable()) {
            missing.add("Shizuku服务");
        } else if (!PermissionChecker.hasShizukuPermission()) {
            missing.add("Shizuku权限");
        }
        
        return missing;
    }
    
    private void showPermissionDialog(List<String> missing) {
        StringBuilder message = new StringBuilder("开启防护前需要以下权限：\n\n");
        for (int i = 0; i < missing.size(); i++) {
            message.append(i + 1).append(". ").append(missing.get(i)).append("\n");
        }
        message.append("\n是否现在前往授权？");
        
        final List<String> missingList = missing;
        
        new AlertDialog.Builder(this)
            .setTitle("权限检查")
            .setMessage(message.toString())
            .setPositiveButton("去授权", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    for (String perm : missingList) {
                        openPermissionSetting(perm);
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void openPermissionSetting(String permission) {
        switch (permission) {
            case "无障碍服务":
                PermissionChecker.openAccessibilitySettings(this);
                break;
            case "悬浮窗权限":
                PermissionChecker.openOverlaySettings(this);
                break;
            case "应用使用情况":
                PermissionChecker.openUsageAccessSettings(this);
                break;
            case "Shizuku服务":
                new AlertDialog.Builder(this)
                    .setTitle("Shizuku未运行")
                    .setMessage("请先安装并启动Shizuku应用，然后在应用中授权此应用的权限。")
                    .setPositiveButton("确定", null)
                    .show();
                break;
            case "Shizuku权限":
                PermissionChecker.requestShizukuPermission(this);
                break;
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {}
        unregisterReceiver(shakeReceiver);
    }
}