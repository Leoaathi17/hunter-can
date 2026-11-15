package com.hunter.canlogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.hunter.canlogger.core.BitrateDetector;
import com.hunter.canlogger.core.CanEngine;
import com.hunter.canlogger.core.SlcanSetup;
import com.hunter.canlogger.core.Text;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service (type connectedDevice) that owns the USB link and the recording file.
 * The CanEngine itself lives in HunterApp; the UI reaches this service through CanService.get().
 * Nothing here depends on the Activity being alive, so logging continues with the screen off.
 *
 * Read-only by construction: the only bytes written to the adapter are C, S0-S8, M1, O, V (CommandGuard).
 */
public class CanService extends Service {
    static final String ACTION_CONNECT = "com.hunter.canlogger.action.CONNECT";
    static final String ACTION_STOP = "com.hunter.canlogger.action.STOP";
    private static final String ACTION_USB_PERMISSION = "com.hunter.canlogger.action.USB_PERMISSION";
    private static final String CHANNEL_ID = "can_logger";
    private static final int NOTIF_ID = 1;
    private static final String TAG = "HunterCan";
    private static final long WATCHDOG_NS = 3_000_000_000L;
    private static final long CMD_MS = SlcanSetup.DEFAULT_CMD_MS;

    /** Link state shown in the status chip. */
    enum LinkState { IDLE, WAITING, PERMISSION, CONNECTING, CONNECTED, RECONNECTING, USER_OFF }

    private static volatile CanService instance;

    static CanService get() { return instance; }

    /** IDLE when the service is not running. */
    static LinkState state() {
        CanService s = instance;
        return s == null ? LinkState.IDLE : s.linkState;
    }

    /** Starts the foreground service (call from a visible Activity: Android 12+ forbids it from the background). */
    static void start(Context c) {
        Intent i = new Intent(c, CanService.class).setAction(ACTION_CONNECT);
        c.startForegroundService(i);
    }

    private final CanEngine engine = HunterApp.engine();
    private UsbManager usb;
    private PowerManager.WakeLock wakeLock;
    private ScheduledExecutorService ticker;
    private Thread linkThread;

    private volatile boolean running;
    private volatile boolean userDisc;
    private volatile boolean abortLink;
    private volatile boolean linkUp;
    private volatile boolean noData;
    private volatile boolean detecting;
    private volatile String detectInfo = "";
    private volatile LinkState linkState = LinkState.WAITING;
    private volatile String status = "starting";
    private volatile CdcAcmLink activeLink;
    private volatile long appliedConfig = -1;
    private boolean everUp;           // link thread only
    private boolean pendingReconnect; // link thread only

    private volatile boolean permPending;
    private volatile long permBackoffUntilMs;
    private final Object sleepLock = new Object();

    private volatile File currentFile;
    private volatile boolean testStartedRecording;

    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat logFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        running = true;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hunter:can");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }

        ticker = Executors.newSingleThreadScheduledExecutor();
        final long[] n = {0};
        ticker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    n[0]++;
                    long now = SystemClock.elapsedRealtimeNanos();
                    if (engine.tick(now)) onTestFinished();
                    if (n[0] % 30 == 0) engine.flushRecording();   // every 3 s
                    if (n[0] % 10 == 0) updateNotification();      // every 1 s
                } catch (RuntimeException e) {
                    Log.e(TAG, "ticker", e);
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);

        log("Service started. Listen-only; sends only C, S0-S8, M1, O, V.");
        linkThread = new Thread(new Runnable() {
            @Override public void run() { linkLoop(); }
        }, "can-link");
        linkThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        try {
            startAsForeground();
        } catch (RuntimeException e) { // e.g. ForegroundServiceStartNotAllowedException after a restart from the background
            Log.e(TAG, "startForeground failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(action)) {
            userDisc = false;
            permBackoffUntilMs = 0;
        }
        wakeLinkThread();
        // Not sticky: Android 12+ would refuse to start a connectedDevice foreground service from the background
        // after a process kill anyway. Plug the cable again or open the app to resume.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        wakeLinkThread();
        try { unregisterReceiver(usbReceiver); } catch (RuntimeException ignored) { }
        if (ticker != null) ticker.shutdownNow();
        try {
            if (linkThread != null) linkThread.join(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        engine.setLinked(false, SystemClock.elapsedRealtimeNanos());
        engine.stopRecording();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    private void shutdown() {
        running = false;
        wakeLinkThread();
        engine.stopRecording();
        currentFile = null;
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ------------------------------------------------------------ foreground notification

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "CAN logging", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while the CANable is being logged");
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 14: the type must be passed and must match the manifest (connectedDevice).
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, CanService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        CanEngine.Snapshot s = engine.snapshot(SystemClock.elapsedRealtimeNanos());
        StringBuilder sb = new StringBuilder(status);
        sb.append("  |  ").append(s.profileName).append(' ').append(s.rateKbps).append('k');
        sb.append("  |  ").append(String.format(Locale.US, "%,d", s.totalFrames)).append(" frames");
        if (s.linked) sb.append("  |  ").append(s.status.label());
        if (s.recording) {
            long sec = (long) s.recSeconds;
            sb.append(String.format(Locale.US, "  |  REC %d:%02d", sec / 60, sec % 60));
        }
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_can), "Stop", stopPi).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_can)
                .setContentTitle("Hunter CAN logger")
                .setContentText(sb.toString())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(contentPi)
                .addAction(stopAction)
                .build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (RuntimeException e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    // ------------------------------------------------------------ USB broadcasts

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (a == null) return;
            if (ACTION_USB_PERMISSION.equals(a)) {
                permPending = false;
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    log("USB permission granted.");
                } else {
                    log("USB permission denied. Tap Connect to ask again.");
                    permBackoffUntilMs = SystemClock.elapsedRealtime() + 60_000;
                }
                wakeLinkThread();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                if (CdcAcmLink.matches(deviceExtra(intent))) {
                    abortLink = true;
                    log("CANable detached.");
                    wakeLinkThread();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (CdcAcmLink.matches(deviceExtra(intent))) {
                    log("CANable attached.");
                    wakeLinkThread();
                }
            }
        }
    };

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private UsbDevice findDevice() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (CdcAcmLink.matches(d)) return d;
        }
        return null;
    }

    private void requestPermission(UsbDevice dev) {
        if (permPending || SystemClock.elapsedRealtime() < permBackoffUntilMs) return;
        permPending = true;
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        // Android 12+: the system fills in extras, so the PendingIntent must be MUTABLE (and explicit on targetSdk 34).
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, flags);
        try {
            usb.requestPermission(dev, pi);
            log("Asking for USB permission...");
        } catch (RuntimeException e) {
            permPending = false;
            log("USB permission request failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ link thread

    private void wakeLinkThread() {
        synchronized (sleepLock) { sleepLock.notifyAll(); }
    }

    private void idle(long ms) {
        synchronized (sleepLock) {
            try { sleepLock.wait(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void setStatus(LinkState s, String text) {
        linkState = s;
        status = text;
    }

    private void linkLoop() {
        while (running) {
            if (userDisc) {
                setStatus(LinkState.USER_OFF, "disconnected (by you)");
                idle(500);
                continue;
            }
            UsbDevice dev = findDevice();
            if (dev == null) {
                if (everUp) setStatus(LinkState.RECONNECTING, "reconnecting...");
                else setStatus(LinkState.WAITING, "waiting for CANable");
                idle(1000);
                continue;
            }
            if (!usb.hasPermission(dev)) {
                setStatus(LinkState.PERMISSION, "USB permission needed");
                requestPermission(dev);
                idle(1000);
                continue;
            }
            CdcAcmLink l = null;
            abortLink = false;
            try {
                setStatus(LinkState.CONNECTING, "connecting...");
                l = CdcAcmLink.open(usb, dev);
                activeLink = l;
                setup(l);
                linkUp = true;
                noData = false;
                engine.setLinked(true, SystemClock.elapsedRealtimeNanos());
                setStatus(LinkState.CONNECTED, "connected");
                if (pendingReconnect) {
                    engine.linkEvent("RECONNECT", SystemClock.elapsedRealtimeNanos());
                    pendingReconnect = false;
                    log("Reconnected.");
                }
                everUp = true;
                readLoop(l, dev);
                if (userDisc || !running) {
                    try { l.sendCommand("C"); } catch (IOException ignored) { }
                }
            } catch (IOException | RuntimeException e) {
                log("Link error: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } finally {
                activeLink = null;
                if (l != null) l.close();
            }
            if (linkUp) {
                linkUp = false;
                engine.setLinked(false, SystemClock.elapsedRealtimeNanos());
                if (running && !userDisc) {
                    engine.linkEvent("DISCONNECT", SystemClock.elapsedRealtimeNanos());
                    pendingReconnect = true;
                    log("Link lost. Will keep trying to reconnect.");
                    setStatus(LinkState.RECONNECTING, "reconnecting...");
                }
            }
            if (running) idle(1000);
        }
        linkUp = false;
        engine.setLinked(false, SystemClock.elapsedRealtimeNanos());
    }

    /** Sink that sends through CdcAcmLink.sendCommand (CommandGuard inside) and sleeps with Thread.sleep. */
    private static final class LinkSink implements SlcanSetup.Sink {
        private final CdcAcmLink link;
        LinkSink(CdcAcmLink link) { this.link = link; }
        @Override public void send(String cmd) throws IOException { link.sendCommand(cmd); }
        @Override public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
    }

    /** C, S<n>, M1, O for the profile's bitrate. */
    private void setup(CdcAcmLink l) throws IOException, InterruptedException {
        int kbps = engine.rateKbps();
        appliedConfig = engine.configVersion();
        SlcanSetup.apply(new LinkSink(l), kbps, CMD_MS);
        log("Configured: " + kbps + " kbps, listen-only, open.");
    }

    private void readLoop(CdcAcmLink l, UsbDevice dev) throws IOException, InterruptedException {
        byte[] buf = new byte[64];
        long lastFrameAt = SystemClock.elapsedRealtimeNanos();
        long framesSeen = engine.totalFrames();
        boolean kicked = false;
        int fastErrors = 0;
        while (running && !userDisc && !abortLink) {
            long t0 = SystemClock.elapsedRealtime();
            int r = l.read(buf, 100);
            long now = SystemClock.elapsedRealtimeNanos();
            if (r > 0) {
                fastErrors = 0;
                engine.onBytes(buf, 0, r, now);
                long total = engine.totalFrames();
                if (total != framesSeen) {
                    framesSeen = total;
                    lastFrameAt = now;
                    kicked = false;
                    if (noData) { noData = false; setStatus(LinkState.CONNECTED, "connected"); }
                }
            } else if (r < 0) {
                // A genuine timeout takes ~100 ms; failing much faster means the device is gone or the pipe is broken.
                if (SystemClock.elapsedRealtime() - t0 < 50) fastErrors++; else fastErrors = 0;
                if (fastErrors >= 5) throw new IOException("USB read failing");
                if (!usb.getDeviceList().containsKey(dev.getDeviceName())) throw new IOException("device removed");
            }
            if (detecting) { // auto-detect drives the adapter itself; do not interfere
                lastFrameAt = now;
                framesSeen = engine.totalFrames();
                continue;
            }
            // Profile or bitrate changed in the UI: re-apply C, S<n>, M1, O.
            if (engine.configVersion() != appliedConfig) {
                setup(l);
                lastFrameAt = SystemClock.elapsedRealtimeNanos();
                framesSeen = engine.totalFrames();
                kicked = false;
                continue;
            }
            // 3 s watchdog: no frames. Show it, and once per silent episode re-send the setup commands
            // (covers a CANable that lost its configuration). On a parked bike with ignition off this is normal.
            if (now - lastFrameAt > WATCHDOG_NS) {
                if (!noData) { noData = true; setStatus(LinkState.CONNECTED, "connected, no data (ignition on?)"); log("No data for 3 s."); }
                if (!kicked) {
                    kicked = true;
                    setup(l);
                }
            }
        }
    }

    // ------------------------------------------------------------ bitrate auto-detect

    boolean isDetecting() { return detecting; }

    String detectInfo() { return detectInfo; }

    /**
     * Auto-detect: tries 500k, 250k, 125k, 1M (listen-only), accepts the first rate with clean traffic.
     * Runs on its own thread; the read loop keeps feeding the engine. Returns false if it cannot start.
     */
    synchronized boolean autoDetect() {
        if (!linkUp || detecting || engine.isRecording() || engine.testActive()) return false;
        detecting = true;
        detectInfo = "Starting...";
        Thread t = new Thread(new Runnable() {
            @Override public void run() { runDetect(); }
        }, "can-detect");
        t.start();
        return true;
    }

    private void runDetect() {
        final int previous = engine.rateKbps();
        try {
            BitrateDetector.Env env = new BitrateDetector.Env() {
                @Override public void send(String cmd) throws IOException {
                    CdcAcmLink l = activeLink;
                    if (l == null) throw new IOException("link lost");
                    l.sendCommand(cmd);
                }
                @Override public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
                @Override public long frames() { return engine.detectCounters()[0]; }
                @Override public long beeps() { return engine.detectCounters()[1]; }
                @Override public long malformed() { return engine.detectCounters()[2]; }
                @Override public void progress(String text) { detectInfo = text; }
            };
            int found = BitrateDetector.run(env, previous, new BitrateDetector.Timing());
            if (found > 0) {
                engine.adoptDetectedRate(found);
                appliedConfig = engine.configVersion();
                log("Auto-detect: " + found + " kbps.");
            } else {
                log("Auto-detect found no valid bitrate; restored " + previous + " kbps.");
            }
        } catch (IOException e) {
            detectInfo = "Auto-detect stopped: " + e.getMessage();
            log(detectInfo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            detectInfo = "Auto-detect error: " + e.getMessage();
            log(detectInfo);
        } finally {
            detecting = false;
        }
    }

    // ------------------------------------------------------------ API used by the UI

    boolean isLinkUp() { return linkUp; }
    boolean isUserDisconnected() { return userDisc; }
    String statusText() { return status; }
    File currentCapture() { return currentFile; }

    /** Starts an append-only capture file. Returns null on success, else an error text. */
    synchronized String startRecording(String label) {
        if (engine.isRecording()) return null;
        Date now = new Date();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        String clean = Text.cleanOr(label, "capture");
        File f = new File(Captures.dir(this), "hunter_" + Captures.safeName(clean) + "_" + stamp.format(now) + ".txt");
        try {
            FileOutputStream fos = new FileOutputStream(f, true); // append-only: never rewrites earlier data
            if (!engine.startRecording(fos, clean, iso.format(now), SystemClock.elapsedRealtimeNanos())) {
                fos.close();
                return null;
            }
            currentFile = f;
            log("Recording to " + f.getName());
            return null;
        } catch (IOException | RuntimeException e) {
            log("Cannot start recording: " + e.getMessage());
            return String.valueOf(e.getMessage());
        }
    }

    synchronized void stopRecording() {
        if (!engine.isRecording()) return;
        long lines = engine.stopRecording();
        File f = currentFile;
        currentFile = null;
        log("Saved " + (f == null ? "" : f.getName()) + " (" + lines + " lines)");
    }

    void mark(String label) {
        String t = engine.mark(Text.cleanOr(label, "event"), SystemClock.elapsedRealtimeNanos());
        if (t != null) log("Marked at " + t + " s");
    }

    void userDisconnect() {
        if (userDisc) return;
        userDisc = true;
        engine.setLinked(false, SystemClock.elapsedRealtimeNanos());
        engine.linkEvent("USER_DISCONNECT", SystemClock.elapsedRealtimeNanos());
        log("Disconnected.");
        wakeLinkThread();
    }

    void connectNow() {
        userDisc = false;
        permBackoffUntilMs = 0;
        wakeLinkThread();
    }

    void flushNow() { engine.flushRecording(); }

    void stopAll() { shutdown(); }

    /**
     * Control test (4 s baseline, 2.5 s prepare, 4 s event) or Find-signal wizard (custom baseline/action seconds).
     * Starts a recording if none is running and stops it again when the test is done (driven by the ticker).
     */
    boolean runTest(String name, String label, int baselineSec, int actionSec) {
        if (!linkUp || detecting || engine.testActive()) return false;
        if (!engine.isRecording()) {
            if (startRecording(label) != null || !engine.isRecording()) return false;
            testStartedRecording = true;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        boolean ok = engine.testBegin(name, baselineSec * 1_000_000_000L, CanEngine.PREPARE_NS, actionSec * 1_000_000_000L, now);
        if (!ok && testStartedRecording) {
            testStartedRecording = false;
            stopRecording();
        }
        return ok;
    }

    private void onTestFinished() {
        if (testStartedRecording) {
            testStartedRecording = false;
            stopRecording();
        }
    }

    synchronized void log(String m) {
        String line = logFmt.format(new Date()) + "  " + m;
        Log.i(TAG, m);
        logLines.addLast(line);
        while (logLines.size() > 200) logLines.removeFirst();
    }

    synchronized List<String> logTail(int n) {
        List<String> all = new ArrayList<>(logLines);
        return all.size() <= n ? all : new ArrayList<>(all.subList(all.size() - n, all.size()));
    }
}
