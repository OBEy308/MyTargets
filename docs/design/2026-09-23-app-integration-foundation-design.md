# App-Integration, Teil 8a: Fundament — Design

Datum: 2026-09-23
Status: Design umgesetzt am 2026-09-24, siehe Nachtrag
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
es schon). Jede `ArrowModel`-Instanz trägt `source` (Pfad der Datei oder
Name des Assets); alle Fehlermeldungen von `ArrowModel` und
`LearnedArrowDetector` nennen `source`, nie mehr einen Pfad direkt. `onnx`
wird `File?` (`null` bei Bytes); `LearnedArrowDetector` hält nach dem Bau
des Netzes nur noch `source` und `meta`, nicht das `ArrowModel`, damit die
29 MB Bytes wieder frei werden. Für den Byte-Weg baut
`LearnedArrowDetector.readNet` ein `MatOfByte` als Spaltenvektor
(`create(n, 1, CV_8UC1)` und `put`), ohne weitere Kopie auf dem Java-Heap;
gegen ein synthetisches Foto liefert der Byte-Weg bitgleiche Treffer wie der
Datei-Weg. Die Datei liest `Dnn.readNetFromONNX` wie bisher über den Pfad;
beide Wege sind im AAR und im Desktop-Jar vorhanden. Das war im Design des
gelernten Finders so vorgesehen („nimmt Bytes oder Pfad“) und erspart der
App, das Modell auf die Platte zu kopieren.

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
  `MemoryInfo.totalMem` unter 6 GB (6 · 1024³ Byte). `MemoryInfo.totalMem`
  liegt unter dem nominellen RAM (ein 6-GB-Gerät meldet rund 5,5 GB, ein
  8-GB-Gerät rund 7,4 GB); mit dieser Schwelle ist Winograd also erst ab
  nominell 8 GB an, 6-GB-Geräte rechnen ohne. Die Schwelle selbst bleibt bei
  6 GiB, eine Spec-Zahl. Ist Winograd aus, rund 400 statt 700 MB nativ,
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
nicht geladen. `ScanFailed` fängt jede `Exception` aus der Erkennung, nicht
nur `CvException` und `IllegalStateException`: Die Pipeline wirft auch
`IllegalArgumentException` aus ihren `require`-Prüfungen, und OpenCVs JNI
wirft für einen nicht-cv-nativen Fehler (bestätigt für `std::bad_alloc` in
`libopencv_java4.so` 4.14.0) ein einfaches `java.lang.Exception`, keine
`RuntimeException`; ein `OutOfMemoryError` fängt `scan` ebenso ab. Einen
Fehler beim Dekodieren, den `PhotoInput` nicht schon als
`PhotoUnreadableException` erkennt, gibt `scan` ebenfalls als `ScanFailed`
weiter; ein defekter EXIF-Block zählt wie ein fehlender als „keine
Brennweite“. `shotsPerEnd <= 0` weist `scan` dagegen am Eingang mit
`require` zurück, das ist ein Fehler des Aufrufers, keiner des Fotos. Das
Modell ist da, der Nutzer soll nicht das Falsche lesen. Was der Nutzer
sieht, entscheidet 8b; 8a liefert nur die Unterscheidung, damit jeder Fall
eine eigene Antwort bekommen kann (Haupt-Design, *Fehlerfälle*).
`modelName` geht in 8c ins Sidecar, damit später klar ist, welches Modell
ein Ergebnis erzeugt hat.

### Build

- `:app`: `implementation project(':detection')`, `implementation libs.opencv.android`.
- ABI-Filter: Release `arm64-v8a`, `armeabi-v7a`; Debug zusätzlich `x86_64`.
- `:detection` bleibt `compileOnly` gegen das Desktop-Jar; die Importregel
  (`core` und `imgproc`, `dnn` nur unter `arrows/`) bleibt bestehen.
- R8: Keep-Regel für `de.dreier.mytargets.detection.arrows.ArrowModel$MetaJson`
  samt Feldern (Gson liest sie per Reflexion; `tools/rules-proguard.pro`
  hält bisher nur `Signature` und Annotationen), dazu `-keep class
  org.opencv.** { *; }`: OpenCVs nativer Code greift per Namen auf seine
  Java-Klassen zu, und das AAR 4.14.0 bringt keine eigenen Regeln mit
  (geprüft, es enthält keine `proguard.txt`). Beide Regeln kommen als
  `consumer-rules.pro` in `:detection`, dem Modul, das die Klasse kennt;
  `:app` bekommt sie über die Abhängigkeit.
- Das Modell wird aus dem Asset gelesen, nicht kopiert; `noCompress` ist
  dafür nicht nötig. fp16-Gewichte lassen sich kaum komprimieren, das APK
  wird durch die Komprimierung also weder kleiner noch größer.

## Datenfluss

```
EndPhotoScanner.get(context).scan(photo, target, shotsPerEnd)   [Dispatchers.Default]
  ├─ ScanSupport.supports(target)?        nein   → Unsupported
  ├─ DetectorHolder.withLoaded             Fehler → ModelUnavailable(cause)
  │    └─ ModelLoading.load: einmal pro Prozess OpenCV laden, Modell aus dem Asset lesen, Netz bauen
  ├─ PhotoInput.read(photo)               Fehler → PhotoUnreadable(cause)
  │    └─ imread: BGR-Mat, EXIF-gedreht, höchstens 4200 px lange Kante, + CameraIntrinsics
  ├─ detector.detect(mat, request)        unter dem Mutex; Mat danach freigeben
  │    jede Exception / OutOfMemoryError  → ScanFailed(cause)
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
- **Instrumentiert, `app/src/androidTest`, auf dem S25:** Das Korpusfoto
  `2026-08-15_bedeckt_frontal_02.jpg` liegt als nicht eingechecktes
  Test-Asset unter `app/src/androidTest/assets/scan/` (README dort
  beschreibt, wie es dahin kommt); fehlt es, überspringen sich nur die
  Scan-Fälle, die grauen, nicht-Bild- und nicht-unterstützten Fälle laufen
  trotzdem. Es läuft durch den echten `EndPhotoScanner` mit dem Modell aus
  den App-Assets. Erwartet: `Detected`, `face` vorhanden, `imageWidth` und
  `imageHeight` gleich der gedrehten Größe aus dem Sidecar (das prüft, dass
  `imread` im AAR die EXIF-Drehung anwendet wie am PC; das Foto hat
  `exifOrientation` 6), die Treffer nahe einer am PC gepinnten Referenz
  (`ScanReferenceRun` in `:detection`: derselbe Detektor über denselben
  Weg, Schranke 0,01), nicht der Korpuswahrheit direkt. Der Orchestrator
  lässt jede Testmethode in einem eigenen Prozess laufen
  (`clearPackageData`), das Modell wird also für jede Methode neu geladen;
  nur ein eigener Testfall (`scansTheCorpusPhotoLikeThePc`) misst die
  warme zweite Erkennung. Zeit und nativer Heap gehen ins Protokoll. Ein
  zweiter Fall: ein Bild ohne Auflage ergibt `Detected` mit
  `FACE_NOT_FOUND`. Ein dritter: eine Datei, die kein Bild ist, ergibt
  `PhotoUnreadable` — das ist der einzige hier gepinnte Lesefehler. Ein nach
  dem Kopf abgeschnittenes JPEG fällt nicht darunter: Es besteht
  `BitmapFactory`s Maße-Prüfung, libjpeg meldet das vorzeitige Ende nur als
  Warnung, und `imread` liefert ein teils graues, nicht leeres Bild zurück,
  das normal weiterläuft (kein Absturz, vermutlich `FACE_NOT_FOUND`; was der
  Nutzer davon sieht, zeigt 8b) — das ist hier ungetestet. Eine leere Datei
  und HEIC sind ebenfalls nur durch Lesen des Codes korrekt, nicht gepinnt.
  Ein vierter Fall prüft den Weg, den weder die Korpusfotos (alle unter
  4200 px) noch der Korpuslauf am PC (ohne `IMREAD_REDUCED_*`) berühren,
  aber jedes 50-MP-Foto nimmt: Dasselbe Foto mit `exifOrientation` 6 wird
  mit erzwungenem Faktor 2 gelesen und muss genau halb so groß ankommen wie
  die gedrehte Größe aus dem Sidecar (auf ganze Pixel aufgerundet, wie
  libjpeg skaliert). Dafür hat `PhotoInput` eine interne Variante mit
  vorgegebenem Faktor, die nur der Test benutzt; die App wählt den Faktor
  immer selbst.
- **Release:** `assembleDevRelease` und `bundleDevRelease` mit R8 laufen
  durch. Die Keep-Regel wird nicht mit einem Instrumentierungslauf gegen
  den minifizierten Build geprüft, sondern über `mapping.txt` und
  `usage.txt` des Release-Builds: `ArrowModel$MetaJson` behält seinen
  Namen, seine Felder bleiben erhalten, nur die ungenutzten,
  Kotlin-generierten Getter dürfen fallen.

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
`app/src/main/assets/arrows/<neuer Name>/`, `LearnedArrowCorpusRun` neu
pinnen, neue App-Version. Vier Stellen kennen den alten Namen und müssen
mitgezogen werden: `ModelLoading.MODEL_NAME`, der Asset-Ordner selbst
(`app/src/main/assets/arrows/<name>/`), der Fallback in
`detection/build.gradle` (`detection.model.dir`, der ohne gesetztes
`DETECTION_MODEL_DIR` auf den alten Ordner zeigt) und die beiden gepinnten
`REFERENCE`-Listen (`ScanReferenceRun.REFERENCE` und
`EndPhotoScannerDeviceTest.REFERENCE`). Wird nur der Ordner umbenannt, ohne
den Gradle-Fallback mitzuziehen, zeigt `detection.model.dir` auf einen Pfad,
der nicht mehr existiert — die Modelltests überspringen sich dann per
`assumeTrue` (grün, aber ungeprüft), statt fehlzuschlagen.

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

## Nachtrag 2026-09-24: umgesetzt und gemessen

Branch `plan/app-integration-foundation`. Vom Design weicht ab:
`LearnedArrowDetector.readNet` baut das Netz für den Byte-Weg aus einem
`MatOfByte` als Spaltenvektor (`create(n, 1, CV_8UC1)` und `put`), nicht über
ein `Mat` mit einer Zeile und `MatOfByte(Mat)`; `ModelLoading` liest das
Asset in ein Array genau passender Größe. So entsteht beim Laden keine
zweite Kopie der 29 MB auf dem Java-Heap. Gegen ein synthetisches Foto
liefert der Byte-Weg bitgleiche Treffer wie der Datei-Weg.
`EndPhotoScanner` fängt aus der Erkennung jede `Exception` ab, nicht nur
`RuntimeException`: OpenCVs JNI wirft für einen nicht-cv-nativen Fehler ein
einfaches `java.lang.Exception`. Ein unerwarteter Fehler beim Dekodieren
gibt ebenfalls `ScanFailed` (`PhotoUnreadableException` bleibt weiterhin
`PhotoUnreadable`), ein defekter EXIF-Block zählt wie ein fehlender als
„keine Brennweite“, und der Singleton `EndPhotoScanner.get` hält nur den
Application-Context fest. Der Gerätetest nutzt JUnits eigene Asserts statt
Truth: `:app` pinnt Guava 27.0.1-android für alle Konfigurationen, Truth
1.4.5 braucht Guava 31, also scheitert jede Truth-Prüfung in einem
instrumentierten Test von `:app` mit `NoSuchMethodError` — vorbestehend,
auch für die anderen instrumentierten Tests von `:app`. Er lädt OpenCV in
`@Before`, weil der Orchestrator jeder Testmethode einen eigenen Prozess
gibt. `consumer-rules.pro`: Das AAR 4.14.0 bringt keine eigenen Keep-Regeln
mit (geprüft, keine `proguard.txt` im AAR).

| Messung | Wert |
|---|---|
| Erster Scan auf dem S25 (mit Laden) | 3654 ms |
| Zweiter Scan | 2653 ms |
| Nativer Heap nach dem Scan | 673 MB |
| Winograd auf dem S25 | an (totalMem 11,1 GB) |
| Release-APK (zwei ABIs) | 77,1 MB |
| AAB | 55,1 MB |
| Referenz `2026-08-15_bedeckt_frontal_02` | 5 Treffer, größte Abweichung Telefon gegen PC 0,00008 |

`bundletool` stand auf dieser Maschine nicht zur Verfügung; die
Downloadgröße für `arm64-v8a` bleibt deshalb geschätzt (zwischen der
AAB-Größe und der Universal-APK-Größe), gemessen sind nur APK und AAB
selbst.

## Nachtrag 2026-09-24, später: Nacharbeiten nach dem Merge

- **Guava:** `:app` pinnt Guava jetzt auf 33.4.3-android statt 27.0.1-android.
  Der Pin bleibt nötig, weil AGP die Test-APK auf die Guava-Version der App
  festlegt und die App Guava über die Drive-Sicherung (`google-api-client`,
  verlangt bis 29.0) zieht; Truth 1.4.5 braucht mindestens 31.1. Damit laufen
  Truth-Prüfungen in den instrumentierten Tests von `:app` wieder, und
  `EndPhotoScannerDeviceTest` nutzt Truth wie `:detection`.
- **`ScanOutcome`** besteht aus Datenklassen (`Unsupported` ein `data
  object`): Protokollzeilen und gescheiterte Prüfungen zeigen Fall und
  Ursache.
- **Abbruch:** Ein Scan, dessen Aufrufer während des Wartens, Ladens oder
  Dekodierens abbricht, wirft `CancellationException` und startet den
  Vorwärtslauf nicht; das geladene Netz bleibt für den nächsten Scan. Ein
  Abbruch während des Vorwärtslaufs lässt diesen zu Ende laufen, OpenCV ist
  nicht unterbrechbar. Der breite `catch (Exception)` im Scanner reicht
  `CancellationException` durch. Gepinnt in
  `DetectorHolderTest.aCallerCancelledDuringTheLoadGetsNoBlockButTheLoadIsKept`
  und im Gerätetest `aScanCancelledBeforeTheForwardPassDoesNotRunIt`.
