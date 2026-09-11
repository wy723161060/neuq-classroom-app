/*
 * 复现 MainActivity.buildScheduleHtml 第 3742 行的 todayDay off-by-one。
 *
 * Java: todayDay = (dow == Calendar.SUNDAY) ? 7 : (dow - Calendar.MONDAY);
 * Calendar 常量：SUNDAY=1, MONDAY=2, TUESDAY=3, WEDNESDAY=4,
 *                THURSDAY=5, FRIDAY=6, SATURDAY=7
 * get(DAY_OF_WEEK) 返回同一套值。
 *
 * 期望：周一=1 … 周六=6, 周日=7
 */
const SUNDAY = 1, MONDAY = 2, TUESDAY = 3, WEDNESDAY = 4,
      THURSDAY = 5, FRIDAY = 6, SATURDAY = 7;

const NAME = { 1: "周日", 2: "周一", 3: "周二", 4: "周三", 5: "周四", 6: "周五", 7: "周六" };
const WANT = { 1: 7, 2: 1, 3: 2, 4: 3, 5: 4, 6: 5, 7: 6 };   // dow → 期望 todayDay

function oldWay(dow) {
  return (dow === SUNDAY) ? 7 : (dow - MONDAY);
}
function newWay(dow) {
  return (dow === SUNDAY) ? 7 : (dow - MONDAY + 1);
}

console.log("dow = Calendar 常量（已在别处出现的同一套写法：2941 行就是 +1 的正确版）\n");
console.log("今天(dow)   旧公式 todayDay   期望   正确?   新公式   正确?");
console.log("-".repeat(66));

let oldBad = 0, newBad = 0;
[SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY].forEach(dow => {
  const o = oldWay(dow), n = newWay(dow), w = WANT[dow];
  const okO = o === w, okN = n === w;
  if (!okO) oldBad++;
  if (!okN) newBad++;
  console.log(
    NAME[dow] + "(" + dow + ")".padEnd(4 - String(dow).length) +
    "    " + String(o).padEnd(16) +
    String(w).padEnd(8) +
    (okO ? "✓" : "✗").padEnd(8) +
    String(n).padEnd(9) +
    (okN ? "✓" : "✗")
  );
});

console.log("-".repeat(66));
console.log("旧公式错误 " + oldBad + " / 7 项；新公式错误 " + newBad + " / 7 项");

// 特别确认 9/11 那天（周五）
console.log("\n>>> 2026-09-11 是周五，dow=Calendar.FRIDAY=" + FRIDAY);
console.log("    旧公式 todayDay = " + oldWay(FRIDAY) + "  → 指向第 " + oldWay(FRIDAY) + " 列 = " + NAME[FRIDAY - 1] + "（错！）");
console.log("    新公式 todayDay = " + newWay(FRIDAY) + "  → 指向第 " + newWay(FRIDAY) + " 列 = 周五（对）");
