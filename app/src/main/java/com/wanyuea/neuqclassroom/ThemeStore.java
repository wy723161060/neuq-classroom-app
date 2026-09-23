package com.wanyuea.neuqclassroom;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

final class ThemeStore {
    private static final String PREFS = "neuq_prefs";
    private static final String KEY_ACCENT = "theme_accent";
    private static final String KEY_BACKGROUND = "theme_background";
    private static final String KEY_SURFACE = "theme_surface";
    private static final String KEY_IMAGE_ALPHA = "theme_image_alpha";
    private static final String IMAGE_NAME = "theme_background.jpg";

    static final int DEFAULT_ACCENT = 0xFF2F6FED;
    static final int DEFAULT_BACKGROUND = 0xFFF3F5F9;
    static final int DEFAULT_SURFACE = 0xFFFFFFFF;

    static final int[] ACCENTS = {
            0xFF2F6FED, 0xFF168F72, 0xFF7655C8, 0xFFD97706,
            0xFFD84F78, 0xFF168A9A, 0xFFC94848, 0xFF4B5FAD
    };
    static final int[] BACKGROUNDS = {
            0xFFF3F5F9, 0xFFFFF9F2, 0xFFF2F7F4,
            0xFFF4F1FB, 0xFFEFF7FD, 0xFFF7F6F2
    };
    static final int[] SURFACES = {
            0xFFFFFFFF, 0xFFFFFDF8, 0xFFF7F9FC,
            0xFFF3F7F4, 0xFFF6F3FB, 0xFFEEF3F8
    };

    private ThemeStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int accent(Context ctx) {
        return prefs(ctx).getInt(KEY_ACCENT, DEFAULT_ACCENT);
    }

    static int background(Context ctx) {
        return prefs(ctx).getInt(KEY_BACKGROUND, DEFAULT_BACKGROUND);
    }

    static int surface(Context ctx) {
        return prefs(ctx).getInt(KEY_SURFACE, DEFAULT_SURFACE);
    }

    static int imageAlpha(Context ctx) {
        int v = prefs(ctx).getInt(KEY_IMAGE_ALPHA, 45);
        return Math.max(0, Math.min(100, v));
    }

    static void setAccent(Context ctx, int color) {
        prefs(ctx).edit().putInt(KEY_ACCENT, color).apply();
    }

    static void setBackground(Context ctx, int color) {
        prefs(ctx).edit().putInt(KEY_BACKGROUND, color).apply();
    }

    static void setSurface(Context ctx, int color) {
        prefs(ctx).edit().putInt(KEY_SURFACE, color).apply();
    }

    static void setImageAlpha(Context ctx, int value) {
        prefs(ctx).edit().putInt(KEY_IMAGE_ALPHA,
                Math.max(0, Math.min(100, value))).apply();
    }

    static void reset(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_ACCENT)
                .remove(KEY_BACKGROUND)
                .remove(KEY_SURFACE)
                .apply();
        clearImage(ctx);
    }

    static boolean hasImage(Context ctx) {
        return imageFile(ctx).exists() && imageFile(ctx).length() > 0;
    }

    static Bitmap image(Context ctx) {
        File f = imageFile(ctx);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= screenW) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    static boolean importImage(Context ctx, Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        try {
            Bitmap src = android.provider.MediaStore.Images.Media.getBitmap(
                    ctx.getContentResolver(), uri);
            if (src == null) return false;
            Bitmap scaled = scaleForScreen(ctx, src);
            out = new FileOutputStream(imageFile(ctx));
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out);
            return true;
        } catch (Exception e) {
            clearImage(ctx);
            return false;
        } finally {
            close(in);
            close(out);
        }
    }

    static void clearImage(Context ctx) {
        File f = imageFile(ctx);
        if (f.exists()) f.delete();
    }

    private static Bitmap scaleForScreen(Context ctx, Bitmap src) {
        int max = Math.max(ctx.getResources().getDisplayMetrics().widthPixels,
                ctx.getResources().getDisplayMetrics().heightPixels) * 2;
        if (src.getWidth() <= max && src.getHeight() <= max) return src;
        float ratio = Math.min((float) max / src.getWidth(),
                (float) max / src.getHeight());
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(src.getWidth() * ratio)),
                Math.max(1, Math.round(src.getHeight() * ratio)), true);
    }

    private static File imageFile(Context ctx) {
        return new File(ctx.getFilesDir(), IMAGE_NAME);
    }

    private static void close(InputStream stream) {
        try {
            if (stream != null) stream.close();
        } catch (Exception ignored) {
        }
    }

    private static void close(OutputStream stream) {
        try {
            if (stream != null) stream.close();
        } catch (Exception ignored) {
        }
    }
}
