package com.hunter.canlogger;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.hunter.canlogger.core.CanEngine;

/** Base for the five pages: a vertical scroll view with a padded column. */
abstract class PageTab implements Tab {
    protected final MainActivity act;
    protected final Context ctx;
    protected final CanEngine eng = HunterApp.engine();
    protected final ScrollView scroll;
    protected final LinearLayout page;

    PageTab(MainActivity act) {
        this.act = act;
        this.ctx = act;
        scroll = new ScrollView(act);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page = Ui.vbox(act);
        int p = Ui.dp(act, 12);
        page.setPadding(p, p, p, p);
        scroll.addView(page, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
    }

    @Override
    public View view() { return scroll; }

    @Override
    public void onShow() { scroll.scrollTo(0, 0); }

    /** Two equal buttons or fields in a row. */
    protected LinearLayout row(View... children) {
        LinearLayout r = Ui.hbox(ctx);
        for (View v : children) r.addView(v, Ui.cell(ctx));
        return r;
    }
}
