package dclnb.dcltools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * DCL拦截确认弹窗 - 悬浮窗形式。
 *
 * 当音量超过阈值时显示，用户选择"是"执行完整拦截，选择"否"仅恢复音量不拦截。
 * 弹窗通过 TYPE_APPLICATION_OVERLAY / TYPE_PHONE 实现顶置，并定期刷新层级防止被覆盖。
 */
public class DclInterceptDialog {

    private static final String TAG = "DclInterceptDialog";
    private static final int DIALOG_WIDTH_DP = 300;
    private static final int DIALOG_HEIGHT_DP = 170;

    // 顶置刷新间隔（毫秒）：定期更新布局以提升窗口层级，防止被锁机应用覆盖
    private static final long TOPMOST_REFRESH_MS = 800L;

    private WindowManager wm;
    private View dialogView;
    private WindowManager.LayoutParams layoutParams;
    private boolean isShowing = false;
    private Handler handler;
    private Runnable topmostTask;
    private float density = 1.0f;

    // 单例，避免重复弹窗
    private static DclInterceptDialog instance;

    // 弹窗关闭回调（由 ProtectionVolumeThreshold 注册）
    private static Runnable onDismissCallback;

    private DclInterceptDialog(Context context) {
        this.wm = (WindowManager) context.getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
        try {
            this.density = context.getApplicationContext()
                    .getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {}
    }

    /**
     * 设置弹窗关闭回调
     */
    public static void setOnDismissCallback(Runnable callback) {
        onDismissCallback = callback;
    }
    
    /**
     * 显示拦截确认弹窗
     * @param context 应用上下文
     */
    public static void show(Context context) {
        if (context == null) return;
        // 如果已在显示，先移除旧的（不触发关闭回调，避免状态错乱）
        if (instance != null && instance.isShowing) {
            instance.removeViewInternal();
        }
        instance = new DclInterceptDialog(context);
        instance.buildAndShow(context);
    }

    /**
     * 构建并显示悬浮窗弹窗
     */
    private void buildAndShow(final Context context) {
        try {
            dialogView = createDialogContent(context);
            layoutParams = buildLayoutParams(context);
            wm.addView(dialogView, layoutParams);
            isShowing = true;
            android.util.Log.i(TAG, "拦截弹窗已显示");
            // 启动顶置刷新：定期更新层级，防止被其它窗口覆盖
            startTopmostRefresh();
        } catch (Exception e) {
            android.util.Log.e(TAG, "显示弹窗失败(type1)，尝试降级", e);
            // 降级重试：TYPE_APPLICATION_OVERLAY 失败则用 TYPE_SYSTEM_OVERLAY
            try {
                dialogView = createDialogContent(context);
                layoutParams = buildLayoutParamsFallback(context);
                wm.addView(dialogView, layoutParams);
                isShowing = true;
                android.util.Log.i(TAG, "拦截弹窗已显示（降级模式）");
                startTopmostRefresh();
            } catch (Exception e2) {
                android.util.Log.e(TAG, "显示弹窗失败(降级后仍失败)", e2);
                dismiss();
            }
        }
    }

    /**
     * 构建窗口参数（优先 TYPE_APPLICATION_OVERLAY / TYPE_PHONE）
     */
    private WindowManager.LayoutParams buildLayoutParams(Context context) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dpToPx(DIALOG_WIDTH_DP),
                dpToPx(DIALOG_HEIGHT_DP),
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;
        params.x = 0;
        params.y = 0;
        return params;
    }

    /**
     * 构建窗口参数（降级模式）
     */
    private WindowManager.LayoutParams buildLayoutParamsFallback(Context context) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dpToPx(DIALOG_WIDTH_DP),
                dpToPx(DIALOG_HEIGHT_DP),
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;
        params.x = 0;
        params.y = 0;
        return params;
    }

    /**
     * 获取悬浮窗类型
     * API 26+ 使用 TYPE_APPLICATION_OVERLAY，旧版本使用 TYPE_PHONE
     */
    private int getOverlayType() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    /**
     * 启动顶置刷新：定期更新布局，提升窗口 z-order
     */
    private void startTopmostRefresh() {
        stopTopmostRefresh();
        topmostTask = new Runnable() {
            @Override
            public void run() {
                if (isShowing && dialogView != null && layoutParams != null) {
                    try {
                        // 重新更新布局以提升窗口层级
                        wm.updateViewLayout(dialogView, layoutParams);
                    } catch (Throwable ignored) {}
                    handler.postDelayed(this, TOPMOST_REFRESH_MS);
                }
            }
        };
        handler.postDelayed(topmostTask, TOPMOST_REFRESH_MS);
    }

    /**
     * 停止顶置刷新
     */
    private void stopTopmostRefresh() {
        if (topmostTask != null && handler != null) {
            handler.removeCallbacks(topmostTask);
            topmostTask = null;
        }
    }

    /**
     * 创建弹窗内容视图
     */
    private View createDialogContent(final Context context) {
        // 根布局
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(18));
        root.setBackgroundColor(0xFFFFFFFF);

        // 标题
        TextView title = new TextView(context);
        title.setText("⚠️ DCL引擎 · 极强拦截");
        title.setTextSize(16);
        title.setTextColor(0xFFCC0000);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dpToPx(10));
        root.addView(title);

        // 提示文本
        TextView message = new TextView(context);
        message.setText("我们似乎发现了病毒应用，是否进行拦截？\n\n"
                + "「是」将执行安全拦截操作\n"
                + "「否」仅恢复音量，不执行拦截");
        message.setTextSize(13);
        message.setTextColor(0xFF333333);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, dpToPx(16));
        root.addView(message);

        // 按钮布局
        LinearLayout btnLayout = new LinearLayout(context);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.CENTER);

        // 「否」按钮
        Button btnNo = new Button(context);
        btnNo.setText("否");
        btnNo.setTextSize(14);
        btnNo.setTextColor(0xFF666666);
        btnNo.setBackgroundColor(0xFFE0E0E0);
        btnNo.setPadding(dpToPx(24), dpToPx(10), dpToPx(24), dpToPx(10));
        btnNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.util.Log.i(TAG, "用户选择「否」，仅恢复音量，不拦截");
                dismiss();
            }
        });

        // 「是」按钮
        Button btnYes = new Button(context);
        btnYes.setText("是");
        btnYes.setTextSize(14);
        btnYes.setTextColor(0xFFFFFFFF);
        btnYes.setBackgroundColor(0xFFFF5722);
        btnYes.setPadding(dpToPx(24), dpToPx(10), dpToPx(24), dpToPx(10));
        btnYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.util.Log.i(TAG, "用户选择「是」，执行完整拦截");
                executeFullIntercept(context);
                dismiss();
            }
        });

        // 添加按钮到布局（否在左，是 在右）
        btnLayout.addView(btnNo);
        btnLayout.addView(btnYes);
        root.addView(btnLayout);

        return root;
    }

    /**
     * 执行完整拦截操作（用户选择"是"时）
     * 参照 DclAccessibilityService 的拦截逻辑：
     *   1. 跳转拦截动画页（抢回屏幕）
     *   2. GuardService 触发防护红条
     *   3. 强制停止第三方应用（排除自身/白名单）
     */
    private void executeFullIntercept(final Context context) {
        // 1. 跳转到拦截动画页（抢回屏幕）
        try {
            android.content.Intent intent = new android.content.Intent(
                    context, DcltoolljActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            context.startActivity(intent);
            android.util.Log.i(TAG, "已跳转拦截动画页");
        } catch (Exception e) {
            android.util.Log.e(TAG, "跳转动画页失败", e);
        }

        // 2. 调用 GuardService 触发防护（红条）
        if (GuardService.getInstance() != null) {
            GuardService.getInstance().triggerProtection();
        }

        // 3. 强制停止第三方应用
        final Context ctx = context;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int count = 0;
                try {
                    android.content.pm.PackageManager pm = ctx.getPackageManager();
                    java.util.List<android.content.pm.ApplicationInfo> apps =
                            pm.getInstalledApplications(0);
                    String selfPkg = ctx.getPackageName();
                    String safeShield = "dclnb.dclaqhd";

                    for (android.content.pm.ApplicationInfo app : apps) {
                        if (app == null) continue;
                        if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                            continue;
                        }
                        if (selfPkg.equals(app.packageName)) continue;
                        if (safeShield.equals(app.packageName)) continue;
                        if (Whitelist.isWhitelisted(ctx, app.packageName)) {
                            continue;
                        }
                        if (ShellExecutor.forceStopApp(app.packageName)) {
                            count++;
                        }
                    }
                    android.util.Log.i(TAG, "安全操作完成，已停止 " + count + " 个应用");
                } catch (Exception e) {
                    android.util.Log.e(TAG, "停止应用失败", e);
                }

                final int stopped = count;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ctx,
                                "安全操作完成，已停止 " + stopped + " 个应用",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "dcl-volume-intercept").start();
    }

    /**
     * 移除弹窗
     */
    public void dismiss() {
        stopTopmostRefresh();
        if (dialogView != null && isShowing) {
            try {
                wm.removeView(dialogView);
            } catch (Exception e) {
                android.util.Log.w(TAG, "移除弹窗失败", e);
            }
            dialogView = null;
            layoutParams = null;
            isShowing = false;
            instance = null;
            // 调用关闭回调
            if (onDismissCallback != null) {
                onDismissCallback.run();
                onDismissCallback = null;
            }
            android.util.Log.i(TAG, "拦截弹窗已关闭");
        }
    }

    /**
     * 仅移除视图，不触发关闭回调（用于 show() 替换旧实例时）
     */
    private void removeViewInternal() {
        stopTopmostRefresh();
        if (dialogView != null && isShowing) {
            try {
                wm.removeView(dialogView);
            } catch (Exception e) {
                android.util.Log.w(TAG, "移除弹窗失败(internal)", e);
            }
            dialogView = null;
            layoutParams = null;
            isShowing = false;
            instance = null;
            android.util.Log.i(TAG, "拦截弹窗已替换(内部)");
        }
    }

    /**
     * dp 转 px（使用真实 density）
     */
    private int dpToPx(int dp) {
        return (int) (dp * density + 0.5f);
    }
}