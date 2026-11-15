package com.hunter.canlogger;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.hunter.canlogger.core.CanEngine;
import com.hunter.canlogger.core.CanId;
import com.hunter.canlogger.core.Compare;
import com.hunter.canlogger.core.StatsTable;
import com.hunter.canlogger.core.Text;
import com.hunter.canlogger.core.UserSignal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Identify: Find-signal wizard, control tests, ranked candidates, per-byte entropy / changed bits,
 * counter and checksum hints (guesses), user-defined signals with JSON export / import.
 */
final class IdentifyTab extends PageTab {
    static final String[] BIKE_CTL = {"Front Brake", "Rear Brake", "Clutch", "Side Stand", "Kill Switch", "Starter Button", "Horn", "High Beam",
            "Low Beam", "Pass / Flash", "Left Indicator", "Right Indicator", "Hazard", "Throttle", "Gear", "Ignition"};
    static final String[] CAR_CTL = {"Brake Pedal", "Clutch Pedal", "Accelerator", "Handbrake", "Reverse Gear", "Horn", "High Beam", "Low Beam",
            "Left Indicator", "Right Indicator", "Hazard", "Wipers", "Driver Door", "Seat Belt", "Window Switch", "Ignition"};
    private static final String[] SECS = {"3", "5", "8", "12"};

    // find signal
    private final EditText wizName;
    private final Spinner wizBase, wizAct;
    private final Button wizGo;
    private final TextView wizInfo;
    // control tests
    private final Collapsible wizCard, ctlCard, byteCard, decCard;
    private final TextView ctlNote, ctlInfo, ctlRes;
    private final LinearLayout ctlGrid;
    private final Button bTest;
    private final List<Button> ctlButtons = new ArrayList<>();
    private String selCtl;
    private String ctlProfile = "";
    // candidates
    private final LinearLayout candBox;
    private final EditText sigName;
    private final TextView sigList;
    private List<Compare.Candidate> shownCands;
    private Compare.Candidate pick;
    private final List<Button> candButtons = new ArrayList<>();
    // byte view
    private final Spinner idSel;
    private final TextView byteTable, hintsTxt;
    private List<Long> idKeys = new ArrayList<>();
    private Long selKey;
    private boolean suppress;
    // decoder
    private final EditText dId, dStart, dLen, dScale, dOff, dName, dUnit, dMin, dMax, decJson;
    private final Spinner dEnd, dSigned, starterSel;
    private final LinearLayout decList;
    private String decSig = "";
    private final Button bStarter;

    IdentifyTab(final MainActivity act) {
        super(act);
        // ================= Find signal
        wizCard = new Collapsible(ctx, "Find signal", true);
        LinearLayout w = wizCard.body();
        w.addView(Ui.note(ctx, "Name what you will change, record a quiet baseline, then do the action and hold it. Bytes that differ are shown as "
                + "candidates, not identifications. Only do this when parked and safe."));
        wizName = Ui.input(ctx, "e.g. Brake pedal, A/C on", null);
        w.addView(Ui.text(ctx, "What are you changing?", 12, Ui.MUT));
        w.addView(wizName, Ui.lp(Ui.MATCH, Ui.WRAP));
        wizBase = Ui.spinner(ctx, SECS);
        wizBase.setSelection(1);
        wizAct = Ui.spinner(ctx, SECS);
        wizAct.setSelection(1);
        LinearLayout secs = Ui.hbox(ctx);
        secs.addView(Ui.labeled(ctx, "Baseline (s)", wizBase));
        secs.addView(Ui.labeled(ctx, "Action (s)", wizAct));
        secs.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        w.addView(secs);
        wizGo = Ui.button(ctx, "Start find signal", true, new View.OnClickListener() {
            @Override public void onClick(View v) { startWizard(); }
        });
        w.addView(wizGo, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        wizInfo = Ui.note(ctx, "Connect first.");
        wizInfo.setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 6), 0, 0);
        w.addView(wizInfo);
        Ui.addCard(page, wizCard);

        // ================= Control tests
        ctlCard = new Collapsible(ctx, "Control tests", false);
        LinearLayout c = ctlCard.body();
        ctlNote = Ui.note(ctx, "Baseline 4 s, then event 4 s. Pick a control, then Run test.");
        c.addView(ctlNote);
        ctlGrid = Ui.vbox(ctx);
        ctlGrid.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        c.addView(ctlGrid);
        bTest = Ui.button(ctx, "Run test", true, new View.OnClickListener() {
            @Override public void onClick(View v) { startControlTest(); }
        });
        c.addView(bTest, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        ctlInfo = Ui.note(ctx, "Pick a control, then Run test.");
        ctlInfo.setPadding(Ui.dp(ctx, 3), Ui.dp(ctx, 6), 0, 0);
        c.addView(ctlInfo);
        ctlRes = Ui.mono(ctx, "", 12, Ui.INK);
        c.addView(ctlRes);
        Ui.addCard(page, ctlCard);

        // ================= Candidates
        LinearLayout cd = Ui.card(ctx);
        cd.addView(Ui.h2(ctx, "Candidates"));
        candBox = Ui.vbox(ctx);
        candBox.addView(Ui.note(ctx, "Results from Find signal or Control tests appear here. Tap one to name it or turn it into a gauge."));
        cd.addView(candBox);
        sigName = Ui.input(ctx, "Name, e.g. Horn switch (bit 3)", null);
        cd.addView(sigName, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        Button bSig = Ui.button(ctx, "Save name", false, new View.OnClickListener() {
            @Override public void onClick(View v) { saveName(); }
        });
        Button bGauge = Ui.button(ctx, "Make gauge", false, new View.OnClickListener() {
            @Override public void onClick(View v) { makeGauge(); }
        });
        cd.addView(row(bSig, bGauge));
        sigList = Ui.mono(ctx, "No names saved yet.", 12, Ui.MUT);
        sigList.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        cd.addView(sigList);
        Ui.addCard(page, cd);

        // ================= Byte view
        byteCard = new Collapsible(ctx, "Byte view for one ID", false);
        LinearLayout b = byteCard.body();
        idSel = Ui.spinner(ctx, new String[]{"(no IDs yet)"});
        idSel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!suppress && pos >= 0 && pos < idKeys.size()) selKey = idKeys.get(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        b.addView(Ui.text(ctx, "ID", 12, Ui.MUT));
        b.addView(idSel, Ui.lp(Ui.MATCH, Ui.WRAP));
        byteTable = Ui.mono(ctx, "", 12, Ui.INK);
        byteTable.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        b.addView(byteTable);
        b.addView(Ui.h3(ctx, "Counter and checksum hints"));
        hintsTxt = Ui.text(ctx, "Select an ID.", 13, Ui.INK);
        b.addView(hintsTxt);
        b.addView(Ui.note(ctx, "Hints come from the last 64 frames of that ID. They are guesses from patterns, never facts. "
                + "Confirm on the vehicle before relying on them."));
        Ui.addCard(page, byteCard);

        // ================= Decoder
        decCard = new Collapsible(ctx, "Signal decoder (my gauges)", false);
        LinearLayout d = decCard.body();
        d.addView(Ui.note(ctx, "Define where a value lives in a frame. It shows as a live gauge on Live and is saved for this vehicle profile on this phone."));
        dId = Ui.hexInput(ctx, "7E8 or 18DAF110");
        dStart = Ui.numberInput(ctx, "0", "0", false, false);
        dLen = Ui.numberInput(ctx, "1", "1", false, false);
        LinearLayout r1 = Ui.hbox(ctx);
        r1.addView(Ui.labeled(ctx, "ID (hex)", dId));
        r1.addView(Ui.labeled(ctx, "Start byte", dStart));
        r1.addView(Ui.labeled(ctx, "Length", dLen));
        d.addView(r1);
        dEnd = Ui.spinner(ctx, new String[]{"Big (Motorola)", "Little (Intel)"});
        dSigned = Ui.spinner(ctx, new String[]{"No", "Yes"});
        dScale = Ui.numberInput(ctx, "1", "1", true, true);
        dOff = Ui.numberInput(ctx, "0", "0", true, true);
        LinearLayout r2 = Ui.hbox(ctx);
        r2.addView(Ui.labeled(ctx, "Endianness", dEnd));
        r2.addView(Ui.labeled(ctx, "Signed", dSigned));
        d.addView(r2);
        LinearLayout r3 = Ui.hbox(ctx);
        r3.addView(Ui.labeled(ctx, "Scale", dScale));
        r3.addView(Ui.labeled(ctx, "Offset", dOff));
        d.addView(r3);
        dName = Ui.input(ctx, "Engine speed", null);
        dUnit = Ui.input(ctx, "rpm, km/h, C", null);
        LinearLayout r4 = Ui.hbox(ctx);
        r4.addView(Ui.labeled(ctx, "Name", dName));
        r4.addView(Ui.labeled(ctx, "Unit", dUnit));
        d.addView(r4);
        dMin = Ui.numberInput(ctx, "auto", null, true, true);
        dMax = Ui.numberInput(ctx, "auto", null, true, true);
        LinearLayout r5 = Ui.hbox(ctx);
        r5.addView(Ui.labeled(ctx, "Gauge min", dMin));
        r5.addView(Ui.labeled(ctx, "Gauge max", dMax));
        d.addView(r5);
        d.addView(Ui.note(ctx, "Units rpm, km/h, mph and gear also drive the main dials on Live."));
        d.addView(Ui.button(ctx, "Add decoder", true, new View.OnClickListener() {
            @Override public void onClick(View v) { addFromForm(); }
        }), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        decList = Ui.vbox(ctx);
        decList.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        d.addView(decList);
        d.addView(Ui.h3(ctx, "Generic starters (unverified)"));
        String[] sl = new String[UserSignal.STARTERS.length];
        for (int i = 0; i < sl.length; i++) sl[i] = UserSignal.STARTERS[i].label + " (unverified)";
        starterSel = Ui.spinner(ctx, sl);
        d.addView(starterSel, Ui.lp(Ui.MATCH, Ui.WRAP));
        bStarter = Ui.button(ctx, "Add starter", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                UserSignal s = UserSignal.sanitize(UserSignal.STARTERS[starterSel.getSelectedItemPosition()].def);
                if (s != null) { eng.addSignal(s); act.toast("Added: " + s.name); }
            }
        });
        d.addView(bStarter, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0, 0));
        d.addView(Ui.h3(ctx, "Export and import"));
        decJson = Ui.input(ctx, "Decoder JSON", null);
        decJson.setSingleLine(false);
        decJson.setMinLines(4);
        decJson.setGravity(android.view.Gravity.TOP);
        decJson.setTypeface(android.graphics.Typeface.MONOSPACE);
        decJson.setTextSize(12);
        decJson.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        d.addView(decJson, Ui.lp(Ui.MATCH, Ui.WRAP));
        Button bExp = Ui.button(ctx, "Export JSON", false, new View.OnClickListener() {
            @Override public void onClick(View v) { exportJson(); }
        });
        Button bImp = Ui.button(ctx, "Import from box", false, new View.OnClickListener() {
            @Override public void onClick(View v) { importJson(decJson.getText().toString()); }
        });
        Button bFile = Ui.button(ctx, "Load file", false, new View.OnClickListener() {
            @Override public void onClick(View v) { act.pickJsonFile(); }
        });
        d.addView(row(bExp, bImp));
        d.addView(bFile, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 0, 0, 0));
        Ui.addCard(page, decCard);
    }

    // ---------------------------------------------------------------- actions

    private boolean busy(CanEngine.Snapshot s) {
        return s.testPhase == CanEngine.TestPhase.BASELINE || s.testPhase == CanEngine.TestPhase.PREPARE || s.testPhase == CanEngine.TestPhase.EVENT;
    }

    private void startWizard() {
        String name = Text.clean(wizName.getText().toString());
        if (name.isEmpty()) { wizInfo.setText("Type what you are changing first."); return; }
        CanService s = CanService.get();
        int base = Integer.parseInt(SECS[wizBase.getSelectedItemPosition()]);
        int actSec = Integer.parseInt(SECS[wizAct.getSelectedItemPosition()]);
        if (s == null || !s.runTest(name, name, base, actSec)) act.toast("Connect first (and wait for any running test).");
    }

    private void startControlTest() {
        if (selCtl == null) return;
        CanService s = CanService.get();
        if (s == null || !s.runTest(selCtl, selCtl, 4, 4)) act.toast("Connect first (and wait for any running test).");
    }

    private void saveName() {
        if (pick == null) { act.toast("Tap a candidate line first, then type a name."); return; }
        String nm = Text.clean(sigName.getText().toString());
        if (nm.isEmpty()) { act.toast("Type a name first."); return; }
        if (eng.nameByte(pick.byteKey(), nm)) act.toast("Saved: " + pick.byteKey() + " = " + nm);
    }

    private void makeGauge() {
        if (pick == null) { act.toast("Tap a candidate line first."); return; }
        dId.setText(CanId.str(pick.key).substring(2));
        dStart.setText(Integer.toString(pick.byteIdx));
        dLen.setText("1");
        dName.setText(Text.clean(sigName.getText().toString()));
        decCard.setOpen(true);
        act.toast("Decoder form filled. Set scale and unit, then Add decoder.");
    }

    private void addFromForm() {
        UserSignal d = UserSignal.fromForm(dId.getText().toString(), dStart.getText().toString(), dLen.getText().toString(),
                dEnd.getSelectedItemPosition() == 1, dSigned.getSelectedItemPosition() == 1, dScale.getText().toString(), dOff.getText().toString(),
                dUnit.getText().toString(), dName.getText().toString(), dMin.getText().toString(), dMax.getText().toString(), false);
        if (d == null) {
            act.toast("Decoder not added. Check ID (hex), start 0-7, length 1-4, start+length at most 8, numeric scale and offset.");
            return;
        }
        UserSignal added = eng.addSignal(d);
        act.toast("Decoder added: " + (added == null ? d.name : added.name));
    }

    private void exportJson() {
        String text = eng.exportSignals();
        decJson.setText(text);
        try {
            File f = new File(Captures.dir(ctx), "hunter_decoders_" + eng.profile().id + ".json");
            FileOutputStream fo = new FileOutputStream(f, false);
            try { fo.write(text.getBytes("UTF-8")); } finally { fo.close(); }
            act.shareFile(f, "application/json");
        } catch (IOException e) {
            act.toast("Export failed: " + e.getMessage());
        }
    }

    void importJson(String text) {
        decJson.setText(text);
        int n = eng.importSignals(text);
        act.toast(n == 0 ? "Imported 0 decoders (invalid JSON or nothing valid)." : "Imported " + n + " decoder(s).");
    }

    /** Called from the Live tab: show this ID in the byte view. */
    void selectId(long key) {
        selKey = key;
        byteCard.setOpen(true);
        idKeys = new ArrayList<>();   // force a rebuild with the new selection on the next refresh
    }

    // ---------------------------------------------------------------- rendering

    private void buildControls(boolean hunter) {
        ctlGrid.removeAllViews();
        ctlButtons.clear();
        selCtl = null;
        String[] names = hunter ? BIKE_CTL : CAR_CTL;
        LinearLayout row = null;
        for (int i = 0; i < names.length; i++) {
            if (i % 2 == 0) { row = Ui.hbox(ctx); ctlGrid.addView(row); }
            final String nm = names[i];
            final Button b = Ui.button(ctx, nm, false, null);
            b.setTextSize(13);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CanEngine.Snapshot s = eng.snapshot(android.os.SystemClock.elapsedRealtimeNanos());
                    if (busy(s)) return;
                    selCtl = nm;
                    for (Button x : ctlButtons) Ui.style(x, x == b);
                    ctlInfo.setText("Selected: " + nm);
                }
            });
            ctlButtons.add(b);
            row.addView(b, Ui.cell(ctx));
        }
        ctlNote.setText((hunter ? "Hunter controls. " : "Car controls. ") + "Baseline 4 s, then event 4 s. Only operate controls when parked and safe.");
    }

    private String phaseText(CanEngine.Snapshot s) {
        String n = s.testControl == null ? "" : s.testControl;
        switch (s.testPhase) {
            case BASELINE: return String.format(Locale.US, "BASELINE: leave %s in its NORMAL state. %.0f s", n, Math.ceil(s.testSecondsLeft));
            case PREPARE: return "Get ready: operate " + n + " and HOLD it in that state...";
            case EVENT: return String.format(Locale.US, "ACTION: hold %s operated. %.0f s", n, Math.ceil(s.testSecondsLeft));
            case DONE: return "Done. Open Candidates below to name a result.";
            default: return null;
        }
    }

    @Override
    public void refresh(CanEngine.Snapshot s, long nowNs) {
        if (!s.profileId.equals(ctlProfile)) { ctlProfile = s.profileId; buildControls(s.hunter); }
        boolean busy = busy(s);
        Ui.enable(bTest, s.linked && selCtl != null && !busy);
        Ui.enable(wizGo, s.linked && !busy);
        String ph = phaseText(s);
        if (ph != null) { wizInfo.setText(ph); ctlInfo.setText(ph); }
        else if (!s.linked) wizInfo.setText("Connect first.");
        else if (wizInfo.getText().toString().equals("Connect first.")) wizInfo.setText("Ready.");
        ctlRes.setText(s.testResult == null ? "" : s.testResult);

        if (s.candidates != shownCands) showCandidates(s);
        renderNames(s);
        renderIdSelector(s);
        renderBytes();
        renderDecoders();
    }

    private void showCandidates(CanEngine.Snapshot s) {
        shownCands = s.candidates;
        pick = null;
        candBox.removeAllViews();
        candButtons.clear();
        if (shownCands == null || shownCands.isEmpty()) {
            candBox.addView(Ui.note(ctx, s.testPhase == CanEngine.TestPhase.DONE ? "No byte changed. Try again."
                    : "Results from Find signal or Control tests appear here. Tap one to name it or turn it into a gauge."));
            return;
        }
        int n = Math.min(8, shownCands.size());
        for (int i = 0; i < n; i++) {
            final Compare.Candidate cand = shownCands.get(i);
            String k = cand.byteKey();
            String nm = eng.byteName(k);
            String known = eng.knownLabel(k);
            final Button b = Ui.button(ctx, cand.text() + (nm != null ? "  [" + nm + "]" : (known != null ? "  (known: " + known + ")" : "")), false, null);
            b.setTypeface(android.graphics.Typeface.MONOSPACE);
            b.setTextSize(12);
            b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pick = cand;
                    String ex = eng.byteName(cand.byteKey());
                    sigName.setText(ex != null ? ex : (selCtl != null ? selCtl : Text.clean(wizName.getText().toString())));
                    for (Button x : candButtons) Ui.style(x, x == b);
                }
            });
            candButtons.add(b);
            candBox.addView(b, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 3, 0, 3));
        }
    }

    private void renderNames(CanEngine.Snapshot s) {
        Map<String, String> names = eng.byteNames();
        if (names.isEmpty()) { sigList.setText("No names saved yet."); return; }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : names.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue());
            String now = currentValue(s, e.getKey());
            if (now != null) sb.append("   now: ").append(now);
            sb.append('\n');
        }
        sigList.setText(sb.toString().trim());
    }

    private static String currentValue(CanEngine.Snapshot s, String byteKey) {
        int sp = byteKey.lastIndexOf(" B");
        if (sp < 0) return null;
        try {
            String idTxt = byteKey.substring(2, sp);
            long id = Long.parseLong(idTxt, 16);
            long key = CanId.key(id, idTxt.length() > 3);
            int idx = Integer.parseInt(byteKey.substring(sp + 2));
            for (StatsTable.Row r : s.rows) {
                if (r.key == key && idx < r.data.length) return "0x" + Text.hex2(r.data[idx] & 0xFF) + " (" + (r.data[idx] & 0xFF) + ")";
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private void renderIdSelector(CanEngine.Snapshot s) {
        List<Long> keys = new ArrayList<>();
        for (StatsTable.Row r : s.rows) keys.add(r.key);
        if (keys.equals(idKeys)) return;
        idKeys = keys;
        if (keys.isEmpty()) { selKey = null; }
        else if (selKey == null || !keys.contains(selKey)) selKey = keys.get(0);
        String[] labels = new String[Math.max(1, keys.size())];
        if (keys.isEmpty()) labels[0] = "(no IDs yet)";
        for (int i = 0; i < keys.size(); i++) labels[i] = CanId.str(keys.get(i));
        suppress = true;
        Ui.setItems(idSel, labels);
        if (selKey != null) idSel.setSelection(keys.indexOf(selKey));
        suppress = false;
    }

    private void renderBytes() {
        if (!byteCard.isOpen()) return;
        StatsTable st = eng.stats();
        if (selKey == null) { byteTable.setText("No IDs yet."); hintsTxt.setText("Select an ID."); return; }
        byte[] last = st.last(selKey);
        if (last == null) { byteTable.setText("No IDs yet."); return; }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("Byte  Now  Entropy         Distinct  Bits (7..0, lit = changed)\n");
        sb.setSpan(new ForegroundColorSpan(Ui.MUT), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (int i = 0; i < last.length; i++) {
            StatsTable.ByteStat b = st.byteStat(selKey, i);
            if (b == null) continue;
            int v = last[i] & 0xFF;
            String nm = eng.byteName(CanId.byteKey(selKey, i));
            int bars = (int) Math.round(b.entropy);
            StringBuilder bar = new StringBuilder();
            for (int k = 0; k < 8; k++) bar.append(k < bars ? '█' : '░');
            sb.append(String.format(Locale.ROOT, "B%-3d  %02X   %s %4.2f  %5d     ", i, v, bar, b.entropy, b.distinct));
            for (int k = 7; k >= 0; k--) {
                int s0 = sb.length();
                sb.append((v >> k & 1) != 0 ? '1' : '0');
                if ((b.togMask >> k & 1) != 0) {
                    sb.setSpan(new BackgroundColorSpan(Color.argb(0x55, 0x22, 0xD3, 0xEE)), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Ui.INK), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    sb.setSpan(new ForegroundColorSpan(Ui.MUT), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (nm != null) {
                int s1 = sb.length();
                sb.append("  ").append(nm);
                sb.setSpan(new ForegroundColorSpan(Ui.OK), s1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append('\n');
        }
        byteTable.setText(sb, TextView.BufferType.SPANNABLE);
        List<StatsTable.Hint> hs = st.hints(selKey);
        if (hs.isEmpty()) {
            hintsTxt.setText(st.ringSize(selKey) < 12 ? "Need at least 12 frames of this ID." : "No counter or checksum pattern found.");
        } else {
            StringBuilder h = new StringBuilder();
            for (StatsTable.Hint x : hs) h.append("• ").append(x.text).append('\n');
            hintsTxt.setText(h.toString().trim());
        }
    }

    private void renderDecoders() {
        List<UserSignal> list = eng.signalList();
        StringBuilder sig = new StringBuilder(eng.profile().id);
        for (UserSignal u : list) sig.append(',').append(u.uid);
        if (sig.toString().equals(decSig)) return;
        decSig = sig.toString();
        decList.removeAllViews();
        if (list.isEmpty()) {
            decList.addView(Ui.note(ctx, "No decoders yet for " + eng.profile().name + "."));
            return;
        }
        for (final UserSignal u : list) {
            LinearLayout r = Ui.hbox(ctx);
            TextView t = Ui.mono(ctx, u.summary(), 12, u.unverified ? Ui.WARN : Ui.INK);
            r.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            r.addView(Ui.button(ctx, "Delete", false, new View.OnClickListener() {
                @Override public void onClick(View v) { eng.removeSignal(u.uid); }
            }));
            decList.addView(r, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 4, 0, 4));
        }
    }
}
