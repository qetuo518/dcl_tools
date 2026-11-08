package dclnb.dcltools;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 白名单工具：管理不会被强制停止的应用包名集合。
 * 内置默认白名单 + 用户自定义；用户可在 UI 中关闭默认项（记录到 removed_defaults）。
 */
public class Whitelist {

    private static final String PREFS_NAME = "dcl_prefs";
    private static final String KEY_WHITELIST = "whitelist_packages";
    private static final String KEY_REMOVED_DEFAULTS = "removed_defaults";

    /** 内置默认白名单（随代码打包，用户可通过开关关闭） */
    private static final Set<String> DEFAULT_WHITELIST = new HashSet<>();

    static {
        // DCL 工具自身
        DEFAULT_WHITELIST.add("dclnb.dcltools");
        DEFAULT_WHITELIST.add("dclnb.dclaqhd");
        // 白名单应用
        DEFAULT_WHITELIST.add("com.latools.app");
        DEFAULT_WHITELIST.add("com.techlock.guard");
        DEFAULT_WHITELIST.add("xiaote.AnQuan");
        DEFAULT_WHITELIST.add("moe.shizuku.privileged.api");
        DEFAULT_WHITELIST.add("dclnb.appmanager");
        DEFAULT_WHITELIST.add("com.youlong.hd");
        DEFAULT_WHITELIST.add("com.youlong.cj");
        DEFAULT_WHITELIST.add("com.youlong.tool");
        DEFAULT_WHITELIST.add("com.LiLongnb666.app");
        DEFAULT_WHITELIST.add("com.viruskiller.app");
        DEFAULT_WHITELIST.add("dcl.aq.top");
        DEFAULT_WHITELIST.add("com.xy.simpledefense");
        DEFAULT_WHITELIST.add("com.protect.guard");
        DEFAULT_WHITELIST.add("com.qingdun.antivirus");
        DEFAULT_WHITELIST.add("dcl.bd");
        DEFAULT_WHITELIST.add("com.ss.android.ugc.awe");
        DEFAULT_WHITELIST.add("com.deepseek.chat");
        DEFAULT_WHITELIST.add("com.xiaoshan.antivirus");
        DEFAULT_WHITELIST.add("frb.axeron.manager");
        DEFAULT_WHITELIST.add("com.xmkillerx");
        DEFAULT_WHITELIST.add("mio.lock.kill");
        DEFAULT_WHITELIST.add("aidepro.mthugo");
        DEFAULT_WHITELIST.add("com.tencent.mobileqq");
        DEFAULT_WHITELIST.add("com.ai.assistance.operit");
        DEFAULT_WHITELIST.add("com.tencent.mm");
        DEFAULT_WHITELIST.add("bin.mt.plus.canary");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读取持久化包名集合（逗号分隔） */
    private static Set<String> readSet(SharedPreferences prefs, String key) {
        Set<String> set = new HashSet<>();
        try {
            String raw = prefs.getString(key, "");
            if (raw != null && !raw.isEmpty()) {
                String[] arr = raw.split("[,，\\n]");
                for (String pkg : arr) {
                    String trimmed = pkg.trim();
                    if (!trimmed.isEmpty()) set.add(trimmed);
                }
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    /** 写入持久化包名集合（逗号分隔） */
    private static void writeSet(SharedPreferences prefs, String key, Set<String> set) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String pkg : set) {
                if (pkg == null || pkg.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(pkg.trim());
            }
            prefs.edit().putString(key, sb.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 获取被用户关闭的默认白名单 */
    private static Set<String> getRemovedDefaults(Context context) {
        return readSet(prefs(context), KEY_REMOVED_DEFAULTS);
    }

    /**
     * 判断包名是否在白名单中
     */
    public static boolean isWhitelisted(Context context, String packageName) {
        if (packageName == null) return false;
        try {
            SharedPreferences prefs = prefs(context);
            Set<String> removed = readSet(prefs, KEY_REMOVED_DEFAULTS);
            if (removed.contains(packageName)) return false;
            if (DEFAULT_WHITELIST.contains(packageName)) return true;
            return readSet(prefs, KEY_WHITELIST).contains(packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 添加包名到白名单（同时从 removed_defaults 撤销）
     */
    public static void addWhitelisted(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            SharedPreferences prefs = prefs(context);

            // 如果是默认白名单项，撤销"被移除"记录即可
            if (DEFAULT_WHITELIST.contains(packageName)) {
                Set<String> removed = readSet(prefs, KEY_REMOVED_DEFAULTS);
                if (removed.remove(packageName)) {
                    writeSet(prefs, KEY_REMOVED_DEFAULTS, removed);
                }
                return;
            }

            Set<String> user = readSet(prefs, KEY_WHITELIST);
            if (user.add(packageName)) {
                writeSet(prefs, KEY_WHITELIST, user);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 从白名单移除包名（内置默认项记录到 removed_defaults）
     */
    public static void removeWhitelisted(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            SharedPreferences prefs = prefs(context);

            if (DEFAULT_WHITELIST.contains(packageName)) {
                Set<String> removed = readSet(prefs, KEY_REMOVED_DEFAULTS);
                if (removed.add(packageName)) {
                    writeSet(prefs, KEY_REMOVED_DEFAULTS, removed);
                }
                return;
            }

            Set<String> user = readSet(prefs, KEY_WHITELIST);
            if (user.remove(packageName)) {
                writeSet(prefs, KEY_WHITELIST, user);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 获取用户自定义白名单（不含内置默认）
     */
    public static Set<String> getUserWhitelist(Context context) {
        return readSet(prefs(context), KEY_WHITELIST);
    }

    /**
     * 获取所有白名单（内置 - 被用户关闭 + 用户自定义）
     */
    public static Set<String> getAllWhitelisted(Context context) {
        try {
            Set<String> all = new HashSet<>(DEFAULT_WHITELIST);
            all.addAll(getUserWhitelist(context));
            all.removeAll(getRemovedDefaults(context));
            return all;
        } catch (Exception e) {
            return new HashSet<>(DEFAULT_WHITELIST);
        }
    }
}