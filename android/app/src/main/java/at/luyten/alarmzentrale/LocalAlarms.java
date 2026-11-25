package at.luyten.alarmzentrale;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Lokaler Wecker – funktioniert komplett ohne Internet.
 * Jede Weckzeit wird als System-Wecker (setAlarmClock) gestellt: exakt, auch im Tiefschlaf
 * und nach Neustart (wird beim Hochfahren neu gestellt, schon vor dem Entsperren).
 */
public class LocalAlarms extends BroadcastReceiver {
    private static final String ACTION = "at.luyten.alarmzentrale.LOCAL_ALARM";
    public static final String[] DAY_NAMES = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};

    public static class Alarm {
        public int id, hour, minute, days; // days: Bit 0 = Mo … Bit 6 = So; 0 = einmalig
        public boolean on;

        public String time() { return String.format(Locale.GERMANY, "%02d:%02d", hour, minute); }

        public String daysText() {
            if (days == 0) return "einmalig";
            if (days == 0b1111111) return "täglich";
            if (days == 0b0011111) return "Mo–Fr";
            if (days == 0b1100000) return "Sa, So";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7; i++) if ((days & (1 << i)) != 0) { if (sb.length() > 0) sb.append(", "); sb.append(DAY_NAMES[i]); }
            return sb.toString();
        }
    }

    // ---------- Speichern ----------
    public static List<Alarm> load(Context c) {
        List<Alarm> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.prefs(c).getString("alarms", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Alarm al = new Alarm();
                al.id = o.getInt("id"); al.hour = o.getInt("h"); al.minute = o.getInt("m");
                al.days = o.optInt("d", 0); al.on = o.optBoolean("on", true);
                l.add(al);
            }
        } catch (Exception ignored) {}
        l.sort((x, y) -> (x.hour * 60 + x.minute) - (y.hour * 60 + y.minute));
        return l;
    }

    public static void save(Context c, List<Alarm> l) {
        JSONArray a = new JSONArray();
        try {
            for (Alarm al : l) {
                JSONObject o = new JSONObject();
                o.put("id", al.id); o.put("h", al.hour); o.put("m", al.minute); o.put("d", al.days); o.put("on", al.on);
                a.put(o);
            }
        } catch (Exception ignored) {}
        Store.prefs(c).edit().putString("alarms", a.toString()).commit();
        scheduleAll(c);
    }

    public static int newId(List<Alarm> l) {
        int max = 0;
        for (Alarm a : l) max = Math.max(max, a.id);
        return max + 1;
    }

    // ---------- Zeitberechnung ----------
    public static long next(Alarm a, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, a.hour);
        c.set(Calendar.MINUTE, a.minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (c.getTimeInMillis() > now) {
                if (a.days == 0) return c.getTimeInMillis();
                int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Mo = 0 … So = 6
                if ((a.days & (1 << dow)) != 0) return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, a.hour); // Sommerzeit-Wechsel
            c.set(Calendar.MINUTE, a.minute);
        }
        return -1;
    }

    /** Nächster aktiver Wecker (für die Anzeige), oder null. */
    public static Alarm nextAlarm(Context c) {
        long now = System.currentTimeMillis(), best = Long.MAX_VALUE;
        Alarm res = null;
        for (Alarm a : load(c)) {
            if (!a.on) continue;
            long t = next(a, now);
            if (t > 0 && t < best) { best = t; res = a; }
        }
        return res;
    }

    // ---------- Stellen ----------
    private static PendingIntent pi(Context c, int id) {
        Intent i = new Intent(c, LocalAlarms.class).setAction(ACTION).putExtra("id", id);
        return PendingIntent.getBroadcast(c, 1000 + id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void scheduleAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        // alte Einträge entfernen (auch gelöschte Wecker)
        int known = Store.prefs(c).getInt("alarm_max_id", 0);
        List<Alarm> l = load(c);
        int max = Math.max(known, newId(l));
        for (int id = 1; id <= max; id++) am.cancel(pi(c, id));
        Store.prefs(c).edit().putInt("alarm_max_id", max).apply();

        long now = System.currentTimeMillis();
        PendingIntent show = PendingIntent.getActivity(c, 6, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        for (Alarm a : l) {
            if (!a.on) continue;
            long t = next(a, now);
            if (t <= 0) continue;
            try {
                if (KeepAlive.canSchedule(c)) am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, show), pi(c, a.id));
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, a.id));
            } catch (Exception e) {
                try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, a.id)); } catch (Exception ignored) {}
            }
        }
    }

    // ---------- Auslösen ----------
    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, PollService.wakeTag("wecker")).acquire(30_000);
        } catch (Exception ignored) {}

        int id = intent.getIntExtra("id", -1);
        List<Alarm> l = load(c);
        Alarm hit = null;
        for (Alarm a : l) if (a.id == id) hit = a;
        if (hit == null || !hit.on) { scheduleAll(c); return; }

        if (hit.days == 0) { hit.on = false; } // einmaliger Wecker schaltet sich ab
        save(c, l); // stellt auch den nächsten Termin

        // lokale IDs sind negativ → werden nicht an den Server gemeldet
        Store.activate(c, -(System.currentTimeMillis() / 1000), Store.TYPE_WAKE);
        Store.prefs(c).edit().putString("label", "Wecker " + hit.time()).commit();
        Intent s = new Intent(c, PollService.class).setAction(PollService.ACTION_RING);
        try { c.startForegroundService(s); } catch (Exception e) {
            PollService ps = PollService.instance;
            if (ps != null) ps.kickRinging();
        }
    }
}
