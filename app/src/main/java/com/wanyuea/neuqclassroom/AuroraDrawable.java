package com.wanyuea.neuqclassroom;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 页面底色：一层渐变 + 三团柔光。
 *
 * <p>为什么需要它：玻璃材质靠「透出来的背景」成立。之前的底色是纯浅灰
 * （#F3F5F9），85% 白的面板叠上去几乎和纯白没差别 —— 视觉上等于没有玻璃。
 * 换成带颜色的柔光背景之后，半透明面板才真的能透出层次。
 *
 * <p>三团光分别落在左上、右上、左下，用径向渐变模拟虚化光斑；颜色取自
 * 主题强调色加一组冷暖补色，所以换主题时整体色调会跟着走。
 *
 * <p>用 Drawable 而不是先画成 Bitmap：按控件尺寸实时绘制，不占一张全屏位图。
 */
final class AuroraDrawable extends Drawable {

    private final int baseTop;
    private final int baseBottom;
    private final int spotAccent;
    private final int spotWarm;
    private final int spotCool;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader baseShader;
    private Shader[] spots;
    private int width;
    private int height;

    AuroraDrawable(int background, int accent) {
        this.baseTop = blend(background, 0xFFFFFFFF, 0.30f);
        this.baseBottom = background;
        // 柔光透明度 22%–35%：卡片是 72% 白，底色必须比它「花」才能透出层次
        this.spotAccent = withAlpha(accent, 0x59);
        this.spotWarm = withAlpha(0xFFFFB27A, 0x38);
        this.spotCool = withAlpha(0xFF8FB8FF, 0x40);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        width = bounds.width();
        height = bounds.height();
        if (width <= 0 || height <= 0) return;

        baseShader = new LinearGradient(0, 0, 0, height,
                baseTop, baseBottom, Shader.TileMode.CLAMP);

        spots = new Shader[]{
                radial(width * 0.16f, height * 0.06f, width * 1.10f, spotAccent),
                radial(width * 0.95f, height * 0.28f, width * 0.85f, spotWarm),
                radial(width * 0.05f, height * 0.95f, width * 0.95f, spotCool)
        };
    }

    private Shader radial(float cx, float cy, float radius, int color) {
        return new RadialGradient(cx, cy, Math.max(1f, radius),
                color, Color.TRANSPARENT, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0 || baseShader == null) return;
        paint.setShader(baseShader);
        canvas.drawRect(0, 0, width, height, paint);
        if (spots != null) {
            for (Shader s : spots) {
                paint.setShader(s);
                canvas.drawRect(0, 0, width, height, paint);
            }
        }
        paint.setShader(null);
    }

    @Override
    public void setAlpha(int alpha) {
        // 整体透明度由页面自己控制，这里不需要
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // 不支持滤镜
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int blend(int base, int overlay, float amount) {
        float keep = 1f - amount;
        int r = Math.round(Color.red(base) * keep + Color.red(overlay) * amount);
        int g = Math.round(Color.green(base) * keep + Color.green(overlay) * amount);
        int b = Math.round(Color.blue(base) * keep + Color.blue(overlay) * amount);
        return Color.rgb(r, g, b);
    }
}
