package com.zgcwkj.comm;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭证加密工具
 * 使用 PBKDF2(ro.serialno, credential_salt) 推导 AES-256 密钥，AES-GCM 加密
 * 密钥推导材料：
 *   password = ro.serialno（设备唯一序列号，不存储在任何文件中）
 *   salt     = credential_salt（随机生成，存储在 config.ini 中）
 * 安全模型：即使算法和源码公开，攻击者需同时拥有 config.ini 和目标设备才能推导密钥
 * 加密值格式：enc:<Base64(12字节IV + 密文)>
 * 不含 enc: 前缀的值视为旧版明文，原样返回（向下兼容）
 */
public class CredentialHelp {

    private static final String TAG = "XpMiBackup";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "enc:";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12; // bytes
    private static final int KEY_LENGTH = 256; // bits
    private static final int PBKDF2_ITERATIONS = 10000;

    /** 缓存设备 SN，避免重复反射 */
    private static volatile String cachedSn;

    /**
     * 加密明文字符串
     * @param plaintext 明文密码
     * @param salt      Base64 编码的随机 salt（来自 config.ini 的 credential_salt）
     * @return "enc:<Base64(iv+ciphertext)>" 格式密文；输入为空时原样返回；加密失败时返回明文
     */
    public static String encrypt(String plaintext, String salt) {
        LogHelp.v(TAG, "encrypt: plaintext.len=" + (plaintext == null ? "null" : plaintext.length())
                + ", salt.len=" + (salt == null ? "null" : salt.length()));
        if (plaintext == null || plaintext.isEmpty()) {
            LogHelp.v(TAG, "encrypt: plaintext is null/empty, skip");
            return plaintext == null ? "" : plaintext;
        }
        try {
            var key = deriveKey(salt);
            LogHelp.v(TAG, "encrypt: key derived OK, algo=" + key.getAlgorithm());
            var cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            var iv = cipher.getIV();
            LogHelp.v(TAG, "encrypt: cipher init OK, iv.len=" + (iv != null ? iv.length : "null"));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);
            var result = PREFIX + Base64.getEncoder().encodeToString(combined);
            LogHelp.v(TAG, "encrypt: SUCCESS, result.len=" + result.length());
            return result;
        } catch (Exception e) {
            LogHelp.e(TAG, "encrypt FAILED: " + e.getClass().getName() + ": " + e.getMessage(), e);
            return plaintext;
        }
    }

    /**
     * 解密字符串
     * @param stored 存储的值（可能是 enc: 前缀的密文或旧版明文）
     * @param salt   Base64 编码的随机 salt
     * @return 明文；无 enc: 前缀时原样返回（兼容旧配置）；解密失败返回空串
     */
    public static String decrypt(String stored, String salt) {
        LogHelp.v(TAG, "decrypt: stored.len=" + (stored == null ? "null" : stored.length())
                + ", hasPrefix=" + (stored != null && stored.startsWith(PREFIX))
                + ", salt.len=" + (salt == null ? "null" : salt.length()));
        if (stored == null || stored.isEmpty()) return stored == null ? "" : stored;
        if (!stored.startsWith(PREFIX)) {
            LogHelp.v(TAG, "decrypt: no enc: prefix, return as plaintext");
            return stored;
        }
        try {
            var combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            var iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            var ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            var key = deriveKey(salt);
            var cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            var result = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            LogHelp.v(TAG, "decrypt: SUCCESS");
            return result;
        } catch (Exception e) {
            LogHelp.e(TAG, "decrypt FAILED: " + e.getClass().getName() + ": " + e.getMessage(), e);
            return "";
        }
    }

    /**
     * 生成 16 字节随机 salt，Base64 编码返回
     * 首次保存配置时调用，之后存储在 config.ini 的 credential_salt 字段
     */
    public static String generateSalt() {
        var bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        var salt = Base64.getEncoder().encodeToString(bytes);
        LogHelp.v(TAG, "generateSalt: generated salt.len=" + salt.length());
        return salt;
    }

    /**
     * PBKDF2 推导 AES-256 密钥
     * password = 设备硬件标识组合（跨进程一致，OTA 不变，永不为空）
     * salt     = credential_salt（随机生成，存储在 config.ini）
     */
    private static SecretKey deriveKey(String salt) throws Exception {
        var key = getDeviceKey();
        LogHelp.v(TAG, "deriveKey: key.len=" + key.length()
                + ", salt.len=" + (salt == null ? "null" : salt.length()));
        var saltBytes = (salt != null && !salt.isEmpty())
                ? Base64.getDecoder().decode(salt)
                : new byte[16];
        LogHelp.v(TAG, "deriveKey: saltBytes.len=" + saltBytes.length);
        var spec = new PBEKeySpec(key.toCharArray(), saltBytes, PBKDF2_ITERATIONS, KEY_LENGTH);
        try {
            var factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            LogHelp.v(TAG, "deriveKey: factory=" + factory.getAlgorithm() + ", provider=" + factory.getProvider().getName());
            var keyBytes = factory.generateSecret(spec).getEncoded();
            LogHelp.v(TAG, "deriveKey: key derived, keyBytes.len=" + keyBytes.length);
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 构建设备/实例标识字符串，作为 PBKDF2 的 password
     * 使用模块包 com.zgcwkj.xpmibackup 的 firstInstallTime 安装时间戳
     * 在所有进程（主 App / 系统设置 / 被 Hook 的备份进程）中通过 PackageManager 查到的值完全相同
     */
    private static String getDeviceKey() {
        var installTime = getModuleInstallTime();
        if (installTime > 0) {
            LogHelp.v(TAG, "getDeviceKey: using module firstInstallTime=" + installTime);
            return "xpmibackup:" + installTime;
        }
        var fallbackKey = android.os.Build.BOARD + ":"
                + android.os.Build.HARDWARE + ":"
                + android.os.Build.MANUFACTURER + ":"
                + android.os.Build.MODEL + ":"
                + android.os.Build.PRODUCT;
        LogHelp.v(TAG, "getDeviceKey: fallback to Build info, len=" + fallbackKey.length());
        return fallbackKey;
    }

    /**
     * 反射获取当前进程的 Application Context，并查询模块 com.zgcwkj.xpmibackup 的首次安装时间
     */
    private static long getModuleInstallTime() {
        try {
            var activityThreadClass = Class.forName("android.app.ActivityThread");
            var currentAppMethod = activityThreadClass.getMethod("currentApplication");
            var app = (android.content.Context) currentAppMethod.invoke(null);
            if (app != null) {
                var pm = app.getPackageManager();
                var pi = pm.getPackageInfo("com.zgcwkj.xpmibackup", 0);
                if (pi != null) {
                    return pi.firstInstallTime;
                }
            }
        } catch (Throwable t) {
            LogHelp.e(TAG, "getModuleInstallTime FAILED: " + t.getClass().getName() + ": " + t.getMessage(), t);
        }
        return 0L;
    }
}
