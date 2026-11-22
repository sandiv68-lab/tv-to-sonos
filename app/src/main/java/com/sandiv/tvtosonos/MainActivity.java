package com.sandiv.tvtosonos;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** One screen: a list of Sonos rooms. Tap one and the TV's sound goes there. */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1;
    private static final int REQ_PERMS = 2;

    private TextView statusView;
    private LinearLayout roomList;
    private final List<Button> roomButtons = new ArrayList<>();
    private List<Sonos.Room> rooms = new ArrayList<>();
    private Sonos.Room pending;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refreshStatus = new Runnable() {
        @Override
        public void run() {
            statusView.setText(StreamService.status);
            for (int i = 0; i < roomButtons.size() && i < rooms.size(); i++) {
                boolean on = StreamService.running && rooms.get(i).ip.equals(StreamService.currentIp);
                roomButtons.get(i).setText((on ? "▶  " : "") + rooms.get(i).name);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        askPermissions();
        findRooms();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refreshStatus);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refreshStatus);
    }

    // ---------- Layout ----------

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 20, 20));
        int pad = dp(48);
        root.setPadding(pad, dp(32), pad, dp(32));

        TextView title = new TextView(this);
        title.setText("TV to Sonos");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(170, 170, 170));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        statusView.setPadding(0, dp(8), 0, dp(20));
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(roomList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.START);
        bar.setPadding(0, dp(16), 0, 0);
        bar.addView(smallButton("Vol −", v -> volume(-4)));
        bar.addView(smallButton("Vol +", v -> volume(+4)));
        bar.addView(smallButton("Stop", v -> stopStreaming()));
        bar.addView(smallButton("Refresh", v -> findRooms()));
        root.addView(bar);
        return root;
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(12));
        b.setLayoutParams(lp);
        return b;
    }

    private void showRooms(List<Sonos.Room> found) {
        rooms = found;
        roomList.removeAllViews();
        roomButtons.clear();
        if (found.isEmpty()) {
            StreamService.status = "No Sonos found. Is the Google TV on the same Wi-Fi as the speakers?";
            return;
        }
        for (Sonos.Room r : found) {
            Button b = new Button(this);
            b.setText(r.name);
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            b.setOnClickListener(v -> choose(r));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            roomList.addView(b, lp);
            roomButtons.add(b);
        }
        roomButtons.get(0).requestFocus();
        if (!StreamService.running) StreamService.status = "Pick a room";
    }

    // ---------- Actions ----------

    private void findRooms() {
        if (!StreamService.running) StreamService.status = "Looking for Sonos speakers…";
        statusView.setText(StreamService.status);
        new Thread(() -> {
            List<Sonos.Room> found = Sonos.discover(this);
            ui.post(() -> showRooms(found));
        }).start();
    }

    private void choose(Sonos.Room room) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pending = room;
            askPermissions();
            return;
        }
        if (StreamService.running) {
            startService(new Intent(this, StreamService.class)
                    .setAction(StreamService.ACTION_SWITCH)
                    .putExtra(StreamService.EXTRA_IP, room.ip)
                    .putExtra(StreamService.EXTRA_NAME, room.name));
            return;
        }
        pending = room;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            StreamService.status = "Error: this Google TV doesn't allow apps to capture its audio";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE || pending == null) return;
        if (resultCode != RESULT_OK || data == null) {
            StreamService.status = "Permission refused. Pick the room again and choose Start/Allow.";
            return;
        }
        startForegroundService(new Intent(this, StreamService.class)
                .setAction(StreamService.ACTION_START)
                .putExtra(StreamService.EXTRA_CODE, resultCode)
                .putExtra(StreamService.EXTRA_DATA, data)
                .putExtra(StreamService.EXTRA_IP, pending.ip)
                .putExtra(StreamService.EXTRA_NAME, pending.name));
        pending = null;
    }

    private void stopStreaming() {
        if (StreamService.running) {
            startService(new Intent(this, StreamService.class).setAction(StreamService.ACTION_STOP));
        }
    }

    private void volume(int delta) {
        String ip = StreamService.currentIp;
        if (ip == null) return;
        new Thread(() -> {
            try {
                Sonos.changeVolume(ip, delta);
            } catch (IOException ignored) {
            }
        }).start();
    }

    // ---------- Permissions ----------

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            StreamService.status = "Error: the app needs the audio permission to send sound to Sonos";
        } else if (pending != null) {
            Sonos.Room r = pending;
            pending = null;
            choose(r);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
