package dclnb.dcltools;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

import androidx.appcompat.app.AppCompatActivity;

public class ShellExecutorActivity extends AppCompatActivity {

    private static final int SHIZUKU_REQUEST_CODE = 10087;

    private EditText commandInput;
    private TextView outputView;
    private RadioGroup modeGroup;
    private Button executeButton;

    private int currentMode = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        // 标题
        TextView title = new TextView(this);
        title.setText("Shell 执行器");
        title.setTextSize(20);
        title.setTextColor(Color.argb(255, 30, 30, 30));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        // 模式选择标签
        TextView modeLabel = new TextView(this);
        modeLabel.setText("执行模式");
        modeLabel.setTextSize(14);
        modeLabel.setTextColor(Color.argb(150, 90, 90, 90));
        modeLabel.setPadding(0, 0, 0, 8);
        root.addView(modeLabel);

        // 模式选择 RadioGroup
        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(LinearLayout.HORIZONTAL);
        modeGroup.setGravity(Gravity.CENTER_HORIZONTAL);

        // SH 模式单选
        RadioButton shBtn = new RadioButton(this);
        shBtn.setText("sh");
        shBtn.setTextSize(12);
        shBtn.setTag(0);
        shBtn.setOnClickListener(onModeClick);
        LinearLayout.LayoutParams shParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        shBtn.setLayoutParams(shParams);

        // Shizuku 模式单选
        RadioButton shizukuBtn = new RadioButton(this);
        shizukuBtn.setText("shizuku");
        shizukuBtn.setTextSize(12);
        shizukuBtn.setTag(1);
        shizukuBtn.setOnClickListener(onModeClick);
        LinearLayout.LayoutParams shizukuParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        shizukuBtn.setLayoutParams(shizukuParams);

        // Root 模式单选
        RadioButton rootBtn = new RadioButton(this);
        rootBtn.setText("root");
        rootBtn.setTextSize(12);
        rootBtn.setTag(2);
        rootBtn.setOnClickListener(onModeClick);
        LinearLayout.LayoutParams rootParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        rootBtn.setLayoutParams(rootParams);

        modeGroup.addView(shBtn);
        modeGroup.addView(shizukuBtn);
        modeGroup.addView(rootBtn);
        root.addView(modeGroup);

        // 命令输入标签
        TextView cmdLabel = new TextView(this);
        cmdLabel.setText("命令");
        cmdLabel.setTextSize(14);
        cmdLabel.setTextColor(Color.argb(150, 90, 90, 90));
        cmdLabel.setPadding(0, 16, 0, 8);
        root.addView(cmdLabel);

        // 命令输入框
        commandInput = new EditText(this);
        commandInput.setHint("输入 shell 命令...");
        commandInput.setTextSize(14);
        commandInput.setSingleLine(false);
        commandInput.setMaxLines(4);
        commandInput.setPadding(12, 12, 12, 12);
        root.addView(commandInput);

        // 执行按钮
        executeButton = new Button(this);
        executeButton.setText("执行");
        executeButton.setTextSize(16);
        executeButton.setAllCaps(false);
        executeButton.setPadding(0, 12, 0, 12);
        executeButton.setOnClickListener(onExecuteClick);
        root.addView(executeButton);

        // 输出标签
        TextView outLabel = new TextView(this);
        outLabel.setText("输出");
        outLabel.setTextSize(14);
        outLabel.setTextColor(Color.argb(150, 90, 90, 90));
        outLabel.setPadding(0, 16, 0, 8);
        root.addView(outLabel);

        // 输出区域
        ScrollView outputScroll = new ScrollView(this);
        outputView = new TextView(this);
        outputView.setTextSize(12);
        outputView.setTextColor(Color.argb(255, 20, 20, 20));
        outputView.setBackgroundColor(Color.argb(255, 248, 248, 248));
        outputView.setPadding(12, 12, 12, 12);
        outputScroll.addView(outputView);
        root.addView(outputScroll);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1
        );
        outputScroll.setLayoutParams(params);

        setContentView(root);

        // 默认选中 sh 模式
        modeGroup.check(shBtn.getId());
        currentMode = 0;
    }

    private final View.OnClickListener onModeClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            currentMode = (Integer) v.getTag();
        }
    };

    private final View.OnClickListener onExecuteClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            String command = commandInput.getText().toString().trim();
            if (command.isEmpty()) {
                Toast.makeText(ShellExecutorActivity.this, "请输入命令", Toast.LENGTH_SHORT).show();
                return;
            }
            executeCommand(command);
        }
    };

    private void executeCommand(final String command) {
        outputView.setText("执行中...");

        final StringBuilder[] output = {new StringBuilder()};
        final StringBuilder[] error = {new StringBuilder()};
        final boolean[] success = {false};

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (currentMode == 0) {
                        success[0] = executeSh(command, output[0], error[0]);
                    } else if (currentMode == 1) {
                        success[0] = executeShizuku(command, output[0], error[0]);
                    } else if (currentMode == 2) {
                        success[0] = executeRoot(command, output[0], error[0]);
                    }
                } catch (Exception e) {
                    error[0].append("错误: ").append(e.getMessage()).append("\n");
                }

                final String result;
                if (success[0]) {
                    result = output[0].toString();
                } else {
                    result = error[0].length() > 0 ? error[0].toString() : "执行失败";
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        outputView.setText(result.isEmpty() ? "(无输出)" : result);
                    }
                });
            }
        }).start();
    }

    private boolean executeSh(final String command, final StringBuilder output, final StringBuilder error) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            java.io.BufferedReader errReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errReader.readLine()) != null) {
                error.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            error.append("异常: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    private boolean executeShizuku(final String command, final StringBuilder output, final StringBuilder error) {
        if (!Shizuku.pingBinder()) {
            error.append("Shizuku 未运行，请先启动 Shizuku 应用\n");
            return false;
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    Shizuku.removeRequestPermissionResultListener(this);
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        executeShizuku(command, output, error);
                    } else {
                        error.append("Shizuku 权限被拒绝\n");
                    }
                }
            });
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
            return false;
        }

        try {
            // 使用 Shizuku.newProcess 执行命令（与项目其他模块一致）
            String[] cmd = new String[]{"sh", "-c", command};
            rikka.shizuku.ShizukuRemoteProcess proc = Shizuku.newProcess(cmd, null, null);

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream()));
            java.io.BufferedReader errReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errReader.readLine()) != null) {
                error.append(line).append("\n");
            }

            return proc.waitFor() == 0;
        } catch (Exception e) {
            error.append("Shizuku 执行失败: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    private boolean executeRoot(final String command, final StringBuilder output, final StringBuilder error) {
        if (!isRootAvailable()) {
            error.append("设备没有 Root 权限\n");
            return false;
        }

        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            java.io.BufferedReader errReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errReader.readLine()) != null) {
                error.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            error.append("Root 执行失败: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    private boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(null);
    }
}
