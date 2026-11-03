package app.caricasolare;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import app.caricasolare.tesla.Commander;
import app.caricasolare.tesla.Keys;
import app.caricasolare.tesla.VehicleSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class Api {
    static final String AUTH_URL = "https://auth.tesla.com/oauth2/v3/authorize";
    static final String DEFAULT_REGION = "https://fleet-api.prd.eu.vn.cloud.tesla.com";
    static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String KEY_PATH = "/.well-known/appspecific/com.tesla.3p.public-key.pem";
    static final String SCOPES = "openid offline_access vehicle_device_data vehicle_location vehicle_charging_cmds";
    static final String SOLAX_URL = "https://www.eu.solaxcloud.com/proxyApp/proxy/api/getRealtimeInfo.do";
    static final String TOKEN_URL = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token";

    private static Api instance;
    private static final Object TOKEN_LOCK = new Object();
    private static final Object SAMPLES_LOCK = new Object();

    private final Context app;
    private Commander commander;
    private String commanderKey = "";
    private final SharedPreferences p;

    static synchronized Api get(Context context) {
        if (instance == null) {
            instance = new Api(context.getApplicationContext());
        }
        return instance;
    }

    private Api(Context context) {
        this.app = context;
        this.p = context.getSharedPreferences("config", 0);
    }

    private String s(String key) {
        return this.p.getString(key, "");
    }

    JSONObject config() throws Exception {
        JSONObject cfg = new JSONObject();
        cfg.put("title", s("title").isEmpty() ? "Casa" : s("title"));
        float homeKwh = this.p.getFloat("home_kwh", 0.0f);
        cfg.put("home_kwh", homeKwh > 0.0f ? homeKwh : JSONObject.NULL);
        cfg.put("max_amps", this.p.getInt("max_amps", 16));
        cfg.put("car_kwh", this.p.getFloat("car_kwh", 57.5f));
        cfg.put("solax_token", s("solax_token"));
        cfg.put("solax_sn", s("solax_sn"));
        cfg.put("solax_ready", !s("solax_token").isEmpty() && !s("solax_sn").isEmpty());
        cfg.put("client_id", s("client_id"));
        cfg.put("domain", s("domain"));
        cfg.put("vin", s("vin"));
        cfg.put("has_secret", !s("client_secret").isEmpty());
        cfg.put("has_key", !s("private_key").isEmpty());

        JSONArray missing = new JSONArray();
        if (s("client_id").isEmpty()) missing.put("Client ID");
        if (s("client_secret").isEmpty()) missing.put("Client Secret");
        if (s("domain").isEmpty()) missing.put("dominio");
        if (s("vin").isEmpty()) missing.put("VIN");
        if (s("private_key").isEmpty()) missing.put("chiave privata");

        cfg.put("missing", missing);
        cfg.put("tesla_ready", missing.length() == 0);
        cfg.put("logged_in", !s("refresh_token").isEmpty());
        cfg.put("pref", s("pref").isEmpty() ? "equilibrio" : s("pref"));
        cfg.put("bat_max_kw", s("bat_max_kw").isEmpty() ? 3.0d : Double.parseDouble(s("bat_max_kw")));
        cfg.put("price_kwh", s("price_kwh").isEmpty() ? 0.3d : Double.parseDouble(s("price_kwh")));
        cfg.put("feedin_kwh", s("feedin_kwh").isEmpty() ? 0.0d : Double.parseDouble(s("feedin_kwh")));
        cfg.put("home_lat", num(s("home_lat")));
        cfg.put("home_lon", num(s("home_lon")));
        cfg.put("tilt", s("tilt").isEmpty() ? 30.0d : Double.parseDouble(s("tilt")));
        cfg.put("azimuth", s("azimuth").isEmpty() ? 0.0d : Double.parseDouble(s("azimuth")));
        return cfg;
    }

    void save(JSONObject input) throws Exception {
        SharedPreferences.Editor ed = this.p.edit();
        String privPem = input.optString("private_key", "").trim();
        if (!privPem.isEmpty()) {
            try {
                Keys.fromPem(privPem);
                if (!privPem.equals(s("private_key"))) {
                    ed.putString("private_key", privPem);
                    ed.putBoolean("ok_published", false)
                            .putBoolean("ok_registered", false)
                            .putBoolean("ok_paired", false);
                }
            } catch (Exception e) {
                throw new Exception("Chiave privata non valida: " + e.getMessage());
            }
        }

        String[] strKeys = {"solax_token", "solax_sn", "title", "client_id", "domain", "vin", "pref"};
        for (String k : strKeys) {
            if (input.has(k)) {
                ed.putString(k, input.optString(k, "").trim());
            }
        }

        String secret = input.optString("client_secret", "").trim();
        if (!secret.isEmpty()) {
            ed.putString("client_secret", secret);
        }

        String vin = input.optString("vin", s("vin")).trim().toUpperCase(Locale.ROOT);
        if (!vin.isEmpty() && !vin.matches("[A-HJ-NPR-Z0-9]{17}")) {
            throw new Exception("Il VIN deve avere 17 caratteri");
        }
        ed.putString("vin", vin);

        String domain = input.optString("domain", s("domain")).trim()
                .replaceAll("^https?://", "")
                .replaceAll("/.*$", "");
        ed.putString("domain", domain);
        if (!domain.isEmpty()) {
            ed.putString("redirect_uri", "https://" + domain + "/callback");
        }

        if (input.has("home_lat") || input.has("home_lon")) {
            String latStr = input.optString("home_lat", "").replace(',', '.').trim();
            String lonStr = input.optString("home_lon", "").replace(',', '.').trim();
            if (latStr.isEmpty() && lonStr.isEmpty()) {
                ed.remove("home_lat");
                ed.remove("home_lon");
            } else {
                try {
                    double lat = Double.parseDouble(latStr);
                    double lon = Double.parseDouble(lonStr);
                    if (Math.abs(lat) <= 90.0d && Math.abs(lon) <= 180.0d) {
                        ed.putString("home_lat", String.valueOf(lat))
                                .putString("home_lon", String.valueOf(lon));
                    } else {
                        throw new Exception("Posizione di casa non valida");
                    }
                } catch (NumberFormatException unused) {
                    throw new Exception("Posizione di casa non valida: scrivi latitudine e longitudine");
                }
            }
        }

        if (input.has("tilt")) {
            double tilt = Double.parseDouble(input.optString("tilt", "30").replace(',', '.'));
            if (tilt >= 0.0d && tilt <= 90.0d) {
                ed.putString("tilt", String.valueOf(tilt));
            } else {
                throw new Exception("Inclinazione dei pannelli tra 0 e 90 gradi");
            }
        }

        if (input.has("azimuth")) {
            double az = Double.parseDouble(input.optString("azimuth", "0"));
            if (az >= -180.0d && az <= 180.0d) {
                ed.putString("azimuth", String.valueOf(az));
            } else {
                throw new Exception("Orientamento non valido");
            }
        }

        for (String priceKey : new String[]{"price_kwh", "feedin_kwh"}) {
            String priceStr = input.optString(priceKey, "").replace(',', '.').replace("€", "").trim();
            if (!priceStr.isEmpty()) {
                double val = Double.parseDouble(priceStr);
                if (val < 0.0d || val > 2.0d) {
                    throw new Exception("Prezzo non valido: scrivilo in euro per kWh, per esempio 0,30");
                }
                ed.putString(priceKey, String.valueOf(val));
            }
        }

        String batMaxStr = input.optString("bat_max_kw", "").replace(',', '.').trim();
        if (!batMaxStr.isEmpty()) {
            double batMax = Double.parseDouble(batMaxStr);
            if (batMax >= 0.5d && batMax <= 15.0d) {
                ed.putString("bat_max_kw", String.valueOf(batMax));
            } else {
                throw new Exception("Carica massima della batteria tra 0,5 e 15 kW");
            }
        }

        String homeKwhStr = input.optString("home_kwh", "").replace(',', '.').trim();
        if (!homeKwhStr.isEmpty()) {
            ed.putFloat("home_kwh", Float.parseFloat(homeKwhStr));
        }

        String maxAmpsStr = input.optString("max_amps", "").trim();
        if (!maxAmpsStr.isEmpty()) {
            int maxAmps = Integer.parseInt(maxAmpsStr);
            if (maxAmps < 5 || maxAmps > 32) {
                throw new Exception("Corrente massima tra 5 e 32 A");
            }
            ed.putInt("max_amps", maxAmps);
        }

        ed.apply();
        this.commander = null;
    }

    void importCode(String code) throws Exception {
        String clean = code.replaceAll("\\s", "");
        if (!clean.startsWith("CS1:")) {
            throw new Exception("Codice non riconosciuto: deve iniziare con CS1:");
        }
        try {
            JSONObject src = new JSONObject(new String(Base64.getUrlDecoder().decode(clean.substring(4)), StandardCharsets.UTF_8));
            JSONObject dst = new JSONObject();
            dst.put("solax_token", src.optString("solax_token"));
            dst.put("solax_sn", src.optString("solax_sn"));
            dst.put("title", src.optString("title", "Casa"));
            dst.put("client_id", src.optString("client_id"));
            dst.put("client_secret", src.optString("client_secret"));
            dst.put("domain", src.optString("domain"));
            dst.put("vin", src.optString("vin"));
            dst.put("private_key", src.optString("private_key"));
            if (src.has("home_kwh") && !src.isNull("home_kwh")) {
                dst.put("home_kwh", String.valueOf(src.optDouble("home_kwh")));
            }
            if (src.has("max_amps")) {
                dst.put("max_amps", String.valueOf(src.optInt("max_amps", 16)));
            }
            save(dst);

            SharedPreferences.Editor ed = this.p.edit();
            if (src.has("region")) {
                ed.putString("region", src.optString("region"));
            }
            if (src.has("car_kwh")) {
                ed.putFloat("car_kwh", (float) src.optDouble("car_kwh", 57.5d));
            }
            boolean allReady = !src.optString("private_key").isEmpty()
                    && !src.optString("domain").isEmpty()
                    && !src.optString("vin").isEmpty();
            ed.putBoolean("ok_published", allReady)
                    .putBoolean("ok_registered", allReady)
                    .putBoolean("ok_paired", allReady)
                    .apply();
        } catch (IllegalArgumentException unused) {
            throw new Exception("Codice incompleto: copialo tutto");
        }
    }

    void forget() {
        this.p.edit().clear().apply();
        this.commander = null;
    }

    JSONObject solax() throws Exception {
        String url = SOLAX_URL + "?tokenId=" + enc(s("solax_token")) + "&sn=" + enc(s("solax_sn"));
        JSONObject resp = new JSONObject(http("GET", url, null, null, null)[1]);
        if (!resp.optBoolean("success")) {
            throw new Exception("SolaX: " + resp.optString("result", resp.optString("exception")));
        }
        JSONObject r = resp.getJSONObject("result");
        double pvW = r.optDouble("powerdc1", 0.0d)
                + r.optDouble("powerdc2", 0.0d)
                + optNum(r, "powerdc3")
                + optNum(r, "powerdc4");
        double acW = r.optDouble("acpower", 0.0d);
        double gridW = r.optDouble("feedinpower", 0.0d);

        JSONObject out = new JSONObject();
        out.put("pv_w", pvW);
        out.put("grid_w", gridW);
        out.put("battery_w", r.optDouble("batPower", 0.0d));
        out.put("home_soc", r.optDouble("soc", 0.0d));
        out.put("house_load_w", acW - gridW);
        Date parsed = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(r.optString("uploadTime"));
        out.put("timestamp", parsed == null ? System.currentTimeMillis() : parsed.getTime());

        this.p.edit().putString("last_solax", out.toString()).apply();
        SolarWidget.refresh(this.app);
        return out;
    }

    private static double optNum(JSONObject obj, String key) {
        return obj.isNull(key) ? 0.0d : obj.optDouble(key, 0.0d);
    }

    String region() {
        return s("region").isEmpty() ? DEFAULT_REGION : s("region");
    }

    String loginUrl() throws Exception {
        if (s("client_id").isEmpty() || s("redirect_uri").isEmpty()) {
            throw new Exception("Configura prima Client ID e dominio");
        }
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        this.p.edit().putString("oauth_state", state).apply();
        return AUTH_URL + "?response_type=code&client_id=" + enc(s("client_id"))
                + "&redirect_uri=" + enc(s("redirect_uri"))
                + "&scope=" + enc(SCOPES)
                + "&state=" + state
                + "&prompt=login&locale=it-IT";
    }

    boolean isCallback(String url) {
        String redirect = s("redirect_uri");
        return !redirect.isEmpty() && url.startsWith(redirect);
    }

    void finishLogin(String callbackUrl) throws Exception {
        Uri uri = Uri.parse(callbackUrl.trim());
        String code = uri.getQueryParameter("code");
        String state = uri.getQueryParameter("state");
        if (code == null) {
            throw new Exception("Nell'indirizzo non c'è il codice di accesso");
        }
        if (state == null || !state.equals(s("oauth_state"))) {
            throw new Exception("L'indirizzo non corrisponde all'ultimo accesso avviato: riprova");
        }
        String form = "grant_type=authorization_code&client_id=" + enc(s("client_id"))
                + "&client_secret=" + enc(s("client_secret"))
                + "&code=" + enc(code)
                + "&audience=" + enc(region())
                + "&redirect_uri=" + enc(s("redirect_uri"));
        String[] resp = http("POST", TOKEN_URL, form, "application/x-www-form-urlencoded", null);
        if (!"200".equals(resp[0])) {
            throw new Exception("Accesso non riuscito (" + resp[0] + "): " + shorten(resp[1]));
        }
        saveTokens(new JSONObject(resp[1]));
    }

    private void saveTokens(JSONObject tok) throws Exception {
        this.p.edit()
                .putString("access_token", tok.getString("access_token"))
                .putString("refresh_token", tok.optString("refresh_token", s("refresh_token")))
                .putLong("expires_at", System.currentTimeMillis() + (tok.optLong("expires_in", 28800L) * 1000L))
                .apply();
    }

    String accessToken() throws Exception {
        synchronized (TOKEN_LOCK) {
            return accessTokenLocked();
        }
    }

    private String accessTokenLocked() throws Exception {
        if (s("refresh_token").isEmpty()) {
            throw new Exception("Accesso Tesla da fare (Impostazioni)");
        }
        if (!s("access_token").isEmpty() && this.p.getLong("expires_at", 0L) - System.currentTimeMillis() > 300_000L) {
            return s("access_token");
        }
        String form = "grant_type=refresh_token&client_id=" + enc(s("client_id"))
                + "&refresh_token=" + enc(s("refresh_token"));
        String[] resp = http("POST", TOKEN_URL, form, "application/x-www-form-urlencoded", null);
        if (!"200".equals(resp[0])) {
            if (resp[0].startsWith("4")) {
                this.p.edit().remove("refresh_token").remove("access_token").apply();
            }
            throw new Exception("Accesso Tesla scaduto: rifallo dalle Impostazioni");
        }
        saveTokens(new JSONObject(resp[1]));
        return s("access_token");
    }

    JSONObject vehicleState() throws Exception {
        String url = region() + "/api/1/vehicles/" + s("vin") + "/vehicle_data?endpoints=" + enc("charge_state;location_data");
        String[] resp = http("GET", url, null, null, accessToken());
        JSONObject out = new JSONObject();
        if ("408".equals(resp[0])) {
            out.put("online", false);
            return out;
        }
        if (!"200".equals(resp[0])) {
            throw new Exception(teslaError(resp));
        }
        count("data");
        JSONObject response = new JSONObject(resp[1]).getJSONObject("response");
        JSONObject charge = response.optJSONObject("charge_state");
        JSONObject drive = response.optJSONObject("drive_state");
        if (drive != null && drive.has("latitude") && !drive.isNull("latitude")) {
            out.put("lat", drive.optDouble("latitude"));
            out.put("lon", drive.optDouble("longitude"));
        }
        if (charge == null) {
            throw new Exception("Tesla non ha inviato lo stato di carica");
        }
        String chargingState = charge.optString("charging_state", "");
        int actualCurrent = charge.optInt("charger_actual_current", 0);
        double voltage = charge.optDouble("charger_voltage", 0.0d);
        boolean charging = "Charging".equals(chargingState) || "Starting".equals(chargingState);
        boolean plugged = !chargingState.isEmpty() && !"Disconnected".equals(chargingState);

        out.put("online", true);
        out.put("plugged", plugged);
        out.put("charging", charging);
        out.put("charging_state", chargingState);
        out.put("soc", charge.optInt("battery_level"));
        out.put("limit", charge.optInt("charge_limit_soc"));
        out.put("amps", actualCurrent);
        out.put("request_amps", charge.optInt("charge_current_request"));
        out.put("max_amps", charge.optInt("charge_current_request_max", this.p.getInt("max_amps", 16)));

        double powerW = 0.0d;
        if (charging) {
            powerW = actualCurrent * (voltage < 100.0d ? 230.0d : voltage);
        }
        out.put("power_w", powerW);
        this.p.edit().putString("last_car", out.toString()).apply();
        SolarWidget.refresh(this.app);
        return out;
    }

    boolean wake() throws Exception {
        return wakeInternal(true);
    }

    private boolean wakeInternal(boolean countStat) throws Exception {
        if (countStat) {
            count("wakes");
        }
        String[] resp = http("POST", region() + "/api/1/vehicles/" + s("vin") + "/wake_up", "", "application/json", accessToken());
        if (!"200".equals(resp[0])) {
            throw new Exception(teslaError(resp));
        }
        try {
            JSONObject r = new JSONObject(resp[1]).optJSONObject("response");
            return r != null && "online".equalsIgnoreCase(r.optString("state"));
        } catch (Exception unused) {
            return false;
        }
    }

    JSONObject command(String name, int value) throws Exception {
        return command(name, value, false);
    }

    JSONObject command(String name, int value, boolean autoWake) throws Exception {
        byte[] payload;
        switch (name) {
            case "amps":
                if (value < 1 || value > 48) {
                    throw new Exception("Corrente non valida");
                }
                payload = VehicleSession.setChargingAmps(value);
                break;
            case "stop":
                payload = VehicleSession.chargeStop();
                break;
            case "limit":
                if (value < 50 || value > 100) {
                    throw new Exception("Limite tra 50% e 100%");
                }
                payload = VehicleSession.setChargeLimit(value);
                break;
            case "start":
                payload = VehicleSession.chargeStart();
                break;
            default:
                throw new Exception("Comando sconosciuto");
        }

        Commander cmd = commander();
        int beforeCalls = cmd.calls();
        try {
            VehicleSession.Result res;
            try {
                res = sendWithBootRetry(cmd, payload);
            } catch (VehicleSession.TeslaException te) {
                String msg = te.getMessage() == null ? "" : te.getMessage().toLowerCase(Locale.ROOT);
                if (autoWake && (msg.contains("dorme") || msg.contains("408"))) {
                    JSONObject woke = wakeAndRead(45_000L);
                    if (!woke.optBoolean("online")) {
                        throw te;
                    }
                    res = sendWithBootRetry(cmd, payload);
                } else {
                    throw te;
                }
            }
            JSONObject out = new JSONObject();
            out.put("ok", res.ok || isBenign(name, res.reason));
            out.put("reason", res.reason);
            return out;
        } finally {
            count("commands", cmd.calls() - beforeCalls);
        }
    }

    private static VehicleSession.Result sendWithBootRetry(Commander cmd, byte[] payload) throws Exception {
        try {
            return cmd.send(payload);
        } catch (VehicleSession.TeslaException te) {
            String msg = te.getMessage() == null ? "" : te.getMessage().toLowerCase(Locale.ROOT);
            if (te.temporary && (msg.contains("avviando") || msg.contains("occupata") || msg.contains("tempo"))) {
                Thread.sleep(4500L);
                return cmd.send(payload);
            }
            throw te;
        }
    }

    private static boolean isBenign(String name, String reason) {
        return ("start".equals(name) && "is_charging".equals(reason))
                || ("stop".equals(name) && "not_charging".equals(reason));
    }

    private synchronized Commander commander() throws Exception {
        String key = s("vin") + s("private_key").hashCode() + region();
        if (this.commander == null || !key.equals(this.commanderKey)) {
            if (s("private_key").isEmpty() || s("vin").isEmpty()) {
                throw new Exception("Configura la parte Tesla nelle Impostazioni");
            }
            this.commander = new Commander(region(), s("vin"), Keys.fromPem(s("private_key")), this::accessToken);
            this.commanderKey = key;
        }
        return this.commander;
    }

    JSONObject setupStatus() throws Exception {
        JSONObject out = new JSONObject();
        boolean hasKey = !s("private_key").isEmpty();
        out.put("has_key", hasKey);
        out.put("public_key", hasKey ? Keys.fromPem(s("private_key")).publicPem() : "");
        out.put("domain", s("domain"));
        out.put("key_url", s("domain").isEmpty() ? "" : "https://" + s("domain") + KEY_PATH);
        out.put("published", this.p.getBoolean("ok_published", false));
        out.put("client", !s("client_id").isEmpty() && !s("client_secret").isEmpty());
        out.put("client_id", s("client_id"));
        out.put("registered", this.p.getBoolean("ok_registered", false));
        out.put("logged_in", !s("refresh_token").isEmpty());
        out.put("vin", s("vin"));
        out.put("paired", this.p.getBoolean("ok_paired", false));
        String pairUrl = (!s("domain").isEmpty() && !s("vin").isEmpty())
                ? "https://tesla.com/_ak/" + s("domain") + "?vin=" + s("vin")
                : "";
        out.put("pair_url", pairUrl);
        return out;
    }

    void createKey(boolean force) throws Exception {
        if (!s("private_key").isEmpty() && !force) {
            throw new Exception("Una chiave c'è già");
        }
        this.p.edit()
                .putString("private_key", Keys.generatePem())
                .putBoolean("ok_published", false)
                .putBoolean("ok_registered", false)
                .putBoolean("ok_paired", false)
                .apply();
        this.commander = null;
    }

    void setDomain(String githubUser) throws Exception {
        String user = githubUser.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^@", "")
                .replaceFirst("^https?://", "")
                .replaceFirst("\\.github\\.io.*$", "")
                .replaceFirst("^github\\.com/", "");
        if (!user.matches("[a-z0-9-]{1,39}")) {
            throw new Exception("Nome utente GitHub non valido");
        }
        String domain = user + ".github.io";
        if (domain.equals(s("domain"))) {
            return;
        }
        this.p.edit()
                .putString("domain", domain)
                .putString("redirect_uri", "https://" + domain + "/callback")
                .putBoolean("ok_published", false)
                .putBoolean("ok_registered", false)
                .putBoolean("ok_paired", false)
                .remove("refresh_token")
                .remove("access_token")
                .apply();
    }

    JSONObject checkPublished() throws Exception {
        if (s("private_key").isEmpty() || s("domain").isEmpty()) {
            throw new Exception("Prima crea la chiave e scrivi il nome utente GitHub");
        }
        String[] resp = http("GET", "https://" + s("domain") + KEY_PATH + "?t=" + System.currentTimeMillis(), null, null, null);
        boolean ok = "200".equals(resp[0]) && norm(resp[1]).equals(norm(Keys.fromPem(s("private_key")).publicPem()));
        String why;
        if (ok) {
            why = "";
        } else if ("200".equals(resp[0])) {
            why = "C'è un file, ma la chiave è diversa: ricopiala tutta";
        } else if ("404".equals(resp[0])) {
            why = "File non trovato: controlla nome e cartelle, oppure aspetta ancora un minuto";
        } else {
            why = "Il sito risponde " + resp[0];
        }
        this.p.edit().putBoolean("ok_published", ok).apply();
        return new JSONObject().put("ok", ok).put("why", why);
    }

    private static String norm(String s) {
        return s.replaceAll("\\s", "");
    }

    void saveClient(String id, String secret) throws Exception {
        String cid = id.trim();
        String csec = secret.trim();
        if (cid.isEmpty()) {
            throw new Exception("Incolla il Client ID");
        }
        SharedPreferences.Editor ed = this.p.edit().putString("client_id", cid);
        if (!csec.isEmpty()) {
            ed.putString("client_secret", csec);
        } else if (s("client_secret").isEmpty()) {
            throw new Exception("Incolla il Client Secret");
        }
        ed.putBoolean("ok_registered", false).apply();
    }

    private String partnerToken() throws Exception {
        String form = "grant_type=client_credentials&client_id=" + enc(s("client_id"))
                + "&client_secret=" + enc(s("client_secret"))
                + "&scope=" + enc(SCOPES.replace("offline_access ", ""))
                + "&audience=" + enc(region());
        String[] resp = http("POST", TOKEN_URL, form, "application/x-www-form-urlencoded", null);
        if (!"200".equals(resp[0])) {
            throw new Exception("Tesla ha rifiutato Client ID o Client Secret (" + resp[0]
                    + "). Ricontrollali e verifica che l'app abbia 'Authorization Code and Machine-to-Machine'.");
        }
        return new JSONObject(resp[1]).getString("access_token");
    }

    JSONObject registerDomain() throws Exception {
        if (s("domain").isEmpty() || s("client_id").isEmpty()) {
            throw new Exception("Completa prima i passi 2 e 3");
        }
        String pTok = partnerToken();
        String[] resp = http(
                "POST",
                region() + "/api/1/partner_accounts",
                new JSONObject().put("domain", s("domain")).toString(),
                "application/json",
                pTok
        );
        if (!"200".equals(resp[0])) {
            throw new Exception("Registrazione non riuscita (" + resp[0] + "): " + shorten(resp[1]));
        }
        String[] keyResp = http(
                "GET",
                region() + "/api/1/partner_accounts/public_key?domain=" + enc(s("domain")),
                null,
                null,
                pTok
        );
        String remoteKey = "";
        if ("200".equals(keyResp[0])) {
            JSONObject r = new JSONObject(keyResp[1]).optJSONObject("response");
            if (r != null) {
                remoteKey = r.optString("public_key", "");
            }
        }
        boolean match = remoteKey.isEmpty()
                || remoteKey.toLowerCase(Locale.ROOT).contains(Keys.fromPem(s("private_key")).publicHex());
        this.p.edit().putBoolean("ok_registered", true).apply();
        return new JSONObject().put("ok", true).put("key_match", match);
    }

    JSONArray vehicles() throws Exception {
        String[] resp = http("GET", region() + "/api/1/vehicles", null, null, accessToken());
        if (!"200".equals(resp[0])) {
            throw new Exception(teslaError(resp));
        }
        JSONArray src = new JSONObject(resp[1]).optJSONArray("response");
        JSONArray out = new JSONArray();
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject v = src.getJSONObject(i);
                out.put(new JSONObject()
                        .put("vin", v.optString("vin"))
                        .put("name", v.optString("display_name", "Tesla")));
            }
        }
        if (out.length() == 1) {
            pickVehicle(out.getJSONObject(0).getString("vin"));
        }
        return out;
    }

    void pickVehicle(String vinInput) throws Exception {
        String vin = vinInput.trim().toUpperCase(Locale.ROOT);
        if (!vin.matches("[A-HJ-NPR-Z0-9]{17}")) {
            throw new Exception("VIN non valido");
        }
        if (!vin.equals(s("vin"))) {
            this.p.edit().putString("vin", vin).putBoolean("ok_paired", false).apply();
        }
        this.commander = null;
    }

    JSONObject checkPaired() throws Exception {
        if (s("vin").isEmpty()) {
            throw new Exception("Scegli prima l'auto");
        }
        String body = new JSONObject().put("vins", new JSONArray().put(s("vin"))).toString();
        String[] resp = http("POST", region() + "/api/1/vehicles/fleet_status", body, "application/json", accessToken());
        if (!"200".equals(resp[0])) {
            throw new Exception(teslaError(resp));
        }
        JSONObject r = new JSONObject(resp[1]).optJSONObject("response");
        JSONArray pairedVins = r == null ? null : r.optJSONArray("key_paired_vins");
        boolean paired = false;
        if (pairedVins != null) {
            for (int i = 0; i < pairedVins.length(); i++) {
                paired |= s("vin").equals(pairedVins.optString(i));
            }
        }
        this.p.edit().putBoolean("ok_paired", paired).apply();
        return new JSONObject().put("ok", paired);
    }

    JSONObject forecast(boolean force) throws Exception {
        if (s("home_lat").isEmpty()) {
            throw new Exception("Serve la posizione di casa (Impostazioni > Previsioni)");
        }
        JSONObject cfg = config();
        String cacheKey = s("home_lat") + "," + s("home_lon") + "," + cfg.get("tilt") + "," + cfg.get("azimuth");
        JSONObject cached = s("fc_cache").isEmpty() ? null : new JSONObject(s("fc_cache"));
        if (cached != null
                && cacheKey.equals(cached.optString("key"))
                && System.currentTimeMillis() - cached.optLong("fetched") < 3_600_000L
                && !force) {
            return cached;
        }
        try {
            String url = FORECAST_URL + "?latitude=" + enc(s("home_lat"))
                    + "&longitude=" + enc(s("home_lon"))
                    + "&hourly=global_tilted_irradiance,cloud_cover&tilt=" + cfg.get("tilt")
                    + "&azimuth=" + cfg.get("azimuth")
                    + "&past_days=7&forecast_days=2&timezone=auto";
            String[] resp = http("GET", url, null, null, null);
            if (!"200".equals(resp[0])) {
                throw new Exception("Open-Meteo ha risposto " + resp[0]);
            }
            JSONObject hourly = new JSONObject(resp[1]).getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time");
            JSONArray gti = hourly.getJSONArray("global_tilted_irradiance");
            JSONArray clouds = hourly.optJSONArray("cloud_cover");
            JSONObject hoursMap = new JSONObject();
            JSONObject cloudsMap = new JSONObject();
            for (int i = 0; i < times.length(); i++) {
                if (!gti.isNull(i)) {
                    hoursMap.put(times.getString(i), gti.getDouble(i));
                }
                if (clouds != null && !clouds.isNull(i)) {
                    cloudsMap.put(times.getString(i), clouds.getDouble(i));
                }
            }
            JSONObject result = new JSONObject()
                    .put("key", cacheKey)
                    .put("fetched", System.currentTimeMillis())
                    .put("hours", hoursMap)
                    .put("clouds", cloudsMap);
            this.p.edit().putString("fc_cache", result.toString()).apply();
            return result;
        } catch (Exception e) {
            if (cached == null || !cacheKey.equals(cached.optString("key"))) {
                throw new Exception("Previsioni meteo non disponibili: " + e.getMessage());
            }
            return cached.put("stale", true);
        }
    }

    String kvGet(String key) {
        return s("kv_" + key);
    }

    void kvSet(String key, String value) {
        this.p.edit().putString("kv_" + key, value).apply();
    }

    JSONObject lastSolax() {
        try {
            return s("last_solax").isEmpty() ? null : new JSONObject(s("last_solax"));
        } catch (Exception unused) {
            return null;
        }
    }

    JSONObject lastCar() {
        try {
            return s("last_car").isEmpty() ? null : new JSONObject(s("last_car"));
        } catch (Exception unused) {
            return null;
        }
    }

    double batteryMaxW() {
        double configured = (s("bat_max_kw").isEmpty() ? 3.0d : Double.parseDouble(s("bat_max_kw"))) * 1000.0d;
        synchronized (SAMPLES_LOCK) {
            try {
                JSONArray samples = s("kv_samples").isEmpty() ? new JSONArray() : new JSONArray(s("kv_samples"));
                double seenMax = 0.0d;
                for (int i = 0; i < samples.length(); i++) {
                    seenMax = Math.max(seenMax, samples.getJSONObject(i).optDouble("bat", 0.0d));
                }
                return Math.max(configured, seenMax);
            } catch (Exception unused) {
                return configured;
            }
        }
    }

    void addSample(JSONObject sample) throws Exception {
        synchronized (SAMPLES_LOCK) {
            JSONArray samples = s("kv_samples").isEmpty() ? new JSONArray() : new JSONArray(s("kv_samples"));
            long t = sample.getLong("t");
            JSONObject prev = samples.length() > 0 ? samples.getJSONObject(samples.length() - 1) : null;
            if (prev == null || prev.optLong("t") < t) {
                samples.put(sample);
                long cutoff = System.currentTimeMillis() - 14L * 86_400_000L;
                JSONArray kept = new JSONArray();
                for (int i = Math.max(0, samples.length() - 4000); i < samples.length(); i++) {
                    if (samples.getJSONObject(i).optLong("t") >= cutoff) {
                        kept.put(samples.getJSONObject(i));
                    }
                }
                SharedPreferences.Editor ed = this.p.edit().putString("kv_samples", kept.toString());
                if (prev != null) {
                    addToDays(ed, prev, sample);
                }
                ed.apply();
            }
        }
    }

    private void addToDays(SharedPreferences.Editor ed, JSONObject prev, JSONObject cur) throws Exception {
        Double carW = cur.has("car") ? cur.optDouble("car") : null;
        double[] iv = History.interval(
                prev.optLong("t"),
                cur.optLong("t"),
                cur.optDouble("pv", 0.0d),
                cur.optDouble("grid", 0.0d),
                carW
        );
        if (iv == null || !cur.has("grid")) {
            return;
        }
        JSONObject days = s("kv_days").isEmpty() ? new JSONObject() : new JSONObject(s("kv_days"));
        String dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(cur.optLong("t")));
        JSONObject dayObj = days.optJSONObject(dayKey);
        if (dayObj == null) {
            dayObj = new JSONObject();
        }
        String[] keys = {"sun", "grid", "pv", "exp", "imp"};
        for (int i = 0; i < keys.length; i++) {
            dayObj.put(keys[i], dayObj.optDouble(keys[i], 0.0d) + iv[i]);
        }
        if (carW != null) {
            dayObj.put("car_h", dayObj.optDouble("car_h", 0.0d) + ((cur.optLong("t") - prev.optLong("t")) / 3_600_000.0d));
        }
        days.put(dayKey, dayObj);
        if (days.length() > 400) {
            ArrayList<String> allKeys = new ArrayList<>();
            Iterator<String> it = days.keys();
            while (it.hasNext()) {
                allKeys.add(it.next());
            }
            Collections.sort(allKeys);
            for (int i = 0; i < allKeys.size() - 400; i++) {
                days.remove(allKeys.get(i));
            }
        }
        ed.putString("kv_days", days.toString());
    }

    JSONObject history() throws Exception {
        return new JSONObject()
                .put("days", s("kv_days").isEmpty() ? new JSONObject() : new JSONObject(s("kv_days")))
                .put("price", s("price_kwh").isEmpty() ? 0.3d : Double.parseDouble(s("price_kwh")))
                .put("feedin", s("feedin_kwh").isEmpty() ? 0.0d : Double.parseDouble(s("feedin_kwh")));
    }

    private static Object num(String s) {
        if (s == null || s.isEmpty()) {
            return JSONObject.NULL;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException unused) {
            return JSONObject.NULL;
        }
    }

    private String month() {
        return new SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new Date());
    }

    private void count(String kind) {
        count(kind, 1);
    }

    private synchronized void count(String kind, int delta) {
        String key = "stat_" + month() + "_" + kind;
        this.p.edit().putInt(key, this.p.getInt(key, 0) + delta).apply();
    }

    JSONObject stats() throws Exception {
        String m = month();
        int data = this.p.getInt("stat_" + m + "_data", 0);
        int commands = this.p.getInt("stat_" + m + "_commands", 0);
        int wakes = this.p.getInt("stat_" + m + "_wakes", 0);
        JSONObject out = new JSONObject();
        out.put("month", m);
        out.put("data", data);
        out.put("commands", commands);
        out.put("wakes", wakes);
        out.put("cost", (data / 500.0d) + (commands / 1000.0d) + (wakes / 50.0d));
        return out;
    }

    private static String teslaError(String[] resp) {
        switch (resp[0]) {
            case "401":
                return "Accesso Tesla scaduto: rifallo dalle Impostazioni";
            case "403":
                return "Permesso negato da Tesla";
            case "404":
                return "Auto non trovata: controlla il VIN";
            case "408":
                return "L'auto dorme o non è raggiungibile";
            case "412":
                return "Dominio non registrato presso Tesla (Impostazioni, passo 4)";
            case "429":
                return "Troppe richieste a Tesla: riprova tra qualche minuto";
            default:
                return "Tesla ha risposto " + resp[0] + ": " + shorten(resp[1]);
        }
    }

    static String[] http(String method, String urlStr, String body, String contentType, String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(method);
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String respBody;
        if (stream == null) {
            respBody = "";
        } else {
            try (InputStream in = stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                respBody = out.toString("UTF-8");
            }
        }
        return new String[]{String.valueOf(code), respBody};
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String shorten(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}

