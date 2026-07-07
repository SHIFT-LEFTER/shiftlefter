// SIEVE Toddler Shell — walking skeleton (sl-toddler-shell-adaptation-o4x).
//
// Trimmed from the LeftGlove Toddler Loop to the ONE-round-trip surface:
//   navigate -> sieve (capture + deterministic analysis) -> screenshot + overlays
//   -> classify / rename / accept / reject / flag-ambiguity -> write ONE Proposal.
//
// Dropped from LeftGlove (deferred to the post-GO full-shell build): multi-mode
// diff/resolve/reconciliation, pass1/pass2 ordering machinery, explore/Toddling
// clicks, localStorage + /save persistence, glossary export, and all direct-LLM
// auto-classification. Those subsystems live unchanged in the leftglove repo as
// the reference to import deliberately later — see decisions/sieve.md.
//
// The analysis is the deterministic Clojure provider (web.clj/analyze-web-evidence)
// reached over the existing HTTP bridge; there is NO LLM call in this UI.

// API base: configurable via ?api= (tests); defaults to same-origin so the UI is
// served by and talks to the one Clojure sieve bridge.
var _params = new URLSearchParams(window.location.search);
const API = _params.get("api") || "";

const CATEGORY_COLORS = {
  clickable: "#22c55e",
  typable: "#3b82f6",
  readable: "#eab308",
  chrome: "#6b7280",
  custom: "#a855f7",
};

const KEY_MAP = {
  c: "clickable",
  t: "typable",
  r: "readable",
  x: "chrome",
  u: "custom",
};

const DECISION_KEY_MAP = { a: "accept", j: "reject", m: "ambiguous" };

const DECISION_COLORS = { accept: "#4ade80", reject: "#f87171", ambiguous: "#fbbf24" };

// ---- State ----
let state = {
  inventory: null,
  analysisRef: null, // { id, fingerprint, schema-version } from /sieve
  screenshotUrl: null,
  screenshotDims: { w: 0, h: 0 },
  currentIndex: 0,
  pageUrl: null,
  classifications: {}, // { [idx]: category }
  names: {}, // { [idx]: { name, intent, notes } }
  decisions: {}, // { [idx]: 'accept' | 'reject' | 'ambiguous' }
  lastProposalId: null,
};

// ---- Toast ----
function showToast(msg, duration) {
  const el = document.getElementById("toast");
  el.textContent = msg;
  el.style.display = "block";
  clearTimeout(el._timer);
  el._timer = setTimeout(() => {
    el.style.display = "none";
  }, duration || 4000);
}

// ---- API calls ----
async function fetchSieve() {
  const res = await fetch(API + "/sieve", { method: "POST", signal: AbortSignal.timeout(30000) });
  if (!res.ok) throw new Error("Sieve request failed: " + res.status);
  return res.json();
}

async function fetchScreenshot() {
  const res = await fetch(API + "/screenshot", { signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new Error("Screenshot request failed: " + res.status);
  const blob = await res.blob();
  return blobToDataUrl(blob);
}

function blobToDataUrl(blob) {
  return new Promise(function (resolve, reject) {
    const reader = new FileReader();
    reader.onload = function () {
      resolve(reader.result);
    };
    reader.onerror = function () {
      reject(reader.error);
    };
    reader.readAsDataURL(blob);
  });
}

async function fetchNavigate(url) {
  const res = await fetch(API + "/navigate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url }),
    signal: AbortSignal.timeout(15000),
  });
  if (!res.ok) throw new Error("Navigate request failed: " + res.status);
  return res.json();
}

async function fetchProposal(payload) {
  const res = await fetch(API + "/proposal", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(15000),
  });
  if (!res.ok) throw new Error("Proposal request failed: " + res.status);
  return res.json();
}

// ---- Core actions ----
var _sieveInProgress = false;

async function doSieve() {
  if (_sieveInProgress) return;
  _sieveInProgress = true;
  const statusEl = document.getElementById("status-indicator");
  statusEl.textContent = "Sieving...";
  try {
    const [inventory, screenshot] = await Promise.all([fetchSieve(), fetchScreenshot()]);

    // Fresh observation — one round-trip, no diff against prior state.
    state.inventory = inventory;
    state.analysisRef = (inventory.sieve && inventory.sieve["analysis-result"]) || null;
    state.screenshotUrl = screenshot;
    state.pageUrl = inventory.url?.raw || inventory.url || null;
    state.currentIndex = 0;
    state.classifications = {};
    state.names = {};
    state.decisions = {};

    await renderScreenshot();
    renderOverlay();
    fillRenameInputs();
    renderPanel();
    renderMetadata();

    statusEl.textContent = (inventory.elements?.length || 0) + " elements";
  } catch (e) {
    statusEl.textContent = "Error";
    showToast("Failed to sieve: " + e.message, 6000);
  } finally {
    _sieveInProgress = false;
  }
}

async function doNavigate() {
  if (_sieveInProgress) return;
  let url = document.getElementById("url-input").value.trim();
  if (!url) return;
  if (!/^https?:\/\//i.test(url)) url = "https://" + url;
  document.getElementById("url-input").value = url;

  const statusEl = document.getElementById("status-indicator");
  statusEl.textContent = "Navigating...";
  try {
    await fetchNavigate(url);
  } catch (e) {
    statusEl.textContent = "Error";
    showToast("Failed to navigate: " + e.message, 6000);
    return;
  }
  await doSieve();
}

async function doWriteProposal() {
  if (!state.inventory?.elements?.length) {
    showToast("Nothing to propose — Sieve first.");
    return;
  }
  if (!state.analysisRef?.id) {
    showToast("No stored analysis to reference. Is the server running with a store root?", 6000);
    return;
  }
  captureCurrentName();

  const claims = buildClaims();
  if (!claims.length) {
    showToast("Classify, rename, or decide at least one element first.");
    return;
  }

  const statusEl = document.getElementById("status-indicator");
  statusEl.textContent = "Writing proposal...";
  try {
    const result = await fetchProposal({
      "analysis-id": state.analysisRef.id,
      "page-url": state.pageUrl,
      claims: claims,
    });
    state.lastProposalId = result["proposal-id"] || result.id || null;
    statusEl.textContent = "Proposal " + (state.lastProposalId || "written");
    showToast(
      "Wrote proposal " +
        (state.lastProposalId || "(ok)") +
        " — " +
        (result.selected || 0) +
        " selected, " +
        (result.rejected || 0) +
        " rejected, " +
        (result.unresolved || 0) +
        " unresolved.",
      6000,
    );
  } catch (e) {
    statusEl.textContent = "Error";
    showToast("Failed to write proposal: " + e.message, 6000);
  }
}

// A claim exists for any element the human touched (classified, named, or decided).
function buildClaims() {
  var idxs = {};
  Object.keys(state.classifications).forEach(function (k) {
    idxs[k] = true;
  });
  Object.keys(state.names).forEach(function (k) {
    idxs[k] = true;
  });
  Object.keys(state.decisions).forEach(function (k) {
    idxs[k] = true;
  });

  var claims = [];
  Object.keys(idxs).forEach(function (k) {
    var idx = parseInt(k, 10);
    var nm = state.names[idx] || {};
    claims.push({
      "element-index": idx,
      category: state.classifications[idx] || null,
      name: nm.name || null,
      intent: nm.intent || null,
      notes: nm.notes || null,
      decision: state.decisions[idx] || null,
    });
  });
  return claims;
}

// ---- Rendering ----
function renderScreenshot() {
  return new Promise((resolve) => {
    const container = document.getElementById("screenshot-container");
    const img = document.getElementById("screenshot-img");
    const emptyState = document.getElementById("empty-state");
    if (!state.screenshotUrl) {
      resolve();
      return;
    }

    img.onload = function () {
      // Screenshot is captured at device pixel ratio; sieve rects are CSS pixels.
      // Use viewport dims from the inventory so overlay coordinates align.
      var vp = state.inventory && state.inventory.viewport;
      const w = vp?.w || img.naturalWidth;
      const h = vp?.h || img.naturalHeight;
      state.screenshotDims = { w: w, h: h };
      img.style.width = w + "px";
      img.style.height = h + "px";
      container.style.display = "inline-block";
      emptyState.style.display = "none";
      resolve();
    };
    img.onerror = function () {
      resolve();
    };
    img.src = state.screenshotUrl;
  });
}

function renderOverlay() {
  const svg = document.getElementById("overlay-svg");
  const elements = state.inventory?.elements;
  if (!elements) return;

  const dims = state.screenshotDims;
  svg.setAttribute("width", dims.w);
  svg.setAttribute("height", dims.h);
  svg.setAttribute("viewBox", "0 0 " + dims.w + " " + dims.h);

  let html = "";
  for (let i = 0; i < elements.length; i++) {
    const el = elements[i];
    const rect = el.rect;
    if (!rect) continue;

    const isCurrent = i === state.currentIndex;
    const classification = state.classifications[i];
    const decision = state.decisions[i];
    const name = state.names[i];

    let stroke, strokeWidth, strokeDash, fill;
    if (isCurrent) {
      stroke = "#22d3ee";
      strokeWidth = 3;
      strokeDash = "";
      fill = "rgba(34,211,238,0.1)";
    } else if (decision) {
      stroke = DECISION_COLORS[decision];
      strokeWidth = 2;
      strokeDash = "";
      fill = "none";
    } else if (classification) {
      stroke = CATEGORY_COLORS[classification] || "#666";
      strokeWidth = classification === "chrome" ? 1 : 2;
      strokeDash = "";
      fill = "none";
    } else {
      stroke = "#666";
      strokeWidth = 1;
      strokeDash = "4,3";
      fill = "none";
    }

    html +=
      "<rect" +
      ' x="' +
      rect.x +
      '" y="' +
      rect.y +
      '"' +
      ' width="' +
      rect.w +
      '" height="' +
      rect.h +
      '"' +
      ' fill="' +
      fill +
      '" stroke="' +
      stroke +
      '" stroke-width="' +
      strokeWidth +
      '"' +
      (strokeDash ? ' stroke-dasharray="' + strokeDash + '"' : "") +
      ' data-index="' +
      i +
      '" onclick="jumpTo(' +
      i +
      ')"/>';

    if (isCurrent) {
      html += svgLabel(rect, el.label || el.tag || "?", "#22d3ee", 12, 600);
    } else if (name && name.name) {
      var gLabel = ((name.intent ? name.intent + "." : "") + name.name).slice(0, 25);
      html += svgLabel(rect, gLabel, "#4ade80", 11, 500);
    }
  }
  svg.innerHTML = html;
}

function svgLabel(rect, text, fill, size, weight) {
  var y = rect.y - 4;
  return (
    '<text x="' +
    rect.x +
    '" y="' +
    (y > 12 ? y : rect.y + 14) +
    '"' +
    ' fill="' +
    fill +
    '" font-size="' +
    size +
    '" font-family="sans-serif"' +
    ' font-weight="' +
    weight +
    '">' +
    escapeHtml(text) +
    "</text>"
  );
}

function locatorStr(el) {
  if (!el.locators) return "";
  return Object.entries(el.locators)
    .filter(function (kv) {
      return kv[1];
    })
    .map(function (kv) {
      return kv[0] + "=" + kv[1];
    })
    .join(", ");
}

function fieldHtml(label, value, style) {
  return (
    '<div><span class="field-label">' +
    label +
    "</span> " +
    '<span class="field-value"' +
    (style ? ' style="' + style + '"' : "") +
    ">" +
    escapeHtml(value) +
    "</span></div>"
  );
}

function renderPanel() {
  const elements = state.inventory?.elements;
  if (!elements || elements.length === 0) return;

  const el = elements[state.currentIndex];
  const detail = document.getElementById("element-detail");
  const classification = state.classifications[state.currentIndex];
  const decision = state.decisions[state.currentIndex];
  const total = elements.length;
  const touchedCount = buildClaims().length;

  if (el) {
    var locs = locatorStr(el);
    var elType = el["element-type"];
    detail.innerHTML =
      fieldHtml("tag", el.tag || "—") +
      (elType ? fieldHtml("type", elType, "color:#888") : "") +
      fieldHtml("label", '"' + (el.label || "—") + '"', "color:#fbbf24") +
      fieldHtml("region", el.region || "—") +
      (locs ? fieldHtml("locators", locs) : "") +
      fieldHtml("sieve category", String(el.category || "—").replace(/^:/, "")) +
      // Minimal nested-perception readout (sl-wbn): surface the containment the
      // extractor now emits. Rich collection/widget overlays are deferred to the
      // shell rebuild (xm7); this only makes the structure visible.
      (typeof el.depth === "number"
        ? fieldHtml(
            "nesting",
            "depth " +
              el.depth +
              (el.parentIndex != null ? ", parent #" + (el.parentIndex + 1) : ", root") +
              (el.category === "structure" ? " · structure" : ""),
            "color:#a78bfa",
          )
        : "") +
      (classification
        ? fieldHtml(
            "your classification",
            classification,
            "color:" + (CATEGORY_COLORS[classification] || "#fff"),
          )
        : "") +
      (decision
        ? fieldHtml("decision", decision, "color:" + (DECISION_COLORS[decision] || "#fff"))
        : "");
  }

  document.getElementById("progress").textContent = "#" + (state.currentIndex + 1) + " / " + total;
  document.getElementById("classified-count").textContent = touchedCount + " touched";
}

// ---- Rename inputs (bound to the current element) ----
function fillRenameInputs() {
  var el = state.inventory?.elements?.[state.currentIndex];
  if (!el) return;
  var existing = state.names[state.currentIndex];
  var nameInput = document.getElementById("name-input");
  var intentInput = document.getElementById("intent-input");
  var notesInput = document.getElementById("notes-input");
  if (nameInput) nameInput.value = existing ? existing.name : proposeName(el);
  if (intentInput) intentInput.value = existing ? existing.intent : deriveIntentName(el.region);
  if (notesInput) notesInput.value = existing ? existing.notes || "" : "";
}

// Persist typed name for the current element. Only stores when a name was actually
// entered, so advancing past untouched elements never fabricates claims.
function captureCurrentName() {
  var nameInput = document.getElementById("name-input");
  if (!nameInput) return;
  var name = (nameInput.value || "").trim();
  var intent = (document.getElementById("intent-input")?.value || "").trim();
  var notes = (document.getElementById("notes-input")?.value || "").trim();
  if (name) {
    state.names[state.currentIndex] = { name: name, intent: intent, source: "human", notes: notes };
  } else {
    delete state.names[state.currentIndex];
  }
}

function proposeName(el) {
  var raw = el.locators?.testid || el.locators?.id || el.locators?.name || el.label || el.tag;
  if (!raw) return "";
  var name = raw
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return name.slice(0, 30);
}

function deriveIntentName(region) {
  if (!region) return "";
  var parts = String(region).split(">");
  var last = parts[parts.length - 1].trim();
  return last.replace(/[-_]/g, " ").replace(/\b\w/g, function (c) {
    return c.toUpperCase();
  });
}

// ---- Interaction ----
function moveTo(index) {
  const total = state.inventory?.elements?.length || 0;
  if (total === 0) return;
  captureCurrentName();
  state.currentIndex = Math.max(0, Math.min(total - 1, index));
  fillRenameInputs();
  renderOverlay();
  renderPanel();
  scrollToCurrentElement();
}

function classify(category) {
  if (!state.inventory?.elements?.length) return;
  captureCurrentName();
  state.classifications[state.currentIndex] = category;

  // Advance to next unclassified, else next in line.
  const total = state.inventory.elements.length;
  let next = state.currentIndex;
  for (let i = 0; i < total; i++) {
    const idx = (state.currentIndex + 1 + i) % total;
    if (!(idx in state.classifications)) {
      next = idx;
      break;
    }
  }
  moveTo(next);
}

function decide(decision) {
  if (!state.inventory?.elements?.length) return;
  captureCurrentName();
  state.decisions[state.currentIndex] = decision;
  renderOverlay();
  renderPanel();
}

function navigate(delta) {
  if (!state.inventory?.elements?.length) return;
  moveTo(state.currentIndex + delta);
}

function jumpTo(index) {
  if (!state.inventory?.elements?.length) return;
  if (index < 0 || index >= state.inventory.elements.length) return;
  moveTo(index);
}

function scrollToCurrentElement() {
  const el = state.inventory?.elements?.[state.currentIndex];
  if (!el?.rect) return;
  const viewport = document.getElementById("viewport");
  const rect = el.rect;
  viewport.scrollTo({
    top: Math.max(0, rect.y - viewport.clientHeight / 3),
    left: Math.max(0, rect.x - viewport.clientWidth / 3),
    behavior: "smooth",
  });
}

// ---- Metadata strip ----
function metaPillGroup(label, cssClass, items) {
  if (!items || !items.length) return "";
  var html = '<div class="meta-group"><span class="meta-label">' + label + "</span>";
  for (var i = 0; i < items.length; i++) {
    html += '<span class="meta-pill ' + cssClass + '">' + escapeHtml(String(items[i])) + "</span>";
  }
  return html + "</div>";
}

function renderMetadata() {
  const strip = document.getElementById("metadata-strip");
  const inv = state.inventory;
  if (!inv) {
    strip.innerHTML = "";
    return;
  }
  strip.innerHTML =
    metaPillGroup("Cookies", "cookies", inv.cookies) +
    metaPillGroup("Tabs", "tabs", inv.tabs != null ? [inv.tabs] : []);
}

// ---- Util ----
function escapeHtml(str) {
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

// ---- Keyboard ----
function isTextInput(elm) {
  return elm && (elm.tagName === "INPUT" || elm.tagName === "TEXTAREA");
}

document.addEventListener("keydown", function (e) {
  var active = document.activeElement;
  if (active === document.getElementById("url-input")) {
    if (e.key === "Enter") {
      e.preventDefault();
      doNavigate();
    }
    return;
  }
  // Don't hijack typing in the rename fields.
  if (isTextInput(active)) return;

  if (KEY_MAP[e.key]) {
    e.preventDefault();
    classify(KEY_MAP[e.key]);
    return;
  }
  if (DECISION_KEY_MAP[e.key]) {
    e.preventDefault();
    decide(DECISION_KEY_MAP[e.key]);
    return;
  }
  if (e.key === "ArrowLeft") {
    e.preventDefault();
    navigate(-1);
  } else if (e.key === "ArrowRight") {
    e.preventDefault();
    navigate(1);
  }
});

// ---- Wire up ----
document.getElementById("btn-sieve").addEventListener("click", doSieve);
document.getElementById("btn-navigate").addEventListener("click", doNavigate);
document.getElementById("btn-proposal").addEventListener("click", doWriteProposal);
