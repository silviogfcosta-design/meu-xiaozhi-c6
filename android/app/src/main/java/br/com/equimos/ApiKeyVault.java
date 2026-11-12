package br.com.equimos;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ApiKeyVault {
    private static final String ALIAS = "equimos_gemini_key";
    private static final String PREFS = "equimos_secure_settings";
    private static final String KEY = "gemini_ciphertext";

    private ApiKeyVault() {}

    private static SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    static void save(Context context, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, packed, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, packed, cipher.getIV().length, encrypted.length);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
    }

    static String load(Context context) {
        try {
            String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, null);
            if (encoded == null) return "";
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length < 29) return "";
            byte[] iv = new byte[12];
            byte[] data = new byte[packed.length - iv.length];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, data, 0, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }
}
