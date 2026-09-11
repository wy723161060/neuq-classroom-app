/* 东秦课表 · Android WebView 注入脚本
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

  /* 导出清洗/过滤函数：通道 2 网站导入（nqImportWeb）要复用同一套规则，
     保证「从教务抓」和「从网站读」得到的数据口径完全一致 */
  window.nqCleanName = cleanName;
  window.nqShouldKeep = shouldKeep;

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

  /* 主流程：N 天 × 7 个时段，串行 + 限速 + 过滤 + 空结果重试
     startOffset：起始日相对今天的天数（0=今天，1=明天） */
  window.nqFetch = async function (days, gapMs, startOffset) {
    var GAP_MS = (typeof gapMs === "number" && gapMs >= 0) ? gapMs : GAP;
    var OFF = (typeof startOffset === "number" && startOffset > 0) ? Math.floor(startOffset) : 0;
    try {
      var BASE = base();
      var out = [];
      var total = days * SLOTS.length;
      var done = 0;
      var throttled = false;
      var rawTotal = 0, keptTotal = 0;

      for (var i = 0; i < days; i++) {
        var d = new Date(Date.now() + (i + OFF) * 86400000);
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
  /* ==================== 课表抓取 ====================
   * 树维教务的课表页返回一个空 HTML 表格加一段 JS 脚本，课程数据以
   * new TaskActivity(...) 写在脚本里，只能从脚本文本提取，不能解析 DOM。
   * 解析逻辑移植自 shiguang_warehouse 的 resources/NEUQ/neuq.js（同一套 EAMS）。
   */

  /* 东秦作息时间表（12 节） */
  var TIME_SLOTS = [
    { n: 1, s: "08:00", e: "08:45" },
    { n: 2, s: "08:50", e: "09:35" },
    { n: 3, s: "10:05", e: "10:50" },
    { n: 4, s: "10:55", e: "11:40" },
    { n: 5, s: "14:00", e: "14:45" },
    { n: 6, s: "14:50", e: "15:35" },
    { n: 7, s: "16:05", e: "16:50" },
    { n: 8, s: "16:55", e: "17:40" },
    { n: 9, s: "18:40", e: "19:25" },
    { n: 10, s: "19:30", e: "20:15" },
    { n: 11, s: "20:25", e: "21:10" },
    { n: 12, s: "21:15", e: "22:00" }
  ];

  function weekdayOf(dateStr) {
    var p = String(dateStr).split("-");
    if (p.length !== 3) return "";
    var d = new Date(parseInt(p[0], 10), parseInt(p[1], 10) - 1, parseInt(p[2], 10));
    return isNaN(d.getTime()) ? "" : WD[d.getDay()];
  }

  /* 取 JS 字面量字符串的内容：去掉引号，变量拼接则回退为变量名 */
  function unquoteJsLiteral(token) {
    var t = String(token == null ? "" : token).trim();
    if (!t || t === "null" || t === "undefined") return "";
    if ((t.charAt(0) === '"' && t.slice(-1) === '"') ||
        (t.charAt(0) === "'" && t.slice(-1) === "'")) {
      return t.slice(1, -1);
    }
    if (t.indexOf("+") >= 0 && /^[a-zA-Z_$][\w$]*\s*\+/.test(t)) {
      return t.split("+")[0].trim();
    }
    return t;
  }

  /* 按逗号分割 JS 实参，忽略引号内的逗号与转义 */
  function splitJsArgs(argsText) {
    var args = [], cur = "", inQuote = "", escaped = false;
    for (var i = 0; i < argsText.length; i++) {
      var ch = argsText.charAt(i);
      if (escaped) { cur += ch; escaped = false; continue; }
      if (ch === "\\") { cur += ch; escaped = true; continue; }
      if (inQuote) { cur += ch; if (ch === inQuote) inQuote = ""; continue; }
      if (ch === '"' || ch === "'") { cur += ch; inQuote = ch; continue; }
      if (ch === ",") { args.push(cur.trim()); cur = ""; continue; }
      cur += ch;
    }
    if (cur.trim() || /,$/.test(argsText)) args.push(cur.trim());
    return args;
  }

  /* 周次位图：字符串下标 i 为 "1" 表示第 i 周有课 */
  function parseWeeksBitmap(bitmap) {
    var weeks = [];
    if (!bitmap || typeof bitmap !== "string") return weeks;
    for (var i = 0; i < bitmap.length; i++) {
      if (bitmap.charAt(i) === "1") weeks.push(i);
    }
    return weeks;
  }

  function normalizeWeeks(weeks) {
    var seen = {}, list = [];
    (weeks || []).forEach(function (w) {
      if (typeof w === "number" && w > 0 && !seen[w]) { seen[w] = 1; list.push(w); }
    });
    list.sort(function (a, b) { return a - b; });
    return list;
  }

  /* 去掉课名末尾的课程序号，如「高等数学(01)」→「高等数学」 */
  function cleanCourseName(name) {
    return String(name == null ? "" : name).replace(/\([\d.]+\)\s*$/, "").trim();
  }

  /* 教师是变量拼接时，从前面的 actTeachers 数组里取真实姓名 */
  function resolveActTeachers(fullText, endIdx) {
    var seg = fullText.slice(Math.max(0, endIdx - 2200), endIdx);
    var re = /var\s+actTeachers\s*=\s*\[([\s\S]*?)\]\s*;/g;
    var m, last = null;
    while ((m = re.exec(seg)) !== null) last = m[1];
    if (!last) return "";
    var names = [], nm;
    var nameRe = /name\s*:\s*(?:"([^"]*)"|'([^']*)')/g;
    while ((nm = nameRe.exec(last)) !== null) {
      var v = (nm[1] || nm[2] || "").trim();
      if (v) names.push(v);
    }
    if (!names.length) return "";
    var uniq = [];
    names.forEach(function (n) { if (uniq.indexOf(n) < 0) uniq.push(n); });
    return uniq.join(",");
  }

  /* 课名是变量拼接时，取前面定义的 courseName 字面量 */
  function resolveCourseNameVar(fullText, endIdx) {
    var seg = fullText.slice(Math.max(0, endIdx - 3000), endIdx);
    var re = /(?:var\s+)?courseName\s*=\s*(?:"([^"]*)"|'([^']*)')(?:\s*;)?/gi;
    var m, values = [];
    while ((m = re.exec(seg)) !== null) {
      var v = (m[1] || m[2] || "").trim();
      if (v) values.push(v);
    }
    return values.length ? values[values.length - 1] : "";
  }

  /* 核心：从课表页脚本里解析 new TaskActivity(...) */
  function parseTaskActivities(text) {
    var src = String(text || "");
    var courses = [];
    if (!src) return courses;

    var re = /activity\s*=\s*new\s+TaskActivity\(([\s\S]*?)\);([\s\S]*?)(?=var\s+taskId|activity\s*=\s*new|$)/g;
    var m;
    while ((m = re.exec(src)) !== null) {
      var args = splitJsArgs(m[1]);
      if (args.length < 7) continue;

      /* 教师/课名可能是变量或变量拼接（actTeachers.join(',') / courseName+"(01)"），
         也可能是裸变量名，三种情况都要回源码里找真实值 */
      var teacher = unquoteJsLiteral(args[1]);
      if (args[1] && !/^['"]/.test(String(args[1]).trim())
          && /join\s*\(|actTeachers/.test(args[1])) {
        var rt = resolveActTeachers(src, m.index);
        if (rt) teacher = rt;
      }

      var name = unquoteJsLiteral(args[3]);
      if (args[3] && !/^['"]/.test(String(args[3]).trim()) && /courseName/.test(args[3])) {
        var rn = resolveCourseNameVar(src, m.index);
        if (rn) {
          var sm = String(args[3]).match(/\+\s*["']([^)]+)["']$/);
          name = rn + (sm ? "(" + sm[1] + ")" : "");
        }
      }
      name = cleanCourseName(name);

      var position = unquoteJsLiteral(args[5])
        .replace(/"/g, "").replace(/\(.*\)/g, "").trim();
      var weeks = normalizeWeeks(parseWeeksBitmap(unquoteJsLiteral(args[6])));

      /* 后续代码块里的 index = 星期 * unitCount + 节次 */
      var idxRe = /index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)/g;
      var im, sections = [], day = -1;
      while ((im = idxRe.exec(m[2])) !== null) {
        day = parseInt(im[1], 10) + 1;
        sections.push(parseInt(im[2], 10) + 1);
      }

      if (day !== -1 && sections.length > 0) {
        sections.sort(function (a, b) { return a - b; });
        courses.push({
          name: name,
          teacher: teacher,
          position: position,
          day: day,
          startSection: sections[0],
          endSection: sections[sections.length - 1],
          weeks: weeks
        });
      }
    }
    return mergeContiguous(courses);
  }

  /* 同一门课在相邻节次连续时合并成一条 */
  function mergeContiguous(list) {
    var arr = (list || []).filter(function (c) {
      return c && c.name && typeof c.day === "number" &&
             typeof c.startSection === "number" && typeof c.endSection === "number";
    });
    arr.sort(function (a, b) {
      if (a.day !== b.day) return a.day - b.day;
      return a.startSection - b.startSection;
    });
    var out = [];
    for (var i = 0; i < arr.length; i++) {
      var item = arr[i];
      var prev = out.length ? out[out.length - 1] : null;
      var same = prev && prev.name === item.name && prev.teacher === item.teacher
        && prev.position === item.position && prev.day === item.day
        && prev.weeks.join(",") === item.weeks.join(",");
      if (same && prev.endSection + 1 === item.startSection) {
        prev.endSection = item.endSection;
      } else {
        out.push(item);
      }
    }
    return out;
  }

  async function fetchText(url, options) {
    var opt = options || {};
    opt.credentials = "same-origin";
    var r = await fetch(url, opt);
    if (!r.ok) throw new Error("HTTP " + r.status);
    return await r.text();
  }

  /* 诊断信息：抓取/解析失败时回传给 App，便于教务改版后快速定位 */
  function diagOf(url, html, extra) {
    var fromEntry = !html && !!lastEntryHtml;
    var s = String(html || lastEntryHtml || "");
    var all = s.match(/TaskActivity/g);
    var idx = s.indexOf("TaskActivity");
    var d = {
      url: String(url || "") || (fromEntry ? "[入口页] courseTableForStd.action" : ""),
      source: fromEntry ? "entryPage" : (html ? "response" : "none"),
      len: s.length,
      taskActivityCount: all ? all.length : 0,
      snippet: s.replace(/<script[\s\S]*?<\/script>/gi, " ")
               .replace(/<[^>]+>/g, " ")
               .replace(/\s+/g, " ").trim().slice(0, 200),
      jsHint: idx >= 0
        ? s.slice(Math.max(0, idx - 140), idx + 420).replace(/\s+/g, " ").trim()
        : ""
    };
    if (extra) {
      for (var k in extra) { if (Object.prototype.hasOwnProperty.call(extra, k)) d[k] = extra[k]; }
    }
    return d;
  }

  /* dataQuery 返回的是 JS 对象字面量而非严格 JSON，先试 JSON.parse 再回退 */
  function parseLooseJson(raw) {
    var t = String(raw || "").trim();
    if (!t) throw new Error("空响应");
    try { return JSON.parse(t); } catch (e) { /* 继续尝试 */ }
    try { return Function("return (" + t + ");")(); } catch (e2) {
      throw new Error("学期数据解析失败");
    }
  }

  /* 从课表入口页提取学号 ids 和学期组件 tagId
     入口页 HTML 存进 lastEntryHtml：这一步失败时往往正是会话过期，
     诊断需要它才能告诉用户「到底拿到了什么页面」 */
  var lastEntryHtml = "";
  async function detectEntry(BASE) {
    var html = await fetchText(BASE + "courseTableForStd.action?&sf_request_type=ajax", {
      method: "GET",
      headers: { "X-Requested-With": "XMLHttpRequest" }
    });
    lastEntryHtml = html;
    if (html.indexOf("请登录") >= 0 || html.indexOf("actionError") >= 0) {
      throw new Error("会话已过期，请重新登录教务");
    }
    var idsM = html.match(/bg\.form\.addInput\(form,"ids","(\d+)"\)/);
    var tagM = html.match(/id="(semesterBar\d+Semester)"/);
    if (!idsM || !tagM) {
      throw new Error("未能识别学号或学期组件，请确认已登录教务");
    }
    return { studentId: idsM[1], tagId: tagM[1] };
  }

  /* 第一步：拉取可选学期列表 */
  window.nqScheduleTerms = async function () {
    var url = "", raw = "";
    try {
      var BASE = base();
      var entry = await detectEntry(BASE);
      url = BASE + "dataQuery.action?sf_request_type=ajax";
      raw = await fetchText(url, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
        body: "tagId=" + encodeURIComponent(entry.tagId) + "&dataType=semesterCalendar"
      });
      var data = parseLooseJson(raw);
      var terms = [];
      if (data && data.semesters && typeof data.semesters === "object") {
        Object.keys(data.semesters).forEach(function (k) {
          var arr = data.semesters[k];
          if (!arr || typeof arr.length !== "number") return;
          for (var i = 0; i < arr.length; i++) {
            var s = arr[i];
            if (!s || !s.id) continue;
            terms.push({
              id: String(s.id),
              name: (String(s.schoolYear || "") + " " + String(s.name || "") + "学期").trim()
            });
          }
        });
      }
      if (!terms.length) throw new Error("学期列表为空");
      window.Android.onScheduleTerms(JSON.stringify({
        ok: true, studentId: entry.studentId, terms: terms
      }));
    } catch (e) {
      window.Android.onScheduleTerms(JSON.stringify({
        ok: false,
        error: String((e && e.message) || e),
        diag: diagOf(url, raw, { stage: "terms" })
      }));
    }
  };

  /* 第二步：按学期 id 拉取课表并解析 */
  window.nqSchedule = async function (semesterId, termName) {
    var url = "", html = "";
    try {
      var BASE = base();
      var entry = await detectEntry(BASE);
      url = BASE + "courseTableForStd!courseTable.action?sf_request_type=ajax";
      html = await fetchText(url, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
        body: [
          "ignoreHead=1",
          "setting.kind=std",
          "startWeek=",
          "semester.id=" + encodeURIComponent(String(semesterId)),
          "ids=" + encodeURIComponent(entry.studentId)
        ].join("&")
      });
      if (html.indexOf("请登录") >= 0 || html.indexOf("actionError") >= 0) {
        throw new Error("会话已过期，请重新登录教务");
      }
      var courses = parseTaskActivities(html);
      if (!courses.length) {
        var hit = html.match(/TaskActivity/g);
        throw new Error("未解析到课程（响应 " + html.length + " 字节，"
          + (hit ? "含 TaskActivity " + hit.length + " 处" : "无 TaskActivity") + "）");
      }
      window.Android.onSchedule(JSON.stringify({
        ok: true,
        semesterId: String(semesterId),
        termName: String(termName || ""),
        courses: courses,
        timeSlots: TIME_SLOTS,
        updated: new Date().toLocaleString("zh-CN")
      }));
    } catch (e) {
      window.Android.onSchedule(JSON.stringify({
        ok: false,
        error: String((e && e.message) || e),
        diag: diagOf(url, html, {
          stage: "courses",
          semesterId: String(semesterId || ""),
          termName: String(termName || "")
        })
      }));
    }
  };

  /* 课表跳空教室：只查指定日期的单个时段，一次请求即可返回 */
  window.nqFetchSlot = async function (date, tb, te, label) {
    try {
      var BASE = base();
      var rows = await query(BASE, String(date), String(tb), String(te));
      var g = {}, cnt = 0, raw = 0;
      rows.forEach(function (r) {
        raw++;
        var nm0 = trim(r["名称"]);
        var b = trim(r["教学楼"]) || inferBuilding(nm0) || "其他";
        if (!shouldKeep(b, nm0, trim(r["教室设备配置"]), trim(r["容量"]))) return;
        var nm = cleanName(b, nm0);
        if (!nm) return;
        cnt++;
        (g[b] = g[b] || []).push(nm);
      });
      window.Android.onResult(JSON.stringify({
        ok: true,
        single: true,
        days: [{
          date: String(date),
          weekday: weekdayOf(date),
          throttle: false,
          slots: [{
            label: String(label || (tb + "-" + te + "节")),
            ok: true, count: cnt, buildings: g
          }]
        }],
        throttled: false,
        rawTotal: raw,
        keptTotal: cnt,
        filtered: raw - cnt,
        updated: new Date().toLocaleString("zh-CN")
      }));
    } catch (e) {
      window.Android.onResult(JSON.stringify({
        ok: false, error: String((e && e.message) || e)
      }));
    }
  };
  true;
})();
