package dclnb.dcltools;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * PersonaActivity - 角色扮演与插件设置页面
 * AIDE 环境专用：无 Lambda、无 Kotlin、纯 Java 匿名内部类。
 */
public class PersonaActivity extends AppCompatActivity {

    private static final String TAG = "PersonaActivity";
    private static final String PREFS_NAME = "agent_config";
    private static final String KEY_FATFISH_ENABLED = "fatfish_enabled";
    private static final String KEY_JIEMI_ENABLED = "jiemi_enabled";
    private static final String KEY_NEKO_ENABLED = "neko_enabled";

    // 插件相关
    private static final int REQ_INSTALL_PLUGIN = 1001;
    private static final int REQ_DELETE_PLUGIN = 1002;
    private static final String PLUGINS_DIR = "plugins";
    private static final String FILE_MAIN_TXT = "main.txt";
    private static final String FILE_B_TXT = "b.txt";

    // SharedPreferences 保存插件列表
    private static final String PREFS_PLUGINS = "installed_plugins";
    private static final String KEY_PLUGINS = "plugin_list";
    // SharedPreferences 保存插件启用状态
    private static final String PREFS_PLUGIN_ENABLED = "plugin_enabled";
    private static final String KEY_PLUGIN_ENABLED_PREFIX = "enabled_";
    private static final String FILE_SEND_TXT = "send.txt";

    // 插件工具条目格式
    private static class PluginTool {
        String name;
        String script;
        PluginTool(String n, String s) { name = n; script = s; }
    }

    // 插件列表项
    private static class PluginItem {
        String folderName;
        String name;
        String version;
        List<PluginTool> tools;
        boolean enabled = true; // 插件是否启用

        PluginItem(String folder, String n, String v, List<PluginTool> t) {
            folderName = folder;
            name = n;
            version = v;
            tools = t;
        }
    }

    private SharedPreferences prefs;
    private SwitchCompat switchFatfish;
    private SwitchCompat switchJiemi;
    private SwitchCompat switchNeko;
    private ListView pluginListView;
    private ArrayAdapter<String> pluginAdapter;
    private ArrayList<String> pluginListStrings;
    private ArrayList<PluginItem> pluginItems;
    
    // 自定义插件适配器
    private PluginAdapter pluginCustomAdapter;
    private ArrayList<PluginItem> pluginItemsForAdapter = new ArrayList<PluginItem>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_persona);

        // 初始化 SharedPreferences
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 加载保存的设置
        boolean fatfishEnabled = prefs.getBoolean(KEY_FATFISH_ENABLED, false);
        boolean jiemiEnabled = prefs.getBoolean(KEY_JIEMI_ENABLED, false);
        boolean nekoEnabled = prefs.getBoolean(KEY_NEKO_ENABLED, false);

        // 初始化控件
        ImageView btnBack = (ImageView) findViewById(R.id.btn_back);
        switchFatfish = (SwitchCompat) findViewById(R.id.switch_fatfish);
        switchJiemi = (SwitchCompat) findViewById(R.id.switch_jiemi);
        switchNeko = (SwitchCompat) findViewById(R.id.switch_neko);
        pluginListView = (ListView) findViewById(R.id.plugin_list_view);

        // 设置初始状态
        switchFatfish.setChecked(fatfishEnabled);
        switchJiemi.setChecked(jiemiEnabled);
        switchNeko.setChecked(nekoEnabled);

        // 初始化插件列表
        pluginListStrings = new ArrayList<String>();
        pluginItems = new ArrayList<PluginItem>();
        pluginCustomAdapter = new PluginAdapter();
        pluginListView.setAdapter(pluginCustomAdapter);

        // 返回按钮
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 大肥鱼开关监听
        switchFatfish.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean(KEY_FATFISH_ENABLED, isChecked);
                editor.apply();
            }
        });

        // 简易破甲开关监听
        switchJiemi.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean(KEY_JIEMI_ENABLED, isChecked);
                editor.apply();
            }
        });

        // 猫娘开关监听
        switchNeko.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean(KEY_NEKO_ENABLED, isChecked);
                editor.apply();
            }
        });

        // 加载已安装插件列表
        loadInstalledPlugins();

        // 安装自定义插件按钮
        View btnInstallPlugin = findViewById(R.id.btn_install_plugin);
        if (btnInstallPlugin != null) {
            btnInstallPlugin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPluginPicker();
                }
            });
        }

        // 插件列表点击事件
        pluginListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PluginItem selectedPlugin = pluginItemsForAdapter.get(position);
                showPluginDetails(selectedPlugin);
            }
        });

        // 长按删除插件
        pluginListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                PluginItem selectedPlugin = pluginItemsForAdapter.get(position);
                showDeleteConfirmDialog(selectedPlugin);
                return true;
            }
        });
    }

    /**
     * 加载已安装插件列表
     */
    private void loadInstalledPlugins() {
        pluginItems.clear();
        pluginItemsForAdapter.clear();

        SharedPreferences installedPlugins = getSharedPreferences(PREFS_PLUGINS, Context.MODE_PRIVATE);
        SharedPreferences enabledPrefs = getSharedPreferences(PREFS_PLUGIN_ENABLED, Context.MODE_PRIVATE);
        String pluginListStr = installedPlugins.getString(KEY_PLUGINS, "");

        if (!pluginListStr.isEmpty()) {
            String[] items = pluginListStr.split("\\|");
            for (String item : items) {
                if (!item.startsWith("plugin:")) continue;

                final PluginItem plugin = parsePluginItem(item);
                if (plugin != null) {
                    // 加载启用状态
                    boolean isEnabled = enabledPrefs.getBoolean(KEY_PLUGIN_ENABLED_PREFIX + plugin.folderName, true);
                    plugin.enabled = isEnabled;
                    pluginItems.add(plugin);
                    pluginItemsForAdapter.add(plugin);
                }
            }
        }

        pluginCustomAdapter.notifyDataSetChanged();
    }

    /**
     * 解析插件列表项
     */
    private PluginItem parsePluginItem(String itemStr) {
        try {
            String[] parts = itemStr.split(":");
            if (parts.length < 2) return null;

            String folderName = parts[1];
            String name = "";
            String version = "";
            List<PluginTool> tools = new ArrayList<PluginTool>();

            for (int i = 2; i < parts.length; i++) {
                if (parts[i].startsWith("name=")) {
                    name = parts[i].substring(5);
                } else if (parts[i].startsWith("version=")) {
                    version = parts[i].substring(8);
                } else if (parts[i].startsWith("tools=")) {
                    String toolsStr = parts[i].substring(6);
                    String[] toolNames = toolsStr.split(",");
                    for (String toolName : toolNames) {
                        PluginTool tool = new PluginTool(toolName, "");
                        tools.add(tool);
                    }
                }
            }

            return new PluginItem(folderName, name, version, tools);
        } catch (Exception e) {
            android.util.Log.e(TAG, "解析插件项失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 显示插件详情
     */
    private void showPluginDetails(final PluginItem plugin) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(plugin.name.isEmpty() ? plugin.folderName : plugin.name);

        StringBuilder message = new StringBuilder();
        if (!plugin.version.isEmpty()) {
            message.append("版本：").append(plugin.version).append("\n\n");
        }
        message.append("工具列表：\n");
        for (PluginTool tool : plugin.tools) {
            message.append("• ").append(tool.name).append("\n");
        }

        builder.setMessage(message.toString());

        builder.setPositiveButton("确定", null);

        builder.show();
    }

    /**
     * 显示删除确认对话框（精美版）
     */
    private void showDeleteConfirmDialog(final PluginItem plugin) {
        final String displayName = plugin.name.isEmpty() ? plugin.folderName : plugin.name;
        
        // 创建自定义对话框布局
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("卸载插件");
        
        // 构建精美的提示信息
        StringBuilder msg = new StringBuilder();
        msg.append("您确定要卸载【").append(displayName).append("】吗？\n\n");
        if (plugin.tools.size() > 0) {
            msg.append("此插件包含 ").append(plugin.tools.size()).append(" 个工具：\n");
            for (int i = 0; i < Math.min(plugin.tools.size(), 3); i++) {
                msg.append("• ").append(plugin.tools.get(i).name).append("\n");
            }
            if (plugin.tools.size() > 3) {
                msg.append("  ... 还有 ").append(plugin.tools.size() - 3).append(" 个工具\n");
            }
        }
        msg.append("\n卸载后将永久删除相关文件。");
        
        builder.setMessage(msg.toString());
        
        // 图标（使用系统警告图标）
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        
        // 确认按钮 - 红色
        builder.setPositiveButton("卸载", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                deletePlugin(plugin);
            }
        });
        
        // 取消按钮 - 灰色
        builder.setNegativeButton("取消", null);
        
        builder.show();
    }

    /**
     * 删除插件（带进度提示）
     */
    private void deletePlugin(final PluginItem plugin) {
        final String displayName = plugin.name.isEmpty() ? plugin.folderName : plugin.name;
        
        // 显示加载提示
        final android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setTitle("正在卸载");
        progressDialog.setMessage("正在删除插件 " + displayName + "...");
        progressDialog.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
        progressDialog.setCancelable(false);
        progressDialog.show();
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    deletePluginInternal(plugin);
                    
                    final String finalDisplayName = displayName;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) {
                                progressDialog.dismiss();
                            }
                            // 显示卸载成功动画
                            showToastWithAnimation("✓ 插件已卸载：" + finalDisplayName);
                            loadInstalledPlugins();
                        }
                    });
                } catch (Exception e) {
                    final String errMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog.isShowing()) {
                                progressDialog.dismiss();
                            }
                            showToastWithAnimation("卸载失败：" + errMsg);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 显示带动画效果的Toast
     */
    private void showToastWithAnimation(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 创建自定义Toast
                android.widget.Toast toast = android.widget.Toast.makeText(PersonaActivity.this, message, android.widget.Toast.LENGTH_SHORT);
                
                // 获取Toast View并设置样式
                android.view.View toastView = toast.getView();
                if (toastView instanceof android.widget.LinearLayout) {
                    android.widget.LinearLayout linearLayout = (android.widget.LinearLayout) toastView;
                    android.widget.TextView textView = linearLayout.getChildAt(0) instanceof android.widget.TextView 
                        ? (android.widget.TextView) linearLayout.getChildAt(0) 
                        : null;
                    
                    if (textView != null) {
                        textView.setTextColor(android.graphics.Color.parseColor("#FFFFFF"));
                        textView.setTextSize(16);
                        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
                        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                        drawable.setCornerRadius(24f);
                        drawable.setColor(android.graphics.Color.parseColor("#FF5722"));
                        drawable.setStroke(2, android.graphics.Color.parseColor("#E64A19"));
                        toastView.setBackground(drawable);
                        toastView.setPadding(32, 16, 32, 16);
                        
                        // 添加淡入淡出动画
                        android.view.animation.AlphaAnimation fadeIn = new android.view.animation.AlphaAnimation(0.0f, 1.0f);
                        fadeIn.setDuration(200);
                        android.view.animation.AlphaAnimation fadeOut = new android.view.animation.AlphaAnimation(1.0f, 0.0f);
                        fadeOut.setDuration(200);
                        fadeOut.setStartOffset(1500);
                        
                        android.view.animation.AnimationSet animationSet = new android.view.animation.AnimationSet(false);
                        animationSet.addAnimation(fadeIn);
                        animationSet.addAnimation(fadeOut);
                        toastView.startAnimation(animationSet);
                    }
                }
                
                toast.show();
            }
        });
    }

    /**
     * 删除插件内部逻辑
     */
    private void deletePluginInternal(final PluginItem plugin) throws Exception {
        // 从SharedPreferences中删除
        SharedPreferences installedPlugins = getSharedPreferences(PREFS_PLUGINS, Context.MODE_PRIVATE);
        String pluginListStr = installedPlugins.getString(KEY_PLUGINS, "");
        StringBuilder newPluginList = new StringBuilder();

        String[] items = pluginListStr.split("\\|");
        boolean found = false;
        for (String item : items) {
            if (!item.startsWith("plugin:")) continue;
            // 修复：插件保存时带有 :name= 等后缀，需用 startsWith 或精确匹配
            if (item.equals("plugin:" + plugin.folderName) || item.startsWith("plugin:" + plugin.folderName + ":")) {
                found = true;
                continue;
            }
            if (newPluginList.length() > 0) {
                newPluginList.append("|");
            }
            newPluginList.append(item);
        }

        if (found) {
            SharedPreferences.Editor editor = installedPlugins.edit();
            editor.putString(KEY_PLUGINS, newPluginList.toString());
            editor.apply();
            
            // 删除启用状态
            SharedPreferences enabledPrefs = getSharedPreferences(PREFS_PLUGIN_ENABLED, Context.MODE_PRIVATE);
            SharedPreferences.Editor enabledEditor = enabledPrefs.edit();
            enabledEditor.remove(KEY_PLUGIN_ENABLED_PREFIX + plugin.folderName);
            enabledEditor.apply();

            // 删除物理文件
            final String pluginDirPath = getFilesDir().getAbsolutePath() + File.separator + PLUGINS_DIR;
            final File pluginDir = new File(pluginDirPath);
            final String folderToDelete = plugin.folderName;
            final File pluginFolder = new File(pluginDir, folderToDelete);
            deleteRecursively(pluginFolder);

            // 提取显示名称为 final 变量，供匿名内部类安全使用
            final String displayName = plugin.name.isEmpty() ? plugin.folderName : plugin.name;

            // 刷新列表
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    loadInstalledPlugins();
                    Toast.makeText(PersonaActivity.this,
                        "插件已删除：" + displayName,
                        Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            throw new Exception("插件未找到");
        }
    }
    
    /**
     * 打开文件选择器，仅允许选择 zip 文件
     */
    private void openPluginPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/zip");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "选择插件包"), REQ_INSTALL_PLUGIN);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_INSTALL_PLUGIN && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                installPlugin(uri);
            }
        }
    }

    /**
     * 安装插件：解压并验证
     */
    private void installPlugin(final Uri uri) {
        Toast.makeText(this, "正在安装插件...", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    installPluginInternal(uri);
                } catch (Exception e) {
                    final String errMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(PersonaActivity.this,
                                "安装失败：" + errMsg,
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 安装插件内部逻辑
     */
    private void installPluginInternal(final Uri uri) throws Exception {
        // 获取临时文件路径
        final String tempPath = getFilesDir().getAbsolutePath() + File.separator + "plugin_temp.zip";
        final File tempFile = new File(tempPath);

        // 复制 URI 到临时文件
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(PersonaActivity.this, "无法读取选中的文件", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        FileOutputStream fos = new FileOutputStream(tempFile);
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) > 0) {
            fos.write(buffer, 0, len);
        }
        fos.close();
        is.close();

        // 解压到插件目录
        final String pluginDirPath = getFilesDir().getAbsolutePath() + File.separator + PLUGINS_DIR;
        final File pluginDir = new File(pluginDirPath);
        if (!pluginDir.exists()) {
            pluginDir.mkdirs();
        }

        // 生成插件文件夹名（使用时间戳避免冲突）
        final String pluginFolderName = "plugin_" + System.currentTimeMillis();
        final File extractDir = new File(pluginDirPath + File.separator + pluginFolderName);
        extractDir.mkdirs();

        // 解压 zip
        ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tempFile));
        ZipEntry entry;
        boolean[] hasMain = new boolean[1];
        boolean[] hasB = new boolean[1];

        while ((entry = zis.getNextEntry()) != null) {
            String entryName = entry.getName();
            File outFile = new File(extractDir, entryName);
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                // 确保父目录存在
                File parentDir = outFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                FileOutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[4096];
                int readLen;
                while ((readLen = zis.read(buf)) > 0) {
                    out.write(buf, 0, readLen);
                }
                out.close();

                // 检查必要文件
                if (FILE_MAIN_TXT.equals(entryName)) {
                    hasMain[0] = true;
                }
                if (FILE_B_TXT.equals(entryName)) {
                    hasB[0] = true;
                }
            }
            zis.closeEntry();
        }
        zis.close();
        tempFile.delete();

        // 验证必要文件
        if (!hasMain[0] || !hasB[0]) {
            final String msg = "无效的插件包：缺少必要文件（需要 main.txt 和 b.txt）";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(PersonaActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
            // 清理未完成的插件目录
            deleteRecursively(extractDir);
            return;
        }

        // 解析插件信息
        final PluginInstallResult result = parsePluginInfo(extractDir);

// 保存到 SharedPreferences
         savePluginInfo(result.name, result.version, pluginFolderName, result.tools);
         
// 保存 send.txt 内容
          if (!result.sendTxtContent.isEmpty()) {
              android.util.Log.d(TAG, "保存 send.txt 内容到 SharedPreferences: folder=" + pluginFolderName);
              SharedPreferences sendTxtPrefs = getSharedPreferences("plugin_send_txt", Context.MODE_PRIVATE);
              SharedPreferences.Editor editor = sendTxtPrefs.edit();
              editor.putString(pluginFolderName, result.sendTxtContent);
              editor.apply();
              android.util.Log.d(TAG, "send.txt 内容已保存: '" + result.sendTxtContent + "'");
          } else {
              android.util.Log.d(TAG, "send.txt 内容为空，不保存");
          }

        // 显示安装成功
        final String displayName = !result.name.isEmpty() ? result.name : pluginFolderName;
        final String displayVersion = !result.version.isEmpty() ? " (v" + result.version + ")" : "";
        final String toolCount = result.tools.size() > 0 ? "，包含 " + result.tools.size() + " 个工具" : "";

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PersonaActivity.this,
                    "插件已安装：" + displayName + displayVersion + toolCount,
                    Toast.LENGTH_LONG).show();
                // 刷新列表
                loadInstalledPlugins();
            }
        });
    }

    /**
     * 插件解析结果
     */
    private static class PluginInstallResult {
        String name = "";
        String version = "";
        List<PluginTool> tools = new ArrayList<PluginTool>();
        String sendTxtContent = ""; // send.txt 文件内容
    }

    // ==================== 插件适配器 ====================
    
    /**
     * 自定义插件列表适配器
     */
    private class PluginAdapter extends android.widget.BaseAdapter {
        @Override
        public int getCount() {
            return pluginItemsForAdapter.size();
        }
        
        @Override
        public Object getItem(int position) {
            return pluginItemsForAdapter.get(position);
        }
        
        @Override
        public long getItemId(int position) {
            return position;
        }
        
        @Override
        public View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_plugin, parent, false);
            }
            
            final PluginItem plugin = pluginItemsForAdapter.get(position);
            
            final android.widget.TextView nameTv = convertView.findViewById(R.id.plugin_name);
            final android.widget.TextView toolsTv = convertView.findViewById(R.id.plugin_tools);
            final androidx.appcompat.widget.SwitchCompat switchCompat = convertView.findViewById(R.id.plugin_switch);
            
            final String displayName = plugin.name.isEmpty() ? plugin.folderName : plugin.name;
            nameTv.setText(displayName + (plugin.version.isEmpty() ? "" : " (v" + plugin.version + ")"));
            
            // 显示工具数量
            final String toolInfo = plugin.tools.size() > 0 ? plugin.tools.size() + " 个工具" : "无工具";
            toolsTv.setText(plugin.enabled ? toolInfo : "已禁用");
            
            switchCompat.setChecked(plugin.enabled);
            
            // 开关监听 - 使用直接监听器避免冲突
            switchCompat.setOnCheckedChangeListener(null);
            final PluginItem tempPlugin = plugin;
            final String tempToolInfo = plugin.tools.size() > 0 ? plugin.tools.size() + " 个工具" : "无工具";
            switchCompat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tempPlugin.enabled = !tempPlugin.enabled;
                    switchCompat.setChecked(tempPlugin.enabled);
                    savePluginEnabled(tempPlugin.folderName, tempPlugin.enabled);
                    toolsTv.setText(tempPlugin.enabled ? tempToolInfo : "已禁用");
                }
            });

            // 删除按钮监听
            final PluginItem deletePluginItem = plugin;
            ImageButton deleteBtn = convertView.findViewById(R.id.btn_delete_plugin);
            if (deleteBtn != null) {
                deleteBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showDeleteConfirmDialog(deletePluginItem);
                    }
                });
            }
            
            return convertView;
        }
    }
    
    /**
     * 保存插件启用状态
     */
    private void savePluginEnabled(String folderName, boolean enabled) {
        SharedPreferences enabledPrefs = getSharedPreferences(PREFS_PLUGIN_ENABLED, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = enabledPrefs.edit();
        editor.putBoolean(KEY_PLUGIN_ENABLED_PREFIX + folderName, enabled);
        editor.apply();
        android.util.Log.d(TAG, "插件 " + folderName + " 启用状态: " + enabled);
    }

    /**
     * 解析插件信息（提取工具名和脚本路径）
     */
    private PluginInstallResult parsePluginInfo(File extractDir) {
        PluginInstallResult result = new PluginInstallResult();

        File bFile = new File(extractDir, FILE_B_TXT);
        File mainFile = new File(extractDir, FILE_MAIN_TXT);
        File scriptsDir = new File(extractDir, "scripts");

        if (bFile.exists()) {
            String content = readFileContent(bFile);
            for (String line : content.split("\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("name:")) {
                    result.name = trimmed.substring(5).trim();
                } else if (trimmed.startsWith("version:")) {
                    result.version = trimmed.substring(8).trim();
                }
            }
        }

        if (mainFile.exists()) {
            String mainContent = readFileContent(mainFile);
            for (String line : mainContent.split("\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
                
                // 解析工具名（从 ['tool_name'] 格式中提取）
                int firstQuote = trimmed.indexOf('\'');
                int secondQuote = trimmed.indexOf('\'', firstQuote + 1);
                if (firstQuote >= 0 && secondQuote > firstQuote) {
                    String toolName = trimmed.substring(firstQuote + 1, secondQuote).trim();
                    if (!toolName.isEmpty()) {
                        // 查找对应的脚本文件
                        String scriptPath = "";
                        if (scriptsDir.exists()) {
                            File scriptFile = new File(scriptsDir, toolName + ".sh");
                            if (scriptFile.exists()) {
                                scriptPath = scriptFile.getAbsolutePath();
                            }
                        }
                        result.tools.add(new PluginTool(toolName, scriptPath));
                    }
                }
            }
        }
        
        // 检查是否有 send.txt 文件
        File sendTxtFile = new File(extractDir, "send.txt");
        if (sendTxtFile.exists()) {
            String sendContent = readFileContent(sendTxtFile);
            result.sendTxtContent = sendContent.trim();
        }

        return result;
    }

    /**
     * 读取文件内容
     */
    private String readFileContent(File file) {
        StringBuilder sb = new StringBuilder();
        try {
            InputStream is = new java.io.FileInputStream(file);
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            is.close();
        } catch (Exception e) {
            android.util.Log.e(TAG, "读取文件失败: " + file.getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
        return sb.toString();
    }

    /**
     * 递归删除目录
     */
    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    /**
     * 从 main.txt 行中解析工具条目
     * 支持格式: ['工具名称'] - 对应的shell脚本
     * 返回 PluginTool 对象，包含工具名和脚本描述
     */
    private PluginTool parseToolEntry(String line) {
        // 查找第一个单引号
        int firstQuote = line.indexOf('\'');
        int secondQuote = line.indexOf('\'', firstQuote + 1);
        if (firstQuote < 0 || secondQuote <= firstQuote) return null;

        String toolName = line.substring(firstQuote + 1, secondQuote).trim();
        if (toolName.isEmpty()) return null;

        // 查找分隔符 ' - ' 或 '-'
        String rest = line.substring(secondQuote + 1).trim();
        String scriptDesc = "";
        int dashIdx = rest.indexOf("-");
        if (dashIdx >= 0) {
            scriptDesc = rest.substring(dashIdx + 1).trim();
        }

        return new PluginTool(toolName, scriptDesc);
    }

    /**
     * 从 main.txt 行中解析工具名称（旧方法，保留兼容）
     */
    private String parseToolName(String line) {
        int firstQuote = line.indexOf('\'');
        int secondQuote = line.indexOf('\'', firstQuote + 1);
        if (firstQuote >= 0 && secondQuote > firstQuote) {
            return line.substring(firstQuote + 1, secondQuote).trim();
        }
        return null;
    }

    /**
     * 保存插件信息到 SharedPreferences
     */
    private void savePluginInfo(String name, String version, String folderName, List<PluginTool> tools) {
        try {
            // 获取已安装插件列表
            SharedPreferences installedPlugins = getSharedPreferences(PREFS_PLUGINS, Context.MODE_PRIVATE);
            String existing = installedPlugins.getString(KEY_PLUGINS, "");

            // 构建新插件条目
            StringBuilder sb = new StringBuilder();
            if (!existing.isEmpty()) {
                sb.append(existing).append("|");
            }
            sb.append("plugin:").append(folderName);
            if (!name.isEmpty()) {
                sb.append(":name=").append(name);
            }
            if (!version.isEmpty()) {
                sb.append(":version=").append(version);
            }
if (tools.size() > 0) {
                 sb.append(":tools=");
                 for (int i = 0; i < tools.size(); i++) {
                     if (i > 0) sb.append(",");
                     PluginTool t = tools.get(i);
                     sb.append(t.name);
                     if (!t.script.isEmpty()) {
                         sb.append(":").append(t.script);
                     }
                 }
             }

            // 保存
            SharedPreferences.Editor editor = installedPlugins.edit();
            editor.putString(KEY_PLUGINS, sb.toString());
            editor.apply();

            android.util.Log.d(TAG, "已保存插件：" + folderName + "，工具数：" + tools.size());
        } catch (Exception e) {
            android.util.Log.e(TAG, "保存插件信息失败：" + e.getMessage());
        }
    }
}
