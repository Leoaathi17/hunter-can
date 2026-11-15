package com.hunter.canlogger.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-ID statistics: count, rate (Hz), last payload, per-byte change tracking, per-byte value histogram and
 * toggled-bit masks (entropy view), a ring of the last 64 payloads (counter/checksum hints), per-second
 * activity buckets and gap detection (health timeline). Time is injected (ns). Synchronised.
 */
public final class StatsTable {
    public static final long HOT_NS = 1_500_000_000L;
    public static final int RING = 64;
    private static final long HZ_STALE_NS = 2_500_000_000L;
    private static final double NS_PER_MS = 1e6;

    private static final class Stat {
        final long key;
        long n;
        byte[] last;
        long firstNs;
        double hz;
        long wn;
        long wt;
        final long[] ch = new long[8];
        final long[] cnt = new long[8];
        final int[][] hist = new int[8][256];
        final int[] tog = new int[64];
        final ArrayDeque<byte[]> ring = new ArrayDeque<>();
        final long[] bkSec = new long[60];
        final int[] bk = new int[60];
        double perMs;     // smoothed period
        long gapN;
        double gapMaxMs;
        long gapAtNs = -1;
        long seenNs = -1;
        long firstSec;
        long ctr = -1;

        Stat(long key, long nowNs) {
            this.key = key;
            this.firstNs = nowNs;
            this.wt = nowNs;
            this.firstSec = nowNs / 1_000_000_000L;
            java.util.Arrays.fill(bkSec, -1);
        }
    }

    /** Immutable view of one row for the UI. ageNs[i] = -1 when byte i never changed. */
    public static final class Row {
        public final long key;
        public final double hz;
        public final long n;
        public final byte[] data;
        public final long[] ageNs;
        public String names = "";

        Row(long key, double hz, long n, byte[] data, long[] ageNs) {
            this.key = key; this.hz = hz; this.n = n; this.data = data; this.ageNs = ageNs;
        }
    }

    /** Entropy view of one byte position. */
    public static final class ByteStat {
        public final long n;
        public final int distinct;
        public final double entropy;
        public final int min;
        public final int max;
        public final int togMask;
        public final long changes;

        ByteStat(long n, int distinct, double entropy, int min, int max, int togMask, long changes) {
            this.n = n; this.distinct = distinct; this.entropy = entropy; this.min = min; this.max = max;
            this.togMask = togMask; this.changes = changes;
        }
    }

    public static final class Hint {
        public final int byteIdx;
        public final String kind;
        public final String text;

        Hint(int byteIdx, String kind, String text) { this.byteIdx = byteIdx; this.kind = kind; this.text = text; }
    }

    /** One row of the gap timeline: per-second activity of the last 30 s. */
    public static final class Timeline {
        public final long key;
        public final boolean periodic;
        public final int[] counts = new int[30]; // oldest first; -1 = before the ID first appeared
        public final long gaps;
        public final double gapMaxMs;

        Timeline(long key, boolean periodic, long gaps, double gapMaxMs) {
            this.key = key; this.periodic = periodic; this.gaps = gaps; this.gapMaxMs = gapMaxMs;
        }
    }

    private final TreeMap<Long, Stat> ids = new TreeMap<>();
    private long total;
    private long lastFrameNs = -1;

    public static final int GAP_FLAG = 1;

    /** Records one frame. Returns GAP_FLAG when this frame ended a gap on a periodic ID. */
    public synchronized int onFrame(long key, byte[] data, long nowNs) {
        Stat s = ids.get(key);
        if (s == null) {
            s = new Stat(key, nowNs);
            ids.put(key, s);
        }
        int flags = 0;
        total++;
        lastFrameNs = nowNs;
        s.n++;
        s.wn++;
        if (s.seenNs >= 0) {
            double dt = (nowNs - s.seenNs) / NS_PER_MS;
            double thr = Math.max(1000.0, 5 * s.perMs);
            if (s.n > 6 && dt > thr) {
                s.gapN++;
                if (dt > s.gapMaxMs) s.gapMaxMs = dt;
                s.gapAtNs = nowNs;
                flags |= GAP_FLAG;
            } else {
                s.perMs = s.perMs > 0 ? s.perMs * .9 + dt * .1 : dt;
            }
        }
        s.seenNs = nowNs;
        long sec = nowNs / 1_000_000_000L;
        int bi = (int) (sec % 60);
        if (s.bkSec[bi] != sec) { s.bkSec[bi] = sec; s.bk[bi] = 0; }
        s.bk[bi]++;
        for (int i = 0; i < data.length; i++) s.hist[i][data[i] & 0xFF]++;
        if (s.last != null) {
            int m = Math.min(data.length, s.last.length);
            for (int i = 0; i < m; i++) {
                int x = (s.last[i] ^ data[i]) & 0xFF;
                if (x != 0) {
                    s.ch[i] = nowNs;
                    s.cnt[i]++;
                    for (int b = 0; b < 8; b++) if ((x >> b & 1) != 0) s.tog[i * 8 + b]++;
                }
            }
        }
        s.last = data;
        s.ring.addLast(data);
        if (s.ring.size() > RING) s.ring.removeFirst();
        if (nowNs - s.wt >= 1_000_000_000L) {
            s.hz = s.wn * 1e9 / (nowNs - s.wt);
            s.wn = 0;
            s.wt = nowNs;
        }
        return flags;
    }

    /** 24-bit running counter check (Hunter 0x140 B1..B3). Returns true when it jumped by more than 8 since the last frame. */
    public synchronized boolean counterJumped(long key, long value) {
        Stat s = ids.get(key);
        if (s == null) return false;
        boolean jump = false;
        if (s.ctr >= 0) jump = ((value - s.ctr) & 0xFFFFFF) > 8;
        s.ctr = value;
        return jump;
    }

    public synchronized byte[] last(long key) {
        Stat s = ids.get(key);
        return s == null || s.last == null ? null : s.last.clone();
    }

    public synchronized long total() { return total; }

    /** Time of the latest frame, or -1 if none yet. */
    public synchronized long lastFrameNs() { return lastFrameNs; }

    public synchronized int idCount() { return ids.size(); }

    public synchronized List<Long> keys() { return new ArrayList<>(ids.keySet()); }

    public synchronized long countOf(long key) {
        Stat s = ids.get(key);
        return s == null ? 0 : s.n;
    }

    public synchronized List<Row> rows(long nowNs) {
        List<Row> out = new ArrayList<>(ids.size());
        for (Map.Entry<Long, Stat> e : ids.entrySet()) {
            Stat s = e.getValue();
            if (s.last == null) continue;
            long[] age = new long[s.last.length];
            for (int i = 0; i < age.length; i++) age[i] = s.cnt[i] > 0 ? nowNs - s.ch[i] : -1;
            double hz = (nowNs - s.wt > HZ_STALE_NS) ? 0 : s.hz;
            out.add(new Row(e.getKey(), hz, s.n, s.last.clone(), age));
        }
        return out;
    }

    // ---------------------------------------------------------------- byte view

    public synchronized ByteStat byteStat(long key, int i) {
        Stat s = ids.get(key);
        if (s == null || i < 0 || i > 7) return null;
        int[] h = s.hist[i];
        long n = 0;
        int distinct = 0, mn = 255, mx = 0;
        for (int v = 0; v < 256; v++) {
            int c = h[v];
            if (c > 0) { n += c; distinct++; if (v < mn) mn = v; if (v > mx) mx = v; }
        }
        double e = 0;
        if (n > 0) {
            for (int v = 0; v < 256; v++) {
                int c = h[v];
                if (c > 0) { double p = (double) c / n; e -= p * (Math.log(p) / Math.log(2)); }
            }
        }
        int mask = 0;
        for (int b = 0; b < 8; b++) if (s.tog[i * 8 + b] > 0) mask |= 1 << b;
        return new ByteStat(n, distinct, e + 0.0, mn, mx, mask, s.cnt[i]);
    }

    public synchronized int ringSize(long key) {
        Stat s = ids.get(key);
        return s == null ? 0 : s.ring.size();
    }

    /** Counter / checksum hints from the last 64 frames of one ID. These are guesses from patterns, never facts. */
    public synchronized List<Hint> hints(long key) {
        List<Hint> out = new ArrayList<>();
        Stat s = ids.get(key);
        if (s == null || s.ring.size() < 12) return out;
        final byte[][] R = s.ring.toArray(new byte[0][]);
        int len = 8;
        for (byte[] r : R) len = Math.min(len, r.length);
        final int T = R.length - 1;
        for (int j = 0; j < len; j++) {
            if (!varies(R, j)) continue;
            int[] dl = new int[T];
            for (int t = 1; t <= T; t++) dl[t - 1] = ((R[t][j] & 0xFF) - (R[t - 1][j] & 0xFF)) & 255;
            int[] mc = modal(dl);
            boolean tagged = false;
            if (mc[0] != 0 && (double) mc[1] / T >= 0.9 && distinctOf(R, j, 0, 255) >= 4) {
                out.add(new Hint(j, "counter", "B" + j + ": looks like a rolling counter, +" + mc[0] + " per frame (mod 256). Hint only."));
                tagged = true;
            }
            if (!tagged) {
                String[] nm = {"low nibble", "high nibble"};
                int[] sh = {0, 4};
                for (int q = 0; q < 2; q++) {
                    int[] nl = new int[T];
                    for (int t = 1; t <= T; t++) nl[t - 1] = (((R[t][j] & 0xFF) >> sh[q] & 15) - ((R[t - 1][j] & 0xFF) >> sh[q] & 15)) & 15;
                    int[] nc = modal(nl);
                    int dv = distinctOf(R, j, sh[q], 15);
                    if (nc[0] != 0 && (double) nc[1] / T >= 0.9 && dv >= 4) {
                        out.add(new Hint(j, "counter", "B" + j + " " + nm[q] + ": looks like a rolling counter, +" + nc[0] + " per frame (mod 16). Hint only."));
                        tagged = true;
                        break;
                    }
                }
            }
            if (!tagged) {
                int nd = 0;
                for (int t = 1; t <= T; t++) if ((R[t][j] & 0xFF) >= (R[t - 1][j] & 0xFF)) nd++;
                if ((double) nd / T >= 0.98 && (R[T][j] & 0xFF) > (R[0][j] & 0xFF) && distinctOf(R, j, 0, 255) >= 3) {
                    out.add(new Hint(j, "monotonic", "B" + j + ": only ever rises in this window (slow counter, odometer or timer?). Hint only."));
                }
            }
        }
        boolean any = false;
        for (int j = 0; j < len; j++) if (varies(R, j)) { any = true; break; }
        if (len >= 3 && any) {
            for (int j = 0; j < len; j++) {
                if (distinctOf(R, j, 0, 255) < 3) continue;
                boolean others = false;
                for (int k = 0; k < len; k++) if (k != j && varies(R, k)) { others = true; break; }
                if (!others) continue;
                for (int mode = 0; mode < 2; mode++) {
                    int[] cs = new int[R.length];
                    for (int t = 0; t < R.length; t++) {
                        int x = 0;
                        for (int k = 0; k < len; k++) {
                            if (k == j) continue;
                            if (mode == 0) x ^= R[t][k] & 0xFF; else x += R[t][k] & 0xFF;
                        }
                        x &= 255;
                        int a = R[t][j] & 0xFF;
                        cs[t] = mode == 0 ? (a ^ x) : ((a - x) & 255);
                    }
                    int[] mc = modal(cs);
                    if ((double) mc[1] / R.length >= 0.95) {
                        String nm = mode == 0 ? "XOR of the other bytes" : "sum of the other bytes (mod 256)";
                        long pct = (long) Math.floor(mc[1] * 100.0 / R.length + 0.5);
                        out.add(new Hint(j, "checksum", "B" + j + ": matches " + nm + (mc[0] != 0 ? " combined with constant 0x" + Text.hex2(mc[0]) : "")
                                + " in " + pct + "% of frames. Possible checksum, hint only."));
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static boolean varies(byte[][] R, int j) {
        byte f = R[0][j];
        for (byte[] r : R) if (r[j] != f) return true;
        return false;
    }

    private static int distinctOf(byte[][] R, int j, int shift, int mask) {
        boolean[] seen = new boolean[256];
        int n = 0;
        for (byte[] r : R) {
            int v = ((r[j] & 0xFF) >> shift) & mask;
            if (!seen[v]) { seen[v] = true; n++; }
        }
        return n;
    }

    /** {value, count}: the most frequent value; on a tie the first value to reach the top count wins (as in the PWA). */
    private static int[] modal(int[] a) {
        int[] c = new int[256];
        int best = 0, bc = 0;
        for (int x : a) {
            int v = x & 255;
            c[v]++;
            if (c[v] > bc) { bc = c[v]; best = v; }
        }
        return new int[]{best, bc};
    }

    // ---------------------------------------------------------------- gaps / timeline

    /** Periodic IDs (period under 1 s) that have been silent for more than max(1 s, 5 periods). */
    public synchronized List<Long> stalled(long nowNs) {
        List<Long> out = new ArrayList<>();
        for (Stat s : ids.values()) {
            if (s.n > 6 && s.perMs > 0 && s.perMs < 1000 && (nowNs - s.seenNs) / NS_PER_MS > Math.max(1000.0, 5 * s.perMs)) out.add(s.key);
        }
        return out;
    }

    /** IDs whose gap was recorded in the last 30 s. */
    public synchronized List<Long> recentGaps(long nowNs) {
        List<Long> out = new ArrayList<>();
        for (Stat s : ids.values()) if (s.gapAtNs >= 0 && nowNs - s.gapAtNs < 30_000_000_000L) out.add(s.key);
        return out;
    }

    public synchronized long gapCount(long key) {
        Stat s = ids.get(key);
        return s == null ? 0 : s.gapN;
    }

    public synchronized double gapMaxMs(long key) {
        Stat s = ids.get(key);
        return s == null ? 0 : s.gapMaxMs;
    }

    /** Frames seen in wall-second "sec" (nowNs / 1e9) for one ID; 0 if none or the bucket is stale. */
    public synchronized int bucket(long key, long sec) {
        Stat s = ids.get(key);
        if (s == null) return 0;
        int i = (int) (((sec % 60) + 60) % 60);
        return s.bkSec[i] == sec ? s.bk[i] : 0;
    }

    public synchronized double periodMs(long key) {
        Stat s = ids.get(key);
        return s == null ? 0 : s.perMs;
    }

    /** Timeline rows (at most maxRows) for the health tab. */
    public synchronized List<Timeline> timeline(long nowNs, int maxRows) {
        List<Timeline> out = new ArrayList<>();
        long sec = nowNs / 1_000_000_000L;
        for (Stat s : ids.values()) {
            if (out.size() >= maxRows) break;
            Timeline t = new Timeline(s.key, s.perMs > 0 && s.perMs < 1000, s.gapN, s.gapMaxMs);
            for (int c = 0; c < 30; c++) {
                long sc = sec - 30 + c;
                if (sc < s.firstSec) { t.counts[c] = -1; continue; }
                int i = (int) (((sc % 60) + 60) % 60);
                t.counts[c] = s.bkSec[i] == sc ? s.bk[i] : 0;
            }
            out.add(t);
        }
        return out;
    }

    public synchronized void resetGaps() {
        for (Stat s : ids.values()) { s.gapN = 0; s.gapMaxMs = 0; s.gapAtNs = -1; }
    }

    public synchronized void reset() {
        ids.clear();
        total = 0;
        lastFrameNs = -1;
    }
}
