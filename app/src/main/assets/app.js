/* Carica solare — app Android: monitoraggio e comandi manuali (nessun collegamento al PC). */
"use strict";

const $ = (s, el = document) => el.querySelector(s);
const NS = "http://www.w3.org/2000/svg";
const REDUCED = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const nf0 = new Intl.NumberFormat("it-IT", { maximumFractionDigits: 0 });
const nf1 = new Intl.NumberFormat("it-IT", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const nf2 = new Intl.NumberFormat("it-IT", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
const hhmm = (d) => (d ? new Date(d).toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" }) : "");
const al = (n) => { const v = Math.round(n); return /^8/.test(String(v)) || v === 11 || v === 18 ? `all'${v}%` : `al ${v}%`; };

const state = { cfg: null, solar: null, solarAt: 0, solarError: null, car: null, carAt: 0, carError: null, amps: 10, flowSig: "",
  samples: [], fc: null, fcError: null, plan: null, lastCmdAt: 0, auto: null };

/* ------------------------------------------------------------------ ponte con la parte nativa */
const pending = new Map();
let seq = 0;
window.__native = (id, ok, json) => {
  const p = pending.get(id);
  if (!p) return;
  pending.delete(id);
  let data = null;
  try { data = json ? JSON.parse(json) : null; } catch { data = { error: "risposta non valida" }; }
  ok ? p.resolve(data) : p.reject(new Error((data && data.error) || "errore"));
};
function native(method, args = {}) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    try { window.Native.call(id, method, JSON.stringify(args)); }
    catch (e) { pending.delete(id); reject(new Error("Funzione non disponibile")); }
  });
}

/* ------------------------------------------------------------------ utilità */
function el(tag, attrs = {}, text) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) { if (k === "class") e.className = v; else if (k === "style") e.style.cssText = v; else e.setAttribute(k, v); }
  if (text != null) e.textContent = text;
  return e;
}
function svg(tag, attrs = {}) {
  const e = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}
function fmtW(w) {
  if (w == null || Number.isNaN(w)) return "—";
  const a = Math.abs(w);
  return a >= 1000 ? `${nf1.format(a / 1000)} kW` : `${nf0.format(a)} W`;
}
function nodeValue(w) {
  if (w == null) return ["—", ""];
  const a = Math.abs(w);
  return a >= 1000 ? [nf2.format(a / 1000), "kW"] : [nf0.format(a), "W"];
}
let toastTimer;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 3200);
}
function banner(msg) {
  const b = $("#banner");
  b.hidden = !msg;
  b.textContent = msg || "";
}
function clockAt(hours) {
  const t = new Date(Date.now() + hours * 3600000);
  const same = t.toDateString() === new Date().toDateString();
  return `${same ? "" : "domani "}verso le ${t.toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" })}`;
}

/* ------------------------------------------------------------------ navigazione */
function route() {
  const view = location.hash.startsWith("#settings") ? "settings" : "home";
  $("#view-home").hidden = view !== "home";
  $("#view-settings").hidden = view !== "settings";
  document.querySelectorAll(".tabbar a").forEach((a) => a.dataset.view === view ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current"));
  if (view === "settings") fillSettings();
  const target = location.hash.length > 9 && document.getElementById(location.hash.slice(1));
  if (target) setTimeout(() => target.scrollIntoView({ block: "start" }), 50); else window.scrollTo(0, 0);
}
window.addEventListener("hashchange", route);

/* ------------------------------------------------------------------ dati */
async function loadConfig() {
  state.cfg = await native("config.get");
  $("#title").textContent = state.cfg.title || "Casa";
  return state.cfg;
}
async function readSolar() {
  if (!state.cfg.solax_ready) return;
  try {
    state.solar = await native("solax.read");
    state.solarAt = Date.now();
    state.solarError = null;
    recordSample(state.solar);
  } catch (e) { state.solarError = e.message; }
  computePlan();
  render();
}
async function readCar(quiet = false) {
  if (!state.cfg.tesla_ready || !state.cfg.logged_in) return;
  const btn = $("#btn-read");
  btn.disabled = true;
  try {
    state.car = await native("tesla.state");
    state.carAt = Date.now();
    state.carError = null;
    if (state.car.request_amps) state.amps = state.car.request_amps;
    if (state.car.limit) { $("#limit").value = state.car.limit; $("#limit-val").textContent = `${state.car.limit}%`; }
  } catch (e) {
    state.carError = e.message;
    if (!quiet) toast(e.message);
  } finally { btn.disabled = false; }
  computePlan();
  render();
  loadCosts();
}
async function loadCosts() {
  try {
    const s = await native("stats");
    $("#costs").textContent = `Fleet API questo mese da questo telefono: ${s.data} letture, ${s.commands} comandi, ${s.wakes} risvegli (circa ${nf2.format(s.cost)} $).`;
  } catch { /* niente */ }
}

/* ------------------------------------------------------------------ render */
function render() {
  const cfg = state.cfg || {};
  const chip = $("#chip");
  if (!cfg.solax_ready) { chip.className = "chip bad"; chip.textContent = "Da configurare"; }
  else if (state.solarError) { chip.className = "chip bad"; chip.textContent = "Inverter non raggiungibile"; }
  else {
    const on = state.auto && state.auto.enabled;
    chip.className = on ? "chip" : "chip dry";
    chip.textContent = `${on ? "Automatico" : "Manuale"} · ${PREFS[currentPref()].label}`;
  }

  const parts = [];
  if (state.solar) parts.push(`inverter ${hhmm(state.solar.timestamp)}`);
  if (state.carAt) parts.push(`Tesla ${hhmm(state.carAt)}`);
  $("#updated").textContent = parts.length ? `Aggiornato: ${parts.join(", ")}` : "";

  const car = state.car;
  const ready = cfg.tesla_ready && cfg.logged_in;
  const hero = $("#cc-car");
  hero.classList.toggle("plugged", !!(car && car.online && car.plugged));
  hero.classList.toggle("charging", !!(car && car.charging));
  const qc = $("#q-charge");
  qc.classList.toggle("on", !!(car && car.charging));
  $("#q-charge-label").textContent = car && car.charging ? "Ferma" : "Avvia";
  ["#btn-opt", "#q-charge", "#auto-switch"].forEach((id) => ($(id).disabled = !ready));
  renderAuto();

  if (!cfg.solax_ready) banner("Vai in Impostazioni e inserisci token e numero di serie SolaX.");
  else if (state.solarError) banner(`SolaX: ${state.solarError}`);
  else banner(null);

  renderFlow();
  renderCharge();
  renderControls();
  renderForecast();
  renderEta();
}

function allocate({ pv, grid, bat, house, car }) {
  const sources = { sun: Math.max(0, pv || 0), battery: Math.max(0, -(bat || 0)), grid: Math.max(0, -(grid || 0)) };
  const sinks = { house: Math.max(0, house || 0), car: Math.max(0, car || 0), battery: Math.max(0, bat || 0), grid: Math.max(0, grid || 0) };
  const order = { sun: ["house", "car", "battery", "grid"], battery: ["house", "car"], grid: ["house", "car", "battery"] };
  const rest = { ...sinks };
  const flows = { sun: {}, battery: {}, grid: {} };
  for (const src of ["sun", "battery", "grid"]) {
    let avail = sources[src];
    for (const sink of order[src]) { const t = Math.min(avail, rest[sink]); flows[src][sink] = t; avail -= t; rest[sink] -= t; }
  }
  return { sources, sinks, flows };
}
function mixFor(alloc, sink) {
  const parts = ["sun", "battery", "grid"].map((s) => [s, alloc.flows[s][sink] || 0]);
  const tot = parts.reduce((a, p) => a + p[1], 0);
  return tot > 0 ? parts.map(([s, w]) => [s, w / tot]) : [];
}
const carPower = () => (state.car && state.car.charging ? state.car.power_w || 0 : 0);

const PATHS = {
  sun: "M200 136 V262", battery: "M118 262 H200", grid: "M282 262 H200",
  house: "M200 262 V312 Q200 330 182 330 H138 Q120 330 120 348 V388",
  car: "M200 262 V312 Q200 330 218 330 H262 Q280 330 280 348 V388",
};
const SOURCE_COLOR = { sun: "--sun-glow", battery: "--battery-glow", grid: "--grid-glow" };
function setNode(id, w, label) {
  const [v, u] = nodeValue(w);
  $(`#v-${id}`).textContent = label ? "—" : v;
  $(`#u-${id}`).textContent = label || u;
}
function renderFlow() {
  const s = state.solar, car = state.car, carW = carPower();
  if (s) {
    setNode("sun", s.pv_w); setNode("battery", s.battery_w); setNode("grid", s.grid_w);
    setNode("house", Math.max(0, s.house_load_w - carW));
    $("#p-battery span").textContent = nf1.format(s.home_soc);
  } else ["sun", "battery", "grid", "house"].forEach((id) => setNode(id, null));
  $("#p-car span").textContent = car && car.soc != null ? `${car.soc}%` : "—";
  if (!car) setNode("car", null, state.carError ? "non letta" : "—");
  else if (!car.online) setNode("car", null, "dorme");
  else if (!car.plugged) setNode("car", null, "scollegata");
  else setNode("car", carW);

  const segs = [];
  if (s) {
    const a = allocate({ pv: s.pv_w, grid: s.grid_w, bat: s.battery_w, house: Math.max(0, s.house_load_w - carW), car: carW });
    if (a.sources.sun > 20) segs.push({ id: "sun", w: a.sources.sun, dir: 1, colors: ["sun"] });
    if (a.sources.battery > 20) segs.push({ id: "battery", w: a.sources.battery, dir: 1, colors: ["battery"] });
    else if (a.sinks.battery > 20) segs.push({ id: "battery", w: a.sinks.battery, dir: -1, colors: ["battery"] });
    if (a.sources.grid > 20) segs.push({ id: "grid", w: a.sources.grid, dir: 1, colors: ["grid"] });
    else if (a.sinks.grid > 20) segs.push({ id: "grid", w: a.sinks.grid, dir: -1, colors: ["grid"] });
    for (const sink of ["house", "car"]) {
      if (a.sinks[sink] > 20) {
        const mix = mixFor(a, sink).filter(([, f]) => f > 0.08);
        const n = Math.max(a.sinks[sink] < 400 ? 1 : a.sinks[sink] < 1500 ? 2 : 3, mix.length);
        segs.push({ id: sink, w: a.sinks[sink], dir: 1, colors: spread(mix, n) });
      }
    }
    // frase
    let h = "";
    if (car && car.charging && carW > 0) {
      const sun = (Object.fromEntries(mixFor(a, "car")).sun) || 0;
      h = `La Tesla carica a ${car.amps} A, ${sun >= 0.95 ? "tutta dal sole" : sun >= 0.5 ? `per il ${Math.round(sun * 100)}% dal sole` : sun > 0.05 ? "solo in parte dal sole" : "dalla rete"}.`;
    } else if (s.grid_w > 300) h = `Stai immettendo in rete ${fmtW(s.grid_w)}.`;
    else if (s.grid_w < -300) h = `Stai prelevando dalla rete ${fmtW(-s.grid_w)}.`;
    $("#headline").textContent = h;
  }
  const sig = JSON.stringify(segs.map((g) => [g.id, g.dir, Math.round(g.w / 250), g.colors]));
  if (sig !== state.flowSig) { state.flowSig = sig; drawFlow(segs); }
}
function spread(mix, n) {
  if (!mix.length) return [];
  const base = mix.map(([s, f]) => [s, Math.floor(f * n), f * n - Math.floor(f * n)]);
  let left = n - base.reduce((a, b) => a + b[1], 0);
  base.sort((a, b) => b[2] - a[2]).forEach((b) => { if (left > 0) { b[1]++; left--; } });
  const counts = Object.fromEntries(base.map(([s, c]) => [s, c]));
  const out = [];
  while (out.length < n) for (const [s] of mix) if (counts[s] > 0) { out.push(s); counts[s]--; }
  return out;
}
function drawFlow(segs) {
  const root = $("#flow-svg");
  root.replaceChildren();
  const defs = svg("defs");
  const f = svg("filter", { id: "glow", x: "-100%", y: "-300%", width: "300%", height: "700%" });
  f.append(svg("feGaussianBlur", { stdDeviation: "2.4", result: "b" }));
  const merge = svg("feMerge");
  merge.append(svg("feMergeNode", { in: "b" }), svg("feMergeNode", { in: "b" }), svg("feMergeNode", { in: "SourceGraphic" }));
  f.append(merge); defs.append(f);
  for (const [id, d] of Object.entries(PATHS)) defs.append(svg("path", { id: `p-${id}`, d }));
  root.append(defs);
  for (const d of Object.values(PATHS)) root.append(svg("path", { d, class: "base" }));
  root.append(svg("circle", { cx: 200, cy: 262, r: 3.5, class: "hub" }));
  for (const g of segs) {
    root.append(svg("path", { d: PATHS[g.id], class: "live", stroke: css(SOURCE_COLOR[g.colors[0]] || "--line") }));
    if (REDUCED) continue;
    const dur = 2.7 - 1.4 * Math.min(1, g.w / 3500);
    g.colors.forEach((src, i) => {
      const p = svg("g", { filter: "url(#glow)" });
      p.append(svg("ellipse", { rx: 10, ry: 3.2, fill: css(SOURCE_COLOR[src]), opacity: 0.9 }), svg("ellipse", { rx: 5, ry: 1.6, fill: "#fff", opacity: 0.55 }));
      const anim = svg("animateMotion", { dur: `${dur.toFixed(2)}s`, repeatCount: "indefinite", rotate: "auto",
        begin: `${(-(i * dur) / g.colors.length).toFixed(2)}s`, keyPoints: g.dir === 1 ? "0;1" : "1;0", keyTimes: "0;1", calcMode: "linear" });
      const mp = svg("mpath");
      mp.setAttributeNS("http://www.w3.org/1999/xlink", "href", `#p-${g.id}`);
      mp.setAttribute("href", `#p-${g.id}`);
      anim.append(mp); p.append(anim); root.append(p);
    });
  }
}

function setBar(id, now, tick, planned = null) {
  const bar = $(id);
  const c = (v) => Math.max(0, Math.min(100, v ?? 0));
  bar.querySelector(".now").style.width = `${c(now)}%`;
  bar.querySelector(".plan").style.width = planned != null && planned > (now ?? 0) + 0.5 ? `${c(planned)}%` : "0";
  const tk = bar.querySelector(".tick");
  tk.hidden = tick == null;
  if (tick != null) tk.style.left = `calc(${c(tick)}% - 1px)`;
}
function renderCharge() {
  const cfg = state.cfg || {}, car = state.car, s = state.solar, plan = state.plan;
  // Tesla
  const soc = car ? car.soc : null, limit = car ? car.limit : null;
  $("#cc-car-soc").textContent = soc == null ? "—" : `${soc}%`;
  $("#cc-car-target").textContent = limit ? `di ${limit}%` : "";
  $("#cc-car-state").textContent = !cfg.tesla_ready ? "da collegare" : !cfg.logged_in ? "accesso da fare"
    : !car ? (state.carError ? "non letta" : "") : !car.online ? "Addormentata" : !car.plugged ? "Non collegata"
    : car.charging ? `${car.amps} A · ${fmtW(car.power_w)}` : "Collegata";
  let eta = "", note = "", planned = null;
  const fc = plan && plan.carKnown;
  if (!cfg.tesla_ready || !cfg.logged_in) { eta = "Non collegata all'app"; note = "Completa \"Collega la Tesla da qui\" in Impostazioni."; }
  else if (!car) { eta = "Stato non letto"; note = state.carError || "Tocca l'icona di aggiornamento in alto."; }
  else if (!car.online) { eta = "Addormentata"; note = "Leggere lo stato non la sveglia; i comandi sì."; }
  else if (soc >= limit) { eta = "Carica completata"; note = `Ha raggiunto il limite del ${limit}%.`; }
  else if (fc && plan.eta.car) {
    eta = `${limit}% ${plan.eta.carText}`;
    note = plan.carPresent ? "Con il sole previsto e la preferenza scelta." : "Se la colleghi adesso, con il sole previsto.";
  } else if (fc && plan.eta.carEnd != null) {
    eta = `Domani sera circa ${Math.round(plan.eta.carEnd)}%`;
    note = "Con il sole previsto non arriva al limite entro domani.";
  } else if (car.charging && car.power_w > 0) {
    const kwh = (limit - soc) / 100 * (cfg.car_kwh || 57.5) / 0.9;
    eta = `Piena ${clockAt(kwh / (car.power_w / 1000))}`;
    note = `Al ritmo attuale di ${fmtW(car.power_w)}.`;
  } else { eta = car.plugged ? "Collegata, ferma" : "Non collegata"; }
  if (fc && plan.eta.carEnd != null) planned = plan.eta.carEnd;
  $("#cc-car-eta").textContent = eta;
  $("#cc-car-note").textContent = note;
  setBar("#cc-car-bar", soc, limit, planned);

  // batteria di casa
  const h = s ? s.home_soc : null;
  $("#cc-home-soc").textContent = h == null ? "—" : `${nf0.format(h)}%`;
  $("#cc-home-state").textContent = !s ? "" : s.battery_w > 20 ? `+${fmtW(s.battery_w)}` : s.battery_w < -20 ? `−${fmtW(-s.battery_w)}` : "ferma";
  let heta = "", hnote = "", hplan = null;
  if (s) {
    const cap = cfg.home_kwh;
    if (h >= 99.5) heta = "Piena";
    else if (plan && cap && plan.eta.home) { heta = `Piena ${plan.eta.homeText}`; hnote = "Con il sole previsto."; }
    else if (plan && cap && plan.eta.homeEnd != null) { heta = `Domani sera circa ${Math.round(plan.eta.homeEnd)}%`; hnote = "Con il sole previsto non si riempie entro domani."; }
    else if (s.battery_w > 20 && cap) { heta = `Piena ${clockAt(((100 - h) / 100 * cap) / (s.battery_w / 1000))}`; hnote = "Al ritmo attuale."; }
    else if (s.battery_w < -20 && cap) { heta = `Scarica ${clockAt(((h - 10) / 100 * cap) / (-s.battery_w / 1000))}`; hnote = "Al consumo attuale."; }
    if (cap) hnote = `${hnote} ${nf1.format(cap * h / 100)} di ${nf1.format(cap)} kWh.`.trim();
    else hnote = "Inserisci la capacità in Impostazioni per sapere quando sarà piena.";
    if (plan && plan.eta.homeEnd != null) hplan = plan.eta.home ? 100 : plan.eta.homeEnd;
  }
  $("#cc-home-eta").textContent = heta;
  $("#cc-home-note").textContent = hnote;
  setBar("#cc-home-bar", h, null, hplan);
}
function renderControls() {
  const cfg = state.cfg || {};
  const ready = cfg.tesla_ready && cfg.logged_in;
  $("#controls").classList.toggle("busy", !ready);
  renderPrefs();
  const max = (state.car && state.car.max_amps) || cfg.max_amps || 16;
  state.amps = Math.max(1, Math.min(max, state.amps));
  $("#amps-out").firstChild.textContent = state.amps;
  $("#amps-kw").textContent = `Circa ${fmtW(state.amps * 230)} in monofase. Massimo ${max} A.`;
  const car = state.car;
  $("#amps-now").textContent = car && car.request_amps ? `ora ${car.request_amps} A` : "";
  $("#limit-now").textContent = car && car.limit ? `ora ${car.limit}%` : "";
  $("#tesla-note").textContent = !ready ? "Per i comandi serve completare la parte Tesla in Impostazioni."
    : state.auto && state.auto.enabled ? "L'automatico è acceso: al prossimo controllo può cambiare quello che imposti qui. Per una correzione che duri, spegnilo." : "";
}

/* ------------------------------------------------------------------ comandi */
async function command(btn, name, value, okText) {
  const all = document.querySelectorAll("#controls button");
  all.forEach((b) => (b.disabled = true));
  const label = btn.textContent;
  btn.textContent = "Invio…";
  try {
    const r = await native("tesla.command", { name, value });
    state.lastCmdAt = Date.now();
    toast(r.ok ? okText : `L'auto ha risposto: ${r.reason || "rifiutato"}`);
    setTimeout(() => readCar(true), 4000);
  } catch (e) { toast(e.message); }
  finally { btn.textContent = label; all.forEach((b) => (b.disabled = false)); loadCosts(); }
}
$("#btn-start").addEventListener("click", (e) => command(e.currentTarget, "start", 0, "Carica avviata"));
$("#btn-stop").addEventListener("click", (e) => command(e.currentTarget, "stop", 0, "Carica fermata"));
$("#btn-amps").addEventListener("click", (e) => command(e.currentTarget, "amps", state.amps, `Corrente impostata a ${state.amps} A`));
$("#btn-limit").addEventListener("click", (e) => command(e.currentTarget, "limit", Number($("#limit").value), `Limite impostato ${al(Number($("#limit").value))}`));
$("#amps-minus").addEventListener("click", () => { state.amps--; renderControls(); });
$("#amps-plus").addEventListener("click", () => { state.amps++; renderControls(); });
$("#limit").addEventListener("input", (e) => ($("#limit-val").textContent = `${e.target.value}%`));
$("#btn-read").addEventListener("click", () => readCar());
$("#btn-wake").addEventListener("click", async (e) => {
  const b = e.currentTarget; b.disabled = true;
  try { await native("tesla.wake"); toast("Sveglia inviata: tra 20-30 secondi leggo lo stato"); setTimeout(() => readCar(true), 25000); }
  catch (err) { toast(err.message); }
  finally { b.disabled = false; loadCosts(); }
});
$("#refresh").addEventListener("click", async (e) => {
  const b = e.currentTarget; b.classList.add("spin"); b.disabled = true;
  await Promise.all([readSolar(), readCar(), loadForecast()]);
  b.classList.remove("spin"); b.disabled = false;
});

/* ------------------------------------------------------------------ preferenza e ottimizzazione */
const VOLTS = 230, MIN_A = 5, RESERVE_W = 100, SUPPORT_W = 1000, SUPPORT_SOC = 50, LEAN_SOC = 96, LEAN_W = 300, TAPER_SOC = 95;
const PREFS = {
  casa: { label: "Casa prima", help: "La Tesla aspetta che la batteria di casa arrivi al 95%. Da lì la batteria rallenta e il sole che avanza va alla Tesla, invece che in rete." },
  equilibrio: { label: "Bilanciata", help: "Batteria di casa prima fino all'80%, poi il sole in più va alla Tesla." },
  auto: { label: "Auto prima", help: `La Tesla prende tutto il sole libero. Se la batteria di casa è sopra il ${SUPPORT_SOC}%, aiuta fino a 1 kW.` },
  max: { label: "Massima", help: "La Tesla carica alla corrente massima, anche prendendo dalla rete." },
};
function currentPref() { return (state.cfg && PREFS[state.cfg.pref]) ? state.cfg.pref : "equilibrio"; }
function renderPrefs() {
  const p = currentPref();
  document.querySelectorAll("#prefs button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.pref === p)));
  $("#pref-help").textContent = PREFS[p].help;
}
document.querySelectorAll("#prefs button").forEach((b) => b.addEventListener("click", async () => {
  if (!state.cfg) return;
  const old = state.cfg.pref;
  state.cfg.pref = b.dataset.pref; renderPrefs();
  try { await native("config.save", { pref: b.dataset.pref }); }
  catch (e) { state.cfg.pref = old; renderPrefs(); toast(e.message); return; }
  computePlan(); render();
  if (old !== b.dataset.pref) applyPrefNow();
}));
/** Una preferenza appena scelta si applica subito, senza aspettare il prossimo controllo. */
async function applyPrefNow() {
  const cfg = state.cfg || {};
  if (!(cfg.tesla_ready && cfg.logged_in)) return;
  if (state.auto && state.auto.enabled) {
    optMsg(`Applico "${PREFS[currentPref()].label}"…`);
    try { await native("auto.runNow", { force: true }); } catch (e) { optMsg(e.message, "warn"); return; }
    for (const wait of [4000, 6000, 8000]) {
      await new Promise((r) => setTimeout(r, wait));
      await loadAuto();
      const a = state.auto || {};
      if (a.last_at && Date.now() - a.last_at < 30000) { optMsg(a.last_text, "ok"); readCar(true); return; }
    }
    optMsg("Il controllo è partito: il risultato lo trovi in \"Cosa ha fatto\".");
  } else optimize();
}
async function setPref(pref) {
  const b = document.querySelector(`#prefs button[data-pref="${pref}"]`);
  if (b) b.click();
}

/** Calcolo puro: quanti ampere dare alla Tesla con questa preferenza (0 = non caricare). Stessa logica dell'app del PC. */
function planCharge(pref, solar, car, maxA) {
  if (pref === "max") return { amps: maxA, availW: null };
  if (pref === "casa" && solar.home_soc < TAPER_SOC) return { amps: 0, availW: null, homeFirst: true };
  const carW = car.charging ? (car.power_w || car.amps * VOLTS) : 0;
  const bat = solar.battery_w, soc = solar.home_soc;
  const prioritySoc = pref === "casa" ? 100 : pref === "equilibrio" ? 80 : 0;
  let contrib;
  if (soc < prioritySoc) {
    // sotto la soglia la batteria di casa deve caricare a piena potenza, anche togliendola all'auto;
    // vicino al pieno accetta meno, quindi le riservo solo quello che sta prendendo
    const batMax = Planner.batteryPower(state.samples || [], ((state.cfg && state.cfg.bat_max_kw) || 3) * 1000);
    const saturated = soc >= 95 || solar.grid_w > 200;   // se immetto in rete, la batteria prende già tutto quello che può
    const want = saturated ? Math.max(bat, 0) : Math.max(batMax, Math.max(bat, 0));
    contrib = bat - want;
  } else if (bat >= 0) contrib = bat;
  else contrib = pref === "auto" && soc >= SUPPORT_SOC ? Math.min(0, bat + SUPPORT_W) : bat;
  let availW = solar.grid_w + carW + contrib - RESERVE_W;
  // batteria di casa (quasi) piena: niente margine e fino a 300 W dalla batteria, meglio che regalarli alla rete
  const lean = soc >= LEAN_SOC;
  if (lean) availW += RESERVE_W + LEAN_W;
  const amps = Math.min(maxA, Math.floor(availW / VOLTS));
  return { amps: amps >= MIN_A ? amps : 0, availW, lean };
}

function optMsg(text, kind = "") {
  const m = $("#opt-msg");
  m.hidden = !text; m.textContent = text || ""; m.className = `opt-msg ${kind}`;
}
function distanceM(lat1, lon1, lat2, lon2) {
  const R = 6371000, r = Math.PI / 180;
  const a = Math.sin((lat2 - lat1) * r / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin((lon2 - lon1) * r / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}
/** true/false se so dove sono auto e casa, null se non lo so. */
function carAtHome() {
  const c = state.cfg || {}, car = state.car;
  if (c.home_lat == null || !car || car.lat == null) return null;
  return distanceM(c.home_lat, c.home_lon, car.lat, car.lon) <= 300;
}
let optimizing = false;
async function optimize() {
  const cfg = state.cfg || {};
  if (optimizing) return;
  if (!(cfg.tesla_ready && cfg.logged_in)) { optMsg("Prima collega la Tesla in Impostazioni.", "warn"); return; }
  const pref = currentPref();
  const btn = $("#btn-opt");
  optimizing = true; btn.disabled = true;
  const label = btn.textContent; btn.textContent = "Leggo inverter e Tesla…";
  try {
    await Promise.all([readSolar(), readCar(true)]);
    const car = state.car, solar = state.solar;
    if (state.carError) return optMsg(state.carError, "warn");
    if (!car || !car.online) return optMsg("La Tesla dorme: tocca \"Sveglia l'auto\" e riprova tra mezzo minuto.", "warn");
    if (!car.plugged) return optMsg("La Tesla non è collegata al cavo.", "warn");
    if (carAtHome() === false) return optMsg("La Tesla non è a casa: non tocco una carica fatta altrove.", "warn");
    if (car.limit && car.soc >= car.limit) return optMsg(`La Tesla è già ${al(car.soc)}, al suo limite: niente da fare.`, "ok");
    const maxA = car.max_amps || cfg.max_amps || 16;
    const homeFirst = pref === "casa" && solar && solar.home_soc < TAPER_SOC;   // decide la batteria, non serve il dato fresco
    if (pref !== "max" && !homeFirst) {
      if (!solar) return optMsg(state.solarError || "Dati dell'inverter non disponibili.", "warn");
      const age = Date.now() - solar.timestamp;
      if (age > 15 * 60000) return optMsg("I dati dell'inverter hanno più di 15 minuti: riprova più tardi.", "warn");
      if (state.lastCmdAt && solar.timestamp < state.lastCmdAt) {
        const next = new Date(Math.max(solar.timestamp + 6 * 60000, Date.now() + 60000));
        return optMsg(`L'inverter non ha ancora visto l'ultima modifica alla carica. Riprova dopo le ${hhmm(next)}, quando SolaX aggiorna i dati.`, "warn");
      }
    }
    const p = planCharge(pref, solar, car, maxA);
    btn.textContent = "Invio i comandi…";
    const done = [];
    const send = async (name, value) => {
      const r = await native("tesla.command", { name, value });
      state.lastCmdAt = Date.now();
      if (!r.ok) throw new Error(`L'auto ha risposto: ${r.reason || "rifiutato"}`);
    };
    if (p.homeFirst) {
      if (car.charging) { await send("stop", 0); done.push("fermato la carica"); }
      optMsg(`Casa prima: la batteria di casa è ${al(solar.home_soc)}, la Tesla aspetta il 95%. ` +
        (done.length ? "Ho fermato la Tesla." : "La Tesla resta ferma.") +
        (state.auto && state.auto.enabled ? " Ripartirà da sola quando la batteria arriverà al 95%." : " Quando la batteria arriverà al 95% tocca di nuovo Ottimizza, oppure accendi l'automatico che la fa ripartire da solo."), "ok");
    } else if (p.amps === 0) {
      const free = Math.max(0, p.availW);
      if (car.charging) { await send("stop", 0); done.push("fermato la carica"); }
      optMsg(`Sole libero per la Tesla: circa ${fmtW(free)}. Ne servono almeno ${fmtW(MIN_A * VOLTS)} (${MIN_A} A)` +
        (done.length ? `, quindi ho ${done.join(" e ")}.` : ", quindi non avvio la carica."), "warn");
    } else {
      if (car.request_amps !== p.amps) { await send("amps", p.amps); done.push(`impostato ${p.amps} A`); }
      if (!car.charging) { await send("start", 0); done.push("avviato la carica"); }
      const src = pref === "max" ? "anche dalla rete" : `circa ${fmtW(p.amps * VOLTS)} dal sole${p.lean ? ", arrotondando verso l'auto perché la batteria di casa è quasi piena" : ""}`;
      optMsg(done.length ? `Fatto: ho ${done.join(" e ")} (${src}).` : `Già a posto: ${p.amps} A (${src}).`, "ok");
      state.amps = p.amps;
      if (state.car) state.car.request_amps = p.amps;   // lo stato di carica lo rileggo tra 5 secondi
    }
    render();
    if (done.length) setTimeout(() => readCar(true), 5000);
  } catch (e) { optMsg(e.message, "warn"); }
  finally { optimizing = false; btn.disabled = false; btn.textContent = label; loadCosts(); }
}
$("#btn-opt").addEventListener("click", () => optimize());
$("#q-charge").addEventListener("click", (e) => {
  const on = state.car && state.car.charging;
  const b = e.currentTarget; b.disabled = true;
  command($(on ? "#btn-stop" : "#btn-start"), on ? "stop" : "start", 0, on ? "Carica fermata" : "Carica avviata")
    .finally(() => { b.disabled = false; });
});




/* ------------------------------------------------------------------ automatico in background */
async function loadAuto() {
  try { state.auto = await native("auto.status"); } catch { state.auto = null; }
  render();
}
function renderAuto() {
  const a = state.auto || {}, on = !!a.enabled;
  $("#auto-switch").checked = on;
  const sunsetOff = !on && a.off_at_sunset && a.off_at && new Date(a.off_at).toDateString() === new Date().toDateString();
  $("#auto-sub").textContent = on ? "Acceso: regola la carica anche a schermo spento. Si spegne da solo al tramonto."
    : sunsetOff ? `Spento da solo al tramonto (${hhmm(a.off_at)}). Riaccendilo quando ti serve.` : "Spento: ottimizzi tu quando vuoi";
  $("#auto-box").hidden = !on;
  if (!on) return;
  const last = a.last_at ? `Ultimo controllo ${hhmm(a.last_at)}: ` : "Primo controllo in corso…";
  const next = a.next_at && a.next_at > Date.now() ? ` · prossimo alle ${hhmm(a.next_at)}` : "";
  const p = $("#auto-last");
  p.replaceChildren(document.createTextNode(last), el("b", {}, a.last_text || ""), document.createTextNode(next));
  $("#auto-warn").hidden = a.battery_ok !== false;
  const ul = $("#auto-log");
  const items = (a.log || []).slice(-20).reverse();
  ul.replaceChildren(...(items.length ? items.map((l) => {
    const li = el("li", { class: l.kind || "" });
    li.append(el("time", {}, hhmm(l.at)), el("span", {}, l.text));
    return li;
  }) : [el("li", {}, "Ancora nessuna azione.")]));
}
async function setAuto(on) {
  const cfg = state.cfg || {};
  if (on && !(cfg.tesla_ready && cfg.logged_in)) { toast("Prima collega la Tesla in Impostazioni"); renderAuto(); return; }
  try {
    await native("auto.set", { on });
    toast(on ? "Automatico acceso: controllo ogni 5 minuti" : "Automatico spento: torni all'ottimizzazione manuale");
  } catch (e) { toast(e.message); }
  setTimeout(loadAuto, 600);
  setTimeout(loadAuto, 4000);
}
$("#auto-switch").addEventListener("change", (e) => setAuto(e.target.checked));
$("#auto-battery").addEventListener("click", () => native("auto.battery").catch((e) => toast(e.message)));
setInterval(() => { if (document.visibilityState === "visible") loadAuto(); }, 20000);

/* ------------------------------------------------------------------ storico e risparmio */
let histRange = "week";
const dkey = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const eur = new Intl.NumberFormat("it-IT", { style: "currency", currency: "EUR" });
const kwhTxt = (v) => `${v >= 10 ? nf0.format(v) : nf1.format(v)} kWh`;
async function loadHistory() {
  try { state.history = await native("history"); } catch { state.history = null; }
  renderHistory();
}
/** Giorni del periodo scelto, dal più vecchio a oggi. */
function histDays(range) {
  const out = [], today = new Date(); today.setHours(12, 0, 0, 0);
  const first = new Date(today);
  if (range === "week") first.setDate(today.getDate() - 6); else first.setDate(1);
  for (const d = new Date(first); d <= today; d.setDate(d.getDate() + 1)) out.push(new Date(d));
  return out;
}
function renderHistory() {
  const h = state.history;
  document.querySelectorAll("#hist-range button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.range === histRange)));
  if (!h) { $("#hist-note").textContent = ""; return; }
  const days = histDays(histRange).map((d) => ({ d, v: h.days[dkey(d)] || {} }));
  const sun = days.reduce((a, x) => a + (x.v.sun || 0), 0), grid = days.reduce((a, x) => a + (x.v.grid || 0), 0);
  const save = sun * Math.max(0, (h.price || 0) - (h.feedin || 0));
  $("#h-sun").textContent = kwhTxt(sun);
  $("#h-grid").textContent = kwhTxt(grid);
  $("#h-save").textContent = eur.format(save);
  const share = sun + grid > 0 ? Math.round(sun / (sun + grid) * 100) : null;
  const tracked = days.reduce((a, x) => a + (x.v.car_h || 0), 0);
  $("#hist-note").textContent = (share != null ? `${share}% della carica della Tesla è venuta dal sole. ` : "Ancora nessuna carica registrata in questo periodo. ") +
    `Conta le cariche fatte mentre l'automatico era acceso o l'app aperta. Risparmio a ${eur.format(h.price || 0)}/kWh` +
    (h.feedin ? ` meno ${eur.format(h.feedin)}/kWh di energia immessa.` : ".") + (tracked ? "" : "");
  drawHistory(days);
}
function drawHistory(days) {
  const box = $("#hist-chart");
  const W = Math.max(280, box.clientWidth || 320), H = 120, top = 14, bottom = 18, left = 2, right = 2;
  const max = Math.max(1, ...days.map((x) => (x.v.sun || 0) + (x.v.grid || 0)));
  const nice = max <= 5 ? Math.ceil(max) : Math.ceil(max / 5) * 5;
  const slot = (W - left - right) / days.length, bw = Math.max(3, Math.min(26, slot - 3));
  const y = (v) => top + (1 - v / nice) * (H - top - bottom);
  const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, height: H, role: "img", "aria-label": "Energia caricata nella Tesla ogni giorno" });
  root.append(svg("line", { x1: left, x2: W - right, y1: y(nice), y2: y(nice), class: "grid" }), svg("line", { x1: left, x2: W - right, y1: y(0), y2: y(0), class: "grid" }));
  const lbl = svg("text", { x: W - right, y: y(nice) - 4, "text-anchor": "end" }); lbl.textContent = `${nice} kWh`; root.append(lbl);
  const tip = el("div", { class: "fc-tip", hidden: "" });
  days.forEach((x, i) => {
    const cx = left + slot * i + slot / 2, s = x.v.sun || 0, g = x.v.grid || 0;
    const g0 = svg("g", { class: "hbar" });
    // segmento sole in basso, rete sopra, con 2 px di stacco
    if (s > 0) g0.append(svg("rect", { x: cx - bw / 2, y: y(s), width: bw, height: Math.max(1, y(0) - y(s)), rx: 3, class: "hsun" }));
    if (g > 0) g0.append(svg("rect", { x: cx - bw / 2, y: y(s + g), width: bw, height: Math.max(1, y(s) - y(s + g) - (s > 0 ? 2 : 0)), rx: 3, class: "hgrid" }));
    g0.append(svg("rect", { x: cx - slot / 2, y: top, width: slot, height: H - top - bottom, fill: "transparent" }));
    const show = () => {
      tip.textContent = `${x.d.toLocaleDateString("it-IT", { weekday: "short", day: "numeric", month: "short" })} · sole ${kwhTxt(s)} · rete ${kwhTxt(g)}`;
      tip.hidden = false; tip.style.left = `${Math.min(box.clientWidth - 110, Math.max(110, cx / W * box.clientWidth))}px`;
    };
    g0.addEventListener("pointerenter", show); g0.addEventListener("pointerdown", show);
    root.append(g0);
    const every = days.length > 10 ? 5 : 1;
    if (i % every === 0 || i === days.length - 1) {
      const t = svg("text", { x: cx, y: H - 4, "text-anchor": "middle" });
      t.textContent = days.length > 10 ? String(x.d.getDate()) : x.d.toLocaleDateString("it-IT", { weekday: "short" }).slice(0, 3);
      root.append(t);
    }
  });
  root.addEventListener("pointerleave", () => (tip.hidden = true));
  box.replaceChildren(root, tip);
}
document.querySelectorAll("#hist-range button").forEach((b) => b.addEventListener("click", () => { histRange = b.dataset.range; renderHistory(); }));

/* ------------------------------------------------------------------ quando saranno cariche */
let etaSig = "";
function renderEta() {
  const c = state.cfg || {}, plan = state.plan, rows = $("#eta-rows");
  const pref = currentPref();
  $("#eta-sub").textContent = plan ? PREFS[pref].label : "";
  if (!plan) {
    const msg = c.home_lat == null ? "Per calcolare gli orari serve la posizione di casa." : state.fcError || "Calcolo in corso…";
    const box = el("div", { class: "eta-empty" }, msg);
    if (c.home_lat == null) box.append(el("a", { class: "btn small", href: "#settings-forecast" }, "Imposta la posizione"));
    rows.replaceChildren(box);
    $("#eta-chart").replaceChildren(); $("#eta-legend").hidden = true; $("#eta-note").textContent = ""; etaSig = "";
    return;
  }
  const m = plan.milestones, now = new Date();
  const row = (kind, what, when, sub, muted) => {
    const r = el("div", { class: "eta-row" });
    const w = el("span", { class: "what" }); w.innerHTML = what;
    r.append(el("i", { class: `dot ${kind}` }), w, el("span", { class: `when${muted ? " muted" : ""}` }, when));
    if (sub) r.append(el("span", { class: "sub" }, sub));
    return r;
  };
  const out = [];
  if (!m.home.length) out.push(row("home", "Batteria di casa", "—", "Inserisci la capacità in Impostazioni per l'orario.", true));
  for (const h of m.home) {
    const sub = !h.done && !h.at && plan.eta.homeEnd != null ? `Domani sera circa ${Math.round(plan.eta.homeEnd)}%.` : "";
    out.push(row("home", `Casa <b>${al(h.soc)}</b>`, short(h.text), sub, h.done || !h.at));
  }
  if (!m.car) {
    out.push(row("car", "Tesla", "—", c.tesla_ready && c.logged_in ? "Tocca l'icona in alto per leggere la carica." : "Collega la Tesla in Impostazioni.", true));
  } else {
    const subs = [];
    if (!plan.carPresent) subs.push(carAtHome() === false ? "Non è a casa: orario se la colleghi appena torna." : "Non è collegata: orario se la colleghi adesso.");
    if (!m.car.done) {
      if (m.car.chargingFrom && m.car.chargingFrom - now > 20 * 60000) subs.push(`La carica col sole parte verso le ${hhmm(m.car.chargingFrom)}.`);
      if (!m.car.at && m.car.tonight != null) subs.push(`Stasera circa ${Math.round(m.car.tonight)}%, domani sera circa ${Math.round(m.car.end ?? m.car.tonight)}%.`);
      if (!m.car.chargingFrom) subs.push("Con il sole previsto non c'è abbastanza energia per caricarla.");
    }
    out.push(row("car", `Tesla <b>${al(m.car.soc)}</b>`, short(m.car.text), subs.join(" "), m.car.done || !m.car.at));
  }
  rows.replaceChildren(...out);
  $("#eta-note").textContent = "Stima con il sole previsto, i consumi tipici della casa e la preferenza scelta.";
  const box = $("#eta-chart");
  const sig = `${plan.track.length}|${plan.track[0] && plan.track[0].t.getTime()}|${Math.round(plan.startHome)}|${plan.startCar}|${pref}|${box.clientWidth}`;
  if (sig !== etaSig) { etaSig = sig; drawEta(plan); }
  $("#eta-legend").hidden = false;
}
const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);
const short = (s) => cap((s || "").replace(/^alle /, "").replace(/ alle /, " ").replace("non entro domani", "oltre domani"));

function drawEta(plan) {
  const box = $("#eta-chart");
  const track = plan.track;
  if (!track.length) { box.replaceChildren(); return; }
  const W = Math.max(280, box.clientWidth || 320), H = 150, top = 14, bottom = 20, left = 4, right = 30;
  const t0 = Date.now(), t1 = track[track.length - 1].t.getTime();
  const x = (t) => left + (t - t0) / (t1 - t0) * (W - left - right);
  const y = (v) => top + (1 - v / 100) * (H - top - bottom);
  const base = H - bottom;
  const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, height: H, role: "img", "aria-label": "Carica prevista della batteria di casa e della Tesla" });
  // sole previsto, sullo sfondo
  const sun = (plan.curve || []).filter((p) => p.t.getTime() >= t0 && p.t.getTime() <= t1);
  const peak = Math.max(1, ...sun.map((p) => p.w));
  if (sun.length) {
    let d = `M${x(sun[0].t.getTime()).toFixed(1)},${base}`;
    for (const p of sun) d += ` L${x(p.t.getTime()).toFixed(1)},${(base - p.w / peak * (base - top) * 0.35).toFixed(1)}`;
    d += ` L${x(sun[sun.length - 1].t.getTime()).toFixed(1)},${base} Z`;
    root.append(svg("path", { d, class: "sunband" }));
  }
  for (const v of [0, 50, 100]) {
    root.append(svg("line", { x1: left, x2: W - right, y1: y(v), y2: y(v), class: "grid" }));
    const tx = svg("text", { x: W - right + 4, y: y(v) + 3.5 }); tx.textContent = `${v}%`; root.append(tx);
  }
  // mezzanotte e ore
  const mid = new Date(); mid.setHours(24, 0, 0, 0);
  if (mid.getTime() < t1) {
    root.append(svg("line", { x1: x(mid), x2: x(mid), y1: top - 10, y2: base, class: "daysep" }));
    const tx = svg("text", { x: x(mid) + 5, y: top - 3, class: "daylabel" }); tx.textContent = "domani"; root.append(tx);
  }
  for (let t = Math.ceil(t0 / 3600000) * 3600000; t <= t1; t += 3600000) {
    const h = new Date(t).getHours();
    if (h % 6 || x(t) < left + 26 || x(t) > W - right - 6) continue;
    const tx = svg("text", { x: x(t), y: H - 5, "text-anchor": "middle" }); tx.textContent = String(h); root.append(tx);
  }
  const nowTx = svg("text", { x: left, y: H - 5, class: "daylabel" }); nowTx.textContent = "ora"; root.append(nowTx);
  // linee
  const line = (key, start, cls) => {
    const pts = [[t0, start]].concat(track.filter((p) => p[key] != null).map((p) => [p.t.getTime(), p[key]]));
    if (pts.length < 2 || start == null) return;
    root.append(svg("path", { d: pts.map(([t, v], i) => `${i ? "L" : "M"}${x(t).toFixed(1)},${y(v).toFixed(1)}`).join(" "), class: cls }));
  };
  line("home", plan.startHome, "lhome");
  if (plan.startCar != null) line("car", plan.startCar, "lcar");
  // traguardi
  const mark = (at, v, color, label) => {
    if (!at) return;
    const cx = x(at.getTime()), cy = y(v);
    root.append(svg("circle", { cx, cy, r: 4.5, fill: css(color), class: "mark" }));
    const tt = svg("title"); tt.textContent = `${label} ${hhmm(at)}`; root.lastChild.append(tt);
  };
  const m = plan.milestones;
  for (const h of m.home) if (h.at) mark(h.at, h.soc === 100 ? 100 : h.soc, "--battery-glow", `${h.soc}%`);
  if (m.car && m.car.at) mark(m.car.at, m.car.soc, "--car-glow", `Tesla ${m.car.soc}%`);
  // lettura al tocco
  const cross = svg("line", { y1: top, y2: base, class: "cross", visibility: "hidden" });
  root.append(cross);
  const tip = el("div", { class: "fc-tip", hidden: "" });
  box.replaceChildren(root, tip);
  const move = (ev) => {
    const r = root.getBoundingClientRect();
    const t = t0 + ((ev.clientX - r.left) / r.width * W - left) / (W - left - right) * (t1 - t0);
    let best = track[0];
    for (const p of track) if (Math.abs(p.t - t) < Math.abs(best.t - t)) best = p;
    const bx = x(best.t.getTime());
    cross.setAttribute("x1", bx); cross.setAttribute("x2", bx); cross.setAttribute("visibility", "visible");
    const parts = [`${best.t.getDate() !== new Date().getDate() ? "domani " : ""}${hhmm(best.t)}`, `casa ${Math.round(best.home)}%`];
    if (best.car != null) parts.push(`Tesla ${Math.round(best.car)}%`);
    tip.textContent = parts.join(" · "); tip.hidden = false;
    tip.style.left = `${Math.min(r.width - 90, Math.max(90, bx / W * r.width))}px`;
  };
  const hide = () => { cross.setAttribute("visibility", "hidden"); tip.hidden = true; };
  root.addEventListener("pointermove", move); root.addEventListener("pointerdown", move);
  root.addEventListener("pointerleave", hide); root.addEventListener("pointercancel", hide);
}
window.addEventListener("resize", () => { etaSig = ""; });

/* ------------------------------------------------------------------ previsioni e consigli */
async function loadSamples() {
  try { const raw = await native("kv.get", { key: "samples" }); state.samples = raw ? JSON.parse(raw) : []; }
  catch { state.samples = []; }
}
/** Storico delle letture: serve a tarare la resa dei pannelli e il consumo tipico della casa. */
function recordSample(s) {
  if (!s || !s.timestamp) return;
  const last = state.samples[state.samples.length - 1];
  if (last && last.t === s.timestamp) return;
  const cfg = state.cfg || {}, car = state.car;
  const carFresh = car && Date.now() - state.carAt < 15 * 60000;
  const sample = { t: s.timestamp, pv: Math.round(s.pv_w), bat: Math.round(s.battery_w), grid: Math.round(s.grid_w) };
  if (carFresh) sample.car = car.online && car.charging ? Math.round(car.power_w || 0) : 0;
  // il consumo della casa lo salvo solo se so quanto stava prendendo la Tesla
  if (carFresh || !cfg.tesla_ready) sample.house = Math.max(0, Math.round(s.house_load_w - (carFresh && car.charging ? car.power_w || 0 : 0)));
  state.samples.push(sample);
  const cutoff = Date.now() - 14 * 86400000;
  state.samples = state.samples.filter((x) => x.t >= cutoff).slice(-4000);
  native("samples.add", { sample }).catch(() => {});
}
async function loadForecast(force = false) {
  const c = state.cfg || {};
  if (c.home_lat == null) { state.fc = null; state.fcError = null; state.plan = null; render(); return; }
  try { state.fc = await native("forecast", { force }); state.fcError = null; }
  catch (e) { state.fcError = e.message; }
  computePlan(); render();
}
function computePlan() {
  const c = state.cfg || {};
  if (!state.fc || !state.solar) { state.plan = null; return; }
  try {
    state.plan = Planner.plan({
      now: new Date(), hours: state.fc.hours, samples: state.samples, solar: state.solar,
      car: state.car && state.car.online ? state.car : null, atHome: carAtHome(), pref: currentPref(),
      homeKwh: c.home_kwh, carKwh: c.car_kwh || 57.5, maxA: (state.car && state.car.max_amps) || c.max_amps || 16,
      batMaxW: (c.bat_max_kw || 3) * 1000,
    });
  } catch (e) { state.plan = null; state.fcError = `Calcolo non riuscito: ${e.message}`; }
}

const TIP_ICONS = {
  strategy: "M5 12.5l4.5 4.5L19 7.5",
  outlook: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M12 2.5v2 M12 19.5v2 M2.5 12h2 M19.5 12h2 M5.3 5.3l1.4 1.4 M17.3 17.3l1.4 1.4 M5.3 18.7l1.4-1.4 M17.3 6.7l1.4-1.4",
  plug: "M9 3v5 M15 3v5 M6.5 8h11v3.5a5.5 5.5 0 0 1-11 0z M12 17v4",
  surplus: "M13 2 5 13.5h6L10 22l9-12h-6z",
  tomorrow: "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z",
};
const kwhFmt = (v) => `${nf0.format(v)} kWh`;
let chartSig = "";
function renderForecast() {
  const c = state.cfg || {}, plan = state.plan, fc = state.fc;
  const empty = $("#fc-empty"), chart = $("#fc-chart"), adv = $("#advice"), tbl = $("#fc-table").closest("details");
  const show = (ok) => { chart.hidden = !ok; adv.hidden = !ok; tbl.hidden = !ok; };
  let msg = null;
  if (c.home_lat == null) msg = "Per le previsioni del sole e i consigli serve la posizione di casa.";
  else if (!fc) msg = state.fcError || "Scarico le previsioni…";
  else if (!plan) msg = state.fcError || (state.solar ? "Calcolo…" : "In attesa della lettura dell'inverter.");
  empty.hidden = !msg;
  if (msg) {
    $("#fc-empty-text").textContent = msg;
    empty.querySelector("a").hidden = c.home_lat != null;
    $("#fc-sum").textContent = ""; $("#fc-note").textContent = "";
    show(false); chartSig = ""; return;
  }
  show(true);
  $("#fc-sum").textContent = `oggi ${kwhFmt(plan.pvToday)} · domani ${kwhFmt(plan.pvTomorrow)}`;
  const sig = `${fc.fetched}|${plan.calibration.factor.toFixed(2)}|${chart.clientWidth}|${new Date().getHours()}:${Math.floor(new Date().getMinutes() / 15)}`;
  if (sig !== chartSig) { chartSig = sig; drawForecast(plan.curve); renderForecastTable(plan.curve); }

  adv.replaceChildren(...plan.advice.filter((a) => a.kind !== "outlook").map((a) => {
    const tip = el("div", { class: `tip ${a.kind}` });
    const ic = el("span", { class: "ic" });
    const s = svg("svg", { viewBox: "0 0 24 24", "aria-hidden": "true" }); s.append(svg("path", { d: TIP_ICONS[a.kind] || TIP_ICONS.outlook }));
    ic.append(s);
    tip.append(ic, el("h3", {}, a.title), el("p", {}, a.text));
    if (a.pref) {
      const b = el("button", { class: "btn primary small", type: "button" }, `Usa "${PREFS[a.pref].label}"`);
      b.addEventListener("click", async () => { await setPref(a.pref); toast(`Preferenza: ${PREFS[a.pref].label}`); });
      tip.append(b);
    }
    return tip;
  }));
  const when = new Date(fc.fetched);
  $("#fc-note").textContent = `Resa dei pannelli ${plan.calibration.how}. Previsioni Open-Meteo delle ${hhmm(when)}${fc.stale ? ", non aggiornate (nessuna connessione)" : ""}.`;
}

function drawForecast(curve) {
  const box = $("#fc-chart");
  const W = Math.max(280, box.clientWidth || 320), H = 132, top = 18, bottom = 20, left = 4, right = 4;
  const t0 = curve[0].t.getTime(), t1 = t0 + 48 * 3600000;
  const peak = Math.max(500, ...curve.map((p) => p.w));
  const yMax = Math.ceil(peak / 1000) * 1000;
  const x = (t) => left + (t - t0) / (t1 - t0) * (W - left - right);
  const y = (w) => top + (1 - w / yMax) * (H - top - bottom);
  const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, height: H, role: "img", "aria-label": "Produzione solare prevista per oggi e domani" });
  const base = H - bottom;
  root.append(svg("line", { x1: left, x2: W - right, y1: top, y2: top, class: "grid" }));
  const lbl = svg("text", { x: W - right, y: top - 5, "text-anchor": "end" }); lbl.textContent = `${nf0.format(yMax / 1000)} kW`; root.append(lbl);
  // area e linea
  let d = `M${x(t0)},${base}`;
  for (const p of curve) d += ` L${x(p.t.getTime()).toFixed(1)},${y(p.w).toFixed(1)}`;
  const dl = curve.map((p, i) => `${i ? "L" : "M"}${x(p.t.getTime()).toFixed(1)},${y(p.w).toFixed(1)}`).join(" ");
  root.append(svg("path", { d: `${d} L${x(curve[curve.length - 1].t.getTime())},${base} Z`, class: "area" }), svg("path", { d: dl, class: "line" }));
  // passato, mezzanotte, ore
  const now = Date.now();
  root.append(svg("rect", { x: left, y: top, width: Math.max(0, x(now) - left), height: base - top, class: "past" }));
  root.append(svg("line", { x1: left, x2: W - right, y1: base, y2: base, class: "base" }));
  const mid = t0 + 24 * 3600000;
  root.append(svg("line", { x1: x(mid), x2: x(mid), y1: top - 12, y2: base, class: "daysep" }));
  [["Oggi", t0], ["Domani", mid]].forEach(([name, t]) => {
    const tx = svg("text", { x: x(t) + 6, y: top - 5, class: "daylabel" }); tx.textContent = name; root.append(tx);
  });
  for (const day of [t0, mid]) for (const h of [6, 12, 18]) {
    const t = day + h * 3600000, tx = svg("text", { x: x(t), y: H - 5, "text-anchor": "middle" });
    tx.textContent = String(h); root.append(tx);
  }
  root.append(svg("line", { x1: x(now), x2: x(now), y1: top, y2: base, class: "nowline" }));
  const nl = svg("text", { x: x(now), y: base + 13, "text-anchor": "middle", class: "daylabel" }); nl.textContent = "ora";
  // l'etichetta "ora" sostituisce l'ora più vicina per non sovrapporsi
  root.querySelectorAll("text").forEach((t) => { if (t.getAttribute("y") == H - 5 && Math.abs(Number(t.getAttribute("x")) - x(now)) < 16) t.remove(); });
  root.append(nl);
  // lettura al tocco
  const cross = svg("line", { y1: top, y2: base, class: "cross", visibility: "hidden" });
  const dot = svg("circle", { r: 4.5, class: "hdot", visibility: "hidden" });
  root.append(cross, dot);
  const tip = el("div", { class: "fc-tip", hidden: "" });
  box.replaceChildren(root, tip);
  const move = (ev) => {
    const r = root.getBoundingClientRect();
    const px = (ev.clientX - r.left) / r.width * W;
    const t = t0 + (px - left) / (W - left - right) * (t1 - t0);
    let best = curve[0];
    for (const p of curve) if (Math.abs(p.t - t) < Math.abs(best.t - t)) best = p;
    const bx = x(best.t.getTime()), by = y(best.w);
    cross.setAttribute("x1", bx); cross.setAttribute("x2", bx); cross.setAttribute("visibility", "visible");
    dot.setAttribute("cx", bx); dot.setAttribute("cy", by); dot.setAttribute("visibility", "visible");
    const day = best.t.getTime() >= mid ? "Domani" : "Oggi";
    tip.replaceChildren(document.createTextNode(`${day} ${hhmm(best.t)} · `), el("b", {}, fmtW(best.w)));
    tip.hidden = false;
    tip.style.left = `${Math.min(r.width - 70, Math.max(70, bx / W * r.width))}px`;
  };
  const hide = () => { cross.setAttribute("visibility", "hidden"); dot.setAttribute("visibility", "hidden"); tip.hidden = true; };
  root.addEventListener("pointermove", move);
  root.addEventListener("pointerdown", move);
  root.addEventListener("pointerleave", hide);
  root.addEventListener("pointercancel", hide);
}
function renderForecastTable(curve) {
  const mid = curve[0].t.getTime() + 24 * 3600000;
  const rows = [];
  for (let h = 0; h < 24; h++) {
    const avg = (from) => {
      const ps = curve.filter((p) => p.t.getTime() >= from + h * 3600000 && p.t.getTime() < from + (h + 1) * 3600000);
      return ps.length ? ps.reduce((a, p) => a + p.w, 0) / ps.length : 0;
    };
    const a = avg(curve[0].t.getTime()), b = avg(mid);
    if (a > 20 || b > 20) rows.push([`${String(h).padStart(2, "0")}:00`, a, b]);
  }
  const table = el("table");
  const head = el("tr"); ["Ora", "Oggi", "Domani"].forEach((t) => head.append(el("th", {}, t)));
  table.append(head);
  for (const [h, a, b] of rows) { const tr = el("tr"); tr.append(el("td", {}, h), el("td", {}, fmtW(a)), el("td", {}, fmtW(b))); table.append(tr); }
  $("#fc-table").replaceChildren(table);
}
window.addEventListener("resize", () => { chartSig = ""; renderForecast(); });

/* ------------------------------------------------------------------ impostazioni */
function fillSettings() {
  const c = state.cfg || {};
  $("#f-solax-token").value = c.solax_token || "";
  $("#f-solax-sn").value = c.solax_sn || "";
  $("#f-title").value = c.title || "";
  $("#f-home-kwh").value = c.home_kwh || "";
  $("#f-bat-max").value = c.bat_max_kw ?? 3;
  $("#f-price").value = c.price_kwh ?? 0.30;
  $("#f-feedin").value = c.feedin_kwh ?? 0;
  $("#f-client-id").value = c.client_id || "";
  $("#f-client-secret").value = "";
  $("#f-client-secret").placeholder = c.has_secret ? "salvato (lascia vuoto per non cambiarlo)" : "";
  $("#f-domain").value = c.domain || "";
  $("#f-vin").value = c.vin || "";
  $("#f-key").value = "";
  $("#f-key").placeholder = c.has_key ? "Chiave salvata (incolla solo per sostituirla)" : "-----BEGIN EC PRIVATE KEY-----";
  $("#f-max-amps").value = c.max_amps || 16;
  $("#f-home-pos").value = c.home_lat != null ? `${c.home_lat}, ${c.home_lon}` : "";
  $("#f-tilt").value = c.tilt ?? 30;
  $("#f-az").value = String(c.azimuth ?? 0);
  const set = (id, ok, text) => { $(`#${id}`).className = `dot ${ok ? "ok" : "bad"}`; $(`#${id}-text`).textContent = text; };
  set("st-cfg", c.tesla_ready, c.tesla_ready ? `Configurata${c.vin ? ` (VIN …${c.vin.slice(-6)})` : ""}` : `Mancano: ${(c.missing || []).join(", ") || "dati"}`);
  set("st-login", c.logged_in, c.logged_in ? "Accesso Tesla fatto da questo telefono" : "Accesso Tesla da fare");
  $("#btn-login").disabled = !(c.client_id && c.has_secret && c.domain);
  loadSetup();
}

/* ------------------------------------------------------------------ collegamento Tesla dal telefono */
async function loadSetup() {
  try { state.setup = await native("setup.status"); } catch (e) { toast(e.message); return; }
  renderWizard();
}
function renderWizard() {
  const s = state.setup;
  if (!s) return;
  const done = [s.has_key, s.published, s.client, s.registered, s.logged_in, !!s.vin, s.paired];
  const now = done.indexOf(false);
  done.forEach((ok, i) => {
    const d = $(`#wz${i + 1}`);
    d.className = ok ? "done" : i === now ? "now" : "todo";
    d.querySelector(".dot").className = `dot${ok ? " ok" : ""}`;
    if (i === now) d.open = true;
    else if (ok) d.open = false;
  });
  const chip = $("#wz-chip");
  const count = done.filter(Boolean).length;
  chip.className = `chip${count === 7 ? "" : " dry"}`;
  chip.textContent = count === 7 ? "Collegata" : `${count} di 7`;

  $("#wz-key").hidden = s.has_key;
  $("#wz-key-done").hidden = !s.has_key;
  $("#wz-key-new").closest("details").hidden = !s.has_key;
  const user = s.domain ? s.domain.replace(/\.github\.io$/, "") : "";
  if (document.activeElement !== $("#wz-gh")) $("#wz-gh").value = user;
  $("#wz-gh-steps").hidden = !(s.has_key && s.domain);
  $("#wz-repo").textContent = s.domain || "";
  $("#wz-pub").textContent = (s.public_key || "").trim();
  $("#wz-origin").textContent = s.domain ? `https://${s.domain}` : "prima completa il passo 2";
  $("#wz-redirect").textContent = s.domain ? `https://${s.domain}/callback` : "prima completa il passo 2";
  if (document.activeElement !== $("#wz-cid")) $("#wz-cid").value = s.client_id || "";
  $("#wz-secret").placeholder = s.client ? "salvato (lascia vuoto per non cambiarlo)" : "";
  $("#wz-login").disabled = !(s.client && s.domain);
  $("#wz-pair").disabled = !s.pair_url;
  if (s.vin) $("#wz-car-msg").textContent = `Auto scelta: VIN …${s.vin.slice(-6)}`;
}
async function step(btn, fn) {
  btn.disabled = true;
  try { await fn(); } catch (e) { toast(e.message); } finally { btn.disabled = false; }
  await loadConfig(); await loadSetup(); render();
}
$("#wz-key").addEventListener("click", (e) => step(e.currentTarget, async () => {
  await native("setup.createKey"); toast("Chiave creata");
}));
$("#wz-key-new").addEventListener("click", (e) => {
  if (!confirm("La chiave attuale smetterà di funzionare: dovrai ripubblicarla su GitHub e riabbinarla all'auto. Continuo?")) return;
  step(e.currentTarget, async () => { await native("setup.createKey", { force: true }); toast("Nuova chiave creata"); });
});
$("#wz-gh-save").addEventListener("click", (e) => step(e.currentTarget, async () => {
  await native("setup.domain", { github: $("#wz-gh").value }); toast("Salvato");
}));
$("#wz-check").addEventListener("click", (e) => step(e.currentTarget, async () => {
  $("#wz-check-msg").textContent = "Controllo…";
  const r = await native("setup.checkPublished");
  $("#wz-check-msg").textContent = r.ok ? "La chiave è online." : r.why;
  if (r.ok) toast("La chiave è online");
}));
$("#wz-client").addEventListener("click", (e) => step(e.currentTarget, async () => {
  await native("setup.client", { id: $("#wz-cid").value, secret: $("#wz-secret").value });
  $("#wz-secret").value = ""; toast("Salvato");
}));
$("#wz-register").addEventListener("click", (e) => step(e.currentTarget, async () => {
  $("#wz-register-msg").textContent = "Registro…";
  const r = await native("setup.register");
  $("#wz-register-msg").textContent = r.key_match ? "Registrato." :
    "Registrato, ma Tesla riporta una chiave diversa dalla tua: ricontrolla il file su GitHub. Se l'abbinamento non riesce, è per questo.";
  if (r.key_match) toast("Dominio registrato presso Tesla");
  else alert($("#wz-register-msg").textContent);
}));
$("#wz-login").addEventListener("click", () => native("tesla.login").catch((e) => toast(e.message)));
$("#wz-cars").addEventListener("click", (e) => step(e.currentTarget, async () => {
  const list = $("#wz-car-list");
  list.replaceChildren();
  const cars = await native("setup.vehicles");
  if (!cars.length) { $("#wz-car-msg").textContent = "Nessuna auto su questo account Tesla."; return; }
  if (cars.length === 1) { toast(`Auto trovata: ${cars[0].name || "Tesla"}`); return; }
  $("#wz-car-msg").textContent = "Tocca la tua auto:";
  for (const c of cars) {
    const b = el("button", { class: "btn", type: "button" }, `${c.name || "Tesla"} · …${c.vin.slice(-6)}`);
    b.addEventListener("click", () => step(b, async () => { await native("setup.pick", { vin: c.vin }); list.replaceChildren(); toast(`Auto scelta: ${c.name || "Tesla"}`); }));
    list.append(b);
  }
}));
$("#wz-pair").addEventListener("click", () => {
  if (state.setup && state.setup.pair_url) native("open", { url: state.setup.pair_url }).catch((e) => toast(e.message));
});
$("#wz-paired").addEventListener("click", (e) => step(e.currentTarget, async () => {
  $("#wz-paired-msg").textContent = "Controllo…";
  const r = await native("setup.paired");
  $("#wz-paired-msg").textContent = r.ok ? "Chiave abbinata: la Tesla è collegata. Torna in Home e tocca \"Leggi stato Tesla\"."
    : "Non ancora abbinata. Conferma nell'app Tesla e in auto, poi riprova tra un minuto.";
  if (r.ok) { alert("Chiave abbinata: la Tesla è collegata. Torna in Home e tocca \"Leggi stato Tesla\"."); readCar(true); }
}));
document.addEventListener("click", (e) => {
  const o = e.target.closest("[data-open]");
  if (o) { e.preventDefault(); native("open", { url: o.dataset.open }).catch((err) => toast(err.message)); return; }
  const c = e.target.closest("[data-copy], [data-copy-text]");
  if (c) {
    const text = c.dataset.copyText || ($(c.dataset.copy) || {}).textContent || "";
    native("copy", { text: text.trim() }).then(() => toast("Copiato")).catch((err) => toast(err.message));
  }
});
$("#btn-import").addEventListener("click", async () => {
  try {
    await native("config.import", { code: $("#code").value.trim() });
    $("#code").value = "";
    await loadConfig(); fillSettings(); render(); readSolar();
    toast("Codice importato. Ora accedi all'account Tesla.");
  } catch (e) { toast(e.message); }
});
$("#btn-save").addEventListener("click", async () => {
  const v = (id) => $(id).value.trim();
  try {
    await native("config.save", {
      solax_token: v("#f-solax-token"), solax_sn: v("#f-solax-sn"), title: v("#f-title"),
      home_kwh: v("#f-home-kwh"), bat_max_kw: v("#f-bat-max"), price_kwh: v("#f-price"), feedin_kwh: v("#f-feedin"), client_id: v("#f-client-id"), client_secret: v("#f-client-secret"),
      domain: v("#f-domain"), vin: v("#f-vin"), private_key: $("#f-key").value.trim(), max_amps: v("#f-max-amps"),
      ...homePos(v("#f-home-pos")), tilt: v("#f-tilt") || "30", azimuth: $("#f-az").value,
    });
    loadForecast(true);
    await loadConfig(); fillSettings(); render(); readSolar(); readCar(true);
    toast("Impostazioni salvate");
  } catch (e) { toast(e.message); }
});
function homePos(text) {
  const nums = (text.match(/-?\d+(?:[.,]\d+)?/g) || []).map((n) => n.replace(",", "."));
  if (!text.trim()) return { home_lat: "", home_lon: "" };
  if (nums.length < 2) throw new Error("Posizione di casa: scrivi latitudine e longitudine, per esempio 45.70, 12.83");
  return { home_lat: nums[0], home_lon: nums[1] };
}
$("#btn-car-pos").addEventListener("click", async (e) => {
  const b = e.currentTarget; b.disabled = true;
  try {
    if (!state.car || state.car.lat == null) await readCar(true);
    if (!state.car || state.car.lat == null) throw new Error(state.carError || "La Tesla non ha mandato la posizione: serve il permesso \"Vehicle Location\" nell'app su developer.tesla.com");
    $("#f-home-pos").value = `${state.car.lat.toFixed(5)}, ${state.car.lon.toFixed(5)}`;
    toast("Posizione della Tesla inserita: tocca Salva");
  } catch (err) { toast(err.message); }
  finally { b.disabled = false; }
});
$("#btn-login").addEventListener("click", () => native("tesla.login").catch((e) => toast(e.message)));
$("#btn-login-ext").addEventListener("click", () => native("tesla.loginExternal").catch((e) => toast(e.message)));
$("#btn-callback").addEventListener("click", async () => {
  try {
    await native("tesla.loginFinish", { url: $("#f-callback").value.trim() });
    await loadConfig(); fillSettings(); render(); readCar(true); toast("Accesso Tesla fatto");
  }
  catch (e) { toast(e.message); }
});
$("#btn-forget").addEventListener("click", async () => {
  if (!confirm("Cancellare dal telefono token, chiave e dati SolaX?")) return;
  await native("config.forget"); await loadConfig(); fillSettings(); loadAuto();
  state.solar = null; state.car = null; render(); toast("Dati cancellati");
});

/* ------------------------------------------------------------------ avvio */
async function start() {
  try { await loadConfig(); } catch (e) { banner(e.message); return; }
  await loadSamples();
  await loadAuto();
  loadHistory();
  route();
  render();
  loadForecast();
  await readSolar();
  if (new URLSearchParams(location.search).get("login") === "ok") toast("Accesso Tesla fatto");
  readCar(true);
}
setInterval(() => { if (document.visibilityState === "visible") readSolar(); }, 60000);
setInterval(() => { if (document.visibilityState === "visible") loadForecast(); }, 30 * 60000);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") { loadAuto(); loadSamples(); loadHistory(); }
  if (document.visibilityState === "visible" && Date.now() - state.solarAt > 50000) readSolar();
});
start();
