package at.luyten.alarmzentrale;

import android.app.NotificationManager;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Erzeugt den Alarmton selbst (kein Klingelton nötig), hält die Lautstärke auf Maximum, vibriert und blitzt. */
public class AlarmPlayer {
    private final Context ctx;
    private final AudioManager am;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioAttributes attrs;

    private volatile boolean playing = false;
    private volatile String type = Store.TYPE_WAKE;
    private Thread audioThread;
    private AudioFocusRequest focus;

    private Vibrator vib;
    private CameraManager cam;
    private String torchId;
    private boolean torchOn = false;
    private volatile boolean torchPaused = false;

    private int savedFilter = -1;
    private int savedAlarmVol = -1;
    private int savedRinger = -1;
    private int tick = 0;

    public AlarmPlayer(Context c) {
        ctx = c.getApplicationContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vib = vm.getDefaultVibrator();
            } else {
                vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception ignored) {}
        try {
            cam = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cam.getCameraIdList()) {
                CameraCharacteristics cc = cam.getCameraCharacteristics(id);
                Boolean f = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { torchId = id; break; }
            }
        } catch (Exception ignored) {}
    }

    public boolean isPlaying() { return playing; }

    public void setType(String t) { type = t; }

    /** Während des QR-Scans braucht die Kamera den Blitz – Stroboskop pausieren. */
    public void pauseTorch(boolean p) {
        torchPaused = p;
        if (p) setTorch(false);
    }

    public synchronized void start(String t) {
        type = t;
        if (playing) return;
        playing = true;
        overrideSilence();
        requestFocus();
        startAudio();
        startVibration();
        h.removeCallbacks(enforcer);
        h.post(enforcer);
    }

    public synchronized void stop() {
        if (!playing) return;
        playing = false;
        h.removeCallbacks(enforcer);
        try { if (audioThread != null) audioThread.join(800); } catch (InterruptedException ignored) {}
        audioThread = null;
        try { if (vib != null) vib.cancel(); } catch (Exception ignored) {}
        setTorch(false);
        try { if (focus != null) am.abandonAudioFocusRequest(focus); } catch (Exception ignored) {}
        restoreSilence();
    }

    // ---------- Lautstärke / Nicht stören ----------
    private void overrideSilence() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (nm.isNotificationPolicyAccessGranted()) {
                savedFilter = nm.getCurrentInterruptionFilter();
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            }
        } catch (Exception ignored) {}
        try { savedRinger = am.getRingerMode(); } catch (Exception ignored) {}
        try { savedAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM); } catch (Exception ignored) {}
        forceVolume();
    }

    private void forceVolume() {
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) != max)
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
        } catch (Exception ignored) {}
        try {
            if (am.isStreamMute(AudioManager.STREAM_ALARM))
                am.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {}
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.isNotificationPolicyAccessGranted()
                    && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        } catch (Exception ignored) {}
    }

    private void restoreSilence() {
        try { if (savedAlarmVol >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0); } catch (Exception ignored) {}
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (savedFilter > 0 && nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(savedFilter);
        } catch (Exception ignored) {}
        try { if (savedRinger >= 0) am.setRingerMode(savedRinger); } catch (Exception ignored) {}
        savedFilter = -1; savedAlarmVol = -1; savedRinger = -1;
    }

    private void requestFocus() {
        try {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(fc -> {})
                    .build();
            am.requestAudioFocus(focus);
        } catch (Exception ignored) {}
    }

    /** Läuft alle 250 ms: Lautstärke zurück auf Maximum, Nicht-stören aus, Stroboskop, Ton/Vibration neu starten falls weg. */
    private final Runnable enforcer = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            forceVolume();
            tick++;
            if (!torchPaused) setTorch(!torchOn); else if (torchOn) setTorch(false);
            if (audioThread == null || !audioThread.isAlive()) startAudio();
            if (tick % 40 == 0) startVibration(); // alle 10 s neu anstoßen
            h.postDelayed(this, 250);
        }
    };

    private void setTorch(boolean on) {
        if (cam == null || torchId == null) return;
        try { cam.setTorchMode(torchId, on); torchOn = on; } catch (Exception ignored) { torchOn = false; }
    }

    // ---------- Vibration ----------
    private void startVibration() {
        if (vib == null) return;
        try {
            long[] pattern = Store.TYPE_ALARM.equals(type)
                    ? new long[]{0, 600, 150, 600, 150}
                    : new long[]{0, 900, 500};
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs);
        } catch (Exception ignored) {}
    }

    // ---------- Ton ----------
    private void startAudio() {
        audioThread = new Thread(this::audioLoop, "alarm-audio");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    private void audioLoop() {
        final int sr = 44100;
        AudioTrack t = null;
        try {
            int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(minBuf, sr / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            t.setVolume(1.0f);
            t.play();
            short[] buf = new short[1024];
            double phase = 0, phase2 = 0;
            long n = 0;
            while (playing) {
                boolean alarm = Store.TYPE_ALARM.equals(type);
                for (int i = 0; i < buf.length; i++, n++) {
                    double ts = n / (double) sr;
                    double f, amp;
                    if (alarm) {
                        // Sirene: schnelles Auf- und Abschwellen 650–1750 Hz
                        double p = ts % 0.9 / 0.9;
                        f = p < 0.5 ? 650 + 2200 * p : 1750 - 2200 * (p - 0.5);
                        amp = 1.0;
                    } else {
                        // Weckton: 4 schrille Pieptöne, kurze Pause
                        double p = ts % 1.0;
                        amp = (p < 0.62 && (p % 0.155) < 0.1) ? 1.0 : 0.0;
                        f = 2900;
                    }
                    phase += 2 * Math.PI * f / sr;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                    phase2 += 2 * Math.PI * f * 1.5 / sr;
                    if (phase2 > 2 * Math.PI) phase2 -= 2 * Math.PI;
                    // stark übersteuert → fast Rechteck, wirkt deutlich lauter
                    double s = Math.sin(phase) * 3.0 + Math.sin(phase2) * 0.8;
                    if (s > 1) s = 1; else if (s < -1) s = -1;
                    buf[i] = (short) (s * amp * 32000);
                }
                t.write(buf, 0, buf.length);
            }
        } catch (Exception ignored) {
        } finally {
            if (t != null) {
                try { t.stop(); } catch (Exception ignored) {}
                try { t.release(); } catch (Exception ignored) {}
            }
        }
    }
}
