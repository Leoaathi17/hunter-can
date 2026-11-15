package com.hunter.canlogger;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.hunter.canlogger.core.Bitrates;
import com.hunter.canlogger.core.CanEngine;
import com.hunter.canlogger.core.IdFormat;
import com.hunter.canlogger.core.VehicleProfile;

import java.util.ArrayList;
import java.util.List;

/** Settings: vehicle profile, bitrate, ID filter, auto-detect, connection, battery, setup guide. */
final class SettingsTab extends PageTab {
    private final List<Button> profButtons = new ArrayList<>();
    private final TextView profNote, detectInfo, linkInfo, bitrateNote;
    private final Spinner rateSel, fmtSel;
    private final Button bDetect, bConnect;
    private boolean suppress;
    private String shownProfile = "";
    private int shownRate = -1;
    private IdFormat shownFmt;

    SettingsTab(final MainActivity act) {
        super(act);
        // ---- vehicle
        LinearLayout v = Ui.card(ctx);
        v.addView(Ui.h2(ctx, "Vehicle profile"));
        LinearLayout pr = Ui.hbox(ctx);
        for (final VehicleProfile p : VehicleProfile.ALL) {
            final Button b = Ui.button(ctx, p.name, false, null);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    if (!eng.setProfile(p.id)) act.toast("Stop recording before changing the profile.");
                }
            });
            profButtons.add(b);
            pr.addView(b, Ui.cell(ctx));
        }
        v.addView(pr);
        profNote = Ui.note(ctx, "");
        profNote.setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 6), 0, 0);
        v.addView(profNote);
        Ui.addCard(page, v);

        // ---- bus
        LinearLayout bus = Ui.card(ctx);
        bus.addView(Ui.h2(ctx, "CAN bus"));
        rateSel = Ui.spinner(ctx, Bitrates.labels());
        rateSel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (suppress || pos < 0 || pos >= Bitrates.KBPS.length) return;
                int kbps = Bitrates.KBPS[pos];
                if (kbps == eng.rateKbps()) return;
                if (!eng.setRate(kbps)) {
                    act.toast("Stop recording before changing the bitrate.");
                    shownRate = -1;
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        bus.addView(Ui.labeled(ctx, "Bitrate (saved per profile)", rateSel), Ui.lp(Ui.MATCH, Ui.WRAP));
        fmtSel = Ui.spinner(ctx, new String[]{IdFormat.BOTH.label, IdFormat.STD11.label, IdFormat.EXT29.label});
        fmtSel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (suppress || pos < 0 || pos >= IdFormat.values().length) return;
                IdFormat f = IdFormat.values()[pos];
                if (f != eng.idFormat()) eng.setIdFormat(f);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        bus.addView(Ui.labeled(ctx, "ID display filter", fmtSel), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        bitrateNote = Ui.note(ctx, "The filter only changes what is shown and recorded. Both 11-bit (t) and 29-bit (T) frames are always parsed. "
                + "A bitrate change is sent to the adapter right away if connected.");
        bitrateNote.setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 6), 0, 0);
        bus.addView(bitrateNote);
        bDetect = Ui.button(ctx, "Auto-detect bitrate", false, new View.OnClickListener() {
            @Override public void onClick(View x) {
                CanService s = CanService.get();
                if (s == null || !s.autoDetect()) act.toast("Connect first. Detection cannot run while recording or testing.");
            }
        });
        bus.addView(bDetect, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        detectInfo = Ui.note(ctx, "Tries 500k, 250k, 125k, then 1M in listen-only mode. A rate is rejected when error beeps or malformed frames appear. "
                + "Nothing is ever transmitted on the bus.");
        detectInfo.setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 6), 0, 0);
        bus.addView(detectInfo);
        Ui.addCard(page, bus);

        // ---- connection
        LinearLayout con = Ui.card(ctx);
        con.addView(Ui.h2(ctx, "Connection"));
        linkInfo = Ui.note(ctx, "");
        con.addView(linkInfo);
        bConnect = Ui.button(ctx, "Connect", true, new View.OnClickListener() {
            @Override public void onClick(View x) {
                CanService s = CanService.get();
                boolean on = s != null && s.isLinkUp() && !s.isUserDisconnected();
                act.connectOrDisconnect(!on);
            }
        });
        con.addView(bConnect, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        con.addView(Ui.button(ctx, "Quit service", false, new View.OnClickListener() {
            @Override public void onClick(View x) {
                CanService s = CanService.get();
                if (s != null) s.stopAll();
                act.toast("Service stopped.");
            }
        }), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        con.addView(Ui.button(ctx, "Battery settings", false, new View.OnClickListener() {
            @Override public void onClick(View x) { openBattery(); }
        }), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        con.addView(Ui.note(ctx, "Some phones stop background apps during long drives. Exempt this app from battery optimisation if the link drops with the screen off."));
        Ui.addCard(page, con);

        // ---- help and data
        LinearLayout h = Ui.card(ctx);
        h.addView(Ui.h2(ctx, "Help and data"));
        h.addView(Ui.button(ctx, "Show setup guide", false, new View.OnClickListener() {
            @Override public void onClick(View x) { act.showSetupGuide(); }
        }), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 0, 0, 8));
        h.addView(Ui.button(ctx, "Forget signal names", false, new View.OnClickListener() {
            @Override public void onClick(View x) { eng.clearNames(); act.toast("Saved byte names cleared."); }
        }));
        Ui.addCard(page, h);

        // ---- safety
        LinearLayout sf = Ui.card(ctx);
        sf.addView(Ui.h2(ctx, "Read-only by design"));
        sf.addView(Ui.note(ctx, "This app only listens. The adapter command set is limited to close, bitrate, listen-only mode, open and version. "
                + "It never sends CAN frames, never requests OBD data, and cannot read or clear fault codes. Not for safety-critical use."));
        Ui.addCard(page, sf);
    }

    private void openBattery() {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(android.content.Context.POWER_SERVICE);
            Intent i;
            if (Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
                i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
            }
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            act.toast("Open Android Settings > Apps > this app > Battery.");
        }
    }

    @Override
    public void refresh(CanEngine.Snapshot s, long nowNs) {
        if (!s.profileId.equals(shownProfile)) {
            shownProfile = s.profileId;
            for (int i = 0; i < profButtons.size(); i++) Ui.style(profButtons.get(i), VehicleProfile.ALL[i].id.equals(shownProfile));
            profNote.setText(VehicleProfile.byId(shownProfile).note);
        }
        if (s.rateKbps != shownRate || s.fmt != shownFmt) {
            shownRate = s.rateKbps;
            shownFmt = s.fmt;
            suppress = true;
            int rc = Bitrates.code(s.rateKbps);
            if (rc >= 0) rateSel.setSelection(rc);
            fmtSel.setSelection(s.fmt.ordinal());
            suppress = false;
        }
        CanService svc = CanService.get();
        boolean detecting = svc != null && svc.isDetecting();
        Ui.enable(bDetect, s.linked && !detecting && !s.recording);
        Ui.enable(rateSel, !s.recording && !detecting);
        String di = svc == null ? null : svc.detectInfo();
        if (detecting && di != null) detectInfo.setText(di);
        else if (!detecting && di != null && !di.isEmpty()) detectInfo.setText(di);
        linkInfo.setText("State: " + CanService.state() + (svc != null ? "\n" + svc.statusText() : "\nService not running."));
        boolean on = svc != null && svc.isLinkUp() && !svc.isUserDisconnected();
        bConnect.setText(on ? "Disconnect" : "Connect");
        Ui.style(bConnect, !on);
    }
}
