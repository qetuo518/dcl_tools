package dclnb.dcltools;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * DclApp - Application 入口。
 *
 * 需求：安全护盾一进入软件默认关闭，任何守护/保活进程都不要有，
 * 只有用户手动开启安全护盾时才会启动相关服务。
 *
 * 因此本 Application 启动时不拉起任何守护/保活组件，
 * 全部交由 DclaqActivity 在用户手动开启防护时再启动。
 */
public class DclApp extends Application {

    private static final String TAG = "DclApp";
    private static Context sContext;

    public static Context getContext() {
        return sContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sContext = this;

        // 需求：不自动启动任何守护/保活进程。
        // 安全护盾默认关闭，只有用户手动开启时才启动相关服务。
        // 因此这里不再拉起 DclCoreService / DclGuardianService /
        // DclRestartService / DclGuardianProcess / 保活闹钟 等。
        Log.d(TAG, "DclApp 启动完成（不自动启动任何守护/保活进程）");
    }
}