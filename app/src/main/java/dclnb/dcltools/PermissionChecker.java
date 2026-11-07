package dclnb.dcltools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

import rikka.shizuku.Shizuku;

/**
 * 权限检查工具类
 * 用于检查安全护盾所需的各种权限
 */
public class PermissionChecker {

    /**
     * 检查无障碍服务是否已启用
     */
    public static boolean isAccessibilityEnabled(Context context) {
        try {
            int accessibilityEnabled = android.provider.Settings.Secure.getInt(
                    context.getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
            if (accessibilityEnabled == 1) {
                String settingValue = android.provider.Settings.Secure.getString(
                        context.getContentResolver(),
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (settingValue != null) {
                    return settingValue.contains(context.getPackageName() + "/" + 
                            DclAccessibilityService.class.getName());
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查悬浮窗权限
     */
    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; // 低版本不需要此权限
    }

    /**
     * 检查应用使用情况权限
     * 使用 AppOpsManager 检查，比 QueryUsageStats 更可靠
     */
    public static boolean hasUsageStatsPermission(Context context) {
        try {
            android.app.AppOpsManager appOps = 
                (android.app.AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow("android:get_usage_stats", 
                android.os.Process.myUid(), context.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查Shizuku是否可用
     */
    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查Shizuku权限是否已授予
     */
    public static boolean hasShizukuPermission() {
        try {
            return Shizuku.pingBinder() && 
                   Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取所有未授权的权限列表
     */
    public static java.util.List<String> getMissingPermissions(Context context) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        
        if (!isAccessibilityEnabled(context)) {
            missing.add("无障碍服务");
        }
        if (!canDrawOverlays(context)) {
            missing.add("悬浮窗权限");
        }
        if (!hasUsageStatsPermission(context)) {
            missing.add("应用使用情况");
        }
        if (!isShizukuAvailable()) {
            missing.add("Shizuku服务");
        } else if (!hasShizukuPermission()) {
            missing.add("Shizuku权限");
        }
        
        return missing;
    }

    /**
     * 检查所有权限是否都已授权
     */
    public static boolean areAllPermissionsGranted(Context context) {
        return getMissingPermissions(context).isEmpty();
    }

    /**
     * 打开无障碍服务设置页面
     */
    public static void openAccessibilitySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 打开悬浮窗权限设置页面
     */
    public static void openOverlaySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 打开应用使用情况设置页面
     */
    public static void openUsageAccessSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 请求Shizuku权限
     * 参照 xtsafe-main 标准：先检查 binder，再检查已授权状态，未授权则请求并注册监听器
     */
    public static void requestShizukuPermission(Context context) {
        try {
            if (!Shizuku.pingBinder()) {
                return;
            }
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            Shizuku.requestPermission(1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 注册 Shizuku 权限结果监听器
     * 用于在 Activity/Service 中接收授权回调，对应 xtsafe-main 的 permissionListener 模式
     *
     * @param requestCode 请求码，需与 requestPermission(requestCode) 一致
     * @param listener    结果回调（grantResult == PackageManager.PERMISSION_GRANTED 表示成功）
     */
    public static void addShizukuPermissionListener(int requestCode,
            Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 注销 Shizuku 权限结果监听器
     */
    public static void removeShizukuPermissionListener(
            Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 显示权限缺失提示对话框
     */
    public static void showPermissionDialog(Context context, java.util.List<String> missing) {
        if (missing.isEmpty()) return;
        
        StringBuilder message = new StringBuilder("以下权限尚未授权，是否前往设置？\n\n");
        for (String perm : missing) {
            message.append("❌ ").append(perm).append("\n");
        }
        
        final java.util.List<String> missingList = missing;
        final Context ctx = context;
        
        new AlertDialog.Builder(context)
            .setTitle("权限检查")
            .setMessage(message.toString())
            .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    openSpecificSettings(ctx, missingList.get(0));
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void openSpecificSettings(Context context, String permissionName) {
        switch (permissionName) {
            case "无障碍服务":
                openAccessibilitySettings(context);
                break;
            case "悬浮窗权限":
                openOverlaySettings(context);
                break;
            case "应用使用情况":
                openUsageAccessSettings(context);
                break;
            case "Shizuku服务":
                // 提示用户安装/启动Shizuku
                new AlertDialog.Builder(context)
                    .setTitle("Shizuku未运行")
                    .setMessage("请先安装并启动Shizuku应用，然后在应用中授权权限。")
                    .setPositiveButton("确定", null)
                    .show();
                break;
            case "Shizuku权限":
                requestShizukuPermission(context);
                break;
        }
    }
}