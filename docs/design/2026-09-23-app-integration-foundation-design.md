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
   vertreten. Die Importregel (`core` und `imgproc` überall, `dnn` nur unter
   `arrows/`, `OpenCvImportRuleTest`) gilt für `:detection`, weil es gegen das
   Desktop-Jar kompiliert; `:app` kompiliert gegen das AAR und nutzt daraus
   auch `imgcodecs` (Foto lesen) und `android` (`OpenCVLoader`).
2. **Das Modell liegt im App-Repo**, als Asset
   `app/src/main/assets/arrows/r4-all-2026-09/` (`model.onnx` fp16, 28,8 MB,
   und `model.json`), ohne den Ordner `parity`. Die Gewichte enthalten keine
   Fotos; der Nutzer hat der Veröffentlichung am 23.9. zugestimmt. Das ersetzt
   den Satz aus dem Design des gelernten Finders (*Trainings- und
   Asset-Kette*), die App kopiere das Modell beim Build aus
   `DETECTION_MODEL_DIR`: Der Build braucht dann keine lokale Property, und
   jeder Stand des App-Repos baut dasselbe APK. Der Preis: Jedes Nachtraining
   ersetzt die Datei und legt 29 MB dauerhaft in die Git-Historie. Bei einem
   Modell je paar Monate tragbar; wird es lästig, kommt Git LFS für
   `*.onnx`, keine Rückkehr zur Build-Property.
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
Werte aus ihrer Registrierung durch; `DetectionResult.failed` setzt `null`
(auch bei `FACE_MISMATCH`: der Registrar bricht dort vor der Homographie ab).
Die Homographie bezieht sich auf das Bild, das `detect` bekam, also auf das
dekodierte: nach EXIF gedreht und eventuell verkleinert, nicht auf die Datei
in Originalgröße (siehe *Foto lesen*). `imageWidth` und `imageHeight` sind
die Maße dieses Bildes. Vertrag für 8b und 8c: Das abgelegte 2048-px-Foto
(Haupt-Design, *Fotoablage*) enthält die gedrehten Pixel und keinen
EXIF-Orientierungstag mehr, sodass 8c die Homographie nur über das
Verhältnis der Breiten skaliert und EXIF nie ein zweites Mal auslegt.

`ArrowModel` bekommt neben dem Ordner eine zweite Quelle: die Gewichte als
Bytes und das Sidecar als String (`ArrowModel.parseMeta(json, source)` gibt
es schon). `LearnedArrowDetector` liest Bytes über
`Dnn.readNetFromONNX(MatOfByte)`, die Datei wie bisher über den Pfad; beide
Wege sind im AAR und im Desktop-Jar vorhanden. Die Fehlermeldungen nennen
statt des Pfads einen Quellnamen. Das war im Design des gelernten Finders
so vorgesehen („nimmt Bytes oder Pfad“) und erspart der App, das Modell auf
die Platte zu kopieren.

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
- **`DetectorHolder`** — ein Objekt pro Prozess. Beim ersten Gebrauch:
  `OpenCVLoader.initLocal()`, `assets/arrows/<name>/model.onnx` und
  `model.json` über den `AssetManager` in den Speicher lesen, daraus das
  `ArrowModel` aus Bytes, `LearnedArrowDetector` erzeugen. Kein Kopieren auf
  die Platte, kein Installer, nichts aufzuräumen; das Byte-Array und das
  `MatOfByte` (je 29 MB) leben nur während des Lesens. Winograd an, außer das
  Gerät hat wenig Speicher: `ActivityManager.isLowRamDevice()` oder
  `MemoryInfo.totalMem` unter 6 GB. Dann aus, rund 400 statt 700 MB nativ,
  1,2 statt 0,5 s auf dem S25 (app-path-Befunde, 3a). Der Gesamtspeicher ist
  das Maß, nicht `memoryClass`: Die ist die Java-Heap-Grenze der App, das Netz
  liegt im nativen Speicher, und 4-GB-Geräte melden oft dieselben 256 MB wie
  8-GB-Geräte. Die Wahl ist eine reine Funktion von (`isLowRam`, `totalMem`).
  Scheitert das Laden, bleibt der Holder leer und versucht es beim nächsten
  Scan erneut. Erkennungen laufen nacheinander hinter einem `Mutex`; der
  Finder ist nicht threadsicher. Das Netz hat kein `close()` und lebt bis zum
  Prozessende, gewollt.
- **`PhotoInput`** — liest eine Datei mit `Imgcodecs.imread` in ein
  8-Bit-BGR-`Mat`, nach EXIF gedreht und bei Bedarf im Dekoder verkleinert,
  und bildet die `CameraIntrinsics` (siehe *Foto lesen*).
- **`EndPhotoScanner`** — die eine Methode, die 8b aufruft:

```kotlin
suspend fun scan(photo: File, target: Target, shotsPerEnd: Int): ScanOutcome

sealed class ScanOutcome {
    class Detected(val result: DetectionResult, val modelName: String) : ScanOutcome()
    object Unsupported : ScanOutcome()                         // Auflage nicht WA-Vollauflage
    class PhotoUnreadable(val cause: Throwable) : ScanOutcome()   // imread liefert ein leeres Mat
    class ModelUnavailable(val cause: Throwable) : ScanOutcome() // OpenCV oder Netz nicht geladen
    class ScanFailed(val cause: Throwable) : ScanOutcome()       // die Erkennung selbst brach ab
}
```

`Detected` umfasst auch die Fälle `FACE_NOT_FOUND` und `FACE_MISMATCH`; sie
stehen im `result`. `ModelUnavailable` heißt nur: OpenCV oder das Netz sind
nicht geladen. Eine `CvException` oder ein `OutOfMemoryError` mitten in der
Erkennung ist `ScanFailed`; das Modell ist da, der Nutzer soll nicht das
Falsche lesen. Was der Nutzer sieht, entscheidet 8b; 8a liefert nur die
Unterscheidung, damit jeder Fall eine eigene Antwort bekommen kann (Haupt-Design,
*Fehlerfälle*). `modelName` geht in 8c ins Sidecar, damit später klar ist,
welches Modell ein Ergebnis erzeugt hat.

### Build

- `:app`: `implementation project(':detection')`, `implementation libs.opencv.android`.
- ABI-Filter: Release `arm64-v8a`, `armeabi-v7a`; Debug zusätzlich `x86_64`.
- `:detection` bleibt `compileOnly` gegen das Desktop-Jar; die Importregel
  (`core` und `imgproc`, `dnn` nur unter `arrows/`) bleibt bestehen.
- R8: Keep-Regel für `de.dreier.mytargets.detection.arrows.ArrowModel$MetaJson`
  samt Feldern (Gson liest sie per Reflexion; `tools/rules-proguard.pro`
  hält bisher nur `Signature` und Annotationen). Sie kommt als
  `consumer-rules.pro` in `:detection`, dem Modul, das die Klasse kennt;
  `:app` bekommt sie über die Abhängigkeit.
- Das Modell wird aus dem Asset gelesen, nicht kopiert; `noCompress` ist
  dafür nicht nötig. fp16-Gewichte lassen sich kaum komprimieren, das APK
  wird durch die Komprimierung also weder kleiner noch größer.

## Datenfluss

```
EndPhotoScanner.scan(photo, target, shotsPerEnd)          [Dispatchers.Default]
  ├─ ScanSupport.supports(target)?        nein   → Unsupported
  ├─ DetectorHolder.get()                 Fehler → ModelUnavailable(cause)
  │    └─ einmal pro Prozess: OpenCV laden, Modell aus dem Asset lesen, Netz bauen
  ├─ PhotoInput.read(photo)               Fehler → PhotoUnreadable(cause)
  │    └─ imread: BGR-Mat, EXIF-gedreht, höchstens 4200 px lange Kante, + CameraIntrinsics
  ├─ detector.detect(mat, request)        unter dem Mutex; Mat danach freigeben
  │    OutOfMemoryError / CvException     → ScanFailed(cause)
  └─ Detected(result, modelName)
```

### Foto lesen

- Dekodiert wird mit `Imgcodecs.imread` aus dem AAR, nicht mit
  `BitmapFactory`. Das ist derselbe Dekoder wie im Korpuslauf
  (`ArrowCorpusRuns` liest mit `imread`), die App sieht also dieselben Pixel
  wie die Pins; Skia und libjpeg dekodieren nicht bitgleich. `imread` wendet
  die EXIF-Drehung selbst an (die Korpustests bauen darauf:
  `CorpusPhotos.checkDecodedSize` vergleicht die gedrehte Größe mit dem
  Sidecar) und liefert direkt BGR. Keine Bitmap, kein `bitmapToMat`, kein
  `cvtColor`, keine zweite Kopie des Bildes im Speicher. Ein leeres `Mat`
  heißt `PhotoUnreadable`.
- Verkleinert wird im Dekoder über `IMREAD_REDUCED_COLOR_2`, `_4` und `_8`:
  halbieren, solange die lange Kante über **4200 px** liegt. Die Maße kommen
  vorab aus dem Dateikopf (`BitmapFactory` mit `inJustDecodeBounds`, das
  dekodiert nichts); der Faktor ist eine reine Funktion der langen Kante,
  höchstens 8. Nichts in der
  Pipeline nutzt mehr als 2000 px: `WorkingScale` arbeitet bei 1600, der
  Roll-Anker auf dem Arbeitsbild, das Modell schrumpft auf
  `preShrinkMaxSide` 2000 vor. Die einzige Bedingung ist, dass Korpusfotos
  unverkleinert bleiben, damit App und Pins dasselbe sehen: 147 Fotos mit
  4000 px, 12 mit 4160, 4 mit 1280 — alle unter 4200. Ein 50-MP-Foto
  (8160 px) kommt mit 4080 px an, 16320 px mit 4080 über `_4`; Fotos
  zwischen 4200 und 8400 px werden auf 2100 bis 4200 px halbiert, immer noch
  über dem Vorschrumpfen. Eine Grenze bei 6000 px hätte dieselben Fotos
  unverkleinert gelassen, aber das BGR-Mat auf gut 80 MB statt gut 40 MB
  wachsen lassen.
- Kameramatrix: `CameraIntrinsics.from35mmEquivalent(width, height, f35)` mit
  der Breite und Höhe des **dekodierten, gedrehten** Bildes; die
  35-mm-Äquivalenz ist unabhängig von der Pixelzahl, das Herunterskalieren
  ist also schon berücksichtigt. `FocalLengthIn35mmFilm` kommt aus
  `androidx.exifinterface` (in `:app` schon vorhanden), der einzige Grund,
  EXIF selbst zu lesen. Fehlt der Wert,
  `CameraIntrinsics.approximate(width, height)` (0,75 · lange Kante).

## Test

- **JVM, `:detection`:** `DetectionResult` trägt `face`; beide Finder reichen
  Homographie, Bildgröße und `Roll` durch, `failed` setzt `null`. Die
  Korpusläufe laufen unverändert über `DetectionRequests.waFull` (Pins
  bleiben gleich, das ist die Prüfung, dass die Übersetzung nur umgezogen ist).
- **JVM, `:detection`, dazu:** `ArrowModel` aus Bytes liefert dieselbe
  Erkennung wie aus dem Ordner (ein Korpusfoto, gleiche Treffer); die
  Fehlermeldungen nennen den Quellnamen.
- **JVM, `:app`:** `:app` hat kein Robolectric. Geprüft werden reine
  Funktionen: der Verkleinerungsfaktor für 1280, 4000, 4160, 4200, 4201,
  8160, 16320 und 40000 px (1, 1, 1, 1, 2, 2, 4, 8); die Wahl von Winograd
  aus `isLowRam` und `totalMem`; `ScanSupport` über die Auflagen-ID.
- **Instrumentiert, `app/src/androidTest`, auf dem S25:** Ein Korpusfoto mit
  Sidecar wird per `adb push` aufs Telefon gelegt und per
  Instrumentierungs-Argument benannt; ohne Argument überspringt sich der
  Test. Es läuft durch den echten `EndPhotoScanner` mit dem Modell aus den
  App-Assets. Erwartet: `Detected`, `face` vorhanden, `imageWidth` und
  `imageHeight` gleich der gedrehten Größe aus dem Sidecar (das prüft, dass
  `imread` im AAR die EXIF-Drehung anwendet wie am PC; ein Foto mit
  `exifOrientation` 6 wählen), Trefferzahl und Lage nahe der Wahrheit aus
  dem Sidecar (Schranke wie im Korpuslauf). Zeit und nativer Heap gehen ins
  Protokoll. Ein zweiter Fall: ein Bild ohne Auflage ergibt `Detected` mit
  `FACE_NOT_FOUND`. Ein dritter: eine Datei, die kein Bild ist, ergibt
  `PhotoUnreadable`. Ein vierter prüft den Weg, den weder die Korpusfotos
  (alle unter 4200 px) noch der Korpuslauf am PC (ohne `IMREAD_REDUCED_*`)
  berühren, aber jedes 50-MP-Foto nimmt: Dasselbe Foto mit
  `exifOrientation` 6 wird mit erzwungenem Faktor 2 gelesen und muss genau
  halb so groß ankommen wie die gedrehte Größe aus dem Sidecar (auf ganze
  Pixel aufgerundet, wie libjpeg skaliert). Dafür hat `PhotoInput` eine
  interne Variante mit vorgegebenem Faktor, die nur der Test benutzt; die
  App wählt den Faktor immer selbst.
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
  aus rund 400 MB nativ plus bis zu rund 40 MB für das Foto als BGR-Mat
  (4200 × 3150 px); eine zweite Kopie des Bildes gibt es mit `imread` nicht.
  Auf einem Gerät mit 3 bis 4 GB kann das Betriebssystem den Prozess
  trotzdem beenden. Der Gerätetest protokolliert den Heap; ein
  Mittelklassegerät wird gemessen, sobald eines verfügbar ist.
- **Downloadgröße.** Über das App Bundle bekommt jeder Nutzer nur seine
  Architektur, das Modell aber immer: gemessen 15,6 MB für `arm64-v8a` mit
  OpenCV (Haupt-Design, *APK-Größe*) plus 29 MB Modell, rund 45 MB gegen
  5,4 MB heute. Das Universal-APK mit zwei ABIs läge bei rund 80 MB. Der
  Plan misst das gebaute Release.
- **Erste Erkennung dauert länger.** Das Netz aus dem Asset zu lesen kommt
  einmal pro Prozess dazu (29 MB aus dem APK plus 0,05 s für das Netz auf
  dem S25). 8b zeigt dafür dieselbe Fortschrittsanzeige.
- **Bildformate.** Ob `imread` im AAR HEIC/HEIF liest, ist nicht geprüft;
  vermutlich nicht. Für 8b spielt das keine Rolle: Die Kamera-App schreibt
  über den Aufnahme-Intent ein JPEG. Für das Nachscannen aus der Galerie
  (8d) kann es zählen, je nachdem, in welchem Format gespeicherte Fotos
  vorliegen. Entschieden wird es mit 8d; bis dahin ergibt ein Format, das
  `imread` nicht kennt, `PhotoUnreadable`.
