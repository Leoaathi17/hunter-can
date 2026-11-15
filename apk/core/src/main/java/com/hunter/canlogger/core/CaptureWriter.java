package com.hunter.canlogger.core;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Append-only capture writer. Buffers in memory; flush() pushes to the OS and fsyncs file streams,
 * so after a crash or power loss at most the last flush interval is missing. A truncated last line
 * is tolerated by the analysis script (it skips lines that do not match).
 */
public final class CaptureWriter implements Closeable {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final OutputStream raw;
    private final BufferedOutputStream out;
    private final StringBuilder sb = new StringBuilder(128);
    private long lines;
    private boolean failed;
    private String failure;
    private boolean closed;

    public CaptureWriter(OutputStream raw, List<String> header) {
        this.raw = raw;
        this.out = new BufferedOutputStream(raw, 16 * 1024);
        for (String h : header) writeLine(h);
    }

    private synchronized void writeLine(String s) {
        if (closed || failed) return;
        try {
            out.write(s.getBytes(UTF8));
            out.write('\n');
            lines++;
        } catch (IOException e) {
            failed = true;
            failure = e.getMessage();
        }
    }

    public synchronized void writeFrame(long relNs, long key, byte[] data) {
        if (closed || failed) return;
        sb.setLength(0);
        CaptureFormat.appendFrame(sb, relNs, key, data);
        sb.append('\n');
        try {
            out.write(sb.toString().getBytes(UTF8));
            lines++;
        } catch (IOException e) {
            failed = true;
            failure = e.getMessage();
        }
    }

    public void writeRaw(String line) { writeLine(line); }

    public synchronized void writeMark(long relNs, String label) { writeLine(CaptureFormat.markLine(relNs, label)); }

    public synchronized void writeEvent(String name, long relNs) { writeLine(CaptureFormat.eventLine(name, relNs)); }

    /** Push buffered data to the OS and fsync (file streams). Safe to call from any thread. */
    public void flush() {
        synchronized (this) {
            if (closed || failed) return;
            try {
                out.flush();
            } catch (IOException e) {
                failed = true;
                failure = e.getMessage();
                return;
            }
        }
        if (raw instanceof FileOutputStream) {
            try {
                ((FileOutputStream) raw).getFD().sync();
            } catch (IOException ignored) {
                // fsync not supported or failed; data is still in the OS cache
            }
        }
    }

    public synchronized long lines() { return lines; }
    public synchronized boolean failed() { return failed; }
    public synchronized String failure() { return failure; }

    @Override
    public void close() {
        flush();
        synchronized (this) {
            if (closed) return;
            closed = true;
            try { out.close(); } catch (IOException ignored) { }
        }
    }
}
