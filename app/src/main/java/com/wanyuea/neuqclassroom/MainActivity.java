package com.wanyuea.neuqclassroom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.LinkedHashSet;
import java.util.List;

public class MainActivity extends Activity {

    private static final String HOME_URL =
            "https://vpn.neuq.edu.cn/http/77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b/eams/homeExt.action";
    private static final String EAMS_BASE =
            "https://vpn.neuq.edu.cn/http/77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b/eams/";
    private boolean pendingQuery = false;
    private int queryDays = 1;
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
    private boolean autoLoginActive = false;

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
        Button btnRefresh = findViewById(R.id.btnRefresh);

        injectJs = readAsset("inject.js");
        css = readAsset("table.css");

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
        btnRefresh.setOnClickListener(v -> {
            showLogin();
            loginView.reload();
        });
        btnRefreshData.setOnClickListener(v -> {
            showLogin();
            askDays();
        });

        loginView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.contains("vpn.neuq.edu.cn")) {
                    // 页面已就绪，把会话 Cookie 落盘
                    CookieManager.getInstance().flush();
                }
                if (!pendingQuery) return;
                // 若被重定向到登录页（会话过期），不要注入，留在页面让用户登录
                if (url == null || url.indexOf("/eams/") < 0) {
                    pendingQuery = false;
                    progressBar.setVisibility(View.GONE);
                    setBusy(false);
                    statusText.setText("会话已过期，请先登录教务再点查询");
                    Toast.makeText(MainActivity.this, "请先登录教务系统", Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingQuery = false;
                statusText.setText("正在查询 " + queryDays + " 天 × 7 个时段…");
                view.evaluateJavascript(injectJs, null);
                view.evaluateJavascript("window.nqFetch(" + queryDays + "," + queryGap + ");", null);
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
     * 启动流程：
     *  1. 有缓存 → 立刻显示缓存内容（用户无需登录即可查看）
     *  2. 同时后台加载教务页；若 Cookie 仍有效则静默进入，用户可一键刷新
     *  3. 无缓存 → 正常加载教务页引导登录
     */
    private void tryAutoLoginThenLoad() {
        String cached = ResultCache.load(this);
        if (cached != null) {
            try {
                JSONObject o = new JSONObject(cached);
                o.put("fromCache", true);
                o.put("cacheAgeText", ResultCache.ageText(this));
                o.put("cacheExpired", ResultCache.isExpired(this));
                showHtml(buildHtml(o));
                String age = ResultCache.ageText(this);
                if (ResultCache.isExpired(this)) {
                    statusText.setText("数据已过期（" + age + "），建议刷新");
                } else {
                    statusText.setText("上次数据 · " + age + "（点「刷新数据」获取最新）");
                }
                btnBack.setVisibility(View.GONE);
                btnRefreshData.setVisibility(View.VISIBLE);
            } catch (Exception ignored) {
                // 缓存损坏，忽略
            }
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
        final String[] items = {"仅今天（约 10 秒）", "近 3 天（约 25 秒）", "近 7 天（约 60 秒）"};
        final int[] days = {1, 3, 7};
        new AlertDialog.Builder(this)
                .setTitle("查询范围")
                .setItems(items, (dialog, which) -> startQuery(days[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void startQuery(int days) {
        queryDays = days;
        statusText.setText("正在打开教务查询页…");
        setBusy(true);
        progressBar.setProgress(0);
        // 先加载教务的空闲教室查询页（与浏览器操作一致，确保会话上下文正确），
        // 页面加载完成后在 onPageFinished 里注入脚本抓取
        pendingQuery = true;
        loginView.loadUrl(EAMS_BASE + "classroom/apply/free!search.action");
    }

    private void showLogin() {
        resultView.setVisibility(View.GONE);
        loginView.setVisibility(View.VISIBLE);
        btnBack.setVisibility(View.GONE);
        btnRefreshData.setVisibility(View.GONE);
        btnQuery.setVisibility(View.VISIBLE);
        statusText.setText("教务系统");
    }

    /** 查询进行中：禁用按钮、显示进度 */
    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnQuery.setEnabled(!busy);
        btnRefreshData.setEnabled(!busy);
        if (busy) {
            btnQuery.setVisibility(View.VISIBLE);
            btnBack.setVisibility(View.GONE);
            btnRefreshData.setVisibility(View.GONE);
        }
    }

    private void showHtml(String html) {
        loginView.setVisibility(View.GONE);
        resultView.setVisibility(View.VISIBLE);
        btnQuery.setVisibility(View.GONE);
        btnBack.setVisibility(View.VISIBLE);
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
                            btnRefreshData.setVisibility(View.VISIBLE);
                            statusText.setText("实时查询失败，显示上次数据（"
                                    + ResultCache.ageText(MainActivity.this) + "）");
                        } else {
                            showLogin();
                        }
                        return;
                    }

                    // 查询成功 → 落盘缓存 + 把 Cookie 一并持久化
                    ResultCache.save(MainActivity.this, json);
                    CookieManager.getInstance().flush();

                    showHtml(buildHtml(o));
                    btnRefreshData.setVisibility(View.VISIBLE);
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
            sb.append("<div class=\"cache").append(expired ? " expired" : "").append("\">")
                    .append(expired ? "数据已过期（" : "离线查看 · 上次抓取于 ")
                    .append(esc(age)).append(expired ? "），请点「刷新数据」" : "")
                    .append("</div>");
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

            sb.append("<details").append(i == 0 ? " open" : "").append("><summary>")
                    .append(d.getString("date")).append(" 周").append(d.optString("weekday", ""))
                    .append("</summary>");

            if (d.optBoolean("throttle", false)) {
                sb.append("<p class=\"warn\">⚠ 该天各时段数量完全相同，可能触发了教务的「请勿过快点击」限流。"
                        + "请点「返回教务」稍等几秒后重查。</p>");
            }

            // 各时段条数概览
            sb.append("<p class=\"cnts\">");
            for (int s = 0; s < nSlot; s++) {
                JSONObject slot = slots.getJSONObject(s);
                sb.append(esc(slot.getString("label"))).append("：")
                        .append(slot.optBoolean("ok", false)
                                ? slot.optInt("count", 0) + " 间" : "失败")
                        .append("　");
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

    @Override
    public void onBackPressed() {
        if (resultView.getVisibility() == View.VISIBLE) {
            showLogin();
        } else if (loginView.canGoBack()) {
            loginView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
