package dclnb.dcltools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VirusScanActivity extends Activity {

    private static final String BLACKLIST_API_URL = "https://latools.top/api/v1/firewall-blacklist";

    private PackageManager pm;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button scanButton;
    private Button networkScanButton;
    private LinearLayout highRiskList;
    private LinearLayout mediumRiskList;
    private LinearLayout lowRiskList;
    private View highRiskContainer;
    private View mediumRiskContainer;
    private View lowRiskContainer;
    private TextView emptyView;

    private List<VirusApp> highRiskApps = new ArrayList<VirusApp>();
    private List<VirusApp> mediumRiskApps = new ArrayList<VirusApp>();
    private List<VirusApp> lowRiskApps = new ArrayList<VirusApp>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_virus_scan);

        pm = getPackageManager();

        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        statusText = (TextView) findViewById(R.id.statusText);
        scanButton = (Button) findViewById(R.id.scanButton);
        networkScanButton = (Button) findViewById(R.id.networkScanButton);
        highRiskList = (LinearLayout) findViewById(R.id.highRiskList);
        mediumRiskList = (LinearLayout) findViewById(R.id.mediumRiskList);
        lowRiskList = (LinearLayout) findViewById(R.id.lowRiskList);
        highRiskContainer = findViewById(R.id.highRiskContainer);
        mediumRiskContainer = findViewById(R.id.mediumRiskContainer);
        lowRiskContainer = findViewById(R.id.lowRiskContainer);
        emptyView = (TextView) findViewById(R.id.emptyView);

        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });

        networkScanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startNetworkScan();
            }
        });
    }

    private void startScan() {
        resetUI();
        Thread scanThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final Result result = doLocalScan();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        highRiskApps.addAll(result.highRisk);
                        mediumRiskApps.addAll(result.mediumRisk);
                        lowRiskApps.addAll(result.lowRisk);
                        showResults();
                    }
                });
            }
        });
        scanThread.start();
    }

    private void resetUI() {
        highRiskApps.clear();
        mediumRiskApps.clear();
        lowRiskApps.clear();
        highRiskList.removeAllViews();
        mediumRiskList.removeAllViews();
        lowRiskList.removeAllViews();
        highRiskContainer.setVisibility(View.GONE);
        mediumRiskContainer.setVisibility(View.GONE);
        lowRiskContainer.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        statusText.setText("正在本地扫描...");
        scanButton.setEnabled(false);
        networkScanButton.setEnabled(false);
    }

    private void startNetworkScan() {
        resetUI();
        Thread netThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final NetResult result = doNetworkScan();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progressBar.setVisibility(View.GONE);
                        scanButton.setEnabled(true);
                        networkScanButton.setEnabled(true);
                        if (result.error != null) {
                            statusText.setText(result.error);
                            statusText.setTextColor(Color.parseColor("#CC1F1F"));
                            new AlertDialog.Builder(VirusScanActivity.this)
                                    .setTitle("联网检测失败")
                                    .setMessage(result.error)
                                    .setPositiveButton("确定", null)
                                    .show();
                            return;
                        }
                        statusText.setTextColor(Color.parseColor("#888888"));
                        if (result.apps.isEmpty()) {
                            statusText.setText("联网检测完成，未发现黑名单应用");
                            emptyView.setVisibility(View.VISIBLE);
                            emptyView.setText("设备安全，未发现已知病毒应用（基于云端黑名单）");
                        } else {
                            statusText.setText("联网检测完成，发现 " + result.apps.size() + " 个已知病毒应用");
                            highRiskContainer.setVisibility(View.VISIBLE);
                            highRiskList.removeAllViews();
                            for (VirusApp app : result.apps) {
                                highRiskList.addView(createVirusEntry(app, "#CC1F1F", "高风险（云端黑名单）"));
                            }
                        }
                    }
                });
            }
        });
        netThread.start();
    }

    private Result doLocalScan() {
        Set<String> virusPkgs = VirusPackages.getVirusPackages(this);
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        List<VirusApp> highRisk = new ArrayList<VirusApp>();
        List<VirusApp> mediumRisk = new ArrayList<VirusApp>();
        List<VirusApp> lowRisk = new ArrayList<VirusApp>();

        int total = apps.size();
        for (int i = 0; i < total; i++) {
            ApplicationInfo ai = apps.get(i);
            if (ai == null) continue;
            if (ai.packageName.equals(getPackageName())) continue;

            String appName = "";
            try {
                appName = pm.getApplicationLabel(ai).toString();
            } catch (Exception e) {
                appName = ai.packageName;
            }

            String pkg = ai.packageName;

            // 高风险：包名直接匹配病毒列表
            if (virusPkgs.contains(pkg)) {
                highRisk.add(new VirusApp(appName, pkg, 2));
                continue;
            }

            // 高风险：com.my.newproject 前缀 + 应用名含 My/horror 关键词（二次确认）
            if (pkg.startsWith("com.my.newproject") && isHorrorAppName(appName)) {
                highRisk.add(new VirusApp(appName, pkg, 2));
                continue;
            }

            // 中风险：应用名包含敏感关键词
            if (isSensitiveName(appName)) {
                mediumRisk.add(new VirusApp(appName, pkg, 1));
                continue;
            }
        }

        return new Result(highRisk, mediumRisk, lowRisk);
    }

    private NetResult doNetworkScan() {
        try {
            URL url = new URL(BLACKLIST_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.1.2)");

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return new NetResult(null, false, "网络连接失败，请检查网络后重试");
            }

            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            is.close();
            conn.disconnect();

            String json = sb.toString();
            Set<String> remoteBlacklist = parseJsonPackages(json);
            if (remoteBlacklist == null) {
                return new NetResult(null, false, "JSON解析失败");
            }

            Set<String> installedPkgs = new HashSet<String>();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo ai : apps) {
                if (ai != null && ai.packageName != null) {
                    installedPkgs.add(ai.packageName);
                }
            }

            List<VirusApp> networkHighRisk = new ArrayList<VirusApp>();
            for (String pkg : remoteBlacklist) {
                if (installedPkgs.contains(pkg)) {
                    String appName = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        appName = pm.getApplicationLabel(ai).toString();
                    } catch (Exception ignored) {}
                    networkHighRisk.add(new VirusApp(appName, pkg, 2));
                }
            }

            return new NetResult(networkHighRisk, true, null);
        } catch (Exception e) {
            return new NetResult(null, false, "扫描异常: " + e.getMessage());
        }
    }

    private Set<String> parseJsonPackages(String json) {
        Set<String> result = new HashSet<String>();
        if (json == null || json.isEmpty()) return result;
        try {
            int start = json.indexOf("\"packages\"");
            if (start < 0) return null;
            start = json.indexOf("[", start);
            if (start < 0) return result;
            int end = json.indexOf("]", start);
            if (end < 0) return result;
            String arr = json.substring(start + 1, end);
            int idx = 0;
            while (idx < arr.length()) {
                int q1 = arr.indexOf("\"", idx);
                if (q1 < 0) break;
                int q2 = arr.indexOf("\"", q1 + 1);
                if (q2 < 0) break;
                String pkg = arr.substring(q1 + 1, q2);
                if (!pkg.isEmpty() && !pkg.equals("packages")) {
                    result.add(pkg);
                }
                idx = q2 + 1;
            }
        } catch (Exception e) {
            return null;
        }
        return result;
    }

    private void showResults() {
        progressBar.setVisibility(View.GONE);
        scanButton.setEnabled(true);
        networkScanButton.setEnabled(true);

        boolean hasResult = !highRiskApps.isEmpty() || !mediumRiskApps.isEmpty() || !lowRiskApps.isEmpty();
        
        if (!hasResult) {
            statusText.setText("扫描完成，设备安全");
            emptyView.setVisibility(View.VISIBLE);
            return;
        }

        statusText.setText("扫描完成，共发现 " + (highRiskApps.size() + mediumRiskApps.size() + lowRiskApps.size()) + " 个疑似应用");

        if (!highRiskApps.isEmpty()) {
            highRiskContainer.setVisibility(View.VISIBLE);
            for (VirusApp app : highRiskApps) {
                highRiskList.addView(createVirusEntry(app, "#CC1F1F", "高风险"));
            }
        }
        if (!mediumRiskApps.isEmpty()) {
            mediumRiskContainer.setVisibility(View.VISIBLE);
            for (VirusApp app : mediumRiskApps) {
                mediumRiskList.addView(createVirusEntry(app, "#FF9800", "中风险"));
            }
        }
        if (!lowRiskApps.isEmpty()) {
            lowRiskContainer.setVisibility(View.VISIBLE);
            for (VirusApp app : lowRiskApps) {
                lowRiskList.addView(createVirusEntry(app, "#FFC107", "低风险"));
            }
        }
    }

    private View createVirusEntry(final VirusApp app, final String color, final String riskLabel) {
        View view = LayoutInflater.from(this).inflate(R.layout.item_virus_entry, null);
        
        ImageView icon = (ImageView) view.findViewById(R.id.virusIcon);
        TextView name = (TextView) view.findViewById(R.id.virusName);
        TextView pkg = (TextView) view.findViewById(R.id.virusPackage);
        TextView risk = (TextView) view.findViewById(R.id.virusRisk);
        Button uninstallBtn = (Button) view.findViewById(R.id.virusUninstallBtn);

        try {
            ApplicationInfo ai = pm.getApplicationInfo(app.packageName, 0);
            icon.setImageDrawable(pm.getApplicationIcon(ai));
        } catch (Exception e) {
            icon.setImageResource(android.R.drawable.ic_dialog_info);
        }

        name.setText(app.appName);
        pkg.setText(app.packageName);
        risk.setText(riskLabel);
        risk.setTextColor(Color.parseColor(color));

        uninstallBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmUninstall(app);
            }
        });

        return view;
    }

    private void confirmUninstall(final VirusApp app) {
        new AlertDialog.Builder(this)
                .setTitle("确认卸载")
                .setMessage("确定要卸载 " + app.appName + " 吗？")
                .setPositiveButton("卸载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        uninstallApp(app.packageName);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void uninstallApp(String packageName) {
        try {
            if (rikka.shizuku.Shizuku.pingBinder()
                    && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                rikka.shizuku.Shizuku.newProcess(
                        new String[]{"pm", "uninstall", "--user", "0", packageName},
                        null, null);
                Toast.makeText(this, "已卸载: " + packageName, Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception e) {}

        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(android.net.Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "卸载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isSensitiveName(String name) {
        if (name == null) return false;
        String[] sensitive = {"锁机", "病毒", "木马", "恶意", "锁屏", "勒索"};
        for (String s : sensitive) {
            if (name.contains(s)) return true;
        }
        return false;
    }

    /**
     * 检测是否为恐怖/恶搞类应用名
     * 用于 com.my.newproject 系列包名的二次确认，避免误报正常应用
     */
    private boolean isHorrorAppName(String name) {
        if (name == null || name.isEmpty()) return false;
        String lowerName = name.toLowerCase();
        return lowerName.contains("my") || lowerName.contains("horror");
    }

    private static class Result {
        List<VirusApp> highRisk;
        List<VirusApp> mediumRisk;
        List<VirusApp> lowRisk;

        Result(List<VirusApp> high, List<VirusApp> medium, List<VirusApp> low) {
            this.highRisk = high;
            this.mediumRisk = medium;
            this.lowRisk = low;
        }
    }

    private static class NetResult {
        List<VirusApp> apps;
        boolean success;
        String error;

        NetResult(List<VirusApp> apps, boolean success, String error) {
            this.apps = apps != null ? apps : new ArrayList<VirusApp>();
            this.success = success;
            this.error = error;
        }
    }

    private static class VirusApp {
        String appName;
        String packageName;
        int riskLevel;

        VirusApp(String appName, String packageName, int riskLevel) {
            this.appName = appName;
            this.packageName = packageName;
            this.riskLevel = riskLevel;
        }
    }
}