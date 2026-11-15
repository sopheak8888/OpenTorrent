# OpenTorrent

A small, ad-free BitTorrent client for Android. Light UI, real [libtorrent](https://www.libtorrent.org/) 2.x engine, and one strict rule:

> **When nothing is transferring, nothing runs.**

No ads, no analytics, no push messaging, no boot receiver, no `JobScheduler`, no wake-up alarms, no self-restart logic.

---

## Features

- Add **magnet links** or **`.torrent` files** (file picker, clipboard, or "Open with" from a file manager)
- Torrent list with name, `done / total`, ETA, speed, peer count, and a 2 dp progress bar
- Tabs: **All · Downloading · Complete**, plus search
- Long-press a torrent: pause / resume / remove (keep files) / remove and delete files
- Settings: Wi-Fi-only transfers, max ↓/↑ rate, listening port, "keep seeding", auto-pause below a battery %
- Foreground notification with overall progress and **Pause all / Resume all**
- Slide-out drawer: Settings · Storage folder · About · **Exit**
- Downloads land in `Download/OpenTorrent/`, visible to Gallery / VLC / My Files
- Recognises `.torrent` mime (`application/x-bittorrent`) and `file`/`content` URIs ending in `.torrent`

### Battery behaviour (the point of this project)

| Situation | What happens |
|---|---|
| Transfer running | Foreground service + partial wake lock, renewed each tick |
| Transfer finishes | Within ~3 s: engine stopped, wake lock released, notification removed, service gone |
| Queue idle | No service, no wake lock, no process work — nothing is scheduled |
| App relaunched with only completed torrents | Nothing starts (completion is saved) |
| **Exit** (drawer) | Pauses everything, stops libtorrent, removes the notification, closes the app and kills the process |

Because the service is `START_NOT_STICKY` and the app never registers an alarm, a job, or a boot receiver, Android cannot bring it back on its own.

Measured on a Galaxy Tab S9 FE (Android 16):

```
01:03:21.818  state changed to: seeding
01:03:21.940  persist: 1 task(s)              <- marked complete in saved state
01:03:23.978  nothing to transfer: shutting engine down
-> ServiceRecord 0 · Wake Locks: size=0 · notification removed
```

---

## Build

No Gradle. The Android SDK tools are driven directly (`aapt2 → javac → d8 → zipalign → apksigner`), which keeps the APK small and the build fully reproducible.

**Requirements**

- JDK 17+ (`javac`, `keytool`)
- Android SDK with `platforms/android-34+` and `build-tools/34+`
- `python3` (launcher icon generation)

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"   # or ANDROID_SDK_ROOT
./build.sh                                        # -> OpenTorrent.apk
```

The first run downloads the libtorrent4j jars from Maven Central into `libs/` (git-ignored). Nothing else is fetched.

> **ABI note:** the default build packages `arm64-v8a` only (that is what the target tablet uses). To support other devices, add the matching jar in the `fetch` block of `build.sh` — `libtorrent4j-android-arm`, `…-x86`, `…-x86_64` — and copy the extra `.so` files into `stage/lib/<abi>/`.

## Install

```bash
adb install -r OpenTorrent.apk

# write access to Download/OpenTorrent (otherwise it falls back to app storage)
adb shell appops set com.opentorrent MANAGE_EXTERNAL_STORAGE allow

# Android 13+
adb shell pm grant com.opentorrent android.permission.POST_NOTIFICATIONS
```

Signing uses `keystore.jks` (auto-generated on first build, git-ignored). Override with `OPENTORRENT_KEYSTORE=/path/to/keystore.jks`.

---

## Source layout

```
AndroidManifest.xml          activities + the dataSync foreground service
build.sh                     aapt2 → javac → d8 → zipalign → apksigner
make_icon.py                 draws res/drawable/ic_launcher.png
src/com/opentorrent/
  App.java                   Application; warms the engine
  MainActivity.java          list, tabs, search, drawer, settings, Exit
  AddActivity.java           receives magnet/.torrent intents, adds, opens the UI
  TorrentService.java        foreground host: tick, wake lock, battery/Wi-Fi guards
  TorrentBox.java            engine + task state + persistence (JSON in prefs)
  Task.java                  one torrent's data
  PillToggle.java            the 36×22 dp switch
  Prefs.java                 settings
res/layout, res/drawable      the UI (custom top bar, tabs, row, drawer, bottom sheet)
```

### Design notes

- **One engine, one process.** `TorrentBox` owns a single `SessionManager`; the service starts it on demand and stops it when idle.
- **Completion is persisted**, so a finished torrent is never re-hashed or restarted on launch.
- **Links are handled by a dedicated activity** (`AddActivity`) rather than `onNewIntent` on the main screen — Android can silently drop intent data when a `singleTask` activity is already running.
- **Wake lock is short-lived and renewed** (10 min timeout, refreshed per tick) so a crash cannot leave the device pinned awake.

---

## Credits

- [libtorrent4j](https://github.com/aldenml/libtorrent4j) — Java bindings for libtorrent (BSD-3-Clause), fetched at build time, not vendored.
- Icon shapes are original vector drawables.

## License

MIT — see [LICENSE](LICENSE). libtorrent and libtorrent4j remain under their own BSD-3-Clause license.

## Disclaimer

This is a BitTorrent client: it transfers whatever you point it at. Only download and share material you have the right to.
