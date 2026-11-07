package dclnb.dcltools;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * DclApp - Application 入口（参照弑神者 GodApp）。
 *
 * 进程启动时拉起所有守护组件：
 *   1. DclCoreService - 无障碍掉线兜底
 *   2. DclKeepAliveReceiver - 系统闹钟兜底（独立于进程）
 *   3. DclGuardianService - 守护守护服务（500ms轮询）
 *   4. DclRestartService - 核心服务重启守护（5s轮询）
 *   5. DclGuardianProcess - 独立进程守护（反向守护主进程）
 *   6. GuardService - 若防护开关开启则拉起
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

        // 1. 调度保活闹钟
        try { DclKeepAliveReceiver.schedule(this); } catch (Throwable ignored) {}

        // 2. 拉起核心常驻服务
        try { DclCoreService.ensureStarted(this); } catch (Throwable ignored) {}

        // 3. 拉起守护守护服务
        try { DclGuardianService.ensureStarted(this); } catch (Throwable ignored) {}

        // 4. 拉起重启守护服务
        try { DclRestartService.ensureStarted(this); } catch (Throwable ignored) {}

        // 5. 拉起独立进程守护（在自身进程死亡时重启主进程）
        try { DclGuardianProcess.ensureStarted(this); } catch (Throwable ignored) {}

        // 6. 若防护开关仍开启，拉起守护服务
        try {
            SharedPreferences prefs = getSharedPreferences("dcl_prefs", MODE_PRIVATE);
            if (prefs.getBoolean("protection_enabled", false)) {
                startService(new android.content.Intent(this, GuardService.class));
            }
        } catch (Throwable ignored) {}

        Log.d(TAG, "所有守护组件已启动");
    }
}