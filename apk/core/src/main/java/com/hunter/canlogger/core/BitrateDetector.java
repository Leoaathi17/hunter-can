package com.hunter.canlogger.core;

import java.io.IOException;

/**
 * Bitrate auto-detect, listen-only. Tries 500k, 250k, 125k, 1M in that order: applies C,S<n>,M1,O, waits for the
 * adapter to settle, then watches the traffic for a dwell time. A rate is accepted when at least 3 frames arrive, no
 * error beep (BEL) is seen and malformed lines stay under 5% of the frames. Nothing but setup commands is ever sent.
 * If nothing works the previous bitrate is restored.
 */
public final class BitrateDetector {
    private BitrateDetector() {}

    public interface Env extends SlcanSetup.Sink {
        /** Frames parsed so far (any ID format). */
        long frames();
        /** Adapter error beeps seen so far. */
        long beeps();
        /** Malformed lines plus overflows so far. */
        long malformed();
        /** Progress text for the UI ("Trying 500 kbps..."). */
        void progress(String text);
    }

    public static final class Timing {
        public long cmdMs = SlcanSetup.DEFAULT_CMD_MS;
        public long settleMs = 150;
        public long detectMs = 1500;
    }

    /** @return the detected bitrate in kbit/s, or 0 if no clean traffic was found (previous rate restored). */
    public static int run(Env env, int previousKbps, Timing t) throws IOException, InterruptedException {
        for (int k : Bitrates.DETECT_ORDER) {
            env.progress("Trying " + k + " kbps...");
            SlcanSetup.apply(env, k, t.cmdMs);
            env.sleep(t.settleMs);
            long f0 = env.frames(), b0 = env.beeps(), x0 = env.malformed();
            env.sleep(t.detectMs);
            long f = env.frames() - f0, b = env.beeps() - b0, x = env.malformed() - x0;
            if (f >= 3 && b == 0 && x <= f * 0.05) {
                env.progress("Found " + k + " kbps. Frames arrive with no error beeps.");
                return k;
            }
        }
        try {
            SlcanSetup.apply(env, previousKbps, t.cmdMs);
        } catch (IOException e) {
            // best effort restore
        }
        env.progress("No clean traffic at 500k, 250k, 125k or 1M. Check wiring, ignition and termination.");
        return 0;
    }
}
