package com.hunter.canlogger.core;

import java.util.Locale;

/** Decodes the known Hunter 350 signals (same map as the PWA). Strings are "–" when unavailable. */
public final class Dashboard {
    public static final String DASH = "–";

    /** Wheel A (0x12B B0:B1 / 10 km/h) = front, wheel B (B2:B3 / 10 km/h) = rear. Both "likely". */
    public String rpm = DASH, gear = DASH, wheelFront = DASH, wheelRear = DASH, b1 = DASH,
            counter = DASH, engineTemp = DASH, intakeTemp = DASH, batteryV = DASH, map = DASH;
    /** Engine temp / intake temp = 0x211 B0 / B1 minus 40 C (likely). Battery = 0x211 B2 x 0.1 V (strong evidence). */
    public double batteryVolts = Double.NaN;
    /** b0bit0 = 0x080 B0 bit0: unverified, on whenever the engine is off. neutral = 0x080 B7 bit6 (likely). */
    public boolean b0bit0, neutral, clutch, sideStand;

    // Confidence tags shown next to the tiles (same wording as the PWA).
    public static final String TAG_WHEEL_FRONT = "likely", TAG_WHEEL_REAR = "likely", TAG_ENGINE_TEMP = "likely",
            TAG_INTAKE_TEMP = "likely", TAG_BATTERY = "strong evidence", TAG_NEUTRAL = "likely";
    public static final String LABEL_B0BIT0 = "B0 bit0 (engine-off?)";
    public static final String LABEL_B0BIT0_KNOWN = "unverified: on when engine off (was front brake)";

    public static String gearName(int b) {
        switch (b) {
            case 0x00: return "N";
            case 0x08: return "1";
            case 0x10: return "2";
            case 0x18: return "3";
            case 0x20: return "4";
            case 0x28: return "5";
            case 0x38: return "init";
            default: return "0x" + Text.hex2(b);
        }
    }

    public static String hex2(int b) { return Text.hex2(b); }

    private static final java.util.Map<String, String> KNOWN = new java.util.HashMap<>();
    static {
        KNOWN.put("0x100 B4", "RPM (high byte)");
        KNOWN.put("0x100 B5", "RPM (low byte)");
        KNOWN.put("0x150 B0", "Gear");
        KNOWN.put("0x12B B0", "Front wheel speed (hi) (likely)");
        KNOWN.put("0x12B B1", "Front wheel speed (lo) (likely)");
        KNOWN.put("0x12B B2", "Rear wheel speed (hi) (likely)");
        KNOWN.put("0x12B B3", "Rear wheel speed (lo) (likely)");
        KNOWN.put("0x80 B0", LABEL_B0BIT0_KNOWN);
        KNOWN.put("0x80 B7", "Clutch bit2 / side stand bit5 / neutral bit6 (likely)");
        KNOWN.put("0x211 B0", "Engine temp raw-40 C (likely)");
        KNOWN.put("0x211 B1", "Intake air temp raw-40 C (likely)");
        KNOWN.put("0x211 B2", "Battery voltage raw x0.1 V (strong evidence)");
        KNOWN.put("0x80 B1", "analog (candidate)");
        KNOWN.put("0x140 B1", "counter hi");
        KNOWN.put("0x140 B2", "counter mid");
        KNOWN.put("0x140 B3", "counter lo");
    }

    /** Label of a byte the Hunter decoders already know ("0x100 B4" -> "RPM (high byte)"), or null. Hunter profile only. */
    public static String knownLabel(String byteKey) { return KNOWN.get(byteKey); }

    public static Dashboard from(StatsTable t) {
        Dashboard d = new Dashboard();
        byte[] b;
        if ((b = t.last(0x100)) != null && b.length >= 6) d.rpm = Integer.toString(u(b[4]) << 8 | u(b[5]));
        if ((b = t.last(0x150)) != null && b.length >= 1) d.gear = gearName(u(b[0]));
        if ((b = t.last(0x12B)) != null && b.length >= 4) {
            if (!(u(b[0]) == 0xFF && u(b[1]) == 0xFF)) d.wheelFront = String.format(Locale.ROOT, "%.1f", (u(b[0]) << 8 | u(b[1])) / 10.0);
            if (!(u(b[2]) == 0xFF && u(b[3]) == 0xFF)) d.wheelRear = String.format(Locale.ROOT, "%.1f", (u(b[2]) << 8 | u(b[3])) / 10.0);
        }
        if ((b = t.last(0x80)) != null && b.length >= 8) {
            d.b1 = Integer.toString(u(b[1]));
            d.b0bit0 = (u(b[0]) & 1) != 0;
            d.clutch = (u(b[7]) & 4) != 0;
            d.sideStand = (u(b[7]) & 0x20) != 0;
            d.neutral = (u(b[7]) & 0x40) != 0;
        }
        if ((b = t.last(0x140)) != null && b.length >= 4) d.counter = Integer.toString(u(b[1]) << 16 | u(b[2]) << 8 | u(b[3]));
        if ((b = t.last(0x211)) != null && b.length >= 3) {
            d.engineTemp = Integer.toString(u(b[0]) - 40);
            d.intakeTemp = Integer.toString(u(b[1]) - 40);
            d.batteryVolts = BatteryMonitor.voltsFromRaw(u(b[2]));
            d.batteryV = String.format(Locale.ROOT, "%.1f", d.batteryVolts);
        }
        if ((b = t.last(0x120)) != null && b.length >= 1) d.map = Integer.toString(u(b[0]) * 10);
        return d;
    }

    private static int u(byte b) { return b & 0xFF; }
}
