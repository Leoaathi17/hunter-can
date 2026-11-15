package com.hunter.canlogger.core;

/** SLCAN bitrate table: S0..S8. */
public final class Bitrates {
    private Bitrates() {}

    /** kbit/s, index = SLCAN code (S0..S8). */
    public static final int[] KBPS = {10, 20, 50, 100, 125, 250, 500, 750, 1000};
    /** Auto-detect order. */
    public static final int[] DETECT_ORDER = {500, 250, 125, 1000};

    /** SLCAN code for a bitrate in kbit/s, or -1 if the adapter has no such rate. */
    public static int code(int kbps) {
        for (int i = 0; i < KBPS.length; i++) if (KBPS[i] == kbps) return i;
        return -1;
    }

    public static boolean isValid(int kbps) { return code(kbps) >= 0; }

    /** "S6" for 500, throws IllegalArgumentException for unsupported rates. */
    public static String command(int kbps) {
        int c = code(kbps);
        if (c < 0) throw new IllegalArgumentException("unsupported bitrate " + kbps);
        return "S" + c;
    }

    public static String label(int kbps) {
        return (kbps >= 1000 ? (kbps / 1000) + " Mbps" : kbps + " kbps") + " (S" + code(kbps) + ")";
    }

    public static String[] labels() {
        String[] r = new String[KBPS.length];
        for (int i = 0; i < r.length; i++) r[i] = label(KBPS[i]);
        return r;
    }
}
