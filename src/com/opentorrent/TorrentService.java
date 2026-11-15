package com.opentorrent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.Locale;

/**
 * Foreground-only engine host.
 *
 * There is deliberately no alarm, no JobScheduler entry, no boot receiver and no
 * restart hook anywhere in this app: when it has nothing to transfer, the service
 * removes its notification, releases the wake lock, stops libtorrent and exits.
 * That single rule is what uTorrent violates with Alarm + RestartAppJobService.
 */
public class TorrentService extends Service {

    private static final String TAG = "OpenTorrent";
    private static final String CHANNEL_ID = "transfers";
    private static final int NOTIF_ID = 1001;
    private static final long TICK_MS = 1000L;
    private static final int IDLE_TICKS_BEFORE_STOP = 3;

    public static final String ACTION_PAUSE_ALL = "com.opentorrent.PAUSE_ALL";
    public static final String ACTION_RESUME_ALL = "com.opentorrent.RESUME_ALL";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TorrentBox box;
    private Prefs prefs;
    private PowerManager.WakeLock wakeLock;
    private int idleTicks;
    private long ticks;
    private boolean foreground;

    public static void ensureRunning(Context c) {
        Intent i = new Intent(c, TorrentService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void shutdown(Context c) {
        c.stopService(new Intent(c, TorrentService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        box = TorrentBox.get(this);
        prefs = new Prefs(this);
        createChannel();
        box.startSession();
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground) {
            startForeground(NOTIF_ID, buildNotification());
            foreground = true;
        }
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PAUSE_ALL.equals(action)) {
            box.pauseAll();
        } else if (ACTION_RESUME_ALL.equals(action)) {
            box.resumeAll();
        }
        box.startSession();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY; // never resurrect itself
    }

    // -------------------------------------------------------------------- tick

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean alive = step();
            if (alive) {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private boolean step() {
        try {
            box.startSession();
            box.refresh();
            box.applyLimits();

            boolean active = box.hasActive();

            if (ticks % 5 == 0) Log.i(TAG, box.diag());
            ticks++;

            // ---- battery guard
            int stopAt = prefs.batteryStop();
            if (active && stopAt > 0 && onBatteryLow(stopAt)) {
                Log.i(TAG, "battery guard: pausing everything at " + stopAt + "%");
                box.pauseAll();
                active = false;
                idleTicks = IDLE_TICKS_BEFORE_STOP;
            }

            // ---- wifi guard
            if (active && prefs.wifiOnly() && !onWifi()) {
                Log.i(TAG, "wifi guard: transfers held until Wi-Fi returns");
                box.pauseAll();
                active = false;
                idleTicks = IDLE_TICKS_BEFORE_STOP;
            }

            setWakeLock(active);

            if (foreground) {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .notify(NOTIF_ID, buildNotification());
            }

            if (!active) {
                idleTicks++;
                if (idleTicks >= IDLE_TICKS_BEFORE_STOP) {
                    Log.i(TAG, "nothing to transfer: shutting engine down");
                    stopSelf();
                    return false;
                }
            } else {
                idleTicks = 0;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "tick failed", t);
            idleTicks++;
            if (idleTicks >= 30) {
                stopSelf();
                return false;
            }
            return true;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        setWakeLock(false);
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
        if (box != null) box.stopSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ----------------------------------------------------------------- helpers

    private void setWakeLock(boolean acquire) {
        if (acquire) {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenTorrent:transfer");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 1000L); // renewed on every tick while active
            }
        } else if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private boolean onWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected() && n.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean onBatteryLow(int threshold) {
        try {
            Intent sticker = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticker == null) return false;
            int level = sticker.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = sticker.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = sticker.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (charging) return false;
            int pct = (int) (level * 100f / scale);
            return pct >= 0 && pct <= threshold;
        } catch (Throwable t) {
            return false;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private String rate(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024f);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024f * 1024f));
    }

    private Notification buildNotification() {
        int active = box.activeCount();
        long dl = box.totalDownloadRate();
        String title = active == 0
                ? "OpenTorrent idle"
                : (active + (active == 1 ? " download" : " downloads"));
        String text = active == 0
                ? "No active transfers — engine stopped"
                : "↓ " + rate(dl) + "  ·  " + Math.round(box.overallProgress() * 100) + "%";

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this, 0, open, pendingFlags());

        boolean anyActive = box.activeCount() > 0;
        Intent toggle = new Intent(this, TorrentService.class)
                .setAction(anyActive ? ACTION_PAUSE_ALL : ACTION_RESUME_ALL);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggle, pendingFlags());

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(anyActive)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null,
                        anyActive ? "Pause all" : "Resume all", togglePi).build());
        if (anyActive) {
            b.setProgress(1000, (int) (box.overallProgress() * 1000), false);
        }
        return b.build();
    }

    private int pendingFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }
}