package at.luyten.alarmzentrale;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/** Vollbild-Alarmanzeige über dem Sperrbildschirm. Abschalten nur per QR-Code. */
public class AlarmActivity extends Activity {
    public static volatile boolean visible = false;
    public static volatile long suppressUntil = 0;

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView big, sub, time, hint;
    private Button scanBtn;
    private FrameLayout scanBox;
    private BarcodeView scanner;
    private boolean scanning = false;
    private boolean blink = false;
    private long scanStarted = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;
        getWindow().setAttributes(lp);

        float d = getResources().getDisplayMetrics().density;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding((int) (20 * d), (int) (48 * d), (int) (20 * d), (int) (32 * d));

        big = new TextView(this);
        big.setTextSize(78);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        big.setTextColor(Color.WHITE);
        root.addView(big, new LinearLayout.LayoutParams(-1, -2));

        sub = new TextView(this);
        sub.setTextSize(24);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        sub.setGravity(Gravity.CENTER);
        sub.setTextColor(Color.WHITE);
        root.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        time = new TextView(this);
        time.setTextSize(18);
        time.setGravity(Gravity.CENTER);
        time.setTextColor(0xDDFFFFFF);
        time.setPadding(0, (int) (10 * d), 0, (int) (16 * d));
        root.addView(time, new LinearLayout.LayoutParams(-1, -2));

        scanBox = new FrameLayout(this);
        scanBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sbl.bottomMargin = (int) (12 * d);
        root.addView(scanBox, sbl);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        spacer.setTag("spacer");

        hint = new TextView(this);
        hint.setTextSize(17);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(Color.WHITE);
        hint.setPadding(0, 0, 0, (int) (12 * d));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        scanBtn = new Button(this);
        scanBtn.setText("QR-Code scannen zum Abschalten");
        scanBtn.setTextSize(20);
        scanBtn.setAllCaps(false);
        scanBtn.setTextColor(Color.BLACK);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(18 * d);
        scanBtn.setBackground(bg);
        scanBtn.setPadding(0, (int) (22 * d), 0, (int) (22 * d));
        scanBtn.setOnClickListener(v -> { if (scanning) stopScan(); else startScan(); });
        root.addView(scanBtn, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        hideBars();
        render();
    }

    private void hideBars() {
        //noinspection deprecation
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void render() {
        boolean alarm = Store.TYPE_ALARM.equals(Store.type(this));
        big.setText(alarm ? "ALARM" : "WECKEN");
        sub.setText(alarm ? "Es wurde ein ALARM ausgelöst!" : "Nur Weckruf – kein Alarm");
        long since = Store.since(this);
        time.setText(since > 0 ? "ausgelöst um " + new SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(new Date(since)) + " Uhr" : "");
        int a, b2;
        if (alarm) { a = 0xFFE5322D; b2 = 0xFF000000; } else { a = 0xFF1F6FD6; b2 = 0xFF0A2340; }
        root.setBackgroundColor(scanning ? b2 : (blink ? a : b2));
        if (!scanning && hint.getText().length() == 0) hint.setText("Leiser-Taste und Sperrtaste sind deaktiviert.");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!Store.active(AlarmActivity.this)) { finishAndRemoveTask(); return; }
            blink = !blink;
            render();
            if (scanning && SystemClock.elapsedRealtime() - scanStarted > 60000) stopScan();
            h.postDelayed(this, 400);
        }
    };

    // ---------- QR-Scanner ----------
    private void startScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            suppressUntil = SystemClock.elapsedRealtime() + 20000;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
            return;
        }
        PollService s = PollService.instance;
        if (s != null && s.player() != null) s.player().pauseTorch(true);
        if (scanner == null) {
            scanner = new BarcodeView(this);
            scanner.setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
            scanBox.addView(scanner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        scanBox.setVisibility(View.VISIBLE);
        root.findViewWithTag("spacer").setVisibility(View.GONE);
        scanning = true;
        scanStarted = SystemClock.elapsedRealtime();
        scanBtn.setText("Scan abbrechen");
        hint.setText("Stop-QR-Code in den Rahmen halten");
        scanner.decodeContinuous(callback);
        scanner.resume();
        render();
    }

    private void stopScan() {
        scanning = false;
        if (scanner != null) { scanner.pause(); }
        scanBox.setVisibility(View.GONE);
        root.findViewWithTag("spacer").setVisibility(View.VISIBLE);
        scanBtn.setText("QR-Code scannen zum Abschalten");
        hint.setText("");
        PollService s = PollService.instance;
        if (s != null && s.player() != null) s.player().pauseTorch(false);
        render();
    }

    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override public void barcodeResult(BarcodeResult result) {
            String txt = result.getText();
            if (txt != null && txt.trim().equals(Store.stopCode(AlarmActivity.this).trim())) {
                scanning = false;
                if (scanner != null) scanner.pause();
                Intent i = new Intent(AlarmActivity.this, PollService.class).setAction(PollService.ACTION_STOP_ALARM);
                try { startService(i); } catch (Exception e) {
                    PollService s = PollService.instance; if (s != null) s.stopAlarm("stopped");
                }
                visible = false;
                finishAndRemoveTask();
            } else {
                hint.setText("Falscher QR-Code – das ist nicht der Stop-Code");
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        suppressUntil = 0;
        if (rc == 7 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startScan();
        else hint.setText("Kamera-Berechtigung fehlt – in den App-Einstellungen erlauben");
    }

    // ---------- Tasten sperren ----------
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_FOCUS:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { /* gesperrt */ }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        hideBars();
        if (!Store.active(this)) { finishAndRemoveTask(); return; }
        h.removeCallbacks(tick);
        h.post(tick);
        if (scanning && scanner != null) scanner.resume();
    }

    @Override
    protected void onPause() {
        visible = false;
        if (scanner != null) scanner.pause();
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
        else if (Store.active(this)) {
            // Benachrichtigungsleiste heruntergezogen → wieder schließen
            //noinspection deprecation
            try { sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)); } catch (Exception ignored) {}
        }
    }
}
