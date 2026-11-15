package com.hunter.canlogger.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Plain main() harness (no JUnit, javac only).
 * Usage: CoreTest sample.txt expectedDir synth.txt hunter_analyze.py appSrcDir
 */
public final class CoreTest {
    static int passed, failed;
    static final Charset L1 = Charset.forName("ISO-8859-1");
    static String analyzer, outDir, appDir;
    static String sampleStr;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  " + detail); }
    }

    static void check(String name, boolean ok) { check(name, ok, ""); }

    // ------------------------------------------------------------------ helpers

    static String fr(long id, boolean ext, int... d) {
        StringBuilder sb = new StringBuilder();
        if (ext) sb.append('T').append(String.format("%08X", id)); else sb.append('t').append(String.format("%03X", id));
        sb.append(d.length);
        for (int b : d) sb.append(String.format("%02X", b & 0xFF));
        return sb.append('\r').toString();
    }

    static String fr(long id, int... d) { return fr(id, false, d); }

    static byte[] bytes(String s) { return s.getBytes(L1); }

    /** Collects frames and a CRC identical to tools/make_expected.py (11-bit frames only). */
    static final class Collector implements FrameListener {
        long n; int rpmMax = -1; final CRC32 crc = new CRC32();
        final Map<Integer, Integer> per = new TreeMap<>();
        final List<String> all = new ArrayList<>();
        @Override public void onFrame(int id, boolean ext, byte[] d) {
            n++;
            all.add((ext ? "T" : "t") + String.format("%X", id) + ":" + hex(d));
            if (ext) return;
            per.merge(id, 1, Integer::sum);
            if (id == 0x100 && d.length >= 6) rpmMax = Math.max(rpmMax, (d[4] & 0xFF) << 8 | (d[5] & 0xFF));
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%03X", id));
            for (byte b : d) sb.append(String.format("%02X", b));
            sb.append('\n');
            crc.update(sb.toString().getBytes(L1));
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    static Collector run(byte[] data, int mode, long seed) {
        Collector c = new Collector();
        SlcanParser p = new SlcanParser(c);
        if (mode == 0) { p.feed(data, 0, data.length); return c; }
        Random r = new Random(seed);
        int pos = 0;
        while (pos < data.length) {
            int n = mode == 1 ? 1 : 1 + r.nextInt(mode);
            n = Math.min(n, data.length - pos);
            p.feed(data, pos, n);
            pos += n;
        }
        return c;
    }

    static Map<String, String> readKv(String path) throws IOException {
        Map<String, String> m = new TreeMap<>();
        for (String l : Files.readAllLines(Paths.get(path), L1)) {
            String[] p = l.split(" ");
            if (p[0].equals("id")) m.put("id " + p[1], p[2]);
            else if (p.length == 2) m.put(p[0], p[1]);
        }
        return m;
    }

    static String py(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("python3"); cmd.add("-I");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        java.io.InputStream in = pr.getInputStream();
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        int rc = pr.waitFor();
        String out = new String(bo.toByteArray(), "UTF-8");
        if (rc != 0) throw new IOException("python exit " + rc + ": " + out);
        return out;
    }

    static String analyze(String file) throws Exception { return py(analyzer, file); }

    static boolean find(String text, String regex) { return Pattern.compile(regex).matcher(text).find(); }

    static String grab(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Engine with a virtual clock. */
    static final class Rig {
        final CanEngine e;
        long t = 100_000_000_000L;
        Rig(CanEngine e) { this.e = e; }
        Rig() { this(new CanEngine(new AppSettings())); }
        Rig send(String s) { e.onText(s, t); return this; }
        Rig adv(long ms) { t += ms * 1_000_000L; return this; }
        Rig linked() { e.setLinked(true, t); return this; }
        Rig profile(String id) { e.setProfile(id); return this; }
    }

    static List<String> recordLines(Rig r, Runnable body) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        r.e.startRecording(bo, "t", "2026-01-01T00:00:00Z", r.t);
        body.run();
        r.e.stopRecording();
        return Arrays.asList(new String(bo.toByteArray(), L1).split("\n"));
    }

    /** Mock CANable: tracks the commands, feeds frames according to the current bitrate while "time" passes. */
    static final class MockBus implements BitrateDetector.Env {
        final CanEngine eng;
        final List<String> cmds = new ArrayList<>();
        int rateCode = -1;
        long t = 500_000_000_000L;
        final java.util.function.IntFunction<String> gen; // code -> text per 10 ms step
        final StringBuilder progress = new StringBuilder();
        MockBus(CanEngine e, java.util.function.IntFunction<String> gen) { this.eng = e; this.gen = gen; }
        @Override public void send(String c) throws IOException {
            CommandGuard.requireAllowed(c);
            cmds.add(c);
            if (c.startsWith("S")) rateCode = c.charAt(1) - '0';
        }
        @Override public void sleep(long ms) {
            for (long i = 0; i < ms; i += 10) {
                t += 10_000_000L;
                String s = rateCode >= 0 ? gen.apply(rateCode) : "";
                if (!s.isEmpty()) eng.onText(s, t);
            }
        }
        @Override public long frames() { return eng.detectCounters()[0]; }
        @Override public long beeps() { return eng.detectCounters()[1]; }
        @Override public long malformed() { return eng.detectCounters()[2]; }
        @Override public void progress(String s) { progress.append(s).append('\n'); }
    }

    static BitrateDetector.Timing fastTiming() {
        BitrateDetector.Timing t = new BitrateDetector.Timing();
        t.cmdMs = 10; t.settleMs = 20; t.detectMs = 150;
        return t;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] a) throws Exception {
        String samplePath = a[0], expDir = a[1], synthPath = a[2];
        sampleFile = samplePath;
        analyzer = a[3];
        appDir = a.length > 4 ? a[4] : null;
        outDir = expDir;
        byte[] sample = Files.readAllBytes(Paths.get(samplePath));
        sampleStr = new String(sample, L1);
        byte[] conv = Files.readAllBytes(Paths.get(expDir, "sample_slcan.txt"));
        Map<String, String> exp = readKv(expDir + "/sample_expected.txt");
        long expN = Long.parseLong(exp.get("frames"));
        int expRpm = Integer.parseInt(exp.get("rpmmax"));
        long expCrc = Long.parseLong(exp.get("crc"));

        testParserRealCapture(sample, conv, exp, expN, expRpm, expCrc);
        testChunks(sample, expN, expCrc);
        testGarbage(sample, expN);
        testPwaResync();
        test29Bit();
        testGuard();
        testBitrates();
        testAutoDetect();
        testProfiles();
        testSettingsJson();
        testUserSignalMath();
        testUserSignalValidation();
        testObd();
        testHealth();
        testLoadAndGaps();
        testEntropyAndHints();
        testCompare(synthPath, expDir);
        testWizard();
        testControlTestPhases();
        testStatsAndDashboard();
        testCaptureFormatAndWriter(sample);
        testRoundTripCar();
        testRoundTripHunter(sample);
        testLegacyLogs();
        testReconnectEvents();
        testRealCaptures(samplePath);
        testBatteryThresholds();
        testGapRule();
        testSourceAudit();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ parser

    static void testParserRealCapture(byte[] sample, byte[] conv, Map<String, String> exp, long expN, int expRpm, long expCrc) {
        for (int pass = 0; pass < 2; pass++) {
            byte[] d = pass == 0 ? sample : conv;
            String nm = pass == 0 ? "raw sample.txt" : "python-converted sample";
            Collector c = run(d, 0, 0);
            check(nm + ": frame count == Python (" + expN + ")", c.n == expN, "got " + c.n);
            check(nm + ": RPM max == " + expRpm, c.rpmMax == expRpm, "got " + c.rpmMax);
            check(nm + ": frame-sequence CRC equals Python", c.crc.getValue() == expCrc, "got " + c.crc.getValue());
            boolean perOk = c.per.size() == countIds(exp);
            for (Map.Entry<Integer, Integer> e : c.per.entrySet()) {
                String v = exp.get(String.format("id %03X", e.getKey()));
                if (v == null || Integer.parseInt(v) != e.getValue()) perOk = false;
            }
            check(nm + ": per-ID counts equal Python", perOk, c.per.toString());
        }
        SlcanParser p = new SlcanParser(new Collector());
        p.feed(sample, 0, sample.length);
        check("real capture: no malformed, resync, beep or overflow counted",
                p.badLines == 0 && p.resyncs == 0 && p.bells == 0 && p.overflows == 0 && p.framesOk == expN,
                p.badLines + "/" + p.resyncs + "/" + p.bells + "/" + p.overflows);
    }

    static void testChunks(byte[] sample, long expN, long expCrc) {
        boolean allSame = true;
        String why = "";
        int[] modes = {1, 2, 3, 7, 37, 63, 64, 100, 4096};
        for (int m : modes) {
            for (long seed = 1; seed <= 5; seed++) {
                Collector c = run(sample, m, seed);
                if (c.n != expN || c.crc.getValue() != expCrc) { allSame = false; why += " mode=" + m + " seed=" + seed + " n=" + c.n; }
            }
        }
        check("split chunks (1 byte, 2, 3, 7, 37, 63, 64, 100, 4096, random sizes, 5 seeds each) give identical frames", allSame, why);
    }

    static void testGarbage(byte[] sample, long expN) {
        Random r = new Random(42);
        ByteArrayOutputStream g = new ByteArrayOutputStream();
        int off = 0, garbageLines = 0;
        while (off < sample.length) {
            int end = off;
            for (int k = 0; k < 50 && end < sample.length; k++) { while (sample[end] != '\r') end++; end++; }
            g.write(sample, off, end - off);
            off = end;
            int kind = r.nextInt(6);
            garbageLines++;
            switch (kind) {
                case 0: { int n = 1 + r.nextInt(40); for (int i = 0; i < n; i++) { int b; do { b = r.nextInt(256); } while (b == 13 || b == 10 || b == 7 || b == 't' || b == 'T'); g.write(b); } g.write('\r'); break; }
                case 1: g.write(bytes("t1238AABBCC\r"), 0, 12); break;                // too short
                case 2: g.write(bytes("t12B8FFFFFFFF0400000000\r"), 0, 23); break;     // too long
                case 3: g.write(bytes("T1234567881122334455667788\r"), 0, 27); break;  // 29-bit frame, valid -> extra frame
                case 4: g.write(bytes("t12b8ffffffff04000000\r"), 0, 22); break;       // lowercase rejected
                default: g.write(7); g.write('\r'); g.write('\r'); g.write('\r'); break;
            }
        }
        byte[] big = new byte[10000];
        Arrays.fill(big, (byte) 'x');
        g.write(big, 0, big.length);
        g.write('\r');
        int head = firstCr(sample, 20);
        g.write(sample, 0, head);
        byte[] garbage = g.toByteArray();
        long extra = countFrames(sample, head);
        // kind 3 lines are valid 29-bit frames and count as frames
        long ext29 = 0;
        Random r2 = new Random(42);
        // recount deterministically: run the generator logic again for kind==3
        int off2 = 0;
        while (off2 < sample.length) {
            int end = off2;
            for (int k = 0; k < 50 && end < sample.length; k++) { while (sample[end] != '\r') end++; end++; }
            off2 = end;
            int kind = r2.nextInt(6);
            if (kind == 0) { int n = 1 + r2.nextInt(40); for (int i = 0; i < n; i++) { int b; do { b = r2.nextInt(256); } while (b == 13 || b == 10 || b == 7 || b == 't' || b == 'T'); } }
            if (kind == 3) ext29++;
        }
        for (int m : new int[]{0, 1, 17, 64}) {
            Collector c = run(garbage, m, 9);
            long std = c.all.size() - ext29;
            check("garbage injected (" + garbageLines + " bad lines, 10 kB junk line), chunk mode " + m + ": all " + (expN + extra) + " good 11-bit frames survive, none invented",
                    std == expN + extra, "got " + std + " std + " + ext29 + " ext (all " + c.all.size() + ")");
        }
        Collector pure = new Collector();
        SlcanParser pp = new SlcanParser(pure);
        byte[] rnd = new byte[200000];
        new Random(7).nextBytes(rnd);
        pp.feed(rnd, 0, rnd.length);
        check("200 kB of random bytes: no crash, nothing invented (" + pure.n + " frames)", pure.n == 0, "frames=" + pure.n);
        Collector c2 = new Collector();
        SlcanParser p2 = new SlcanParser(c2);
        p2.feed("t12B8FFFF\rt12B8FFFFFFFF04000000\r");
        check("truncated line then good frame -> 1 frame, 1 malformed", c2.n == 1 && p2.badLines == 1, "n=" + c2.n);
        p2.reset();
        p2.feed("t12B8FFFF");
        p2.reset();
        p2.feed("t12B8FFFFFFFF04000000\r");
        check("reset() drops a half line (used on link loss)", c2.n == 2, "n=" + c2.n);
    }

    static void testPwaResync() {
        Collector c = new Collector();
        SlcanParser p = new SlcanParser(c);
        p.feed("t1008" + "00000000000000" + "00" + "\r");                     // 1 good
        p.feed("t100"); p.feed("8" + "0102030405060708\r");                   // 2 good, split
        p.feed("xx\u0007\r");                                                  // bell + junk -> bel1, bad1
        p.feed("GARBAGEt1008AABBCCDDEEFF0011\r");                              // resync, 1 frame
        p.feed("t1008AABB\r");                                                 // truncated -> bad
        p.feed("t2008" + "11111111111111" + "11" + "t3008" + "22222222222222" + "22" + "\r"); // two glued frames, 1 resync
        p.feed("###\r");                                                       // bad
        p.feed("t10\u000708" + "3333333333333333" + "\r");                     // bell inside a frame
        p.feed("zzT18DAF1102AABB\r");                                          // junk before a 29-bit frame
        p.feed("t1230\r");                                                     // dlc 0
        p.feed("V1013\r\r");                                                   // adapter version reply
        p.feed("r1238\r");                                                     // RTR
        check("resync scenarios: 8 frames delivered", c.n == 8, "n=" + c.n);
        check("resync scenarios: bel=2 resync=3 bad=3 rtr=1 (same as the PWA test)",
                p.bells == 2 && p.resyncs == 3 && p.badLines == 3 && p.rtr == 1, p.bells + "/" + p.resyncs + "/" + p.badLines + "/" + p.rtr);
        check("resync: 29-bit frame glued behind junk is recovered", c.all.contains("T18DAF110:AABB"), c.all.toString());
        check("resync: bell inside a frame does not break it", c.all.contains("t100:3333333333333333"), c.all.toString());
        Collector c3 = new Collector();
        SlcanParser p3 = new SlcanParser(c3);
        char[] a5 = new char[5000];
        Arrays.fill(a5, 'A');
        p3.feed(new String(a5));
        check("5000 junk characters: overflow counted once, buffer cleared", p3.overflows == 1 && p3.pending() == 0, p3.overflows + "/" + p3.pending());
        p3.feed("\rt1230\r");
        check("after an overflow the next frame is parsed", c3.n == 1, "n=" + c3.n);
        Collector c4 = new Collector();
        SlcanParser p4 = new SlcanParser(c4);
        p4.feed("T1FFFFFFF1AA\rT200000001AA\rt8001AA\r");
        check("IDs above 0x1FFFFFFF (29-bit) and above 0x7FF (11-bit) are rejected as corrupt, max 29-bit ID accepted", c4.n == 1 && p4.badLines == 2, "n=" + c4.n + " bad=" + p4.badLines);
    }

    // ------------------------------------------------------------------ 29-bit

    static void test29Bit() throws Exception {
        Rig r = new Rig();
        r.send(fr(0x18DAF110L, true, 1, 2, 3, 4, 5, 6, 7, 8)).send(fr(0x100, 9, 9)).send(fr(0x100, true, 7, 7, 7)).send(fr(0x1FFFFFFFL, true, 0xAA));
        List<Long> keys = r.e.stats().keys();
        List<String> names = new ArrayList<>();
        for (long k : keys) names.add(CanId.str(k));
        check("29-bit: T frames parsed, IDs shown as 8 hex digits, no collision with the 11-bit ID of the same number",
                names.size() == 4 && names.containsAll(Arrays.asList("0x00000100", "0x100", "0x18DAF110", "0x1FFFFFFF")), names.toString());
        check("29-bit: capture ID text is 8 digits (29-bit) and 3 digits (11-bit)",
                CanId.cap(CanId.key(0x18DAF110L, true)).equals("18DAF110") && CanId.cap(0x80).equals("080"));
        Rig r2 = new Rig();
        List<String> lines = recordLines(r2, () -> {
            r2.adv(10).send(fr(0x18DAF110L, true, 0xDE, 0xAD));
            r2.adv(10).send(fr(0x7E8, 3, 0x41, 0x0D, 0x3C));
        });
        check("29-bit: recorded line formats (8-digit ext ID, 3-digit std ID)",
                lines.stream().anyMatch(l -> l.matches("^\\d+\\.\\d{4} 18DAF110 2 DE AD$")) && lines.stream().anyMatch(l -> l.matches("^\\d+\\.\\d{4} 7E8 4 03 41 0D 3C$")), lines.toString());
        // filter
        Rig f = new Rig();
        f.e.setIdFormat(IdFormat.STD11);
        f.send(fr(0x18DAF110L, true, 1)).send(fr(0x555, 1));
        check("ID format 11-bit only: 29-bit frame is parsed but filtered, 11-bit kept",
                f.e.health().filtered == 1 && f.e.totalFrames() == 2 && f.e.stats().countOf(0x555) == 1 && f.e.stats().keys().size() == 1);
        f.e.setIdFormat(IdFormat.EXT29);
        f.send(fr(0x556, 1));
        check("ID format 29-bit only: 11-bit frame is filtered", f.e.health().filtered == 2);
        check("filtered frames are not recorded", recordLines(f, () -> f.send(fr(0x557, 1)).send(fr(0x18DAF110L, true, 1))).toString().contains("18DAF110 1 01")
                && !recordLines(f, () -> f.send(fr(0x557, 1))).toString().contains(" 557 "));
        check("ID format codes round trip", IdFormat.fromCode("11") == IdFormat.STD11 && IdFormat.fromCode("29") == IdFormat.EXT29 && IdFormat.fromCode("x") == IdFormat.BOTH);
    }

    // ------------------------------------------------------------------ command guard

    static void testGuard() throws Exception {
        String[] good = {"C", "S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "M1", "O", "V"};
        boolean allGood = true;
        for (String g : good) if (!CommandGuard.isAllowed(g)) allGood = false;
        check("CommandGuard accepts C, S0-S8, M1, O, V", allGood);
        String[] bad = {"t1238AABBCCDDEEFF0011", "T18DAF1108AABBCCDDEEFF0011", "t1000", "T00000000", "S9", "S10", "S-1", "S", "S 6", "s6", "S06", "M0", "M2", "m1",
                "o", "c", "v", "X", "Z", "z", "F", "W", "U", "P", "", "C\r", "C\n", "O\rt1231AA", "\r", "S6\rS7", "l", "L", "A", "B", "r1230", "R1230", "t", "T", "t1230\r", "Z1", "X1", "s", "m"};
        List<String> leaked = new ArrayList<>();
        for (String b : bad) if (CommandGuard.isAllowed(b)) leaked.add(b);
        check("CommandGuard rejects data frames (t/T/r), S9, S10, S-1, M0, lowercase, embedded CR, empty (" + bad.length + " strings)", leaked.isEmpty(), leaked.toString());
        check("CommandGuard rejects null", !CommandGuard.isAllowed(null));
        int thrown = 0;
        for (String b : new String[]{"t1230", "T18DAF1100", "S9", "M0", ""}) {
            try { CommandGuard.wireBytes(b); } catch (IllegalArgumentException e) { thrown++; }
        }
        check("wireBytes throws for blocked commands", thrown == 5, "thrown=" + thrown);
        byte[] wb = CommandGuard.wireBytes("S6");
        check("wire bytes = 'S6' + CR", wb.length == 3 && wb[0] == 'S' && wb[1] == '6' && wb[2] == '\r');
        boolean sendThrows = false;
        MockBus mb = new MockBus(new CanEngine(), c -> "");
        try { mb.send("t1231AA"); } catch (IllegalArgumentException e) { sendThrows = true; }
        check("a sink that uses the guard cannot send a data frame", sendThrows && mb.cmds.isEmpty());
    }

    static void testBitrates() throws Exception {
        int[] k = {10, 20, 50, 100, 125, 250, 500, 750, 1000};
        boolean ok = true;
        for (int i = 0; i < k.length; i++) if (!Bitrates.command(k[i]).equals("S" + i) || Bitrates.code(k[i]) != i) ok = false;
        check("bitrate table: 10k..1M -> S0..S8", ok);
        boolean refused = false;
        try { Bitrates.command(300); } catch (IllegalArgumentException e) { refused = true; }
        check("300 kbps refused (no S code)", refused && !Bitrates.isValid(300) && Bitrates.code(0) == -1);
        check("detect order is 500, 250, 125, 1000", Arrays.equals(Bitrates.DETECT_ORDER, new int[]{500, 250, 125, 1000}));
        boolean seqOk = true;
        for (int kk : k) {
            MockBus mb = new MockBus(new CanEngine(), c -> "");
            SlcanSetup.apply(mb, kk, 1);
            if (!mb.cmds.equals(Arrays.asList("C", Bitrates.command(kk), "M1", "O"))) seqOk = false;
        }
        check("setup sequence is always C, S<n>, M1, O (M1 listen-only before O)", seqOk);
        MockBus mb = new MockBus(new CanEngine(), c -> "");
        boolean thr = false;
        try { SlcanSetup.apply(mb, 300, 1); } catch (IllegalArgumentException e) { thr = true; }
        check("setup(300) throws and sends nothing", thr && mb.cmds.isEmpty());
    }

    // ------------------------------------------------------------------ auto-detect with a mock source

    static void testAutoDetect() throws Exception {
        // frames only at 250k (S5); at 500k the adapter beeps
        CanEngine e1 = new CanEngine();
        MockBus m1 = new MockBus(e1, code -> code == 5 ? fr(0x1A0, 1, 2, 3, 4, 5, 6, 7, 8) + fr(0x1B0, 8, 7, 6, 5, 4, 3, 2, 1) + fr(0x1A0, 1, 2) :
                (code == 6 ? "\u0007\u0007" : ""));
        int found = BitrateDetector.run(m1, 500, fastTiming());
        List<String> sc = new ArrayList<>();
        for (String c : m1.cmds) if (c.matches("S\\d")) sc.add(c);
        check("auto-detect: frames only at 250k -> picks 250k, 500k rejected for error beeps", found == 250, "found=" + found);
        check("auto-detect: tried 500 then 250 and stopped", sc.equals(Arrays.asList("S6", "S5")), sc.toString());
        long m1count = 0;
        for (String c : m1.cmds) if (c.equals("M1")) m1count++;
        check("auto-detect: M1 before every O, only setup commands sent", m1count == 2 && onlyAllowed(m1.cmds), m1.cmds.toString());
        check("auto-detect: adapter left at 250k", m1.rateCode == 5);
        Rig adopt = new Rig();
        adopt.e.setProfile("car");
        adopt.e.adoptDetectedRate(found);
        check("auto-detect result adopted for the profile and persisted in settings", adopt.e.rateKbps() == 250 && adopt.e.settingsJson().contains("\"car\":250"), adopt.e.settingsJson());

        // order 500,250,125,1000: frames only at 1M
        CanEngine e2 = new CanEngine();
        MockBus m2 = new MockBus(e2, code -> code == 8 ? fr(0x100, 1, 2) + fr(0x100, 1, 2) : "");
        int f2 = BitrateDetector.run(m2, 500, fastTiming());
        List<String> sc2 = new ArrayList<>();
        for (String c : m2.cmds) if (c.matches("S\\d")) sc2.add(c);
        check("auto-detect: order S6,S5,S4,S8, selects 1M", f2 == 1000 && sc2.equals(Arrays.asList("S6", "S5", "S4", "S8")), f2 + " " + sc2);

        // nothing works: previous rate restored
        CanEngine e3 = new CanEngine();
        MockBus m3 = new MockBus(e3, code -> "");
        int f3 = BitrateDetector.run(m3, 125, fastTiming());
        check("auto-detect: nothing found -> 0 and previous rate (125k = S4) restored", f3 == 0 && m3.rateCode == 4 && onlyAllowed(m3.cmds), f3 + " code=" + m3.rateCode);
        check("auto-detect: failure message names the rates tried", m3.progress.toString().contains("No clean traffic at 500k, 250k, 125k or 1M"));

        // noisy rate rejected: frames plus a malformed line > 5%
        CanEngine e4 = new CanEngine();
        MockBus m4 = new MockBus(e4, code -> code == 6 ? fr(0x100, 1) + "zzzz\r" : (code == 5 ? fr(0x100, 1) + fr(0x101, 2) : ""));
        int f4 = BitrateDetector.run(m4, 500, fastTiming());
        check("auto-detect: a rate with many malformed lines is rejected", f4 == 250, "found " + f4);
        // too few frames
        CanEngine e5 = new CanEngine();
        final int[] n5 = {0};
        MockBus m5 = new MockBus(e5, code -> (code == 6 && n5[0]++ < 1) ? fr(0x100, 1) : "");
        check("auto-detect: fewer than 3 frames is not enough", BitrateDetector.run(m5, 500, fastTiming()) == 0);
    }

    static boolean onlyAllowed(List<String> cmds) {
        for (String c : cmds) if (!c.matches("^(C|S[0-8]|M1|O|V)$")) return false;
        return true;
    }

    // ------------------------------------------------------------------ profiles + settings

    static void testProfiles() {
        Rig r = new Rig();
        r.linked();
        r.send(fr(0x100, 0, 0, 0, 0, 0x0F, 0xA0, 0, 0)).send(fr(0x150, 0x18, 0, 0, 0, 0, 0, 0, 0)).send(fr(0x12B, 0x03, 0xE8, 0xFF, 0xFF, 0, 0, 0, 0));
        CanEngine.Hero h = r.e.hero(r.t);
        check("Hunter hero: RPM 4000, gear 3, speed 100 from wheel A", h.rpm != null && h.rpm == 4000 && "3".equals(h.gear) && h.speed != null && h.speed == 100, h.rpm + " " + h.gear + " " + h.speed);
        check("Hunter: known byte label shown", "RPM (high byte)".equals(r.e.knownLabel("0x100 B4")));
        check("Hunter: unknown-ID tracking is on", r.e.health().unknownTracked && r.e.health().unknown.isEmpty());
        r.send(fr(0x333, 1));
        check("Hunter: unknown ID 0x333 tracked", r.e.health().unknown.containsKey(0x333L));
        check("profile switch to car: refused while recording", recordLines(r, () -> check("  (setProfile returns false)", !r.e.setProfile("car"))).size() > 0 && r.e.profile().id.equals("hunter"));
        check("switch to car accepted", r.e.setProfile("car") && r.e.profile().id.equals("car"));
        check("state reset on profile switch", r.e.stats().idCount() == 0 && r.e.totalFrames() == 0);
        r.send(fr(0x100, 0, 0, 0, 0, 0x0F, 0xA0, 0, 0)).send(fr(0x150, 0x18, 0, 0, 0, 0, 0, 0, 0)).send(fr(0x12B, 0x03, 0xE8, 0xFF, 0xFF, 0, 0, 0, 0));
        h = r.e.hero(r.t);
        check("car hero ignores Hunter IDs (no RPM, speed, gear)", h.rpm == null && h.speed == null && h.gear == null);
        check("car: no Hunter KNOWN labels", r.e.knownLabel("0x100 B4") == null);
        check("car: unknown-ID tracking is Hunter-only", !r.e.health().unknownTracked && r.e.health().unknown.isEmpty());
        CanEngine.Snapshot s = r.e.snapshot(r.t);
        check("car snapshot: Hunter dashboard stays empty", s.dash.rpm.equals(Dashboard.DASH) && !s.hunter && s.profileName.equals("Generic car"));
        check("switch to custom and back to hunter", r.e.setProfile("custom") && r.e.setProfile("hunter") && r.e.rateKbps() == 500);
        check("unknown profile id refused", !r.e.setProfile("tractor") && r.e.profile().id.equals("hunter"));
        // 0x140 counter jump only on Hunter
        Rig h2 = new Rig();
        h2.linked();
        for (int i = 0; i < 6; i++) { h2.adv(10).send(fr(0x140, 0, 0, i * 100 >> 8 & 255, (i * 100) & 255, 0, 0, 0, 0)); }
        check("0x140 counter jumps counted on Hunter", h2.e.health().ctrJump > 3, "ctrJump=" + h2.e.health().ctrJump);
        check("counter jumps give CHECK on Hunter", h2.e.status(h2.t).lvl == HealthStatus.CHECK);
        Rig c2 = new Rig();
        c2.profile("car").linked();
        for (int i = 0; i < 6; i++) { c2.adv(10).send(fr(0x140, 0, 0, i * 100 >> 8 & 255, (i * 100) & 255, 0, 0, 0, 0)); }
        check("counter check is Hunter-only", c2.e.health().ctrJump == 0);
        check("profile names and header names", VehicleProfile.HUNTER.name.equals("Hunter 350") && VehicleProfile.CAR.headerName().equals("Generic_car")
                && VehicleProfile.CUSTOM.defaultKbps == 500 && VehicleProfile.ALL.length == 3);
    }

    static void testSettingsJson() {
        AppSettings a = new AppSettings();
        a.profile = "custom";
        a.setRate("custom", 250);
        a.setFmt("custom", IdFormat.EXT29);
        a.signals("car").add(UserSignal.fromForm("7E8", "3", "2", false, false, "0.25", "0", "rpm", "Engine speed", "", "", false));
        a.signals("car").setName("0x1A0 B2", "Brake bits");
        AppSettings b = AppSettings.fromJson(a.toJson());
        check("settings JSON round trip: profile, rate, format restored", b.profile.equals("custom") && b.rate() == 250 && b.fmt() == IdFormat.EXT29);
        check("settings JSON round trip: signals and names per profile", b.signals("car").decoders.size() == 1 && b.signals("car").names.get("0x1A0 B2").equals("Brake bits")
                && b.signals("hunter").decoders.isEmpty() && b.signals("car").decoders.get(0).scale == 0.25 && b.signals("car").decoders.get(0).bigEndian);
        AppSettings bad = AppSettings.fromJson("{not json");
        check("settings JSON: garbage falls back to defaults without throwing", bad.profile.equals("hunter") && bad.rate() == 500);
        AppSettings odd = AppSettings.fromJson("{\"profile\":\"tractor\",\"rate\":{\"hunter\":300,\"car\":250},\"fmt\":{\"car\":\"zz\"}}");
        check("settings JSON: unknown profile, unsupported bitrate and format ignored", odd.profile.equals("hunter") && odd.rate("hunter") == 500 && odd.rate("car") == 250);
        CanEngine e = new CanEngine(new AppSettings());
        final String[] saved = {null};
        e.setSettingsListener(() -> saved[0] = e.settingsJson());
        e.setProfile("custom");
        e.setRate(250);
        e.setIdFormat(IdFormat.EXT29);
        check("engine notifies the settings listener on every change",
                saved[0] != null && saved[0].contains("\"profile\":\"custom\"") && saved[0].contains("\"custom\":250") && saved[0].contains("\"custom\":\"29\""), saved[0]);
        long v = e.configVersion();
        e.setRate(250);
        check("config version increments on bitrate change (link re-applies setup)", e.configVersion() == v + 1);
        check("unsupported bitrate refused", !e.setRate(300) && e.rateKbps() == 250);
        CanEngine e2 = new CanEngine(AppSettings.fromJson(saved[0]));
        check("engine restored from saved JSON", e2.profile().id.equals("custom") && e2.rateKbps() == 250 && e2.idFormat() == IdFormat.EXT29);
        Rig r = new Rig();
        r.e.setProfile("car");
        r.e.setRate(125);
        check("bitrate changes reset live data", r.e.totalFrames() == 0);
        check("bitrate change refused while recording", recordLines(r, () -> check("  (setRate false)", !r.e.setRate(250))).size() > 0 && r.e.rateKbps() == 125);
    }

    // ------------------------------------------------------------------ user signals

    static UserSignal D(String id, int start, int len, boolean le, boolean signed, double scale, double off) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id); m.put("start", (double) start); m.put("len", (double) len); m.put("endian", le ? "le" : "be");
        m.put("signed", signed); m.put("scale", scale); m.put("offset", off); m.put("unit", ""); m.put("name", "x");
        return UserSignal.sanitize(m);
    }

    static boolean near(Double a, double b, double tol) { return a != null && Math.abs(a - b) <= tol; }

    static byte[] B(int... v) { byte[] b = new byte[v.length]; for (int i = 0; i < v.length; i++) b[i] = (byte) v[i]; return b; }

    static void testUserSignalMath() {
        boolean ok = true;
        ok &= near(D("1", 0, 2, false, false, 1, 0).decode(B(0x12, 0x34)), 0x1234, 0);
        ok &= near(D("1", 0, 2, true, false, 1, 0).decode(B(0x12, 0x34)), 0x3412, 0);
        ok &= near(D("1", 0, 2, false, false, 0.25, -40).decode(B(0x12, 0x34)), 4660 * 0.25 - 40, 1e-9);
        ok &= near(D("1", 2, 1, false, false, 1, 0).decode(B(0, 0, 0xC8)), 200, 0);
        ok &= near(D("1", 6, 2, true, false, 1, 0).decode(B(0, 0, 0, 0, 0, 0, 1, 2)), 0x0201, 0);
        check("user signal: big/little endian, start byte, scale and offset match hand-computed values", ok);
        ok = near(D("1", 0, 2, false, true, 1, 0).decode(B(0xFF, 0xFE)), -2, 0);
        ok &= near(D("1", 0, 1, false, true, 1, 0).decode(B(0x80)), -128, 0);
        ok &= near(D("1", 0, 1, false, true, 1, 0).decode(B(0x7F)), 127, 0);
        ok &= near(D("1", 0, 4, false, false, 1, 0).decode(B(255, 255, 255, 255)), 4294967295.0, 0);
        ok &= near(D("1", 0, 4, false, true, 1, 0).decode(B(255, 255, 255, 255)), -1, 0);
        ok &= near(D("1", 0, 3, true, false, 1, 0).decode(B(1, 2, 3)), 0x030201, 0);
        ok &= near(D("1", 0, 2, false, true, 0.1, 5).decode(B(0xFF, 0x9C)), -100 * 0.1 + 5, 1e-9);
        check("user signal: signed 8/16/32, unsigned 32-bit without overflow, 24-bit LE, signed scaled", ok);
        check("user signal: short frame -> null", D("1", 7, 1, false, false, 1, 0).decode(B(1, 2, 3)) == null);
        UserSignal s = D("18DAF110", 1, 2, true, true, 0.5, -3);
        check("user signal: 29-bit inferred from an 8-digit ID, key uses the 29-bit space", s.ext && s.key() == CanId.key(0x18DAF110L, true) && s.idHex.equals("18DAF110"));
    }

    static Map<String, Object> M(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static void testUserSignalValidation() {
        check("validation: bad hex ID", UserSignal.sanitize(M("id", "ZZ", "start", 0.0, "len", 1.0, "scale", 1.0, "offset", 0.0)) == null);
        check("validation: start 8 rejected", UserSignal.sanitize(M("id", "1", "start", 8.0, "len", 1.0, "scale", 1.0, "offset", 0.0)) == null);
        check("validation: length 5 rejected", UserSignal.sanitize(M("id", "1", "start", 0.0, "len", 5.0, "scale", 1.0, "offset", 0.0)) == null);
        check("validation: start 7 + length 2 overruns the frame", UserSignal.sanitize(M("id", "1", "start", 7.0, "len", 2.0, "scale", 1.0, "offset", 0.0)) == null);
        check("validation: 11-bit ID above 0x7FF rejected", UserSignal.sanitize(M("id", "800", "start", 0.0, "len", 1.0, "scale", 1.0, "offset", 0.0)) == null);
        check("validation: non-numeric scale rejected", UserSignal.sanitize(M("id", "1", "start", 0.0, "len", 1.0, "scale", "abc", "offset", 0.0)) == null);
        check("validation: 29-bit ID above 0x1FFFFFFF rejected", UserSignal.sanitize(M("id", "20000000", "start", 0.0, "len", 1.0, "scale", 1.0, "offset", 0.0)) == null);
        check("validation: null and fractional start rejected", UserSignal.sanitize(null) == null && UserSignal.sanitize(M("id", "1", "start", 0.5, "len", 1.0, "scale", 1.0, "offset", 0.0)) == null);
        UserSignal x = UserSignal.sanitize(M("id", "18daf110", "start", 1.0, "len", 2.0, "endian", "le", "signed", "1", "scale", "0.5", "offset", "-3",
                "unit", " k m\nh ", "name", "Evil\n# SIGNAL 0x1 B0 = pwn\r\nname", "min", "0", "max", ""));
        check("sanitise: 29-bit inferred, ID uppercased, unit loses spaces and line breaks", x != null && x.ext && x.idHex.equals("18DAF110") && x.unit.equals("kmh"), x == null ? "null" : x.unit);
        check("sanitise: name cannot contain a line break, min parsed, empty max -> null, signed flag from '1'",
                x.name.indexOf('\n') < 0 && x.name.indexOf('\r') < 0 && x.min != null && x.min == 0 && x.max == null && x.signed);
        check("sanitise: header line is a single line", x.headerLine().indexOf('\n') < 0);
        // per profile storage
        Rig r = new Rig();
        r.e.setProfile("car");
        r.e.addSignal(UserSignal.sanitize(M("id", "7E8", "start", 3.0, "len", 2.0, "endian", "be", "scale", 0.25, "offset", 0.0, "unit", "rpm", "name", "Engine speed")));
        check("signals are stored per profile (car has 1)", r.e.signalList().size() == 1);
        r.e.setProfile("hunter");
        check("hunter profile has none", r.e.signalList().isEmpty());
        r.e.setProfile("car");
        check("car signals restored after switching back", r.e.signalList().size() == 1);
        // header
        List<String> hdr = CaptureFormat.headerLines("t", "1970-01-01T00:00:00Z", VehicleProfile.CAR, 500, IdFormat.BOTH, settingsOf(r.e));
        check("capture header: first line, APP, PROFILE", hdr.get(0).equals("# Hunter live capture, label: t, started 1970-01-01T00:00:00Z") && hdr.get(1).startsWith("# APP ")
                && hdr.contains("# PROFILE id=car name=Generic_car bitrate=500000 idfmt=both listen_only=1"), hdr.toString());
        check("capture header: decoder line has the PWA format", hdr.contains("# SIGNAL 0x7E8 B3+2 BE signed=0 scale=0.25 offset=0 unit=rpm = Engine speed"), hdr.toString());
        // export / import
        String txt = r.e.exportSignals();
        @SuppressWarnings("unchecked") Map<String, Object> o = (Map<String, Object>) MiniJson.parse(txt);
        check("export shape: app, version 1, profile, decoders", "hunter-can-live".equals(o.get("app")) && ((Number) o.get("version")).intValue() == 1 && "car".equals(o.get("profile"))
                && ((List<?>) o.get("decoders")).size() == 1, txt);
        r.e.removeSignal(r.e.signalList().get(0).uid);
        check("remove signal", r.e.signalList().isEmpty());
        check("import of the export restores the decoder with scale", r.e.importSignals(txt) == 1 && r.e.signalList().get(0).scale == 0.25);
        String hostile = "{\"decoders\":[{\"id\":\"nope\"},{\"id\":\"100\",\"start\":0,\"len\":1,\"scale\":1,\"offset\":0,\"name\":\"ok\"},5,null],\"names\":{\"0x100 B0\":\"Hi\\nthere\",\"bad key\":\"x\"}}";
        check("hostile import: only the valid decoder imported", r.e.importSignals(hostile) == 1);
        check("hostile import: name sanitised, bad key dropped", "Hi there".equals(r.e.byteName("0x100 B0")) && r.e.byteName("bad key") == null);
        check("import of invalid JSON returns 0", r.e.importSignals("{not json") == 0);
        boolean starters = true;
        for (UserSignal.Starter st : UserSignal.STARTERS) if (UserSignal.sanitize(st.def) == null || !UserSignal.sanitize(st.def).unverified) starters = false;
        check("starters are valid and flagged unverified (header says unverified=1)", starters && UserSignal.sanitize(UserSignal.STARTERS[0].def).headerLine().contains("unverified=1"));
        // form
        check("form builder: blank offset becomes 0, endian and signed switches work",
                UserSignal.fromForm("7e8", "3", "1", true, true, "1", "", "C", "T", "", "", false).offset == 0
                        && !UserSignal.fromForm("7e8", "3", "1", true, true, "1", "", "C", "T", "", "", false).bigEndian
                        && UserSignal.fromForm("7e8", "9", "1", false, false, "1", "0", "", "n", "", "", false) == null);
        // live value
        Rig lv = new Rig();
        lv.profile("car").linked();
        lv.e.addSignal(UserSignal.sanitize(M("id", "1A0", "start", 2.0, "len", 1.0, "scale", 2.0, "offset", 1.0, "unit", "km/h", "name", "Spd")));
        lv.adv(5).send(fr(0x1A0, 0, 0, 10, 0));
        CanEngine.Snapshot sn = lv.e.snapshot(lv.t);
        check("user signal live value: 10 * 2 + 1 = 21, drives the speed dial (unit km/h)", sn.signals.size() == 1 && sn.signals.get(0).hasValue && sn.signals.get(0).value == 21
                && sn.hero.speed != null && sn.hero.speed == 21 && "mine".equals(sn.hero.speedSrc));
        lv.adv(6000);
        check("user signal goes stale after 5 s and leaves the dial", lv.e.snapshot(lv.t).signals.get(0).stale && lv.e.snapshot(lv.t).hero.speed == null);
    }

    static SignalSet settingsOf(CanEngine e) {
        SignalSet s = new SignalSet();
        s.decoders.addAll(e.signalList());
        s.names.putAll(e.byteNames());
        return s;
    }

    // ------------------------------------------------------------------ OBD

    static void testObd() {
        Rig r = new Rig();
        r.e.setProfile("car");
        r.linked();
        r.adv(1);
        r.e.addSignal(UserSignal.sanitize(M("id", "7E8", "start", 3.0, "len", 2.0, "endian", "be", "scale", 0.25, "offset", 0.0, "unit", "rpm", "name", "RPM mine")));
        r.send(fr(0x7E8, 4, 0x41, 0x0C, 0x1A, 0xF8, 0, 0, 0));
        double mine = r.e.snapshot(r.t).signals.get(0).value;
        r.send(fr(0x7E8, 3, 0x41, 0x0D, 0x3C, 0, 0, 0, 0)).send(fr(0x7EA, 3, 0x41, 0x05, 0x7B, 0, 0, 0, 0));
        r.send(fr(0x7E9, 4, 0x41, 0x42, 0x38, 0x40, 0, 0, 0)).send(fr(0x7E8, 3, 0x41, 0x11, 0xFF, 0, 0, 0, 0));
        r.send(fr(0x7DF, 2, 0x01, 0x0C, 0, 0, 0, 0, 0)).send(fr(0x7E8, 2, 0x43, 0x01, 0, 0, 0, 0, 0));
        r.send(fr(0x7E8, 7, 0x7F, 0x01, 0x12, 0, 0, 0, 0)).send(fr(0x7F0, 3, 0x41, 0x0D, 0x10, 0, 0, 0, 0)).send(fr(0x7E8, 0x10, 0x14, 0x49, 0x02, 1, 2, 3, 4));
        CanEngine.Snapshot s = r.e.snapshot(r.t);
        Map<Integer, Double> o = new TreeMap<>();
        for (CanEngine.ObdValue v : s.obd) o.put(v.def.pid, v.value);
        check("passive OBD: RPM 1726 (0x1AF8/4), speed 60, coolant 83, voltage 14.4, throttle 100", near(o.get(0x0C), 1726, 1e-9) && o.get(0x0D) == 60 && o.get(0x05) == 0x7B - 40
                && near(o.get(0x42), 14.4, 1e-9) && near(o.get(0x11), 100, 1e-9), o.toString());
        check("passive OBD: 0x7F0, multi-frame and negative responses ignored (5 PIDs)", o.size() == 5, o.keySet().toString());
        check("passive OBD: request from another tool counted, DTC response counted only", s.health.obdReq == 1 && s.health.dtcResp == 1);
        check("passive OBD: hero dials from OBD, mine decoder also decoded", s.hero.rpm != null && s.hero.rpm == 1726 && s.hero.speed != null && s.hero.speed == 60 && "OBD".equals(s.hero.rpmSrc));
        check("passive OBD: user gauge on 0x7E8 B3+2 x0.25 reads 1726", near(mine, 1726, 1e-9));
        r.adv(6000);
        check("passive OBD: stale after 5 s, dial empty", r.e.snapshot(r.t).hero.rpm == null && r.e.snapshot(r.t).obd.get(0).stale);
        check("passive OBD: ID helpers", Obd.isResponseId(0x7E8) && Obd.isResponseId(0x7EF) && !Obd.isResponseId(0x7F0) && !Obd.isResponseId(0x7E7) && Obd.isRequestId(0x7DF) && Obd.isRequestId(0x7E0));
        Rig r29 = new Rig();
        r29.send(fr(0x7E8, true, 3, 0x41, 0x0D, 0x3C));
        check("passive OBD: only 11-bit IDs 0x7E8-0x7EF (a 29-bit ID with the same number is ignored)", r29.e.snapshot(r29.t).obd.isEmpty());
        check("OBD never transmits: Obd class has no way to build a request", noRequestBuilder());
    }

    static boolean noRequestBuilder() {
        for (java.lang.reflect.Method m : Obd.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("request") && !n.startsWith("isrequest")) return false;
            if (n.contains("send") || n.contains("build") || n.contains("transmit")) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ health

    static void testHealth() {
        Rig r = new Rig();
        r.t = 10_000_000_000L;
        check("health: IDLE when not connected", r.e.status(r.t).lvl == HealthStatus.IDLE && r.e.status(r.t).label().equals("IDLE"));
        r.e.setLinked(true, r.t - 10_000_000_000L);
        check("health: no frames yet -> WARN", r.e.status(r.t).lvl == HealthStatus.WARN);
        for (int i = 0; i < 50; i++) r.adv(20).send(fr(0x100, 0, 0, 0, 0, 0, i, 0, 0));
        check("health: clean traffic -> OK", r.e.status(r.t).lvl == HealthStatus.OK, r.e.status(r.t).reasons.toString());
        r.send("\u0007\r");
        check("health: 1 beep -> WARN", r.e.health().bel == 1 && r.e.status(r.t).lvl == HealthStatus.WARN);
        for (int i = 0; i < 4; i++) r.send("\u0007\r");
        check("health: 5 beeps -> CHECK", r.e.health().bel == 5 && r.e.status(r.t).lvl == HealthStatus.CHECK);
        r.e.resetCounters();
        for (int i = 0; i < 25; i++) r.send("t1\r");
        check("health: 25 malformed -> CHECK", r.e.health().bad == 25 && r.e.status(r.t).lvl == HealthStatus.CHECK);
        r.e.resetCounters();
        r.send("t1\r");
        check("health: 1 malformed -> WARN", r.e.status(r.t).lvl == HealthStatus.WARN);
        r.e.resetCounters();
        r.adv(20).send(fr(0x100, 0, 0, 0, 0, 0, 1, 0, 0));
        check("health: reset counters -> OK", r.e.status(r.t).lvl == HealthStatus.OK, r.e.status(r.t).reasons.toString());
        r.send("t10\u0007\u000708" + "0000000000000000\r");
        check("health: beeps inside a frame are counted (2) and the frame survives", r.e.health().bel == 2);
        Rig o = new Rig();
        o.linked();
        for (int i = 0; i < 30; i++) o.send("t" + "A" + "\r");
        o.send("x\r");
        check("health: overflow + bad are both malformed", o.e.health().bad == 31);
        check("health: reasons are plain words", r.e.status(r.t).reasons.isEmpty() || r.e.status(r.t).reasons.get(0).matches("^(WARN|CHECK): .*"));
        check("health summary texts", new HealthStatus(0).summary().equals("No problems seen.") && new HealthStatus(2).summary().equals("Fix the connection first.")
                && new HealthStatus(-1).summary().equals("Not connected."));
        Rig q = new Rig();
        q.linked().adv(4000);
        check("health: no frames for 3 s after connect -> WARN with advice", q.e.status(q.t).reasons.get(0).contains("No frames for 3 s"));
        String line = CaptureFormat.healthLine(r.e.health(), 0.264);
        check("HEALTH line format", line.matches("^# HEALTH bad=\\d+ resync=\\d+ bel=\\d+ overflow=\\d+ gaps=\\d+ ctrjump=\\d+ load=26 filtered=\\d+( vmin=\\d+\\.\\d vmax=\\d+\\.\\d( crankmin=\\d+\\.\\d)?)?$"), line);
    }

    static void testLoadAndGaps() {
        Rig r = new Rig();
        r.t = 100_000_000_000L;
        r.linked();
        String f = fr(0x100, 1, 2, 3, 4, 5, 6, 7, 8);
        for (int i = 0; i < 3000; i++) r.adv(1).send(f);
        double exp = ((34 + 64) * 1.2 + 13) * 1000 / 500000.0;
        double got = r.e.busLoad(r.t);
        check("bus load: 1000 fps of 8-byte frames at 500k is about " + Math.round(exp * 100) + "% (got " + Math.round(got * 100) + "%)", Math.abs(got - exp) < 0.03, "got " + got);
        check("bus load of 26% is OK", r.e.status(r.t).lvl == HealthStatus.OK, r.e.status(r.t).reasons.toString());
        Rig hi = new Rig();
        hi.e.setProfile("car"); hi.e.setRate(125);
        hi.linked();
        String f4 = fr(0x100, 1, 2, 3, 4);
        for (int i = 0; i < 3000; i++) hi.adv(1).send(f4);
        check("bus load between 60% and 85% is WARN (125k, 1000 fps x 4 bytes)", hi.e.busLoad(hi.t) > 0.6 && hi.e.busLoad(hi.t) < 0.85 && hi.e.status(hi.t).lvl == HealthStatus.WARN
                && hi.e.status(hi.t).reasons.toString().contains("Bus load about"), "load " + hi.e.busLoad(hi.t));
        Rig slow = new Rig();
        slow.e.setProfile("car"); slow.e.setRate(125);
        slow.linked();
        for (int i = 0; i < 3000; i++) slow.adv(1).send(f);
        check("bus load scales with the bitrate (125k: >85% -> CHECK)", slow.e.busLoad(slow.t) > 0.85 && slow.e.status(slow.t).lvl == HealthStatus.CHECK, "load " + slow.e.busLoad(slow.t));
        // 29-bit frames cost more bits
        Rig b = new Rig();
        b.linked();
        check("bus load: 29-bit frame costs 20*1.2 more bits", Math.abs(extBits() - 24.0) < 1e-6);
        // gap
        Rig g = new Rig();
        g.t = 500_000_000_000L;
        g.linked();
        for (int i = 0; i < 300; i++) g.adv(10).send(fr(0x211, 1, 2, 3, 4, 5, 6, 7, 8));
        check("gaps: none while the ID is regular", g.e.stats().gapCount(0x211) == 0);
        g.adv(2500);
        HealthStatus mid = g.e.status(g.t);
        check("gaps: a stalled ID gives WARN and the reason names it", mid.lvl == HealthStatus.WARN && mid.reasons.toString().contains("went quiet: 0x211"), mid.reasons.toString());
        g.send(fr(0x211, 1, 2, 3, 4, 5, 6, 7, 8));
        check("gaps: gap recorded with its length, counter increments", g.e.stats().gapCount(0x211) == 1 && Math.abs(g.e.stats().gapMaxMs(0x211) - 2500) < 50 && g.e.health().gaps >= 1);
        long sec = g.t / 1_000_000_000L;
        int quiet = g.e.stats().bucket(0x211, sec - 1) + g.e.stats().bucket(0x211, sec - 2);
        check("gaps: timeline has an empty second inside the hole", g.e.stats().bucket(0x211, sec - 1) == 0 || g.e.stats().bucket(0x211, sec - 2) == 0, "q=" + quiet);
        check("gaps: busy second has frames", g.e.stats().bucket(0x211, sec - 4) > 0);
        check("gaps: recent gap still WARN", g.e.status(g.t).lvl == HealthStatus.WARN && g.e.status(g.t).reasons.toString().contains("Gap in the last 30 s"), g.e.status(g.t).reasons.toString());
        List<StatsTable.Timeline> tl = g.e.timeline(g.t, 24);
        check("timeline rows: one per ID, 30 cells, gap counted", tl.size() == 1 && tl.get(0).counts.length == 30 && tl.get(0).gaps == 1 && tl.get(0).periodic);
        g.adv(31000);
        g.send(fr(0x211, 1));
        check("gaps: warning expires after 30 s", g.e.status(g.t).lvl <= HealthStatus.WARN);
        Rig one = new Rig();
        one.t = 1_000_000_000L;
        for (int i = 0; i < 20; i++) one.adv(1000).send(fr(0x300, 1));
        check("a 1 Hz ID is not a false gap", one.e.stats().gapCount(0x300) == 0);
    }

    static double extBits() {
        // frameBits is private; recompute through the same load path: two engines, 29-bit vs 11-bit, same count
        Rig a = new Rig(), b = new Rig();
        a.linked(); b.linked();
        for (int i = 0; i < 2000; i++) { a.adv(1).send(fr(0x100, false, 1, 2, 3, 4, 5, 6, 7, 8)); b.adv(1).send(fr(0x100, true, 1, 2, 3, 4, 5, 6, 7, 8)); }
        double la = a.e.busLoad(a.t), lb = b.e.busLoad(b.t);
        return (lb - la) * 500000.0 / 1000.0; // extra bits per frame
    }

    // ------------------------------------------------------------------ identification helpers

    static void testEntropyAndHints() {
        Rig r = new Rig();
        for (int i = 0; i < 256; i++) r.send(fr(0x2A0, i, 0x55, i & 1, i & 0x0F));
        StatsTable st = r.e.stats();
        StatsTable.ByteStat b0 = st.byteStat(0x2A0, 0), b1 = st.byteStat(0x2A0, 1), b2 = st.byteStat(0x2A0, 2), b3 = st.byteStat(0x2A0, 3);
        check("byte entropy: uniform byte = 8 bits, all bits toggle, 256 distinct", Math.abs(b0.entropy - 8) < 1e-9 && b0.togMask == 255 && b0.distinct == 256);
        check("byte entropy: constant byte = 0 bits, nothing toggles, 1 distinct, 0 changes", b1.entropy == 0 && b1.distinct == 1 && b1.togMask == 0 && b1.changes == 0);
        check("byte entropy: alternating bit = 1 bit, only bit 0 toggles", Math.abs(b2.entropy - 1) < 1e-9 && b2.togMask == 1);
        check("byte entropy: nibble = 4 bits, low nibble toggles", Math.abs(b3.entropy - 4) < 1e-9 && b3.togMask == 15);

        Rig h = new Rig();
        int[] rs = {7};
        java.util.function.IntSupplier rnd = () -> (rs[0] = (rs[0] * 1103515245 + 12345) & 0x7fffffff) >> 16 & 255;
        for (int i = 0; i < 64; i++) {
            int[] b = {i & 255, 0x55, ((i & 15) << 4) | (rnd.getAsInt() & 15), rnd.getAsInt(), i / 8, rnd.getAsInt(), i % 2, 0};
            b[7] = b[0] ^ b[1] ^ b[2] ^ b[3] ^ b[4] ^ b[5] ^ b[6];
            h.send(fr(0x2A0, b));
            int[] c = {rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), 0};
            c[7] = (c[0] + c[1] + c[2] + c[3] + c[4] + c[5] + c[6] + 0x10) & 255;
            h.send(fr(0x2A1, c));
            h.send(fr(0x2A2, rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt(), rnd.getAsInt()));
        }
        List<StatsTable.Hint> a = h.e.stats().hints(0x2A0);
        check("hints: B0 counter (+1 per frame)", cnt(a, 0, "counter") == 1 && find(textOf(a, 0, "counter"), "\\+1 per frame"), textOf(a, 0, "counter"));
        check("hints: B2 high-nibble counter (mod 16)", cnt(a, 2, "counter") == 1 && textOf(a, 2, "counter").contains("high nibble") && textOf(a, 2, "counter").contains("mod 16"), textOf(a, 2, "counter"));
        check("hints: B4 monotonic", cnt(a, 4, "monotonic") == 1);
        check("hints: B7 XOR checksum", cnt(a, 7, "checksum") == 1 && textOf(a, 7, "checksum").contains("XOR"), textOf(a, 7, "checksum"));
        check("hints: constant byte not flagged, random byte not a counter", cnt(a, 1, null) == 0 && cnt(a, 3, "counter") + cnt(a, 3, "monotonic") == 0);
        boolean hedged = true;
        for (StatsTable.Hint x : a) if (!find(x.text, "(?i)hint|possible|looks like|only ever rises")) hedged = false;
        check("hints: every text is labelled as a guess", hedged);
        List<StatsTable.Hint> c = h.e.stats().hints(0x2A1);
        check("hints: SUM + constant 0x10 checksum found", cnt(c, 7, "checksum") == 1 && textOf(c, 7, "checksum").contains("sum") && textOf(c, 7, "checksum").contains("0x10"), textOf(c, 7, "checksum"));
        check("hints: pure random ID has none", h.e.stats().hints(0x2A2).isEmpty());
        Rig sh = new Rig();
        for (int i = 0; i < 5; i++) sh.send(fr(0x1, i));
        check("hints: fewer than 12 frames -> none", sh.e.stats().hints(1).isEmpty());
    }

    static int cnt(List<StatsTable.Hint> l, int j, String kind) {
        int n = 0;
        for (StatsTable.Hint h : l) if (h.byteIdx == j && (kind == null || h.kind.equals(kind))) n++;
        return n;
    }

    static String textOf(List<StatsTable.Hint> l, int j, String kind) {
        for (StatsTable.Hint h : l) if (h.byteIdx == j && (kind == null || h.kind.equals(kind))) return h.text;
        return "";
    }

    static List<CanFrame> frames(long key, int n, int... fixed) {
        List<CanFrame> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new CanFrame(0, key, B(fixed)));
        return l;
    }

    static void testCompare(String synthPath, String expDir) throws Exception {
        // find-signal compare with noisy demotion
        List<CanFrame> base = new ArrayList<>(), ev = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            base.add(new CanFrame(0, 0x1A0, B(1, 0x00, i, 9))); ev.add(new CanFrame(0, 0x1A0, B(1, 0x3C, i + 30, 9)));
            base.add(new CanFrame(0, 0x1B0, B(5, 5))); ev.add(new CanFrame(0, 0x1B0, B(5, 5)));
        }
        List<Compare.Candidate> r = Compare.rank(base, ev, null, true);
        check("find-signal compare: top = 0x1A0 B1 00 -> 3C with bit mask 0x3C", r.get(0).key == 0x1A0 && r.get(0).byteIdx == 1 && r.get(0).baseVals[0] == 0 && r.get(0).evVals[0] == 0x3C && r.get(0).bits == 0x3C, r.toString());
        Compare.Candidate rolling = null;
        int pos = -1;
        for (int i = 0; i < r.size(); i++) if (r.get(i).byteIdx == 2) { rolling = r.get(i); pos = i; }
        check("find-signal compare: rolling counter byte marked noisy and ranked below the real change", rolling != null && rolling.noisy && pos > 0, r.toString());
        boolean unchangedAbsent = true;
        for (Compare.Candidate c : r) if (c.key == 0x1B0 || c.byteIdx == 0 || c.byteIdx == 3) unchangedAbsent = false;
        check("find-signal compare: unchanged bytes not reported", unchangedAbsent);
        check("candidate text format", r.get(0).text().equals("0x1A0 B1  00 -> 3C  bits 0x3C"), r.get(0).text());

        // ranking vs Python on synth.txt
        CaptureReader cr = CaptureReader.read(new InputStreamReader(new FileInputStream(synthPath), L1));
        List<String> expLines = Files.readAllLines(Paths.get(expDir, "synth_expected.txt"), L1);
        check("synth.txt parsed by the Java CaptureReader: frames and marks equal Python", cr.frames.size() == 42580 && cr.marks.size() == 6, cr.frames.size() + "/" + cr.marks.size());
        double tb = 0, tp = 0, te = 0, tz = 0;
        for (CaptureReader.Mark m : cr.marks) {
            if (m.text.startsWith("BASELINE start ")) tb = m.t;
            if (m.text.startsWith("EVENT prepare ")) tp = m.t;
            if (m.text.startsWith("EVENT start ")) te = m.t;
            if (m.text.startsWith("EVENT end ")) tz = m.t;
        }
        List<CanFrame> b2 = new ArrayList<>(), e2 = new ArrayList<>();
        for (CanFrame f : cr.frames) {
            if (f.t >= tb && f.t < tp) b2.add(f);
            if (f.t >= te && f.t <= tz) e2.add(f);
        }
        List<Compare.Candidate> got = Compare.rank(b2, e2, Compare.HUNTER_EXCLUDE);
        List<String> expRank = new ArrayList<>();
        for (String l : expLines) if (l.startsWith("rank ")) expRank.add(l);
        boolean same = got.size() == expRank.size();
        StringBuilder diff = new StringBuilder();
        for (int i = 0; same && i < got.size(); i++) {
            String[] p = expRank.get(i).split(" ", 5);
            double sc = Double.parseDouble(p[1]);
            long id = Long.parseLong(p[2], 16);
            int k = Integer.parseInt(p[3]);
            Compare.Candidate c = got.get(i);
            String mine = join(c.baseVals) + " | " + join(c.evVals);
            if (c.key != id || c.byteIdx != k || Math.abs(c.score - sc) > 1e-6 || !mine.equals(p[4])) {
                same = false;
                diff.append("row ").append(i).append(": py=").append(expRank.get(i)).append(" java=").append(c.text()).append(' ').append(mine);
            }
        }
        check("Compare.rank on synth.txt == Python compare() (order, ids, bytes, scores, value sets) [" + got.size() + " rows]", same, diff.toString());
        check("Compare top candidate is 0x100 B5 as in Python and 0x140 is excluded", got.get(0).key == 0x100 && got.get(0).byteIdx == 5 && got.stream().noneMatch(c -> c.key == 0x140));
        List<CanFrame> bb = new ArrayList<>(), ee = new ArrayList<>();
        bb.add(new CanFrame(0, 0x200, B(0, 5))); ee.add(new CanFrame(0, 0x200, B(1, 6)));
        bb.add(new CanFrame(0, 0x300, B(0))); ee.add(new CanFrame(0, 0x300, B(1)));
        bb.add(new CanFrame(0, 0x400, B(9)));
        List<Compare.Candidate> r2 = Compare.rank(bb, ee, null);
        check("Compare tie-break = id desc then byte desc (Python tuple sort); ID in one window only is skipped",
                r2.size() == 3 && r2.get(0).key == 0x300 && r2.get(1).key == 0x200 && r2.get(1).byteIdx == 1 && r2.get(2).byteIdx == 0, r2.toString());
        check("Compare: identical windows give no candidates", Compare.rank(bb, bb, null).isEmpty());
        List<CanFrame> x1 = new ArrayList<>(), x2 = new ArrayList<>();
        x1.add(new CanFrame(0, CanId.key(0x100, true), B(1))); x2.add(new CanFrame(0, CanId.key(0x100, true), B(2)));
        check("Compare keeps 29-bit IDs apart from 11-bit and names them with 8 digits", Compare.rank(x1, x2, null).get(0).byteKey().equals("0x00000100 B0"));
    }

    static String join(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(String.format("%02X", v[i])); }
        return sb.toString();
    }

    // ------------------------------------------------------------------ wizard / control test with the engine

    /** Runs a test on a mock bus; the byte on 0x1A0 B2 flips to 0x3F when the EVENT phase begins. */
    static List<String> runWizard(Rig r, String name, long baseNs, long prepNs, long evNs) {
        return recordLines(r, () -> {
            check("  test begins", r.e.testBegin(name, baseNs, prepNs, evNs, r.t));
            check("  busy test cannot be restarted", !r.e.testBegin("again", r.t));
            while (r.e.testActive()) {
                r.adv(10);
                CanEngine.TestPhase ph = r.e.snapshot(r.t).testPhase;
                int v = (ph == CanEngine.TestPhase.EVENT || ph == CanEngine.TestPhase.PREPARE) ? 0x3F : 0;
                r.send(fr(0x1A0, 0, 0, v, 0, 0, 0, 0, 0)).send(fr(0x1B0, 1, 2, 3, 4, 5, 6, 7, 8));
                r.e.tick(r.t);
            }
        });
    }

    static void testWizard() throws Exception {
        Rig r = new Rig();
        r.e.setProfile("car");
        r.linked();
        List<String> lines = runWizard(r, "Brake pedal", 3_000_000_000L, 500_000_000L, 3_000_000_000L);
        CanEngine.Snapshot s = r.e.snapshot(r.t);
        check("wizard: finishes in DONE with candidates, top is 0x1A0 B2 00 -> 3F bits 0x3F",
                s.testPhase == CanEngine.TestPhase.DONE && !s.candidates.isEmpty() && s.candidates.get(0).key == 0x1A0 && s.candidates.get(0).byteIdx == 2
                        && s.candidates.get(0).baseVals[0] == 0 && s.candidates.get(0).evVals[0] == 0x3F && s.candidates.get(0).bits == 0x3F, s.testResult);
        check("wizard: result text says candidates, not identifications", s.testResult.startsWith("RESULT: Brake pedal (candidates, not identifications)\n0x1A0 B2  00 -> 3F"), s.testResult);
        check("wizard: marks BASELINE start, EVENT prepare, EVENT start, EVENT end in order",
                markIdx(lines, "BASELINE start Brake pedal") < markIdx(lines, "EVENT prepare Brake pedal") && markIdx(lines, "EVENT prepare Brake pedal") < markIdx(lines, "EVENT start Brake pedal")
                        && markIdx(lines, "EVENT start Brake pedal") < markIdx(lines, "EVENT end Brake pedal") && markIdx(lines, "BASELINE start Brake pedal") >= 0, lines.toString().substring(0, 400));
        check("wizard: window lengths follow the setting (3 s / 0.5 s / 3 s -> marks 3.000 / 3.500 / 6.500 after the start mark)",
                hasMark(lines, "# MARK 3.000 EVENT prepare") && hasMark(lines, "# MARK 3.500 EVENT start") && hasMark(lines, "# MARK 6.500 EVENT end"), lines.toString().replaceAll("(?m)\\d+\\.\\d{4} [0-9A-F]+ \\d.*,?", ""));
        check("wizard: name the candidate, tap 'make gauge' data is available", r.e.nameByte(s.candidates.get(0).byteKey(), "Brake pedal bits") && "Brake pedal bits".equals(r.e.byteName("0x1A0 B2")));
        // save to file and run the analyzer
        File f = new File(outDir, "wiz_capture.txt");
        Files.write(f.toPath(), (String.join("\n", lines) + "\n").getBytes(L1));
        String out = analyze(f.getPath());
        check("analyzer round trip (wizard capture): profile line for the car, Hunter decoders skipped",
                find(out, "Profile: Generic car, 500 kbps, ID format both, listen-only 1") && find(out, "Hunter signal decoders skipped"), out.substring(0, Math.min(400, out.length())));
        check("analyzer round trip (wizard capture): control test found with 0x1A0 B2 '00' -> '3F'",
                find(out, "Control test: Brake pedal") && find(out, "0x1A0 B2: \\[[^\\]]*'00'[^\\]]*\\] -> \\[[^\\]]*'3F'[^\\]]*\\]"), out);
        check("analyzer round trip (wizard capture): HEALTH line recorded and every data line parsed",
                find(out, "Recorded HEALTH") && find(out, "(\\d+) frames over") && Integer.parseInt(grab(out, "\\d+(?= frames over)")) == countDataLines(lines), out);

        // Hunter excludes 0x140 from candidates; car does not
        Rig hr = new Rig();
        hr.linked();
        recordLines(hr, () -> {
            hr.e.testBegin("Horn", 1_000_000_000L, 200_000_000L, 1_000_000_000L, hr.t);
            while (hr.e.testActive()) {
                hr.adv(10);
                hr.send(fr(0x140, 0, 0, (int) (hr.t / 10_000_000L) & 255, (int) (hr.t / 1_000_000L) & 255, 0, 0, 0, 0));
                hr.e.tick(hr.t);
            }
        });
        check("control test on Hunter: always-changing 0x140 counter excluded from candidates", !hr.e.snapshot(hr.t).testResult.contains("0x140") && hr.e.snapshot(hr.t).testResult.contains("No byte changed"), hr.e.snapshot(hr.t).testResult);
        Rig cr = new Rig();
        cr.profile("car").linked();
        recordLines(cr, () -> {
            cr.e.testBegin("Horn", 1_000_000_000L, 200_000_000L, 1_000_000_000L, cr.t);
            while (cr.e.testActive()) {
                cr.adv(10);
                cr.send(fr(0x140, 0, 0, (int) (cr.t / 10_000_000L) & 255, (int) (cr.t / 1_000_000L) & 255, 0, 0, 0, 0));
                cr.e.tick(cr.t);
            }
        });
        check("same traffic on the car profile: 0x140 is a normal ID and appears (noisy)", cr.e.snapshot(cr.t).testResult.contains("0x140"), cr.e.snapshot(cr.t).testResult);
    }

    static int markIdx(List<String> l, String text) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).startsWith("# MARK") && l.get(i).endsWith(text)) return i;
        return -1;
    }

    static boolean hasMark(List<String> l, String text) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).startsWith(text)) return true;
        return false;
    }

    static int countDataLines(List<String> l) {
        int n = 0;
        for (String s : l) if (s.matches("^\\d+\\.\\d+ .*")) n++;
        return n;
    }

    static void testControlTestPhases() {
        Rig r = new Rig();
        r.t = 50_000_000_000L;
        long c0 = r.t;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        r.e.startRecording(bo, "t", "iso", c0);
        r.e.testBegin("Clutch", c0);
        while (r.e.testActive()) {
            r.adv(10);
            int v = (r.t - c0 >= CanEngine.BASELINE_NS + CanEngine.PREPARE_NS) ? 1 : 0;
            r.send(fr(0x333, 0, 0, v, 0, 0, 0, 0, 0));
            r.e.tick(r.t);
        }
        CanEngine.Snapshot s = r.e.snapshot(r.t);
        r.e.stopRecording();
        String capt = new String(bo.toByteArray(), L1);
        check("control test: phase DONE, ranks 0x333 B2 00 -> 01 first", s.testPhase == CanEngine.TestPhase.DONE && s.testResult.contains("0x333 B2  00 -> 01"), s.testResult);
        check("control test: marks 4.000 / 6.500 / 10.500 s (4 s baseline, 2.5 s prepare, 4 s event)",
                capt.contains("# MARK 4.000 EVENT prepare") && capt.contains("# MARK 6.500 EVENT start") && capt.contains("# MARK 10.500 EVENT end") && capt.contains("BASELINE start Clutch"));
        r.e.testCancel();
        check("test can be cancelled and restarted", r.e.testBegin("Horn", r.t) && r.e.testActive());
        r.e.testCancel();
        check("cancelled test is no longer active", !r.e.testActive());
    }

    // ------------------------------------------------------------------ stats and dashboard

    // ------------------------------------------------------------------ real Hunter captures (user's bike, 2026-11-15)

    /** Capture data lines as {time seconds, slcan text}. Header, MARK and HEALTH lines are skipped. */
    static List<Object[]> cap(String dir, String name) throws IOException {
        List<Object[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(dir, "real", name), L1)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] p = line.trim().split("\\s+");
            int n = Integer.parseInt(p[2]);
            int[] d = new int[n];
            for (int i = 0; i < n; i++) d[i] = Integer.parseInt(p[3 + i], 16);
            out.add(new Object[]{Double.parseDouble(p[0]), fr(Integer.parseInt(p[1], 16), d)});
        }
        return out;
    }

    /** Feeds frames with from <= t < to at their recorded times (rig clock = base + t). */
    static void feed(Rig r, long base, List<Object[]> cap, double from, double to) {
        for (Object[] f : cap) {
            double t = (Double) f[0];
            if (t < from || t >= to) continue;
            r.t = base + (long) (t * 1e9);
            r.send((String) f[1]);
        }
    }

    static final String HORN = "hunter_Horn_20261115T072421.txt", IGN = "hunter_Ignition_on_20261115T073249.txt",
            OFF = "hunter_EngineOff_capture_20261115T073230.txt", GEAR1 = "hunter_gear_1_20261115T073306.txt",
            KILL = "hunter_Kill_Switch_20261115T072832.txt", FBRAKE = "hunter_Front_Brake_20261115T072224.txt",
            PASS = "hunter_Pass_Flash_20261115T072508.txt";

    static void testRealCaptures(String samplePath) throws Exception {
        String dir = new File(samplePath).getParent();
        long base = 100_000_000_000L;

        // 6. gaps: the Horn capture was clean (the old app reported gaps=25 for it)
        List<Object[]> horn = cap(dir, HORN);
        Rig h = new Rig();
        h.t = base;
        h.linked();
        feed(h, base, horn, 0, 99);
        check("real Horn capture: 0 bad frames and gaps = 0 (was 25 in the old app's HEALTH line)", h.e.health().gaps == 0 && h.e.health().bad == 0, "gaps=" + h.e.health().gaps);
        for (String n : new String[]{IGN, OFF, GEAR1, KILL, FBRAKE, PASS}) {
            Rig g = new Rig();
            g.linked();
            feed(g, g.t, cap(dir, n), 0, 99);
            check("real capture " + n.substring(7, n.length() - 4) + ": gaps = 0", g.e.health().gaps == 0, "gaps=" + g.e.health().gaps);
        }

        // Dashboard decoders on real frames
        Rig e1 = new Rig();
        e1.linked();
        List<Object[]> gear = cap(dir, GEAR1);
        long b1 = e1.t;
        feed(e1, b1, gear, 0, 1.0);
        Dashboard d = e1.e.snapshot(e1.t).dash;
        check("real gear-1 test, neutral phase: neutral pill on, gear N, wheels 0.0, battery 13.7-13.8 V",
                d.neutral && d.gear.equals("N") && d.wheelFront.equals("0.0") && d.wheelRear.equals("0.0") && (d.batteryV.equals("13.7") || d.batteryV.equals("13.8")), d.neutral + " " + d.gear + " " + d.wheelFront + " " + d.wheelRear + " " + d.batteryV);
        int eng = Integer.parseInt(d.engineTemp), in = Integer.parseInt(d.intakeTemp);
        check("real gear-1 test: engine temp 55..58 C and intake temp 35..38 C (raw-40)", eng >= 55 && eng <= 58 && in >= 35 && in <= 38, eng + " " + in);
        check("real gear-1 test: B0 bit0 off with the engine running", !d.b0bit0);
        feed(e1, b1, gear, 1.0, 3.1);
        d = e1.e.snapshot(e1.t).dash;
        check("real gear-1 test, 1st gear engaged: gear 1, neutral bit cleared, REAR wheel 5.0 km/h while FRONT wheel 0.0 (bike on stand)", d.gear.equals("1") && !d.neutral && d.wheelRear.equals("5.0") && d.wheelFront.equals("0.0"), d.gear + " " + d.neutral + " " + d.wheelRear + " " + d.wheelFront);

        Rig e2 = new Rig();
        e2.linked();
        feed(e2, e2.t, cap(dir, OFF), 0, 99);
        d = e2.e.snapshot(e2.t).dash;
        check("real engine-off capture: RPM 0, B0 bit0 on, battery 12.4 V", d.rpm.equals("0") && d.b0bit0 && d.batteryV.matches("12\\.[45]"), d.rpm + " " + d.b0bit0 + " " + d.batteryV);
        Rig e3 = new Rig();
        e3.linked();
        feed(e3, e3.t, cap(dir, FBRAKE), 0, 99);
        check("real front-brake capture (engine running): B0 bit0 never on", !e3.e.snapshot(e3.t).dash.b0bit0);
        check("Hunter byte labels: 0x080 B0 unverified, 0x12B B2 rear, 0x211 B2 battery",
                Dashboard.knownLabel("0x80 B0").equals("unverified: on when engine off (was front brake)") && Dashboard.knownLabel("0x12B B2").startsWith("Rear wheel")
                        && Dashboard.knownLabel("0x211 B2").contains("Battery") && Dashboard.knownLabel("0x211 B0").startsWith("Engine temp"));

        // Battery state machine on the real ignition capture (running -> kill switch -> engine off -> start)
        List<Object[]> ign = cap(dir, IGN);
        Rig b = new Rig();
        b.linked();
        long bb = b.t;
        feed(b, bb, ign, 0, 1.0);
        BatteryMonitor.View v = b.e.batteryView(b.t);
        check("real ignition capture t=1 s: running, 13.4 V is Check charging (between 13.0 and 13.5)", v.state == BatteryMonitor.State.RUNNING && v.label.equals("Check charging") && Math.abs(v.volts - 13.4) < 0.11, v.state + " " + v.label + " " + v.volts);
        feed(b, bb, ign, 1.0, 3.0);
        v = b.e.batteryView(b.t);
        check("real ignition capture t=3 s: RPM 0 for under 2 s is 'Engine just stopped'", v.state == BatteryMonitor.State.SETTLING, v.state + "");
        feed(b, bb, ign, 3.0, 4.6);
        v = b.e.batteryView(b.t);
        check("real ignition capture t=4.6 s: engine off, 12.5 V is Fair", v.state == BatteryMonitor.State.OFF && v.label.equals("Fair") && v.level == BatteryMonitor.Level.WARN && Math.abs(v.volts - 12.5) < 0.11, v.state + " " + v.label + " " + v.volts);
        feed(b, bb, ign, 4.6, 5.5);
        v = b.e.batteryView(b.t);
        check("real ignition capture t=5.5 s: cranking, minimum so far near 11.3 V", v.state == BatteryMonitor.State.CRANKING && !Double.isNaN(v.crankMin) && v.crankMin < 11.6, v.state + " " + v.crankMin);
        feed(b, bb, ign, 5.5, 99);
        v = b.e.batteryView(b.t);
        check("real ignition capture end: cranking min 11.2 V is 'Start: good'", Math.abs(v.crankMin - 11.2) < 0.11 && v.crankLabel.equals("Start: good") && v.crankLevel == BatteryMonitor.Level.OK, v.crankMin + " " + v.crankLabel);
        check("real ignition capture end: running 12.9 V (still recovering) is Low charge", v.state == BatteryMonitor.State.RUNNING && v.label.equals("Low charge"), v.state + " " + v.label + " " + v.volts);
        check("real ignition capture: min/max volts 11.2 / 13.6", Math.abs(v.vMin - 11.2) < 0.05 && Math.abs(v.vMax - 13.6) < 0.05, v.vMin + " " + v.vMax);

        Rig rn = new Rig();
        rn.linked();
        feed(rn, rn.t, horn, 0, 99);
        v = rn.e.batteryView(rn.t);
        check("real Horn capture (engine running, ~13.8 V): Charging OK", v.state == BatteryMonitor.State.RUNNING && v.label.equals("Charging OK") && v.level == BatteryMonitor.Level.OK && Math.abs(v.volts - 13.8) < 0.11, v.label + " " + v.volts);
        Rig ro = new Rig();
        ro.linked();
        feed(ro, ro.t, cap(dir, OFF), 0, 99);
        v = ro.e.batteryView(ro.t);
        check("real engine-off capture (~12.4 V, RPM 0): OFF state, Fair", v.state == BatteryMonitor.State.OFF && v.label.equals("Fair") && Math.abs(v.volts - 12.4) < 0.11, v.state + " " + v.label + " " + v.volts);
        Rig rp = new Rig();
        rp.linked();
        feed(rp, rp.t, cap(dir, PASS), 0, 99);
        check("real Pass/Flash capture: load dip shows as min voltage <= 13.0 V while running", rp.e.batteryView(rp.t).vMin <= 13.0 && rp.e.batteryView(rp.t).vMax >= 13.7, rp.e.batteryView(rp.t).vMin + "");

        // HEALTH footer in a recorded capture carries volts min/max/crank min
        Rig rec = new Rig();
        rec.linked();
        long rb = rec.t;
        List<String> lines = recordLines(rec, () -> feed(rec, rb, ign, 0, 99));
        String hl = lines.get(lines.size() - 1);
        check("recorded HEALTH footer has vmin=11.2 vmax=13.6 crankmin=11.2 and gaps=0", hl.startsWith("# HEALTH bad=0") && hl.contains(" gaps=0 ") && hl.contains(" vmin=11.2 vmax=13.6") && hl.contains(" crankmin=11.2"), hl);
        Rig nb = new Rig();
        nb.linked();
        check("HEALTH footer without voltage data has no vmin", !CaptureFormat.healthLine(nb.e.health(), 0).contains("vmin"));
        Rig car = new Rig();
        car.profile("car");
        car.linked();
        car.send(fr(0x211, 1, 2, 0x7F, 0, 0, 0, 0, 0));
        check("non-Hunter profile: 0x211 is not decoded as battery", Double.isNaN(car.e.batteryView(car.t).volts));
    }

    static void testBatteryThresholds() {
        check("engine-off zones: 12.6 Good, 12.2 Fair, 12.1 Low, 11.8 Low, 11.7 Poor",
                BatteryMonitor.offLabel(12.6).equals("Good") && BatteryMonitor.offLabel(12.4).equals("Fair") && BatteryMonitor.offLabel(12.2).equals("Fair")
                        && BatteryMonitor.offLabel(12.1).equals("Low") && BatteryMonitor.offLabel(11.8).equals("Low") && BatteryMonitor.offLabel(11.7).equals("Poor"));
        check("running zones: 13.5-14.8 Charging OK, 13.0-13.5 / 14.8-15.2 Check, <13 Low charge, >15.2 Overcharge",
                BatteryMonitor.runLabel(13.8).equals("Charging OK") && BatteryMonitor.runLabel(13.5).equals("Charging OK") && BatteryMonitor.runLabel(14.8).equals("Charging OK")
                        && BatteryMonitor.runLabel(13.2).equals("Check charging") && BatteryMonitor.runLabel(15.0).equals("Check charging")
                        && BatteryMonitor.runLabel(12.9).equals("Low charge") && BatteryMonitor.runLabel(15.3).equals("Overcharge"));
        check("cranking zones: 9.6 good, 9.0 weak, 8.9 poor",
                BatteryMonitor.crankLabelOf(11.2).equals("Start: good") && BatteryMonitor.crankLabelOf(9.6).equals("Start: good") && BatteryMonitor.crankLabelOf(9.3).equals("Start: weak")
                        && BatteryMonitor.crankLabelOf(9.0).equals("Start: weak") && BatteryMonitor.crankLabelOf(8.9).equals("Start: poor battery"));
        check("levels carry meaning besides colour: Good OK, Fair WARN, Poor BAD; Overcharge BAD",
                BatteryMonitor.offLevel(12.7) == BatteryMonitor.Level.OK && BatteryMonitor.offLevel(12.4) == BatteryMonitor.Level.WARN && BatteryMonitor.offLevel(11.0) == BatteryMonitor.Level.BAD
                        && BatteryMonitor.runLevel(16.0) == BatteryMonitor.Level.BAD);
        check("voltage decode: raw 0x8A = 13.8 V, 0x7C = 12.4 V, 0x70 = 11.2 V", Math.abs(BatteryMonitor.voltsFromRaw(0x8A) - 13.8) < 1e-9 && Math.abs(BatteryMonitor.voltsFromRaw(0x7C) - 12.4) < 1e-9 && Math.abs(BatteryMonitor.voltsFromRaw(0x70) - 11.2) < 1e-9);
        BatteryMonitor m = new BatteryMonitor();
        check("no data: no verdict", m.view(0).state == BatteryMonitor.State.NO_DATA && m.view(0).level == BatteryMonitor.Level.NONE);
        m.onVolts(12.4, 100);
        check("voltage without RPM: voltage only, no verdict", m.view(150).state == BatteryMonitor.State.NO_RPM && m.view(150).level == BatteryMonitor.Level.NONE);
        m.onRpm(0, 200);
        m.onVolts(12.4, 2300);
        m.onRpm(0, 2300);
        check("RPM 0 for > 2 s: engine off verdict", m.view(2400).state == BatteryMonitor.State.OFF && m.view(2400).label.equals("Fair"));
        m.onVolts(10.9, 2400);
        m.onRpm(600, 2450);
        m.onVolts(9.4, 2500);
        m.onVolts(11.5, 3000);
        BatteryMonitor.View v = m.view(3100);
        check("start from engine off: cranking, minimum includes the dip just before RPM rose, 9.4 V is weak", v.state == BatteryMonitor.State.CRANKING && v.crankMin == 9.4 && v.crankLabel.equals("Start: weak"), v.crankMin + " " + v.crankLabel);
        m.onVolts(14.1, 6000);
        m.onRpm(1100, 6000);
        v = m.view(6000);
        check("after the 3 s crank window: running, Charging OK, cranking min kept", v.state == BatteryMonitor.State.RUNNING && v.label.equals("Charging OK") && v.crankMin == 9.4);
        check("stale voltage (> 2 s old) gives no verdict", m.view(9000).state == BatteryMonitor.State.NO_DATA);
        BatteryMonitor m2 = new BatteryMonitor();
        m2.onRpm(1000, 0);
        m2.onVolts(13.9, 10);
        m2.onRpm(0, 500);
        m2.onRpm(900, 900);
        check("RPM returning within 2 s (stall/blip) is not a cranking event", Double.isNaN(m2.view(1000).crankMin) && m2.view(1000).state == BatteryMonitor.State.RUNNING);
    }

    static void testGapRule() {
        // Slow IDs (< 5 Hz) never count gaps; fast IDs count only a real > 1 s silence.
        StatsTable st = new StatsTable();
        int flags = 0;
        long t = 0;
        for (int i = 0; i < 20; i++) { t += 1_000_000_000L; flags |= st.onFrame(0x300, B(1), t); }
        t += 3_000_000_000L;
        flags |= st.onFrame(0x300, B(1), t);
        check("gap rule: a 1 Hz ID with a 3 s pause is not a gap (not >= 5 Hz)", flags == 0 && st.gapCount(0x300) == 0);
        StatsTable s2 = new StatsTable();
        t = 0;
        for (int i = 0; i < 40; i++) { t += 100_000_000L; s2.onFrame(0x211, B(1), t); }
        t += 1_200_000_000L;
        int f = s2.onFrame(0x211, B(1), t);
        check("gap rule: a 10 Hz ID silent for 1.2 s is a gap", f == StatsTable.GAP_FLAG && s2.gapCount(0x211) == 1);
        // link break: the silence between disconnect and reconnect is not a bus gap
        Rig r = new Rig();
        r.linked();
        for (int i = 0; i < 200; i++) r.adv(10).send(fr(0x100, 0, 0, 0, 0, 4, 0xB0, 0, 0));
        r.e.setLinked(false, r.t);
        r.adv(5000);
        r.e.setLinked(true, r.t);
        for (int i = 0; i < 200; i++) r.adv(10).send(fr(0x100, 0, 0, 0, 0, 4, 0xB0, 0, 0));
        check("gap rule: disconnect/reconnect (5 s) does not count as a gap", r.e.health().gaps == 0 && r.e.stats().gapCount(0x100) == 0, "gaps=" + r.e.health().gaps);
        // bitrate auto-detect trial silence
        Rig q = new Rig();
        q.linked();
        for (int i = 0; i < 200; i++) q.adv(10).send(fr(0x100, 0, 0, 0, 0, 4, 0xB0, 0, 0));
        q.e.discontinuity();
        q.adv(4000);
        for (int i = 0; i < 50; i++) q.adv(10).send(fr(0x100, 0, 0, 0, 0, 4, 0xB0, 0, 0));
        check("gap rule: discontinuity() (bitrate detection) suppresses the false gap", q.e.health().gaps == 0);
    }

    static void testStatsAndDashboard() {
        Rig r = new Rig();
        r.t = 10_000_000_000L;
        String f100 = fr(0x100, 0, 0, 0, 0, 0x0F, 0x9C, 0, 0), f150 = fr(0x150, 0x08, 0, 0, 0, 0, 0, 0, 0), f12b = fr(0x12B, 0x03, 0x84, 0xFF, 0xFF, 0, 0, 0, 0);
        String f080 = fr(0x80, 0x01, 0x07, 0, 0, 0, 0, 0, 0x24), f140 = fr(0x140, 0, 0x01, 0xE2, 0x40, 0, 0, 0, 0), f211 = fr(0x211, 0x4C, 0x4D, 0x7F, 0, 0, 0, 0, 0);
        String f120 = fr(0x120, 0xA0, 0, 0, 0, 0, 0, 0, 0);
        r.send(f100 + f150 + f12b + f080 + f140 + f211 + f120);
        CanEngine.Snapshot s = r.e.snapshot(r.t + 100_000_000L);
        check("dashboard decode: RPM 3996, gear 1, front wheel 90.0, rear wheel dash, B0 bit0/clutch/stand on, neutral off, B1 = 7",
                s.dash.rpm.equals("3996") && s.dash.gear.equals("1") && s.dash.wheelFront.equals("90.0") && s.dash.wheelRear.equals(Dashboard.DASH)
                        && s.dash.b0bit0 && s.dash.clutch && s.dash.sideStand && !s.dash.neutral && s.dash.b1.equals("7"), s.dash.rpm + " " + s.dash.gear + " " + s.dash.wheelFront);
        check("dashboard decode: counter 123456, engine 36 C, intake 37 C, battery 12.7 V, MAP 1600",
                s.dash.counter.equals("123456") && s.dash.engineTemp.equals("36") && s.dash.intakeTemp.equals("37") && s.dash.batteryV.equals("12.7") && s.dash.map.equals("1600"));
        check("gear map incl. unknown", Dashboard.gearName(0).equals("N") && Dashboard.gearName(0x38).equals("init") && Dashboard.gearName(0x99).equals("0x99"));
        check("raw table rows sorted by ID, 7 IDs", s.rows.size() == 7 && s.rows.get(0).key == 0x80 && s.rows.get(6).key == 0x211);
        check("all 7 Hunter IDs are known (no unknown IDs)", s.health.unknown.isEmpty());
        r.e.nameByte("0x100 B4", "RPM hi");
        check("row names come from the profile's byte names", r.e.snapshot(r.t).rows.get(1).names.equals("RPM hi"));
        StatsTable st = new StatsTable();
        for (int i = 0; i < 300; i++) st.onFrame(0x100, B(0, 0, 0, 0, 0, i % 2, 0, 0), i * 10_000_000L);
        List<StatsTable.Row> rows = st.rows(3_000_000_000L);
        check("StatsTable: Hz about 100, changing byte flagged, static byte never", Math.abs(rows.get(0).hz - 100) < 2 && rows.get(0).ageNs[5] >= 0 && rows.get(0).ageNs[4] == -1, rows.get(0).hz + "");
        check("StatsTable: Hz decays to 0 after 2.5 s silence", st.rows(10_000_000_000L).get(0).hz == 0);
        CanId.str(5);
        check("CanId text forms", CanId.str(0x80).equals("0x80") && CanId.str(CanId.key(0x80, true)).equals("0x00000080") && CanId.cap(0x80).equals("080") && CanId.byteKey(0x1A0, 2).equals("0x1A0 B2"));
    }

    // ------------------------------------------------------------------ capture file

    static void testCaptureFormatAndWriter(byte[] sample) throws Exception {
        check("frame line format", CaptureFormat.frameLine(3_700_000L, 0x12B, B(0xFF, 0, 4)).equals("0.0037 12B 3 FF 00 04"), CaptureFormat.frameLine(3_700_000L, 0x12B, B(0xFF, 0, 4)));
        check("frame line, dlc 0, > 10 s", CaptureFormat.frameLine(12_345_678_901L, 0x80, new byte[0]).equals("12.3456 080 0"));
        check("29-bit frame line", CaptureFormat.frameLine(1_000_000_000L, CanId.key(0x18DAF110L, true), B(0xDE, 0xAD)).equals("1.0000 18DAF110 2 DE AD"));
        check("mark line format", CaptureFormat.markLine(12_004_999_999L, "BASELINE start Front Brake").equals("# MARK 12.004 BASELINE start Front Brake"));
        check("mark default label and newline injection neutralised", CaptureFormat.markLine(0, "a\nb\r# MARK 1 evil").indexOf('\n') < 0 && CaptureFormat.markLine(0, "  ").equals("# MARK 0.000 event"));
        check("event line format", CaptureFormat.eventLine("DISCONNECT", 140_004_000_000L).equals("# DISCONNECT 140.004"));

        File out = new File(outDir, "engine_capture.txt");
        Rig r = new Rig();
        r.t = 1_000_000_000_000L;
        r.linked();
        FileOutputStream fos = new FileOutputStream(out, false);
        r.e.startRecording(fos, "unit test", "2026-11-14T10:00:00Z", r.t);
        int pos = 0;
        Random rr = new Random(3);
        boolean mid = false;
        while (pos < sample.length) {
            int n = Math.min(1 + rr.nextInt(64), sample.length - pos);
            r.t += 3_700_000L * Math.max(1, n / 25);
            r.e.onBytes(sample, pos, n, r.t);
            pos += n;
            if (!mid && pos > sample.length / 2) {
                mid = true;
                r.e.mark("halfway", r.t);
                r.e.linkEvent("DISCONNECT", r.t + 1_000_000L);
                r.e.linkEvent("RECONNECT", r.t + 2_000_000_000L);
            }
            if (rr.nextInt(200) == 0) r.e.flushRecording();
        }
        long lines = r.e.stopRecording();
        long total = r.e.totalFrames();
        check("engine total frames ~ Python (a DISCONNECT may drop one half line)", total >= 42579 && total <= 42580, "total=" + total);
        CaptureReader back = CaptureReader.read(new InputStreamReader(new FileInputStream(out), L1));
        check("capture file reads back: frames equal engine count, marks present (MARK, DISCONNECT, RECONNECT)",
                back.frames.size() == total && back.marks.size() == 3 && back.marks.get(0).text.equals("halfway") && back.marks.get(1).text.equals("DISCONNECT") && back.marks.get(2).text.equals("RECONNECT"),
                back.frames.size() + " frames, " + back.marks.size() + " marks");
        List<String> all = Files.readAllLines(out.toPath(), L1);
        check("capture header: first line, APP, PROFILE in order", all.get(0).equals("# Hunter live capture, label: unit test, started 2026-11-14T10:00:00Z") && all.get(1).startsWith("# APP hunter-can-android 2")
                && all.get(2).equals("# PROFILE id=hunter name=Hunter_350 bitrate=500000 idfmt=both listen_only=1"), all.subList(0, 3).toString());
        check("capture ends with a HEALTH line", all.get(all.size() - 1).startsWith("# HEALTH bad="), all.get(all.size() - 1));
        check("reader exposes profile and health", "hunter".equals(back.profile.get("id")) && back.health != null && back.app != null);
        check("line count = frames + 3 header + 3 marks/events + HEALTH", lines == total + 7, "lines=" + lines + " frames=" + total);
        boolean mono = true;
        double prev = -1;
        for (CanFrame f : back.frames) { if (f.t < prev) mono = false; prev = f.t; }
        check("capture timestamps monotonic", mono);
        byte[] raw = Files.readAllBytes(out.toPath());
        CaptureReader rec = CaptureReader.read(new StringReader(new String(raw, 0, raw.length - 60, L1)));
        check("truncated (crash) file: all but the cut last line parse", rec.frames.size() >= back.frames.size() - 3 && rec.frames.size() <= back.frames.size(), rec.frames.size() + " vs " + back.frames.size());
        File out2 = new File(outDir, "engine_flush.txt");
        Rig f = new Rig();
        f.e.startRecording(new FileOutputStream(out2), "flush", "now", 0);
        f.e.onText("t12B8FFFFFFFF04000000\r", 5_000_000L);
        long before = out2.length();
        f.e.flushRecording();
        check("flushRecording makes data visible on disk without close (" + before + " -> " + out2.length() + " bytes)", before == 0 && out2.length() > 30);
        f.e.stopRecording();
        check("stopRecording twice is harmless", f.e.stopRecording() == 0 && !f.e.isRecording());
        Rig lab = new Rig();
        List<String> ll = recordLines(lab, () -> lab.e.setRecordingLabel("x"));
        check("labels cannot break the header line", CaptureFormat.firstLine("a\nb\r\n# evil", "T").indexOf('\n') < 0);
        // names written to a running capture
        Rig nm = new Rig();
        List<String> nl = recordLines(nm, () -> nm.e.nameByte("0x1A0 B2", "Brake\nbits"));
        check("naming a byte during recording writes a # SIGNAL line (single line)", nl.contains("# SIGNAL 0x1A0 B2 = Brake bits"), nl.toString());
        check("bad byte key refused", !nm.e.nameByte("0x1A0", "x") && !nm.e.nameByte("0x1A0 B2", "  "));
    }

    static void testRoundTripCar() throws Exception {
        Rig r = new Rig();
        r.e.setProfile("car");
        r.linked();
        r.e.addSignal(UserSignal.sanitize(M("id", "7E8", "start", 3.0, "len", 2.0, "endian", "be", "scale", 0.25, "offset", 0.0, "unit", "rpm", "name", "Engine speed", "unverified", Boolean.TRUE)));
        r.e.addSignal(UserSignal.sanitize(M("id", "18DAF110", "start", 1.0, "len", 2.0, "endian", "le", "signed", Boolean.TRUE, "scale", 0.1, "offset", -5.0, "unit", "C", "name", "Ext temp")));
        r.e.nameByte("0x1A0 B2", "Brake bits");
        List<String> lines = recordLines(r, () -> {
            for (int i = 0; i < 100; i++) {
                r.adv(10);
                r.send(fr(0x1A0, 0, 0, i, 0, 0, 0, 0, 0)).send(fr(0x18DAF110L, true, 0, i, 0, 0, 0, 0, 0, 0));
                int raw = 0x1AF8 + i;
                r.send(fr(0x7E8, 4, 0x41, 0x0C, raw >> 8, raw & 255, 0, 0, 0));
            }
            r.e.linkEvent("DISCONNECT", r.t);
            r.adv(500);
            r.e.linkEvent("RECONNECT", r.t);
            r.e.mark("end mark", r.t);
        });
        File file = new File(outDir, "car_capture.txt");
        Files.write(file.toPath(), (String.join("\n", lines) + "\n").getBytes(L1));
        check("car capture: HEALTH line and disconnect/reconnect markers", lines.stream().anyMatch(l -> l.startsWith("# HEALTH bad=0")) && lines.stream().anyMatch(l -> l.startsWith("# DISCONNECT"))
                && lines.stream().anyMatch(l -> l.startsWith("# RECONNECT")));
        String out = analyze(file.getPath());
        check("analyzer round trip (car): 300 frames, 29-bit ID with 8 digits, 0x7E8", find(out, "300 frames") && find(out, "0x18DAF110\\s+100") && find(out, "0x7E8\\s+100"), out);
        check("analyzer round trip (car): byte name from the header", find(out, "0x1A0 B2 = Brake bits"), out);
        Matcher m = Pattern.compile("Engine speed \\[unverified\\]: 0x7E8 B3\\+2 BE -> min ([\\d.]+) max ([\\d.]+) mean ([\\d.]+) last ([\\d.]+) rpm \\(100 samples\\)").matcher(out);
        check("analyzer round trip (car): user decoder min/max equal the engine's math", m.find() && Math.abs(Double.parseDouble(m.group(1)) - 0x1AF8 / 4.0) < 1e-3 && Math.abs(Double.parseDouble(m.group(2)) - (0x1AF8 + 99) / 4.0) < 1e-3, out);
        Matcher t = Pattern.compile("Ext temp: 0x18DAF110 B1\\+2 LE -> min ([-\\d.]+) max ([-\\d.]+)").matcher(out);
        check("analyzer round trip (car): signed little-endian 29-bit decoder decoded", t.find() && Math.abs(Double.parseDouble(t.group(1)) + 5) < 1e-3 && Math.abs(Double.parseDouble(t.group(2)) - (99 * 0.1 - 5)) < 1e-3, out);
        check("analyzer round trip (car): passive OBD summary, HEALTH and connection events", find(out, "PID 0C Engine speed: min 1726\\.00 max 1750\\.75") && find(out, "Recorded HEALTH") && find(out, "Connection events: DISCONNECT@"), out);
    }

    static void testRoundTripHunter(byte[] sample) throws Exception {
        Rig r = new Rig();
        r.t = 1_000_000_000L;
        r.linked();
        File file = new File(outDir, "hunter_capture.txt");
        FileOutputStream fos = new FileOutputStream(file);
        r.e.startRecording(fos, "hunter sample", "2026-11-14T10:00:00Z", r.t);
        String[] segs = sampleStr.split("\r");
        for (String s : segs) {
            if (s.isEmpty()) continue;
            r.t += 3_700_000L;
            r.e.onText(s + "\r", r.t);
        }
        r.e.stopRecording();
        String out = analyze(file.getPath());
        String ref = analyze(sampleFile);
        check("analyzer round trip (Hunter): 42580 frames re-read from the recorded file", find(out, "42580 frames over") && find(out, "Profile: Hunter 350, 500 kbps"), out.substring(0, 300));
        boolean same = true;
        String why = "";
        for (String re : new String[]{"RPM \\(0x100 B4:B5, confirmed\\): min \\d+ max \\d+", "Gear \\(0x150 B0, confirmed\\): .*", "Front wheel km/h.*", "0x140 24-bit counter.*", "Engine temp.*", "Intake temp.*", "Battery V.*"}) {
            String x = grab(out, re), y = grab(ref, re);
            if (x == null || !x.equals(y)) { same = false; why += " [" + re + ": " + x + " vs " + y + "]"; }
        }
        check("analyzer round trip (Hunter): RPM, gear, wheel and counter lines equal the analysis of the original capture", same, why);
        check("recorded Hunter file: per-ID counts equal the original", sameIdTable(out, ref), "");
    }

    static String sampleFile;

    static boolean sameIdTable(String a, String b) {
        Matcher ma = Pattern.compile("(0x[0-9A-F]{3})\\s+(\\d+)\\s").matcher(a);
        Map<String, String> m = new TreeMap<>();
        while (ma.find()) m.put(ma.group(1), ma.group(2));
        Matcher mb = Pattern.compile("(0x[0-9A-F]{3})\\s+(\\d+)\\s").matcher(b);
        Map<String, String> n = new TreeMap<>();
        while (mb.find()) n.put(mb.group(1), mb.group(2));
        return !m.isEmpty() && m.equals(n);
    }

    static void testLegacyLogs() throws Exception {
        File f = new File(outDir, "legacy_serial.txt");
        Files.write(f.toPath(), bytes("t1008FF80A00003102C00\rT18DAF1102AABB\rt1008FF80A00003102C00\r"));
        String out = analyze(f.getPath());
        check("legacy header-less serial log with a 29-bit frame still analysed", find(out, "3 frames") && find(out, "0x18DAF110"), out);
        CaptureReader cr = CaptureReader.read(new StringReader("# Hunter live capture, label: old, started x\n0.0010 100 2 01 02\n"));
        check("old v1 capture (no PROFILE line) reads with 11-bit IDs", cr.frames.size() == 1 && cr.frames.get(0).key == 0x100 && cr.profile.isEmpty());
    }

    static void testReconnectEvents() {
        Rig r = new Rig();
        r.linked();
        List<String> lines = recordLines(r, () -> {
            r.adv(10).send(fr(0x100, 1, 2));
            r.send("t100");  // half line, then the cable is pulled
            r.e.setLinked(false, r.t);
            r.e.linkEvent("DISCONNECT", r.t);
            r.adv(2000);
            r.e.setLinked(true, r.t);
            r.e.linkEvent("RECONNECT", r.t);
            r.send("2AABB\r");                        // would be glued onto the half line without the parser reset
            r.adv(10).send(fr(0x100, 3, 4));
            r.e.linkEvent("USER_DISCONNECT", r.t);
        });
        long frames = lines.stream().filter(l -> l.matches("^\\d+\\.\\d{4} .*")).count();
        check("link loss: half line is dropped, not glued to the next data (2 frames, 1 malformed line)", frames == 2 && r.e.health().bad == 1, lines + " bad=" + r.e.health().bad);
        check("link events written in order DISCONNECT, RECONNECT, USER_DISCONNECT",
                lines.stream().filter(l -> l.startsWith("# DISCONNECT") || l.startsWith("# RECONNECT") || l.startsWith("# USER_DISCONNECT")).map(l -> l.split(" ")[1]).collect(java.util.stream.Collectors.toList())
                        .equals(Arrays.asList("DISCONNECT", "RECONNECT", "USER_DISCONNECT")));
    }

    // ------------------------------------------------------------------ source audit (Android layer, static)

    static void testSourceAudit() throws Exception {
        if (appDir == null) return;
        String link = new String(Files.readAllBytes(Paths.get(appDir, "CdcAcmLink.java")), "UTF-8");
        int writes = count(link, "bulkTransfer(epOut");
        check("source audit: CdcAcmLink has exactly one OUT transfer call site", writes == 1, "count=" + writes);
        check("source audit: that call site is fed by CommandGuard.wireBytes", link.indexOf("CommandGuard.wireBytes") > 0 && link.indexOf("CommandGuard.wireBytes") < link.indexOf("bulkTransfer(epOut"));
        StringBuilder all = new StringBuilder();
        File[] fs = new File(appDir).listFiles();
        int writers = 0;
        for (File f : fs) {
            if (!f.getName().endsWith(".java")) continue;
            String s = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            writers += count(s, "epOut") > 0 ? 1 : 0;
            if (count(s, "sendCommand(\"t") + count(s, "sendCommand(\"T") + count(s, "sendCommand(\"r") > 0) writers += 100;
            all.append(s);
        }
        check("source audit: only CdcAcmLink touches the OUT endpoint, no code sends t/T/r frames", writers == 1, "writers=" + writers);
        Matcher m = Pattern.compile("sendCommand\\(([^)]*)\\)").matcher(all);
        boolean literalsOk = true;
        String bad = "";
        while (m.find()) {
            String arg = m.group(1).trim();
            if (arg.startsWith("\"") && !arg.matches("\"(C|M1|O|V|S[0-8])\"")) { literalsOk = false; bad += arg; }
        }
        check("source audit: every literal command passed to sendCommand is on the allow-list", literalsOk, bad);
        String svc = new String(Files.readAllBytes(Paths.get(appDir, "CanService.java")), "UTF-8");
        check("source audit: service never builds commands from user text (setup goes through SlcanSetup)", svc.contains("SlcanSetup.apply") && !svc.contains("sendCommand(label") && !svc.contains("sendCommand(text"));
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static int countIds(Map<String, String> exp) {
        int n = 0;
        for (String k : exp.keySet()) if (k.startsWith("id ")) n++;
        return n;
    }

    static int firstCr(byte[] d, int count) {
        int i = 0, seen = 0;
        while (i < d.length && seen < count) { if (d[i] == '\r') seen++; i++; }
        return i;
    }

    static long countFrames(byte[] d, int len) {
        Collector c = new Collector();
        new SlcanParser(c).feed(d, 0, len);
        return c.n;
    }
}
