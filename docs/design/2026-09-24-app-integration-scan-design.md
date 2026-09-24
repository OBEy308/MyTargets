# App-Integration, Teil 8b: Scan aus der Eingabe — Design

Datum: 2026-09-24
Status: Design, noch nicht umgesetzt
Basis: Master `38286bac` (8a samt Nacharbeiten, PR #18 und #19)
Vorgänger: Haupt-Design `2026-09-09-arrow-detection-design.md` (*Integration in
die App*: Einstiegspunkte, Zustand über den Kamera-Ausflug, Anwenden und
Korrigieren, Fotoablage, Fehlerfälle), `2026-09-23-app-integration-foundation-design.md` (8a)

## Ziel

Aus der Passeneingabe heraus ein Foto der Auflage machen, die Pfeile erkennen
und in die aktuelle Passe eintragen. 8a liefert dafür
`EndPhotoScanner.scan(photo, target, shotsPerEnd): ScanOutcome`; 8b baut
Menüpunkt, Kamera-Ausflug, Fotoablage, Eintragen, Meldungen und Rückgängig
darum.

Was das Haupt-Design schon festlegt, gilt weiter und wird hier nur konkret:
Menüpunkt im Überlauf, Aufnahme über `EasyImage`, *pending scan* in
`outState`, `endId`-Prüfung vor dem Schreiben, Foto immer als `EndImage` mit
2048 px langer Kante, Rückfrage bei vorhandenen Treffern, danach explizit
Plotting, Snackbar mit Rückgängig, eine eigene Meldung je Fehlerfall.
Leitsatz: **Bei Unsicherheit lieber nichts eintragen als etwas Falsches.**

## Entscheidungen

1. **Sperrender Fortschrittsdialog mit „Abbrechen“.** Während des Scans (erster
   rund 3,7 s mit Laden, danach rund 2,6 s auf dem S25) lässt sich die Eingabe
   nicht bedienen. Die `endId`-Prüfung schlägt damit im Normalbetrieb nie an;
   sie schützt nach Prozesstod. Ein nicht sperrender Scan hätte einen zweiten
   Zustand „während des Scans bearbeitet“ gebraucht.
2. **Rückfrage vor der Kamera.** Hat die Passe schon Treffer, fragt das Tippen
   auf „Passe scannen“: „Die Passe hat schon N Treffer. Beim Scan ersetzen?“.
   Wer ablehnt, macht kein Foto. Das Ergebnis wird danach ohne weitere Frage
   eingetragen; kein Dialog muss einen Prozesstod überstehen.
3. **Rückgängig nimmt nur die Treffer zurück.** Das Foto bleibt an der Passe,
   wie es das Haupt-Design will („unabhängig vom Erkennungserfolg“). Wer es
   nicht will, löscht es in der Galerie.
4. **Der pending scan ist der einzige Zustand, der Lauf ist wiederholbar.**
   Kein ViewModel, kein WorkManager. Stirbt die Activity mitten im Scan
   (Drehen, Prozesstod), stirbt der Job mit; nach dem nächsten Laden startet
   er neu. Ein Weg für Normalfall, Drehen und Prozesstod, für höchstens einen
   zweiten Scan von rund 3 s.
5. **Erst ablegen, dann scannen.** Die 2048-px-Kopie entsteht über
   `BitmapFactory`, nicht über OpenCV, und hängt an der Passe, bevor der Scan
   beginnt. Die Ablage hängt so weder am Modell noch an OpenCV, und ein
   Absturz im Scan kostet das Foto nicht.
6. **Passen mit `exact = true` und gesetzten Treffern zeigen immer Plotting.**
   Neue Regel in `TargetView.replaceWithEnd`, nicht nur für gescannte Passen.
   Das betrifft auch Passen, die ein Nutzer mit Tastatur-Einstellung früher
   von Hand geplottet hat; sie haben Positionen und erscheinen folgerichtig
   als Plot. Eine Ausnahmeliste nur für gescannte Passen wäre mehr Zustand und
   nach dem Schließen der Eingabe vergessen.

## Zustand

```kotlin
data class PendingScan(
    val endId: Long,
    val originalPath: String?,    // null, solange die Kamera offen ist
    val storedImageName: String?  // gesetzt, sobald die 2048-px-Kopie an der Passe hängt
)
```

`EndScanFlow` hält höchstens einen `PendingScan` und schreibt ihn in
`InputActivity.onSaveInstanceState` über `toBundle()` in `outState` (unter
einem eigenen Schlüssel, neben `TRAINING_ID`, `ROUND_ID`, `END_INDEX`), gelesen
in `onCreate` über `fromBundle()`. Er wird erst verworfen, wenn das Ergebnis
eingetragen, gemeldet oder abgebrochen ist.

Eine neue Passe steht schon beim Anzeigen in der Datenbank
(`AugmentedRound.addEnd` ruft `insertCompleteEnd`); die `endId` ist also beim
Tippen auf den Menüpunkt immer gesetzt.

## Ablauf

1. **Menüpunkt `action_scan_end`** („Passe scannen“, `showAsAction="never"`),
   sichtbar, wenn `action_photo` sichtbar ist (`Utils.hasCameraHardware`) und
   `ScanSupport.supports(d.currentRound.round.target)`.
   - Hat die aktuelle Passe gesetzte Treffer, erst die Rückfrage
     (Entscheidung 2). Ablehnen beendet den Ablauf.
   - `PendingScan(endId = d.currentEnd.end.id, null, null)` anlegen, dann
     `EasyImage.openCameraForImage(this, 0)` in
     `try`/`catch (ActivityNotFoundException)` mit `no_camera_app`; im
     Fehlerfall den pending scan verwerfen.
   - Die `endId` wird **vor** der Kamera festgehalten, sonst ist sie nach
     einem Prozesstod unbekannt.
2. **`onActivityResult`**: `EasyImage.handleActivityResult` (der bestehende
   Galerie-Zweig mit `GALLERY_REQUEST_CODE` bleibt unverändert davor).
   - Foto geliefert: `originalPath` setzen.
   - Abgebrochen: `lastlyTakenButCanceledPhoto` löschen (wie
     `GalleryActivity`), pending scan verwerfen.
   - Foto geliefert, aber kein pending scan vorhanden: Datei löschen. Ohne
     `endId` gibt es kein Ziel.
3. **Sobald `data` da ist**, am Ende von `onLoadFinished` und in `onResume`
   bei vorhandenem `data`: Liegt ein pending scan mit `originalPath` vor und
   läuft noch kein Job, startet der Lauf in `lifecycleScope` mit dem
   sperrenden Dialog (Entscheidung 1):
   1. **Ablegen**, falls `storedImageName == null`: `EndPhotoStore.store`
      (auf `Dispatchers.IO`), dann `endDAO.insertEndImage(EndImage(name,
      endId))`, dann `storedImageName` setzen. Ist `endId` die aktuelle
      Passe, kommt das Bild auch in `d.currentEnd.images` (Menü-Icon
      wechselt). Scheitert das Ablegen oder das Einfügen (Passe gelöscht,
      Fremdschlüssel), wird protokolliert, die abgelegte Datei gelöscht und
      trotzdem weitergemacht.
   2. **Prüfen**: `d.currentEnd.end.id == endId`? Wenn nicht: nichts
      eintragen, Original löschen, Hinweis „Foto an Passe N abgelegt, nicht
      eingetragen“, pending scan verwerfen.
   3. **Scannen**: `EndPhotoScanner.get(this).scan(original,
      target, shotsPerEnd)`.
   4. **Anwenden oder melden** (siehe unten), Original löschen, pending scan
      verwerfen, Dialog schließen.
4. **Abbrechen** im Dialog: Job abbrechen (die Abbruchlogik aus PR #19 lässt
   den Vorwärtslauf dann aus, wenn er noch nicht begonnen hat), nichts
   eintragen. Das Foto bleibt abgelegt, wenn Schritt 3.1 schon lief. Original
   und pending scan werden gelöscht.

**Drehen oder Prozesstod mitten im Lauf:** Der Job stirbt mit der Activity,
der Dialog ebenso. Der pending scan steht in `outState`; nach dem nächsten
Laden beginnt Schritt 3 neu. `storedImageName` verhindert ein zweites Bild.

**Sperren:** Der Dialog ist modal, nicht wegtippbar (`cancelable(false)`),
nur „Abbrechen“ beendet ihn.

## Anwenden

- **Umwandeln.** Die `DetectedShot`s kommen in der Reihenfolge der Erkennung
  auf die Plätze `0..k-1`, die übrigen bleiben `NOTHING_SELECTED` (bei einem
  Spot ist die Reihenfolge ohne Bedeutung; die Liste hat immer `shotsPerEnd`
  Einträge). `x`/`y` werden übernommen, Pfeilnummern bleiben leer. Der
  Ringwert kommt aus `targetDrawable.getZoneFromPoint(x, y)` des
  `TargetView`, wie beim Setzen von Hand, also mit Pfeilradius und
  Linienregel. `ScanApplication` bekommt ihn deshalb als Funktion übergeben.
- **Eintragen.** `d.currentEnd.shots` ersetzen, `end.exact = true`,
  `targetView.replaceWithEnd(shots, exact = true)` (zeigt nach Entscheidung 6
  Plotting), `saveCurrentEnd()`, dann wie in `onEndFinished`:
  Navigationsknöpfe, Wear, Menü, und die Summen wie in `onEndUpdated`.
- **Rückgängig.** Vor dem Eintragen eine Kopie der Treffer und von `exact`.
  Die Snackbar-Aktion setzt beides über denselben Weg zurück, View und
  Datenbank. Die Kopie lebt so lange wie die Snackbar; nach Drehen oder
  Prozesstod gibt es kein Rückgängig mehr, die Treffer bleiben.

## Meldungen

Das Foto ist in jedem Fall schon abgelegt (außer das Ablegen selbst schlug
fehl).

| Ergebnis | Eingetragen? | Snackbar |
|---|---|---|
| `COMPLETE` | ja | „6 Pfeile erkannt“ · Rückgängig |
| `FEWER_THAN_EXPECTED` | ja | „4 von 6 Pfeilen erkannt, Rest bitte setzen“ · Rückgängig |
| `AMBIGUOUS_SURPLUS` | ja, nur die sicheren | „4 sicher erkannt, weitere unklar“ · Rückgängig |
| `SPOT_OVERFLOW` | ja | wie `AMBIGUOUS_SURPLUS`, mit dem Grund (bei WA-Voll praktisch nie) |
| Auflage gefunden, 0 Pfeile | **nein**, vorhandene Treffer bleiben | „Keine Pfeile erkannt“ · Neu aufnehmen |
| `FACE_NOT_FOUND` | nein | „Auflage nicht gefunden“ · Neu aufnehmen |
| `FACE_MISMATCH` | nein | „Foto passt nicht zur Auflage <Name>“ |
| `PhotoUnreadable` | nein | „Foto konnte nicht gelesen werden“ |
| `ModelUnavailable`, `ScanFailed` | nein | „Erkennung fehlgeschlagen“; Ursache per `Timber.e` |
| `Unsupported` | nein | kann nicht auftreten (Menüpunkt ausgeblendet); `Timber.w`, keine Meldung |
| `endId` passt nicht | nein | „Foto an Passe N abgelegt, nicht eingetragen“ |

„Neu aufnehmen“ startet Schritt 1 ohne Rückfrage, weil nichts eingetragen
wurde; bei 0 Pfeilen trotz vorhandener Treffer gilt die frühere Zustimmung
weiter. Das neue Foto wird zusätzlich abgelegt.

Die Konfidenz färbt nichts ein (Haupt-Design, v1).

## Bausteine

### Neu

- **`EndPhotoStore`** (`features/detection`):
  `store(original: File, dir: File): String` gibt den Dateinamen zurück.
  Maße über `inJustDecodeBounds`; dekodiert mit der größten Zweierpotenz als
  `inSampleSize`, bei der die lange Kante noch mindestens 2048 px hat; eine
  `Matrix` wendet die EXIF-Orientierung an (alle acht Werte, auch die
  gespiegelten, aus `androidx.exifinterface`) und skaliert auf genau 2048 px
  lange Kante, kleinere Fotos bleiben in ihrer Größe. JPEG, Qualität 90, als
  `File.createTempFile("img", ".jpg", dir)`. `Bitmap.compress` schreibt kein
  EXIF: Das abgelegte Foto enthält gedrehte Pixel ohne Orientierungstag, der
  Vertrag aus 8a für 8c. Die Pixelwerte weichen von `imread` ab (Skia gegen
  libjpeg), die Geometrie nicht; 8c skaliert die Homographie nur über das
  Verhältnis der Breiten. Die Größenrechnung (`inSampleSize`, Zielmaße) ist
  eine reine Funktion.
- **`ScanApplication`** (`features/detection`): reine Funktion
  `ScanOutcome × shotsPerEnd × (x, y) -> Ring -> ScanReport`. `ScanReport`
  trägt die einzutragenden Treffer oder `null` (nichts eintragen) und die
  Meldungsart samt Zahlen (erkannt, erwartet, Auflagenname). Die Tabelle oben
  steckt genau hier; die Activity setzt nur noch Text und Aktion.
- **`PendingScan`** mit `toBundle()` / `fromBundle()`.
- **`EndScanFlow`** (`features/training/input`): führt Ablauf 1 bis 4 aus,
  hält den Job und den Dialog. `InputActivity` ruft es an vier Stellen:
  Menüpunkt, `onActivityResult`, „`data` geladen“, `onSaveInstanceState`
  (plus Lesen in `onCreate`).

### Geändert

- `input_end.xml`: `action_scan_end`.
- `InputActivity`: die vier Aufrufe, Sichtbarkeit in `onPrepareOptionsMenu`.
- `TargetView.replaceWithEnd`: die Wahl wird zur reinen Funktion
  `inputMethodFor(shots, exact, setting)`:
  - keine Treffer gesetzt: `exact` → Plotting, sonst Tastatur (wie bisher);
  - Treffer gesetzt und `exact`: Plotting (neu, Entscheidung 6);
  - Treffer gesetzt, nicht `exact`: `setting` (wie bisher).
- Strings auf Englisch und Deutsch; die übrigen Sprachen fallen auf Englisch
  zurück.
- `app/build.gradle`: `androidTestImplementation` espresso-intents.

## Test

- **JVM, `:app`:**
  - `ScanApplication` für jede Zeile der Tabelle, darunter: 0 Pfeile mit
    Auflage ergibt `null`; die Liste hat immer `shotsPerEnd` Einträge; der
    Ring kommt aus der übergebenen Funktion.
  - `inputMethodFor` über alle Kombinationen (leer/gesetzt × exact/nicht ×
    beide Einstellungen).
  - Größenrechnung von `EndPhotoStore` für 1280, 2048, 2049, 4000, 8160 px
    (`inSampleSize` und Zielmaße).
- **Instrumentiert, S25:**
  - `EndPhotoStore` mit dem Korpusfoto aus 8a
    (`2026-08-15_bedeckt_frontal_02.jpg`, `exifOrientation` 6): Ergebnis
    1536 × 2048, kein Orientierungstag.
  - `PendingScan` über ein `Bundle` hin und zurück.
  - Ablauftest von `InputActivity` mit espresso-intents: Kamera-Intent
    abfangen, das Testfoto in die `EXTRA_OUTPUT`-URI kopieren, `RESULT_OK`.
    Erwartet: Treffer in der Datenbank, ein `EndImage` an der Passe, Original
    gelöscht, `TargetView` in Plotting. Fehlt das Foto, überspringt sich der
    Test wie in 8a.
- **Von Hand, S25** (Protokoll im Nachtrag):
  - Prozesstod während der Kamera („Aktivitäten nicht behalten“): Treffer
    landen in der richtigen Passe, genau ein Bild.
  - Drehen während des Scans: Scan startet neu, genau ein Bild.
  - Abbrechen im Dialog: nichts eingetragen, Foto abgelegt.
  - Rückgängig: vorherige Treffer in View und nach Neustart der Eingabe.
  - Rückfrage bei vorhandenen Treffern, Ablehnen macht kein Foto.
  - Foto einer Wand: „Auflage nicht gefunden“, Neu aufnehmen.
  - Tastatur-Einstellung: gescannte Passe erscheint als Plot, auch nach
    Verlassen und Wiederkehren.

## Was nicht Teil von 8b ist

- Sidecar `<fileName>.json` und Foto-Ebene im `TargetView` (8c). Die
  Homographie aus `DetectionResult.face` wird in 8b noch nicht gespeichert;
  8c schreibt sie, ohne Sidecar greift dort der Registrar auf dem
  abgelegten Foto.
- Nachscannen aus `GalleryActivity` und Debug-Bildschirm (8d).
- Andere Auflagen als WA-Vollauflage, Mehrspot, Lagesensor.
- Optische Markierung unsicherer Treffer.

## Offene Risiken

- **Speicher beim Ablegen.** `EndPhotoStore` dekodiert mit `inSampleSize`
  höchstens eine Bitmap mit gut 4096 px langer Kante (ARGB, rund 50 MB bei
  4096 × 3072) plus das 2048-px-Ergebnis, auf dem Java-Heap, vor dem Scan
  und nicht gleichzeitig mit dessen BGR-Mat. Für ein 50-MP-Foto (8160 px)
  ist `inSampleSize` 2, also 4080 px.
- **EasyImage und Prozesstod.** EasyImage merkt sich den Pfad der Aufnahme in
  `SharedPreferences`; dass `handleActivityResult` nach Prozesstod die Datei
  liefert, zeigt bisher nur der Galerieweg. Die Checkliste prüft es für die
  Eingabe.
- **Neue Plotting-Regel** (Entscheidung 6) ändert die Anzeige alter,
  geplotteter Passen für Nutzer mit Tastatur-Einstellung. Gewollt, aber
  sichtbar.
