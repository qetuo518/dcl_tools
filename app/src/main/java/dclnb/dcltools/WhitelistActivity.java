package dclnb.dcltools;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WhitelistActivity extends AppCompatActivity {

    private static final String TAG = "WhitelistActivity";

    private ListView listApps;
    private AppAdapter appAdapter;
    private List<AppInfo> allApps;
    private Set<String> whitelistedPackages;
    private LinearLayout loadingContainer;
    private LinearLayout emptyContainer;
    private TextView tvCount;
    private EditText etSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist);
        Log.i(TAG, "onCreate 开始");

        // 初始化视图
        listApps = findViewById(R.id.listApps);
        loadingContainer = findViewById(R.id.loadingContainer);
        emptyContainer = findViewById(R.id.emptyContainer);
        tvCount = findViewById(R.id.tvCount);
        etSearch = findViewById(R.id.etSearch);

        // 初始化白名单
        whitelistedPackages = new HashSet<>(Whitelist.getAllWhitelisted(this));
        Log.i(TAG, "当前白名单数量: " + whitelistedPackages.size());

        // 初始化列表
        allApps = new ArrayList<>();
        appAdapter = new AppAdapter(this);
        listApps.setAdapter(appAdapter);

        // 添加自定义包名按钮
        Button btnAdd = findViewById(R.id.btnAdd);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAddDialog();
                }
            });
        }

        // 搜索框
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    filterApps(s.toString());
                }
            });
        }

        // 异步加载应用列表（避免阻塞主线程）
        loadAppsAsync();
        Log.i(TAG, "onCreate 完成");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
        // 刷新白名单状态显示
        whitelistedPackages = new HashSet<>(Whitelist.getAllWhitelisted(this));
        updateDisplay();
    }

    /**
     * 异步加载应用列表（后台线程加载，主线程刷新 UI）
     */
    private void loadAppsAsync() {
        loadingContainer.setVisibility(View.VISIBLE);
        listApps.setVisibility(View.GONE);
        emptyContainer.setVisibility(View.GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<AppInfo> apps = loadAllApps(WhitelistActivity.this);
                    Log.i(TAG, "加载了 " + apps.size() + " 个应用");

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            allApps = apps;
                            loadingContainer.setVisibility(View.GONE);

                            if (allApps.isEmpty()) {
                                emptyContainer.setVisibility(View.VISIBLE);
                            } else {
                                emptyContainer.setVisibility(View.GONE);
                                listApps.setVisibility(View.VISIBLE);
                                updateDisplay();
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "加载失败", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loadingContainer.setVisibility(View.GONE);
                            emptyContainer.setVisibility(View.VISIBLE);
                            Toast.makeText(WhitelistActivity.this, "加载应用列表失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 加载所有已安装应用
     */
    private List<AppInfo> loadAllApps(Context context) {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        Log.i(TAG, "已安装应用数量: " + installedApps.size());

        for (ApplicationInfo ai : installedApps) {
            if (ai == null) continue;

            AppInfo app = new AppInfo();
            app.packageName = ai.packageName;
            try {
                app.appName = ai.loadLabel(pm).toString();
            } catch (Exception e) {
                app.appName = ai.packageName;
            }
            try {
                app.icon = pm.getApplicationIcon(ai);
            } catch (Exception e) {
                app.icon = null;
            }
            app.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            apps.add(app);
        }

        Log.i(TAG, "成功处理 " + apps.size() + " 个应用");
        return apps;
    }

    /**
     * 更新显示：白名单应用排前，未启用排后
     */
    private void updateDisplay() {
        List<AppInfo> sorted = new ArrayList<>(allApps);

        // 排序：白名单在前，非白名单在后；同组内按应用名排序
        Collections.sort(sorted, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                boolean aW = whitelistedPackages.contains(a.packageName);
                boolean bW = whitelistedPackages.contains(b.packageName);
                if (aW != bW) {
                    return aW ? -1 : 1;
                }
                String an = a.appName != null ? a.appName : a.packageName;
                String bn = b.appName != null ? b.appName : b.packageName;
                return an.compareToIgnoreCase(bn);
            }
        });

        appAdapter.setApps(sorted);
        appAdapter.notifyDataSetChanged();

        // 更新计数
        int whitelistCount = 0;
        for (AppInfo app : sorted) {
            if (whitelistedPackages.contains(app.packageName)) whitelistCount++;
        }
        tvCount.setText("共 " + sorted.size() + " 个，白名单 " + whitelistCount);
    }

    /**
     * 筛选应用
     */
    private void filterApps(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            updateDisplay();
            return;
        }

        keyword = keyword.toLowerCase();
        List<AppInfo> filtered = new ArrayList<>();
        for (AppInfo app : allApps) {
            boolean match = app.appName != null && app.appName.toLowerCase().contains(keyword)
                    || app.packageName.toLowerCase().contains(keyword);
            if (match) {
                filtered.add(app);
            }
        }

        // 同样排序：白名单在前
        Collections.sort(filtered, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                boolean aW = whitelistedPackages.contains(a.packageName);
                boolean bW = whitelistedPackages.contains(b.packageName);
                if (aW != bW) {
                    return aW ? -1 : 1;
                }
                String an = a.appName != null ? a.appName : a.packageName;
                String bn = b.appName != null ? b.appName : b.packageName;
                return an.compareToIgnoreCase(bn);
            }
        });

        appAdapter.setApps(filtered);
        appAdapter.notifyDataSetChanged();
    }

    /**
     * 显示添加对话框
     */
    private void showAddDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入包名，如 com.example.app");

        new AlertDialog.Builder(this)
            .setTitle("添加应用到白名单")
            .setMessage("请输入完整包名")
            .setView(input)
            .setPositiveButton("添加", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    String pkg = input.getText().toString().trim();
                    if (pkg.isEmpty()) {
                        Toast.makeText(WhitelistActivity.this, "包名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!pkg.contains(".")) {
                        Toast.makeText(WhitelistActivity.this, "包名格式不正确", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Whitelist.addWhitelisted(WhitelistActivity.this, pkg);
                    whitelistedPackages.add(pkg);
                    updateDisplay();

                    Toast.makeText(WhitelistActivity.this, "已添加: " + pkg, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    /**
     * 应用信息类
     */
    private static class AppInfo {
        String packageName;
        String appName;
        boolean isSystem;
        Drawable icon;
    }

    /**
     * 列表适配器（单列表 + 开关）
     */
    private class AppAdapter extends BaseAdapter {
        private Context context;
        private List<AppInfo> apps;

        public AppAdapter(Context ctx) {
            this.context = ctx;
            this.apps = new ArrayList<>();
        }

        public void setApps(List<AppInfo> list) {
            this.apps = list;
        }

        @Override
        public int getCount() {
            return apps == null ? 0 : apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps == null ? null : apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View view = convertView;
            ViewHolder holder;

            if (view == null) {
                view = View.inflate(context, R.layout.item_whitelist, null);
                holder = new ViewHolder();
                holder.appIcon = view.findViewById(R.id.appIcon);
                holder.appName = view.findViewById(R.id.appName);
                holder.appPackage = view.findViewById(R.id.appPackage);
                holder.appSwitch = view.findViewById(R.id.appSwitch);
                view.setTag(holder);
            } else {
                holder = (ViewHolder) view.getTag();
            }

            if (apps == null || position >= apps.size()) {
                return view;
            }

            final AppInfo app = apps.get(position);

            holder.appName.setText(app.appName != null ? app.appName : app.packageName);
            holder.appPackage.setText(app.packageName);
            if (app.icon != null) {
                holder.appIcon.setImageDrawable(app.icon);
            } else {
                holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            // 防止复用视图时误触发监听：先解绑，再设置状态，最后绑定新监听
            holder.appSwitch.setOnCheckedChangeListener(null);

            final boolean isWhitelisted = whitelistedPackages.contains(app.packageName);
            holder.appSwitch.setChecked(isWhitelisted);

            holder.appSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    boolean current = whitelistedPackages.contains(app.packageName);
                    if (isChecked == current) return; // 状态未变化，忽略

                    if (isChecked) {
                        Whitelist.addWhitelisted(WhitelistActivity.this, app.packageName);
                        whitelistedPackages.add(app.packageName);
                        Toast.makeText(WhitelistActivity.this, "已添加到白名单: " + app.appName, Toast.LENGTH_SHORT).show();
                    } else {
                        Whitelist.removeWhitelisted(WhitelistActivity.this, app.packageName);
                        whitelistedPackages.remove(app.packageName);
                        Toast.makeText(WhitelistActivity.this, "已从白名单移除: " + app.appName, Toast.LENGTH_SHORT).show();
                    }
                    updateDisplay();
                }
            });

            return view;
        }

        private class ViewHolder {
            ImageView appIcon;
            TextView appName;
            TextView appPackage;
            Switch appSwitch;
        }
    }
}