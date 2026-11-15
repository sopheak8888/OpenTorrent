package com.opentorrent;

import android.app.Application;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Touch the engine once so the task list is loaded before any UI shows.
        TorrentBox.get(this);
    }
}