package com.hunter.canlogger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.hunter.canlogger.core.BatteryMonitor;

/**
 * Battery voltage arc, same geometry and style as the RPM gauge (270 degrees, opening at the bottom, 10 to 16 V).
 * A needle marks the value; the state is always written as text under the number, never colour alone.
 * Voltage-state indicator only, not a battery health test.
 */
final class BatteryGaugeView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint state = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private double volts = Double.NaN;
    private int color = Ui.ACC;
    private String centerText = "–";
    private String stateText = "No voltage";

    BatteryGaugeView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0x2E94A9C6);
        value.setStyle(Paint.Style.STROKE);
        value.setStrokeCap(Paint.Cap.ROUND);
        needle.setStyle(Paint.Style.STROKE);
        needle.setStrokeCap(Paint.Cap.ROUND);
        needle.setColor(Ui.INK);
        tick.setStyle(Paint.Style.STROKE);
        tick.setColor(0x8094A9C6);
        tick.setStrokeWidth(1.2f * d);
        label.setColor(Ui.MUT);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.MONOSPACE);
        big.setColor(Ui.INK);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        state.setTextAlign(Paint.Align.CENTER);
        state.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setContentDescription("Battery voltage gauge, no data");
    }

    void setView(BatteryMonitor.View v) {
        volts = v.volts;
        centerText = Double.isNaN(v.volts) ? "–" : String.format(java.util.Locale.ROOT, "%.1f", v.volts);
        stateText = v.label;
        switch (v.level) {
            case OK: color = Ui.OK; break;
            case WARN: color = Ui.WARN; break;
            case BAD: color = Ui.BAD; break;
            default: color = Ui.ACC;
        }
        setContentDescription("Battery voltage " + (Double.isNaN(v.volts) ? "no data" : centerText + " volts, " + stateText));
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int cap = Ui.dp(getContext(), 260);
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED || w <= 0) w = cap;
        w = Math.min(w, cap);
        setMeasuredDimension(w, Math.round(w * 0.86f));
    }

    private static double frac(double v) {
        return Math.max(0, Math.min(1, (v - BatteryMonitor.GAUGE_MIN_V) / (BatteryMonitor.GAUGE_MAX_V - BatteryMonitor.GAUGE_MIN_V)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float s = w / 220f;
        float cx = w / 2f, cy = 110f * s;
        float sw = 12f * s;
        track.setStrokeWidth(sw);
        value.setStrokeWidth(sw);
        needle.setStrokeWidth(3f * s);
        float r = 80f * s;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        cv.drawArc(oval, 135f, 270f, false, track);
        if (!Double.isNaN(volts)) {
            float pct = (float) frac(volts);
            value.setColor(color);
            if (pct > 0.004f) cv.drawArc(oval, 135f, 270f * pct, false, value);
            double a = Math.toRadians(135.0 + 270.0 * pct);
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            cv.drawLine(cx + 64f * s * c, cy + 64f * s * sn, cx + 98f * s * c, cy + 98f * s * sn, needle);
        }
        label.setTextSize(10f * s * 1.35f);
        for (int v = 10; v <= 16; v++) {
            double a = Math.toRadians(135.0 + 270.0 * frac(v));
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            cv.drawLine(cx + 90f * s * c, cy + 90f * s * sn, cx + 96f * s * c, cy + 96f * s * sn, tick);
            cv.drawText(Integer.toString(v), cx + 106f * s * c, cy + 106f * s * sn + 3f * s * 1.35f, label);
        }
        big.setTextSize(40f * s * 1.0f);
        cv.drawText(centerText, cx, cy + 4f * s, big);
        label.setTextSize(13f * s * 1.2f);
        cv.drawText("V", cx, cy + 24f * s, label);
        state.setColor(color);
        state.setTextSize(15f * s * 1.1f);
        cv.drawText(stateText, cx, cy + 44f * s, state);
    }
}
