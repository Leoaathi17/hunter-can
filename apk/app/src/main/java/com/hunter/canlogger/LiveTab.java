package com.hunter.canlogger;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hunter.canlogger.core.BatteryMonitor;
import com.hunter.canlogger.core.CanEngine;
import com.hunter.canlogger.core.CanId;
import com.hunter.canlogger.core.Dashboard;
import com.hunter.canlogger.core.StatsTable;
import com.hunter.canlogger.core.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Live: onboarding, animated RPM arc, speed and gear, vehicle signals, passive OBD, user gauges, all CAN IDs. */
final class LiveTab extends PageTab {
    private final LinearLayout onboarding;
    private final GaugeView gauge;
    private final TextView spd, spdUnit, spdTag, gear, gearTag;
    private final LinearLayout hunterCard;
    private final TextView wa, wb, b1, ctr, engT, intT, batV, map;
    private final TextView pB0, pNe, pCl, pSs;
    private final BatteryGaugeView batGauge;
    private final LinearLayout batCard;
    private final TextView batCrank, batMinMax;
    private final LinearLayout obdCard;
    private final TextView obdNote;
    private final LinearLayout obdGrid;
    private final LinearLayout userCard;
    private final LinearLayout userGrid;
    private final LinearLayout idList;
    private final List<TextView> idRows = new ArrayList<>();

    LiveTab(MainActivity act) {
        super(act);
        // ---- onboarding
        onboarding = Ui.card(ctx);
        onboarding.addView(Ui.h2(ctx, "Get connected in 3 steps"));
        onboarding.addView(Ui.note(ctx, "1. Plug the CANable into the phone with an OTG cable, and its CAN-H / CAN-L into the vehicle's diagnostic or bus wiring.\n"
                + "2. Switch the ignition on (the engine can stay off).\n"
                + "3. Tap Connect, and allow the USB permission when Android asks."));
        onboarding.addView(Ui.note(ctx, "The app only listens. Pick your vehicle in Settings first if it is not the Hunter 350."));
        Button bConn = Ui.button(ctx, "Connect", true, new View.OnClickListener() {
            @Override public void onClick(View v) { HunterApp.setOnboardingDone(true); onboarding.setVisibility(View.GONE); LiveTab.this.act.connectOrDisconnect(true); }
        });
        Button bOk = Ui.button(ctx, "Got it", false, new View.OnClickListener() {
            @Override public void onClick(View v) { HunterApp.setOnboardingDone(true); onboarding.setVisibility(View.GONE); }
        });
        LinearLayout ob = row(bConn, bOk);
        ob.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        onboarding.addView(ob);
        Ui.addCard(page, onboarding);
        onboarding.setVisibility(HunterApp.onboardingDone() ? View.GONE : View.VISIBLE);

        // ---- hero
        LinearLayout hero = Ui.card(ctx);
        gauge = new GaugeView(ctx);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        hero.addView(gauge, gl);
        LinearLayout duo = Ui.hbox(ctx);
        LinearLayout sp = tileBox("Speed");
        spdTag = (TextView) sp.getChildAt(0).findViewWithTag("tag");
        spd = Ui.mono(ctx, "–", 34, Ui.INK);
        spd.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sp.addView(spd);
        spdUnit = Ui.note(ctx, "km/h");
        sp.addView(spdUnit);
        LinearLayout gr = tileBox("Gear");
        gearTag = (TextView) gr.getChildAt(0).findViewWithTag("tag");
        gear = Ui.mono(ctx, "–", 34, Ui.INK);
        gear.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        gr.addView(gear);
        duo.addView(sp, Ui.lp(ctx, 0, Ui.WRAP, 1.4f, 3, 8, 3, 0));
        duo.addView(gr, Ui.lp(ctx, 0, Ui.WRAP, 1f, 3, 8, 3, 0));
        hero.addView(duo);
        Ui.addCard(page, hero);

        // ---- Hunter signals
        hunterCard = Ui.card(ctx);
        hunterCard.addView(Ui.h2(ctx, "Hunter 350 signals"));
        LinearLayout r1 = Ui.hbox(ctx), r2 = Ui.hbox(ctx), r3 = Ui.hbox(ctx);
        LinearLayout r4 = Ui.hbox(ctx);
        wa = Ui.tile(ctx, r1, "Front wheel km/h (" + Dashboard.TAG_WHEEL_FRONT + ")", 24);
        wb = Ui.tile(ctx, r1, "Rear wheel km/h (" + Dashboard.TAG_WHEEL_REAR + ")", 24);
        b1 = Ui.tile(ctx, r2, "0x080 B1 analog (candidate)", 24);
        ctr = Ui.tile(ctx, r2, "0x140 counter 24-bit (hypothesis)", 24);
        engT = Ui.tile(ctx, r3, "Engine temp °C (" + Dashboard.TAG_ENGINE_TEMP + ")", 24);
        intT = Ui.tile(ctx, r3, "Intake temp °C (" + Dashboard.TAG_INTAKE_TEMP + ")", 24);
        batV = Ui.tile(ctx, r4, "Battery V (" + Dashboard.TAG_BATTERY + ")", 24);
        map = Ui.tile(ctx, r4, "0x120 B0 MAP mbar (candidate)", 24);
        hunterCard.addView(r1);
        hunterCard.addView(r2);
        hunterCard.addView(r3);
        hunterCard.addView(r4);
        LinearLayout pills = Ui.hbox(ctx);
        pB0 = pill(Dashboard.LABEL_B0BIT0);
        pNe = pill("Neutral (likely)");
        pCl = pill("Clutch");
        pSs = pill("Side stand");
        pills.addView(pB0);
        pills.addView(pNe);
        pills.addView(pCl);
        pills.addView(pSs);
        pills.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        hunterCard.addView(pills);
        hunterCard.addView(Ui.note(ctx, "0x080 B0 bit0 was labelled front brake but never turned on with the engine running; it is on whenever the engine is off. "
                + "Neutral (0x080 B7 bit6) also clears when the kill switch is turned off: unresolved. Horn, high/low beam, pass/flash and rear brake "
                + "were not found on CAN (pass/flash only shows as a battery-voltage dip)."));
        Ui.addCard(page, hunterCard);

        // ---- battery
        batCard = Ui.card(ctx);
        batCard.addView(Ui.h2(ctx, "Battery"));
        batGauge = new BatteryGaugeView(ctx);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        bl.gravity = Gravity.CENTER_HORIZONTAL;
        batCard.addView(batGauge, bl);
        batCrank = Ui.mono(ctx, "Cranking min: – (start the engine to measure)", 14, Ui.INK);
        batCard.addView(batCrank);
        batMinMax = Ui.note(ctx, "");
        batCard.addView(batMinMax);
        batCard.addView(Ui.note(ctx, BatteryMonitor.DISCLAIMER + " Engine off (RPM 0 for 2 s): "
                + BatteryMonitor.OFF_GOOD_V + " V and up Good, " + BatteryMonitor.OFF_FAIR_V + " to " + BatteryMonitor.OFF_GOOD_V + " Fair, below "
                + BatteryMonitor.OFF_FAIR_V + " Low, below " + BatteryMonitor.OFF_POOR_V + " Poor. Running: " + BatteryMonitor.RUN_OK_LO + " to "
                + BatteryMonitor.RUN_OK_HI + " Charging OK. Cranking minimum: " + BatteryMonitor.CRANK_GOOD_V + " V and up good, "
                + BatteryMonitor.CRANK_WEAK_V + " to " + BatteryMonitor.CRANK_GOOD_V + " weak, below " + BatteryMonitor.CRANK_WEAK_V + " poor."));
        Ui.addCard(page, batCard);

        // ---- OBD
        obdCard = Ui.card(ctx);
        obdCard.addView(Ui.h2(ctx, "Passive OBD-II readings"));
        obdNote = Ui.note(ctx, "Waiting for OBD responses. The app never sends requests, so values appear only if the car or another scan tool "
                + "is already asking (IDs 0x7E8 to 0x7EF).");
        obdCard.addView(obdNote);
        obdGrid = Ui.vbox(ctx);
        obdCard.addView(obdGrid);
        Ui.addCard(page, obdCard);

        // ---- user signals
        userCard = Ui.card(ctx);
        userCard.addView(Ui.h2(ctx, "My signals"));
        userGrid = Ui.vbox(ctx);
        userCard.addView(userGrid);
        Ui.addCard(page, userCard);

        // ---- all IDs
        LinearLayout ids = Ui.card(ctx);
        ids.addView(Ui.h2(ctx, "All CAN IDs"));
        ids.addView(Ui.note(ctx, "Bytes light up when they change. Tap a row to inspect it in Identify."));
        idList = Ui.vbox(ctx);
        idList.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        ids.addView(idList);
        Ui.addCard(page, ids);
    }

    private LinearLayout tileBox(String caption) {
        LinearLayout t = Ui.vbox(ctx);
        t.setBackground(Ui.shape(ctx, Ui.SURF2, Ui.LINE, 14));
        t.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        LinearLayout head = Ui.hbox(ctx);
        head.addView(Ui.text(ctx, caption, 12, Ui.MUT));
        TextView tag = Ui.text(ctx, "", 11, Ui.WARN);
        tag.setTag("tag");
        tag.setPadding(Ui.dp(ctx, 6), 0, 0, 0);
        head.addView(tag);
        t.addView(head);
        return t;
    }

    private TextView pill(String s) {
        TextView t = Ui.text(ctx, s, 14, Ui.MUT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 8), Ui.dp(ctx, 14), Ui.dp(ctx, 8));
        t.setBackground(Ui.shape(ctx, 0x2494A9C6, 0, 99));
        t.setLayoutParams(Ui.lp(ctx, Ui.WRAP, Ui.WRAP, 0, 0, 0, 8, 0));
        return t;
    }

    private void setPill(TextView t, boolean on) {
        t.setBackground(Ui.shape(ctx, on ? Ui.OK : 0x2494A9C6, 0, 99));
        t.setTextColor(on ? 0xFF03221A : Ui.MUT);
    }

    void showOnboarding() {
        onboarding.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
    }

    /** 10 Hz: dial, speed, gear only (cheap). */
    void fastRefresh(CanEngine.Hero h, boolean hunter) {
        int base = hunter ? 9000 : 8000;
        if (h.rpm != null) {
            int need = Math.max(base, (int) Math.ceil((h.rpm + 500) / 1000.0) * 1000);
            gauge.setMax(need);
        } else {
            gauge.setMax(base);
        }
        gauge.setValue(h.rpm);
        spd.setText(h.speed == null ? "–" : (h.speed < 100 ? String.format(Locale.ROOT, "%.1f", h.speed) : String.format(Locale.ROOT, "%.0f", h.speed)));
        spdUnit.setText(h.speedUnit);
        spdTag.setText(h.speedSrc);
        gear.setText(h.gear == null ? "–" : h.gear);
        gearTag.setText(hunter ? "confirmed" : "");
    }

    @Override
    public void refresh(CanEngine.Snapshot s, long nowNs) {
        fastRefresh(s.hero, s.hunter);
        hunterCard.setVisibility(s.hunter ? View.VISIBLE : View.GONE);
        batCard.setVisibility(s.hunter ? View.VISIBLE : View.GONE);
        if (s.hunter && s.battery != null) {
            BatteryMonitor.View bv = s.battery;
            batGauge.setView(bv);
            batCrank.setText(Double.isNaN(bv.crankMin) ? "Cranking min: – (start the engine to measure)"
                    : String.format(Locale.ROOT, "Cranking min: %.1f V  %s", bv.crankMin, bv.crankLabel));
            batMinMax.setText(Double.isNaN(bv.vMin) ? "" : String.format(Locale.ROOT, "Min %.1f V, max %.1f V since this recording or connection", bv.vMin, bv.vMax));
        }
        obdCard.setVisibility(s.hunter ? View.GONE : View.VISIBLE);
        if (s.hunter) {
            Dashboard d = s.dash;
            wa.setText(d.wheelFront);
            wb.setText(d.wheelRear);
            b1.setText(d.b1);
            ctr.setText(d.counter);
            engT.setText(d.engineTemp);
            intT.setText(d.intakeTemp);
            batV.setText(d.batteryV);
            map.setText(d.map);
            setPill(pB0, d.b0bit0);
            setPill(pNe, d.neutral);
            setPill(pCl, d.clutch);
            setPill(pSs, d.sideStand);
        } else {
            renderObd(s);
        }
        renderUser(s);
        renderRows(s.rows);
    }

    private void renderObd(CanEngine.Snapshot s) {
        obdNote.setVisibility(s.obd.isEmpty() ? View.VISIBLE : View.GONE);
        obdGrid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < s.obd.size(); i++) {
            if (i % 2 == 0) { row = Ui.hbox(ctx); obdGrid.addView(row); }
            CanEngine.ObdValue o = s.obd.get(i);
            LinearLayout t = Ui.vbox(ctx);
            t.setBackground(Ui.shape(ctx, Ui.SURF2, Ui.LINE, 14));
            t.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
            t.addView(Ui.text(ctx, o.def.name + "  (PID " + Text.hex2(o.def.pid) + ")", 12, Ui.MUT));
            TextView v = Ui.mono(ctx, Text.fmtNum(o.value) + " " + o.def.unit, 22, Ui.INK);
            v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            t.addView(v);
            t.setAlpha(o.stale ? 0.45f : 1f);
            row.addView(t, Ui.cell(ctx));
        }
        if (s.obd.size() % 2 == 1 && row != null) row.addView(new View(ctx), Ui.cell(ctx));
        obdGrid.setPadding(0, s.obd.isEmpty() ? 0 : Ui.dp(ctx, 8), 0, 0);
    }

    private void renderUser(CanEngine.Snapshot s) {
        userCard.setVisibility(s.signals.isEmpty() ? View.GONE : View.VISIBLE);
        userGrid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < s.signals.size(); i++) {
            if (i % 2 == 0) { row = Ui.hbox(ctx); userGrid.addView(row); }
            CanEngine.SigValue sv = s.signals.get(i);
            LinearLayout t = Ui.vbox(ctx);
            t.setBackground(Ui.shape(ctx, Ui.SURF2, Ui.LINE, 14));
            t.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
            t.addView(Ui.text(ctx, sv.sig.name + (sv.sig.unverified ? "  [unverified]" : ""), 12, sv.sig.unverified ? Ui.WARN : Ui.MUT));
            TextView v = Ui.mono(ctx, (sv.hasValue ? Text.fmtNum(sv.value) : "–") + " " + sv.sig.unit, 22, Ui.INK);
            v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            t.addView(v);
            LinearLayout bar = Ui.hbox(ctx);
            bar.setBackground(Ui.shape(ctx, 0x2994A9C6, 0, 4));
            View fill = new View(ctx);
            fill.setBackground(Ui.shape(ctx, Ui.ACC, 0, 4));
            float f = (float) sv.fraction();
            bar.addView(fill, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 8), Math.max(0.0001f, f)));
            bar.addView(new View(ctx), new LinearLayout.LayoutParams(0, Ui.dp(ctx, 8), Math.max(0.0001f, 1f - f)));
            t.addView(bar, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 6, 0, 0));
            t.setAlpha(sv.stale ? 0.45f : 1f);
            row.addView(t, Ui.cell(ctx));
        }
        if (s.signals.size() % 2 == 1 && row != null) row.addView(new View(ctx), Ui.cell(ctx));
    }

    private void renderRows(List<StatsTable.Row> rows) {
        int n = Math.min(rows.size(), 200);
        while (idRows.size() < n) {
            TextView t = Ui.mono(ctx, "", 13, Ui.INK);
            t.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8));
            t.setMinHeight(Ui.dp(ctx, 56));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Object k = v.getTag();
                    if (k instanceof Long) act.openIdentify((Long) k);
                }
            });
            idList.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP));
            idRows.add(t);
        }
        while (idRows.size() > n) {
            TextView t = idRows.remove(idRows.size() - 1);
            idList.removeView(t);
        }
        for (int i = 0; i < n; i++) {
            StatsTable.Row r = rows.get(i);
            TextView t = idRows.get(i);
            t.setTag(r.key);
            t.setText(rowText(r), TextView.BufferType.SPANNABLE);
        }
    }

    private static SpannableStringBuilder rowText(StatsTable.Row r) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String head = String.format(Locale.ROOT, "%-11s %4.0f Hz %8d", CanId.str(r.key), r.hz, r.n);
        sb.append(head);
        sb.setSpan(new ForegroundColorSpan(Ui.MUT), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (r.names != null && !r.names.isEmpty()) {
            int s0 = sb.length();
            sb.append("  ").append(r.names);
            sb.setSpan(new ForegroundColorSpan(Ui.OK), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append('\n');
        for (int i = 0; i < r.data.length; i++) {
            int start = sb.length();
            sb.append(Text.hex2(r.data[i] & 0xFF));
            long age = r.ageNs[i];
            boolean hot = age >= 0 && age < StatsTable.HOT_NS;
            if (hot) {
                float a = Math.max(0.18f, 1f - age / (float) StatsTable.HOT_NS);
                sb.setSpan(new BackgroundColorSpan(Color.argb(Math.round(a * 255), 0x22, 0xD3, 0xEE)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFF031318), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new ForegroundColorSpan(age >= 0 ? Ui.INK : 0xFF6B7F9B), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append(' ');
        }
        return sb;
    }
}
