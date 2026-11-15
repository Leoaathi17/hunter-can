package com.hunter.canlogger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.LinearLayout;

/** Bottom navigation: Live, Record, Identify, Health, Settings. Icons are drawn with Canvas paths (no assets). */
final class NavBar extends LinearLayout {
    interface Listener { void onSelected(int index); }

    static final String[] LABELS = {"Live", "Record", "Identify", "Health", "Settings"};

    private final Item[] items = new Item[LABELS.length];
    private Listener listener;
    private int selected = -1;

    NavBar(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setBackgroundColor(0xF2070E1C);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            items[i] = new Item(c, i);
            items[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { select(idx, true); }
            });
            addView(items[i], new LayoutParams(0, Ui.dp(c, 64), 1f));
        }
    }

    void setListener(Listener l) { listener = l; }

    int selected() { return selected; }

    void select(int i, boolean notify) {
        if (i == selected) return;
        selected = i;
        for (int k = 0; k < items.length; k++) items[k].setChosen(k == i);
        if (notify && listener != null) listener.onSelected(i);
    }

    private static final class Item extends View {
        private final int index;
        private boolean chosen;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        Item(Context c, int index) {
            super(c);
            this.index = index;
            float d = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(1.9f * d);
            fill.setStyle(Paint.Style.FILL);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(12f * d);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            setClickable(true);
            setFocusable(true);
            setContentDescription(LABELS[index]);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x3322D3EE), null, Ui.shape(c, 0xFFFFFFFF, 0, 0)));
        }

        void setChosen(boolean c) {
            chosen = c;
            setSelected(c);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            int col = chosen ? Ui.ACC : Ui.MUT;
            stroke.setColor(col);
            fill.setColor(col);
            text.setColor(col);
            float w = getWidth(), cx = w / 2f, cy = 24f * d, r = 11f * d;
            if (chosen) {
                fill.setColor(Ui.ACC);
                cv.drawRect(w * 0.22f, 0, w * 0.78f, 2f * d, fill);
                fill.setColor(col);
            }
            switch (index) {
                case 0: // gauge: open arc and needle
                    rect.set(cx - r, cy - r + 2 * d, cx + r, cy + r + 2 * d);
                    cv.drawArc(rect, 160f, 220f, false, stroke);
                    cv.drawLine(cx, cy + 2 * d, cx + 6 * d, cy - 5 * d, stroke);
                    break;
                case 1: // record: ring and dot
                    cv.drawCircle(cx, cy, r - 1.5f * d, stroke);
                    cv.drawCircle(cx, cy, 4f * d, fill);
                    break;
                case 2: // magnifier
                    cv.drawCircle(cx - 2 * d, cy - 2 * d, 7f * d, stroke);
                    cv.drawLine(cx + 3 * d, cy + 3 * d, cx + 10 * d, cy + 10 * d, stroke);
                    break;
                case 3: // pulse line
                    path.reset();
                    path.moveTo(cx - 12 * d, cy);
                    path.lineTo(cx - 6 * d, cy);
                    path.lineTo(cx - 3 * d, cy - 8 * d);
                    path.lineTo(cx + 2 * d, cy + 9 * d);
                    path.lineTo(cx + 5 * d, cy);
                    path.lineTo(cx + 12 * d, cy);
                    cv.drawPath(path, stroke);
                    break;
                default: // sliders
                    cv.drawLine(cx - 11 * d, cy - 6 * d, cx + 11 * d, cy - 6 * d, stroke);
                    cv.drawLine(cx - 11 * d, cy + 6 * d, cx + 11 * d, cy + 6 * d, stroke);
                    fill.setColor(Ui.BG);
                    cv.drawCircle(cx + 4 * d, cy - 6 * d, 3.2f * d, fill);
                    cv.drawCircle(cx - 4 * d, cy + 6 * d, 3.2f * d, fill);
                    cv.drawCircle(cx + 4 * d, cy - 6 * d, 3.2f * d, stroke);
                    cv.drawCircle(cx - 4 * d, cy + 6 * d, 3.2f * d, stroke);
                    break;
            }
            cv.drawText(LABELS[index], cx, 54f * d, text);
        }
    }
}
