package com.opentorrent;

import org.json.JSONException;
import org.json.JSONObject;

/** One torrent in the list. Plain data, filled from libtorrent status polls. */
public class Task {

    public String id;           // 40-char hex info hash (lowercase)
    public String name;         // display name
    public String magnet;       // magnet URI, or null
    public String torrentPath;  // saved .torrent path, or null
    public String saveDir;      // absolute save directory
    public long addedAt;
    public boolean paused;
    public boolean complete;
    public transient boolean logged;

    // transient (refreshed every poll)
    public float progress;
    public long totalBytes;
    public long doneBytes;
    public int dlRate;
    public int ulRate;
    public int peers;
    public String state = "queued";

    public boolean isActive() {
        if (paused) return false;
        return "downloading".equals(state)
                || "downloading_metadata".equals(state)
                || "checking".equals(state)
                || "allocating".equals(state)
                || "seeding".equals(state);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("magnet", magnet);
        o.put("torrentPath", torrentPath);
        o.put("saveDir", saveDir);
        o.put("addedAt", addedAt);
        o.put("paused", paused);
        o.put("complete", complete);
        o.put("totalBytes", totalBytes);
        return o;
    }

    public static Task fromJson(JSONObject o) {
        Task t = new Task();
        t.id = o.optString("id", null);
        t.name = o.optString("name", "torrent");
        t.magnet = o.optString("magnet", null);
        t.torrentPath = o.optString("torrentPath", null);
        t.saveDir = o.optString("saveDir", null);
        t.addedAt = o.optLong("addedAt", 0L);
        t.paused = o.optBoolean("paused", false);
        t.complete = o.optBoolean("complete", false);
        t.totalBytes = o.optLong("totalBytes", 0);
        return t;
    }
}