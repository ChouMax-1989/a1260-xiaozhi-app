package io.github.choumax.a1260xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores only issued WebSocket tokens, encrypted with an Android Keystore AES-GCM key. */
final class IssuedTokenStore {
    private static final String STORE = "issued_connection";
    private static final String BLOB = "token_blob";
    private static final String KEY = "a1260_xiaozhi_issued_token_v1";
    private final SharedPreferences prefs;
    IssuedTokenStore(Context context) { prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE); }
    void save(String token) throws Exception { prefs.edit().putString(BLOB, encrypt(token)).commit(); }
    String load() throws Exception { String value = prefs.getString(BLOB, ""); return value.isEmpty() ? "" : decrypt(value); }
    void clear() { prefs.edit().remove(BLOB).apply(); }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private static String encrypt(String token) throws Exception { Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)); byte[] iv = cipher.getIV(); ByteBuffer joined = ByteBuffer.allocate(1 + iv.length + encrypted.length).put((byte) iv.length).put(iv).put(encrypted); return Base64.encodeToString(joined.array(), Base64.NO_WRAP); }
    private static String decrypt(String encoded) throws Exception { ByteBuffer joined = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP)); int ivLength = joined.get() & 0xff; if (ivLength < 12 || ivLength > 16 || joined.remaining() <= ivLength) throw new IllegalStateException("Invalid encrypted token."); byte[] iv = new byte[ivLength]; joined.get(iv); byte[] encrypted = new byte[joined.remaining()]; joined.get(encrypted); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv)); return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8); }
}
