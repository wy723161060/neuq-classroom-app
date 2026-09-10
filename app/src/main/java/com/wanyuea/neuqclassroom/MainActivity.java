package com.wanyuea.neuqclassroom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String HOME_URL =
            "https://vpn.neuq.edu.cn/http/77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b/eams/homeExt.action";
    private static final String EAMS_BASE =
            "https://vpn.neuq.edu.cn/http/77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b/eams/";
    private boolean pendingQuery = false;
    private int queryDays = 1;
    private int queryStartOffset = 0;   // 起始日相对今天的天数（0=今天，1=明天）
    private int queryGap = 1000;

    private WebView loginView;
    private WebView resultView;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button btnQuery;
    private Button btnBack;
    private Button btnRefreshData;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String injectJs = "";
    private String css = "";
    private String scheduleCss = "";
    private boolean autoLoginActive = false;

    /* ---------- 底部 Tab ---------- */
    private static final int TAB_CLASSROOM = 0;
    private static final int TAB_SCHEDULE = 1;
    private int currentTab = TAB_SCHEDULE;   // 课表是主页面
    private LinearLayout tabClassroom;
    private LinearLayout tabSchedule;
    private View indClassroom;
    private View indSchedule;
    private TextView tvClassroom;
    private TextView tvSchedule;
    private Button btnImport;
    private Button btnReimport;

    /* ---------- 顶栏按钮状态机 ----------
       所有按钮显隐只由 applyUi(state) 一处决定。
       以前是 30 处散落的 setVisibility，漏改一处就会出 bug
       （比如返回键失效、空教室页残留课表按钮）。 */

    /** 顶栏状态：决定哪些按钮可见 */
    private enum UiState {
        LOGIN,          // 教务 WebView 可见，等用户登录
        ROOM_EMPTY,     // 空教室页，还没有数据 → 查空教室
        ROOM_DATA,      // 空教室页，有数据 → 刷新数据
        SCHEDULE_EMPTY, // 课表页，还没导入 → 导入课表
        SCHEDULE_DATA,  // 课表页，有课表 → 重新导入
        BUSY            // 任意页面正在查询/抓取
    }
    private UiState uiState = UiState.SCHEDULE_EMPTY;

    /* ---------- 课表状态 ---------- */
    private JSONObject scheduleData;        // 当前课表：courses / timeSlots / semesterId
    private String lastDiagText = "";       // 最近一次导入失败的诊断信息，供复制排查
    private int weekOffset = 0;             // 用户在周次切换条上的手动偏移
    private boolean pendingTerms = false;   // 等待抓取学期列表
    private boolean pendingSchedule = false;// 等待抓取课表
    private String pendingSemesterId = "";
    private String pendingTermName = "";
    private boolean pendingSingle = false;  // 等待单时段查询（课表 → 空教室）
    private String singleDate = "";
    private String singleLabel = "";
    private int singleTb = 1;
    private int singleTe = 2;
    private String highlightDate = "";      // 从课表跳过来时，要高亮的那一天
    private int highlightTb = 0;            // 高亮的起始节次（0=不高亮）

    private static final String PREFS = "neuq_prefs";
    private static final String KEY_TERM_START = "termStart_";    // 第 1 周周一，毫秒

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loginView = findViewById(R.id.loginView);
        resultView = findViewById(R.id.resultView);
        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);
        btnQuery = findViewById(R.id.btnQuery);
        btnBack = findViewById(R.id.btnBack);
        btnRefreshData = findViewById(R.id.btnRefreshData);
        btnImport = findViewById(R.id.btnImport);
        btnReimport = findViewById(R.id.btnReimport);
        tabClassroom = findViewById(R.id.tabClassroom);
        tabSchedule = findViewById(R.id.tabSchedule);
        indClassroom = findViewById(R.id.indClassroom);
        indSchedule = findViewById(R.id.indSchedule);
        tvClassroom = findViewById(R.id.tvClassroom);
        tvSchedule = findViewById(R.id.tvSchedule);

        injectJs = readAsset("inject.js");
        css = readAsset("table.css");
        scheduleCss = readAsset("schedule.css");

        setupWebView(loginView);
        setupWebView(resultView);
        resultView.addJavascriptInterface(new Bridge(), "AndroidResultHost");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(loginView, true);
        // 持久化 Cookie（含 WebVPN / CAS 会话票据），下次启动仍是登录态
        cm.setAcceptThirdPartyCookies(resultView, true);
        cm.flush();

        btnQuery.setOnClickListener(v -> askDays());
        btnBack.setOnClickListener(v -> showLogin());
        btnRefreshData.setOnClickListener(v -> {
            showLogin();
            askDays();
        });
        btnImport.setOnClickListener(v -> startScheduleImport());
        btnReimport.setOnClickListener(v -> startScheduleImport());
        tabClassroom.setOnClickListener(v -> switchTab(TAB_CLASSROOM));
        tabSchedule.setOnClickListener(v -> switchTab(TAB_SCHEDULE));

        loginView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.contains("vpn.neuq.edu.cn")) {
                    // 页面已就绪，把会话 Cookie 落盘
                    CookieManager.getInstance().flush();
                }
                if (!(pendingQuery || pendingTerms || pendingSchedule || pendingSingle)) return;
                // 若被重定向到登录页（会话过期），不要注入，留在页面让用户登录
                if (url == null || url.indexOf("/eams/") < 0) {
                    pendingQuery = pendingTerms = pendingSchedule = pendingSingle = false;
                    progressBar.setVisibility(View.GONE);
                    setBusy(false);
                    statusText.setText("会话已过期，请先登录教务再操作");
                    Toast.makeText(MainActivity.this, "请先登录教务系统", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (pendingTerms) {
                    pendingTerms = false;
                    setBusy(true);
                    statusText.setText("正在读取学期列表…");
                    view.evaluateJavascript(injectJs, null);
                    view.evaluateJavascript("window.nqScheduleTerms();", null);
                    return;
                }
                if (pendingSchedule) {
                    pendingSchedule = false;
                    setBusy(true);
                    statusText.setText("正在抓取课表…");
                    view.evaluateJavascript(injectJs, null);
                    view.evaluateJavascript("window.nqSchedule('" + pendingSemesterId + "','"
                            + pendingTermName.replace("'", "") + "');", null);
                    return;
                }
                if (pendingSingle) {
                    pendingSingle = false;
                    setBusy(true);
                    statusText.setText("正在查询 " + singleLabel + " 的空教室…");
                    view.evaluateJavascript(injectJs, null);
                    view.evaluateJavascript("window.nqFetchSlot('" + singleDate + "',"
                            + singleTb + "," + singleTe + ",'"
                            + singleLabel.replace("'", "") + "');", null);
                    return;
                }
                pendingQuery = false;
                statusText.setText("正在查询 " + queryDays + " 天 × 7 个时段…");
                view.evaluateJavascript(injectJs, null);
                view.evaluateJavascript("window.nqFetch(" + queryDays + ","
                        + queryGap + "," + queryStartOffset + ");", null);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                autoLoginActive = url != null && url.contains("vpn.neuq.edu.cn");
            }
        });

        // 尝试自动登录（复用已保存的 Cookie）
        tryAutoLoginThenLoad();
    }

    /**
     * 启动流程（课表为主页面）：
     *  1. 读课表缓存 → 有则立刻渲染「本周」课表，无需联网、无需登录
     *  2. 无课表 → 显示空态，引导点「导入课表」
     *  3. 同时后台加载教务页，让 Cookie 有机会自动续期
     *
     * 注意：每次启动都把 weekOffset 归零 —— 上次翻到第 5 周是临时查看行为，
     * 重进 App 应该回到本周。用户翻周只影响当次会话，不跨启动持久化。
     */
    private void tryAutoLoginThenLoad() {
        String sched = ScheduleCache.load(this);
        if (sched != null) {
            try {
                JSONObject s = new JSONObject(sched);
                if (s.optBoolean("ok", false) && s.has("courses")) {
                    scheduleData = s;
                    weekOffset = 0;          // 默认显示本周
                }
            } catch (Exception ignored) {
                // 缓存损坏，当作没有课表处理
            }
        }

        if (scheduleData != null) {
            renderSchedule();
            statusText.setText("第 " + currentWeek() + " 周 · 课表来自本地缓存");
        } else {
            showScheduleHtml(emptyScheduleHtml());
            applyUi(UiState.SCHEDULE_EMPTY);
            statusText.setText("还没有课表，点「导入课表」");
        }

        // 后台加载登录页，让 Cookie 有机会自动续期
        loginView.loadUrl(HOME_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (view == loginView) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                }
            }
        });
        if (wv == loginView) {
            wv.addJavascriptInterface(new Bridge(), "Android");
        }
    }

    private void askDays() {
        // 起始日 → 连续天数。选「明天」就是明天起连着 7 天，
        // 因为教务接口只支持「某天 + 连续 N 天」，不能直接跳着查本周剩余几天。
        final String[] items = {
                "今天（1 天，约 10 秒）",
                "明天起 7 天（约 60 秒）",
                "今天起 7 天（约 60 秒）"
        };
        final int[] starts = {0, 1, 0};
        final int[] days = {1, 7, 7};
        new AlertDialog.Builder(this)
                .setTitle("查询范围")
                .setItems(items, (dialog, which) -> startQuery(starts[which], days[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void startQuery(int startOffsetDays, int days) {
        queryStartOffset = startOffsetDays;
        queryDays = days;
        highlightDate = "";        // 手动查的，不带课表高亮
        highlightTb = 0;
        statusText.setText("正在打开教务查询页…");
        setBusy(true);
        progressBar.setProgress(0);
        // 先加载教务的空闲教室查询页（与浏览器操作一致，确保会话上下文正确），
        // 页面加载完成后在 onPageFinished 里注入脚本抓取
        pendingQuery = true;
        loginView.loadUrl(EAMS_BASE + "classroom/apply/free!search.action");
    }

    /**
     * 顶栏按钮显隐的唯一出口。
     *
     * 调用方只管声明「现在处于什么状态」，不需要知道有哪些按钮、谁该隐藏。
     * 想加按钮/改规则，只改这个方法。
     */
    private void applyUi(UiState state) {
        uiState = state;
        boolean room = (currentTab == TAB_CLASSROOM);

        // 各状态下的按钮可见性
        boolean vQuery = false, vBack = false, vRefreshData = false;
        boolean vImport = false, vReimport = false;

        switch (state) {
            case LOGIN:
                // 正看着教务网页：只给「查空教室」（课表 Tab 下给「导入课表」）
                if (room) vQuery = true; else vImport = true;
                break;
            case ROOM_EMPTY:
                vQuery = true;
                break;
            case ROOM_DATA:
                vRefreshData = true;
                break;
            case SCHEDULE_EMPTY:
                vImport = true;
                break;
            case SCHEDULE_DATA:
                vReimport = true;
                break;
            case BUSY:
                // 查询中：空教室页保留按钮位置（禁用态），课表页全部隐藏
                if (room) vQuery = true;
                break;
        }

        btnQuery.setVisibility(vQuery ? View.VISIBLE : View.GONE);
        btnBack.setVisibility(vBack ? View.VISIBLE : View.GONE);
        btnRefreshData.setVisibility(vRefreshData ? View.VISIBLE : View.GONE);
        btnImport.setVisibility(vImport ? View.VISIBLE : View.GONE);
        btnReimport.setVisibility(vReimport ? View.VISIBLE : View.GONE);

        // 忙碌时禁用可点的按钮，避免重复触发
        boolean busy = (state == UiState.BUSY);
        btnQuery.setEnabled(!busy);
        btnRefreshData.setEnabled(!busy);
        btnImport.setEnabled(!busy);
        btnReimport.setEnabled(!busy);
    }

    private void showLogin() {
        resultView.setVisibility(View.GONE);
        loginView.setVisibility(View.VISIBLE);
        statusText.setText("教务系统");
        applyUi(UiState.LOGIN);
    }

    /** 查询进行中：显示进度条，并切到 BUSY 状态 */
    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            applyUi(UiState.BUSY);
        } else {
            // 结束忙碌：回到当前 Tab 的常态（有没有数据决定具体状态）
            applyIdleUi();
        }
    }

    /** 当前 Tab 在非忙碌时应处的状态 */
    private void applyIdleUi() {
        if (currentTab == TAB_CLASSROOM) {
            applyUi(ResultCache.load(this) != null ? UiState.ROOM_DATA : UiState.ROOM_EMPTY);
        } else {
            applyUi(scheduleData != null ? UiState.SCHEDULE_DATA : UiState.SCHEDULE_EMPTY);
        }
    }

    private void showHtml(String html) {
        loginView.setVisibility(View.GONE);
        resultView.setVisibility(View.VISIBLE);
        applyUi(UiState.ROOM_DATA);
        resultView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    /* ---------- JS 回调 ---------- */
    class Bridge {
        @JavascriptInterface
        public void onProgress(final int cur, final int total, final String msg) {
            mainHandler.post(() -> {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(Math.round(cur * 100f / Math.max(total, 1)));
                statusText.setText("查询中 " + cur + "/" + total + "  " + (msg == null ? "" : msg));
            });
        }

        @JavascriptInterface
        public void onResult(final String json) {
            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                setBusy(false);
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok", false)) {
                        String err = o.optString("error", "未知错误");
                        statusText.setText("查询失败：" + err);
                        Toast.makeText(MainActivity.this,
                                "查询失败：" + err, Toast.LENGTH_LONG).show();
                        // 有缓存则回退显示缓存，避免白屏
                        String cached = ResultCache.load(MainActivity.this);
                        if (cached != null) {
                            JSONObject c = new JSONObject(cached);
                            c.put("fromCache", true);
                            c.put("cacheAgeText", ResultCache.ageText(MainActivity.this));
                            c.put("cacheExpired", ResultCache.isExpired(MainActivity.this));
                            showHtml(buildHtml(c));
                            statusText.setText("实时查询失败，显示上次数据（"
                                    + ResultCache.ageText(MainActivity.this) + "）");
                        } else {
                            showLogin();
                        }
                        return;
                    }

                    // 单时段查询（课表联动）是临时结果，不覆盖完整的空教室缓存
                    if (!o.optBoolean("single", false)) {
                        ResultCache.save(MainActivity.this, json);
                    }
                    CookieManager.getInstance().flush();

                    showHtml(buildHtml(o));
                    if (o.optBoolean("throttled", false)) {
                        statusText.setText("更新于 " + o.optString("updated", "")
                                + "（疑似被限流，各时段数量相同，建议重查）");
                    } else {
                        statusText.setText("更新于 " + o.optString("updated", ""));
                    }
                } catch (Exception e) {
                    statusText.setText("解析结果失败：" + e.getMessage());
                }
            });
        }

        /** 学期列表就绪 → 弹窗让用户选学期 */
        @JavascriptInterface
        public void onScheduleTerms(final String json) {
            mainHandler.post(() -> {
                setBusy(false);
                progressBar.setVisibility(View.GONE);
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok", false)) {
                        showImportFailure("读取学期失败",
                                o.optString("error", "未知错误"), o);
                        return;
                    }
                    JSONArray terms = o.getJSONArray("terms");
                    if (terms.length() == 0) {
                        statusText.setText("教务未返回任何学期");
                        return;
                    }
                    showTermPicker(terms);
                } catch (Exception e) {
                    statusText.setText("解析学期失败：" + e.getMessage());
                }
            });
        }

        /** 课表抓取完成 → 落盘 + 首次导入时询问开学日期 */
        @JavascriptInterface
        public void onSchedule(final String json) {
            mainHandler.post(() -> {
                setBusy(false);
                progressBar.setVisibility(View.GONE);
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok", false)) {
                        showImportFailure("导入课表失败",
                                o.optString("error", "未知错误"), o);
                        return;
                    }
                    ScheduleCache.save(MainActivity.this, json);
                    scheduleData = o;
                    CookieManager.getInstance().flush();

                    String sid = o.optString("semesterId", "");
                    weekOffset = 0;          // 刚导入，从本周看起
                    int n = o.getJSONArray("courses").length();
                    if (termStartMs(sid) <= 0) {
                        askTermStart(sid, n);   // 首次导入该学期，问开学日期以推算周次
                    } else {
                        renderSchedule();
                        statusText.setText("课表已导入 · 共 " + n + " 门课");
                    }
                } catch (Exception e) {
                    statusText.setText("解析课表失败：" + e.getMessage());
                }
            });
        }

        /** 点击空白时段 → 跳到空教室页查这一节的空闲教室 */
        /** 点周次条中间 → 重新选开学日期 */
        @JavascriptInterface
        public void setTermStart() {
            mainHandler.post(() -> resetTermStart());
        }

        @JavascriptInterface
        public void onFreeClick(final int day, final int section) {
            mainHandler.post(() -> jumpToFreeRooms(day, section, section));
        }

        /** 点击课程卡片 → 显示这节课的详情 */
        @JavascriptInterface
        public void onCourseClick(final int day, final int startSection, final int endSection) {
            mainHandler.post(() -> showCourseDetail(day, startSection, endSection));
        }

        @JavascriptInterface
        public void prevWeek() {
            mainHandler.post(() -> shiftWeek(-1));
        }

        @JavascriptInterface
        public void nextWeek() {
            mainHandler.post(() -> shiftWeek(1));
        }

        @JavascriptInterface
        public void thisWeek() {
            mainHandler.post(() -> resetWeek());
        }
    }

    /* ---------- 生成表格 HTML（按楼层划分，对齐原项目） ---------- */

    /** 按楼层划分的楼栋：工学馆按楼层分，其余楼栋整体一列 */
    private static final String FLOOR_BUILDING = "工学馆";
    /** 工学馆实际到 8 层（含 803/825 等），取 9 层留余量 */
    private static final int MAX_FLOOR = 9;

    /** 楼栋展示顺序（工学馆排第一，其余按此顺序） */
    private static final String[] BUILDING_ORDER = {
            "工学馆", "基础楼", "综合实验楼", "地质楼", "管理楼", "科技楼", "人文楼"
    };

    private String buildHtml(JSONObject o) throws Exception {
        JSONArray days = o.getJSONArray("days");
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>东秦空教室速查</title><style>").append(css).append("</style></head><body>");
        sb.append("<h1>东秦 · 空闲教室速查</h1>");

        // 离线缓存提示
        if (o.optBoolean("fromCache", false)) {
            String age = o.optString("cacheAgeText", "");
            boolean expired = o.optBoolean("cacheExpired", false);
            boolean stale = o.optBoolean("staleDay", false);
            sb.append("<div class=\"cache")
                    .append(expired || stale ? " expired" : "").append("\">");
            if (expired) {
                sb.append("数据已过期（").append(esc(age)).append("），请点「刷新数据」");
            } else if (stale) {
                sb.append("这是 <b>")
                        .append(esc(cacheFirstDate(o))).append("</b> 的数据，不是今天的 · ")
                        .append(esc(age)).append("抓取，点「刷新数据」看今天的");
            } else {
                sb.append("今日空教室 · 上次抓取于 ").append(esc(age));
            }
            sb.append("</div>");
        }

        sb.append("<p class=\"upd\">数据实时取自教务系统 · 更新于 ")
                .append(o.optString("updated", "")).append("</p>");
        int raw = o.optInt("rawTotal", 0), kept = o.optInt("keptTotal", 0);
        if (raw > 0) {
            sb.append("<p class=\"upd\">已排除实验室、机房、语音室、活动教室、体育场地等非自习教室：原始 ")
                    .append(raw).append(" 条 → 保留 <b>").append(kept).append("</b> 条</p>");
        }

        // 图例
        sb.append("<div class=\"legend\">")
                .append("<span><b>粗体</b> 全天有空</span>")
                .append("<span><u>下划线</u> 比上一时段新增</span>")
                .append("<span><del>删除线</del> 下个时段将上课</span>")
                .append("</div>");

        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.getJSONObject(i);
            JSONArray slots = d.getJSONArray("slots");
            int nSlot = slots.length();

            // 收集本日出现的楼栋
            LinkedHashSet<String> present = new LinkedHashSet<>();
            for (int s = 0; s < nSlot; s++) {
                JSONArray names = slots.getJSONObject(s).getJSONObject("buildings").names();
                if (names == null) continue;
                for (int b = 0; b < names.length(); b++) present.add(names.getString(b));
            }
            // 按预设顺序排列，未知楼栋追加在后
            List<String> ordered = new ArrayList<>();
            for (String b : BUILDING_ORDER) if (present.contains(b)) ordered.add(b);
            for (String b : present) if (!ordered.contains(b)) ordered.add(b);

            String dDate = d.getString("date");
            boolean isToday = dDate.equals(dateStr(System.currentTimeMillis()));
            boolean isJumped = dDate.equals(highlightDate);
            sb.append("<details").append(i == 0 ? " open" : "").append("><summary>")
                    .append(esc(dDate)).append(" 周").append(esc(d.optString("weekday", "")))
                    .append(isToday ? "<span class=\"tag\">今天</span>" : "")
                    .append(isJumped ? "<span class=\"tag jump\">从课表跳来</span>" : "")
                    .append("</summary>");

            if (d.optBoolean("throttle", false)) {
                sb.append("<p class=\"warn\">⚠ 该天各时段数量完全相同，可能触发了教务的「请勿过快点击」限流。"
                        + "请点「返回教务」稍等几秒后重查。</p>");
            }

            if (isJumped && highlightTb > 0) {
                sb.append("<p class=\"jump-tip\">你点的是 <b>第 ").append(highlightTb)
                        .append(" 节</b>，怕你还想看看别的时段，这一天整个都查了。</p>");
            }

            // 各时段条数概览
            sb.append("<p class=\"cnts\">");
            for (int s = 0; s < nSlot; s++) {
                JSONObject slot = slots.getJSONObject(s);
                boolean hit = isJumped && s == highlightTb - 1;
                if (hit) sb.append("<mark>");
                sb.append(esc(slot.getString("label"))).append("：")
                        .append(slot.optBoolean("ok", false)
                                ? slot.optInt("count", 0) + " 间" : "失败");
                if (hit) sb.append("</mark>");
                sb.append("　");
            }
            sb.append("</p>");

            // 全天空闲集合（用于加粗）
            java.util.Map<String, java.util.Set<String>> allDay = computeAllDayFree(slots);

            sb.append("<div class=\"wrap\"><table><thead><tr><th class=\"blead\">楼栋 / 楼层</th>");
            for (int s = 0; s < nSlot; s++) {
                sb.append("<th class=\"slot\">")
                        .append(esc(slots.getJSONObject(s).getString("label")))
                        .append("</th>");
            }
            sb.append("</tr></thead><tbody>");

            for (String b : ordered) {
                if (FLOOR_BUILDING.equals(b)) {
                    // 工学馆：按 1-7 层分行
                    sb.append("<tr><td class=\"bld group\" colspan=\"").append(nSlot + 1)
                            .append("\">").append(esc(b)).append("（按楼层）</td></tr>");
                    for (int floor = 1; floor <= MAX_FLOOR; floor++) {
                        appendRow(sb, slots, b, floor, allDay, "floor");
                    }
                    // 无法归入 1-7 层的房间（如 8 层以上/非数字）
                    appendRow(sb, slots, b, 0, allDay, "floor");
                } else {
                    appendRow(sb, slots, b, -1, allDay, "");
                }
            }
            sb.append("</tbody></table></div></details>");
        }

        sb.append("<p class=\"foot\">数据仅在你已登录的教务会话中读取，不会上传任何信息。</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 输出一行。
     *
     * @param floor -1=整栋一列；0=该楼栋中无法归入 1-7 层的房间；1..7=指定楼层
     * @param cls   附加到楼栋单元格的样式类
     */
    private void appendRow(StringBuilder sb, JSONArray slots, String building, int floor,
                           java.util.Map<String, java.util.Set<String>> allDay, String cls) {
        int nSlot = slots.length();

        // 先按楼层筛出每个时段的房间，全空则整行省略
        List<List<String>> perSlot = new ArrayList<>();
        boolean anyRoom = false;
        for (int s = 0; s < nSlot; s++) {
            List<String> rooms = new ArrayList<>();
            JSONArray arr = roomsOf(slots, s, building);
            if (arr != null) {
                for (int k = 0; k < arr.length(); k++) {
                    String r = arr.optString(k, "");
                    if (r.isEmpty()) continue;
                    if (floor > 0 && floorOf(r) != floor) continue;
                    if (floor == 0 && floorOf(r) > 0 && floorOf(r) <= MAX_FLOOR) continue;
                    rooms.add(r);
                }
            }
            sortRooms(rooms);
            if (!rooms.isEmpty()) anyRoom = true;
            perSlot.add(rooms);
        }
        if (!anyRoom) return;   // 该行整天空无教室，不显示

        String label = floor > 0 ? (floor + " 层") : building;
        sb.append("<tr><td class=\"bld ").append(cls).append("\">").append(esc(label)).append("</td>");

        for (int s = 0; s < nSlot; s++) {
            List<String> rooms = perSlot.get(s);
            sb.append("<td class=\"slot\">");
            if (rooms.isEmpty()) {
                sb.append("<span class=\"none\">—</span>");
            } else {
                sb.append("<span class=\"cnt\">").append(rooms.size()).append("</span>");
                sb.append("<span class=\"rooms\">");
                for (int k = 0; k < rooms.size(); k++) {
                    if (k > 0) sb.append(" ");
                    sb.append(styleRoom(rooms.get(k), building, s, nSlot, slots, allDay));
                }
                sb.append("</span>");
            }
            sb.append("</td>");
        }
        sb.append("</tr>");
    }

    /** null 安全：取某天某时段的某楼栋房间数组 */
    private static JSONArray roomsOf(JSONArray slots, int idx, String building) {
        JSONObject slot = slots.optJSONObject(idx);
        if (slot == null) return null;
        JSONObject bk = slot.optJSONObject("buildings");
        if (bk == null) return null;
        return bk.optJSONArray(building);
    }

    /** 从房间号推断楼层：取前导数字，若为 4 位数（如 4012）取首位，3 位（如 410）取首位后一位 */
    private static int floorOf(String room) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < room.length(); i++) {
            char ch = room.charAt(i);
            if (Character.isDigit(ch)) digits.append(ch);
            else break;
        }
        if (digits.length() == 0) return -1;
        try {
            int n = Integer.parseInt(digits.toString());
            if (digits.length() >= 4) return n / 1000;        // 4012 -> 4 层
            if (digits.length() == 3) return n / 100;         // 410  -> 4 层
            if (digits.length() == 2) return n / 10;          // 41   -> 4 层
            return n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 数字优先排序：410 在 508 前，同数字按后缀字母 */
    private static void sortRooms(List<String> rooms) {
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

    /** 加粗=全天有空；下划线=比上一时段新增；删除线=下个时段将上课 */
    private String styleRoom(String room, String building, int slotIdx, int nSlot,
                             JSONArray slots, java.util.Map<String, java.util.Set<String>> allDay) {
        boolean bold = false;
        java.util.Set<String> set = allDay.get(building);
        if (set != null) bold = set.contains(room);

        boolean underline = false;
        if (slotIdx > 0) {
            underline = !containsRoom(slots, slotIdx - 1, building, room);
        }
        boolean strike = false;
        if (slotIdx < nSlot - 1) {
            strike = !containsRoom(slots, slotIdx + 1, building, room);
        }

        String s = esc(room);
        if (underline) s = "<u>" + s + "</u>";
        if (strike) s = "<del>" + s + "</del>";
        if (bold) s = "<strong>" + s + "</strong>";
        return s;
    }

    private static boolean containsRoom(JSONArray slots, int idx, String building, String room) {
        JSONArray arr = roomsOf(slots, idx, building);
        if (arr == null) return false;
        for (int k = 0; k < arr.length(); k++) {
            if (room.equals(arr.optString(k, ""))) return true;
        }
        return false;
    }

    /** 计算「全天空闲」教室集合（在全部时段都出现） */
    private static java.util.Map<String, java.util.Set<String>> computeAllDayFree(JSONArray slots) {
        java.util.Map<String, java.util.Set<String>> result = new java.util.HashMap<>();
        int nSlot = slots.length();
        if (nSlot == 0) return result;

        // 收集所有出现过的楼栋
        java.util.Set<String> buildings = new java.util.HashSet<>();
        for (int s = 0; s < nSlot; s++) {
            JSONObject slot = slots.optJSONObject(s);
            if (slot == null) continue;
            JSONObject bk = slot.optJSONObject("buildings");
            if (bk == null) continue;
            JSONArray names = bk.names();
            if (names == null) continue;
            for (int b = 0; b < names.length(); b++) {
                buildings.add(names.optString(b, ""));
            }
        }

        for (String building : buildings) {
            java.util.Set<String> common = null;
            for (int s = 0; s < nSlot; s++) {
                JSONArray arr = roomsOf(slots, s, building);
                java.util.Set<String> cur = new java.util.HashSet<>();
                if (arr != null) {
                    for (int k = 0; k < arr.length(); k++) {
                        String r = arr.optString(k, "");
                        if (!r.isEmpty()) cur.add(r);
                    }
                }
                if (common == null) common = cur;
                else common.retainAll(cur);
            }
            if (common != null && !common.isEmpty()) result.put(building, common);
        }
        return result;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String readAsset(String name) {
        try (InputStream is = getAssets().open(name);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /* ==================== 课表 ==================== */

    private static final String[] WD_CN = {"一", "二", "三", "四", "五", "六", "日"};
    private static final int PERIODS = 12;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private long termStartMs(String sid) {
        return prefs().getLong(KEY_TERM_START + sid, 0);
    }

    private void saveTermStart(String sid, long ms) {
        prefs().edit().putLong(KEY_TERM_START + sid, ms).apply();
    }

    /** 缓存里第一天的日期字符串，用于提示「这是 X 月 X 日的数据」 */
    private static String cacheFirstDate(JSONObject o) {
        JSONArray days = o.optJSONArray("days");
        if (days == null || days.length() == 0) return "";
        JSONObject d0 = days.optJSONObject(0);
        return d0 == null ? "" : d0.optString("date", "");
    }

    /**
     * 缓存里的第一天是否不是今天。
     * nqFetch 返回的 days[0].date 是抓取当天，格式 yyyy-MM-dd。
     * 只要不是今天，就说明这份空教室数据已经「过期了一天」，得提醒用户刷新。
     */
    private static boolean isCacheStaleToday(JSONObject o) {
        JSONArray days = o.optJSONArray("days");
        if (days == null || days.length() == 0) return true;
        JSONObject d0 = days.optJSONObject(0);
        if (d0 == null) return true;
        String date = d0.optString("date", "");
        if (date.isEmpty()) return true;
        return !date.equals(dateStr(System.currentTimeMillis()));
    }

    /** 某时刻所在周的周一 00:00 */
    private static long mondayOf(long ms) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int dow = c.get(Calendar.DAY_OF_WEEK);          // 周日=1 … 周六=7
        int delta = (dow == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - dow);
        c.add(Calendar.DAY_OF_MONTH, delta);
        return c.getTimeInMillis();
    }

    private static String dateStr(long ms) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(ms);
        return String.format(Locale.CHINA, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static String mdStr(long ms) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(ms);
        return (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 当天 00:00:00.000 —— 用来算「目标日期距今天几天」 */
    private static long startOfDay(long ms) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 当前应显示的周次 = 按开学日期推算 + 用户手动偏移 */
    private int currentWeek() {
        if (scheduleData == null) return 1;
        long start = termStartMs(scheduleData.optString("semesterId", ""));
        if (start <= 0) return 1;
        long weeks = Math.round((mondayOf(System.currentTimeMillis()) - mondayOf(start)) / (7.0 * DAY_MS));
        int w = (int) weeks + 1;
        if (w < 1) w = 1;
        return w + weekOffset;
    }

    /** 第 week 周的周一 */
    private long weekMonday(int week) {
        if (scheduleData == null) return mondayOf(System.currentTimeMillis());
        long start = termStartMs(scheduleData.optString("semesterId", ""));
        if (start <= 0) return mondayOf(System.currentTimeMillis());
        return mondayOf(start) + (week - 1) * 7L * DAY_MS;
    }

    /** 课表色块配色：按课名哈希稳定取色，同名课程永远同色 */
    private static int courseColorIdx(String name) {
        return ((name == null ? "" : name).hashCode() & 0x7fffffff) % 8;
    }

    private static boolean inWeek(JSONArray weeks, int week) {
        if (weeks == null || weeks.length() == 0) return true;   // 无周次信息则不过滤
        for (int i = 0; i < weeks.length(); i++) {
            if (weeks.optInt(i, -1) == week) return true;
        }
        return false;
    }

    private void switchTab(int tab) {
        // 启动时 currentTab 已是课表，此时点「课表」仍要重新渲染（可能刚导入完或日期已改）
        boolean sameTab = (currentTab == tab);
        currentTab = tab;
        boolean room = (tab == TAB_CLASSROOM);
        indClassroom.setVisibility(room ? View.VISIBLE : View.INVISIBLE);
        indSchedule.setVisibility(room ? View.INVISIBLE : View.VISIBLE);
        tvClassroom.setTextColor(room ? 0xFF1B4D8F : 0xFF9AA2B4);
        tvSchedule.setTextColor(room ? 0xFF9AA2B4 : 0xFF1B4D8F);
        if (sameTab && tab == TAB_CLASSROOM) return;

        // 切 Tab 后按钮状态由下面的渲染路径各自设定，这里不用预设

        if (room) {
            String cached = ResultCache.load(this);
            if (cached != null) {
                try {
                    JSONObject o = new JSONObject(cached);
                    o.put("fromCache", true);
                    o.put("cacheAgeText", ResultCache.ageText(this));
                    o.put("cacheExpired", ResultCache.isExpired(this));
                    // 缓存存的是「抓取当天起算」的若干天，隔天再打开首日就不是今天了
                    // 加一句提示，避免把昨天的空教室当成今天的
                    o.put("staleDay", isCacheStaleToday(o));
                    showHtml(buildHtml(o));   // 内部会切到 ROOM_DATA
                    String age = ResultCache.ageText(this);
                    if (ResultCache.isExpired(this)) {
                        statusText.setText("数据已过期（" + age + "），建议刷新");
                    } else if (o.optBoolean("staleDay", false)) {
                        statusText.setText("缓存不是今天的（" + age + "），建议刷新");
                    } else {
                        statusText.setText("今日空教室 · " + age + "抓取");
                    }
                } catch (Exception ignored) {
                    showLogin();
                }
            } else {
                showLogin();
            }
        } else {
            if (scheduleData != null) {
                renderSchedule();
            } else {
                showScheduleHtml(emptyScheduleHtml());
                applyUi(UiState.SCHEDULE_EMPTY);
                statusText.setText("还没有课表，点「导入课表」");
            }
        }
    }

    private void startScheduleImport() {
        statusText.setText("正在打开教务课表页…");
        setBusy(true);
        progressBar.setProgress(0);
        pendingTerms = true;
        loginView.loadUrl(EAMS_BASE + "courseTableForStd.action");
    }

    /**
     * 导入失败：把教务返回的诊断信息（响应长度、TaskActivity 命中数、页面文本片段）
     * 展示出来并允许一键复制 —— 教务一旦改版，靠这段信息就能直接定位。
     */
    private void showImportFailure(String title, String err, JSONObject payload) {
        lastDiagText = formatDiag(err, payload);
        statusText.setText(title + "：" + err);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(lastDiagText)
                .setPositiveButton("复制诊断", (d, w) -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(
                                ClipData.newPlainText("neuq-diag", lastDiagText));
                        Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("重新导入", (d, w) -> startScheduleImport())
                .setNeutralButton("关闭", null)
                .show();
    }

    private static String diagSourceCn(String src) {
        switch (src) {
            case "entryPage": return "课表入口页（未发起点课表请求）";
            case "response":  return "课表接口响应";
            default:          return "无（请求未发出或已中断）";
        }
    }

    private String formatDiag(String err, JSONObject payload) {
        StringBuilder sb = new StringBuilder();
        sb.append(err).append("\n");
        if (payload == null) return sb.toString();
        JSONObject d = payload.optJSONObject("diag");
        if (d == null) return sb.append("\n（无诊断信息）").toString();
        sb.append("\n──── 诊断 ────\n");
        sb.append("阶段：").append(d.optString("stage", "-")).append("\n");
        sb.append("来源：").append(diagSourceCn(d.optString("source", "-"))).append("\n");
        sb.append("URL：").append(d.optString("url", "-")).append("\n");
        sb.append("响应字节：").append(d.optString("len", "-")).append("\n");
        sb.append("TaskActivity 命中：").append(d.optString("taskActivityCount", "0")).append(" 处\n");
        if (d.has("termName")) {
            sb.append("学期：").append(d.optString("termName", "-"))
              .append("（id=").append(d.optString("semesterId", "-")).append("）\n");
        }
        String snip = d.optString("snippet", "");
        if (!snip.isEmpty()) sb.append("\n页面文本：\n").append(snip).append("\n");
        String hint = d.optString("jsHint", "");
        if (!hint.isEmpty()) sb.append("\n脚本片段：\n").append(hint).append("\n");
        return sb.toString();
    }

    private void showTermPicker(JSONArray terms) {
        int n = terms.length();
        String[] names = new String[n];
        final String[] ids = new String[n];
        for (int i = 0; i < n; i++) {
            JSONObject t = terms.optJSONObject(i);
            ids[i] = t == null ? "" : t.optString("id", "");
            names[i] = t == null ? "?" : t.optString("name", ids[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择学期")
                .setItems(names, (dialog, which) -> startScheduleFetch(ids[which], names[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void startScheduleFetch(String semesterId, String termName) {
        pendingSemesterId = semesterId;
        pendingTermName = termName;
        pendingSchedule = true;
        statusText.setText("正在抓取「" + termName + "」…");
        setBusy(true);
        loginView.loadUrl(EAMS_BASE + "courseTableForStd.action");
    }

    /** 首次导入某学期时问开学日期，之后据此推算周次 */
    /**
     * 把 DatePicker 切到滚轮模式。
     * 布局里的 datePickerMode/calendarViewShown 属性在部分 ROM 上会被忽略，
     * 所以运行时再显式设一遍（这两个 setter 在 API 26+ 标记废弃，但仍是 spinner 模式下
     * 唯一可靠的开关，故 @SuppressWarnings 保留）。
     */
    @SuppressWarnings("deprecation")
    private static void useSpinner(DatePicker dp) {
        dp.setSpinnersShown(true);
        dp.setCalendarViewShown(false);
    }

    /** 开学日期：滚轮选（年 / 月 / 日），不用日历网格 */
    private void askTermStart(String sid, int courseCount) {
        View v = getLayoutInflater().inflate(R.layout.dialog_date_spinner, null);
        DatePicker dp = v.findViewById(R.id.datePicker);
        useSpinner(dp);

        // 默认停在「本周周一」，大多数情况下开学日就在附近
        Calendar dft = Calendar.getInstance(Locale.CHINA);
        dft.setTimeInMillis(mondayOf(System.currentTimeMillis()));
        dp.updateDate(dft.get(Calendar.YEAR), dft.get(Calendar.MONTH),
                dft.get(Calendar.DAY_OF_MONTH));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("本学期第 1 周从哪天开始？")
                .setView(v)
                .setCancelable(false)
                .setPositiveButton("确定", null)
                .setNegativeButton("稍后设置", null)
                .create();
        dlg.show();
        // 点「确定」后立刻收起弹窗再渲染，避免先卡一下再消失
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            Calendar c = Calendar.getInstance(Locale.CHINA);
            c.set(dp.getYear(), dp.getMonth(), dp.getDayOfMonth(), 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            saveTermStart(sid, mondayOf(c.getTimeInMillis()));
            dlg.dismiss();
            renderSchedule();
            statusText.setText("课表已导入 · 共 " + courseCount + " 门课");
        });
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(b -> {
            dlg.dismiss();
            renderSchedule();
            statusText.setText("课表已导入 · 共 " + courseCount
                    + " 门课（点顶部「第 N 周」可设开学日期）");
        });
    }

    /** 重新设定当前学期的开学日期（点课表顶部「第 N 周」触发） */
    private void resetTermStart() {
        if (scheduleData == null) return;
        String sid = scheduleData.optString("semesterId", "");
        long cur = termStartMs(sid);
        if (cur <= 0) cur = mondayOf(System.currentTimeMillis());

        View v = getLayoutInflater().inflate(R.layout.dialog_date_spinner, null);
        DatePicker dp = v.findViewById(R.id.datePicker);
        useSpinner(dp);
        Calendar c0 = Calendar.getInstance(Locale.CHINA);
        c0.setTimeInMillis(cur);
        dp.updateDate(c0.get(Calendar.YEAR), c0.get(Calendar.MONTH),
                c0.get(Calendar.DAY_OF_MONTH));

        new AlertDialog.Builder(this)
                .setTitle("开学日期（第 1 周的周一）")
                .setView(v)
                .setPositiveButton("确定", (d, w) -> {
                    Calendar c = Calendar.getInstance(Locale.CHINA);
                    c.set(dp.getYear(), dp.getMonth(), dp.getDayOfMonth(), 0, 0, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    saveTermStart(sid, mondayOf(c.getTimeInMillis()));
                    renderSchedule();
                    statusText.setText("开学日期已更新 · 现在是第 " + currentWeek() + " 周");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 翻周只影响本次会话，不写盘 —— 下次打开 App 仍从本周开始 */
    private void shiftWeek(int delta) {
        if (scheduleData == null) return;
        weekOffset += delta;
        renderSchedule();
        statusText.setText("第 " + currentWeek() + " 周"
                + (weekOffset == 0 ? "" : "（点「本周」回到当前周）"));
    }

    private void resetWeek() {
        if (scheduleData == null) return;
        weekOffset = 0;
        renderSchedule();
        statusText.setText("已回到本周 · 第 " + currentWeek() + " 周");
    }

    private void renderSchedule() {
        if (scheduleData == null) return;
        try {
            showScheduleHtml(buildScheduleHtml(scheduleData, currentWeek()));
            applyUi(UiState.SCHEDULE_DATA);
        } catch (Exception e) {
            statusText.setText("课表渲染失败：" + e.getMessage());
        }
    }

    private void showScheduleHtml(String html) {
        loginView.setVisibility(View.GONE);
        resultView.setVisibility(View.VISIBLE);
        resultView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String emptyScheduleHtml() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>" + scheduleCss + "</style></head><body>"
                + "<h1>我的课表</h1>"
                + "<div class=\"empty\"><b>还没有课表</b>"
                + "点上方「导入课表」，登录教务后自动读取<br>导入一次即可长期离线查看</div>"
                + "<p class=\"tip\">课表只从你本人已登录的教务会话读取，保存在手机本地，不会上传。</p>"
                + "</body></html>";
    }

    /** 生成课表周视图 HTML */
    private String buildScheduleHtml(JSONObject o, int week) throws Exception {
        JSONArray courses = o.getJSONArray("courses");
        long weekMon = weekMonday(week);

        // 今天所在的列（0=周一 … 6=周日），不在本周则为 -1
        int todayCol = -1;
        if (mondayOf(System.currentTimeMillis()) == weekMon) {
            Calendar tc = Calendar.getInstance(Locale.CHINA);
            int dow = tc.get(Calendar.DAY_OF_WEEK);
            todayCol = (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY);
        }

        // 先把课程摆进网格，跨节次的用 rowspan 覆盖下方单元格
        JSONObject[][] grid = new JSONObject[PERIODS][7];
        boolean[][] covered = new boolean[PERIODS][7];
        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;
            if (!inWeek(c.optJSONArray("weeks"), week)) continue;
            int day = c.optInt("day", 0);
            int st = c.optInt("startSection", 0);
            int en = c.optInt("endSection", st);
            if (day < 1 || day > 7 || st < 1 || st > PERIODS) continue;
            if (en < st) en = st;
            if (en > PERIODS) en = PERIODS;
            int r = st - 1, col = day - 1;
            if (grid[r][col] == null) grid[r][col] = c;
            for (int k = r + 1; k < en; k++) covered[k][col] = true;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>我的课表</title><style>").append(scheduleCss).append("</style></head><body>");
        sb.append("<h1>我的课表</h1>");
        sb.append("<p class=\"sub\">").append(esc(o.optString("termName", "")))
                .append(" · 更新于 ").append(esc(o.optString("updated", ""))).append("</p>");

        sb.append("<div class=\"weekbar\">")
                .append("<button onclick=\"AndroidResultHost.prevWeek()\">‹ 上周</button>")
                .append("<div class=\"cur\" onclick=\"AndroidResultHost.setTermStart()\">第 ")
                .append(week).append(" 周")
                .append("<small>").append(dateStr(weekMon)).append(" 起 · 点此改开学日期")
                .append("</small></div>")
                .append("<button onclick=\"AndroidResultHost.nextWeek()\">下周 ›</button>")
                .append("<button class=\"today\" onclick=\"AndroidResultHost.thisWeek()\">本周</button>")
                .append("</div>");

        sb.append("<div class=\"gridwrap\"><table class=\"grid\"><thead><tr>");
        sb.append("<th class=\"corner\"></th>");
        for (int c = 0; c < 7; c++) {
            sb.append("<th").append(c == todayCol ? " class=\"today\"" : "").append(">")
                    .append("<span class=\"wd\">").append(WD_CN[c]).append("</span>")
                    .append("<span class=\"dt\">").append(mdStr(weekMon + c * DAY_MS))
                    .append("</span></th>");
        }
        sb.append("</tr></thead><tbody>");

        JSONArray slots = o.optJSONArray("timeSlots");
        for (int r = 0; r < PERIODS; r++) {
            sb.append("<tr><td class=\"per\"><span class=\"no\">").append(r + 1).append("</span>");
            String st = "", et = "";
            if (slots != null && slots.length() > r) {
                JSONObject t = slots.optJSONObject(r);
                if (t != null) {
                    st = t.optString("s", "");
                    et = t.optString("e", "");
                }
            }
            sb.append("<span class=\"st\">").append(esc(st)).append("</span>")
                    .append("<span class=\"et\">").append(esc(et)).append("</span></td>");

            for (int c = 0; c < 7; c++) {
                if (covered[r][c]) continue;      // 已被上方 rowspan 占用
                JSONObject co = grid[r][c];
                if (co == null) {
                    // 没课的时段：点一下直接查这个时段的空闲教室
                    sb.append("<td class=\"cell free")
                            .append(c == todayCol ? " today" : "")
                            .append("\"><div class=\"free-slot\" onclick=\"AndroidResultHost.onFreeClick(")
                            .append(c + 1).append(",").append(r + 1)
                            .append(")\"><span class=\"plus\">+</span>"
                                    + "<span class=\"fd\">空教室</span></div></td>");
                } else {
                    int cs = co.optInt("startSection", r + 1);
                    int ce = co.optInt("endSection", r + 1);
                    int span = ce - cs + 1;
                    if (span < 1) span = 1;
                    String nm = co.optString("name", "");
                    sb.append("<td class=\"cell\" rowspan=\"").append(span).append("\">")
                            .append("<div class=\"course c").append(courseColorIdx(nm))
                            .append("\" onclick=\"AndroidResultHost.onCourseClick(")
                            .append(c + 1).append(",").append(cs).append(",").append(ce)
                            .append(")\">")
                            .append("<div class=\"nm\">").append(esc(nm)).append("</div>")
                            .append("<div class=\"rm\">").append(esc(co.optString("position", "")))
                            .append("</div></div></td>");
                }
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");

        sb.append("<p class=\"tip\">点 <b>空白时段</b> 查这个时间的空闲教室；"
                + "点 <b>课程卡片</b> 看课程详情。</p>");
        sb.append("<p class=\"foot\">课表保存在手机本地，不会上传任何信息。</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 课表 → 空教室：点到某天的空白时段，就把那一天完整查一遍。
     *
     * 以前只查被点的那一节课，用户看到「第 3-4 节有空教室」之后想知道「那第 5-6 节呢」
     * 就得退回去重点一次。既然教务接口按「整天的连续 N 天」计价，索性一次把这一天 7 个
     * 时段都拉回来，结果页里高亮用户点的那一节 —— 多花几秒，少点好几次。
     */
    private void jumpToFreeRooms(int day, int startSection, int endSection) {
        if (scheduleData == null) return;
        if (day < 1 || day > 7) return;

        int week = currentWeek();
        long target = weekMonday(week) + (day - 1) * DAY_MS;
        long today = System.currentTimeMillis();
        int offset = (int) Math.round((target - startOfDay(today)) / (double) DAY_MS);

        String label = "第 " + week + " 周 周" + WD_CN[day - 1] + " "
                + startSection + "-" + endSection + " 节";

        // 切到空教室 Tab（复用 switchTab，保证按钮显隐与 Tab 高亮不会两处漂移）
        if (currentTab != TAB_CLASSROOM) switchTab(TAB_CLASSROOM);
        else applyIdleUi();

        highlightDate = dateStr(target);
        highlightTb = startSection;

        if (offset < 0) {
            // 过去的日子：整周查询装不下，退回单时段查询
            singleDate = dateStr(target);
            singleTb = startSection;
            singleTe = endSection;
            singleLabel = label;
            pendingSingle = true;
            statusText.setText("正在查询 " + label + " 的空教室…");
            setBusy(true);
            loginView.loadUrl(EAMS_BASE + "classroom/apply/free!search.action");
            return;
        }

        // 未来/今天：查这一天完整 7 个时段
        queryStartOffset = offset;
        queryDays = 1;
        pendingQuery = true;
        statusText.setText("正在查询 周" + WD_CN[day - 1] + " 全天 7 个时段的空教室"
                + (offset == 0 ? "（今天）" : "…"));
        setBusy(true);
        progressBar.setProgress(0);
        loginView.loadUrl(EAMS_BASE + "classroom/apply/free!search.action");
    }

    /* ---------- 课程详情 ---------- */

    /** 点课程卡片：这节课你是有课的，所以给详情，而不是去查空教室 */
    private void showCourseDetail(int day, int st, int en) {
        if (scheduleData == null) return;
        JSONArray courses = scheduleData.optJSONArray("courses");
        if (courses == null) return;

        int week = currentWeek();
        JSONObject hit = null;
        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;
            if (c.optInt("day", 0) != day) continue;
            if (c.optInt("startSection", 0) != st) continue;
            if (c.optInt("endSection", st) != en) continue;
            if (!inWeek(c.optJSONArray("weeks"), week)) continue;
            hit = c;
            break;
        }
        if (hit == null) {
            Toast.makeText(this, "这节课本周不上", Toast.LENGTH_SHORT).show();
            return;
        }

        String t0 = slotTime(st, false), t1 = slotTime(en, true);
        StringBuilder m = new StringBuilder();
        m.append("教师：").append(nz(hit.optString("teacher", ""))).append("\n");
        m.append("地点：").append(nz(hit.optString("position", ""))).append("\n");
        m.append("时间：周").append(WD_CN[day - 1]).append(" 第 ").append(st)
                .append(en > st ? "-" + en : "").append(" 节");
        if (!t0.isEmpty() && !t1.isEmpty()) m.append("  ").append(t0).append("–").append(t1);
        m.append("\n周次：").append(weeksText(hit.optJSONArray("weeks")));

        new AlertDialog.Builder(this)
                .setTitle(hit.optString("name", "课程"))
                .setMessage(m.toString())
                .setPositiveButton("仍要查该时段空教室",
                        (d, w) -> jumpToFreeRooms(day, st, en))
                .setNegativeButton("关闭", null)
                .show();
    }

    private static String nz(String s) {
        return (s == null || s.trim().isEmpty()) ? "未注明" : s.trim();
    }

    /** 第 section 节的起（或止）时间，取不到返回空串 */
    private String slotTime(int section, boolean end) {
        if (scheduleData == null || section < 1) return "";
        JSONArray slots = scheduleData.optJSONArray("timeSlots");
        if (slots == null || slots.length() < section) return "";
        JSONObject t = slots.optJSONObject(section - 1);
        return t == null ? "" : t.optString(end ? "e" : "s", "");
    }

    /** 周次数组 → "3-11 周" / "1-5、8-10 周" */
    private static String weeksText(JSONArray weeks) {
        if (weeks == null || weeks.length() == 0) return "全周";
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < weeks.length(); i++) {
            int w = weeks.optInt(i, 0);
            if (w > 0) list.add(w);
        }
        if (list.isEmpty()) return "全周";
        Collections.sort(list);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < list.size()) {
            int s = list.get(i), e = s;
            while (i + 1 < list.size() && list.get(i + 1) == e + 1) e = list.get(++i);
            if (sb.length() > 0) sb.append("、");
            sb.append(s == e ? String.valueOf(s) : (s + "-" + e));
            i++;
        }
        return sb.append(" 周").toString();
    }

    /**
     * 返回键优先级：
     *  1. 用户正看着教务网页（点了「返回教务」/刷新，loginView 可见）
     *     → 先让 WebView 回退，退无可退再退出
     *  2. 不在课表主页（即空教室页）→ 回课表主页
     *  3. 已在课表主页 → 退出 App
     *
     * 关键：判「在不在主页」只看 currentTab，不看 loginView。
     * 因为 loginView 在后台被反复 loadUrl（登录页 / 查询页 / 课表页…），
     * 历史栈很长；用它判断会让返回键莫名其妙地回退很多次才退得出去。
     */
    @Override
    public void onBackPressed() {
        if (loginView.getVisibility() == View.VISIBLE) {
            if (loginView.canGoBack()) {
                loginView.goBack();
            } else {
                super.onBackPressed();
            }
            return;
        }
        if (currentTab != TAB_SCHEDULE) {
            switchTab(TAB_SCHEDULE);   // 空教室是次页面 → 回课表主页
            return;
        }
        super.onBackPressed();         // 已在课表主页 → 退出
    }
}
