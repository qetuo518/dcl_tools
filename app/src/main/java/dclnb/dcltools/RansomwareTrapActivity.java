package dclnb.dcltools;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 勒索病毒诱捕设置页面。
 */
public class RansomwareTrapActivity extends android.app.Activity {

    private static final int REQ_WRITE_STORAGE = 1002;
    private boolean requestingPermission = false;
    private SharedPreferences prefs;
    private Switch switchView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(40, 40, 40, 40);

        // 标题
        TextView title = new TextView(this);
        title.setText("勒索病毒防护");
        title.setTextSize(22);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        // 说明
        TextView tip = new TextView(this);
        tip.setText("开启后会在 /sdcard/DCL安全/dclnb.txt 写入诱饵文件。\n" +
                "每秒检测该文件，若文件被删除或被篡改（内容非勒索病毒诱捕）" +
                "\n立即强制停止除系统应用外的所有应用。");
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 30);
        root.addView(tip);

        // 开关
        switchView = new Switch(this);
        switchView.setChecked(RansomwareTrapService.isEnabled(this));
        switchView.setTextOn("已开启");
        switchView.setTextOff("已关闭");
        switchView.setTextSize(15);
        switchView.setTextColor(getTextColor());
        root.addView(switchView);

        // 状态说明
        final TextView statusView = new TextView(this);
        updateStatus(statusView);
        root.addView(statusView);

        // 返回
        Button backBtn = new Button(this);
        backBtn.setText("返回");
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        root.addView(backBtn);

        scrollView.addView(root);
        setContentView(scrollView);

        switchView.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    if (!hasStoragePermission()) {
                        switchView.setChecked(false);
                        requestStoragePermission();
                        return;
                    }
                    prefs.edit().putBoolean("ransomware_trap_enabled", true).apply();
                    RansomwareTrapService.start(RansomwareTrapActivity.this);
                    Toast.makeText(RansomwareTrapActivity.this, "勒索病毒防护已开启", Toast.LENGTH_SHORT).show();
                } else {
                    prefs.edit().putBoolean("ransomware_trap_enabled", false).apply();
                    RansomwareTrapService.stop(RansomwareTrapActivity.this);
                    try {
                        java.io.File dir = new java.io.File("/sdcard/DCL安全");
                        if (dir.exists()) {
                            deleteRecursively(dir);
                        }
                    } catch (Throwable ignored) {}
                    Toast.makeText(RansomwareTrapActivity.this, "勒索病毒防护已关闭", Toast.LENGTH_SHORT).show();
                }
                updateStatus(statusView);
            }
        });
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (requestingPermission) return;
        requestingPermission = true;

        String msg;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            msg = "勒索病毒防护需要在 /sdcard 创建诱饵文件。\n" +
                    "请授予「所有文件访问权限」。\n" +
                    "点击「去授权」跳转到系统设置。";
            new android.app.AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage(msg)
                    .setPositiveButton("去授权", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            dialog.dismiss();
                            requestingPermission = false;
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                startActivity(intent);
                            }
                        }
                    })
                    .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            dialog.dismiss();
                            requestingPermission = false;
                        }
                    })
                    .setCancelable(false)
                    .show();
        } else {
            msg = "勒索病毒防护需要读取和写入外部存储的权限。";
            new android.app.AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage(msg)
                    .setPositiveButton("授权", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            dialog.dismiss();
                            requestingPermission = false;
                            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                    REQ_WRITE_STORAGE);
                        }
                    })
                    .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            dialog.dismiss();
                            requestingPermission = false;
                        }
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_WRITE_STORAGE) {
            requestingPermission = false;
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "未授权存储权限，无法开启勒索病毒防护", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateStatus(TextView tv) {
        boolean on = RansomwareTrapService.isEnabled(this);
        String status = on ? "运行中" : "已关闭";
        tv.setText("当前状态：" + status);
        tv.setTextColor(getSecondaryTextColor());
        tv.setTextSize(12);
        tv.setPadding(0, 8, 0, 0);
    }

    private void deleteRecursively(java.io.File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            for (java.io.File c : f.listFiles()) deleteRecursively(c);
        }
        f.delete();
    }

    private int getTextColor() {
        return Color.argb(255, 30, 30, 30);
    }

    private int getSecondaryTextColor() {
        return Color.argb(150, 90, 90, 90);
    }
}