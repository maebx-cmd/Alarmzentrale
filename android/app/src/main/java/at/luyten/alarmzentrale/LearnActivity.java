package at.luyten.alarmzentrale;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.Collections;

/** Beliebigen QR-Code als neuen Stop-Code anlernen. */
public class LearnActivity extends Activity {
    private BarcodeView scanner;
    private boolean done = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Store.active(this)) { finish(); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF111418);
        col.setFitsSystemWindows(true);
        TextView t = new TextView(this);
        t.setText("QR-Code scannen, der künftig den Wecker abschaltet");
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(30, 40, 30, 30);
        col.addView(t);
        FrameLayout box = new FrameLayout(this);
        scanner = new BarcodeView(this);
        scanner.setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        box.addView(scanner, new FrameLayout.LayoutParams(-1, -1));
        col.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(col);
        scanner.decodeSingle(result -> {
            if (done) return;
            done = true;
            String code = result.getText();
            new AlertDialog.Builder(this)
                    .setTitle("Als Stop-Code speichern?")
                    .setMessage(code + "\n\nAb jetzt lässt sich der Wecker nur mehr mit genau diesem QR-Code abschalten.")
                    .setPositiveButton("Speichern", (d, w) -> {
                        Store.prefs(this).edit().putString("stop", code.trim()).apply();
                        finish();
                    })
                    .setNegativeButton("Abbrechen", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scanner == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 3);
        else scanner.resume();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) scanner.resume();
        else finish();
    }

    @Override
    protected void onPause() {
        if (scanner != null) scanner.pause();
        super.onPause();
    }
}
