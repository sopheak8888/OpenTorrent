package com.opentorrent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TorrentBox.Listener {

    private static final int REQ_TORRENT_FILE = 11;
    private static final int REQ_NOTIF = 12;

    private static final String[] TAB_LABELS = {"All", "Downloading", "Complete"};
    private TorrentBox box;
    private Prefs prefs;
    private ListView list;
    private TextView tvTitle, tvHeader, tvPauseAll, tvEmpty;
    private EditText etSearch;
    private View spacer;
    private LinearLayout tabsRow;
    private View drawer, scrim;
    private boolean drawerOpen;
    private float edgeDownX;
    private boolean edgeTracking;
    private TorrentAdapter adapter;

    private int tabIndex = 0;
    private String lastHandledUri;
    private long lastHandledAt;
    private String query = "";

    private int colorPrimary, colorText, colorSub, colorUnselect;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        box = TorrentBox.get(this);
        prefs = new Prefs(this);
        colorPrimary = getColor(R.color.primary);
        colorText = getColor(R.color.text_primary);
        colorSub = getColor(R.color.text_secondary);
        colorUnselect = getColor(R.color.text_unselect);

        tvTitle = (TextView) findViewById(R.id.tvTitle);
        tvHeader = (TextView) findViewById(R.id.tvHeader);
        tvPauseAll = (TextView) findViewById(R.id.tvPauseAll);
        tvEmpty = (TextView) findViewById(R.id.empty);
        etSearch = (EditText) findViewById(R.id.etSearch);
        spacer = findViewById(R.id.spacer);
        tabsRow = (LinearLayout) findViewById(R.id.tabs);
        list = (ListView) findViewById(R.id.list);

        buildTabs();
        buildDrawer();

        adapter = new TorrentAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(tvEmpty);

        findViewById(R.id.btnMenu).setOnClickListener(v -> openDrawer());
        findViewById(R.id.btnSearch).setOnClickListener(v -> toggleSearch());
        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddSheet());
        findViewById(R.id.btnSelect).setOnClickListener(v -> showBatch());
        tvPauseAll.setOnClickListener(v -> {
            if (box.activeCount() > 0) {
                box.pauseAll();
                TorrentService.shutdown(this);
            } else {
                box.resumeAll();
                TorrentService.ensureRunning(this);
            }
            render();
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            Task t = adapter.getItem(position);
            if (t != null) showTaskMenu(t);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.US);
                render();
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }

        handleIntent(getIntent());
    }

    // ------------------------------------------------------------------ chrome

    private void buildTabs() {
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            View v = inf.inflate(R.layout.tab_item, tabsRow, false);
            TextView label = (TextView) v.findViewById(R.id.tabText);
            label.setText(TAB_LABELS[i]);
            v.setOnClickListener(x -> {
                tabIndex = index;
                updateTabs();
                render();
            });
            tabsRow.addView(v);
        }
        updateTabs();
    }

    private void updateTabs() {
        for (int i = 0; i < tabsRow.getChildCount(); i++) {
            View v = tabsRow.getChildAt(i);
            TextView label = (TextView) v.findViewById(R.id.tabText);
            View ind = v.findViewById(R.id.tabIndicator);
            boolean selected = i == tabIndex;
            label.setTextColor(selected ? colorText : colorUnselect);
            label.getPaint().setFakeBoldText(selected);
            label.invalidate();
            ind.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void toggleSearch() {
        boolean open = etSearch.getVisibility() == View.VISIBLE;
        if (open) {
            etSearch.setText("");
            etSearch.setVisibility(View.GONE);
            tvTitle.setVisibility(View.VISIBLE);
            spacer.setVisibility(View.VISIBLE);
            hideKeyboard();
        } else {
            tvTitle.setVisibility(View.GONE);
            spacer.setVisibility(View.GONE);
            etSearch.setVisibility(View.VISIBLE);
            etSearch.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    // ---------------------------------------------------------------- drawer

    private void buildDrawer() {
        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.scrim);

        LinearLayout items = (LinearLayout) findViewById(R.id.drawerItems);
        final int[] icons = {R.drawable.ic_settings, R.drawable.ic_folder, R.drawable.ic_info, R.drawable.ic_exit};
        final String[] labels = {"Settings", "Storage folder", "About", "Exit"};

        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            View row = inf.inflate(R.layout.drawer_item, items, false);
            ((ImageView) row.findViewById(R.id.itemIcon)).setImageResource(icons[i]);
            ((TextView) row.findViewById(R.id.itemText)).setText(labels[i]);
            row.setOnClickListener(v -> {
                switch (index) {
                    case 0:
                        closeDrawer();
                        showSettings();
                        break;
                    case 1:
                        closeDrawer();
                        toast("Saving to: " + TorrentBox.defaultSaveDir(this).getAbsolutePath());
                        break;
                    case 2:
                        closeDrawer();
                        showAbout();
                        break;
                    default:
                        showExit();
                        break;
                }
            });
            items.addView(row);
        }

        scrim.setOnClickListener(v -> closeDrawer());
    }

    /**
     * Edge-swipe opens the drawer without stealing taps: events are still
     * dispatched to the content, so the ☰ button keeps working.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (!drawerOpen) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    edgeDownX = ev.getX();
                    edgeTracking = edgeDownX <= dp(24);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (edgeTracking && ev.getX() - edgeDownX > dp(36)) {
                        edgeTracking = false;
                        openDrawer();
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    edgeTracking = false;
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-dp(280));
        scrim.setAlpha(0f);
        drawer.animate().translationX(0f).setDuration(220).start();
        scrim.animate().alpha(0.35f).setDuration(220).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.animate().translationX(-dp(280)).setDuration(200)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    /** The explicit way out: stop the engine and close the app. */
    private void showExit() {
        int active = box.activeCount();
        if (active == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Exit OpenTorrent?")
                    .setMessage("Nothing is transferring right now — the engine is already stopped.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Exit", (d, w) -> exitNow(true))
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(active + (active == 1 ? " transfer running" : " transfers running"))
                .setItems(new String[]{"Stop transfers and exit", "Keep downloading in background"},
                        (d, which) -> exitNow(which == 0))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exitNow(boolean stopTransfers) {
        closeDrawer();
        if (stopTransfers) {
            box.pauseAll();
            box.persistNow();
            TorrentService.shutdown(this);
            box.shutdownEngine();
            finishAffinity();
            // A real exit: nothing left to keep the process around.
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        finishAffinity();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (etSearch.getVisibility() == View.VISIBLE) {
            toggleSearch();
            return;
        }
        super.onBackPressed();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("OpenTorrent")
                .setMessage("No ads. No analytics. No background resurrection.\n\n"
                        + "Engine: libtorrent 2.x\n"
                        + "The foreground service and wake lock exist only while a transfer is moving.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAddSheet() {
        String clip = clipboardMagnet();
        String[] items = clip.isEmpty()
                ? new String[]{"Magnet link", "Choose .torrent file"}
                : new String[]{"Magnet link", "Paste magnet from clipboard", "Choose .torrent file"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) showMagnetDialog("");
                    else if (clip.isEmpty()) pickTorrentFile();
                    else if (which == 1) addMagnet(clip);
                    else pickTorrentFile();
                })
                .show();
    }

    private void showBatch() {
        String[] items = {"Pause all", "Resume all", "Remove completed", "Remove all"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            box.pauseAll();
                            TorrentService.shutdown(this);
                            break;
                        case 1:
                            box.resumeAll();
                            TorrentService.ensureRunning(this);
                            break;
                        case 2:
                            for (Task t : box.tasks()) {
                                if (t.complete) box.remove(t.id, false);
                            }
                            break;
                        default:
                            confirmRemoveAll();
                            break;
                    }
                    render();
                })
                .show();
    }

    private void confirmRemoveAll() {
        new AlertDialog.Builder(this)
                .setTitle("Remove all torrents?")
                .setMessage("Downloaded files stay on storage.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    for (Task t : box.tasks()) box.remove(t.id, false);
                    TorrentService.shutdown(this);
                    render();
                })
                .show();
    }

    // -------------------------------------------------------------------- list

    @Override
    protected void onResume() {
        super.onResume();
        box.addListener(this);
        ui.post(refresher);
        Log.i("OpenTorrent", "onResume intent: " + getIntent() + " data=" + getIntent().getData());
        handleIntent(getIntent());
        for (Task t : box.tasks()) {
            if (!t.paused && !t.complete) {
                TorrentService.ensureRunning(this);
                break;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        box.removeListener(this);
        ui.removeCallbacks(refresher);
    }

    @Override
    public void onChanged() {
        ui.post(this::render);
    }

    private void render() {
        List<Task> shown = filtered();
        adapter.setData(shown);

        tvHeader.setText("Torrents (" + shown.size() + ")");
        tvPauseAll.setText(box.activeCount() > 0 ? "Pause All" : "Resume All");
        tvPauseAll.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private List<Task> filtered() {
        List<Task> out = new ArrayList<>();
        for (Task t : box.tasks()) {
            if (!query.isEmpty() && !t.name.toLowerCase(Locale.US).contains(query)) continue;
            if (!matchesTab(t)) continue;
            out.add(t);
        }
        return out;
    }

    private boolean matchesTab(Task t) {
        String s = t.state == null ? "" : t.state;
        if (tabIndex == 0) return true;
        if (tabIndex == 1) {
            return !t.complete && !t.paused
                    && ("downloading".equals(s) || "downloading_metadata".equals(s) || "checking".equals(s));
        }
        return t.complete || "finished".equals(s) || "seeding".equals(s);
    }

    // --------------------------------------------------------------------- add

    private void pickTorrentFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_TORRENT_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TORRENT_FILE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            byte[] bytes = readAll(uri);
            String name = uri.getLastPathSegment();
            box.addTorrentFile(bytes, name);
            TorrentService.ensureRunning(this);
            render();
            toast("Added");
        } catch (Throwable t) {
            toast("Not a valid torrent: " + t.getMessage());
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("cannot open");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private void showMagnetDialog(String prefill) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("magnet:?xt=urn:btih:…");
        input.setText(prefill);
        input.setTextSize(13);

        FrameLayout wrap = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Add magnet link")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> addMagnet(input.getText().toString().trim()))
                .show();
    }

    private String clipboardMagnet() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence s = clip.getItemAt(0).coerceToText(this);
                if (s != null && s.toString().trim().startsWith("magnet:")) {
                    return s.toString().trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void addMagnet(String uri) {
        if (uri == null || !uri.startsWith("magnet:")) {
            toast("Not a magnet link");
            return;
        }
        toast("Resolving magnet…");
        box.addMagnetAsync(uri, error -> runOnUiThread(() -> {
            if (error != null) {
                toast("Could not add: " + error);
            } else {
                TorrentService.ensureRunning(this);
                render();
                toast("Added");
            }
        }));
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        String uri = data.toString();
        long now = System.currentTimeMillis();
        // The system can hand the same intent back when the task is resumed.
        if (uri.equals(lastHandledUri) && now - lastHandledAt < 1500) return;
        lastHandledUri = uri;
        lastHandledAt = now;
        Log.i("OpenTorrent", "intent data: " + uri);

        if (data.getScheme() != null && data.getScheme().startsWith("magnet")) {
            addMagnet(uri);
            return;
        }
        String last = data.getLastPathSegment();
        if (last != null && last.toLowerCase(Locale.US).endsWith(".torrent")) {
            try {
                box.addTorrentFile(readAll(data), last);
                TorrentService.ensureRunning(this);
                render();
                toast("Added " + last);
            } catch (Throwable t) {
                Log.e("OpenTorrent", "add torrent failed", t);
                toast("Could not add: " + t.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private void showTaskMenu(Task t) {
        String[] options = {t.paused ? "Resume" : "Pause", "Remove (keep files)",
                "Remove and delete files", "Show save folder"};
        new AlertDialog.Builder(this)
                .setTitle(t.name)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (t.paused) {
                                box.resume(t.id);
                                TorrentService.ensureRunning(this);
                            } else {
                                box.pause(t.id);
                            }
                            render();
                            break;
                        case 1:
                            box.remove(t.id, false);
                            render();
                            break;
                        case 2:
                            confirmDelete(t);
                            break;
                        default:
                            toast("Saving to: " + t.saveDir);
                            break;
                    }
                })
                .show();
    }

    private void confirmDelete(Task t) {
        new AlertDialog.Builder(this)
                .setTitle("Delete files?")
                .setMessage(t.name + "\n\nThis removes the torrent and deletes its downloaded files.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    box.remove(t.id, true);
                    render();
                })
                .show();
    }

    private void showSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, 0);

        final CheckBox wifiOnly = new CheckBox(this);
        wifiOnly.setText("Only download on Wi-Fi");
        wifiOnly.setChecked(prefs.wifiOnly());
        root.addView(wifiOnly);

        final CheckBox seed = new CheckBox(this);
        seed.setText("Keep seeding after download completes");
        seed.setChecked(prefs.seedAfterDone());
        root.addView(seed);

        final EditText dl = numberField("Max download KB/s (0 = unlimited)", prefs.maxDownloadKb());
        root.addView(dl);
        final EditText ul = numberField("Max upload KB/s (0 = unlimited)", prefs.maxUploadKb());
        root.addView(ul);
        final EditText battery = numberField("Pause below battery % (0 = off)", prefs.batteryStop());
        root.addView(battery);
        final EditText port = numberField("Listening port", prefs.listenPort());
        root.addView(port);

        TextView note = new TextView(this);
        note.setText("Save folder: " + TorrentBox.defaultSaveDir(this).getAbsolutePath());
        note.setTextSize(12);
        note.setTextColor(colorSub);
        note.setPadding(0, pad / 2, 0, 0);
        root.addView(note);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.wifiOnly(wifiOnly.isChecked());
                    prefs.seedAfterDone(seed.isChecked());
                    prefs.maxDownloadKb(parse(dl, 0));
                    prefs.maxUploadKb(parse(ul, 0));
                    prefs.batteryStop(parse(battery, 0));
                    prefs.listenPort(parse(port, 6881));
                    box.applyLimits();
                    render();
                });

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            b.setNeutralButton("Allow folder", (d, w) -> requestAllFiles());
        }
        b.show();
    }

    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private EditText numberField(String label, int value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint(label);
        e.setText(String.valueOf(value));
        e.setTextSize(13);
        return e;
    }

    private static int parse(EditText e, int fallback) {
        try {
            String s = e.getText().toString().trim();
            return s.isEmpty() ? fallback : Integer.parseInt(s);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- formatting

    private static String rate(long bps) {
        if (bps < 1024) return bps + " B/s";
        if (bps < 1024 * 1024) return String.format(Locale.US, "%.0f K/s", bps / 1024f);
        return String.format(Locale.US, "%.1f M/s", bps / (1024f * 1024f));
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.0f K", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f M", bytes / (1024f * 1024f));
        return String.format(Locale.US, "%.1f G", bytes / (1024f * 1024f * 1024f));
    }

    /** "1h 53m" style ETA like µTorrent. */
    private static String eta(long remainingBytes, long bytesPerSec) {
        if (bytesPerSec <= 0 || remainingBytes <= 0) return "--";
        long secs = remainingBytes / bytesPerSec;
        if (secs > 100L * 3600) return "--";
        long h = secs / 3600, m = (secs % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return Math.max(1, secs) + "s";
    }

    // ------------------------------------------------------------------ adapter

    private class TorrentAdapter extends BaseAdapter {

        private final List<Task> data = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        void setData(List<Task> items) {
            data.clear();
            data.addAll(items);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return data.size(); }

        @Override
        public Task getItem(int position) { return data.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = inflater.inflate(R.layout.row_torrent, parent, false);
            Task t = data.get(position);

            TextView name = (TextView) v.findViewById(R.id.tvName);
            TextView size = (TextView) v.findViewById(R.id.tvSize);
            TextView time = (TextView) v.findViewById(R.id.tvTime);
            TextView pct = (TextView) v.findViewById(R.id.tvPct);
            ProgressBar bar = (ProgressBar) v.findViewById(R.id.bar);
            ImageButton pause = (ImageButton) v.findViewById(R.id.btnPause);
            PillToggle toggle = (PillToggle) v.findViewById(R.id.toggle);

            name.setText(t.name);

            if (t.complete) {
                size.setText(size(t.totalBytes));
            } else {
                String done = t.doneBytes > 0 ? size(t.doneBytes) + " / " : "";
                size.setText(done + size(t.totalBytes));
            }

            float p = Math.max(0f, Math.min(1f, t.progress));
            bar.setProgress((int) (p * 1000));

            boolean active = !t.paused && !t.complete && box.isActive(t);
            String s = t.state == null ? "" : t.state;

            if (t.paused) {
                time.setText("Paused");
                time.setTextColor(colorSub);
            } else if (t.complete) {
                time.setText(prefs.seedAfterDone() && "seeding".equals(s) ? "Seeding" : "Completed");
                time.setTextColor(colorPrimary);
            } else if ("downloading_metadata".equals(s)) {
                time.setText("Resolving magnet…");
                time.setTextColor(colorSub);
            } else if ("checking".equals(s)) {
                time.setText("Checking files…");
                time.setTextColor(colorSub);
            } else if (active) {
                long remaining = Math.max(0, t.totalBytes - t.doneBytes);
                if (t.dlRate > 0) {
                    time.setText(eta(remaining, t.dlRate) + " (" + rate(t.dlRate) + ")");
                } else {
                    time.setText("Connecting…");
                }
                time.setTextColor(colorSub);
            } else {
                time.setText("Queued");
                time.setTextColor(colorSub);
            }

            if (t.complete) {
                pct.setText("");
            } else {
                pct.setText(Math.round(p * 100) + "%");
            }

            if (active) {
                pause.setVisibility(View.VISIBLE);
                toggle.setVisibility(View.GONE);
                pause.setOnClickListener(x -> {
                    box.pause(t.id);
                    render();
                });
            } else {
                pause.setVisibility(View.GONE);
                toggle.setVisibility(View.VISIBLE);
                boolean dormantDone = t.complete && !prefs.seedAfterDone();
                toggle.setAlpha(dormantDone ? 0.45f : 1f);
                toggle.setEnabled(!dormantDone);
                toggle.setOn(!t.paused && !dormantDone);
                toggle.setOnChangeListener(on -> {
                    if (on) {
                        box.resume(t.id);
                        TorrentService.ensureRunning(MainActivity.this);
                    } else {
                        box.pause(t.id);
                    }
                    render();
                });
            }
            return v;
        }
    }
}