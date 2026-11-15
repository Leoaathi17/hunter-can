package com.hunter.canlogger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import com.hunter.canlogger.core.CanId;
import com.hunter.canlogger.core.StatsTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-ID message timeline, last 30 s, one cell per second. Cyan = frames arrived, red = a periodic ID went silent,
 * dark = silent but not periodic. Colour is never the only signal: the gap list under the view repeats it in words.
 */
final class TimelineView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<StatsTable.Timeline> rows = new ArrayList<>();

    TimelineView(Context c) {
        super(c);
        t.setColor(Ui.MUT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(Ui.dp(c, 10) * 1.1f);
        setContentDescription("Per-ID message timeline, last 30 seconds");
    }

    void setRows(List<StatsTable.Timeline> r) {
        rows = r;
        requestLayout();
        invalidate();
    }

    private int rowH() { return Ui.dp(getContext(), 16); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.max(Ui.dp(getContext(), 40), rows.size() * rowH() + Ui.dp(getContext(), 6)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float lw = Ui.dp(c, 92), rh = rowH();
        float cw = (getWidth() - lw) / 30f;
        cv.drawColor(0x99030810);
        for (int r = 0; r < rows.size(); r++) {
            StatsTable.Timeline tl = rows.get(r);
            float y = r * rh + Ui.dp(c, 3);
            cv.drawText(CanId.str(tl.key), Ui.dp(c, 4), y + rh * 0.72f, t);
            for (int i = 0; i < 30; i++) {
                int n = tl.counts[i];
                if (n < 0) continue;
                p.setColor(n > 0 ? Ui.ACC : (tl.periodic ? Ui.BAD : 0xFF2A3A52));
                cv.drawRect(lw + i * cw + 1, y, lw + (i + 1) * cw - 1, y + rh - Ui.dp(c, 3), p);
            }
        }
    }
}
