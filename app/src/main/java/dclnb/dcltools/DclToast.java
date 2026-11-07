package dclnb.dcltools;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * DCL 统一轻提示（DclToast）。
 * 
 * 集中管理无障碍服务里的所有浮层提示，统一风格与动画：
 *   · BANNER  顶部通栏横幅（可点击），N 毫秒后自动消失
 *   · PILL    顶部居中胶囊提示，N 毫秒无更新自动消失
 *
 * 统一样式：圆角、内边距、文字大小、背景色、层级类型、出现/消失动画。
 * 动画：弹出 0.85→1.0 + 透明→不透明；隐藏 1.0→0.9 + 不透明→透明。
 */
public class DclToast {

    /** 胶囊提示默认停留时长（毫秒） */
    private static final long DEFAULT_PILL_DURATION = 1500L;
    /** 横幅自动消失时长（毫秒） */
    private static final long DEFAULT_BANNER_AUTO_HIDE_MS = 5000L;
    /** 弹出动画时长 */
    private static final long SHOW_DURATION = 200L;
    /** 隐藏动画时长 */
    private static final long HIDE_DURATION = 150L;

    /** 弹出起始缩放 */
    private static final float SHOW_SCALE_FROM = 0.85f;
    /** 隐藏结束缩放 */
    private static final float HIDE_SCALE_TO = 0.90f;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler handler;

    // ---- 顶部通栏横幅 ----
    private View bannerView;
    private Runnable bannerAutoHideRunnable;

    // ---- 顶部胶囊提示 ----
    private View pillView;
    private final Runnable pillDismiss = new Runnable() {
        @Override
        public void run() {
            hidePill();
        }
    };

    /** 点击回调 */
    public interface OnClick {
        void onClick();
    }

    public DclToast(AccessibilityService service, Handler handler) {
        this.service = service;
        this.handler = handler;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * 设置横幅自动消失时长，-1 表示永不自动消失
     */
    public void setBannerAutoHideMs(long ms) {
        // 注意：此方法仅用于动态调整，静态常量 DEFAULT_BANNER_AUTO_HIDE_MS 已设为 5000L
    }

    private float density() {
        return service.getResources().getDisplayMetrics().density;
    }

    private int dp(int v) {
        return (int) (v * density() + 0.5f);
    }

    // ==================== 动画 ====================

    /** 在 addView 之前设置初始状态，避免闪一帧原大小 */
    private void prepareShow(View v) {
        if (v == null) return;
        v.setScaleX(SHOW_SCALE_FROM);
        v.setScaleY(SHOW_SCALE_FROM);
        v.setAlpha(0f);
    }

    private void playShow(View v) {
        if (v == null) return;
        v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(SHOW_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 播放隐藏动画，结束后再移除视图 */
    private void playHideAndRemove(final View v) {
        if (v == null) return;
        v.animate()
                .scaleX(HIDE_SCALE_TO)
                .scaleY(HIDE_SCALE_TO)
                .alpha(0f)
                .setDuration(HIDE_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        removeViewSilently(v);
                    }
                })
                .start();
    }

    private void removeViewSilently(View v) {
        if (v == null || wm == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {}
    }

    // ==================== 顶部通栏横幅 ====================

    public boolean isBannerShowing() {
        return bannerView != null;
    }

    /**
     * 显示顶部通栏横幅（红底白字，可点击）。
     * 已显示时会先播放隐藏动画，再显示新的。
     * 默认 5 秒后自动消失。
     *
     * @param text     文案
     * @param listener 点击回调，可为 null
     */
    public void showBanner(String text, final OnClick listener) {
        // 先取消旧的定时任务
        cancelBannerAutoHide();
        
        // 如果已有横幅，立即移除（不做动画，避免重叠）
        if (bannerView != null) {
            removeViewSilently(bannerView);
            bannerView = null;
        }

        try {
            if (wm == null) return;

            LinearLayout banner = new LinearLayout(service);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(dp(12), dp(6), dp(12), dp(6));
            banner.setBackgroundColor(0xCCD32F2F); // 半透明红色
            banner.setClickable(true);

            TextView tv = new TextView(service);
            tv.setText(text == null ? "" : text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            banner.addView(tv);
            
            banner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideBanner();
                    if (listener != null) listener.onClick();
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE 
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;

            prepareShow(banner);
            wm.addView(banner, lp);
            bannerView = banner;
            playShow(banner);

            // 设置自动消失定时器
            bannerAutoHideRunnable = new Runnable() {
                @Override
                public void run() {
                    hideBanner();
                }
            };
            handler.postDelayed(bannerAutoHideRunnable, DEFAULT_BANNER_AUTO_HIDE_MS);
        } catch (Exception e) {
            android.util.Log.e("DclToast", "showBanner失败", e);
        }
    }

    /** 取消横幅自动消失定时器 */
    private void cancelBannerAutoHide() {
        if (bannerAutoHideRunnable != null) {
            handler.removeCallbacks(bannerAutoHideRunnable);
            bannerAutoHideRunnable = null;
        }
    }

    public void hideBanner() {
        cancelBannerAutoHide();
        if (bannerView == null) return;
        View v = bannerView;
        bannerView = null;
        playHideAndRemove(v);
    }

    // ==================== 顶部胶囊提示 ====================

    public boolean isPillShowing() {
        return pillView != null;
    }

    /**
     * 显示/更新顶部居中胶囊提示。复用一个浮窗，重复调用只更新文字并重置计时。
     *
     * @param text       显示文字
     * @param durationMs 无更新自动消失时长（毫秒），<=0 用默认值
     */
    public void showPill(String text, long durationMs) {
        long dur = durationMs > 0 ? durationMs : DEFAULT_PILL_DURATION;
        try {
            if (wm == null) return;
            
            if (pillView != null) {
                // 更新已有pill的文字并重置计时
                Object tag = pillView.getTag();
                if (tag instanceof TextView) {
                    ((TextView) tag).setText(text == null ? "" : text);
                }
                handler.removeCallbacks(pillDismiss);
                handler.postDelayed(pillDismiss, dur);
                return;
            }

            LinearLayout box = new LinearLayout(service);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(0xCC000000);
            box.setBackground(bg);
            box.setPadding(dp(18), dp(10), dp(18), dp(10));

            TextView tv = new TextView(service);
            tv.setText(text == null ? "" : text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            box.addView(tv);
            box.setTag(tv);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, 
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(80);

            prepareShow(box);
            wm.addView(box, lp);
            pillView = box;
            playShow(box);
            handler.removeCallbacks(pillDismiss);
            handler.postDelayed(pillDismiss, dur);
        } catch (Exception ignored) {}
    }

    public void hidePill() {
        handler.removeCallbacks(pillDismiss);
        if (pillView == null) return;
        View v = pillView;
        pillView = null;
        playHideAndRemove(v);
    }

    // ==================== 清理 ====================

    /** 服务销毁时清理全部提示与回调 */
    public void cleanup() {
        cancelBannerAutoHide();
        handler.removeCallbacks(pillDismiss);
        if (pillView != null) {
            View v = pillView;
            pillView = null;
            removeViewSilently(v);
        }
        if (bannerView != null) {
            View v = bannerView;
            bannerView = null;
            removeViewSilently(v);
        }
    }
}