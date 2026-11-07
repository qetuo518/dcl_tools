package dclnb.dcltools;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 疑似病毒/锁机应用包名管理
 *
 * 包名来源（取并集，命中任一即判定为病毒/锁机应用）：
 *   1. 规则文件三源动态加载（优先级：导入 > 云端 > 内置）：
 *      filesDir/scan_rules_imported.json - 用户导入规则（可删除）
 *      filesDir/scan_rules_cloud.json    - 云端更新规则（可删除）
 *      assets/scan_rules.json            - 内置规则（随 APK 打包）
 *   2. 硬编码兜底库 BUILTIN_FALLBACK（assets 读取失败时使用）
 *   3. 用户自定义包名（SharedPreferences：dot_config/custom_virus_packages）
 */
public class VirusPackages {

    /** 内置包名缓存（由规则文件动态加载，此处置空） */
    public static String[] BUILTIN = {};

    /** 硬编码兜底病毒包名库（与 xtsafe-main scan_rules.json 的 virusPackages 同步） */
    private static final String[] BUILTIN_FALLBACK = {
            // 儿童锁/家长控制类锁机应用
            "com.ShiYuan.base",
            "com.broaddeep.safe.childrennetguard",
            "com.vivo.familycare",
            "com.google.android.apps.kids.familylinkhelper",
            "com.chinafic.shzs.children",
            "com.blabla.watchdog",
            "com.sangfei.guardian",
            "com.mmguardian.tabletsecurity",
            "com.clevguard.kidsguard.parent",
            // 已知恶意锁机/勒索应用
            "com.h-1",
            "com.ats.yr",
            "com.cange.wd",
            "com.mycompany.application",
            "com.liangcheng.mini",
            "c18.dhmgbup.pgmt.tnecnet.moc.abef971.m0",
            "com.huge.p34366425",
            "com.i6b401775ea8ced552",
            "com.i9236f23a6f538c44",
            "com.i838d322f2a6b750a",
            "com.Tsc",
            "com.my.evil.tim",
            "com.zz166666556",
            "com.Qinjian",
            "com.qq2973357235.wzasj",
            "com.nancisancongsuoji",
            "com.Mixo.XianBao",
            "com.Tong.nb",
            "com.fundot.p4bu",
            "com.gwchina.lssw.child",
            "com.kidguard360.app",
            "com.green.guard",
            "com.wmz.timelock",
            "com.example.lock",
            "com.num.kid",
            "com.fuck.lock",
            "com.harmoxidev2",
            "com.my.newproject26851546484645",
            "dadongbei.shi.wodejiaxiang",
            "My.horror.Android13",
            "com.my.newproject56",
            "com.servers.ozzbzk",
            "com.my.newproject6616616162",
            "com.lockgen.template.app",
            "com.gzmsnby7723",
            "com.root6767xxsXiaobailian67",
            "com.examjdgjshvsjsjbwbjshple.lock",
            "com.primatelabs.geekbench11451427403",
            "com.exjdbrhdydgdhudhdheudhfufufhfhfhhehdhmple.lock",
            "test.virus",
            "com.mycompany.myapp",
            "com.my.newproject91",
            "uxklwljtx.xsoldp.sjjid",
            "com.service.device.android.app",
            "com.optimizer.x",
            "com.example.testapp",
            "com.cjk",
            "com.lc.nb",
            "cqsgk.com",
            "io.github.dogguard",
            "com.rainbowcat",
            "com.system.update",
            "com.tg.minvy54.memz",
            "com.horrorAngela3",
            "com.my.horror",
            "n.v.m",
            "com.my.newproject9vbvgh",
            "com.rangestudio.android22installer",
            "com.wuyu.lock",
            "Ez.Phone.Virus",
            "com.io.mrafy.home.gh.io.mrafy.home.gh",
            "com.my.newproject214",
            "com.my.newproject2",
            "com.nm.Eiy",
            "com.LiJun.setup",
            "com.my.newproject62",
            "com.hydramacro",
            "com.my.newproject62skzjx",
            "dot.delete.systen",
            "COM.VIRUS",
            "com.my.newproject3",
            "com.my.newproject6",
            "com.my.newproject7",
            "com.my.newproject8",
            "com.my.newproject9",
            "com.my.newproject10",
            "com.Carol.nb",
            "com.my.newproject4",
            "com.my.newproject5",
            "com.my.newproject11",
            "com.my.newproject12",
            "com.my.newproject13",
            "com.my.newproject14",
            "com.my.newproject15",
            "com.my.newproject21.SketchApplication",
            "com.my.newproject23.SketchApplication",
            "com.my.newproject16",
            "com.my.newproject17",
            "com.my.newproject18",
            "com.my.newproject19",
            "com.my.newproject20",
            "com.my.newproject21",
            "com.my.newproject22",
            "com.my.newproject23",
            "com.my.newproject24",
            "com.my.newproject25",
            "com.my.newproject26",
            "com.my.newproject27",
            "com.my.newproject94",
            "com.my.newproject95",
            "com.my.newproject96",
            "com.my.newproject97",
            "com.my.jaj",
            "com.my.cadj",
            "com.naturegames.ransom",
            "mio.terminaldeity.pro",
            "mio.terminaldeity.plus",
            "com.zidongdianjiqi",
            "hztesyhffsxtjryqv.klptnrsblerooiyes.kwbmnbaocvow",
            "com.bangbangtang.lock",
            "com.cange.kbzxg.waosv",
            "com.cange.pyhzy.bxqqg",
            "com.chuimou.myapp",
            "com.doubao.lockprank",
            "com.example.chuimou",
            "com.example.whkbnb.sj.nb.app",
            "com.greendemon.lock",
            "com.runaway.pluing",
            "com.sssp",
            "com.synb666",
            "com.xiaoshannb.lock",
            "com.prank.bsod",
            "com.my.newproject82",
            "com.example.wzalock.jqb",
            "com.my.newprodnt1145147891",
            "example.xfc.pro",
            // 仿冒系统/安全类虚假安全应用
            "com.android.lock.screen",
            "com.screen.lock.fake",
            "com.phone.security.lock",
            "com.android.system.lock",
            "com.google.play.protect",
            "com.android.settings.lock",
            "com.whatsapp.security.lock",
            "com.facebook.video.lock",
            "com.android.virus.scan",
            "com.antivirus.pro.fake",
            "com.android.clean.master",
            "com.phone.booster.fake",
            "com.battery.saver.pro",
            "com.android.app.lock",
            "com.privacy.guard.fake",
            "com.android.trojan.generic",
            "com.android.malware.test",
            "com.spy.mobile.tracker",
            "com.sms.backup.fake",
            "com.android.call.recorder",
            "com.ghost.scream.lock",
            "com.horror.scary.virus",
            "com.fake.systemui.lock",
            "com.ransom.test01",
            "com.lock.screen.ghost",
            "com.evil.volume.bomb",
            "com.fake.settings.lock",
            "com.black.screen.ransom",
            "com.creepy.noise.virus",
            "com.auto.permission.steal",
            "com.fake.updater.lock",
            "com.dark.horror.test",
            "com.lock.scream.123",
            "com.malware.volumeflood",
            "com.ghostcall.lock",
            "com.fake.security.virus",
            "com.shadow.ransom.test",
            "com.terrify.screen.lock",
            "com.auto.open.horror",
            "com.deletefile.testlock",
            "com.mio.terminaldeity.old",
            "com.voice.horror.lock",
            "com.fake.system.alert",
            "com.silent.ransom.test",
            "com.scary.popup.virus",
            "com.my.newprodnt1145146669967892",
            "hxr.qt.dqmutyfkhy80",
            "com.my.newprodns",
            "com.my.newmymy",
            "com.my.newmy",
            "com.my.new",
            "com.my.newproject63",
            "com.my.newproject64",
            "com.my.newproject65",
            "com.my.newproject66"
    };

    /** 上次加载时间戳，5 秒缓存保证规则文件改动后能较快生效 */
    private static long lastLoadTime = 0;
    private static final long LOAD_INTERVAL_MS = 5000;

    public static final String PREFS_NAME = "dot_config";
    public static final String KEY_CUSTOM = "custom_virus_packages";
    public static final String KEY_MANAGED = "managed_packages";

    /**
     * 供外部（如 Application 启动时）直接注入已解析好的内置包名列表，
     * 避免每次 getVirusPackages 都走一遍三源文件读取。
     */
    public static void setBuiltin(String[] list) {
        if (list != null) {
            BUILTIN = list;
            lastLoadTime = System.currentTimeMillis();
        }
    }

    /** 获取全部病毒包名集合（规则文件 + 兜底库 + 用户自定义） */
    public static Set<String> getVirusPackages(Context context) {
        // 缓存失效或从未加载时，从规则文件重新加载
        if (System.currentTimeMillis() - lastLoadTime > LOAD_INTERVAL_MS || BUILTIN.length == 0) {
            loadFromRules(context);
        }
        Set<String> set = new HashSet<String>();
        if (BUILTIN.length > 0) {
            for (String p : BUILTIN) {
                if (p != null && !p.isEmpty()) set.add(p.trim());
            }
        } else {
            // 规则文件不可用时回退到硬编码兜底库
            for (String p : BUILTIN_FALLBACK) {
                if (p != null && !p.isEmpty()) set.add(p.trim());
            }
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_CUSTOM, "");
            if (raw != null && !raw.isEmpty()) {
                String[] arr = raw.split("[,，\\n]");
                for (String s : arr) {
                    String p = s.trim();
                    if (!p.isEmpty()) set.add(p);
                }
            }
        } catch (Exception e) {}
        return set;
    }

    /** 从规则文件动态加载病毒包名列表（优先级：导入 > 云端 > 内置） */
    private static void loadFromRules(Context context) {
        // 逐个尝试，一个失败不影响下一个
        String json = tryRead(context, "scan_rules_imported.json");
        if (json == null) json = tryRead(context, "scan_rules_cloud.json");
        if (json == null) json = tryReadAssets(context, "scan_rules.json");
        if (json != null) {
            try {
                JSONObject root = new JSONObject(json);
                JSONArray arr = root.optJSONArray("virusPackages");
                if (arr != null && arr.length() > 0) {
                    String[] list = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) list[i] = arr.getString(i);
                    BUILTIN = list;
                    lastLoadTime = System.currentTimeMillis();
                }
            } catch (Exception ignored) {}
        }
    }

    /** 安全读取 filesDir 下的文件，失败返回 null */
    private static String tryRead(Context context, String fileName) {
        try {
            File f = new File(context.getFilesDir(), fileName);
            if (f.exists()) return readFile(f);
        } catch (Exception ignored) {}
        return null;
    }

    /** 安全读取 assets 下的文件，失败返回 null */
    private static String tryReadAssets(Context context, String fileName) {
        try {
            InputStream is = context.getAssets().open(fileName);
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } catch (Exception ignored) {}
        return null;
    }

    private static String readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        BufferedReader r = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        fis.close();
        return sb.toString();
    }

    /** 保存自定义病毒包名（逗号/换行分隔） */
    public static void saveCustomVirusPackages(Context context, String raw) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CUSTOM, raw == null ? "" : raw.trim()).apply();
    }

    /** 获取自定义病毒包名字符串 */
    public static String getCustomVirusPackages(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM, "");
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 管控列表 ====================
    // 管控与病毒不同：管控不禁止安装，只是单独展示管理

    /** 获取管控包名集合 */
    public static Set<String> getManagedPackages(Context context) {
        Set<String> set = new HashSet<String>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_MANAGED, "");
            if (raw != null && !raw.isEmpty()) {
                String[] arr = raw.split("[,，\\n]");
                for (String s : arr) {
                    String p = s.trim();
                    if (!p.isEmpty()) set.add(p);
                }
            }
        } catch (Exception e) {}
        return set;
    }

    /** 判断是否管控 */
    public static boolean isManaged(Context context, String pkg) {
        return getManagedPackages(context).contains(pkg);
    }

    /** 添加为管控 */
    public static void addManagedPackage(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        String p = pkg.trim();
        Set<String> set = getManagedPackages(context);
        set.add(p);
        saveManagedPackages(context, set);
    }

    /** 移除管控 */
    public static void removeManagedPackage(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> set = getManagedPackages(context);
        set.remove(pkg.trim());
        saveManagedPackages(context, set);
    }

    private static void saveManagedPackages(Context context, Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MANAGED, sb.toString()).apply();
    }
}