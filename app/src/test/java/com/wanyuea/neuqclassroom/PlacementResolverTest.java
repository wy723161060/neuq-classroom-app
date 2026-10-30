package com.wanyuea.neuqclassroom;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 排课解析的单测。
 *
 * <p>这类逻辑的 bug 有个共同点：界面看起来「只是少了一节课」，很难靠肉眼发现。
 * v1.1.1 修的「跨周调休丢课」就是这样漏出去的 —— 所以把每个分支都钉成断言，
 * 以后改这段代码，跑一遍就知道有没有碰坏别的情况。
 *
 * <p>不依赖 Android：直接 {@code gradle testDebugUnitTest} 就能跑。
 */
public class PlacementResolverTest {

    /* ==================== 构造工具 ==================== */

    private static JSONObject course(String name, int day, int start, int end, int... weeks) {
        try {
            JSONObject c = new JSONObject();
            c.put("name", name);
            c.put("day", day);
            c.put("startSection", start);
            c.put("endSection", end);
            if (weeks != null && weeks.length > 0) {
                JSONArray arr = new JSONArray();
                for (int w : weeks) arr.put(w);
                c.put("weeks", arr);
            }
            c.put("position", "工学馆101");
            c.put("teacher", "张老师");
            return c;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static JSONArray courses(JSONObject... items) {
        JSONArray arr = new JSONArray();
        for (JSONObject c : items) arr.put(c);
        return arr;
    }

    /** 单节调课 / 停课（scope=week 或 all） */
    private static PlacementResolver.Adjustment adjust(
            int seq, JSONObject target, String scope, int week, int day, int start, int end) {
        return new PlacementResolver.Adjustment(
                seq, PlacementResolver.courseKey(target), scope, week,
                day, start, end, -1, target.optInt("day", 0));
    }

    /** 整天调休：把 srcWeek 的 srcDay 整天搬到 targetWeek 的 day */
    private static PlacementResolver.Adjustment dayMove(
            int seq, int srcWeek, int srcDay, int targetWeek, int day) {
        return new PlacementResolver.Adjustment(
                seq, "", PlacementResolver.SCOPE_DAY, srcWeek,
                day, 0, 0, targetWeek, srcDay);
    }

    private static List<PlacementResolver.Adjustment> adj(
            PlacementResolver.Adjustment... items) {
        return items == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(items));
    }

    /** 断言结果里恰好有一节课，并返回它 */
    private static PlacementResolver.Placement only(List<PlacementResolver.Placement> list) {
        assertEquals("应当恰好解析出 1 节课，实际 " + list.size(), 1, list.size());
        return list.get(0);
    }

    /* ==================== 基础 ==================== */

    @Test
    public void 没有调课时按原位置摆放() {
        JSONObject c = course("高等数学", 2, 3, 4, 1, 2, 3);
        List<PlacementResolver.Placement> out =
                PlacementResolver.resolve(courses(c), 2, adj());

        PlacementResolver.Placement p = only(out);
        assertEquals(2, p.day);
        assertEquals(3, p.start);
        assertEquals(4, p.end);
        assertTrue(p.adjust == null);
    }

    @Test
    public void 不在本周的课不出现() {
        JSONObject c = course("高等数学", 2, 3, 4, 1, 2, 3);
        assertEquals(0, PlacementResolver.resolve(courses(c), 9, adj()).size());
    }

    @Test
    public void 没有周次字段时视为每周都有() {
        JSONObject c = course("体育", 3, 5, 6);
        assertEquals(1, PlacementResolver.resolve(courses(c), 7, adj()).size());
    }

    /* ==================== 单节调课 ==================== */

    @Test
    public void 仅本周调课只影响那一周() {
        JSONObject c = course("线性代数", 4, 1, 2, 5);
        PlacementResolver.Adjustment a =
                adjust(0, c, PlacementResolver.SCOPE_WEEK, 5, 6, 3, 4);

        PlacementResolver.Placement p = only(PlacementResolver.resolve(courses(c), 5, adj(a)));
        assertEquals(6, p.day);
        assertEquals(3, p.start);
        assertEquals(4, p.end);

        // 第 6 周不该受影响（weeks 里本来也没有第 6 周）
        assertEquals(0, PlacementResolver.resolve(courses(c), 6, adj(a)).size());
    }

    @Test
    public void 每周调课对之后的周同样生效() {
        JSONObject c = course("大学英语", 1, 1, 2);
        PlacementResolver.Adjustment a =
                adjust(0, c, PlacementResolver.SCOPE_ALL, 1, 3, 1, 2);

        assertEquals(3, only(PlacementResolver.resolve(courses(c), 1, adj(a))).day);
        assertEquals(3, only(PlacementResolver.resolve(courses(c), 12, adj(a))).day);
    }

    @Test
    public void 本周记录优先于每周记录() {
        JSONObject c = course("计算机网络", 5, 5, 6, 5, 6);
        PlacementResolver.Adjustment all =
                adjust(0, c, PlacementResolver.SCOPE_ALL, 1, 2, 5, 6);       // 每周一
        PlacementResolver.Adjustment oneWeek =
                adjust(1, c, PlacementResolver.SCOPE_WEEK, 5, 4, 5, 6);      // 第 5 周改周四

        assertEquals(4, only(PlacementResolver.resolve(courses(c), 5, adj(all, oneWeek))).day);
        assertEquals(2, only(PlacementResolver.resolve(courses(c), 6, adj(all, oneWeek))).day);
    }

    @Test
    public void 调课只作用于匹配的那一条排课() {
        JSONObject c1 = course("高等数学", 2, 1, 2, 3);
        JSONObject c2 = course("高等数学", 4, 3, 4, 3);
        PlacementResolver.Adjustment a =
                adjust(0, c2, PlacementResolver.SCOPE_WEEK, 3, 5, 3, 4);

        List<PlacementResolver.Placement> out =
                PlacementResolver.resolve(courses(c1, c2), 3, adj(a));
        assertEquals(2, out.size());
        // c1 留在周二，c2 被挪到周五
        assertEquals(2, out.get(0).day);
        assertEquals(5, out.get(1).day);
    }

    /* ==================== 停课 ==================== */

    @Test
    public void 停课记录让课程整节消失() {
        JSONObject c = course("体育", 3, 5, 6, 4);
        PlacementResolver.Adjustment stop = adjust(0, c, PlacementResolver.SCOPE_WEEK, 4, 0, 0, 0);

        assertEquals(0, PlacementResolver.resolve(courses(c), 4, adj(stop)).size());
    }

    /* ==================== 整天调休 ==================== */

    @Test
    public void 同周整天调休只搬一次不重复() {
        JSONObject c = course("专业课", 4, 3, 4, 5);
        PlacementResolver.Adjustment move = dayMove(0, 5, 4, 5, 6);   // 第5周周四 → 第5周周六

        PlacementResolver.Placement p = only(PlacementResolver.resolve(courses(c), 5, adj(move)));
        assertEquals(6, p.day);
        assertEquals(3, p.start);
    }

    @Test
    public void 跨周调休源周清空目标周补上() {
        JSONObject c = course("专业课", 4, 3, 4, 5);
        PlacementResolver.Adjustment move = dayMove(0, 5, 4, 6, 6);   // 第5周周四 → 第6周周六

        // 源周（第 5 周）：原位置必须腾空
        assertEquals(0, PlacementResolver.resolve(courses(c), 5, adj(move)).size());

        // 目标周（第 6 周）：课程要按目标星期出现，节次不变
        PlacementResolver.Placement p = only(PlacementResolver.resolve(courses(c), 6, adj(move)));
        assertEquals(6, p.day);
        assertEquals(3, p.start);
        assertEquals(4, p.end);
    }

    @Test
    public void 跨周调休不影响源周的其它日子() {
        JSONObject thu = course("周四课", 4, 3, 4, 5);
        JSONObject fri = course("周五课", 5, 7, 8, 5);
        PlacementResolver.Adjustment move = dayMove(0, 5, 4, 6, 6);

        List<PlacementResolver.Placement> src =
                PlacementResolver.resolve(courses(thu, fri), 5, adj(move));
        assertEquals(1, src.size());
        assertEquals(5, src.get(0).day);   // 只剩周五那门

        List<PlacementResolver.Placement> dst =
                PlacementResolver.resolve(courses(thu, fri), 6, adj(move));
        assertEquals(1, dst.size());       // 目标周只补搬过来的那一门
        assertEquals(6, dst.get(0).day);
    }

    @Test
    public void 单节调课优先于整天调休() {
        JSONObject c = course("专业课", 4, 3, 4, 5);
        PlacementResolver.Adjustment single =
                adjust(0, c, PlacementResolver.SCOPE_WEEK, 5, 5, 3, 4);   // 单独改到周五
        PlacementResolver.Adjustment move = dayMove(1, 5, 4, 5, 6);      // 整天调休到周六

        // 用户对这门课单独调过 -> 全天调休不能把它搬到周六，也不能重复补
        PlacementResolver.Placement p = only(
                PlacementResolver.resolve(courses(c), 5, adj(single, move)));
        assertEquals(5, p.day);
    }

    /* ==================== 边界 ==================== */

    @Test
    public void 越界的节次被裁剪到最后节() {
        JSONObject c = course("晚课", 1, 11, 12, 1);
        PlacementResolver.Adjustment a =
                adjust(0, c, PlacementResolver.SCOPE_WEEK, 1, 1, 11, 99);

        assertEquals(PlacementResolver.PERIODS, only(
                PlacementResolver.resolve(courses(c), 1, adj(a))).end);
    }

    @Test
    public void 调到不存在的星期会被丢弃() {
        JSONObject c = course("高数", 2, 3, 4, 1);
        PlacementResolver.Adjustment a =
                adjust(0, c, PlacementResolver.SCOPE_WEEK, 1, 9, 3, 4);

        assertEquals(0, PlacementResolver.resolve(courses(c), 1, adj(a)).size());
    }

    @Test
    public void 旧的单文件课表没有课程时返回空表() {
        assertEquals(0, PlacementResolver.resolve(null, 1, adj()).size());
        assertEquals(0, PlacementResolver.resolve(new JSONArray(), 1, adj()).size());
    }

    @Test
    public void 调课记录列表为空时不抛异常() {
        JSONObject c = course("高数", 2, 3, 4, 1);
        assertEquals(1, PlacementResolver.resolve(courses(c), 1, null).size());
        assertEquals(1, PlacementResolver.resolve(courses(c), 1,
                Collections.<PlacementResolver.Adjustment>emptyList()).size());
    }

    /* ==================== 去重规则 ==================== */

    @Test
    public void 去重后整天调休不进入单课列表() {
        JSONObject c = course("专业课", 4, 3, 4, 5);
        PlacementResolver.Adjustment single =
                adjust(0, c, PlacementResolver.SCOPE_WEEK, 5, 5, 3, 4);
        PlacementResolver.Adjustment move = dayMove(1, 5, 4, 6, 6);

        List<PlacementResolver.Adjustment> eff =
                PlacementResolver.effectiveAdjustments(adj(single, move), 5);
        assertEquals(1, eff.size());
        assertTrue(!eff.get(0).isDayMove());
    }

    @Test
    public void 同一门课的多次调课只保留最后一条() {
        JSONObject c = course("高数", 2, 3, 4, 5);
        PlacementResolver.Adjustment first =
                adjust(0, c, PlacementResolver.SCOPE_WEEK, 5, 3, 3, 4);
        PlacementResolver.Adjustment second =
                adjust(1, c, PlacementResolver.SCOPE_WEEK, 5, 6, 3, 4);

        assertEquals(1, PlacementResolver.effectiveAdjustments(adj(first, second), 5).size());
        assertEquals(6, only(PlacementResolver.resolve(courses(c), 5, adj(first, second))).day);
    }

    /* ==================== 键 ==================== */

    @Test
    public void 课程键由课名与原始位置组成() {
        JSONObject c = course("高数(01)", 2, 3, 4, 5);
        assertEquals("高数(01)|2|3|4", PlacementResolver.courseKey(c));
    }
}
