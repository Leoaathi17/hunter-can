package com.hunter.canlogger.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** User signals (decoders) and byte names for ONE vehicle profile. Not thread-safe; CanEngine guards access. */
public final class SignalSet {
    private static final AtomicInteger UID = new AtomicInteger();
    private static final Pattern NAME_KEY = Pattern.compile("^0x[0-9A-F]+ B\\d$");

    public static final String APP = "hunter-can-live";

    public final List<UserSignal> decoders = new ArrayList<>();
    /** "0x1A0 B2" -> "Brake bits" */
    public final Map<String, String> names = new LinkedHashMap<>();

    public static boolean isNameKey(String k) { return k != null && NAME_KEY.matcher(k).matches(); }

    public UserSignal add(UserSignal s) {
        if (s == null) return null;
        UserSignal d = s.withUid(UID.incrementAndGet());
        decoders.add(d);
        return d;
    }

    public boolean remove(int uid) {
        for (int i = 0; i < decoders.size(); i++) {
            if (decoders.get(i).uid == uid) { decoders.remove(i); return true; }
        }
        return false;
    }

    public Map<Long, List<UserSignal>> index() {
        Map<Long, List<UserSignal>> m = new HashMap<>();
        for (UserSignal d : decoders) {
            List<UserSignal> l = m.get(d.key());
            if (l == null) { l = new ArrayList<>(); m.put(d.key(), l); }
            l.add(d);
        }
        return m;
    }

    public void setName(String key, String name) {
        String n = Text.clean(name);
        if (isNameKey(key) && !n.isEmpty()) names.put(key, n);
    }

    // ---------------------------------------------------------------- JSON

    public Map<String, Object> toMap(String profileId) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("app", APP);
        o.put("version", 1.0);
        o.put("profile", profileId);
        List<Object> ds = new ArrayList<>();
        for (UserSignal d : decoders) ds.add(d.toMap());
        o.put("decoders", ds);
        o.put("names", new LinkedHashMap<String, Object>(names));
        return o;
    }

    /** Export in the same shape as the PWA ("hunter-can-live", version 1, profile, decoders, names). */
    public String exportJson(String profileId) {
        return MiniJson.write(toMap(profileId), true);
    }

    /** Imports decoders and names; invalid entries are skipped. Returns the number of decoders imported (0 on bad JSON). */
    @SuppressWarnings("unchecked")
    public int importJson(String text) {
        Object o;
        try { o = MiniJson.parse(text); } catch (RuntimeException e) { return 0; }
        return importValue(o);
    }

    @SuppressWarnings("unchecked")
    int importValue(Object o) {
        List<Object> list = null;
        if (o instanceof List) list = (List<Object>) o;
        else if (o instanceof Map && ((Map<String, Object>) o).get("decoders") instanceof List) list = (List<Object>) ((Map<String, Object>) o).get("decoders");
        int n = 0;
        if (list != null) {
            for (Object x : list) {
                if (!(x instanceof Map)) continue;
                UserSignal d = UserSignal.sanitize((Map<String, Object>) x);
                if (d != null) { add(d); n++; }
            }
        }
        if (o instanceof Map && ((Map<String, Object>) o).get("names") instanceof Map) {
            Map<String, Object> nm = (Map<String, Object>) ((Map<String, Object>) o).get("names");
            for (Map.Entry<String, Object> e : nm.entrySet()) {
                if (isNameKey(e.getKey()) && e.getValue() != null) setName(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return n;
    }
}
