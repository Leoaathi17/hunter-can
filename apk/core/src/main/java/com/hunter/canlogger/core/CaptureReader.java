package com.hunter.canlogger.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the text capture format back (mirrors parse_live in hunter_analyze.py). Used by tests and tools. */
public final class CaptureReader {
    public static final class Mark {
        public final double t;
        public final String text;
        public Mark(double t, String text) { this.t = t; this.text = text; }
    }

    public final List<CanFrame> frames = new ArrayList<>();
    public final List<Mark> marks = new ArrayList<>();
    /** key=value pairs of the "# PROFILE" line. */
    public final Map<String, String> profile = new LinkedHashMap<>();
    public final List<String> signalLines = new ArrayList<>();
    public String app;
    public String health;
    public String firstLine;

    private static final Pattern LIVE = Pattern.compile("^(\\d+\\.\\d+)\\s+([0-9A-F]{3}|[0-9A-F]{8})\\s+(\\d)\\s*((?:[0-9A-F]{2}\\s*)*)$");

    public static CaptureReader read(Reader r) throws IOException {
        CaptureReader c = new CaptureReader();
        BufferedReader br = new BufferedReader(r);
        String line;
        boolean first = true;
        while ((line = br.readLine()) != null) {
            if (first) { c.firstLine = line; first = false; }
            if (line.startsWith("# MARK ")) {
                String[] p = line.split(" ", 4);
                try { c.marks.add(new Mark(Double.parseDouble(p[2]), p.length > 3 ? p[3] : "")); } catch (RuntimeException e) { /* skip */ }
            } else if (line.startsWith("# DISCONNECT ") || line.startsWith("# RECONNECT ") || line.startsWith("# USER_DISCONNECT ")) {
                String[] p = line.trim().split("\\s+");
                try { c.marks.add(new Mark(Double.parseDouble(p[2]), p[1])); } catch (RuntimeException e) { /* skip */ }
            } else if (line.startsWith("# PROFILE ")) {
                for (String t : line.substring(10).trim().split("\\s+")) {
                    int i = t.indexOf('=');
                    if (i > 0) c.profile.put(t.substring(0, i), t.substring(i + 1));
                }
            } else if (line.startsWith("# APP ")) {
                c.app = line.substring(6).trim();
            } else if (line.startsWith("# SIGNAL ")) {
                c.signalLines.add(line);
            } else if (line.startsWith("# HEALTH")) {
                c.health = line.substring(2).trim();
            } else if (line.startsWith("#")) {
                continue;
            } else {
                Matcher m = LIVE.matcher(line.trim());
                if (m.matches()) {
                    int n = Integer.parseInt(m.group(3));
                    String hex = m.group(4).replace(" ", "");
                    if (hex.length() != 2 * n) continue;
                    byte[] d = new byte[n];
                    for (int i = 0; i < n; i++) d[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                    String id = m.group(2);
                    c.frames.add(new CanFrame(Double.parseDouble(m.group(1)), CanId.key(Long.parseLong(id, 16), id.length() == 8), d));
                }
            }
        }
        return c;
    }
}
