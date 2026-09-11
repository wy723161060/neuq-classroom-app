package com.wanyuea.neuqclassroom;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 「记住密码自动登录」的账号密码存储。
 *
 * 安全设计（这个类存的是用户的校园网密码，标准必须拉满）：
 *  · 默认关闭，用户在「更多」里手动打开才生效；
 *  · 密钥放在 Android Keystore（硬件安全芯片里，App 自己都取不出明文密钥），
 *    AES-256/GCM 加密后才写进 SharedPreferences，落盘的不是明文；
 *  · 只存本机，不经过任何服务器，也不写日志；
 *  · 用户关掉开关时可选择连密码一起删；「清除全部缓存」也会一并删除；
 *  · Keystore 密钥失效（如恢复出厂、锁屏凭据变更）时解密会失败，
 *    此时静默丢弃失效数据，自动登录退回手动，绝不重试到死循环。
 *
 * 校园统一身份认证的密码提交时由页面自身的 JS 加密（encrypt.wisedu.js），
 * App 全程不接触、不复刻那套加密逻辑 —— 只在用户手动提交的瞬间读取输入框原文，
 * 下次替用户把输入框填上、点同一个登录按钮。
 */
final class CredentialStore {

    private static final String KEY_ALIAS = "neuq_cred_key";
    private static final String PREFS = "neuq_prefs";
    private static final String K_ENABLED = "auto_login_enabled";
    private static final String K_BLOB = "cred_blob";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private CredentialStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ==================== 开关 ==================== */

    static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_ENABLED, false);
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_ENABLED, on).apply();
    }

    /** 是否已保存过账密 */
    static boolean has(Context ctx) {
        return prefs(ctx).contains(K_BLOB);
    }

    /* ==================== 存取 ==================== */

    /**
     * 保存账密。成功返回 true；Keystore 不可用（个别机型/ROM 被裁剪）返回 false，
     * 调用方应提示用户「此设备不支持安全存储」，绝不能明文兜底落盘。
     */
    static boolean save(Context ctx, String user, String pass) {
        if (user == null || pass == null || user.isEmpty() || pass.isEmpty()) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("u", user);
            o.put("p", pass);
            o.put("t", System.currentTimeMillis());

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            prefs(ctx).edit()
                    .putString(K_BLOB, Base64.encodeToString(out, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取账密，返回 {用户名, 密码}；未保存 / 损坏 / 密钥失效返回 null */
    static String[] read(Context ctx) {
        String b64 = prefs(ctx).getString(K_BLOB, null);
        if (b64 == null) return null;
        try {
            byte[] in = Base64.decode(b64, Base64.NO_WRAP);
            if (in.length <= IV_LEN) throw new IllegalStateException("blob too short");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, in, 0, IV_LEN));
            byte[] pt = c.doFinal(in, IV_LEN, in.length - IV_LEN);
            JSONObject o = new JSONObject(new String(pt, StandardCharsets.UTF_8));
            String u = o.optString("u", "");
            String p = o.optString("p", "");
            if (u.isEmpty() || p.isEmpty()) return null;
            return new String[]{u, p};
        } catch (Exception e) {
            // 密钥失效 / 数据损坏：这份密文永远解不开了，留着只会反复失败，直接丢弃
            clear(ctx);
            return null;
        }
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().remove(K_BLOB).apply();
    }

    /** 界面提示用的打码用户名：保留前两位 */
    static String mask(String user) {
        if (user == null || user.length() <= 2) return "··";
        return user.substring(0, 2) + "··";
    }

    /* ==================== 密钥 ==================== */

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
