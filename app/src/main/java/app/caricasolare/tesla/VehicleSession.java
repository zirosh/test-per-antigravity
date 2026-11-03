package app.caricasolare.tesla;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class VehicleSession {
    static final int DOMAIN_INFOTAINMENT = 3;
    public static final int FLAG_ENCRYPT_RESPONSE = 2;
    public static final int LIFETIME_SECONDS = 10;
    private static final SecureRandom RNG = new SecureRandom();

    static final int SIG_HMAC = 6;
    static final int SIG_HMAC_PERSONALIZED = 8;
    static final int SIG_AES_GCM_RESPONSE = 9;

    static final int TAG_SIGNATURE_TYPE = 0;
    static final int TAG_DOMAIN = 1;
    static final int TAG_PERSONALIZATION = 2;
    static final int TAG_EPOCH = 3;
    static final int TAG_EXPIRES_AT = 4;
    static final int TAG_COUNTER = 5;
    static final int TAG_CHALLENGE = 6;
    static final int TAG_FLAGS = 7;
    static final int TAG_REQUEST_HASH = 8;
    static final int TAG_FAULT = 9;
    static final int TAG_END = 255;

    private final Clock clock;
    private long counter;
    private byte[] epoch;
    private final Keys keys;
    private final byte[] routingAddress;
    private byte[] sessionKey;
    private long timeZeroMillis;
    private byte[] vehiclePublic;
    private final byte[] vin;

    public interface Clock {
        long millis();
    }

    public VehicleSession(Keys keys, String vin) {
        this(keys, vin, System::currentTimeMillis);
    }

    public VehicleSession(Keys keys, String vin, Clock clock) {
        this.routingAddress = random(16);
        this.keys = keys;
        this.vin = vin.getBytes(StandardCharsets.US_ASCII);
        this.clock = clock;
    }

    public boolean ready() {
        return this.sessionKey != null;
    }

    public byte[][] sessionRequest() {
        byte[] uuid = random(16);
        byte[] req = new Proto.Writer()
                .message(6, new Proto.Writer().uint(1, DOMAIN_INFOTAINMENT))
                .message(7, new Proto.Writer().bytes(2, this.routingAddress))
                .message(14, new Proto.Writer().bytes(1, this.keys.publicBytes))
                .bytes(51, uuid)
                .toBytes();
        return new byte[][]{req, uuid};
    }

    public void acceptSession(byte[] responseBytes, byte[] challenge) throws Exception {
        Proto.Message msg = Proto.Message.parse(responseBytes);
        checkFault(msg);
        byte[] sessionInfo = msg.bytes(15);
        if (sessionInfo == null) {
            throw new TeslaException("L'auto non ha inviato i dati di sessione", false);
        }
        Proto.Message sigData = msg.msg(13);
        byte[] tag = (sigData == null || sigData.msg(6) == null) ? null : sigData.msg(6).bytes(1);
        if (tag == null) {
            throw new TeslaException("Dati di sessione non firmati dall'auto", false);
        }
        installSession(sessionInfo);
        if (MessageDigest.isEqual(sessionInfoTag(challenge, sessionInfo), tag)) {
            return;
        }
        this.sessionKey = null;
        throw new TeslaException("Firma dei dati di sessione non valida", false);
    }

    private void installSession(byte[] sessionInfoBytes) throws Exception {
        Proto.Message info = Proto.Message.parse(sessionInfoBytes);
        if (info.num(5) == 1) {
            throw new TeslaException("La chiave non è abbinata all'auto", false);
        }
        byte[] pubKey = info.bytes(2);
        if (this.vehiclePublic != null && pubKey != null && !Arrays.equals(pubKey, this.vehiclePublic)) {
            throw new TeslaException("La chiave dell'auto è cambiata", false);
        }
        this.vehiclePublic = pubKey;
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(this.keys.privateKey);
        ka.doPhase(Keys.publicFromBytes(pubKey), true);
        byte[] sharedSecret = ka.generateSecret();
        byte[] padded = new byte[32];
        System.arraycopy(sharedSecret, 0, padded, 32 - sharedSecret.length, sharedSecret.length);
        this.sessionKey = Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(padded), 16);
        this.epoch = info.bytes(3) == null ? new byte[16] : info.bytes(3);
        this.counter = Math.max(this.counter, info.num(1));
        this.timeZeroMillis = this.clock.millis() - (info.num(4) * 1000L);
    }

    public byte[][] signCommand(byte[] payload, int flags) throws Exception {
        if (!ready()) {
            throw new IllegalStateException("sessione non aperta");
        }
        this.counter++;
        long expiresAt = ((this.clock.millis() + LIFETIME_SECONDS * 1000L) - this.timeZeroMillis) / 1000L;
        byte[] tag = commandTag(payload, expiresAt, this.counter, flags);
        byte[] messageBytes = new Proto.Writer()
                .message(6, new Proto.Writer().uint(1, DOMAIN_INFOTAINMENT))
                .message(7, new Proto.Writer().bytes(2, this.routingAddress))
                .bytes(10, payload)
                .message(13, new Proto.Writer()
                        .message(1, new Proto.Writer().bytes(1, this.keys.publicBytes))
                        .message(8, new Proto.Writer()
                                .bytes(1, this.epoch)
                                .uint(2, this.counter)
                                .fixed32(3, expiresAt)
                                .bytes(4, tag)
                                .toBytes())
                        .toBytes())
                .bytes(51, random(16))
                .uint(52, flags)
                .toBytes();
        byte[] reqHash = new byte[tag.length + 1];
        reqHash[0] = (byte) SIG_HMAC_PERSONALIZED;
        System.arraycopy(tag, 0, reqHash, 1, tag.length);
        return new byte[][]{messageBytes, reqHash};
    }

    public static final class Result {
        public final boolean ok;
        public final String reason;
        public final boolean retry;

        Result(boolean ok, String reason, boolean retry) {
            this.ok = ok;
            this.reason = reason;
            this.retry = retry;
        }
    }

    public Result readResponse(byte[] responseBytes, byte[] requestHash) throws Exception {
        Proto.Message msg = Proto.Message.parse(responseBytes);
        Proto.Message statusMsg = msg.msg(12);
        long fault = statusMsg == null ? 0L : statusMsg.num(2);
        if (fault != 0) {
            if ((fault == 5 || fault == 6 || fault == 15 || fault == 17) && msg.bytes(15) != null) {
                byte[] sessionInfo = msg.bytes(15);
                Proto.Message sigData = msg.msg(13);
                byte[] tag = (sigData != null && sigData.msg(6) != null) ? sigData.msg(6).bytes(1) : null;
                if (tag != null) {
                    byte[] prevKey = this.sessionKey;
                    installSession(sessionInfo);
                    if (!MessageDigest.isEqual(sessionInfoTag(requestHash, sessionInfo), tag)
                            && !MessageDigest.isEqual(sessionInfoTag(msg.bytes(50), sessionInfo), tag)) {
                        this.sessionKey = prevKey;
                        throw new TeslaException("Risincronizzazione non valida", false);
                    }
                    return new Result(false, faultText(fault), true);
                }
            }
            throw new TeslaException(faultText(fault), fault == 1 || fault == 2 || fault == 11);
        }

        byte[] payload = msg.bytes(10);
        Proto.Message sigData = msg.msg(13);
        Proto.Message aesGcm = sigData != null ? sigData.msg(9) : null;
        if (aesGcm != null) {
            if (payload == null) {
                payload = new byte[0];
            }
            payload = decrypt(msg, aesGcm, requestHash, payload);
        }
        if (payload == null) {
            return new Result(true, "", false);
        }
        Proto.Message actionStatus = Proto.Message.parse(payload).msg(1);
        if (actionStatus == null || actionStatus.num(1) == 0) {
            return new Result(true, "", false);
        }
        String reason = "";
        Proto.Message resultReason = actionStatus.msg(2);
        if (resultReason != null && resultReason.bytes(1) != null) {
            reason = new String(resultReason.bytes(1), StandardCharsets.UTF_8);
        }
        return new Result(false, reason, false);
    }

    private byte[] decrypt(Proto.Message envelope, Proto.Message aesInfo, byte[] requestHash, byte[] ciphertext) throws Exception {
        Proto.Message fromDest = envelope.msg(7);
        long domain = fromDest == null ? DOMAIN_INFOTAINMENT : fromDest.num(1);
        Proto.Message statusMsg = envelope.msg(12);
        long fault = statusMsg == null ? 0L : statusMsg.num(2);

        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        tlv(meta, TAG_SIGNATURE_TYPE, new byte[]{SIG_AES_GCM_RESPONSE});
        tlv(meta, TAG_DOMAIN, new byte[]{(byte) domain});
        tlv(meta, TAG_PERSONALIZATION, this.vin);
        tlv(meta, TAG_COUNTER, u32(aesInfo.num(2)));
        tlv(meta, TAG_FLAGS, u32(envelope.num(52)));
        tlv(meta, TAG_REQUEST_HASH, requestHash);
        tlv(meta, TAG_FAULT, u32(fault));
        meta.write(TAG_END);

        byte[] aad = MessageDigest.getInstance("SHA-256").digest(meta.toByteArray());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(this.sessionKey, "AES"), new GCMParameterSpec(128, aesInfo.bytes(1)));
        cipher.updateAAD(aad);
        byte[] gcmTag = aesInfo.bytes(3);
        byte[] combined = new byte[ciphertext.length + gcmTag.length];
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(gcmTag, 0, combined, ciphertext.length, gcmTag.length);
        return cipher.doFinal(combined);
    }

    private static byte[] vehicleAction(int actionField, byte[] body) {
        return new Proto.Writer().message(2, new Proto.Writer().message(actionField, body).toBytes()).toBytes();
    }

    public static byte[] chargeStart() {
        return vehicleAction(6, new Proto.Writer().message(2, new byte[0]).toBytes());
    }

    public static byte[] chargeStop() {
        return vehicleAction(6, new Proto.Writer().message(5, new byte[0]).toBytes());
    }

    public static byte[] setChargingAmps(int amps) {
        return vehicleAction(43, new Proto.Writer().int32(1, amps).toBytes());
    }

    public static byte[] setChargeLimit(int percent) {
        return vehicleAction(5, new Proto.Writer().int32(1, percent).toBytes());
    }

    private byte[] subkey(String label) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(this.sessionKey, "HmacSHA256"));
        return mac.doFinal(label.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] sessionInfoTag(byte[] challenge, byte[] sessionInfo) throws Exception {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        tlv(meta, TAG_SIGNATURE_TYPE, new byte[]{SIG_HMAC});
        tlv(meta, TAG_PERSONALIZATION, this.vin);
        tlv(meta, TAG_CHALLENGE, challenge);
        meta.write(TAG_END);
        meta.write(sessionInfo);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(subkey("session info"), "HmacSHA256"));
        return mac.doFinal(meta.toByteArray());
    }

    private byte[] commandTag(byte[] payload, long expiresAt, long counterVal, int flags) throws Exception {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        tlv(meta, TAG_SIGNATURE_TYPE, new byte[]{SIG_HMAC_PERSONALIZED});
        tlv(meta, TAG_DOMAIN, new byte[]{DOMAIN_INFOTAINMENT});
        tlv(meta, TAG_PERSONALIZATION, this.vin);
        tlv(meta, TAG_EPOCH, this.epoch);
        tlv(meta, TAG_EXPIRES_AT, u32(expiresAt));
        tlv(meta, TAG_COUNTER, u32(counterVal));
        if (flags > 0) {
            tlv(meta, TAG_FLAGS, u32(flags));
        }
        meta.write(TAG_END);
        meta.write(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(subkey("authenticated command"), "HmacSHA256"));
        return mac.doFinal(meta.toByteArray());
    }

    private static void tlv(ByteArrayOutputStream out, int tag, byte[] value) {
        if (value == null) {
            return;
        }
        if (value.length > TAG_END) {
            throw new IllegalArgumentException("campo troppo lungo");
        }
        out.write(tag);
        out.write(value.length);
        out.write(value, 0, value.length);
    }

    private static byte[] u32(long v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] random(int len) {
        byte[] b = new byte[len];
        RNG.nextBytes(b);
        return b;
    }

    private static void checkFault(Proto.Message msg) throws TeslaException {
        Proto.Message status = msg.msg(12);
        long fault = status == null ? 0L : status.num(2);
        if (fault != 0) {
            throw new TeslaException(faultText(fault), fault == 1 || fault == 2 || fault == 11);
        }
    }

    static String faultText(long fault) {
        switch ((int) fault) {
            case 1:
                return "Auto occupata, riprova";
            case 2:
                return "L'auto non ha risposto in tempo";
            case 3:
                return "La chiave non è abbinata all'auto (rifai l'abbinamento dal telefono)";
            case 4:
                return "La chiave è stata disattivata nell'auto";
            case 5:
                return "Firma non valida";
            case 6:
                return "Contatore già usato";
            case 7:
                return "Permessi insufficienti per questo comando";
            case 11:
                return "L'auto si sta ancora avviando, riprova tra poco";
            case 12:
                return "Comando inviato al VIN sbagliato";
            case 15:
                return "Sessione scaduta";
            case 17:
                return "Comando scaduto (orologio non allineato)";
            case 21:
                return "Accesso da app disattivato nell'auto";
            default:
                return "L'auto ha rifiutato il comando (codice " + fault + ")";
        }
    }

    public static final class TeslaException extends Exception {
        public final boolean temporary;

        public TeslaException(String message, boolean temporary) {
            super(message);
            this.temporary = temporary;
        }
    }
}

