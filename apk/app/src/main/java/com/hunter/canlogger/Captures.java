package com.hunter.canlogger;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Capture and export files in the app's external files dir: Android/data/com.hunter.canlogger/files/captures/ */
final class Captures {
    private Captures() {}

    static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "captures");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Capture files (.txt), newest first. */
    static List<File> list(Context c) {
        File[] fs = dir(c).listFiles();
        List<File> out = new ArrayList<>();
        if (fs != null) {
            for (File f : fs) if (f.isFile() && f.getName().endsWith(".txt")) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        return out;
    }

    static File latest(Context c) {
        List<File> l = list(c);
        return l.isEmpty() ? null : l.get(0);
    }

    static String safeName(String s) {
        String r = (s == null ? "" : s.trim()).replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
        return r.isEmpty() ? "capture" : r;
    }
}
