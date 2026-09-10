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
import android.widget.ImageButton;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
    private ImageButton btnQuery;
    private ImageButton btnBack;
    private ImageButton btnRefreshData;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String injectJs = "";
    private String css = "";
    private String scheduleCss = "";
    private boolean autoLoginActive = false;

    /* ---------- 底部 Tab ---------- */
    private static final int TAB_CLASSROOM = 0;
    private static final int TAB_SCHEDULE = 1;
    private static final int TAB_MORE = 2;
    private int currentTab = TAB_SCHEDULE;   // 课表是主页面
    private LinearLayout tabClassroom;
    private LinearLayout tabSchedule;
    private LinearLayout tabMore;
    private View indClassroom;
    private View indSchedule;
    private View indMore;
    private TextView tvClassroom;
    private TextView tvSchedule;
    private TextView tvMore;

    /** 底部 Tab 的选中/未选中配色，三个 Tab 共用一处，避免改色时漏改 */
    private static final int TAB_ON = 0xFF1B4D8F;
    private static final int TAB_OFF = 0xFF9AA2B4;

    /* ---------- 顶栏按钮状态机 ----------
       所有按钮显隐只由 applyUi(state) 一处决定。
       以前是 30 处散落的 setVisibility，漏改一处就会出 bug
       （比如返回键失效、空教室页残留课表按钮）。 */

    /** 顶栏状态：决定哪些按钮可见 */
    private enum UiState {
        LOGIN,          // 教务 WebView 可见，等用户登录
        ROOM_EMPTY,     // 空教室页，还没有数据 → 查空教室
        ROOM_DATA,      // 空教室页，有数据 → 刷新数据
        SCHEDULE_EMPTY, // 课表页，还没导入 → 刷新（去登录/导入）
        SCHEDULE_DATA,  // 课表页，有课表 → 刷新（重新导入）
        MORE,           // 更多页（设置类，顶栏不放业务按钮）
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
    private boolean singleMode = false;     // 当前结果页是不是「单时段」视图（带时段快捷切换）
    private String singleWeekday = "";      // 单时段视图的星期，用于时段快捷按钮重查

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
        tabClassroom = findViewById(R.id.tabClassroom);
        tabSchedule = findViewById(R.id.tabSchedule);
        tabMore = findViewById(R.id.tabMore);
        indClassroom = findViewById(R.id.indClassroom);
        indSchedule = findViewById(R.id.indSchedule);
        indMore = findViewById(R.id.indMore);
        tvClassroom = findViewById(R.id.tvClassroom);
        tvSchedule = findViewById(R.id.tvSchedule);
        tvMore = findViewById(R.id.tvMore);

        injectJs = readAsset("inject.js");
        css = readAsset("table.css");
        scheduleCss = readAsset("schedule.css");

        setupWebView(loginView);
        setupWebView(resultView);
        resultView.addJavascriptInterface(new Bridge(), "AndroidResultHost");
        setupMorePage();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(loginView, true);
        // 持久化 Cookie（含 WebVPN / CAS 会话票据），下次启动仍是登录态
        cm.setAcceptThirdPartyCookies(resultView, true);
        cm.flush();

        btnQuery.setOnClickListener(v -> {
            // 顶栏「查空教室」只在空教室页出现；课表页那个位置是刷新
            if (currentTab == TAB_CLASSROOM) askDays();
            else startScheduleImport();
        });
        btnBack.setOnClickListener(v -> showLogin());
        // 刷新：空教室页重查空教室；课表页重新导入课表
        btnRefreshData.setOnClickListener(v -> {
            if (currentTab == TAB_CLASSROOM) {
                showLogin();
                askDays();
            } else {
                startScheduleImport();
            }
        });
        tabClassroom.setOnClickListener(v -> switchTab(TAB_CLASSROOM));
        tabSchedule.setOnClickListener(v -> switchTab(TAB_SCHEDULE));
        tabMore.setOnClickListener(v -> switchTab(TAB_MORE));

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
            statusText.setText("还没有课表，去「更多 → 教务处登录」导入");
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
        singleMode = false;        // 整表视图，不是单时段
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

        switch (state) {
            case LOGIN:
                // 正看着教务网页：空教室页给「查空教室」，课表页给「刷新」（=重新导入）
                if (room) vQuery = true; else vRefreshData = true;
                break;
            case ROOM_EMPTY:
                vQuery = true;
                break;
            case ROOM_DATA:
                vRefreshData = true;
                break;
            case SCHEDULE_EMPTY:
            case SCHEDULE_DATA:
                // 导入课表的入口已挪到「更多 → 教务处登录」，顶栏只留一个刷新
                vRefreshData = true;
                break;
            case MORE:
                // 更多页是设置列表，顶栏不放业务按钮，避免和页面内的按钮打架
                break;
            case BUSY:
                // 查询中：空教室页保留按钮位置（禁用态），其余页面全部隐藏
                if (room) vQuery = true;
                break;
        }

        btnQuery.setVisibility(vQuery ? View.VISIBLE : View.GONE);
        btnBack.setVisibility(vBack ? View.VISIBLE : View.GONE);
        btnRefreshData.setVisibility(vRefreshData ? View.VISIBLE : View.GONE);

        // 忙碌时禁用可点的按钮，避免重复触发
        boolean busy = (state == UiState.BUSY);
        btnQuery.setEnabled(!busy);
        btnRefreshData.setEnabled(!busy);
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
        } else if (currentTab == TAB_MORE) {
            applyUi(UiState.MORE);
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

        /**
         * 结果页顶部时段快捷切换：点了「下午5-6节」就换到那一组重查。
         * 日期沿用当前展示的那一天，所以只传时段下标。
         */
        @JavascriptInterface
        public void pickSlot(final int slotIdx) {
            mainHandler.post(() -> switchSlot(slotIdx));
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

    /** 按楼层划分的楼栋：工学馆按楼层分行，其余楼栋整体一行 */
    private static final String FLOOR_BUILDING = "工学馆";
    /** 工学馆实际到 8 层（含 803/825 等），取 9 层留余量 */
    private static final int MAX_FLOOR = 9;

    /**
     * 楼栋 → 校区 Tab 的分组。
     *
     * 参照东秦空闲教室总表的三分区：工学馆（按楼层拆）、本部其它、南校区。
     * 每组内的楼栋顺序即展示顺序；未列入的楼栋归到「本部其它」并追加在后。
     */
    private static final String[] CAMPUS_TABS = {"工学馆", "本部其它", "南校区"};
    private static final String[][] CAMPUS_BUILDINGS = {
            {"工学馆"},
            {"基础楼", "综合实验楼", "地质楼", "管理楼"},
            {"科技楼", "人文楼"}
    };

    /** 楼栋展示顺序（工学馆排第一，其余按此顺序），保留给课表联动等旧逻辑使用 */
    private static final String[] BUILDING_ORDER = {
            "工学馆", "基础楼", "综合实验楼", "地质楼", "管理楼", "科技楼", "人文楼"
    };

    /**
     * 空教室总表的交互脚本。
     *
     * 只用事件委托绑一次，不给每个元素挂 onclick —— 一天最多 3 个 Tab × 6 个时段，
     * 内联 onclick 会让 HTML 膨胀不少，而且以后改结构容易漏。
     */
    private static final String TABLE_SCRIPT =
            "<script>(function(){"
            + "function closest(el,sel){while(el&&el.nodeType===1){if(el.matches(sel))return el;el=el.parentNode;}return null;}"
            // 楼栋 Tab：切 active，同时让同容器内的 content 跟着切
            + "function pickTab(btn){"
            + "var bar=btn.parentNode;"
            + "var kids=bar.children;"
            + "for(var i=0;i<kids.length;i++){kids[i].classList.remove('active');}"
            + "btn.classList.add('active');"
            + "var box=bar.parentNode;"
            + "for(var j=0;j<box.children.length;j++){"
            + "var c=box.children[j];"
            + "if(c.classList&&c.classList.contains('tab-content')){c.classList.remove('active');}"
            + "}"
            + "var target=document.getElementById(btn.getAttribute('data-tab'));"
            + "if(target)target.classList.add('active');"
            + "}"
            // 时段折叠
            + "function fold(h){"
            + "h.classList.toggle('collapsed');"
            + "var b=document.getElementById(h.getAttribute('data-fold'));"
            + "if(b)b.classList.toggle('collapsed',h.classList.contains('collapsed'));"
            + "}"
            + "document.addEventListener('click',function(e){"
            + "var t=closest(e.target,'.tab-button');if(t){pickTab(t);return;}"
            + "var h=closest(e.target,'.timeslot-title');if(h){fold(h);return;}"
            + "},false);"
            + "})();</script>";

    /**
     * 单时段视图顶部的时段快捷切换条。
     *
     * 用户从课表点进来时只查了一组（比如第 3-4 节），这一排按钮让他不用退回课表
     * 就能直接换到别的一组重查。「昼间1-8节」是教务的合并统计项，不放进切换条。
     */
    private void appendSlotSwitcher(StringBuilder sb) {
        sb.append("<div class=\"slotbar\">")
                .append("<div class=\"slotbar-tip\">点课表空白格只查了这一组，"
                        + "想换别的时段直接点下面：</div>")
                .append("<div class=\"slotbar-btns\">");
        for (int i = 0; i < FREE_SLOTS.length; i++) {
            if (i == 4) continue;    // 跳过「昼间1-8节」合并项
            int tb = FREE_SLOTS[i][0], te = FREE_SLOTS[i][1];
            boolean cur = (tb == singleTb && te == singleTe);
            sb.append("<button class=\"slotbtn").append(cur ? " cur" : "")
                    .append("\" onclick=\"AndroidResultHost.pickSlot(").append(i).append(")\">")
                    .append(tb).append("-").append(te).append(" 节")
                    .append("</button>");
        }
        sb.append("</div></div>");
    }

    private String buildHtml(JSONObject o) throws Exception {        JSONArray days = o.getJSONArray("days");
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>东秦空教室速查</title><style>").append(css).append("</style></head><body>");

        // 页头：先给「今天」的日期，再给表格标题（对齐参考站点的版式）
        sb.append("<h1>空闲教室总表</h1>");
        sb.append("<p class=\"info-text\">数据实时取自教务系统 · 更新于 ")
                .append(esc(o.optString("updated", ""))).append("</p>");

        // 单时段视图（点课表空白格跳过来）：顶部给一排时段快捷切换
        if (o.optBoolean("single", false)) {
            appendSlotSwitcher(sb);
        }

        int raw = o.optInt("rawTotal", 0), kept = o.optInt("keptTotal", 0);
        if (raw > 0) {
            sb.append("<p class=\"info-text\">已排除实验室、机房、语音室等非自习教室：原始 ")
                    .append(raw).append(" 条 → 保留 <b>").append(kept).append("</b> 条</p>");
        }

        // 离线缓存提示
        if (o.optBoolean("fromCache", false)) {
            String age = o.optString("cacheAgeText", "");
            boolean expired = o.optBoolean("cacheExpired", false);
            boolean stale = o.optBoolean("staleDay", false);
            sb.append("<div class=\"cache")
                    .append(expired || stale ? " expired" : "").append("\">");
            if (expired) {
                sb.append("数据已过期（").append(esc(age)).append("），请刷新");
            } else if (stale) {
                sb.append("这是 <b>")
                        .append(esc(cacheFirstDate(o))).append("</b> 的数据，不是今天的 · ")
                        .append(esc(age)).append("抓取");
            } else {
                sb.append("离线数据 · 上次抓取于 ").append(esc(age));
            }
            sb.append("</div>");
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
            sb.append("<details class=\"day\"").append(i == 0 ? " open" : "").append("><summary>")
                    .append(esc(dDate)).append(" 周").append(esc(d.optString("weekday", "")))
                    .append(isToday ? "<span class=\"tag\">今天</span>" : "")
                    .append(isJumped ? "<span class=\"tag jump\">从课表跳来</span>" : "")
                    .append("</summary>");

            if (d.optBoolean("throttle", false)) {
                sb.append("<p class=\"warn\">⚠ 该天各时段数量完全相同，可能触发了教务的「请勿过快点击」限流。"
                        + "请点「返回教务」稍等几秒后重查。</p>");
            }

            // 单时段视图：说明为什么只有一组数据；整表视图：说明为什么整天都查了
            if (isJumped && highlightTb > 0) {
                if (singleMode) {
                    sb.append("<p class=\"jump-tip\">你点的是 <b>第 ").append(highlightTb)
                            .append(" 节</b>，属 <b>").append(highlightTb).append("-")
                            .append(highlightTb + 1).append(" 节</b> 这一组，"
                                    + "下面是这一组所有空闲教室。</p>");
                } else {
                    sb.append("<p class=\"jump-tip\">你点的是 <b>第 ").append(highlightTb)
                            .append(" 节</b>，这一天整个都查了。</p>");
                }
            }

            // 全天空闲集合（用于加粗）
            java.util.Map<String, java.util.Set<String>> allDay = computeAllDayFree(slots);

            appendCampusTabs(sb, i, days, slots, ordered, allDay, isJumped);
            sb.append("</details>");
        }

        sb.append("<p class=\"foot\">数据仅在你已登录的教务会话中读取，不会上传任何信息。</p>");
        sb.append(TABLE_SCRIPT);
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 输出某一天的「楼栋 Tab + 时段表格」主体。
     *
     * 结构对齐东秦空闲教室总表：
     *   楼栋 Tab（工学馆 / 本部其它 / 南校区）
     *     └ 每个时段一个可折叠区块
     *         └ 一张表：工学馆按楼层为行（1F…9F），其它楼栋以楼栋名为行
     *
     * @param dayIdx  第几天（用于生成唯一 DOM id）
     * @param ordered 本日实际出现的楼栋，已排序
     */
    private void appendCampusTabs(StringBuilder sb, int dayIdx, JSONArray days, JSONArray slots,
                                  List<String> ordered,
                                  java.util.Map<String, java.util.Set<String>> allDay,
                                  boolean isJumped) throws Exception {
        int nSlot = slots.length();

        // 只保留本日真正有数据的 Tab；全空也保留工学馆，避免页面整个空掉
        List<String> liveTabs = new ArrayList<>();
        List<List<String>> liveBuildings = new ArrayList<>();
        for (int t = 0; t < CAMPUS_TABS.length; t++) {
            List<String> bs = new ArrayList<>();
            for (String b : CAMPUS_BUILDINGS[t]) if (ordered.contains(b)) bs.add(b);
            if (bs.isEmpty()) continue;
            liveTabs.add(CAMPUS_TABS[t]);
            liveBuildings.add(bs);
        }
        // 不在预设分组里的楼栋（教务新增了楼），统一塞进「本部其它」
        List<String> known = new ArrayList<>();
        for (String[] group : CAMPUS_BUILDINGS) {
            for (String b : group) known.add(b);
        }
        for (String b : ordered) {
            if (known.contains(b)) continue;
            int qi = liveTabs.indexOf("本部其它");
            if (qi < 0) {
                liveTabs.add("本部其它");
                liveBuildings.add(new ArrayList<>());
                qi = liveTabs.size() - 1;
            }
            liveBuildings.get(qi).add(b);
        }

        if (liveTabs.isEmpty()) {
            sb.append("<p class=\"empty-day\">这一天没有查到空闲教室</p>");
            return;
        }

        sb.append("<div class=\"tab-container\"><div class=\"tab-buttons\">");
        for (int t = 0; t < liveTabs.size(); t++) {
            sb.append("<button class=\"tab-button").append(t == 0 ? " active" : "")
                    .append("\" data-tab=\"tab-").append(dayIdx).append("-").append(t)
                    .append("\">").append(esc(liveTabs.get(t))).append("</button>");
        }
        sb.append("</div>");

        for (int t = 0; t < liveTabs.size(); t++) {
            sb.append("<div class=\"tab-content").append(t == 0 ? " active" : "")
                    .append("\" id=\"tab-").append(dayIdx).append("-").append(t).append("\">");
            appendSlotSections(sb, dayIdx, t, slots, liveBuildings.get(t), allDay, isJumped);
            sb.append("</div>");
        }
        sb.append("</div>");
    }

    /** 某个楼栋分组下，逐个时段输出「标题 + 表格」 */
    private void appendSlotSections(StringBuilder sb, int dayIdx, int tabIdx, JSONArray slots,
                                    List<String> buildings,
                                    java.util.Map<String, java.util.Set<String>> allDay,
                                    boolean isJumped) throws Exception {
        int nSlot = slots.length();
        boolean hasAny = false;

        for (int s = 0; s < nSlot; s++) {
            JSONArray slotRow = buildSlotTable(slots, s, buildings, allDay);
            if (slotRow == null) continue;    // 该时段本分组无教室，整块省略
            hasAny = true;

            String label = slots.getJSONObject(s).getString("label");
            boolean hit = isJumped && s == highlightTb - 1;
            int shown = slotRow.length();

            // 标题：可折叠，默认展开；从课表跳来的那一节标记出来
            sb.append("<h3 class=\"timeslot-title").append(hit ? " hit" : "")
                    .append("\" data-fold=\"body-").append(dayIdx).append("-").append(tabIdx)
                    .append("-").append(s).append("\">")
                    .append("<span class=\"toggle-icon\"></span>")
                    .append(esc(label))
                    .append("<span class=\"cnt-mini\">（").append(shown).append(" 行）</span>")
                    .append("</h3>");

            sb.append("<div class=\"timeslot-body\" id=\"body-")
                    .append(dayIdx).append("-").append(tabIdx).append("-").append(s).append("\">")
                    .append("<table class=\"slot-table\">");

            for (int r = 0; r < shown; r++) {
                JSONArray row = slotRow.getJSONArray(r);
                String head = row.getString(0);
                String headCls = row.getString(1);
                String cells = row.getString(2);
                sb.append("<tr><td class=\"").append(headCls).append("\">")
                        .append(esc(head)).append("</td>")
                        .append("<td class=\"rooms\">").append(cells).append("</td></tr>");
            }
            sb.append("</table></div>");
        }

        if (!hasAny) {
            sb.append("<p class=\"empty-day\">这些时段都没有空闲教室</p>");
        }
    }

    /**
     * 构建某个时段、某个楼栋分组下的表格行。
     *
     * 返回值为「行数组」，每行是 3 元组 [行头文字, 行头样式类, 教室单元格 HTML]：
     *   · 工学馆 → 按楼层拆行，行头 "1F"…"9F"，样式类 floor
     *   · 其它楼栋 → 每个楼栋一行，行头是楼栋名，样式类 building
     * 该时段本分组完全没有教室时返回 null（调用方跳过整块）。
     */
    private JSONArray buildSlotTable(JSONArray slots, int slotIdx, List<String> buildings,
                                     java.util.Map<String, java.util.Set<String>> allDay) throws Exception {
        int nSlot = slots.length();
        JSONArray rows = new JSONArray();
        boolean any = false;

        for (String b : buildings) {
            if (FLOOR_BUILDING.equals(b)) {
                // 工学馆：1..MAX_FLOOR 逐层，另有无法归层的房间放最后一行
                for (int floor = 1; floor <= MAX_FLOOR; floor++) {
                    JSONArray r = slotRow(slots, slotIdx, nSlot, b, floor, allDay);
                    if (r != null) { rows.put(r); any = true; }
                }
                JSONArray rest = slotRow(slots, slotIdx, nSlot, b, 0, allDay);
                if (rest != null) { rows.put(rest); any = true; }
            } else {
                // 其余楼栋：整栋一行，不拆楼层
                JSONArray r = slotRow(slots, slotIdx, nSlot, b, -1, allDay);
                if (r != null) { rows.put(r); any = true; }
            }
        }
        return any ? rows : null;
    }

    /**
     * 生成单行：[行头, 行头类名, 教室 HTML]，该行在本时段无教室则返回 null。
     *
     * @param floor -1=整栋一行；0=无法归入 1..MAX_FLOOR 的房间；1..MAX_FLOOR=指定楼层
     */
    private JSONArray slotRow(JSONArray slots, int slotIdx, int nSlot, String building, int floor,
                              java.util.Map<String, java.util.Set<String>> allDay) throws Exception {
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

        StringBuilder cells = new StringBuilder();
        for (int k = 0; k < rooms.size(); k++) {
            if (k > 0) cells.append(" ");
            cells.append("<span class=\"r\">")
                    .append(styleRoom(rooms.get(k), building, slotIdx, nSlot, slots, allDay))
                    .append("</span>");
        }

        String head;
        String cls;
        if (floor > 0) {
            head = floor + "F";
            cls = "floor";
        } else if (floor == 0) {
            head = "其他";
            cls = "floor";
        } else {
            head = building;
            cls = "building";
        }
        JSONArray row = new JSONArray();
        row.put(head).put(cls).put(cells.toString());
        return row;
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

    /* ==================== 「更多」页 ==================== */

    /** 「更多」页的原生布局（不是 WebView） */
    private View morePage;

    /* 外部链接集中在这里，改地址只改这一处 */
    private static final String APP_REPO = "https://github.com/wy723161060/neuq-classroom-app";
    private static final String WEB_SITE = "https://neuq-classroom-query-2kb.pages.dev";
    private static final String WEB_REPO = "https://github.com/wanYuea/neuq-classroom-query";

    /** 用系统浏览器打开外部链接（App 内不内嵌浏览，避免和教务 WebView 抢会话） */
    private void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 绑定「更多」页的行点击事件（只绑一次，在 onCreate 里调用） */
    private void setupMorePage() {
        morePage = findViewById(R.id.morePage);
        morePage.findViewById(R.id.rowLogin).setOnClickListener(v -> {
            showLogin();
            statusText.setText("教务系统 · 登录后回「更多」导入课表");
        });
        morePage.findViewById(R.id.rowImport).setOnClickListener(v -> startScheduleImport());
        morePage.findViewById(R.id.rowCache).setOnClickListener(v -> showCacheManager());
        morePage.findViewById(R.id.rowWebSite).setOnClickListener(v -> openUrl(WEB_SITE));
        morePage.findViewById(R.id.rowWebRepo).setOnClickListener(v -> openUrl(WEB_REPO));
        morePage.findViewById(R.id.rowAppRepo).setOnClickListener(v -> openUrl(APP_REPO));
        morePage.findViewById(R.id.rowCopyDiag).setOnClickListener(v -> copyDiag());
        morePage.findViewById(R.id.rowHowto).setOnClickListener(v -> showHowto());
    }

    /** 切到「更多」页：刷新一遍其上的动态文案（版本号 / 缓存统计） */
    private void showMore() {
        refreshMorePage();
        loginView.setVisibility(View.GONE);
        resultView.setVisibility(View.GONE);
        morePage.setVisibility(View.VISIBLE);
        applyUi(UiState.MORE);
        statusText.setText("更多");
    }

    /**
     * 重新计算「更多」页上的动态文案。
     *
     * 版本号、缓存大小、缓存年龄这些都会随使用变化，每次进页面或清完缓存都要重算，
     * 否则用户会看到过期的数字。
     */
    private void refreshMorePage() {
        if (morePage == null) return;

        TextView tvVer = morePage.findViewById(R.id.tvVersion);
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // 取不到就显示占位符
        }
        tvVer.setText("版本 " + (ver.isEmpty() ? "-" : ver));

        // 导入课表这一行的文案随有没有课表变化
        boolean hasSchedule = (scheduleData != null || ScheduleCache.load(this) != null);
        TextView subImport = morePage.findViewById(R.id.subImport);
        subImport.setText(hasSchedule
                ? "重新从教务抓取当前学期课表"
                : "登录教务后从教务读取本学期课表");
        morePage.findViewById(R.id.badgeImport)
                .setVisibility(hasSchedule ? View.VISIBLE : View.GONE);

        // 缓存统计
        long schedSize = cacheFileSize("schedule.json");
        long roomSize = cacheFileSize("cache.json");
        long total = Math.max(schedSize, 0) + Math.max(roomSize, 0);
        TextView subCache = morePage.findViewById(R.id.subCache);
        subCache.setText("课表 " + (schedSize >= 0 ? sizeText(schedSize) : "无")
                + " · " + (hasSchedule ? ScheduleCache.ageText(this) : "未导入")
                + "　｜　空教室 " + (roomSize >= 0 ? sizeText(roomSize) : "无")
                + " · " + (roomSize >= 0 ? ResultCache.ageText(this) : "未查询"));
        TextView subTotal = morePage.findViewById(R.id.subCacheTotal);
        subTotal.setText("合计 " + sizeText(total) + "，全部存在本机");
    }

    /** 文件大小文案 */
    private static String sizeText(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.CHINA, "%.2f MB", bytes / 1024.0 / 1024.0);
    }

    /** 某个缓存文件的大小（不存在返回 -1） */
    private long cacheFileSize(String name) {
        File f = new File(getFilesDir(), name);
        return f.exists() ? f.length() : -1;
    }

    private int cacheFileCount(String name) {
        return new File(getFilesDir(), name).exists() ? 1 : 0;
    }


    /* ---------- 缓存管理弹窗 ---------- */

    /** 缓存管理：显示两项缓存的大小/时间，可分别清除 */
    private void showCacheManager() {
        long schedSize = cacheFileSize("schedule.json");
        long roomSize = cacheFileSize("cache.json");
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> kinds = new java.util.ArrayList<>();

        labels.add("课表缓存　" + (schedSize >= 0 ? sizeText(schedSize) : "无")
                + "　" + ScheduleCache.ageText(this));
        kinds.add(1);
        labels.add("空教室缓存　" + (roomSize >= 0 ? sizeText(roomSize) : "无")
                + "　" + (roomSize >= 0 ? ResultCache.ageText(this) : "未查询"));
        kinds.add(2);
        labels.add("全部清除（含开学日期设置）");
        kinds.add(0);

        final String[] arr = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("缓存管理")
                .setItems(arr, (d, which) -> {
                    int kind = kinds.get(which);
                    if (kind == 0) confirmClearAll();
                    else if (kind == 1) clearScheduleCache();
                    else clearRoomCache();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("清除全部缓存？")
                .setMessage("将删除课表缓存、空教室缓存和开学日期设置。\n"
                        + "课表需要重新导入，空教室需要重新查询。")
                .setPositiveButton("清除", (d, w) -> {
                    ScheduleCache.clear(this);
                    ResultCache.clear(this);
                    scheduleData = null;
                    weekOffset = 0;
                    // 开学日期是 SharedPreferences 里的 termStart_<sid>，一并清掉
                    SharedPreferences.Editor e = prefs().edit();
                    for (String k : prefs().getAll().keySet()) {
                        if (k.startsWith(KEY_TERM_START)) e.remove(k);
                    }
                    e.apply();
                    Toast.makeText(this, "缓存已全部清除", Toast.LENGTH_SHORT).show();
                    refreshMorePage();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearScheduleCache() {
        if (cacheFileSize("schedule.json") < 0) {
            Toast.makeText(this, "没有课表缓存", Toast.LENGTH_SHORT).show();
            return;
        }
        ScheduleCache.clear(this);
        scheduleData = null;
        weekOffset = 0;
        Toast.makeText(this, "课表缓存已清除", Toast.LENGTH_SHORT).show();
        refreshMorePage();
    }

    private void clearRoomCache() {
        if (cacheFileSize("cache.json") < 0) {
            Toast.makeText(this, "没有空教室缓存", Toast.LENGTH_SHORT).show();
            return;
        }
        ResultCache.clear(this);
        Toast.makeText(this, "空教室缓存已清除", Toast.LENGTH_SHORT).show();
        refreshMorePage();
    }

    /** 诊断信息：反馈问题时贴给开发者 */
    private String buildDiagText() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // 取不到就留空
        }
        StringBuilder sb = new StringBuilder();
        sb.append("东秦空教室 诊断信息\n");
        sb.append("版本：").append(ver.isEmpty() ? "-" : ver).append("\n");
        sb.append("Android：").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型：").append(android.os.Build.MANUFACTURER).append(" ")
                .append(android.os.Build.MODEL).append("\n");
        sb.append("──── 缓存 ────\n");
        long s = cacheFileSize("schedule.json");
        long r = cacheFileSize("cache.json");
        sb.append("课表缓存：").append(s >= 0 ? sizeText(s) + "，" + ScheduleCache.ageText(this) : "无")
                .append("\n");
        sb.append("空教室缓存：").append(r >= 0 ? sizeText(r) + "，" + ResultCache.ageText(this) : "无")
                .append("\n");
        if (scheduleData != null) {
            sb.append("当前学期：").append(scheduleData.optString("termName", "-"))
                    .append("（id=").append(scheduleData.optString("semesterId", "-")).append("）\n");
            sb.append("课程数：").append(scheduleData.optJSONArray("courses") == null ? 0
                    : scheduleData.optJSONArray("courses").length()).append("\n");
        }
        if (!lastDiagText.isEmpty()) {
            sb.append("──── 最近一次导入失败 ────\n").append(lastDiagText).append("\n");
        }
        return sb.toString();
    }

    private void copyDiag() {
        String txt = buildDiagText();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("neuq-diag", txt));
            Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show();
        }
    }

    /** 使用说明 */
    private void showHowto() {
        String msg = "1. 首次使用\n"
                + "   更多 → 教务处登录 → 登录统一身份认证（学校账号，App 不保存密码）。\n\n"
                + "2. 导入课表\n"
                + "   登录后回本页点「导入课表」，选学期即可；首次需选开学日期以便推算周次。\n\n"
                + "3. 查空教室\n"
                + "   底部「空教室」→ 顶栏刷新图标 → 选今天还是 7 天。也可在课表里直接点空白格子，"
                + "只查那一组时段。\n\n"
                + "4. 换学期 / 改开学日期\n"
                + "   点课表顶部「第 N 周」即可重设开学日期；换学期请到「更多 → 导入课表」重选。\n\n"
                + "5. 数据在哪\n"
                + "   课表与空教室结果都缓存在手机本地，断网也能看。可在「更多 → 缓存管理」里清除。";
        new AlertDialog.Builder(this)
                .setTitle("使用说明")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
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

    /** 底部 Tab 高亮：三个 Tab 共用一处，避免以后加 Tab 漏改 */
    private void applyTabHighlight(int tab) {
        indClassroom.setVisibility(tab == TAB_CLASSROOM ? View.VISIBLE : View.INVISIBLE);
        indSchedule.setVisibility(tab == TAB_SCHEDULE ? View.VISIBLE : View.INVISIBLE);
        indMore.setVisibility(tab == TAB_MORE ? View.VISIBLE : View.INVISIBLE);
        tvClassroom.setTextColor(tab == TAB_CLASSROOM ? TAB_ON : TAB_OFF);
        tvSchedule.setTextColor(tab == TAB_SCHEDULE ? TAB_ON : TAB_OFF);
        tvMore.setTextColor(tab == TAB_MORE ? TAB_ON : TAB_OFF);
    }

    private void switchTab(int tab) {
        // 启动时 currentTab 已是课表，此时点「课表」仍要重新渲染（可能刚导入完或日期已改）
        boolean sameTab = (currentTab == tab);
        currentTab = tab;
        boolean room = (tab == TAB_CLASSROOM);
        applyTabHighlight(tab);
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
        } else if (tab == TAB_MORE) {
            showMore();
        } else {
            if (scheduleData != null) {
                renderSchedule();
            } else {
                showScheduleHtml(emptyScheduleHtml());
                applyUi(UiState.SCHEDULE_EMPTY);
                statusText.setText("还没有课表，去「更多 → 教务处登录」导入");
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
                + "去底部「更多 → 教务处登录」，登录后即可导入<br>导入一次即可长期离线查看</div>"
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
     * 空教室接口返回的时段定义，与 inject.js 的 SLOTS 一一对应。
     *
     * ⚠️ 这是「查询时段」，不是「课表节次」：一个时段含 2 节，且第 5 项
     * 「昼间1-8节」是教务为了显示而混进来的合并项，跨度 8 节。
     * 所以**绝不能拿课表的节次直接当它的下标** —— 第 2 节会指到「上午3-4节」。
     */
    private static final int[][] FREE_SLOTS = {
            {1, 2}, {3, 4}, {5, 6}, {7, 8}, {1, 8}, {9, 10}, {11, 12}
    };

    /**
     * 课表节次 → 空教室时段下标。
     *
     * 取「覆盖该节次、且跨度最小」的那个时段；跨度最小保证了第 5 节命中
     * 「下午5-6节」而不是跨度 8 的「昼间1-8节」。找不到返回 -1。
     */
    private static int slotIndexOf(int section) {
        int best = -1, bestSpan = Integer.MAX_VALUE;
        for (int i = 0; i < FREE_SLOTS.length; i++) {
            int tb = FREE_SLOTS[i][0], te = FREE_SLOTS[i][1];
            if (section >= tb && section <= te) {
                int span = te - tb + 1;
                if (span < bestSpan) {
                    bestSpan = span;
                    best = i;
                }
            }
        }
        return best;
    }

    /**
     * 课表 → 空教室：点到某天的空白时段，直接查那一节的空闲教室。
     *
     * 只查被点的那一节（一次请求，约 1.5 秒），结果页把那一条置顶并高亮 ——
     * 用户点「第 3 节」，就该立刻看到第 3 节的空教室，而不是一整天 7 个时段里去找。
     */
    private void jumpToFreeRooms(int day, int startSection, int endSection) {
        if (scheduleData == null) return;
        if (day < 1 || day > 7) return;

        int week = currentWeek();
        long target = weekMonday(week) + (day - 1) * DAY_MS;
        int offset = (int) Math.round((target - startOfDay(System.currentTimeMillis())) / (double) DAY_MS);

        // 把课表节次折到「查询时段」上，再拿时段自己的节次区间去查
        int slotIdx = slotIndexOf(startSection);
        int qb = startSection, qe = endSection;
        if (slotIdx >= 0) {
            qb = FREE_SLOTS[slotIdx][0];
            qe = FREE_SLOTS[slotIdx][1];
        } else if (qe < qb) {
            qe = qb;
        }

        String label = "第 " + week + " 周 周" + WD_CN[day - 1] + " " + qb + "-" + qe + " 节";

        // 切到空教室 Tab（复用 switchTab，保证按钮显隐与 Tab 高亮不会两处漂移）
        if (currentTab != TAB_CLASSROOM) switchTab(TAB_CLASSROOM);
        else applyIdleUi();

        highlightDate = dateStr(target);
        highlightTb = qb;
        singleMode = true;
        singleWeekday = WD_CN[day - 1];

        // 过去的日子也能查（单时段接口不受「从今天起算」限制），所以不需要回退分支
        singleDate = dateStr(target);
        singleTb = qb;
        singleTe = qe;
        singleLabel = label;
        pendingSingle = true;
        statusText.setText("正在查询 " + label + " 的空教室…");
        setBusy(true);
        loginView.loadUrl(EAMS_BASE + "classroom/apply/free!search.action");
    }

    /**
     * 单时段视图里换一组时段重查（结果页顶部的快捷按钮）。
     *
     * 日期不变，只换节次区间 —— 这样用户在「第 3-4 节」看完，想再看看「第 5-6 节」，
     * 直接在结果页点一下就行，不用退回课表再点一次。
     */
    private void switchSlot(int slotIdx) {
        if (slotIdx < 0 || slotIdx >= FREE_SLOTS.length) return;
        if (singleDate.isEmpty()) return;

        int qb = FREE_SLOTS[slotIdx][0];
        int qe = FREE_SLOTS[slotIdx][1];
        String label = singleDate + " " + singleWeekday + " " + qb + "-" + qe + " 节";

        highlightTb = qb;
        singleTb = qb;
        singleTe = qe;
        singleLabel = label;
        pendingSingle = true;

        statusText.setText("正在查询 " + qb + "-" + qe + " 节的空教室…");
        setBusy(true);
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
        // 更多页 / 空教室页都是次页面 → 先回课表主页
        if (currentTab != TAB_SCHEDULE) {
            switchTab(TAB_SCHEDULE);
            return;
        }
        super.onBackPressed();         // 已在课表主页 → 退出
    }
}
