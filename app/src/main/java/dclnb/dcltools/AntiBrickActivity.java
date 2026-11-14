package dclnb.dcltools;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class AntiBrickActivity extends AppCompatActivity {
    private static final String TAG = "AntiBrickActivity";
    private static final int REQUEST_CODE_PICK_SCRIPT = 1001;

    private Button btnPickFile;
    private Button btnAnalyze;
    private ProgressBar pbLoading;
    private TextView tvFilePath;
    private TextView tvScanStatus;
    private TextView tvResult;

    private Uri selectedScriptUri;
    private boolean scriptSelected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_anti_brick);
        setupToolbar();
        initViews();
        setupClickListeners();
    }

    private void setupToolbar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("防格机检测");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void initViews() {
        btnPickFile = (Button) findViewById(R.id.btn_pick_file);
        btnAnalyze = (Button) findViewById(R.id.btn_analyze);
        pbLoading = (ProgressBar) findViewById(R.id.pb_loading);
        tvFilePath = (TextView) findViewById(R.id.tv_file_path);
        tvScanStatus = (TextView) findViewById(R.id.tv_scan_status);
        tvResult = (TextView) findViewById(R.id.tv_result);
    }

    private void setupClickListeners() {
        final Button pickBtn = btnPickFile;
        final Button analyzeBtn = btnAnalyze;
        
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });

        analyzeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkAndStartScan();
            }
        });
    }

    private void checkAndStartScan() {
        if (!scriptSelected || selectedScriptUri == null) {
            Toast.makeText(this, "请先选择Shell脚本文件", Toast.LENGTH_SHORT).show();
            return;
        }
        startScan(selectedScriptUri);
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择Shell脚本"), REQUEST_CODE_PICK_SCRIPT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_SCRIPT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedScriptUri = data.getData();
            scriptSelected = true;
            tvFilePath.setText(selectedScriptUri.toString());
            btnAnalyze.setEnabled(true);
            tvResult.setText("已选择脚本，点击开始检测进行分析");
            tvResult.setTextColor(0xFF4CAF50);
            Log.d(TAG, "选择脚本: " + selectedScriptUri);
        }
    }

    private void startScan(final Uri scriptUri) {
        final Button pickBtn = btnPickFile;
        final Button analyzeBtn = btnAnalyze;
        final ProgressBar loading = pbLoading;
        final TextView statusTv = tvScanStatus;
        final TextView resultTv = tvResult;
        final AntiBrickActivity activity = this;

        loading.setVisibility(View.VISIBLE);
        analyzeBtn.setEnabled(false);
        pickBtn.setEnabled(false);
        statusTv.setVisibility(View.VISIBLE);
        statusTv.setText("正在扫描脚本...");
        statusTv.setTextColor(0xFFFF9800);
        resultTv.setText("");

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ShellSandbox.ExecutionResult result = ShellSandbox.executeInSandbox(activity, scriptUri);
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loading.setVisibility(View.GONE);
                            analyzeBtn.setEnabled(true);
                            pickBtn.setEnabled(true);

                            if (result.scanResult == null) {
                                statusTv.setVisibility(View.GONE);
                                resultTv.setText(result.error != null ? result.error : "无法读取脚本内容");
                                resultTv.setTextColor(0xFFF44336);
                                return;
                            }

                            ShellSandbox.ScanResult scanResult = result.scanResult;
                            int riskColor = getColorByRiskLevel(scanResult.riskLevel);
                            statusTv.setTextColor(riskColor);
                            statusTv.setText("风险等级: " + scanResult.riskLevel.label + " (评分: " + scanResult.riskScore + ")");
                            resultTv.setText(buildResultText(scanResult, result));
                            resultTv.setTextColor(riskColor);

                            if (!scanResult.isSafeToExecute) {
                                new AlertDialog.Builder(activity)
                                    .setTitle("危险检测！")
                                    .setMessage("该脚本包含高危危险命令，已阻止执行！\n\n危险命令:\n" + String.join("\n", scanResult.dangerCommands))
                                    .setPositiveButton("知道了", null)
                                    .setCancelable(false)
                                    .show();
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "扫描异常: " + e.getMessage(), e);
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loading.setVisibility(View.GONE);
                            analyzeBtn.setEnabled(true);
                            pickBtn.setEnabled(true);
                            statusTv.setVisibility(View.GONE);
                            resultTv.setText("扫描出错: " + errorMsg);
                            resultTv.setTextColor(0xFFF44336);
                            Toast.makeText(activity, "扫描失败: " + errorMsg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
        thread.start();
    }

    private String buildResultText(ShellSandbox.ScanResult scanResult, ShellSandbox.ExecutionResult execResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("摘要: ").append(scanResult.summary).append("\n\n");

        if (!scanResult.dangerCommands.isEmpty()) {
            sb.append("危险命令:\n");
            for (String cmd : scanResult.dangerCommands) sb.append("  ").append(cmd).append("\n");
            sb.append("\n");
        }

        if (!scanResult.warnings.isEmpty()) {
            sb.append("警告信息:\n");
            for (String warning : scanResult.warnings) sb.append("  ").append(warning).append("\n");
            sb.append("\n");
        }

        if (execResult.blocked) {
            sb.append("执行状态: 已阻止\n原因: ").append(execResult.error);
        } else if (execResult.success) {
            sb.append("执行状态: 成功 (退出码: ").append(execResult.exitCode).append(")\n");
            if (!execResult.output.isEmpty()) sb.append("输出:\n").append(execResult.output);
        } else if (execResult.timeout) {
            sb.append("执行状态: 超时\n原因: ").append(execResult.error);
        } else {
            sb.append("执行状态: 失败\n原因: ").append(execResult.error);
        }
        return sb.toString();
    }

    private int getColorByRiskLevel(ShellSandbox.RiskLevel level) {
        switch (level) {
            case SAFE: return 0xFF4CAF50;
            case LOW: return 0xFF8BC34A;
            case MEDIUM: return 0xFFFFC107;
            case HIGH: return 0xFFFF9800;
            case DANGEROUS: return 0xFFF44336;
            default: return 0xFF757575;
        }
    }
}