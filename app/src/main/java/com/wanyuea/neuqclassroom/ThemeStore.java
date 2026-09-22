package com.wanyuea.neuqclassroom;

import android.content.Context;
import android.content.SharedPreferences;

final class ThemeStore {
    private static final String PREFS = "neuq_prefs";
    private static final String KEY_ACCENT = "theme_accent";
    private static final String KEY_BACKGROUND = "theme_background";
    private static final String KEY_SURFACE = "theme_surface";

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

    static void setAccent(Context ctx, int color) {
        prefs(ctx).edit().putInt(KEY_ACCENT, color).apply();
    }

    static void setBackground(Context ctx, int color) {
        prefs(ctx).edit().putInt(KEY_BACKGROUND, color).apply();
    }

    static void setSurface(Context ctx, int color) {
        prefs(ctx).edit().putInt(KEY_SURFACE, color).apply();
    }

    static void reset(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_ACCENT)
                .remove(KEY_BACKGROUND)
                .remove(KEY_SURFACE)
                .apply();
    }
}
