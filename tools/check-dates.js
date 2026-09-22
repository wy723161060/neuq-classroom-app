#!/usr/bin/env node
/*
 * 日期逻辑校验 —— 发版前跑一遍。
 *
 *   node tools/check-dates.js
 *
 * 为什么要有这个脚本
 * ------------------
 * v3.8.3 出过一个 bug：课表页「今天」高亮整列偏了一天（周五被标成周四）。
 * 根因是星期换算手写成 `dow - Calendar.MONDAY`，漏了 +1。它藏得深是因为：
 *   · 只有周日走 `? 7` 分支恰好正确，其余 6 天全错；
 *   · 没有断言，错到什么程度全靠肉眼看界面；
 *   · 同一套换算在 MainActivity 里手写了 3 处，写法不一致。
 *
 * 这个脚本的作用是「把这类错误挡在发版之前」。它不另抄一份算法（那会和
 * 代码一起漂移、最终失去意义），而是**从 MainActivity.java 源码里逐字
 * 抽取 weekdayOf 的实现**，再对 7 天穷举断言。
 *
 * 三条校验
 *   1. weekdayOf：周一~周日 → 1..7（含周日，那是当初漏测的那天）
 *   2. 表头日期：第 N 列 == 本周一 + (N-1) 天
 *   3. 今天高亮：把系统日期当作真实日期，断言高亮列就是那天所在的星期
 *
 * 退出码 0 = 全过；非 0 = 有失败项，不要发版。
 */
"use strict";

const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const JAVA = path.join(ROOT, "app", "src", "main", "java",
  "com", "wanyuea", "neuqclassroom", "MainActivity.java");

const DAY_MS = 24 * 3600 * 1000;
const WD_CN = ["一", "二", "三", "四", "五", "六", "日"];

/* Calendar 常量（与 Java 的 java.util.Calendar 一致） */
const SUNDAY = 1, MONDAY = 2;

let total = 0, failed = 0;
const failures = [];
function ok(cond, msg) {
  total++;
  if (cond) {
    console.log("    \u2713 " + msg);
  } else {
    failed++;
    failures.push(msg);
    console.log("    \u2717 " + msg);
  }
}

/* ══════════════════════════════════════════════════════════════
   0. 从源码抽取 weekdayOf（保证测的是打进包里的那份实现）
   ══════════════════════════════════════════════════════════════ */
const src = fs.readFileSync(JAVA, "utf8");

const WD_MARK = "/** 星期几：1=周一 … 7=周日 */";
const iW = src.indexOf(WD_MARK);
if (iW < 0) {
  console.error("找不到 weekdayOf 的注释锚点，源码结构可能变了。");
  console.error("锚点应为：" + WD_MARK);
  process.exit(2);
}
const wdBody = src.slice(iW, src.indexOf("\n    }", iW) + 6);

/* 从方法体里解析出「返回表达式」，确保它真的含 +1 */
const retLine = (wdBody.match(/return\s+([^;]+);/) || [])[1] || "";
console.log("\n[0] 从源码抽取 weekdayOf 的返回表达式");
console.log("    " + retLine.trim());

/* ══════════════════════════════════════════════════════════════
   1. weekdayOf：7 天穷举
   ══════════════════════════════════════════════════════════════ */
console.log("\n[1] weekdayOf：周一~周日 → 1..7");

/* JS 的 getDay()：0=周日 … 6=周六，先映射到 Calendar 的 DAY_OF_WEEK 体系 */
function toCalDow(jsDow) { return jsDow === 0 ? SUNDAY : jsDow + 1; }

/* 逐字执行源码抽出来的表达式：把 Calendar 常量注入作用域。
   Java 里写的是 Calendar.SUNDAY，求值前剥掉 Calendar. 前缀，
   同时把 Calendar 本身也注入（万一以后改成别的写法）。 */
function weekdayOfFromSource(ms) {
  const dow = toCalDow(new Date(ms).getDay());
  const expr = retLine.replace(/Calendar\./g, "");
  /* eslint-disable no-new-func */
  return new Function("dow", "SUNDAY", "MONDAY", "Calendar", "return (" + expr + ");")(
    dow, SUNDAY, MONDAY, { SUNDAY: SUNDAY, MONDAY: MONDAY });
}

/* 构造一个「已知是周几」的时间戳：2026-09-07 是周一 */
const MONDAY_2026_09_07 = new Date(2026, 8, 7, 12, 0, 0).getTime();
const expects = [
  [0, 1, "周一"], [1, 2, "周二"], [2, 3, "周三"], [3, 4, "周四"],
  [4, 5, "周五"], [5, 6, "周六"], [6, 7, "周日"],
];
expects.forEach(function (e) {
  const ms = MONDAY_2026_09_07 + e[0] * DAY_MS;
  const got = weekdayOfFromSource(ms);
  ok(got === e[1], e[2] + " → 期望 " + e[1] + "，实际 " + got);
});

/* 若返回表达式里没有 +1，明确指出这是历史 bug 的写法 */
if (!/\+\s*1/.test(retLine)) {
  console.log("\n    ⚠ 返回表达式里看不到 +1 —— 这正是 v3.8.3 那个 bug 的写法。");
  console.log("      正确写法： (dow == Calendar.SUNDAY) ? 7 : (dow - Calendar.MONDAY + 1)");
}

/* ══════════════════════════════════════════════════════════════
   2. 表头日期：第 N 列 == 本周一 + (N-1) 天
   ══════════════════════════════════════════════════════════════ */
console.log("\n[2] 表头日期：第 N 列 == 本周一 + (N-1) 天");

function mondayOf(ms) {
  const c = new Date(ms);
  c.setHours(0, 0, 0, 0);
  const dow = c.getDay();
  c.setDate(c.getDate() + ((dow === 0) ? -6 : (1 - dow)));
  return c.getTime();
}
function mdStr(ms) { const c = new Date(ms); return (c.getMonth() + 1) + "/" + c.getDate(); }

/* 开学 8/31（周一）→ 9/11 落在第 2 周，与用户截图一致 */
const TERM = new Date(2026, 7, 31).getTime();
const NOW = new Date(2026, 8, 11, 21, 31, 0).getTime();
const week = Math.round((mondayOf(NOW) - mondayOf(TERM)) / (7.0 * DAY_MS)) + 1;
const weekMon = mondayOf(TERM) + (week - 1) * 7 * DAY_MS;

ok(week === 2, "开学 8/31 · 9/11 → 第 2 周，实际第 " + week + " 周");

const expectDates = ["9/7", "9/8", "9/9", "9/10", "9/11", "9/12", "9/13"];
for (let i = 0; i < 7; i++) {
  const got = mdStr(weekMon + i * DAY_MS);
  ok(got === expectDates[i],
     "第 " + (i + 1) + " 列（周" + WD_CN[i] + "）= " + expectDates[i] + "，实际 " + got);
}

/* ══════════════════════════════════════════════════════════════
   3. 今天高亮：高亮列 == 系统日期所在星期
   ══════════════════════════════════════════════════════════════ */
console.log("\n[3] 今天高亮：高亮列 == 系统日期所在星期");

/* 修复后的真实路径：todayDay = weekdayOf(now) */
function todayDayOf(nowMs, weekMon) {
  if (mondayOf(nowMs) !== weekMon) return -1;
  return weekdayOfFromSource(nowMs);
}

/* 用 2026-09-11（周五）验证 —— 用户截图那天 */
const td = todayDayOf(NOW, weekMon);
ok(td === 5, "9/11（周五）→ todayDay = 5，实际 " + td);
ok(WD_CN[td - 1] === "五", "todayDay 指向「" + WD_CN[td - 1] + "」，应为「五」");
ok(mdStr(weekMon + (td - 1) * DAY_MS) === "9/11",
   "高亮列日期 = 9/11，实际 " + mdStr(weekMon + (td - 1) * DAY_MS));

/* 一周 7 天全过一遍：高亮列必须始终等于那天本身 */
console.log("    一周 7 天逐日核对（高亮列 == 当天）：");
for (let i = 0; i < 7; i++) {
  const day = weekMon + i * DAY_MS;
  const d = todayDayOf(day, weekMon);
  const dateOnCol = mdStr(weekMon + (d - 1) * DAY_MS);
  ok(d === i + 1 && dateOnCol === mdStr(day),
     "周" + WD_CN[i] + " " + mdStr(day) + " → 高亮列周" + WD_CN[d - 1] +
     " " + dateOnCol);
}

/* ══════════════════════════════════════════════════════════════
   4. 源码一致性：不允许再出现手写的星期算式
   ══════════════════════════════════════════════════════════════ */
console.log("\n[4] 源码一致性：不允许手写 `dow - Calendar.MONDAY` 这类算式");

const handWritten = [];
const lines = src.split("\n");
lines.forEach(function (l, i) {
  if (/dow\s*-\s*Calendar\.MONDAY/.test(l)) {
    const correct = /\+\s*1/.test(l);
    handWritten.push({ line: i + 1, text: l.trim(), correct: correct });
  }
});
if (handWritten.length === 0) {
  console.log("    （没有手写算式，全部收敛到 weekdayOf）");
} else {
  handWritten.forEach(function (h) {
    ok(h.correct,
       "第 " + h.line + " 行手写算式" + (h.correct ? "含 +1（正确）" : "缺 +1（就是那个 bug）"));
  });
}

/* ══════════════════════════════════════════════════════════════
   5. 周次语义：真实当前周与正在查看的周必须分开
   ══════════════════════════════════════════════════════════════ */
console.log("\n[5] 周次语义：当前周不随手动翻周变化");
ok(src.includes("private int actualCurrentWeek()"),
   "存在不叠加 weekOffset 的 actualCurrentWeek()");
ok(/private int currentWeek\(\)[\s\S]*?actualCurrentWeek\(\)\s*\+\s*weekOffset/.test(src),
   "currentWeek() = actualCurrentWeek() + weekOffset");
ok(/ssCurrentWeek[\s\S]{0,120}actualCurrentWeek\(\)/.test(src),
   "课表设置页的「当前周」使用 actualCurrentWeek()");

/* ══════════════════════════════════════════════════════════════ */
console.log("\n" + "=".repeat(56));
console.log("共 " + total + " 项断言，失败 " + failed + " 项");
if (failed) {
  console.log("\n失败明细：");
  failures.forEach(function (f) { console.log("  · " + f); });
  console.log("\n✗ 日期逻辑有问题，不要发版。");
  process.exit(1);
}
console.log("\n✓ 日期逻辑全部正确。");
