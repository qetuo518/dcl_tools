package dclnb.dcltools;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * 创建文件
     * @param path 文件路径
     * @param content 文件内容
     * @return 成功返回 true，失败返回 false
     */
    public static boolean createFile(String path, String content) {
        try {
            File file = new File(path);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            OutputStream os = new FileOutputStream(file);
            os.write(content.getBytes("UTF-8"));
            os.flush();
            os.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取文件内容
     * @param path 文件路径
     * @return 文件内容；若文件不存在或读取失败则返回 null
     */
    public static String readFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除文件
     * @param path 文件路径
     * @return 成功返回 true，失败返回 false
     */
    public static boolean deleteFile(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                return file.delete();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从网络下载文件
     * @param url 下载地址
     * @param destPath 保存路径
     * @return 成功返回 true，失败返回 false
     */
    public static boolean downloadFile(String url, String destPath) {
        try {
            File file = new File(destPath);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                conn.disconnect();
                return false;
            }
            InputStream is = conn.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            FileOutputStream fos = new FileOutputStream(file);
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.close();
            is.close();
            conn.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行 shell 命令（Agent 工具入口）
     * @param command 完整 shell 命令字符串
     * @param p 执行级别：0=Shizuku 执行（需要 Shizuku 服务已授权），1=Root 执行（需要 Root 权限）
     * @return 命令的标准输出文本；若命令失败则返回以 "ERROR:" 开头的错误信息
     */
    public static String execShell(String command, int p) {
        if (p == 0) {
            // Shizuku 执行
            String out = execShizukuWithOutput(new String[]{"sh", "-c", command});
            if (out != null) return out;
            return "ERROR: Shizuku 不可用或未授权";
        }
        // p == 1：Root 执行
        return execRootWithOutput(command);
    }

    /** 执行 Root 命令并返回标准输出（带超时，失败时返回带 ERROR: 前缀的错误文本） */
    private static String execRootWithOutput(String command) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                BufferedReader errReader = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                StringBuilder errSb = new StringBuilder();
                String errLine;
                while ((errLine = errReader.readLine()) != null) {
                    errSb.append(errLine).append("\n");
                }
                errReader.close();
                String errMsg = errSb.toString().trim();
                return "ERROR: exit code " + exitCode
                        + (errMsg.length() > 0 ? "\n" + errMsg : "");
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /** 执行普通 shell 命令并返回标准输出（失败时返回带 ERROR: 前缀的错误文本） */
    private static String execShellWithOutput(String command) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                // 读取 stderr 以便返回详细错误信息
                BufferedReader errReader = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                StringBuilder errSb = new StringBuilder();
                String errLine;
                while ((errLine = errReader.readLine()) != null) {
                    errSb.append(errLine).append("\n");
                }
                errReader.close();
                String errMsg = errSb.toString().trim();
                return "ERROR: exit code " + exitCode
                        + (errMsg.length() > 0 ? "\n" + errMsg : "");
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 列出目录内容
     * @param path 目录路径
     * @return 目录内容列表字符串；若失败则返回 null
     */
    public static String listFiles(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                return null;
            }
            File[] files = dir.listFiles();
            if (files == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                sb.append(f.isDirectory() ? "[DIR] " : "[FILE] ");
                sb.append(f.getName());
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 搜索文件（支持简单通配符 * 和 ?）
     * @param dirPath 搜索根目录
     * @param pattern 文件名模式，如 *.apk
     * @return 匹配文件路径列表字符串；若失败则返回 null
     */
    public static String findFiles(String dirPath, String pattern) {
        try {
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) {
                return null;
            }
            List<File> result = new ArrayList<File>();
            findFilesRecursive(dir, pattern, result, 0);
            if (result.isEmpty()) return "未找到匹配文件";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < result.size(); i++) {
                sb.append(result.get(i).getAbsolutePath()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void findFilesRecursive(File dir, String pattern, List<File> result, int depth) {
        if (depth > 10) return; // 防止过深递归
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile() && matchWildcard(f.getName(), pattern)) {
                result.add(f);
            } else if (f.isDirectory()) {
                findFilesRecursive(f, pattern, result, depth + 1);
            }
        }
    }

    /** 简单的通配符匹配，支持 * 和 ? */
    private static boolean matchWildcard(String name, String pattern) {
        if (pattern == null || pattern.equals("*")) return true;
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        try {
            return name.matches(regex);
        } catch (Exception e) {
            return name.contains(pattern.replace("*", "").replace("?", ""));
        }
    }

    public static boolean forceStopApp(String packageName) {
        String[] cmd = {"am", "force-stop", packageName};
        if (execShizuku(cmd)) return true;
        if (execRoot("am force-stop " + packageName)) return true;
        return execShell(cmd);
    }
}