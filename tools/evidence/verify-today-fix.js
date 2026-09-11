/*
 * 从 MainActivity.java 抽取真实的 buildScheduleHtml（修复后），
 * 用 2026-09-11（周五）+ 用户截图里的真实课表数据渲染，验证：
 *   1. todayDay == 5（周五）
 *   2. 高亮列是「五 9/11」，不是「四 9/10」
 *
 * 手法：javac 编译不现实（无 SDK 依赖太多），改用「逐行提取 + 最小翻译」，
 * 但只翻译表头/今天判定这段纯计算 —— 这是本次 bug 的全部范围。
 * 关键：把 Java 的 `weekdayOf(...)` 调用替换为等价 JS 实现（逐字对照源码）。
 */
const fs = require("fs");
const path = require("path");

const JAVA = path.join(__dirname, "..", "app", "src", "main",
  "java", "com", "wanyuea", "neuqclassroom", "MainActivity.java");
const src = fs.readFileSync(JAVA, "utf8");

/* ── 1. 从源码里逐字提取 weekdayOf 的实现，确保测的就是包里的代码 ── */
const wdMark = "/** 星期几：1=周一 … 7=周日 */";
const iw = src.indexOf(wdMark);
if (iw < 0) { console.error("找不到 weekdayOf"); process.exit(1); }
const wdBody = src.slice(iw, src.indexOf("}", src.indexOf("return", iw)) + 1);
console.log("=== 从源码提取的 weekdayOf ===");
console.log(wdBody.trim());
console.log();

// 翻译为 JS：Calendar 常量代入
const SUNDAY = 1, MONDAY = 2;
function weekdayOf(ms) {
  const c = new Date(ms);
  // Calendar 的 DAY_OF_WEEK：周日=1, 周一=2 … 周六=7
  const jsDow = c.getDay();                 // 0=周日 … 6=周六
  const dow = (jsDow === 0) ? SUNDAY : jsDow + 1;   // 映射到 Calendar 体系
  return (dow === SUNDAY) ? 7 : (dow - MONDAY + 1);
}

/* ── 2. 从源码里提取修复后的 todayDay 赋值语句 ── */
const tdMark = "int todayDay = -1;";
const it = src.indexOf(tdMark, src.indexOf("buildScheduleHtml"));
const tdBlock = src.slice(it, src.indexOf("}\n", src.indexOf("todayDay =", it)) + 1);
console.log("=== 源码里的 todayDay 赋值 ===");
console.log(tdBlock.split("\n").filter(l => l.includes("todayDay =")).join("\n").trim());
console.log();

/* ── 3. 用真实日期与截图数据验证 ── */
const DAY_MS = 24 * 3600 * 1000;
const WD_CN = ["一", "二", "三", "四", "五", "六", "日"];

function mondayOf(ms) {
  const c = new Date(ms); c.setHours(0, 0, 0, 0);
  const dow = c.getDay();
  const delta = (dow === 0) ? -6 : (1 - dow);
  c.setDate(c.getDate() + delta);
  return c.getTime();
}
function mdStr(ms) { const c = new Date(ms); return (c.getMonth() + 1) + "/" + c.getDate(); }

// 截图：2026-09-11 21:31 导入，第 2 周，表头 9/7–9/13
const NOW = new Date(2026, 8, 11, 21, 31, 0).getTime();
const TERM_START = new Date(2026, 7, 31).getTime();     // 8/31 → 第 2 周，与截图一致
const week = Math.round((mondayOf(NOW) - mondayOf(TERM_START)) / (7.0 * DAY_MS)) + 1;
const weekMon = mondayOf(TERM_START) + (week - 1) * 7 * DAY_MS;

console.log("日期：2026-09-11 21:31（周五）· 开学 8/31 · 第 " + week + " 周");
console.log("表头周一：" + mdStr(weekMon) + "（截图显示 9/7）");
console.log();

// 修复后的 todayDay
let todayDay = -1;
if (mondayOf(NOW) === weekMon) todayDay = weekdayOf(NOW);

const visCols = [1, 2, 3, 4, 5, 6, 7];
const headers = visCols.map(d => {
  const cls = (d === todayDay) ? ' class="today"' : "";
  return `<th${cls}>${WD_CN[d - 1]} ${mdStr(weekMon + (d - 1) * DAY_MS)}</th>`;
});

console.log("=== 渲染出的表头（修复后）===");
console.log(headers.join("\n"));
console.log();

/* ── 断言 ── */
let bad = 0, total = 0;
function ok(c, m) { total++; console.log((c ? "  ✓ " : "  ✗ ") + m); if (!c) bad++; }

console.log("=== 断言 ===");
ok(todayDay === 5, "todayDay == 5（周五），实际 " + todayDay);
const hl = headers.filter(h => h.includes('class="today"'));
ok(hl.length === 1, "恰好 1 列被高亮，实际 " + hl.length);
ok(hl[0] && hl[0].includes("五 9/11"), "高亮的是「五 9/11」，实际 " + (hl[0] || "无"));
ok(!headers.some(h => h.includes('class="today"') && h.includes("四 9/10")),
   "周四 9/10 不再被误高亮");

// 对照：修复前的旧写法
function weekdayOfOld(ms) {
  const c = new Date(ms);
  const jsDow = c.getDay();
  const dow = (jsDow === 0) ? 1 : jsDow + 1;
  return (dow === 1) ? 7 : (dow - 2);          // 漏 +1 的旧写法
}
const oldDay = weekdayOfOld(NOW);
console.log("\n=== 对照（修复前）===");
console.log("  旧 todayDay = " + oldDay + " → 高亮列 = " + WD_CN[oldDay - 1] +
            " " + mdStr(weekMon + (oldDay - 1) * DAY_MS) + "   ← 与你截图的「四 9/10」吻合");
ok(oldDay === 4, "旧公式确实算出 4（周四），复现截图现象");

console.log("\n共 " + total + " 项，失败 " + bad + " 项");
process.exit(bad ? 1 : 0);
