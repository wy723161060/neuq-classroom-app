package com.wanyuea.neuqclassroom;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 空教室数据整形的单测。
 *
 * 这几条规则以前是散在 HTML 拼接里的，错了只能靠数页面上的行 ——
 * 现在钉成断言：楼层归并、Tab 归属、三种状态、排序、高亮。
 */
public class RoomTableModelTest {

    /* ==================== 构造工具 ==================== */

    private static JSONObject slot(String label, JSONObject buildings) {
        try {
            JSONObject s = new JSONObject();
            s.put("label", label);
            s.put("ok", true);
            s.put("buildings", buildings);
            return s;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject buildings(Object... kv) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i < kv.length; i += 2) {
                String key = (String) kv[i];
                Object val = kv[i + 1];
                JSONArray arr = new JSONArray();
                if (val instanceof String[]) {
                    for (String s : (String[]) val) arr.put(s);
                } else if (val instanceof String) {
                    arr.put(val);
                }
                o.put(key, arr);
            }
            return o;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject result(JSONObject... slots) {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject s : slots) arr.put(s);
            JSONObject day = new JSONObject();
            day.put("date", "2026-10-30");
            day.put("weekday", "五");
            day.put("slots", arr);

            JSONArray days = new JSONArray();
            days.put(day);

            JSONObject o = new JSONObject();
            o.put("days", days);
            o.put("updated", "2026-10-30 12:00");
            o.put("rawTotal", 100);
            o.put("keptTotal", 50);
            return o;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static RoomTableModel.Table table(JSONObject o) {
        return RoomTableModel.build(o, "", 0, -1, false);
    }

    /* ==================== 楼层与楼栋 ==================== */

    @Test
    public void 工学馆按楼层拆行其它楼栋整栋一行() {
        JSONObject o = result(slot("上午1-2节", buildings(
                "工学馆", new String[]{"410", "503"},
                "基础楼", new String[]{"217"})));

        RoomTableModel.Table t = table(o);
        RoomTableModel.Day day = t.days.get(0);
        assertEquals(2, day.tabs.size());           // 工学馆 + 本部其它

        List<RoomTableModel.Row> rows = day.tabs.get(0).slots.get(0).rows;
        assertEquals(2, rows.size());
        assertEquals("4F", rows.get(0).label);
        assertEquals("5F", rows.get(1).label);
        assertTrue(rows.get(0).floorStyle);
        assertEquals("410", rows.get(0).rooms.get(0).name);
    }

    @Test
    public void 非工学馆的教室不进入工学馆Tab() {
        JSONObject o = result(slot("上午1-2节", buildings(
                "工学馆", new String[]{"410"},
                "基础楼", new String[]{"217"})));

        RoomTableModel.Table t = table(o);
        RoomTableModel.Tab gxg = t.days.get(0).tabs.get(0);
        assertEquals("工学馆", gxg.name);
        assertEquals(1, gxg.slots.get(0).rows.size());

        RoomTableModel.Tab other = t.days.get(0).tabs.get(1);
        assertEquals("本部其它", other.name);
        assertEquals("基础楼", other.slots.get(0).rows.get(0).label);
        assertFalse(other.slots.get(0).rows.get(0).floorStyle);
    }

    @Test
    public void 未知楼栋归入本部其它() {
        JSONObject o = result(slot("上午1-2节", buildings("新盖的楼", new String[]{"101"})));

        RoomTableModel.Table t = table(o);
        List<RoomTableModel.Tab> tabs = t.days.get(0).tabs;
        assertEquals(1, tabs.size());
        assertEquals("本部其它", tabs.get(0).name);
        assertEquals("新盖的楼", tabs.get(0).slots.get(0).rows.get(0).label);
    }

    @Test
    public void 无法归层的房间落到其他行() {
        JSONObject o = result(slot("上午1-2节", buildings("工学馆", new String[]{"A101"})));

        List<RoomTableModel.Row> rows = table(o).days.get(0).tabs.get(0).slots.get(0).rows;
        assertEquals(1, rows.size());
        assertEquals("其他", rows.get(0).label);
    }

    @Test
    public void 教室号按数字排序而不是字典序() {
        JSONObject o = result(slot("上午1-2节", buildings("基础楼", new String[]{"508", "410", "217"})));

        List<RoomTableModel.Room> rooms =
                table(o).days.get(0).tabs.get(0).slots.get(0).rows.get(0).rooms;
        assertEquals("217", rooms.get(0).name);
        assertEquals("410", rooms.get(1).name);
        assertEquals("508", rooms.get(2).name);
    }

    /* ==================== 三种状态 ==================== */

    @Test
    public void 全时段都出现的教室标记为全天有空() {
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217"})),
                slot("上午3-4节", buildings("基础楼", new String[]{"217", "308"})));

        RoomTableModel.Table t = table(o);
        RoomTableModel.Room first = t.days.get(0).tabs.get(0).slots.get(0).rows.get(0).rooms.get(0);
        assertEquals("217", first.name);
        assertTrue(first.allDayFree);

        // 308 只出现在第二个时段，不是全天有空
        RoomTableModel.Room second = t.days.get(0).tabs.get(0).slots.get(1).rows.get(0).rooms.get(1);
        assertEquals("308", second.name);
        assertFalse(second.allDayFree);
    }

    @Test
    public void 比上一时段新增的教室带下划线标记() {
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217"})),
                slot("上午3-4节", buildings("基础楼", new String[]{"217", "308"})));

        RoomTableModel.Table t = table(o);
        RoomTableModel.Room added = t.days.get(0).tabs.get(0).slots.get(1).rows.get(0).rooms.get(1);
        assertEquals("308", added.name);
        assertTrue(added.newInSlot);
        assertFalse(added.allDayFree);
    }

    @Test
    public void 下个时段将上课的教室带删除线标记() {
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217", "308"})),
                slot("上午3-4节", buildings("基础楼", new String[]{"217"})));

        RoomTableModel.Table t = table(o);
        List<RoomTableModel.Room> first = t.days.get(0).tabs.get(0).slots.get(0).rows.get(0).rooms;
        RoomTableModel.Room leaving = first.get(1);
        assertEquals("308", leaving.name);
        assertTrue(leaving.leavingNext);
    }

    @Test
    public void 最后一个时段没有下一个时段不算删除线() {
        JSONObject o = result(slot("晚上11-12节", buildings("基础楼", new String[]{"217"})));
        RoomTableModel.Room r = table(o).days.get(0).tabs.get(0).slots.get(0).rows.get(0).rooms.get(0);
        assertFalse(r.leavingNext);
        assertFalse(r.newInSlot);
    }

    /* ==================== 结构 ==================== */

    @Test
    public void 某时段该楼栋没教室时整块省略() {
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217"})),
                slot("上午3-4节", buildings("工学馆", new String[]{"410"})));

        RoomTableModel.Table t = table(o);
        List<RoomTableModel.Tab> tabs = t.days.get(0).tabs;
        // 工学馆 只有第二个时段有数据；本部其它 只有第一个
        assertEquals(1, tabs.get(0).slots.size());
        assertEquals("上午3-4节", tabs.get(0).slots.get(0).label);
        assertEquals(1, tabs.get(1).slots.size());
        assertEquals("上午1-2节", tabs.get(1).slots.get(0).label);
    }

    @Test
    public void 完全没有数据的日子标记为空() {
        JSONObject o = result();
        RoomTableModel.Day day = table(o).days.get(0);
        assertTrue(day.isEmpty());
    }

    @Test
    public void 旧缓存里的周三会去掉周字() {
        JSONObject o = result(slot("上午1-2节", buildings("基础楼", new String[]{"217"})));
        try {
            o.getJSONArray("days").getJSONObject(0).put("weekday", "周三");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals("三", table(o).days.get(0).weekday);
    }

    @Test
    public void 从课表跳来时只高亮命中的那个时段() {
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217"})),
                slot("上午3-4节", buildings("基础楼", new String[]{"217"})));

        RoomTableModel.Table t = RoomTableModel.build(o, "2026-10-30", 3, 1, false);
        RoomTableModel.Day day = t.days.get(0);
        assertTrue(day.jumped);
        assertFalse(day.tabs.get(0).slots.get(0).highlighted);
        assertTrue(day.tabs.get(0).slots.get(1).highlighted);
    }

    /* ==================== 表头信息 ==================== */

    @Test
    public void 统计与缓存信息透传() {
        JSONObject o = result(slot("上午1-2节", buildings("基础楼", new String[]{"217"})));
        try {
            o.put("fromCache", true);
            o.put("cacheExpired", true);
            o.put("cacheAgeText", "2 小时前");
            o.put("cacheFirstDate", "2026-10-29");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        RoomTableModel.Table t = table(o);
        assertEquals(100, t.rawTotal);
        assertEquals(50, t.keptTotal);
        assertTrue(t.fromCache);
        assertTrue(t.cacheExpired);
        assertEquals("2 小时前", t.cacheAgeText);
        assertEquals("2026-10-30", t.cacheFirstDate);   // 取 days[0].date
    }

    @Test
    public void 昼间合并项始终排在最后() {
        // 模拟旧缓存：昼间夹在中间
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217"})),
                slot("昼间1-8节", buildings("基础楼", new String[]{"217"})),
                slot("上午3-4节", buildings("基础楼", new String[]{"217"})));

        List<RoomTableModel.Slot> slots = table(o).days.get(0).tabs.get(0).slots;
        assertEquals(3, slots.size());
        assertEquals("上午1-2节", slots.get(0).label);
        assertEquals("上午3-4节", slots.get(1).label);
        assertEquals("昼间1-8节", slots.get(2).label);
    }

    @Test
    public void 没有昼间时段时顺序原样保留() {
        JSONArray raw = new JSONArray();
        raw.put(slot("上午1-2节", buildings("基础楼", new String[]{"217"})));
        raw.put(slot("上午3-4节", buildings("基础楼", new String[]{"217"})));

        JSONArray out = RoomTableModel.normalizedSlots(raw);
        assertEquals(2, out.length());
        assertEquals("上午1-2节", out.optJSONObject(0).optString("label"));
        assertEquals("上午3-4节", out.optJSONObject(1).optString("label"));
    }

    @Test
    public void 昼间合并项不带任何状态标记() {
        // 217 在三个时段都出现：普通时段应当判为「全天有空」，
        // 但合并项本身横跨 8 节，不能拿相邻时段做比较，三个标记都必须关掉。
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217"})),
                slot("上午3-4节", buildings("基础楼", new String[]{"217", "308"})),
                slot("昼间1-8节", buildings("基础楼", new String[]{"217", "308"})));

        List<RoomTableModel.Slot> slots = table(o).days.get(0).tabs.get(0).slots;
        RoomTableModel.Slot merged = slots.get(slots.size() - 1);
        assertEquals("昼间1-8节", merged.label);

        for (RoomTableModel.Row row : merged.rows) {
            for (RoomTableModel.Room r : row.rooms) {
                assertFalse(r.name + " 不该标全天有空", r.allDayFree);
                assertFalse(r.name + " 不该标新增", r.newInSlot);
                assertFalse(r.name + " 不该标将上课", r.leavingNext);
            }
        }

        // 普通时段仍按原规则标记
        assertTrue(slots.get(0).rows.get(0).rooms.get(0).allDayFree);
    }

    @Test
    public void 晚上11点12节之后没有下一时段() {
        // 昼间1-8节排在末尾，但它不是「11-12 的下一节课」。
        // 308 不在昼间里 —— 如果拿昼间当邻居，308 会被误标删除线。
        JSONObject o = result(
                slot("晚上9-10节", buildings("基础楼", new String[]{"217"})),
                slot("晚上11-12节", buildings("基础楼", new String[]{"217", "308"})),
                slot("昼间1-8节", buildings("基础楼", new String[]{"217"})));

        List<RoomTableModel.Slot> slots = table(o).days.get(0).tabs.get(0).slots;
        RoomTableModel.Room last = slots.get(1).rows.get(0).rooms.get(1);
        assertEquals("308", last.name);
        assertFalse("11-12 是最后一个真实时段，不该有删除线", last.leavingNext);
    }

    @Test
    public void 合并项不作为相邻时段参与标记() {
        // 1-2 后面只剩合并项：308 不该被判为「下个时段将上课」
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217", "308"})),
                slot("昼间1-8节", buildings("基础楼", new String[]{"217"})));

        List<RoomTableModel.Room> rooms =
                table(o).days.get(0).tabs.get(0).slots.get(0).rows.get(0).rooms;
        assertEquals("308", rooms.get(1).name);
        assertFalse(rooms.get(1).leavingNext);
    }

    @Test
    public void 单时段聚焦只显示一组但保留相邻标记() {
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217"})),
                slot("上午3-4节", buildings("基础楼", new String[]{"217", "308"})));
        try {
            o.put("single", true);
            o.put("focus", 1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        RoomTableModel.Table t = RoomTableModel.build(o, "", 0, -1, true);
        List<RoomTableModel.Slot> slots = t.days.get(0).tabs.get(0).slots;
        assertEquals("只画 focus 那一组", 1, slots.size());
        assertEquals("上午3-4节", slots.get(0).label);

        RoomTableModel.Room added = slots.get(0).rows.get(0).rooms.get(1);
        assertEquals("308", added.name);
        assertTrue("上一时段没有它，应标新增", added.newInSlot);
        assertFalse("只有两组数据，不能断言全天有空", added.allDayFree);
    }

    @Test
    public void 单时段结果不会把所有教室标成全天有空() {
        JSONObject o = result(
                slot("上午3-4节", buildings("基础楼", new String[]{"217", "308"})));
        try {
            o.put("single", true);
            o.put("focus", 0);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        RoomTableModel.Room r = RoomTableModel.build(o, "", 0, -1, true)
                .days.get(0).tabs.get(0).slots.get(0).rows.get(0).rooms.get(0);
        assertFalse("只有一组数据时无法判断全天有空", r.allDayFree);
    }

    @Test
    public void 旧顺序缓存里11点12节同样不标删除线() {
        // 旧缓存里「昼间」夹在 7-8 和 9-10 之间，归一化后 11-12 才是最后一个真实时段。
        // 这条对应线上真实场景：升级后直接打开旧数据，不该出现「下节将上课」。
        JSONObject o = result(
                slot("上午1-2节", buildings("基础楼", new String[]{"217"})),
                slot("上午3-4节", buildings("基础楼", new String[]{"217"})),
                slot("下午5-6节", buildings("基础楼", new String[]{"217"})),
                slot("下午7-8节", buildings("基础楼", new String[]{"217"})),
                slot("昼间1-8节", buildings("基础楼", new String[]{"217"})),
                slot("晚上9-10节", buildings("基础楼", new String[]{"217"})),
                slot("晚上11-12节", buildings("基础楼", new String[]{"217", "308"})));

        List<RoomTableModel.Slot> slots = table(o).days.get(0).tabs.get(0).slots;
        assertEquals("昼间1-8节", slots.get(slots.size() - 1).label);

        RoomTableModel.Slot last = null;
        for (RoomTableModel.Slot s : slots) {
            if ("晚上11-12节".equals(s.label)) last = s;
        }
        assertEquals("晚上11-12节", last.label);
        for (RoomTableModel.Room r : last.rows.get(0).rooms) {
            assertFalse(r.name + " 不该标将上课", r.leavingNext);
        }
    }

    /* ==================== 工具 ==================== */

    @Test
    public void 楼层推断覆盖四位三位两位() {
        assertEquals(4, RoomTableModel.floorOf("4012"));
        assertEquals(4, RoomTableModel.floorOf("410"));
        assertEquals(4, RoomTableModel.floorOf("41"));
        assertEquals(8, RoomTableModel.floorOf("803"));
        assertEquals(-1, RoomTableModel.floorOf("A101"));
        assertEquals(-1, RoomTableModel.floorOf(""));
    }

    @Test
    public void 空结果不抛异常() {
        RoomTableModel.Table t = RoomTableModel.build(null, "", 0, -1, false);
        assertNotNull(t);
        assertTrue(t.days.isEmpty());
    }

    @Test
    public void 取不存在的教室数组返回null() {
        JSONArray slots = new JSONArray();
        assertNull(RoomTableModel.roomsOf(slots, 0, "工学馆"));
    }
}
