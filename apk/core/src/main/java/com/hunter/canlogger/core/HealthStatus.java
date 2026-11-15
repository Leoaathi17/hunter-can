package com.hunter.canlogger.core;

import java.util.ArrayList;
import java.util.List;

/** Overall bus status: IDLE (not connected), OK, WARN, CHECK, with the reasons in plain words. */
public final class HealthStatus {
    public static final int IDLE = -1, OK = 0, WARN = 1, CHECK = 2;

    public final int lvl;
    public final List<String> reasons = new ArrayList<>();

    public HealthStatus(int lvl) { this.lvl = lvl; }

    public String label() { return lvl < 0 ? "IDLE" : lvl == 0 ? "OK" : lvl == 1 ? "WARN" : "CHECK"; }

    public String summary() {
        return lvl < 0 ? "Not connected." : lvl == 0 ? "No problems seen." : lvl == 1 ? "Something to look at." : "Fix the connection first.";
    }
}
