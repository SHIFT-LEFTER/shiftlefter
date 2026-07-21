// ShiftLefter HTML run report renderer (sl-muq9).
//
// The report is a VIEWER: the sole source of truth is the EDN data island
// (the script element with type application/edn, id run-data) — run-ctx,
// scenario envelopes, diagnostics, summary. This file parses that island and
// builds the DOM from it. Deliberately liftable into the served multi-run
// viewer (sl-zztu): nothing here assumes file:// or a single run beyond
// render().
//
// SAFETY: all island data is untrusted (scenario names, step text, error
// messages come from user feature files and arbitrary exceptions). Every
// data value reaches the page via textContent — never via HTML parsing,
// never via attribute interpolation. (No literal angle-bracket tag text in
// these comments either: this file is inlined into the document, and tests
// count tag occurrences.)

"use strict";

// ---------------------------------------------------------------------------
// EDN parser — the subset reporter envelopes can contain (invariant 3):
// nil, booleans, numbers, strings, keywords, symbols, vectors/lists, maps,
// sets, #uuid (and generic tagged literals, tag dropped). Keywords parse to
// their literal string form (":status"); maps to Map; sets to arrays.
// ---------------------------------------------------------------------------

function parseEdn(text) {
  let pos = 0;

  function err(msg) {
    throw new Error("EDN parse error at " + pos + ": " + msg);
  }

  function skipWs() {
    while (pos < text.length) {
      const c = text[pos];
      if (c === " " || c === "\t" || c === "\n" || c === "\r" || c === ",") {
        pos++;
      } else if (c === ";") {
        while (pos < text.length && text[pos] !== "\n") pos++;
      } else {
        return;
      }
    }
  }

  function readString() {
    pos++; // opening quote
    let out = "";
    while (pos < text.length) {
      const c = text[pos];
      if (c === '"') {
        pos++;
        return out;
      }
      if (c === "\\") {
        const e = text[pos + 1];
        if (e === "u") {
          out += String.fromCharCode(parseInt(text.substr(pos + 2, 4), 16));
          pos += 6;
        } else {
          const map = { n: "\n", t: "\t", r: "\r", f: "\f", b: "\b" };
          out += map[e] !== undefined ? map[e] : e;
          pos += 2;
        }
      } else {
        out += c;
        pos++;
      }
    }
    err("unterminated string");
  }

  const TOKEN_END = /[\s,()\[\]{}"]/;

  function readToken() {
    const start = pos;
    while (pos < text.length && !TOKEN_END.test(text[pos])) pos++;
    return text.slice(start, pos);
  }

  function readChar() {
    pos++; // backslash
    const tok = readToken();
    const named = { newline: "\n", space: " ", tab: "\t", return: "\r" };
    return named[tok] !== undefined ? named[tok] : tok.charAt(0);
  }

  function readColl(close) {
    pos++; // opening delimiter
    const items = [];
    for (;;) {
      skipWs();
      if (pos >= text.length) err("unterminated collection");
      if (text[pos] === close) {
        pos++;
        return items;
      }
      items.push(readValue());
    }
  }

  function readMap() {
    const items = readColl("}");
    if (items.length % 2 !== 0) err("map with odd entry count");
    const m = new Map();
    for (let i = 0; i < items.length; i += 2) m.set(items[i], items[i + 1]);
    return m;
  }

  function readDispatch() {
    pos++; // '#'
    if (text[pos] === "{") return readColl("}"); // set -> array
    if (text[pos] === "_") {
      pos++;
      readValue(); // discard
      return readValue();
    }
    readToken(); // tag symbol (uuid, inst, ...) — value stands in for it
    skipWs();
    return readValue();
  }

  function readValue() {
    skipWs();
    if (pos >= text.length) err("unexpected end of input");
    const c = text[pos];
    if (c === '"') return readString();
    if (c === "{") return readMap();
    if (c === "[") return readColl("]");
    if (c === "(") return readColl(")");
    if (c === "#") return readDispatch();
    if (c === "\\") return readChar();
    const tok = readToken();
    if (tok === "") err("unexpected character " + c);
    if (tok === "nil") return null;
    if (tok === "true") return true;
    if (tok === "false") return false;
    if (/^[+-]?(\d+\.?\d*([eE][+-]?\d+)?|\.\d+)[MN]?$/.test(tok)) {
      return parseFloat(tok);
    }
    return tok; // keyword (":status") or symbol, as its literal text
  }

  const v = readValue();
  skipWs();
  return v;
}

// ---------------------------------------------------------------------------
// Data access helpers
// ---------------------------------------------------------------------------

function k(m, key) {
  return m instanceof Map ? m.get(key) : undefined;
}

function kIn(m, keys) {
  let v = m;
  for (const key of keys) {
    v = k(v, key);
    if (v === undefined || v === null) return v;
  }
  return v;
}

function fmtMs(ms) {
  return ((ms || 0) / 1000).toFixed(3) + "s";
}

function relativePath(projectRoot, path) {
  if (projectRoot && path && path.startsWith(projectRoot)) {
    return path.slice(projectRoot.length).replace(/^[/\\]+/, "");
  }
  return path || "";
}

// ---------------------------------------------------------------------------
// Status classification
// ---------------------------------------------------------------------------

function isSynthetic(step) {
  return kIn(step, [":step", ":step/synthetic?"]) === true;
}

function isExpandedChild(step) {
  return kIn(step, [":step", ":step/macro", ":role"]) === ":expanded";
}

function firstStepOfStatus(scenario, status) {
  return (k(scenario, ":steps") || []).find((s) => k(s, ":status") === status && !isSynthetic(s));
}

// A failed scenario whose failing step threw (carries :exception-class) is
// rendered as an ERROR — the same failure/error split the JUnit reporter
// makes (sl-40to D-rules).
function scenarioStatusClass(scenario) {
  const status = k(scenario, ":status");
  if (status === ":failed") {
    const fs = firstStepOfStatus(scenario, ":failed");
    if (fs && k(k(fs, ":error"), ":exception-class")) return "st-error";
    return "st-failed";
  }
  return "st-" + (status || ":unknown").slice(1);
}

// "Red" mirrors exit-code semantics: failed always; :error (a lifecycle
// hook threw, sl-esq) always; pending only when the run was strict
// (allow-pending? false). Red scenarios render default-open.
function isRed(scenario, allowPending) {
  const status = k(scenario, ":status");
  if (status === ":failed") return true;
  if (status === ":error") return true;
  if (status === ":pending" && !allowPending) return true;
  return false;
}

const STATUS_WORDS = {
  "st-passed": "PASSED",
  "st-failed": "FAILED",
  "st-error": "ERROR",
  "st-pending": "PENDING",
  "st-skipped": "SKIPPED",
};

// ---------------------------------------------------------------------------
// Macro transcript rules — authored-primary rendering, ported from the JUnit
// reporter's D6 rules: the synthetic wrapper (:role :call) is the authored
// line; expanded children carry a "+ [key N/M]" marker.
// ---------------------------------------------------------------------------

function macroCounts(steps) {
  const counts = {};
  for (const s of steps) {
    const macro = kIn(s, [":step", ":step/macro"]);
    if (macro && k(macro, ":role") === ":call") {
      const key = k(macro, ":key") + "|" + kIn(macro, [":call-site", ":line"]);
      counts[key] = k(macro, ":step-count");
    }
  }
  return counts;
}

function childMarker(step, counts) {
  const macro = kIn(step, [":step", ":step/macro"]);
  const n = (k(macro, ":index") || 0) + 1;
  const key = k(macro, ":key") + "|" + kIn(macro, [":call-site", ":line"]);
  const m = counts[key];
  return "+ [" + k(macro, ":key") + " " + n + "/" + (m || "?") + "] ";
}

function macroAttribution(step, counts) {
  if (!isExpandedChild(step)) return "";
  const macro = kIn(step, [":step", ":step/macro"]);
  const n = (k(macro, ":index") || 0) + 1;
  const key = k(macro, ":key") + "|" + kIn(macro, [":call-site", ":line"]);
  const m = counts[key];
  return (
    "\n[via macro '" +
    k(macro, ":key") +
    "' called at feature line " +
    kIn(macro, [":call-site", ":line"]) +
    ", step " +
    n +
    "/" +
    (m || "?") +
    ": " +
    kIn(step, [":step", ":step/text"]) +
    "]"
  );
}

// ---------------------------------------------------------------------------
// DOM construction (textContent only — data is never parsed as HTML)
// ---------------------------------------------------------------------------

function el(tag, className, textContent) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (textContent !== undefined) node.textContent = textContent;
  return node;
}

function renderHeader(runCtx, summary, scenarios) {
  const header = el("header", "run-header");
  const failed = summary ? k(summary, ":exit-code") !== 0 : null;

  const h1 = el("h1", null, k(runCtx, ":project-name") || "ShiftLefter run");
  if (failed !== null) {
    const badge = el(
      "span",
      "run-status " + (failed ? "st-failed" : "st-passed"),
      failed ? "FAILED" : "PASSED",
    );
    h1.appendChild(badge);
  }
  header.appendChild(h1);

  const totalMs = scenarios.reduce((t, s) => t + (k(s, ":duration-ms") || 0), 0);
  const meta = [
    k(runCtx, ":started-at"),
    fmtMs(totalMs),
    k(runCtx, ":version") ? "v" + k(runCtx, ":version") : null,
    k(runCtx, ":mode") ? k(runCtx, ":mode").slice(1) + " mode" : null,
  ]
    .filter(Boolean)
    .join(" · ");
  header.appendChild(el("p", "run-meta", meta));

  const selection = k(runCtx, ":selection");
  if (selection) {
    const selected = k(selection, ":selected");
    const filteredOut = k(selection, ":filtered-out");
    const filter = k(selection, ":filter");
    const parts = [];
    for (const which of [":include", ":exclude"]) {
      const tags = k(filter, which) || [];
      if (tags.length) {
        parts.push(which.slice(1) + " " + tags.join(", "));
      }
    }
    header.appendChild(
      el(
        "p",
        "run-selection",
        "Ran " +
          selected +
          " of " +
          (selected + filteredOut) +
          " scenarios (" +
          filteredOut +
          " filtered out by tags)" +
          (parts.length ? " — filter: " + parts.join("; ") : ""),
      ),
    );
  }

  const counts = summary ? k(summary, ":counts") : null;
  const countsList = el("ul", "run-counts");
  countsList.appendChild(el("li", "count", scenarios.length + " scenario(s)"));
  for (const status of [":passed", ":failed", ":pending", ":skipped"]) {
    const n = counts ? k(counts, status) || 0 : 0;
    countsList.appendChild(el("li", "count st-" + status.slice(1), n + " " + status.slice(1)));
  }
  // :error (hook threw, sl-esq) — only when present, so hook-less reports
  // keep the historical four-count row.
  const errorCount = counts ? k(counts, ":error") || 0 : 0;
  if (errorCount > 0) {
    countsList.appendChild(el("li", "count st-error", errorCount + " error"));
  }
  header.appendChild(countsList);

  const controls = el("div", "controls");
  const expand = el("button", null, "Expand all");
  expand.addEventListener("click", () => setAllOpen(true));
  const collapse = el("button", null, "Collapse all");
  collapse.addEventListener("click", () => setAllOpen(false));
  const label = el("label");
  const checkbox = el("input");
  checkbox.type = "checkbox";
  checkbox.addEventListener("change", () =>
    document.body.classList.toggle("failures-only", checkbox.checked),
  );
  label.appendChild(checkbox);
  label.appendChild(document.createTextNode(" Failures only"));
  controls.appendChild(expand);
  controls.appendChild(collapse);
  controls.appendChild(label);
  header.appendChild(controls);

  return header;
}

function setAllOpen(open) {
  for (const d of document.querySelectorAll("details.scenario")) {
    d.open = open;
  }
}

function renderStep(step, counts) {
  const status = k(step, ":status");
  const error = k(step, ":error");
  const statusClass =
    status === ":failed" && error && k(error, ":exception-class")
      ? "st-error"
      : "st-" + (status || ":unknown").slice(1);
  const li = el("li", "step " + statusClass);
  li.appendChild(el("span", "step-status", STATUS_WORDS[statusClass] || ""));

  const text = el("span", "step-text");
  if (isExpandedChild(step)) {
    text.appendChild(el("span", "macro-marker", childMarker(step, counts)));
  }
  text.appendChild(
    document.createTextNode(
      kIn(step, [":step", ":step/keyword"]) + " " + kIn(step, [":step", ":step/text"]),
    ),
  );
  li.appendChild(text);
  li.appendChild(el("span", "duration", fmtMs(k(step, ":duration-ms"))));

  if (error) {
    const detail = el("div", "error-detail");
    detail.appendChild(
      el(
        "div",
        "error-type",
        k(error, ":exception-class") || (k(error, ":type") || "").slice(1) || "failure",
      ),
    );
    let message = k(error, ":message") || "";
    // Dual attribution (D6): a failing expanded child names BOTH the
    // authored macro call and the child step.
    message += macroAttribution(step, counts);
    if (k(error, ":value")) message += "\nvalue: " + k(error, ":value");
    detail.appendChild(el("pre", null, message));
    li.appendChild(detail);
  }
  return li;
}

// One row per lifecycle hook that ran (sl-esq): name, phase, status,
// duration, and the ctx keys a Before contributed.
function renderHookLine(record) {
  const status = k(record, ":status"); // ":ok" | ":failed"
  const line = el("div", "hook-line" + (status === ":failed" ? " st-failed" : ""));
  line.appendChild(el("span", "hook-status", status === ":failed" ? "FAILED" : "OK"));
  line.appendChild(
    el(
      "span",
      "hook-text",
      "hook " + k(record, ":name") + " [" + String(k(record, ":phase")).slice(1) + "]",
    ),
  );
  const contributed = k(record, ":contributed") || [];
  if (contributed.length) {
    line.appendChild(el("span", "contributed", "-> " + contributed.join(", ")));
  }
  const err = k(record, ":error");
  if (err && k(err, ":message")) {
    line.appendChild(el("span", "hook-error", k(err, ":message")));
  }
  line.appendChild(el("span", "duration", fmtMs(k(record, ":duration-ms"))));
  return line;
}

function renderScenario(scenario, allowPending) {
  const statusClass = scenarioStatusClass(scenario);
  const red = isRed(scenario, allowPending);
  const details = el("details", "scenario " + statusClass + (red ? " red" : ""));
  details.open = red; // failures + errors default-open (strict pending too)

  const pickle = kIn(scenario, [":plan", ":plan/pickle"]);
  const summary = el("summary");
  summary.appendChild(el("span", "status-word " + statusClass, STATUS_WORDS[statusClass] || ""));
  summary.appendChild(el("span", "scenario-name", k(pickle, ":pickle/name") || "(unnamed)"));
  const tags = k(pickle, ":pickle/tags") || [];
  if (tags.length) {
    const tagSpan = el("span", "tags");
    for (const tag of tags) {
      tagSpan.appendChild(el("span", "tag", k(tag, ":name")));
    }
    summary.appendChild(tagSpan);
  }
  // Derived-scheduling annotation (sl-esq round-5 unification): a scenario
  // auto-serialized by a gate (costume, shared-impl, a :requires-serial
  // hook) shows a greyed @serial chip annotated with WHY — "ran as though
  // this was also true", visually distinct from authored tags. An authored
  // @serial (:tag reason) already shows its real tag chip.
  const schedule = k(scenario, ":schedule");
  if (schedule && k(schedule, ":serial?") === true) {
    const reason = k(schedule, ":reason");
    if (reason !== ":tag") {
      const reasonText = Array.isArray(reason)
        ? String(reason[0]).slice(1) + " " + reason[1]
        : String(reason).slice(1);
      const derived = el("span", "tags derived-schedule");
      derived.appendChild(el("span", "tag derived", "@serial"));
      derived.appendChild(el("span", "derived-note", "via " + reasonText));
      summary.appendChild(derived);
    }
  }
  summary.appendChild(el("span", "duration", fmtMs(k(scenario, ":duration-ms"))));
  details.appendChild(summary);

  // Scenario-level hook failure (sl-esq): the :error lives on the scenario,
  // not on any step — render its attributed detail block above the steps.
  const scenarioError = k(scenario, ":error");
  if (scenarioError) {
    const detail = el("div", "error-detail scenario-error");
    detail.appendChild(
      el("div", "error-type", (k(scenarioError, ":type") || "").slice(1) || "error"),
    );
    let message = "";
    if (k(scenarioError, ":hook")) message += "hook '" + k(scenarioError, ":hook") + "' -- ";
    message += k(scenarioError, ":message") || "";
    const reg = k(k(scenarioError, ":registration"), ":path");
    if (reg) message += "\nregistered at: " + reg;
    const tagSource = k(scenarioError, ":tag-source");
    if (tagSource && k(tagSource, ":file")) {
      message += "\ntagged at: " + k(tagSource, ":file") + ":" + k(tagSource, ":line");
    }
    detail.appendChild(el("pre", null, message));
    details.appendChild(detail);
  }

  const steps = k(scenario, ":steps") || [];
  const counts = macroCounts(steps);
  const list = el("ol", "steps");
  for (const step of steps) list.appendChild(renderStep(step, counts));
  // Hook lines (sl-esq) mirror execution order: before-phase above the
  // steps, after-phase below. Hook-less scenarios render exactly as before.
  const hookRecords = k(scenario, ":hooks") || [];
  for (const h of hookRecords.filter((r) => k(r, ":phase") === ":before")) {
    details.appendChild(renderHookLine(h));
  }
  details.appendChild(list);
  for (const h of hookRecords.filter((r) => k(r, ":phase") === ":after")) {
    details.appendChild(renderHookLine(h));
  }
  return details;
}

function renderFeatures(runCtx, scenarios) {
  const main = el("main");
  main.id = "features";
  const allowPending = k(runCtx, ":allow-pending?") === true;
  const projectRoot = k(runCtx, ":project-root");
  const groups = new Map(); // source-file -> {name, scenarios} in plan order
  for (const scenario of scenarios) {
    const pickle = kIn(scenario, [":plan", ":plan/pickle"]);
    const file = k(pickle, ":pickle/source-file") || "(unknown)";
    if (!groups.has(file)) {
      groups.set(file, {
        name: k(pickle, ":pickle/feature-name") || "(unnamed feature)",
        scenarios: [],
      });
    }
    groups.get(file).scenarios.push(scenario);
  }
  for (const [file, group] of groups) {
    const section = el("section", "feature");
    const heading = el("h2", "feature-name", "Feature: " + group.name);
    section.appendChild(heading);
    section.appendChild(el("span", "feature-path", relativePath(projectRoot, file)));
    let hasRed = false;
    for (const scenario of group.scenarios) {
      if (isRed(scenario, allowPending)) hasRed = true;
      section.appendChild(renderScenario(scenario, allowPending));
    }
    if (hasRed) section.classList.add("has-red");
    main.appendChild(section);
  }
  return main;
}

function renderDiagnostics(diagnostics) {
  const issues = k(diagnostics, ":svo-issues") || [];
  if (!issues.length) return null;
  const section = el("section", "diagnostics");
  section.appendChild(el("h2", null, "Diagnostics (" + issues.length + ")"));
  const list = el("ul");
  for (const issue of issues) {
    list.appendChild(el("li", null, k(issue, ":message") || k(issue, ":type") || "(diagnostic)"));
  }
  section.appendChild(list);
  return section;
}

function render() {
  const island = document.getElementById("run-data");
  const data = parseEdn(island.textContent);
  const runCtx = k(data, ":run-ctx") || new Map();
  const scenarios = k(data, ":scenarios") || [];
  const summary = k(data, ":summary");

  const app = document.getElementById("app");
  app.appendChild(renderHeader(runCtx, summary, scenarios));
  app.appendChild(renderFeatures(runCtx, scenarios));
  const diagnostics = renderDiagnostics(k(data, ":diagnostics"));
  if (diagnostics) app.appendChild(diagnostics);
  app.appendChild(
    el(
      "footer",
      "run-footer",
      "Generated by ShiftLefter" +
        (k(runCtx, ":version") ? " v" + k(runCtx, ":version") : "") +
        (k(runCtx, ":run-id") ? " · run " + k(runCtx, ":run-id") : ""),
    ),
  );
}

document.addEventListener("DOMContentLoaded", render);
