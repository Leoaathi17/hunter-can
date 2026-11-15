package com.hunter.canlogger.core;

import java.util.Arrays;

/** Vehicle profiles: Hunter 350 (with decoders), Generic car, Custom. Same ids, names and notes as the PWA. */
public final class VehicleProfile {
    public final String id;
    public final String name;
    public final int defaultKbps;
    public final String note;
    private final long[] known; // null = not tracked

    private VehicleProfile(String id, String name, int kbps, String note, long[] known) {
        this.id = id;
        this.name = name;
        this.defaultKbps = kbps;
        this.note = note;
        this.known = known;
    }

    public static final VehicleProfile HUNTER = new VehicleProfile("hunter", "Hunter 350", 500,
            "Royal Enfield Hunter 350 decoders. 500 kbps, 11-bit IDs.",
            new long[]{0x80, 0x100, 0x120, 0x12B, 0x140, 0x150, 0x211, 0x280, 0x290, 0x450});
    public static final VehicleProfile CAR = new VehicleProfile("car", "Generic car", 500,
            "No vehicle-specific decoders. Passive OBD-II readings and your own signals. Most cars use 500 kbps; some use 250 or 125.", null);
    public static final VehicleProfile CUSTOM = new VehicleProfile("custom", "Custom", 500,
            "Pick any bitrate and ID format. Passive OBD-II readings and your own signals.", null);

    public static final VehicleProfile[] ALL = {HUNTER, CAR, CUSTOM};

    public static VehicleProfile byId(String id) {
        for (VehicleProfile p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    public boolean isHunter() { return this == HUNTER; }
    public boolean tracksUnknown() { return known != null; }

    public boolean isKnown(long key) {
        if (known == null) return false;
        for (long k : known) if (k == key) return true;
        return false;
    }

    public long[] knownIds() { return known == null ? null : Arrays.copyOf(known, known.length); }

    /** Name as written in the capture header (spaces become underscores). */
    public String headerName() { return name.replaceAll("\\s+", "_"); }
}
