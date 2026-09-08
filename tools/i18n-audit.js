// v1.3.0(vc55) task 3: welcome-layer i18n audit (node; dicts are JS not strict JSON)
const fs = require("fs");
const html = fs.readFileSync("app/src/main/assets/index.html", "utf8");
const js = fs.readFileSync("app/src/main/assets/app.js", "utf8");

const start = js.indexOf("var I18N=") + "var I18N=".length;
const end = js.indexOf("},LANG=") + 1;
const blob = js.slice(start, end);
const I18N = eval("(" + blob + ")");
const tw = I18N["zh-TW"], en = I18N["en"];
console.log("dict parse OK: zh-TW=%d keys, en=%d keys", Object.keys(tw).length, Object.keys(en).length);

// every data-i18n* key in HTML must exist in both dicts
const keys = new Set();
for (const m of html.matchAll(/data-i18n(?:-t|-ph|-init)?="([^"]+)"/g)) keys.add(m[1]);
const missing = [...keys].filter(k => !(k in tw) || !(k in en));
console.log("html data-i18n keys: %d", keys.size);
if (missing.length) {
  console.log("MISSING IN DICTS:");
  for (const k of missing) console.log("  [%s%s] %s", k in tw ? "" : "zh-TW", (!(k in en) ? "/en" : ""), k);
} else {
  console.log("all html keys present in both dicts OK");
}

// welcome layer hardcoded CJK scan
const wm = html.match(/<div id="welcome"[\s\S]*?<button id="wAccept"[\s\S]*?<\/button>\s*<\/div>/);
let issues = [];
if (wm) {
  for (const line of wm[0].split("\n")) {
    const s = line.trim();
    if (/[\u4e00-\u9fff]/.test(s) && !s.includes("data-i18n")) issues.push(s);
  }
}
console.log("welcome block found: %s", !!wm);
if (issues.length) {
  console.log("HARDCODED CJK LINES (no data-i18n):");
  for (const s of issues) console.log("  " + s);
} else {
  console.log("welcome layer: no hardcoded CJK without data-i18n OK");
}

process.exit((missing.length || issues.length) ? 1 : 0);
