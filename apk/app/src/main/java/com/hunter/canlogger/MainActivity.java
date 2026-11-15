package com.hunter.canlogger;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.hunter.canlogger.core.CanEngine;
import com.hunter.canlogger.core.HealthStatus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** Single activity: top bar with status chip, five pages, bottom navigation. All UI is built in code. */
public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 11;
    private static final int REQ_JSON = 12;
    private static final long FAST_MS = 100, FULL_MS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CanEngine eng = HunterApp.engine();

    private Tab[] tabs;
    private LiveTab live;
    private IdentifyTab identify;
    private FrameLayout content;
    private NavBar nav;
    private TextView chip, chipDot;
    private Button topBtn;
    private ObjectAnimator pulse;
    private int current = -1;
    private long lastFull;
    private boolean visible;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            long now = SystemClock.elapsedRealtimeNanos();
            live.fastRefresh(eng.hero(now), eng.profile().isHunter());
            if (now - lastFull >= FULL_MS * 1_000_000L) { lastFull = now; refreshFull(now); }
            handler.postDelayed(this, FAST_MS);
        }
    };

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        buildUi();
        requestNotificationPermission();
        handleIntent(getIntent());
        nav.select(0, true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) startServiceSafely();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Ui.SURF);
        int p = Ui.dp(this, 12);
        top.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        TextView brand = Ui.text(this, "HUNTER CAN", 16, Ui.ACC);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.12f);
        top.addView(brand, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));

        LinearLayout chipBox = new LinearLayout(this);
        chipBox.setOrientation(LinearLayout.HORIZONTAL);
        chipBox.setGravity(Gravity.CENTER_VERTICAL);
        chipBox.setBackground(Ui.shape(this, Ui.SURF2, Ui.LINE, 99));
        chipBox.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        chipDot = new TextView(this);
        chipDot.setBackground(Ui.shape(this, Ui.MUT, 0, 99));
        chipBox.addView(chipDot, new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)));
        chip = Ui.text(this, "Idle", 13, Ui.INK);
        chip.setPadding(Ui.dp(this, 8), 0, 0, 0);
        chipBox.addView(chip);
        top.addView(chipBox);

        topBtn = Ui.button(this, "Connect", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                CanService s = CanService.get();
                boolean on = s != null && s.isLinkUp() && !s.isUserDisconnected();
                connectOrDisconnect(!on);
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 44));
        bp.leftMargin = Ui.dp(this, 8);
        top.addView(topBtn, bp);
        root.addView(top, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // pages
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        live = new LiveTab(this);
        identify = new IdentifyTab(this);
        tabs = new Tab[]{live, new RecordTab(this), identify, new HealthTab(this), new SettingsTab(this)};
        for (Tab t : tabs) {
            View v = t.view();
            v.setVisibility(View.GONE);
            content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        nav = new NavBar(this);
        nav.setListener(new NavBar.Listener() {
            @Override public void onSelected(int index) { showTab(index); }
        });
        root.addView(nav, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        setContentView(root);
    }

    private void showTab(int i) {
        if (i < 0 || i >= tabs.length) return;
        if (current >= 0) tabs[current].view().setVisibility(View.GONE);
        current = i;
        tabs[i].view().setVisibility(View.VISIBLE);
        tabs[i].onShow();
        long now = SystemClock.elapsedRealtimeNanos();
        lastFull = now;
        refreshFull(now);
    }

    private void refreshFull(long now) {
        CanEngine.Snapshot s = eng.snapshot(now);
        updateTop(s);
        if (current >= 0) tabs[current].refresh(s, now);
    }

    private void updateTop(CanEngine.Snapshot s) {
        CanService.LinkState st = CanService.state();
        String txt;
        int col;
        switch (st) {
            case CONNECTED:
                txt = s.status.label();
                col = s.status.lvl == HealthStatus.OK ? Ui.OK : s.status.lvl == HealthStatus.WARN ? Ui.WARN : s.status.lvl == HealthStatus.CHECK ? Ui.BAD : Ui.ACC;
                if (s.status.lvl == HealthStatus.IDLE) { txt = "Connected"; col = Ui.ACC; }
                break;
            case WAITING: txt = "Waiting for adapter"; col = Ui.MUT; break;
            case PERMISSION: txt = "Allow USB"; col = Ui.WARN; break;
            case CONNECTING: txt = "Connecting"; col = Ui.ACC; break;
            case RECONNECTING: txt = "Reconnecting"; col = Ui.WARN; break;
            case USER_OFF: txt = "Disconnected"; col = Ui.MUT; break;
            default: txt = "Idle"; col = Ui.MUT; break;
        }
        chip.setText(txt);
        chipDot.setBackground(Ui.shape(this, col, 0, 99));
        boolean active = st == CanService.LinkState.CONNECTED || st == CanService.LinkState.CONNECTING || st == CanService.LinkState.RECONNECTING;
        boolean animate = active && ValueAnimator.areAnimatorsEnabled();
        if (animate && pulse == null) {
            pulse = ObjectAnimator.ofFloat(chipDot, View.ALPHA, 1f, 0.3f);
            pulse.setDuration(900);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.start();
        } else if (!animate && pulse != null) {
            pulse.cancel();
            pulse = null;
            chipDot.setAlpha(1f);
        }
        CanService svc = CanService.get();
        boolean on = svc != null && svc.isLinkUp() && !svc.isUserDisconnected();
        topBtn.setText(on ? "Disconnect" : "Connect");
        Ui.style(topBtn, !on);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        long now = SystemClock.elapsedRealtimeNanos();
        lastFull = now;
        refreshFull(now);
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, FAST_MS);
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacks(ticker);
        if (pulse != null) { pulse.cancel(); pulse = null; }
        super.onPause();
    }

    // ------------------------------------------------------------------ calls from the pages

    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    void connectOrDisconnect(boolean connect) {
        CanService s = CanService.get();
        if (connect) {
            if (s == null) startServiceSafely(); else s.connectNow();
        } else if (s != null) {
            s.userDisconnect();
        }
    }

    private void startServiceSafely() {
        try {
            CanService.start(this);
        } catch (RuntimeException e) { // e.g. started from the background on Android 12+
            toast("Could not start the service: " + e.getMessage());
        }
    }

    void openIdentify(long key) {
        identify.selectId(key);
        nav.select(2, true);
    }

    void showSetupGuide() {
        nav.select(0, true);
        live.showOnboarding();
    }

    void shareFile(File f, String mime) {
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share " + f.getName()));
        } catch (RuntimeException e) {
            toast("Could not share: " + e.getMessage());
        }
    }

    void pickJsonFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_JSON);
        } catch (ActivityNotFoundException e) {
            toast("No file picker available. Paste the JSON into the box instead.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_JSON || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(data.getData());
            if (in == null) { toast("Could not open that file."); return; }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 1_000_000) { toast("File too large for a decoder list."); return; }
                }
            } finally {
                in.close();
            }
            identify.importJson(bo.toString("UTF-8"));
        } catch (IOException | RuntimeException e) {
            toast("Could not read the file: " + e.getMessage());
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }
}
