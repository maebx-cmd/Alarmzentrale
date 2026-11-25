package at.luyten.alarmzentrale;

import android.content.Context;
import android.content.SharedPreferences;

/** Einstellungen und Alarmzustand – im gerätegeschützten Speicher, damit alles schon vor dem ersten Entsperren nach einem Neustart läuft. */
public final class Store {
    public static final String DEVICE_KEY = "M6e_iIBbd2IgRQS8KQtDMOhF";
    public static final String DEFAULT_URL = "https://alarmzentrale.ma-eberharter-94.workers.dev";
    public static final String DEFAULT_STOP_CODE = "AZ-STOP-uSk2NyKwCO7g";
    public static final long POLL_MS = 5000;

    public static final String TYPE_ALARM = "alarm";
    public static final String TYPE_WAKE = "wake";

    private Store() {}

    public static SharedPreferences prefs(Context c) {
        Context dp = c.getApplicationContext().createDeviceProtectedStorageContext();
        return dp.getSharedPreferences("alarmzentrale", Context.MODE_PRIVATE);
    }

    public static String url(Context c) {
        String u = prefs(c).getString("url", DEFAULT_URL).trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public static String stopCode(Context c) { return prefs(c).getString("stop", DEFAULT_STOP_CODE); }

    // ---- Alarmzustand ----
    public static boolean active(Context c) { return prefs(c).getBoolean("active", false); }
    public static String type(Context c) { return prefs(c).getString("type", TYPE_WAKE); }
    public static long since(Context c) { return prefs(c).getLong("since", 0); }
    public static String label(Context c) { return prefs(c).getString("label", ""); }
    public static String ids(Context c) { return prefs(c).getString("ids", ""); }

    public static boolean hasId(Context c, long id) {
        for (String s : ids(c).split(",")) if (s.equals(String.valueOf(id))) return true;
        return false;
    }

    /** Startet bzw. erweitert einen Alarm. Alarm hat Vorrang vor Wecken. */
    public static synchronized void activate(Context c, long id, String type) {
        SharedPreferences p = prefs(c);
        boolean wasActive = p.getBoolean("active", false);
        String newType = type;
        if (wasActive && TYPE_ALARM.equals(p.getString("type", TYPE_WAKE))) newType = TYPE_ALARM;
        String ids = p.getString("ids", "");
        if (!hasId(c, id)) ids = ids.isEmpty() ? String.valueOf(id) : ids + "," + id;
        p.edit().putBoolean("active", true)
                .putString("type", newType)
                .putLong("since", wasActive ? p.getLong("since", System.currentTimeMillis()) : System.currentTimeMillis())
                .putString("ids", ids)
                .commit();
    }

    public static synchronized String clear(Context c) {
        String ids = ids(c);
        prefs(c).edit().putBoolean("active", false).putString("ids", "").remove("label").commit();
        return ids;
    }

    // ---- Bestätigungen an den Server (werden bei Verbindungsfehlern nachgereicht) ----
    public static synchronized void queueAck(Context c, long id, String state) {
        if (id <= 0) return;
        String q = prefs(c).getString("acks", "");
        String e = id + ":" + state + ":" + System.currentTimeMillis();
        prefs(c).edit().putString("acks", q.isEmpty() ? e : q + ";" + e).commit();
    }

    public static synchronized String acks(Context c) { return prefs(c).getString("acks", ""); }

    public static synchronized void removeAck(Context c, String entry) {
        StringBuilder sb = new StringBuilder();
        for (String s : acks(c).split(";")) {
            if (s.isEmpty() || s.equals(entry)) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(s);
        }
        prefs(c).edit().putString("acks", sb.toString()).commit();
    }
}
