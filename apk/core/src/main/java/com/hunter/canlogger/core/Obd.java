package com.hunter.canlogger.core;

/**
 * Passive OBD-II decoding (SAE J1979 mode 01 formulas). The app NEVER sends a request: it only decodes
 * single-frame responses that appear on 0x7E8..0x7EF because the car or another scan tool asked.
 */
public final class Obd {
    private Obd() {}

    public static final class Def {
        public final int pid;
        public final String name;
        public final String unit;
        public final int len;

        Def(int pid, String name, String unit, int len) { this.pid = pid; this.name = name; this.unit = unit; this.len = len; }

        public double eval(int a, int b) {
            switch (pid) {
                case 0x04: return a * 100.0 / 255;
                case 0x05: return a - 40;
                case 0x0B: return a;
                case 0x0C: return (a * 256 + b) / 4.0;
                case 0x0D: return a;
                case 0x0F: return a - 40;
                case 0x10: return (a * 256 + b) / 100.0;
                case 0x11: return a * 100.0 / 255;
                case 0x2F: return a * 100.0 / 255;
                case 0x42: return (a * 256 + b) / 1000.0;
                case 0x46: return a - 40;
                case 0x5C: return a - 40;
                default: return a;
            }
        }
    }

    private static final Def[] DEFS = {
        new Def(0x04, "Engine load", "%", 1),
        new Def(0x05, "Coolant temp", "°C", 1),
        new Def(0x0B, "Intake MAP", "kPa", 1),
        new Def(0x0C, "Engine speed", "rpm", 2),
        new Def(0x0D, "Vehicle speed", "km/h", 1),
        new Def(0x0F, "Intake air temp", "°C", 1),
        new Def(0x10, "MAF rate", "g/s", 2),
        new Def(0x11, "Throttle", "%", 1),
        new Def(0x2F, "Fuel level", "%", 1),
        new Def(0x42, "Module voltage", "V", 2),
        new Def(0x46, "Ambient temp", "°C", 1),
        new Def(0x5C, "Oil temp", "°C", 1)
    };

    public static Def def(int pid) {
        for (Def d : DEFS) if (d.pid == pid) return d;
        return null;
    }

    /** True for the response IDs 0x7E8..0x7EF (11-bit only). */
    public static boolean isResponseId(long key) { return key >= 0x7E8 && key <= 0x7EF; }

    /** True for request IDs seen from OTHER tools: 0x7DF and 0x7E0..0x7E7. Only counted. */
    public static boolean isRequestId(long key) { return key == 0x7DF || (key >= 0x7E0 && key <= 0x7E7); }

    /** Result of decoding one response frame. */
    public static final class Reading {
        public final Def def;
        public final double value;
        public Reading(Def def, double value) { this.def = def; this.value = value; }
    }

    /**
     * Single-frame ISO-TP mode-01 response [len, 0x41, pid, A, B]. Returns null when the frame is not such a response
     * (multi-frame, negative response 0x7F, unknown PID, too short).
     */
    public static Reading decodeResponse(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) >= 2 && (d[0] & 0xFF) <= 7 && (d[1] & 0xFF) == 0x41) {
            Def def = def(d[2] & 0xFF);
            if (def != null && d.length >= 3 + def.len && (d[0] & 0xFF) >= 2 + def.len) {
                int a = d[3] & 0xFF;
                int b = d.length > 4 ? d[4] & 0xFF : 0;
                return new Reading(def, def.eval(a, b));
            }
        }
        return null;
    }

    /** True for a mode-03 response (0x43): another tool is reading stored fault codes. Only counted, never decoded. */
    public static boolean isDtcResponse(byte[] d) {
        return d.length >= 3 && (d[0] & 0xFF) >= 2 && (d[0] & 0xFF) <= 7 && (d[1] & 0xFF) == 0x43;
    }
}
