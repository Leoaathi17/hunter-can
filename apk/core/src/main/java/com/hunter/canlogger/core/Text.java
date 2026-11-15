package com.hunter.canlogger.core;

import java.math.BigDecimal;
import java.util.Locale;

/** Small string helpers shared by the capture writer, signal definitions and the UI. */
public final class Text {
    private Text() {}

    public static String hex2(int b) {
        String s = Integer.toHexString(b & 0xFF).toUpperCase(Locale.ROOT);
        return s.length() < 2 ? "0" + s : s;
    }

    /**
     * Same as clean() in the PWA: control characters become spaces, runs of white space collapse,
     * result is trimmed and cut to 60 characters. A label can never contain a line break.
     */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 32 || c == 127 ? ' ' : c);
        }
        String r = sb.toString().replaceAll("\\s+", " ").trim();
        return r.length() > 60 ? r.substring(0, 60) : r;
    }

    public static String cleanOr(String s, String fallback) {
        String r = clean(s);
        return r.isEmpty() ? fallback : r;
    }

    /** Number to text the way JavaScript prints it for ordinary values: 1, 0.25, -3, 0.0001 (no exponent, no ".0"). */
    public static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        String s = new BigDecimal(Double.toString(d)).toPlainString();
        if (s.indexOf('.') >= 0) {
            int e = s.length();
            while (e > 0 && s.charAt(e - 1) == '0') e--;
            if (e > 0 && s.charAt(e - 1) == '.') e--;
            s = s.substring(0, e);
        }
        return s;
    }

    /** Compact value for gauges: 1234, 123.4, 12.34 (same as fmtNum in the PWA). */
    public static String fmtNum(double v) {
        double a = Math.abs(v);
        if (a >= 1000) return String.format(Locale.ROOT, "%.0f", v);
        if (a >= 100) return String.format(Locale.ROOT, "%.1f", v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.indexOf('.') >= 0) {
            int e = s.length();
            while (e > 0 && s.charAt(e - 1) == '0') e--;
            if (e > 0 && s.charAt(e - 1) == '.') e--;
            s = s.substring(0, e);
        }
        return s.isEmpty() || s.equals("-") ? "0" : s;
    }
}
