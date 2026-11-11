package dclnb.dcltools;

import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * BenchmarkActivity - 性能跑分测试
 */
public class BenchmarkActivity extends AppCompatActivity {

    private static final String TAG = "BenchmarkActivity";
    private static final long GL_TEST_MS = 5000;
    private static final long CPU_TEST_MS = 3000;

    private Button btnStart;
    private TextView tvFps;
    private TextView tvGlScore;
    private TextView tvFloatOps;
    private TextView tvAllocSpeed;
    private TextView tvCpuScore;
    private TextView tvTotalScore;
    private TextView tvDeviceInfo;
    private TextView tvProgressText;
    private ProgressBar progressBar;
    private BenchmarkView benchmarkView;

    private Thread testThread;
    private volatile boolean testRunning = false;
    
    private static float staticFpsResult = 0f;
    private static float staticFloatOps = 0f;
    private static float staticAllocSpeed = 0f;
    private static int staticTotalScore = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_benchmark);

        setupToolbar();
        initViews();
        showDeviceInfo();
    }

    private void setupToolbar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setTitle("性能跑分");
            }
        }
    }

    private void initViews() {
        btnStart = (Button) findViewById(R.id.btn_start);
        tvFps = (TextView) findViewById(R.id.tv_fps);
        tvGlScore = (TextView) findViewById(R.id.tv_gl_score);
        tvFloatOps = (TextView) findViewById(R.id.tv_float_ops);
        tvAllocSpeed = (TextView) findViewById(R.id.tv_alloc_speed);
        tvCpuScore = (TextView) findViewById(R.id.tv_cpu_score);
        tvTotalScore = (TextView) findViewById(R.id.tv_total_score);
        tvDeviceInfo = (TextView) findViewById(R.id.tv_device_info);
        tvProgressText = (TextView) findViewById(R.id.tv_progress_text);
        progressBar = (ProgressBar) findViewById(R.id.progress_bar);
        benchmarkView = (BenchmarkView) findViewById(R.id.benchmark_view);

        benchmarkView.post(new Runnable() {
            public void run() {
                benchmarkView.start();
            }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBenchmark();
            }
        });
    }

    private void showDeviceInfo() {
        String info = Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        tvDeviceInfo.setText(info);
    }

    private void startBenchmark() {
        if (testRunning) return;
        testRunning = true;

        btnStart.setEnabled(false);
        tvFps.setText("--");
        tvGlScore.setText("--");
        tvFloatOps.setText("--");
        tvAllocSpeed.setText("--");
        tvCpuScore.setText("--");
        tvTotalScore.setText("--");
        tvProgressText.setText("正在准备测试...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        testThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runBenchmark();
            }
        });
        testThread.start();
    }

    private void runBenchmark() {
        try {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvProgressText.setText("正在进行2D渲染测试...");
                    progressBar.setProgress(20);
                }
            });

            staticFpsResult = runGLTest();

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvProgressText.setText("正在进行CPU浮点运算测试...");
                    progressBar.setProgress(60);
                }
            });

            final float floatOpsLocal = runCpuTest();
            staticFloatOps = floatOpsLocal;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvProgressText.setText("正在进行内存分配速度测试...");
                    progressBar.setProgress(80);
                }
            });

            final float allocSpeedLocal = runMemoryTest();
            staticAllocSpeed = allocSpeedLocal;

            final float glScore = staticFpsResult * 10f;
            final float cpuScore = staticFloatOps / 1000000f;
            final float allocScore = staticAllocSpeed / 1024f;
            staticTotalScore = (int) Math.round(glScore + cpuScore * 5f + allocScore * 2f);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvFps.setText(String.format("%.1f FPS", staticFpsResult));
                    tvGlScore.setText(String.format("%.0f 分", glScore));
                    tvFloatOps.setText(String.format("%.2f M ops/s", staticFloatOps / 1000000f));
                    tvAllocSpeed.setText(String.format("%.1f MB/s", staticAllocSpeed / 1024f));
                    tvCpuScore.setText(String.format("%.0f 分", cpuScore * 5f));
                    tvTotalScore.setText(String.valueOf(staticTotalScore));
                    tvProgressText.setText("测试完成！");
                    progressBar.setProgress(100);
                    btnStart.setEnabled(true);
                }
            });
        } catch (Exception e) {
            final String err = e.getMessage();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvProgressText.setText("测试出错: " + err);
                    btnStart.setEnabled(true);
                }
            });
        } finally {
            testRunning = false;
        }
    }

    private float runGLTest() {
        // 正方体预热5秒（不计入FPS）
        benchmarkView.setMode(BenchmarkView.MODE_CUBIC);
        benchmarkView.startTest();
        try { Thread.sleep(5000); } catch (InterruptedException e) {}
        
        // 切换到游戏模式并重置计时器
        benchmarkView.setMode(BenchmarkView.MODE_GAME);
        benchmarkView.startTest(); // 重置计时器
        try { Thread.sleep(5000); } catch (InterruptedException e) {}
        
        return benchmarkView.getFPS();
    }

    private float runCpuTest() {
        long startTime = SystemClock.elapsedRealtime();
        long endTime = startTime + CPU_TEST_MS;
        double sum = 0;
        long iterations = 0;

        while (SystemClock.elapsedRealtime() < endTime) {
            for (int i = 0; i < 100000; i++) {
                sum += Math.sqrt(i * 1.0) * Math.sin(i * 0.1) + Math.cos(i * 0.05);
            }
            iterations += 100000;
        }

        long elapsed = SystemClock.elapsedRealtime() - startTime;
        return (iterations * 6.0f) / (elapsed / 1000.0f);
    }

    private float runMemoryTest() {
        long startTime = SystemClock.elapsedRealtime();
        long endTime = startTime + 2000;
        long totalBytes = 0;

        while (SystemClock.elapsedRealtime() < endTime) {
            byte[] buffer = new byte[1024];
            buffer[0] = (byte) 0x01;
            totalBytes += buffer.length;
        }

        long elapsed = SystemClock.elapsedRealtime() - startTime;
        return (totalBytes / 1024.0f) / (elapsed / 1000.0f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (benchmarkView != null) {
            benchmarkView.stop();
        }
    }
}