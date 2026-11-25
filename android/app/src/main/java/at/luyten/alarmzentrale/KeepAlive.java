package at.luyten.alarmzentrale;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

/**
 * Wachhalter über den System-Wecker (setAlarmClock): wird auch im Tiefschlaf und
 * von Huawei/EMUI zuverlässig ausgelöst, weckt das Gerät alle 30 s, startet den
 * Dienst neu falls er beendet wurde und löst sofort eine Abfrage aus.
 */
public class KeepAlive extends BroadcastReceiver {
    public static final long INTERVAL_MS = 30_000;
    private static final String ACTION = "at.luyten.alarmzentrale.KEEPALIVE";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, PollService.wakeTag("kick"));
            wl.acquire(15_000); // Zeit für eine Abfrage, auch wenn der Dienst-Wakelock vom System ignoriert wird
        } catch (Exception ignored) {}
        schedule(c);
        PollService s = PollService.instance;
        if (s == null) PollService.start(c);
        else s.kick();
    }

    public static boolean canSchedule(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am.canScheduleExactAlarms();
    }

    private static long lastScheduled = 0;

    /** Aus der Abfrageschleife: höchstens alle 15 s neu stellen (sonst flackert „nächster Wecker“). */
    public static void scheduleIfDue(Context c) {
        if (android.os.SystemClock.elapsedRealtime() - lastScheduled > 15_000) schedule(c);
    }

    public static void schedule(Context c) {
        lastScheduled = android.os.SystemClock.elapsedRealtime();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, KeepAlive.class).setAction(ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long at = System.currentTimeMillis() + INTERVAL_MS;
        try {
            if (canSchedule(c)) {
                PendingIntent show = PendingIntent.getActivity(c, 5, new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); } catch (Exception ignored) {}
        }
    }
}
