package com.sandiv.tvtosonos;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One screen: tick the Sonos rooms you want, press Play, and the TV's sound goes there. */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1;
    private static final int REQ_PERMS = 2;

    // Button colours: bright amber = the one the remote is on, green = playing, grey = everything else.
    private static final int FOCUS = Color.rgb(255, 179, 0);
    private static final int PLAYING = Color.rgb(30, 110, 50);
    private static final int IDLE = Color.rgb(50, 50, 50);

    private static final int MIN_SCALE = 70, MAX_SCALE = 130, STEP = 10;

    private TextView statusView;
    private LinearLayout roomList;
    private final List<Button> roomButtons = new ArrayList<>();
    private List<Sonos.Room> rooms = new ArrayList<>();
    private final Set<String> ticked = new LinkedHashSet<>(); // room IPs, in the order they were ticked
    private boolean pendingPlay;
    private int scale = 100; // text size %, saved between uses
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refreshStatus = new Runnable() {
        @Override
        public void run() {
            statusView.setText(StreamService.status);
            updateRoomButtons();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = prefs().getInt("scale", 100);
        if (StreamService.running) ticked.addAll(StreamService.playingIps);
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

    private SharedPreferences prefs() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }

    // ---------- Layout ----------

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 20, 20));
        root.setPadding(dp(48), dp(28), dp(48), dp(24));

        TextView title = new TextView(this);
        title.setText("TV to WiFi Play");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text(title, 32);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(170, 170, 170));
        text(statusView, 18);
        statusView.setPadding(0, dp(6), 0, dp(16));
        statusView.setText(StreamService.status);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(roomList);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(14), 0, 0);
        bar.addView(smallButton("▶ Play", v -> play()));
        bar.addView(smallButton("Stop", v -> stopStreaming()));
        bar.addView(smallButton("Vol −", v -> volume(-4)));
        bar.addView(smallButton("Vol +", v -> volume(+4)));
        bar.addView(smallButton("Refresh", v -> findRooms()));
        bar.addView(smallButton("A−", v -> changeScale(-STEP)));
        TextView pct = new TextView(this);
        pct.setText(scale + "%");
        pct.setTextColor(Color.rgb(170, 170, 170));
        text(pct, 16);
        pct.setPadding(0, 0, dp(12), 0);
        bar.addView(pct);
        bar.addView(smallButton("A+", v -> changeScale(+STEP)));
        root.addView(bar);

        TextView credit = new TextView(this);
        credit.setText("Made by Sandiv68");
        credit.setTextColor(Color.rgb(120, 120, 120));
        text(credit, 12);
        credit.setGravity(Gravity.END);
        credit.setPadding(0, dp(8), 0, 0);
        root.addView(credit);
        return root;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTag(R.id.label_tag, label);
        b.setAllCaps(false);
        text(b, 18);
        style(b, false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        b.setLayoutParams(lp);
        return b;
    }

    private void showRooms(List<Sonos.Room> found) {
        rooms = found;
        roomList.removeAllViews();
        roomButtons.clear();
        if (found.isEmpty()) {
            StreamService.status = "No Sonos found. Is the Google TV on the same Wi-Fi as the speakers?";
            statusView.setText(StreamService.status);
            return;
        }
        for (Sonos.Room r : found) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            text(b, 22);
            style(b, false);
            b.setOnClickListener(v -> toggle(r));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            roomList.addView(b, lp);
            roomButtons.add(b);
        }
        updateRoomButtons();
        if (getCurrentFocus() == null) roomButtons.get(0).requestFocus();
        if (!StreamService.running) {
            StreamService.status = "Tick the rooms you want, then press Play";
            statusView.setText(StreamService.status);
        }
    }

    /** ☑/☐ shows what's ticked; green background shows what's actually playing. */
    private void updateRoomButtons() {
        Set<String> playing = StreamService.running ? StreamService.playingIps : java.util.Collections.emptySet();
        for (int i = 0; i < roomButtons.size() && i < rooms.size(); i++) {
            Sonos.Room r = rooms.get(i);
            Button b = roomButtons.get(i);
            boolean on = playing.contains(r.ip);
            b.setText((ticked.contains(r.ip) ? "☑  " : "☐  ") + r.name + (on ? "   ▶ playing" : ""));
            if (!Boolean.valueOf(on).equals(b.getTag())) style(b, on);
        }
    }

    // ---------- Actions ----------

    private void findRooms() {
        if (!StreamService.running) {
            StreamService.status = "Looking for Sonos speakers…";
            statusView.setText(StreamService.status);
        }
        new Thread(() -> {
            List<Sonos.Room> found = Sonos.discover(this);
            ui.post(() -> showRooms(found));
        }).start();
    }

    private void toggle(Sonos.Room r) {
        if (!ticked.remove(r.ip)) ticked.add(r.ip);
        updateRoomButtons();
        if (StreamService.running) {
            StreamService.status = "Press Play to update the rooms";
            statusView.setText(StreamService.status);
        }
    }

    private void play() {
        List<Sonos.Room> chosen = chosenRooms();
        if (chosen.isEmpty()) {
            if (StreamService.running) {
                stopStreaming();
            } else {
                StreamService.status = "Tick at least one room first";
                statusView.setText(StreamService.status);
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPlay = true;
            askPermissions();
            return;
        }
        if (StreamService.running) {
            startService(withRooms(new Intent(this, StreamService.class).setAction(StreamService.ACTION_SET), chosen));
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            StreamService.status = "Error: this Google TV doesn't allow apps to capture its audio";
        }
    }

    private List<Sonos.Room> chosenRooms() {
        List<Sonos.Room> out = new ArrayList<>();
        for (String ip : ticked) {
            for (Sonos.Room r : rooms) {
                if (r.ip.equals(ip)) out.add(r);
            }
        }
        return out;
    }

    private static Intent withRooms(Intent i, List<Sonos.Room> chosen) {
        ArrayList<String> ips = new ArrayList<>(), uuids = new ArrayList<>(), names = new ArrayList<>();
        for (Sonos.Room r : chosen) {
            ips.add(r.ip);
            uuids.add(r.uuid);
            names.add(r.name);
        }
        return i.putStringArrayListExtra(StreamService.EXTRA_IPS, ips)
                .putStringArrayListExtra(StreamService.EXTRA_UUIDS, uuids)
                .putStringArrayListExtra(StreamService.EXTRA_NAMES, names);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        List<Sonos.Room> chosen = chosenRooms();
        if (resultCode != RESULT_OK || data == null) {
            StreamService.status = "Permission refused. Press Play again and choose Start/Allow.";
            return;
        }
        if (chosen.isEmpty()) return;
        startForegroundService(withRooms(new Intent(this, StreamService.class)
                .setAction(StreamService.ACTION_START)
                .putExtra(StreamService.EXTRA_CODE, resultCode)
                .putExtra(StreamService.EXTRA_DATA, data), chosen));
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

    /** A− / A+: text size between 70% and 130%, remembered for next time. */
    private void changeScale(int delta) {
        int next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale + delta));
        if (next == scale) return;
        scale = next;
        prefs().edit().putInt("scale", scale).apply();
        String focusLabel = delta < 0 ? "A−" : "A+";
        setContentView(buildLayout());
        showRooms(rooms);
        View again = findButton(getWindow().getDecorView(), focusLabel);
        if (again != null) again.requestFocus();
    }

    private View findButton(View v, String label) {
        if (v instanceof Button && label.equals(v.getTag(R.id.label_tag))) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findButton(g.getChildAt(i), label);
                if (f != null) return f;
            }
        }
        return null;
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
        } else if (pendingPlay) {
            pendingPlay = false;
            play();
        }
    }

    // ---------- Looks ----------

    /** Grey normally, green while playing, bright amber with black text when the remote is on it. */
    private void style(Button b, boolean playing) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(FOCUS));
        bg.addState(new int[]{android.R.attr.state_pressed}, rounded(FOCUS));
        bg.addState(new int[]{}, rounded(playing ? PLAYING : IDLE));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{Color.BLACK, Color.BLACK, Color.WHITE}));
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setTag(playing);
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        return d;
    }

    /** Sets text size scaled by the A−/A+ setting. */
    private void text(TextView v, float sp) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale / 100f);
    }

    /** dp, also scaled so padding shrinks/grows with the text. */
    private int dp(int v) {
        return Math.round(v * scale / 100f * getResources().getDisplayMetrics().density);
    }
}
