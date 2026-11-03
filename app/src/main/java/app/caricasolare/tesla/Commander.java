package app.caricasolare.tesla;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Commander {
    private static final Pattern RESPONSE = Pattern.compile("\"response\"\\s*:\\s*\"([^\"]*)\"");
    private final String baseUrl;
    private int commandsSent;
    private final VehicleSession session;
    private final TokenSource tokens;
    private final String vin;

    public interface TokenSource {
        String token() throws Exception;
    }

    public Commander(String baseUrl, String vin, Keys keys, TokenSource tokens) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.vin = vin;
        this.tokens = tokens;
        this.session = new VehicleSession(keys, vin);
    }

    public int calls() {
        return this.commandsSent;
    }

    public synchronized VehicleSession.Result send(byte[] action) throws Exception {
        if (!this.session.ready()) {
            openSession();
        }
        for (int i = 0; i < 2; i++) {
            byte[][] signed = this.session.signCommand(action, VehicleSession.FLAG_ENCRYPT_RESPONSE);
            VehicleSession.Result res = this.session.readResponse(post(signed[0]), signed[1]);
            if (!res.retry) {
                return res;
            }
        }
        throw new VehicleSession.TeslaException("L'auto continua a chiedere una nuova sessione: riprova tra poco", true);
    }

    private void openSession() throws Exception {
        byte[][] req = this.session.sessionRequest();
        this.session.acceptSession(post(req[0]), req[1]);
    }

    private byte[] post(byte[] routableMsg) throws Exception {
        this.commandsSent++;
        HttpURLConnection conn = (HttpURLConnection) new URL(this.baseUrl + "/api/1/vehicles/" + this.vin + "/signed_command").openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + this.tokens.token());
        String body = "{\"routable_message\":\"" + Base64.getEncoder().encodeToString(routableMsg) + "\"}";
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String respBody = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code == 408) {
            throw new VehicleSession.TeslaException("L'auto dorme o non è raggiungibile: svegliala e riprova", true);
        }
        if (code == 401) {
            throw new VehicleSession.TeslaException("Accesso Tesla scaduto: rifai l'accesso dalle impostazioni", false);
        }
        if (code == 403) {
            throw new VehicleSession.TeslaException("Permesso negato da Tesla (controlla gli scope dell'app)", false);
        }
        if (code == 429) {
            throw new VehicleSession.TeslaException("Troppe richieste a Tesla: riprova tra qualche minuto", true);
        }
        if (code >= 400) {
            throw new VehicleSession.TeslaException("Tesla ha risposto " + code + ": " + shorten(respBody), code >= 500);
        }
        Matcher matcher = RESPONSE.matcher(respBody);
        if (!matcher.find()) {
            throw new VehicleSession.TeslaException("Risposta Tesla inattesa: " + shorten(respBody), false);
        }
        return Base64.getDecoder().decode(matcher.group(1).replace("\\/", "/"));
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static String shorten(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}

