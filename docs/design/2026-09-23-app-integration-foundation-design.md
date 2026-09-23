# App-Integration, Teil 8a: Fundament — Design

Datum: 2026-09-23
Status: Design, abgestimmt am 23.9. (Ansatz 1); Plan folgt in `docs/plans/`
Basis: Master `f7002b24` (Roll-Anker), Korpus `ac64539`, Modell `r4-all-2026-09`
Vorgänger: Haupt-Design `2026-09-09-arrow-detection-design.md` (Schritt 8,
*Integration in die App*, Nachtrag vom 23.9.), `2026-09-17-learned-finder-app-path.md`
(Preis des gelernten Finders), `2026-09-23-registration-roll-anchor-design.md`

## Ziel

Schritt 8 des Haupt-Designs ist zu groß für einen Plan. Er wird in vier
Teilprojekte geschnitten, jedes mit eigenem Design, Plan und PR:

| Teil | Inhalt |
|---|---|
| **8a Fundament** (dieses Dokument) | OpenCV und Modell in der App, ein Erkennungsdienst in `:app`, `DetectionResult` mit Registrierung |
| 8b Scan aus der Eingabe | Menüpunkt, Kamera, pending scan, `endId`-Prüfung, Fotoablage, Fehlerfälle, Snackbar mit Rückgängig |
| 8c Korrekturebene | Sidecar `<fileName>.json`, Foto als Ebene im `TargetView`, Deckkraft, Lupe |
| 8d Galerie und Debug | Nachscannen aus `GalleryActivity`, Debug-Bildschirm |

8a hat keine sichtbare Oberfläche. Es liefert eine einzige Methode, die 8b
aufruft, und weist auf dem Telefon nach, dass AAR, Modell-Asset, EXIF und
Speicher im echten APK tragen, bevor 8b darauf baut.

## Entscheidungen

1. **OpenCV aus Maven, wie es ist.** `org.opencv:opencv` 4.14.0 als AAR; es
   enthält `dnn`, das der gelernte Finder braucht. Ein eigener Build nur mit
   `core` und `imgproc` fällt damit weg, er müsste `dnn` mitbauen. Release
   nur `arm64-v8a` und `armeabi-v7a`, Debug zusätzlich `x86_64` (Haupt-Design,
   *APK-Größe*). F-Droid bleibt dokumentiert offen; die App ist dort nicht
   vertreten.
2. **Das Modell liegt im App-Repo**, als Asset
   `app/src/main/assets/arrows/r4-all-2026-09/` (`model.onnx` fp16, 28,8 MB,
   und `model.json`), ohne den Ordner `parity`. Die Gewichte enthalten keine
   Fotos; der Nutzer hat der Veröffentlichung am 23.9. zugestimmt.
3. **Kein Lagesensor.** Die Aufnahme läuft über die externe Kamera-App
   (`EasyImage`); im Moment der Aufnahme läuft MyTargets nicht vorn, und EXIF
   trägt nur Vierteldrehungen. Der Bild-Anker aus PR #17 ist die einzige
   Quelle der Rolle; Ansichten ohne Anker (22 von 90 im Korpus) bleiben ohne.
   Eine eigene Kamera mit Schwerkraftvektor ist ein mögliches späteres
   Teilprojekt.
4. **Nur der gelernte Finder läuft in der App.** Der klassische bleibt als
   Code für Korpusmessungen und Kandidatenmerkmale, die App ruft ihn nicht.
   Kein automatischer Rückfall: Er träfe ein Viertel der Pfeile, und der
   Nutzer könnte schlechte von guten Treffern nicht unterscheiden.
5. **Nur WA-Vollauflagen** (`WAFull.ID`, alle Durchmesser). Farbübergänge
   gibt es nur für sie, und das Modell hat nur sie gesehen. Für jede andere
   Auflage bietet 8b den Scan nicht an. Weitere Auflagen kommen jeweils mit
   eigenen Korpusfotos.
6. **Die Android-Anbindung liegt in `:app`**, `:detection` bleibt ohne
   `Context` (Haupt-Design, *Modulschnitt*). Ein eigenes Modul
   `:detection-android` lohnt für drei, vier Klassen nicht, die nur `:app`
   nutzt.

## Bausteine

### In `:detection`

`DetectionResult` bekommt die Registrierung, die die Korrekturebene (8c) und
ihr Sidecar brauchen:

```kotlin
class DetectedFace(
    val imageToTarget: Mat3,   // Pixel des übergebenen Bildes -> spot-lokale Auflagenkoordinaten
    val imageWidth: Int,
    val imageHeight: Int,
    val roll: Roll             // angewendet oder nicht, samt Winkel
)

class DetectionResult(
    val shots: List<DetectedShot>,
    val faceConfidence: Float,
    val reason: SelectionReason?,
    val failure: DetectionFailure?,
    val face: DetectedFace?    // null genau dann, wenn failure != null
)
```

`LearnedArrowDetector.detect` und `OpenCvArrowDetector.detect` reichen die
Werte aus ihrer Registrierung durch; `DetectionResult.failed` setzt `null`.
Die Homographie bezieht sich auf das Bild, das `detect` bekam, also auf das
dekodierte, nicht auf die Datei in Originalgröße (siehe *Foto lesen*).

Die Übersetzung von Auflage in Anfrage wandert aus den Korpustests nach
`:detection`, damit App und Korpuslauf dieselbe benutzen:
`DetectionRequests.waFull(shotsPerEnd, intrinsics)` mit `FaceLayout.singleSpot()`,
`WaFullZones.RADII` und `RingTransitions.WA_FULL`. `ArrowCorpusRuns.requestFor`
ruft sie auf. `WaFullZones` liegt heute im Testcode
(`detection/src/test/.../arrows/WaFullZones.kt`) und zieht mit nach `main` um;
`RingTransitions.WA_FULL` liegt schon dort.

### In `:app`, Paket `de.dreier.mytargets.features.detection`

- **`ScanSupport.supports(target: Target): Boolean`** — `target.id == WAFull.ID`.
  Die Wertungsart spielt keine Rolle; der Ringwert entsteht wie bisher aus
  x/y. 8b blendet damit den Menüpunkt aus.
- **`ArrowModelInstaller`** — kopiert `assets/arrows/<name>/` beim ersten
  Gebrauch nach `noBackupFilesDir/arrows/<name>/` (nicht ins Backup: 29 MB,
  jederzeit aus dem APK wiederherstellbar). Stimmen Name und Dateigröße, wird
  nicht kopiert. Kopiert wird in einen Nebenordner und dann umbenannt, damit
  ein abgebrochener Kopiervorgang keinen halben Ordner hinterlässt. Alte
  Modellordner mit anderem Namen werden gelöscht. Liefert
  `ArrowModel.load(dir)` und den Modellnamen.
- **`DetectorHolder`** — ein Objekt pro Prozess. Beim ersten Gebrauch:
  `OpenCVLoader.initLocal()`, Modell installieren, `LearnedArrowDetector`
  erzeugen. Winograd an, außer bei `ActivityManager.isLowRamDevice()` oder
  `memoryClass < 256` (MB): dann aus, rund 400 statt 700 MB nativ, 1,2 statt
  0,5 s auf dem S25 (app-path-Befunde, 3a). Scheitert das Laden, bleibt der
  Holder leer und versucht es beim nächsten Scan erneut. Erkennungen laufen
  nacheinander hinter einem `Mutex`; der Finder ist nicht threadsicher. Das
  Netz hat kein `close()` und lebt bis zum Prozessende, gewollt.
- **`PhotoInput`** — liest eine Datei in ein 8-Bit-BGR-`Mat`, gedreht nach
  EXIF, und bildet die `CameraIntrinsics` (siehe *Foto lesen*).
- **`EndPhotoScanner`** — die eine Methode, die 8b aufruft:

```kotlin
suspend fun scan(photo: File, target: Target, shotsPerEnd: Int): ScanOutcome

sealed class ScanOutcome {
    class Detected(val result: DetectionResult, val modelName: String) : ScanOutcome()
    object Unsupported : ScanOutcome()                         // Auflage nicht WA-Vollauflage
    class PhotoUnreadable(val cause: Throwable) : ScanOutcome()
    class ModelUnavailable(val cause: Throwable) : ScanOutcome() // Laden gescheitert, kein Speicher
}
```

`Detected` umfasst auch die Fälle `FACE_NOT_FOUND` und `FACE_MISMATCH`; sie
stehen im `result`. Was der Nutzer sieht, entscheidet 8b; 8a liefert nur die
Unterscheidung, damit jeder Fall eine eigene Antwort bekommen kann (Haupt-Design,
*Fehlerfälle*). `modelName` geht in 8c ins Sidecar, damit später klar ist,
welches Modell ein Ergebnis erzeugt hat.

### Build

- `:app`: `implementation project(':detection')`, `implementation libs.opencv.android`.
- ABI-Filter: Release `arm64-v8a`, `armeabi-v7a`; Debug zusätzlich `x86_64`.
- `:detection` bleibt `compileOnly` gegen das Desktop-Jar; die Importregel
  (nur `core`, `imgproc`, `dnn`) bleibt bestehen.
- R8: Keep-Regel für `de.dreier.mytargets.detection.arrows.ArrowModel$MetaJson`
  (Gson liest sie per Reflexion) in `tools/rules-proguard.pro`, sofern
  `:detection` keine eigene `consumer-rules.pro` bekommt; die
  Consumer-Regel im Bibliotheksmodul ist der sauberere Ort und wird bevorzugt.
- Die Assets werden nicht komprimiert abgelegt (`noCompress 'onnx'`), damit
  das Kopieren nicht zusätzlich entpackt.

## Datenfluss

```
EndPhotoScanner.scan(photo, target, shotsPerEnd)          [Dispatchers.Default]
  ├─ ScanSupport.supports(target)?        nein   → Unsupported
  ├─ DetectorHolder.get()                 Fehler → ModelUnavailable(cause)
  │    └─ einmal pro Prozess: OpenCV laden, Modell installieren, Netz lesen
  ├─ PhotoInput.read(photo)               Fehler → PhotoUnreadable(cause)
  │    └─ BGR-Mat, EXIF-gedreht, höchstens 6000 px lange Kante, + CameraIntrinsics
  ├─ detector.detect(mat, request)        unter dem Mutex; Mat danach freigeben
  │    OutOfMemoryError / CvException     → ModelUnavailable(cause)
  └─ Detected(result, modelName)
```

### Foto lesen

- Dekodiert wird mit `BitmapFactory` und `inSampleSize`: halbieren, solange
  die lange Kante über **6000 px** liegt. `inSampleSize` wirkt nur in
  Zweierpotenzen; eine Grenze bei 4000 px hätte die 4160-px-Fotos des
  Korpus auf 2080 px halbiert. Im Korpus: 147 Fotos mit 4000 px, 12 mit
  4160, 4 mit 1280 — alle bleiben unverkleinert. Ein 50-MP-Foto (8160 px)
  kommt mit 4080 px an. Registrar (`WorkingScale`) und Modell
  (`preShrinkMaxSide` 2000) verkleinern danach selbst.
- EXIF-Drehung über `androidx.exifinterface` (in `:app` schon vorhanden),
  angewendet auf das Mat (`Core.rotate`), nicht über eine Bitmap-Matrix, um
  keine zweite Bitmap-Kopie zu halten. Die Bitmap wird nach `Utils.bitmapToMat`
  sofort freigegeben; `bitmapToMat` liefert RGBA, danach `cvtColor` nach BGR.
- Kameramatrix: `CameraIntrinsics.from35mmEquivalent(width, height, f35)` mit
  der Breite und Höhe des **dekodierten, gedrehten** Bildes; die
  35-mm-Äquivalenz ist unabhängig von der Pixelzahl, das Herunterskalieren
  ist also schon berücksichtigt. Fehlt `FocalLengthIn35mmFilm`,
  `CameraIntrinsics.approximate(width, height)` (0,75 · lange Kante).

## Test

- **JVM, `:detection`:** `DetectionResult` trägt `face`; beide Finder reichen
  Homographie, Bildgröße und `Roll` durch, `failed` setzt `null`. Die
  Korpusläufe laufen unverändert über `DetectionRequests.waFull` (Pins
  bleiben gleich, das ist die Prüfung, dass die Übersetzung nur umgezogen ist).
- **JVM, `:app`:** `:app` hat kein Robolectric. Geprüft werden reine
  Funktionen: `inSampleSize` für 1280, 4000, 4160, 8160, 16320 px; die Wahl
  von Winograd aus `isLowRam` und `memoryClass`; `ScanSupport` über die
  Auflagen-ID.
- **Instrumentiert, `app/src/androidTest`, auf dem S25:** Ein Korpusfoto mit
  Sidecar wird per `adb push` aufs Telefon gelegt und per
  Instrumentierungs-Argument benannt; ohne Argument überspringt sich der
  Test. Es läuft durch den echten `EndPhotoScanner` mit dem Modell aus den
  App-Assets. Erwartet: `Detected`, `face` vorhanden, Trefferzahl und Lage
  nahe der Wahrheit aus dem Sidecar (Schranke wie im Korpuslauf). Zeit und
  nativer Heap gehen ins Protokoll. Ein zweiter Fall: ein Bild ohne Auflage
  ergibt `Detected` mit `FACE_NOT_FOUND`.
- **Release:** `assembleRelease` mit R8 läuft durch; der Instrumentierungstest
  läuft einmal gegen den minifizierten Build (`testBuildType`), damit die
  Gson-Keep-Regel am Gerät geprüft ist. Ist das mit der vorhandenen
  Build-Konfiguration nicht machbar, prüft der Plan die Regel stattdessen
  im entpackten APK (`MetaJson` samt Feldnamen erhalten).

## Was nicht Teil von 8a ist

- Alles mit Oberfläche: Menüpunkt, Fortschritt, Meldungen (8b), Ebene (8c),
  Galerie und Debug-Bildschirm (8d).
- Fotoablage und Sidecar: 8b legt das Foto ab, 8c schreibt das Sidecar.
  8a liefert dafür `face` und `modelName`.
- **Die zurückgestellten Punkte des Roll-Ankers:** die Radius-Maske
  vorberechnen (nur, wenn der Gerätetest zeigt, dass der Anker spürbar Zeit
  kostet; die Zeit steht im Protokoll), ein Schutz gegen eine starke
  Strohkante (braucht erst Korpusfälle), die Anker-Winkel der Ansichten ohne
  Spitzen berichten (reine Messung).
- Mehrspot-Auflagen und andere Auflagen als WA-Vollauflage.
- Der Lagesensor (Entscheidung 3).

## Nachtraining

Das Modell ist ein austauschbares Asset. Ein neues Modell heißt: `train.py
--all` und `export_onnx.py` im Korpus-Repo, Ordner nach
`app/src/main/assets/arrows/<neuer Name>/`, `DetectorHolder` auf den Namen
umstellen, `LearnedArrowCorpusRun` neu pinnen, neue App-Version.

Die Serie vom 17.9. ist der einzige Testsatz, den `r4-all-2026-09` nie
gesehen hat (52,3 % schräg). Nachtrainiert wird deshalb erst mit der
nächsten Fotoserie: dann geht der 17.9. ins Training, und die neue Serie
wird Testsatz.

Später senkt die App den Aufwand fürs Annotieren: Jede korrigierte Passe
trägt Foto, Homographie (Sidecar, 8c) und die korrigierten Treffer, also
fertige Labels. Ein Export „Fotos plus korrigierte Treffer“ ins
Korpusformat ist ein eigenes späteres Teilprojekt; 8a bereitet ihn nur vor,
indem der Modellname mitgeführt wird.

## Offene Risiken

- **Speicher auf schwachen Geräten.** Gemessen ist nur das S25. Mit Winograd
  aus rund 400 MB nativ plus bis zu rund 80 MB für das Foto als BGR-Mat
(6000 × 4500 px), kurzzeitig zusätzlich die ARGB-Bitmap (rund 110 MB); auf einem Gerät mit
  3 bis 4 GB kann das Betriebssystem den Prozess trotzdem beenden. Der
  Gerätetest protokolliert den Heap; ein Mittelklassegerät wird gemessen,
  sobald eines verfügbar ist.
- **APK-Größe.** Rund 50 MB für ein Universal-APK mit OpenCV (geschätzt) plus
  29 MB Modell. Über ein App Bundle bekommt jeder Nutzer nur seine
  Architektur, das Modell aber immer. Der Plan misst das gebaute Release.
- **Erste Erkennung dauert länger.** Modell kopieren (29 MB) und Netz lesen
  kommen einmal pro Installation bzw. Prozess dazu. 8b zeigt dafür dieselbe
  Fortschrittsanzeige.
