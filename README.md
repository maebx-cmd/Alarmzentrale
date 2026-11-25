# Alarmzentrale

Statusseite (GitHub Pages) + Cloudflare Worker + Android-Wecker-App.

```
Statusseite ──PIN──▶ Worker (D1) ◀── fragt alle 5 s ── Android-App
             ◀── Status: erreichbar, Akku, empfangen, abgeschaltet ──
```

## 1. Worker (Cloudflare-Dashboard)
1. **Storage & Databases → D1 → Create** → Name `alarmzentrale`.
2. **Workers & Pages → Create → Worker** → Name `alarmzentrale` → Deploy → **Edit code** → Inhalt von `worker/worker.js` einfügen → Deploy.
3. Worker → **Settings → Bindings → Add → D1 database** → Variablenname **`DB`** → Datenbank `alarmzentrale` → Deploy.
4. Test: `https://alarmzentrale.<dein-name>.workers.dev/api/status` liefert JSON.

Tabellen legt der Worker selbst an. Kostenloser Plan reicht (≈ 17.000 Abfragen/Tag bei 5-s-Takt).

## 2. Statusseite (GitHub Pages)
1. In `index.html` ganz unten `WORKER_URL` auf deine Worker-Adresse setzen.
2. Repo → **Settings → Pages** → Source „Deploy from a branch“, Branch `main`, Ordner `/ (root)`.

## 3. APK
Jeder Push in `android/` baut die APK über GitHub Actions. Download: Repo → **Releases → „Alarmzentrale APK“ → Alarmzentrale.apk** (direkt am Handy öffnen). Der Signaturschlüssel liegt fest im Repo, deshalb lassen sich spätere Builds einfach drüberinstallieren.

## 4. Einrichtung am Handy
1. APK installieren, öffnen.
2. Worker-Adresse eintragen → Speichern.
3. Alle Berechtigungen auf ✓ bringen (Benachrichtigungen, Kamera, Über anderen Apps, Nicht-stören-Zugriff, Akku-Optimierung, Vollbild).
4. `stop-qr.png` ausdrucken und dort aufhängen, wo man aufstehen muss. Oder: „Eigenen QR-Code anlernen“.
5. **Test: Wecken** auslösen und mit dem QR-Code abschalten.

Samsung: zusätzlich Einstellungen → Akku → Hintergrundnutzungslimits → Alarmzentrale bei den **nie schlafenden Apps** eintragen.
Xiaomi/Huawei: Autostart erlauben, Akku-Sparmodus „Keine Einschränkungen“.

## Wachhalten (auch Huawei)
- Dauerdienst mit Wakelock (auf Huawei/Honor unter einem vom System geduldeten Namen) + WLAN-Sperre, Abfrage alle 5 s.
- Zusätzlich ein System-Wecker (`setAlarmClock`) als Wachhalter: Er feuert nur, wenn die Abfrage im Tiefschlaf hängen bleibt, weckt das Gerät, startet den Dienst bei Bedarf neu und fragt sofort ab. Deshalb zeigt das Handy einen „nächsten Wecker“ in ≤ 30 s an.
- Solange die App offen ist, geht der Bildschirm nicht aus.
- Die Statusseite zeigt „erreichbar“, solange die letzte Meldung jünger als 75 s ist.

## Wecker (offline)
In der App oben: „+ Wecker hinzufügen“ → Uhrzeit → Wochentage (nichts gewählt = einmalig). Antippen = ändern, lange drücken = löschen, Schalter = an/aus.
Läuft ohne Internet über den System-Wecker, übersteht Tiefschlaf und Neustart (wird schon vor dem Entsperren neu gestellt). Klingelt mit dem Weckton und lässt sich nur per QR-Code abschalten. Kein Schlummern.

## Notfall-Storno
Auf der Statusseite erscheint bei einer laufenden oder noch nicht zugestellten Auslösung „Notfall: aus der Ferne abstellen“ (eigener 4-stelliger PIN, nur als Hash im Worker). Das Gerät schaltet beim nächsten Abruf ab, die Seite zeigt „Aus der Ferne abgestellt“.

## Verhalten beim Alarm
- Eigener Sirenenton (Alarm) bzw. schrilles Piepen (Wecken) über den Alarm-Kanal, Lautstärke wird alle 250 ms auf Maximum zurückgesetzt.
- Stumm- und Nicht-stören-Modus werden ausgehebelt.
- Lauter/Leiser-Tasten tun nichts. Die Sperrtaste schaltet den Bildschirm sofort wieder ein.
- Home-Taste oder Wegwischen: Die Alarmanzeige kommt innerhalb einer Sekunde zurück.
- Vibration + blinkendes Blitzlicht; der Bildschirm blinkt rot (ALARM) oder blau (WECKEN).
- Ein Neustart beendet den Alarm nicht: Nach dem Hochfahren klingelt es weiter.
- Abschalten **nur** mit dem Stop-QR-Code. Danach meldet die App „abgeschaltet“ an die Statusseite.
- Wird nicht abgeholt (Gerät offline), verfällt die Auslösung nach 15 Minuten.

PIN: im Worker nur als SHA-256-Hash hinterlegt. Nach 10 Fehlversuchen ist die Eingabe 10 Minuten gesperrt.
