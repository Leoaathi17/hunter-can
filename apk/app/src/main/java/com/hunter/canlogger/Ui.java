package com.hunter.canlogger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * Hand-written dark "futuristic Material" look: deep navy surfaces, cyan/teal accent, large touch targets (48 dp+).
 * No Material Components dependency: every widget is styled here in code.
 */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF050A14;
    static final int SURF = 0xFF0E1A2E;
    static final int SURF2 = 0xFF14243E;
    static final int LINE = 0xFF1F3552;
    static final int INK = 0xFFE9F4FF;
    static final int MUT = 0xFF94A9C6;
    static final int ACC = 0xFF22D3EE;
    static final int ACC2 = 0xFF2DD4BF;
    static final int OK = 0xFF34D399;
    static final int WARN = 0xFFFBBF24;
    static final int BAD = 0xFFFF6B6B;
    static final int ON_ACC = 0xFF031318;

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable shape(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** Weighted child (0 width) with a small margin: for rows of equal buttons / fields. */
    static LinearLayout.LayoutParams cell(Context c) { return lp(c, 0, WRAP, 1f, 3, 3, 3, 3); }

    // ---------------------------------------------------------------- text

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView mono(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    static TextView note(Context c, CharSequence s) {
        TextView t = text(c, s, 13, MUT);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    static TextView h2(Context c, CharSequence s) {
        TextView t = text(c, s, 16, INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView h3(Context c, CharSequence s) {
        TextView t = text(c, s, 14, INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 12), 0, dp(c, 4));
        return t;
    }

    // ---------------------------------------------------------------- containers

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(shape(c, SURF, LINE, 16));
        l.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        return l;
    }

    /** Adds a card to a vertical page with the standard gap. */
    static void addCard(LinearLayout page, View card) {
        Context c = page.getContext();
        page.addView(card, lp(c, MATCH, WRAP, 0, 0, 0, 0, 12));
    }

    static View space(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(lp(MATCH, dp(c, heightDp)));
        return v;
    }

    /** Small tile: caption above a large monospace value. Returns the value view; the tile is the value's parent. */
    static TextView tile(Context c, LinearLayout row, String caption, float valueSp) {
        LinearLayout t = vbox(c);
        t.setBackground(shape(c, SURF2, LINE, 14));
        t.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        TextView k = text(c, caption, 12, MUT);
        t.addView(k);
        TextView v = mono(c, "–", valueSp, INK);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.addView(v);
        row.addView(t, cell(c));
        return v;
    }

    // ---------------------------------------------------------------- buttons and fields

    static Button button(Context c, String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        style(b, primary);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    /** Primary = cyan gradient with dark text, otherwise outlined glass. */
    static void style(Button b, boolean primary) {
        Context c = b.getContext();
        GradientDrawable base;
        if (primary) {
            base = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACC, ACC2});
            base.setCornerRadius(dp(c, 12));
            b.setTextColor(ON_ACC);
        } else {
            base = shape(c, 0x2622D3EE, LINE, 12);
            b.setTextColor(INK);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), base, null));
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.42f);
    }

    static EditText input(Context c, String hint, String initial) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(0xFF5E7392);
        e.setTextColor(INK);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setMinHeight(dp(c, 48));
        e.setBackground(shape(c, 0xB3030810, LINE, 10));
        e.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        if (initial != null) e.setText(initial);
        return e;
    }

    static EditText numberInput(Context c, String hint, String initial, boolean decimal, boolean signed) {
        EditText e = input(c, hint, initial);
        int t = InputType.TYPE_CLASS_NUMBER;
        if (decimal) t |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) t |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        e.setInputType(t);
        return e;
    }

    static EditText hexInput(Context c, String hint) {
        EditText e = input(c, hint, null);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    /** Spinner with dark popup and readable text. */
    static Spinner spinner(Context c, String[] items) {
        Spinner s = new Spinner(c, Spinner.MODE_DROPDOWN);
        setItems(s, items);
        s.setBackground(shape(c, 0xB3030810, LINE, 10));
        s.setMinimumHeight(dp(c, 48));
        s.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        return s;
    }

    static void setItems(Spinner s, String[] items) {
        final Context c = s.getContext();
        ArrayAdapter<String> a = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(INK);
                t.setTextSize(15);
                t.setMinHeight(dp(c, 48));
                t.setGravity(Gravity.CENTER_VERTICAL);
                return t;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(position, convertView, parent);
                t.setTextColor(INK);
                t.setTextSize(15);
                t.setBackgroundColor(SURF2);
                t.setMinHeight(dp(c, 48));
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
                return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
    }

    /** Small label above a field, taking an equal share of a row. */
    static LinearLayout labeled(Context c, String label, View field) {
        LinearLayout l = vbox(c);
        l.addView(text(c, label, 12, MUT));
        l.addView(field, lp(MATCH, WRAP));
        l.setLayoutParams(cell(c));
        return l;
    }

    static int blend(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
