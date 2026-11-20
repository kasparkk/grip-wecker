# Grip-Wecker

Sonnenaufgangs-Wecker für den JBL Grip als native Android-App (Kotlin). Läuft ohne Laptop und ohne Relais: das Handy verbindet sich zur Weckzeit selbst per BLE mit dem Grip.

**APK:** [grip-wecker.apk (neuester Build)](https://github.com/kasparkk/grip-wecker/releases/download/latest/grip-wecker.apk)

## Oberfläche

Die App zeigt die Web-App [JBL-grip-light-control](https://github.com/kasparkk/JBL-grip-light-control) (Lichtshow, Farben, Helligkeit, Musik, Relais) in einer WebView. Weil es in der WebView kein Web Bluetooth gibt, ersetzt `assets/web/bridge.js` `navigator.bluetooth` und leitet an die native BLE-Verbindung weiter. `assets/web/android.js` hängt das Wecker-Formular an den nativen Wecker, blendet die Einrichtung ein und verbindet den gespeicherten Grip automatisch.

Web-App aktualisieren: `tools/sync-web.sh` ausführen, committen, pushen.

## Ablauf

1. `setAlarmClock` weckt das Handy zum Beginn des Sonnenaufgangs (Weckzeit minus Vorlauf).
2. Ein Foreground-Service verbindet sich mit dem Grip, setzt Theme Freeze und fährt das Licht hoch: Rot, Orange, Blau, Helligkeit 3 bis 100 %.
3. Zur Weckzeit: Weckton vom Handy (Alarm-Lautstärke, wird über 60 s lauter) und Weckbildschirm über dem Sperrbildschirm.
4. **Schlummern** stoppt den Ton und klingelt nach der eingestellten Zeit wieder. **Aus** stoppt den Ton und lässt das Licht an (Theme Freeze). Nach 20 min geht der Ton von selbst aus.

## Grip-Protokoll (BLE)

- Service `65786365-6c70-6f69-6e74-2e636f6d0000`, Write-Characteristic `…0002` (ohne Antwort)
- Paket: `00 DD 02 00 01 00 05 00` + `fid_lo fid_hi 01 00 wert`
- Theme `0x0D40` (Freeze `0x8C`), Helligkeit `0x0D41` (`0x80 | prozent` = an), Farbe `0x0D46` (1 Orange, 2 Pink, 3 Blau, 4 Rot)
- Mindestens 170 ms zwischen zwei Paketen

Vorlage: [JBL-grip-light-control](https://github.com/kasparkk/JBL-grip-light-control) (Wecker-Abschnitt in `index.html`).

## Build

GitHub Actions baut bei jedem Push auf `main` die APK und ersetzt das Release `latest`. Signiert wird mit dem festen Schlüssel `app/grip.keystore`, damit Updates sich über die alte Version installieren lassen. Bei einem Fehler landet das Build-Log im Branch `ci-log`.
