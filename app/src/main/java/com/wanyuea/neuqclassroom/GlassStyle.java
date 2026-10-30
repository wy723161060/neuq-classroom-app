package com.wanyuea.neuqclassroom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * 玻璃材质。
 *
 * <p>「液态玻璃」在 Android 上真正难的是背景模糊：平台只在 API 31+ 提供
 * {@code RenderEffect} / 窗口模糊，而这个项目 minSdk 是 26、且零第三方依赖，
 * 引入实时模糊的成本和风险都不划算。所以这里做的是**材质层**的近似：
 *
 * <ol>
 *   <li>半透明底色 —— 页面背景、自定义背景图能透上来，这是「玻璃」的根；</li>
 *   <li>顶部高光渐变 —— 光线掠过玻璃面的那道亮边；</li>
 *   <li>发丝描边 —— 勾出玻璃的厚度，深色背景下尤其明显。</li>
 * </ol>
 *
 * <p>透明度统一取 46% / 65% / 80% 三档：正文永远是深色字，所以即使叠在
 * 柔光背景上，80% 这一档的对比度仍然够。要更透就用 {@link #FILL_FAINT}，
 * 但别再往下调，深色背景图上会看不清字。
 */
final class GlassStyle {

    /** 72% 白：内容卡片、弹窗正文 */
    static final int FILL = 0xB8FFFFFF;
    /** 70% 白：底部 Dock、次级卡片 */
    static final int FILL_SOFT = 0xB3FFFFFF;
    /** 46% 白：表头、时段列这类「不希望抢戏」的底 */
    static final int FILL_FAINT = 0x75FFFFFF;
    /** 发丝描边 */
    static final int BORDER = 0x99FFFFFF;

    private GlassStyle() {}

    /**
     * 一块玻璃。
     *
     * @param radiusDp 圆角（dp）
     * @param tint     底色（只取 RGB，透明度由 alpha 决定）
     * @param alpha    0–255，越大越不透
     */
    static Drawable panel(Context ctx, float radiusDp, int tint, int alpha) {
        int radius = dp(ctx, radiusDp);

        GradientDrawable base = new GradientDrawable();
        base.setShape(GradientDrawable.RECTANGLE);
        base.setColor((tint & 0x00FFFFFF) | ((alpha & 0xFF) << 24));
        base.setCornerRadius(radius);

        // 高光：左上偏白，往右下淡出
        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0x3DFFFFFF, 0x12FFFFFF, 0x00FFFFFF});
        sheen.setShape(GradientDrawable.RECTANGLE);
        sheen.setCornerRadius(radius);

        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.RECTANGLE);
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(radius);
        border.setStroke(Math.max(1, dp(ctx, 1)), BORDER);

        return new LayerDrawable(new Drawable[]{base, sheen, border});
    }

    /** 内容卡片：玻璃 + 轻微投影（投影靠 outline 跟随圆角） */
    static void card(View v, float radiusDp, int tint, int alpha) {
        if (v == null) return;
        v.setBackground(panel(v.getContext(), radiusDp, tint, alpha));
        v.setElevation(dp(v.getContext(), 3));
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        v.setClipToOutline(true);
    }

    /** 胶囊按钮：圆角取很大值，GradientDrawable 会按高度裁成胶囊 */
    static void pill(View v, int tint, int alpha) {
        if (v == null) return;
        v.setBackground(panel(v.getContext(), 100f, tint, alpha));
    }

    static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }
}
