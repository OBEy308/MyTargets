# Plan 3d: Gelernter Pfeilfinder hinter `ArrowDetector` — Design

Datum: 2026-09-17
Status: Design, abgestimmt; gegengelesen am 17.9. gegen Code, Korpus-Repo und `runs/r4` (Entscheidungen 4 und 5, Vorverkleinerung, Paritätstest); Plan folgt in `docs/plans/`
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
4. **Überschuss wird nach Wert gekürzt, nicht verworfen.** Liegen mehr
   Spitzen über der Schwelle als `expectedShots`, bleiben die höchsten, ohne
   Lückenregel. So wurde der PoC gemessen; eine Lückenregel auf dem
   Heatmap-Wert (wie die 0,15-Lücke der `CandidateSelection`, Haupt-Spec „im
   Zweifel nichts schreiben") würde die Korpuszahl verändern. Sie bleibt
   Schritt 8 vorbehalten; der Bericht dieses Plans liefert die Werte, an denen
   sie zu prüfen wäre.
5. **Normierung und Sigmoid stehen im ONNX-Graphen**, nicht im Kotlin-Code.
   `export_onnx.py` exportiert ein Hüllmodul `(x − mean)/std → UNet →
   Sigmoid` für ein RGB-Bild `x` in [0, 1]; die App liefert genau das
   (`blobFromImage` mit Skalierung 1/255, die Division steht nicht noch einmal
   im Graphen) und liest Wahrscheinlichkeiten. Das nimmt zwei Fehlerquellen aus der Parität
   (Kanalreihenfolge, Division je Kanal auf dem 4D-Blob, die die Java-API
   nicht bequem kann) und hält die Beilage klein. Abgestimmt am 17.9. nach dem
   Gegenlesen, zusammen mit 4.

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
   imageToTarget, edge = model.inputSize)`. Vorher wird das Original **mit
   derselben Regel wie `prepare.py`** verkleinert: die längste Bildseite auf
   `preShrinkMaxSide` (2000 px) mit `INTER_AREA`, die Homographie entsprechend
   skaliert. Die Auflage spannt danach meist noch 1000 bis 1500 px, der
   bilineare Warp tastet also rund 2× unter; genau diese Pixel hat das Modell
   gesehen, und der Vorhersageschritt in `train.py` (`augment` bei 768 = 768)
   ist eine Identität, das Modell sah exakt das PNG aus `prepare.py`. Eine
   Verkleinerung „auf etwa Zielgröße" gäbe schärfere Bilder mit weniger
   Aliasing, also andere Eingaben. Die 2000 ist eine Trainingskonstante und
   steht in der Beilage. Der Rahmen ist derselbe wie im PoC: ±1,1
   Auflageneinheiten je Achse, Pixelabbildung `k·(t + EXTENT) − 0,5`, geprüft
   am 17.9. (`face_to_px` in `prepare.py` gegen `FaceWarp.pixelOf`).
3. **Eingabe:** `Dnn.blobFromImage` mit Skalierung 1/255, Mittelwert 0,
   `swapRB = true` (BGR nach RGB). Normierung und Sigmoid stehen im Graphen
   (Entscheidung 5); der Zeittest mit seiner Näherung einer Standardabweichung
   für alle Kanäle bleibt davon unberührt, er lädt sein eigenes Modell.
4. **Vorwärtslauf:** `net.setInput`, `net.forward()`, Ausgabe
   `1 × 2 × H/2 × W/2` Wahrscheinlichkeiten; Kanal 0 ist die Spitzen-Heatmap
   (Kanal 1, der Schaft, wird nicht benutzt).
5. **Spitzen:** `HeatmapPeaks.find(heat, stride = 2, kernel = 5, threshold,
   maxCount = request.expectedShots)`: lokale Maxima (Wert gleich dem Maximum
   im 5×5-Fenster und mindestens Schwelle), Subpixel-Schwerpunkt über das
   3×3-Fenster, Rückgabe `(u, v, wert)` in Eingabepixeln, nach Wert absteigend,
   auf `maxCount` gekürzt. Das ist `peaks()` aus `train.py`, Zeile für Zeile.
6. **Rückrechnung** aus Eingabepixeln in Auflagenkoordinaten (Umkehrung der
   Abbildung aus Schritt 2), Spot-Zuordnung über `SpotMapping` wie heute,
   Ausgabe `DetectedShot(faceIndex, x, y, confidence = Heatmap-Wert)`,
   `faceConfidence` wie beim klassischen aus den Ringresiduen der
   Registrierung. **Spitzen außerhalb jeder Auflage** (im Randband ab Radius 1
   oder daneben) haben in Schritt 5 einen der `expectedShots` Plätze belegt,
   wie im PoC, und fallen hier weg; sie werden in `LearnedAnalysis` als
   verworfen geführt und im Debug-Bild gezeigt. `SelectionReason`:
   `COMPLETE`, wenn nach der Zuordnung `expectedShots` Spitzen übrig sind,
   sonst `FEWER_THAN_EXPECTED`; `SPOT_OVERFLOW`, wenn ein Spot mehr Spitzen als
   `maxArrowsPerSpot` bekäme (die überzähligen mit dem kleinsten Wert fallen
   weg; auf den WA-Full-Auflagen des Korpus ist `maxArrowsPerSpot` gleich
   `expectedShots`, der Fall ist dort nicht erreichbar). `AMBIGUOUS_SURPLUS`
   kommt nicht vor: In `peaks()` gilt erst die Schwelle, dann die Kürzung auf
   die Höchstzahl nach Wert, ohne Lückenprüfung (Entscheidung 4); ein neuer
   Grund wird nicht eingeführt.

`analyse` liefert ein `LearnedAnalysis` mit Registrierung, Heatmap-Spitzen,
Ausgabe und Zeiten je Stufe (Registrierung, Warp, Netz, Spitzen), so dass der
Korpuslauf und der spätere Debug-Bildschirm dieselben Daten sehen wie beim
klassischen Finder.

**Debug-Bilder** über `DebugSink`, im Stil von `ArrowDebugImages`: die
Heatmap als rotes Glühen über dem entzerrten Bild, die angenommenen Spitzen
grün, die unter der Höchstzahl weggefallenen gelb, die außerhalb der Auflage
verworfenen grau.

**Was bewusst wegbleibt:** Mischbetrieb mit dem klassischen Finder,
Schaftrichtung und Nocke (die Heatmap gibt nur die Spitze; die App braucht
für `Shot` nur x, y), eine dynamische Eingabegröße (der ONNX-Export ist fest,
je Größe ein Modell), eine eigene Schwellenwahl zur Laufzeit.

## Trainings- und Asset-Kette

**Das ausgelieferte Modell trainiert auf allen Ansichten.** `train.py` bekommt
einen Modus `--all`: kein Fold, alle Ansichten mit Wahrheit im Training,
Konfiguration von r4 (768 px, Ausschnitte 512 px, 60 Epochen), Ausgabe
`model_all.pt` und `args.json`, keine Schwellenwahl (sie fiele auf
Trainingsansichten). Rund 40 Minuten auf der CPU dieses Rechners (r4: 34 s je
Epoche auf vier Fünfteln der Daten).

**`export_onnx.py`** exportiert das Hüllmodul mit Normierung und Sigmoid
(Entscheidung 5) und schreibt auf Wunsch (`--peaks <ansicht>…`) für benannte
Ansichten die Spitzen aus dem **exportierten ONNX über `cv2.dnn`** auf dem
PNG aus `prepare.py` nach `parity/<name>.json`. Das ist die Referenz des
Paritätstests; `evaluate.py` taugt dafür nicht, es liest zurückgehaltene
Heatmaps aus `detections.json`, und ein `--all`-Lauf hat keine.

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
model.onnx    fp16, feste Eingabe 768 x 768 RGB in [0,1], Normierung und
              Sigmoid im Graphen, aus export_onnx.py
model.json    {
                "inputSize": 768, "stride": 2, "kernel": 5,
                "preShrinkMaxSide": 2000,
                "threshold": 0.12,
                "training": "runs/all-2026-09-17", "corpus": "Korpus-Commit des Trainings",
                "thresholdFrom": "median of r4 FP-limited fold thresholds",
                "crossValidation": {"oblique": "71.3 % at 1.06 FP/view", "fpLimited": "63.3 % at 0.64"}
              }
parity/       Referenzspitzen je Ansicht für den Paritätstest, aus export_onnx.py --peaks
```

`ArrowModel.load(dir)` in `:detection` liest Gewichte und Beilage; die
Beilage ist die einzige Quelle für Größe, Vorverkleinerung und Schwelle,
nichts davon steht im Kotlin-Code. Die ImageNet-Werte stehen nur noch in
`export_onnx.py`, wo sie in den Graphen wandern.

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
  es beide brauchen. Zwei Abweichungen zum PoC nennt der Bericht im Kopf:
  Der Ansichtensatz ist nicht derselbe (der PoC lässt die frontalen Ansichten
  mit übernommener Wahrheit ohne `tipPx` weg, der Korpuslauf misst sie gegen
  die geteilte Wahrheit; der Bericht nennt die Zahl der Ansichten), und
  Spitzen außerhalb der Auflage zählte `metrics.evaluate` im PoC als
  Fehlfund, während sie den App-Bericht nie erreichen. Die Fehlfundzahl der
  App kann deshalb unter der des PoC liegen.
- **Paritätstest gegen den PoC:** Für zwei Korpusansichten (eine schräge, eine
  frontale) liegt im Modellordner `parity/<name>.json` mit den Spitzen aus
  `export_onnx.py --peaks` (dasselbe ONNX, `cv2.dnn`, PNG aus `prepare.py`).
  Der Test **speist die Sidecar-Homographie über einen Stub-`FaceRegistrar`
  ein**, statt zu registrieren: Der PoC rendert mit `registration.imageToTarget`
  aus dem Sidecar, die App registriert selbst, und die 0,01 Radien, auf die
  beide übereinstimmen, sind bei 768 px rund 3,5 px, siebenmal die Toleranz.
  Mit der Homographie des PoC muss der Kotlin-Weg dieselben Spitzen auf 0,5
  Eingabepixel und denselben Wert auf 0,01 liefern (fp16 gegen fp32 verschiebt
  Logits um höchstens 0,007, bei p ≈ 0,12 also den Wert um rund 0,0007). Das
  verankert Vorverkleinerung, Warp, Netz und Maxima in einem Test; die
  Registrierung misst der Korpuslauf.
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
  Die Java-Klasse `Net` hat kein `release`; der native Speicher geht erst im
  Finalizer frei. Wer die Instanz loslässt, bekommt die 700 MB nicht sofort
  zurück; für Schritt 8 heißt das eine Instanz je Prozess, nicht je Foto.

## Risiken

- **OpenCV 4.9 (JVM-Tests) gegen 4.14 (App):** Der ONNX-Import des
  Desktop-Jars ist ungeprüft; die App-Version ist geprüft. Erste Aufgabe des
  Plans ist ein Rauchtest, der das Modell im Desktop-Jar lädt und einen
  Vorwärtslauf macht. Schlägt er fehl, ist der Ausweg ein Desktop-Jar 4.14
  (`org.openpnp:opencv` gibt es bis 4.9; Alternative `org.bytedeco:opencv`
  oder das offizielle Java-Paket), zu entscheiden dann.
- **Parität der Entzerrung:** Beide Seiten warpen bilinear; der Unterschied
  läge allein in der Vorverkleinerung, und die übernimmt Schritt 2 mit der
  Regel von `prepare.py` (längste Seite 2000 px, `INTER_AREA`). Bleibt der
  Paritätstest trotzdem über 0,5 px, ist als Nächstes die Rundung der
  verkleinerten Bildgröße (`round` in Python gegen Kotlin) und die
  Skalierung der Homographie zu vergleichen, nicht der Warp.
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
