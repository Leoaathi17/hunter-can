package com.hunter.canlogger.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A user-defined signal: where a value lives in a frame (ID, start byte, length, endianness, signedness)
 * and how to turn the raw integer into a number (scale, offset, unit, name). Immutable once built.
 * Build only through sanitize(): it rejects anything out of range and strips line breaks from text.
 */
public final class UserSignal {
    public final int uid;
    /** Hex ID without leading zeros, uppercase ("7E8", "18DAF110"). */
    public final String idHex;
    public final boolean ext;
    public final int start;
    public final int len;
    public final boolean bigEndian;
    public final boolean signed;
    public final double scale;
    public final double offset;
    public final String unit;
    public final String name;
    public final Double min;
    public final Double max;
    public final boolean unverified;

    private UserSignal(int uid, String idHex, boolean ext, int start, int len, boolean be, boolean signed,
                       double scale, double offset, String unit, String name, Double min, Double max, boolean unverified) {
        this.uid = uid;
        this.idHex = idHex;
        this.ext = ext;
        this.start = start;
        this.len = len;
        this.bigEndian = be;
        this.signed = signed;
        this.scale = scale;
        this.offset = offset;
        this.unit = unit;
        this.name = name;
        this.min = min;
        this.max = max;
        this.unverified = unverified;
    }

    UserSignal withUid(int u) {
        return new UserSignal(u, idHex, ext, start, len, bigEndian, signed, scale, offset, unit, name, min, max, unverified);
    }

    public long key() { return CanId.key(Long.parseLong(idHex, 16), ext); }

    // ---------------------------------------------------------------- sanitising

    private static double toNum(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        if (o instanceof String) {
            String t = ((String) o).trim();
            if (t.isEmpty()) return Double.NaN;
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private static Double optNum(Object o) {
        double d = toNum(o);
        return Double.isNaN(d) || Double.isInfinite(d) ? null : Double.valueOf(d);
    }

    private static boolean isInt(double d) { return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d); }

    /**
     * Validates a raw definition (from the form or from imported JSON). Returns null when invalid:
     * bad hex ID, ID above 0x7FF (11-bit) or 0x1FFFFFFF (29-bit), start outside 0..7, length outside 1..4,
     * start+length above 8, non-numeric scale or offset.
     */
    public static UserSignal sanitize(Map<String, Object> o) {
        if (o == null) return null;
        Object idObj = o.get("id");
        String idt = idObj == null ? "" : String.valueOf(idObj).trim();
        if (idt.length() >= 2 && (idt.startsWith("0x") || idt.startsWith("0X"))) idt = idt.substring(2).trim();
        idt = idt.toUpperCase(java.util.Locale.ROOT);
        if (idt.isEmpty() || idt.length() > 8) return null;
        for (int i = 0; i < idt.length(); i++) {
            char c = idt.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return null;
        }
        long idn = Long.parseLong(idt, 16);
        boolean ext = idt.length() > 3 || Boolean.TRUE.equals(o.get("ext"));
        if (ext ? idn > CanId.MAX_EXT : idn > CanId.MAX_STD) return null;
        double st = toNum(o.get("start")), ln = toNum(o.get("len"));
        if (!isInt(st) || !isInt(ln) || st < 0 || st > 7 || ln < 1 || ln > 4 || st + ln > 8) return null;
        double scale = toNum(o.get("scale")), offset = toNum(o.get("offset"));
        if (Double.isNaN(scale) || Double.isInfinite(scale) || Double.isNaN(offset) || Double.isInfinite(offset)) return null;
        boolean be = !"le".equals(o.get("endian"));
        boolean signed = toNum(o.get("signed")) == 1.0;
        String unit = Text.clean(o.get("unit") == null ? "" : String.valueOf(o.get("unit"))).replaceAll("\\s+", "");
        if (unit.length() > 8) unit = unit.substring(0, 8);
        String name = Text.cleanOr(o.get("name") == null ? "" : String.valueOf(o.get("name")), "Signal");
        return new UserSignal(0, Long.toHexString(idn).toUpperCase(java.util.Locale.ROOT), ext, (int) st, (int) ln, be, signed,
                scale, offset, unit, name, optNum(o.get("min")), optNum(o.get("max")), Boolean.TRUE.equals(o.get("unverified")) || toNum(o.get("unverified")) == 1.0);
    }

    /** Convenience for the UI form: all fields as text. */
    public static UserSignal fromForm(String id, String start, String len, boolean littleEndian, boolean signed,
                                      String scale, String offset, String unit, String name, String min, String max, boolean unverified) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("start", start);
        m.put("len", len);
        m.put("endian", littleEndian ? "le" : "be");
        m.put("signed", signed ? "1" : "0");
        m.put("scale", scale);
        m.put("offset", offset == null || offset.trim().isEmpty() ? "0" : offset);
        m.put("unit", unit);
        m.put("name", name);
        m.put("min", min);
        m.put("max", max);
        m.put("unverified", unverified);
        return sanitize(m);
    }

    // ---------------------------------------------------------------- decode

    /** Decodes this signal from a frame payload, or returns null if the payload is too short. */
    public Double decode(byte[] data) {
        if (data == null || data.length < start + len) return null;
        long raw = 0;
        if (bigEndian) {
            for (int i = 0; i < len; i++) raw = raw * 256 + (data[start + i] & 0xFF);
        } else {
            for (int i = len - 1; i >= 0; i--) raw = raw * 256 + (data[start + i] & 0xFF);
        }
        if (signed) {
            long m = 1L << (8 * len);
            if (raw >= m / 2) raw -= m;
        }
        return raw * scale + offset;
    }

    // ---------------------------------------------------------------- text forms

    /** "# SIGNAL 0x7E8 B3+2 BE signed=0 scale=0.25 offset=0 unit=rpm = Engine speed" */
    public String headerLine() {
        return "# SIGNAL 0x" + (ext ? CanId.pad(idHex, 8) : idHex) + " B" + start + "+" + len + " " + (bigEndian ? "BE" : "LE")
                + " signed=" + (signed ? 1 : 0) + " scale=" + Text.num(scale) + " offset=" + Text.num(offset)
                + " unit=" + (unit.isEmpty() ? "-" : unit) + (unverified ? " unverified=1" : "") + " = " + name;
    }

    public String idText() { return "0x" + (ext ? CanId.pad(idHex, 8) : idHex); }

    public String summary() {
        return name + (unverified ? " [unverified]" : "") + "\n" + idText() + " B" + start + "+" + len + " " + (bigEndian ? "BE" : "LE")
                + (signed ? " signed" : "") + " x" + Text.num(scale) + " " + (offset >= 0 ? "+" : "") + Text.num(offset) + " " + unit;
    }

    /** JSON form (without uid), field names as in the PWA export. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", idHex);
        m.put("ext", ext);
        m.put("start", (double) start);
        m.put("len", (double) len);
        m.put("endian", bigEndian ? "be" : "le");
        m.put("signed", signed);
        m.put("scale", scale);
        m.put("offset", offset);
        m.put("unit", unit);
        m.put("name", name);
        m.put("min", min);
        m.put("max", max);
        m.put("unverified", unverified);
        return m;
    }

    // ---------------------------------------------------------------- generic starters (always unverified)

    public static final class Starter {
        public final String label;
        public final Map<String, Object> def;
        Starter(String label, String id, int start, int len, String endian, double scale, double offset, String unit, String name, Double min, Double max) {
            this.label = label;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("start", (double) start);
            m.put("len", (double) len);
            m.put("endian", endian);
            m.put("scale", scale);
            m.put("offset", offset);
            m.put("unit", unit);
            m.put("name", name);
            m.put("min", min);
            m.put("max", max);
            m.put("unverified", Boolean.TRUE);
            this.def = m;
        }
    }

    public static final Starter[] STARTERS = {
        new Starter("OBD engine speed (7E8, PID 0C)", "7E8", 3, 2, "be", 0.25, 0, "rpm", "OBD engine speed", 0.0, 8000.0),
        new Starter("OBD vehicle speed (7E8, PID 0D)", "7E8", 3, 1, "be", 1, 0, "km/h", "OBD vehicle speed", 0.0, 240.0),
        new Starter("OBD coolant temp (7E8, PID 05)", "7E8", 3, 1, "be", 1, -40, "C", "OBD coolant temp", -40.0, 130.0),
        new Starter("OBD throttle (7E8, PID 11)", "7E8", 3, 1, "be", 0.392157, 0, "%", "OBD throttle", 0.0, 100.0),
        new Starter("Blank 16-bit big-endian value", "000", 0, 2, "be", 1, 0, "", "My 16-bit value", null, null),
        new Starter("Blank 16-bit little-endian value", "000", 0, 2, "le", 1, 0, "", "My 16-bit value (LE)", null, null)
    };
}
