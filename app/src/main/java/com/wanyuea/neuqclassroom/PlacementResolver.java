package com.wanyuea.neuqclassroom;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排课解析：把「课表 JSON + 本地调课记录」折算成某一周每节课实际摆放的位置。
 *
 * 为什么单独成一个类：
 * <ol>
 *   <li>这是全项目最容易改坏的一段 —— v1.1.1 的「跨周调休丢课」就出在这里；</li>
 *   <li>它不碰任何 Android API，可以脱离设备跑 JVM 单测（见 PlacementResolverTest）。</li>
 * </ol>
 *
 * 刻意保持纯函数：所有输入都由调用方传入，不读 SharedPreferences、不写文件、不碰 UI。
 * 这样测试里可以直接构造 JSON 和调课记录，不需要 Robolectric 或真机。
 */
final class PlacementResolver {

    /** 与 MainActivity.PERIODS 同一个值：东秦一天 12 节 */
    static final int PERIODS = 12;

    /** 调课作用范围，取值与 AdjustCache 的同名常量一一对应 */
    static final String SCOPE_WEEK = "week";
    static final String SCOPE_ALL = "all";
    static final String SCOPE_DAY = "day";

    private PlacementResolver() {}

    /**
     * 排课需要的调课字段（AdjustCache.Adjust 的投影）。
     *
     * <p>{@code seq} 是「在调用方传入的那个 list 里的下标」。去重、改序之后它依然指向
     * 原来那条记录，调用方靠它拿回自己的对象（UI 层要读教室、课名等这里用不到的字段）。
     *
     * <p>字段和 AdjustCache.Adjust 保持同名，减少阅读时的来回对照。
     */
    static final class Adjustment {
        final int seq;
        final String courseKey;
        final String scope;
        /** 生效周；整天调休时表示源周 */
        final int week;

        /** 调整后的星期；<= 0 表示停课 */
        final int day;
        final int startSection;
        final int endSection;

        /** 整天调休的目标周，其余情况为 -1 */
        final int targetWeek;

        /** 原位置的星期与节次范围，用于「影子」与调课清单 */
        final int srcDay;
        final int srcStart;
        final int srcEnd;

        /** 课名与「调整后」的教室 —— 渲染卡片时才用得到，算法本身不参与判断 */
        final String name;
        final String room;

        /** 只带算法字段的构造：单测和纯计算用，UI 字段留空 */
        Adjustment(int seq, String courseKey, String scope, int week,
                   int day, int startSection, int endSection, int targetWeek, int srcDay) {
            this(seq, courseKey, "", scope, week, day, startSection, endSection,
                    targetWeek, srcDay, 0, 0, "");
        }

        Adjustment(int seq, String courseKey, String name, String scope, int week,
                   int day, int startSection, int endSection, int targetWeek, int srcDay,
                   int srcStart, int srcEnd, String room) {
            this.seq = seq;
            this.courseKey = courseKey == null ? "" : courseKey;
            this.name = name == null ? "" : name;
            this.scope = scope == null ? SCOPE_WEEK : scope;
            this.week = week;
            this.day = day;
            this.startSection = startSection;
            this.endSection = endSection;
            this.targetWeek = targetWeek;
            this.srcDay = srcDay;
            this.srcStart = srcStart;
            this.srcEnd = srcEnd;
            this.room = room == null ? "" : room;
        }

        boolean isCancelled() {
            return day <= 0;
        }

        boolean isDayMove() {
            return SCOPE_DAY.equals(scope);
        }

        boolean appliesTo(int week) {
            return SCOPE_ALL.equals(scope) || this.week == week;
        }
    }

    /** 一节（或一段连续节次）课在本周的实际位置 */
    static final class Placement {
        final JSONObject course;
        final Adjustment adjust;
        final int day;
        final int start;
        final int end;

        Placement(JSONObject course, Adjustment adjust, int day, int start, int end) {
            this.course = course;
            this.adjust = adjust;
            this.day = day;
            this.start = start;
            this.end = end;
        }

        boolean overlaps(Placement other) {
            return other != null && day == other.day && start <= other.end && other.start <= end;
        }
    }

    /**
     * 第 week 周真正生效的调课，按课程键去重。
     *
     * <p>同一门课既有「每周」又有「本周」记录时，本周的优先 —— 用户先设了每周调课，
     * 又单独改了这一周，预期显然是后者生效。整天调休不参与（它作用于「一整天」，
     * 由 {@link #resolve} 单独处理）。
     */
    static List<Adjustment> effectiveAdjustments(List<Adjustment> all, int week) {
        Map<String, Adjustment> eff = new LinkedHashMap<>();
        if (all == null) return new ArrayList<>();
        for (Adjustment a : all) {
            if (a.isDayMove()) continue;
            if (a.appliesTo(week) && SCOPE_ALL.equals(a.scope)) eff.put(a.courseKey, a);
        }
        for (Adjustment a : all) {
            if (a.isDayMove()) continue;
            if (a.appliesTo(week) && !SCOPE_ALL.equals(a.scope)) eff.put(a.courseKey, a);
        }
        return new ArrayList<>(eff.values());
    }

    /**
     * 汇总某一周实际显示的课程。
     *
     * <p>同时处理单课调课、停课和整天调休 —— 网格、冲突判断和课程详情都复用这一份结果，
     * 避免三个地方各算一遍之后口径不一致。
     *
     * @param courses     课表 JSON 的 courses 数组
     * @param week        目标周次
     * @param adjustments 该课表的**全部**调课记录（含整天调休），去重在这里做
     */
    static List<Placement> resolve(JSONArray courses, int week, List<Adjustment> adjustments) {
        List<Placement> out = new ArrayList<>();
        if (courses == null) return out;
        List<Adjustment> all = adjustments == null ? new ArrayList<>() : adjustments;

        Map<String, Adjustment> adjByKey = new HashMap<>();
        for (Adjustment a : effectiveAdjustments(all, week)) {
            adjByKey.put(a.courseKey, a);
        }

        Map<Integer, Adjustment> dayOut = new HashMap<>();
        List<Adjustment> dayIns = new ArrayList<>();
        for (Adjustment m : all) {
            if (!m.isDayMove()) continue;
            if (m.week == week) dayOut.put(m.srcDay, m);
            if (m.targetWeek == week) dayIns.add(m);
        }

        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null || !inWeek(c.optJSONArray("weeks"), week)) continue;
            Adjustment a = adjByKey.get(courseKey(c));
            if (a != null && a.isCancelled()) continue;
            // 整天调休优先移走原位置；但用户又对这门课做过单节调课时，
            // 以单节调课的目标位置显示。
            if (dayOut.containsKey(c.optInt("day", 0)) && a == null) continue;

            int day = c.optInt("day", 0);
            int st = c.optInt("startSection", 0);
            int en = c.optInt("endSection", st);
            if (a != null) {
                day = a.day;
                st = a.startSection;
                en = a.endSection;
            }
            if (day < 1 || day > 7 || st < 1 || st > PERIODS) continue;
            if (en < st) en = st;
            if (en > PERIODS) en = PERIODS;
            out.add(new Placement(c, a, day, st, en));
        }

        // 整天调休搬入的课程，其 weeks 属于源周，需要单独补进目标周。
        for (Adjustment m : dayIns) {
            for (int i = 0; i < courses.length(); i++) {
                JSONObject c = courses.optJSONObject(i);
                if (c == null || c.optInt("day", 0) != m.srcDay) continue;
                if (!inWeek(c.optJSONArray("weeks"), m.week)) continue;
                // 带单节调课/停课的课主循环已按调整结果摆放，这里不能重复补
                if (adjByKey.get(courseKey(c)) != null) continue;
                int day = m.day;
                int st = c.optInt("startSection", 0);
                int en = c.optInt("endSection", st);
                if (st < 1 || st > PERIODS) continue;
                if (en < st) en = st;
                if (en > PERIODS) en = PERIODS;
                out.add(new Placement(c, m, day, st, en));
            }
        }
        return out;
    }

    /**
     * 同一格或跨节次重叠的课程全部计入冲突数。
     *
     * <p>按天分组后两两比较：只要时间段有交集就算冲突 —— 调课把两门课叠到一起时，
     * 用户需要在详情里看到「还有别的课也在这」，而不只是主卡上的一个角标。
     *
     * <p>课表里同一门课可能被拆成多条连续记录（inject.js 按连续节次拆块），
     * 那属于同一条排课，不算冲突，所以用对象身份排除。
     */
    static Map<Placement, Integer> conflictCounts(List<Placement> placements) {
        Map<Placement, Integer> counts = new HashMap<>();
        if (placements == null || placements.isEmpty()) return counts;

        Map<Integer, List<Placement>> byDay = new LinkedHashMap<>();
        for (Placement p : placements) {
            List<Placement> sameDay = byDay.get(p.day);
            if (sameDay == null) {
                sameDay = new ArrayList<>();
                byDay.put(p.day, sameDay);
            }
            sameDay.add(p);
        }

        for (List<Placement> sameDay : byDay.values()) {
            for (int i = 0; i < sameDay.size(); i++) {
                for (int j = i + 1; j < sameDay.size(); j++) {
                    Placement a = sameDay.get(i);
                    Placement b = sameDay.get(j);
                    if (!a.overlaps(b)) continue;
                    if (a.course == b.course) continue;
                    counts.put(a, counts.getOrDefault(a, 0) + 1);
                    counts.put(b, counts.getOrDefault(b, 0) + 1);
                }
            }
        }
        return counts;
    }

    /** 这门课在第 week 周有没有课；weeks 为空视为每周都有（老课表没这个字段） */
    static boolean inWeek(JSONArray weeks, int week) {
        if (weeks == null || weeks.length() == 0) return true;
        for (int i = 0; i < weeks.length(); i++) {
            if (weeks.optInt(i, -1) == week) return true;
        }
        return false;
    }

    /**
     * 课程的唯一键 —— 把调课记录匹配回课程用。
     *
     * <p>用「课名 + 原星期 + 原起止节」，不用数组下标：重新导入课表后顺序可能变，
     * 下标会串到别的课上；而这四个字段描述的就是同一条排课记录，重导入后依然对得上。
     */
    static String courseKey(JSONObject c) {
        if (c == null) return "";
        int st = c.optInt("startSection", 0);
        return c.optString("name", "") + "|" + c.optInt("day", 0) + "|" + st + "|"
                + c.optInt("endSection", st);
    }
}
