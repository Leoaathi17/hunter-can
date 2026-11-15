package com.hunter.canlogger;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Animated RPM arc (270 degrees, opening at the bottom) with 1000-rpm ticks, glow, and the value in the middle.
 * The needle value eases toward the target on every frame; with "remove animations" on, it jumps instead.
 */
final class GaugeView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float shown;
    private float target;
    private int max = 9000;
    private String centerText = "–";
    private String unitText = "rpm";

    GaugeView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0x2E94A9C6);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        value.setStyle(Paint.Style.STROKE);
        value.setStrokeCap(Paint.Cap.ROUND);
        tick.setStyle(Paint.Style.STROKE);
        tick.setColor(0x8094A9C6);
        tick.setStrokeWidth(1.2f * d);
        label.setColor(Ui.MUT);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.MONOSPACE);
        big.setColor(Ui.INK);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        unit.setColor(Ui.MUT);
        unit.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Engine speed gauge");
    }

    void setMax(int m) {
        if (m != max && m > 0) { max = m; invalidate(); }
    }

    int max() { return max; }

    void setUnit(String u) { unitText = u; }

    /** rpm == null shows a dash and lets the arc fall back to zero. */
    void setValue(Double rpm) {
        String t = rpm == null ? "–" : Integer.toString((int) Math.round(rpm));
        target = rpm == null ? 0f : rpm.floatValue();
        if (!t.equals(centerText)) {
            centerText = t;
            setContentDescription("Engine speed " + (rpm == null ? "no data" : t + " rpm"));
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int cap = Ui.dp(getContext(), 340);
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED || w <= 0) w = cap;
        w = Math.min(w, cap);
        setMeasuredDimension(w, Math.round(w * 0.86f));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float s = w / 220f;                 // 1 unit = 1/220 of the width, same geometry as the web page (viewBox 200)
        float cx = w / 2f, cy = 110f * s;
        float sw = 12f * s;
        track.setStrokeWidth(sw);
        value.setStrokeWidth(sw);
        glow.setStrokeWidth(sw + 8f * s);
        float r = 80f * s;
        oval.set(cx - r, cy - r, cx + r, cy + r);

        boolean animate = ValueAnimator.areAnimatorsEnabled();
        float diff = target - shown;
        if (!animate || Math.abs(diff) < 2f) shown = target; else shown += diff * 0.22f;

        float pct = Math.max(0f, Math.min(1f, shown / max));
        cv.drawArc(oval, 135f, 270f, false, track);
        if (pct > 0.004f) {
            boolean hot = pct > 0.88f;
            int col = hot ? Ui.BAD : Ui.ACC;
            glow.setColor((col & 0x00FFFFFF) | 0x33000000);
            value.setColor(col);
            cv.drawArc(oval, 135f, 270f * pct, false, glow);
            cv.drawArc(oval, 135f, 270f * pct, false, value);
        }

        int n = Math.max(1, Math.round(max / 1000f));
        label.setTextSize(10f * s * 1.35f);
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(135.0 + 270.0 * i / n);
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            cv.drawLine(cx + 90f * s * c, cy + 90f * s * sn, cx + 96f * s * c, cy + 96f * s * sn, tick);
            if (n <= 12 || i % 2 == 0) cv.drawText(Integer.toString(i), cx + 105f * s * c, cy + 105f * s * sn + 3f * s * 1.35f, label);
        }

        big.setTextSize(Math.min(56f * s * 1.0f, 48f * s * 1.3f));
        cv.drawText(centerText, cx, cy + 10f * s, big);
        unit.setTextSize(13f * s * 1.2f);
        cv.drawText(unitText, cx, cy + 34f * s, unit);

        if (animate && Math.abs(target - shown) >= 0.5f) postInvalidateOnAnimation();
    }
}
