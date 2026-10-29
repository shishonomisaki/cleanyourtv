package io.github.shishonomisaki.parentlock;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Shows the address a parent types into the phone's browser. Reachable only while the TV is unlocked. */
public class InfoActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF10121C);

        String address = tailscaleAddress();
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(40);

        TextView body = new TextView(this);
        body.setTextColor(0xFFC9CCDC);
        body.setTextSize(24);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 32, 0, 0);
        body.setText(address == null
                ? getString(R.string.info_no_tailscale)
                : getString(R.string.info_open, "http://" + address + ":" + new LockState(this).port()));

        root.addView(title);
        root.addView(body);
        setContentView(root);
    }

    static String tailscaleAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && WebServer.isTailnet(a)) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
