package com.hunter.canlogger.core;

/**
 * Battery voltage state indicator for the Hunter 350 (0x211 B2 x 0.1 V, engine state from 0x100 RPM).
 *
 * This is a voltage-state indicator, NOT a state-of-health measurement. A voltage reading cannot tell how much
 * capacity a battery has left. The thresholds below are typical 12 V lead-acid values, unverified for this bike.
 * Keep the same numbers in index.html (BAT_T).
 */
public final class BatteryMonitor {
    // ---- tunable thresholds: typical 12 V lead-acid thresholds, unverified for this bike -------------------------
    public static final double OFF_GOOD_V = 12.6;      // engine off: >= this is Good
    public static final double OFF_FAIR_V = 12.2;      // engine off: >= this is Fair, below is Low
    public static final double OFF_POOR_V = 11.8;      // engine off: below this is Poor
    public static final double RUN_OK_LO = 13.5;       // running: 13.5 .. 14.8 is Charging OK
    public static final double RUN_OK_HI = 14.8;
    public static final double RUN_CHECK_LO = 13.0;    // running: 13.0 .. 13.5 and 14.8 .. 15.2 is Check; below 13.0 Low charge
    public static final double RUN_CHECK_HI = 15.2;    // running: above this is Overcharge
    public static final double CRANK_GOOD_V = 9.6;     // cranking minimum: >= this is good
    public static final double CRANK_WEAK_V = 9.0;     // >= this is weak, below is poor
    public static final long ENGINE_OFF_AFTER_MS = 2000;   // RPM 0 for longer than this = engine off
    public static final long CRANK_WINDOW_MS = 3000;       // minimum voltage is recorded for this long after RPM rises from 0
    public static final long CRANK_PRE_MS = 500;           // voltage samples this long before RPM rose also count (the dip starts first)
    public static final long STALE_MS = 2000;              // voltage or RPM older than this is not used
    public static final double GAUGE_MIN_V = 10.0;
    public static final double GAUGE_MAX_V = 16.0;
    // ----------------------------------------------------------------------------------------------------------

    public static final String DISCLAIMER = "Voltage state only, not a battery health test. Typical 12 V lead-acid thresholds, unverified for this bike.";

    public enum State { NO_DATA, NO_RPM, SETTLING, OFF, RUNNING, CRANKING }

    /** OK = green, WARN = amber, BAD = red, NONE = grey. The label text always carries the meaning too. */
    public enum Level { NONE, OK, WARN, BAD }

    public static final class View {
        public State state = State.NO_DATA;
        public Level level = Level.NONE;
        public String label = "No voltage";
        public double volts = Double.NaN;
        public double crankMin = Double.NaN;
        public Level crankLevel = Level.NONE;
        public String crankLabel = "";
        public double vMin = Double.NaN, vMax = Double.NaN;
    }

    private double volts = Double.NaN;
    private long voltsAt = Long.MIN_VALUE;
    private double rpm = Double.NaN;
    private long rpmAt = Long.MIN_VALUE;
    private long zeroSince = -1;
    private long crankStart = -1;
    private double crankMin = Double.NaN;
    private double vMin = Double.NaN, vMax = Double.NaN;
    private final long[] preT = new long[16];
    private final double[] preV = new double[16];
    private int preN;

    public synchronized void resetMinMax() { vMin = vMax = Double.NaN; }

    public synchronized void resetAll() {
        volts = Double.NaN; voltsAt = Long.MIN_VALUE; rpm = Double.NaN; rpmAt = Long.MIN_VALUE;
        zeroSince = -1; crankStart = -1; crankMin = Double.NaN; preN = 0; resetMinMax();
    }

    /** Raw 0x211 B2 to volts. */
    public static double voltsFromRaw(int raw) { return (raw & 0xFF) * 0.1; }

    public synchronized void onVolts(double v, long nowMs) {
        volts = v; voltsAt = nowMs;
        if (Double.isNaN(vMin) || v < vMin) vMin = v;
        if (Double.isNaN(vMax) || v > vMax) vMax = v;
        if (crankStart >= 0 && nowMs - crankStart < CRANK_WINDOW_MS && (Double.isNaN(crankMin) || v < crankMin)) crankMin = v;
        if (preN == preT.length) {
            System.arraycopy(preT, 1, preT, 0, preN - 1);
            System.arraycopy(preV, 1, preV, 0, preN - 1);
            preN--;
        }
        preT[preN] = nowMs; preV[preN] = v; preN++;
    }

    public synchronized void onRpm(double r, long nowMs) {
        rpm = r; rpmAt = nowMs;
        if (r > 0) {
            if (zeroSince >= 0 && nowMs - zeroSince >= ENGINE_OFF_AFTER_MS) { // engine was off, RPM rose: a start
                crankStart = nowMs;
                crankMin = Double.NaN;
                for (int i = 0; i < preN; i++) {
                    if (nowMs - preT[i] <= CRANK_PRE_MS && (Double.isNaN(crankMin) || preV[i] < crankMin)) crankMin = preV[i];
                }
            }
            zeroSince = -1;
        } else if (zeroSince < 0) {
            zeroSince = nowMs;
        }
    }

    public static Level offLevel(double v) { return v >= OFF_GOOD_V ? Level.OK : v >= OFF_FAIR_V ? Level.WARN : Level.BAD; }

    public static String offLabel(double v) {
        return v >= OFF_GOOD_V ? "Good" : v >= OFF_FAIR_V ? "Fair" : v >= OFF_POOR_V ? "Low" : "Poor";
    }

    public static Level runLevel(double v) { return v >= RUN_OK_LO && v <= RUN_OK_HI ? Level.OK : v >= RUN_CHECK_LO && v <= RUN_CHECK_HI ? Level.WARN : Level.BAD; }

    public static String runLabel(double v) {
        if (v >= RUN_OK_LO && v <= RUN_OK_HI) return "Charging OK";
        if (v > RUN_CHECK_HI) return "Overcharge";
        if (v < RUN_CHECK_LO) return "Low charge";
        return "Check charging";
    }

    public static Level crankLevelOf(double v) { return v >= CRANK_GOOD_V ? Level.OK : v >= CRANK_WEAK_V ? Level.WARN : Level.BAD; }

    public static String crankLabelOf(double v) { return v >= CRANK_GOOD_V ? "Start: good" : v >= CRANK_WEAK_V ? "Start: weak" : "Start: poor battery"; }

    public synchronized View view(long nowMs) {
        View w = new View();
        w.vMin = vMin; w.vMax = vMax;
        boolean vOk = !Double.isNaN(volts) && nowMs - voltsAt <= STALE_MS;
        boolean rOk = !Double.isNaN(rpm) && nowMs - rpmAt <= STALE_MS;
        if (!Double.isNaN(crankMin)) {
            w.crankMin = crankMin; w.crankLevel = crankLevelOf(crankMin); w.crankLabel = crankLabelOf(crankMin);
        }
        if (!vOk) return w;
        w.volts = volts;
        if (!rOk) {
            w.state = State.NO_RPM; w.label = "Voltage only (no RPM)";
        } else if (rpm > 0) {
            if (crankStart >= 0 && nowMs - crankStart < CRANK_WINDOW_MS) {
                w.state = State.CRANKING; w.label = "Cranking";
            } else {
                w.state = State.RUNNING; w.level = runLevel(volts); w.label = runLabel(volts);
            }
        } else if (zeroSince >= 0 && nowMs - zeroSince >= ENGINE_OFF_AFTER_MS) {
            w.state = State.OFF; w.level = offLevel(volts); w.label = offLabel(volts);
        } else {
            w.state = State.SETTLING; w.label = "Engine just stopped";
        }
        return w;
    }

    /** Text appended to the capture HEALTH line, or empty when no voltage was seen. */
    public synchronized String healthSuffix() {
        if (Double.isNaN(vMin)) return "";
        String s = String.format(java.util.Locale.ROOT, " vmin=%.1f vmax=%.1f", vMin, vMax);
        if (!Double.isNaN(crankMin)) s += String.format(java.util.Locale.ROOT, " crankmin=%.1f", crankMin);
        return s;
    }
}
