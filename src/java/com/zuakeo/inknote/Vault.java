package com.zuakeo.inknote;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 敏感配置的加密存储：密钥放在 Android Keystore（不出芯片），
 * 配置里只存 "v1:iv:cipher" 这种形式。root 设备挡不住 root 读取，
 * 但至少文件被单独拷走时解不开。
 */
public class Vault {

    private static final String TAG = "InkNote";
    private static final String ALIAS = "inknote_vault_v1";
    private static final String PREFIX = "v1:";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    public static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
        return PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    public static String decrypt(String token) throws Exception {
        String body = token.substring(PREFIX.length());
        int i = body.indexOf(':');
        byte[] iv = Base64.decode(body.substring(0, i), Base64.NO_WRAP);
        byte[] ct = Base64.decode(body.substring(i + 1), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(c.doFinal(ct), "UTF-8");
    }

    public static boolean isEncrypted(String v) {
        return v != null && v.startsWith(PREFIX);
    }

    /** 读配置里的敏感项；如果还是明文，顺手加密写回。 */
    public static String read(Context ctx, String name) {
        SharedPreferences sp = Net.prefs(ctx);
        String v = sp.getString(name, "");
        if (v == null || v.trim().isEmpty()) return "";
        if (isEncrypted(v)) {
            try {
                return decrypt(v);
            } catch (Throwable t) {
                // 换机 / 清过 Keystore：解不开就当没配，避免把密文当密钥用
                Log.e(TAG, "解密失败：" + name, t);
                return "";
            }
        }
        try {
            sp.edit().putString(name, encrypt(v.trim())).apply();
        } catch (Throwable t) {
            Log.e(TAG, "加密失败：" + name, t);
        }
        return v.trim();
    }
}
