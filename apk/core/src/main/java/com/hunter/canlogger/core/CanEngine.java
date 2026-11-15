package com.hunter.canlogger.core;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure-Java heart of the logger: parser -> ID filter -> stats / health / OBD / user signals -> recording -> test windows.
 * No Android imports. All public methods are thread-safe. Time (ns, monotonic) is always passed in.
 * The engine only LISTENS. It has no way to produce bytes for the adapter.
 */
public final class CanEngine implements FrameListener {
    public enum TestPhase { IDLE, BASELINE, PREPARE, EVENT, DONE }

    public static final long BASELINE_NS = 4_000_000_000L;
    public static final long PREPARE_NS = 2_500_000_000L;
    public static final long EVENT_NS = 4_000_000_000L;
    /** A value is shown as stale when its last frame is older than this. */
    public static final long STALE_NS = 5_000_000_000L;
    /** Live readings older than this no longer count as "no data". */
    public static final long QUIET_NS = 3_000_000_000L;

    // ---------------------------------------------------------------- snapshot types

    public static final class ObdValue {
        public final Obd.Def def;
        public final double value;
        public final boolean stale;
        public final int ecuId;

        ObdValue(Obd.Def def, double value, boolean stale, int ecuId) { this.def = def; this.value = value; this.stale = stale; this.ecuId = ecuId; }
    }

    public static final class SigValue {
        public final UserSignal sig;
        public final boolean hasValue;
        public final double value;
        public final double lo;
        public final double hi;
        public final boolean stale;

        SigValue(UserSignal sig, boolean has, double v, double lo, double hi, boolean stale) {
            this.sig = sig; this.hasValue = has; this.value = v; this.lo = lo; this.hi = hi; this.stale = stale;
        }

        /** 0..1 position inside the gauge range (min/max of the definition, else the observed range). */
        public double fraction() {
            if (!hasValue) return 0;
            double l = sig.min != null ? sig.min : lo, h = sig.max != null ? sig.max : hi;
            if (h <= l) h = l + 1;
            return Math.max(0, Math.min(1, (value - l) / (h - l)));
        }
    }

    /** Main dial values: from Hunter decoders, passive OBD, or user signals with unit rpm / km/h / mph / gear. */
    public static final class Hero {
        public Double rpm;
        public Double speed;
        public String speedUnit = "km/h";
        public String gear;
        public String rpmSrc = "";
        public String speedSrc = "";
    }

    /** Immutable UI snapshot. */
    public static final class Snapshot {
        public boolean linked;
        public String profileId;
        public String profileName;
        public boolean hunter;
        public int rateKbps;
        public IdFormat fmt;
        public Dashboard dash;
        public Hero hero;
        public List<StatsTable.Row> rows;
        public List<ObdValue> obd;
        public List<SigValue> signals;
        public HealthCounters health;
        public HealthStatus status;
        public double load;
        public long totalFrames;
        public long msSinceLastFrame; // -1 if no frame yet
        public long msSinceLink;      // -1 when not linked
        public boolean recording;
        public long recLines;
        public double recSeconds;
        public String recLabel;
        public boolean recFailed;
        public TestPhase testPhase;
        public String testControl;
        public double testSecondsLeft;
        public String testResult;
        public List<Compare.Candidate> candidates;
        public long badLines; // kept for older callers
    }

    // ---------------------------------------------------------------- state

    private final Object lock = new Object();
    private final AppSettings settings;
    private final StatsTable stats = new StatsTable();
    private final SlcanParser parser = new SlcanParser(this);

    private long now; // time of the chunk currently being parsed
    private long framesParsed;
    private boolean linked;
    private long connAt = -1;
    private long lastFrameAt = -1;
    private long configVersion;
    private Runnable settingsListener;

    // health (parser counters live in the parser)
    private long hGaps, hCtrJump, hDlcOdd, hFiltered, hObdReq, hDtcResp;
    private final Map<Long, Integer> hUnknown = new LinkedHashMap<>();
    private double loadBits;
    private long loadT = -1;
    private double load;

    private final Map<Integer, Object[]> obd = new TreeMap<>(); // pid -> {Obd.Def, Double value, Long tNs, Integer ecu}
    private final Map<Integer, double[]> uv = new HashMap<>();   // uid -> {value, tNs, lo, hi}
    private Map<Long, List<UserSignal>> decBy = new HashMap<>();

    private CaptureWriter writer;
    private long recStartNs;
    private String recLabel = "capture";

    private TestPhase phase = TestPhase.IDLE;
    private String testControl;
    private long phaseEndNs;
    private long prepNs = PREPARE_NS, eventNs = EVENT_NS;
    private List<CanFrame> baseWin = new ArrayList<>();
    private List<CanFrame> evWin = new ArrayList<>();
    private String testResult = "";
    private List<Compare.Candidate> candidates = Collections.emptyList();

    public CanEngine() { this(new AppSettings()); }

    public CanEngine(AppSettings s) {
        this.settings = s;
        decBy = settings.signals().index();
    }

    public StatsTable stats() { return stats; }

    // ---------------------------------------------------------------- data path

    public void onBytes(byte[] b, int off, int n, long nowNs) {
        synchronized (lock) {
            now = nowNs;
            parser.feed(b, off, n);
        }
    }

    /** Test helper: feed text. */
    public void onText(String s, long nowNs) {
        synchronized (lock) {
            now = nowNs;
            parser.feed(s);
        }
    }

    private static double frameBits(boolean ext, int n) { return ((ext ? 54 : 34) + 8 * n) * 1.2 + 13; }

    private void loadTick(long n) {
        if (loadT < 0) { loadT = n; return; }
        long dt = n - loadT;
        if (dt >= 1_000_000_000L) {
            load = Math.min(1.5, loadBits * 1e9 / dt / (settings.rate() * 1000.0));
            loadBits = 0;
            loadT = n;
        }
    }

    @Override
    public void onFrame(int id, boolean ext, byte[] data) { // called by the parser while 'lock' is held
        framesParsed++;
        if (!settings.fmt().accepts(ext)) { hFiltered++; return; }
        long key = CanId.key(id, ext);
        loadTick(now);
        loadBits += frameBits(ext, data.length);
        lastFrameAt = now;
        if ((stats.onFrame(key, data, now) & StatsTable.GAP_FLAG) != 0) hGaps++;
        VehicleProfile p = settings.profile();
        if (p.tracksUnknown()) {
            boolean known = p.isKnown(key);
            if (!known) {
                Integer c = hUnknown.get(key);
                hUnknown.put(key, c == null ? 1 : c + 1);
            } else if (data.length < 8) {
                hDlcOdd++;
            }
        }
        if (p.isHunter() && key == 0x140 && data.length >= 4) {
            long v = (data[1] & 0xFF) << 16 | (data[2] & 0xFF) << 8 | (data[3] & 0xFF);
            if (stats.counterJumped(key, v)) hCtrJump++;
        }
        if (!ext) {
            if (Obd.isResponseId(key)) {
                Obd.Reading r = Obd.decodeResponse(data);
                if (r != null) obd.put(r.def.pid, new Object[]{r.def, r.value, now, id});
                else if (Obd.isDtcResponse(data)) hDtcResp++;
            } else if (Obd.isRequestId(key)) {
                hObdReq++;
            }
        }
        List<UserSignal> ds = decBy.get(key);
        if (ds != null) {
            for (UserSignal d : ds) {
                Double v = d.decode(data);
                if (v == null) continue;
                double[] u = uv.get(d.uid);
                if (u == null) { u = new double[]{v, now, v, v}; uv.put(d.uid, u); }
                u[0] = v;
                u[1] = now;
                if (v < u[2]) u[2] = v;
                if (v > u[3]) u[3] = v;
            }
        }
        if (phase == TestPhase.BASELINE) baseWin.add(new CanFrame(0, key, data));
        else if (phase == TestPhase.EVENT) evWin.add(new CanFrame(0, key, data));
        if (writer != null) writer.writeFrame(now - recStartNs, key, data);
    }

    /** All frames the parser delivered, including ones the ID-format filter dropped. */
    public long totalFrames() {
        synchronized (lock) { return framesParsed; }
    }

    // ---------------------------------------------------------------- settings (persisted by the caller)

    public void setSettingsListener(Runnable r) {
        synchronized (lock) { settingsListener = r; }
    }

    private void fire(Runnable r) { if (r != null) r.run(); }

    public String settingsJson() {
        synchronized (lock) { return settings.toJson(); }
    }

    public VehicleProfile profile() {
        synchronized (lock) { return settings.profile(); }
    }

    public int rateKbps() {
        synchronized (lock) { return settings.rate(); }
    }

    public IdFormat idFormat() {
        synchronized (lock) { return settings.fmt(); }
    }

    /** Incremented whenever profile or bitrate changed, so the link layer knows to re-apply C,S<n>,M1,O. */
    public long configVersion() {
        synchronized (lock) { return configVersion; }
    }

    /** Refused (false) while recording. Switching clears the live view and loads that profile's signals. */
    public boolean setProfile(String id) {
        Runnable l;
        synchronized (lock) {
            if (VehicleProfile.byId(id) == null || writer != null) return false;
            if (id.equals(settings.profile)) return true;
            settings.profile = id;
            resetLiveLocked();
            decBy = settings.signals().index();
            configVersion++;
            l = settingsListener;
        }
        fire(l);
        return true;
    }

    /** Refused (false) while recording or for rates the adapter does not have. */
    public boolean setRate(int kbps) {
        Runnable l;
        synchronized (lock) {
            if (!Bitrates.isValid(kbps) || writer != null) return false;
            settings.setRate(settings.profile, kbps);
            resetLiveLocked();
            configVersion++;
            l = settingsListener;
        }
        fire(l);
        return true;
    }

    /** Bitrate found by auto-detect (called while the link already runs at that rate, so no re-apply is needed). */
    public void adoptDetectedRate(int kbps) {
        Runnable l;
        synchronized (lock) {
            if (!Bitrates.isValid(kbps)) return;
            settings.setRate(settings.profile, kbps);
            resetLiveLocked();
            l = settingsListener;
        }
        fire(l);
    }

    public void setIdFormat(IdFormat f) {
        Runnable l;
        synchronized (lock) {
            settings.setFmt(settings.profile, f);
            l = settingsListener;
        }
        fire(l);
    }

    // ---------------------------------------------------------------- user signals and names

    public UserSignal addSignal(UserSignal s) {
        Runnable l;
        UserSignal d;
        synchronized (lock) {
            d = settings.signals().add(s);
            if (d == null) return null;
            decBy = settings.signals().index();
            l = settingsListener;
        }
        fire(l);
        return d;
    }

    public boolean removeSignal(int uid) {
        Runnable l;
        synchronized (lock) {
            if (!settings.signals().remove(uid)) return false;
            uv.remove(uid);
            decBy = settings.signals().index();
            l = settingsListener;
        }
        fire(l);
        return true;
    }

    public List<UserSignal> signalList() {
        synchronized (lock) { return new ArrayList<>(settings.signals().decoders); }
    }

    public Map<String, String> byteNames() {
        synchronized (lock) { return new LinkedHashMap<>(settings.signals().names); }
    }

    /** Names a byte ("0x1A0 B2" -> name). Also written into a running capture as "# SIGNAL". */
    public boolean nameByte(String key, String name) {
        Runnable l;
        synchronized (lock) {
            String n = Text.clean(name);
            if (!SignalSet.isNameKey(key) || n.isEmpty()) return false;
            settings.signals().setName(key, n);
            if (writer != null) writer.writeRaw(CaptureFormat.signalNameLine(key, n));
            l = settingsListener;
        }
        fire(l);
        return true;
    }

    public void clearNames() {
        Runnable l;
        synchronized (lock) {
            settings.signals().names.clear();
            l = settingsListener;
        }
        fire(l);
    }

    public String exportSignals() {
        synchronized (lock) { return settings.signals().exportJson(settings.profile); }
    }

    public int importSignals(String json) {
        Runnable l;
        int n;
        synchronized (lock) {
            n = settings.signals().importJson(json);
            decBy = settings.signals().index();
            l = settingsListener;
        }
        fire(l);
        return n;
    }

    // ---------------------------------------------------------------- live state

    private void resetLiveLocked() {
        stats.reset();
        obd.clear();
        uv.clear();
        framesParsed = 0;
        lastFrameAt = -1;
        parser.reset();
        resetCountersLocked();
        loadBits = 0;
        loadT = -1;
        load = 0;
    }

    public void resetLive() {
        synchronized (lock) { resetLiveLocked(); }
    }

    private void resetCountersLocked() {
        parser.resetCounters();
        hGaps = hCtrJump = hDlcOdd = hFiltered = hObdReq = hDtcResp = 0;
        hUnknown.clear();
        stats.resetGaps();
    }

    /** "Reset counters" on the Health tab. */
    public void resetCounters() {
        synchronized (lock) { resetCountersLocked(); }
    }

    /** Called by the link layer: the adapter is configured and delivering (true) or the link is down (false). */
    public void setLinked(boolean up, long nowNs) {
        synchronized (lock) {
            linked = up;
            if (up) connAt = nowNs;
        }
    }

    /** {frames parsed, error beeps, malformed lines + overflows} for the bitrate auto-detect. */
    public long[] detectCounters() {
        synchronized (lock) { return new long[]{framesParsed, parser.bells, parser.badLines + parser.overflows}; }
    }

    // ---------------------------------------------------------------- recording

    /** Writes the capture header (first line, APP, PROFILE, byte names, decoders) and starts recording. */
    public boolean startRecording(OutputStream out, String label, String isoStart, long nowNs) {
        synchronized (lock) {
            if (writer != null) return false;
            recLabel = Text.cleanOr(label, "capture");
            recStartNs = nowNs;
            writer = new CaptureWriter(out, CaptureFormat.headerLines(recLabel, isoStart, settings.profile(), settings.rate(),
                    settings.fmt(), settings.signals()));
            return true;
        }
    }

    public void setRecordingLabel(String label) {
        synchronized (lock) { if (writer != null) recLabel = Text.cleanOr(label, recLabel); }
    }

    /** Stops recording (writes the HEALTH line first) and closes the stream. Returns lines written (0 if not recording). */
    public long stopRecording() {
        CaptureWriter w;
        synchronized (lock) {
            w = writer;
            if (w != null) {
                w.writeRaw(CaptureFormat.healthLine(healthLocked(), load));
                writer = null;
            }
        }
        if (w == null) return 0;
        long n = w.lines();
        w.close();
        return n;
    }

    public boolean isRecording() {
        synchronized (lock) { return writer != null; }
    }

    /** Called from the 3 s timer. fsync happens outside the engine lock so the read thread is not blocked. */
    public void flushRecording() {
        CaptureWriter w;
        synchronized (lock) { w = writer; }
        if (w != null) w.flush();
    }

    /** Returns the mark time in seconds as text (for logs), or null if not recording. */
    public String mark(String label, long nowNs) {
        synchronized (lock) {
            if (writer == null) return null;
            writer.writeMark(nowNs - recStartNs, label);
            return String.format(Locale.ROOT, "%.3f", (nowNs - recStartNs) / 1e9);
        }
    }

    /** DISCONNECT, RECONNECT or USER_DISCONNECT. Resets the parser so no half line is carried across a link gap. */
    public void linkEvent(String name, long nowNs) {
        synchronized (lock) {
            parser.reset();
            if (writer != null) writer.writeEvent(name, nowNs - recStartNs);
        }
    }

    // ---------------------------------------------------------------- control test / find-signal wizard

    /** Control test: BASELINE (4 s) -> PREPARE (2.5 s) -> EVENT (4 s) -> DONE. Drive with tick(). Returns false if busy. */
    public boolean testBegin(String control, long nowNs) {
        return testBegin(control, BASELINE_NS, PREPARE_NS, EVENT_NS, nowNs);
    }

    /** Same with custom window lengths (the Find-signal wizard uses 3/5/8/12 s). */
    public boolean testBegin(String control, long baselineNs, long prepareNs, long eventNs, long nowNs) {
        synchronized (lock) {
            if (testActiveLocked()) return false;
            testControl = Text.cleanOr(control, "signal");
            baseWin = new ArrayList<>();
            evWin = new ArrayList<>();
            testResult = "";
            candidates = Collections.emptyList();
            prepNs = prepareNs;
            this.eventNs = eventNs;
            phase = TestPhase.BASELINE;
            phaseEndNs = nowNs + baselineNs;
            markLocked("BASELINE start " + testControl, nowNs);
            return true;
        }
    }

    private boolean testActiveLocked() {
        return phase == TestPhase.BASELINE || phase == TestPhase.PREPARE || phase == TestPhase.EVENT;
    }

    public boolean testActive() {
        synchronized (lock) { return testActiveLocked(); }
    }

    /** Advance phases by time. Returns true when a test just finished. */
    public boolean tick(long nowNs) {
        synchronized (lock) {
            boolean finished = false;
            if (phase == TestPhase.BASELINE && nowNs >= phaseEndNs) {
                phase = TestPhase.PREPARE;
                markLocked("EVENT prepare " + testControl, nowNs);
                phaseEndNs = nowNs + prepNs;
            }
            if (phase == TestPhase.PREPARE && nowNs >= phaseEndNs) {
                phase = TestPhase.EVENT;
                markLocked("EVENT start " + testControl, nowNs);
                phaseEndNs = nowNs + eventNs;
            }
            if (phase == TestPhase.EVENT && nowNs >= phaseEndNs) {
                markLocked("EVENT end " + testControl, nowNs);
                candidates = Compare.rank(baseWin, evWin, settings.profile().isHunter() ? Compare.HUNTER_EXCLUDE : null, true);
                StringBuilder sb = new StringBuilder("RESULT: " + testControl + " (candidates, not identifications)\n");
                if (candidates.isEmpty()) sb.append("No byte changed. Try again.");
                for (Compare.Candidate c : candidates) sb.append(c.text()).append('\n');
                testResult = sb.toString().trim();
                phase = TestPhase.DONE;
                baseWin = new ArrayList<>();
                evWin = new ArrayList<>();
                finished = true;
            }
            return finished;
        }
    }

    public void testCancel() {
        synchronized (lock) {
            phase = TestPhase.IDLE;
            baseWin = new ArrayList<>();
            evWin = new ArrayList<>();
        }
    }

    private void markLocked(String text, long nowNs) {
        if (writer != null) writer.writeMark(nowNs - recStartNs, text);
    }

    // ---------------------------------------------------------------- health

    private HealthCounters healthLocked() {
        HealthCounters h = new HealthCounters();
        h.bad = parser.badLines;
        h.resync = parser.resyncs;
        h.bel = parser.bells;
        h.overflow = parser.overflows;
        h.rtr = parser.rtr;
        h.gaps = hGaps;
        h.ctrJump = hCtrJump;
        h.dlcOdd = hDlcOdd;
        h.filtered = hFiltered;
        h.obdReq = hObdReq;
        h.dtcResp = hDtcResp;
        h.unknown.putAll(hUnknown);
        h.unknownTracked = settings.profile().tracksUnknown();
        return h;
    }

    public HealthCounters health() {
        synchronized (lock) { return healthLocked(); }
    }

    public HealthStatus status(long nowNs) {
        synchronized (lock) { return statusLocked(nowNs); }
    }

    private HealthStatus statusLocked(long n) {
        if (!linked) {
            HealthStatus s = new HealthStatus(HealthStatus.IDLE);
            s.reasons.add("Not connected.");
            return s;
        }
        loadTick(n);
        List<String> r = new ArrayList<>();
        int lvl = 0;
        if (n - connAt > QUIET_NS && (lastFrameAt < 0 || n - lastFrameAt > QUIET_NS)) {
            r.add("WARN: No frames for 3 s. Is the ignition on? Is the bitrate right? Try Auto-detect in Settings.");
            lvl = Math.max(lvl, 1);
        }
        long bel = parser.bells;
        if (bel >= 5) { r.add("CHECK: " + bel + " adapter error beeps. Usually wrong bitrate, bad wiring or missing termination."); lvl = 2; }
        else if (bel > 0) { r.add("WARN: " + bel + " adapter error beep(s)."); lvl = Math.max(lvl, 1); }
        long mal = parser.badLines + parser.overflows;
        if (mal > 20) { r.add("CHECK: " + mal + " malformed frames or overflows. Check the cable and USB connection."); lvl = 2; }
        else if (mal > 0) { r.add("WARN: " + mal + " malformed frame(s) dropped."); lvl = Math.max(lvl, 1); }
        if (settings.profile().isHunter() && hCtrJump > 3) { r.add("CHECK: 0x140 counter jumped " + hCtrJump + " times, so frames are being lost."); lvl = 2; }
        int pct = (int) Math.round(load * 100);
        if (load > 0.85) { r.add("CHECK: Bus load about " + pct + "%. Very high."); lvl = 2; }
        else if (load > 0.6) { r.add("WARN: Bus load about " + pct + "%."); lvl = Math.max(lvl, 1); }
        List<Long> st = stats.stalled(n);
        if (!st.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(4, st.size()); i++) { if (i > 0) sb.append(' '); sb.append(CanId.str(st.get(i))); }
            r.add("WARN: " + st.size() + " ID(s) went quiet: " + sb + (st.size() > 4 ? " ..." : ""));
            lvl = Math.max(lvl, 1);
        } else {
            List<Long> rg = stats.recentGaps(n);
            if (!rg.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(4, rg.size()); i++) { if (i > 0) sb.append(' '); sb.append(CanId.str(rg.get(i))); }
                r.add("WARN: Gap in the last 30 s on " + sb + ".");
                lvl = Math.max(lvl, 1);
            }
        }
        HealthStatus s = new HealthStatus(lvl);
        s.reasons.addAll(r);
        return s;
    }

    public double busLoad(long nowNs) {
        synchronized (lock) {
            if (linked) loadTick(nowNs);
            return load;
        }
    }

    // ---------------------------------------------------------------- views for the UI

    private Hero heroLocked(long n) {
        Hero h = new Hero();
        boolean hunter = settings.profile().isHunter();
        if (hunter) {
            byte[] d = stats.last(0x100);
            if (d != null && d.length > 5) { h.rpm = (double) ((d[4] & 0xFF) << 8 | (d[5] & 0xFF)); h.rpmSrc = "0x100"; }
            d = stats.last(0x12B);
            if (d != null && d.length > 1 && !((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFF)) {
                h.speed = ((d[0] & 0xFF) << 8 | (d[1] & 0xFF)) / 10.0;
                h.speedSrc = "wheel A";
            }
            d = stats.last(0x150);
            if (d != null && d.length > 0) h.gear = Dashboard.gearName(d[0] & 0xFF);
        } else {
            Object[] o = obd.get(0x0C);
            if (o != null && n - (Long) o[2] < STALE_NS) { h.rpm = (Double) o[1]; h.rpmSrc = "OBD"; }
            o = obd.get(0x0D);
            if (o != null && n - (Long) o[2] < STALE_NS) { h.speed = (Double) o[1]; h.speedSrc = "OBD"; }
        }
        for (UserSignal d : settings.signals().decoders) {
            double[] u = uv.get(d.uid);
            if (u == null || n - (long) u[1] > STALE_NS) continue;
            String un = d.unit.toLowerCase(Locale.ROOT);
            if (un.equals("rpm") && h.rpm == null) { h.rpm = u[0]; h.rpmSrc = "mine"; }
            else if ((un.equals("km/h") || un.equals("kph") || un.equals("mph")) && h.speed == null) { h.speed = u[0]; h.speedUnit = d.unit; h.speedSrc = "mine"; }
            else if (un.equals("gear") && h.gear == null) { h.gear = Long.toString(Math.round(u[0])); }
        }
        return h;
    }

    public Hero hero(long nowNs) {
        synchronized (lock) { return heroLocked(nowNs); }
    }

    public Snapshot snapshot(long nowNs) {
        Snapshot s = new Snapshot();
        synchronized (lock) {
            VehicleProfile p = settings.profile();
            s.linked = linked;
            s.profileId = p.id;
            s.profileName = p.name;
            s.hunter = p.isHunter();
            s.rateKbps = settings.rate();
            s.fmt = settings.fmt();
            s.dash = p.isHunter() ? Dashboard.from(stats) : new Dashboard();
            s.hero = heroLocked(nowNs);
            s.rows = stats.rows(nowNs);
            Map<String, String> names = settings.signals().names;
            if (!names.isEmpty()) {
                for (StatsTable.Row r : s.rows) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < r.data.length; i++) {
                        String nm = names.get(CanId.byteKey(r.key, i));
                        if (nm != null) { if (sb.length() > 0) sb.append("; "); sb.append(nm); }
                    }
                    r.names = sb.toString();
                }
            }
            s.obd = new ArrayList<>();
            for (Object[] o : obd.values()) s.obd.add(new ObdValue((Obd.Def) o[0], (Double) o[1], nowNs - (Long) o[2] > STALE_NS, (Integer) o[3]));
            s.signals = new ArrayList<>();
            for (UserSignal d : settings.signals().decoders) {
                double[] u = uv.get(d.uid);
                if (u == null) s.signals.add(new SigValue(d, false, 0, 0, 0, true));
                else s.signals.add(new SigValue(d, true, u[0], u[2], u[3], nowNs - (long) u[1] > STALE_NS));
            }
            if (linked) loadTick(nowNs);
            s.load = load;
            s.health = healthLocked();
            s.status = statusLocked(nowNs);
            s.totalFrames = framesParsed;
            s.msSinceLastFrame = lastFrameAt < 0 ? -1 : (nowNs - lastFrameAt) / 1_000_000L;
            s.msSinceLink = linked && connAt >= 0 ? (nowNs - connAt) / 1_000_000L : -1;
            s.badLines = parser.badLines;
            s.recording = writer != null;
            s.recLabel = recLabel;
            if (writer != null) {
                s.recLines = writer.lines();
                s.recSeconds = (nowNs - recStartNs) / 1e9;
                s.recFailed = writer.failed();
            }
            s.testPhase = phase;
            s.testControl = testControl;
            s.testSecondsLeft = testActiveLocked() ? Math.max(0, (phaseEndNs - nowNs) / 1e9) : 0;
            s.testResult = testResult;
            s.candidates = candidates;
        }
        return s;
    }

    /** Gap timeline rows for the Health tab. */
    public List<StatsTable.Timeline> timeline(long nowNs, int maxRows) {
        synchronized (lock) { return stats.timeline(nowNs, maxRows); }
    }

    /** Label of a byte the Hunter decoders already know, or null (always null for other profiles). */
    public String knownLabel(String byteKey) {
        synchronized (lock) { return settings.profile().isHunter() ? Dashboard.knownLabel(byteKey) : null; }
    }

    public String byteName(String byteKey) {
        synchronized (lock) { return settings.signals().names.get(byteKey); }
    }
}
