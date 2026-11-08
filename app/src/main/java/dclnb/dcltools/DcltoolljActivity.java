package dclnb.dcltools;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 仅用于显示 assets/dcltoolslj.html 的页面。
 * 纯代码构建 WebView，不依赖任何 XML 布局。
 * 动画播放完后自动跳转到 DCL安全护盾。
 */
public class DcltoolljActivity extends AppCompatActivity {

    private WebView webView;
    private static final long ANIMATION_DURATION_MS = 5500L; // 动画时长约5.3秒

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // 让 WebView 自行处理页面内导航，避免跳到外部浏览器
        webView.setWebViewClient(new WebViewClient());

        webView.loadUrl("file:///android_asset/dcltoolslj.html");
        
        // 动画播放完后等待1秒再回到主页
        long delayMs = ANIMATION_DURATION_MS + 1000L;
        new Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(DcltoolljActivity.this, 
                        Class.forName("dclnb.dcltools.DclaqActivity"));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                } catch (Exception e) {
                    // 跳转失败时返回主界面
                    finish();
                }
            }
        }, delayMs);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
