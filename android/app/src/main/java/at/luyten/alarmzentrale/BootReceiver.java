package at.luyten.alarmzentrale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet die Verbindung nach Neustart oder App-Update automatisch. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LocalAlarms.scheduleAll(context);
        PollService.start(context);
    }
}
