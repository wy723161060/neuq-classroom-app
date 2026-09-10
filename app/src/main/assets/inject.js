/* 东秦空教室速查 · Android WebView 注入脚本
 * 运行于教务系统页面上下文（同源，可读取教务数据）
 * 结果通过 window.Android.onResult(json) 回传给 App
 *
 * 重要 1：教务系统有「请不要过快点击」类风控。原 Rust 版每次查询前都会
 *   sleep(request_delay)（默认 2s）。本脚本同样限速，否则连续 POST 会被
 *   返回上一次的缓存结果 —— 表现为「所有时段数据都一样」。
 *
 * 重要 2：需要排除非自习类教室。规则移植自原项目 src/config.rs：
 *   FORBIDDEN_CONFIGS / FORBIDDEN_BUILDINGS，以及 src/processor.rs 的
 *   名称清洗（去掉楼栋前缀）。
 */
(function () {
  var SLOTS = [
    ["1", "2", "上午1-2节"],
    ["3", "4", "上午3-4节"],
    ["5", "6", "下午5-6节"],
    ["7", "8", "下午7-8节"],
    ["1", "8", "昼间1-8节"],
    ["9", "10", "晚上9-10节"],
    ["11", "12", "晚上11-12节"]
  ];
  var DEF_ENC = "77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b";

  /* ===== 过滤规则（对齐原项目） ===== */
  // 禁止的教室设备配置类型
  var FORBIDDEN_CONFIGS = {
    "体育教学场地": 1, "机房": 1, "实验室": 1, "活动教室": 1, "研讨室": 1,
    "多功能": 1, "智慧教室": 1, "不排课教室": 1, "语音室": 1
  };
  // 禁止的教学楼（整栋排除）
  var FORBIDDEN_BUILDINGS = { "大学会馆": 1, "旧实验楼": 1 };
  // 需要去掉前缀的教学楼
  var STRIP_PREFIX = {
    "工学馆": 1, "管理楼": 1, "科技楼": 1, "人文楼": 1,
    "综合实验楼": 1, "地质楼": 1, "基础楼": 1
  };

  var GAP = 1200;           // 每次查询前的等待（毫秒）
  var EMPTY_RETRY = 2;      // 空结果重试次数
  var HTTP_RETRY = 3;       // 网络/5xx 重试次数
  var WD = ["日", "一", "二", "三", "四", "五", "六"];

  function pad(n) { return String(n).padStart(2, "0"); }
  function dayStr(d) {
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
  }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function base() {
    var m = (location.pathname || "").match(/\/http\/([a-f0-9]+)\/eams/);
    var enc = m ? m[1] : DEF_ENC;
    return location.origin + "/http/" + enc + "/eams/";
  }
  function report(done, total, msg) {
    if (window.Android && window.Android.onProgress) {
      window.Android.onProgress(done, total, msg || "");
    }
  }
  function trim(s) { return (s == null ? "" : String(s)).trim(); }

  /* 名称清洗（对齐 processor.rs clean_name）：
     1) 「自主学习室A科技楼6001-1A」→「6001-1A (自习室)」
     2) 已知楼栋前缀一律去掉：「工学馆410」→「410」
     3) 楼栋字段为空时，尝试从名称里识别楼栋并剥掉前缀（如「科技楼1028」「综合楼708」） */
  var KNOWN_BUILDINGS = ["工学馆", "综合实验楼", "综合楼", "基础楼", "地质楼",
                         "管理楼", "科技楼", "人文楼"];
  function cleanName(building, name) {
    var n = trim(name);
    if (!n) return "";
    // 自主学习室：形如 自主学习室[A-Z]科技楼XXXX
    var m = n.match(/^自主学习室([A-Z])科技楼(.+)$/);
    if (m) return trim(m[2]) + " (自习室)";
    // 去掉任意已知楼栋前缀（含「综合楼」这类简称）
    for (var i = 0; i < KNOWN_BUILDINGS.length; i++) {
      var b = KNOWN_BUILDINGS[i];
      if (n.indexOf(b) === 0) {
        n = n.substring(b.length).trim();
        break;
      }
    }
    // 重复前缀（如「科技楼科技楼1028」）
    for (var j = 0; j < KNOWN_BUILDINGS.length; j++) {
      var b2 = KNOWN_BUILDINGS[j];
      if (n.indexOf(b2) === 0) {
        n = n.substring(b2.length).trim();
        break;
      }
    }
    return n;
  }

  /* 从名称推断真实楼栋（楼栋字段为空时用） */
  function inferBuilding(name) {
    var n = trim(name);
    for (var i = 0; i < KNOWN_BUILDINGS.length; i++) {
      if (n.indexOf(KNOWN_BUILDINGS[i]) === 0) {
        // 「综合楼」归入「综合实验楼」
        return KNOWN_BUILDINGS[i] === "综合楼" ? "综合实验楼" : KNOWN_BUILDINGS[i];
      }
    }
    return "";
  }

  /* 判断名称是否为合法房间号：数字+可选字母/后缀，或自习室格式 */
  function isRoomNumber(name) {
    var n = trim(name);
    if (!n) return false;
    if (n.indexOf("(自习室)") >= 0) return true;
    // 允许「（录播）6050」这类带说明前缀的房间号
    var cleaned = n.replace(/^（[^）]*）/, "").replace(/^\([^)]*\)/, "").trim();
    return /^\d+[A-Za-z]?(-[A-Za-z0-9-]+)?$/.test(cleaned);
  }

  /* 判断该教室是否应保留 */
  function shouldKeep(building, name, config, capacity) {
    var b = trim(building), c = trim(config);

    // Rule 1/2：楼栋和设备配置都为空 → 丢弃
    if (!b && !c) return false;
    // Rule 2：楼栋非空但设备配置为空 → 丢弃（数据不完整）
    if (!c && b) return false;
    // Rule 3：容量为 "0" → 丢弃（"0" 通常是无效占位；空则放过）
    if (trim(capacity) === "0") return false;
    // Rule 5：禁止的设备配置类型
    if (FORBIDDEN_CONFIGS[c]) return false;
    // Rule 6：禁止的整栋
    if (FORBIDDEN_BUILDINGS[b]) return false;
    // Rule 7：名称清洗后必须是合法房间号，否则是说明性文字 → 丢弃
    if (!isRoomNumber(cleanName(b, name))) return false;

    return true;
  }

  /* 单次查询：POST free!search.action，解析 table.gridtable */
  async function query(BASE, date, tb, te) {
    var body = new URLSearchParams();
    body.set("classroom.building.id", "");
    body.set("cycleTime.dateBegin", date);
    body.set("cycleTime.dateEnd", date);
    body.set("timeBegin", tb);
    body.set("timeEnd", te);
    body.set("pageSize", "1000");
    body.set("classroom.type.id", "");
    body.set("classroom.campus.id", "");
    body.set("seats", "");
    body.set("classroom.name", "");
    body.set("cycleTime.cycleCount", "1");
    body.set("cycleTime.cycleType", "1");
    body.set("roomApplyTimeType", "0");

    var lastErr = null;
    for (var t = 0; t < HTTP_RETRY; t++) {
      var r = await fetch(BASE + "classroom/apply/free!search.action", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-Requested-With": "XMLHttpRequest"
        },
        body: body.toString(),
        credentials: "same-origin"
      });
      if (!r.ok) {
        lastErr = new Error("HTTP " + r.status);
        await sleep(800 * (t + 1));
        continue;
      }
      var html = await r.text();
      if (!html) { lastErr = new Error("空响应"); await sleep(800); continue; }
      if (html.indexOf("actionError") >= 0 || html.indexOf("请登录") >= 0) {
        throw new Error("会话已过期，请重新登录教务");
      }
      return parse(html);
    }
    throw lastErr || new Error("查询失败");
  }

  function parse(html) {
    var doc = new DOMParser().parseFromString(html, "text/html");
    var table = doc.querySelector("table.gridtable");
    if (!table) return [];

    var heads = [];
    var thead = table.querySelector("thead");
    if (thead) {
      thead.querySelectorAll("th").forEach(function (th) {
        heads.push(th.textContent.trim());
      });
    }
    var rows = [];
    table.querySelectorAll("tbody tr").forEach(function (tr) {
      var cells = tr.querySelectorAll("td");
      if (cells.length < 2) return;
      var rec = {};
      if (heads.length >= 2) {
        heads.forEach(function (h, i) {
          if (cells[i]) rec[h] = cells[i].textContent.trim();
        });
      } else {
        rec["教学楼"] = cells[0].textContent.trim();
        rec["名称"] = cells[1].textContent.trim();
      }
      rows.push(rec);
    });
    return rows;
  }

  /* 主流程：N 天 × 7 个时段，串行 + 限速 + 过滤 + 空结果重试 */
  window.nqFetch = async function (days, gapMs) {
    var GAP_MS = (typeof gapMs === "number" && gapMs >= 0) ? gapMs : GAP;
    try {
      var BASE = base();
      var out = [];
      var total = days * SLOTS.length;
      var done = 0;
      var throttled = false;
      var rawTotal = 0, keptTotal = 0;

      for (var i = 0; i < days; i++) {
        var d = new Date(Date.now() + i * 86400000);
        var date = dayStr(d);
        var wd = WD[d.getDay()];
        var slots = [];

        for (var s = 0; s < SLOTS.length; s++) {
          var label = SLOTS[s][2];
          if (GAP_MS > 0) await sleep(GAP_MS);

          var rows = null;
          var err = null;
          for (var a = 0; a <= EMPTY_RETRY; a++) {
            try {
              rows = await query(BASE, date, SLOTS[s][0], SLOTS[s][1]);
            } catch (e) {
              err = e;
              rows = null;
            }
            if (rows && rows.length > 0) break;
            if (a < EMPTY_RETRY) await sleep(GAP_MS + 600 * (a + 1));
          }

          if (rows && rows.length > 0) {
            var g = {};
            var cnt = 0;
            rows.forEach(function (r) {
              rawTotal++;
              var b = trim(r["教学楼"]);
              var raw = trim(r["名称"]);
              // 楼栋字段为空时，尝试从名称推断
              if (!b) b = inferBuilding(raw);
              if (!b) b = "其他";
              var cfg = trim(r["教室设备配置"]);
              var cap = trim(r["容量"]);
              if (!shouldKeep(b, raw, cfg, cap)) return;
              var nm = cleanName(b, raw);
              if (!nm) return;
              keptTotal++;
              cnt++;
              (g[b] = g[b] || []).push(nm);
            });
            slots.push({ label: label, ok: true, count: cnt, buildings: g });
          } else {
            slots.push({
              label: label, ok: false, count: 0, buildings: {},
              error: err ? String(err.message || err) : "无数据"
            });
          }
          done++;
          report(done, total, date + " " + label);
        }

        /* 风控判定：全部时段条数完全一致且 >0，说明重复拿到同一份缓存 */
        var nums = [];
        slots.forEach(function (x) { if (x.ok && x.count > 0) nums.push(x.count); });
        var allSame = nums.length >= 4 && nums.every(function (n) { return n === nums[0]; });
        if (allSame) throttled = true;

        out.push({ date: date, weekday: wd, throttle: allSame, slots: slots });
      }

      window.Android.onResult(JSON.stringify({
        ok: true,
        days: out,
        throttled: throttled,
        gap: GAP_MS,
        rawTotal: rawTotal,
        keptTotal: keptTotal,
        filtered: rawTotal - keptTotal,
        updated: new Date().toLocaleString("zh-CN")
      }));
    } catch (e) {
      window.Android.onResult(JSON.stringify({
        ok: false, error: String((e && e.message) || e)
      }));
    }
  };

  /* 诊断用：只查一天一个时段，返回原始条数 / 保留条数 / 被排除明细 */
  window.nqProbe = async function (tb, te) {
    try {
      var BASE = base();
      var d = new Date();
      var rows = await query(BASE, dayStr(d), String(tb), String(te));
      var kept = 0, reasons = {};
      rows.forEach(function (r) {
        var b = trim(r["教学楼"]) || "其他";
        var cfg = trim(r["教室设备配置"]);
        var cap = trim(r["容量"]);
        if (shouldKeep(b, trim(r["名称"]), cfg, cap)) { kept++; return; }
        var why = FORBIDDEN_CONFIGS[cfg] ? ("配置:" + cfg)
                : FORBIDDEN_BUILDINGS[b] ? ("整栋:" + b)
                : (cap === "0" ? "容量0" : "数据不完整");
        reasons[why] = (reasons[why] || 0) + 1;
      });
      return JSON.stringify({
        ok: true, total: rows.length, kept: kept,
        filtered: rows.length - kept, reasons: reasons
      });
    } catch (e) {
      return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
    }
  };
  true;
})();
