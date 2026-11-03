/* Previsioni e consigli: porting di app/forecast.py e app/planner.py dell'app del PC.
   Funzioni pure (nessuna chiamata di rete): ricevono irraggiamento, storico e stato, restituiscono orari e consigli.
   Simula a passi di 15 minuti fino a fine domani e confronta le preferenze "Casa prima", "Bilanciata", "Auto prima". */
"use strict";

const Planner = (() => {
  const STEP_MS = 15 * 60000;
  const DT_H = 0.25;
  const HOME_EFF = 0.95;
  const TAPER_SOC = 95, TAPER_W = 500;   // sopra il 95% la batteria di casa carica piano (circa 500 W)
  const MIN = 60000, HOUR = 3600000;

  /* regole di ogni preferenza: precedenza alla batteria di casa fino a `priority`% e aiuto della batteria all'auto */
  const STRATEGIES = {
    casa: { priority: 101, supportSoc: 101, supportW: 0, mode: "solar", homeFirst: true },
    equilibrio: { priority: 80, supportSoc: 101, supportW: 0, mode: "solar" },
    auto: { priority: 0, supportSoc: 50, supportW: 1000, mode: "solar" },
    max: { priority: 101, supportSoc: 101, supportW: 0, mode: "max" },
  };
  const NAMES = { casa: "Casa prima", equilibrio: "Bilanciata", auto: "Auto prima", max: "Massima" };

  const pad = (n) => String(n).padStart(2, "0");
  const hourKey = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:00`;
  const dayKey = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const median = (xs) => { const s = [...xs].sort((a, b) => a - b), m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
  const al = (n) => { const v = Math.round(n); return /^8/.test(String(v)) || v === 11 || v === 18 ? `all'${v}%` : `al ${v}%`; };
  const hm = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const kwh1 = (v) => v.toLocaleString("it-IT", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  const kwh0 = (v) => Math.round(v).toLocaleString("it-IT");

  /* ------------------------------------------------------------------ sole */
  /** Irraggiamento all'istante `when`: il valore delle ore T vale per (T-1h, T], lo colloco a T-30'. */
  function gtiAt(hours, when) {
    const base = new Date(when); base.setMinutes(0, 0, 0);
    const t0 = new Date(base.getTime() + (when.getMinutes() >= 30 ? 30 : -30) * MIN);
    const t1 = new Date(t0.getTime() + HOUR);
    const v0 = hours[hourKey(new Date(t0.getTime() + 30 * MIN))];
    const v1 = hours[hourKey(new Date(t1.getTime() + 30 * MIN))];
    if (v0 == null && v1 == null) return 0;
    if (v0 == null || v1 == null) return v0 == null ? v1 : v0;
    return v0 + (v1 - v0) * ((when - t0) / HOUR);
  }

  /** W prodotti per ogni W/m²: mediana dello storico, altrimenti la lettura di adesso, altrimenti 6. */
  function calibrate(hours, samples, current) {
    const ratios = [];
    for (const s of samples) {
      if (s.pv == null) continue;
      const g = gtiAt(hours, new Date(s.t));
      if (g >= 150 && s.pv > 100) ratios.push(s.pv / g);
    }
    if (ratios.length >= 12) return { factor: median(ratios), how: `tarata su ${ratios.length} letture dei giorni scorsi` };
    if (current) {
      const g = gtiAt(hours, new Date(current.t));
      if (g >= 150 && current.pv > 100) return { factor: current.pv / g, how: "tarata sulla lettura di adesso: diventa più precisa con i giorni" };
    }
    return { factor: 6, how: "stima generica: diventa precisa dopo qualche giorno di uso" };
  }

  function pvForecast(hours, factor, start, end, stepMin = 15, clip = null) {
    const out = [];
    for (let t = start.getTime(); t < end.getTime(); t += stepMin * MIN) {
      let w = gtiAt(hours, new Date(t)) * factor;
      if (clip) w = Math.min(w, clip);
      out.push({ t: new Date(t), w: Math.max(0, w) });
    }
    return out;
  }

  /* ------------------------------------------------------------------ dallo storico */
  function houseProfile(samples, fallback) {
    const byHour = {};
    for (const s of samples) if (s.house != null) (byHour[new Date(s.t).getHours()] ||= []).push(s.house);
    const all = Object.values(byHour).flat();
    if (all.length < 96) return Array(24).fill(fallback || 400);
    const base = median(all);
    return Array.from({ length: 24 }, (_, h) => (byHour[h] && byHour[h].length >= 3 ? median(byHour[h]) : base));
  }
  /** Potenza massima di carica della batteria di casa: quella impostata, o di più se l'ho vista nello storico. */
  function batteryPower(samples, configuredW = 3000) {
    const seen = samples.filter((s) => s.bat != null).map((s) => s.bat);
    return Math.max(configuredW, seen.length ? Math.max(...seen) : 0);
  }

  /* ------------------------------------------------------------------ simulazione */
  function simulate(o, start, pv, house, rule, carFrom) {
    const { volts = 230, minA = 5, maxA = 16, reserve = 100, carKwh = 57.5, carEff = 0.9, homeKwh = 0, batW, minSoc = 10 } = o;
    const res = { rule, steps: [], homeFull: null, carFull: null, carKwhToday: 0, exportToday: 0, importKwh: 0,
      exportAfterFullToday: 0, both: null, storedEnd: 0, homeEnd: null, carEnd: null };
    let home = start.home, csoc = start.car;
    let homeFull = start.home >= TAPER_SOC;   // "Casa prima": la Tesla aspetta la batteria di casa al 95%
    const limit = start.limit;
    const today = pv.length ? dayKey(pv[0].t) : null;
    const lastSun = pv.filter((p) => p.w > 80).map((p) => p.t.getTime());
    const sunsetLast = lastSun.length ? Math.max(...lastSun) : null;

    for (const { t, w: pvW } of pv) {
      const load = house[t.getHours()];
      const net = pvW - load;
      const room = homeKwh ? Math.max(0, (100 - home) / 100 * homeKwh * 1000 / DT_H / HOME_EFF) : 0;
      const batCap = home >= TAPER_SOC ? Math.min(batW, TAPER_W) : batW;   // rallentamento sopra il 95%
      const batWant = Math.min(Math.max(net, 0), batCap, room);
      let carW = 0;
      if (home >= TAPER_SOC) homeFull = true; else if (home < 90) homeFull = false;
      const present = csoc != null && limit != null && (!carFrom || t >= carFrom) && !(rule.homeFirst && !homeFull);
      if (present && csoc < limit - 0.05) {
        let amps;
        if (rule.mode === "max") amps = maxA;
        else {
          let avail = net - (home < rule.priority ? batWant : 0) - reserve;
          if (home >= rule.supportSoc) avail += rule.supportW;
          // batteria di casa piena: arrotondo verso l'auto (fino a 300 W dalla batteria, sopra il 96%)
          if (home >= 96 && (!rule.homeFirst || homeFull)) avail += reserve + 300;
          const raw = Math.floor(avail / volts);
          amps = raw >= minA ? Math.min(raw, maxA) : 0;
        }
        carW = amps * volts;
      }
      const rest = net - carW;
      let bat;
      if (rest > 0) {
        bat = Math.min(rest, batCap, room);
        // se nel quarto d'ora supera il 95%, da lì in poi carica piano
        if (homeKwh && home < TAPER_SOC && bat > TAPER_W) {
          const toTaper = (TAPER_SOC - home) / 100 * homeKwh / HOME_EFF;          // kWh fino al 95%
          const hours = toTaper / (bat / 1000);
          if (hours < DT_H) bat = (toTaper + Math.min(TAPER_W, bat) / 1000 * (DT_H - hours)) / DT_H * 1000;
        }
      }
      else {
        const availKwh = Math.max(0, (home - minSoc) / 100 * homeKwh);
        bat = -Math.min(-rest, batW, homeKwh ? availKwh * 1000 / DT_H * HOME_EFF : 0);
      }
      const grid = rest - bat;

      if (homeKwh) home = Math.min(100, Math.max(0, home + bat * DT_H / 1000 * (bat > 0 ? HOME_EFF : 1 / HOME_EFF) / homeKwh * 100));
      if (carW && csoc != null) {
        const added = carW * DT_H / 1000;
        csoc = Math.min(limit, csoc + added * carEff / carKwh * 100);
        if (dayKey(t) === today) res.carKwhToday += added;
      }
      if (dayKey(t) === today && grid > 0) {
        res.exportToday += grid * DT_H / 1000;
        if (res.homeFull && (res.carFull || csoc == null)) res.exportAfterFullToday += grid * DT_H / 1000;
      }
      if (grid < 0) res.importKwh += -grid * DT_H / 1000;

      const end = new Date(t.getTime() + STEP_MS);
      if (!res.homeFull && homeKwh && home >= 99.5) res.homeFull = end;
      if (!res.carFull && csoc != null && limit != null && csoc >= limit - 0.1) res.carFull = end;
      const stored = home / 100 * homeKwh + (csoc || 0) / 100 * carKwh;
      if (sunsetLast && t.getTime() <= sunsetLast) { res.storedEnd = stored; res.homeEnd = home; res.carEnd = csoc; }
      res.steps.push({ t: end, pv: pvW, house: load, car: carW, bat, grid, home, car_soc: csoc });
    }
    if (res.homeFull && res.carFull) res.both = new Date(Math.max(res.homeFull, res.carFull));
    return res;
  }

  function bestOf(results, current) {
    const key = (r) => [r.both ? r.both.getTime() : Infinity, -Math.round(r.storedEnd * 2) / 2,
      Math.round(r.importKwh * 10) / 10, r.rule.name === current ? 0 : 1];
    return results.slice().sort((a, b) => {
      const ka = key(a), kb = key(b);
      for (let i = 0; i < ka.length; i++) if (ka[i] !== kb[i]) return ka[i] < kb[i] ? -1 : 1;
      return 0;
    })[0];
  }

  /* ------------------------------------------------------------------ testi */
  function whenText(t, now) {
    if (!t) return "non entro domani";
    if (t <= new Date(now.getTime() + STEP_MS)) return "adesso";
    if (dayKey(t) === dayKey(now)) return `alle ${hm(t)}`;
    const tomorrow = new Date(now); tomorrow.setDate(now.getDate() + 1);
    if (dayKey(t) === dayKey(tomorrow)) return `domani alle ${hm(t)}`;
    return `il ${pad(t.getDate())}/${pad(t.getMonth() + 1)} alle ${hm(t)}`;
  }

  /**
   * input: { now, hours, samples, solar {pv_w, home_soc, house_load_w, timestamp}, car {soc, limit, plugged, online, power_w} | null,
   *          atHome (bool|null), pref, homeKwh, carKwh, maxA }
   */
  function plan(input) {
    const { now, hours, samples, solar, car, pref, homeKwh, carKwh = 57.5, maxA = 16 } = input;
    const cal = calibrate(hours, samples, { t: solar.timestamp, pv: solar.pv_w });
    const seenPv = samples.filter((s) => s.pv).map((s) => s.pv);
    const clip = seenPv.length > 50 ? Math.max(...seenPv) * 1.05 : null;
    const start = new Date(now); start.setSeconds(0, 0);
    start.setMinutes(start.getMinutes() + ((15 - (start.getMinutes() % 15)) % 15));
    const end = new Date(now); end.setDate(end.getDate() + 2); end.setHours(0, 0, 0, 0);
    const pv = pvForecast(hours, cal.factor, start, end, 15, clip);
    const carW = car && car.charging ? car.power_w || 0 : 0;
    const house = houseProfile(samples, Math.min(800, Math.max(250, solar.house_load_w - carW)));
    const batW = batteryPower(samples, input.batMaxW || 3000);
    const carKnown = !!(car && car.soc != null && car.limit);
    const carPresent = !!(carKnown && car.plugged && input.atHome !== false);
    const st = { home: solar.home_soc, car: carKnown ? car.soc : null, limit: carKnown ? car.limit : null };
    const carFrom = carPresent ? null : now;                       // "se la colleghi adesso"
    const opts = { homeKwh: homeKwh || 0, carKwh, maxA, batW };

    const current = STRATEGIES[pref] ? pref : "equilibrio";
    const names = ["casa", "equilibrio", "auto"].concat(current === "max" ? ["max"] : []);
    const results = names.map((n) => simulate(opts, st, pv, house, { name: n, ...STRATEGIES[n] }, carFrom));
    const cur = results.find((r) => r.rule.name === current);
    const best = bestOf(results.filter((r) => r.rule.mode === "solar"), current);

    const tomorrow = new Date(now); tomorrow.setDate(now.getDate() + 1);
    const sum = (day) => pv.filter((p) => dayKey(p.t) === day).reduce((a, p) => a + p.w, 0) * DT_H / 1000;
    const pvTodayLeft = sum(dayKey(now)), pvTomorrow = sum(dayKey(tomorrow));
    const advice = [];

    // 1. preferenza migliore
    if (best.rule.name !== current) {
      let gain;
      if (best.both && (!cur.both || cur.both - best.both >= STEP_MS)) {
        gain = `entrambe le batterie piene ${whenText(best.both, now)} invece che ${whenText(cur.both, now)}`;
      } else {
        const vs = (a, b) => `${al(a)} invece che ${al(b)}`.replace("invece che al ", "invece del ").replace("invece che all'", "invece dell'");
        gain = `domani sera ${kwh1(best.storedEnd - cur.storedEnd)} kWh in più nelle due batterie: casa ${vs(best.homeEnd, cur.homeEnd)}`
          + (best.carEnd != null ? `, Tesla ${vs(best.carEnd, cur.carEnd)}` : "");
      }
      if (current === "max" && best.both) gain = `il sole basta: ${gain.replace(/ invece che .*$/, "")}, senza prendere dalla rete`;
      if (best.both || best.storedEnd - cur.storedEnd >= 1 || current === "max") {
        advice.push({ kind: "strategy", title: `Conviene: ${NAMES[best.rule.name]}`, text: `Con il sole previsto, ${gain}.`, pref: best.rule.name });
      }
    }
    const use = advice.length ? best : cur;

    // 2. previsione
    const carTxt = !carKnown ? "la carica della Tesla non è ancora nota"
      : use.carFull ? `la Tesla arriva ${al(st.limit)} ${whenText(use.carFull, now)}`
      : `la Tesla arriva ${al(use.carEnd)} domani sera`;
    const homeTxt = !homeKwh ? "per la batteria di casa inserisci la capacità in Impostazioni"
      : use.homeFull ? `la batteria di casa è piena ${whenText(use.homeFull, now)}`
      : `la batteria di casa arriva ${al(use.homeEnd)} domani sera`;
    advice.push({ kind: "outlook", title: use === cur ? "Previsione" : "Se applichi il consiglio",
      text: `${homeTxt[0].toUpperCase()}${homeTxt.slice(1)}, ${carTxt}.` });

    // 3. auto non collegata
    if (carKnown && !carPresent && st.car < st.limit && pvTodayLeft > 1) {
      const later = simulate(opts, st, pv, house, best.rule, new Date(now.getTime() + 2 * HOUR));
      const lost = best.carKwhToday - later.carKwhToday;
      if (best.carKwhToday >= 0.5) {
        let txt = `Se la colleghi adesso, oggi il sole le dà circa ${kwh1(best.carKwhToday)} kWh.`;
        if (lost >= 0.5) txt += ` Collegandola tra 2 ore ne perdi circa ${kwh1(lost)}.`;
        advice.push({ kind: "plug", title: input.atHome === false ? "Quando torni, collega la Tesla" : "Collega la Tesla", text: txt });
      }
    }

    // 4. energia in avanzo
    if (use.exportAfterFullToday >= 1.5 && use.homeFull) {
      const first = carKnown && use.carFull ? new Date(Math.max(use.homeFull, use.carFull)) : use.homeFull;
      advice.push({ kind: "surplus", title: "Energia in avanzo",
        text: `Dopo le ${hm(first)} avanzeranno circa ${kwh0(use.exportAfterFullToday)} kWh che finirebbero in rete: buon momento per lavatrice, lavastoviglie o forno.` });
    }

    // 5. domani e la notte
    if (carKnown && pvTomorrow >= 1) {
      const tonight = use.steps.filter((s) => dayKey(s.t) === dayKey(now));
      const socTonight = tonight.length && tonight[tonight.length - 1].car_soc != null ? tonight[tonight.length - 1].car_soc : st.car;
      let txt;
      if (socTonight >= st.limit - 0.5) txt = `Domani previsti circa ${kwh0(pvTomorrow)} kWh di sole; la Tesla sarà già carica.`;
      else if (use.carFull && dayKey(use.carFull) === dayKey(tomorrow)) {
        txt = `Domani previsti circa ${kwh0(pvTomorrow)} kWh di sole: la Tesla arriva ${al(st.limit)} ${whenText(use.carFull, now)}. Non serve caricare da rete stanotte.`;
      } else {
        const target = Math.min(st.limit, socTonight + (st.limit - (use.carEnd ?? socTonight)));
        txt = `Domani solo circa ${kwh0(pvTomorrow)} kWh di sole: la Tesla arriva ${al(use.carEnd ?? socTonight)}. ` +
          `Per arrivare ${al(st.limit)}, stanotte carica da rete fino ${al(target)} (Massima, con il limite ${al(target)}): al resto pensa il sole.`;
      }
      advice.push({ kind: "tomorrow", title: "Stanotte e domani", text: txt });
    }

    // curva del sole di oggi e domani, ogni 30 minuti
    const day0 = new Date(now); day0.setHours(0, 0, 0, 0);
    const curve = pvForecast(hours, cal.factor, day0, end, 30, clip);
    return {
      calibration: cal, pvTodayLeft, pvTomorrow,
      pvToday: curve.filter((p) => dayKey(p.t) === dayKey(now)).reduce((a, p) => a + p.w, 0) * 0.5 / 1000,
      curve, carKnown, carPresent,
      milestones: milestones(cur, st, current, homeKwh, carKnown, now),
      track: cur.steps.map((s) => ({ t: s.t, home: s.home, car: s.car_soc, carW: s.car })),
      startHome: st.home, startCar: st.car,
      eta: { home: homeKwh ? cur.homeFull : null, car: carKnown ? cur.carFull : null,
        homeText: homeKwh ? whenText(cur.homeFull, now) : null, carText: carKnown ? whenText(cur.carFull, now) : null,
        homeEnd: cur.homeEnd, carEnd: cur.carEnd },
      advice, current, best: best.rule.name,
      results: results.map((r) => ({ name: r.rule.name, both: r.both, home: r.homeFull, car: r.carFull, storedEnd: r.storedEnd })),
    };
  }

  /** Orari in cui le due batterie arrivano ai loro traguardi, con la preferenza scelta. */
  function milestones(r, st, pref, homeKwh, carKnown, now) {
    const first = (pred) => { const s = r.steps.find(pred); return s ? s.t : null; };
    const home = [];
    if (homeKwh) {
      const targets = pref === "equilibrio" ? [80, 100] : [100];
      for (const soc of targets) {
        const done = soc === 100 ? st.home >= 99.5 : st.home >= soc;
        const at = done ? null : first((s) => s.home >= (soc === 100 ? 99.5 : soc));
        home.push({ soc, done, at, text: done ? "già" : at ? whenText(at, now) : "non entro domani" });
      }
    }
    let car = null;
    if (carKnown) {
      const done = st.car >= st.limit - 0.1;
      const today = r.steps.filter((s) => dayKey(s.t) === dayKey(now) && s.car_soc != null);
      car = { soc: st.limit, done, at: done ? null : r.carFull,
        text: done ? "già" : r.carFull ? whenText(r.carFull, now) : "non entro domani",
        tonight: today.length ? today[today.length - 1].car_soc : st.car, end: r.carEnd,
        chargingFrom: first((s) => s.car > 0) };
    }
    return { home, car, pref };
  }

  return { gtiAt, calibrate, pvForecast, houseProfile, batteryPower, simulate, plan, whenText, STRATEGIES, NAMES, hourKey };
})();
if (typeof module !== "undefined") module.exports = Planner;
