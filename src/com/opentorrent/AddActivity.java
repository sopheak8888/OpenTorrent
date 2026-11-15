package com.opentorrent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Receives magnet links and .torrent files, hands them to the engine, then goes
 * away. Kept separate from MainActivity so intent data is never dropped.
 */
public class AddActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Uri data = intent != null ? intent.getData() : null;
        if (data == null) {
            finish();
            return;
        }

        String uri = data.toString();
        Log.i("OpenTorrent", "AddActivity: " + uri);
        TorrentBox box = TorrentBox.get(this);

        if (data.getScheme() != null && data.getScheme().startsWith("magnet")) {
            toast("Adding magnet…");
            box.addMagnetAsync(uri, error -> runOnUiThread(() -> {
                toast(error == null ? "Added" : "Could not add: " + error);
                if (error == null) openUi();
                finish();
            }));
            return;
        }

        try {
            byte[] bytes = readAll(data);
            String name = data.getLastPathSegment();
            if (name != null && name.toLowerCase(Locale.US).contains(".torrent")) {
                // keep the real name; TorrentBox prefers the torrent's own name anyway
            }
            box.addTorrentFile(bytes, name);
            TorrentService.ensureRunning(this);
            toast("Added");
            openUi();
        } catch (Throwable t) {
            Log.w("OpenTorrent", "add failed", t);
            toast("Could not add: " + t.getMessage());
        }
        finish();
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

    private void openUi() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
