package at.luyten.alarmzentrale;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Einrichtung: Worker-Adresse, Berechtigungen, Test, Stop-QR-Code. */
public class MainActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private EditText urlField;
    private LinearLayout permBox;
    private TextView stopInfo;
    private LinearLayout alarmBox;
    private TextView nextInfo;
    private float d;

    private static final int BG = 0xFF111418, CARD = 0xFF1B2027, TXT = 0xFFEEF1F4, MUT = 0xFF8C96A3;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        // Solange die App offen ist, geht der Bildschirm nie aus (unabhängig vom System-Timeout)
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(18), px(18), px(18), px(30));
        sv.addView(col);

        TextView title = text("ALARMZENTRALE", 15, MUT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.14f);
        col.addView(title);

        // Status
        LinearLayout c1 = card(col);
        status = text("…", 18, TXT);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        c1.addView(status);

        // Wecker (offline)
        LinearLayout cw = card(col);
        cw.addView(text("Wecker – funktioniert auch ohne Internet", 14, MUT));
        nextInfo = text("", 15, TXT);
        nextInfo.setPadding(0, px(4), 0, px(4));
        cw.addView(nextInfo);
        alarmBox = new LinearLayout(this);
        alarmBox.setOrientation(LinearLayout.VERTICAL);
        cw.addView(alarmBox);
        cw.addView(button("+ Wecker hinzufügen", 0xFF2F7FE0, v -> editAlarm(null)));

        // Worker-Adresse
        LinearLayout c2 = card(col);
        c2.addView(text("Worker-Adresse", 14, MUT));
        urlField = new EditText(this);
        urlField.setTextColor(TXT);
        urlField.setHintTextColor(MUT);
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setText(Store.url(this));
        c2.addView(urlField);
        c2.addView(button("Speichern", 0xFF2F7FE0, v -> {
            String u = urlField.getText().toString().trim();
            if (!u.startsWith("https://")) { toast("Adresse muss mit https:// beginnen"); return; }
            Store.prefs(this).edit().putString("url", u).apply();
            toast("Gespeichert");
        }));

        // Berechtigungen
        LinearLayout c3 = card(col);
        c3.addView(text("Berechtigungen – alle müssen ✓ sein", 14, MUT));
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        c3.addView(permBox);

        // Huawei / Honor
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        if (m.contains("huawei") || m.contains("honor")) {
            LinearLayout ch = card(col);
            ch.addView(text("Huawei: Unter „App-Start“ die Alarmzentrale auf „Manuell verwalten“ stellen und alle drei Schalter einschalten (Automatischer Start, Sekundärer Start, Im Hintergrund ausführen).", 14, MUT));
            ch.addView(button("Huawei App-Start öffnen", 0xFF3A4350, v -> openHuawei()));
        }

        // Test
        LinearLayout c4 = card(col);
        c4.addView(text("Test (nur am Gerät, Abschalten nur mit QR-Code!)", 14, MUT));
        c4.addView(button("Test: Wecken", 0xFF2F7FE0, v -> confirmTest(Store.TYPE_WAKE)));
        c4.addView(button("Test: Alarm", 0xFFE5322D, v -> confirmTest(Store.TYPE_ALARM)));

        // Stop-Code
        LinearLayout c5 = card(col);
        c5.addView(text("Stop-QR-Code", 14, MUT));
        stopInfo = text("", 15, TXT);
        c5.addView(stopInfo);
        c5.addView(button("Eigenen QR-Code anlernen", 0xFF3A4350, v -> startActivity(new Intent(this, LearnActivity.class))));
        c5.addView(button("Auf Standard-Code zurücksetzen", 0xFF3A4350, v -> {
            Store.prefs(this).edit().putString("stop", Store.DEFAULT_STOP_CODE).apply();
            refresh();
        }));

        setContentView(sv);
        PollService.start(this);
    }

    private void confirmTest(String type) {
        new AlertDialog.Builder(this)
                .setTitle(Store.TYPE_ALARM.equals(type) ? "Test-Alarm starten?" : "Test-Weckruf starten?")
                .setMessage("Der Ton geht auf volle Lautstärke und lässt sich NUR mit dem Stop-QR-Code abschalten. Hast du den QR-Code griffbereit?")
                .setPositiveButton("Starten", (dlg, w) -> {
                    Intent i = new Intent(this, PollService.class).setAction(PollService.ACTION_TEST).putExtra(PollService.EXTRA_TYPE, type);
                    startForegroundService(i);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ---------- Wecker ----------
    private void refreshAlarms() {
        alarmBox.removeAllViews();
        java.util.List<LocalAlarms.Alarm> list = LocalAlarms.load(this);
        if (list.isEmpty()) alarmBox.addView(text("Noch kein Wecker gestellt.", 15, MUT));
        for (LocalAlarms.Alarm a : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, px(8), 0, px(8));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView t = text(a.time(), 32, a.on ? TXT : MUT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            txt.addView(t);
            txt.addView(text(a.daysText(), 14, MUT));
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            android.widget.Switch sw = new android.widget.Switch(this);
            sw.setChecked(a.on);
            sw.setScaleX(1.3f); sw.setScaleY(1.3f);
            sw.setOnCheckedChangeListener((b, on) -> {
                java.util.List<LocalAlarms.Alarm> l = LocalAlarms.load(this);
                for (LocalAlarms.Alarm x : l) if (x.id == a.id) x.on = on;
                LocalAlarms.save(this, l);
                refreshAlarms();
            });
            row.addView(sw);
            row.setOnClickListener(v -> editAlarm(a));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("Wecker " + a.time() + " löschen?")
                        .setPositiveButton("Löschen", (d, w) -> {
                            java.util.List<LocalAlarms.Alarm> l = LocalAlarms.load(this);
                            l.removeIf(x -> x.id == a.id);
                            LocalAlarms.save(this, l);
                            refreshAlarms();
                        })
                        .setNegativeButton("Abbrechen", null).show();
                return true;
            });
            alarmBox.addView(row);
        }
        LocalAlarms.Alarm n = LocalAlarms.nextAlarm(this);
        if (n == null) nextInfo.setText("Kein Wecker aktiv");
        else {
            long t = LocalAlarms.next(n, System.currentTimeMillis());
            long mins = Math.max(0, (t - System.currentTimeMillis()) / 60000 + 1);
            String day = new java.text.SimpleDateFormat("EEEE", java.util.Locale.GERMANY).format(new java.util.Date(t));
            nextInfo.setText("Nächster Wecker: " + day + " " + n.time() + "  (in " + (mins / 60) + " h " + (mins % 60) + " min)");
        }
        if (list.size() > 0) alarmBox.addView(text("Antippen = ändern · lange drücken = löschen", 12, MUT));
    }

    private void editAlarm(LocalAlarms.Alarm existing) {
        int h = existing != null ? existing.hour : 6, m = existing != null ? existing.minute : 0;
        android.app.TimePickerDialog tp = new android.app.TimePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert,
                (view, hour, minute) -> pickDays(existing, hour, minute), h, m, true);
        tp.setTitle(existing == null ? "Neuer Wecker" : "Wecker ändern");
        tp.show();
    }

    private void pickDays(LocalAlarms.Alarm existing, int hour, int minute) {
        final boolean[] sel = new boolean[7];
        int days = existing != null ? existing.days : 0;
        for (int i = 0; i < 7; i++) sel[i] = (days & (1 << i)) != 0;
        String[] names = {"Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"};
        new AlertDialog.Builder(this)
                .setTitle("Wiederholen? (nichts gewählt = einmalig)")
                .setMultiChoiceItems(names, sel, (d, i, on) -> sel[i] = on)
                .setPositiveButton("Speichern", (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < 7; i++) if (sel[i]) mask |= 1 << i;
                    java.util.List<LocalAlarms.Alarm> l = LocalAlarms.load(this);
                    LocalAlarms.Alarm a = null;
                    if (existing != null) for (LocalAlarms.Alarm x : l) if (x.id == existing.id) a = x;
                    if (a == null) { a = new LocalAlarms.Alarm(); a.id = LocalAlarms.newId(l); l.add(a); }
                    a.hour = hour; a.minute = minute; a.days = mask; a.on = true;
                    LocalAlarms.save(this, l);
                    refreshAlarms();
                    if (!KeepAlive.canSchedule(this)) toast("Achtung: Berechtigung „Wecker & Erinnerungen“ fehlt");
                    else toast("Wecker gestellt: " + a.time() + " " + a.daysText());
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ---------- Berechtigungen ----------
    private static class Perm {
        final String name; final boolean ok; final Runnable fix;
        Perm(String n, boolean o, Runnable f) { name = n; ok = o; fix = f; }
    }

    private List<Perm> perms() {
        List<Perm> l = new ArrayList<>();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= 33)
            l.add(new Perm("Benachrichtigungen", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1)));
        l.add(new Perm("Kamera (QR-Scanner)", checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
                () -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 2)));
        l.add(new Perm("Über anderen Apps einblenden", Settings.canDrawOverlays(this),
                () -> open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))));
        l.add(new Perm("Nicht-stören-Zugriff (Stummmodus durchbrechen)", nm.isNotificationPolicyAccessGranted(),
                () -> open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        l.add(new Perm("Akku-Optimierung aus (läuft dauerhaft)", pm.isIgnoringBatteryOptimizations(getPackageName()),
                () -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())))));
        if (Build.VERSION.SDK_INT >= 31)
            l.add(new Perm("Wecker & Erinnerungen (Wachhalter)", KeepAlive.canSchedule(this),
                    () -> open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())))));
        if (Build.VERSION.SDK_INT >= 34)
            l.add(new Perm("Vollbild-Benachrichtigungen", nm.canUseFullScreenIntent(),
                    () -> open(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())))));
        return l;
    }

    private void openHuawei() {
        String[][] targets = {
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        };
        for (String[] t : targets) {
            try {
                startActivity(new Intent().setComponent(new android.content.ComponentName(t[0], t[1])));
                return;
            } catch (Exception ignored) {}
        }
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void open(Intent i) {
        try { startActivity(i); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception ignored) {}
        }
    }

    private void refresh() {
        refreshAlarms();
        permBox.removeAllViews();
        for (Perm p : perms()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, px(8), 0, px(8));
            TextView t = text((p.ok ? "✓  " : "✗  ") + p.name, 16, p.ok ? 0xFF22C55E : 0xFFFF6B66);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (!p.ok) {
                Button fix = new Button(this);
                fix.setText("Erlauben");
                fix.setAllCaps(false);
                fix.setOnClickListener(v -> p.fix.run());
                row.addView(fix);
            }
            permBox.addView(row);
        }
        String code = Store.stopCode(this);
        stopInfo.setText(code.equals(Store.DEFAULT_STOP_CODE)
                ? "Standard-Code aktiv (stop-qr.png aus dem Repo ausdrucken)"
                : "Eigener Code aktiv: " + (code.length() > 40 ? code.substring(0, 40) + "…" : code));
    }

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            long ago = PollService.lastOk == 0 ? -1 : (System.currentTimeMillis() - PollService.lastOk) / 1000;
            boolean ok = ago >= 0 && ago < 20;
            status.setText((ok ? "● " : "● ") + PollService.statusText
                    + (ago >= 0 ? "\nletzte Antwort vor " + ago + " s" : ""));
            status.setTextColor(ok ? 0xFF22C55E : 0xFFFF6B66);
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        PollService.start(this);
        refresh();
        h.post(statusTick);
    }

    @Override protected void onPause() { h.removeCallbacks(statusTick); super.onPause(); }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) { refresh(); }

    // ---------- UI-Helfer ----------
    private int px(int dp) { return (int) (dp * d); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(16), px(14), px(16), px(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD); g.setCornerRadius(px(14));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(14);
        parent.addView(c, lp);
        return c;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(px(12));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(54));
        lp.topMargin = px(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
