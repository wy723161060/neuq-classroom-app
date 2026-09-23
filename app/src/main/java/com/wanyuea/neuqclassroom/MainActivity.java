package com.wanyuea.neuqclassroom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class MainActivity extends Activity {

    private static final String HOME_URL =
            "https://vpn.neuq.edu.cn/http/77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b/eams/homeExt.action";
    private static final String EAMS_BASE =
            "https://vpn.neuq.edu.cn/http/77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b/eams/";

    /* ---------- 通道 2 网站导入 ---------- */
    /** 每次打开软件自动从通道 2 导入空教室（用户在数据来源对话框里勾选） */
    private static final String KEY_AUTO_IMPORT_WEB = "auto_import_web";
    /** 显示周六 / 周日（课表外观设置） */
    private static final String KEY_SHOW_SAT = "show_saturday";
    private static final String KEY_SHOW_SUN = "show_sunday";
    /** 通道 2 数据页里每天的时刻表（与模板 template.tera.html 一致） */
    private static final String[] WEB_SLOTS = {"1-2", "3-4", "5-6", "7-8", "9-10", "11-12"};

    private boolean pendingQuery = false;
    private int queryDays = 1;
    private int queryStartOffset = 0;   // 起始日相对今天的天数（0=今天，1=明天）
    private int queryGap = 1000;

    private WebView loginView;
    private WebView resultView;
    private View eamsBrowserOverlay;
    private EditText eamsAddressInput;
    private WebView webView;          // 内嵌浏览器：空闲教室总表网页
    private LinearLayout webPage;     // 内嵌浏览器整块（标题栏 + 进度条 + WebView）
    private TextView webUrlText;
    private ProgressBar webProgress;
    private ImageButton btnWebBack;
    private ImageButton btnWebReload;
    /** 课表设置 / 课程管理两个子页（原生整页，从课表设置面板进入） */
    private View schedSettingsPage;
    private View courseManagerPage;
    /** 自定义背景图所在层：放在内容容器最底部，图片透出来后卡片仍保持可读。 */
    private View themeBackgroundImage;
    private ImageButton btnWebOpenOuter;
    private TextView statusText;
    private ProgressBar progressBar;
    /**
     * 导入/查询进度卡片（底部弹出）。只做「看得见的进度」：
     * 用户可以按返回键把它收起（后台照常跑），所以它不参与任何状态机判断。
     */
    private Dialog progressDialog;
    private ImageButton btnRefreshData;
    private ImageButton btnImportTop;
    private ImageButton btnMoreTop;
    /** 课表设置面板正在同步滑杆/列表：避免 setProgress 反过来触发监听器里的重绘 */
    private boolean schedSyncing = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String injectJs = "";
    private String css = "";
    private String scheduleCss = "";
    private boolean autoLoginActive = false;
    /** 冲突格子里用户点选的课程偏好；只影响界面显示，不修改课表数据。 */
    private final Map<String, String> conflictPrefs = new HashMap<>();

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
    private ImageView iconClassroom;
    private ImageView iconSchedule;
    private ImageView iconMore;
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
        WEB,            // 内嵌浏览器打开总表网页
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
    /**
     * 等登录完成后弹「查询范围」。
     *
     * 空教室页点刷新/查询时如果还没登录，应该先让用户登，**登完再问范围**。
     * 早先是 showLogin() 紧跟着 askDays()，结果对话框直接糊在 WebVPN 登录页上，
     * 用户还没登录就被问「查几天」。
     */
    private boolean pendingAskRange = false;
    /**
     * 本次 pending 操作是否已经提示过「请先登录」。
     * 被重定向到登录页时 pending 标记会保留（等登录完自动续跑），
     * 登录页可能连续加载几个 URL，没有这个标记会反复弹 Toast。
     */
    private boolean loginHintShown = false;

    /* ---------- 记住密码自动登录 ---------- */
    /** 已尝试的自动登录次数：密码错了 CAS 会重新渲染登录页，试 3 次就交还用户，防止死循环 */
    private int autoLoginTries = 0;
    /** 登录页弹了验证码：自动登录帮不上忙，交还用户；登录成功后复位 */
    private boolean captchaHold = false;
    /** 本会话是否出现过统一身份认证登录页（用于判断「该提醒补录密码」的时机） */
    private boolean loginPageSeen = false;
    /** 用户是否主动要求进入教务/登录流程；启动后台会话探测不算。 */
    private boolean loginRequested = false;
    /** 「自动登录未生效」补救提示是否已经给过（每次会话最多一次，不连环打扰） */
    private boolean autoLoginMissHintShown = false;
    /**
     * 自动登录引导提示是否已经弹过（每次会话一次就够）。
     * 开关打开但还没存过密码时，用户不知道「这次登录会被记住」——
     * 在登录页提示一次，之后同会话不再打扰；登录成功也不复位，
     * 因为密码已经存下来了，提示没有第二次意义。
     */
    private boolean autoLoginHintShown = false;

    /* ---------- 通道 2 网站导入空教室 ---------- */
    /** 正在从通道 2 抓数据：webView 的 onPageFinished 据此分流（浏览 vs 导入） */
    private boolean webImportBusy = false;
    private int webImportTries = 0;
    /** 本次导入是否用户手动发起（失败时要明确提示；启动的自动导入则静默） */
    private boolean webImportManual = false;
    /**
     * 本次浏览的入口落地页（重定向后的最终地址）。
     * 返回键用它判断「是否还在入口页」：不在就 goBack，在就退出 ——
     * 对入口重定向免疫，也不会把上一次浏览的历史带进来。
     */
    private String webEntryUrl = "";
    /** 本次会话是否已经提示过「每次打开自动导入」：问一次就够了 */
    private boolean autoImportPromptShown = false;

    /** 网站上的 6 个时段，按本机格式的标签写法（顺序即本机缓存里的顺序，缺「昼间1-8节」由交集补齐） */
    private static final String[] SLOT_LABELS_WEB = {
            "上午1-2节", "上午3-4节", "下午5-6节", "下午7-8节", "晚上9-10节", "晚上11-12节"
    };

    /**
     * 通道 2 数据提取脚本（运行在 tsiao.io 页面里）。
     *
     * 站点是静态渲染的：7 天 × 6 时段的空教室摆在固定 id 的单元格中
     * （day-{i}-GXG{层}F{时段}、day-{i}-JCL{时段} …），单元格文本就是
     * 空格分隔的房间号列表。这里逐格读出来，用 inject.js 导出的同一套
     * 清洗/过滤规则（nqCleanName / nqShouldKeep）整理成和教务抓取一致的
     * buildings 结构，日期基准取页面的「更新时间」（数据属于哪一天以生成为准）。
     */
    private static final String WEB_EXTRACT_JS =
            "(function(){"
            + "if(!window.nqCleanName||!window.nqShouldKeep)return{ok:false,error:'no-helper'};"
            + "var up=document.querySelector('#update-time-placeholder');"
            + "var m=up?((up.textContent||'').match(/(\\d{4})\\/(\\d{2})\\/(\\d{2})/)):null;"
            + "if(!m)return{ok:false,error:'no-date'};"
            + "function pad(n){return (n<10?'0':'')+n}"
            + "var base=new Date(+m[1],+m[2]-1,+m[3]);"
            + "function dstr(d){return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())}"
+ "function wd(d){return '日一二三四五六'.charAt(d.getDay())}"
            + "var SLOTS=['1-2','3-4','5-6','7-8','9-10','11-12'];"
            + "var LABEL={'1-2':'上午1-2节','3-4':'上午3-4节','5-6':'下午5-6节',"
            + "'7-8':'下午7-8节','9-10':'晚上9-10节','11-12':'晚上11-12节'};"
            + "var OTHER={基础楼:'JCL',综合实验楼:'ZHSYL',地质楼:'DZL',管理楼:'GLL',科技楼:'KJL',人文楼:'RWL'};"
            + "function rooms(txt){if(!txt)return[];var t=txt.replace(/\\u00a0/g,' ').trim();"
            + "if(!t||t==='无')return[];return t.split(/[\\s]+/).filter(function(x){return x&&x!=='无'})}"
            + "function cell(id){var e=document.getElementById(id);return e?(e.textContent||''):''}"
            + "var days=[];"
            + "for(var i=0;i<7;i++){"
            + "var cont=document.getElementById('day-'+i+'-content');if(!cont)break;"
            + "var d=new Date(base.getTime());d.setDate(base.getDate()+i);"
            + "var slots=[];"
            + "for(var s=0;s<SLOTS.length;s++){var slot=SLOTS[s];var g={};var cnt=0;"
            + "var gxg=[];"
            + "for(var f=1;f<=7;f++){var arr=rooms(cell('day-'+i+'-GXG'+f+'F'+slot));"
            + "for(var k=0;k<arr.length;k++){var nm=window.nqCleanName('工学馆',arr[k]);"
            + "if(window.nqShouldKeep('工学馆',nm,'多媒体','60')){gxg.push(nm);cnt++}}}"
            + "if(gxg.length)g['工学馆']=gxg;"
            + "for(var b in OTHER){var arr2=rooms(cell('day-'+i+'-'+OTHER[b]+slot));var list=[];"
            + "for(var k2=0;k2<arr2.length;k2++){var nm2=window.nqCleanName(b,arr2[k2]);"
            + "if(window.nqShouldKeep(b,nm2,'多媒体','60')){list.push(nm2);cnt++}}"
            + "if(list.length)g[b]=list;}"
            + "slots.push({label:LABEL[slot],ok:true,count:cnt,buildings:g});"
            + "}"
            + "days.push({date:dstr(d),weekday:wd(d),throttle:false,slots:slots});"
            + "}"
            + "if(!days.length)return{ok:false,error:'no-days'};"
            + "return{ok:true,days:days,updated:up?(up.textContent||'').trim():''}"
            + "})()";

    /**
     * 抓取钩子：以事件委托挂在 document 的捕获阶段，用户点「登录」/表单提交的瞬间
     * 把输入框原文发给 App。只在开关打开时注入；页面自身的加密逻辑
     * （encrypt.wisedu.js）在提交阶段才跑，捕获阶段读到的是用户输入的原文，
     * 随后页面照常加密提交，互不干扰。
     *
     * v3.7.4 修的坑：旧版把监听器挂在 #casLoginForm / .submitBtn 节点上，
     * 但 CAS 页面有 1 秒淡入 + VPN 脚本可能重建/自刷新 DOM —— 节点一换，
     * 监听器随旧节点一起消失，用户点登录时钩子已经不在了（静默失败，
     * 表现为「自动登录未生效」）。事件委托挂在 document 上与具体节点无关，
     * 表单怎么重建都逮得到。
     */
    private static final String CRED_HOOK_JS =
            "(function(){if(window.__nqHook)return;window.__nqHook=1;"
            + "var last=0;"
            + "function vis(e){return e&&(e.offsetWidth>0||e.offsetHeight>0);}"
            + "function meta(e){return ((e.id||'')+' '+(e.name||'')+' '+(e.placeholder||'')).toLowerCase();}"
            + "function bad(m){return m.indexOf('cap')>=0||m.indexOf('code')>=0||m.indexOf('verif')>=0;}"
            // 通用字段识别：不管登录页是统一身份认证还是 WebVPN 门户自己的表单，
            // 主文档找不到就扫同源 iframe（WebVPN 常把登录页放进框架里）
            + "function docs(){var a=[document],f=document.querySelectorAll('iframe'),i;"
            + "for(i=0;i<f.length;i++){try{var d=f[i].contentDocument;if(d)a.push(d);}catch(e){}}return a;}"
            + "function send(){"
            + "try{"
            + "if(Date.now()-last<2500)return;"
            + "var ds=docs(),k,i,pw=null,f=null;"
            + "for(k=0;k<ds.length;k++){"
            + "var ins=ds[k].querySelectorAll('input');"
            + "for(i=0;i<ins.length;i++){"
            + "var t=(ins[i].getAttribute('type')||'text').toLowerCase();"
            + "if(t==='password'&&vis(ins[i])&&ins[i].value){pw=ins[i];break;}}"
            + "if(pw){f=pw.form||ds[k];break;}}"
            + "if(!pw)return;"
            + "var cand=(f||document).querySelectorAll('input'),u=null;"
            + "for(i=0;i<cand.length;i++){"
            + "var e=cand[i],t2=(e.getAttribute('type')||'text').toLowerCase();"
            + "if(t2!=='text'&&t2!=='tel'&&t2!=='email'&&t2!=='')continue;"
            + "if(!vis(e))continue;if(bad(meta(e)))continue;u=e;break;}"
            + "if(!u||!u.value)return;"
            + "last=Date.now();"
            + "Android.onCapturedCredentials(u.value,pw.value);"
            + "}catch(e){}}"
            + "document.addEventListener('submit',send,true);"
            // 点击委托：只认提交类按钮，避免点「忘记密码」等把半截密码记下来
            + "document.addEventListener('click',function(e){"
            + "var t=e.target;"
            + "if(t&&t.closest&&t.closest('button,input[type=submit],.submitBtn,#login-button'))send();"
            + "},true);"
            + "})()";

    private String singleDate = "";
    private String singleLabel = "";
    private int singleTb = 1;
    private int singleTe = 2;
    private int singleDay = 1;
    private String highlightDate = "";      // 从课表跳过来时，要高亮的那一天
    private int highlightTb = 0;            // 高亮的起始节次（0=不高亮），用于顶部提示文案
    /** 高亮时段在 days[].slots 数组里的下标（-1=不高亮）—— 与节次号是两回事，分开记 */
    private int highlightSlotIdx = -1;
    private boolean singleMode = false;     // 当前结果页是不是「单时段」视图（带时段快捷切换）
    private String singleWeekday = "";      // 单时段视图的星期，用于时段快捷按钮重查

    private static final String PREFS = "neuq_prefs";
    private static final String KEY_TERM_START = "termStart_";    // 第 1 周周一，毫秒
    private static final int REQUEST_THEME_IMAGE = 6211;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loginView = findViewById(R.id.loginView);
        resultView = findViewById(R.id.resultView);
        themeBackgroundImage = findViewById(R.id.themeBackgroundImage);
        setupEamsBrowserOverlay();
        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);
        btnRefreshData = findViewById(R.id.btnRefreshData);
        btnImportTop = findViewById(R.id.btnImportTop);
        btnMoreTop = findViewById(R.id.btnMoreTop);
        tabClassroom = findViewById(R.id.tabClassroom);
        tabSchedule = findViewById(R.id.tabSchedule);
        tabMore = findViewById(R.id.tabMore);
        indClassroom = findViewById(R.id.indClassroom);
        indSchedule = findViewById(R.id.indSchedule);
        indMore = findViewById(R.id.indMore);
        iconClassroom = findViewById(R.id.iconClassroom);
        iconSchedule = findViewById(R.id.iconSchedule);
        iconMore = findViewById(R.id.iconMore);
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
        setupSchedSubPages();
        setupWebBrowser();
        applyTheme();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(loginView, true);
        // 持久化 Cookie（含 WebVPN / CAS 会话票据），下次启动仍是登录态
        cm.setAcceptThirdPartyCookies(resultView, true);
        cm.setAcceptThirdPartyCookies(webView, true);
        cm.flush();

        // 顶栏动作（按 Tab 分工，显隐由 applyUi 一处决定）：
        //   空教室页 —— 刷新（重新查询空闲教室，未登录先走登录，登录后再让选范围）
        //   课表页   —— 导入课表（从教务抓取）+ 更多（课表设置面板）
        btnRefreshData.setOnClickListener(v -> refreshRooms());
        btnImportTop.setOnClickListener(v -> startScheduleImport());
        btnMoreTop.setOnClickListener(v -> showSchedulePanel());
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
                // 回到教务页 = 这次登录（不管自动还是手动）成功了，自动登录的计数复位
                if (url != null && url.contains("/eams/")) {
                    autoLoginTries = 0;
                    captchaHold = false;
                    // 开了自动登录但登录成功后仍没存下密码 = 抓取没成功。
                    // 这时候「没生效」的原因用户自己是看不出来的，必须明说怎么补救。
                    // 只在本会话真的出现过登录页时才提示（开了开关但还没登录过时不打扰）。
                    if (loginPageSeen && CredentialStore.isEnabled(MainActivity.this)
                            && !CredentialStore.has(MainActivity.this) && !autoLoginMissHintShown) {
                        autoLoginMissHintShown = true;
                        statusText.setText("自动登录未生效 · 请到「更多」里输入一次账号密码");
                        Toast.makeText(MainActivity.this,
                                "自动登录还没建立：请到「更多 → 记住密码并自动登录」"
                                        + "点一下，输入一次账号密码即可",
                                Toast.LENGTH_LONG).show();
                    }
                }
                // 统一身份认证登录页：开了「记住密码」就走自动登录 / 挂抓取钩子。
                // 注意要放在 pending 早退之前 —— 启动时的后台加载（无 pending）也会
                // 撞上登录页，那正是自动登录最有用的场景。
                // v3.7.4：WebVPN 门户自己的登录页（/login）也一并挂钩 ——
                // 不同账号状态下的登录页可能落在其中任意一个。
                if (url != null && (url.contains("authserver/login")
                        || url.contains("vpn.neuq.edu.cn/login"))) {
                    handleLoginPage(view, url);
                }
                if (!(pendingQuery || pendingTerms || pendingSchedule || pendingSingle
                        || pendingAskRange)) return;
                // 被重定向到登录页（还没登录 / 会话过期）：
                // 关键点是**不清 pending 标记**。用户登完之后会回到教务页，
                // 那时同一个 onPageFinished 会再跑一遍，URL 落在 /eams/ 上，
                // 流程自己就接着往下走了 —— 用户不需要再点第二次。
                // 早先这里是清空标记 + 报「会话已过期」，用户登完发现什么都没发生，
                // 从表现上看就是「点了没反应」。
                if (url == null || url.indexOf("/eams/") < 0) {
                    progressBar.setVisibility(View.GONE);
                    setBusy(false);
                    if (!loginHintShown) {
                        loginHintShown = true;
                        // 空教室查询是「先登录再选范围」，提示要说清楚下一步是什么，
                        // 否则用户登完不知道还要选一次范围
                        String hint = pendingAskRange
                                ? "请先登录教务，登录后选择查询范围"
                                : "请先登录教务系统，登录后会自动继续";
                        statusText.setText(hint);
                        Toast.makeText(MainActivity.this, hint, Toast.LENGTH_SHORT).show();
                    } else {
                        statusText.setText("等待登录教务系统…");
                    }
                    return;
                }
                // 已经站在教务页上：本次操作所需的会话就绪，复位提示状态
                loginHintShown = false;
                // 登录完成，轮到问查询范围了（用户要求范围选择必须在登录之后）
                if (pendingAskRange) {
                    pendingAskRange = false;
                    setBusy(false);
                    statusText.setText("已登录 · 请选择查询范围");
                    askDays();
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
                    executeSingleSlotQuery();
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
     *  1. 读当前课表缓存 → 有则立刻渲染「本周」课表，无需联网、无需登录
     *  2. 无课表 → 显示空态，引导点顶栏「导入课表」
     *  3. 同时后台加载教务页，让 Cookie 有机会自动续期
     *
     * 注意：每次启动都把 weekOffset 归零 —— 上次翻到第 5 周是临时查看行为，
     * 重进 App 应该回到本周。用户翻周只影响当次会话，不跨启动持久化。
     */
    private void tryAutoLoginThenLoad() {
        loadCurrentSchedule();   // 内部已把 weekOffset 归零

        if (scheduleData != null) {
            renderSchedule();
            statusText.setText(ScheduleCache.currentName(this) + " · 第 " + currentWeek() + " 周");
        } else {
            renderSchedulePage();   // 空态文案带上当前课表的名字
        }

        // 后台加载登录页，让 Cookie 有机会自动续期
        loginView.loadUrl(HOME_URL);

        // 用户设置过「每次打开自动从通道2导入」：延迟几秒再后台静默刷新。
        // 不延迟的话，App 一启动就用隐藏 WebView 拉一个 1MB+ 的页面，
        // 和课表首屏抢渲染资源 —— 用户感知就是「打开软件卡」。
        if (prefs().getBoolean(KEY_AUTO_IMPORT_WEB, false)) {
            mainHandler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) importRoomsFromWeb(false);
            }, 4000);
        }

        // 自动检查更新：每天最多请求一次 GitHub Releases。
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) maybeCheckForUpdates();
        }, 2500);
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

    /** 内嵌浏览器的装配与交互（只调一次，在 onCreate 里） */
    private void setupWebBrowser() {
        webPage = findViewById(R.id.webPage);
        webView = findViewById(R.id.webView);
        webUrlText = findViewById(R.id.webUrlText);
        webProgress = findViewById(R.id.webProgress);
        btnWebBack = findViewById(R.id.btnWebBack);
        btnWebReload = findViewById(R.id.btnWebReload);
        btnWebOpenOuter = findViewById(R.id.btnWebOpenOuter);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // 加载流畅度：通道页是大体积静态页（7 天数据内联在 HTML 里），
        // 这几项能省掉不必要的布局/缩放开销
        s.setTextZoom(100);                 // 跟随系统字体缩放会触发整页重排
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);   // 静态页吃 HTTP 缓存，二次打开快很多

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                webProgress.setProgress(newProgress);
                webProgress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                               WebResourceRequest request) {
                // 通道2数据已经内联在主 HTML 中。导入时阻断字体、CSS、图片和脚本，
                // 避免为了读取表格文本加载整站主题资源。
                if (webImportBusy && request != null && !request.isForMainFrame()) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null) webUrlText.setText(prettyHost(url));

                // 记录入口落地页：入口 URL 若有重定向，这里拿到的是最终地址，
                // 它就是本次浏览的「根」—— 返回键退到根就直接出浏览器。
                // 不用 clearHistory()：它在 onPageFinished 里调用存在时序竞态
                // （后退列表可能在清除后又被 Chromium 提交），实测清不干净。
                if (!webImportBusy && webEntryUrl.isEmpty() && url != null) {
                    webEntryUrl = url;
                }

                // 通道 2 数据导入：页面渲染完成后等 SPA 稳定再提取
                if (webImportBusy) scheduleWebImportExtract(view, url);
            }
        });

        // 返回：不在入口页就退网页，退到入口页（或重定向后的落地页）就回「更多」
        btnWebBack.setOnClickListener(v -> hideWebBrowser());
        btnWebReload.setOnClickListener(v -> webView.reload());
        btnWebOpenOuter.setOnClickListener(v -> {
            String u = webView.getUrl();
            if (u != null) openUrl(u);
        });
    }

    /**
     * 关掉内嵌浏览器，回到「更多」页。
     *
     * 返回键语义：浏览器的「根」是本次打开通道时的落地页 ——
     *  · 不在根上（用户在站内点过链接）→ goBack 在站内后退；
     *  · 已经在根上 → 直接退出浏览器。
     * 这样即使 WebView 历史里残留着上一次通道的页面（clearHistory 有竞态清不干净），
     * 也不会「按一下返回冒出另一个通道」。
     */
    private void hideWebBrowser() {
        String cur = webView.getUrl();
        boolean atRoot = sameSitePage(cur, webEntryUrl);
        if (!atRoot && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        webView.stopLoading();
        webPage.setVisibility(View.GONE);
        morePage.setVisibility(View.VISIBLE);
        showMore();
    }

    /** 同一「页面」判定：同主机且同路径（忽略结尾斜杠与查询/锚点），用于识别入口落地页 */
    private static boolean sameSitePage(String a, String b) {
        if (a == null || b == null || !a.startsWith("http") || !b.startsWith("http")) return false;
        try {
            android.net.Uri ua = android.net.Uri.parse(a);
            android.net.Uri ub = android.net.Uri.parse(b);
            String ha = ua.getHost(), hb = ub.getHost();
            if (ha == null || !ha.equals(hb)) return false;
            return normalizePath(ua.getPath()).equals(normalizePath(ub.getPath()));
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizePath(String p) {
        if (p == null || p.isEmpty()) return "/";
        return (p.endsWith("/") && p.length() > 1) ? p.substring(0, p.length() - 1) : p;
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
        highlightSlotIdx = -1;
        singleMode = false;        // 整表视图，不是单时段
        statusText.setText("正在打开教务查询页…");
        setBusy(true);
        progressBar.setProgress(0);
        // 先加载教务的空闲教室查询页（与浏览器操作一致，确保会话上下文正确），
        // 页面加载完成后在 onPageFinished 里注入脚本抓取
        pendingQuery = true;
        // 底部进度卡片随操作一起出发：整表查询逐时段串行、耗时较长，
        // 先把「要等多久、可以干嘛」告诉用户
        showProgressDialog("正在查询空教室",
                "实时取自教务系统 · 逐时段串行查询，可随时切到别的页面等待结果");
        loginHintShown = false;   // 新的一次操作，登录提示重新开始算
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
        boolean schedule = (currentTab == TAB_SCHEDULE);

        // 各状态下的按钮可见性
        boolean vRefreshData = false, vImport = false, vMoreTop = false;

        switch (state) {
            case LOGIN:
            case ROOM_EMPTY:
            case ROOM_DATA:
            case BUSY:
                // 空教室页：只放「刷新」。导入课表已按用户要求移到课表页 —— 导入的对象是课表，
                // 入口就该在课表页，空教室页顶栏多一个不相干的按钮反而要解释。
                if (room) {
                    vRefreshData = true;
                }
                // 课表页：导入 + 更多。四个状态下都给，是因为这页的内容可能还在加载 /
                // 还没登录，按钮忽隐忽现比一直放着更让人迷惑。
                if (schedule) {
                    vImport = true;
                    vMoreTop = true;
                }
                break;
            case SCHEDULE_EMPTY:
            case SCHEDULE_DATA:
                // 课表页顶栏两个动作：导入课表（重新抓取 / 换学期）+ 更多（课表设置面板）。
                // 空课表时也要给导入 —— 这正是最需要导入的时候。
                if (schedule) {
                    vImport = true;
                    vMoreTop = true;
                }
                break;
            case MORE:
                // 更多页是设置列表，顶栏不放业务按钮，避免和页面内的按钮打架
                break;
            case WEB:
                // 内嵌浏览器自带标题栏（返回 / 刷新 / 外开），顶栏再放按钮就重复了
                break;
        }

        btnRefreshData.setVisibility(vRefreshData ? View.VISIBLE : View.GONE);
        btnImportTop.setVisibility(vImport ? View.VISIBLE : View.GONE);
        btnMoreTop.setVisibility(vMoreTop ? View.VISIBLE : View.GONE);

        // 忙碌时禁用可点的按钮，避免重复触发
        boolean busy = (state == UiState.BUSY);
        btnRefreshData.setEnabled(!busy);
        btnImportTop.setEnabled(!busy);
        btnMoreTop.setEnabled(!busy);
    }

    /**
     * 四个内容视图（教务 WebView / 结果 WebView / 更多页 / 内嵌浏览器）互斥显示。
     *
     * 之前是各处只把自己关心的那个 GONE 掉，结果「更多」页点教务处登录没反应 ——
     * morePage 还盖在最上层。全部收敛到这一个方法，以后加视图也不会再漏。
     */
    private void showContentView(View target) {
        if (target == null) return;
        View[] all = {loginView, resultView, morePage, webPage,
                schedSettingsPage, courseManagerPage};
        for (View v : all) {
            if (v != null && v != target) v.setVisibility(View.GONE);
        }
        if (target.getVisibility() != View.VISIBLE) {
            float offset = 10f * getResources().getDisplayMetrics().density;
            target.setAlpha(0f);
            target.setTranslationY(offset);
            target.setVisibility(View.VISIBLE);
            target.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private void setupEamsBrowserOverlay() {
        eamsBrowserOverlay = findViewById(R.id.eamsBrowserOverlay);
        eamsAddressInput = findViewById(R.id.eamsAddressInput);
        loginView = findViewById(R.id.loginView);
        View anchor = findViewById(R.id.eamsWebAnchor);

        // 把 WebView 移进覆盖层，这样地址栏、悬浮按钮与教务网页一起出现/消失。
        if (anchor != null && loginView.getParent() != anchor.getParent()) {
            ((ViewGroup) anchor.getParent()).addView(loginView, 0,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
            anchor.setVisibility(View.GONE);
        }

        findViewById(R.id.btnEamsGo).setOnClickListener(v -> {
            String url = eamsAddressInput.getText().toString().trim();
            if (url.isEmpty()) return;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            loginView.loadUrl(url);
        });
        findViewById(R.id.btnEamsHelp).setOnClickListener(v -> showEamsBrowserHelp());
        findViewById(R.id.tvEamsPcMode).setOnClickListener(v ->
                Toast.makeText(this, "请把地址改为学校教务系统电脑版入口",
                        Toast.LENGTH_SHORT).show());
        findViewById(R.id.tvEamsPasswordHelp).setOnClickListener(v ->
                Toast.makeText(this,
                        "若密码一直错误，请到学校统一身份认证页面重置密码",
                        Toast.LENGTH_LONG).show());
        findViewById(R.id.btnEamsBack).setOnClickListener(v -> {
            if (loginView.canGoBack()) {
                loginView.goBack();
            } else {
                setEamsBrowserOverlay(false);
            }
        });
    }

    private void setEamsBrowserOverlay(boolean show) {
        if (eamsBrowserOverlay != null) {
            eamsBrowserOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showEamsBrowserHelp() {
        new AlertDialog.Builder(this)
                .setTitle("教务浏览器说明")
                .setMessage("1. 输入学校教务系统网址并打开；\n"
                        + "2. 登录统一身份认证；\n"
                        + "3. 已登录后，点右下角“下载”读取导入数据。\n"
                        + "“记住密码”开启时会自动填入账号密码。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showLogin() {
        showContentView(loginView);
        setEamsBrowserOverlay(true);
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
        showContentView(resultView);
        setEamsBrowserOverlay(false);
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
                // 顶栏小进度条之外，同步推进底部进度卡片（没弹出时是 no-op）
                updateProgressDialog(cur, total, "查询中 " + cur + "/" + total
                        + (msg == null || msg.isEmpty() ? "" : " · " + msg));
            });
        }

        /**
         * 「记住密码」抓取回调：用户在统一身份认证页点了「登录」的一瞬间触发。
         * 开关关闭时不保存（钩子也只会在开关打开时注入，这里是双保险）。
         */
        @JavascriptInterface
        public void onCapturedCredentials(final String user, final String pass) {
            mainHandler.post(() -> {
                if (!CredentialStore.isEnabled(MainActivity.this)) return;
                boolean ok = CredentialStore.save(MainActivity.this, user, pass);
                if (ok) {
                    Toast.makeText(MainActivity.this,
                            "已记住账号，下次将自动登录", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this,
                            "此设备不支持安全存储，未保存密码", Toast.LENGTH_LONG).show();
                }
                refreshMorePage();
            });
        }

        /**
         * 自动登录每一步的结果回传（filled=已填并提交 / captcha=要验证码 / noform=没找到登录框）。
         * 之前填不上就静默 return，用户只看到「没生效」却不知道原因 —— 现在每一步可见。
         */
        @JavascriptInterface
        public void onAutoLoginResult(final String code) {
            mainHandler.post(() -> {
                if (code != null && code.startsWith("filled")) {
                    // 记下这次是「按统一身份认证 id」还是「通用表单识别」命中的，便于排查
                    boolean generic = code.endsWith(":generic");
                    statusText.setText(generic ? "正在自动登录（通用表单识别）…" : "正在自动登录…");
                } else if ("captcha".equals(code)) {
                    captchaHold = true;
                    statusText.setText("登录需要验证码，请手动完成");
                    Toast.makeText(MainActivity.this,
                            "本次登录需要验证码，请手动输入完成登录", Toast.LENGTH_LONG).show();
                } else if ("noform".equals(code)) {
                    statusText.setText("自动登录未找到登录框 · 请手动登录");
                    if (loginRequested) {
                        Toast.makeText(MainActivity.this,
                                "自动登录没找到登录框：请手动登录",
                                Toast.LENGTH_LONG).show();
                    }
                } else if ("nobutton".equals(code)) {
                    statusText.setText("自动登录未找到登录按钮 · 请手动登录");
                } else if ("stuck".equals(code)) {
                    // 填了也点了，但页面没走 —— 密码改了 / 被风控 / 页面用 AJAX 慢跳
                    statusText.setText("自动登录已提交但未跳转 · 请手动登录");
                    Toast.makeText(MainActivity.this,
                            "自动登录已提交，但登录页没有跳转：请手动登录；"
                                    + "若每次都这样，到「更多 → 记住密码并自动登录」更新密码",
                            Toast.LENGTH_LONG).show();
                }
                // 其他值（等待中）不打扰用户
            });
        }

        @JavascriptInterface
        public void onResult(final String json) {
            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                // 一次性收掉进度卡片：弹窗被用户收起后这里是 no-op（刻意设计，
                // 收起 ≠ 取消，后台查询照常跑完）
                dismissProgressDialog();
                setBusy(false);
                setEamsBrowserOverlay(false);
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
                // 第一阶段（取学期）结束就收卡片；选完学期后的抓取
                // 由 startScheduleFetch 重新弹出，文案带上学期名
                dismissProgressDialog();
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
                dismissProgressDialog();   // 导入结束（含失败分支走 showImportFailure），统一收卡片
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
                        showScheduleAfterImport(n, null);
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

        /* ---------- 空态页里的引导入口 ----------
           课表 / 空教室都没有数据时，空态页会渲染一个按钮直接调这里。
           走的是和「更多 → 教务处登录」完全相同的路径（打开教务 WebView），
           所以用户不用先自己找到「更多」再去登录。 */

        /** 空课表页的引导：登录教务处并导入课表 */
        @JavascriptInterface
        public void guideImportSchedule() {
            mainHandler.post(() -> startScheduleImport());
        }

        /** 空空教室页的引导：登录教务处并查询空教室 */
        @JavascriptInterface
        public void guideQueryRooms() {
            mainHandler.post(MainActivity.this::beginRoomQueryWithLogin);
        }
    }

    /**
     * 走「先登录、登录完成后再选查询范围」的空教室查询流程。
     *
     * 直接加载查询页来探会话：会话有效就停在 /eams/ 上，onPageFinished 里
     * 看到 pendingAskRange 就弹范围选择；会话失效会被重定向到登录页，
     * pending 标记保留，用户登完回到教务页时再接着弹。
     */
    private void beginRoomQueryWithLogin() {
        loginRequested = true;
        pendingAskRange = true;
        loginHintShown = false;
        showContentView(loginView);
        setEamsBrowserOverlay(true);
        eamsAddressInput.setText(EAMS_BASE + "classroom/apply/free!search.action");
        statusText.setText("请先登录教务，登录后选择查询范围");
        setBusy(true);
        progressBar.setProgress(0);
        loginView.loadUrl(EAMS_BASE + "classroom/apply/free!search.action");
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

    private String buildHtml(JSONObject o) throws Exception {
        JSONArray days = o.getJSONArray("days");
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            String weekday = day.optString("weekday");
            // 兼容旧缓存：旧通道数据曾存成“周三”，渲染端固定补“周”。
            if (weekday.startsWith("周") && weekday.length() > 1) {
                day.put("weekday", weekday.substring(1));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>东秦课表 · 空闲教室速查</title><style>").append(css).append("</style>");
        sb.append("<style>:root{--page-bg:").append(cssColor(ThemeStore.background(this)))
                .append(";--container-bg:").append(cssColor(ThemeStore.surface(this)))
                .append(";--brand:").append(cssColor(ThemeStore.accent(this)))
                .append(";}</style></head><body>");

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
            // 高亮「从课表跳过来」的那一个时段：按下标比，不能按节次号比 ——
            // 「3-4节」的节次号是 3，但它在 slots 数组里的下标是 1，混用会高亮错行
            boolean hit = isJumped && s == highlightSlotIdx;
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
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/wy723161060/neuq-classroom-app/releases/latest";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    private static final String KEY_LAST_UPDATE_NOTIFIED = "last_update_notified";
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final String[] CN_DOWNLOAD_PROXIES = {
            "https://gh-proxy.com/",
            "https://ghproxy.net/"
    };

    private volatile boolean updateCheckRunning = false;
    private UpdateInfo availableUpdate;
    private String updateRowText = "自动检查 GitHub Releases，每天最多一次";

    /*
     * 「空教室表网页」的两个通道 —— 同一份空闲教室总表的不同入口，
     * 一个站点被限流或被墙时可以换另一条走。
     *
     * 常量名直接带通道号，避免再出现「改对了链接、挂错了通道」——
     * 上一版就因为常量叫 WEB_SITE/WEB_MIRROR，把 tsiao.io 挂到了通道1。
     * 两条都实测过 HTTP 200 才写进来。
     */
    private static final String WEB_CH2 = "https://neuq.tsiao.io/";

    /** 用系统浏览器打开外部链接（仓库页这类，不适合内嵌） */
    private void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 在 App 内打开空闲教室总表网页。
     *
     * 走独立的 webView（不是教务那两个）：总表和教务是两套站点，
     * 共用 WebView 会把总表页面留成下一个用户的「上一次浏览会话」，
     * 也会让返回键的语义变得含糊（到底是在退网页还是在退 App）。
     */
    private void openWebTable(String url) {
        // 从「更多」进来的：当前 Tab 就是「更多」，高亮也要跟着落到它上面，
        // 否则底部会停留在上一个 Tab，看起来像「更多」没被选中。
        currentTab = TAB_MORE;
        applyTabHighlight(TAB_MORE);
        webEntryUrl = "";   // 每次打开通道都重置「根」，返回键的语义以本次落地页为准
        setWebImagesEnabled(true);   // 浏览要完整渲染，导入期间关掉的图片这里恢复
        webView.loadUrl(url);
        showContentView(webPage);
        webUrlText.setText(prettyHost(url));
        webProgress.setVisibility(View.VISIBLE);
        webProgress.setProgress(0);
        applyUi(UiState.WEB);
    }

    /** 只留主机名，长 URL 在窄屏上会把标题栏挤爆 */
    private String prettyHost(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            return h == null ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    /** 绑定「更多」页的行点击事件（只绑一次，在 onCreate 里调用） */
    private void setupMorePage() {
        morePage = findViewById(R.id.morePage);
        morePage.findViewById(R.id.rowLogin).setOnClickListener(v -> {
            showLogin();
            statusText.setText("教务系统 · 登录后回课表页点「导入课表」");
        });

        // 记住密码自动登录（v3.8.2 换方案）：
        // 账密由用户在本机 App 里明确输入一次，不再依赖从登录页抓 ——
        // 登录页被 WebVPN 改写、DOM 随时重建，抓取链路任何一环断掉都是静默失败，
        // 用户只能看到「不生效」。直接输入链路最短、最可控。
        Switch swAuto = morePage.findViewById(R.id.swAutoLogin);
        swAuto.setChecked(CredentialStore.isEnabled(this));
        swAuto.setOnCheckedChangeListener((b, on) -> {
            if (on) {
                CredentialStore.setEnabled(MainActivity.this, true);
                if (CredentialStore.has(this)) {
                    Toast.makeText(this, "已开启 · 下次会话过期时自动登录", Toast.LENGTH_SHORT).show();
                    refreshMorePage();
                } else {
                    askCredentials("输入一次账号密码");
                }
            } else {
                CredentialStore.setEnabled(MainActivity.this, false);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("关闭自动登录")
                        .setMessage("已保存的账号密码要一起删除吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            CredentialStore.clear(MainActivity.this);
                            Toast.makeText(MainActivity.this,
                                    "已删除保存的账号密码", Toast.LENGTH_SHORT).show();
                            refreshMorePage();
                        })
                        .setNegativeButton("保留", (d, w) -> refreshMorePage())
                        .show();
            }
        });

        // 点这一行 = 重新输入 / 清除已保存的账密
        morePage.findViewById(R.id.rowAutoLogin).setOnClickListener(v -> {
            if (CredentialStore.has(this)) {
                askCredentials("更新账号密码");
            } else {
                askCredentials("输入一次账号密码");
            }
        });

        morePage.findViewById(R.id.rowCache).setOnClickListener(v -> showCacheManager());
        morePage.findViewById(R.id.rowChannel2).setOnClickListener(v -> openWebTable(WEB_CH2));
        morePage.findViewById(R.id.rowTheme).setOnClickListener(v -> showThemeSheet());
        morePage.findViewById(R.id.rowUpdate).setOnClickListener(v -> {
            if (updateCheckRunning) {
                Toast.makeText(this, "正在检查更新，请稍候", Toast.LENGTH_SHORT).show();
            } else {
                checkForUpdates(true);
            }
        });
        morePage.findViewById(R.id.rowAppRepo).setOnClickListener(v -> openUrl(APP_REPO));
        morePage.findViewById(R.id.rowCopyDiag).setOnClickListener(v -> copyDiag());
        morePage.findViewById(R.id.rowHowto).setOnClickListener(v -> showHowto());
    }

    /** 切到「更多」页：刷新一遍其上的动态文案（版本号 / 缓存统计） */
    private void showMore() {
        refreshMorePage();
        showContentView(morePage);
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

        // 自动登录这一行的说明：说清楚现在记没记、记的是哪个账号
        TextView subAuto = morePage.findViewById(R.id.subAutoLogin);
        if (CredentialStore.has(this)) {
            String[] cred = CredentialStore.read(this);
            String who = cred != null ? CredentialStore.mask(cred[0]) : "已保存";
            subAuto.setText((CredentialStore.isEnabled(this) ? "已开启 · " : "已关闭 · ")
                    + "账号 " + who + " · 点此行可更新或清除");
        } else if (CredentialStore.isEnabled(this)) {
            subAuto.setText("已开启 · 还没保存账密，点此行输入");
        } else {
            subAuto.setText("未开启 · 开启后输入一次即可自动登录");
        }

        // 缓存统计
        long schedSize = ScheduleCache.sizeBytes(this);
        long roomSize = ResultCache.sizeBytes(this);
        long total = Math.max(schedSize, 0) + Math.max(roomSize, 0);
        boolean hasSchedule = (scheduleData != null || ScheduleCache.load(this) != null);
        TextView subCache = morePage.findViewById(R.id.subCache);
        subCache.setText("课表 " + (schedSize >= 0 ? sizeText(schedSize) : "无")
                + " · " + ScheduleCache.count(this) + " 张｜"
                + (hasSchedule ? ScheduleCache.ageText(this) : "未导入")
                + "　｜　空教室 " + (roomSize >= 0 ? sizeText(roomSize) : "无")
                + " · " + (roomSize >= 0 ? ResultCache.ageText(this) : "未查询"));
        TextView subTotal = morePage.findViewById(R.id.subCacheTotal);
        subTotal.setText("合计 " + sizeText(total) + "，全部存在本机");

        TextView subUpdate = morePage.findViewById(R.id.subUpdate);
        subUpdate.setText(updateRowText);
        morePage.findViewById(R.id.badgeUpdate).setVisibility(
                availableUpdate == null ? View.GONE : View.VISIBLE);

        TextView subTheme = morePage.findViewById(R.id.subTheme);
        boolean customTheme = ThemeStore.accent(this) != ThemeStore.DEFAULT_ACCENT
                || ThemeStore.background(this) != ThemeStore.DEFAULT_BACKGROUND
                || ThemeStore.surface(this) != ThemeStore.DEFAULT_SURFACE;
        subTheme.setText(customTheme ? "已应用自定义主题 · 点击调整" : "自定义主题色、页面底色和卡片背景");
    }

    /** 文件大小文案 */
    private static String sizeText(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.CHINA, "%.2f MB", bytes / 1024.0 / 1024.0);
    }

    /* ---------- 自动检查更新 ---------- */

    private static final class UpdateInfo {
        final String version;
        final String title;
        final String body;
        final String publishedAt;
        final String downloadUrl;
        final String releaseUrl;
        final String digest;
        final long size;
        final boolean hasUpdate;

        UpdateInfo(String version, String title, String body, String publishedAt,
                   String downloadUrl, String releaseUrl, String digest,
                   long size, boolean hasUpdate) {
            this.version = version;
            this.title = title;
            this.body = body;
            this.publishedAt = publishedAt;
            this.downloadUrl = downloadUrl;
            this.releaseUrl = releaseUrl;
            this.digest = digest;
            this.size = size;
            this.hasUpdate = hasUpdate;
        }

        String displayVersion() {
            return "v" + version;
        }
    }

    /** 启动时调用：距上次检查不足 24 小时就不重复请求。 */
    private void maybeCheckForUpdates() {
        long last = prefs().getLong(KEY_LAST_UPDATE_CHECK, 0L);
        if (System.currentTimeMillis() - last < UPDATE_CHECK_INTERVAL_MS) return;
        checkForUpdates(false);
    }

    /**
     * 请求 GitHub 最新 Release 并比较版本。
     *
     * @param manual true=用户主动点击，失败或已是最新都给提示；
     *               false=启动时静默检查，只在新版本首次出现时弹面板。
     */
    private void checkForUpdates(final boolean manual) {
        if (updateCheckRunning) return;
        updateCheckRunning = true;
        if (manual) {
            updateRowText = "正在检查 GitHub Releases…";
            refreshMorePage();
        }

        new Thread(() -> {
            UpdateInfo info = null;
            String error = null;
            try {
                info = fetchLatestRelease();
            } catch (Exception e) {
                error = e.getMessage();
            }

            final UpdateInfo result = info;
            final String failure = error;
            mainHandler.post(() -> {
                updateCheckRunning = false;
                prefs().edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
                if (isFinishing() || isDestroyed()) return;

                if (failure != null || result == null) {
                    updateRowText = "检查失败 · 点击重试";
                    refreshMorePage();
                    if (manual) {
                        Toast.makeText(MainActivity.this,
                                "检查更新失败：" + (failure == null ? "未知错误" : failure),
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }

                if (!result.hasUpdate) {
                    availableUpdate = null;
                    updateRowText = "当前已是最新版本 " + result.displayVersion() + " · 点击复查";
                    refreshMorePage();
                    if (manual) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("检查更新")
                                .setMessage("当前已是最新版本\n\n"
                                        + "当前版本：" + result.displayVersion())
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                    return;
                }

                availableUpdate = result;
                updateRowText = "发现新版本 " + result.displayVersion() + " · 点击查看";
                refreshMorePage();

                String notified = prefs().getString(KEY_LAST_UPDATE_NOTIFIED, "");
                if (manual || !result.version.equals(notified)) {
                    showUpdateDialog(result);
                    prefs().edit().putString(KEY_LAST_UPDATE_NOTIFIED, result.version).apply();
                }
            });
        }, "github-update-check").start();
    }

    /** 从 GitHub API 拉取并解析 latest release，调用线程不是主线程。 */
    private UpdateInfo fetchLatestRelease() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "neuq-classroom-app");

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            String raw = readStream(in);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub API HTTP " + code);
            }

            JSONObject release = new JSONObject(raw);
            if (release.optBoolean("draft", false)
                    || release.optBoolean("prerelease", false)) {
                throw new IOException("最新版本不是正式发布");
            }

            String version = normalizeVersion(release.optString("tag_name", ""));
            if (version.isEmpty()) throw new IOException("Release 缺少版本号");

            String releaseUrl = release.optString("html_url", APP_REPO + "/releases/latest");
            if (!isTrustedReleaseUrl(releaseUrl)) releaseUrl = APP_REPO + "/releases/latest";

            String downloadUrl = releaseUrl;
            String digest = "";
            long size = -1L;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    String contentType = asset.optString("content_type", "");
                    if (!name.toLowerCase(Locale.US).endsWith(".apk")
                            && !contentType.contains("android.package-archive")) {
                        continue;
                    }
                    String candidate = asset.optString("browser_download_url", "");
                    if (!isTrustedReleaseUrl(candidate)) continue;
                    downloadUrl = candidate;
                    digest = asset.optString("digest", "");
                    if (digest.startsWith("sha256:")) digest = digest.substring(7);
                    size = asset.optLong("size", -1L);
                    break;
                }
            }

            boolean hasUpdate = compareVersions(version, currentVersionName()) > 0;
            return new UpdateInfo(
                    version,
                    release.optString("name", ""),
                    release.optString("body", ""),
                    release.optString("published_at", release.optString("created_at", "")),
                    downloadUrl,
                    releaseUrl,
                    digest,
                    size,
                    hasUpdate);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String currentVersionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null || v.isEmpty() ? "0.0.0" : v;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    private static String normalizeVersion(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (!s.isEmpty() && !Character.isDigit(s.charAt(0))) s = s.substring(1);
        return s;
    }

    /** 只比较前三段数字，3.9、v3.9.0、3.9.0-beta 都视作 3.9.0。 */
    private static int compareVersions(String newer, String current) {
        String[] a = normalizeVersion(newer).split("\\D+");
        String[] b = normalizeVersion(current).split("\\D+");
        for (int i = 0; i < 3; i++) {
            int av = versionPart(a, i);
            int bv = versionPart(b, i);
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int versionPart(String[] parts, int index) {
        if (parts == null || index >= parts.length || parts[index].isEmpty()) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isTrustedReleaseUrl(String url) {
        return url != null
                && url.startsWith("https://github.com/wy723161060/neuq-classroom-app/");
    }

    /** 弹出与项目现有抽屉风格一致的更新面板。 */
    private void showUpdateDialog(final UpdateInfo info) {
        View sheet = LayoutInflater.from(this).inflate(R.layout.sheet_update, null);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        applyThemeToView(sheet);
        dialog.setCanceledOnTouchOutside(true);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setGravity(Gravity.BOTTOM);
            win.setWindowAnimations(R.style.BottomSheetAnimation);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        ((TextView) sheet.findViewById(R.id.tvUpdateCurrent))
                .setText("当前版本 " + currentVersionName());
        ((TextView) sheet.findViewById(R.id.tvUpdateBadge))
                .setText("新版本 " + info.displayVersion());

        String date = formatReleaseDate(info.publishedAt);
        String size = info.size > 0 ? sizeText(info.size) : "大小未知";
        ((TextView) sheet.findViewById(R.id.tvUpdateMeta))
                .setText((date.isEmpty() ? "发布时间未知" : date) + "　　" + size);
        ((TextView) sheet.findViewById(R.id.tvUpdateLog))
                .setText(buildUpdateLog(info));
        TextView digest = sheet.findViewById(R.id.tvUpdateDigest);
        if (info.digest.isEmpty()) {
            digest.setVisibility(View.GONE);
        } else {
            digest.setText("SHA-256  " + info.digest);
            digest.setVisibility(View.VISIBLE);
        }

        Button download = sheet.findViewById(R.id.btnUpdateDownload);
        Button githubDownload = sheet.findViewById(R.id.btnUpdateRelease);
        boolean hasApkAsset = !info.downloadUrl.equals(info.releaseUrl);
        if (!hasApkAsset) {
            download.setText("打开 GitHub 发布页");
            githubDownload.setVisibility(View.GONE);
        }
        final String cnDownloadUrl = buildCnDownloadUrl(info.downloadUrl);
        download.setOnClickListener(v -> {
            openUrl(hasApkAsset ? cnDownloadUrl : info.releaseUrl);
            dialog.dismiss();
        });
        githubDownload.setOnClickListener(v -> {
            openUrl(hasApkAsset ? info.downloadUrl : info.releaseUrl);
            dialog.dismiss();
        });
        sheet.findViewById(R.id.tvUpdateRepo).setOnClickListener(v -> openUrl(info.releaseUrl));
        sheet.findViewById(R.id.btnUpdateClose).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /**
     * 将 GitHub 官方下载地址映射到国内代理。
     *
     * 这些代理不是官方服务，只作为主按钮的加速入口；面板会明确提示第三方属性，
     * 并保留「从 GitHub 下载」作为可信回退。代理站点可能失效，因此地址集中在这里。
     */
    private static String buildCnDownloadUrl(String githubUrl) {
        if (!isTrustedReleaseUrl(githubUrl)) return githubUrl;
        return CN_DOWNLOAD_PROXIES[0] + githubUrl;
    }

    private static String formatReleaseDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso);
            if (d == null) return iso.replace('T', ' ').replace("Z", "");
            SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            return out.format(d);
        } catch (Exception e) {
            return iso.replace('T', ' ').replace("Z", "");
        }
    }

    private static String buildUpdateLog(UpdateInfo info) {
        String body = info.body == null ? "" : info.body.trim();
        body = body.replace("\r\n", "\n")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("**", "")
                .replace("`", "");
        if (body.isEmpty()) body = "本次更新没有提供详细说明。";
        if (body.length() > 4000) body = body.substring(0, 4000) + "\n…";
        return "# " + info.displayVersion() + "\n\n" + body;
    }

    /* ---------- 主题与背景 ---------- */

    private void showThemeSheet() {
        View sheet = LayoutInflater.from(this).inflate(R.layout.sheet_theme, null);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        applyThemeToView(sheet);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setGravity(Gravity.BOTTOM);
            win.setWindowAnimations(R.style.BottomSheetAnimation);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        applyThemeToView(sheet);
        renderThemeSheet(sheet);

        sheet.findViewById(R.id.btnThemeReset).setOnClickListener(v -> {
            ThemeStore.reset(this);
            applyTheme();
            renderThemeSheet(sheet);
            sheet.findViewById(R.id.themeImageAlpha).setEnabled(false);
            sheet.findViewById(R.id.themeImageAlpha).setOnTouchListener((view, event) -> true);
            toastThemeApplied();
        });
        sheet.findViewById(R.id.btnThemeClose).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void renderThemeSheet(View sheet) {
        int accent = ThemeStore.accent(this);
        int background = ThemeStore.background(this);
        int surface = ThemeStore.surface(this);
        boolean hasImage = ThemeStore.hasImage(this);
        int imageAlpha = ThemeStore.imageAlpha(this);

        sheet.findViewById(R.id.themePreview).setBackgroundColor(surface);
        View accentBar = sheet.findViewById(R.id.themePreviewAccent);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(accent);
        barBg.setCornerRadius(4 * getResources().getDisplayMetrics().density);
        accentBar.setBackground(barBg);

        int[] previewColors = {
                accent,
                blendColor(accent, 0xFFFFFFFF, 0.42f),
                blendColor(accent, 0xFFF2A33A, 0.34f)
        };
        int[] previewIds = {
                R.id.themePreviewCourse1, R.id.themePreviewCourse2, R.id.themePreviewCourse3
        };
        for (int i = 0; i < previewIds.length; i++) {
            View course = sheet.findViewById(previewIds[i]);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(previewColors[i]);
            bg.setCornerRadius(9 * getResources().getDisplayMetrics().density);
            course.setBackground(bg);
        }

        addThemeSwatches(sheet.findViewById(R.id.themeAccentSwatches),
                ThemeStore.ACCENTS, accent, color -> {
                    ThemeStore.setAccent(this, color);
                    applyTheme();
                    renderThemeSheet(sheet);
                    toastThemeApplied();
                });
        addThemeSwatches(sheet.findViewById(R.id.themeBackgroundSwatches),
                ThemeStore.BACKGROUNDS, background, color -> {
                    ThemeStore.setBackground(this, color);
                    ThemeStore.clearImage(this);
                    applyTheme();
                    renderThemeSheet(sheet);
                    toastThemeApplied();
                });
        addThemeSwatches(sheet.findViewById(R.id.themeSurfaceSwatches),
                ThemeStore.SURFACES, surface, color -> {
                    ThemeStore.setSurface(this, color);
                    applyTheme();
                    renderThemeSheet(sheet);
                    toastThemeApplied();
                });

        TextView imageState = sheet.findViewById(R.id.themeImageState);
        imageState.setText(hasImage ? "已使用自定义背景图" : "未设置背景图");
        TextView alphaLabel = sheet.findViewById(R.id.themeImageAlphaLabel);
        alphaLabel.setText("背景图透明度 " + imageAlpha + "%");
        alphaLabel.setEnabled(hasImage);
        SeekBar alpha = sheet.findViewById(R.id.themeImageAlpha);
        alpha.setEnabled(hasImage);
        alpha.setProgress(imageAlpha);
        alpha.setOnTouchListener(hasImage ? null : (v, event) -> true);
        alpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                ThemeStore.setImageAlpha(MainActivity.this, value);
                alphaLabel.setText("背景图透明度 " + value + "%");
                applyTheme();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        sheet.findViewById(R.id.btnThemePickImage).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_THEME_IMAGE);
        });
    }

    private interface ThemeColorPicked {
        void onPicked(int color);
    }

    private void addThemeSwatches(LinearLayout container, int[] colors, int selected,
                                  ThemeColorPicked callback) {
        container.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        for (int color : colors) {
            FrameLayout outer = new FrameLayout(this);
            LinearLayout.LayoutParams outerLp = new LinearLayout.LayoutParams(
                    Math.round(42 * density), Math.round(42 * density));
            outerLp.leftMargin = Math.round(2 * density);
            outerLp.rightMargin = Math.round(2 * density);
            outer.setLayoutParams(outerLp);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(0x00000000);
            if (color == selected) {
                ring.setStroke(Math.round(2 * density),
                        ThemeStore.accent(this));
            }
            outer.setBackground(ring);
            outer.setOnClickListener(v -> callback.onPicked(color));

            View dot = new View(this);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                    Math.round(32 * density), Math.round(32 * density));
            dotLp.gravity = Gravity.CENTER;
            dot.setLayoutParams(dotLp);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(color);
            dotBg.setStroke(Math.round(1 * density), 0x22000000);
            dot.setBackground(dotBg);
            outer.addView(dot);
            container.addView(outer);
        }
    }

    private void toastThemeApplied() {
        Toast.makeText(this, "主题已应用", Toast.LENGTH_SHORT).show();
    }

    private void applyTheme() {
        int accent = ThemeStore.accent(this);
        int background = ThemeStore.background(this);
        int surface = ThemeStore.surface(this);

        View root = findViewById(R.id.mainRoot);
        View topBar = findViewById(R.id.topBar);
        View bottomNav = findViewById(R.id.bottomNav);
        if (root != null) root.setBackgroundColor(background);
        if (topBar != null) topBar.setBackgroundColor(background);
        if (bottomNav != null) bottomNav.setBackgroundColor(background);

        boolean hasImage = ThemeStore.hasImage(this);
        if (themeBackgroundImage != null) {
            if (hasImage) {
                android.graphics.Bitmap bmp = ThemeStore.image(this);
                if (bmp != null) {
                    themeBackgroundImage.setBackground(new android.graphics.drawable.BitmapDrawable(
                            getResources(), bmp));
                    themeBackgroundImage.setVisibility(View.VISIBLE);
                    themeBackgroundImage.getBackground().setAlpha(
                            ThemeStore.imageAlpha(this) * 255 / 100);
                } else {
                    themeBackgroundImage.setVisibility(View.GONE);
                }
            } else {
                themeBackgroundImage.setVisibility(View.GONE);
            }
        }

        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(surface);

        btnRefreshData.setImageTintList(ColorStateList.valueOf(accent));
        btnImportTop.setImageTintList(ColorStateList.valueOf(accent));
        btnMoreTop.setImageTintList(ColorStateList.valueOf(accent));
        progressBar.setProgressTintList(ColorStateList.valueOf(accent));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(blendColor(
                background, 0xFFD8DFE9, 0.55f)));

        View content = findViewById(android.R.id.content);
        if (content != null) {
            for (int id : new int[]{R.id.moreScroll, R.id.schedSettingsScroll}) {
                View scroll = content.findViewById(id);
                if (scroll != null) scroll.setBackgroundColor(0x00000000);
            }
            View courseManager = content.findViewById(R.id.courseManagerPage);
            if (courseManager != null) courseManager.setBackgroundColor(0x00000000);
            applyThemeToView(content);
        }
        applyTabHighlight(currentTab);

        if (currentTab == TAB_SCHEDULE && scheduleData != null
                && resultView.getVisibility() == View.VISIBLE) {
            renderSchedule();
        } else if (currentTab == TAB_CLASSROOM && resultView.getVisibility() == View.VISIBLE) {
            String cached = ResultCache.load(this);
            if (cached != null) {
                try {
                    JSONObject o = new JSONObject(cached);
                    o.put("fromCache", true);
                    o.put("cacheAgeText", ResultCache.ageText(this));
                    o.put("cacheExpired", ResultCache.isExpired(this));
                    o.put("staleDay", isCacheStaleToday(o));
                    showHtml(buildHtml(o));
                } catch (Exception ignored) {
                }
            }
        }
        refreshMorePage();
    }

    private void applyThemeToView(View view) {
        if (view == null) return;
        int surface = ThemeStore.surface(this);
        android.graphics.drawable.Drawable bg = view.getBackground();
        if (bg instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) bg;
            ColorStateList colorState = shape.getColor();
            int current = colorState == null ? 0 : colorState.getDefaultColor();
            boolean marked = Boolean.TRUE.equals(view.getTag(R.id.theme_surface_tag));
            if (marked || isThemeSurfaceColor(current)) {
                android.graphics.drawable.Drawable changed = shape.mutate();
                ((GradientDrawable) changed).setColor(surface);
                view.setBackground(changed);
                view.setTag(R.id.theme_surface_tag, true);
            }
        }
        if (view instanceof ViewGroup && !(view instanceof WebView)) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyThemeToView(group.getChildAt(i));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_THEME_IMAGE
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (ThemeStore.importImage(this, data.getData())) {
                applyTheme();
                Toast.makeText(this, "自定义背景图已应用", Toast.LENGTH_SHORT).show();
            } else {
                applyTheme();
                Toast.makeText(this, "背景图读取失败，请换一张图片", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static boolean isThemeSurfaceColor(int color) {
        if (android.graphics.Color.alpha(color) < 240) return false;
        return android.graphics.Color.red(color) >= 242
                && android.graphics.Color.green(color) >= 242
                && android.graphics.Color.blue(color) >= 242;
    }

    private static int blendColor(int base, int overlay, float amount) {
        float keep = 1f - Math.max(0f, Math.min(1f, amount));
        int r = Math.round(android.graphics.Color.red(base) * keep
                + android.graphics.Color.red(overlay) * amount);
        int g = Math.round(android.graphics.Color.green(base) * keep
                + android.graphics.Color.green(overlay) * amount);
        int b = Math.round(android.graphics.Color.blue(base) * keep
                + android.graphics.Color.blue(overlay) * amount);
        return android.graphics.Color.rgb(r, g, b);
    }

    private static int darkenColor(int color, float factor) {
        int r = Math.round(android.graphics.Color.red(color) * factor);
        int g = Math.round(android.graphics.Color.green(color) * factor);
        int b = Math.round(android.graphics.Color.blue(color) * factor);
        return android.graphics.Color.rgb(r, g, b);
    }

    private static String cssColor(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }

    /* ---------- 缓存管理弹窗 ---------- */

    /** 缓存管理：显示两项缓存的大小/时间，可分别清除 */
    /**
     * 缓存管理：底部卡片式抽屉。
     *
     * 每条缓存给出「体积 + 条数 + 抓取时间 + 占用比例条」，
     * 清理按钮独立成行（原来是列表项，点一下就清，容易误触）。
     *
     * 用系统 Dialog 而不是 BottomSheetDialog：本项目零第三方依赖
     * （见 app/build.gradle 末尾），为一个抽屉引入 material 库不划算。
     */
    /* ---------- 导入/查询进度卡片 ---------- */

    /**
     * 弹出（或刷新）底部进度卡片。
     * 已在显示时只更新文案：同一时刻只会有一个进行中的操作，
     * 重复 new Dialog 会在旧弹窗上再摞一层，没必要。
     * 换操作复用弹窗时必须把进度/状态行一并归零 —— 否则上个操作
     * 跑到 86% 就点新的查询，卡片会从旧进度直接起步。
     */
    private void showProgressDialog(String title, String hint) {
        if (progressDialog != null && progressDialog.isShowing()) {
            ((TextView) progressDialog.findViewById(R.id.pgTitle)).setText(title);
            ((TextView) progressDialog.findViewById(R.id.pgHint)).setText(hint);
            ((ProgressBar) progressDialog.findViewById(R.id.pgBar)).setProgress(0);
            ((TextView) progressDialog.findViewById(R.id.pgMsg)).setText("…");
            return;
        }
        // 换了操作但旧弹窗还挂着（理论上 dismiss 都已接好，这里兜底）：
        // 先收掉再开新的，避免引用失效
        dismissProgressDialog();

        View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
        ((TextView) sheet.findViewById(R.id.pgTitle)).setText(title);
        ((TextView) sheet.findViewById(R.id.pgHint)).setText(hint);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        applyThemeToView(sheet);
        Window win = dialog.getWindow();
        if (win != null) {
            // 贴底、通栏：与缓存管理抽屉同一套观感（见 showCacheManager）
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setGravity(Gravity.BOTTOM);
            win.setWindowAnimations(R.style.BottomSheetAnimation);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        // 可取消但只「收起」：按返回键只是把卡片收走，后台查询/导入继续跑，
        // 结果回来后照常渲染。onResult 里的 dismissProgressDialog 对已收起的
        // 弹窗是 no-op，这是刻意设计 —— 用户不该为了看进度被锁在当前页面。
        dialog.setCancelable(true);
        dialog.setOnCancelListener(d -> progressDialog = null);
        dialog.show();
        progressDialog = dialog;
    }

    /** 推进进度卡片。弹窗不在就静默忽略（不主动再弹，避免打扰收起了它的用户） */
    private void updateProgressDialog(int cur, int total, String msg) {
        if (progressDialog == null) return;
        ProgressBar bar = progressDialog.findViewById(R.id.pgBar);
        bar.setMax(Math.max(total, 1));
        bar.setProgress(cur);
        ((TextView) progressDialog.findViewById(R.id.pgMsg)).setText(msg);
    }

    /** 收起进度卡片；对已收起/已消失的弹窗是 no-op，各结束点可以无脑调用 */
    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }

    private void showCacheManager() {
        View sheet = LayoutInflater.from(this).inflate(R.layout.sheet_cache, null);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        applyThemeToView(sheet);
        Window win = dialog.getWindow();
        if (win != null) {
            // 贴底、通栏：抽屉的观感靠「从底部弹出」而不是库
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setGravity(Gravity.BOTTOM);
            win.setWindowAnimations(R.style.BottomSheetAnimation);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        fillCacheSheet(sheet);

        sheet.findViewById(R.id.cacheSchedClear).setOnClickListener(v -> {
            clearScheduleCache();
            fillCacheSheet(sheet);
        });
        sheet.findViewById(R.id.cacheRoomClear).setOnClickListener(v -> {
            clearRoomCache();
            fillCacheSheet(sheet);
        });
        sheet.findViewById(R.id.cacheClearAll).setOnClickListener(v -> {
            confirmClearAll();
            fillCacheSheet(sheet);
        });

        dialog.show();
    }

    /** 把当前缓存状态刷到抽屉上（清理后原地刷新，不用关了再开） */
    private void fillCacheSheet(View sheet) {
        long schedSize = Math.max(ScheduleCache.sizeBytes(this), 0);
        long roomSize = Math.max(ResultCache.sizeBytes(this), 0);
        long total = schedSize + roomSize;

        ((TextView) sheet.findViewById(R.id.cacheTotal))
                .setText("共占用 " + sizeText(total));
        int items = (schedSize > 0 ? 1 : 0) + (roomSize > 0 ? 1 : 0);
        ((TextView) sheet.findViewById(R.id.cacheTotalSub))
                .setText(items + " 项有缓存");

        // 课表：现在可能有好几张表，把张数写出来 ——
        // 否则「清除全部课表」在用户眼里只是清掉一张，点下去才发现别的也没了
        boolean hasSched = schedSize > 0;
        int schedCount = ScheduleCache.count(this);
        ((TextView) sheet.findViewById(R.id.cacheSchedSize))
                .setText(hasSched ? sizeText(schedSize) : "无");
        ((TextView) sheet.findViewById(R.id.cacheSchedMeta)).setText(!hasSched
                ? "还没有导入过课表"
                : schedCount + " 张课表 · " + ScheduleCache.ageText(this)
                        + " · 当前 " + scheduleCourseCount() + " 门课");
        ((ProgressBar) sheet.findViewById(R.id.cacheSchedBar))
                .setProgress(percent(schedSize, total));
        View schedClear = sheet.findViewById(R.id.cacheSchedClear);
        schedClear.setEnabled(hasSched);
        schedClear.setAlpha(hasSched ? 1f : 0.45f);

        // 空教室
        boolean hasRoom = roomSize > 0;
        ((TextView) sheet.findViewById(R.id.cacheRoomSize))
                .setText(hasRoom ? sizeText(roomSize) : "无");
        ((TextView) sheet.findViewById(R.id.cacheRoomMeta)).setText(hasRoom
                ? ResultCache.ageText(this) + " · " + roomEntryCount() + " 个楼层条目"
                : "还没有查询过空教室");
        ((ProgressBar) sheet.findViewById(R.id.cacheRoomBar))
                .setProgress(percent(roomSize, total));
        View roomClear = sheet.findViewById(R.id.cacheRoomClear);
        roomClear.setEnabled(hasRoom);
        roomClear.setAlpha(hasRoom ? 1f : 0.45f);
    }

    /** 占总量百分比，用于占用比例条 */
    private int percent(long part, long total) {
        return total <= 0 ? 0 : (int) Math.round(part * 100.0 / total);
    }

    /** 课表缓存里的课程数（读不出就返回 0） */
    private int scheduleCourseCount() {
        try {
            String s = ScheduleCache.load(this);
            if (s == null) return 0;
            JSONArray c = new JSONObject(s).optJSONArray("courses");
            return c == null ? 0 : c.length();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 空教室缓存里的楼层条目数（读不出就返回 0） */
    private int roomEntryCount() {
        try {
            String s = ResultCache.load(this);
            if (s == null) return 0;
            JSONObject o = new JSONObject(s);
            JSONArray days = o.optJSONArray("days");
            if (days == null) return 0;
            int n = 0;
            for (int i = 0; i < days.length(); i++) {
                JSONArray slots = days.getJSONObject(i).optJSONArray("slots");
                if (slots == null) continue;
                for (int k = 0; k < slots.length(); k++) {
                    JSONObject buildings = slots.getJSONObject(k).optJSONObject("buildings");
                    JSONArray names = buildings == null ? null : buildings.names();
                    if (names != null) n += names.length();
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("清除全部缓存？")
                .setMessage("将删除全部课表（含多张课表）、调课记录、保存的登录密码、"
                        + "空教室缓存和开学日期设置。\n课表需要重新导入，空教室需要重新查询。")
                .setPositiveButton("清除", (d, w) -> {
                    ScheduleCache.clear(this);
                    AdjustCache.clear(this);   // 调课记录跟着课表一起没，留着就是孤儿数据
                    CredentialStore.clear(this);   // 保存的登录密码属于隐私数据，一并删除
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
                    loadCurrentSchedule();
                    renderSchedulePage();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 清除课表缓存。
     *
     * 多课表之后这个动作会一次删掉全部张数，破坏面比原来大得多，
     * 所以先弹一次确认并把张数写进问题里 —— 原来是一点就清，太轻了。
     */
    private void clearScheduleCache() {
        if (ScheduleCache.sizeBytes(this) < 0) {
            Toast.makeText(this, "没有课表缓存", Toast.LENGTH_SHORT).show();
            return;
        }
        int n = ScheduleCache.count(this);
        new AlertDialog.Builder(this)
                .setTitle("清除全部课表？")
                .setMessage("本机共有 " + n + " 张课表，将全部删除（教务系统上的课表不受影响）。\n"
                        + "之后需要重新导入。")
                .setPositiveButton("清除", (d, w) -> {
                    ScheduleCache.clear(this);
                    AdjustCache.clear(this);
                    scheduleData = null;
                    weekOffset = 0;
                    Toast.makeText(this, "课表缓存已清除", Toast.LENGTH_SHORT).show();
                    loadCurrentSchedule();
                    renderSchedulePage();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearRoomCache() {
        if (ResultCache.sizeBytes(this) < 0) {
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
        sb.append("东秦课表 诊断信息\n");
        sb.append("版本：").append(ver.isEmpty() ? "-" : ver).append("\n");
        sb.append("Android：").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型：").append(android.os.Build.MANUFACTURER).append(" ")
                .append(android.os.Build.MODEL).append("\n");
        sb.append("──── 缓存 ────\n");
        long s = ScheduleCache.sizeBytes(this);
        long r = ResultCache.sizeBytes(this);
        sb.append("课表缓存：").append(s >= 0 ? sizeText(s) + "，" + ScheduleCache.ageText(this) : "无")
                .append("\n");
        sb.append("课表张数：").append(ScheduleCache.count(this))
                .append("（当前「").append(ScheduleCache.currentName(this)).append("」）\n");
        sb.append("调课记录：").append(AdjustCache.count(this, ScheduleCache.currentId(this)))
                .append(" 条（仅统计当前课表）\n");
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
                + "2. 自动登录（可选）\n"
                + "   更多 → 「记住密码并自动登录」打开开关，在弹窗里输入一次学号与密码即可；"
                + "会话过期时会自动填入并登录（遇到验证码仍需手动输入）。"
                + "密码经手机安全芯片加密后只存在本机，不上传；点该行可随时更新或清除。\n\n"
                + "3. 导入课表\n"
                + "   登录后切到「课表」，点右上角 ⤓ 导入课表，选学期即可；"
                + "首次需选开学日期以便推算周次。\n\n"
                + "4. 查空教室\n"
                + "   底部「空教室」→ 顶栏刷新图标 → 选数据来源（教务处实时 / 通道2网站免登录）。"
                + "也可在课表里直接点空白格子，只查那一组时段。\n\n"
                + "5. 换学期 / 多张课表\n"
                + "   课表页右上角 ⋮ 打开面板：拖动滑杆跳周、切换 / 删除课表、添加新课表。"
                + "「课表设置」里可改开学日期、课程名称等。\n\n"
                + "6. 调课\n"
                + "   老师临时挪课 / 换教室 / 某周停课时，⋮ → 调课：可选单节课调整，"
                + "也可整节课表日搬移（含跨周，适配节假日调休）。只改本机显示，随时可撤销。\n\n"
                + "7. 改开学日期\n"
                + "   课表页 ⋮ → 课表设置 → 第一周的第一天。\n\n"
                + "8. 数据在哪\n"
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

    /** 按开学日期推算的真实当前周，不包含用户手动翻周偏移。 */
    private int actualCurrentWeek() {
        if (scheduleData == null) return 1;
        long start = termStartMs(scheduleData.optString("semesterId", ""));
        if (start <= 0) return 1;
        long weeks = Math.round((mondayOf(System.currentTimeMillis()) - mondayOf(start)) / (7.0 * DAY_MS));
        int w = (int) weeks + 1;
        if (w < 1) w = 1;
        return w;
    }

    /** 当前应显示的周次 = 真实当前周 + 用户手动偏移。 */
    private int currentWeek() {
        return actualCurrentWeek() + weekOffset;
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
        return ((name == null ? "" : name).hashCode() & 0x7fffffff) % 10;
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
        applyTabItem(iconClassroom, indClassroom, tvClassroom, tab == TAB_CLASSROOM);
        applyTabItem(iconSchedule, indSchedule, tvSchedule, tab == TAB_SCHEDULE);
        applyTabItem(iconMore, indMore, tvMore, tab == TAB_MORE);
    }

    private void applyTabItem(ImageView icon, View indicator, TextView label, boolean active) {
        int color = active ? ThemeStore.accent(this) : TAB_OFF;
        icon.setColorFilter(color);
        label.setTextColor(color);
        label.setTypeface(null, active
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        indicator.animate().cancel();
        if (active) {
            indicator.setVisibility(View.VISIBLE);
            indicator.setAlpha(0f);
            indicator.setScaleX(0.65f);
            indicator.animate().alpha(1f).scaleX(1f).setDuration(180).start();
        } else {
            indicator.setVisibility(View.INVISIBLE);
            indicator.setAlpha(0f);
            indicator.setScaleX(0.65f);
        }
    }

    private void switchTab(int tab) {
        // 内嵌浏览器开着时点任意底部 Tab，都视为「离开浏览器」，
        // 否则网页会继续盖在最上层，Tab 看起来怎么点都没反应。
        if (webPage.getVisibility() == View.VISIBLE) {
            webView.stopLoading();
            webPage.setVisibility(View.GONE);
        }

        // 切 Tab 视为放弃这次待续的抓取。
        // pending 标记现在会在登录跳转时保留（等登录完自动续跑），
        // 如果用户中途切走却不清掉，以后他自然浏览到教务页时会被旧标记触发一次查询。
        pendingQuery = pendingTerms = pendingSchedule = pendingSingle = false;
        pendingAskRange = false;
        loginHintShown = false;
        // 操作被切 Tab 放弃了，进度卡片跟着收掉（和按返回键收起的「只藏不取消」不同）
        dismissProgressDialog();

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
                // 没有缓存：给空态引导页，而不是直接把教务登录页糊上来 ——
                // 后者会让人以为「这个页面就是教务系统」，找不到该点哪里
                showEmptyRoom();
            }
        } else if (tab == TAB_MORE) {
            showMore();
        } else {
            if (scheduleData != null) {
                renderSchedule();
                statusText.setText(ScheduleCache.currentName(this)
                        + " · 第 " + currentWeek() + " 周");
            } else {
                showScheduleHtml(emptyScheduleHtml());
                applyUi(UiState.SCHEDULE_EMPTY);
                statusText.setText("「" + ScheduleCache.currentName(this)
                        + "」还没有内容，点右上角导入课表");
            }
        }
    }

    private void startScheduleImport() {
        // 必须先把教务 WebView 显示出来。
        // 早先这里只 loadUrl 不切视图，从「空课表 → 登录教务处并导入课表」点进来时，
        // 界面纹丝不动、只有一个 Toast 飘过 —— 表现就是「点了没反应」。
        showContentView(loginView);
        setEamsBrowserOverlay(true);
        eamsAddressInput.setText(EAMS_BASE + "courseTableForStd.action");
        statusText.setText("正在打开教务课表页…");
        setBusy(true);
        progressBar.setProgress(0);
        pendingTerms = true;
        // 卡片先解释整体流程（取学期 → 选学期 → 抓课程），登录跳转、
        // 读取学期期间都挂着；第一阶段结束由 onScheduleTerms 收掉，
        // 选完学期 startScheduleFetch 再以学期名重新弹出
        showProgressDialog("正在导入课表", "先取学期列表，选学期后自动抓取课程");
        loginHintShown = false;   // 新的一次操作，登录提示重新开始算
        loginRequested = true;
        loginView.loadUrl(EAMS_BASE + "courseTableForStd.action");
    }

    /**
     * 导入完成 → 切回课表 Tab 展示。
     *
     * 从「空教室」或「更多」发起的导入，内容落在课表页才合理。
     * 早先只 renderSchedule() 不换 Tab，底部高亮还停在原来的 Tab 上，
     * 内容和 Tab 对不上（比如停在「更多」却在显示课表）。
     */
    private void showScheduleAfterImport(int courseCount, String extra) {
        if (currentTab != TAB_SCHEDULE) {
            switchTab(TAB_SCHEDULE);   // 内部会 renderSchedule
        } else {
            renderSchedule();
        }
        setEamsBrowserOverlay(false);
        statusText.setText("「" + ScheduleCache.currentName(this) + "」已导入 · "
                + courseCount + " 门课" + (extra == null ? "" : extra));
        refreshMorePage();
    }

    /**
     * 空教室页顶栏的「刷新」：先让用户选数据来源。
     *
     * 两条路各有适用场景：
     *  · 教务处实时 —— 数据最新最准，但要登录，且查询有时段限速（7 天约 1 分钟）；
     *  · 通道2网站  —— 免登录、几秒出结果（站点每小时自动更新），
     *    缺点是数据新鲜度取决于站点。
     * 对话框里同时放「每次打开自动从通道2导入」的勾选项，一次选择长期生效。
     */
    private void refreshRooms() {
        View box = LayoutInflater.from(this).inflate(R.layout.dialog_room_source, null);
        final Switch swAuto = box.findViewById(R.id.swAutoImport);
        swAuto.setChecked(prefs().getBoolean(KEY_AUTO_IMPORT_WEB, false));
        // 开关随拨随存：不依赖用户点了哪个选项
        swAuto.setOnCheckedChangeListener((b, on) ->
                prefs().edit().putBoolean(KEY_AUTO_IMPORT_WEB, on).apply());

        AlertDialog dialog = new AlertDialog.Builder(this).setView(box).create();
        // 点卡片即选择：整张卡片是热区，比列表行好点，也不用先选再确认
        box.findViewById(R.id.optEams).setOnClickListener(v -> {
            dialog.dismiss();
            if (isEamsLoaded()) {
                showLogin();
                askDays();
            } else {
                beginRoomQueryWithLogin();
            }
        });
        box.findViewById(R.id.optWeb).setOnClickListener(v -> {
            dialog.dismiss();
            importRoomsFromWeb(true);
        });
        box.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /* ---------- 通道 2 网站导入 ---------- */

    /**
     * 从通道 2 网站（tsiao.io，静态渲染的 7 天总表）读取空教室数据。
     *
     * 网站把 7 天数据全部渲染在一个 index.html 里，按天/时段摆在固定 id 的单元格中
     * （day-N-GXG{层}F{时段} 等），所以这里的「导入」= 用隐藏的 webView 加载页面，
     * 等 SPA 渲染稳定后读 DOM，再用 inject.js 里同一套清洗/过滤规则整理成本机缓存。
     * 数据口径与「登录教务处查询」完全一致，只是来源不同。
     *
     * @param manual true=用户在刷新对话框里主动选的（失败要明确提示）；
     *               false=启动时的自动导入（失败静默，保底用旧缓存）
     */
    private void importRoomsFromWeb(final boolean manual) {
        if (webImportBusy) return;
        // 用户正在用内嵌浏览器浏览网页时不去抢这个 webView
        if (webPage.getVisibility() == View.VISIBLE) {
            if (manual) Toast.makeText(this, "请先关闭内嵌浏览器再导入", Toast.LENGTH_SHORT).show();
            return;
        }
        webImportBusy = true;
        webImportTries = 0;
        webImportManual = manual;
        if (manual) {
            statusText.setText("正在从通道2读取数据…");
            Toast.makeText(this, "正在从通道2读取空教室数据…", Toast.LENGTH_SHORT).show();
            // 只在手动导入时弹进度卡片：启动时的静默自动导入不该打扰用户
            showProgressDialog("正在读取通道2数据", "免登录 · 数据来自每小时自动更新的总表");
        }
        // 导入只需要表格文本：关掉图片加载，大页面的传输和渲染都省一大截
        setWebImagesEnabled(false);
        webView.stopLoading();
        new Thread(() -> {
            String html = null;
            String error = null;
            try {
                html = fetchWebTableHtml();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String page = html;
            final String failure = error;
            mainHandler.post(() -> {
                if (!webImportBusy) return;
                if (page == null || page.isEmpty()) {
                    if (manual && failure != null) {
                        statusText.setText("通道2读取失败：" + failure);
                    }
                    webImportFail();
                    return;
                }
                webView.loadDataWithBaseURL(WEB_CH2, page, "text/html", "UTF-8", null);
            });
        }, "channel2-download").start();
    }

    private String fetchWebTableHtml() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(WEB_CH2).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "neuq-classroom-app");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            long size = conn.getContentLengthLong();
            if (size > 4L * 1024 * 1024) throw new IOException("页面过大");
            String html = readStreamLimited(conn.getInputStream(), 4 * 1024 * 1024);
            if (!html.contains("day-0-content")) {
                throw new IOException("页面结构异常");
            }
            return html;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStreamLimited(InputStream in, int maxBytes) throws IOException {
        if (in == null) return "";
        try (InputStream input = in;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) > 0) {
                if (out.size() + n > maxBytes) throw new IOException("响应过大");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 通道页图片加载开关：导入时关（省流量省渲染），用户浏览时开 */
    private void setWebImagesEnabled(boolean on) {
        webView.getSettings().setLoadsImagesAutomatically(on);
        webView.getSettings().setBlockNetworkImage(!on);
    }

    /**
     * 页面加载完 → 等 SPA 把表格渲染出来再提取。
     * HTML 已在后台完整下载，表格数据本身是内联的；只需给 WebView 很短的时间
     * 建立 DOM。提取不到再快速重试（最多 2 次）。
     */
    private void scheduleWebImportExtract(final WebView view, String url) {
        if (!webImportBusy) return;
        if (url == null || !url.contains("tsiao.io")) return;   // 只认通道 2
        mainHandler.postDelayed(() -> {
            if (!webImportBusy) return;
            // 先注入 inject.js 拿到清洗/过滤函数，再执行提取
            view.evaluateJavascript(injectJs, null);
            view.evaluateJavascript(WEB_EXTRACT_JS, value -> mainHandler.post(() -> {
                if (!webImportBusy) return;
                String json = value == null ? "" : value.trim();
                if (json.length() > 1 && !json.equals("null") && applyWebImport(json)) return;
                // 没提到数据：SPA 渲染慢或结构变了，重试
                if (++webImportTries < 3) {
                    mainHandler.postDelayed(this::retryWebImport, 600);
                } else {
                    webImportFail();
                }
            }));
        }, 250);
    }

    /** 提取重试：再给页面 2 秒渲染时间，仍拿不到就认输 */
    private void retryWebImport() {
        if (!webImportBusy) return;
        webView.evaluateJavascript(WEB_EXTRACT_JS, value -> mainHandler.post(() -> {
            if (!webImportBusy) return;
            String j2 = value == null ? "" : value.trim();
            if (j2.length() > 1 && !j2.equals("null") && applyWebImport(j2)) return;
            if (++webImportTries < 3) {
                mainHandler.postDelayed(this::retryWebImport, 600);
            } else {
                webImportFail();
            }
        }));
    }

    /** 解析提取结果 → 组装成本机缓存格式 → 落盘并刷新界面 */
    private boolean applyWebImport(String json) {
        try {
            // WEB_EXTRACT_JS 返回的是对象字面量，evaluateJavascript 会把它序列化成 JSON 文本
            JSONObject raw = new JSONObject(json);
            if (!raw.optBoolean("ok", false)) return false;
            JSONArray rawDays = raw.optJSONArray("days");
            if (rawDays == null || rawDays.length() == 0) return false;

            JSONArray days = new JSONArray();
            for (int i = 0; i < rawDays.length(); i++) {
                JSONObject src = rawDays.optJSONObject(i);
                if (src == null) continue;
                JSONObject day = new JSONObject();
                day.put("date", src.optString("date"));
                // 旧版通道提取脚本曾存入“周三”，渲染端会再补“周”，先归一化避免“周周三”。
                String weekday = src.optString("weekday");
                if (weekday.startsWith("周")) weekday = weekday.substring(1);
                day.put("weekday", weekday);
                day.put("throttle", false);

                // 网站只有 6 个时段；本机格式里还有一个「昼间1-8节」，
                // 用 1-2/3-4/5-6/7-8 四个时段的交集补出来
                Map<String, JSONObject> bySlot = new HashMap<>();
                JSONArray srcSlots = src.optJSONArray("slots");
                if (srcSlots != null) {
                    for (int s = 0; s < srcSlots.length(); s++) {
                        JSONObject sl = srcSlots.optJSONObject(s);
                        if (sl != null) bySlot.put(sl.optString("label"), sl);
                    }
                }
                JSONArray slots = new JSONArray();
                JSONObject all = null;
                for (String label : SLOT_LABELS_WEB) {
                    JSONObject sl = bySlot.get(label);
                    if (sl == null) continue;
                    slots.put(sl);
                    if ("昼间1-8节".equals(label)) all = sl;
                }
                // 补「昼间1-8节」= 1-2 ∩ 3-4 ∩ 5-6 ∩ 7-8
                Map<String, Set<String>> acc = null;
                for (String label : new String[]{"上午1-2节", "上午3-4节", "下午5-6节", "下午7-8节"}) {
                    JSONObject sl = bySlot.get(label);
                    if (sl == null) continue;
                    JSONObject bs = sl.optJSONObject("buildings");
                    if (bs == null) continue;
                    Map<String, Set<String>> cur = new HashMap<>();
                    java.util.Iterator<String> it = bs.keys();
                    while (it.hasNext()) {
                        String b = it.next();
                        JSONArray arr = bs.optJSONArray(b);
                        if (arr == null) continue;
                        Set<String> set = new LinkedHashSet<>();
                        for (int k = 0; k < arr.length(); k++) set.add(arr.optString(k));
                        cur.put(b, set);
                    }
                    acc = (acc == null) ? cur : intersect(acc, cur);
                }
                if (acc != null && !acc.isEmpty()) {
                    JSONObject bs = new JSONObject();
                    int cnt = 0;
                    for (Map.Entry<String, Set<String>> e : acc.entrySet()) {
                        JSONArray arr = new JSONArray();
                        for (String r : e.getValue()) { arr.put(r); cnt++; }
                        bs.put(e.getKey(), arr);
                    }
                    JSONObject sl = new JSONObject();
                    sl.put("label", "昼间1-8节");
                    sl.put("ok", true);
                    sl.put("count", cnt);
                    sl.put("buildings", bs);
                    // 插在 7-8 之后、9-10 之前，与本机格式一致
                    JSONArray reordered = new JSONArray();
                    for (int s = 0; s < slots.length(); s++) {
                        JSONObject x = slots.optJSONObject(s);
                        if (x != null && "晚上9-10节".equals(x.optString("label"))) reordered.put(sl);
                        reordered.put(x);
                    }
                    slots = reordered;
                }
                day.put("slots", slots);
                days.put(day);
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("days", days);
            out.put("fromWeb", true);
            out.put("throttled", false);
            out.put("updated", raw.optString("updated", ""));
            ResultCache.save(this, out.toString());
            webImportBusy = false;
            dismissProgressDialog();   // 通道2导入成功，收掉进度卡片（静默导入时本来就是 no-op）

            boolean onRoomTab = (currentTab == TAB_CLASSROOM);
            if (onRoomTab) {
                showHtml(buildHtml(out));
                statusText.setText("已从通道2更新 · " + (days.length() > 0
                        ? rawDays.optJSONObject(0).optString("date") : ""));
            } else {
                Toast.makeText(this, "空教室数据已从通道2更新", Toast.LENGTH_SHORT).show();
            }
            // 首次成功后提示一次「每次打开自动导入」
            if (!autoImportPromptShown && !prefs().getBoolean(KEY_AUTO_IMPORT_WEB, false)) {
                autoImportPromptShown = true;
                new AlertDialog.Builder(this)
                        .setTitle("自动更新空教室？")
                        .setMessage("可以设置每次打开软件时，自动从通道2网站读取最新空教室数据"
                                + "（免登录，后台静默更新；之后在刷新对话框里可取消勾选）。")
                        .setPositiveButton("每次自动导入", (d, w) ->
                                prefs().edit().putBoolean(KEY_AUTO_IMPORT_WEB, true).apply())
                        .setNegativeButton("暂不", null)
                        .show();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 两个楼栋→房间集合的映射取交集（用于拼「昼间1-8节」） */
    private static Map<String, Set<String>> intersect(Map<String, Set<String>> a,
                                                      Map<String, Set<String>> b) {
        Map<String, Set<String>> out = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : a.entrySet()) {
            Set<String> other = b.get(e.getKey());
            if (other == null) continue;
            Set<String> both = new LinkedHashSet<>(e.getValue());
            both.retainAll(other);
            if (!both.isEmpty()) out.put(e.getKey(), both);
        }
        return out;
    }

    private void webImportFail() {
        boolean manual = webImportManual;
        webImportBusy = false;
        dismissProgressDialog();   // 无论手动还是静默失败都收卡片，失败提示走 statusText/Toast
        setWebImagesEnabled(true);
        if (manual) {
            statusText.setText("通道2读取失败");
            Toast.makeText(this, "通道2读取失败，可改用教务处查询", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 是否已经有教务会话可用。
     *
     * 判据是「loginView 当前停留的 URL 是否落在 /eams/ 上」——
     * 被重定向到 CAS / WebVPN 登录页时 URL 不含 /eams/。
     * 这只是个启发式判断，猜错了也只是多走一次登录跳转，不会出错。
     */
    private boolean isEamsLoaded() {
        String u = loginView.getUrl();
        return u != null && u.contains("/eams/");
    }

    /* ---------- 记住密码自动登录 ---------- */

    /**
     * 让用户在本机输入一次账号密码（v3.8.2 的新方案主路径）。
     *
     * 之前靠注入 JS 从登录页抓取，但登录页经 WebVPN 改写、DOM 随时重建，
     * 钩子注入时机/表单重建/桥接任一环节出问题都会静默失败；
     * 由用户在 App 内明确输入一次，链路最短，也最符合「我自己的账号我自己交给本机保管」。
     */
    private void askCredentials(String title) {
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_credential, null);
        final EditText userEt = form.findViewById(R.id.credUser);
        final EditText passEt = form.findViewById(R.id.credPass);
        String[] saved = CredentialStore.read(this);
        if (saved != null) {
            userEt.setText(saved[0]);
            passEt.setText(saved[1]);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(form)
                .setPositiveButton("保存并开启", (d, w) -> {
                    String u = userEt.getText().toString().trim();
                    String p = passEt.getText().toString();
                    if (u.isEmpty() || p.isEmpty()) {
                        Toast.makeText(this, "账号和密码都要填写", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (CredentialStore.save(this, u, p)) {
                        CredentialStore.setEnabled(this, true);
                        Toast.makeText(this, "已保存 · 下次会话过期时会自动登录",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "此设备不支持安全存储，未保存密码",
                                Toast.LENGTH_LONG).show();
                    }
                    refreshMorePage();
                })
                .setNegativeButton("取消", null);
        if (CredentialStore.has(this)) {
            b.setNeutralButton("清除已保存", (d, w) -> {
                CredentialStore.clear(this);
                Toast.makeText(this, "已清除保存的账号密码", Toast.LENGTH_SHORT).show();
                refreshMorePage();
            });
        }
        b.show();
    }

    /**
     * 统一身份认证登录页加载完成后的分流：
     *  · 有保存的账密且没被验证码/失败拦住 → 自动填表并点「登录」；
     *  · 没有账密（或自动登录放弃）→ 挂抓取钩子，这次手动登录会被记住。
     *
     * 自动登录最多试 3 次：密码改了的话 CAS 会原样重渲染登录页，
     * 不设上限就是无限刷新循环 —— 交还用户手动登录才是正确兜底。
     */
    private void handleLoginPage(WebView view, String url) {
        boolean enabled = CredentialStore.isEnabled(this);
        if (!enabled) return;
        loginPageSeen = true;
        // 启动后台探测遇到登录页：只静默尝试一次自动填表，失败也不打扰主界面。
        if (!loginRequested) return;

        String[] cred = CredentialStore.read(this);
        if (cred != null && autoLoginTries < 3 && !captchaHold) {
            autoLoginTries++;
            view.evaluateJavascript(buildAutoLoginJs(cred[0], cred[1]), null);
            return;
        }
        // 走不到自动登录（没保存过 / 试完了 / 要验证码）：
        // 挂抓取钩子，用户手动登录这一次会被记住，下次就能自动
        view.evaluateJavascript(CRED_HOOK_JS, null);
        // 引导提示：开关开着但还没存过账密时，告诉用户去哪里输入
        // （v3.8.2 起账密由用户在 App 内输入，不再依赖从登录页抓）
        if (!autoLoginHintShown) {
            autoLoginHintShown = true;
            Toast.makeText(this,
                    "自动登录还没建立：到「更多 → 记住密码并自动登录」输入一次账号密码",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 自动登录脚本：填入账密后点同一个登录按钮，加密仍由页面自己的 JS 完成。
     *
     * v3.7.3 修的坑：CAS 页面有约 1 秒淡入 + WebVPN 脚本初始化，
     * onPageFinished 时立刻填表可能撞上表单不可交互（或 VPN 脚本自刷新页面），
     * 而且填不上就静默 return —— 表现就是「自动登录没生效」但谁也不知道为什么。
     * 现在改成轮询等表单就绪（最多约 7 秒）再填，并把每一步结果回传给 App：
     * filled=已填并提交 / captcha=需要验证码 / noform=始终没找到登录框。
     */
    private String buildAutoLoginJs(String user, String pass) {
        return "(function(){"
                + "if(window.__nqAuto)return;"
                + "window.__nqAuto=1;"
                + "var PASSFILL=" + JSONObject.quote(pass) + ";"
                + "function report(code){try{Android.onAutoLoginResult(code);}catch(e){}}"
                + "function vis(e){return e&&(e.offsetWidth>0||e.offsetHeight>0);}"
                + "function meta(e){return ((e.id||'')+' '+(e.name||'')+' '+(e.placeholder||'')).toLowerCase();}"
                + "function bad(m){return m.indexOf('cap')>=0||m.indexOf('code')>=0||m.indexOf('verif')>=0;}"
                // WebVPN 可能把登录页放进 iframe：主文档找不到时一并扫同源框架
                + "function docs(){var a=[document],f=document.querySelectorAll('iframe'),i;"
                + "for(i=0;i<f.length;i++){try{var d=f[i].contentDocument;if(d)a.push(d);}catch(e){}}return a;}"
                // 主路径认统一身份认证的具体 id；认不出就通用识别 ——
                // WebVPN 门户等其它登录页的字段 id 不一样，写死 id 就会「未找到登录框」
                + "function findFields(){"
                + "var ds=docs(),k,i;"
                + "for(k=0;k<ds.length;k++){"
                + "var d=ds[k];"
                + "var f=d.querySelector('#casLoginForm'),"
                + "u=d.querySelector('#username'),p=d.querySelector('#password');"
                + "if(f&&u&&p&&vis(p))return{f:f,u:u,p:p,kind:'cas'};"
                + "}"
                + "for(k=0;k<ds.length;k++){"
                + "var d2=ds[k],ins=d2.querySelectorAll('input'),pw=null;"
                + "for(i=0;i<ins.length;i++){"
                + "var t=(ins[i].getAttribute('type')||'text').toLowerCase();"
                + "if(t==='password'&&vis(ins[i])){pw=ins[i];break;}}"
                + "if(!pw)continue;"
                + "var f2=pw.form||d2.querySelector('form')||d2;"
                + "var cand=f2.querySelectorAll('input'),user=null;"
                + "for(i=0;i<cand.length;i++){"
                + "var e=cand[i],t2=(e.getAttribute('type')||'text').toLowerCase();"
                + "if(t2!=='text'&&t2!=='tel'&&t2!=='email'&&t2!=='')continue;"
                + "if(!vis(e))continue;if(bad(meta(e)))continue;user=e;break;}"
                + "if(user)return{f:f2,u:user,p:pw,kind:'generic'};"
                + "}"
                + "return null;"
                + "}"
                + "function hasCaptcha(){"
                + "var ds=docs(),k,i;"
                + "for(k=0;k<ds.length;k++){"
                + "var ins=ds[k].querySelectorAll('input');"
                + "for(i=0;i<ins.length;i++){"
                + "var e=ins[i];if(!vis(e))continue;"
                + "var t=(e.getAttribute('type')||'').toLowerCase();if(t==='hidden')continue;"
                + "if(bad(meta(e)))return true;}"
                + "}"
                + "var cd=document.querySelector('#cpatchaDiv');"
                + "return !!(cd&&vis(cd));"
                + "}"
                + "function submit(f){"
                + "var b=f.querySelector&&f.querySelector('.submitBtn,button[type=submit],input[type=submit],#login-button,button');"
                + "if(b){b.click();return true;}"
                + "if(f.requestSubmit){f.requestSubmit();return true;}"
                + "if(f.submit){f.submit();return true;}"
                + "return false;"
                + "}"
                + "function attempt(){"
                + "var o=findFields();if(!o)return null;"
                + "if(hasCaptcha())return 'captcha';"
                + "o.u.value=" + JSONObject.quote(user) + ";"
                + "o.p.value=PASSFILL;"
                + "return submit(o.f)?('filled:'+o.kind):'nobutton';"
                + "}"
                + "var r=attempt();"
                + "if(r){report(r);blur();return;}"
                + "var n=0;"
                + "var timer=setInterval(function(){"
                + "if(++n>18){clearInterval(timer);report('noform');return;}"
                + "var r2=attempt();"
                + "if(r2){clearInterval(timer);report(r2);blur();}"
                + "},400);"
                // 提交后 8 秒仍停在同一文档、登录框还在、密码还是我们填的值 =
                // 提交没生效（密码已改或触发风控）。用 8 秒是给 WebVPN 多跳重定向
                // 与某些 AJAX 登录留余量，太短会误报。
                + "function blur(){setTimeout(function(){"
                + "var o=findFields();"
                + "if(o&&o.p&&o.p.value===PASSFILL)report('stuck');"
                + "},8000);}"
                + "})()";
    }

    /**
     * 导入失败：把教务返回的诊断信息（响应长度、TaskActivity 命中数、页面文本片段）
     * 展示出来并允许一键复制 —— 教务一旦改版，靠这段信息就能直接定位。
     */
    private void showImportFailure(String title, String err, JSONObject payload) {
        dismissProgressDialog();   // 导入到此为止，进度卡片先收掉再展示诊断
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
        // 第二阶段抓取重新弹卡片：用户选学期可能隔了一阵，把「现在抓的是哪张表」说清楚
        showProgressDialog("正在导入课表", "正在抓取「" + termName + "」的课程，请稍候");
        statusText.setText("正在抓取「" + termName + "」…");
        setBusy(true);
        loginView.loadUrl(EAMS_BASE + "courseTableForStd.action");
    }

    /** 首次导入某学期时问开学日期，之后据此推算周次。 */
    private void askTermStart(String sid, int courseCount) {
        long initial = mondayOf(System.currentTimeMillis());
        showCalendarPicker("本学期第 1 周从哪天开始？", initial, "稍后设置",
                () -> showScheduleAfterImport(courseCount, "（可在「课表设置」中重设开学日期）"),
                ms -> {
                    saveTermStart(sid, mondayOf(ms));
                    showScheduleAfterImport(courseCount, null);
                });
    }

    /** 重新设定当前学期的开学日期（点课表顶部「第 N 周」触发） */
    private void resetTermStart() {
        if (scheduleData == null) return;
        String sid = scheduleData.optString("semesterId", "");
        long cur = termStartMs(sid);
        if (cur <= 0) cur = mondayOf(System.currentTimeMillis());

        showCalendarPicker("开学日期（第 1 周的周一）", cur, "取消", null, ms -> {
            saveTermStart(sid, mondayOf(ms));
            renderSchedule();
            statusText.setText("开学日期已更新 · 现在是第 " + actualCurrentWeek() + " 周");
        });
    }

    /* ---------- 日期选择与换算（整日调休用） ---------- */

    /** 日期选择回调 */
    private interface DatePicked {
        void onDate(long ms);
    }

    /** 通用日期选择器：统一使用月历网格。 */
    private void pickDate(String title, long initialMs, DatePicked cb) {
        showCalendarPicker(title, initialMs, "取消", null, cb);
    }

    private void showCalendarPicker(String title, long initialMs, String negativeText,
                                    Runnable onCancel, DatePicked cb) {
        View content = getLayoutInflater().inflate(R.layout.dialog_calendar_picker, null);
        TextView selectedText = content.findViewById(R.id.calendarSelected);
        TextView monthText = content.findViewById(R.id.calendarMonth);
        TextView caption = content.findViewById(R.id.calendarCaption);
        LinearLayout grid = content.findViewById(R.id.calendarGrid);

        caption.setText("待选择日期");
        final long[] selected = {startOfDay(initialMs)};
        final Calendar[] shown = {Calendar.getInstance(Locale.CHINA)};
        shown[0].setTimeInMillis(selected[0]);
        shown[0].set(Calendar.DAY_OF_MONTH, 1);
        final Runnable[] render = new Runnable[1];
        float density = getResources().getDisplayMetrics().density;
        int cellSize = Math.round(36 * density);
        int margin = Math.round(2 * density);
        int accent = 0xFF5266A3;
        int text = 0xFF4E5360;
        int muted = 0xFF98A0B2;
        int weekend = 0xFFD85F63;

        render[0] = () -> {
            selectedText.setText(formatCalendarDate(selected[0]));
            monthText.setText(String.format(Locale.CHINA, "%d年%d月",
                    shown[0].get(Calendar.YEAR), shown[0].get(Calendar.MONTH) + 1));
            grid.removeAllViews();

            Calendar first = (Calendar) shown[0].clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            int leading = weekdayOf(first.getTimeInMillis()) - 1;
            int maxDay = first.getActualMaximum(Calendar.DAY_OF_MONTH);
            int rows = (leading + maxDay <= 35) ? 5 : 6;
            int cells = rows * 7;
            for (int row = 0; row < rows; row++) {
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lineLp.topMargin = margin;
                line.setLayoutParams(lineLp);

                for (int col = 0; col < 7; col++) {
                    int cellIndex = row * 7 + col;
                    int day = cellIndex - leading + 1;
                    if (cellIndex >= cells || day < 1 || day > maxDay) {
                        View blank = new View(this);
                        LinearLayout.LayoutParams blankLp =
                                new LinearLayout.LayoutParams(cellSize, cellSize);
                        blankLp.leftMargin = margin;
                        blankLp.rightMargin = margin;
                        blank.setLayoutParams(blankLp);
                        line.addView(blank);
                        continue;
                    }

                    Calendar dayCal = (Calendar) shown[0].clone();
                    dayCal.set(Calendar.DAY_OF_MONTH, day);
                    long dayMs = startOfDay(dayCal.getTimeInMillis());
                    boolean isSelected = dayMs == selected[0];
                    boolean isToday = dayMs == startOfDay(System.currentTimeMillis());

                    TextView cell = new TextView(this);
                    LinearLayout.LayoutParams cellLp =
                            new LinearLayout.LayoutParams(cellSize, cellSize);
                    cellLp.leftMargin = margin;
                    cellLp.rightMargin = margin;
                    cell.setLayoutParams(cellLp);
                    cell.setGravity(Gravity.CENTER);
                    cell.setText(String.valueOf(day));
                    cell.setTextSize(13f);
                    cell.setTypeface(null, isSelected
                            ? android.graphics.Typeface.BOLD
                            : android.graphics.Typeface.NORMAL);
                    cell.setTextColor(isSelected ? 0xFFFFFFFF
                            : (col == 5 || col == 6 ? weekend : text));

                    GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.OVAL);
                    if (isSelected) {
                        bg.setColor(accent);
                    } else {
                        bg.setColor(0x00000000);
                        if (isToday) {
                            bg.setStroke(Math.round(1.5f * density), muted);
                        }
                    }
                    cell.setBackground(bg);
                    final long picked = dayMs;
                    cell.setOnClickListener(v -> {
                        selected[0] = picked;
                        render[0].run();
                    });
                    line.addView(cell);
                }
                grid.addView(line);
            }
        };

        content.findViewById(R.id.calendarPrev).setOnClickListener(v -> {
            shown[0].add(Calendar.MONTH, -1);
            render[0].run();
        });
        content.findViewById(R.id.calendarNext).setOnClickListener(v -> {
            shown[0].add(Calendar.MONTH, 1);
            render[0].run();
        });
        render[0].run();

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.CalendarDialog)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("确定", (d, w) -> cb.onDate(selected[0]))
                .setNegativeButton(negativeText, (d, w) -> {
                    if (onCancel != null) onCancel.run();
                })
                .create();
        dialog.setOnCancelListener(d -> {
            if (onCancel != null) onCancel.run();
        });
        dialog.show();
        Window dialogWin = dialog.getWindow();
        if (dialogWin != null) {
            dialogWin.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            dialogWin.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
        }
    }

    private static String formatCalendarDate(long ms) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(ms);
        return String.format(Locale.CHINA, "%d年%d月%d日",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 日期 → 「10-01 周四」 */
    private static String dateCn(long ms) {
        return mdStr(ms) + " 周" + wdCn(weekdayOf(ms));
    }

    /** 星期几：1=周一 … 7=周日 */
    private static int weekdayOf(long ms) {
        Calendar c = Calendar.getInstance(Locale.CHINA);
        c.setTimeInMillis(ms);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return (dow == Calendar.SUNDAY) ? 7 : (dow - Calendar.MONDAY + 1);
    }

    /** 日期 → 学期内 {周次, 星期}；不在本学期范围内返回 null */
    private int[] dateToWeekDay(String sid, long ms) {
        long start = termStartMs(sid);
        if (start <= 0) return null;
        long days = Math.round((startOfDay(ms) - startOfDay(start)) / (double) DAY_MS);
        if (days < 0) return null;
        int week = (int) (days / 7) + 1;
        int day = (int) (days % 7) + 1;
        if (week > Math.max(maxWeekOfView(), 20)) return null;
        return new int[]{week, day};
    }

    /** 周次+星期 → 「10-01 周四」（没设开学日期时退回「第N周 周X」） */
    private String dateOfWeekDay(int week, int day) {
        long start = scheduleData == null ? 0 : termStartMs(scheduleData.optString("semesterId", ""));
        if (start <= 0) return "第" + week + "周 周" + wdCn(day);
        return dateCn(weekMonday(week) + (day - 1) * DAY_MS);
    }

    /** 翻周只影响本次会话，不写盘 —— 下次打开 App 仍从本周开始 */
    private void shiftWeek(int delta) {
        if (scheduleData == null) return;
        int target = currentWeek() + delta;
        if (target < 1 || target > maxWeekOfView()) {
            Toast.makeText(this, target < 1 ? "已经是第 1 周" : "已经是最后一周",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        weekOffset = target - actualCurrentWeek();
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

    /* ==================== 多张课表 / 课表设置面板 ==================== */

    /** 把「当前课表」从缓存读进内存（只改内存，不动界面） */
    private void loadCurrentSchedule() {
        scheduleData = null;
        weekOffset = 0;
        String raw = ScheduleCache.load(this);
        if (raw == null) return;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optBoolean("ok", false) && o.has("courses")) scheduleData = o;
        } catch (Exception ignored) {
            // 缓存损坏，当作没有课表处理
        }
    }

    /**
     * 按内存里的 scheduleData 重画课表页。
     *
     * 停在别的 Tab 上时不碰视图 —— 否则会在用户正看着「更多」的时候把他甩到课表页；
     * 但「更多」页上的张数 / 文案还是要刷新，那两个数字已经过期了。
     */
    private void renderSchedulePage() {
        String name = ScheduleCache.currentName(this);
        if (currentTab == TAB_SCHEDULE) {
            if (scheduleData != null) {
                renderSchedule();
                statusText.setText(name + " · 第 " + currentWeek() + " 周"
                        + (weekOffset == 0 ? "" : "（手动翻周中）"));
            } else {
                showScheduleHtml(emptyScheduleHtml());
                applyUi(UiState.SCHEDULE_EMPTY);
                statusText.setText("「" + name + "」还没有内容，点右上角导入课表");
            }
        }
        refreshMorePage();
    }

    /**
     * 课表设置面板：周数滑杆 + 课表列表 + 调课 / 添加课表。
     *
     * 入口是课表页顶栏的 ⋮。切换课表之所以放在这里而不是「更多」页，
     * 是因为它和「上周 / 下周」一样属于课表自身的操作 —— 用户改课表时
     * 视线和手指都在课表页上，不该先切到设置页再切回来。
     */
    private void showSchedulePanel() {
        View sheet = LayoutInflater.from(this).inflate(R.layout.sheet_schedule, null);
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        applyThemeToView(sheet);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setGravity(Gravity.BOTTOM);
            win.setWindowAnimations(R.style.BottomSheetAnimation);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        syncScheduleSheet(sheet, dialog);

        sheet.findViewById(R.id.schedAddCourse).setOnClickListener(v -> {
            dialog.dismiss();
            if (scheduleData == null) {
                Toast.makeText(this, "请先创建或导入一张课表", Toast.LENGTH_SHORT).show();
                return;
            }
            addCourseManually();
        });
        sheet.findViewById(R.id.schedAdjust).setOnClickListener(v -> {
            if (scheduleData == null) {
                Toast.makeText(this, "先导入课表才能调课", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            showAdjustSheet(currentWeek());
        });

        // 四宫格：课表的次级功能入口
        sheet.findViewById(R.id.gridTime).setOnClickListener(v -> {
            dialog.dismiss();
            showTimeSlots();
        });
        sheet.findViewById(R.id.gridSettings).setOnClickListener(v -> {
            dialog.dismiss();
            openSchedSettings();
        });
        sheet.findViewById(R.id.gridCourses).setOnClickListener(v -> {
            dialog.dismiss();
            openCourseManager();
        });
        sheet.findViewById(R.id.gridFaq).setOnClickListener(v -> {
            dialog.dismiss();
            showHowto();
        });

        final View now = sheet.findViewById(R.id.schedNow);
        now.setOnClickListener(v -> {
            if (weekOffset == 0) return;
            weekOffset = 0;
            syncScheduleSheet(sheet, dialog);
            renderSchedule();
            statusText.setText(ScheduleCache.currentName(this) + " · 第 " + currentWeek() + " 周");
        });

        // 拖动时实时重绘：面板只占下半屏，上面的课表还看得见，
        // 松手才更新的话用户没法边拖边找「第几周」
        SeekBar bar = sheet.findViewById(R.id.schedSeek);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (!fromUser || schedSyncing) return;
                int target = Math.max(1, value);
                weekOffset += target - currentWeek();   // 让 currentWeek() 正好落在目标周
                renderSchedule();
                updateWeekLabel(sheet);
                now.setVisibility(weekOffset == 0 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        dialog.show();
    }

    /**
     * 把课表仓库的现状刷到面板上：周数滑杆 / 周次文字 / 课表卡片列表。
     * 切换、删除、调课之后都调它原地刷新，不用关了面板再开。
     */
    private void syncScheduleSheet(View sheet, Dialog dialog) {
        SeekBar bar = sheet.findViewById(R.id.schedSeek);
        int max = maxWeekOfView();
        bar.setMax(max);
        // setProgress 会回调上面的监听器，先挡住，否则打开面板就相当于用户拖了一下
        schedSyncing = true;
        bar.setProgress(Math.max(1, Math.min(currentWeek(), max)));
        schedSyncing = false;
        bar.setEnabled(scheduleData != null);

        updateWeekLabel(sheet);
        sheet.findViewById(R.id.schedNow)
                .setVisibility(weekOffset == 0 ? View.GONE : View.VISIBLE);
        sheet.findViewById(R.id.schedAdjust).setEnabled(scheduleData != null);

        List<ScheduleCache.Meta> metas = ScheduleCache.list(this);
        ((TextView) sheet.findViewById(R.id.schedCount)).setText("共 " + metas.size() + " 张");

        // 各张表各有多少条调课：一次性读完整份记录再分组，
        // 免得每张卡片都去读一遍文件
        Map<String, Integer> adjPerSched = new HashMap<>();
        for (AdjustCache.Adjust a : AdjustCache.list(this, null)) {
            Integer n = adjPerSched.get(a.schedId);
            adjPerSched.put(a.schedId, (n == null ? 0 : n) + 1);
        }

        LinearLayout list = sheet.findViewById(R.id.schedList);
        list.removeAllViews();
        final String cur = ScheduleCache.currentId(this);
        boolean canDelete = metas.size() > 1;
        LayoutInflater inf = LayoutInflater.from(this);
        for (final ScheduleCache.Meta m : metas) {
            final boolean on = m.id.equals(cur);
            View row = inf.inflate(R.layout.item_schedule_card, list, false);
            row.setBackgroundResource(on ? R.drawable.sched_card_on : R.drawable.sched_card_off);
            ((TextView) row.findViewById(R.id.schedCardName)).setText(m.name);
            Integer n = adjPerSched.get(m.id);
            ((TextView) row.findViewById(R.id.schedCardMeta))
                    .setText(scheduleMetaText(m, n == null ? 0 : n));
            row.findViewById(R.id.schedCardBadge).setVisibility(on ? View.VISIBLE : View.GONE);

            View del = row.findViewById(R.id.schedCardDel);
            del.setVisibility(canDelete ? View.VISIBLE : View.GONE);
            del.setOnClickListener(v -> confirmDeleteSchedule(sheet, dialog, m));

            row.setOnClickListener(v -> {
                if (!on) switchTo(m.id);
                dialog.dismiss();
            });
            list.addView(row);
        }
    }

    /** 课表卡片副标题：空槽位说清「还没内容」，有内容的给出学期 / 门数 / 更新时间 */
    private String scheduleMetaText(ScheduleCache.Meta m, int adjCount) {
        if (m.isEmpty()) return "空课表 · 去课表页点右上角「导入课表」抓取";
        StringBuilder sb = new StringBuilder();
        // 名字是导入时自动填的学期名时就不用再说一遍，否则会重复成「2026 秋 · 2026 秋」
        if (!m.termName.isEmpty() && !m.termName.equals(m.name)) {
            sb.append(m.termName).append(" · ");
        }
        sb.append(m.courseCount).append(" 门课");
        sb.append(" · ").append(ScheduleCache.ageText(System.currentTimeMillis() - m.savedAt));
        if (adjCount > 0) sb.append(" · ").append(adjCount).append(" 条调课");
        return sb.toString();
    }

    /** 周次文字 + 起止日期（当前课表为空时不显示周次，免得给出无意义的「第 1 周」） */
    private void updateWeekLabel(View sheet) {
        TextView wv = sheet.findViewById(R.id.schedWeekText);
        TextView sub = sheet.findViewById(R.id.schedWeekSub);
        if (scheduleData == null) {
            wv.setText("—");
            sub.setText("当前课表还没有内容，先导入");
            return;
        }
        int week = currentWeek();
        wv.setText("第 " + week + " 周");
        String sid = scheduleData.optString("semesterId", "");
        if (termStartMs(sid) <= 0) {
            sub.setText("还没设置开学日期");
        } else {
            int actual = actualCurrentWeek();
            String base = dateStr(weekMonday(week)) + " 起";
            sub.setText(week == actual ? base : base + " · 当前为第 " + actual + " 周");
        }
    }

    /** 滑杆上界：课表里出现过的最大周次；没有周次信息时按 20 周算 */
    private int maxWeekOfView() {
        int max = 0;
        if (scheduleData != null) {
            JSONArray cs = scheduleData.optJSONArray("courses");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    JSONArray ws = c == null ? null : c.optJSONArray("weeks");
                    if (ws == null) continue;
                    for (int k = 0; k < ws.length(); k++) max = Math.max(max, ws.optInt(k, 0));
                }
            }
        }
        if (max <= 0) max = 20;
        return Math.max(max, actualCurrentWeek());
    }

    /** 切换到某张课表：设为当前 + 重读缓存 + 切到课表页 */
    private void switchTo(String id) {
        ScheduleCache.select(this, id);
        loadCurrentSchedule();
        if (currentTab == TAB_SCHEDULE) {
            renderSchedulePage();
        } else {
            switchTab(TAB_SCHEDULE);
        }
        Toast.makeText(this, "已切换到「" + ScheduleCache.currentName(this) + "」",
                Toast.LENGTH_SHORT).show();
    }

    /** 删除一张课表：只删本机缓存，连带把它的调课记录一起带走 */
    private void confirmDeleteSchedule(final View sheet, final Dialog dialog,
                                       final ScheduleCache.Meta m) {
        final String before = ScheduleCache.currentId(this);
        new AlertDialog.Builder(this)
                .setTitle("删除「" + m.name + "」？")
                .setMessage("只删除本机缓存，教务系统上的课表不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    if (!ScheduleCache.remove(this, m.id)) {
                        Toast.makeText(this, "至少要留一张课表", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AdjustCache.removeSched(this, m.id);
                    if (m.id.equals(before)) loadCurrentSchedule();
                    renderSchedulePage();
                    syncScheduleSheet(sheet, dialog);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 添加课表：新建一张空课表并切过去。
     *
     * 为什么不直接开始导入：一张课表的名字（本学期 / 下学期 / 同学的）是用户的语义。
     * 先把槽位建出来、名字定下来，再点「导入课表」填内容，多张表之间的边界才清楚；
     * 万一导入失败，也不会在半路上把原来那张表的内容覆盖掉。
     */
    private void addSchedule() {
        final EditText input = new EditText(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setText("课表 " + (ScheduleCache.count(this) + 1));
        input.setSelection(input.getText().length());   // 默认名可直接覆盖，不用先删
        input.setHint("例如：2026 秋季 / 同学的课表");

        new AlertDialog.Builder(this)
                .setTitle("添加课表")
                .setMessage("新建一张空白课表，之后在课表页点右上角「导入课表」抓取内容。")
                .setView(input)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "课表 " + ScheduleCache.count(this);
                    ScheduleCache.add(this, name);
                    loadCurrentSchedule();
                    switchTab(TAB_SCHEDULE);
                    renderSchedulePage();
                    Toast.makeText(this, "已新建「" + name + "」，点右上角导入课表",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ==================== 调课 ==================== */

    /**
     * 调课面板。
     *
     * 现实里的课表和教务系统里那套经常对不上：老师临时把周四的课挪到周六、
     * 换教室、某一周停课，教务不一定会更新。这里做的是**本地覆盖**：
     * 只改本机显示，不回写教务，也不申请什么，所见即所得。
     *
     * @param week 作用周次 —— 就是打开面板时课表停在哪一周
     */
    private void showAdjustSheet(final int week) {
        View sheet = LayoutInflater.from(this).inflate(R.layout.sheet_adjust, null);
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        applyThemeToView(sheet);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setGravity(Gravity.BOTTOM);
            win.setWindowAnimations(R.style.BottomSheetAnimation);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        final String schedId = ScheduleCache.currentId(this);
        ((TextView) sheet.findViewById(R.id.adjWeekHint))
                .setText("第 " + week + " 周 · " + dateStr(weekMonday(week)) + " 起 · 当前课表「"
                        + ScheduleCache.currentName(this) + "」");

        final List<JSONObject> weekCourses = coursesOfWeek(week);
        final String[] keys = new String[weekCourses.size()];
        for (int i = 0; i < weekCourses.size(); i++) keys[i] = courseKey(weekCourses.get(i));

        final Spinner courseSp = sheet.findViewById(R.id.adjCourse);
        final Spinner daySp = sheet.findViewById(R.id.adjDay);
        final Spinner startSp = sheet.findViewById(R.id.adjSeStart);
        final Spinner endSp = sheet.findViewById(R.id.adjSeEnd);

        String[] days = new String[7];
        for (int i = 0; i < 7; i++) days[i] = "周" + WD_CN[i];
        String[] sections = new String[PERIODS];
        for (int i = 0; i < PERIODS; i++) sections[i] = "第 " + (i + 1) + " 节";
        daySp.setAdapter(spinnerAdapter(days));
        startSp.setAdapter(spinnerAdapter(sections));
        endSp.setAdapter(spinnerAdapter(sections));

        // ── ④ 整天调课（节假日调休）：直接选日期，不让用户自己换算周次 ──
        final TextView dmSrcText = sheet.findViewById(R.id.adjDmSrcText);
        final TextView dmDstText = sheet.findViewById(R.id.adjDmDstText);
        // 用数组持有选中日期（lambda 里要改值）；默认：本周周一 → 本周周六
        final long[] srcMs = {weekMonday(week)};
        final long[] dstMs = {weekMonday(week) + 5 * DAY_MS};
        dmSrcText.setText(dateCn(srcMs[0]));
        dmDstText.setText(dateCn(dstMs[0]));

        sheet.findViewById(R.id.adjDmSrcRow).setOnClickListener(v ->
                pickDate("把哪一天的课调走？", srcMs[0], ms -> {
                    srcMs[0] = ms;
                    dmSrcText.setText(dateCn(ms));
                }));
        sheet.findViewById(R.id.adjDmDstRow).setOnClickListener(v ->
                pickDate("整体调到哪一天？", dstMs[0], ms -> {
                    dstMs[0] = ms;
                    dmDstText.setText(dateCn(ms));
                }));

        // 确认整天调课
        sheet.findViewById(R.id.adjDmConfirm).setOnClickListener(v -> {
            int[] src = dateToWeekDay(schedId, srcMs[0]);
            if (src == null) src = inferWeekDayWithoutTermStart(srcMs[0]);
            int[] dst = dateToWeekDay(schedId, dstMs[0]);
            if (dst == null) dst = inferWeekDayWithoutTermStart(dstMs[0]);
            if (src == null || dst == null) {
                Toast.makeText(this, "日期不在本学期范围内，请先设置开学日期", Toast.LENGTH_LONG).show();
                return;
            }
            int srcWeek = src[0], srcDay = src[1], dstWeek = dst[0], dstDay = dst[1];
            if (srcWeek == dstWeek && srcDay == dstDay) {
                Toast.makeText(this, "目标日期和来源相同，请重新选择", Toast.LENGTH_SHORT).show();
                return;
            }
            // 同一个源日只允许一条整天调课，改目标时替换而不是叠加
            AdjustCache.removeDayMove(this, schedId, srcWeek, srcDay);
            AdjustCache.Adjust a = new AdjustCache.Adjust();
            a.schedId = schedId;
            a.scope = AdjustCache.SCOPE_DAY;
            a.courseKey = "dm|" + srcWeek + "|" + srcDay + "|" + dstWeek + "|" + dstDay;
            a.name = "第" + srcWeek + "周 周" + WD_CN[srcDay - 1] + " 全天";
            a.week = srcWeek;
            a.srcDay = srcDay;
            a.day = dstDay;
            a.targetWeek = dstWeek;
            a.createdAt = System.currentTimeMillis();
            AdjustCache.put(this, a);
            Toast.makeText(this, "已调休：" + dateCn(srcMs[0]) + " → " + dateCn(dstMs[0]),
                    Toast.LENGTH_LONG).show();
            renderSchedule();
            fillAdjustSheet(sheet, week, schedId, weekCourses, keys);
        });
        fillAdjustSheet(sheet, week, schedId, weekCourses, keys);

        // 默认只展开「整天调课」；单节调课由入口按钮展开，减少一屏拥挤。
        View singleSection = sheet.findViewById(R.id.adjSingleSection);
        singleSection.setVisibility(View.GONE);
        sheet.findViewById(R.id.adjOpenSingle).setOnClickListener(v -> {
            boolean show = singleSection.getVisibility() != View.VISIBLE;
            singleSection.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                android.widget.ScrollView scroll = (android.widget.ScrollView)
                        sheet.findViewById(R.id.adjSingleSection).getParent().getParent();
                scroll.smoothScrollTo(0, singleSection.getTop());
            }
        });

        // 换一门课就把 ② 预填成它当前生效的位置，用户只需改要变的那一项
        courseSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= weekCourses.size()) return;
                prefillAdjustTarget(sheet, weekCourses.get(position),
                        findEffective(schedId, keys[position], week));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        sheet.findViewById(R.id.adjClose).setOnClickListener(v -> dialog.dismiss());

        // 确认调课
        sheet.findViewById(R.id.adjConfirm).setOnClickListener(v -> {
            int i = courseSp.getSelectedItemPosition();
            if (i < 0 || i >= weekCourses.size()) {
                Toast.makeText(this, "先选一门课", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject c = weekCourses.get(i);
            boolean all = ((RadioButton) sheet.findViewById(R.id.adjScopeAll)).isChecked();

            AdjustCache.Adjust a = new AdjustCache.Adjust();
            a.schedId = schedId;
            a.courseKey = keys[i];
            a.name = c.optString("name", "");
            a.scope = all ? AdjustCache.SCOPE_ALL : AdjustCache.SCOPE_WEEK;
            a.week = week;
            a.day = daySp.getSelectedItemPosition() + 1;
            a.startSection = startSp.getSelectedItemPosition() + 1;
            a.endSection = endSp.getSelectedItemPosition() + 1;
            if (a.endSection < a.startSection) a.endSection = a.startSection;
            a.room = ((EditText) sheet.findViewById(R.id.adjRoom)).getText().toString().trim();
            a.srcDay = c.optInt("day", 0);
            a.srcStart = c.optInt("startSection", 0);
            a.srcEnd = c.optInt("endSection", a.srcStart);
            a.srcRoom = c.optString("position", "");
            a.createdAt = System.currentTimeMillis();

            // 调回原位（同一天、同起始节、教室也没改）等于没调：
            // 直接删掉这条记录，而不是留一条「自己调到自己」的废记录
            if (a.day == a.srcDay && a.startSection == a.srcStart
                    && (a.room.isEmpty() || a.room.equals(a.srcRoom))) {
                AdjustCache.removeOne(this, schedId, keys[i], a.scope, week);
                Toast.makeText(this, "已恢复原本的时间", Toast.LENGTH_SHORT).show();
            } else {
                AdjustCache.put(this, a);
                Toast.makeText(this, "已调课：" + a.name + " → 周" + WD_CN[a.day - 1]
                        + " " + a.startSection + "-" + a.endSection + " 节", Toast.LENGTH_LONG).show();
            }
            renderSchedule();
            fillAdjustSheet(sheet, week, schedId, weekCourses, keys);
        });

        // 停课
        sheet.findViewById(R.id.adjCancelWeek).setOnClickListener(v -> {
            int i = courseSp.getSelectedItemPosition();
            if (i < 0 || i >= weekCourses.size()) {
                Toast.makeText(this, "先选一门课", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject c = weekCourses.get(i);
            boolean all = ((RadioButton) sheet.findViewById(R.id.adjScopeAll)).isChecked();

            AdjustCache.Adjust a = new AdjustCache.Adjust();
            a.schedId = schedId;
            a.courseKey = keys[i];
            a.name = c.optString("name", "");
            a.scope = all ? AdjustCache.SCOPE_ALL : AdjustCache.SCOPE_WEEK;
            a.week = week;
            a.day = 0;                      // day <= 0 表示停课
            a.srcDay = c.optInt("day", 0);
            a.srcStart = c.optInt("startSection", 0);
            a.srcEnd = c.optInt("endSection", a.srcStart);
            a.srcRoom = c.optString("position", "");
            a.createdAt = System.currentTimeMillis();

            AdjustCache.put(this, a);
            Toast.makeText(this, "已标记停课：" + a.name
                            + (all ? "（之后每周都不排）" : "（仅第 " + week + " 周）"),
                    Toast.LENGTH_LONG).show();
            renderSchedule();
            fillAdjustSheet(sheet, week, schedId, weekCourses, keys);
        });

        dialog.show();
    }

    /** 未设置开学日期时，用「本周周一」作虚拟第 1 周起点换算，保证整天调课可继续。 */
    private int[] inferWeekDayWithoutTermStart(long ms) {
        long base = mondayOf(System.currentTimeMillis());
        long days = startOfDay(ms) - startOfDay(base);
        int offset = (int) Math.floor(days / (7.0 * DAY_MS));
        int week = offset + 1;
        if (week < 1) week = 1;
        return new int[]{week, weekdayOf(ms)};
    }

    /**
     * 刷新调课面板：① 课程选择器 + 底部「已有记录」列表。
     *
     * @param keys 与 weekCourses 一一对应的课程键（在外面算好，避免每次重算）
     */
    private void fillAdjustSheet(View sheet, int week, String schedId,
                                 List<JSONObject> weekCourses, String[] keys) {
        Spinner courseSp = sheet.findViewById(R.id.adjCourse);
        boolean has = !weekCourses.isEmpty();
        courseSp.setVisibility(has ? View.VISIBLE : View.GONE);
        sheet.findViewById(R.id.adjEmpty).setVisibility(has ? View.GONE : View.VISIBLE);
        sheet.findViewById(R.id.adjConfirm).setEnabled(has);
        sheet.findViewById(R.id.adjCancelWeek).setEnabled(has);

        if (has) {
            int keep = Math.max(0, courseSp.getSelectedItemPosition());
            String[] labels = new String[weekCourses.size()];
            for (int i = 0; i < weekCourses.size(); i++) {
                labels[i] = courseLabel(weekCourses.get(i),
                        findEffective(schedId, keys[i], week));
            }
            courseSp.setAdapter(spinnerAdapter(labels));
            courseSp.setSelection(Math.min(keep, weekCourses.size() - 1));
        }

        // 已有记录：调课是临时动作，用户最需要的是一条条改回去的能力
        List<AdjustCache.Adjust> all = AdjustCache.list(this, schedId);
        LinearLayout list = sheet.findViewById(R.id.adjList);
        list.removeAllViews();
        sheet.findViewById(R.id.adjListTitle)
                .setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (final AdjustCache.Adjust a : all) {
            View row = inf.inflate(R.layout.item_adjust_row, list, false);
            ((TextView) row.findViewById(R.id.adjRowText)).setText(adjustText(a));
            row.findViewById(R.id.adjRowUndo).setOnClickListener(v -> {
                AdjustCache.remove(this, a.id);
                Toast.makeText(this, "已撤销该调课", Toast.LENGTH_SHORT).show();
                renderSchedule();
                fillAdjustSheet(sheet, week, schedId, weekCourses, keys);
            });
            list.addView(row);
        }
    }

    /** 某门课在指定周实际生效的调课记录（没有则 null） */
    private AdjustCache.Adjust findEffective(String schedId, String courseKey, int week) {
        for (AdjustCache.Adjust a : AdjustCache.forWeek(this, schedId, week)) {
            if (a.courseKey.equals(courseKey)) return a;
        }
        return null;
    }

    /** 选中某门课后，把「调到」那组控件预填成它当前生效的位置 */
    private void prefillAdjustTarget(View sheet, JSONObject c, AdjustCache.Adjust a) {
        int day, sb, se;
        String room;
        boolean cancelled = false;
        if (a == null) {
            day = c.optInt("day", 1);
            sb = c.optInt("startSection", 1);
            se = c.optInt("endSection", sb);
            room = c.optString("position", "");
        } else if (a.isCancelled()) {
            // 已停课的课没有「调整后位置」，用原位置填，用户改完就是恢复
            cancelled = true;
            day = a.srcDay;
            sb = a.srcStart;
            se = a.srcEnd;
            room = a.srcRoom;
        } else {
            day = a.day;
            sb = a.startSection;
            se = a.endSection;
            room = a.room.isEmpty() ? a.srcRoom : a.room;
        }
        day = clamp(day, 1, 7);
        sb = clamp(sb, 1, PERIODS);
        se = clamp(se, sb, PERIODS);

        ((Spinner) sheet.findViewById(R.id.adjDay)).setSelection(day - 1);
        ((Spinner) sheet.findViewById(R.id.adjSeStart)).setSelection(sb - 1);
        ((Spinner) sheet.findViewById(R.id.adjSeEnd)).setSelection(se - 1);
        EditText roomEt = sheet.findViewById(R.id.adjRoom);
        roomEt.setText(room);
        roomEt.setSelection(roomEt.getText().length());

        // 已有「每周」记录时默认选中「之后每周」：用户多半是在改那一条
        boolean all = (a != null && !cancelled && AdjustCache.SCOPE_ALL.equals(a.scope));
        ((RadioButton) sheet.findViewById(R.id.adjScopeAll)).setChecked(all);
        ((RadioButton) sheet.findViewById(R.id.adjScopeWeek)).setChecked(!all);
    }

    /** 这一周实际要上的课（按周次过滤），按 星期 + 起始节 排序，和课表上的顺序一致 */
    private List<JSONObject> coursesOfWeek(int week) {
        List<JSONObject> out = new ArrayList<>();
        if (scheduleData == null) return out;
        JSONArray cs = scheduleData.optJSONArray("courses");
        if (cs == null) return out;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            if (!inWeek(c.optJSONArray("weeks"), week)) continue;
            out.add(c);
        }
        Collections.sort(out, (a, b) -> {
            int d = a.optInt("day", 0) - b.optInt("day", 0);
            return d != 0 ? d : a.optInt("startSection", 0) - b.optInt("startSection", 0);
        });
        return out;
    }

    /**
     * 课程的唯一键 —— 把调课记录匹配回课程用。
     *
     * 用「课名 + 原星期 + 原起止节」，不用数组下标：重新导入课表后顺序可能变，
     * 下标会串到别的课上；而这四个字段描述的就是同一条排课记录，重导入后依然对得上。
     */
    private static String courseKey(JSONObject c) {
        int st = c.optInt("startSection", 0);
        return c.optString("name", "") + "|" + c.optInt("day", 0) + "|" + st + "|"
                + c.optInt("endSection", st);
    }

    /** 选择器里的一行：位置 · 课名 · 教室（已经调过课的显示新位置并标注） */
    private String courseLabel(JSONObject c, AdjustCache.Adjust a) {
        int day = c.optInt("day", 1);
        int sb = c.optInt("startSection", 1);
        int se = c.optInt("endSection", sb);
        String room = c.optString("position", "");
        String suffix = "";
        if (a != null) {
            if (a.isCancelled()) {
                suffix = "（已停课）";
            } else {
                day = a.day;
                sb = a.startSection;
                se = a.endSection;
                if (!a.room.isEmpty()) room = a.room;
                suffix = "（已调课）";
            }
        }
        StringBuilder sb2 = new StringBuilder();
        sb2.append("周").append(wdCn(day)).append(" ").append(sb).append("-").append(se)
                .append(" 节 · ").append(c.optString("name", ""));
        if (!room.isEmpty()) sb2.append(" · ").append(room);
        return sb2.append(suffix).toString();
    }

    /** 已有记录的一句话描述：「每周 · 高等数学：原 周四 3-4 节 → 周六 5-6 节」 */
    private String adjustText(AdjustCache.Adjust a) {
        if (AdjustCache.SCOPE_DAY.equals(a.scope)) {
            // 整日调休按日期表述，和学校通知、和用户当初的选择口径一致
            return "调休 · " + dateOfWeekDay(a.week, a.srcDay) + " 全天 → "
                    + dateOfWeekDay(a.targetWeek, a.day);
        }
        String scope = AdjustCache.SCOPE_ALL.equals(a.scope) ? "每周" : "第 " + a.week + " 周";
        String from = "周" + wdCn(a.srcDay) + " " + a.srcStart + "-" + a.srcEnd + " 节";
        if (a.isCancelled()) return scope + " · " + a.name + "（原 " + from + "）停课";
        String to = "周" + wdCn(a.day) + " " + a.startSection + "-" + a.endSection + " 节";
        return scope + " · " + a.name + "：原 " + from + " → " + to
                + (a.room.isEmpty() ? "" : "（" + a.room + "）");
    }

    private static String wdCn(int day) {
        return (day >= 1 && day <= 7) ? WD_CN[day - 1] : "?";
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private ArrayAdapter<String> spinnerAdapter(String[] items) {
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return ad;
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
        showContentView(resultView);
        resultView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String emptyScheduleHtml() {
        String name = ScheduleCache.currentName(this);
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>" + scheduleCss + "</style></head><body>"
                + "<h1>我的课表</h1>"
                + "<div class=\"empty\"><b>「" + esc(name) + "」还没有内容</b>"
                + "登录教务处后即可导入本学期课表<br>导入一次即可长期离线查看"
                // 引导入口：直接在这里把「登录并导入」摆出来，
                // 用户不用先自己摸到顶栏那个图标是干什么的
                + "<button class=\"guide\" onclick=\"AndroidResultHost.guideImportSchedule()\">"
                + "登录教务处并导入课表</button></div>"
                + "<p class=\"tip\">也可以从底部「更多 → 教务处登录」先登录。"
                + "多张课表可用课表页右上角 ⋮ 添加与切换；课表只从你本人已登录的教务会话读取，"
                + "保存在手机本地，不会上传。</p>"
                + "</body></html>";
    }

    /** 空教室空态：显示引导页（状态是 ROOM_EMPTY，顶栏给「查空教室」） */
    private void showEmptyRoom() {
        showContentView(resultView);
        resultView.loadDataWithBaseURL(null, emptyRoomHtml(), "text/html", "UTF-8", null);
        applyUi(UiState.ROOM_EMPTY);
    }

    /** 空教室页的空态：没有缓存时先给引导，而不是直接把教务登录页糊上来 */
    private String emptyRoomHtml() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>" + css + "</style></head><body>"
                + "<h1>空闲教室总表</h1>"
                + "<div class=\"empty\"><b>还没有空教室数据</b>"
                + "登录教务处后即可查询全校空闲教室<br>查一次会缓存到本机，之后离线也能翻看"
                + "<button class=\"guide\" onclick=\"AndroidResultHost.guideQueryRooms()\">"
                + "登录教务处并查询</button></div>"
                + "<p class=\"tip\">也可以从底部「更多 → 教务处登录」进入。"
                + "数据实时取自教务系统，不经过任何第三方服务器。</p>"
                + "</body></html>";
    }

    /** 调课在原位置留下的「影子」：告诉用户这节课去哪了 / 为什么不见了 */
    private static final class Ghost {
        final String name;
        final String text;
        final int span;
        final boolean cancelled;

        Ghost(String name, String text, int span, boolean cancelled) {
            this.name = name;
            this.text = text;
            this.span = span;
            this.cancelled = cancelled;
        }
    }

    /** 课程在本周的实际摆放位置（已经叠加单课调课和整天调休）。 */
    private static final class CoursePlacement {
        final JSONObject course;
        final AdjustCache.Adjust adjust;
        final int day;
        final int start;
        final int end;

        CoursePlacement(JSONObject course, AdjustCache.Adjust adjust, int day, int start, int end) {
            this.course = course;
            this.adjust = adjust;
            this.day = day;
            this.start = start;
            this.end = end;
        }

        boolean overlaps(CoursePlacement other) {
            return other != null && day == other.day && start <= other.end && other.start <= end;
        }
    }

    /**
     * 汇总某一周实际显示的课程。
     *
     * 这一层同时处理单课调课、停课和整天调休，后续网格、冲突判断和课程详情
     * 都复用同一份结果，避免三个地方各算一遍后又出现口径不一致。
     */
    private List<CoursePlacement> effectivePlacementsForWeek(int week) {
        List<CoursePlacement> out = new ArrayList<>();
        if (scheduleData == null) return out;
        JSONArray courses = scheduleData.optJSONArray("courses");
        if (courses == null) return out;

        String schedId = ScheduleCache.currentId(this);
        Map<String, AdjustCache.Adjust> adjByKey = new HashMap<>();
        for (AdjustCache.Adjust a : AdjustCache.forWeek(this, schedId, week)) {
            adjByKey.put(a.courseKey, a);
        }

        Map<Integer, AdjustCache.Adjust> dayOut = new HashMap<>();
        List<AdjustCache.Adjust> dayIns = new ArrayList<>();
        for (AdjustCache.Adjust m : AdjustCache.dayMoves(this, schedId)) {
            if (m.week == week) dayOut.put(m.srcDay, m);
            if (m.targetWeek == week) dayIns.add(m);
        }

        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null || !inWeek(c.optJSONArray("weeks"), week)) continue;
            if (dayOut.containsKey(c.optInt("day", 0))) continue;

            AdjustCache.Adjust a = adjByKey.get(courseKey(c));
            if (a != null && a.isCancelled()) continue;

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
            out.add(new CoursePlacement(c, a, day, st, en));
        }

        // 整天调休搬入的课程，其 weeks 属于源周，需要单独补进目标周。
        for (AdjustCache.Adjust m : dayIns) {
            for (int i = 0; i < courses.length(); i++) {
                JSONObject c = courses.optJSONObject(i);
                if (c == null || c.optInt("day", 0) != m.srcDay) continue;
                if (!inWeek(c.optJSONArray("weeks"), m.week)) continue;
                // 课程本来也覆盖目标周时不重复摆放。
                if (inWeek(c.optJSONArray("weeks"), week)) continue;
                int st = c.optInt("startSection", 0);
                int en = c.optInt("endSection", st);
                if (st < 1 || st > PERIODS) continue;
                if (en < st) en = st;
                if (en > PERIODS) en = PERIODS;
                out.add(new CoursePlacement(c, m, m.day, st, en));
            }
        }
        return out;
    }

    /** 同一格或跨节次重叠的课程全部计入冲突数。 */
    private static Map<CoursePlacement, Integer> computeConflictCounts(
            List<CoursePlacement>[][] grid) {
        Map<CoursePlacement, Integer> counts = new HashMap<>();
        for (int day = 0; day < 7; day++) {
            List<CoursePlacement> all = new ArrayList<>();
            for (int row = 0; row < PERIODS; row++) {
                List<CoursePlacement> bucket = grid[row][day];
                if (bucket != null) all.addAll(bucket);
            }
            for (int i = 0; i < all.size(); i++) {
                for (int j = i + 1; j < all.size(); j++) {
                    CoursePlacement a = all.get(i);
                    CoursePlacement b = all.get(j);
                    if (!a.overlaps(b)) continue;
                    if (a.course == b.course) continue;
                    counts.put(a, counts.getOrDefault(a, 0) + 1);
                    counts.put(b, counts.getOrDefault(b, 0) + 1);
                }
            }
        }
        return counts;
    }

    /** 生成课表周视图 HTML */
    @SuppressWarnings("unchecked")
    private String buildScheduleHtml(JSONObject o, int week) throws Exception {
        JSONArray courses = o.getJSONArray("courses");
        long weekMon = weekMonday(week);

        // 课表外观：周六/周日可以整列隐藏（很多人周末没课，藏掉表格更清爽）
        boolean showSat = prefs().getBoolean(KEY_SHOW_SAT, true);
        boolean showSun = prefs().getBoolean(KEY_SHOW_SUN, true);
        List<Integer> visCols = new ArrayList<>();   // 显示的列 → 真实星期(1-7)
        for (int d = 1; d <= 7; d++) {
            if (d == 6 && !showSat) continue;
            if (d == 7 && !showSun) continue;
            visCols.add(d);
        }
        if (visCols.isEmpty()) {   // 兜底：全藏了就没法看了
            visCols.add(6);
            visCols.add(7);
        }

        // 今天所在的星期（1=周一 … 7=周日）；不在本周则为 -1。
        // 渲染时遍历 visCols，被隐藏的列不会输出，所以这里无需再管显隐。
        int todayDay = -1;
        if (mondayOf(System.currentTimeMillis()) == weekMon) {
            // 复用 weekdayOf（1=周一 … 7=周日）。
            // ⚠️ 早先这里手写成 `dow - Calendar.MONDAY`，漏了 +1：
            // Calendar 里 MONDAY=2，周五的 dow=6 → 算出 4（周四），
            // 于是周一到周六的高亮整整偏了一列，周四被当成「今天」。
            todayDay = weekdayOf(System.currentTimeMillis());
        }

        // ── 调课：本地调整 ────────────────────────────────────────────
        // 调整只影响显示：教务的数据一个字节都不改，重新导入课表后记录依然有效。
        final String schedId = ScheduleCache.currentId(this);
        final List<AdjustCache.Adjust> adjusts = AdjustCache.forWeek(this, schedId, week);
        Map<String, AdjustCache.Adjust> adjByKey = new HashMap<>();
        for (AdjustCache.Adjust a : adjusts) adjByKey.put(a.courseKey, a);

        Map<Integer, AdjustCache.Adjust> dayOut = new HashMap<>();
        List<AdjustCache.Adjust> dayIns = new ArrayList<>();
        for (AdjustCache.Adjust m : AdjustCache.dayMoves(this, schedId)) {
            if (m.week == week) dayOut.put(m.srcDay, m);
            if (m.targetWeek == week) dayIns.add(m);
        }

        List<CoursePlacement>[][] grid = new List[PERIODS][7];
        boolean[][] covered = new boolean[PERIODS][7];
        Ghost[][] ghost = new Ghost[PERIODS][7];
        boolean[][] ghostCovered = new boolean[PERIODS][7];

        // 原位置影子：整天调休、停课、单课改时间后，都保留明确的去向提示。
        JSONArray allCourses = o.optJSONArray("courses");
        if (allCourses != null) {
            for (int i = 0; i < allCourses.length(); i++) {
                JSONObject c = allCourses.optJSONObject(i);
                if (c == null || !inWeek(c.optJSONArray("weeks"), week)) continue;
                int day = c.optInt("day", 0);
                int st = c.optInt("startSection", 0);
                int en = c.optInt("endSection", st);
                if (day < 1 || day > 7 || st < 1 || st > PERIODS) continue;
                if (en < st) en = st;
                if (en > PERIODS) en = PERIODS;

                String name = c.optString("name", "");
                AdjustCache.Adjust dm = dayOut.get(day);
                if (dm != null) {
                    ghost[st - 1][day - 1] = new Ghost(name,
                            "调休至 " + dateOfWeekDay(dm.targetWeek, dm.day),
                            en - st + 1, false);
                    continue;
                }

                AdjustCache.Adjust a = adjByKey.get(courseKey(c));
                if (a == null) continue;
                if (a.isCancelled()) {
                    ghost[st - 1][day - 1] = new Ghost(name, "本周停课", en - st + 1, true);
                } else if (a.day != day || a.startSection != st || a.endSection != en) {
                    ghost[st - 1][day - 1] = new Ghost(name,
                            "已调至 周" + wdCn(a.day) + " "
                                    + a.startSection + "-" + a.endSection + " 节",
                            en - st + 1, false);
                }
            }
        }

        // 同一个格子保留全部课程；用户在详情里选过哪门，就把那门渲染成主卡。
        for (CoursePlacement p : effectivePlacementsForWeek(week)) {
            int r = p.start - 1, col = p.day - 1;
            List<CoursePlacement> bucket = grid[r][col];
            if (bucket == null) {
                bucket = new ArrayList<>();
                grid[r][col] = bucket;
            }
            bucket.add(p);
            for (int k = r + 1; k < p.end; k++) covered[k][col] = true;
        }
        Map<CoursePlacement, Integer> conflictCounts = computeConflictCounts(grid);

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>我的课表</title><style>").append(scheduleCss).append("</style>");
        sb.append("<style>:root{--page-bg:").append(cssColor(ThemeStore.background(this)))
                .append(";--surface:").append(cssColor(ThemeStore.surface(this)))
                .append(";--accent:").append(cssColor(ThemeStore.accent(this)))
                .append(";--accent-dark:").append(cssColor(darkenColor(ThemeStore.accent(this), .78f)))
                .append(";}</style></head><body>");
        // 标题用「当前课表名」：多张课表之后，光写「我的课表」分不清在看哪一张
        sb.append("<h1 class=\"term-title\">")
                .append(esc(ScheduleCache.currentName(this))).append("</h1>");
        String term = o.optString("termName", "");
        sb.append("<p class=\"sub\">");
        if (!term.isEmpty() && !term.equals(ScheduleCache.currentName(this))) {
            sb.append(esc(term)).append(" · ");
        }
        sb.append("更新于 ").append(esc(o.optString("updated", ""))).append("</p>");

        // 周次条：中间只显示「第 N 周」。开学日期的设置挪进了
        // 「课表设置」页（点中间改日期是个藏得太深的入口，用户反馈过）。
        sb.append("<div class=\"weekbar\">")
                .append("<button onclick=\"AndroidResultHost.prevWeek()\">‹ 上周</button>")
                .append("<div class=\"cur\">第 ").append(week).append(" 周</div>")
                .append("<button onclick=\"AndroidResultHost.nextWeek()\">下周 ›</button>")
                .append("<button class=\"today\" onclick=\"AndroidResultHost.thisWeek()\">本周</button>")
                .append("</div>");

        sb.append("<div class=\"gridwrap\"><table class=\"grid\"><thead><tr>");
        sb.append("<th class=\"corner\"></th>");
        for (int d : visCols) {
            int c = d - 1;
            sb.append("<th").append(d == todayDay ? " class=\"today\"" : "").append(">")
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

            for (int d : visCols) {
                int c = d - 1;
                if (covered[r][c]) continue;      // 已被上方 rowspan 占用
                List<CoursePlacement> bucket = grid[r][c];
                CoursePlacement placement = pickConflictPlacement(bucket, r, c);
                if (placement != null) {
                    JSONObject co = placement.course;
                    AdjustCache.Adjust adj = placement.adjust;
                    int cs = placement.start;
                    int ce = placement.end;
                    int span = clamp(ce - cs + 1, 1, PERIODS - r);
                    String nm = co.optString("name", "");
                    // 调过课的卡片要显示调整后的教室，否则用户会按旧教室跑错楼
                    String room = (adj != null && !adj.room.isEmpty())
                            ? adj.room : co.optString("position", "");
                    int conflicts = conflictCounts.getOrDefault(placement, 0);
                    sb.append("<td class=\"cell\" rowspan=\"").append(span).append("\">")
                            .append("<div class=\"course c").append(courseColorIdx(nm))
                            .append(adj != null ? " adj" : "")
                            .append(conflicts > 0 ? " conflict" : "")
                            .append("\" onclick=\"AndroidResultHost.onCourseClick(")
                            .append(d).append(",").append(cs).append(",").append(ce)
                            .append(")\">")
                            .append("<div class=\"nm\">").append(esc(nm)).append("</div>")
                            .append("<div class=\"rm\">").append(esc(room)).append("</div>")
                            .append(adj != null ? "<span class=\"tag\">调</span>" : "")
                            .append(conflicts > 0
                                    ? "<span class=\"tag conflict\">冲突</span>" : "")
                            .append("</div></td>");
                } else if (ghost[r][c] != null && !ghostCovered[r][c]) {
                    Ghost g = ghost[r][c];
                    int span = clamp(g.span, 1, PERIODS - r);
                    for (int k = r + 1; k < r + span; k++) ghostCovered[k][c] = true;
                    sb.append("<td class=\"cell\" rowspan=\"").append(span).append("\">")
                            .append("<div class=\"ghost").append(g.cancelled ? " cancel" : "")
                            .append("\"><b>").append(esc(g.name)).append("</b>")
                            .append("<span>").append(esc(g.text)).append("</span></div></td>");
                } else {
                    // 没课的时段：点一下直接查这个时段的空闲教室
                    sb.append("<td class=\"cell free\"><div class=\"free-slot\" "
                                    + "onclick=\"AndroidResultHost.onFreeClick(")
                            .append(d).append(",").append(r + 1)
                            .append(")\"><span class=\"plus\">+</span>"
                                    + "<span class=\"fd\">空教室</span></div></td>");
                }
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");

        // 本周调课清单：影子可能被别的课挡住（同一格只画得下一个），
        // 清单保证「这周改了哪些课」一定看得到，不必再去翻设置面板
        int adjustCount = adjusts.size() + dayOut.size() + dayIns.size();
        if (adjustCount > 0) {
            sb.append("<div class=\"adjsum\"><b>本周调课 · ").append(adjustCount).append(" 条</b>");
            for (AdjustCache.Adjust m : dayMovesOf(week, dayOut, dayIns)) {
                if (m.week == week) {
                    sb.append("<div class=\"row\">· ").append(dateOfWeekDay(m.week, m.srcDay))
                            .append(" 全天 → ").append(dateOfWeekDay(m.targetWeek, m.day))
                            .append("（调休）</div>");
                } else {
                    sb.append("<div class=\"row\">· ").append(dateOfWeekDay(m.week, m.srcDay))
                            .append(" 全天 → 本周 ").append(dateOfWeekDay(m.targetWeek, m.day))
                            .append("（调休）</div>");
                }
            }
            for (AdjustCache.Adjust a : adjusts) {
                String scope = AdjustCache.SCOPE_ALL.equals(a.scope) ? "（每周）" : "";
                sb.append("<div class=\"row\">· ").append(esc(a.name));
                if (a.isCancelled()) {
                    sb.append(" 周").append(wdCn(a.srcDay))
                            .append(" ").append(a.srcStart).append("-").append(a.srcEnd)
                            .append(" 节 <b>停课</b>");
                } else {
                    sb.append(" 由 周").append(wdCn(a.srcDay))
                            .append(" ").append(a.srcStart).append("-").append(a.srcEnd)
                            .append(" 节 → 周").append(wdCn(a.day))
                            .append(" ").append(a.startSection).append("-").append(a.endSection)
                            .append(" 节");
                }
                sb.append(scope).append("</div>");
            }
            sb.append("<div class=\"row hint\">在「⋮ → 调课」里可以修改或撤销</div></div>");
        }

        sb.append("<p class=\"tip\">点 <b>空白时段</b> 查这个时间的空闲教室；"
                + "点 <b>课程卡片</b> 看课程详情；"
                + "老师临时挪课 / 换教室，用右上角 <b>⋮ → 调课</b> 改本地显示。</p>");
        sb.append("<p class=\"foot\">课表保存在手机本地，不会上传任何信息。</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 冲突格子的主卡：优先用用户在详情里选过的课程，否则保留第一门。 */
    private CoursePlacement pickConflictPlacement(
            List<CoursePlacement> bucket, int row, int col) {
        if (bucket == null || bucket.isEmpty()) return null;
        CoursePlacement first = bucket.get(0);
        if (bucket.size() == 1) return first;
        String pref = conflictPrefs.get(row + "|" + col);
        if (pref == null) return first;
        for (CoursePlacement p : bucket) {
            if (pref.equals(courseKey(p.course))) return p;
        }
        return first;
    }

    /** 调课清单里整天调休部分的条目（本周搬出的 + 搬入的） */
    private static List<AdjustCache.Adjust> dayMovesOf(int week,
                                                       Map<Integer, AdjustCache.Adjust> dayOut,
                                                       List<AdjustCache.Adjust> dayIns) {
        List<AdjustCache.Adjust> out = new ArrayList<>();
        for (AdjustCache.Adjust m : dayOut.values()) {
            if (m.week == week) out.add(m);
        }
        out.addAll(dayIns);
        return out;
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

    /** 课表 → 空教室：把课表节次折到查询时段后，统一走本地缓存优先流程。 */
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
        openSingleSlot(dateStr(target), day, qb, qe, label);
    }

    /**
     * 单时段查询总入口。
     *
     * 只要本机存在空教室缓存，就先尝试按目标日期筛选；命中时完全离线。
     * 未命中但缓存确实存在时，也只说明缓存范围并给出刷新选择，不强制登录。
     */
    private void openSingleSlot(String date, int day, int qb, int qe, String label) {
        int slotIdx = slotIndexOf(qb);

        JSONObject cachedDay = findCachedDay(date);
        if (cachedDay != null) {
            renderCachedSingle(date, day, qb, qe, label, slotIdx, cachedDay);
            return;
        }

        if (hasRoomCache()) {
            showRoomCacheMissDialog(date, day, qb, qe, label);
            return;
        }
        querySingleSlotFromEams(date, day, qb, qe, label);
    }

    private void renderCachedSingle(String date, int day, int qb, int qe, String label,
                                    int slotIdx, JSONObject cachedDay) {
        singleMode = true;
        singleWeekday = WD_CN[day - 1];
        singleDay = day;
        highlightDate = date;
        highlightTb = qb;
        highlightSlotIdx = slotIdx;
        singleDate = date;
        singleTb = qb;
        singleTe = qe;
        singleLabel = label;
        try {
            // 缓存视图和教务单时段查询保持一致：只显示命中的那组时段。
            // 切换时段仍可用结果页顶部按钮，每次都从缓存中重新筛。
            JSONObject singleDayView = new JSONObject(cachedDay.toString());
            JSONArray allSlots = singleDayView.optJSONArray("slots");
            JSONObject picked = null;
            if (allSlots != null) {
                for (int i = 0; i < allSlots.length(); i++) {
                    JSONObject slot = allSlots.optJSONObject(i);
                    if (slot == null) continue;
                    JSONArray rooms = slot.optJSONObject("buildings") == null
                            ? null : slot.optJSONObject("buildings").names();
                    if (slot.optBoolean("ok", false)
                            && (rooms != null && rooms.length() > 0)) {
                        // 匹配不到明确时段时，退回「第一个可用时段」，避免页面为空。
                        if (picked == null) picked = slot;
                    }
                }
            }
            if (slotIdx >= 0 && slotIdx < allSlots.length()) {
                picked = allSlots.optJSONObject(slotIdx);
            }
            if (picked == null) {
                Toast.makeText(this, "缓存中没有可用时段", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray slots = new JSONArray().put(picked);
            singleDayView.put("slots", slots);
            JSONObject view = new JSONObject();
            view.put("ok", true);
            view.put("single", true);
            view.put("days", new JSONArray().put(singleDayView));
            view.put("fromCache", true);
            view.put("cacheAgeText", ResultCache.ageText(this));
            view.put("cacheExpired", ResultCache.isExpired(this));
            view.put("updated", cachedDay.optString("date", ""));

            if (currentTab != TAB_CLASSROOM) switchTab(TAB_CLASSROOM);
            else applyIdleUi();
            showHtml(buildHtml(view));
            statusText.setText(label + " · 来自本机缓存");
            setEamsBrowserOverlay(false);
        } catch (Exception e) {
            Toast.makeText(this, "缓存结果读取失败，请重新查询", Toast.LENGTH_SHORT).show();
        }
    }

    private void querySingleSlotFromEams(String date, int day, int qb, int qe, String label) {
        if (currentTab != TAB_CLASSROOM) switchTab(TAB_CLASSROOM);
        else applyIdleUi();

        highlightDate = date;
        highlightTb = qb;
        highlightSlotIdx = slotIndexOf(qb);
        singleMode = true;
        singleWeekday = WD_CN[day - 1];
        singleDay = day;

        // 过去的日子也能查（单时段接口不受「从今天起算」限制），所以不需要回退分支
        singleDate = date;
        singleTb = qb;
        singleTe = qe;
        singleLabel = label;
        if (isEamsLoaded()) {
            pendingSingle = false;
            executeSingleSlotQuery();
            return;
        }

        pendingSingle = true;
        loginHintShown = false;
        loginView.loadUrl(EAMS_BASE + "classroom/apply/free!search.action");
    }

    private void executeSingleSlotQuery() {
        if (loginView == null) return;
        // 只查一组时段，很快；但引导卡片仍给出预期，避免「点了没反应」的错觉
        showProgressDialog("正在查询空教室", "只查这一组时段，几秒就好");
        statusText.setText("正在查询 " + singleLabel + " 的空教室…");
        setBusy(true);
        loginView.evaluateJavascript(injectJs, null);
        loginView.evaluateJavascript("window.nqFetchSlot('" + singleDate + "',"
                + singleTb + "," + singleTe + ",'"
                + singleLabel.replace("'", "") + "');", null);
    }

    private boolean hasRoomCache() {
        String raw = ResultCache.load(this);
        if (raw == null) return false;
        try {
            return new JSONObject(raw).optBoolean("ok", false);
        } catch (Exception e) {
            return false;
        }
    }

    private String roomCacheRange() {
        String raw = ResultCache.load(this);
        if (raw == null) return "";
        try {
            JSONArray days = new JSONObject(raw).optJSONArray("days");
            if (days == null || days.length() == 0) return "";
            String first = days.optJSONObject(0).optString("date", "");
            String last = days.optJSONObject(days.length() - 1).optString("date", "");
            if (first.equals(last)) return first;
            return first + " 至 " + last;
        } catch (Exception e) {
            return "";
        }
    }

    private void showRoomCacheMissDialog(String date, int day, int qb, int qe, String label) {
        String range = roomCacheRange();
        String message = "本机已有空教室缓存，但不包含 " + date + "。";
        if (!range.isEmpty()) message += "\n当前缓存范围：" + range + "。";
        message += "\n\n通道 2 只提供最近 7 天的总表，无法直接筛选范围外日期。"
                + "可以更新缓存，或改用教务实时查询。";

        new AlertDialog.Builder(this)
                .setTitle("缓存中没有这一天")
                .setMessage(message)
                .setPositiveButton("更新缓存", (d, w) -> importRoomsFromWeb(true))
                .setNeutralButton("教务实时查询",
                        (d, w) -> querySingleSlotFromEams(date, day, qb, qe, label))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从本机缓存里找指定日期的那一天数据（没有返回 null） */
    private JSONObject findCachedDay(String date) {
        String s = ResultCache.load(this);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            if (!o.optBoolean("ok", false)) return null;
            JSONArray days = o.optJSONArray("days");
            if (days == null) return null;
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d != null && date.equals(d.optString("date"))) {
                    // 只有真有数据的日期才算命中：全是 ok=false 的空壳不如直接去实时查
                    JSONArray slots = d.optJSONArray("slots");
                    boolean usable = false;
                    if (slots != null) {
                        for (int k = 0; k < slots.length(); k++) {
                            JSONObject sl = slots.optJSONObject(k);
                            if (sl != null && sl.optBoolean("ok", false)) {
                                usable = true;
                                break;
                            }
                        }
                    }
                    return usable ? d : null;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
        String label = singleDate + " 周" + singleWeekday + " " + qb + "-" + qe + " 节";
        openSingleSlot(singleDate, singleDay, qb, qe, label);
    }

    /* ---------- 课程详情 ---------- */

    /** 点课程卡片：这节课你是有课的，所以给详情，而不是去查空教室 */
    private void showCourseDetail(int day, int st, int en) {
        showCourseDetail(day, st, en, null);
    }

    private void showCourseDetail(int day, int st, int en, String preferredKey) {
        int week = currentWeek();
        List<CoursePlacement> placements = effectivePlacementsForWeek(week);
        CoursePlacement hit = null;
        CoursePlacement firstAtPosition = null;
        for (CoursePlacement p : placements) {
            if (p.day != day || p.start != st || p.end != en) continue;
            if (firstAtPosition == null) firstAtPosition = p;
            if (preferredKey != null && preferredKey.equals(courseKey(p.course))) {
                hit = p;
                break;
            }
        }
        if (hit == null) hit = firstAtPosition;
        if (hit == null) {
            Toast.makeText(this, "这节课本周不上", Toast.LENGTH_SHORT).show();
            return;
        }

        // 选中课程放在第一行，其余重叠课程随后列出。
        List<CoursePlacement> conflictCourses = new ArrayList<>();
        conflictCourses.add(hit);
        for (CoursePlacement p : placements) {
            if (p == hit || !p.overlaps(hit)) continue;
            if (p.course == hit.course) continue;
            conflictCourses.add(p);
        }
        showCourseDetailSheet(hit, conflictCourses);
    }

    private void showCourseDetailSheet(final CoursePlacement hit,
                                       final List<CoursePlacement> conflictCourses) {
        View sheet = LayoutInflater.from(this).inflate(R.layout.sheet_course_detail, null);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        applyThemeToView(sheet);
        dialog.setCanceledOnTouchOutside(true);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setGravity(Gravity.BOTTOM);
            win.setWindowAnimations(R.style.BottomSheetAnimation);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        final CoursePlacement[] current = {hit};
        TextView title = sheet.findViewById(R.id.tvCourseTitle);
        TextView weeksView = sheet.findViewById(R.id.tvCourseWeeks);
        TextView timeView = sheet.findViewById(R.id.tvCourseTime);
        TextView teacherView = sheet.findViewById(R.id.tvCourseTeacher);
        TextView roomView = sheet.findViewById(R.id.tvCourseRoom);
        TextView adjustView = sheet.findViewById(R.id.tvCourseAdjust);
        TextView freeButton = sheet.findViewById(R.id.btnCourseFree);
        Runnable refreshUi = () -> {
            CoursePlacement p = current[0];
            JSONObject course = p.course;
            String name = course.optString("name", "课程");
            String room = p.adjust != null && !p.adjust.room.isEmpty()
                    ? p.adjust.room : course.optString("position", "");
            title.setText(name);
            weeksView.setText(weeksText(course.optJSONArray("weeks")));

            String t0 = slotTime(p.start, false);
            String t1 = slotTime(p.end, true);
            String time = "第 " + p.start + (p.end > p.start ? "-" + p.end : "") + " 节";
            if (!t0.isEmpty() && !t1.isEmpty()) time += "　" + t0 + "–" + t1;
            timeView.setText(time);
            teacherView.setText(nz(course.optString("teacher", "")));
            roomView.setText(nz(room));

            if (p.adjust == null) {
                adjustView.setVisibility(View.GONE);
            } else if (AdjustCache.SCOPE_DAY.equals(p.adjust.scope)) {
                adjustView.setText("本地调休：原排在第 " + p.adjust.week + " 周 周"
                        + WD_CN[p.adjust.srcDay - 1] + "，已整体调整到当前时间");
                adjustView.setVisibility(View.VISIBLE);
            } else if (p.adjust.isCancelled()) {
                adjustView.setText("这节课本周已停课");
                adjustView.setVisibility(View.VISIBLE);
            } else {
                adjustView.setText("本地调课：原 周" + WD_CN[p.adjust.srcDay - 1]
                        + " " + p.adjust.srcStart + "-" + p.adjust.srcEnd + " 节");
                adjustView.setVisibility(View.VISIBLE);
            }

            freeButton.setText("查询“" + name + "”此时段空教室");
        };
        refreshUi.run();

        View conflictBox = sheet.findViewById(R.id.llCourseConflicts);
        LinearLayout conflictList = sheet.findViewById(R.id.llConflictList);
        if (conflictCourses.size() <= 1) {
            conflictBox.setVisibility(View.GONE);
        } else {
            conflictBox.setVisibility(View.VISIBLE);
            ((TextView) sheet.findViewById(R.id.tvConflictTitle))
                    .setText("时间冲突课程 · " + conflictCourses.size() + " 门");
            int pad = (int) (getResources().getDisplayMetrics().density * 6);
            for (CoursePlacement p : conflictCourses) {
                RadioButton rb = new RadioButton(this);
                rb.setText(p.course.optString("name", "课程"));
                rb.setTextSize(16f);
                rb.setTextColor(0xFF252A33);
                rb.setPadding(0, pad, 0, pad);
                rb.setButtonTintList(ColorStateList.valueOf(0xFF1B4D8F));
                rb.setChecked(p == current[0]);
                rb.setOnClickListener(v -> {
                    if (p == current[0]) return;
                    current[0] = p;
                    conflictPrefs.put(hit.day + "|" + (hit.start - 1),
                            courseKey(p.course));
                    refreshUi.run();
                    renderSchedule();
                    for (int j = 0; j < conflictList.getChildCount(); j++) {
                        RadioButton item = (RadioButton) conflictList.getChildAt(j);
                        item.setChecked(item == rb);
                    }
                });
                conflictList.addView(rb, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        sheet.findViewById(R.id.btnCourseFree).setOnClickListener(v -> {
            dialog.dismiss();
            jumpToFreeRooms(current[0].day, current[0].start, current[0].end);
        });
        sheet.findViewById(R.id.btnCourseClose).setOnClickListener(v -> dialog.dismiss());
        sheet.findViewById(R.id.btnCourseCopy).setOnClickListener(v -> {
            CoursePlacement p = current[0];
            JSONObject course = p.course;
            String name = course.optString("name", "课程");
            String room = p.adjust != null && !p.adjust.room.isEmpty()
                    ? p.adjust.room : course.optString("position", "");
            String text = name + "\n周次：" + weeksText(course.optJSONArray("weeks"))
                    + "\n时间：周" + WD_CN[p.day - 1]
                    + " 第 " + p.start + "-" + p.end + " 节"
                    + "\n教师：" + nz(course.optString("teacher", ""))
                    + "\n地点：" + nz(room);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("course", text));
                Toast.makeText(this, "课程信息已复制", Toast.LENGTH_SHORT).show();
            }
        });
        sheet.findViewById(R.id.btnCourseEdit).setOnClickListener(v -> {
            dialog.dismiss();
            CoursePlacement p = current[0];
            showEditCourseDialog(p.course.optString("name", "课程"), p.course);
        });
        sheet.findViewById(R.id.btnCourseDelete).setOnClickListener(v -> {
            dialog.dismiss();
            showDeleteScopeSheet(current[0]);
        });
        dialog.show();
    }

    /**
     * 课程删除范围选择：仅当前周、同位置全部周次、整门课程。
     * 调课记录会按对应范围清理，避免留下孤儿记录。
     */
    private void showDeleteScopeSheet(final CoursePlacement placement) {
        View sheet = LayoutInflater.from(this).inflate(R.layout.sheet_delete_scope, null);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setGravity(Gravity.BOTTOM);
            win.setWindowAnimations(R.style.BottomSheetAnimation);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        String courseName = placement.course.optString("name", "课程");
        final CourseInfo allInfo = new CourseInfo(courseName);
        JSONArray courses = scheduleData == null ? null : scheduleData.optJSONArray("courses");
        if (courses != null) {
            for (int i = 0; i < courses.length(); i++) {
                JSONObject c = courses.optJSONObject(i);
                if (c != null && courseName.equals(c.optString("name", ""))) allInfo.count++;
            }
        }

        sheet.findViewById(R.id.optionOneWeek).setOnClickListener(v -> {
            dialog.dismiss();
            deleteCurrentWeekInstance(placement);
        });
        sheet.findViewById(R.id.optionSameSlot).setOnClickListener(v -> {
            dialog.dismiss();
            deleteSameSlotAcrossWeeks(placement);
        });
        sheet.findViewById(R.id.optionAllCourse).setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteCourse(allInfo);
        });
        sheet.findViewById(R.id.optionCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * 仅删除当前周显示的这节课。做法不是直接删排课记录，而是从该记录的
     * 周次列表里移掉当前周；这样其他周不受影响。
     */
    private void deleteCurrentWeekInstance(final CoursePlacement placement) {
        JSONObject target = placement.course;
        try {
            JSONArray weeks = target.optJSONArray("weeks");
            if (weeks == null) {
                weeks = new JSONArray();
                for (int w = 1; w <= maxWeekOfView(); w++) weeks.put(w);
                target.put("weeks", weeks);
            }
            int currentViewWeek = currentWeek();
            for (int i = weeks.length() - 1; i >= 0; i--) {
                if (weeks.optInt(i, -1) == currentViewWeek) weeks.remove(i);
            }
            if (weeks.length() == 0 && scheduleData != null) {
                JSONArray cs = scheduleData.optJSONArray("courses");
                if (cs != null) {
                    for (int i = 0; i < cs.length(); i++) {
                        if (cs.optJSONObject(i) == target) {
                            cs.remove(i);
                            break;
                        }
                    }
                }
            }
            persistSchedule();
            refreshCourseManager();
            renderSchedule();
            Toast.makeText(this, "已删除当前周这一节课", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "删除失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 删除同星期同起止节同教师同地点、覆盖当前周的所有排课记录。
     * 这和参考软件的“全部周五同老师同地点”一致。
     */
    private void deleteSameSlotAcrossWeeks(final CoursePlacement placement) {
        try {
            JSONArray cs = scheduleData.optJSONArray("courses");
            if (cs == null) return;
            String name = placement.course.optString("name", "");
            String teacher = nz(placement.course.optString("teacher", ""));
            String room = placement.adjust != null && !placement.adjust.room.isEmpty()
                    ? placement.adjust.room : nz(placement.course.optString("position", ""));
            int day = placement.day, st = placement.start, en = placement.end;

            for (int i = cs.length() - 1; i >= 0; i--) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null || !name.equals(c.optString("name", ""))) continue;
                int cDay = c.optInt("day", 0);
                int cSt = c.optInt("startSection", 0);
                int cEn = Math.max(cSt, c.optInt("endSection", cSt));
                String cTeacher = nz(c.optString("teacher", ""));
                String cRoom = nz(c.optString("position", ""));
                if (cDay != day || cSt != st || cEn != en) continue;
                if (!teacher.equals(cTeacher) || !room.equals(cRoom)) continue;
                int currentViewWeek = currentWeek();
                if (!inWeek(c.optJSONArray("weeks"), currentViewWeek)) continue;
                cs.remove(i);
                AdjustCache.removeCourse(this, ScheduleCache.currentId(this), courseKey(c));
            }
            persistSchedule();
            refreshCourseManager();
            renderSchedule();
            Toast.makeText(this, "已删除同位置的全部周次课程", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "删除失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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

    /* ==================== 课表设置 / 课程管理 子页 ==================== */

    /** 课程卡片墙的淡色底（与课表十色循环一一对应，文字统一用深色） */
    private static final int[] COURSE_PASTEL = {
            0xFFF28B82, 0xFF81C995, 0xFF8AB4F8, 0xFFFDD663, 0xFFC58AF9,
            0xFF78D9D0, 0xFFFFB68B, 0xFFF6A6C1, 0xFFC5E384, 0xFF9FB7D9
    };

    /** 绑定两个子页的点击事件（只绑一次，在 onCreate 里调用） */
    private void setupSchedSubPages() {
        schedSettingsPage = findViewById(R.id.schedSettingsPage);
        courseManagerPage = findViewById(R.id.courseManagerPage);

        schedSettingsPage.findViewById(R.id.ssBack)
                .setOnClickListener(v -> closeSchedSubPage());
        schedSettingsPage.findViewById(R.id.ssRowName)
                .setOnClickListener(v -> renameCurrentSchedule());
        schedSettingsPage.findViewById(R.id.ssRowTimes)
                .setOnClickListener(v -> showTimeSlots());
        schedSettingsPage.findViewById(R.id.ssRowTermStart)
                .setOnClickListener(v -> resetTermStart());
        schedSettingsPage.findViewById(R.id.ssRowCourses)
                .setOnClickListener(v -> openCourseManager());
        schedSettingsPage.findViewById(R.id.ssRowReimport)
                .setOnClickListener(v -> startScheduleImport());
        schedSettingsPage.findViewById(R.id.ssRowDelete)
                .setOnClickListener(v -> deleteCurrentSchedule());

        Switch swSat = schedSettingsPage.findViewById(R.id.ssShowSat);
        Switch swSun = schedSettingsPage.findViewById(R.id.ssShowSun);
        swSat.setChecked(prefs().getBoolean(KEY_SHOW_SAT, true));
        swSun.setChecked(prefs().getBoolean(KEY_SHOW_SUN, true));
        // 勾选即生效：重渲染当前课表（列少了表格反而更宽松好认）
        swSat.setOnCheckedChangeListener((b, on) -> {
            prefs().edit().putBoolean(KEY_SHOW_SAT, on).apply();
            renderSchedule();
        });
        swSun.setOnCheckedChangeListener((b, on) -> {
            prefs().edit().putBoolean(KEY_SHOW_SUN, on).apply();
            renderSchedule();
        });

        courseManagerPage.findViewById(R.id.cmBack)
                .setOnClickListener(v -> closeSchedSubPage());
        courseManagerPage.findViewById(R.id.cmClear)
                .setOnClickListener(v -> confirmClearCourses());
        courseManagerPage.findViewById(R.id.cmAdd)
                .setOnClickListener(v -> addCourseManually());
    }

    /** 关掉子页回到课表（返回键和子页的返回按钮共用） */
    private void closeSchedSubPage() {
        if (currentTab != TAB_SCHEDULE) {
            switchTab(TAB_SCHEDULE);
            return;
        }
        renderSchedulePage();
    }

    /** 打开「课表设置」页并刷新其上的动态文案 */
    private void openSchedSettings() {
        if (scheduleData == null) {
            Toast.makeText(this, "先导入课表才能设置", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshSchedSettings();
        showContentView(schedSettingsPage);
    }

    private void refreshSchedSettings() {
        if (schedSettingsPage == null) return;
        ((TextView) schedSettingsPage.findViewById(R.id.ssName))
                .setText(ScheduleCache.currentName(this));

        String sid = scheduleData == null ? "" : scheduleData.optString("semesterId", "");
        long start = termStartMs(sid);
        TextView ts = schedSettingsPage.findViewById(R.id.ssTermStart);
        if (start > 0) {
            Calendar c = Calendar.getInstance(Locale.CHINA);
            c.setTimeInMillis(start);
            ts.setText(dateStr(start) + " 星期"
                    + "日一二三四五六".charAt(c.get(Calendar.DAY_OF_WEEK) - 1));
        } else {
            ts.setText("点击设置");
        }

        ((TextView) schedSettingsPage.findViewById(R.id.ssCurrentWeek))
                .setText("第 " + actualCurrentWeek() + " 周");
        ((TextView) schedSettingsPage.findViewById(R.id.ssPeriods))
                .setText(PERIODS + " 节");
        ((TextView) schedSettingsPage.findViewById(R.id.ssMaxWeek))
                .setText(maxWeekOfView() + " 周");
    }

    /** 上课时间：展示教务导入的每节课起止时间（只读 —— 时间来自教务，改了会和实际上课对不上） */
    private void showTimeSlots() {
        if (scheduleData == null) {
            Toast.makeText(this, "先导入课表才能查看上课时间", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONArray slots = scheduleData.optJSONArray("timeSlots");
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < PERIODS; r++) {
            String st = "", et = "";
            if (slots != null && slots.length() > r) {
                JSONObject t = slots.optJSONObject(r);
                if (t != null) {
                    st = t.optString("s", "");
                    et = t.optString("e", "");
                }
            }
            sb.append("第 ").append(r + 1).append(" 节　")
                    .append(st.isEmpty() ? "--:--" : st).append(" ~ ")
                    .append(et.isEmpty() ? "--:--" : et).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("上课时间")
                .setMessage(sb.toString().trim())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 修改当前课表的名字（面板里那张卡片和课表页标题都会跟着变） */
    private void renameCurrentSchedule() {
        final EditText input = new EditText(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setText(ScheduleCache.currentName(this));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("课表名称")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    ScheduleCache.rename(this, ScheduleCache.currentId(this), name);
                    renderSchedulePage();
                    Toast.makeText(this, "已改名「" + name + "」", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 删除当前正在看的这张课表 */
    private void deleteCurrentSchedule() {
        String id = ScheduleCache.currentId(this);
        String name = ScheduleCache.currentName(this);
        new AlertDialog.Builder(this)
                .setTitle("删除「" + name + "」？")
                .setMessage("只删除本机缓存（含这张表的调课记录），教务系统上的课表不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    if (!ScheduleCache.remove(this, id)) {
                        Toast.makeText(this, "至少要留一张课表", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AdjustCache.removeSched(this, id);
                    loadCurrentSchedule();
                    closeSchedSubPage();
                    Toast.makeText(this, "已删除「" + name + "」", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 打开「课程管理」页并刷新卡片墙 */
    private void openCourseManager() {
        if (scheduleData == null) {
            Toast.makeText(this, "先导入课表才能管理课程", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshCourseManager();
        showContentView(courseManagerPage);
    }

    /** 一门课在卡片墙上的聚合信息（同名课的全部节次归到一张卡片） */
    private static final class CourseInfo {
        final String name;
        int count = 0;
        String teacher = "";
        String room = "";

        CourseInfo(String name) {
            this.name = name;
        }
    }

    /** 按课名聚合当前课表 */
    private List<CourseInfo> collectCourses() {
        Map<String, CourseInfo> map = new LinkedHashMap<>();
        JSONArray cs = scheduleData == null ? null : scheduleData.optJSONArray("courses");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null) continue;
                String n = c.optString("name", "").trim();
                if (n.isEmpty()) continue;
                CourseInfo info = map.get(n);
                if (info == null) {
                    info = new CourseInfo(n);
                    map.put(n, info);
                }
                info.count++;
                String t = c.optString("teacher", "").trim();
                String r = c.optString("position", "").trim();
                if (!t.isEmpty() && info.teacher.isEmpty()) info.teacher = t;
                if (!r.isEmpty() && info.room.isEmpty()) info.room = r;
            }
        }
        return new ArrayList<>(map.values());
    }

    /** 重建课程卡片墙（编辑 / 删除 / 清空 / 添加之后都调它） */
    private void refreshCourseManager() {
        if (courseManagerPage == null) return;
        LinearLayout grid = courseManagerPage.findViewById(R.id.cmGrid);
        grid.removeAllViews();

        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        JSONArray courses = scheduleData == null ? null : scheduleData.optJSONArray("courses");
        if (courses != null) {
            for (int i = 0; i < courses.length(); i++) {
                JSONObject c = courses.optJSONObject(i);
                if (c == null) continue;
                String name = c.optString("name", "").trim();
                if (name.isEmpty()) continue;
                List<JSONObject> list = groups.get(name);
                if (list == null) {
                    list = new ArrayList<>();
                    groups.put(name, list);
                }
                list.add(c);
            }
        }
        courseManagerPage.findViewById(R.id.cmEmpty)
                .setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);

        float dp = getResources().getDisplayMetrics().density;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Map.Entry<String, List<JSONObject>> entry : groups.entrySet()) {
            final String courseName = entry.getKey();
            final List<JSONObject> instances = entry.getValue();
            View group = inflater.inflate(R.layout.item_course_group, grid, false);

            ((TextView) group.findViewById(R.id.courseGroupName)).setText(courseName);
            ((TextView) group.findViewById(R.id.courseGroupMeta))
                    .setText(instances.size() + " 个时间段");

            GradientDrawable colorBar = new GradientDrawable();
            colorBar.setColor(COURSE_PASTEL[courseColorIdx(courseName)]);
            colorBar.setCornerRadius(4 * dp);
            group.findViewById(R.id.courseGroupColor).setBackground(colorBar);

            LinearLayout segments = group.findViewById(R.id.courseGroupSegments);
            for (final JSONObject instance : instances) {
                View row = inflater.inflate(R.layout.item_course_instance, segments, false);
                int day = clamp(instance.optInt("day", 1), 1, 7);
                int st = instance.optInt("startSection", 1);
                int en = Math.max(st, instance.optInt("endSection", st));
                ((TextView) row.findViewById(R.id.courseInstanceWeeks))
                        .setText(weeksText(instance.optJSONArray("weeks")));
                ((TextView) row.findViewById(R.id.courseInstanceTime))
                        .setText("周" + WD_CN[day - 1] + "　第 " + st + "-" + en + " 节");
                String teacher = nz(instance.optString("teacher", ""));
                String room = nz(instance.optString("position", ""));
                ((TextView) row.findViewById(R.id.courseInstanceMeta))
                        .setText(teacher + " · " + room);

                row.setOnClickListener(v -> showEditCourseDialog(courseName, instance));
                row.findViewById(R.id.courseInstanceDelete)
                        .setOnClickListener(v -> confirmDeleteCourseInstance(instance));
                segments.addView(row);
            }

            group.findViewById(R.id.courseGroupAdd).setOnClickListener(v -> {
                String teacher = instances.isEmpty() ? "" : instances.get(0).optString("teacher", "");
                String room = instances.isEmpty() ? "" : instances.get(0).optString("position", "");
                addCourseManually(courseName, teacher, room);
            });
            group.findViewById(R.id.courseGroupHeader).setOnLongClickListener(v -> {
                CourseInfo info = new CourseInfo(courseName);
                info.count = instances.size();
                confirmDeleteCourse(info);
                return true;
            });
            grid.addView(group);
        }
    }

    private void confirmDeleteCourseInstance(final JSONObject target) {
        if (target == null || scheduleData == null) return;
        new AlertDialog.Builder(this)
                .setTitle("删除这个时间段？")
                .setMessage("只会删除当前这一条排课，不影响同名课程的其他时间段。")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        JSONArray courses = scheduleData.optJSONArray("courses");
                        if (courses == null) return;
                        for (int i = 0; i < courses.length(); i++) {
                            if (courses.optJSONObject(i) == target) {
                                courses.remove(i);
                                break;
                            }
                        }
                        AdjustCache.removeCourse(this, ScheduleCache.currentId(this),
                                courseKey(target));
                        persistSchedule();
                        refreshCourseManager();
                        renderSchedule();
                        Toast.makeText(this, "已删除该时间段", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "删除失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface WeeksPicked {
        void onPicked(JSONArray weeks);
    }

    private static JSONArray copyWeeks(JSONArray source) {
        JSONArray out = new JSONArray();
        if (source == null) return out;
        for (int i = 0; i < source.length(); i++) {
            int w = source.optInt(i, 0);
            if (w > 0) out.put(w);
        }
        return out;
    }

    /**
     * 多周网格选择器。空数组统一代表「全周」，与原有课程数据结构保持兼容。
     */
    private void showWeekPicker(JSONArray initial, int maxWeek, WeeksPicked callback) {
        int max = Math.max(1, Math.min(Math.max(maxWeek, 20), 30));
        boolean[] selected = new boolean[max + 1];
        boolean initialAll = initial == null || initial.length() == 0;
        if (initialAll) {
            for (int i = 1; i <= max; i++) selected[i] = true;
        } else {
            for (int i = 0; i < initial.length(); i++) {
                int w = initial.optInt(i, 0);
                if (w >= 1 && w <= max) selected[w] = true;
            }
        }

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_week_picker, null);
        LinearLayout grid = content.findViewById(R.id.weekGrid);
        TextView modeAll = content.findViewById(R.id.weekModeAll);
        TextView modeOdd = content.findViewById(R.id.weekModeOdd);
        TextView modeEven = content.findViewById(R.id.weekModeEven);
        final Runnable[] refresh = new Runnable[1];

        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(42 * density);
        int gap = Math.round(5 * density);
        int cols = 6;
        List<TextView> cells = new ArrayList<>();

        for (int start = 1; start <= max; start += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = gap;
            row.setLayoutParams(rowLp);

            for (int w = start; w < start + cols && w <= max; w++) {
                final int week = w;
                TextView cell = new TextView(this);
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(size, size);
                cellLp.leftMargin = gap;
                cellLp.rightMargin = gap;
                cell.setLayoutParams(cellLp);
                cell.setGravity(Gravity.CENTER);
                cell.setText(String.valueOf(w));
                cell.setTextSize(14f);
                cell.setTypeface(null, android.graphics.Typeface.BOLD);
                cell.setOnClickListener(v -> {
                    selected[week] = !selected[week];
                    refresh[0].run();
                });
                cells.add(cell);
                row.addView(cell);
            }
            grid.addView(row);
        }

        refresh[0] = () -> {
            boolean all = true;
            int idx = 0;
            for (int w = 1; w <= max; w++) {
                TextView cell = cells.get(idx++);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(selected[w] ? 0xFF2F6FED : 0xFFF1F4F8);
                cell.setBackground(bg);
                cell.setTextColor(selected[w] ? 0xFFFFFFFF : 0xFF52627A);
                if (!selected[w]) all = false;
            }
            modeAll.setTextColor(all ? 0xFF2F6FED : 0xFF667085);
        };

        modeAll.setOnClickListener(v -> {
            for (int i = 1; i <= max; i++) selected[i] = true;
            refresh[0].run();
        });
        modeOdd.setOnClickListener(v -> {
            for (int i = 1; i <= max; i++) selected[i] = i % 2 == 1;
            refresh[0].run();
        });
        modeEven.setOnClickListener(v -> {
            for (int i = 1; i <= max; i++) selected[i] = i % 2 == 0;
            refresh[0].run();
        });
        refresh[0].run();

        new AlertDialog.Builder(this)
                .setTitle("选择周次")
                .setView(content)
                .setPositiveButton("确定", (d, w) -> {
                    boolean all = true;
                    for (int i = 1; i <= max; i++) if (!selected[i]) all = false;
                    JSONArray result = new JSONArray();
                    if (!all) {
                        for (int i = 1; i <= max; i++) if (selected[i]) result.put(i);
                    }
                    callback.onPicked(result);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 编辑一门课（WakeUp 式）：先列出这门课的全部节次，选一节进入编辑。
     * 名称 / 教师 / 教室是整门课的属性；星期 / 节次 / 周次只改选中的那一节。
     */
    private void editCourse(String oldName) {
        List<JSONObject> instances = new ArrayList<>();
        JSONArray cs = scheduleData.optJSONArray("courses");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c != null && oldName.equals(c.optString("name", ""))) instances.add(c);
            }
        }
        if (instances.isEmpty()) return;

        if (instances.size() == 1) {
            showEditCourseDialog(oldName, instances.get(0));
            return;
        }
        // 多节次：让用户挑要改的那一节
        String[] items = new String[instances.size()];
        for (int i = 0; i < instances.size(); i++) {
            JSONObject c = instances.get(i);
            int day = clamp(c.optInt("day", 1), 1, 7);
            int st = c.optInt("startSection", 1), en = c.optInt("endSection", st);
            items[i] = "周" + WD_CN[day - 1] + " " + st + "-" + en + " 节 · "
                    + weeksText(c.optJSONArray("weeks"));
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要编辑的节次")
                .setItems(items, (d, w) -> showEditCourseDialog(oldName, instances.get(w)))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 编辑指定节次：名称/教师/教室作用于整门课，星期/节次/周次只改这一节 */
    private void showEditCourseDialog(final String oldName, final JSONObject target) {
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_course_edit, null);
        final EditText nameEt = form.findViewById(R.id.ceName);
        final EditText teacherEt = form.findViewById(R.id.ceTeacher);
        final EditText roomEt = form.findViewById(R.id.ceRoom);
        final Spinner daySp = form.findViewById(R.id.ceDay);
        final Spinner startSp = form.findViewById(R.id.ceStart);
        final Spinner endSp = form.findViewById(R.id.ceEnd);
        final TextView weeksValue = form.findViewById(R.id.ceWeeksValue);

        nameEt.setText(target.optString("name", oldName));
        teacherEt.setText(target.optString("teacher", ""));
        roomEt.setText(target.optString("position", ""));

        String[] days = new String[7];
        for (int i = 0; i < 7; i++) days[i] = "周" + WD_CN[i];
        String[] sections = new String[PERIODS];
        for (int i = 0; i < PERIODS; i++) sections[i] = "第 " + (i + 1) + " 节";
        daySp.setAdapter(spinnerAdapter(days));
        startSp.setAdapter(spinnerAdapter(sections));
        endSp.setAdapter(spinnerAdapter(sections));
        daySp.setSelection(clamp(target.optInt("day", 1), 1, 7) - 1);
        int st = clamp(target.optInt("startSection", 1), 1, PERIODS);
        int en = clamp(target.optInt("endSection", st), 1, PERIODS);
        startSp.setSelection(st - 1);
        endSp.setSelection(en - 1);

        int maxW = Math.max(maxWeekOfView(), 20);
        final JSONArray[] selectedWeeks = {copyWeeks(target.optJSONArray("weeks"))};
        weeksValue.setText(weeksText(selectedWeeks[0]));
        form.findViewById(R.id.ceWeeksPick).setOnClickListener(v ->
                showWeekPicker(selectedWeeks[0], maxW, weeks -> {
                    selectedWeeks[0] = weeks;
                    weeksValue.setText(weeksText(weeks));
                }));

        new AlertDialog.Builder(this)
                .setTitle("编辑课程")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String newName = nameEt.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "课程名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyInstanceEdit(oldName, target, newName,
                            teacherEt.getText().toString().trim(),
                            roomEt.getText().toString().trim(),
                            daySp.getSelectedItemPosition() + 1,
                            startSp.getSelectedItemPosition() + 1,
                            endSp.getSelectedItemPosition() + 1,
                            selectedWeeks[0]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把编辑结果写回：名称/教师/教室全课生效，时间/周次只改选中节次 */
    private void applyInstanceEdit(String oldName, JSONObject target, String newName,
                                   String teacher, String room,
                                   int day, int st, int en, JSONArray weeks) {
        try {
            String oldKey = courseKey(target);
            int oldDay = target.optInt("day", 0);
            int oldSt = target.optInt("startSection", 0);

            // 名称是整门课的属性：同步改名 + 修正调课记录的键
            if (!newName.equals(oldName)) {
                JSONArray cs = scheduleData.getJSONArray("courses");
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c != null && oldName.equals(c.optString("name", ""))) c.put("name", newName);
                }
            }
            target.put("name", newName);
            target.put("teacher", teacher);
            target.put("position", room);
            target.put("day", day);
            target.put("startSection", st);
            target.put("endSection", en);
            if (weeks == null || weeks.length() == 0) target.remove("weeks");
            else target.put("weeks", weeks);

            // 时间被改 = 用户显式调整了这节课，原调课记录已无意义
            if (day != oldDay || st != oldSt) {
                AdjustCache.removeCourse(this, ScheduleCache.currentId(this), oldKey);
            }
            if (!newName.equals(oldName)) {
                AdjustCache.rekeyRename(this, ScheduleCache.currentId(this), oldName, newName);
            }
            persistSchedule();
            refreshCourseManager();
            renderSchedule();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteCourse(final CourseInfo info) {
        new AlertDialog.Builder(this)
                .setTitle("删除「" + info.name + "」？")
                .setMessage("将从这张课表中删除这门课的全部 " + info.count + " 个节次（只改本机显示）。")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        JSONArray cs = scheduleData.getJSONArray("courses");
                        JSONArray next = new JSONArray();
                        for (int i = 0; i < cs.length(); i++) {
                            JSONObject c = cs.optJSONObject(i);
                            if (c == null) continue;
                            if (info.name.equals(c.optString("name", ""))) continue;
                            next.put(c);
                        }
                        scheduleData.put("courses", next);
                        persistSchedule();
                        AdjustCache.removeByName(this, ScheduleCache.currentId(this), info.name);
                        refreshCourseManager();
                        renderSchedule();
                        Toast.makeText(this, "已删除「" + info.name + "」", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "删除失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 清空这张课表的全部课程 */
    private void confirmClearCourses() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部课程？")
                .setMessage("这张课表上的所有课程和调课记录都会删除（开学日期设置保留）。")
                .setPositiveButton("清空", (d, w) -> {
                    try {
                        scheduleData.put("courses", new JSONArray());
                        persistSchedule();
                        AdjustCache.clear(this);
                        refreshCourseManager();
                        renderSchedule();
                        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "清空失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 手动添加一节课（考试 / 补课 / 自建课表都可以用），可选周次（全周 / 单双周 / 自定义） */
    private void addCourseManually() {
        addCourseManually("", "", "");
    }

    private void addCourseManually(String presetName, String presetTeacher, String presetRoom) {
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_course_add, null);
        final EditText nameEt = form.findViewById(R.id.caName);
        final EditText teacherEt = form.findViewById(R.id.caTeacher);
        final EditText roomEt = form.findViewById(R.id.caRoom);
        final Spinner daySp = form.findViewById(R.id.caDay);
        final Spinner startSp = form.findViewById(R.id.caStart);
        final Spinner endSp = form.findViewById(R.id.caEnd);
        final TextView weeksValue = form.findViewById(R.id.caWeeksValue);
        if (presetName != null && !presetName.isEmpty()) {
            nameEt.setText(presetName);
        }
        if (presetTeacher != null && !presetTeacher.isEmpty()) {
            teacherEt.setText(presetTeacher);
        }
        if (presetRoom != null && !presetRoom.isEmpty()) {
            roomEt.setText(presetRoom);
        }

        String[] days = new String[7];
        for (int i = 0; i < 7; i++) days[i] = "周" + WD_CN[i];
        String[] sections = new String[PERIODS];
        for (int i = 0; i < PERIODS; i++) sections[i] = "第 " + (i + 1) + " 节";
        daySp.setAdapter(spinnerAdapter(days));
        startSp.setAdapter(spinnerAdapter(sections));
        endSp.setAdapter(spinnerAdapter(sections));

        int maxW = Math.max(maxWeekOfView(), 20);
        final JSONArray[] selectedWeeks = {new JSONArray()};
        weeksValue.setText("全周");
        form.findViewById(R.id.caWeeksPick).setOnClickListener(v ->
                showWeekPicker(selectedWeeks[0], maxW, weeks -> {
                    selectedWeeks[0] = weeks;
                    weeksValue.setText(weeksText(weeks));
                }));

        new AlertDialog.Builder(this)
                .setTitle("添加课程")
                .setView(form)
                .setPositiveButton("添加", (d, w) -> {
                    String name = nameEt.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请填写课程名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int day = daySp.getSelectedItemPosition() + 1;
                    int st = startSp.getSelectedItemPosition() + 1;
                    int en = endSp.getSelectedItemPosition() + 1;
                    if (en < st) en = st;
                    try {
                        JSONObject c = new JSONObject();
                        c.put("name", name);
                        c.put("teacher", teacherEt.getText().toString().trim());
                        c.put("position", roomEt.getText().toString().trim());
                        c.put("day", day);
                        c.put("startSection", st);
                        c.put("endSection", en);
                        JSONArray ws = selectedWeeks[0];
                        if (ws.length() > 0) c.put("weeks", ws);   // 空数组 = 全周
                        JSONArray cs = scheduleData.optJSONArray("courses");
                        if (cs == null) {
                            cs = new JSONArray();
                            scheduleData.put("courses", cs);
                        }
                        cs.put(c);
                        persistSchedule();
                        refreshCourseManager();
                        renderSchedule();
                        Toast.makeText(this, "已添加「" + name + "」", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "添加失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把内存里的课表改动写回缓存（课程编辑 / 添加 / 删除之后调用） */
    private void persistSchedule() {
        if (scheduleData == null) return;
        try {
            scheduleData.put("updated", new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", Locale.CHINA).format(new java.util.Date()));
        } catch (Exception ignored) {
        }
        ScheduleCache.save(this, scheduleData.toString());
        refreshMorePage();
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
        // 课表设置 / 课程管理是「课表页上的子页」：返回先关子页，再谈 Tab 和退出
        if (schedSettingsPage != null && schedSettingsPage.getVisibility() == View.VISIBLE) {
            closeSchedSubPage();
            return;
        }
        if (courseManagerPage != null && courseManagerPage.getVisibility() == View.VISIBLE) {
            closeSchedSubPage();
            return;
        }
        // 内嵌浏览器：优先退网页，退到底才回「更多」
        if (webPage.getVisibility() == View.VISIBLE) {
            hideWebBrowser();
            return;
        }
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
