package dclnb.dcltools;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * MainActivity - 主界面（参照弑神者 GodActivity）。
 *
 * 功能：
 *   1. 展示工具卡片入口
 *   2. 启动时异步检查服务器公告并弹窗提示（每次都显示）
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String NOTICE_API_URL = "https://qght.cn/echouser.php?file=notice&admin=114514000";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化UI
        setupToolbar();
        setupClickListeners();
        
        // 延迟1秒检查公告（等待界面稳定）
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkAndShowNotice();
            }
        }, 1000);
    }

    /**
     * 设置标题栏
     */
    private void setupToolbar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setTitle("工具箱");
            }
        }
    }

    /**
     * 设置各卡片点击事件
     */
    private void setupClickListeners() {
        LinearLayout cardChat = (LinearLayout) findViewById(R.id.card_chat);
        LinearLayout cardCrypto = (LinearLayout) findViewById(R.id.card_crypto);
        LinearLayout cardShell = (LinearLayout) findViewById(R.id.card_shell);
        LinearLayout cardSecurity = (LinearLayout) findViewById(R.id.card_security);
        LinearLayout cardBenchmark = (LinearLayout) findViewById(R.id.card_benchmark);
        LinearLayout cardFileAnalyzer = (LinearLayout) findViewById(R.id.card_file_analyzer);
        LinearLayout cardGithub = (LinearLayout) findViewById(R.id.card_github);
        LinearLayout cardSponsor = (LinearLayout) findViewById(R.id.card_sponsor);

        if (cardChat != null) {
            cardChat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, ChatActivity.class);
                    startActivity(i);
                }
            });
        }

        if (cardCrypto != null) {
            cardCrypto.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, CryptoActivity.class);
                    startActivity(i);
                }
            });
        }

        if (cardShell != null) {
            cardShell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, ShellExecutorActivity.class);
                    startActivity(i);
                }
            });
        }

        if (cardSecurity != null) {
            cardSecurity.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, SecurityActivity.class);
                    startActivity(i);
                }
            });
        }

        if (cardBenchmark != null) {
            cardBenchmark.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, BenchmarkActivity.class);
                    startActivity(i);
                }
            });
        }

        if (cardFileAnalyzer != null) {
            cardFileAnalyzer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, FileAnalyzerActivity.class);
                    startActivity(i);
                }
            });
        }

        if (cardGithub != null) {
            cardGithub.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/qetuo518/dcl_tools/"));
                    startActivity(browserIntent);
                }
            });
        }

        if (cardSponsor != null) {
            cardSponsor.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, ZfActivity.class);
                    startActivity(i);
                }
            });
        }
    }

    /**
     * 异步获取服务器公告并弹窗提示（每次都显示）。
     */
    private void checkAndShowNotice() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "开始获取公告...");
                    final String noticeContent = fetchNoticeFromServer();
                    
                    if (noticeContent == null) {
                        Log.w(TAG, "获取公告失败（网络错误或API返回空）");
                        return;
                    }
                    
                    Log.d(TAG, "获取到公告内容: [" + noticeContent + "]");

                    final String normalized = noticeContent.trim();
                    
                    // 将 <br> 标签替换为换行符
                    final String displayText = normalized.replaceAll("<br\\s*/?>", "\n");
                    
                    // 在主线程显示弹窗（每次都显示）
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Log.d(TAG, "正在显示公告弹窗...");
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("公告")
                                        .setMessage(displayText)
                                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                Log.d(TAG, "用户点击了确定");
                                            }
                                        })
                                        .setCancelable(false)
                                        .show();
                                Log.d(TAG, "公告弹窗已显示");
                            } catch (Exception e) {
                                Log.e(TAG, "显示弹窗异常: " + e.getMessage(), e);
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "检查公告异常: " + e.getMessage(), e);
                }
            }
        }).start();
    }

    /**
     * 从服务器获取公告内容。
     */
    private String fetchNoticeFromServer() {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            Log.d(TAG, "请求API: " + NOTICE_API_URL);
            URL url = new URL(NOTICE_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13)");

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "API返回状态码: " + responseCode);
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "公告接口返回非200状态码: " + responseCode);
                return null;
            }

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String result = sb.toString();
            Log.d(TAG, "API返回内容长度: " + result.length() + ", 内容: [" + result + "]");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "获取公告网络异常: " + e.getMessage(), e);
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
}