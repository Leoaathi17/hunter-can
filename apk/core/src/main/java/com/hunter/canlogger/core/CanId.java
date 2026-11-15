package com.hunter.canlogger.core;

import java.util.Locale;

/** Helpers for the combined ID key: 11-bit IDs use the plain number, 29-bit IDs add 2^32 (same scheme as the PWA). */
public final class CanId {
    private CanId() {}

    public static final long EXT = 1L << 32;
    public static final long MAX_STD = 0x7FFL;
    public static final long MAX_EXT = 0x1FFFFFFFL;

    public static long key(long id, boolean ext) { return ext ? id + EXT : id; }
    public static boolean isExt(long key) { return key >= EXT; }
    public static long num(long key) { return key >= EXT ? key - EXT : key; }

    /** Display form: 0x100, 0x80 (11-bit, no padding) or 0x18DAF110 (29-bit, always 8 digits). */
    public static String str(long key) {
        return key >= EXT ? "0x" + pad(Long.toHexString(num(key)).toUpperCase(Locale.ROOT), 8)
                : "0x" + Long.toHexString(key).toUpperCase(Locale.ROOT);
    }

    /** Capture-file form: 3 hex digits for 11-bit, 8 for 29-bit. */
    public static String cap(long key) {
        return key >= EXT ? pad(Long.toHexString(num(key)).toUpperCase(Locale.ROOT), 8)
                : pad(Long.toHexString(key).toUpperCase(Locale.ROOT), 3);
    }

    /** Byte reference used for signal names, e.g. "0x1A0 B2". */
    public static String byteKey(long key, int index) { return str(key) + " B" + index; }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < n; i++) sb.append('0');
        return sb.append(s).toString();
    }
}
