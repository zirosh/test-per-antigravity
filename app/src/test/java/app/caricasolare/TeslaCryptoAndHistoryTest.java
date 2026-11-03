package app.caricasolare;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.caricasolare.tesla.Keys;
import app.caricasolare.tesla.Proto;
import app.caricasolare.tesla.VehicleSession;
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
import org.junit.Test;

public class TeslaCryptoAndHistoryTest {

    @Test
    public void keysGenerateParseAndExportPublicKey() throws Exception {
        String pem = Keys.generatePem();
        assertTrue(pem.contains("BEGIN EC PRIVATE KEY"));

        Keys parsed = Keys.fromPem(pem);
        assertEquals(65, parsed.publicBytes.length);
        assertEquals(4, parsed.publicBytes[0]);
        assertEquals(130, parsed.publicHex().length());
        assertTrue(parsed.publicPem().contains("BEGIN PUBLIC KEY"));
        assertNotNull(Keys.publicFromBytes(parsed.publicBytes));
    }

    @Test
    public void protoWriterAndMessageRoundtrip() {
        byte[] raw = new Proto.Writer()
                .uint(1, 300L)
                .int32(2, -5)
                .fixed32(3, 0x12345678L)
                .bytes(4, new byte[]{1, 2, 3, 4})
                .message(5, new Proto.Writer().uint(1, 42L))
                .toBytes();

        Proto.Message msg = Proto.Message.parse(raw);
        assertTrue(msg.has(1));
        assertFalse(msg.has(99));
        assertEquals(300L, msg.num(1));
        assertEquals(-5L, msg.num(2));
        assertEquals(0x12345678L, msg.num(3));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, msg.bytes(4));
        assertEquals(42L, msg.msg(5).num(1));
    }

    @Test
    public void vehicleSessionEndToEndHandshakeCommandSigningAndEncryptedResponse() throws Exception {
        Keys clientKeys = Keys.fromPem(Keys.generatePem());
        Keys carKeys = Keys.fromPem(Keys.generatePem());
        String vin = "LRW3E7FA0PC123456";
        long fixedNow = 1_750_000_000_000L;

        VehicleSession session = new VehicleSession(clientKeys, vin, () -> fixedNow);
        assertFalse(session.ready());

        byte[][] reqAndUuid = session.sessionRequest();
        Proto.Message reqMsg = Proto.Message.parse(reqAndUuid[0]);
        assertArrayEquals(clientKeys.publicBytes, reqMsg.msg(14).bytes(1));
        byte[] challenge = reqAndUuid[1];

        // Simula l'auto: calcola la sessionKey ECDH = SHA1(pad32(sharedSecret))[0..16]
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(carKeys.privateKey);
        ka.doPhase(Keys.publicFromBytes(clientKeys.publicBytes), true);
        byte[] secret = ka.generateSecret();
        byte[] padded = new byte[32];
        System.arraycopy(secret, 0, padded, 32 - secret.length, secret.length);
        byte[] sessionKey = Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(padded), 16);

        byte[] epoch = new byte[16];
        new SecureRandom().nextBytes(epoch);
        byte[] sessionInfo = new Proto.Writer()
                .uint(1, 10L)               // counter
                .bytes(2, carKeys.publicBytes)
                .bytes(3, epoch)
                .uint(4, 500L)              // clock_time = 500 s
                .toBytes();

        // Firma sessionInfo con subkey("session info")
        Mac subMac = Mac.getInstance("HmacSHA256");
        subMac.init(new SecretKeySpec(sessionKey, "HmacSHA256"));
        byte[] infoSubkey = subMac.doFinal("session info".getBytes(StandardCharsets.US_ASCII));

        ByteArrayOutputStream infoMeta = new ByteArrayOutputStream();
        tlv(infoMeta, 0, new byte[]{6});
        tlv(infoMeta, 2, vin.getBytes(StandardCharsets.US_ASCII));
        tlv(infoMeta, 6, challenge);
        infoMeta.write(255);
        infoMeta.write(sessionInfo);
        Mac tagMac = Mac.getInstance("HmacSHA256");
        tagMac.init(new SecretKeySpec(infoSubkey, "HmacSHA256"));
        byte[] infoTag = tagMac.doFinal(infoMeta.toByteArray());

        byte[] handshakeResp = new Proto.Writer()
                .message(13, new Proto.Writer().message(6, new Proto.Writer().bytes(1, infoTag)))
                .bytes(15, sessionInfo)
                .toBytes();

        session.acceptSession(handshakeResp, challenge);
        assertTrue(session.ready());

        // Firma un comando setChargingAmps(12)
        byte[] cmdPayload = VehicleSession.setChargingAmps(12);
        byte[][] signedCmd = session.signCommand(cmdPayload, VehicleSession.FLAG_ENCRYPT_RESPONSE);
        Proto.Message cmdEnvelope = Proto.Message.parse(signedCmd[0]);
        assertArrayEquals(cmdPayload, cmdEnvelope.bytes(10));
        assertEquals(11L, cmdEnvelope.msg(13).msg(8).num(2)); // counter incrementato da 10 a 11

        // Simula una risposta cifrata AES-GCM dall'auto (operationStatus = 0 -> ok)
        byte[] plainActionResp = new Proto.Writer()
                .message(1, new Proto.Writer().uint(1, 0L))
                .toBytes();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);

        ByteArrayOutputStream aadMeta = new ByteArrayOutputStream();
        tlv(aadMeta, 0, new byte[]{9});
        tlv(aadMeta, 1, new byte[]{3});
        tlv(aadMeta, 2, vin.getBytes(StandardCharsets.US_ASCII));
        tlv(aadMeta, 5, new byte[]{0, 0, 0, 1});
        tlv(aadMeta, 7, new byte[]{0, 0, 0, 2});
        tlv(aadMeta, 8, signedCmd[1]);
        tlv(aadMeta, 9, new byte[]{0, 0, 0, 0});
        aadMeta.write(255);
        byte[] aad = MessageDigest.getInstance("SHA-256").digest(aadMeta.toByteArray());

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        byte[] enc = cipher.doFinal(plainActionResp);
        byte[] ciphertext = Arrays.copyOf(enc, enc.length - 16);
        byte[] gcmTag = Arrays.copyOfRange(enc, enc.length - 16, enc.length);

        byte[] encryptedEnvelope = new Proto.Writer()
                .message(7, new Proto.Writer().uint(1, 3L))
                .bytes(10, ciphertext)
                .message(13, new Proto.Writer().message(9, new Proto.Writer()
                        .bytes(1, nonce)
                        .uint(2, 1L)
                        .bytes(3, gcmTag)))
                .uint(52, VehicleSession.FLAG_ENCRYPT_RESPONSE)
                .toBytes();

        VehicleSession.Result res = session.readResponse(encryptedEnvelope, signedCmd[1]);
        assertTrue(res.ok);
        assertEquals("", res.reason);
        assertFalse(res.retry);
    }

    @Test
    public void historyIntervalSplitsSolarAndGridEnergyCorrectly() {
        assertNull(History.interval(1000L, 1000L, 3000, 0, 2000.0d));
        assertNull(History.interval(0L, History.MAX_GAP_MS + 1L, 3000, 0, 2000.0d));

        // 6 minuti (0,1 h): auto 2300 W, rete -500 W (prelievo) -> 500 W da rete (0,05 kWh) e 1800 W da sole (0,18 kWh)
        double[] slice = History.interval(0L, 360_000L, 2500.0d, -500.0d, 2300.0d);
        assertNotNull(slice);
        assertEquals(0.18d, slice[0], 1e-6); // car sun kWh
        assertEquals(0.05d, slice[1], 1e-6); // car grid kWh
        assertEquals(0.25d, slice[2], 1e-6); // pv kWh
        assertEquals(0.00d, slice[3], 1e-6); // export kWh
        assertEquals(0.05d, slice[4], 1e-6); // import kWh
    }

    private static void tlv(ByteArrayOutputStream out, int tag, byte[] val) {
        out.write(tag);
        out.write(val.length);
        out.write(val, 0, val.length);
    }
}

