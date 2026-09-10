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
import java.util.List;

/**
 * 课表本地缓存（支持多张课表）。
 *
 * 存储位置：应用私有目录 files/schedules.json，结构为
 * <pre>
 * {
 *   "version": 2,
 *   "current": "s1",
 *   "items": [
 *     { "id": "s1", "name": "2026-2027 秋季学期", "savedAt": 1757..., "data": {课表 JSON} },
 *     { "id": "s2", "name": "下学期", "savedAt": 0 }
 *   ]
 * }
 * </pre>
 *
 * 为什么要多张：同一个人可能同时要对照「自己本学期」「下学期的预备表」
 * 「同学的课表」。早先只有 files/schedule.json 单文件，换学期只能把旧表覆盖掉，
 * 想看回去就得重新登录导入一次。
 *
 * 空槽位（savedAt = 0、没有 data）是合法状态 —— 「添加课表」先建槽位再导入，
 * 名字由用户定，多张表之间的边界才清楚。
 *
 * 兼容：老版本的 files/schedule.json 会在第一次读取时自动迁移成 s1，
 * 迁移成功才删除旧文件，中途失败不丢数据。
 *
 * 30 天后标记为过期仅作提醒，不阻止查看 —— 课表过期通常只是意味着该换学期了。
 */
final class ScheduleCache {

    /** 当前使用的存储文件名（缓存管理/诊断里显示大小都要用它，别再写字面量） */
    static final String FILE_NAME = "schedules.json";
    private static final String LEGACY_FILE = "schedule.json";
    private static final long TTL_MS = 30L * 24 * 60 * 60 * 1000;   // 30 天
    /** 新建槽位的默认名（形如「课表 2」），导入成功后会被真实学期名替换 */
    private static final String DEFAULT_PREFIX = "课表 ";

    private ScheduleCache() {}

    /** 一张课表的元信息（不含内容），够列表展示与切换用 */
    static final class Meta {
        final String id;
        final String name;
        final String termName;
        final int courseCount;
        final long savedAt;

        Meta(String id, String name, String termName, int courseCount, long savedAt) {
            this.id = id;
            this.name = name;
            this.termName = termName;
            this.courseCount = courseCount;
            this.savedAt = savedAt;
        }

        /** 还没导入过内容的空槽位 */
        boolean isEmpty() {
            return savedAt <= 0;
        }
    }

    /* ==================== 文件读写 ==================== */

    private static File storeFile(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    private static File legacyFile(Context ctx) {
        return new File(ctx.getFilesDir(), LEGACY_FILE);
    }

    private static String readFile(File f) {
        if (!f.exists()) return null;
        try {
            long len = f.length();
            if (len <= 0 || len > 64 * 1024 * 1024) return null;   // 防御异常大文件
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

    private static void writeFile(File f, String text) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(text);
            w.flush();
            w.close();
            fos.close();
        } catch (Exception ignored) {
            // 缓存写入失败不应阻断导入流程
        }
    }

    /* ==================== 仓库读取 ==================== */

    private static JSONObject newStore() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", 2);
            o.put("current", "");
            o.put("items", new JSONArray());
        } catch (Exception ignored) {
            // JSONObject.put 对 String/JSONArray 不会抛，这里只是保险
        }
        return o;
    }

    /** 读取整个课表仓库；首次调用会把旧的单文件课表迁移进来 */
    private static JSONObject store(Context ctx) {
        String s = readFile(storeFile(ctx));
        if (s != null) {
            try {
                JSONObject o = new JSONObject(s);
                JSONArray items = o.optJSONArray("items");
                if (items != null && items.length() > 0) return o;
            } catch (Exception ignored) {
                // 文件损坏，走下面的迁移 / 重建
            }
        }
        JSONObject migrated = migrateLegacy(ctx);
        return migrated != null ? migrated : newStore();
    }

    /** 老版本单文件 → 新仓库。成功才删旧文件 */
    private static JSONObject migrateLegacy(Context ctx) {
        File lg = legacyFile(ctx);
        String raw = readFile(lg);
        if (raw == null) return null;
        try {
            JSONObject data = new JSONObject(raw);
            long savedAt = data.optLong("savedAt", System.currentTimeMillis());
            String id = "s1";
            JSONObject it = makeItem(id, nameFor(data, 0));
            it.put("savedAt", savedAt);
            it.put("data", data);

            JSONArray items = new JSONArray();
            items.put(it);
            JSONObject st = newStore();
            st.put("current", id);
            st.put("items", items);
            writeFile(storeFile(ctx), st.toString());
            lg.delete();
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保证仓库里至少有一张课表，且 current 指向真实存在的 id；返回 current。
     *
     * 这个「兜底」让上层不用到处判空：任何时刻 list() 都非空、currentId() 都可用。
     * 注意这里只改内存对象，不落盘 —— 真正写盘的是 save/add/select 这些显式动作。
     */
    private static String ensureCurrent(JSONObject st) {
        JSONArray items = st.optJSONArray("items");
        if (items == null) {
            items = new JSONArray();
            try {
                st.put("items", items);
            } catch (Exception ignored) {
            }
        }
        if (items.length() == 0) {
            JSONObject it = makeItem(newId(items), "我的课表");
            items.put(it);
            try {
                st.put("current", it.optString("id", ""));
            } catch (Exception ignored) {
            }
            return it.optString("id", "");
        }
        String cur = st.optString("current", "");
        if (itemAt(items, cur) == null) {
            JSONObject first = items.optJSONObject(0);
            cur = first == null ? "" : first.optString("id", "");
            try {
                st.put("current", cur);
            } catch (Exception ignored) {
            }
        }
        return cur;
    }

    private static JSONObject makeItem(String id, String name) {
        JSONObject it = new JSONObject();
        try {
            it.put("id", id);
            it.put("name", name);
            it.put("savedAt", 0);
        } catch (Exception ignored) {
        }
        return it;
    }

    /** 生成不与现有项冲突的 id：取已有 sN 的最大 N 再加一 */
    private static String newId(JSONArray items) {
        int max = 0;
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String id = it.optString("id", "");
                if (id.startsWith("s")) {
                    try {
                        max = Math.max(max, Integer.parseInt(id.substring(1)));
                    } catch (Exception ignored) {
                        // 非 sN 形式的 id 不参与编号
                    }
                }
            }
        }
        return "s" + (max + 1);
    }

    private static JSONObject itemAt(JSONArray items, String id) {
        if (items == null || id == null || id.isEmpty()) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && id.equals(it.optString("id", ""))) return it;
        }
        return null;
    }

    /* ==================== 列表 / 切换 ==================== */

    /** 全部课表（按存储顺序），列表展示与切换用 */
    static List<Meta> list(Context ctx) {
        JSONObject st = store(ctx);
        ensureCurrent(st);
        List<Meta> out = new ArrayList<>();
        JSONArray items = st.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            JSONObject data = it.optJSONObject("data");
            int n = 0;
            String term = "";
            if (data != null) {
                JSONArray cs = data.optJSONArray("courses");
                n = cs == null ? 0 : cs.length();
                term = data.optString("termName", "");
            }
            out.add(new Meta(it.optString("id", ""), it.optString("name", "课表"),
                    term, n, it.optLong("savedAt", 0)));
        }
        return out;
    }

    static int count(Context ctx) {
        return list(ctx).size();
    }

    static String currentId(Context ctx) {
        return ensureCurrent(store(ctx));
    }

    static String currentName(Context ctx) {
        JSONObject st = store(ctx);
        String cur = ensureCurrent(st);
        JSONObject it = itemAt(st.optJSONArray("items"), cur);
        return it == null ? "课表" : it.optString("name", "课表");
    }

    static void select(Context ctx, String id) {
        JSONObject st = store(ctx);
        ensureCurrent(st);
        if (itemAt(st.optJSONArray("items"), id) == null) return;
        try {
            st.put("current", id);
        } catch (Exception ignored) {
        }
        writeFile(storeFile(ctx), st.toString());
    }

    /**
     * 新建一张空课表并设为当前，返回其 id。
     *
     * name 为空时自动按序号命名（课表 2、课表 3…）。
     */
    static String add(Context ctx, String name) {
        JSONObject st = store(ctx);
        ensureCurrent(st);
        JSONArray items = st.optJSONArray("items");
        if (items == null) {
            items = new JSONArray();
            try {
                st.put("items", items);
            } catch (Exception ignored) {
            }
        }
        String id = newId(items);
        String n = (name == null || name.trim().isEmpty())
                ? DEFAULT_PREFIX + (items.length() + 1) : name.trim();
        JSONObject it = makeItem(id, n);
        items.put(it);
        try {
            st.put("current", id);
        } catch (Exception ignored) {
        }
        writeFile(storeFile(ctx), st.toString());
        return id;
    }

    /** 删除一张课表；删的是当前表则自动切到剩下第一张。只剩一张时拒绝删除 */
    static boolean remove(Context ctx, String id) {
        JSONObject st = store(ctx);
        ensureCurrent(st);
        JSONArray items = st.optJSONArray("items");
        if (items == null || items.length() <= 1) return false;
        if (itemAt(items, id) == null) return false;

        JSONArray next = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || id.equals(it.optString("id", ""))) continue;
            next.put(it);
        }
        try {
            st.put("items", next);
            if (id.equals(st.optString("current", ""))) {
                JSONObject first = next.optJSONObject(0);
                st.put("current", first == null ? "" : first.optString("id", ""));
            }
        } catch (Exception ignored) {
        }
        writeFile(storeFile(ctx), st.toString());
        return true;
    }

    static void rename(Context ctx, String id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        JSONObject st = store(ctx);
        ensureCurrent(st);
        JSONArray items = st.optJSONArray("items");
        JSONObject it = itemAt(items, id);
        if (it == null) return;
        try {
            it.put("name", name.trim());
        } catch (Exception ignored) {
        }
        writeFile(storeFile(ctx), st.toString());
    }

    /* ==================== 内容读写 ==================== */

    /**
     * 保存课表 JSON 到「当前课表」。
     *
     * 名字还是默认的（我的课表 / 课表 N）时，顺手用真实学期名替换 ——
     * 用户在列表里看到「2026-2027 秋季学期」比看到「课表 2」有用得多。
     */
    static void save(Context ctx, String scheduleJson) {
        try {
            JSONObject data = new JSONObject(scheduleJson);
            long now = System.currentTimeMillis();
            data.put("savedAt", now);   // 兼容按 savedAt 计算年龄的旧读取方

            JSONObject st = store(ctx);
            String cur = ensureCurrent(st);
            JSONObject it = itemAt(st.optJSONArray("items"), cur);
            if (it == null) return;
            it.put("data", data);
            it.put("savedAt", now);
            String term = data.optString("termName", "").trim();
            if (!term.isEmpty() && isDefaultName(it.optString("name", ""))) it.put("name", term);
            st.put("items", st.optJSONArray("items"));
            writeFile(storeFile(ctx), st.toString());
        } catch (Exception ignored) {
            // 缓存写入失败不应阻断导入流程
        }
    }

    /** 当前课表的原始 JSON（外层补 savedAt），没有内容返回 null */
    static String load(Context ctx) {
        JSONObject st = store(ctx);
        String cur = ensureCurrent(st);
        JSONObject it = itemAt(st.optJSONArray("items"), cur);
        if (it == null) return null;
        JSONObject data = it.optJSONObject("data");
        if (data == null) return null;
        try {
            data.put("savedAt", it.optLong("savedAt", 0));
        } catch (Exception ignored) {
        }
        return data.toString();
    }

    /** 缓存年龄（毫秒），当前课表为空返回 -1 */
    static long ageMs(Context ctx) {
        JSONObject st = store(ctx);
        String cur = ensureCurrent(st);
        JSONObject it = itemAt(st.optJSONArray("items"), cur);
        if (it == null) return -1;
        long savedAt = it.optLong("savedAt", 0);
        return savedAt <= 0 ? -1 : System.currentTimeMillis() - savedAt;
    }

    /** 是否已过期（超过 30 天，通常意味着该换学期了） */
    static boolean isExpired(Context ctx) {
        long age = ageMs(ctx);
        return age < 0 || age > TTL_MS;
    }

    static String ageText(Context ctx) {
        return ageText(ageMs(ctx));
    }

    /** 人类可读的「多久之前」；入参为 -1 时表示从未导入 */
    static String ageText(long age) {
        if (age < 0) return "从未导入";
        long min = age / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hour = min / 60;
        if (hour < 24) return hour + " 小时前";
        long day = hour / 24;
        return day + " 天前";
    }

    /** 仓库文件字节数，不存在返回 -1 */
    static long sizeBytes(Context ctx) {
        store(ctx);   // 触发一次性迁移，避免把旧文件算漏
        File f = storeFile(ctx);
        return f.exists() ? f.length() : -1;
    }

    /** 只清空「当前课表」的内容，保留槽位与名字（用于单张表重新导入前的重置） */
    static void clearCurrent(Context ctx) {
        JSONObject st = store(ctx);
        String cur = ensureCurrent(st);
        JSONObject it = itemAt(st.optJSONArray("items"), cur);
        if (it == null) return;
        try {
            it.remove("data");
            it.put("savedAt", 0);
        } catch (Exception ignored) {
        }
        writeFile(storeFile(ctx), st.toString());
    }

    /** 清空全部课表（含槽位定义），新旧两个文件都删干净 */
    static void clear(Context ctx) {
        File f = storeFile(ctx);
        if (f.exists()) f.delete();
        File l = legacyFile(ctx);
        if (l.exists()) l.delete();
    }

    /* ==================== 小工具 ==================== */

    /** 「我的课表」「课表 3」这类自动名 —— 导入后可以被真实学期名替换 */
    private static boolean isDefaultName(String name) {
        if (name == null) return true;
        String t = name.trim();
        if (t.isEmpty() || "我的课表".equals(t)) return true;
        if (!t.startsWith(DEFAULT_PREFIX)) return false;
        String num = t.substring(DEFAULT_PREFIX.length());
        if (num.isEmpty()) return false;
        for (int i = 0; i < num.length(); i++) {
            if (!Character.isDigit(num.charAt(i))) return false;
        }
        return true;
    }

    /** 迁移旧文件时的默认名：优先用学期名 */
    private static String nameFor(JSONObject data, int index) {
        String term = data == null ? "" : data.optString("termName", "").trim();
        return term.isEmpty() ? DEFAULT_PREFIX + (index + 1) : term;
    }
}
