package com.hunter.canlogger.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the app remembers: selected profile, bitrate and ID format per profile, signals per profile. */
public final class AppSettings {
    public String profile = VehicleProfile.HUNTER.id;
    private final Map<String, Integer> rate = new LinkedHashMap<>();
    private final Map<String, IdFormat> fmt = new LinkedHashMap<>();
    private final Map<String, SignalSet> sets = new LinkedHashMap<>();

    public AppSettings() {
        for (VehicleProfile p : VehicleProfile.ALL) {
            rate.put(p.id, p.defaultKbps);
            fmt.put(p.id, IdFormat.BOTH);
            sets.put(p.id, new SignalSet());
        }
    }

    public VehicleProfile profile() {
        VehicleProfile p = VehicleProfile.byId(profile);
        return p == null ? VehicleProfile.HUNTER : p;
    }

    public int rate() { return rate(profile); }

    public int rate(String pid) {
        Integer r = rate.get(pid);
        return r != null && Bitrates.isValid(r) ? r : 500;
    }

    public void setRate(String pid, int kbps) { if (Bitrates.isValid(kbps) && VehicleProfile.byId(pid) != null) rate.put(pid, kbps); }

    public IdFormat fmt() {
        IdFormat f = fmt.get(profile);
        return f == null ? IdFormat.BOTH : f;
    }

    public void setFmt(String pid, IdFormat f) { if (VehicleProfile.byId(pid) != null && f != null) fmt.put(pid, f); }

    public SignalSet signals() { return signals(profile); }

    public SignalSet signals(String pid) {
        SignalSet s = sets.get(pid);
        if (s == null) { s = new SignalSet(); sets.put(pid, s); }
        return s;
    }

    // ---------------------------------------------------------------- persistence

    public String toJson() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("profile", profile);
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> f = new LinkedHashMap<>();
        Map<String, Object> sg = new LinkedHashMap<>();
        for (VehicleProfile p : VehicleProfile.ALL) {
            r.put(p.id, (double) rate(p.id));
            IdFormat x = fmt.get(p.id);
            f.put(p.id, (x == null ? IdFormat.BOTH : x).code);
            sg.put(p.id, signals(p.id).toMap(p.id));
        }
        o.put("rate", r);
        o.put("fmt", f);
        o.put("signals", sg);
        return MiniJson.write(o, false);
    }

    /** Lenient: anything unreadable falls back to defaults. Never throws. */
    @SuppressWarnings("unchecked")
    public static AppSettings fromJson(String text) {
        AppSettings a = new AppSettings();
        if (text == null) return a;
        try {
            Object o = MiniJson.parse(text);
            if (!(o instanceof Map)) return a;
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.get("profile") instanceof String && VehicleProfile.byId((String) m.get("profile")) != null) a.profile = (String) m.get("profile");
            if (m.get("rate") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("rate")).entrySet()) {
                    if (e.getValue() instanceof Number) a.setRate(e.getKey(), ((Number) e.getValue()).intValue());
                }
            }
            if (m.get("fmt") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("fmt")).entrySet()) {
                    if (e.getValue() instanceof String) a.setFmt(e.getKey(), IdFormat.fromCode((String) e.getValue()));
                }
            }
            if (m.get("signals") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("signals")).entrySet()) {
                    if (VehicleProfile.byId(e.getKey()) != null) a.signals(e.getKey()).importValue(e.getValue());
                }
            }
        } catch (RuntimeException e) {
            return new AppSettings();
        }
        return a;
    }

    public List<String> profileIds() {
        List<String> l = new ArrayList<>();
        for (VehicleProfile p : VehicleProfile.ALL) l.add(p.id);
        return l;
    }
}
