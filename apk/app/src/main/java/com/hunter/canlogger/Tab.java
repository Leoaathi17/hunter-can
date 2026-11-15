package com.hunter.canlogger;

import android.view.View;

import com.hunter.canlogger.core.CanEngine;

/** One bottom-navigation page. refresh() is called about 3 times a second while the tab is visible. */
interface Tab {
    View view();

    /** Called when the tab becomes visible. */
    void onShow();

    void refresh(CanEngine.Snapshot s, long nowNs);
}
