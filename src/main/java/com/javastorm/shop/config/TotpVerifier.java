package com.javastorm.shop.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;

/**
 * RFC 6238 TOTP 双因素认证校验器（兼容 Google Authenticator / 1Password / Authy，6 位 30 秒步长，容忍 ±1 步时钟偏差）。
 */
public final class TotpVerifier {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    private TotpVerifier() {
    }

    public static boolean verify(String base32Secret, String code, Instant now) {
        if (base32Secret == null || base32Secret.isBlank() || code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.length() != DIGITS || !trimmed.chars().allMatch(Character::isDigit)) {
            return false;
        }
        byte[] key = decodeBase32(base32Secret);
        if (key.length == 0) {
            return false;
        }
        long step = now.getEpochSecond() / STEP_SECONDS;
        for (long delta = -1; delta <= 1; delta++) {
            if (generateCode(key, step + delta).equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    public static String generate(String base32Secret, Instant now) {
        return generateCode(decodeBase32(base32Secret), now.getEpochSecond() / STEP_SECONDS);
    }

    private static String generateCode(byte[] key, long counter) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    static byte[] decodeBase32(String input) {
        String cleaned = input.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);
        int bitBuffer = 0;
        int bitCount = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int pos = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            int val = BASE32.indexOf(cleaned.charAt(i));
            if (val < 0) {
                return new byte[0];
            }
            bitBuffer = (bitBuffer << 5) | val;
            bitCount += 5;
            if (bitCount >= 8) {
                bitCount -= 8;
                out[pos++] = (byte) ((bitBuffer >> bitCount) & 0xFF);
            }
        }
        return out;
    }
}
