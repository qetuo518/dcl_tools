package dclnb.dcltools;

import android.content.Context;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/**
 * 运行环境检测工具。
 * 用于判断当前设备/虚拟机是否具备执行提权操作的条件，
 * 方便在权限不足时给出明确提示而非静默失败。
 */
public class EnvChecker {

    private EnvChecker() {}

    /** Shizuku binder 是否可用且已授权 */
    public static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 设备是否有 Root 权限（通过 su -c id 检测） */
    public static boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            br.close();
            p.destroy();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** 无障碍服务是否已在设置中启用（无论是否运行中） */
    public static boolean isAccessibilityEnabledInSettings(Context ctx) {
        try {
            String list = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            String self = ctx.getPackageName() + "/dclnb.dcltools.DclAccessibilityService";
            String shortSelf = ctx.getPackageName() + "/.DclAccessibilityService";
            for (String item : list.split(":")) {
                if (self.equals(item.trim()) || shortSelf.equals(item.trim())) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前环境的可读描述，用于 UI 提示。
     * 格式："Shizuku: ✅  Root: ❌  无障碍: ✅"
     */
    public static String getEnvDescription(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Shizuku: ").append(isShizukuReady() ? "✅" : "❌").append("  ");
        sb.append("Root: ").append(isRootAvailable() ? "✅" : "❌").append("  ");
        sb.append("无障碍: ").append(isAccessibilityEnabledInSettings(ctx) ? "✅" : "❌");
        return sb.toString();
    }

    /**
     * 是否有任何提权能力（Shizuku 或 Root）
     */
    public static boolean hasAnyPrivilege() {
        return isShizukuReady() || isRootAvailable();
    }
}