package io.github.shishonomisaki.parentlock;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * The guard. While the TV is locked it eats every remote key it is given and puts the lock screen back over
 * whatever comes to the front (an app, an HDMI input, Live TV). When the time runs out it pauses playback,
 * mutes, and locks. It also keeps Settings shut while unlocked, so the service cannot be switched off from
 * the TV, unless a parent opened Settings from the phone. It reads which package is on screen and nothing else.
 */
public class GuardService extends AccessibilityService {
    private static final long CHECK_MS = 5000;
    private static final String[] SETTINGS_PACKAGES = {"com.android.tv.settings", "com.android.settings"};
    private static volatile GuardService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LockState state;
    private WebServer server;
    private boolean wasLocked = true;
    private static final int[] ALERT_MINUTES = {5, 3, 1};
    private final java.util.Set<Integer> alerted = new java.util.HashSet<>();
    private String lastForeground;
    private Countdown countdown;
    private String currentApp;   // the app whose volume is being tracked, null on Home or while locked
    private String currentOutput = "tv";

    /**
     * Any app that declares Home: the stock launcher today, a new one tomorrow. resolveActivity is no use
     * here, since with several HOME apps and no default it answers Android's chooser.
     */
    private boolean isHome(String pkg) {
        if (pkg == null) return false;
        for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            if (r.activityInfo != null && pkg.equals(r.activityInfo.packageName)) return true;
        }
        return false;
    }

    /** The app that plays Live TV and the HDMI inputs: the one that handles TV input channels. */
    private boolean isTvInput(String pkg) {
        Intent tv = new Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/channel");
        for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(tv, 0)) {
            if (r.activityInfo != null && pkg.equals(r.activityInfo.packageName)) return true;
        }
        return false;
    }

    private long lastCheck;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastCheck != 0 && state != null && !state.locked()) {
                state.addWatched(Math.min(now - lastCheck, CHECK_MS * 2));
            }
            lastCheck = now;
            enforce(null);
            handler.postDelayed(this, CHECK_MS);
        }
    };

    /**
     * What the phone page shows under "Watching": the app on screen by its name, or @locked, @home or @tv (the
     * TV input app: Live TV and HDMI), which the page words in the phone's language.
     */
    static String nowShowing() {
        GuardService s = instance;
        if (s == null) return "";
        if (s.state.locked()) return "@locked";
        String pkg = s.lastForeground;
        if (pkg == null || s.isHome(pkg)) return "@home";
        if (s.isTvInput(pkg)) return "@tv";
        try {
            android.content.pm.PackageManager pm = s.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Called after the phone page or adb changed the state. */
    static void refresh() {
        GuardService s = instance;
        if (s != null) s.handler.post(() -> s.enforce(null));
    }

    /** The phone page moves to the port now stored (AdminReceiver SET_PORT). */
    static void rebindWeb() {
        GuardService s = instance;
        if (s != null && s.server != null) s.server.rebind();
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        state = new LockState(this);
        wasLocked = !state.locked();   // force the first enforce() to act either way
        ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).registerAudioDeviceCallback(deviceCallback, handler);
        countdown = new Countdown(this, state);
        server = new WebServer(this, state);
        server.start();
        handler.post(check);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (state == null || !state.locked()) return false;
        // The screensaver came up over the lock screen: let the key through to wake it, then cover the TV again.
        if (isDream(lastForeground)) {
            lastForeground = null;
            handler.postDelayed(this::showLock, 800);
            return false;
        }
        return true;
    }

    /** The screensaver (a dream) in front: its own package, from the TV's screensaver setting or a known one. */
    private boolean isDream(String pkg) {
        if (pkg == null) return false;
        try {
            String c = android.provider.Settings.Secure.getString(getContentResolver(), "screensaver_components");
            if (c != null) {
                for (String component : c.split(",")) if (component.startsWith(pkg + "/")) return true;
            }
        } catch (Exception ignored) {
            // hidden setting on some builds; fall back to the usual dreams
        }
        return pkg.equals("com.google.android.backdrop") || pkg.equals("com.google.android.apps.tv.dreamx")
                || pkg.equals("com.android.dreams.basic");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        enforce(pkg == null ? null : pkg.toString());
    }

    private void enforce(String foreground) {
        if (state == null) return;
        boolean locked = state.locked();
        if (locked != wasLocked) {
            wasLocked = locked;
            if (locked) lockNow();
            else unlockNow();
        }
        // Our own countdown pill reports a window change too; while unlocked it must not count as leaving Home.
        boolean ownOverlay = !locked && getPackageName().equals(foreground);
        if (foreground != null && !foreground.equals("com.android.systemui") && !ownOverlay) {
            lastForeground = foreground;
        }
        if (locked) {
            countdown.hide();
            if (isDream(lastForeground)) return;   // the next key wakes it (onKeyEvent), then the lock comes back
            // On a window change, and on every periodic check in case a window slipped in without an event.
            if (lastForeground == null || !lastForeground.equals(getPackageName())) showLock();
            return;
        }
        if (foreground != null && !ownOverlay && !foreground.equals("com.android.systemui")) switchApp(foreground);
        countdown.update(isHome(lastForeground));
        alertNearEnd(state.remainingMs());
        // lastForeground, not the event's package: the periodic check also has to close Settings when its five
        // minutes run out while someone sits still in it.
        if (lastForeground != null && !state.settingsOpen()) {
            for (String p : SETTINGS_PACKAGES) {
                if (p.equals(lastForeground)) {
                    lastForeground = null;   // until Home's window event arrives: one Home press, one toast
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    Toast.makeText(this, R.string.settings_locked, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * The countdown pill blinks at 5, 3 and 1 minutes left, once each. Only the last mark crossed fires (a
     * 3-minute window gets one alert, not three), and an extension that lifts the time back above a mark
     * re-arms it.
     */
    private void alertNearEnd(long left) {
        Integer crossed = null;
        for (int m : ALERT_MINUTES) {
            if (left > m * 60_000L) alerted.remove(m);
            else crossed = m;   // ALERT_MINUTES runs high to low, so this ends on the lowest mark crossed
        }
        if (crossed == null || alerted.contains(crossed)) return;
        for (int m : ALERT_MINUTES) if (m >= crossed) alerted.add(m);
        countdown.alert();
    }

    private void lockNow() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        leaveApp();
        // fixed-volume or do-not-disturb builds refuse; the lock screen still covers the picture
        setVolume(0);
        // No GLOBAL_ACTION_HOME here: Home lands after the lock screen and covers it.
        showLock();
    }

    private void unlockNow() {
        alerted.clear();
        setVolume(state.defaultVolume(output()));
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** "bt" while a Bluetooth speaker or headphones are connected (Android plays through them then), else "tv". */
    @SuppressWarnings("deprecation")
    private String output() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio.isBluetoothA2dpOn()) return "bt";
        for (android.media.AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return "bt";
        }
        return "tv";
    }

    /**
     * A speaker connecting or dropping mid-app: give the new output its own level for the app on screen (or its
     * default on Home). Delayed, because Android routes the stream a moment after the device appears and a
     * level set before that lands on the old output.
     */
    private final android.media.AudioDeviceCallback deviceCallback = new android.media.AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
            if (hasA2dp(added)) outputChanged();
        }

        @Override
        public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removed) {
            if (hasA2dp(removed)) outputChanged();
        }
    };

    private static boolean hasA2dp(android.media.AudioDeviceInfo[] devices) {
        for (android.media.AudioDeviceInfo d : devices) {
            if (d.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true;
        }
        return false;
    }

    private final Runnable applyOutput = () -> {
        if (state == null || state.locked()) return;
        currentOutput = output();
        setVolume(currentApp != null ? state.appVolume(currentOutput, currentApp)
                : state.defaultVolume(currentOutput));
    };

    private void outputChanged() {
        handler.removeCallbacks(applyOutput);
        handler.postDelayed(applyOutput, 1500);
    }

    /** Called on every foreground change while unlocked: remember the volume of the app left, apply the next one's. */
    private void switchApp(String pkg) {
        if (pkg == null || pkg.equals(currentApp)) return;
        leaveApp();
        if (isHome(pkg) || pkg.equals(getPackageName())) return;   // Home keeps whatever was playing before
        currentApp = pkg;
        currentOutput = output();
        setVolume(state.appVolume(currentOutput, pkg));
    }

    private void leaveApp() {
        if (currentApp == null) return;
        // A speaker connected or dropped mid-app: the level now belongs to the other output, so don't file it.
        if (output().equals(currentOutput)) {
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            state.saveAppVolume(currentOutput, currentApp, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
        currentApp = null;
    }

    /** -1 leaves the volume as it is (no starting level set). */
    private void setVolume(int volume) {
        if (volume < 0) return;
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        } catch (SecurityException ignored) {
        }
    }

    private void showLock() {
        Intent i = new Intent(this, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).unregisterAudioDeviceCallback(deviceCallback);
        if (countdown != null) countdown.hide();
        if (server != null) server.stop();
        super.onDestroy();
    }
}
