package com.wanyuea.neuqclassroom;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 课表本地缓存。
 *
 * 课表一学期才变一次，因此只在用户主动「重新导入」或换学期时更新，
 * 平时打开 App 直接读本地，秒开且不需要登录态。
 *
 * 存储位置：应用私有目录 files/schedule.json（卸载 App 即清除）。
 * 30 天后标记为过期仅作提醒，不阻止查看——课表过期通常只是意味着该换学期了。
 */
final class ScheduleCache {

    private static final String FILE_NAME = "schedule.json";
    private static final long TTL_MS = 30L * 24 * 60 * 60 * 1000;   // 30 天

    private ScheduleCache() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** 保存课表 JSON，外层补 savedAt 时间戳 */
    static void save(Context ctx, String scheduleJson) {
        try {
            JSONObject o = new JSONObject(scheduleJson);
            o.put("savedAt", System.currentTimeMillis());
            FileOutputStream fos = new FileOutputStream(file(ctx));
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(o.toString());
            w.flush();
            w.close();
            fos.close();
        } catch (Exception ignored) {
            // 缓存写入失败不应阻断导入流程
        }
    }

    /** 读取缓存的原始 JSON，无缓存或损坏返回 null */
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

    /** 是否已过期（超过 30 天，通常意味着该换学期了） */
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
