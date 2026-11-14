package dclnb.dcltools;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShellSandbox {
    private static final String TAG = "ShellSandbox";
    private static final int TIMEOUT_MS = 30000;

    private static final Pattern[] DANGEROUS_PATTERNS = {
        Pattern.compile("mkfs\\.(ext|fat|ntfs|xfs|f2fs)\\s", Pattern.CASE_INSENSITIVE),
        Pattern.compile("fdisk\\s+-[a-zA-Z]*f", Pattern.CASE_INSENSITIVE),
        Pattern.compile("wipefs", Pattern.CASE_INSENSITIVE),
        Pattern.compile("rm\\s+.*-rf\\s+/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("dd\\s+if=.*/dev/zero\\s+of=/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("reboot\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("poweroff\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("halt\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("shutdown\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("mount\\s+/dev/.*(\\s|-)(system|data|cache|boot|recovery)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("umount\\s+/(system|data|cache)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("wget\\s+.*\\.apk", Pattern.CASE_INSENSITIVE),
        Pattern.compile("curl\\s+.*\\.apk", Pattern.CASE_INSENSITIVE),
        Pattern.compile("nc\\s+-[lpe]\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("shred\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("insmod\\s+.*(rootkit|hide)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("LD_PRELOAD", Pattern.CASE_INSENSITIVE),
    };

    public enum RiskLevel {
        SAFE("安全", 0), LOW("低风险", 10), MEDIUM("中风险", 30), HIGH("高风险", 60), DANGEROUS("危险", 100);
        final String label;
        final int score;
        RiskLevel(String label, int score) { this.label = label; this.score = score; }
    }

    public static class ScanResult {
        public RiskLevel riskLevel = RiskLevel.SAFE;
        public int riskScore = 0;
        public List<String> warnings = new ArrayList<>();
        public List<String> dangerCommands = new ArrayList<>();
        public boolean isSafeToExecute = true;
        public String summary = "";

        public void addWarning(String warning, boolean isDanger) {
            warnings.add(warning);
            if (isDanger) { dangerCommands.add(warning); riskScore += 25; }
            else { riskScore += 5; }
        }

        public void calculateRiskLevel() {
            if (riskScore >= 80) { riskLevel = RiskLevel.DANGEROUS; isSafeToExecute = false; summary = "检测到高危危险命令，禁止执行！"; }
            else if (riskScore >= 50) { riskLevel = RiskLevel.HIGH; isSafeToExecute = false; summary = "存在较高风险，建议谨慎操作"; }
            else if (riskScore >= 20) { riskLevel = RiskLevel.MEDIUM; isSafeToExecute = true; summary = "存在中等风险，请注意观察"; }
            else if (riskScore > 0) { riskLevel = RiskLevel.LOW; isSafeToExecute = true; summary = "基本安全，但仍需注意"; }
            else { riskLevel = RiskLevel.SAFE; isSafeToExecute = true; summary = "未检测到危险命令，可以安全执行"; }
        }
    }

    public static ScanResult staticScan(String scriptContent) {
        ScanResult result = new ScanResult();
        if (scriptContent == null || scriptContent.isEmpty()) {
            result.addWarning("脚本内容为空", false);
            result.calculateRiskLevel();
            return result;
        }

        // 1. 直接正则匹配
        String[] lines = scriptContent.split("\n");
        for (int lineNum = 0; lineNum < lines.length; lineNum++) {
            String trimmedLine = lines[lineNum].trim();
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) continue;
            for (Pattern pattern : DANGEROUS_PATTERNS) {
                if (pattern.matcher(trimmedLine).find()) {
                    result.addWarning("行 " + (lineNum + 1) + ": 匹配危险模式 [" + truncate(pattern.pattern(), 40) + "]", true);
                }
            }
        }

        // 2. Base64检测
        scanForBase64(result, scriptContent);

        // 3. 其他安全检查
        if (scriptContent.contains("| rm") || scriptContent.contains(" |rm")) {
            result.addWarning("检测到管道重定向到删除命令", true);
        }
        if (scriptContent.contains("eval ")) {
            result.addWarning("使用eval可能执行恶意代码", false);
        }
        if (scriptContent.length() > 10000) {
            result.addWarning("脚本文件过大，可能存在隐藏恶意代码", false);
        }
        if (scriptContent.contains("base64") && scriptContent.contains("-d")) {
            result.addWarning("检测到base64解码操作，可能存在编码绕过", true);
        }
        if (scriptContent.contains("bash -c")) {
            result.addWarning("检测到bash -c执行，可能动态构造命令", true);
        }
        if (scriptContent.contains("sh -c")) {
            result.addWarning("检测到sh -c执行，可能动态构造命令", true);
        }

        result.calculateRiskLevel();
        return result;
    }

    private static void scanForBase64(ScanResult result, String scriptContent) {
        // 检测 base64 命令执行
        if (scriptContent.contains("base64") && (scriptContent.contains("-d") || scriptContent.contains("--decode"))) {
            result.addWarning("检测到 base64 解码命令，可能存在编码绕过", true);
        }
        
        // 提取并解码 Base64 字符串
        Pattern base64Pattern = Pattern.compile("[A-Za-z0-9+/]{20,}={0,2}");
        Matcher matcher = base64Pattern.matcher(scriptContent);
        
        while (matcher.find()) {
            String encoded = matcher.group();
            try {
                byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                String decodedStr = new String(decoded, "UTF-8");
                Log.d(TAG, "解码Base64: " + encoded.substring(0, Math.min(20, encoded.length())) + "...");
                checkDecodedContent(result, encoded, decodedStr);
            } catch (Exception e) {
                // 不是有效的Base64，跳过
            }
        }
        
        // 检测 ${} 变量拼接命令
        if (scriptContent.contains("${") || scriptContent.contains("$(")) {
            result.addWarning("检测到变量拼接/命令替换，可能动态构造危险命令", true);
        }
        
        // 检测 xxd/od 等 hex 解码
        if (scriptContent.contains("xxd") && scriptContent.contains("-r")) {
            result.addWarning("检测到 xxd 解码，可能隐藏恶意命令", true);
        }
        if (scriptContent.contains("printf") && scriptContent.contains("\\x")) {
            result.addWarning("检测到 printf 十六进制编码，可能隐藏恶意命令", true);
        }
        
        // 检测 echo 管道到 bash/sh
        if (scriptContent.matches(".*echo\\s+.*\\|\\s*(bash|sh).*")) {
            result.addWarning("检测到 echo | bash 模式，常见下载执行攻击", true);
        }
        if (scriptContent.matches(".*curl.*\\|\\s*(bash|sh).*") || scriptContent.matches(".*wget.*\\|\\s*(bash|sh).*")) {
            result.addWarning("检测到下载管道到 bash/sh，可能执行远程恶意代码", true);
        }
    }
    
    private static void checkDecodedContent(ScanResult result, String encoded, String decodedStr) {
        String lowerDecoded = decodedStr.toLowerCase();
        if (lowerDecoded.contains("rm -rf /") || lowerDecoded.contains("rm -rf --no-preserve-root")) {
            result.addWarning("Base64解码包含危险删除命令: " + truncate(decodedStr, 50), true);
        }
        if (lowerDecoded.contains("mkfs.") || lowerDecoded.contains("/dev/")) {
            result.addWarning("Base64解码包含格式化命令: " + truncate(decodedStr, 50), true);
        }
        if (lowerDecoded.contains("reboot") || lowerDecoded.contains("poweroff") || lowerDecoded.contains("halt")) {
            result.addWarning("Base64解码包含系统关机命令: " + truncate(decodedStr, 50), true);
        }
        if (lowerDecoded.contains("dd if=/dev/zero") || lowerDecoded.contains("/dev/zero")) {
            result.addWarning("Base64解码包含 dd 擦除命令: " + truncate(decodedStr, 50), true);
        }
        if (lowerDecoded.contains("chmod 777") || lowerDecoded.contains("chmod -R 777")) {
            result.addWarning("Base64解码包含权限修改: " + truncate(decodedStr, 50), true);
        }
        if (lowerDecoded.contains("/bin/bash") || lowerDecoded.contains("/bin/sh")) {
            result.addWarning("Base64解码包含shell路径: " + truncate(decodedStr, 50), true);
        }
        if (lowerDecoded.contains("nc -e") || lowerDecoded.contains("ncat") || lowerDecoded.contains("netcat")) {
            result.addWarning("Base64解码包含netcat反向shell: " + truncate(decodedStr, 50), true);
        }
        if (lowerDecoded.contains("wget") || lowerDecoded.contains("curl")) {
            result.addWarning("Base64解码包含下载命令: " + truncate(decodedStr, 50), true);
        }
    }

    public static String readContentFromUri(Context context, Uri uri) {
        Log.d(TAG, "读取URI: " + uri);
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                reader.close();
                Log.d(TAG, "成功读取，长度: " + sb.length());
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "ContentResolver失败: " + e.getMessage());
        }
        try {
            String path = getPathFromUri(context, uri);
            if (path != null) {
                File file = new File(path);
                if (file.exists()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    reader.close();
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "路径方式失败: " + e.getMessage());
        }
        return null;
    }

    private static String getPathFromUri(Context context, Uri uri) {
        String path = uri.getPath();
        if (path != null && path.contains("/document/")) {
            String docId = path.substring(path.indexOf("/document/") + 10);
            if (docId.startsWith("primary:")) {
                String fullPath = "/storage/emulated/0" + docId.substring("primary:".length());
                if (new File(fullPath).exists()) return fullPath;
            }
        }
        if (path != null && new File(path).exists()) return path;
        try {
            String[] projection = {android.provider.MediaStore.Files.FileColumns.DATA};
            android.database.Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DATA);
                if (index >= 0) { String p = cursor.getString(index); cursor.close(); if (p != null) return p; }
                cursor.close();
            }
        } catch (Exception e) { Log.e(TAG, "MediaStore查询失败: " + e.getMessage()); }
        return null;
    }

    public static ExecutionResult executeInSandbox(Context context, Uri scriptUri) {
        ExecutionResult execResult = new ExecutionResult();
        execResult.success = false;
        String scriptContent = readContentFromUri(context, scriptUri);
        if (scriptContent == null) { execResult.error = "无法读取脚本文件"; return execResult; }
        ScanResult scanResult = staticScan(scriptContent);
        execResult.scanResult = scanResult;
        if (!scanResult.isSafeToExecute) {
            execResult.blocked = true;
            execResult.error = "检测到危险命令，已阻止执行:\n" + String.join("\n", scanResult.dangerCommands);
            return execResult;
        }
        try {
            File sandboxDir = new File("/data/local/tmp/dcl_sandbox_" + System.currentTimeMillis());
            if (!sandboxDir.mkdirs()) { sandboxDir = new File("/sdcard/DCL_Sandbox"); sandboxDir.mkdirs(); }
            File sandboxScript = new File(sandboxDir, "run.sh");
            OutputStream os = new FileOutputStream(sandboxScript);
            os.write(scriptContent.getBytes("UTF-8"));
            os.flush(); os.close();
            Runtime.getRuntime().exec(new String[]{"chmod", "+x", sandboxScript.getAbsolutePath()}).waitFor();
            ProcessBuilder pb = new ProcessBuilder("sh", sandboxScript.getAbsolutePath());
            pb.directory(sandboxDir); pb.redirectErrorStream(true);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            long startTime = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                    process.destroy();
                    execResult.timeout = true;
                    execResult.error = "脚本执行超时（" + TIMEOUT_MS/1000 + "秒）";
                    break;
                }
            }
            reader.close();
            int exitCode = process.waitFor();
            deleteRecursively(sandboxDir);
            execResult.success = (exitCode == 0);
            execResult.exitCode = exitCode;
            execResult.output = output.toString();
            execResult.blocked = false;
        } catch (IOException e) { execResult.error = "执行异常: " + e.getMessage(); }
        catch (InterruptedException e) { execResult.error = "执行中断: " + e.getMessage(); Thread.currentThread().interrupt(); }
        catch (Exception e) { execResult.error = "未知错误: " + e.getMessage(); }
        return execResult;
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static String truncate(String str, int maxLength) {
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
    }

    public static class ExecutionResult {
        public boolean success = false;
        public boolean blocked = false;
        public boolean timeout = false;
        public int exitCode = -1;
        public String output = "";
        public String error = "";
        public ScanResult scanResult = null;
    }
}