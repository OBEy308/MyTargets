# Gelernte Pfeilfindung — der Preis des Wegs in die App

Datum: 2026-09-17
Status: Messung, keine Umsetzung; Telefonzahlen vom Abend des 17.9. ergänzt
Basis: Master `95a00a11`, Korpus `0e30f18`, Modell r4 Fold 3 (`../MyTargets-learn/runs/r4/model_fold3.pt`)
Vorgänger: `2026-09-15-learned-selection-findings.md` (Nachträge vom 17.9.)

## Frage

Das Heatmap-Modell des PoC erreicht auf zurückgehaltenen schrägen Ansichten
63 % der Pfeile bei 0,64 Fehlfunden je Ansicht, 71 % bei 1,06. Bevor es
hinter die `ArrowDetector`-Schnittstelle kommt (Haupt-Spec, *Upgrade-Pfad zu
Ansatz C*), ist zu beziffern, was das kostet: welche Laufzeitbibliothek, wie
viel APK, wie viel Zeit und Speicher auf dem Telefon, und was F-Droid dazu
sagt. Gemessen am PC (Ryzen 5000, ein Thread, sofern nicht anders gesagt)
und, seit dem Abend des 17.9., auf einem Telefon (Abschnitt 3a).

## Befunde

### 1. Die Laufzeitbibliothek ist schon in der App

Die App liefert `org.opencv:opencv` 4.14.0 als AAR aus Maven aus. Dieses AAR
enthält das Modul **`dnn`**: 15 Java-Klassen unter `org.opencv.dnn` und die
native Bibliothek mit dem Modul (`libopencv_java4.so`, arm64-v8a 24,7 MB,
armeabi-v7a 16,1 MB, so wie bisher). Ein ONNX-Export des Modells lädt mit
`Dnn.readNetFromONNX` und rechnet dasselbe wie PyTorch (größte Abweichung der
Logits 7·10⁻⁶). **Es braucht weder TFLite/LiteRT noch ONNX Runtime**, also
keine neue Abhängigkeit und keinen zweiten nativen Block im APK.

Was sich ändert: Die Regel des Registrierungs-Designs, dass `:detection` nur
`org.opencv.core` und `org.opencv.imgproc` importieren darf, wird um
`org.opencv.dnn` erweitert. Das Desktop-Jar, gegen das kompiliert wird
(`org.openpnp:opencv` 4.9.0), hat `dnn` ebenfalls, die Kompilierprüfung bleibt
also erhalten.

Der Import auf dem Gerät ist geprüft: OpenCV 4.14.0 auf dem Telefon liest
den fp16-Export in 0,05 s und liefert die erwartete Ausgabe (Abschnitt 3a).
Eine Einschränkung bleibt: Der Export ist **auf eine Eingabegröße
festgelegt**, weil die Resize-Größen im Graphen stehen: ein 768-px-Export
nimmt keine 512-px-Eingabe. Je gewählter Auflösung ein Export.

### 2. Größe

| Form | Datei | Lädt in OpenCV DNN | Abweichung zu fp32 |
|---|---|---|---|
| PyTorch `state_dict` fp32 | 57,6 MB | – | – |
| ONNX fp32 | 57,5 MB | ja | – |
| **ONNX fp16** (Gewichte halbiert, Ein-/Ausgabe fp32) | **28,8 MB** | **ja**, gleiche Geschwindigkeit | Logits ≤ 0,007 |
| ONNX int8 (dynamische Quantisierung) | 14,5 MB | **nein** (`DynamicQuantizeLinear` unbekannt) | – |

14,4 Mio. Parameter: 11,2 Mio. im ResNet-18-Encoder, 3,2 Mio. im Decoder.
Gegen die rund 50 MB, die das Design für ein Universal-APK mit OpenCV schätzt,
kosten die fp16-Gewichte rund 60 % mehr Download; fp32 verdoppelt ihn. Der
Weg auf 14 MB (int8) führt nicht über OpenCV DNN, sondern über statische
Quantisierung nach TFLite oder ONNX Runtime Mobile, also über eine neue
Abhängigkeit; die dynamische int8-Variante war in ONNX Runtime am PC zudem
dreizehnmal langsamer als fp32 (16,7 s), kein Weg. Ein kleineres Rückgrat
(MobileNet-Klasse, 2 bis 4 Mio. Parameter) wäre der andere Weg auf unter
15 MB, ist aber nicht trainiert und nicht gemessen.

### 3. Laufzeit und Speicher

| | 512 px | 768 px |
|---|---|---|
| OpenCV DNN fp32, 1 Thread, PC | 0,55 s | 1,22 s |
| OpenCV DNN fp32, 4 Threads, PC | – | 0,56 s |
| PyTorch, 1 Thread, PC (Vergleich) | 0,62 s | 1,31 s |
| Speicherzuwachs des Prozesses für Laden und ersten Durchlauf | +157 MB | +269 MB |
| Trefferquote schräg (r3 bzw. r4, Schwelle je Fold nach F1) | 65 % bei 1,6 FP | 71 % bei 1,06 FP |
| Trefferquote schräg mit Fehlfund-Grenze 0,65 | – | 63 % bei 0,64 FP |

### 3a. Auf dem Telefon gemessen

Samsung Galaxy S25 (SM-S931B, Snapdragon 8 Elite, 8 Kerne, Android 16,
arm64-v8a), OpenCV 4.14.0 aus dem AAR, fp16-Export von r4 Fold 3, Eingabe
eine entzerrte Korpus-Auflage. Instrumentierungstest
`LearnedFinderTimingTest` in `detection/src/androidTest` (README dort); drei
Durchläufe je Zelle nach einem Aufwärmdurchlauf.

| | 768 px | 512 px |
|---|---|---|
| Modell lesen | 0,05 s | 0,04 s |
| Winograd an, ein Thread | 0,90 s | 0,42 s |
| **Winograd an, alle Kerne** | **0,53 s** | **0,23 s** |
| Winograd aus, ein Thread | 2,41 s | 1,10 s |
| Winograd aus, alle Kerne | 1,23 s | 0,58 s |
| Nativer Heap mit geladenem Netz nach dem Durchlauf, Winograd an | rund 700 MB | rund 550 MB |
| dito, Winograd aus | rund 400 MB | rund 250 MB |

Lesart:

- **Zeit ist kein Thema.** Ein Foto kostet auf diesem Telefon eine halbe
  Sekunde bei 768 px auf allen Kernen, ein Kern allein ist schneller als ein
  PC-Kern. Ein Mittelklasse-Telefon liegt vielleicht beim Dreifachen, also
  1,5 s: für einen Schritt, der nach dem Foto einmal läuft, tragbar.
- **Speicher ist das Thema, und Winograd ist die Stellschraube.** OpenCV DNN
  rechnet 3×3-Faltungen mit Winograd-Puffern, die bei 768 px rund 300 MB
  zusätzlich kosten. Mit Winograd (Voreinstellung) hält das Netz rund 700 MB
  nativen Speicher, ohne rund 400 MB, bei 2,3-facher Zeit (1,2 s auf allen
  Kernen). 700 MB sind auf einem Telefon mit 8 oder 12 GB unauffällig, auf
  einem mit 4 GB ein Grund, im Hintergrund beendet zu werden; 400 MB sind auf
  4 GB machbar. `Net.enableWinograd(false)` ist ein Aufruf; die App kann ihn
  von `ActivityManager.getMemoryClass()` oder dem Gesamtspeicher abhängig
  machen.
- Die Speicherzahlen sind Momentaufnahmen des nativen Heaps in einem
  Prozess, der nacheinander vier Netze lud; die Java-Hülle gibt den nativen
  Teil erst im Finalizer frei, und der PSS fiel während der zweiten Variante
  sichtbar, als das erste Netz verschwand. Die Größenordnung und der
  Winograd-Unterschied sind belastbar, die letzte Stelle nicht. Für eine
  exakte Zahl je Variante bräuchte es einen Prozess je Variante.
- Die PC-Messung hatte den Speicher unterschätzt (+269 MB gegen rund 700 MB
  mit Winograd auf dem Telefon); die Zeit hatte sie richtig eingeordnet.

### 4. F-Droid

MyTargets ist auf Google Play (seit 2014) und in APK-Spiegeln zu finden,
**nicht auf F-Droid**; das Design nennt die App "F-Droid-freundlich" und hat
die Frage, ob F-Droid das vorgebaute OpenCV-AAR annimmt, bereits offen
gelassen. Die Inclusion Policy verlangt, dass Binärabhängigkeiten aus den
Quellen gebaut werden, und freie Lizenzen für Assets; zu gelernten Gewichten
sagt sie nichts Ausdrückliches. Für dieses Modell hieße das: Der Encoder
startet von den ImageNet-Gewichten von torchvision (BSD-3), das Training läuft
auf dem privaten Korpus (GPS-Daten in Fotos), also ist die Gewichtsdatei aus
öffentlichen Quellen nicht reproduzierbar. Wird F-Droid je ein Ziel, bleibt der
Weg, von null zu trainieren (`train.py --no-pretrained` gibt es) auf einem
veröffentlichbaren Korpus, oder das Modell als Anti-Feature zu kennzeichnen.
Heute ist es kein Hindernis, weil F-Droid kein Kanal ist; es kommt zur
OpenCV-AAR-Frage hinzu und wird mit ihr entschieden.

### 5. Was an Code dazukäme

Hinter `ArrowDetector`: das entzerrte Auflagenbild auf 768 × 768 bringen
(Rahmen `[-1,1; 1,1]²` wie `prepare.py`), ImageNet-Normierung, `blobFromImage`,
`net.forward()`, lokale Maxima (Kern 5, Schwelle aus dem Training,
Subpixel-Schwerpunkt über 3 × 3, höchstens `expectedShots`), zurück in
Auflagenkoordinaten und als `ArrowCandidate` mit Zuversicht = Heatmap-Wert.
Am PC sind das 60 Zeilen Python (`peaks`, `to_stored_px` in `train.py`), in
Kotlin eine Klasse von rund 150 Zeilen plus das Laden der Gewichte aus den
Assets. Die Kandidaten laufen durch dieselbe Auswahl und Messung wie heute
(`ArrowCorpusRun`, Schranken), damit die Korpuszahl der App und die des PoC
vergleichbar bleiben. Die Schwelle ist die im Training gewählte, nicht eine im
Nachhinein gesetzte.

## Der Preis, zusammengefasst

| Posten | Kosten |
|---|---|
| Neue Abhängigkeit | keine, `dnn` steckt im vorhandenen OpenCV-AAR |
| APK | +29 MB (fp16), gegen rund 50 MB heute |
| Laufzeit je Foto | 0,5 s bei 768 px auf einem S25 (alle Kerne), 1,2 s ohne Winograd; Mittelklasse geschätzt das Dreifache |
| Speicher | rund 700 MB nativ bei 768 px mit Winograd, rund 400 MB ohne; 512 px 550 / 250 MB |
| Genauigkeit | 71 % bei 1,06 FP oder 63 % bei 0,64 FP, gegen 25 % bei 0,9 FP heute |
| F-Droid | offen wie bisher (AAR), Gewichte kommen als zweite offene Frage dazu |
| Code | eine `ArrowDetector`-Implementierung, rund 150 Zeilen, plus Assets |

## Was nicht gemessen ist

- Zeit und Speicher auf einem Mittelklasse-Telefon; gemessen ist ein
  Spitzengerät (S25). Der Test läuft auf jedem angeschlossenen Gerät.
- Der Speicher je Variante in einem eigenen Prozess (siehe 3a).
- Ein kleineres Rückgrat für unter 15 MB und unter 400 MB Speicher.
- Ob 512 px mit den Fehlfunden von r3 (1,6 je Ansicht) am Telefon den
  Zeitgewinn wert ist; bei 768 px und Fehlfund-Grenze ist das Kriterium erst
  erfüllt.

## Empfehlung

Der Weg ist billiger als angenommen: keine neue Bibliothek, kein TFLite-Export,
29 MB Assets, eine halbe Sekunde je Foto auf dem gemessenen Telefon. Die
Auflösung kann 768 px sein, weil erst dort das Kriterium erfüllt ist und die
Zeit es erlaubt; der Speicher wird über Winograd gesteuert (aus auf Geräten
mit wenig Speicher, 1,2 s statt 0,5 s). Nächster Schritt ist die
`ArrowDetector`-Implementierung gegen den Korpus gemessen. Der klassische Finder bleibt als Rückfall und als
Lieferant der Kandidatenmerkmale erhalten; die Schnittstelle sieht den Tausch
seit dem ersten Design vor.
