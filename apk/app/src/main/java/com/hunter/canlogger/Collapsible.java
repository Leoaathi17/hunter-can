package com.hunter.canlogger;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Card with a tappable header that shows or hides its body ("details" element of the web page). */
final class Collapsible extends LinearLayout {
    private final LinearLayout body;
    private final TextView arrow;
    private boolean open;

    Collapsible(Context c, String title, boolean startOpen) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(Ui.shape(c, Ui.SURF, Ui.LINE, 16));
        setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 14), Ui.dp(c, 4));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(Ui.dp(c, 52));
        head.setClickable(true);
        head.setFocusable(true);
        TextView t = Ui.h2(c, title);
        head.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        arrow = Ui.text(c, "+", 22, Ui.ACC);
        arrow.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        head.addView(arrow);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setOpen(!open); }
        });
        addView(head, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        body = new LinearLayout(c);
        body.setOrientation(VERTICAL);
        body.setPadding(0, 0, 0, Ui.dp(c, 10));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setOpen(startOpen);
    }

    LinearLayout body() { return body; }

    boolean isOpen() { return open; }

    void setOpen(boolean o) {
        open = o;
        body.setVisibility(o ? View.VISIBLE : View.GONE);
        arrow.setText(o ? "-" : "+");
    }
}
