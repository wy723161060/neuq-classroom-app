package com.wanyuea.neuqclassroom;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调课记录（本地调整）。
 *
 * 现实里的课表和教务系统里的课表经常对不上：老师临时把周四的课挪到周六、
 * 换教室、某一周停课。教务系统不一定会及时更新（很多时候压根不会），
 * 而用户需要的是一张「这周我到底该去哪」的课表。
 *
 * 所以这里存的是**纯本地覆盖**：
 *   · 不改教务数据，不回写、不申请，只影响本机显示；
 *   · 按「课表 id + 课程键」匹配，重新导入课表后记录仍然有效；
 *   · 可以只作用某一周（scope=week），也可以每周都生效（scope=all）；
 *   · day <= 0 表示这节课「本周停课」。
 *
 * 存储：应用私有目录 files/adjust.json
 * <pre>
 * { "version": 1, "items": [
 *     { "id": "a1", "sched": "s1", "key": "高等数学|2|3|4", "name": "高等数学",
 *       "scope": "week", "week": 5,
 *       "day": 6, "sb": 3, "se": 4, "room": "工学馆A101",
 *       "sd": 2, "ssb": 3, "sse": 4, "sroom": "工学馆A101", "at": 1757... }
 * ] }
 * </pre>
 */
final class AdjustCache {

    static final String FILE_NAME = "adjust.json";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    /** 作用范围：只这一次（某一周） / 之后每周 */
    static final String SCOPE_WEEK = "week";
    static final String SCOPE_ALL = "all";

    private AdjustCache() {}

    /** 一条调课记录 */
    static final class Adjust {
        String id = "";
        String schedId = "";
        String courseKey = "";
        String name = "";
        String scope = SCOPE_WEEK;
        int week = 1;

        /** 调整后的位置；day <= 0 表示停课 */
        int day = 0;
        int startSection = 0;
        int endSection = 0;
        String room = "";

        /** 原位置，用于「影子」提示与清单展示 */
        int srcDay = 0;
        int srcStart = 0;
        int srcEnd = 0;
        String srcRoom = "";
        long createdAt = 0;

        boolean isCancelled() {
            return day <= 0;
        }

        /** 是否在本周生效 */
        boolean appliesTo(int w) {
            return SCOPE_ALL.equals(scope) || week == w;
        }
    }

    /* ==================== 文件读写 ==================== */

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    private static JSONObject store(Context ctx) {
        File f = file(ctx);
        if (f.exists()) {
            try {
                long len = f.length();
                if (len > 0 && len <= MAX_BYTES) {
                    byte[] buf = new byte[(int) len];
                    FileInputStream fis = new FileInputStream(f);
                    int off = 0, n;
                    while (off < buf.length && (n = fis.read(buf, off, buf.length - off)) > 0) off += n;
                    fis.close();
                    JSONObject o = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
                    if (o.optJSONArray("items") != null) return o;
                }
            } catch (Exception ignored) {
                // 文件损坏，当作没有调课记录
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("version", 1);
            o.put("items", new JSONArray());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void persist(Context ctx, JSONObject store) {
        try {
            FileOutputStream fos = new FileOutputStream(file(ctx));
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(store.toString());
            w.flush();
            w.close();
            fos.close();
        } catch (Exception ignored) {
            // 写入失败不影响课表显示（本次会话内存里已经改过）
        }
    }

    private static Adjust fromJson(JSONObject o) {
        Adjust a = new Adjust();
        a.id = o.optString("id", "");
        a.schedId = o.optString("sched", "");
        a.courseKey = o.optString("key", "");
        a.name = o.optString("name", "");
        a.scope = o.optString("scope", SCOPE_WEEK);
        a.week = o.optInt("week", 1);
        a.day = o.optInt("day", 0);
        a.startSection = o.optInt("sb", 0);
        a.endSection = o.optInt("se", 0);
        a.room = o.optString("room", "");
        a.srcDay = o.optInt("sd", 0);
        a.srcStart = o.optInt("ssb", 0);
        a.srcEnd = o.optInt("sse", 0);
        a.srcRoom = o.optString("sroom", "");
        a.createdAt = o.optLong("at", 0);
        return a;
    }

    private static JSONObject toJson(Adjust a) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", a.id);
        o.put("sched", a.schedId);
        o.put("key", a.courseKey);
        o.put("name", a.name);
        o.put("scope", a.scope);
        o.put("week", a.week);
        o.put("day", a.day);
        o.put("sb", a.startSection);
        o.put("se", a.endSection);
        o.put("room", a.room);
        o.put("sd", a.srcDay);
        o.put("ssb", a.srcStart);
        o.put("sse", a.srcEnd);
        o.put("sroom", a.srcRoom);
        o.put("at", a.createdAt);
        return o;
    }

    /* ==================== 查询 ==================== */

    /** 某张课表的全部调课记录（文件顺序） */
    static List<Adjust> list(Context ctx, String schedId) {
        List<Adjust> out = new ArrayList<>();
        JSONArray items = store(ctx).optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            Adjust a = fromJson(o);
            if (schedId != null && !schedId.isEmpty() && !schedId.equals(a.schedId)) continue;
            out.add(a);
        }
        return out;
    }

    /**
     * 第 week 周实际生效的调课，按课程键去重。
     *
     * 同一门课既有「每周」又有「本周」记录时，本周的优先 ——
     * 用户先设了每周调课，又单独改了这一周，预期显然是后者生效。
     */
    static List<Adjust> forWeek(Context ctx, String schedId, int week) {
        List<Adjust> all = list(ctx, schedId);
        Map<String, Adjust> eff = new LinkedHashMap<>();
        for (Adjust a : all) {
            if (a.appliesTo(week) && SCOPE_ALL.equals(a.scope)) eff.put(a.courseKey, a);
        }
        for (Adjust a : all) {
            if (a.appliesTo(week) && !SCOPE_ALL.equals(a.scope)) eff.put(a.courseKey, a);
        }
        return new ArrayList<>(eff.values());
    }

    /** 这门课有没有任何调课记录（用来决定「撤销调课」按钮要不要亮） */
    static boolean hasCourse(Context ctx, String schedId, String courseKey) {
        for (Adjust a : list(ctx, schedId)) {
            if (a.courseKey.equals(courseKey)) return true;
        }
        return false;
    }

    static int count(Context ctx, String schedId) {
        return list(ctx, schedId).size();
    }

    static long sizeBytes(Context ctx) {
        File f = file(ctx);
        return f.exists() ? f.length() : -1;
    }

    /* ==================== 写入 ==================== */

    /**
     * 新增 / 覆盖一条记录。
     *
     * 同一门课 + 同一作用范围只保留一条：用户改了三次时间，最后只该剩最后那次，
     * 而不是叠三条互相矛盾的记录。
     */
    static void put(Context ctx, Adjust a) {
        if (a.id == null || a.id.isEmpty()) {
            a.id = "a" + System.currentTimeMillis();
        }
        JSONObject store = store(ctx);
        JSONArray items = store.optJSONArray("items");
        if (items == null) {
            items = new JSONArray();
            try {
                store.put("items", items);
            } catch (Exception ignored) {
            }
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            boolean sameTarget = a.schedId.equals(o.optString("sched", ""))
                    && a.courseKey.equals(o.optString("key", ""))
                    && a.scope.equals(o.optString("scope", SCOPE_WEEK))
                    && (SCOPE_ALL.equals(a.scope) || a.week == o.optInt("week", 1));
            if (sameTarget) continue;
            next.put(o);
        }
        try {
            next.put(toJson(a));
            store.put("items", next);
        } catch (Exception ignored) {
        }
        persist(ctx, store);
    }

    static void remove(Context ctx, String id) {
        JSONObject store = store(ctx);
        JSONArray items = store.optJSONArray("items");
        if (items == null) return;
        JSONArray next = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null || id.equals(o.optString("id", ""))) continue;
            next.put(o);
        }
        try {
            store.put("items", next);
        } catch (Exception ignored) {
        }
        persist(ctx, store);
    }

    /** 撤销某门课在某个作用范围上的记录（「仅本周」与「每周」各算一条，互不牵连） */
    static void removeOne(Context ctx, String schedId, String courseKey, String scope, int week) {
        JSONObject store = store(ctx);
        JSONArray items = store.optJSONArray("items");
        if (items == null) return;
        JSONArray next = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            boolean hit = schedId.equals(o.optString("sched", ""))
                    && courseKey.equals(o.optString("key", ""))
                    && scope.equals(o.optString("scope", SCOPE_WEEK))
                    && (SCOPE_ALL.equals(scope) || week == o.optInt("week", 1));
            if (hit) continue;
            next.put(o);
        }
        try {
            store.put("items", next);
        } catch (Exception ignored) {
        }
        persist(ctx, store);
    }

    /** 撤销某门课的全部调课（不分周次、不分作用范围） */
    static void removeCourse(Context ctx, String schedId, String courseKey) {
        JSONObject store = store(ctx);
        JSONArray items = store.optJSONArray("items");
        if (items == null) return;
        JSONArray next = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            if (schedId.equals(o.optString("sched", ""))
                    && courseKey.equals(o.optString("key", ""))) continue;
            next.put(o);
        }
        try {
            store.put("items", next);
        } catch (Exception ignored) {
        }
        persist(ctx, store);
    }

    /** 删除某张课表时，把它的调课记录一起带走，避免残留成孤儿数据 */
    static void removeSched(Context ctx, String schedId) {
        JSONObject store = store(ctx);
        JSONArray items = store.optJSONArray("items");
        if (items == null) return;
        JSONArray next = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null || schedId.equals(o.optString("sched", ""))) continue;
            next.put(o);
        }
        try {
            store.put("items", next);
        } catch (Exception ignored) {
        }
        persist(ctx, store);
    }

    static void clear(Context ctx) {
        File f = file(ctx);
        if (f.exists()) f.delete();
    }
}
