package com.hunter.canlogger.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Text capture format, identical to the PWA v2 (and readable by hunter_analyze.py):
 * <pre>
 *   # Hunter live capture, label: X, started &lt;ISO time&gt;
 *   # APP hunter-can-android 2
 *   # PROFILE id=car name=Generic_car bitrate=500000 idfmt=both listen_only=1
 *   # SIGNAL 0x1A0 B2 = Brake bits
 *   # SIGNAL 0x7E8 B3+2 BE signed=0 scale=0.25 offset=0 unit=rpm = Engine speed
 *   &lt;sec.4dp&gt; &lt;ID 3 or 8 hex&gt; &lt;dlc&gt; &lt;hex bytes separated by spaces&gt;
 *   # MARK &lt;sec.3dp&gt; &lt;label&gt;
 *   # DISCONNECT|RECONNECT|USER_DISCONNECT &lt;sec.3dp&gt;
 *   # HEALTH bad=0 resync=0 bel=0 overflow=0 gaps=0 ctrjump=0 load=26 filtered=0
 * </pre>
 */
public final class CaptureFormat {
    private CaptureFormat() {}

    public static final String APP = "hunter-can-android";
    public static final int APP_VERSION = 2;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** Replace line breaks and control characters so a label can never break the line format. */
    public static String cleanLabel(String s, String fallback) { return Text.cleanOr(s, fallback); }

    public static String firstLine(String label, String isoStart) {
        return "# Hunter live capture, label: " + Text.cleanOr(label, "capture") + ", started " + isoStart;
    }

    /** Kept for older callers. */
    public static String header(String label, String isoStart) { return firstLine(label, isoStart); }

    /** Full header: first line, APP, PROFILE, then byte names and decoders of the profile. */
    public static List<String> headerLines(String label, String isoStart, VehicleProfile p, int kbps, IdFormat fmt, SignalSet sig) {
        List<String> h = new ArrayList<>();
        h.add(firstLine(label, isoStart));
        h.add("# APP " + APP + " " + APP_VERSION);
        h.add("# PROFILE id=" + p.id + " name=" + p.headerName() + " bitrate=" + (kbps * 1000) + " idfmt=" + fmt.code + " listen_only=1");
        if (sig != null) {
            for (Map.Entry<String, String> e : sig.names.entrySet()) {
                if (SignalSet.isNameKey(e.getKey())) h.add(signalNameLine(e.getKey(), e.getValue()));
            }
            for (UserSignal d : sig.decoders) h.add(d.headerLine());
        }
        return h;
    }

    public static String signalNameLine(String key, String name) { return "# SIGNAL " + key + " = " + Text.clean(name); }

    private static void appendSeconds(StringBuilder sb, long relNs, int decimals) {
        if (relNs < 0) relNs = 0;
        long unit = decimals == 4 ? 100_000L : 1_000_000L; // ns per last decimal digit
        long t = relNs / unit;
        long pow = decimals == 4 ? 10_000L : 1_000L;
        sb.append(t / pow).append('.');
        String frac = Long.toString(t % pow);
        for (int i = frac.length(); i < decimals; i++) sb.append('0');
        sb.append(frac);
    }

    public static void appendFrame(StringBuilder sb, long relNs, long key, byte[] data) {
        appendSeconds(sb, relNs, 4);
        sb.append(' ').append(CanId.cap(key)).append(' ').append(data.length);
        for (byte b : data) sb.append(' ').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
    }

    public static String frameLine(long relNs, long key, byte[] data) {
        StringBuilder sb = new StringBuilder(48);
        appendFrame(sb, relNs, key, data);
        return sb.toString();
    }

    public static String markLine(long relNs, String label) {
        StringBuilder sb = new StringBuilder("# MARK ");
        appendSeconds(sb, relNs, 3);
        sb.append(' ').append(Text.cleanOr(label, "event"));
        return sb.toString();
    }

    /** name: DISCONNECT, RECONNECT or USER_DISCONNECT. */
    public static String eventLine(String name, long relNs) {
        StringBuilder sb = new StringBuilder("# ").append(name).append(' ');
        appendSeconds(sb, relNs, 3);
        return sb.toString();
    }

    public static String healthLine(HealthCounters h, double load) {
        return "# HEALTH bad=" + h.bad + " resync=" + h.resync + " bel=" + h.bel + " overflow=" + h.overflow
                + " gaps=" + h.gaps + " ctrjump=" + h.ctrJump + " load=" + Math.round(load * 100) + " filtered=" + h.filtered;
    }
}
