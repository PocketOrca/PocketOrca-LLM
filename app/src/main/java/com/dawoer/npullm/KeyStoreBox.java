package com.dawoer.npullm;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API key storage for the CLI remote mode (spec §3.4): Android Keystore
 * AES/GCM. Raw key never leaves Java except into the Authorization header;
 * JS only sees a fingerprint (first 8 hex of SHA-256).
 */
final class KeyStoreBox {
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "npullm_apikey";
    private static final String PREF = "npullm_secure";
    private static final String K_ENC = "apikey_enc";
    private static final String K_IV = "apikey_iv";

    private KeyStoreBox() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    synchronized static void save(Context c, String plain) throws Exception {
        SecretKey k = key();
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        // Same Keystore rule as ensureLocal: no caller-provided IV.
        ci.init(Cipher.ENCRYPT_MODE, k);
        byte[] enc = ci.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] iv = ci.getIV();
        prefs(c).edit()
                .putString(K_ENC, b64(enc))
                .putString(K_IV, b64(iv))
                .apply();
    }

    synchronized static String load(Context c) {
        try {
            String encS = prefs(c).getString(K_ENC, null);
            String ivS = prefs(c).getString(K_IV, null);
            if (encS == null || ivS == null) return "";
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, unb64(ivS)));
            return new String(ci.doFinal(unb64(encS)), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    synchronized static void clear(Context c) {
        prefs(c).edit().remove(K_ENC).remove(K_IV).apply();
    }

    /** First 8 hex of SHA-256 — safe to display (spec: 指纹前 8 位). */
    static String fingerprint(String plain) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable e) {
            return "????????";
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String b64(byte[] a) { return Base64.encodeToString(a, Base64.NO_WRAP); }

    private static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }

    /* ---------- vc104: local server API key — SEPARATE slot, never touches
     * the CLI remote-endpoint key above (vc105: the first cut wrongly reused
     * the same alias and would have read/clobbered the remote key) ---------- */

    private static final String LOCAL_ALIAS = "npullm_localkey";
    private static final String LPREF = "npullm_local";
    private static final String K_ENC_L = "localkey_enc";
    private static final String K_IV_L = "localkey_iv";

    /** "sk-" + 32 hex (16 random bytes) — standard-looking bearer key. Pure, JVM-tested. */
    static String generateLocalKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("sk-");
        for (int i = 0; i < b.length; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    private static SecretKey localKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(LOCAL_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        kg.init(new KeyGenParameterSpec.Builder(LOCAL_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    /** Existing local key, or generate + persist a fresh one. Idempotent. */
    synchronized static String ensureLocal(Context c) {
        android.util.Log.d("npullm_key", "ensureLocal enter");
        try {
            SharedPreferences p = c.getSharedPreferences(LPREF, Context.MODE_PRIVATE);
            String encS = p.getString(K_ENC_L, null), ivS = p.getString(K_IV_L, null);
            if (encS != null && ivS != null) {
                Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
                ci.init(Cipher.DECRYPT_MODE, localKey(), new GCMParameterSpec(128, unb64(ivS)));
                String k = new String(ci.doFinal(unb64(encS)), StandardCharsets.UTF_8);
                if (!k.isEmpty()) {
                    android.util.Log.d("npullm_key", "ensureLocal decrypt hit");
                    return k;
                }
            }
        } catch (Throwable e) {
            android.util.Log.w("npullm_key", "ensureLocal decrypt failed: " + e);
        }
        String fresh = generateLocalKey();
        try {
            SecretKey k = localKey();
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            // AndroidKeyStore forbids caller-provided IVs — let it generate
            // one and read it back (S25-verified failure: "Caller-provided
            // IV not permitted").
            ci.init(Cipher.ENCRYPT_MODE, k);
            byte[] enc = ci.doFinal(fresh.getBytes(StandardCharsets.UTF_8));
            byte[] iv = ci.getIV();
            c.getSharedPreferences(LPREF, Context.MODE_PRIVATE).edit()
                    .putString(K_ENC_L, b64(enc))
                    .putString(K_IV_L, b64(iv))
                    .apply();
            android.util.Log.d("npullm_key", "ensureLocal generated+saved");
        } catch (Throwable e) {
            android.util.Log.w("npullm_key", "ensureLocal gen/save FAILED: " + e);
            return ""; // keystore unavailable — caller treats as auth-off
        }
        return fresh;
    }
}
