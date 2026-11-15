package com.opentorrent;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole torrent engine lives here: one session, one process, no alarms,
 * no boot receivers, no ad SDKs, no resurrection loop.
 *
 * Battery contract:
 *  - the libtorrent session only exists while the foreground service runs;
 *  - the service only runs while something is actually transferring.
 */
public class TorrentBox {

    private static final String TAG = "OpenTorrent";
    private static final String TASKS_KEY = "tasks_json";

    public interface Listener {
        void onChanged();
    }

    public interface AddCallback {
        void onResult(String error); // null error == success
    }

    private static TorrentBox instance;

    private final Context ctx;
    private final Prefs prefs;
    private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    private SessionManager sm;
    private String lastError;

    private TorrentBox(Context c) {
        ctx = c.getApplicationContext();
        prefs = new Prefs(ctx);
        load();
    }

    public static synchronized TorrentBox get(Context c) {
        if (instance == null) instance = new TorrentBox(c);
        return instance;
    }

    // ---------------------------------------------------------------- listeners

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onChanged();
            } catch (Throwable t) {
                Log.w(TAG, "listener", t);
            }
        }
    }

    public String lastError() { return lastError; }
    public void clearError() { lastError = null; }

    // ------------------------------------------------------------------ session

    public synchronized boolean isRunning() {
        return sm != null && sm.isRunning();
    }

    public synchronized void startSession() {
        if (sm != null) {
            if (sm.isRunning()) return;
            sm.start();
            return;
        }
        sm = new SessionManager();
        sm.start();

        try {
            SettingsPack sp = new SettingsPack();
            sp.setEnableDht(true);
            sp.setEnableLsd(true);
            sm.applySettings(sp);
        } catch (Throwable e) {
            Log.w(TAG, "settings", e);
        }

        int dl = prefs.maxDownloadKb();
        int ul = prefs.maxUploadKb();
        if (dl > 0) sm.downloadRateLimit(dl * 1024);
        if (ul > 0) sm.uploadRateLimit(ul * 1024);
        sm.listenInterfaces("0.0.0.0:" + prefs.listenPort());

        sm.addListener(alertListener);

        for (Task t : new ArrayList<>(tasks.values())) {
            if (t.paused) continue;
            reattach(t);
        }
        Log.i(TAG, "session started, " + tasks.size() + " task(s)");
        notifyChanged();
    }

    public synchronized void stopSession() {
        if (sm != null) {
            try {
                sm.stop();
            } catch (Throwable t) {
                Log.w(TAG, "stop", t);
            }
            sm = null;
        }
        for (Task t : tasks.values()) {
            t.dlRate = 0;
            t.ulRate = 0;
        }
        notifyChanged();
    }

    public synchronized SessionManager session() { return sm; }

    private final AlertListener alertListener = new AlertListener() {
        @Override
        public int[] types() {
            return null; // all
        }

        @Override
        public void alert(org.libtorrent4j.alerts.Alert<?> a) {
            try {
                String w = a.what();
                if (w == null) return;
                boolean interesting = w.contains("error") || w.contains("tracker")
                        || "add_torrent".equals(w) || "state_changed".equals(w);
                if (!interesting) return;
                Log.i(TAG, "alert " + w + ": " + trim(a.message()));
            } catch (Throwable ignored) {
            }
        }
    };

    private static String trim(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private void reattach(Task t) {
        try {
            if (t.torrentPath != null) {
                File f = new File(t.torrentPath);
                if (f.exists()) {
                    TorrentInfo ti = new TorrentInfo(f);
                    String n = ti.name();
                    if (n != null && n.length() > 0) t.name = n;
                    sm.download(ti, new File(t.saveDir));
                    return;
                }
            }
            if (t.magnet != null) {
                sm.download(new TorrentInfo(fetchMagnetBytes(t.magnet)), new File(t.saveDir));
            }
        } catch (Throwable e) {
            Log.w(TAG, "reattach failed " + t.name + ": " + e.getMessage());
        }
    }

    private byte[] fetchMagnetBytes(String uri) {
        return sm.fetchMagnet(uri, 60, new File(ctx.getFilesDir(), "resume"));
    }

    // -------------------------------------------------------------------- tasks

    public synchronized List<Task> tasks() {
        return new ArrayList<>(tasks.values());
    }

    public synchronized Task task(String id) {
        return tasks.get(id);
    }

    /** True when something is actually moving — drives wake lock and service life. */
    public synchronized boolean hasActive() {
        for (Task t : tasks.values()) {
            if (isActive(t)) return true;
        }
        return false;
    }

    public boolean isActive(Task t) {
        if (t.paused) return false;
        if (t.complete && !prefs.seedAfterDone()) return false;
        TorrentStatus.State s = stateOf(t);
        if (s == null) return false;
        switch (s) {
            case DOWNLOADING:
            case DOWNLOADING_METADATA:
            case CHECKING_FILES:
            case CHECKING_RESUME_DATA:
                return true;
            case SEEDING:
                return prefs.seedAfterDone();
            default:
                return false;
        }
    }

    public synchronized long totalDownloadRate() {
        long r = 0;
        for (Task t : tasks.values()) if (!t.paused) r += t.dlRate;
        return r;
    }

    public synchronized long totalUploadRate() {
        long r = 0;
        for (Task t : tasks.values()) if (!t.paused) r += t.ulRate;
        return r;
    }

    public synchronized int activeCount() {
        int n = 0;
        for (Task t : tasks.values()) if (isActive(t)) n++;
        return n;
    }

    public synchronized float overallProgress() {
        float p = 0;
        int n = 0;
        for (Task t : tasks.values()) {
            p += Math.min(1f, t.progress);
            n++;
        }
        return n == 0 ? 0 : p / n;
    }

    // --------------------------------------------------------------------- add

    public static File defaultSaveDir(Context c) {
        File f = new File(Environment.getExternalStorageDirectory(), "Download/OpenTorrent");
        try {
            if (f.exists() || f.mkdirs()) {
                File probe = new File(f, ".probe");
                if (probe.exists() || probe.createNewFile()) {
                    probe.delete();
                    return f;
                }
            }
        } catch (Throwable ignored) {
        }
        File ext = c.getExternalFilesDir(null);
        if (ext == null) ext = c.getFilesDir();
        File fallback = new File(ext, "downloads");
        fallback.mkdirs();
        return fallback;
    }

    /** Adds a .torrent payload (already decoded bytes). Safe from any thread. */
    public synchronized Task addTorrentFile(byte[] data, String displayName) throws Exception {
        startSession();
        TorrentInfo ti = new TorrentInfo(data);
        String hex = ti.infoHash().toHex().toLowerCase();
        File saveDir = defaultSaveDir(ctx);

        File dir = new File(ctx.getFilesDir(), "torrents");
        dir.mkdirs();
        File tf = new File(dir, hex + ".torrent");
        writeFile(tf, data);

        Task t = new Task();
        t.id = hex;
        String realName = ti.name();
        t.name = (realName != null && realName.length() > 0) ? realName
                : (displayName != null ? displayName : hex);
        t.saveDir = saveDir.getAbsolutePath();
        t.addedAt = System.currentTimeMillis();
        t.torrentPath = tf.getAbsolutePath();
        t.totalBytes = ti.totalSize();

        sm.download(ti, saveDir);
        tasks.put(hex, t);
        persist();
        notifyChanged();
        return t;
    }

    /**
     * Magnet links need metadata first; that is the only blocking step and it runs
     * off the UI thread. The resolved .torrent is cached, so a restart never needs
     * the network again.
     */
    public void addMagnetAsync(String uri, AddCallback cb) {
        new Thread(() -> {
            String error = null;
            try {
                String hex = hashFromMagnet(uri);
                if (hex == null) {
                    throw new IllegalArgumentException("Only 40-char hex info hashes are supported");
                }
                startSession();
                byte[] data = fetchMagnetBytes(uri);
                if (data == null || data.length == 0) {
                    throw new IllegalStateException("No peers found for this magnet (metadata timeout)");
                }
                Task t = addTorrentFile(data, null);
                t.magnet = uri;
                persist();
            } catch (Throwable t) {
                error = t.getMessage();
                Log.w(TAG, "add magnet failed", t);
            }
            if (cb != null) cb.onResult(error);
            notifyChanged();
        }, "magnet-resolve").start();
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    public static String hashFromMagnet(String uri) {
        if (uri == null) return null;
        String marker = "urn:btih:";
        int i = uri.toLowerCase().indexOf(marker);
        if (i < 0) return null;
        int start = i + marker.length();
        int end = start;
        while (end < uri.length() && isHex(uri.charAt(end))) end++;
        if (end - start != 40) return null;
        return uri.substring(start, end).toLowerCase();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // ----------------------------------------------------------------- actions

    public synchronized void pause(String id) {
        Task t = tasks.get(id);
        if (t == null) return;
        t.paused = true;
        TorrentHandle h = handle(t);
        if (h != null) {
            try {
                h.pause();
            } catch (Throwable ignored) {
            }
        }
        persist();
        notifyChanged();
    }

    public synchronized void resume(String id) {
        Task t = tasks.get(id);
        if (t == null) return;
        t.paused = false;
        TorrentHandle h = handle(t);
        if (h != null) {
            try {
                h.resume();
            } catch (Throwable ignored) {
            }
        } else if (isRunning()) {
            reattach(t);
        }
        persist();
        notifyChanged();
    }

    public synchronized void pauseAll() {
        for (Task t : new ArrayList<>(tasks.values())) {
            if (!t.paused) pause(t.id);
        }
    }

    public synchronized void resumeAll() {
        for (Task t : new ArrayList<>(tasks.values())) {
            if (t.paused) resume(t.id);
        }
    }

    public synchronized void remove(String id, boolean deleteFiles) {
        Task t = tasks.get(id);
        if (t == null) return;
        TorrentHandle h = handle(t);
        if (h != null) {
            try {
                sm.remove(h);
            } catch (Throwable ignored) {
            }
        }
        if (t.torrentPath != null) new File(t.torrentPath).delete();
        if (deleteFiles && t.saveDir != null) {
            File target = new File(new File(t.saveDir), t.name);
            if (target.exists()) deleteRecursive(target);
        }
        tasks.remove(id);
        persist();
        notifyChanged();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    // ------------------------------------------------------------------ refresh

    private TorrentHandle handle(Task t) {
        if (sm == null || t.id == null) return null;
        try {
            return sm.find(Sha1Hash.parseHex(t.id));
        } catch (Throwable e) {
            return null;
        }
    }

    private TorrentStatus.State stateOf(Task t) {
        TorrentHandle h = handle(t);
        if (h == null) return null;
        try {
            return h.status().state();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Called by the service every tick. Cheap; creates no wake-ups by itself. */
    public synchronized void refresh() {
        if (sm == null) return;
        for (Task t : tasks.values()) {
            TorrentHandle h = handle(t);
            if (h == null) {
                if (!t.paused) t.state = "queued";
                continue;
            }
            try {
                TorrentStatus s = h.status();
                t.state = stateName(s.state());
                t.progress = s.progress();
                t.totalBytes = s.totalWanted();
                t.doneBytes = s.totalWantedDone();
                t.dlRate = t.paused ? 0 : s.downloadRate();
                t.ulRate = t.paused ? 0 : s.uploadRate();
                t.peers = s.numPeers();
                long wanted = s.totalWanted();
                boolean done = s.isFinished()
                        || s.state() == TorrentStatus.State.SEEDING
                        || (wanted > 0 && s.totalWantedDone() >= wanted);
                if (done && !t.complete) {
                    t.complete = true;
                    t.doneBytes = t.totalBytes;
                    persist(); // remember it, so a relaunch does not re-check or auto-start
                }
                String n = s.name();
                if (n != null && n.length() > 0 && !n.equals("0")) t.name = n;
            } catch (Throwable ignored) {
            }
        }
        notifyChanged();
    }

    private static String stateName(TorrentStatus.State s) {
        switch (s) {
            case DOWNLOADING: return "downloading";
            case DOWNLOADING_METADATA: return "downloading_metadata";
            case CHECKING_FILES:
            case CHECKING_RESUME_DATA: return "checking";
            case FINISHED: return "finished";
            case SEEDING: return "seeding";
            default: return "queued";
        }
    }

    public synchronized void applyLimits() {
        if (sm == null) return;
        int dl = prefs.maxDownloadKb();
        int ul = prefs.maxUploadKb();
        sm.downloadRateLimit(dl > 0 ? dl * 1024 : 0);
        sm.uploadRateLimit(ul > 0 ? ul * 1024 : 0);
    }

    // -------------------------------------------------------------- persistence

    private void persist() {
        try {
            String json = tasksJson();
            Log.i(TAG, "persist: " + tasks.size() + " task(s), " + json.length() + " chars");
            prefs.raw().edit().putString(TASKS_KEY, json).apply();
        } catch (Throwable t) {
            Log.w(TAG, "persist", t);
        }
    }

    /** Blocking write, used right before the process is torn down on Exit. */
    public synchronized void persistNow() {
        try {
            prefs.raw().edit().putString(TASKS_KEY, tasksJson()).commit();
        } catch (Throwable t) {
            Log.w(TAG, "persistNow", t);
        }
    }

    private String tasksJson() throws Exception {
        JSONArray arr = new JSONArray();
        for (Task t : tasks.values()) arr.put(t.toJson());
        return arr.toString();
    }

    /** Used by Exit: drop all live state so nothing can keep running. */
    public synchronized void shutdownEngine() {
        stopSession();
    }

    private void load() {
        String raw = prefs.raw().getString(TASKS_KEY, null);
        Log.i(TAG, "load: " + (raw == null ? "no saved tasks" : raw.length() + " chars"));
        if (raw == null) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Task t = Task.fromJson(arr.getJSONObject(i));
                if (t.id != null) tasks.put(t.id, t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "load", t);
        }
    }

    /** Engine health for logs/diagnostics. */
    public synchronized String diag() {
        if (sm == null) return "engine=off";
        int peers = 0;
        for (Task t : tasks.values()) peers += t.peers;
        long dht = -1;
        try {
            dht = sm.dhtNodes();
        } catch (Throwable ignored) {
        }
        String eps = "";
        try {
            eps = String.valueOf(sm.listenEndpoints());
        } catch (Throwable ignored) {
        }
        return "engine=on dht=" + dht + " torrents=" + tasks.size() + " peers=" + peers + " eps=" + eps;
    }

    public String summary() {
        int n = tasks.size();
        if (n == 0) return "No torrents";
        return n + (n == 1 ? " torrent" : " torrents") + " · " + activeCount() + " active";
    }

    public Collection<Task> snapshot() {
        return new ArrayList<>(tasks.values());
    }

    public Map<String, Task> map() {
        return new LinkedHashMap<>(tasks);
    }
}