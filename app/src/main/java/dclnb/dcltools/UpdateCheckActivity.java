package dclnb.dcltools;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 启动页：检查更新
 * - 显示转圈动画 + "检查更新..."
 * - 版本号自动获取本地 versionName
 * - 后台账号: 114514000
 * - 连接失败则提示并继续进入主页面
 */
public class UpdateCheckActivity extends AppCompatActivity {

    private static final String TAG = "UpdateCheckActivity";
    private static final String UPDATE_API_URL = "https://qght.cn/echouser.php?file=new&admin=114514000";
    private static final String UPDATE_API_URL_BACKUP = "https://qght.cc/echouser.php?file=new&admin=114514000";
    private static final String STARTNUM_API_URL = "https://qght.cn/startnum.php?user=114514000";
    private static final String STARTNUM_API_URL_BACKUP = "https://qght.cc/startnum.php?user=114514000";
    private static final long TIMEOUT_MS = 8000;

    private static class ViewRef {
        TextView tvStatus;
        ViewRef(TextView tv) { tvStatus = tv; }
    }

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean checkFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_check);

        final ProgressBar progressBar = findViewById(R.id.progress_bar);
        final TextView tvStatus = findViewById(R.id.tv_status);
        final TextView tvVersion = findViewById(R.id.tv_version);

        // 判断是否首次启动（首次启动会上报一次在线请求）
        checkFirstLaunch();

        // 获取本地版本号
        String localVersion = getLocalVersion();
        tvVersion.setText("版本 " + localVersion);

        // 开始检查更新
        checkUpdate(tvStatus);
    }

    /** 判断是否首次启动：若标志文件不存在，则上报在线请求并创建标志文件 */
    private void checkFirstLaunch() {
        final File flagFile = new File(getFilesDir(), "first_run.flag");
        if (flagFile.exists()) {
            return; // 已启动过，什么都不做
        }
        // 第一次启动：后台发送一次请求，其他不要干任何事情
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 尝试主网址，失败后尝试备用网址
                    httpGetWithFallback(STARTNUM_API_URL, STARTNUM_API_URL_BACKUP, 5000);
                } catch (Exception e) {
                    // 忽略失败
                } finally {
                    try {
                        flagFile.createNewFile();
                    } catch (Exception e) {
                        // 忽略失败
                    }
                }
            }
        }).start();
    }

    /** 获取本地版本号 */
    private String getLocalVersion() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.0";
        } catch (Exception e) {
            return "1.0";
        }
    }

    /** 检查更新 */
    private void checkUpdate(final TextView tvStatus) {
        final ViewRef ref = new ViewRef(tvStatus);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = httpGetWithFallback(UPDATE_API_URL, UPDATE_API_URL_BACKUP, TIMEOUT_MS);

                    if (!checkFinished) {
                        if (response == null || response.isEmpty()) {
                            // 网络错误
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    ref.tvStatus.setText("无法连接至服务器");
                                    Toast.makeText(UpdateCheckActivity.this,
                                            "无法连接至服务器，将进入主页面",
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            // 解析响应
                            final String newVersion = extractField(response, "软件版本:\\((.*?)\\)");
                            String content = extractField(response, "更新内容:\\((.*?)\\)");
                            final String updateUrl = extractField(response, "更新链接:\\((.*?)\\)");

                            content = content.replace("<br>", "\n");
                            final String finalContent = content;
                            final String localVer = getLocalVersion();

                            if (newVersion.isEmpty()) {
                                // 无法解析
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        ref.tvStatus.setText("无法解析更新信息");
                                    }
                                });
                            } else if (versionGt(newVersion, localVer)) {
                                // 有新版本
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        showUpdateDialog(newVersion, finalContent, updateUrl);
                                    }
                                });
                            } else {
                                // 已是最新
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        ref.tvStatus.setText("已是最新版本");
                                    }
                                });
                            }
                        }
                    }
                } catch (Exception e) {
                    // 异常时也继续进入主页面
                    if (!checkFinished) {
                        final String errMsg = e.getMessage();
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                ref.tvStatus.setText("检查失败，继续进入...");
                                Toast.makeText(UpdateCheckActivity.this,
                                        "检查更新失败：" + errMsg,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } finally {
                    // 延迟后进入主页面（给用户看提示）
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!checkFinished) {
                                checkFinished = true;
                                goToMainActivity();
                            }
                        }
                    }, 1500);
                }
            }
        }).start();
    }

    /** 显示更新对话框 */
    private void showUpdateDialog(final String newVersion, final String content, final String updateUrl) {
        checkFinished = true;
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + newVersion)
                .setMessage("更新内容：\n" + content)
                .setPositiveButton("更新", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            if (updateUrl != null && updateUrl.length() > 0) {
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl));
                                startActivity(intent);
                            }
                        } catch (Exception e) {
                            Toast.makeText(UpdateCheckActivity.this,
                                    "无法打开更新链接", Toast.LENGTH_SHORT).show();
                        }
                        finish();
                    }
                })
                .setNegativeButton("稍后再说", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        goToMainActivity();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /** 进入主页面 */
    private void goToMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    /** HTTP GET 请求 */
    private String httpGet(String urlStr, long timeoutMs) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout((int) timeoutMs);
            conn.setReadTimeout((int) timeoutMs);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    /** HTTP GET 请求，先尝试主网址，失败后尝试备用网址 */
    private String httpGetWithFallback(String primaryUrl, String backupUrl, long timeoutMs) {
        String result = httpGet(primaryUrl, timeoutMs);
        if (result != null) {
            return result;
        }
        if (backupUrl != null && !backupUrl.isEmpty()) {
            Log.d(TAG, "主网址失败，尝试备用网址: " + backupUrl);
            return httpGet(backupUrl, timeoutMs);
        }
        return null;
    }

    /** 正则提取第一个分组 */
    private String extractField(String text, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL).matcher(text);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 版本比较 v1 > v2 */
    private boolean versionGt(String v1, String v2) {
        if (v1 == null || v2 == null) return false;
        String[] parts1 = v1.split("[^0-9]+");
        String[] parts2 = v2.split("[^0-9]+");
        int n = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < n; i++) {
            int a = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
            int b = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
            if (a > b) return true;
            if (a < b) return false;
        }
        return false;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
