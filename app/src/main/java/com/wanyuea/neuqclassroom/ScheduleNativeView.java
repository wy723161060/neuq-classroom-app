package com.wanyuea.neuqclassroom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 课表周视图（原生）。
 *
 * <p>替代原来的 HTML 渲染。结构是「每列一个纵向 LinearLayout + 固定节次高度」：
 * 一门跨 2 节的课就是一个 2 倍高的卡片，不需要 rowspan，也不需要自绘 Canvas。
 *
 * <p>只负责摆放，不参与排课计算 —— 课程放在哪个格子由 {@link PlacementResolver} 决定，
 * 周一/周日是否显示、今天高亮这些也只读 {@link Data} 里算好的结果。
 */
final class ScheduleNativeView extends LinearLayout {

    interface Listener {
        void onCourseClick(int day, int start, int end);

        void onGhostClick(int day, int start, int end);

        void onFreeClick(int day, int section);

        void onPrevWeek();

        void onNextWeek();

        void onThisWeek();

        void onGuideImport();
    }

    /** 原位置留下的影子 */
    static final class Ghost {
        final int day;
        final int start;
        final int span;
        final String name;
        final String text;
        final boolean cancelled;

        Ghost(int day, int start, int span, String name, String text, boolean cancelled) {
            this.day = day;
            this.start = start;
            this.span = span;
            this.name = name == null ? "" : name;
            this.text = text == null ? "" : text;
            this.cancelled = cancelled;
        }
    }

    static final class Data {
        String title = "";
        String termName = "";
        String updated = "";
        int week = 1;
        long weekMonday = 0L;
        /** 1=周一 … 7=周日；不在本周为 -1 */
        int todayDay = -1;
        boolean showSat = true;
        boolean showSun = true;
        String[] slotStart = new String[PlacementResolver.PERIODS];
        String[] slotEnd = new String[PlacementResolver.PERIODS];
        List<PlacementResolver.Placement> placements = new ArrayList<>();
        List<Ghost> ghosts = new ArrayList<>();
        List<String> dayMoveLines = new ArrayList<>();
        List<String> adjustLines = new ArrayList<>();
        Map<String, String> conflictPrefs = new HashMap<>();
        /** 同一格/跨节次重叠的冲突数，由 MainActivity 传入（与详情面板同一份） */
        Map<PlacementResolver.Placement, Integer> conflicts = new HashMap<>();
        int surfaceColor = Color.WHITE;
        int accentColor = 0xFF1B4D8F;
    }

    private static final int PERIODS = PlacementResolver.PERIODS;
    private static final String[] WD_CN = {"一", "二", "三", "四", "五", "六", "日"};
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 与 schedule.css 的 .c0 ~ .c9 同一套低饱和配色 */
    private static final int[] COURSE_COLORS = {
            0xFFF28B82, 0xFF81C995, 0xFF8AB4F8, 0xFFFDD663, 0xFFC58AF9,
            0xFF78D9D0, 0xFFFFB68B, 0xFFF6A6C1, 0xFFC5E384, 0xFF9FB7D9
    };

    private static final int TEXT_MAIN = 0xFF1F2430;
    private static final int TEXT_SUB = 0xFF8A93A3;
    private static final int TEXT_HEAD = 0xFF33405C;
    private static final int LINE = 0xFFF0F0F4;

    private final Listener listener;
    private Data data;

    private final int periodH = dp(58);
    private final int periodColW = dp(42);
    private final int headerH = dp(54);

    ScheduleNativeView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        // 透明：玻璃卡片要透出页面渐变和自定义背景图，这里不能铺不透明底
        setBackgroundColor(Color.TRANSPARENT);
    }

    /* ==================== 入口 ==================== */

    void render(Data d) {
        this.data = d;
        removeAllViews();
        setOrientation(VERTICAL);
        setBackgroundColor(Color.TRANSPARENT);
        setPadding(dp(12), dp(10), dp(12), dp(18));

        addView(titleView(d));
        addView(subView(d));

        List<Integer> cols = columns(d);
        addView(weekBar(d));

        // 对齐 schedule.css 的 table.grid：白底 + 16px 圆角 + 1px 边 + 轻投影
        LinearLayout gridCard = new LinearLayout(getContext());
        gridCard.setOrientation(VERTICAL);
        // 玻璃而不是纯白：底色（极光背景）要能透上来，否则「液态玻璃」只剩投影
        gridCard.setBackground(GlassStyle.panel(getContext(), 16f, 0xFFFFFFFF, 0xB8));
        gridCard.setElevation(dp(2));
        gridCard.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        gridCard.setClipToOutline(true);
        gridCard.addView(gridHeader(d, cols), matchWrap());
        gridCard.addView(gridBody(d, cols), matchWrap());
        addView(gridCard);
        View summary = adjustSummary(d);
        if (summary != null) addView(summary);
        addView(footTip());
    }

    /** 还没有课表时的引导页（原来是 emptyScheduleHtml） */
    void renderEmpty(String scheduleName) {
        removeAllViews();
        setOrientation(VERTICAL);
        setPadding(dp(20), dp(24), dp(20), dp(24));

        TextView h = new TextView(getContext());
        h.setText("我的课表");
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(TEXT_MAIN);
        addView(h, matchWrap());

        TextView box = new TextView(getContext());
        box.setText("「" + scheduleName + "」还没有内容\n\n登录教务处后即可导入本学期课表，"
                + "导入一次即可长期离线查看。");
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        box.setTextColor(TEXT_SUB);
        box.setLineSpacing(dp(4), 1f);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), dp(20), dp(16), dp(20));
        box.setBackground(GlassStyle.panel(getContext(), 16f, 0xFFFFFFFF, 0xB8));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(14);
        addView(box, lp);

        TextView btn = new TextView(getContext());
        btn.setText("登录教务处并导入课表");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(11), dp(16), dp(11));
        btn.setBackground(round(0xFF1B4D8F, dp(10), 0, 0));
        btn.setOnClickListener(v -> {
            if (listener != null) listener.onGuideImport();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.topMargin = dp(16);
        addView(btn, blp);

        TextView tip = new TextView(getContext());
        tip.setText("也可以从底部「更多 → 教务处登录」先登录。多张课表可用课表页右上角 ⋮ "
                + "添加与切换；课表只从你本人已登录的教务会话读取，保存在手机本地，不会上传。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tip.setTextColor(TEXT_SUB);
        tip.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams tlp = matchWrap();
        tlp.topMargin = dp(18);
        addView(tip, tlp);
    }

    /* ==================== 头部 ==================== */

    private View titleView(Data d) {
        TextView tv = new TextView(getContext());
        tv.setText(d.title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(TEXT_MAIN);
        tv.setPadding(dp(2), dp(2), dp(2), dp(2));
        return tv;
    }

    private View subView(Data d) {
        StringBuilder sb = new StringBuilder();
        if (!d.termName.isEmpty() && !d.termName.equals(d.title)) {
            sb.append(d.termName).append(" · ");
        }
        sb.append("更新于 ").append(d.updated == null ? "" : d.updated);
        TextView tv = new TextView(getContext());
        tv.setText(sb.toString());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(TEXT_SUB);
        tv.setPadding(dp(2), 0, dp(2), dp(12));
        return tv;
    }

    /** 周次条：白卡 + 三个描边按钮，中间「第 N 周」占满剩余宽度（对齐 .weekbar） */
    private View weekBar(Data d) {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(9), dp(10), dp(9));

        bar.setBackground(GlassStyle.panel(getContext(), 14f, 0xFFFFFFFF, 0xB8));
        bar.setElevation(dp(2));
        bar.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        bar.setClipToOutline(true);

        LinearLayout.LayoutParams barLp = matchWrap();
        barLp.bottomMargin = dp(12);
        bar.setLayoutParams(barLp);

        TextView prev = weekButton("‹ 上周", false);
        prev.setOnClickListener(v -> {
            if (listener != null) listener.onPrevWeek();
        });
        bar.addView(prev);

        TextView cur = new TextView(getContext());
        cur.setText("第 " + d.week + " 周");
        cur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        cur.setTypeface(Typeface.DEFAULT_BOLD);
        cur.setTextColor(d.accentColor);
        cur.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams curLp = weightWrap(1f);
        curLp.leftMargin = dp(8);
        curLp.rightMargin = dp(8);
        bar.addView(cur, curLp);

        TextView next = weekButton("下周 ›", false);
        next.setOnClickListener(v -> {
            if (listener != null) listener.onNextWeek();
        });
        bar.addView(next);

        TextView today = weekButton("本周", true);
        today.setOnClickListener(v -> {
            if (listener != null) listener.onThisWeek();
        });
        LinearLayout.LayoutParams todayLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        todayLp.leftMargin = dp(8);
        bar.addView(today, todayLp);
        return bar;
    }

    /** 周次条按钮：白底 + 1px 边框 + 10px 圆角；「本周」用主色描边 */
    private TextView weekButton(String text, boolean accent) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(11), dp(7), dp(11), dp(7));
        tv.setTextColor(accent ? 0xFF2F6FED : 0xFF44506B);

        GradientDrawable g = new GradientDrawable();
        g.setColor(0xB3FFFFFF);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), accent ? 0xFF2F6FED : 0xFFDFE3EA);
        tv.setBackground(g);
        return tv;
    }

    /* ==================== 表头 ==================== */

    private View gridHeader(Data d, List<Integer> cols) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);

        View corner = new View(getContext());
        corner.setBackgroundColor(0x8CF7F8FB);
        row.addView(corner, new LinearLayout.LayoutParams(periodColW, headerH));

        int accentDark = darken(d.accentColor, 0.76f);
        for (int day : cols) {
            boolean today = day == d.todayDay;

            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundColor(0x8CF8FAFD);
            cell.setPadding(dp(6), dp(4), dp(6), dp(4));

            // 「今天」在 CSS 里是内缩的渐变胶囊，不是整格填色
            LinearLayout inner = new LinearLayout(getContext());
            inner.setOrientation(VERTICAL);
            inner.setGravity(Gravity.CENTER);
            if (today) {
                GradientDrawable pill = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{d.accentColor, accentDark});
                pill.setCornerRadius(dp(14));
                inner.setBackground(pill);
            }

            TextView wd = new TextView(getContext());
            wd.setText(WD_CN[day - 1]);
            wd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            wd.setTypeface(Typeface.DEFAULT_BOLD);
            wd.setTextColor(today ? Color.WHITE : TEXT_HEAD);
            wd.setGravity(Gravity.CENTER);

            TextView dt = new TextView(getContext());
            dt.setText(mdStr(d.weekMonday + (day - 1) * DAY_MS));
            dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            dt.setTextColor(today ? 0xFFD4DAE4 : 0xFF9AA2B4);
            dt.setGravity(Gravity.CENTER);

            inner.addView(wd, matchWrap());
            inner.addView(dt, matchWrap());
            if (today) {
                TextView tag = new TextView(getContext());
                tag.setText("今天");
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
                tag.setTypeface(Typeface.DEFAULT_BOLD);
                tag.setTextColor(0xFFFFD17A);
                tag.setGravity(Gravity.CENTER);
                inner.addView(tag, matchWrap());
            }
            cell.addView(inner, matchWrap());
            row.addView(cell, new LinearLayout.LayoutParams(0, headerH, 1f));
        }
        return row;
    }

    /* ==================== 表格主体 ==================== */

    private View gridBody(Data d, List<Integer> cols) {
        int totalH = periodH * PERIODS;

        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(HORIZONTAL);

        body.addView(periodColumn(d), new LinearLayout.LayoutParams(periodColW, totalH));

        for (int day : cols) {
            body.addView(dayColumn(d, day), new LinearLayout.LayoutParams(0, totalH, 1f));
        }
        return body;
    }

    /** 左侧节次列：1…12 + 作息时间 */
    private View periodColumn(Data d) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);

        for (int r = 0; r < PERIODS; r++) {
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(rect(0x99FAFBFD, LINE));

            TextView no = new TextView(getContext());
            no.setText(String.valueOf(r + 1));
            no.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            no.setTypeface(Typeface.DEFAULT_BOLD);
            no.setTextColor(0xFF44506B);
            no.setGravity(Gravity.CENTER);

            TextView st = new TextView(getContext());
            st.setText(nz(textAt(d.slotStart, r)));
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            st.setTextColor(0xFFA3ABBB);
            st.setGravity(Gravity.CENTER);

            TextView et = new TextView(getContext());
            et.setText(nz(textAt(d.slotEnd, r)));
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            et.setTextColor(0xFFA3ABBB);
            et.setGravity(Gravity.CENTER);

            cell.addView(no, matchWrap());
            cell.addView(st, matchWrap());
            cell.addView(et, matchWrap());
            col.addView(cell, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, periodH));
        }
        return col;
    }

    /** 一天一列：按行往下摆，跨节次的卡片一次占掉多个节次的高度 */
    private View dayColumn(Data d, int day) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);

        Map<Integer, PlacementResolver.Placement> atRow = new HashMap<>();
        Map<Integer, Ghost> ghostAt = new HashMap<>();
        for (PlacementResolver.Placement p : d.placements) {
            if (p.day != day) continue;
            PlacementResolver.Placement old = atRow.get(p.start);
            if (old == null || preferred(d, day, p, old)) atRow.put(p.start, p);
        }
        for (Ghost g : d.ghosts) {
            if (g.day == day) ghostAt.put(g.start, g);
        }

        boolean[] covered = new boolean[PERIODS + 2];
        for (int r = 0; r < PERIODS; r++) {
            if (covered[r]) continue;

            PlacementResolver.Placement p = atRow.get(r + 1);
            if (p != null) {
                int span = Math.max(1, Math.min(p.end - p.start + 1, PERIODS - r));
                col.addView(courseCard(d, p, day, span),
                        new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, span * periodH));
                for (int k = r + 1; k < r + span; k++) covered[k] = true;
                continue;
            }

            Ghost g = ghostAt.get(r + 1);
            if (g != null) {
                int span = Math.max(1, Math.min(g.span, PERIODS - r));
                col.addView(ghostCard(g, day),
                        new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, span * periodH));
                for (int k = r + 1; k < r + span; k++) covered[k] = true;
                continue;
            }

            col.addView(freeCell(day, r + 1),
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, periodH));
        }
        return col;
    }

    /** 同一格有多门课时，优先显示用户在详情里选过的那门 */
    private boolean preferred(Data d, int day, PlacementResolver.Placement candidate,
                              PlacementResolver.Placement current) {
        String pref = d.conflictPrefs == null ? null : d.conflictPrefs.get((candidate.start - 1) + "|" + (day - 1));
        return pref != null && pref.equals(PlacementResolver.courseKey(candidate.course));
    }

    private View courseCard(Data d, PlacementResolver.Placement p, int day, int span) {
        String name = p.course.optString("name", "");
        String room = p.adjust != null && !p.adjust.room.isEmpty()
                ? p.adjust.room : p.course.optString("position", "");
        int conflicts = d.conflicts == null ? 0 : d.conflicts.getOrDefault(p, 0);

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(3), dp(4), dp(3), dp(4));

        // 对齐 .course：9px 圆角、铺满格子、文字居中
        Drawable base = GlassStyle.panel(getContext(), 9f,
                COURSE_COLORS[colorIdx(name)], 0xF2);
        if (p.adjust != null) {
            // 调过课的卡片加一圈 2px 橙边（CSS: inset 0 0 0 2px #FFB020）
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(0x00000000);
            ring.setCornerRadius(dp(9));
            ring.setStroke(dp(2), 0xFFFFB020);
            base = new LayerDrawable(new Drawable[]{base, ring});
        }
        box.setBackground(base);

        TextView nm = new TextView(getContext());
        nm.setText(name);
        nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(0xFF243044);
        nm.setGravity(Gravity.CENTER);
        nm.setMaxLines(span >= 2 ? 3 : 2);
        nm.setLineSpacing(dp(1), 1f);
        box.addView(nm, matchWrap());

        if (!room.isEmpty()) {
            TextView rm = new TextView(getContext());
            rm.setText(room);
            rm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            rm.setTextColor(0xC2243044);     // CSS opacity: .76
            rm.setGravity(Gravity.CENTER);
            rm.setMaxLines(2);
            box.addView(rm, matchWrap());
        }

        if (p.adjust != null) box.addView(tag("调", 0xFF4A3000, 0xFFFFB020));
        if (conflicts > 0) box.addView(tag("冲突", 0xFFFFFFFF, 0xFFB3261E));

        box.setOnClickListener(v -> {
            if (listener != null) listener.onCourseClick(day, p.start, p.end);
        });
        return box;
    }

    private View ghostCard(Ghost g, int day) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(dp(3), dp(3), dp(3), dp(3));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x0DFFFFFF);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), g.cancelled ? 0xFFC62828 : 0xFF9AA2B4, dp(3), dp(3));
        box.setBackground(bg);

        TextView nm = new TextView(getContext());
        nm.setText(g.name);
        nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        nm.setTextColor(0xFF8A93A3);
        nm.setMaxLines(2);
        box.addView(nm, matchWrap());

        TextView txt = new TextView(getContext());
        txt.setText(g.text);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        txt.setTextColor(g.cancelled ? 0xFFC62828 : 0xFF9AA2B4);
        txt.setMaxLines(2);
        box.addView(txt, matchWrap());

        box.setOnClickListener(v -> {
            if (listener != null) listener.onGhostClick(day, g.start, g.start + g.span - 1);
        });
        return box;
    }

    /** 空白时段：虚线小框，点一下查这一节的空教室（对齐 .free-slot） */
    private View freeCell(int day, int section) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackgroundColor(0x59FCFCFD);
        cell.setPadding(dp(2), dp(2), dp(2), dp(2));

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setGravity(Gravity.CENTER);
        GradientDrawable dashed = new GradientDrawable();
        dashed.setColor(0x00000000);
        dashed.setCornerRadius(dp(7));
        dashed.setStroke(dp(1), 0xFFDDE3EF, dp(3), dp(2));
        box.setBackground(dashed);

        TextView plus = new TextView(getContext());
        plus.setText("+");
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        plus.setTypeface(Typeface.DEFAULT_BOLD);
        plus.setTextColor(0xFFC3CAD9);
        plus.setGravity(Gravity.CENTER);

        TextView label = new TextView(getContext());
        label.setText("空教室");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        label.setTextColor(0xFFCCD2DE);
        label.setGravity(Gravity.CENTER);

        box.addView(plus, matchWrap());
        box.addView(label, matchWrap());
        cell.addView(box, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        cell.setOnClickListener(v -> {
            if (listener != null) listener.onFreeClick(day, section);
        });
        return cell;
    }

    /* ==================== 调课清单 ==================== */

    private View adjustSummary(Data d) {
        int count = d.dayMoveLines.size() + d.adjustLines.size();
        if (count <= 0) return null;

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(GlassStyle.panel(getContext(), 16f, 0xFFFFF7EC, 0xE0));

        TextView head = new TextView(getContext());
        head.setText("本周调课 · " + count + " 条");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(0xFF8A5A00);
        box.addView(head, matchWrap());

        for (String line : d.dayMoveLines) box.addView(summaryLine("· " + line, 0xFF6B6255));
        for (String line : d.adjustLines) box.addView(summaryLine("· " + stripTags(line), 0xFF6B6255));
        box.addView(summaryLine("在「⋮ → 调课」里可以修改或撤销", 0xFF9A9184));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.setLayoutParams(lp);
        return box;
    }

    private View summaryLine(String text, int color) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(2), 1f);
        return tv;
    }

    /** 清单行里残留的 <b> 标记（原 HTML 用）—— 原生只取文字 */
    private static String stripTags(String s) {
        return s == null ? "" : s.replace("<b>", "").replace("</b>", "");
    }

    private View footTip() {
        TextView tv = new TextView(getContext());
        tv.setText("左右滑动切换周次；点空白时段查这个时间的空闲教室；点课程卡片看详情；"
                + "老师临时挪课 / 换教室，用右上角 ⋮ → 调课 改本地显示。");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTextColor(TEXT_SUB);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(dp(2), dp(12), dp(2), 0);
        return tv;
    }

    /* ==================== 小工具 ==================== */

    private List<Integer> columns(Data d) {
        List<Integer> cols = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            if (day == 6 && !d.showSat) continue;
            if (day == 7 && !d.showSun) continue;
            cols.add(day);
        }
        return cols;
    }

    private static int colorIdx(String name) {
        return ((name == null ? "" : name).hashCode() & 0x7fffffff) % COURSE_COLORS.length;
    }

    private static int darken(int color, float factor) {
        int r = Math.round(Color.red(color) * factor);
        int g = Math.round(Color.green(color) * factor);
        int b = Math.round(Color.blue(color) * factor);
        return Color.rgb(r, g, b);
    }

    private static String mdStr(long ms) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(ms);
        return (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH);
    }

    private static String textAt(String[] arr, int i) {
        return arr != null && i < arr.length && arr[i] != null ? arr[i] : "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 小角标：调 / 冲突 */
    private View tag(String text, int color, int bg) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(3), 0, dp(3), 1);
        tv.setBackground(round(bg, dp(3), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private GradientDrawable round(int color, int radius, int strokeW, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeW > 0) d.setStroke(strokeW, strokeColor);
        return d;
    }

    private GradientDrawable rect(int color, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, weight);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
