package app.caricasolare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class AutoEngineTest {

    private static final class MemoryStore implements AutoEngine.Store {
        final Map<String, Object> data = new HashMap<>();

        @Override
        public String getString(String key, String def) {
            Object v = data.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override
        public long getLong(String key, long def) {
            Object v = data.get(key);
            return v instanceof Number ? ((Number) v).longValue() : def;
        }

        @Override
        public int getInt(String key, int def) {
            Object v = data.get(key);
            return v instanceof Number ? ((Number) v).intValue() : def;
        }

        @Override
        public float getFloat(String key, float def) {
            Object v = data.get(key);
            return v instanceof Number ? ((Number) v).floatValue() : def;
        }

        @Override
        public boolean getBoolean(String key, boolean def) {
            Object v = data.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override
        public void putString(String key, String val) {
            data.put(key, val);
        }

        @Override
        public void putLong(String key, long val) {
            data.put(key, val);
        }

        @Override
        public void putInt(String key, int val) {
            data.put(key, val);
        }

        @Override
        public void putFloat(String key, float val) {
            data.put(key, val);
        }

        @Override
        public void putBoolean(String key, boolean val) {
            data.put(key, val);
        }
    }

    private static final class FakeGateway implements AutoEngine.Gateway {
        String pref = "casa";
        double maxAmps = 16;
        double batMaxW = 3000.0d;
        double costUsd = 0.25d;
        Double homeLat = 45.4642d;
        Double homeLon = 9.1900d;

        JSONObject solaxResp;
        JSONObject stateResp;
        JSONObject wakeResp;

        int stateCalls = 0;
        int wakeCalls = 0;
        final List<String> commands = new ArrayList<>();
        final List<JSONObject> samples = new ArrayList<>();
        String failCommandWith = null;
        int failCommandTimes = 0;

        @Override
        public JSONObject config() throws Exception {
            return new JSONObject()
                    .put("solax_ready", true)
                    .put("tesla_ready", true)
                    .put("logged_in", true)
                    .put("pref", pref)
                    .put("max_amps", (int) maxAmps)
                    .put("home_lat", homeLat == null ? JSONObject.NULL : homeLat)
                    .put("home_lon", homeLon == null ? JSONObject.NULL : homeLon);
        }

        @Override
        public JSONObject solax() {
            return solaxResp;
        }

        @Override
        public double batteryMaxW() {
            return batMaxW;
        }

        @Override
        public JSONObject stats() throws Exception {
            return new JSONObject().put("cost", costUsd);
        }

        @Override
        public JSONObject vehicleState() {
            stateCalls++;
            return stateResp;
        }

        @Override
        public JSONObject wakeAndRead(long timeoutMs) {
            wakeCalls++;
            if (wakeResp != null && wakeResp.optBoolean("online")) {
                stateResp = wakeResp;
            }
            return wakeResp;
        }

        @Override
        public JSONObject command(String name, int value, boolean autoWake) throws Exception {
            if (failCommandWith != null && failCommandTimes > 0) {
                failCommandTimes--;
                throw new Exception(failCommandWith);
            }
            commands.add(name + ":" + value);
            return new JSONObject().put("ok", true).put("reason", "");
        }

        @Override
        public void addSample(JSONObject sample) {
            samples.add(sample);
        }
    }

    private FakeGateway gw;
    private MemoryStore st;
    private AutoEngine engine;
    private long baseNoon;

    @Before
    public void setUp() {
        gw = new FakeGateway();
        st = new MemoryStore();
        st.putBoolean("enabled", true);
        engine = new AutoEngine(gw, st);

        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.JUNE, 15, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        baseNoon = cal.getTimeInMillis();
    }

    private static JSONObject solax(long ts, double pv, double grid, double bat, double homeSoc) throws Exception {
        return new JSONObject()
                .put("timestamp", ts)
                .put("pv_w", pv)
                .put("grid_w", grid)
                .put("battery_w", bat)
                .put("home_soc", homeSoc)
                .put("house_load_w", Math.max(0.0d, pv - grid - bat));
    }

    private static JSONObject awakeCar(boolean plugged, boolean charging, int amps, int soc, double lat, double lon) throws Exception {
        return new JSONObject()
                .put("online", true)
                .put("plugged", plugged)
                .put("charging", charging)
                .put("amps", charging ? amps : 0)
                .put("request_amps", amps)
                .put("max_amps", 16)
                .put("soc", soc)
                .put("limit", 80)
                .put("power_w", charging ? amps * 230.0d : 0.0d)
                .put("lat", lat)
                .put("lon", lon);
    }

    @Test
    public void wakesCarAndStartsChargingInSameCycleWhenHouseBatteryFinishes() throws Exception {
        gw.pref = "casa";
        // La Tesla dorme dall'inizio della mattina
        gw.stateResp = new JSONObject().put("online", false);
        gw.wakeResp = awakeCar(true, false, 8, 52, 45.4642d, 9.1900d);

        // 12:00 — batteria di casa all'85%: la Tesla dorme e non viene svegliata
        long t0 = baseNoon;
        gw.solaxResp = solax(t0 - 30_000L, 3800, 100, 3200, 85);
        String r0 = engine.cycleAt(t0, false);
        assertTrue(r0.contains("aspetto la batteria di casa al 95%"));
        assertEquals(0, gw.wakeCalls);
        assertTrue(gw.commands.isEmpty());

        // 12:05 — batteria di casa arriva al 96% e rallenta: 2500 W finiscono in rete!
        long t1 = t0 + 5 * AutoEngine.MIN;
        gw.solaxResp = solax(t1 - 20_000L, 3800, 2500, 700, 96);
        String r1 = engine.cycleAt(t1, false);
        assertTrue(r1.contains("confermo al prossimo controllo"));
        assertEquals(0, gw.wakeCalls);

        // 12:10 — sole confermato: sveglia la Tesla E avvia subito la carica nello stesso ciclo!
        long t2 = t1 + 5 * AutoEngine.MIN;
        gw.solaxResp = solax(t2 - 20_000L, 3800, 2500, 700, 96);
        String r2 = engine.cycleAt(t2, false);
        assertEquals(1, gw.wakeCalls);
        // avail = 2500 - 100 + 400 (lean al 96%) = 2800 W -> 12 A
        assertEquals(List.of("amps:12", "start:0"), gw.commands);
        assertTrue(r2.contains("Tesla svegliata"));
        assertTrue(r2.contains("Avvio a 12 A"));
        assertTrue(st.getBoolean("started_by_auto", false));
    }

    @Test
    public void recoversAutomaticallyWhenCachedOnlineCarActuallyFellAsleepBeforeSwitch() throws Exception {
        gw.pref = "equilibrio";
        long t0 = baseNoon;

        // 12:00 — auto letta sveglia e collegata, batteria di casa al 76% (carica tutta in batteria)
        gw.stateResp = awakeCar(true, false, 6, 50, 45.4642d, 9.1900d);
        gw.solaxResp = solax(t0 - 20_000L, 3100, 50, 2600, 76);
        String r0 = engine.cycleAt(t0, false);
        assertEquals(1, gw.stateCalls);
        assertTrue(r0.contains("Sole libero"));
        assertEquals(0, gw.wakeCalls);

        // Nel frattempo la Tesla si addormenta, ma non sono ancora passati 30 minuti dall'ultima lettura!
        // 12:10 — batteria di casa sale all'82%: ora 2600 W della batteria + 200 W rete passano all'auto
        long t1 = t0 + 10 * AutoEngine.MIN;
        gw.solaxResp = solax(t1 - 20_000L, 3200, 200, 2600, 82);
        String r1 = engine.cycleAt(t1, false);
        assertTrue(r1.contains("confermo al prossimo controllo"));
        assertEquals(1, gw.stateCalls); // usa ancora la cache

        // 12:15 — prova a mandare amps, ma la Tesla dorme (HTTP 408) -> la sveglia e avvia subito!
        gw.failCommandWith = "L'auto dorme (premi Sveglia)";
        gw.failCommandTimes = 1;
        gw.wakeResp = awakeCar(true, false, 6, 50, 45.4642d, 9.1900d);

        long t2 = t1 + 5 * AutoEngine.MIN;
        gw.solaxResp = solax(t2 - 20_000L, 3200, 200, 2600, 82);
        String r2 = engine.cycleAt(t2, false);

        assertEquals(1, gw.wakeCalls);
        // avail = 200 + 2600 - 100 = 2700 W -> 11 A
        assertEquals(List.of("amps:11", "start:0"), gw.commands);
        assertTrue(r2.contains("Tesla svegliata"));
        assertTrue(r2.contains("Avvio a 11 A"));
    }

    @Test
    public void recoversWhenAmpsAdjustmentHitsSleepingCar() throws Exception {
        gw.pref = "auto";
        long t0 = baseNoon;

        // Auto registrata come in carica a 6 A, poi si è fermata/addormentata
        st.putString("car", awakeCar(true, true, 6, 55, 45.4642d, 9.1900d).toString());
        st.putLong("last_read", t0 - 2 * AutoEngine.MIN);
        st.putLong("last_awake_read", t0 - 2 * AutoEngine.MIN);
        st.putBoolean("started_by_auto", true);

        // Il sole sale: AutoEngine vuole alzare da 6 A a 13 A ("amps"), riceve 408, sveglia e riavvia!
        gw.failCommandWith = "L'auto dorme (premi Sveglia)";
        gw.failCommandTimes = 1;
        gw.wakeResp = awakeCar(true, false, 6, 55, 45.4642d, 9.1900d);
        gw.solaxResp = solax(t0 - 15_000L, 3600, 1800, 0, 85);

        String res = engine.cycleAt(t0, false);
        assertEquals(1, gw.wakeCalls);
        // Da sveglia (charging=false), avail = 1800 - 100 = 1700 W -> 7 A -> amps:7 + start:0
        assertEquals(List.of("amps:7", "start:0"), gw.commands);
        assertTrue(res.contains("Tesla svegliata"));
    }

    @Test
    public void expiresOldUnpluggedStateSoPluggedSleepingCarWakesUp() throws Exception {
        gw.pref = "equilibrio";
        long t0 = baseNoon;

        // Mattina: auto letta SCOLLEGATA, poi il proprietario collega il cavo e l'auto si addormenta
        gw.stateResp = awakeCar(false, false, 10, 45, 45.4642d, 9.1900d);
        gw.solaxResp = solax(t0 - 20_000L, 1200, 0, 900, 50);
        engine.cycleAt(t0, false);
        assertEquals(1, gw.stateCalls);

        // Dopo 15 minuti l'auto si è addormentata: essendo < 45 min, se dorme ancora evita risvegli inutili
        gw.stateResp = new JSONObject().put("online", false);
        long t15 = t0 + 15 * AutoEngine.MIN;
        gw.solaxResp = solax(t15 - 20_000L, 3500, 2200, 500, 85);
        String r15 = engine.cycleAt(t15, false);
        assertEquals("Tesla addormentata e non collegata", r15);
        assertEquals(0, gw.wakeCalls);

        // Passati 50 minuti (> NEGATIVE_STATE_TTL_MS = 45 min), il blocco "non collegata" scade:
        // quando la batteria di casa è all'85% e c'è sole, verifica svegliando l'auto e avvia la carica!
        gw.wakeResp = awakeCar(true, false, 10, 45, 45.4642d, 9.1900d);
        long t50 = t0 + 50 * AutoEngine.MIN;
        gw.solaxResp = solax(t50 - 20_000L, 3500, 2200, 500, 85);
        engine.cycleAt(t50, false); // conferma 1/2

        long t55 = t0 + 55 * AutoEngine.MIN;
        gw.solaxResp = solax(t55 - 20_000L, 3500, 2200, 500, 85);
        String r55 = engine.cycleAt(t55, false); // conferma 2/2 -> sveglia e carica!
        assertEquals(1, gw.wakeCalls);
        // avail = 2200 + 500 - 100 = 2600 W -> 11 A
        assertEquals(List.of("amps:11", "start:0"), gw.commands);
        assertTrue(r55.contains("Avvio a 11 A"));
    }

    @Test
    public void forceCycleWakesAndStartsImmediatelyInSingleStep() throws Exception {
        gw.pref = "auto";
        long t0 = baseNoon;
        gw.stateResp = new JSONObject().put("online", false);
        gw.wakeResp = awakeCar(true, false, 16, 40, 45.4642d, 9.1900d);
        gw.solaxResp = solax(t0 - 30_000L, 3400, 400, 2400, 75);

        // L'utente preme "Auto prima" (force = true): sveglia e parte subito in 1 solo ciclo!
        String res = engine.cycleAt(t0, true);
        assertEquals(1, gw.wakeCalls);
        // avail = 400 + 2400 - 100 = 2700 W -> 11 A
        assertEquals(List.of("amps:11", "start:0"), gw.commands);
        assertTrue(res.contains("Avvio a 11 A"));
    }

    @Test
    public void slowWakeCompletesOnNextCycleEvenIfSolaxTimestampDidNotAdvance() throws Exception {
        gw.pref = "equilibrio";
        long t0 = baseNoon;

        // 1° tentativo: la Tesla impiega più di 45s a prendere rete (wakeAndRead restituisce online=false)
        gw.stateResp = new JSONObject().put("online", false);
        gw.wakeResp = new JSONObject().put("online", false);

        gw.solaxResp = solax(t0 - 30_000L, 3500, 2200, 500, 85);
        engine.cycleAt(t0, false); // 1/2

        long t1 = t0 + 5 * AutoEngine.MIN;
        long sameSolaxTs = t1 - 20_000L;
        gw.solaxResp = solax(sameSolaxTs, 3500, 2200, 500, 85);
        String pending = engine.cycleAt(t1, false); // 2/2 -> invia wake, ancora in risveglio
        assertEquals(1, gw.wakeCalls);
        assertTrue(pending.contains("in risveglio"));
        assertTrue(st.getBoolean("force_read", false));

        // 5 minuti dopo la Tesla si è svegliata, anche se l'inverter SolaX ha ancora lo stesso uploadTime!
        gw.stateResp = awakeCar(true, false, 8, 50, 45.4642d, 9.1900d);
        long t2 = t1 + 5 * AutoEngine.MIN;
        gw.solaxResp = solax(sameSolaxTs, 3500, 2200, 500, 85);
        String started = engine.cycleAt(t2, false);
        assertFalse(started.startsWith("In attesa"));
        assertEquals(List.of("amps:11", "start:0"), gw.commands);
    }

    @Test
    public void stopOnAlreadySleepingCarDoesNotWakeIt() throws Exception {
        gw.pref = "equilibrio";
        long t0 = baseNoon;

        st.putString("car", awakeCar(true, true, 5, 60, 45.4642d, 9.1900d).toString());
        st.putLong("last_read", t0 - AutoEngine.MIN);
        st.putLong("last_awake_read", t0 - AutoEngine.MIN);
        st.putBoolean("started_by_auto", true);
        st.putInt("stop_count", 2); // prossimo ciclo senza sole è il 3/3 -> stop

        gw.failCommandWith = "L'auto dorme (premi Sveglia)";
        gw.failCommandTimes = 1;
        gw.solaxResp = solax(t0 - 10_000L, 300, -900, 0, 80);

        String res = engine.cycleAt(t0, false);
        assertEquals(0, gw.wakeCalls);
        assertTrue(res.contains("già fermata e addormentata"));
        assertFalse(st.getBoolean("started_by_auto", true));
    }
}

