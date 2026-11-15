package com.hunter.canlogger.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Ranked byte-change comparison between a baseline window and an event window.
 * Score and tie-break follow compare() in hunter_analyze.py (top 12). Like the PWA it can additionally demote bytes
 * that already varied a lot in the baseline ("noisy": more than 8 distinct values) and reports the bit mask
 * that was stable in one window and flipped in the other.
 * Results are CANDIDATES, not identifications.
 */
public final class Compare {
    private Compare() {}

    public static final int MAX_RESULTS = 12;
    /** 0x140 is a running counter on the Hunter and always "changes"; hunter_analyze.py excludes it for Hunter captures. */
    public static final Set<Long> HUNTER_EXCLUDE = Collections.singleton(0x140L);
    public static final Set<Long> DEFAULT_EXCLUDE = HUNTER_EXCLUDE;

    public static final class Candidate {
        public final double score;
        public final long key;
        public final int byteIdx;
        public final int[] baseVals; // ascending, at most 5
        public final int[] evVals;
        public final int bits;
        public final boolean noisy;

        Candidate(double score, long key, int byteIdx, int[] b, int[] e, int bits, boolean noisy) {
            this.score = score; this.key = key; this.byteIdx = byteIdx; this.baseVals = b; this.evVals = e;
            this.bits = bits; this.noisy = noisy;
        }

        private static String join(int[] v) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(Text.hex2(v[i])); }
            return sb.toString();
        }

        public String byteKey() { return CanId.byteKey(key, byteIdx); }

        /** "0x1A0 B2  00 -> 3F  bits 0x3F" (same text as candText in the PWA). */
        public String text() {
            return byteKey() + "  " + join(baseVals) + " -> " + join(evVals) + "  bits 0x" + Text.hex2(bits) + (noisy ? "  (noisy in baseline)" : "");
        }

        @Override
        public String toString() { return text(); }
    }

    private static Map<Long, List<byte[]>> group(List<CanFrame> w) {
        Map<Long, List<byte[]>> m = new TreeMap<>();
        for (CanFrame f : w) {
            List<byte[]> l = m.get(f.key);
            if (l == null) { l = new ArrayList<>(); m.put(f.key, l); }
            l.add(f.data);
        }
        return m;
    }

    private static int at(byte[] d, int k) { return d.length > k ? d[k] & 0xFF : 0; }

    private static int[] firstFive(Set<Integer> s) {
        int n = Math.min(5, s.size());
        int[] r = new int[n];
        int i = 0;
        for (int v : s) { if (i >= n) break; r[i++] = v; }
        return r;
    }

    /** Python-compatible ranking (no noisy demotion). */
    public static List<Candidate> rank(List<CanFrame> base, List<CanFrame> ev, Set<Long> exclude) {
        return rank(base, ev, exclude, false);
    }

    public static List<Candidate> rank(List<CanFrame> base, List<CanFrame> ev, Set<Long> exclude, boolean demoteNoisy) {
        Map<Long, List<byte[]>> B = group(base), E = group(ev);
        Set<Long> allIds = new HashSet<>(B.keySet());
        allIds.addAll(E.keySet());
        if (exclude != null) allIds.removeAll(exclude);
        List<Candidate> out = new ArrayList<>();
        for (long id : allIds) {
            List<byte[]> bl = B.get(id), el = E.get(id);
            if (bl == null || el == null || bl.isEmpty() || el.isEmpty()) continue;
            for (int k = 0; k < 8; k++) {
                TreeSet<Integer> bs = new TreeSet<>(), es = new TreeSet<>();
                long bsum = 0, esum = 0;
                int emin = 255, emax = 0;
                int bAnd = 255, bOr = 0, eAnd = 255, eOr = 0;
                for (byte[] d : bl) { int v = at(d, k); bs.add(v); bsum += v; bAnd &= v; bOr |= v; }
                for (byte[] d : el) { int v = at(d, k); es.add(v); esum += v; if (v < emin) emin = v; if (v > emax) emax = v; eAnd &= v; eOr |= v; }
                if (bs.equals(es)) continue;
                int sym = 0;
                for (int v : bs) if (!es.contains(v)) sym++;
                for (int v : es) if (!bs.contains(v)) sym++;
                double bmean = (double) bsum / bl.size(), emean = (double) esum / el.size();
                double score = sym * 10 + Math.abs(emean - bmean) + (emax - emin);
                boolean noisy = demoteNoisy && bs.size() > 8;
                if (noisy) score = Math.min(score * 0.1, 10);
                int bZero = ~bOr & 255, eZero = ~eOr & 255;
                int bits = ((bAnd & eZero) | (bZero & eAnd)) & 255;
                out.add(new Candidate(score, id, k, firstFive(bs), firstFive(es), bits, noisy));
            }
        }
        // Python: sort tuples (score, id, k, ...) descending
        Collections.sort(out, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                int c = Double.compare(b.score, a.score);
                if (c != 0) return c;
                if (a.key != b.key) return a.key > b.key ? -1 : 1;
                return Integer.compare(b.byteIdx, a.byteIdx);
            }
        });
        return out.size() > MAX_RESULTS ? new ArrayList<>(out.subList(0, MAX_RESULTS)) : out;
    }
}
