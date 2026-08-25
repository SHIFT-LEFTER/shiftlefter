// report.js runtime smoke (sl-t0r9) — executes every generated report page in
// jsdom and fails on an empty #app or any console/window error. Adapted from
// the Warden pass-2 Q3 harness (fuzz/p1op-pass2/Q3/smoke.js), which found the
// shape; the load-await + settle below is Warden's hard-won lesson — report.js
// renders on DOMContentLoaded, so asserting before window load false-negatives.
//
// Usage: node smoke.js <dir-of-html-files>   (exit 0 = all pages render clean)
const fs = require("fs");
const path = require("path");
const { JSDOM, VirtualConsole } = require("jsdom");

const dir = process.argv[2];
const files = fs.readdirSync(dir).filter((f) => f.endsWith(".html")).sort();

function runOne(f) {
  return new Promise((resolve) => {
    const html = fs.readFileSync(path.join(dir, f), "utf8");
    const errors = [];
    const vc = new VirtualConsole();
    vc.on("jsdomError", (e) => errors.push("jsdomError: " + (e.detail && e.detail.stack ? e.detail.stack : e.message || String(e))));
    vc.on("error", (...a) => errors.push("console.error: " + a.join(" ")));
    vc.on("warn", (...a) => errors.push("console.warn: " + a.join(" ")));
    let dom;
    try {
      dom = new JSDOM(html, { runScripts: "dangerously", virtualConsole: vc, pretendToBeVisual: true });
    } catch (e) {
      errors.push("construct threw: " + e.message);
      return resolve({ f, errors, appLen: -1, headerText: "(construct failed)" });
    }
    const w = dom.window;
    w.addEventListener("error", (ev) => errors.push("window.error: " + (ev.error && ev.error.stack ? ev.error.stack : ev.message)));
    w.addEventListener("load", () => {
      setTimeout(() => {
        const doc = w.document;
        const app = doc.querySelector("#app");
        const appLen = app ? app.innerHTML.trim().length : -1;
        const header = doc.querySelector("header, .run-header, h1");
        const headerText = header ? header.textContent.replace(/\s+/g, " ").trim().slice(0, 200) : "(no header)";
        const badges = Array.from(doc.querySelectorAll("[class*='badge'], [class*='st-']")).slice(0, 6)
          .map((b) => `${b.className}:"${b.textContent.trim().slice(0, 30)}"`);
        resolve({ f, errors, appLen, headerText, badges, childCount: app ? app.children.length : -1 });
      }, 50);
    });
  });
}

(async () => {
  let bad = 0;
  for (const f of files) {
    const r = await runOne(f);
    const ok = r.appLen > 0 && r.errors.length === 0;
    if (!ok) bad++;
    console.log(`${ok ? "OK  " : "FAIL"} ${r.f}: #app=${r.appLen} chars/${r.childCount} children, errors=${r.errors.length}`);
    console.log(`      header="${r.headerText}"`);
    if (r.badges && r.badges.length) console.log("      badges: " + r.badges.join(" | "));
    for (const e of r.errors) console.log("      " + e.split("\n").slice(0, 3).join(" // ").slice(0, 400));
  }
  console.log(`\n${files.length} files, ${bad} bad`);
  process.exit(bad ? 1 : 0);
})();
