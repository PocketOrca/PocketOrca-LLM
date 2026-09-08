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
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        ci.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
        byte[] enc = ci.doFinal(plain.getBytes(StandardCharsets.UTF_8));
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
}
