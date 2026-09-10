package com.wanyuea.neuqclassroom;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 本地缓存：保存上次抓取结果，让用户再次打开 App 时无需登录即可查看。
 *
 * 存储位置：应用私有目录 files/cache.json（卸载 App 即清除，其他应用无法读取）。
 * 7 天后视为过期（与抓取范围一致），过期仍可查看，但会提示刷新。
 */
final class ResultCache {

    private static final String FILE_NAME = "cache.json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;   // 7 天

    private ResultCache() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** 保存：直接存原始 JSON，外层补上 savedAt 时间戳 */
    static void save(Context ctx, String resultJson) {
        try {
            JSONObject o = new JSONObject(resultJson);
            o.put("savedAt", System.currentTimeMillis());
            FileOutputStream fos = new FileOutputStream(file(ctx));
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(o.toString());
            w.flush();
            w.close();
            fos.close();
        } catch (Exception ignored) {
            // 缓存失败不影响主流程
        }
    }

    /** 读取缓存的原始 JSON，无缓存返回 null */
    static String load(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return null;
        try {
            long len = f.length();
            if (len <= 0 || len > 32 * 1024 * 1024) return null;   // 防御异常大文件
            byte[] buf = new byte[(int) len];
            FileInputStream fis = new FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = fis.read(buf, off, buf.length - off)) > 0) off += n;
            fis.close();
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 缓存年龄（毫秒），无缓存返回 -1 */
    static long ageMs(Context ctx) {
        String s = load(ctx);
        if (s == null) return -1;
        try {
            long savedAt = new JSONObject(s).optLong("savedAt", 0);
            if (savedAt <= 0) return -1;
            return System.currentTimeMillis() - savedAt;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 是否已过期（超过 7 天） */
    static boolean isExpired(Context ctx) {
        long age = ageMs(ctx);
        return age < 0 || age > TTL_MS;
    }

    /** 人类可读的「多久之前」 */
    static String ageText(Context ctx) {
        long age = ageMs(ctx);
        if (age < 0) return "未知";
        long min = age / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hour = min / 60;
        if (hour < 24) return hour + " 小时前";
        long day = hour / 24;
        return day + " 天前";
    }

    static void clear(Context ctx) {
        File f = file(ctx);
        if (f.exists()) f.delete();
    }
}
