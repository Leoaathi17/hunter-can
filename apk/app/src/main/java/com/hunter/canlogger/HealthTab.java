package com.hunter.canlogger;

import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hunter.canlogger.core.CanEngine;
import com.hunter.canlogger.core.CanId;
import com.hunter.canlogger.core.HealthCounters;
import com.hunter.canlogger.core.HealthStatus;
import com.hunter.canlogger.core.StatsTable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Health: OK / WARN / CHECK with reasons, counters, bus load, per-ID timeline, unknown IDs. */
final class HealthTab extends PageTab {
    private final TextView level, summary, reasons, loadTxt;
    private final View loadFill, loadRest;
    private final LinearLayout grid;
    private final TimelineView timeline;
    private final TextView gapTxt, unknown;

    HealthTab(MainActivity act) {
        super(act);
        LinearLayout ro = Ui.card(ctx);
        ro.setBackground(Ui.shape(ctx, 0x8C082820, 0x6634D399, 16));
        TextView t = Ui.text(ctx, "Read-only. This app only listens. It never sends CAN data and never clears or writes ECU fault codes (DTCs), "
                + "because that would require transmitting to the vehicle.", 13, Ui.INK);
        ro.addView(t);
        Ui.addCard(page, ro);

        LinearLayout st = Ui.card(ctx);
        LinearLayout head = Ui.hbox(ctx);
        level = Ui.mono(ctx, "IDLE", 34, Ui.MUT);
        level.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        summary = Ui.note(ctx, "Not connected.");
        summary.setPadding(Ui.dp(ctx, 14), 0, 0, 0);
        head.addView(level);
        head.addView(summary);
        st.addView(head);
        reasons = Ui.text(ctx, "", 13, Ui.INK);
        reasons.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        st.addView(reasons);
        Ui.addCard(page, st);

        LinearLayout bus = Ui.card(ctx);
        bus.addView(Ui.h2(ctx, "Bus"));
        loadTxt = Ui.note(ctx, "Bus load estimate: about 0%");
        bus.addView(loadTxt);
        LinearLayout bar = Ui.hbox(ctx);
        bar.setBackground(Ui.shape(ctx, 0x2994A9C6, 0, 4));
        loadFill = new View(ctx);
        loadFill.setBackground(Ui.shape(ctx, Ui.ACC, 0, 4));
        loadRest = new View(ctx);
        bar.addView(loadFill, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 8), 0.0001f));
        bar.addView(loadRest, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 8), 1f));
        bus.addView(bar, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 6, 0, 8));
        grid = Ui.vbox(ctx);
        bus.addView(grid);
        Button reset = Ui.button(ctx, "Reset counters", false, new View.OnClickListener() {
            @Override public void onClick(View v) { eng.resetCounters(); HealthTab.this.act.toast("Counters reset."); }
        });
        bus.addView(reset, Ui.lp(ctx, Ui.WRAP, Ui.WRAP, 0, 0, 8, 0, 0));
        Ui.addCard(page, bus);

        LinearLayout tl = Ui.card(ctx);
        tl.addView(Ui.h2(ctx, "Message timeline (last 30 s)"));
        tl.addView(Ui.note(ctx, "One row per ID, one cell per second. Cyan means frames arrived. Red means a periodic ID went silent."));
        timeline = new TimelineView(ctx);
        tl.addView(timeline, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        gapTxt = Ui.mono(ctx, "No IDs yet.", 12, Ui.MUT);
        gapTxt.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        tl.addView(gapTxt);
        Ui.addCard(page, tl);

        LinearLayout un = Ui.card(ctx);
        un.addView(Ui.h2(ctx, "Unknown IDs"));
        unknown = Ui.mono(ctx, "none", 12, Ui.MUT);
        un.addView(unknown);
        Ui.addCard(page, un);
    }

    private static String tileText(String k, long v) { return k + "\n" + v; }

    @Override
    public void refresh(CanEngine.Snapshot s, long nowNs) {
        HealthStatus st = s.status;
        int col = st.lvl == HealthStatus.OK ? Ui.OK : st.lvl == HealthStatus.WARN ? Ui.WARN : st.lvl == HealthStatus.CHECK ? Ui.BAD : Ui.MUT;
        level.setText(st.label());
        level.setTextColor(col);
        summary.setText(st.summary());
        StringBuilder r = new StringBuilder();
        for (String x : st.reasons) r.append("• ").append(x).append('\n');
        reasons.setText(r.toString().trim());

        int pct = (int) Math.round(s.load * 100);
        loadTxt.setText("Bus load estimate: about " + pct + "%");
        float f = Math.max(0.0001f, Math.min(1f, (float) s.load));
        loadFill.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(ctx, 8), f));
        loadRest.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(ctx, 8), Math.max(0.0001f, 1f - f)));

        HealthCounters h = s.health;
        grid.removeAllViews();
        String[][] tiles = s.hunter
                ? new String[][]{{"Error beeps", "" + h.bel}, {"Malformed", "" + h.bad}, {"Resynced", "" + h.resync}, {"Overflows", "" + h.overflow},
                {"Message gaps", "" + h.gaps}, {"Frames", "" + s.totalFrames}, {"0x140 jumps", "" + h.ctrJump}, {"Short frames", "" + h.dlcOdd}}
                : new String[][]{{"Error beeps", "" + h.bel}, {"Malformed", "" + h.bad}, {"Resynced", "" + h.resync}, {"Overflows", "" + h.overflow},
                {"Message gaps", "" + h.gaps}, {"Frames", "" + s.totalFrames}, {"Filtered by ID format", "" + h.filtered}, {"OBD codes read by others", "" + h.dtcResp}};
        LinearLayout row = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % 2 == 0) { row = Ui.hbox(ctx); grid.addView(row); }
            Ui.tile(ctx, row, tiles[i][0], 22).setText(tiles[i][1]);
        }

        List<StatsTable.Timeline> tl = eng.timeline(nowNs, 24);
        timeline.setRows(tl);
        StringBuilder g = new StringBuilder();
        for (StatsTable.Timeline x : tl) {
            if (x.gaps > 0) g.append(String.format(Locale.ROOT, "%s: %d gap(s), longest %.1f s\n", CanId.str(x.key), x.gaps, x.gapMaxMs / 1000.0));
        }
        gapTxt.setText(g.length() > 0 ? g.toString().trim() : (tl.isEmpty() ? "No IDs yet." : "No gaps seen."));

        if (!h.unknownTracked) {
            unknown.setText("Not tracked for this profile.");
        } else if (h.unknown.isEmpty()) {
            unknown.setText("none");
        } else {
            StringBuilder u = new StringBuilder();
            for (Map.Entry<Long, Integer> e : h.unknown.entrySet()) u.append(CanId.str(e.getKey())).append('(').append(e.getValue()).append(") ");
            unknown.setText(u.toString().trim());
        }
    }
}
