package com.hunter.canlogger;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.hunter.canlogger.core.CanEngine;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** Record: label, start/stop, mark, share, capture list, log. Files are written append-only and fsynced every 3 s. */
final class RecordTab extends PageTab {
    private final EditText label;
    private final Button bRec, bMark, bShare, bFiles;
    private final TextView info, log;
    private boolean recording;

    RecordTab(final MainActivity act) {
        super(act);
        android.widget.LinearLayout c = Ui.card(ctx);
        c.addView(Ui.h2(ctx, "Record"));
        label = Ui.input(ctx, "Label, e.g. Pass / Flash", null);
        c.addView(Ui.text(ctx, "Label", 12, Ui.MUT));
        c.addView(label, Ui.lp(Ui.MATCH, Ui.WRAP));
        bRec = Ui.button(ctx, "Start recording", true, new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecording(); }
        });
        bMark = Ui.button(ctx, "Mark event", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                CanService s = CanService.get();
                if (s != null && eng.isRecording()) {
                    s.mark(label.getText().toString());
                    act.toast("Marked");
                }
            }
        });
        c.addView(row(bRec, bMark));
        bShare = Ui.button(ctx, "Share latest", false, new View.OnClickListener() {
            @Override public void onClick(View v) { shareLatest(); }
        });
        bFiles = Ui.button(ctx, "All captures", false, new View.OnClickListener() {
            @Override public void onClick(View v) { pickCapture(); }
        });
        c.addView(row(bShare, bFiles));
        info = Ui.note(ctx, "Not recording.");
        info.setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 6), 0, 0);
        c.addView(info);
        c.addView(Ui.note(ctx, "Captures are written to disk as they arrive (flushed every 3 s), so nothing is lost if the app is closed. "
                + "Files are plain text and open with hunter_analyze.py. Location: Android/data/com.hunter.canlogger/files/captures/"));
        Ui.addCard(page, c);

        android.widget.LinearLayout lc = Ui.card(ctx);
        lc.addView(Ui.h2(ctx, "Log"));
        log = Ui.mono(ctx, "", 11, Ui.MUT);
        log.setBackground(Ui.shape(ctx, 0xB3030810, 0, 10));
        log.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8));
        lc.addView(log, Ui.lp(Ui.MATCH, Ui.WRAP));
        Ui.addCard(page, lc);
    }

    private void toggleRecording() {
        CanService s = CanService.get();
        if (recording) {
            if (s != null) s.stopRecording();
            return;
        }
        if (s == null || !s.isLinkUp()) { act.toast("Connect first."); return; }
        String err = s.startRecording(label.getText().toString());
        if (err != null) act.toast("Cannot record: " + err);
    }

    /** Called by the Identify tab when it wants the label field to follow the selected control. */
    void setLabel(String s) { label.setText(s); }

    private void shareLatest() {
        CanService s = CanService.get();
        if (s != null) s.flushNow();
        File f = Captures.latest(ctx);
        if (f == null) { act.toast("No capture yet."); return; }
        act.shareFile(f, "text/plain");
    }

    private void pickCapture() {
        final List<File> all = Captures.list(ctx);
        if (all.isEmpty()) { act.toast("No captures yet."); return; }
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) names[i] = all.get(i).getName() + "  (" + (all.get(i).length() / 1024) + " kB)";
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Share which capture?")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        CanService s = CanService.get();
                        if (s != null) s.flushNow();
                        act.shareFile(all.get(which), "text/plain");
                    }
                })
                .show();
    }

    @Override
    public void refresh(CanEngine.Snapshot s, long nowNs) {
        recording = s.recording;
        boolean testing = s.testPhase == CanEngine.TestPhase.BASELINE || s.testPhase == CanEngine.TestPhase.PREPARE || s.testPhase == CanEngine.TestPhase.EVENT;
        bRec.setText(s.recording ? "Stop recording" : "Start recording");
        Ui.enable(bRec, (s.linked || s.recording) && !testing);
        Ui.enable(bMark, s.recording);
        if (s.recording) {
            info.setText(String.format(Locale.US, "Recording “%s”: %d lines, %d s%s", s.recLabel, s.recLines, (long) s.recSeconds,
                    s.recFailed ? "  WRITE FAILED (disk full?)" : ""));
            info.setTextColor(s.recFailed ? Ui.BAD : Ui.MUT);
        } else {
            File lf = Captures.latest(ctx);
            info.setText(lf == null ? "Not recording." : "Last capture: " + lf.getName());
            info.setTextColor(Ui.MUT);
        }
        CanService svc = CanService.get();
        StringBuilder lg = new StringBuilder();
        if (svc != null) for (String l : svc.logTail(30)) lg.append(l).append('\n');
        else lg.append("Service not running.");
        log.setText(lg.toString().trim());
    }
}
