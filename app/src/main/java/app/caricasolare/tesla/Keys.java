package app.caricasolare.tesla;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;

public final class Keys {
    private static final byte[] SPKI_PREFIX = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200");
    public final ECPrivateKey privateKey;
    public final byte[] publicBytes;

    private Keys(ECPrivateKey privateKey, byte[] publicBytes) {
        this.privateKey = privateKey;
        this.publicBytes = publicBytes;
    }

    public static ECParameterSpec p256() throws Exception {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    public static Keys fromPem(String pemText) throws Exception {
        byte[] der = Base64.getDecoder().decode(pemText.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
        if (!pemText.contains("EC PRIVATE KEY")) {
            throw new IllegalArgumentException("serve una chiave 'EC PRIVATE KEY'");
        }
        int[] pos = {0};
        expect(der, pos, 48);
        readLength(der, pos);
        expect(der, pos, 2);
        int verLen = readLength(der, pos);
        pos[0] += verLen;
        expect(der, pos, 4);
        int privLen = readLength(der, pos);
        byte[] privScalar = new byte[privLen];
        System.arraycopy(der, pos[0], privScalar, 0, privLen);
        pos[0] += privLen;

        byte[] pubBytes = null;
        while (pos[0] < der.length) {
            int tag = der[pos[0]++] & 255;
            int len = readLength(der, pos);
            if (tag == 161) {
                int[] inner = {pos[0]};
                expect(der, inner, 3);
                int bitLen = readLength(der, inner) - 1;
                byte[] extracted = new byte[bitLen];
                System.arraycopy(der, inner[0] + 1, extracted, 0, bitLen);
                pubBytes = extracted;
            }
            pos[0] += len;
        }
        if (pubBytes == null || pubBytes.length != 65 || pubBytes[0] != 4) {
            throw new IllegalArgumentException("chiave pubblica mancante nel PEM");
        }
        ECPrivateKey priv = (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, privScalar), p256()));
        return new Keys(priv, pubBytes);
    }

    public static String generatePem() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        byte[] priv32 = fixed32(((ECPrivateKey) kp.getPrivate()).getS());
        byte[] pub65 = uncompressed(((ECPublicKey) kp.getPublic()).getW());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(hex("30770201010420"));
        out.write(priv32);
        out.write(hex("a00a06082a8648ce3d030107a144034200"));
        out.write(pub65);
        return pem("EC PRIVATE KEY", out.toByteArray());
    }

    public String publicPem() {
        byte[] spki = new byte[SPKI_PREFIX.length + this.publicBytes.length];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(this.publicBytes, 0, spki, SPKI_PREFIX.length, this.publicBytes.length);
        return pem("PUBLIC KEY", spki);
    }

    public String publicHex() {
        StringBuilder sb = new StringBuilder();
        for (byte b : this.publicBytes) {
            sb.append(String.format("%02x", b & 255));
        }
        return sb.toString();
    }

    private static byte[] uncompressed(ECPoint point) {
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(fixed32(point.getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed32(point.getAffineY()), 0, out, 33, 32);
        return out;
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copyLen = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copyLen, out, 32 - copyLen, copyLen);
        return out;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] hex(String s) {
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static ECPublicKey publicFromBytes(byte[] raw) throws Exception {
        if (raw == null || raw.length != 65 || raw[0] != 4) {
            throw new IllegalArgumentException("chiave pubblica dell'auto non valida");
        }
        byte[] x = new byte[32];
        byte[] y = new byte[32];
        System.arraycopy(raw, 1, x, 0, 32);
        System.arraycopy(raw, 33, y, 0, 32);
        ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, p256()));
    }

    private static void expect(byte[] der, int[] pos, int expectedTag) {
        int idx = pos[0]++;
        if ((der[idx] & 255) != expectedTag) {
            throw new IllegalArgumentException("PEM non valido");
        }
    }

    private static int readLength(byte[] der, int[] pos) {
        int first = der[pos[0]++] & 255;
        if (first < 128) {
            return first;
        }
        int numBytes = first & 127;
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (der[pos[0]++] & 255);
        }
        return length;
    }
}
