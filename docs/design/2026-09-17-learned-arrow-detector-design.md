# Plan 3d: Gelernter Pfeilfinder hinter `ArrowDetector` — Design

Datum: 2026-09-17
Status: Design, abgestimmt; Plan folgt in `docs/plans/`
Basis: Master `e1dc042b` (Befunde des PoC bis zur Telefonmessung), Korpus `e4fb964` (Lauf r4, `export_onnx.py`)
Vorgänger: `2026-09-15-learned-selection-findings.md`, `2026-09-17-learned-finder-app-path.md`

## Ziel

Das Heatmap-Modell des PoC (Lauf r4: 71 % der schrägen Pfeile bei 1,06
Fehlfunden je Ansicht, 63 % bei 0,64, Ring 92 % richtig) wird zu einer zweiten
Implementierung der `ArrowDetector`-Schnittstelle in `:detection`, gemessen
mit demselben Korpuslauf und denselben Kennzahlen wie der klassische Finder.
Der klassische Finder bleibt daneben bestehen. Der Einbau in die App
(Schritt 8 der Haupt-Spec) ist nicht Teil dieses Plans; er bekommt hier nur
die Schnittstelle, die er braucht.

## Entscheidungen

Abgestimmt am 17.9.2026:

1. **Gewichte im Korpus-Repo, per Build-Property.** Wie `DETECTION_CORPUS_DIR`
   heute; keine Binärdatei in der Historie der App, die private Herkunft
   (Korpus mit GPS-Daten) bleibt privat.
2. **Eigene Klasse, klassischer Finder bleibt daneben.** Kein Mischbetrieb:
   Die Heatmap-Spitzen laufen nicht durch Schaftlauf oder
   `CandidateSelection`, damit die Korpuszahl der App die des PoC bleibt.
3. **Laufzeit OpenCV DNN**, das im ausgelieferten AAR steckt (Befund vom
   17.9.); keine neue Abhängigkeit. `:detection` darf dafür `org.opencv.dnn`
   importieren.

## Architektur und Datenfluss

Neue Klasse `LearnedArrowDetector` im Paket `de.dreier.mytargets.detection.arrows`,
implementiert `ArrowDetector`. Konstruktor: `FaceRegistrar` (Standard
`OpenCvFaceRegistrar`, wie beim klassischen), ein `ArrowModel` (Gewichte und
Beilage, siehe *Ablageformat*) und `winograd: Boolean = true`. Das Netz wird im
Konstruktor einmal gelesen (`Dnn.readNetFromONNX`), `enableWinograd` gesetzt,
und lebt so lange wie die Instanz.

Ablauf je Foto in `analyse(image, request, debug)`, analog zum klassischen:

1. **Registrierung** wie heute über den `FaceRegistrar`; `Failed` wird wie
   heute zu `DetectionResult.failed`.
2. **Entzerrung fest auf die Modellgröße:** `FaceWarp.warp(image,
   imageToTarget, edge = model.inputSize)`. Vorher wird das Original wie in
   `prepare.py` mit `INTER_AREA` so weit verkleinert, dass die Auflage etwa
   die Zielgröße hat, damit App und Training dieselben Pixel sehen (der Warp
   allein unterabtastet ein 4000-px-Foto auf 768 px ohne Filter). Der Rahmen
   ist derselbe wie im PoC: `[-1,1; 1,1]²`, Pixelabbildung
   `k·(t + EXTENT) − 0,5`, geprüft am 17.9.
3. **Eingabe:** BGR nach RGB, `Dnn.blobFromImage` mit Skalierung 1/255 und
   Mittelwert 0, danach je Kanal `(x − mean_c) / std_c` mit den
   ImageNet-Werten aus der Beilage. Nicht die Näherung des Zeittests mit einer
   Standardabweichung für alle Kanäle.
4. **Vorwärtslauf:** `net.setInput`, `net.forward()`, Ausgabe
   `1 × 2 × H/2 × W/2` Logits; Kanal 0 durch die Sigmoid ist die
   Spitzen-Heatmap (Kanal 1, der Schaft, wird nicht benutzt).
5. **Spitzen:** `HeatmapPeaks.find(heat, stride = 2, kernel = 5, threshold,
   maxCount = request.expectedShots)`: lokale Maxima (Wert gleich dem Maximum
   im 5×5-Fenster und mindestens Schwelle), Subpixel-Schwerpunkt über das
   3×3-Fenster, Rückgabe `(u, v, wert)` in Eingabepixeln, nach Wert absteigend,
   auf `maxCount` gekürzt. Das ist `peaks()` aus `train.py`, Zeile für Zeile.
6. **Rückrechnung** aus Eingabepixeln in Auflagenkoordinaten (Umkehrung der
   Abbildung aus Schritt 2), Spot-Zuordnung über `SpotMapping` wie heute,
   Ausgabe `DetectedShot(faceIndex, x, y, confidence = Heatmap-Wert)`.
   `SelectionReason`: `COMPLETE`, wenn `expectedShots` Spitzen über der
   Schwelle lagen, sonst `FEWER_THAN_EXPECTED`; `SPOT_OVERFLOW`, wenn ein Spot
   mehr Spitzen als `maxArrowsPerSpot` bekäme (die überzähligen mit dem
   kleinsten Wert fallen weg). `AMBIGUOUS_SURPLUS` kommt nicht vor, weil die
   Höchstzahl vor der Schwelle greift; ein neuer Grund wird nicht eingeführt.

`analyse` liefert ein `LearnedAnalysis` mit Registrierung, Heatmap-Spitzen,
Ausgabe und Zeiten je Stufe (Registrierung, Warp, Netz, Spitzen), so dass der
Korpuslauf und der spätere Debug-Bildschirm dieselben Daten sehen wie beim
klassischen Finder.

**Debug-Bilder** über `DebugSink`, im Stil von `ArrowDebugImages`: die
Heatmap als rotes Glühen über dem entzerrten Bild, die angenommenen Spitzen
grün, die unter der Höchstzahl weggefallenen gelb.

**Was bewusst wegbleibt:** Mischbetrieb mit dem klassischen Finder,
Schaftrichtung und Nocke (die Heatmap gibt nur die Spitze; die App braucht
für `Shot` nur x, y), eine dynamische Eingabegröße (der ONNX-Export ist fest,
je Größe ein Modell), eine eigene Schwellenwahl zur Laufzeit.

## Trainings- und Asset-Kette

**Das ausgelieferte Modell trainiert auf allen Ansichten.** `train.py` bekommt
einen Modus `--all`: kein Fold, alle Ansichten mit Wahrheit im Training,
Konfiguration von r4 (768 px, Ausschnitte 512 px, 60 Epochen), Ausgabe
`model_all.pt`. Rund 40 Minuten auf der CPU dieses Rechners.

**Güte und Messung sind getrennt.** Ein Modell auf allen Daten hat keine
zurückgehaltenen Ansichten mehr; der Korpuslauf der App misst es auf seinen
Trainingsfotos. Seine Zahl ist eine **Obergrenze und eine Regressionswache**
(die Kette Warp, Normierung, Netz, Spitzen funktioniert, und nichts wird
später schlechter), keine Schätzung der Güte auf neuen Fotos. Die
Güte-Schätzung bleibt die Kreuzvalidierung des PoC (r4: 71 % bei 1,06,
63 % bei 0,64). Beides steht so im Bericht und über den Pins.

**Schwelle:** der Median der fünf FP-begrenzten Fold-Schwellen von r4
(0,12, 0,12, 0,10, 0,12, 0,12), also **0,12**. Sie wird nicht auf den
Trainingsfotos gewählt, weil das die Fehlfunde unterschätzen würde.

**Ablageformat** im Korpus-Repo, `models/arrows/<name>/` (erster Name
`r4-all-2026-09`):

```
model.onnx    fp16, feste Eingabe 768 x 768, aus export_onnx.py
model.json    {
                "inputSize": 768, "stride": 2, "kernel": 5,
                "threshold": 0.12,
                "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225],
                "training": "runs/all-2026-09-17", "corpus": "Korpus-Commit des Trainings",
                "thresholdFrom": "median of r4 FP-limited fold thresholds",
                "crossValidation": {"oblique": "71.3 % at 1.06 FP/view", "fpLimited": "63.3 % at 0.64"}
              }
```

`ArrowModel.load(dir)` in `:detection` liest beides; die Beilage ist die
einzige Quelle für Größe, Schwelle und Normierung, nichts davon steht im
Kotlin-Code.

**Auffinden:** Gradle-Property `DETECTION_MODEL_DIR` in
`gradle-local.properties` (Beispiel in `gradle-local.properties.example`,
Abschnitt in `BUILDING.md`), an den Test als System-Property
`detection.model.dir` durchgereicht wie `detection.corpus.dir`. Fehlt sie oder
die Datei, wird der gelernte Korpuslauf **übersprungen** (Assume), nicht rot.

**App-Seite** (Schritt 8, nicht hier): Die App kopiert `model.onnx` und
`model.json` beim Build aus `DETECTION_MODEL_DIR` in ihre Assets und baut
daraus ein `ArrowModel`; `LearnedArrowDetector` nimmt Bytes oder Pfad, keinen
Android-Asset-Namen.

## Messung und Tests

- **`LearnedArrowCorpusRun`** neben `ArrowCorpusRun`, derselbe Bericht
  (`detection.report.dir`), dieselben Kennzahlen aus `:detection-corpus`
  (Treffer innerhalb 0,05 Radien plus Toleranz, global nächste Paare zuerst,
  Wahrheit je Ansicht aus `tipPx`), eigene `ArrowPins` mit Vermerk
  „Trainingsfotos, Obergrenze". Der klassische Lauf und seine Pins bleiben
  unverändert. Gemeinsamer Code (Korpus laden, Anfrage bauen, Bericht
  schreiben) wird aus `ArrowCorpusRun` in eine Hilfsklasse gezogen, nur so weit
  es beide brauchen.
- **Paritätstest gegen den PoC:** Für zwei Korpusansichten (eine schräge, eine
  frontale) liegt im Modellordner eine Referenz `parity/<name>.json` mit den
  Python-Spitzen aus `evaluate.py` auf dem `--all`-Modell. Der Kotlin-Weg muss
  dieselben Spitzen auf 0,5 Eingabepixel und denselben Wert auf 0,01 liefern.
  Das verankert Warp, Normierung, Netz und Maxima in einem Test. Weicht die
  Entzerrung der App vom `prepare.py`-Rendering ab, zeigt sich das hier.
- **Reine JVM-Tests** für `HeatmapPeaks` mit synthetischen Heatmaps: Schwelle,
  Kern, Höchstzahl, Subpixel-Schwerpunkt, Sortierung, zwei Spitzen im
  Abstand unter dem Kern verschmelzen zu einer (der bekannte Fall der dichten
  Gruppe).
- **`ArrowModel`-Tests:** Beilage lesen, fehlende Felder benannt abgelehnt,
  Größe des ONNX gegen `inputSize` geprüft (der Export ist fest, ein
  512-px-Modell mit 768 in der Beilage wird beim ersten Vorwärtslauf benannt
  abgelehnt).
- **`OpenCvImportRuleTest`** lernt `org.opencv.dnn` als drittes erlaubtes
  Paket, nur für das Paket `arrows`.
- **Der Zeittest** `LearnedFinderTimingTest` vom 17.9. bleibt, wie er ist.

## Fehlerfälle

- Modell nicht lesbar (Datei fehlt, ONNX-Import schlägt fehl): Ausnahme beim
  Konstruieren mit Pfad und OpenCV-Meldung; die App entscheidet später, ob sie
  auf den klassischen Finder zurückfällt. Kein stilles Weiterlaufen.
- Registrierung fehlgeschlagen: wie heute.
- Keine Spitze über der Schwelle: leere Liste, `FEWER_THAN_EXPECTED`; kein
  Fehlschlag.
- Speicher: nicht in `:detection` behandelt; die App setzt `winograd = false`
  auf Geräten mit wenig Speicher (Befund vom 17.9.: rund 700 gegen 400 MB).

## Risiken

- **OpenCV 4.9 (JVM-Tests) gegen 4.14 (App):** Der ONNX-Import des
  Desktop-Jars ist ungeprüft; die App-Version ist geprüft. Erste Aufgabe des
  Plans ist ein Rauchtest, der das Modell im Desktop-Jar lädt und einen
  Vorwärtslauf macht. Schlägt er fehl, ist der Ausweg ein Desktop-Jar 4.14
  (`org.openpnp:opencv` gibt es bis 4.9; Alternative `org.bytedeco:opencv`
  oder das offizielle Java-Paket), zu entscheiden dann.
- **Parität der Entzerrung:** `prepare.py` verkleinert vor dem Warp mit
  `INTER_AREA`, `FaceWarp.warp` warpt bilinear direkt. Der Paritätstest misst
  den Unterschied; liegt er über 0,5 px, wird die Vorverkleinerung in
  `FaceWarp` nachgezogen.
- **Trainingsfotos im Korpuslauf:** die Zahl liest sich besser als die Güte
  ist. Deshalb der Vermerk in Bericht und Pins und die Kreuzvalidierungszahl
  daneben.
- **Fold-Schwelle auf dem All-Modell:** Ein Modell auf mehr Daten kann
  schärfere Heatmaps geben, dann ist 0,12 zu vorsichtig oder zu großzügig. Die
  Fehlfundrate des Korpuslaufs zeigt die Richtung; eine Korrektur wäre eine
  neue Beilage, keine Codeänderung.

## Was nicht Teil dieses Plans ist

Der Einbau in die App (Kamera, Assets, Anzeige, Korrektur), der Roll-Anker im
Registrar (Voraussetzung für die Gruppenanzeige, eigener Plan), ein kleineres
Rückgrat, Copy-Paste-Augmentierung und neue Fotos, die Trennung dichter
Gruppen.
