package com.hunter.canlogger.core;

/**
 * Incremental SLCAN stream parser. Accepts arbitrary chunk boundaries. Same rules as feed() in the PWA:
 * <ul>
 * <li>11-bit frames "t" + 3 hex ID (at most 7FF) + DLC 0..8 + 2*DLC hex, 29-bit frames "T" + 8 hex ID (at most 1FFFFFFF) + DLC + data.
 * Uppercase hex only. IDs above the limit are treated as corrupted lines (the PWA would accept them).</li>
 * <li>Lines end with CR (LF is tolerated). BEL (0x07) is the adapter's error beep: counted and removed anywhere.</li>
 * <li>Remote frames (r/R) are counted and ignored, adapter replies (V..., N...) are ignored silently.</li>
 * <li>A line that is not a clean frame is searched for glued frames ("resync"); if none is found it is "bad".</li>
 * <li>A line longer than 4096 bytes is an overflow: it is dropped up to the next line end.</li>
 * </ul>
 * Not thread-safe by itself; CanEngine serialises access.
 */
public final class SlcanParser {
    public static final int MAX_LINE = 4096;

    private final byte[] line = new byte[MAX_LINE];
    private int len;
    private boolean discarding;
    private final FrameListener listener;

    public long framesOk;
    public long badLines;
    public long resyncs;
    public long overflows;
    public long bells;
    public long rtr;

    public SlcanParser(FrameListener listener) {
        this.listener = listener;
    }

    /** Drops a half line (used on link loss so nothing is glued across a gap). Counters are kept. */
    public void reset() {
        len = 0;
        discarding = false;
    }

    public void resetCounters() {
        framesOk = 0;
        badLines = 0;
        resyncs = 0;
        overflows = 0;
        bells = 0;
        rtr = 0;
    }

    /** Bytes currently held in the partial line buffer. */
    public int pending() { return len; }

    public void feed(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        feed(b, 0, b.length);
    }

    public void feed(byte[] b, int off, int n) {
        for (int i = off; i < off + n; i++) {
            byte c = b[i];
            if (c == 7) { bells++; continue; }
            if (c == '\r' || c == '\n') {
                if (discarding) discarding = false;
                else if (len > 0) processLine();
                len = 0;
            } else if (!discarding) {
                if (len == MAX_LINE) {
                    overflows++;
                    len = 0;
                    discarding = true;
                } else {
                    line[len++] = c;
                }
            }
        }
    }

    private static int hex(byte c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static boolean isHexRun(byte[] a, int from, int to) {
        for (int i = from; i < to; i++) if (hex(a[i]) < 0) return false;
        return true;
    }

    /** Parses line[s, e) as exactly one frame and delivers it. */
    private boolean tryFrame(int s, int e) {
        if (e - s < 5) return false;
        int idDigits;
        boolean ext;
        if (line[s] == 't') { idDigits = 3; ext = false; }
        else if (line[s] == 'T') { idDigits = 8; ext = true; }
        else return false;
        int dlcPos = s + 1 + idDigits;
        if (dlcPos >= e) return false;
        int dlc = line[dlcPos] - '0';
        if (dlc < 0 || dlc > 8 || e - s != 2 + idDigits + 2 * dlc) return false;
        long id = 0;
        for (int i = s + 1; i < dlcPos; i++) {
            int h = hex(line[i]);
            if (h < 0) return false;
            id = id << 4 | h;
        }
        if (id > (ext ? CanId.MAX_EXT : CanId.MAX_STD)) return false; // not a valid 11-bit / 29-bit identifier: corrupted line
        byte[] d = new byte[dlc];
        for (int k = 0; k < dlc; k++) {
            int hi = hex(line[dlcPos + 1 + 2 * k]), lo = hex(line[dlcPos + 2 + 2 * k]);
            if (hi < 0 || lo < 0) return false;
            d[k] = (byte) (hi << 4 | lo);
        }
        framesOk++;
        listener.onFrame((int) id, ext, d);
        return true;
    }

    private boolean isRemoteFrame() {
        // ^[rR][0-9A-F]{3,8}[0-8]$
        if (len < 5 || len > 10) return false;
        if (line[0] != 'r' && line[0] != 'R') return false;
        if (line[len - 1] < '0' || line[len - 1] > '8') return false;
        return isHexRun(line, 1, len - 1);
    }

    private boolean isAdapterReply() {
        // ^[Vv][0-9A-Za-z]*$  or  ^N[0-9A-Za-z]*$
        if (line[0] != 'V' && line[0] != 'v' && line[0] != 'N') return false;
        for (int i = 1; i < len; i++) {
            byte c = line[i];
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!ok) return false;
        }
        return true;
    }

    private void processLine() {
        if (tryFrame(0, len)) return;
        if (isRemoteFrame()) { rtr++; return; }
        if (isAdapterReply()) return;
        // resync: junk glued in front of a frame, or two frames glued together
        int got = 0;
        int i = 0;
        while (i < len) {
            if (line[i] == 't' || line[i] == 'T') {
                int j = i + 1;
                while (j < len && hex(line[j]) >= 0) j++;
                if (tryFrame(i, j)) got++;
                i = j;
            } else {
                i++;
            }
        }
        if (got > 0) resyncs++; else badLines++;
    }
}
