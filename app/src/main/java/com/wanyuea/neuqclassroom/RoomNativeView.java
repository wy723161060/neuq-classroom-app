package com.wanyuea.neuqclassroom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 空闲教室总表（原生）。
 *
 * <p>替代原来的 HTML 表格。层级：日期（可折叠）→ 楼栋 Tab → 时段（可折叠）→ 行（楼层/楼栋）→ 教室。
 *
 * <p>以前这些折叠和 Tab 靠内嵌 JS（{@code TABLE_SCRIPT}）切 class，现在就是
 * {@code setVisibility}；楼层归并、三种状态的判断全部在 {@link RoomTableModel} 里，
 * 这里是纯摆放。
 */
final class RoomNativeView extends LinearLayout {

    interface Listener {
        void onGuideQuery();

        /** 单时段视图顶部的时段快捷切换 */
        void onPickSlot(int slotIdx);
    }

    private static final int TEXT_MAIN = 0xFF1F2430;
    private static final int TEXT_SUB = 0xFF8A93A3;
    private static final int TEXT_HEAD = 0xFF33405C;
    private static final int LINE = 0xFFE6EAF2;
    /** 表格线：对齐 CSS 的 --table-border (#EDF0F5) */
    private static final int TABLE_LINE = 0xFFEDF0F5;

    private final Listener listener;
    private RoomTableModel.Table table;
    private int dayIndex;
    private LinearLayout dayHost;

    RoomNativeView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        // 透明：让页面渐变/自定义背景图透上来，玻璃层才有内容可透
        setBackgroundColor(Color.TRANSPARENT);
    }

    /* ==================== 入口 ==================== */

    void render(RoomTableModel.Table t, String[] slotLabels, int[] slotIndices, int activePos) {
        this.table = t;
        this.dayIndex = pickStartDay(t);

        removeAllViews();
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(10), dp(12), dp(18));

        addView(title("空闲教室总表"));

        StringBuilder info = new StringBuilder("数据实时取自教务系统");
        if (!t.updated.isEmpty()) info.append(" · 更新于 ").append(t.updated);
        addView(sub(info.toString()));

        if (t.single && slotLabels != null && slotLabels.length > 0) {
            addView(slotSwitcher(slotLabels, slotIndices, activePos));
        }

        if (t.rawTotal > 0) {
            addView(sub("已排除实验室、机房、语音室等非自习教室：原始 " + t.rawTotal
                    + " 条 → 保留 " + t.keptTotal + " 条"), matchWrap());
        }

        if (t.fromCache) addView(cacheBanner(t));
        addView(legend());

        dayHost = new LinearLayout(getContext());
        dayHost.setOrientation(VERTICAL);
        addView(dayHost);
        renderCurrentDay();

        addView(sub("数据仅在你已登录的教务会话中读取，不会上传任何信息。"));
    }

    /** 起始日期：优先「今天」，其次「从课表跳来」，都没有就看第一天 */
    private static int pickStartDay(RoomTableModel.Table t) {
        for (int i = 0; i < t.days.size(); i++) {
            if (t.days.get(i).today) return i;
        }
        for (int i = 0; i < t.days.size(); i++) {
            if (t.days.get(i).jumped) return i;
        }
        return 0;
    }

    /** 只画当前这一天 */
    private void renderCurrentDay() {
        if (dayHost == null) return;
        dayHost.removeAllViews();
        if (table == null || table.days.isEmpty()) return;
        if (dayIndex < 0) dayIndex = 0;
        if (dayIndex >= table.days.size()) dayIndex = table.days.size() - 1;

        RoomTableModel.Day day = table.days.get(dayIndex);
        dayHost.addView(dayBar(day), matchWrap());

        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        body.setPadding(0, dp(8), 0, 0);
        if (day.throttled) {
            body.addView(warn("⚠ 该天各时段数量完全相同，可能触发了教务的「请勿过快点击」限流。"
                    + "请稍等几秒后重查。"), matchWrap());
        }
        if (day.isEmpty()) {
            body.addView(emptyLine("这一天没有查到空闲教室"), matchWrap());
        } else {
            body.addView(tabs(day));
        }
        dayHost.addView(body, matchWrap());
    }

    /**
     * 左右滑动换日期。
     *
     * @return 是否真的切了（到头返回 false）
     */
    boolean shiftDay(int delta) {
        if (table == null || table.days.size() <= 1) return false;
        int target = dayIndex + delta;
        if (target < 0 || target >= table.days.size()) return false;
        dayIndex = target;
        renderCurrentDay();
        dayHost.setTranslationX(delta > 0 ? dp(28) : -dp(28));
        dayHost.setAlpha(0.5f);
        dayHost.animate().translationX(0f).alpha(1f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        return true;
    }

    /** 日期条：‹ 日期 [今天] ›，多天时下面还有一排指示点 */
    private View dayBar(final RoomTableModel.Day day) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setPadding(dp(6), dp(8), dp(6), dp(8));

        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xFFFFFFFF);
        barBg.setCornerRadius(dp(14));
        barBg.setStroke(dp(1), 0xFFE7ECF3);
        card.setBackground(barBg);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(dayArrow("‹", -1), new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout mid = new LinearLayout(getContext());
        mid.setOrientation(VERTICAL);
        mid.setGravity(Gravity.CENTER);

        TextView date = new TextView(getContext());
        date.setText(day.date + " 周" + day.weekday);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        date.setTypeface(Typeface.DEFAULT_BOLD);
        date.setTextColor(TEXT_MAIN);
        date.setGravity(Gravity.CENTER);
        mid.addView(date, matchWrap());

        if (day.today || day.jumped) {
            LinearLayout tags = new LinearLayout(getContext());
            tags.setOrientation(HORIZONTAL);
            tags.setGravity(Gravity.CENTER);
            float d = getResources().getDisplayMetrics().density;
            tags.setTranslationY(-2 * d);
            if (day.today) tags.addView(chipTag("今天", 0xFF2F6FED, 0xFFFFFFFF));
            if (day.jumped) tags.addView(chipTag("从课表跳来", 0xFFE3ECF8, 0xFF2F6FED));
            mid.addView(tags, matchWrap());
        }
        row.addView(mid, weightWrap(1f));

        row.addView(dayArrow("›", 1), new LinearLayout.LayoutParams(dp(40), dp(40)));
        card.addView(row, matchWrap());

        if (table != null && table.days.size() > 1) {
            LinearLayout dots = new LinearLayout(getContext());
            dots.setOrientation(HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            for (int i = 0; i < table.days.size(); i++) {
                View dot = new View(getContext());
                GradientDrawable dg = new GradientDrawable();
                dg.setShape(GradientDrawable.OVAL);
                dg.setColor(i == dayIndex ? 0xFF2F6FED : 0xFFD8DEE8);
                dot.setBackground(dg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                dots.addView(dot, lp);
            }
            LinearLayout.LayoutParams dlp = matchWrap();
            dlp.topMargin = dp(6);
            card.addView(dots, dlp);
        }
        return card;
    }

    private TextView dayArrow(String text, final int delta) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        tv.setTextColor(0xFF55617A);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(v -> {
            if (shiftDay(delta)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        });
        return tv;
    }

    /** 还没有数据时的引导页（原来是 emptyRoomHtml） */
    void renderEmpty() {
        removeAllViews();
        setOrientation(VERTICAL);
        setPadding(dp(20), dp(24), dp(20), dp(24));

        addView(title("空闲教室总表"));

        TextView box = new TextView(getContext());
        box.setText("还没有空教室数据\n\n登录教务处后即可查询全校空闲教室，"
                + "查一次会缓存到本机，之后离线也能翻看。");
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        box.setTextColor(TEXT_SUB);
        box.setLineSpacing(dp(4), 1f);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), dp(20), dp(16), dp(20));
        box.setBackground(round(0xFFF7F9FC, dp(12), 0, 0));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(14);
        addView(box, lp);

        TextView btn = new TextView(getContext());
        btn.setText("登录教务处并查询");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(11), dp(16), dp(11));
        btn.setBackground(round(0xFF1B4D8F, dp(10), 0, 0));
        btn.setOnClickListener(v -> {
            if (listener != null) listener.onGuideQuery();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.topMargin = dp(16);
        addView(btn, blp);

        TextView tip = new TextView(getContext());
        tip.setText("也可以从底部「更多 → 教务处登录」进入。数据实时取自教务系统，"
                + "不经过任何第三方服务器。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tip.setTextColor(TEXT_SUB);
        tip.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams tlp = matchWrap();
        tlp.topMargin = dp(18);
        addView(tip, tlp);
    }

    /* ==================== 一天 ==================== */

    /* ==================== 楼栋 Tab ==================== */

    /**
     * 楼栋 Tab：分段控件（segmented control），不是胶囊按钮。
     *
     * 对齐 table.css：整条底 #EEF3FB、按钮等分、激活项白底 + 2dp 蓝色下边线。
     * 之前做成一排圆角胶囊，和原版的观感差得最远。
     */
    private View tabs(final RoomTableModel.Day day) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        // 与上方卡片同宽：之前左右各缩 10dp，两张卡对不齐很突兀
        box.setLayoutParams(matchWrap());

        GradientDrawable tabBg = new GradientDrawable();
        tabBg.setColor(0xFFFFFFFF);
        tabBg.setCornerRadius(dp(14));
        tabBg.setStroke(dp(1), 0xFFE7ECF3);
        box.setBackground(tabBg);
        box.setClipToOutline(true);

        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setBackgroundColor(0xFFEEF3FB);

        final List<View> contents = new ArrayList<>();
        final List<LinearLayout> cells = new ArrayList<>();
        final List<TextView> labels = new ArrayList<>();

        for (int i = 0; i < day.tabs.size(); i++) {
            final RoomTableModel.Tab tab = day.tabs.get(i);

            final LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(VERTICAL);
            cell.setBackgroundColor(i == 0 ? 0xFFFFFFFF : 0x00FFFFFF);

            final TextView label = new TextView(getContext());
            label.setText(tab.name);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(9), 0, dp(9));
            label.setTextColor(i == 0 ? 0xFF1B4D8F : 0xFF667085);
            cell.addView(label, matchWrap());

            final View underline = new View(getContext());
            underline.setBackgroundColor(0xFFBED2EF);
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(2));
            underline.setLayoutParams(ulp);
            underline.setVisibility(i == 0 ? VISIBLE : INVISIBLE);
            cell.addView(underline);

            final LinearLayout content = new LinearLayout(getContext());
            content.setOrientation(VERTICAL);
            content.setPadding(dp(10), 0, dp(10), dp(10));
            for (RoomTableModel.Slot slot : tab.slots) {
                content.addView(slotBlock(slot), matchWrap());
            }
            content.setVisibility(i == 0 ? VISIBLE : GONE);
            contents.add(content);
            cells.add(cell);
            labels.add(label);

            cell.setOnClickListener(v -> {
                for (int k = 0; k < contents.size(); k++) {
                    boolean active = contents.get(k) == content;
                    contents.get(k).setVisibility(active ? VISIBLE : GONE);
                    cells.get(k).setBackgroundColor(active ? 0xFFFFFFFF : 0x00FFFFFF);
                    labels.get(k).setTextColor(active ? 0xFF1B4D8F : 0xFF667085);
                    cells.get(k).getChildAt(1).setVisibility(
                            active ? VISIBLE : INVISIBLE);
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1f);
            bar.addView(cell, lp);

            if (i < day.tabs.size() - 1) {
                View sep = new View(getContext());
                sep.setBackgroundColor(0xFFE1E8F3);
                bar.addView(sep, new LinearLayout.LayoutParams(dp(1),
                        LayoutParams.MATCH_PARENT));
            }
        }

        box.addView(bar, matchWrap());
        for (View c : contents) box.addView(c, matchWrap());
        return box;
    }

    /** 单时段切换条用的胶囊按钮（与楼栋 Tab 是两种控件，别混用） */
    private void styleTabButton(TextView tv, boolean active) {
        tv.setTextColor(active ? Color.WHITE : 0xFF55617A);
        tv.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        if (active) {
            tv.setBackground(round(0xFF1B4D8F, dp(8), 0, 0));
        } else {
            tv.setBackground(null);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFFFFFFFF);
            g.setCornerRadius(dp(10));
            g.setStroke(dp(1), 0xFFDCE3ED);
            tv.setBackground(g);
        }
    }

    /* ==================== 时段 ==================== */

    /** 一个时段：标题 + 一张两列表格（行头 / 教室） */
    private View slotBlock(final RoomTableModel.Slot slot) {
        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(VERTICAL);

        final LinearLayout table = new LinearLayout(getContext());
        table.setOrientation(VERTICAL);
        // 对齐 table.css：普通表格网格，直角、1px #EDF0F5 外框，不用圆角卡片
        GradientDrawable tableBg = new GradientDrawable();
        tableBg.setColor(0xFFFFFFFF);
        tableBg.setStroke(dp(1), TABLE_LINE);
        table.setBackground(tableBg);
        for (int i = 0; i < slot.rows.size(); i++) {
            table.addView(tableRow(slot.rows.get(i), i == slot.rows.size() - 1),
                    matchWrap());
        }

        // 标题：居中、15sp 粗体 #777，前面一个三角（折叠后旋转 -90°）
        final LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, dp(10), 0, dp(3));

        final TextView arrow = new TextView(getContext());
        arrow.setText("▼");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        arrow.setTextColor(slot.highlighted ? 0xFF2F6FED : 0xFF999999);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        alp.rightMargin = dp(5);
        head.addView(arrow, alp);

        TextView label = new TextView(getContext());
        label.setText(slot.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(slot.highlighted ? 0xFF2F6FED : 0xFF777777);
        head.addView(label, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        TextView count = new TextView(getContext());
        count.setText("（" + slot.rowCount() + " 行）");
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        count.setTextColor(0xFFAAAAAB);
        head.addView(count, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        head.setOnClickListener(v -> {
            boolean show = table.getVisibility() != VISIBLE;
            table.setVisibility(show ? VISIBLE : GONE);
            arrow.animate().rotation(show ? 0f : -90f).setDuration(200).start();
        });

        block.addView(head, matchWrap());
        LinearLayout.LayoutParams tlp = matchWrap();
        tlp.bottomMargin = dp(4);
        block.addView(table, tlp);
        return block;
    }

    /**
     * 表格的一行：左侧行头（1F / 楼栋名）+ 右侧教室。
     *
     * 尺寸与对齐全部对齐 table.css：行头 44px、浅底 #FAFBFD、居中加粗；
     * 教室列居中、13px、行高 1.9。
     */
    private View tableRow(RoomTableModel.Row row, boolean last) {
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(VERTICAL);

        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(HORIZONTAL);
        line.setMinimumHeight(dp(34));

        TextView label = new TextView(getContext());
        label.setText(row.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(0xFF171A21);
        label.setGravity(Gravity.CENTER);
        label.setBackgroundColor(0xFFFAFBFD);
        line.addView(label, new LinearLayout.LayoutParams(dp(44), LayoutParams.MATCH_PARENT));

        View divider = new View(getContext());
        divider.setBackgroundColor(TABLE_LINE);
        line.addView(divider, new LinearLayout.LayoutParams(dp(1), LayoutParams.MATCH_PARENT));

        TextView rooms = new TextView(getContext());
        rooms.setText(roomSpans(row.rooms));
        rooms.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        rooms.setTextColor(0xFF171A21);
        rooms.setGravity(Gravity.CENTER);
        // CSS line-height 1.9 ≈ 13px 字号加 9px 行距
        rooms.setLineSpacing(dp(9), 1f);
        rooms.setPadding(dp(6), dp(5), dp(6), dp(5));
        line.addView(rooms, weightWrap(1f));

        wrap.addView(line, matchWrap());
        if (!last) {
            View sep = new View(getContext());
            sep.setBackgroundColor(TABLE_LINE);
            wrap.addView(sep, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));
        }
        return wrap;
    }

    /**
     * 把一行教室拼成可换行的富文本。
     *
     * 三种状态用 Span 表达（粗体 / 下划线 / 删除线），这样整行是一个 TextView，
     * 换行交给系统排版 —— 不再需要自写的 FlowLayout，也不会出现胶囊之间的空隙。
     */
    private CharSequence roomSpans(List<RoomTableModel.Room> rooms) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < rooms.size(); i++) {
            RoomTableModel.Room room = rooms.get(i);
            // 间隔给足 4 个空格（约 13dp）：CSS 的 6px 是建立在等宽字体上的，
            // 系统无衬线字体的空格更窄，两个空格会让 217/308 粘成 217308
            if (i > 0) sb.append("    ");
            int start = sb.length();
            sb.append(room.name);
            int end = sb.length();
            if (room.newInSlot) {
                sb.setSpan(new UnderlineSpan(), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (room.leavingNext) {
                sb.setSpan(new StrikethroughSpan(), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                // CSS：删除线的字是灰的，比正文弱一档
                sb.setSpan(new ForegroundColorSpan(0xFF9A9A9A), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (room.allDayFree) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFF4C4C4C), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    /* ==================== 零件 ==================== */

    private View slotSwitcher(final String[] labels, final int[] indices, int activePos) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(2));

        for (int i = 0; i < labels.length; i++) {
            final int pos = i;
            TextView btn = new TextView(getContext());
            btn.setText(labels[i]);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(10), dp(6), dp(10), dp(6));
            styleTabButton(btn, i == activePos);
            btn.setOnClickListener(v -> {
                if (listener != null && indices != null && pos < indices.length) {
                    listener.onPickSlot(indices[pos]);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(5);
            row.addView(btn, lp);
        }
        return row;
    }

    private View cacheBanner(RoomTableModel.Table t) {
        TextView tv = new TextView(getContext());
        if (t.cacheExpired) {
            tv.setText("数据已过期（" + t.cacheAgeText + "），请刷新");
            tv.setBackground(round(0xFFFFEBEE, dp(8), dp(1), 0xFFF3C6CB));
            tv.setTextColor(0xFFC62828);
        } else if (t.staleDay) {
            tv.setText("这是 " + t.cacheFirstDate + " 的数据，不是今天的 · "
                    + t.cacheAgeText + "抓取");
            tv.setBackground(round(0xFFFFF7EC, dp(8), dp(1), 0xFFF3DDBB));
            tv.setTextColor(0xFF8A5A00);
        } else {
            tv.setText("离线数据 · 上次抓取于 " + t.cacheAgeText);
            tv.setBackground(round(0xFFF2F4F9, dp(8), 0, 0));
            tv.setTextColor(0xFF55617A);
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * 图例：对齐 CSS 的白卡（1px 边 + 14px 圆角 + 轻投影），两行放下三条说明。
     */
    private View legend() {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));

        GradientDrawable legendBg = new GradientDrawable();
        legendBg.setColor(0xFFFFFFFF);
        legendBg.setCornerRadius(dp(14));
        legendBg.setStroke(dp(1), 0xFFE7ECF3);
        box.setBackground(legendBg);

        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(10);
        box.setLayoutParams(lp);

        LinearLayout line1 = new LinearLayout(getContext());
        line1.setOrientation(HORIZONTAL);
        line1.addView(legendItem("粗体 全天有空"));
        line1.addView(legendItem("下划线 比上一时段新增"));

        box.addView(line1);
        box.addView(legendItem("删除线 下个时段将上课"));
        return box;
    }

    private View legendItem(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTextColor(TEXT_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        lp.bottomMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View warn(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(0xFF8A5A00);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        tv.setBackground(round(0xFFFFF7EC, dp(8), dp(1), 0xFFF3DDBB));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View emptyLine(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(TEXT_SUB);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(14), 0, dp(14));
        return tv;
    }

    private View chipTag(String text, int bg, int fg) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        tv.setTextColor(fg);
        tv.setPadding(dp(5), dp(2), dp(5), dp(2));
        tv.setBackground(round(bg, dp(4), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(5);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView title(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(TEXT_MAIN);
        return tv;
    }

    private TextView sub(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(TEXT_SUB);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(0, dp(2), 0, dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
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
