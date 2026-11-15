package com.hunter.canlogger.core;

import java.io.IOException;

/** The only setup sequence the app ever sends: C, S<n>, M1 (listen-only), O. Every command passes CommandGuard. */
public final class SlcanSetup {
    private SlcanSetup() {}

    public static final long DEFAULT_CMD_MS = 150;

    public interface Sink {
        /** Sends one command (without CR). The implementation must go through CommandGuard. */
        void send(String cmd) throws IOException;
        void sleep(long ms) throws InterruptedException;
    }

    public static final String[] sequence(int kbps) {
        return new String[]{"C", Bitrates.command(kbps), "M1", "O"};
    }

    public static void apply(Sink sink, int kbps, long cmdMs) throws IOException, InterruptedException {
        for (String c : sequence(kbps)) {
            CommandGuard.requireAllowed(c);
            sink.send(c);
            sink.sleep(cmdMs);
        }
    }
}
