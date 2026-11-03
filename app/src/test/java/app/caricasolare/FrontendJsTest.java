package app.caricasolare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

public class FrontendJsTest {

    private static String readAsset(String name) throws Exception {
        Path p1 = Paths.get("src", "main", "assets", name);
        Path p = Files.exists(p1) ? p1 : Paths.get("app", "src", "main", "assets", name);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void plannerJsSimulatesSolarHomeAndCarSchedule() throws Exception {
        String plannerCode = readAsset("planner.js");
        try (Context ctx = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            ctx.eval("js", plannerCode);
            Value res = ctx.eval("js", """
                (() => {
                  const now = new Date("2026-06-15T10:00:00");
                  const hours = {};
                  const d0 = new Date("2026-06-15T00:00:00");
                  for (let h = 0; h < 48; h++) {
                    const d = new Date(d0.getTime() + h * 3600000);
                    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}T${String(d.getHours()).padStart(2, "0")}:00`;
                    const hr = d.getHours();
                    hours[key] = (hr >= 7 && hr <= 19) ? 750 : 0;
                  }
                  const p = Planner.plan({
                    now,
                    hours,
                    samples: [],
                    solar: { pv_w: 3600, grid_w: 1800, battery_w: 1200, home_soc: 82, house_load_w: 600 },
                    car: { online: true, plugged: true, charging: false, soc: 50, limit: 80, max_amps: 16 },
                    atHome: true,
                    pref: "equilibrio",
                    homeKwh: 10,
                    carKwh: 60,
                    maxA: 16,
                    batMaxW: 3000
                  });
                  return JSON.stringify({
                    pvTodayPos: p.pvToday > 10,
                    trackLen: p.track.length,
                    carEndAboveStart: p.eta.carEnd > 50
                  });
                })()
                """);
            String json = res.asString();
            assertTrue(json, json.contains("\"pvTodayPos\":true"));
            assertTrue(json, json.contains("\"carEndAboveStart\":true"));
        }
    }

    @Test
    public void appJsSyntaxLoadsAndOptimizeAutoWakesSleepingTesla() throws Exception {
        String plannerCode = readAsset("planner.js");
        String appCode = readAsset("app.js");

        try (Context ctx = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            // Minimal DOM + Native bridge shim to execute the entire app.js in strict mode
            ctx.eval("js", """
                const __elements = new Map();
                function __makeEl(id = "") {
                  const el = {
                    id,
                    hidden: false,
                    disabled: false,
                    checked: false,
                    value: "80",
                    textContent: "",
                    innerHTML: "",
                    className: "",
                    dataset: {},
                    style: { cssText: "", width: "", left: "" },
                    clientWidth: 320,
                    firstChild: { textContent: "" },
                    classList: { toggle() {}, add() {}, remove() {} },
                    setAttribute(k, v) { this[k] = v; },
                    setAttributeNS() {},
                    getAttribute(k) { return this[k] ?? null; },
                    remove() {},
                    append() {},
                    replaceChildren() {},
                    addEventListener() {},
                    querySelector() { return __makeEl(); },
                    querySelectorAll() { return []; },
                    closest() { return __makeEl(); },
                    getBoundingClientRect() { return { left: 0, width: 320 }; },
                    scrollIntoView() {}
                  };
                  return el;
                }
                globalThis.window = globalThis;
                globalThis.addEventListener = () => {};
                globalThis.URLSearchParams = class { constructor() {} get() { return null; } };
                globalThis.location = { hash: "#home", search: "" };
                globalThis.document = {
                  visibilityState: "visible",
                  activeElement: null,
                  documentElement: {},
                  getElementById(id) {
                    if (!__elements.has(id)) __elements.set(id, __makeEl(id));
                    return __elements.get(id);
                  },
                  querySelector(sel) {
                    if (sel.startsWith("#")) return this.getElementById(sel.slice(1));
                    return __makeEl(sel);
                  },
                  querySelectorAll() { return []; },
                  createElement(tag) { return __makeEl(tag); },
                  createElementNS(ns, tag) { return __makeEl(tag); },
                  createTextNode(t) { return { textContent: t }; },
                  addEventListener() {}
                };
                globalThis.getComputedStyle = () => ({ getPropertyValue: () => "#f59e0b" });
                globalThis.matchMedia = () => ({ matches: true });
                globalThis.setInterval = () => 1;
                globalThis.setTimeout = (fn, ms) => { if (ms <= 10) fn(); return 1; };
                globalThis.clearTimeout = () => {};
                globalThis.scrollTo = () => {};
                globalThis.__calls = [];
                globalThis.Native = {
                  call(id, method, rawArgs) {
                    const args = JSON.parse(rawArgs || "{}");
                    __calls.push(method + (args.name ? ":" + args.name + "=" + args.value : ""));
                    let resp = {};
                    if (method === "config.get") resp = { title: "Casa", solax_ready: true, tesla_ready: true, logged_in: true, pref: "casa", max_amps: 16, home_kwh: 10, car_kwh: 60, bat_max_kw: 3, price_kwh: 0.3, feedin_kwh: 0, home_lat: 45.46, home_lon: 9.19, tilt: 30, azimuth: 0, missing: [] };
                    else if (method === "kv.get") resp = "[]";
                    else if (method === "auto.status") resp = { enabled: true, last_at: Date.now(), last_text: "Ok", next_at: Date.now() + 300000, log: [], battery_ok: true };
                    else if (method === "history") resp = { price: 0.3, feedin: 0, days: {} };
                    else if (method === "solax.read") resp = { pv_w: 3800, grid_w: 2500, battery_w: 600, home_soc: 96, house_load_w: 700, timestamp: Date.now() };
                    else if (method === "tesla.state") resp = { online: false };
                    else if (method === "tesla.wakeAndRead") resp = { online: true, plugged: true, charging: false, soc: 52, limit: 80, amps: 0, request_amps: 8, max_amps: 16, power_w: 0, lat: 45.46, lon: 9.19 };
                    else if (method === "tesla.command") resp = { ok: true, reason: "" };
                    else if (method === "stats") resp = { data: 2, commands: 2, wakes: 1, cost: 0.02 };
                    else if (method === "forecast") resp = { fetched: Date.now(), hours: {}, clouds: {} };
                    window.__native(id, true, JSON.stringify(resp));
                  }
                };
                """);

            ctx.eval("js", plannerCode);
            ctx.eval("js", appCode);

            // Ora chiamiamo optimize() con batteria di casa al 96% e Tesla addormentata:
            // deve chiamare tesla.wakeAndRead, impostare 12 A e avviare la carica!
            ctx.eval("js", "__calls.length = 0; var __done = false; optimize().then(() => { __done = true; });");
            boolean done = ctx.eval("js", "__done").asBoolean();
            assertTrue("optimize() promise resolved synchronously via shim", done);

            String calls = ctx.eval("js", "JSON.stringify(__calls)").asString();
            String optMsg = ctx.eval("js", "document.getElementById('opt-msg').textContent").asString();

            assertTrue("Should call tesla.wakeAndRead: " + calls, calls.contains("tesla.wakeAndRead"));
            assertTrue("Should set 12A: " + calls, calls.contains("tesla.command:amps=12"));
            assertTrue("Should start charging: " + calls, calls.contains("tesla.command:start=0"));
            assertTrue("Unexpected optMsg: " + optMsg, optMsg.contains("impostato 12 A") && optMsg.contains("avviato la carica"));

            // Verifica anche che se la batteria di casa è sotto il 95% in "Casa prima", NON sveglia inutilmente l'auto
            ctx.eval("js", """
                __calls.length = 0;
                const origCall = Native.call;
                Native.call = (id, method, rawArgs) => {
                  if (method === "solax.read") {
                    __calls.push(method);
                    window.__native(id, true, JSON.stringify({ pv_w: 3200, grid_w: 50, battery_w: 2800, home_soc: 78, house_load_w: 350, timestamp: Date.now() }));
                    return;
                  }
                  origCall(id, method, rawArgs);
                };
                optimize();
                """);
            String callsBelow95 = ctx.eval("js", "JSON.stringify(__calls)").asString();
            assertEquals(-1, callsBelow95.indexOf("tesla.wakeAndRead"));
        }
    }
}
