package com.wanyuea.neuqclassroom;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 空教室结果的数据整形：把 inject.js / 通道 2 返回的 JSON 摊成「日 → 楼栋 Tab → 时段 → 行 → 教室」。
 *
 * <p>以前这层逻辑是直接拼 HTML 的（{@code buildSlotTable} / {@code styleRoom} 一路 append），
 * 结果就是「分组对不对」只能靠数页面上的行。搬成纯模型之后：
 * <ul>
 *   <li>楼层归并、Tab 归属、三种状态（全天有空 / 新增 / 将要上课）都能单测；</li>
 *   <li>原生视图只负责把这份树摆出来，不再参与业务判断。</li>
 * </ul>
 *
 * <p>不依赖 Android：日期只用 {@link Calendar}，所以测试跑在 JVM 上。
 */
final class RoomTableModel {

    /** 按楼层划分的楼栋：工学馆按楼层分行，其余楼栋整体一行 */
    static final String FLOOR_BUILDING = "工学馆";
    /** 工学馆实际到 8 层（含 803/825 等），取 9 层留余量 */
    static final int MAX_FLOOR = 9;

    static final String[] CAMPUS_TABS = {"工学馆", "本部其它", "南校区"};
    static final String[][] CAMPUS_BUILDINGS = {
            {"工学馆"},
            {"基础楼", "综合实验楼", "地质楼", "管理楼"},
            {"科技楼", "人文楼"}
    };
    static final String[] BUILDING_ORDER = {
            "工学馆", "基础楼", "综合实验楼", "地质楼", "管理楼", "科技楼", "人文楼"
    };

    private RoomTableModel() {}

    /* ==================== 输出结构 ==================== */

    static final class Room {
        final String name;
        /** 全天有空 → 粗体 */
        final boolean allDayFree;
        /** 比上一时段新增 → 下划线 */
        final boolean newInSlot;
        /** 下个时段将上课 → 删除线 */
        final boolean leavingNext;

        Room(String name, boolean allDayFree, boolean newInSlot, boolean leavingNext) {
            this.name = name;
            this.allDayFree = allDayFree;
            this.newInSlot = newInSlot;
            this.leavingNext = leavingNext;
        }
    }

    static final class Row {
        /** "1F" / "其他" / 楼栋名 */
        final String label;
        /** true=楼层样式（窄、灰），false=楼栋样式（宽、深） */
        final boolean floorStyle;
        final List<Room> rooms;

        Row(String label, boolean floorStyle, List<Room> rooms) {
            this.label = label;
            this.floorStyle = floorStyle;
            this.rooms = rooms;
        }
    }

    static final class Slot {
        final String label;
        final boolean highlighted;
        final List<Row> rows;

        Slot(String label, boolean highlighted, List<Row> rows) {
            this.label = label;
            this.highlighted = highlighted;
            this.rows = rows;
        }

        int rowCount() {
            return rows.size();
        }
    }

    static final class Tab {
        final String name;
        final List<Slot> slots;

        Tab(String name, List<Slot> slots) {
            this.name = name;
            this.slots = slots;
        }

        boolean isEmpty() {
            return slots.isEmpty();
        }
    }

    static final class Day {
        String date = "";
        String weekday = "";
        boolean today;
        /** 从课表点过来落在这一天 */
        boolean jumped;
        boolean throttled;
        final List<Tab> tabs = new ArrayList<>();

        boolean isEmpty() {
            return tabs.isEmpty();
        }
    }

    static final class Table {
        String updated = "";
        int rawTotal;
        int keptTotal;
        boolean single;
        boolean fromCache;
        boolean cacheExpired;
        boolean staleDay;
        String cacheAgeText = "";
        String cacheFirstDate = "";
        /** 从课表跳来时点的节次号，用于文案 */
        int highlightTb;
        boolean singleMode;
        final List<Day> days = new ArrayList<>();
    }

    /* ==================== 入口 ==================== */

    static Table build(JSONObject o, String highlightDate, int highlightTb,
                       int highlightSlotIdx, boolean singleMode) {
        Table t = new Table();
        if (o == null) return t;
        t.updated = o.optString("updated", "");
        t.rawTotal = o.optInt("rawTotal", 0);
        t.keptTotal = o.optInt("keptTotal", 0);
        t.single = o.optBoolean("single", false);
        t.fromCache = o.optBoolean("fromCache", false);
        t.cacheExpired = o.optBoolean("cacheExpired", false);
        t.staleDay = o.optBoolean("staleDay", false);
        t.cacheAgeText = o.optString("cacheAgeText", "");
        t.cacheFirstDate = cacheFirstDate(o);
        t.highlightTb = highlightTb;
        t.singleMode = singleMode;

        JSONArray days = o.optJSONArray("days");
        if (days == null) return t;

        String today = dateStr(System.currentTimeMillis());
        for (int i = 0; i < days.length(); i++) {
            JSONObject src = days.optJSONObject(i);
            if (src == null) continue;
            Day day = new Day();
            day.date = src.optString("date", "");
            day.weekday = stripWeekPrefix(src.optString("weekday", ""));
            day.today = day.date.equals(today);
            day.jumped = day.date.equals(highlightDate);
            day.throttled = src.optBoolean("throttle", false);

            JSONArray slots = normalizedSlots(src.optJSONArray("slots"));
            if (slots == null || slots.length() == 0) {
                t.days.add(day);
                continue;
            }

            List<String> ordered = orderedBuildings(slots);
            // 「全天有空」只有拿到整天数据时才成立。单时段视图可能只带回
            // 前/后两组数据，用它们求交集会把所有教室都判成粗体，纯属误导。
            // 非单时段视图（整表）本来就是整天数据，直接算。
            boolean fullDay = !singleMode || slots.length() >= 6;
            Set<String> allDayKeys = fullDay ? allDayFreeKeys(slots) : new HashSet<String>();

            // focus：单时段视图要显示的那一组（-1 = 全显示）。
            // 数据可能带着前/后时段一起回来，用来算下划线和删除线，但只画 focus 那一组。
            int onlyIndex = -1;
            int highlightIndex = day.jumped ? highlightSlotIdx : -1;
            if (singleMode) {
                int focus = o.optInt("focus", -1);
                if (focus >= 0 && focus < slots.length()) {
                    onlyIndex = focus;
                    highlightIndex = focus;
                }
            }

            List<Tab> tabs = buildTabs(slots, ordered, allDayKeys, onlyIndex, highlightIndex);
            for (Tab tab : tabs) {
                if (!tab.slots.isEmpty()) day.tabs.add(tab);
            }
            t.days.add(day);
        }
        return t;
    }

    /* ==================== 分组 ==================== */

    /** 「昼间1-8节」是教务的合并统计项，不参与三种状态标记 */
    static boolean isMergedLabel(String label) {
        return label != null && label.startsWith("昼间");
    }

    /**
     * 把「昼间1-8节」这个合并统计项统一挪到末尾。
     *
     * 它跨度 8 节，夹在 7-8 和 9-10 之间会把 1-2 → 11-12 的自然顺序截断。
     * 这里顺手兼容旧缓存：数据里它是第 5 项，直接按标签重排，
     * 用户升级后打开旧数据看到的也是新顺序，不用等下一次查询。
     */
    static JSONArray normalizedSlots(JSONArray slots) {
        if (slots == null || slots.length() <= 1) return slots;
        JSONArray normal = new JSONArray();
        JSONArray merged = new JSONArray();
        for (int i = 0; i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null) continue;
            if (isMergedLabel(s.optString("label", ""))) merged.put(s);
            else normal.put(s);
        }
        for (int i = 0; i < merged.length(); i++) {
            JSONObject s = merged.optJSONObject(i);
            if (s != null) normal.put(s);
        }
        return normal;
    }

    /** 本日出现过的楼栋，按预设顺序排，未知楼栋追加在后 */
    private static List<String> orderedBuildings(JSONArray slots) {
        Set<String> present = new LinkedHashSet<>();
        for (int s = 0; s < slots.length(); s++) {
            JSONObject slot = slots.optJSONObject(s);
            if (slot == null) continue;
            JSONObject bk = slot.optJSONObject("buildings");
            if (bk == null) continue;
            JSONArray names = bk.names();
            if (names == null) continue;
            for (int b = 0; b < names.length(); b++) {
                present.add(names.optString(b, ""));
            }
        }
        List<String> ordered = new ArrayList<>();
        for (String b : BUILDING_ORDER) if (present.contains(b)) ordered.add(b);
        for (String b : present) if (!ordered.contains(b)) ordered.add(b);
        return ordered;
    }

    /**
     * 楼栋 → 校区 Tab。
     *
     * 只保留本日真正有数据的 Tab；不在预设分组里的楼栋（教务新增了楼）统一塞进「本部其它」。
     */
    private static List<Tab> buildTabs(JSONArray slots, List<String> ordered, Set<String> allDayKeys,
                                       int onlyIndex, int highlightIndex) {
        List<String> tabNames = new ArrayList<>();
        List<List<String>> tabBuildings = new ArrayList<>();

        for (int t = 0; t < CAMPUS_TABS.length; t++) {
            List<String> bs = new ArrayList<>();
            for (String b : CAMPUS_BUILDINGS[t]) if (ordered.contains(b)) bs.add(b);
            if (bs.isEmpty()) continue;
            tabNames.add(CAMPUS_TABS[t]);
            tabBuildings.add(bs);
        }

        Set<String> known = new HashSet<>();
        for (String[] group : CAMPUS_BUILDINGS) for (String b : group) known.add(b);
        for (String b : ordered) {
            if (known.contains(b)) continue;
            int qi = tabNames.indexOf("本部其它");
            if (qi < 0) {
                tabNames.add("本部其它");
                tabBuildings.add(new ArrayList<>());
                qi = tabNames.size() - 1;
            }
            tabBuildings.get(qi).add(b);
        }

        List<Tab> tabs = new ArrayList<>();
        for (int t = 0; t < tabNames.size(); t++) {
            List<Slot> slotList = new ArrayList<>();
            for (int s = 0; s < slots.length(); s++) {
                // 单时段视图：只画 focus 那一组，但状态仍按整段数据算
                if (onlyIndex >= 0 && s != onlyIndex) continue;
                Slot slot = buildSlot(slots, s, tabBuildings.get(t), allDayKeys, s == highlightIndex);
                if (slot != null) slotList.add(slot);
            }
            tabs.add(new Tab(tabNames.get(t), slotList));
        }
        return tabs;
    }

    /** 某个 Tab（一组楼栋）下的某个时段；该时段这组楼栋都没教室时返回 null */
    private static Slot buildSlot(JSONArray slots, int slotIdx, List<String> buildings,
                                  Set<String> allDayKeys, boolean highlighted) {
        JSONObject slotJson = slots.optJSONObject(slotIdx);
        if (slotJson == null) return null;
        String label = slotJson.optString("label", "");
        // 合并项的「上一时段/下一时段」没有意义（它本身横跨 8 节），
        // 所以整块不做状态标记，教室号原样列出。
        boolean merged = isMergedLabel(label);
        // 相邻时段要跳过合并项：「昼间1-8节」排在末尾时，
        // 11-12 节的「下一时段」会被算成它，从而误标删除线。
        int prevIdx = neighbor(slots, slotIdx, -1);
        int nextIdx = neighbor(slots, slotIdx, 1);
        // 显式再判一次「是不是最后一个真实时段」：
        // 即使以后有人改动 neighbor 的跳过规则，最后一节也不会被标「下节将上课」。
        boolean lastReal = slotIdx == lastRealIndex(slots);

        List<Row> rows = new ArrayList<>();
        for (String b : buildings) {
            if (FLOOR_BUILDING.equals(b)) {
                // 工学馆：1..MAX_FLOOR 逐层，无法归层的房间放最后一行
                for (int floor = 1; floor <= MAX_FLOOR; floor++) {
                    Row row = buildRow(slots, slotIdx, prevIdx, nextIdx, lastReal,
                            b, floor, allDayKeys, merged);
                    if (row != null) rows.add(row);
                }
                Row rest = buildRow(slots, slotIdx, prevIdx, nextIdx, lastReal,
                        b, 0, allDayKeys, merged);
                if (rest != null) rows.add(rest);
            } else {
                Row row = buildRow(slots, slotIdx, prevIdx, nextIdx, lastReal,
                        b, -1, allDayKeys, merged);
                if (row != null) rows.add(row);
            }
        }
        if (rows.isEmpty()) return null;

        return new Slot(label, highlighted, rows);
    }

    /** 最后一个「真实时段」的下标；全是合并项时返回 -1 */
    private static int lastRealIndex(JSONArray slots) {
        for (int i = slots.length() - 1; i >= 0; i--) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null) continue;
            if (isMergedLabel(s.optString("label", ""))) continue;
            return i;
        }
        return -1;
    }

    /**
     * 从 from 往前/往后找第一个「真实时段」，跳过「昼间1-8节」这类合并项。
     *
     * @param dir -1 往前，+1 往后；找不到返回 -1
     */
    private static int neighbor(JSONArray slots, int from, int dir) {
        for (int i = from + dir; i >= 0 && i < slots.length(); i += dir) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null) continue;
            if (isMergedLabel(s.optString("label", ""))) continue;
            return i;
        }
        return -1;
    }

    /**
     * 一行。[行头, 楼层样式, 教室列表]，该行在本时段无教室则返回 null。
     *
     * @param floor -1=整栋一行；0=无法归入 1..MAX_FLOOR 的房间；1..MAX_FLOOR=指定楼层
     */
    private static Row buildRow(JSONArray slots, int slotIdx, int prevIdx, int nextIdx,
                                boolean lastReal, String building, int floor,
                                Set<String> allDayKeys, boolean merged) {
        List<String> rooms = new ArrayList<>();
        JSONArray arr = roomsOf(slots, slotIdx, building);
        if (arr != null) {
            for (int k = 0; k < arr.length(); k++) {
                String r = arr.optString(k, "");
                if (r.isEmpty()) continue;
                int f = floorOf(r);
                if (floor > 0 && f != floor) continue;
                if (floor == 0 && f > 0 && f <= MAX_FLOOR) continue;
                rooms.add(r);
            }
        }
        if (rooms.isEmpty()) return null;
        sortRooms(rooms);

        List<Room> out = new ArrayList<>(rooms.size());
        for (String name : rooms) {
            if (merged) {
                out.add(new Room(name, false, false, false));
                continue;
            }
            boolean allDay = allDayKeys.contains(building + "\u0000" + name);
            boolean isNew = prevIdx >= 0 && !containsRoom(slots, prevIdx, building, name);
            boolean leaving = !lastReal && nextIdx >= 0
                    && !containsRoom(slots, nextIdx, building, name);
            out.add(new Room(name, allDay, isNew, leaving));
        }

        String label;
        boolean floorStyle;
        if (floor > 0) {
            label = floor + "F";
            floorStyle = true;
        } else if (floor == 0) {
            label = "其他";
            floorStyle = true;
        } else {
            label = building;
            floorStyle = false;
        }
        return new Row(label, floorStyle, out);
    }

    /* ==================== 状态计算 ==================== */

    /** 在全部时段都出现的教室，键为「楼栋\u0000教室」 */
    private static Set<String> allDayFreeKeys(JSONArray slots) {
        Set<String> buildings = new HashSet<>();
        for (int s = 0; s < slots.length(); s++) {
            JSONObject slot = slots.optJSONObject(s);
            if (slot == null) continue;
            JSONObject bk = slot.optJSONObject("buildings");
            if (bk == null) continue;
            JSONArray names = bk.names();
            if (names == null) continue;
            for (int b = 0; b < names.length(); b++) buildings.add(names.optString(b, ""));
        }

        Set<String> out = new HashSet<>();
        for (String building : buildings) {
            Set<String> common = null;
            for (int s = 0; s < slots.length(); s++) {
                JSONArray arr = roomsOf(slots, s, building);
                Set<String> cur = new HashSet<>();
                if (arr != null) {
                    for (int k = 0; k < arr.length(); k++) {
                        String r = arr.optString(k, "");
                        if (!r.isEmpty()) cur.add(r);
                    }
                }
                if (common == null) common = cur;
                else common.retainAll(cur);
            }
            if (common != null) {
                for (String r : common) out.add(building + "\u0000" + r);
            }
        }
        return out;
    }

    private static boolean containsRoom(JSONArray slots, int idx, String building, String room) {
        JSONArray arr = roomsOf(slots, idx, building);
        if (arr == null) return false;
        for (int k = 0; k < arr.length(); k++) {
            if (room.equals(arr.optString(k, ""))) return true;
        }
        return false;
    }

    static JSONArray roomsOf(JSONArray slots, int idx, String building) {
        JSONObject slot = slots.optJSONObject(idx);
        if (slot == null) return null;
        JSONObject bk = slot.optJSONObject("buildings");
        if (bk == null) return null;
        return bk.optJSONArray(building);
    }

    /* ==================== 小工具 ==================== */

    /** 从房间号推断楼层：4012→4、410→4、41→4，非数字 -1 */
    static int floorOf(String room) {
        StringBuilder digits = new StringBuilder();
        if (room == null) return -1;
        for (int i = 0; i < room.length(); i++) {
            char ch = room.charAt(i);
            if (Character.isDigit(ch)) digits.append(ch);
            else break;
        }
        if (digits.length() == 0) return -1;
        try {
            int n = Integer.parseInt(digits.toString());
            if (digits.length() >= 4) return n / 1000;
            if (digits.length() == 3) return n / 100;
            if (digits.length() == 2) return n / 10;
            return n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 数字优先排序：410 在 508 前，同数字按后缀字母 */
    static void sortRooms(List<String> rooms) {
        rooms.sort((a, b) -> {
            int na = leadingNumber(a), nb = leadingNumber(b);
            if (na != nb) return Integer.compare(na, nb);
            return a.compareTo(b);
        });
    }

    private static int leadingNumber(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 兼容旧缓存：旧通道数据曾存成「周三」，统一去掉前缀 */
    static String stripWeekPrefix(String weekday) {
        if (weekday == null) return "";
        if (weekday.startsWith("周") && weekday.length() > 1) return weekday.substring(1);
        return weekday;
    }

    static String dateStr(long ms) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(ms);
        return String.format(Locale.CHINA, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static String cacheFirstDate(JSONObject o) {
        JSONArray days = o.optJSONArray("days");
        if (days == null || days.length() == 0) return "";
        JSONObject d0 = days.optJSONObject(0);
        return d0 == null ? "" : d0.optString("date", "");
    }
}
