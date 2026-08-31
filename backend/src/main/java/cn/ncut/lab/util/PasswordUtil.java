package cn.ncut.lab.util;

import org.bouncycastle.crypto.generators.SCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 与 Node 版 crypto.scryptSync 完全兼容：
 *   格式  scrypt$<saltHex>$<hashHex>
 *   参数  N=16384, r=8, p=1, dkLen=64
 *   注意  salt 以“十六进制字符串本身的 ASCII 字节”参与运算（与 Node 行为一致），
 *         而非把十六进制解码为原始字节。
 */
public final class PasswordUtil {

    private static final int N = 16384;
    private static final int R = 8;
    private static final int P = 1;
    private static final int DK_LEN = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    public static String hash(String password) {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String saltHex = toHex(saltBytes);
        String hashHex = scryptHex(password, saltHex);
        return "scrypt$" + saltHex + "$" + hashHex;
    }

    public static boolean verify(String password, String stored) {
        if (stored == null || stored.isEmpty()) return false;
        if (!stored.startsWith("scrypt$")) {
            // 迁移期兼容明文
            return String.valueOf(password).equals(stored);
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 3) return false;
        String saltHex = parts[1];
        String expect = parts[2];
        String actual = scryptHex(password, saltHex);
        return constantTimeEquals(actual, expect);
    }

    private static String scryptHex(String password, String saltHex) {
        byte[] out = SCrypt.generate(
                String.valueOf(password).getBytes(StandardCharsets.UTF_8),
                saltHex.getBytes(StandardCharsets.UTF_8),
                N, R, P, DK_LEN);
        return toHex(out);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(x, y);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
