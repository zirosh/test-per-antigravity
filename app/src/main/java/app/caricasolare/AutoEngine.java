package app.caricasolare;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class AutoEngine {
    private static final double BUDGET_STOP_USD = 9.0d;
    static final int MAX_WAKES_PER_DAY = 10;
    static final long MIN = 60_000L;
    static final long WAKE_WAIT_TIMEOUT_MS = 45_000L;
    static final long WAKE_RETRY_COOLDOWN_MS = 10 * MIN;
    static final long NEGATIVE_STATE_TTL_MS = 45 * MIN;
    static final long POSITIVE_STATE_TTL_MS = 12 * 60 * MIN;

    interface Gateway {
        JSONObject config() throws Exception;
        JSONObject solax() throws Exception;
        double batteryMaxW();
        JSONObject stats() throws Exception;
        JSONObject vehicleState() throws Exception;
        JSONObject wakeAndRead(long timeoutMs) throws Exception;
        JSONObject command(String name, int value, boolean autoWake) throws Exception;
        void addSample(JSONObject sample) throws Exception;
    }

    interface Store {
        String getString(String key, String def);
        long getLong(String key, long def);
        int getInt(String key, int def);
        float getFloat(String key, float def);
        boolean getBoolean(String key, boolean def);
        void putString(String key, String val);
        void putLong(String key, long val);
        void putInt(String key, int val);
        void putFloat(String key, float val);
        void putBoolean(String key, boolean val);
    }

    private static final class ApiGateway implements Gateway {
        private final Api api;

        ApiGateway(Api api) {
            this.api = api;
        }

        @Override
        public JSONObject config() throws Exception {
            return this.api.config();
        }

        @Override
        public JSONObject solax() throws Exception {
            return this.api.solax();
        }

        @Override
        public double batteryMaxW() {
            return this.api.batteryMaxW();
        }

        @Override
        public JSONObject stats() throws Exception {
            return this.api.stats();
        }

        @Override
        public JSONObject vehicleState() throws Exception {
            return this.api.vehicleState();
        }

        @Override
        public JSONObject wakeAndRead(long timeoutMs) throws Exception {
            return this.api.wakeAndRead(timeoutMs);
        }

        @Override
        public JSONObject command(String name, int value, boolean autoWake) throws Exception {
            return this.api.command(name, value, autoWake);
        }

        @Override
        public void addSample(JSONObject sample) throws Exception {
            this.api.addSample(sample);
        }
    }

    private static final class PrefsStore implements Store {
        private final SharedPreferences sp;

        PrefsStore(SharedPreferences sp) {
            this.sp = sp;
        }

        @Override
        public String getString(String key, String def) {
            return this.sp.getString(key, def);
        }

        @Override
        public long getLong(String key, long def) {
            return this.sp.getLong(key, def);
        }

        @Override
        public int getInt(String key, int def) {
            return this.sp.getInt(key, def);
        }

        @Override
        public float getFloat(String key, float def) {
            return this.sp.getFloat(key, def);
        }

        @Override
        public boolean getBoolean(String key, boolean def) {
            return this.sp.getBoolean(key, def);
        }

        @Override
        public void putString(String key, String val) {
            this.sp.edit().putString(key, val).apply();
        }

        @Override
        public void putLong(String key, long val) {
            this.sp.edit().putLong(key, val).apply();
        }

        @Override
        public void putInt(String key, int val) {
            this.sp.edit().putInt(key, val).apply();
        }

        @Override
        public void putFloat(String key, float val) {
            this.sp.edit().putFloat(key, val).apply();
        }

        @Override
        public void putBoolean(String key, boolean val) {
            this.sp.edit().putBoolean(key, val).apply();
        }
    }

    private final Gateway api;
    private final Store st;

    AutoEngine(Context context) {
        this(new ApiGateway(Api.get(context)), new PrefsStore(context.getSharedPreferences("auto", 0)));
    }

    AutoEngine(Gateway api, Store st) {
        this.api = api;
        this.st = st;
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences("auto", 0).getBoolean("enabled", false);
    }

    static void setEnabled(Context context, boolean on) {
        SharedPreferences.Editor ed = context.getSharedPreferences("auto", 0).edit().putBoolean("enabled", on);
        if (on) {
            ed.putInt("start_count", 0)
                    .putInt("stop_count", 0)
                    .putBoolean("force_read", true)
                    .putBoolean("saw_day", false)
                    .putInt("dark_count", 0)
                    .putBoolean("off_at_sunset", false);
        }
        ed.apply();
    }

    synchronized String cycle() {
        return cycle(false);
    }

    synchronized String cycle(boolean force) {
        return cycleAt(System.currentTimeMillis(), force);
    }

    synchronized String cycleAt(long now, boolean force) {
        String runResult;
        try {
            if (force) {
                this.st.putBoolean("force_read", true);
            }
            runResult = run(now, force);
        } catch (Exception e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            runResult = "Errore: " + err;
            log(now, runResult, "warn");
        }
        String finalText = sunset(now, runResult);
        String prevText = this.st.getString("last_text", "");
        if (!finalText.startsWith("In attesa")
                && !finalText.startsWith("Attendo")
                && !sameReason(prevText, finalText)
                && !finalText.equals(this.st.getString("last_logged", ""))) {
            log(now, finalText, "info");
        }
        this.st.putLong("last_at", now);
        this.st.putString("last_text", finalText);
        return finalText;
    }

    private String sunset(long now, String currentText) {
        long lastSolaxTs = this.st.getLong("last_solax_ts", 0L);
        if (lastSolaxTs == this.st.getLong("sunset_checked_ts", -1L)) {
            return currentText;
        }
        this.st.putLong("sunset_checked_ts", lastSolaxTs);
        double pv = this.st.getFloat("last_pv", 0.0f);
        boolean sawDay = this.st.getBoolean("saw_day", false) || pv >= 300.0d;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int darkCount = AutoRules.endOfDay(
                pv,
                cal.get(Calendar.HOUR_OF_DAY),
                sawDay,
                this.st.getInt("dark_count", 0),
                this.st.getBoolean("started_by_auto", false)
        );
        if (darkCount >= 0) {
            this.st.putBoolean("saw_day", sawDay);
            this.st.putInt("dark_count", darkCount);
            return currentText;
        }
        this.st.putBoolean("enabled", false);
        this.st.putBoolean("off_at_sunset", true);
        this.st.putLong("off_at", now);
        String msg = "Tramonto: automatico spento. Domani riaccendilo dall'app";
        log(now, msg, "act");
        return msg;
    }

    private String run(long now, boolean force) throws Exception {
        long realStart = System.currentTimeMillis();
        JSONObject cfg = this.api.config();
        if (!cfg.optBoolean("solax_ready")) {
            return "Mancano i dati SolaX";
        }
        if (!cfg.optBoolean("tesla_ready") || !cfg.optBoolean("logged_in")) {
            return "La Tesla non è ancora collegata all'app";
        }

        String pref = cfg.optString("pref", "equilibrio");
        JSONObject solaxJson;
        try {
            solaxJson = this.api.solax();
        } catch (Exception e) {
            return "Inverter non raggiungibile: " + e.getMessage();
        }

        long solaxTs = solaxJson.getLong("timestamp");
        boolean forceRead = this.st.getBoolean("force_read", false);
        if (!force && !forceRead && solaxTs <= this.st.getLong("last_solax_ts", 0L)) {
            return "In attesa di dati nuovi dall'inverter";
        }
        if (!force && solaxTs < this.st.getLong("last_cmd_at", 0L) + MIN) {
            return "Attendo che l'inverter veda l'ultima modifica";
        }
        if (now - solaxTs > 15 * MIN) {
            return "I dati dell'inverter hanno più di 15 minuti";
        }

        this.st.putLong("last_solax_ts", solaxTs);
        this.st.putFloat("last_pv", (float) solaxJson.optDouble("pv_w"));

        AutoRules.Solar solar = new AutoRules.Solar(
                solaxJson.optDouble("pv_w"),
                solaxJson.optDouble("grid_w"),
                solaxJson.optDouble("battery_w"),
                solaxJson.optDouble("home_soc")
        );
        solar.batMax = this.api.batteryMaxW();
        boolean isDay = solar.pv >= AutoRules.DAY_PV_W;

        AutoRules.Memory memory = memory();
        String cachedCarStr = this.st.getString("car", "");
        JSONObject carJson = cachedCarStr.isEmpty() ? null : new JSONObject(cachedCarStr);

        if (this.api.stats().optDouble("cost") >= BUDGET_STOP_USD) {
            recordSample(solaxJson, carJson, now);
            return "Credito Fleet API del mese quasi finito: mi fermo fino al mese prossimo";
        }

        boolean wasCharging = carJson != null && carJson.optBoolean("charging");
        boolean wasOnlineUnplugged = carJson != null
                && carJson.optBoolean("online", true)
                && !carJson.optBoolean("plugged", true);
        long lastRead = this.st.getLong("last_read", 0L);
        long readInterval;
        if (wasCharging || (isDay && wasOnlineUnplugged)) {
            readInterval = 10 * MIN;
        } else {
            readInterval = isDay ? 30 * MIN : 60 * MIN;
        }

        boolean dueByTime = forceRead || carJson == null || (now - lastRead >= readInterval - 30_000L);
        boolean worthReading = forceRead || carJson == null || "max".equals(pref) || isDay || (wasCharging && memory.startedByAuto);

        if (dueByTime && worthReading) {
            this.st.putLong("last_read", now);
            this.st.putBoolean("force_read", false);
            try {
                JSONObject freshState = this.api.vehicleState();
                if (!freshState.optBoolean("online")) {
                    if (carJson == null) {
                        carJson = new JSONObject();
                    }
                    carJson.put("online", false).put("charging", false);
                } else {
                    this.st.putLong("last_awake_read", now);
                    carJson = freshState;
                }
                this.st.putString("car", carJson.toString());
            } catch (Exception e) {
                log(now, "Lettura Tesla non riuscita: " + e.getMessage(), "warn");
                if (carJson == null) {
                    return "Lettura Tesla non riuscita: " + e.getMessage();
                }
            }
        }

        recordSample(solaxJson, carJson, now);
        AutoRules.Car car = toCar(carJson, cfg);

        if (car != null && !car.online) {
            return asleep(pref, solar, car, memory, now);
        }

        AutoRules.Decision decision = AutoRules.decide(pref, solar, car, memory, force);
        saveMemory(memory);
        if ("none".equals(decision.action)) {
            return decision.text;
        }

        try {
            return executeDecision(decision, car, carJson, memory, now, "");
        } catch (Exception e) {
            this.st.putBoolean("force_read", true);
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("dorme")) {
                if (carJson == null) {
                    carJson = new JSONObject();
                }
                carJson.put("online", false).put("charging", false);
                this.st.putString("car", carJson.toString());
                if ("start".equals(decision.action)) {
                    return asleep(pref, solar, car, memory, now);
                }
            }
            String errMsg = "Comando non riuscito: " + msg;
            log(now, errMsg, "warn");
            return errMsg;
        }
    }

    private String asleep(String pref, AutoRules.Solar solar, AutoRules.Car car, AutoRules.Memory memory, long now) throws Exception {
        boolean knownState = this.st.getString("car", "").contains("\"plugged\"");
        AutoRules.Decision dec = AutoRules.decideAsleep(pref, solar, car, memory, knownState);
        saveMemory(memory);
        if (!"wake".equals(dec.action)) {
            return dec.text;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(now));
        int wakes = today.equals(this.st.getString("wake_day", "")) ? this.st.getInt("wakes", 0) : 0;
        if (wakes >= 3) {
            return "Tesla addormentata: risvegli di oggi finiti (3)";
        }
        long lastWake = this.st.getLong("last_wake", 0L);
        if (now - lastWake < (car != null && car.plugged ? 30 * MIN : 2 * 60 * MIN)) {
            this.st.putBoolean("force_read", true);
            return "Tesla in risveglio, la rileggo al prossimo controllo";
        }
        this.st.putString("wake_day", today);
        this.st.putInt("wakes", wakes + 1);
        this.st.putLong("last_wake", now);
        this.st.putBoolean("force_read", true);
        this.api.wake();
        String msg = dec.text + " · in risveglio (" + (wakes + 1) + "/3 oggi)";
        log(now, msg, "act");
        return msg;
    }

    private String executeDecision(
            AutoRules.Decision decision,
            AutoRules.Car car,
            JSONObject carJson,
            AutoRules.Memory memory,
            long now,
            String prefix
    ) throws Exception {
            AutoRules.Decision decision,
            AutoRules.Car car,
            JSONObject carJson,
            AutoRules.Memory memory,
            long now,
            long realStart,
            String prefix
    ) throws Exception {
        if ("start".equals(decision.action)) {
            if (car.requestAmps != decision.amps) {
                send("amps", decision.amps);
            }
            send("start", 0);
            memory.startedByAuto = true;
            carJson.put("charging", true)
                    .put("amps", decision.amps)
                    .put("request_amps", decision.amps)
                    .put("power_w", decision.amps * AutoRules.VOLTS);
        } else if ("amps".equals(decision.action)) {
            send("amps", decision.amps);
            carJson.put("amps", decision.amps)
                    .put("request_amps", decision.amps)
                    .put("power_w", decision.amps * AutoRules.VOLTS);
        } else if ("stop".equals(decision.action)) {
            send("stop", 0);
            memory.startedByAuto = false;
            carJson.put("charging", false)
                    .put("amps", 0)
                    .put("power_w", 0);
        }
        memory.reset();
        saveMemory(memory);
        this.st.putString("car", carJson.toString());
        this.st.putLong("last_cmd_at", now + Math.max(0L, System.currentTimeMillis() - realStart));
        String fullText = prefix + decision.text;
        log(now, fullText, "act");
        return fullText;
    }

    private static boolean isAsleepError(String msg) {
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("dorme") || lower.contains("408") || lower.contains("avviando");
    }

    private void send(String name, int value) throws Exception {
        JSONObject res = this.api.command(name, value, false);
        if (!res.optBoolean("ok")) {
            throw new Exception("l'auto ha risposto " + res.optString("reason", "no"));
        }
    }

    private AutoRules.Car toCar(JSONObject carJson, JSONObject cfg) {
        if (carJson == null) {
            return null;
        }
        AutoRules.Car car = new AutoRules.Car();
        car.online = carJson.optBoolean("online", true);
        car.plugged = carJson.optBoolean("plugged");
        car.charging = carJson.optBoolean("charging");
        car.soc = carJson.optInt("soc");
        car.limit = carJson.optInt("limit", 80);
        car.amps = carJson.optInt("amps");
        car.requestAmps = carJson.optInt("request_amps");
        car.maxAmps = carJson.optInt("max_amps", cfg.optInt("max_amps", 16));
        car.powerW = carJson.optDouble("power_w", 0.0d);
        if (carJson.has("lat") && !cfg.isNull("home_lat")) {
            car.atHome = distanceM(
                    cfg.optDouble("home_lat"),
                    cfg.optDouble("home_lon"),
                    carJson.optDouble("lat"),
                    carJson.optDouble("lon")
            ) <= 300.0d;
        }
        return car;
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double rad = Math.PI / 180.0d;
        double dLat = (lat2 - lat1) * rad / 2.0d;
        double dLon = (lon2 - lon1) * rad / 2.0d;
        double a = Math.pow(Math.sin(dLat), 2.0d)
                + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.pow(Math.sin(dLon), 2.0d);
        return Math.asin(Math.sqrt(a)) * 1.2742E7d;
    }

    private void recordSample(JSONObject solaxJson, JSONObject carJson, long now) {
        try {
            JSONObject sample = new JSONObject()
                    .put("t", solaxJson.getLong("timestamp"))
                    .put("pv", Math.round(solaxJson.optDouble("pv_w")))
                    .put("bat", Math.round(solaxJson.optDouble("battery_w")))
                    .put("grid", Math.round(solaxJson.optDouble("grid_w")));
            long lastRead = this.st.getLong("last_read", 0L);
            if (carJson != null && now - lastRead < 35 * MIN) {
                long carW = (carJson.optBoolean("online", true) && carJson.optBoolean("charging"))
                        ? Math.round(carJson.optDouble("power_w", 0.0d))
                        : 0L;
                sample.put("car", carW);
            }
            if (carJson != null && now - lastRead < 15 * MIN) {
                double chargingW = carJson.optBoolean("charging") ? carJson.optDouble("power_w", 0.0d) : 0.0d;
                sample.put("house", Math.max(0L, Math.round(solaxJson.optDouble("house_load_w") - chargingW)));
            }
            this.api.addSample(sample);
        } catch (Exception unused) {
        }
    }

    private static boolean sameReason(String a, String b) {
        return a.replaceAll("[0-9.,]+ ?(kW|W|%|A)?", "#")
                .equals(b.replaceAll("[0-9.,]+ ?(kW|W|%|A)?", "#"));
    }

    private AutoRules.Memory memory() {
        AutoRules.Memory m = new AutoRules.Memory();
        m.startCount = this.st.getInt("start_count", 0);
        m.stopCount = this.st.getInt("stop_count", 0);
        m.startedByAuto = this.st.getBoolean("started_by_auto", false);
        m.homeFull = this.st.getBoolean("home_full", false);
        return m;
    }

    private void saveMemory(AutoRules.Memory m) {
        this.st.putInt("start_count", m.startCount);
        this.st.putInt("stop_count", m.stopCount);
        this.st.putBoolean("started_by_auto", m.startedByAuto);
        this.st.putBoolean("home_full", m.homeFull);
    }

    private void log(long now, String text, String kind) {
        try {
            JSONArray arr = new JSONArray(this.st.getString("log", "[]"));
            arr.put(new JSONObject().put("at", now).put("text", text).put("kind", kind));
            this.st.putString("last_logged", text);
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, arr.length() - 40); i < arr.length(); i++) {
                trimmed.put(arr.get(i));
            }
            this.st.putString("log", trimmed.toString());
        } catch (Exception unused) {
        }
    }

    JSONObject status() throws Exception {
        return new JSONObject()
                .put("enabled", this.st.getBoolean("enabled", false))
                .put("last_at", this.st.getLong("last_at", 0L))
                .put("last_text", this.st.getString("last_text", ""))
                .put("next_at", this.st.getLong("next_at", 0L))
                .put("off_at_sunset", this.st.getBoolean("off_at_sunset", false))
                .put("off_at", this.st.getLong("off_at", 0L))
                .put("log", new JSONArray(this.st.getString("log", "[]")));
    }

    void setNextAt(long nextAt) {
        this.st.putLong("next_at", nextAt);
    }

    void manualCommand() {
        this.st.putBoolean("force_read", true);
        this.st.putLong("last_cmd_at", System.currentTimeMillis());
    }
}
