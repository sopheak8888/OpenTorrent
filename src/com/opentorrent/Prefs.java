package com.opentorrent;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny settings store. No background components, no scheduling. */
public class Prefs {

    public static final String KEY_WIFI_ONLY = "wifi_only";
    public static final String KEY_SEED = "seed_after_done";
    public static final String KEY_MAX_DL = "max_dl_kb";
    public static final String KEY_MAX_UL = "max_ul_kb";
    public static final String KEY_BATTERY_STOP = "battery_stop";
    public static final String KEY_PORT = "listen_port";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("opentorrent", Context.MODE_PRIVATE);
    }

    public boolean wifiOnly() { return sp.getBoolean(KEY_WIFI_ONLY, false); }
    public void wifiOnly(boolean v) { sp.edit().putBoolean(KEY_WIFI_ONLY, v).apply(); }

    public boolean seedAfterDone() { return sp.getBoolean(KEY_SEED, false); }
    public void seedAfterDone(boolean v) { sp.edit().putBoolean(KEY_SEED, v).apply(); }

    public int maxDownloadKb() { return sp.getInt(KEY_MAX_DL, 0); }
    public void maxDownloadKb(int v) { sp.edit().putInt(KEY_MAX_DL, v).apply(); }

    public int maxUploadKb() { return sp.getInt(KEY_MAX_UL, 0); }
    public void maxUploadKb(int v) { sp.edit().putInt(KEY_MAX_UL, v).apply(); }

    public int batteryStop() { return sp.getInt(KEY_BATTERY_STOP, 0); }
    public void batteryStop(int v) { sp.edit().putInt(KEY_BATTERY_STOP, v).apply(); }

    public int listenPort() { return sp.getInt(KEY_PORT, 6881); }
    public void listenPort(int v) { sp.edit().putInt(KEY_PORT, v).apply(); }

    /** Shared with TorrentBox persistence. */
    public SharedPreferences raw() { return sp; }
}