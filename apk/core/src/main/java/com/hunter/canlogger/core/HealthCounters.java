package com.hunter.canlogger.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable copy of the bus-health counters (same set as H in the PWA). */
public final class HealthCounters {
    public long bad, resync, bel, overflow, gaps, ctrJump, dlcOdd, filtered, rtr, obdReq, dtcResp;
    /** Unknown IDs (Hunter profile only): key -> frames seen. */
    public final Map<Long, Integer> unknown = new LinkedHashMap<>();
    public boolean unknownTracked;
}
