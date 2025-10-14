package org.unicitylabs.nostr.util;

/**
 * Hex encoding/decoding utilities.
 */
public class HexUtils {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    /**
     * Convert byte array to hex string.
     *
     * @param bytes Byte array
     * @return Hex string (lowercase)
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }

    /**
     * Convert hex string to byte array.
     *
     * @param hex Hex string
     * @return Byte array
     */
    public static byte[] fromHex(String hex) {
        if (hex == null) {
            return null;
        }
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    /**
     * Check if a string is valid hex.
     *
     * @param str String to check
     * @return true if valid hex
     */
    public static boolean isHex(String str) {
        if (str == null || str.length() % 2 != 0) {
            return false;
        }
        return str.matches("[0-9a-fA-F]+");
    }

    private HexUtils() {
        // Utility class
    }
}
