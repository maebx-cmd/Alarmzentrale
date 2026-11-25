package at.luyten.alarmzentrale;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Dauerläufer: fragt alle 5 s beim Worker nach, löst den Alarm aus und hält ihn am Leben. */
public class PollService extends Service {
    public static final String ACTION_STOP_ALARM = "at.luyten.alarmzentrale.STOP";
    public static final String ACTION_TEST = "at.luyten.alarmzentrale.TEST";
    public static final String EXTRA_TYPE = "type";

    static final String CH_SERVICE = "verbindung";
    static final String CH_ALARM = "alarm";
    static final int NOTI_SERVICE = 1;
    static final int NOTI_ALARM = 2;

    public static volatile PollService instance;
    public static volatile String statusText = "Startet …";
    public static volatile long lastOk = 0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private Thread pollThread;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private AlarmPlayer player;
    private boolean lastShownOnline = false;
    private long testId = -1;

    public static void start(Context c) {
        Intent i = new Intent(c, PollService.class);
        try { c.startForegroundService(i); } catch (Exception e) { try { c.startService(i); } catch (Exception ignored) {} }
    }

    public AlarmPlayer player() { return player; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
        Notification n = serviceNotification("Verbinde mit der Alarmzentrale …");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTI_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTI_SERVICE, n);

        player = new AlarmPlayer(this);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeTag("poll"));
        cpuLock.setReferenceCounted(false);
        cpuLock.acquire();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            //noinspection deprecation
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "alarmzentrale:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {}

        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, f);

        running = true;
        KeepAlive.schedule(this);
        pollThread = new Thread(this::pollLoop, "alarm-poll");
        pollThread.start();

        if (Store.active(this)) startRinging(); // z. B. nach Neustart während eines Alarms
    }

    /** Huawei/Honor (PowerGenie) beendet Apps mit Dauer-Wakelock – außer bei bestimmten System-Tags. */
    static String wakeTag(String what) {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        if (m.contains("huawei") || m.contains("honor")) return "LocationManagerService";
        return "alarmzentrale:" + what;
    }

    private final Object sleeper = new Object();

    /** Sofort eine Abfrage auslösen (vom Wachhalter). */
    public void kick() {
        try { if (cpuLock != null && !cpuLock.isHeld()) cpuLock.acquire(); } catch (Exception ignored) {}
        synchronized (sleeper) { sleeper.notifyAll(); }
        if (pollThread == null || !pollThread.isAlive()) {
            pollThread = new Thread(this::pollLoop, "alarm-poll");
            pollThread.start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_ALARM.equals(intent.getAction())) {
            stopAlarm("stopped");
        } else if (intent != null && ACTION_TEST.equals(intent.getAction())) {
            String t = intent.getStringExtra(EXTRA_TYPE);
            Store.activate(this, testId--, Store.TYPE_ALARM.equals(t) ? Store.TYPE_ALARM : Store.TYPE_WAKE);
            startRinging();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
        if (player != null) player.stop();
        try { if (cpuLock != null) cpuLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null) wifiLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        start(this); // App aus „Zuletzt verwendet“ gewischt → weiterlaufen
        super.onTaskRemoved(rootIntent);
    }

    // ================= Server-Kommunikation =================
    private void pollLoop() {
        while (running) {
            long t0 = SystemClock.elapsedRealtime();
            try {
                flushAcks();
                poll();
                lastOk = System.currentTimeMillis();
                statusText = "Verbunden – Alarmierung möglich";
                updateServiceNotification(true);
            } catch (Exception e) {
                statusText = "Keine Verbindung: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                updateServiceNotification(false);
            }
            // Wachhalter nach hinten schieben: er feuert nur, wenn diese Schleife im Tiefschlaf hängen bleibt
            KeepAlive.scheduleIfDue(this);
            long wait = Store.POLL_MS - (SystemClock.elapsedRealtime() - t0);
            if (wait > 0) synchronized (sleeper) { try { sleeper.wait(wait); } catch (InterruptedException ignored) {} }
        }
    }

    private void poll() throws Exception {
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int pct = -1; boolean chg = false;
        if (bat != null) {
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (lvl >= 0 && sc > 0) pct = Math.round(lvl * 100f / sc);
            chg = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        String q = "/api/poll?key=" + Store.DEVICE_KEY + "&bat=" + pct + "&chg=" + (chg ? 1 : 0)
                + "&ringing=" + (Store.active(this) ? 1 : 0);
        JSONObject r = new JSONObject(http("GET", Store.url(this) + q, null));
        if (!r.isNull("cmd")) {
            JSONObject c = r.getJSONObject("cmd");
            long id = c.getLong("id");
            String type = Store.TYPE_ALARM.equals(c.optString("type")) ? Store.TYPE_ALARM : Store.TYPE_WAKE;
            if (!Store.hasId(this, id)) {
                Store.activate(this, id, type);
                main.post(this::startRinging);
            }
            Store.queueAck(this, id, "received");
            flushAcks();
        }
        // Notfall-Storno aus der Ferne
        org.json.JSONArray cancel = r.optJSONArray("cancel");
        if (cancel != null && cancel.length() > 0) {
            boolean hit = false;
            for (int k = 0; k < cancel.length(); k++) {
                long id = cancel.getLong(k);
                if (Store.hasId(this, id)) hit = true;
                else Store.queueAck(this, id, "cancelled");
            }
            if (hit) main.post(() -> stopAlarm("cancelled"));
            else flushAcks();
        }
    }

    private void flushAcks() throws Exception {
        String all = Store.acks(this);
        if (all.isEmpty()) return;
        for (String e : all.split(";")) {
            if (e.isEmpty()) continue;
            String[] p = e.split(":");
            if (p.length < 3) { Store.removeAck(this, e); continue; }
            JSONObject b = new JSONObject();
            b.put("key", Store.DEVICE_KEY);
            b.put("id", Long.parseLong(p[0]));
            b.put("state", p[1]);
            b.put("t", Long.parseLong(p[2]));
            http("POST", Store.url(this) + "/api/ack", b.toString());
            Store.removeAck(this, e);
        }
    }

    private static String http(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) {
                byte[] b = new byte[4096]; int n;
                while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                is.close();
            }
            String s = bo.toString("UTF-8");
            if (code >= 400) throw new Exception("HTTP " + code);
            return s;
        } finally {
            c.disconnect();
        }
    }

    // ================= Alarm =================
    void startRinging() {
        if (!Store.active(this)) return;
        player.setType(Store.type(this));
        player.start(Store.type(this));
        postAlarmNotification();
        launchAlarmUi();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 1000);
    }

    void stopAlarm(String state) {
        String ids = Store.clear(this);
        for (String s : ids.split(",")) {
            try { Store.queueAck(this, Long.parseLong(s), state); } catch (Exception ignored) {}
        }
        main.removeCallbacks(watchdog);
        player.stop();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTI_ALARM);
        new Thread(() -> { try { flushAcks(); } catch (Exception ignored) {} }).start();
    }

    /** Hält Bildschirm und Alarmanzeige vorne: Sperrtaste, Home-Taste, Wegwischen – alles wird zurückgeholt. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!Store.active(PollService.this)) return;
            player.setType(Store.type(PollService.this));
            if (!player.isPlaying()) player.start(Store.type(PollService.this));
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isInteractive()) wakeScreen();
            else if (!AlarmActivity.visible && SystemClock.elapsedRealtime() > AlarmActivity.suppressUntil) launchAlarmUi();
            main.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Store.active(context)) main.postDelayed(PollService.this::wakeScreen, 300);
        }
    };

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        if (!Store.active(this)) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "alarmzentrale:screen");
            wl.acquire(4000);
        } catch (Exception ignored) {}
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(NOTI_ALARM);
        postAlarmNotification();
        launchAlarmUi();
    }

    private void launchAlarmUi() {
        try {
            Intent i = new Intent(this, AlarmActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    // ================= Benachrichtigungen =================
    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Verbindung zur Zentrale", NotificationManager.IMPORTANCE_LOW);
        s.setShowBadge(false);
        nm.createNotificationChannel(s);
        NotificationChannel a = new NotificationChannel(CH_ALARM, "Alarm", NotificationManager.IMPORTANCE_HIGH);
        a.setSound(null, null);
        a.enableVibration(false);
        a.setBypassDnd(true);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);
    }

    private Notification serviceNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Alarmzentrale")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void updateServiceNotification(boolean online) {
        if (online == lastShownOnline) return;
        lastShownOnline = online;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTI_SERVICE, serviceNotification(online ? "Verbunden – Alarmierung möglich" : "Keine Verbindung zur Zentrale"));
    }

    private void postAlarmNotification() {
        boolean alarm = Store.TYPE_ALARM.equals(Store.type(this));
        Intent i = new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(alarm ? "ALARM" : "WECKEN")
                .setContentText(alarm ? "Alarm ausgelöst – QR-Code scannen zum Abschalten" : "Weckruf – QR-Code scannen zum Abschalten")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setColor(alarm ? 0xFFE5322D : 0xFF2F7FE0)
                .build();
        n.flags |= Notification.FLAG_INSISTENT | Notification.FLAG_NO_CLEAR;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTI_ALARM, n);
    }
}
