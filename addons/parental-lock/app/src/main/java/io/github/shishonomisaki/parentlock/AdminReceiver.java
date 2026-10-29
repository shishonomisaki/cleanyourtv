package io.github.shishonomisaki.parentlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Set the PIN, or unlock and lock for testing and recovery, from the adb shell only (the manifest requires
 * WRITE_SECURE_SETTINGS, which ordinary apps cannot hold):
 *   am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a SET_PIN --es pin 123456
 *   am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a UNLOCK --ei minutes 30
 *   am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a LOCK
 *   am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a SETTINGS --ei minutes 5
 *   am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a SET_VOLUME --es output tv --ei volume 13
 *       starting level on the TV's speakers (output bt: a Bluetooth speaker); add --es app <package> for one
 *       app; --ei volume -1 removes it. Unset levels leave the volume alone.
 *   am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a RESET_VOLUMES   (forget every app's level)
 *   am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a PORT            (prints the phone page's port)
 *   am broadcast -n io.github.shishonomisaki.parentlock/.AdminReceiver -a SET_PORT --ei port <1024-65535>
 */
public class AdminReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LockState state = new LockState(context);
        String action = intent.getAction();
        if ("SET_PIN".equals(action)) {
            String pin = intent.getStringExtra("pin");
            if (pin != null && pin.matches("\\d{6}")) state.setPin(pin);
        } else if ("UNLOCK".equals(action)) {
            state.unlock(intent.getIntExtra("minutes", 30));
            GuardService.refresh();
        } else if ("SETTINGS".equals(action)) {
            state.openSettings(intent.getIntExtra("minutes", 5));
            GuardService.refresh();
        } else if ("SET_VOLUME".equals(action)) {
            String output = intent.getStringExtra("output");
            if ("tv".equals(output) || "bt".equals(output)) {
                state.setStartVolume(output, intent.getStringExtra("app"), intent.getIntExtra("volume", -1));
            }
        } else if ("RESET_VOLUMES".equals(action)) {
            state.resetVolumes();
        } else if ("SET_PORT".equals(action)) {
            int port = intent.getIntExtra("port", 0);
            if (port >= 1024 && port <= 65535) {
                state.setPort(port);
                GuardService.rebindWeb();
            }
            setResultData(String.valueOf(state.port()));
        } else if ("PORT".equals(action)) {
            setResultData(String.valueOf(state.port()));
        } else if ("LOCK".equals(action)) {
            state.lock();
            GuardService.refresh();
        }
    }
}
