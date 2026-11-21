package com.gathin.tvhome;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * goathouse: the Sendspin encryption layer — Noise_KKpsk2_25519_AESGCM_SHA256 (noiseprotocol.org rev 34), this side
 * as the responder (the server always initiates). Curve25519 via Android's XDH (API 31+), AES-GCM, SHA-256.
 */
final class Noise {
    static final String PROTOCOL = "Noise_KKpsk2_25519_AESGCM_SHA256";
    /** Spec: Sentinel PSK = SHA-256("sendspin-sentinel-psk-v1"), used whenever no pairing applies. */
    static final byte[] SENTINEL_PSK = sha256("sendspin-sentinel-psk-v1".getBytes(StandardCharsets.UTF_8));

    private static final byte[] X509_PREFIX = hexBytes("302a300506032b656e032100");
    private static final byte[] PKCS8_PREFIX = hexBytes("302e020100300506032b656e04220420");

    // ---------- keys ----------

    /** A fresh static/ephemeral keypair: {private 32 bytes, public 32 bytes}. */
    static byte[][] generateKeyPair() throws Exception {
        // Conscrypt's XDH generator is X25519 by default and rejects explicit parameter specs.
        KeyPairGenerator g;
        try {
            g = KeyPairGenerator.getInstance("X25519");
        } catch (Exception e) {
            g = KeyPairGenerator.getInstance("XDH");
        }
        KeyPair kp = g.generateKeyPair();
        return new byte[][]{tail(kp.getPrivate().getEncoded()), tail(kp.getPublic().getEncoded())};
    }

    static byte[] publicFromPrivate(byte[] priv) throws Exception {
        // XDH has no direct derivation API: X25519(priv, basepoint 9).
        byte[] base = new byte[32];
        base[0] = 9;
        return dh(priv, base);
    }

    static byte[] dh(byte[] priv, byte[] pub) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("XDH");
        PrivateKey sk = kf.generatePrivate(new PKCS8EncodedKeySpec(concat(PKCS8_PREFIX, priv)));
        PublicKey pk = kf.generatePublic(new X509EncodedKeySpec(concat(X509_PREFIX, pub)));
        KeyAgreement ka = KeyAgreement.getInstance("XDH");
        ka.init(sk);
        ka.doPhase(pk, true);
        return ka.generateSecret();
    }

    // ---------- cipher state ----------

    static final class CipherState {
        private byte[] k;
        private long n;

        CipherState(byte[] k) { this.k = k; }

        boolean hasKey() { return k != null; }

        byte[] encrypt(byte[] ad, byte[] plaintext) throws Exception {
            if (k == null) return plaintext;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, nonce(n++)));
            if (ad != null && ad.length > 0) c.updateAAD(ad);
            return c.doFinal(plaintext);
        }

        byte[] decrypt(byte[] ad, byte[] ciphertext) throws Exception {
            if (k == null) return ciphertext;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, nonce(n)));
            if (ad != null && ad.length > 0) c.updateAAD(ad);
            byte[] out = c.doFinal(ciphertext);   // throws on a bad tag: the caller closes the connection
            n++;
            return out;
        }

        /** AESGCM nonce: 32 zero bits then the 64-bit counter, big-endian (Noise spec 12.4). */
        private static byte[] nonce(long n) {
            return ByteBuffer.allocate(12).putInt(0).putLong(n).array();
        }
    }

    // ---------- handshake (responder side of KKpsk2) ----------

    static final class Responder {
        private final byte[] sPriv, sPub, rs;
        private byte[] ck, h, ePriv, ePub, re;
        private CipherState cs = new CipherState(null);
        CipherState send, recv;

        /** prologue = client/init bytes || server/init bytes; rs = the server's static public key. */
        Responder(byte[] sPriv, byte[] sPub, byte[] rs, byte[] prologue) {
            this.sPriv = sPriv;
            this.sPub = sPub;
            this.rs = rs;
            byte[] name = PROTOCOL.getBytes(StandardCharsets.US_ASCII);
            h = name.length <= 32 ? Arrays.copyOf(name, 32) : sha256(name);
            ck = h.clone();
            mixHash(prologue);
            mixHash(rs);     // pre-message "-> s": the initiator's (server's) static key
            mixHash(sPub);   // pre-message "<- s": ours
        }

        /** Message 1 from the server: e, es, ss + encrypted JSON payload {psk_id, psk_category}. */
        byte[] readMessage1(byte[] msg) throws Exception {
            re = Arrays.copyOfRange(msg, 0, 32);
            mixHash(re);
            mixKey(re);                       // psk handshakes: e also feeds the chaining key
            mixKey(dh(sPriv, re));            // es
            mixKey(dh(sPriv, rs));            // ss
            return decryptAndHash(Arrays.copyOfRange(msg, 32, msg.length));
        }

        /** Message 2 to the server: e, ee, se, psk + encrypted payload "{}" ; then the transport keys. */
        byte[] writeMessage2(byte[] psk, byte[] payload) throws Exception {
            byte[][] e = generateKeyPair();
            ePriv = e[0];
            ePub = e[1];
            mixHash(ePub);
            mixKey(ePub);
            mixKey(dh(ePriv, re));            // ee
            mixKey(dh(ePriv, rs));            // se
            mixKeyAndHash(psk);               // psk2
            byte[] ct = encryptAndHash(payload);
            byte[][] keys = hkdf(ck, new byte[0], 2);
            recv = new CipherState(Arrays.copyOf(keys[0], 32));   // initiator -> responder
            send = new CipherState(Arrays.copyOf(keys[1], 32));   // responder -> initiator
            return concat(ePub, ct);
        }

        private void mixHash(byte[] data) { h = sha256(concat(h, data)); }

        private void mixKey(byte[] ikm) throws Exception {
            byte[][] o = hkdf(ck, ikm, 2);
            ck = o[0];
            cs = new CipherState(Arrays.copyOf(o[1], 32));
        }

        private void mixKeyAndHash(byte[] ikm) throws Exception {
            byte[][] o = hkdf(ck, ikm, 3);
            ck = o[0];
            mixHash(o[1]);
            cs = new CipherState(Arrays.copyOf(o[2], 32));
        }

        private byte[] encryptAndHash(byte[] pt) throws Exception {
            byte[] ct = cs.encrypt(h, pt);
            mixHash(ct);
            return ct;
        }

        private byte[] decryptAndHash(byte[] ct) throws Exception {
            byte[] pt = cs.decrypt(h, ct);
            mixHash(ct);
            return pt;
        }
    }

    // ---------- primitives ----------

    static byte[][] hkdf(byte[] chainingKey, byte[] ikm, int outputs) throws Exception {
        byte[] temp = hmac(chainingKey, ikm);
        byte[] o1 = hmac(temp, new byte[]{1});
        byte[] o2 = hmac(temp, concat(o1, new byte[]{2}));
        if (outputs == 2) return new byte[][]{o1, o2};
        byte[] o3 = hmac(temp, concat(o2, new byte[]{3}));
        return new byte[][]{o1, o2, o3};
    }

    static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private static byte[] tail(byte[] encoded) { return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length); }

    private static byte[] hexBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
