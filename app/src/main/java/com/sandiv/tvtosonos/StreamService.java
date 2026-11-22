package com.sandiv.tvtosonos;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures the Google TV's audio, encodes it as an AAC stream, serves it over HTTP
 * on the local network, and tells the chosen Sonos room to play that stream.
 */
public class StreamService extends Service {

    public static final String ACTION_START = "start";
    public static final String ACTION_SWITCH = "switch";
    public static final String ACTION_STOP = "stop";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_NAME = "name";

    // Read by the screen to show what's happening.
    public static volatile String status = "Pick a room";
    public static volatile boolean running = false;
    public static volatile String currentIp = null;

    private static final int RATE = 44100;

    private MediaProjection projection;
    private volatile boolean active;
    private Thread captureThread;
    private ServerSocket server;
    private int port;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger streamRequests = new AtomicInteger();
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Object sonosLock = new Object();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        String ip = intent.getStringExtra(EXTRA_IP);
        String name = intent.getStringExtra(EXTRA_NAME);

        if (ACTION_SWITCH.equals(action) && active) {
            new Thread(() -> connectSonos(ip, name)).start();
            return START_NOT_STICKY;
        }

        // ACTION_START: must be in the foreground before touching MediaProjection.
        startForeground(1, notification("Connecting to " + name + "…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            fail("Google TV refused audio capture");
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                shutdown();
            }
        }, new Handler(Looper.getMainLooper()));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvtosonos:stream");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvtosonos:wifi");
            wifiLock.acquire();
        }

        active = true;
        running = true;
        try {
            startServer();
        } catch (IOException e) {
            fail("Couldn't open the stream (" + e.getMessage() + ")");
            return START_NOT_STICKY;
        }
        captureThread = new Thread(this::captureLoop, "capture");
        captureThread.start();
        new Thread(() -> connectSonos(ip, name)).start();
        return START_NOT_STICKY;
    }

    // ---------- Sonos ----------

    private void connectSonos(String ip, String name) {
        synchronized (sonosLock) {
            if (currentIp != null && !currentIp.equals(ip)) Sonos.stop(currentIp);
            currentIp = ip;
            setStatus("Connecting to " + name + "…");
            String tvIp;
            try {
                tvIp = Sonos.localIpFor(ip);
            } catch (IOException e) {
                setStatus("Can't reach " + name + " on this Wi-Fi");
                return;
            }
            String hostPath = tvIp + ":" + port + "/tv.aac";
            // Sonos accepts live streams in a few URL styles; try each until one plays.
            String[] uris = {
                    "x-rincon-mp3radio://" + hostPath,
                    "aac://http://" + hostPath,
                    "http://" + hostPath
            };
            String lastError = "";
            for (String uri : uris) {
                if (!active || !ip.equals(currentIp)) return;
                int before = streamRequests.get();
                try {
                    Sonos.play(ip, uri);
                } catch (IOException e) {
                    lastError = e.getMessage();
                    continue;
                }
                for (int i = 0; i < 8; i++) {
                    sleep(1000);
                    String st = Sonos.state(ip);
                    if ("PLAYING".equals(st) && streamRequests.get() > before) {
                        setStatus("Playing on " + name);
                        return;
                    }
                    if ("STOPPED".equals(st) && i >= 3) break;
                }
                Sonos.stop(ip);
            }
            setStatus("Sonos wouldn't play the stream " + lastError);
        }
    }

    // ---------- Audio capture + AAC encoding ----------

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before starting.
    private void captureLoop() {
        AudioRecord record = null;
        MediaCodec codec = null;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min * 4, 32768))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();

            MediaFormat mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
            mf.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            mf.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
            mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            record.startRecording();

            byte[] pcm = new byte[4096];
            long ptsUs = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (active) {
                int n = record.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                int offset = 0;
                while (offset < n && active) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(in);
                        ib.clear();
                        int len = Math.min(n - offset, ib.remaining());
                        ib.put(pcm, offset, len);
                        codec.queueInputBuffer(in, 0, len, ptsUs, 0);
                        ptsUs += len * 1_000_000L / (RATE * 4);
                        offset += len;
                    }
                    drain(codec, info);
                }
            }
        } catch (Exception e) {
            if (active) fail("Audio capture stopped (" + e.getMessage() + ")");
        } finally {
            try { if (record != null) { record.stop(); record.release(); } } catch (Exception ignored) { }
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) { }
        }
    }

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info) {
        int out;
        while ((out = codec.dequeueOutputBuffer(info, 0)) >= 0) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                ByteBuffer ob = codec.getOutputBuffer(out);
                byte[] frame = new byte[info.size + 7];
                writeAdtsHeader(frame, frame.length);
                ob.position(info.offset);
                ob.get(frame, 7, info.size);
                for (Client c : clients) c.offer(frame);
            }
            codec.releaseOutputBuffer(out, false);
        }
    }

    /** 7-byte ADTS header so each AAC frame can be played as a live stream (AAC-LC, 44.1 kHz, stereo). */
    private static void writeAdtsHeader(byte[] p, int len) {
        int profile = 2, freqIdx = 4, chan = 2;
        p[0] = (byte) 0xFF;
        p[1] = (byte) 0xF1;
        p[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chan >> 2));
        p[3] = (byte) (((chan & 3) << 6) + (len >> 11));
        p[4] = (byte) ((len & 0x7FF) >> 3);
        p[5] = (byte) (((len & 7) << 5) + 0x1F);
        p[6] = (byte) 0xFC;
    }

    // ---------- Tiny HTTP server ----------

    private void startServer() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(8090));
        } catch (IOException busy) {
            server.bind(new InetSocketAddress(0));
        }
        port = server.getLocalPort();
        Thread t = new Thread(() -> {
            while (active) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> handle(s), "http-client").start();
                } catch (IOException e) {
                    if (!active) return;
                }
            }
        }, "http");
        t.start();
    }

    private void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            String request = readHeaders(s.getInputStream());
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: audio/aac\r\n"
                    + "Cache-Control: no-cache, no-store\r\n"
                    + "icy-name: Google TV\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (request.startsWith("HEAD")) {
                s.close();
                return;
            }
            streamRequests.incrementAndGet();
            Client c = new Client(s, out);
            clients.add(c);
            c.run(); // blocks until the speaker disconnects
            clients.remove(c);
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0 && sb.length() < 8192) {
            sb.append((char) b);
            if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) break;
        }
        return sb.toString();
    }

    /** One connected speaker. Frames queue up here so a slow connection never stalls the capture. */
    private final class Client {
        final Socket socket;
        final OutputStream out;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(400);

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        void offer(byte[] frame) {
            if (!queue.offer(frame)) {
                queue.poll();
                queue.offer(frame);
            }
        }

        void run() throws IOException {
            while (active && !socket.isClosed()) {
                try {
                    byte[] f = queue.poll(2, TimeUnit.SECONDS);
                    if (f != null) out.write(f);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // ---------- Housekeeping ----------

    private void shutdown() {
        boolean wasActive = active;
        active = false;
        running = false;
        String ip = currentIp;
        currentIp = null;
        if (wasActive && ip != null) new Thread(() -> Sonos.stop(ip)).start();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        for (Client c : clients) {
            try { c.socket.close(); } catch (IOException ignored) { }
        }
        clients.clear();
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            try { p.stop(); } catch (Exception ignored) { }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (!status.startsWith("Error")) status = "Stopped";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void fail(String message) {
        status = "Error: " + message;
        shutdown();
    }

    private void setStatus(String s) {
        status = s;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && active) nm.notify(1, notification(s));
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("stream", "Streaming", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, StreamService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "stream")
                .setContentTitle("TV to Sonos")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    @Override
    public void onDestroy() {
        if (active) shutdown();
        super.onDestroy();
    }
}
