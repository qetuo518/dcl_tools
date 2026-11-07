package dclnb.dcltools;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/**
 * Shell 命令执行器
 */
public class ShellExecutor {

    /** 执行 Shizuku 命令（静默，无输出） */
    public static boolean execShizuku(String[] cmd) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.newProcess(cmd, null, null);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 执行 Shizuku 命令并等待结果 */
    public static boolean execShizukuSync(String[] cmd) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                rikka.shizuku.ShizukuRemoteProcess proc = Shizuku.newProcess(cmd, null, null);
                return proc.waitFor() == 0;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 执行 Shizuku 命令并返回标准输出 */
    public static String execShizukuWithOutput(String[] cmd) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                rikka.shizuku.ShizukuRemoteProcess proc = Shizuku.newProcess(cmd, null, null);
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                proc.waitFor();
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 执行 Root 命令（带超时） */
    private static final long ROOT_TIMEOUT_MS = 1500L;

    public static boolean execRoot(String cmd) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (Build.VERSION.SDK_INT >= 26) {
                if (proc.waitFor(ROOT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return proc.exitValue() == 0;
                }
            } else {
                final Process p = proc;
                final java.util.concurrent.atomic.AtomicBoolean done =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try { p.waitFor(); done.set(true); } catch (Exception ignored) {}
                    }
                });
                t.start();
                t.join(ROOT_TIMEOUT_MS);
                if (done.get()) return proc.exitValue() == 0;
            }
        } catch (Exception ignored) {}
        finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    public static boolean execShell(String[] cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(cmd);
            return proc.waitFor() == 0;
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean forceStopApp(String packageName) {
        String[] cmd = {"am", "force-stop", packageName};
        if (execShizuku(cmd)) return true;
        if (execRoot("am force-stop " + packageName)) return true;
        return execShell(cmd);
    }
}