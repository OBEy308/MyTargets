# Gelernter Pfeilfinder hinter `ArrowDetector` (Plan 3d) — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Das Heatmap-Modell des PoC (Lauf r4) wird als `LearnedArrowDetector` eine zweite Implementierung von `ArrowDetector` in `:detection`, liest seine Gewichte aus einem Modellordner des Korpus-Repos und wird mit einem eigenen Korpuslauf, denselben Kennzahlen wie der klassische Finder und eigenen Schranken gemessen; der klassische Finder bleibt unverändert daneben.

**Architecture:** Registrierung wie heute, dann Vorverkleinerung nach der Regel von `prepare.py`, Entzerrung mit `FaceWarp.warp` fest auf die Modellgröße, `Dnn.blobFromImage` (Skalierung 1/255, RGB), ein Vorwärtslauf durch OpenCV DNN, lokale Maxima der Spitzen-Heatmap (`HeatmapPeaks`, Zeile für Zeile `peaks()` aus `train.py`), Kürzung nach Wert auf `expectedShots`, Rückrechnung in Auflagenkoordinaten und Spot-Zuordnung mit `SpotMapping`. Normierung und Sigmoid stehen im ONNX-Graphen, den `export_onnx.py` als Hüllmodul exportiert; Größe, Vorverkleinerung und Schwelle stehen nur in der Beilage `model.json`. Der Korpuslauf `LearnedArrowCorpusRun` teilt sich Laden, Anfrage und Wahrheitsbild mit `ArrowCorpusRun` über eine Hilfsklasse.

**Tech Stack:** Kotlin, Android-Library-Modul, `org.openpnp:opencv:4.9.0-0` (compileOnly und Test, mit `dnn`), Gson für die Beilage, JUnit 4, Truth; Python 3.12 im `learn/.venv` des Korpus-Repos (torch 2.14 CPU, opencv-python 5.0, onnx 1.22, onnxconverter-common).

**Spec:** `docs/design/2026-09-17-learned-arrow-detector-design.md` (bindend für diesen Plan), dazu `docs/design/2026-09-17-learned-finder-app-path.md` (Telefonmessung), `docs/design/2026-09-11-detection-arrows-design.md` (klassischer Finder, Korpuslauf und Bericht) und im Korpus-Repo `learn/train.py`, `learn/prepare.py`, `learn/export_onnx.py`, `learn/README.md`.

**Voraussetzung:** Branch `plan/learned-arrow-detector` von `master`, nachdem der Design-Branch `design/learned-arrow-detector` (Commits `dccad286`, `c3db1a20` und dieser Plan) gemergt ist. Korpus-Repo auf `e4fb964` oder später, `learn/.venv` vorhanden, `../MyTargets-learn/runs/r4/` mit `model_fold3.pt` und `../MyTargets-learn/data/` (768 px, 100 Ansichten) vorhanden.

## Präzisierungen gegenüber dem Design

Beim Ausarbeiten sind diese Stellen genauer geworden. Keine ändert eine Entscheidung des Designs.

1. **Gson in `:detection`.** Die Beilage `model.json` braucht einen JSON-Leser; `:detection` hat keinen. Gson ist in `:detection-corpus` und in `:app` schon da (`libs.gson`), also keine neue Abhängigkeit der App.
2. **`CorpusLoader` überspringt `models/` an der Wurzel.** Der Loader durchsucht jeden Unterordner und meldet jede JSON-Datei ohne Bild als verwaiste Sidecar; `RealCorpusTest` verlangt, dass es keine gibt. Ohne die Ausnahme würde `models/arrows/<name>/model.json` den Korpustest rot machen. Die Ausnahme gilt nur für den Ordner `models` direkt unter der Korpuswurzel.
3. **`HeatmapPeaks.find` liefert alle Spitzen über der Schwelle** (`maxCount` ist optional); der Detektor kürzt selbst auf `expectedShots`. Dasselbe Ergebnis wie der Aufruf im Design, aber Debug-Bild und Bericht sehen auch den Überschuss (gelb).
4. **Schwellenvergleich in float32.** `peaks()` vergleicht eine float32-Heatmap mit der Schwelle, die torch dafür nach float32 wandelt. Kotlin vergleicht deshalb `value < threshold.toFloat()`, nicht in Double; sonst fiele ein Pixel, das genau auf der Schwelle liegt, auf einer Seite weg.
5. **`FaceWarp.targetOf`** ist die Umkehrung von `FaceWarp.pixelOf` und kommt als kleine Ergänzung ins Paket `registration`; Schritt 6 des Designs braucht sie.
6. **Rundung der Vorverkleinerung mit `Math.rint`.** Pythons `round` rundet auf die nächste gerade Zahl, wenn es genau .5 trifft; `Math.rint` ebenso. `roundToInt` täte es nicht.
7. **`ArrowModel` nimmt einen Pfad**, keine Bytes: `Dnn.readNetFromONNX(String)` liest die Datei. Die App kopiert ihre Assets in eine Datei, wie der Zeittest es tut. Eine Bytes-Variante kommt, wenn Schritt 8 sie braucht.
8. **Die Paritätsreferenz rendert aus dem JPEG mit OpenCV**, nicht aus dem PNG von `prepare.py`. Die PNGs sind mit Pillow dekodiert, die App dekodiert mit OpenCV; ein Unterschied der JPEG-Dekoder gehört nicht in den Paritätstest. `export_onnx.py --peaks` liest das Foto mit `cv2.imread`, verkleinert und entzerrt mit `prepare.rectify` (derselbe Code, den `prepare.py` für die Trainingsbilder nutzt) und schreibt zusätzlich den Mittelwert des entzerrten Bildes je Kanal als Diagnose, damit ein Paritätsfehler zwischen Bildweg und Netz unterscheidbar ist.
9. **Schritt 6 des Designs steht in `LearnedSelection.place`**, einer reinen Funktion ohne OpenCV, damit Randband, Spot-Überlauf und `SelectionReason` ohne Netz getestet werden. `LearnedAnalysis.Analysed` trägt alle Spitzen, die behaltenen, die auf der Auflage, die außerhalb und die angenommenen.
10. **Bericht:** `ArrowReport.render` bekommt einen optionalen Vorspann (`preface`) für die Vermerke des Designs. Die Spalten, die nur der klassische Finder füllt (Q, Registrierungs-Q, Linienabstand, gemeinsamer Punkt, NO_SHAFT, RAN_OUT), bleiben `-` bzw. 0; die Zeitspalten heißen weiter `registration / warp / search / walk` und der Vorspann sagt, dass `search` das Netz und `walk` die Spitzen sind.
11. **Zwei Debug-Bilder:** `5-heatmap` (rotes Glühen) und `6-spitzen` (grün angenommen, gelb weggefallen, grau außerhalb), Bild `7-wahrheit` schreibt der Lauf wie beim klassischen.
12. **Der Zeittest bleibt.** `export_onnx.py` exportiert ab jetzt immer das Hüllmodul; wer die Zeittest-Assets neu erzeugt, bekommt ein Modell mit Normierung, und der Zeittest normiert dann doppelt. Für die Zeitmessung ist das ohne Belang; die README des Zeittests sagt es.
13. **Probeordner vor dem All-Modell.** Bis das Training auf allen Ansichten fertig ist, laufen Rauch-, Modell- und Paritätstest gegen `../MyTargets-learn/models-smoke/` mit Fold 3 von r4 (außerhalb des Korpus, gitignored durch die Lage). Task 12 stellt `DETECTION_MODEL_DIR` auf den Modellordner im Korpus um.
14. **`train.py`:** die Trainingsschleife wandert in `fit()`, das Fold-Training und `--all` teilen; die Seeds je Fold bleiben, r4 bliebe reproduzierbar.
15. **`prepare.py`:** Vorverkleinerung und Warp wandern in `rectify()`, die Konstante heißt `PRE_SHRINK_MAX_SIDE = 2000`; die Ausgabe ändert sich nicht.
16. **`model.json`** trägt zusätzlich `precision` und `exported`; der Kotlin-Leser ignoriert unbekannte Felder.

## Global Constraints

- **Sprache:** Code, Bezeichner, Kommentare, Berichtstexte Englisch; Plan und Design Deutsch.
- **Lizenzkopf:** Jede neue Kotlin-Datei beginnt mit dem GPLv2-Kopf aus `detection/src/main/java/de/dreier/mytargets/detection/ArrowDetector.kt` (Zeilen 1–14, „Copyright (C) 2026 MyTargets contributors“). Die Codeblöcke unten lassen ihn weg.
- **Pfade:** `:detection` Hauptcode unter `detection/src/main/java/de/dreier/mytargets/detection/...`, Tests unter `detection/src/test/java/de/dreier/mytargets/detection/...`; `:detection-corpus` unter `detection-corpus/src/main/kotlin/...` und `detection-corpus/src/test/kotlin/...`. Korpus-Repo: `C:\Users\olive\projekte\MyTargets-corpus` (`../MyTargets-corpus`), Erzeugtes in `../MyTargets-learn`.
- **OpenCV:** Der Hauptcode importiert `org.opencv.core` und `org.opencv.imgproc` überall, `org.opencv.dnn` nur unter `arrows/` (Task 2). `OpenCvImportRuleTest` prüft es.
- **Speicher:** Jede selbst erzeugte `Mat` wird in `try`/`finally` mit `release()` freigegeben; eine Funktion, die eine neue `Mat` zurückgibt, füllt sie in `releaseIfThrows`. Pixel werden einmal per `Mat.get(0, 0, array)` geholt.
- **Keine Gewichte im Hauptrepo.** Kein `.onnx` und kein `.pt` unter `C:\Users\olive\projekte\MyTargets`, außer den gitignorierten Zeittest-Assets. Vor jedem Commit `git status --short` lesen.
- **Nichts Erzeugtes im Korpus außer `models/`.** Probeexporte und Logs nach `../MyTargets-learn`.
- **Python:** immer `learn/.venv/Scripts/python` aus `../MyTargets-corpus/learn` heraus. Läufe über 10 Minuten (das Training) als getrennter Prozess mit Logdatei starten (PowerShell `Start-Process`), nicht im Vordergrund.
- **JDK 17:** Gradle braucht `JAVA_HOME` auf ein JDK 17 (`BUILDING.md`); unten steht `JAVA_HOME=<jdk17>`.
- **Testbefehle:** `./gradlew :detection:testDevDebugUnitTest --tests "<Filter>"` und `./gradlew :detection-corpus:test`. Tests mit Korpus oder Modell brauchen `DETECTION_CORPUS_DIR` bzw. `DETECTION_MODEL_DIR` in `gradle-local.properties` und überspringen sich ohne. Nach einer Änderung an Korpus oder Modellordner `--rerun` anhängen; Gradle kennt sie nicht als Eingabe.
- **Commits:** einer je Task; im Hauptrepo Nachricht Englisch im Stil `detection: …` / `detection-corpus: …`, im Korpus-Repo `learn: …` / `models: …`; mit den Attributionszeilen, die die Umgebung vorgibt.
- **`:app` bleibt unberührt.**

---

## File Structure

`arrows/` steht für `detection/src/main/java/de/dreier/mytargets/detection/arrows/`, `arrows-test/` für das Gegenstück unter `src/test/java`; `registration/`, `registration-test/` entsprechend. `corpus/` und `metrics/` meinen `:detection-corpus`. `learn/` meint das Korpus-Repo.

| Datei | Task | Verantwortung |
|---|---|---|
| `detection/build.gradle`, `gradle-local.properties.example`, `BUILDING.md` | 1, 5, 15 | Property `DETECTION_MODEL_DIR`, Gson, Doku |
| `arrows-test/LearnedModelSmokeTest.kt` | 1, 5 | Das Modell lädt im Desktop-Jar 4.9 und antwortet in der erwarteten Form |
| `registration-test/OpenCvImports.kt`, `OpenCvImportRuleTest.kt` | 2 | `org.opencv.dnn` nur unter `arrows/` |
| `arrows/HeatmapPeaks.kt` | 3 | `Heatmap`, `Peak`, lokale Maxima mit Subpixel wie `peaks()` |
| `arrows/PreShrink.kt`, `registration/FaceWarp.kt` | 4 | Vorverkleinerung nach `prepare.py`; `targetOf` |
| `arrows/ArrowModel.kt` | 5 | Modellordner lesen: Gewichte-Pfad und Beilage, benannte Fehler |
| `corpus/CorpusLoader.kt`, Korpus-`README.md` | 6 | `models/` ist kein Korpusordner |
| `learn/train.py` | 7 | `fit()`, `--all` |
| `learn/prepare.py`, `learn/export_onnx.py` | 8 | `rectify()`; Hüllmodul, `--out`, `model.json`, `--peaks` |
| `arrows/LearnedSelection.kt`, `arrows/LearnedAnalysis.kt` | 9 | Schritt 6: Randband, Spot-Kappe, Grund; das Ergebnis für den Lauf |
| `arrows/LearnedArrowDetector.kt` | 9 | Der Detektor |
| `arrows/LearnedDebugImages.kt` | 10 | Bilder 5 und 6 |
| `arrows-test/LearnedParityTest.kt` | 11 | Parität gegen `parity/*.json` |
| `models/arrows/r4-all-2026-09/` (Korpus-Repo) | 12 | Das ausgelieferte Modell |
| `arrows-test/ArrowCorpusRuns.kt`, `metrics/ArrowReport.kt` | 13 | Gemeinsamer Code der Läufe; Vorspann |
| `arrows-test/LearnedArrowCorpusRun.kt` | 14, 15 | Der gelernte Korpuslauf, seine Pins |
| `learn/README.md`, Design-Nachtrag, `androidTest/README.md` | 15 | Doku und Messwerte |

---

## Task 1: Property `DETECTION_MODEL_DIR` und der Rauchtest im Desktop-Jar

Das größte Risiko des Designs zuerst: Liest OpenCV 4.9 (Desktop-Jar der JVM-Tests) den fp16-ONNX-Export und rechnet ihn? Der Test bleibt danach als Wache für den Modellordner.

**Files:**
- Modify: `detection/build.gradle` (Block `tasks.withType(Test)`)
- Modify: `gradle-local.properties.example`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedModelSmokeTest.kt`

**Interfaces:**
- Consumes: `OpenCvRule` (Testquellen, `registration`), `org.opencv.dnn.Dnn`, `org.opencv.dnn.Net`
- Produces: System-Property `detection.model.dir` in `testDevDebugUnitTest`; die Konvention „Tests mit Modell überspringen sich ohne `DETECTION_MODEL_DIR`“, die Task 5, 11 und 14 übernehmen

- [ ] **Step 1: Den Probeordner anlegen**

Fold 3 von r4 liegt als fp16-Export bei 768 px unter `../MyTargets-learn/r4_fold3_fp16.onnx` (28,8 MB; alternativ `detection/src/androidTest/assets/model_fold3_768_fp16.onnx`, dieselbe Datei).

```bash
mkdir -p ../MyTargets-learn/models-smoke
cp ../MyTargets-learn/r4_fold3_fp16.onnx ../MyTargets-learn/models-smoke/model.onnx
ls -l ../MyTargets-learn/models-smoke
```

Expected: `model.onnx` mit rund 28,8 MB.

- [ ] **Step 2: Die Property durchreichen**

In `detection/build.gradle` den Block am Ende ersetzen:

```groovy
// The corpus run and its report are wired into one variant only; otherwise
// ./gradlew test would measure the corpus in all six. The learned finder's
// model folder (design 3d, Auffinden) travels the same way: absent, the tests
// that need it skip themselves.
tasks.withType(Test).configureEach { task ->
    if (task.name == 'testDevDebugUnitTest') {
        def corpusDir = rootProject.findProperty('DETECTION_CORPUS_DIR')
        if (corpusDir != null) {
            task.systemProperty 'detection.corpus.dir',
                rootProject.file(corpusDir).absolutePath
        }
        def modelDir = rootProject.findProperty('DETECTION_MODEL_DIR')
        if (modelDir != null) {
            task.systemProperty 'detection.model.dir',
                rootProject.file(modelDir).absolutePath
        }
        task.systemProperty 'detection.report.dir',
            layout.buildDirectory.dir('reports/detection').get().asFile.absolutePath
    }
}
```

In `gradle-local.properties.example` anhängen:

```properties

# Optional: folder with the learned arrow finder's model (model.onnx and
# model.json, design 3d). Leave unset to skip the tests that need it. See BUILDING.md, section 4.
DETECTION_MODEL_DIR=../MyTargets-corpus/models/arrows/r4-all-2026-09
```

In der eigenen `gradle-local.properties` (nicht im Repo) vorerst:

```properties
DETECTION_MODEL_DIR=../MyTargets-learn/models-smoke
```

- [ ] **Step 3: Den Rauchtest schreiben**

`LearnedModelSmokeTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import java.io.File

/**
 * The desktop jar of the JVM tests is OpenCV 4.9, the app ships 4.14 (design
 * 3d, Risiken): this reads the model of the configured folder with 4.9 and
 * runs it once. Skips itself without DETECTION_MODEL_DIR.
 */
class LearnedModelSmokeTest {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var dir: File

    @Before
    fun requireModel() {
        val configured = System.getProperty("detection.model.dir")
        assumeTrue("DETECTION_MODEL_DIR is not configured", configured != null)
        dir = File(configured!!)
        assumeTrue("model directory does not exist: $configured", dir.isDirectory)
        assumeTrue("no model.onnx in $configured", File(dir, "model.onnx").isFile)
    }

    @Test
    fun theDesktopJarReadsTheModelAndRunsIt() {
        val net = Dnn.readNetFromONNX(File(dir, "model.onnx").absolutePath)
        assertThat(net.empty()).isFalse()

        val input = Mat.zeros(768, 768, CvType.CV_8UC3)
        val blob = Dnn.blobFromImage(input, 1.0 / 255.0, Size(768.0, 768.0), Scalar(0.0, 0.0, 0.0), true, false)
        try {
            net.setInput(blob)
            val out = net.forward()
            try {
                assertThat(out.dims()).isEqualTo(4)
                assertThat(listOf(out.size(0), out.size(1), out.size(2), out.size(3)))
                    .containsExactly(1, 2, 384, 384).inOrder()
                assertThat(out.type()).isEqualTo(CvType.CV_32F)
            } finally {
                out.release()
            }
        } finally {
            blob.release()
            input.release()
        }
    }
}
```

- [ ] **Step 4: Laufen lassen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedModelSmokeTest*"`

Expected: PASS, 1 Test ausgeführt (nicht übersprungen; im Zweifel `build/reports/tests/testDevDebugUnitTest/index.html` oder `--info` prüfen).

Schlägt `readNetFromONNX` mit einer OpenCV-Meldung fehl, ist das der Befund, den das Design unter *Risiken* nennt. Dann zuerst mit dem fp32-Export prüfen (`cp ../MyTargets-learn/r4_fold3.onnx ../MyTargets-learn/models-smoke/model.onnx`, 57,5 MB): Lädt fp32 und fp16 nicht, ist der Ausweg ein Desktop-Jar 4.14 (`org.bytedeco:opencv` oder das offizielle Java-Paket), lädt beides nicht, ist der ONNX-Import von 4.9 zu schwach und der Jar-Wechsel nötig. **In beiden Fällen anhalten und den Befund melden**; der Plan geht von einem grünen Rauchtest aus.

- [ ] **Step 5: Commit**

```bash
git add detection/build.gradle gradle-local.properties.example detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedModelSmokeTest.kt
git commit -m "detection: DETECTION_MODEL_DIR and a smoke test that reads the learned model in the desktop jar"
```

---

## Task 2: Die Importregel lernt `org.opencv.dnn` unter `arrows/`

**Files:**
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvImports.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvImportRuleTest.kt`

**Interfaces:**
- Produces: `OpenCvImports.forbidden(sources: Map<String, String>): List<String>` erlaubt `org.opencv.dnn.` für Quellen, deren Pfad relativ zu `src/main/java/de/dreier/mytargets/detection` mit `arrows/` beginnt (Windows-Backslash eingeschlossen)

- [ ] **Step 1: Den Test schreiben**

In `OpenCvImportRuleTest` ergänzen:

```kotlin
    @Test
    fun dnnIsAllowedUnderArrowsAndNowhereElse() {
        val sources = mapOf(
            "arrows/LearnedArrowDetector.kt" to "import org.opencv.dnn.Dnn\nimport org.opencv.dnn.Net\n",
            "arrows\\OnWindows.kt" to "import org.opencv.dnn.Dnn\n",
            "registration/Registrar.kt" to "import org.opencv.dnn.Net\n"
        )

        assertThat(OpenCvImports.forbidden(sources))
            .containsExactly("registration/Registrar.kt: org.opencv.dnn.Net")
    }
```

Den Test `theMainSourcesImportOnlyCoreAndImgproc` umbenennen in `theMainSourcesImportOnlyTheAllowedPackages`; Inhalt unverändert.

- [ ] **Step 2: Fehlschlag sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*OpenCvImportRuleTest*"`
Expected: FAIL, `dnnIsAllowedUnderArrowsAndNowhereElse` meldet drei verbotene Importe statt einem.

- [ ] **Step 3: Die Regel erweitern**

`OpenCvImports.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

/**
 * The main code compiles against the desktop jar, which carries modules the
 * Android AAR lacks (org.opencv.highgui). core and imgproc are safe on both
 * everywhere; dnn is in the AAR too (design 3d, decision 3) and only the
 * learned finder under arrows/ may use it.
 */
object OpenCvImports {

    private val allowedEverywhere = listOf("org.opencv.core.", "org.opencv.imgproc.")
    private val allowedUnderArrows = listOf("org.opencv.dnn.")
    private val importLine =
        Regex("""^\s*import\s+(org\.opencv\.[A-Za-z0-9_.]*)""", RegexOption.MULTILINE)

    /** "file: imported name" for every import outside the packages allowed for that file. */
    fun forbidden(sources: Map<String, String>): List<String> =
        sources.flatMap { (name, text) ->
            val underArrows = name.replace('\\', '/').startsWith("arrows/")
            importLine.findAll(text)
                .map { it.groupValues[1] }
                .filter { imported ->
                    allowedEverywhere.none { imported.startsWith(it) } &&
                        !(underArrows && allowedUnderArrows.any { imported.startsWith(it) })
                }
                .map { "$name: $it" }
                .toList()
        }.sorted()
}
```

- [ ] **Step 4: Grün sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*OpenCvImportRuleTest*"`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Commit**

```bash
git add detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvImports.kt detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvImportRuleTest.kt
git commit -m "detection: the import rule allows org.opencv.dnn under arrows/ only"
```

---

## Task 3: `HeatmapPeaks`

`peaks()` aus `train.py`, Zeile für Zeile, ohne OpenCV: ein Pixel ist eine Spitze, wenn sein Wert dem Maximum im `kernel × kernel`-Fenster (am Rand beschnitten) gleicht und die Schwelle erreicht; seine Lage ist der Schwerpunkt des 3×3-Fensters, gewichtet mit den rohen Werten, mal `stride`.

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/HeatmapPeaks.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/HeatmapPeaksTest.kt`

**Interfaces:**
- Produces:
  - `data class Peak(val u: Double, val v: Double, val value: Double)` — Lage in Pixeln der Netzeingabe (Pixelzentren auf ganzen Zahlen), Wert die Wahrscheinlichkeit
  - `class Heatmap(val width: Int, val height: Int, val values: FloatArray)` mit `operator fun get(x: Int, y: Int): Float`
  - `HeatmapPeaks.find(heat: Heatmap, stride: Int, kernel: Int, threshold: Double, maxCount: Int? = null): List<Peak>` — nach Wert absteigend, stabil, auf `maxCount` gekürzt

- [ ] **Step 1: Die Tests schreiben**

`HeatmapPeaksTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeatmapPeaksTest {

    private fun heat(width: Int, height: Int, vararg cells: Triple<Int, Int, Float>): Heatmap {
        val values = FloatArray(width * height)
        for ((x, y, v) in cells) values[y * width + x] = v
        return Heatmap(width, height, values)
    }

    private fun find(heat: Heatmap, threshold: Double = 0.12, maxCount: Int? = null) =
        HeatmapPeaks.find(heat, stride = 2, kernel = 5, threshold = threshold, maxCount = maxCount)

    @Test
    fun aLonePixelIsAPeakAtTwiceItsIndex() {
        val peaks = find(heat(8, 8, Triple(3, 4, 0.5f)))

        assertThat(peaks).containsExactly(Peak(6.0, 8.0, 0.5))
    }

    @Test
    fun belowTheThresholdNothingIsAPeak() {
        assertThat(find(heat(8, 8, Triple(3, 4, 0.11f)))).isEmpty()
    }

    @Test
    fun aValueExactlyOnTheThresholdIsKeptLikeTorchDoes() {
        // torch casts the threshold to float32 before comparing; 0.12f is a hair below 0.12.
        assertThat(find(heat(8, 8, Triple(3, 4, 0.12f)))).hasSize(1)
    }

    @Test
    fun thePositionIsTheCentroidOfTheThreeByThreeWindow() {
        val peaks = find(heat(8, 8, Triple(3, 4, 0.5f), Triple(4, 4, 0.25f)))

        // (3 * 0.5 + 4 * 0.25) / 0.75 = 3.333.. in heat pixels, times the stride.
        assertThat(peaks).hasSize(1)
        assertThat(peaks[0].u).isWithin(1e-9).of(2.0 * (3.0 * 0.5 + 4.0 * 0.25) / 0.75)
        assertThat(peaks[0].v).isWithin(1e-9).of(8.0)
        assertThat(peaks[0].value).isWithin(1e-9).of(0.5)
    }

    @Test
    fun theWindowIsClippedAtTheBorder() {
        val peaks = find(heat(8, 8, Triple(0, 0, 0.5f), Triple(1, 0, 0.1f)))

        assertThat(peaks).hasSize(1)
        assertThat(peaks[0].u).isWithin(1e-6).of(2.0 * (0.0 * 0.5 + 1.0 * 0.1) / 0.6)
        assertThat(peaks[0].v).isWithin(1e-9).of(0.0)
    }

    @Test
    fun twoPeaksCloserThanTheKernelMergeIntoTheHigherOne() {
        // Two pixels apart lies inside the 5 x 5 window; three apart does not.
        val close = find(heat(12, 12, Triple(3, 3, 0.5f), Triple(5, 3, 0.4f)))
        val apart = find(heat(12, 12, Triple(3, 3, 0.5f), Triple(6, 3, 0.4f)))

        assertThat(close.map { it.value }).containsExactly(0.5)
        assertThat(apart.map { it.value }).containsExactly(0.5, 0.4).inOrder()
    }

    @Test
    fun equalNeighboursAreBothPeaks() {
        // The behaviour of peaks(): a plateau keeps every pixel equal to the window maximum.
        assertThat(find(heat(8, 8, Triple(3, 3, 0.5f), Triple(4, 3, 0.5f)))).hasSize(2)
    }

    @Test
    fun peaksComeHighestFirstAndAreCutToMaxCount() {
        val heat = heat(20, 20, Triple(2, 2, 0.3f), Triple(10, 2, 0.9f), Triple(2, 10, 0.6f), Triple(10, 10, 0.2f))

        assertThat(find(heat).map { it.value }).containsExactly(0.9, 0.6, 0.3, 0.2).inOrder()
        assertThat(find(heat, maxCount = 2).map { it.value }).containsExactly(0.9, 0.6).inOrder()
        assertThat(find(heat, maxCount = 0)).isEmpty()
    }

    @Test
    fun aHeatmapChecksItsSize() {
        try {
            Heatmap(4, 4, FloatArray(15))
            throw AssertionError("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("16")
        }
    }
}
```

- [ ] **Step 2: Fehlschlag sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*HeatmapPeaksTest*"`
Expected: Kompilierfehler, `Heatmap`, `Peak` und `HeatmapPeaks` fehlen.

- [ ] **Step 3: Implementieren**

`HeatmapPeaks.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import kotlin.math.max
import kotlin.math.min

/**
 * A local maximum of the tip heatmap (design 3d, step 5): [u], [v] in pixels of
 * the network input, pixel centres at integers; [value] the probability there.
 */
data class Peak(val u: Double, val v: Double, val value: Double)

/** The tip heatmap as the network returns it: channel 0, row-major, at the stride's resolution. */
class Heatmap(val width: Int, val height: Int, val values: FloatArray) {

    init {
        require(width > 0 && height > 0) { "a heatmap needs at least one pixel, got $width x $height" }
        require(values.size == width * height) { "expected ${width * height} values for $width x $height, got ${values.size}" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]
}

/**
 * peaks() of train.py, line for line, so the app cuts the heatmap exactly as
 * the PoC was measured: a pixel is a peak when it equals the maximum of the
 * kernel x kernel window around it (clipped at the border) and reaches the
 * threshold; its position is the centroid of the 3 x 3 window weighted by the
 * raw values, scaled by the stride into input pixels.
 */
object HeatmapPeaks {

    /** Highest value first, stable for equal values; at most [maxCount] when given. */
    fun find(heat: Heatmap, stride: Int, kernel: Int, threshold: Double, maxCount: Int? = null): List<Peak> {
        require(stride >= 1) { "the stride is at least 1, got $stride" }
        require(kernel >= 1 && kernel % 2 == 1) { "the kernel is odd and at least 1, got $kernel" }
        require(maxCount == null || maxCount >= 0) { "maxCount is not negative, got $maxCount" }
        // torch compares the float32 heatmap against the threshold cast to float32.
        val limit = threshold.toFloat()
        val reach = kernel / 2
        val found = ArrayList<Peak>()
        for (y in 0 until heat.height) {
            for (x in 0 until heat.width) {
                val centre = heat[x, y]
                if (centre < limit) continue
                var windowMax = Float.NEGATIVE_INFINITY
                for (j in max(0, y - reach)..min(heat.height - 1, y + reach)) {
                    for (i in max(0, x - reach)..min(heat.width - 1, x + reach)) {
                        windowMax = max(windowMax, heat[i, j])
                    }
                }
                if (centre != windowMax) continue
                var weight = 0.0
                var sumX = 0.0
                var sumY = 0.0
                for (j in max(0, y - 1)..min(heat.height - 1, y + 1)) {
                    for (i in max(0, x - 1)..min(heat.width - 1, x + 1)) {
                        val w = heat[i, j].toDouble()
                        weight += w
                        sumX += i * w
                        sumY += j * w
                    }
                }
                found += Peak(sumX / weight * stride, sumY / weight * stride, centre.toDouble())
            }
        }
        val ranked = found.sortedByDescending { it.value }
        return if (maxCount == null) ranked else ranked.take(maxCount)
    }
}
```

`weight` ist nie 0: der Mittelpixel liegt über der Schwelle, und die Beilage verlangt eine Schwelle über 0 (Task 5).

- [ ] **Step 4: Grün sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*HeatmapPeaksTest*"`
Expected: PASS, 9 Tests.

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/HeatmapPeaks.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/HeatmapPeaksTest.kt
git commit -m "detection: HeatmapPeaks, the local maxima of train.py's peaks() in Kotlin"
```

---

## Task 4: `PreShrink` und `FaceWarp.targetOf`

Schritt 2 des Designs: die längste Bildseite auf `preShrinkMaxSide` mit `INTER_AREA`, die Homographie skaliert; dazu die Umkehrung von `FaceWarp.pixelOf` für Schritt 6.

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/PreShrink.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/registration/FaceWarp.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/PreShrinkTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/FaceWarpTest.kt`

**Interfaces:**
- Consumes: `Mat3`, `Vec2`, `Imgproc.resize`
- Produces:
  - `class PreShrink` mit `val factor: Double`, `val width: Int`, `val height: Int`, `val isIdentity: Boolean`, `fun shrink(image: Mat): Mat` (neue Mat, der Aufrufer gibt frei), `fun imageToTarget(fromOriginal: Mat3): Mat3`; `PreShrink.of(width: Int, height: Int, maxSide: Int): PreShrink`
  - `FaceWarp.targetOf(px: Vec2, edge: Int): Vec2`

- [ ] **Step 1: Die Tests schreiben**

`PreShrinkTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

class PreShrinkTest {

    @get:Rule
    val openCv = OpenCvRule()

    @Test
    fun thePhotographOfTheCorpusShrinksToHalf() {
        // 4000 x 2252, the S25's photographs: pre = 2000 / 4000.
        val shrink = PreShrink.of(4000, 2252, 2000)

        assertThat(shrink.factor).isWithin(1e-12).of(0.5)
        assertThat(shrink.width).isEqualTo(2000)
        assertThat(shrink.height).isEqualTo(1126)
        assertThat(shrink.isIdentity).isFalse()
    }

    @Test
    fun aSmallImageStaysAsItIs() {
        val shrink = PreShrink.of(1600, 1200, 2000)

        assertThat(shrink.isIdentity).isTrue()
        assertThat(shrink.width).isEqualTo(1600)
        assertThat(shrink.height).isEqualTo(1200)
    }

    @Test
    fun theSizeRoundsHalfToEvenLikePython() {
        // Python's round(2.5) is 2 and round(3.5) is 4; Math.rint agrees, roundToInt would not.
        assertThat(PreShrink.of(10, 5, 5).height).isEqualTo(2)   // 5 * 0.5 = 2.5 -> 2
        assertThat(PreShrink.of(10, 7, 5).height).isEqualTo(4)   // 7 * 0.5 = 3.5 -> 4
    }

    @Test
    fun theShrunkHomographyMapsTheShrunkPixelWhereTheOriginalMappedTheOriginalPixel() {
        val shrink = PreShrink.of(4000, 2252, 2000)
        val original = Mat3.of(
            0.00236109, 7e-08, -4.19406437,
            0.00016237, 0.00213358, -3.48990962,
            0.0006076, -5.421e-05, 1.0
        )
        val p = Vec2(1775.17, 1501.02)

        val fromOriginal = original.mapPoint(p)!!
        val fromShrunk = shrink.imageToTarget(original).mapPoint(p * shrink.factor)!!

        assertThat(fromShrunk.distanceTo(fromOriginal)).isLessThan(1e-9)
    }

    @Test
    fun shrinkResamplesToTheComputedSize() {
        val image = Mat(2252, 4000, CvType.CV_8UC3, Scalar(10.0, 20.0, 30.0))
        val small = PreShrink.of(4000, 2252, 2000).shrink(image)
        try {
            assertThat(small.cols()).isEqualTo(2000)
            assertThat(small.rows()).isEqualTo(1126)
            assertThat(small.type()).isEqualTo(CvType.CV_8UC3)
        } finally {
            small.release()
            image.release()
        }
    }

    @Test
    fun anIdentityShrinkReturnsACopy() {
        val image = Mat(1200, 1600, CvType.CV_8UC3, Scalar(10.0, 20.0, 30.0))
        val small = PreShrink.of(1600, 1200, 2000).shrink(image)
        try {
            assertThat(small.nativeObj).isNotEqualTo(image.nativeObj)
            assertThat(small.cols()).isEqualTo(1600)
        } finally {
            small.release()
            image.release()
        }
    }
}
```

In `FaceWarpTest` ergänzen:

```kotlin
    @Test
    fun targetOfInvertsPixelOf() {
        val edge = 768
        for (target in listOf(Vec2(0.0, 0.0), Vec2(-1.1, -1.1), Vec2(0.37, -0.82))) {
            val back = FaceWarp.targetOf(FaceWarp.pixelOf(target, edge), edge)
            assertThat(back.distanceTo(target)).isLessThan(1e-12)
        }
        // Pixel 383.5 of 768 is the face centre: k = 768 / 2.2, k * 1.1 - 0.5 = 383.5.
        assertThat(FaceWarp.targetOf(Vec2(383.5, 383.5), edge).length).isLessThan(1e-12)
    }
```

- [ ] **Step 2: Fehlschlag sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*PreShrinkTest*" --tests "*FaceWarpTest*"`
Expected: Kompilierfehler, `PreShrink` und `targetOf` fehlen.

- [ ] **Step 3: Implementieren**

`PreShrink.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.rint

/**
 * The pre-shrink of prepare.py (design 3d, step 2): the longest side of the
 * original down to [maxSide] pixels with INTER_AREA before the warp, so the
 * warp samples the pixels the model was trained on. The size rounds half to
 * even like Python's round(), which Math.rint does and roundToInt does not.
 */
class PreShrink private constructor(val factor: Double, val width: Int, val height: Int) {

    val isIdentity: Boolean
        get() = factor == 1.0

    /** A new image of [width] x [height]; a copy when nothing shrinks. The caller releases it. */
    fun shrink(image: Mat): Mat =
        if (isIdentity) {
            image.clone()
        } else {
            Mat().releaseIfThrows { small ->
                Imgproc.resize(image, small, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            }
        }

    /** The homography from pixels of the shrunk image to target coordinates: H times S_pre of prepare.py. */
    fun imageToTarget(fromOriginal: Mat3): Mat3 =
        fromOriginal * Mat3.of(
            1.0 / factor, 0.0, 0.0,
            0.0, 1.0 / factor, 0.0,
            0.0, 0.0, 1.0
        )

    companion object {
        fun of(width: Int, height: Int, maxSide: Int): PreShrink {
            require(width > 0 && height > 0) { "an image has positive size, got $width x $height" }
            require(maxSide > 0) { "maxSide is positive, got $maxSide" }
            val factor = min(1.0, maxSide.toDouble() / max(width, height))
            return PreShrink(factor, rint(width * factor).toInt(), rint(height * factor).toInt())
        }
    }
}
```

`releaseIfThrows` ist `internal` im Paket `registration` desselben Moduls; der Import funktioniert wie in `ArrowDebugImages`.

In `FaceWarp` nach `pixelOf` einfügen:

```kotlin
    /** Inverse of [pixelOf]: the target point whose image is pixel [px] of a warped image of [edge] px. */
    fun targetOf(px: Vec2, edge: Int): Vec2 {
        val k = edge / (2.0 * EXTENT)
        return Vec2((px.x + 0.5) / k - EXTENT, (px.y + 0.5) / k - EXTENT)
    }
```

- [ ] **Step 4: Grün sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*PreShrinkTest*" --tests "*FaceWarpTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/PreShrink.kt detection/src/main/java/de/dreier/mytargets/detection/registration/FaceWarp.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/PreShrinkTest.kt detection/src/test/java/de/dreier/mytargets/detection/registration/FaceWarpTest.kt
git commit -m "detection: PreShrink like prepare.py and FaceWarp.targetOf"
```

---

## Task 5: `ArrowModel` und die Beilage

Der Modellordner (Design, *Ablageformat*): `model.onnx` und `model.json`. Die Beilage ist die einzige Quelle für Größe, Vorverkleinerung und Schwelle.

**Files:**
- Modify: `detection/build.gradle` (`implementation libs.gson`)
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowModel.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowModelTest.kt`
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedModelSmokeTest.kt`

**Interfaces:**
- Produces:
  - `class ArrowModelMeta(val inputSize: Int, val stride: Int, val kernel: Int, val preShrinkMaxSide: Int, val threshold: Double, val training: String?, val corpus: String?, val thresholdFrom: String?, val crossValidation: Map<String, String>)` mit `val outputSize: Int = inputSize / stride`
  - `class ArrowModel(val onnx: File, val meta: ArrowModelMeta)`; `ArrowModel.load(dir: File): ArrowModel`; `ArrowModel.parseMeta(json: String, source: String = META_FILE): ArrowModelMeta`; `ArrowModel.ONNX_FILE = "model.onnx"`, `ArrowModel.META_FILE = "model.json"`

- [ ] **Step 1: Gson**

In `detection/build.gradle`, im `dependencies`-Block nach `implementation libs.kotlin.stdlib.jdk7`:

```groovy
    // The learned finder's model folder carries a JSON sidecar (design 3d,
    // Ablageformat); :app and :detection-corpus read JSON with Gson already.
    implementation libs.gson
```

- [ ] **Step 2: Die Tests schreiben**

`ArrowModelTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArrowModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val complete = """
        {
          "inputSize": 768, "stride": 2, "kernel": 5,
          "preShrinkMaxSide": 2000,
          "threshold": 0.12,
          "training": "runs/all-2026-09-17", "corpus": "e4fb964",
          "thresholdFrom": "median of r4 FP-limited fold thresholds",
          "crossValidation": {"oblique": "71.3 % at 1.06 FP/view", "fpLimited": "63.3 % at 0.64"},
          "precision": "fp16"
        }
    """.trimIndent()

    private fun failure(json: String): IllegalArgumentException =
        try {
            ArrowModel.parseMeta(json, "model.json")
            throw AssertionError("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }

    @Test
    fun readsEveryFieldOfTheSidecar() {
        val meta = ArrowModel.parseMeta(complete)

        assertThat(meta.inputSize).isEqualTo(768)
        assertThat(meta.stride).isEqualTo(2)
        assertThat(meta.kernel).isEqualTo(5)
        assertThat(meta.preShrinkMaxSide).isEqualTo(2000)
        assertThat(meta.threshold).isWithin(1e-12).of(0.12)
        assertThat(meta.outputSize).isEqualTo(384)
        assertThat(meta.training).isEqualTo("runs/all-2026-09-17")
        assertThat(meta.corpus).isEqualTo("e4fb964")
        assertThat(meta.thresholdFrom).isEqualTo("median of r4 FP-limited fold thresholds")
        assertThat(meta.crossValidation).containsEntry("fpLimited", "63.3 % at 0.64")
    }

    @Test
    fun theDescriptiveFieldsAreOptional() {
        val meta = ArrowModel.parseMeta(
            """{"inputSize": 512, "stride": 2, "kernel": 5, "preShrinkMaxSide": 2000, "threshold": 0.1}"""
        )

        assertThat(meta.training).isNull()
        assertThat(meta.crossValidation).isEmpty()
    }

    @Test
    fun aMissingFieldIsNamed() {
        val e = failure("""{"inputSize": 768, "stride": 2, "kernel": 5, "preShrinkMaxSide": 2000}""")

        assertThat(e).hasMessageThat().contains("model.json: missing threshold")
    }

    @Test
    fun brokenJsonIsNamed() {
        assertThat(failure("{ this is not json")).hasMessageThat().contains("model.json: cannot read JSON")
        assertThat(failure("")).hasMessageThat().contains("model.json: empty JSON")
    }

    @Test
    fun theValuesAreChecked() {
        fun with(field: String, value: String) = complete.replace(Regex(""""$field": [^,\n]*"""), """"$field": $value""")

        assertThat(failure(with("threshold", "0"))).hasMessageThat().contains("threshold")
        assertThat(failure(with("threshold", "1.5"))).hasMessageThat().contains("threshold")
        assertThat(failure(with("kernel", "4"))).hasMessageThat().contains("kernel")
        assertThat(failure(with("inputSize", "767"))).hasMessageThat().contains("stride")
        assertThat(failure(with("preShrinkMaxSide", "500"))).hasMessageThat().contains("preShrinkMaxSide")
    }

    @Test
    fun loadFindsBothFilesInTheFolder() {
        val dir = folder.newFolder("r4-all")
        File(dir, "model.onnx").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "model.json").writeText(complete)

        val model = ArrowModel.load(dir)

        assertThat(model.onnx).isEqualTo(File(dir, "model.onnx"))
        assertThat(model.meta.inputSize).isEqualTo(768)
    }

    @Test
    fun loadNamesTheMissingFile() {
        val dir = folder.newFolder("empty")
        File(dir, "model.json").writeText(complete)

        val e = try {
            ArrowModel.load(dir)
            throw AssertionError("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }
        assertThat(e).hasMessageThat().contains("model.onnx")
        assertThat(e).hasMessageThat().contains(dir.name)
    }
}
```

- [ ] **Step 3: Fehlschlag sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*ArrowModelTest*"`
Expected: Kompilierfehler, `ArrowModel` fehlt.

- [ ] **Step 4: Implementieren**

`ArrowModel.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File

/**
 * The sidecar model.json of a model folder (design 3d, Ablageformat): the only
 * source of the input size, the pre-shrink and the threshold. Nothing of it is
 * repeated in Kotlin code.
 */
class ArrowModelMeta(
    /** Edge of the square network input in pixels; the ONNX export is fixed to it. */
    val inputSize: Int,
    /** Input pixels per heatmap pixel. */
    val stride: Int,
    /** Window of the local maximum search, odd. */
    val kernel: Int,
    /** Longest side of the original before the warp, the constant of prepare.py. */
    val preShrinkMaxSide: Int,
    /** A heatmap value at or above this is a peak. */
    val threshold: Double,
    val training: String?,
    val corpus: String?,
    val thresholdFrom: String?,
    val crossValidation: Map<String, String>
) {
    init {
        require(stride >= 1) { "stride is at least 1, got $stride" }
        require(inputSize > 0 && inputSize % stride == 0) {
            "inputSize is a positive multiple of the stride $stride, got $inputSize"
        }
        require(kernel >= 1 && kernel % 2 == 1) { "kernel is odd and at least 1, got $kernel" }
        require(preShrinkMaxSide >= inputSize) {
            "preShrinkMaxSide ($preShrinkMaxSide) is at least the inputSize ($inputSize)"
        }
        require(threshold > 0.0 && threshold < 1.0) { "threshold lies in (0, 1), got $threshold" }
    }

    /** Edge of the heatmap the network returns. */
    val outputSize: Int
        get() = inputSize / stride
}

/** A model folder: the ONNX weights and their sidecar. */
class ArrowModel(val onnx: File, val meta: ArrowModelMeta) {

    companion object {
        const val ONNX_FILE = "model.onnx"
        const val META_FILE = "model.json"

        /** Reads [dir]/model.json and checks that [dir]/model.onnx exists; the network itself is read by the detector. */
        fun load(dir: File): ArrowModel {
            val onnx = File(dir, ONNX_FILE)
            val meta = File(dir, META_FILE)
            require(onnx.isFile) { "${dir.path}: no $ONNX_FILE" }
            require(meta.isFile) { "${dir.path}: no $META_FILE" }
            return ArrowModel(onnx, parseMeta(meta.readText(), meta.path))
        }

        /** Parses a sidecar; every error names [source] and the field. */
        fun parseMeta(json: String, source: String = META_FILE): ArrowModelMeta {
            val raw = try {
                Gson().fromJson(json, MetaJson::class.java)
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("$source: cannot read JSON", e)
            } ?: throw IllegalArgumentException("$source: empty JSON")

            fun <T> need(name: String, value: T?): T = requireNotNull(value) { "$source: missing $name" }
            val inputSize = need("inputSize", raw.inputSize)
            val stride = need("stride", raw.stride)
            val kernel = need("kernel", raw.kernel)
            val preShrinkMaxSide = need("preShrinkMaxSide", raw.preShrinkMaxSide)
            val threshold = need("threshold", raw.threshold)
            // Only the value checks of the constructor are prefixed here; a missing field already names the source.
            try {
                return ArrowModelMeta(
                    inputSize, stride, kernel, preShrinkMaxSide, threshold,
                    raw.training, raw.corpus, raw.thresholdFrom, raw.crossValidation ?: emptyMap()
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$source: ${e.message}", e)
            }
        }
    }

    // Nullable, so a missing field is reported by name rather than as a zero.
    private class MetaJson {
        var inputSize: Int? = null
        var stride: Int? = null
        var kernel: Int? = null
        var preShrinkMaxSide: Int? = null
        var threshold: Double? = null
        var training: String? = null
        var corpus: String? = null
        var thresholdFrom: String? = null
        var crossValidation: Map<String, String>? = null
    }
}
```

- [ ] **Step 5: Grün sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*ArrowModelTest*"`
Expected: PASS, 7 Tests.

- [ ] **Step 6: Rauchtest und Probeordner auf die Beilage umstellen**

In `../MyTargets-learn/models-smoke/model.json` von Hand anlegen (Task 8 überschreibt sie mit dem Export):

```json
{
  "inputSize": 768, "stride": 2, "kernel": 5,
  "preShrinkMaxSide": 2000,
  "threshold": 0.12,
  "training": "runs/r4 fold 3 (smoke folder, not the shipped model)",
  "thresholdFrom": "median of r4 FP-limited fold thresholds"
}
```

`LearnedModelSmokeTest` liest Größe und Ausgabe aus der Beilage statt der festen 768:

```kotlin
    @Test
    fun theDesktopJarReadsTheModelAndRunsIt() {
        val model = ArrowModel.load(dir)
        val size = model.meta.inputSize
        val net = Dnn.readNetFromONNX(model.onnx.absolutePath)
        assertThat(net.empty()).isFalse()

        val input = Mat.zeros(size, size, CvType.CV_8UC3)
        val blob = Dnn.blobFromImage(input, 1.0 / 255.0, Size(size.toDouble(), size.toDouble()), Scalar(0.0, 0.0, 0.0), true, false)
        try {
            net.setInput(blob)
            val out = net.forward()
            try {
                assertThat(out.dims()).isEqualTo(4)
                assertThat(listOf(out.size(0), out.size(1), out.size(2), out.size(3)))
                    .containsExactly(1, 2, model.meta.outputSize, model.meta.outputSize).inOrder()
                assertThat(out.type()).isEqualTo(CvType.CV_32F)
            } finally {
                out.release()
            }
        } finally {
            blob.release()
            input.release()
        }
    }
```

Die `assumeTrue`-Zeile für `model.onnx` in `requireModel` durch eine für beide Dateien ersetzen:

```kotlin
        assumeTrue("no ${ArrowModel.ONNX_FILE} in $configured", File(dir, ArrowModel.ONNX_FILE).isFile)
        assumeTrue("no ${ArrowModel.META_FILE} in $configured", File(dir, ArrowModel.META_FILE).isFile)
```

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedModelSmokeTest*" --rerun`
Expected: PASS, 1 Test ausgeführt.

- [ ] **Step 7: Commit**

```bash
git add detection/build.gradle detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowModel.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowModelTest.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedModelSmokeTest.kt
git commit -m "detection: ArrowModel reads the model folder and its sidecar"
```

---

## Task 6: `CorpusLoader` überspringt `models/`

**Files:**
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoader.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoaderTest.kt`
- Modify: `../MyTargets-corpus/README.md` (Korpus-Repo, Abschnitt *Aufbau*)

**Interfaces:**
- Produces: `CorpusLoader.MODELS_DIR = "models"`; der Ordner direkt unter der Wurzel wird weder nach Bildern noch nach Sidecars durchsucht

- [ ] **Step 1: Die Tests schreiben**

In `CorpusLoaderTest` ergänzen:

```kotlin
    @Test
    fun theModelsFolderAtTheRootIsNoCorpusFolder() {
        // Design 3d, Ablageformat: the learned finder's weights and their JSON
        // sidecars live under models/; none of it is a photograph or a sidecar.
        write("wa-full/a6_998877.jpg")
        write("models/arrows/r4-all/model.json", "{}")
        write("models/arrows/r4-all/model.onnx")
        write("models/arrows/r4-all/parity/view.json", "{}")
        write("models/arrows/r4-all/preview.png")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName }).containsExactly("a6_998877.jpg")
        assertThat(result.ignored).isEmpty()
        assertThat(result.orphanSidecars).isEmpty()
    }

    @Test
    fun aModelsFolderBelowTheRootIsAnOrdinaryFolder() {
        write("wa-full/models/stray.json", "{}")

        assertThat(CorpusLoader.load(folder.root).orphanSidecars).containsExactly("stray.json")
    }
```

- [ ] **Step 2: Fehlschlag sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection-corpus:test --tests "*CorpusLoaderTest*"`
Expected: FAIL, `theModelsFolderAtTheRootIsNoCorpusFolder` findet `preview.png` unter `ignored` und zwei verwaiste Sidecars.

- [ ] **Step 3: Implementieren**

In `CorpusLoader`:

```kotlin
    /**
     * The learned arrow finder's model folders (design 3d, Ablageformat):
     * weights, a JSON sidecar per model and parity references. Not a corpus
     * folder; only skipped directly under the root.
     */
    const val MODELS_DIR = "models"
```

In `load(root)` den Aufruf ändern:

```kotlin
        loadDirectory(
            root, entries, ignored, orphans, folderOfImage = mutableMapOf(),
            exemptFromOrphanCheck = setOf(OUT_OF_SCOPE_FILE),
            skipDirectories = setOf(MODELS_DIR)
        )
```

`loadDirectory` bekommt den Parameter `skipDirectories: Set<String> = emptySet()` (nach `exemptFromOrphanCheck`), und die Rekursion prüft ihn; der rekursive Aufruf gibt ihn nicht weiter, so gilt er nur an der Wurzel:

```kotlin
        for (child in children.sortedBy { it.name }) {
            // A dot-directory (`.git`, a tool's `.venv` under `learn/`) is
            // never a corpus folder; the images its packages ship would
            // otherwise be reported as unrecognised. Skipped whole, as is
            // the models folder at the root.
            if (child.isDirectory && !child.name.startsWith(".") && child.name !in skipDirectories) {
                loadDirectory(child, entries, ignored, orphans, folderOfImage)
            }
        }
```

Die Klassen-Doku um einen Satz ergänzen: „`models/` at the root holds the learned finder's weights and is not read; see [MODELS_DIR].“

- [ ] **Step 4: Grün sehen, samt echtem Korpus**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection-corpus:test`
Expected: PASS, `RealCorpusTest` weiter bei 117 Einträgen.

- [ ] **Step 5: Korpus-README**

In `../MyTargets-corpus/README.md`, Abschnitt *Aufbau*, an den Baum anhängen:

```
models/arrows/<name>/ Gewichte des gelernten Pfeilfinders (model.onnx, model.json, parity/), kein Korpusordner:
                      der Loader der App überspringt `models/` an der Wurzel. Siehe learn/README.md.
```

Commit im Korpus-Repo:

```bash
git -C ../MyTargets-corpus add README.md
git -C ../MyTargets-corpus commit -m "README: models/ holds the learned finder's weights, not photographs"
```

- [ ] **Step 6: Commit**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoader.kt detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoaderTest.kt
git commit -m "detection-corpus: the loader skips models/ at the corpus root"
```

---

## Task 7: `train.py --all` und das Training starten

Das ausgelieferte Modell trainiert auf allen Ansichten (Design, *Trainings- und Asset-Kette*): Konfiguration von r4, kein Fold, keine Schwellenwahl. Rund 45 Minuten; der Lauf startet hier und läuft neben Task 8 bis 11.

**Files:**
- Modify: `../MyTargets-corpus/learn/train.py`

**Interfaces:**
- Produces: `fit(args, views, train_names, seed, label) -> model`; `train_all(args, views, run)`; Flag `--all`; Ausgabe `<run>/model_all.pt` und `<run>/args.json`

- [ ] **Step 1: Die Trainingsschleife in `fit()` ziehen**

In `train.py` vor `train_fold` einfügen und `train_fold` darauf umstellen:

```python
def fit(args, views, train_names, seed, label):
    """Train one model from scratch on train_names; train_fold and train_all share this."""
    torch.manual_seed(seed); random.seed(seed); np.random.seed(seed)
    ds = Dataset(views, train_names * args.repeat, args.res, args.crop, True, args.stride, args.sigma, seed)
    dl = torch.utils.data.DataLoader(ds, batch_size=args.batch, shuffle=True, num_workers=args.workers, drop_last=True, persistent_workers=args.workers > 0)
    model = UNet(pretrained=not args.no_pretrained)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    steps = args.epochs * len(dl)
    warm = max(3, steps // 20)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda i: (i + 1) / warm if i < warm else 0.5 * (1 + math.cos(math.pi * (i - warm) / max(1, steps - warm))))
    t0 = time.time()
    for ep in range(args.epochs):
        ds.epoch = ep
        model.train(); tot = 0.0; n = 0
        for x, y, m in dl:
            out = model(x)
            loss = focal_loss(out, y, m)
            opt.zero_grad(); loss.backward(); opt.step(); sched.step()
            tot += loss.item(); n += 1
        print(f"{label} epoch {ep + 1}/{args.epochs} loss {tot / max(1, n):.4f} ({time.time() - t0:.0f} s)", flush=True)
    return model


def train_fold(args, views, fold, fold_of, run):
    names = [e["name"] for e in views.index["entries"]]
    train_names = [n for n in names if fold_of[n] != fold]
    test_names = [n for n in names if fold_of[n] == fold]
    model = fit(args, views, train_names, args.seed + fold, f"fold {fold}")
    torch.save(model.state_dict(), run / f"model_fold{fold}.pt")
    train_heats = predict(model, views, train_names, args.res, args.stride)
    ...  # unchanged from here on
```

Die Zeilen von `train_heats = ...` bis `return dets, thresh, dets_fp, thresh_fp` bleiben, wie sie sind. Die drei Seed-Zeilen, `ds`, `dl`, `model`, `opt`, `steps`, `warm`, `sched`, `t0` und die Epochenschleife fallen aus `train_fold` heraus. Die Seeds werden wie vorher **vor** dem Dataset gesetzt (`args.seed + fold`), r4 bliebe reproduzierbar.

- [ ] **Step 2: `train_all` und das Flag**

Nach `train_fold`:

```python
def train_all(args, views, run):
    """No fold: every view with truth trains the model that ships (design 3d,
    Trainings- und Asset-Kette). No threshold is chosen here, it would fall on
    training views; the shipped one is the median of r4's FP-limited fold thresholds."""
    names = [e["name"] for e in views.index["entries"]]
    model = fit(args, views, names, args.seed, "all")
    torch.save(model.state_dict(), run / "model_all.pt")
    arrows = sum(len(e["shots"]) for e in views.index["entries"])
    print(f"all: {len(names)} views, {arrows} arrows -> {run / 'model_all.pt'}", flush=True)
```

In `main()`:

```python
    ap.add_argument("--all", action="store_true", help="no folds: train one model on every view for the app; no threshold, no detections")
```

und nach `json.dump(vars(args), open(run / "args.json", "w"), indent=1)`:

```python
    if args.all:
        train_all(args, views, run)
        return
```

Den Docstring am Dateikopf um den Absatz ergänzen: „`--all` trains one model on every view for the app (design 3d in the main repo); it writes model_all.pt and no detections.json.“

- [ ] **Step 3: Kurz prüfen, dass die Folds unverändert laufen**

```bash
cd ../MyTargets-corpus/learn && .venv/Scripts/python -c "import ast,sys; ast.parse(open('train.py').read()); print('syntax ok')"
.venv/Scripts/python train.py --epochs 1 --folds 5 --fold 0 --res 256 --crop 192 --run ../../MyTargets-learn/runs/smoke-fit
```

Expected: eine Epoche `fold 0 epoch 1/1 loss …`, dann `fold 0: threshold F1 … / FP-limited … chosen on 80 training views; 20 held-out views predicted` und ein Bericht. Danach `rm -r ../../MyTargets-learn/runs/smoke-fit`.

- [ ] **Step 4: Das Training auf allen Ansichten starten**

Als getrennter Prozess (PowerShell), Log außerhalb des Korpus:

```powershell
Start-Process -FilePath "C:\Users\olive\projekte\MyTargets-corpus\learn\.venv\Scripts\python.exe" `
  -ArgumentList "train.py","--all","--epochs","60","--res","768","--crop","512","--run","../../MyTargets-learn/runs/all-2026-09-17" `
  -WorkingDirectory "C:\Users\olive\projekte\MyTargets-corpus\learn" `
  -RedirectStandardOutput "C:\Users\olive\projekte\MyTargets-learn\runs_all.log" `
  -RedirectStandardError "C:\Users\olive\projekte\MyTargets-learn\runs_all.err"
```

Nach einer Minute prüfen: `tail -3 ../MyTargets-learn/runs_all.log` zeigt `all epoch 1/60 …` (rund 43 s je Epoche, also gut 40 Minuten). Fertig ist der Lauf, wenn die letzte Zeile `all: 100 views, … arrows -> …model_all.pt` lautet. Task 12 wartet darauf; bis dahin weiter mit Task 8.

- [ ] **Step 5: Commit im Korpus-Repo**

```bash
git -C ../MyTargets-corpus add learn/train.py
git -C ../MyTargets-corpus commit -m "learn: train.py --all trains the model that ships on every view"
```

---

## Task 8: `export_onnx.py` exportiert das Hüllmodul, die Beilage und die Paritätsreferenz

Entscheidung 5 des Designs: `(x − mean)/std → UNet → Sigmoid` für RGB in [0, 1]. Dazu `--out` für den Modellordner, `model.json`, und `--peaks` für `parity/<name>.json`. Geprobt wird mit Fold 3 von r4 in den Probeordner; Task 12 exportiert das All-Modell.

**Files:**
- Modify: `../MyTargets-corpus/learn/prepare.py` (`PRE_SHRINK_MAX_SIDE`, `rectify()`)
- Modify: `../MyTargets-corpus/learn/export_onnx.py`

**Interfaces:**
- Consumes: `train.UNet`, `train.peaks`, `prepare.face_to_px`, `prepare.CORPUS`, `prepare.OUT`
- Produces:
  - `prepare.PRE_SHRINK_MAX_SIDE = 2000`, `prepare.rectify(img, H, size) -> size x size image` (BGR in, BGR out; RGB in, RGB out)
  - `export_onnx.py <run> (--fold N | --all) [--res 768] [--fp16] [--out DIR --threshold T --threshold-from TEXT --cv-oblique TEXT --cv-fp-limited TEXT] [--peaks NAME ...]`
  - `model.json` mit den Feldern aus Task 5 plus `precision`, `exported`
  - `parity/<name>.json`: `{"view", "inputSize", "stride", "kernel", "threshold", "maxCount", "imageToTarget": [[…],[…],[…]], "imageMeanBgr": [b, g, r], "peaks": [{"u", "v", "value"}, …]}`

- [ ] **Step 1: `rectify()` in `prepare.py`**

Nach `face_to_px`:

```python
PRE_SHRINK_MAX_SIDE = 2000  # longest side before the warp; the app repeats this (design 3d, step 2)


def rectify(img, H, size):
    """The face of img (any channel order, EXIF applied) in the frame of
    face_to_px(size): shrink the longest side to PRE_SHRINK_MAX_SIDE with
    INTER_AREA so the warp skips no source pixel, scale H accordingly, warp
    bilinearly. The one rendering the model trains on and the app reproduces."""
    h0, w0 = img.shape[:2]
    pre = min(1.0, PRE_SHRINK_MAX_SIDE / max(w0, h0))
    small = cv2.resize(img, (round(w0 * pre), round(h0 * pre)), interpolation=cv2.INTER_AREA) if pre < 1 else img
    S_pre = np.diag([1 / pre, 1 / pre, 1.0])  # small px -> full px
    M = face_to_px(size) @ H @ S_pre
    return cv2.warpPerspective(small, M, (size, size), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0))
```

In `main()` die fünf Zeilen von `pre = min(1.0, 2000.0 / max(w0, h0))` bis `rect = cv2.warpPerspective(...)` durch `rect = rectify(img, H, size)` ersetzen (`h0, w0` bleiben für den `assert` davor).

Prüfen, dass die Ausgabe gleich bleibt:

```bash
cd ../MyTargets-corpus/learn && .venv/Scripts/python prepare.py --out ../../MyTargets-learn/data-check > /dev/null && .venv/Scripts/python -c "
import cv2, numpy as np, pathlib
a = pathlib.Path('../../MyTargets-learn/data'); b = pathlib.Path('../../MyTargets-learn/data-check')
diff = max(np.abs(cv2.imread(str(p)).astype(int) - cv2.imread(str(b / p.name)).astype(int)).max() for p in a.glob('*.png'))
print('max pixel diff', diff)"
```

Expected: `max pixel diff 0`. Danach `rm -r ../../MyTargets-learn/data-check`.

- [ ] **Step 2: `export_onnx.py` neu schreiben**

```python
"""Export a trained model to ONNX for the app and check it in OpenCV DNN.

  python export_onnx.py runs/<run> --fold 3 [--res 768] [--fp16]
      -> <run>/model_fold3_<res>[_fp16].onnx, as the timing test assets need them
  python export_onnx.py runs/<run> --all --res 768 --fp16 --out <corpus>/models/arrows/<name> \
      --threshold 0.12 --threshold-from "..." --cv-oblique "..." --cv-fp-limited "..." --peaks <view> [<view> ...]
      -> <out>/model.onnx, <out>/model.json, <out>/parity/<view>.json (design 3d, Ablageformat)

The graph is the wrapper (x - mean) / std -> UNet -> Sigmoid for an RGB image x
in [0, 1] (design 3d, decision 5): the app feeds blobFromImage(1/255, swapRB)
and reads probabilities; the ImageNet constants live here and nowhere in
Kotlin. The export is fixed to one input size because the bilinear Resize
sizes are baked into the graph: one export per resolution.

--peaks writes the parity reference: for each named view the photograph is
read with cv2 (as the app decodes), shrunk and warped with prepare.rectify,
run through the EXPORTED onnx in cv2.dnn and cut with train.peaks at the
threshold and shotsPerEnd. LearnedParityTest in the main repo must reproduce
these peaks to 0.5 px and 0.01 in value.
"""
import argparse, json, os, shutil, subprocess, sys, time
from pathlib import Path
import numpy as np
import cv2
import torch
import torch.nn as nn

from train import UNet, peaks, DATA
from prepare import CORPUS, rectify, PRE_SHRINK_MAX_SIDE

KERNEL = 5  # window of peaks(); the app reads it from model.json


class Wrapped(nn.Module):
    """ImageNet normalisation and sigmoid around the U-Net, so the app feeds RGB in [0, 1]."""

    def __init__(self, unet):
        super().__init__()
        self.unet = unet
        self.register_buffer("mean", torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1))

    def forward(self, x):
        return torch.sigmoid(self.unet((x - self.mean) / self.std))


def export(run, weights, res, fp16):
    """Writes the fp32 export and, with fp16, the half copy; returns the files and the torch reference on a random input."""
    unet = UNet(pretrained=False)
    unet.load_state_dict(torch.load(run / weights, map_location="cpu", weights_only=True))
    model = Wrapped(unet).eval()
    x = torch.rand(1, 3, res, res)
    stem = weights.replace(".pt", "")
    out = run / f"{stem}_{res}.onnx"
    torch.onnx.export(model, x, out, input_names=["image"], output_names=["heat"], opset_version=17, dynamo=False)
    with torch.no_grad():
        ref = model(x).numpy()
    files = [out]
    if fp16:
        import onnx
        from onnxconverter_common import float16
        half = float16.convert_float_to_float16(onnx.load(out), keep_io_types=True)
        out16 = run / f"{stem}_{res}_fp16.onnx"
        onnx.save(half, out16)
        files.append(out16)
    return files, x.numpy(), ref


def check(files, x, ref, res):
    cv2.setNumThreads(1)
    for f in files:
        net = cv2.dnn.readNetFromONNX(str(f))
        net.setInput(x)
        y = net.forward()
        t0 = time.time()
        for _ in range(3):
            net.forward()
        dt = (time.time() - t0) / 3
        print(f"{f.name}: {os.path.getsize(f) / 1e6:.1f} MB, OpenCV DNN 1 thread {res} px {dt:.2f} s, "
              f"max |prob diff| vs torch {np.abs(y - ref).max():.2e}, output {y.shape}, max prob {y[0, 0].max():.3f}")


def corpus_commit():
    return subprocess.check_output(["git", "-C", str(CORPUS), "rev-parse", "--short", "HEAD"], text=True).strip()


def write_model_folder(a, run, final):
    out_dir = Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy(final, out_dir / "model.onnx")
    saved = json.load(open(run / "args.json"))
    meta = dict(
        inputSize=a.res, stride=saved["stride"], kernel=KERNEL,
        preShrinkMaxSide=PRE_SHRINK_MAX_SIDE,
        threshold=a.threshold,
        training=str(run.as_posix()), corpus=corpus_commit(),
        thresholdFrom=a.threshold_from,
        crossValidation={k: v for k, v in (("oblique", a.cv_oblique), ("fpLimited", a.cv_fp_limited)) if v},
        precision="fp16" if a.fp16 else "fp32",
        exported=time.strftime("%Y-%m-%d"),
    )
    json.dump(meta, open(out_dir / "model.json", "w"), indent=2)
    print(f"model folder {out_dir}: model.onnx ({os.path.getsize(out_dir / 'model.onnx') / 1e6:.1f} MB), model.json {meta}")
    return out_dir, meta


def write_parity(out_dir, meta, names):
    """The reference of the parity test, from the exported onnx through cv2.dnn on the cv2-decoded photograph."""
    index = json.load(open(DATA / "index.json"))
    entries = {e["name"]: e for e in index["entries"]}
    res, stride, thresh = meta["inputSize"], meta["stride"], meta["threshold"]
    net = cv2.dnn.readNetFromONNX(str(out_dir / "model.onnx"))
    parity = out_dir / "parity"
    parity.mkdir(exist_ok=True)
    for name in names:
        e = entries[name]
        side_file = next(p for p in CORPUS.glob(f"*/{name}.json") if p.parent.name != "learn")
        side = json.load(open(side_file, encoding="utf-8"))
        H = np.array(side["registration"]["imageToTarget"], float)
        bgr = cv2.imread(str(side_file.with_suffix(".jpg")), cv2.IMREAD_COLOR)  # EXIF applied, like the app
        assert sorted(bgr.shape[:2]) == sorted((side["image"]["width"], side["image"]["height"])), name
        rect = rectify(bgr, H, res)
        blob = cv2.dnn.blobFromImage(rect, 1 / 255.0, (res, res), (0, 0, 0), swapRB=True, crop=False)
        net.setInput(blob)
        heat = net.forward()[0, 0]
        dets = peaks(heat, stride, KERNEL, thresh, max_n=e["shotsPerEnd"])
        ref = dict(view=name, inputSize=res, stride=stride, kernel=KERNEL, threshold=thresh, maxCount=e["shotsPerEnd"],
                   imageToTarget=side["registration"]["imageToTarget"],
                   imageMeanBgr=[float(m) for m in rect.reshape(-1, 3).mean(0)],
                   peaks=[dict(u=u, v=v, value=c) for u, v, c in dets])
        json.dump(ref, open(parity / f"{name}.json", "w"), indent=1)
        print(f"parity {name}: {len(dets)} peaks of {e['shotsPerEnd']}, values {[round(c, 3) for _, _, c in dets]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--fold", type=int, default=None, help="export model_fold<N>.pt")
    ap.add_argument("--all", action="store_true", help="export model_all.pt (train.py --all)")
    ap.add_argument("--res", type=int, default=768)
    ap.add_argument("--fp16", action="store_true", help="also write a half-precision copy (needs onnxconverter-common)")
    ap.add_argument("--out", default=None, help="model folder to write model.onnx and model.json into")
    ap.add_argument("--threshold", type=float, default=None)
    ap.add_argument("--threshold-from", default=None)
    ap.add_argument("--cv-oblique", default=None)
    ap.add_argument("--cv-fp-limited", default=None)
    ap.add_argument("--peaks", nargs="*", default=[], help="views to write parity/<name>.json for (needs --out)")
    a = ap.parse_args()
    if (a.fold is None) == (not a.all):
        sys.exit("give exactly one of --fold N or --all")
    if a.out and a.threshold is None:
        sys.exit("--out needs --threshold")
    run = Path(a.run)
    weights = "model_all.pt" if a.all else f"model_fold{a.fold}.pt"
    files, x, ref = export(run, weights, a.res, a.fp16)
    check(files, x, ref, a.res)
    if a.out:
        out_dir, meta = write_model_folder(a, run, files[-1])
        if a.peaks:
            write_parity(out_dir, meta, a.peaks)


if __name__ == "__main__":
    main()
```

Hinweis zu `--fold`: bisher war `--fold 0` Voreinstellung; jetzt ist eine der beiden Quellen Pflicht. Die README des Zeittests nennt `--fold 3` ausdrücklich, sie bleibt gültig.

- [ ] **Step 3: Probeexport von Fold 3 in den Probeordner**

```bash
cd ../MyTargets-corpus/learn && .venv/Scripts/python export_onnx.py ../../MyTargets-learn/runs/r4 --fold 3 --res 768 --fp16 \
  --out ../../MyTargets-learn/models-smoke --threshold 0.12 \
  --threshold-from "smoke folder: r4 fold 3, median of r4 FP-limited fold thresholds" \
  --peaks 2026-09-15_bedeckt_stark-schraeg_07 2026-08-15_bedeckt_frontal_02
```

Expected:
- zwei Zeilen `model_fold3_768.onnx: 57.5 MB … max |prob diff| vs torch …e-0x` und `model_fold3_768_fp16.onnx: 28.8 MB …`; die Differenz der fp16-Datei höchstens rund `1e-3` (bei p um 0,1 verschiebt ein Logit-Fehler von 0,007 den Wert um 0,0007), die der fp32-Datei unter `1e-5`; `max prob` deutlich über der Schwelle (ein Zufallsbild gibt meist kleine Werte, das ist kein Fehler; die Parität prüft echte Fotos);
- `model folder ../../MyTargets-learn/models-smoke: model.onnx (28.8 MB), model.json {...}`;
- `parity 2026-09-15_bedeckt_stark-schraeg_07: k peaks of 6, values [...]` mit `k` zwischen 3 und 6 und Werten über 0,12; dasselbe für die frontale Ansicht.

Dann der Rauchtest gegen den neuen Ordner:

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedModelSmokeTest*" --rerun`
Expected: PASS.

- [ ] **Step 4: Commit im Korpus-Repo**

```bash
git -C ../MyTargets-corpus add learn/prepare.py learn/export_onnx.py
git -C ../MyTargets-corpus commit -m "learn: export the wrapper with normalisation and sigmoid, the model folder and the parity reference"
```

---

## Task 9: `LearnedSelection`, `LearnedAnalysis` und `LearnedArrowDetector`

Schritt 6 des Designs zuerst als reine Funktion, dann der Detektor darum.

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedSelection.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedAnalysis.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetector.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/StubRegistrar.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedSelectionTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetectorTest.kt`

**Interfaces:**
- Consumes: `Peak`, `Heatmap`, `HeatmapPeaks.find`, `PreShrink`, `FaceWarp.warp`/`targetOf`, `ArrowModel`, `SpotMapping.locate`, `Candidate`, `SelectionReason`, `FaceRegistrar`, `RegistrationOutcome`, `OpenCvArrowDetector.faceConfidence`, `DebugSink.show`
- Produces:
  - `class LearnedFind(val peak: Peak, val candidate: Candidate)`
  - `LearnedSelection.place(kept: List<Peak>, inputSize: Int, layout: FaceLayout, expectedShots: Int, maxPerSpot: Int): LearnedSelection.Placement` mit `onFace: List<LearnedFind>`, `outsideFace: List<Peak>`, `accepted: List<LearnedFind>` (dieselben Instanzen wie in `onFace`), `reason: SelectionReason`
  - `class LearnedTimings(val registrationMs: Long, val warpMs: Long, val networkMs: Long, val peaksMs: Long)`
  - `sealed interface LearnedAnalysis` mit `NotRegistered(registration: RegistrationOutcome.Failed, timings)` und `Analysed(registration: RegistrationOutcome.Registered, peaks: List<Peak>, kept: List<Peak>, onFace, outsideFace, accepted, reason, timings)`
  - `class LearnedArrowDetector(model: ArrowModel, registrar: FaceRegistrar = OpenCvFaceRegistrar(), winograd: Boolean = true) : ArrowDetector` mit `fun analyse(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): LearnedAnalysis`; der Konstruktor wirft `IllegalStateException`, wenn OpenCV das Modell nicht liest
  - Test-Helfer `class StubRegistrar(private val outcome: RegistrationOutcome) : FaceRegistrar`, der jedes Bild mit `outcome` beantwortet (`FaceRegistrar` ist kein `fun interface`, ein Lambda geht nicht)

- [ ] **Step 1: Test der Auswahl**

`LearnedSelectionTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.FaceWarp
import org.junit.Test

class LearnedSelectionTest {

    private val edge = 768

    /** A peak at target coordinates (x, y) with [value]. */
    private fun peakAt(x: Double, y: Double, value: Double): Peak {
        val px = FaceWarp.pixelOf(Vec2(x, y), edge)
        return Peak(px.x, px.y, value)
    }

    private val threeSpot = FaceLayout(
        facePositions = listOf(Vec2(-0.52, 0.5), Vec2(0.0, -0.5), Vec2(0.52, 0.5)),
        faceRadius = 0.48
    )

    @Test
    fun peaksOnTheFaceBecomeCandidatesInSpotLocalCoordinates() {
        val kept = listOf(peakAt(0.3, -0.4, 0.9), peakAt(-0.1, 0.2, 0.5))

        val placement = LearnedSelection.place(kept, edge, FaceLayout.singleSpot(), expectedShots = 2, maxPerSpot = 2)

        assertThat(placement.reason).isEqualTo(SelectionReason.COMPLETE)
        assertThat(placement.outsideFace).isEmpty()
        assertThat(placement.accepted).hasSize(2)
        val first = placement.accepted[0].candidate
        assertThat(first.faceIndex).isEqualTo(0)
        assertThat(first.local.distanceTo(Vec2(0.3, -0.4))).isLessThan(1e-9)
        assertThat(first.confidence).isWithin(1e-12).of(0.9)
        assertThat(placement.accepted[0].peak).isSameInstanceAs(kept[0])
    }

    @Test
    fun aPeakInTheBandBeyondRadiusOneIsOutsideAndTakesItsPlace() {
        // Design step 6: it took one of the expectedShots places in step 5 and counts for nothing now.
        val kept = listOf(peakAt(0.0, 1.05, 0.8), peakAt(0.2, 0.2, 0.4))

        val placement = LearnedSelection.place(kept, edge, FaceLayout.singleSpot(), expectedShots = 2, maxPerSpot = 2)

        assertThat(placement.outsideFace).containsExactly(kept[0])
        assertThat(placement.onFace.map { it.peak }).containsExactly(kept[1])
        assertThat(placement.accepted).hasSize(1)
        assertThat(placement.reason).isEqualTo(SelectionReason.FEWER_THAN_EXPECTED)
    }

    @Test
    fun noPeaksMeansFewerThanExpected() {
        val placement = LearnedSelection.place(emptyList(), edge, FaceLayout.singleSpot(), expectedShots = 6, maxPerSpot = 6)

        assertThat(placement.accepted).isEmpty()
        assertThat(placement.reason).isEqualTo(SelectionReason.FEWER_THAN_EXPECTED)
    }

    @Test
    fun aSpotOverItsCapDropsItsLowestPeaksAndSaysSo() {
        // Three shots on three spots, one per spot; two peaks land on spot 1.
        val kept = listOf(peakAt(0.0, -0.5, 0.9), peakAt(0.1, -0.4, 0.7), peakAt(0.52, 0.5, 0.6))

        val placement = LearnedSelection.place(kept, edge, threeSpot, expectedShots = 3, maxPerSpot = 1)

        assertThat(placement.onFace).hasSize(3)
        assertThat(placement.accepted.map { it.peak.value }).containsExactly(0.9, 0.6).inOrder()
        assertThat(placement.accepted.map { it.candidate.faceIndex }).containsExactly(1, 2).inOrder()
        assertThat(placement.reason).isEqualTo(SelectionReason.SPOT_OVERFLOW)
    }

    @Test
    fun theSpotLocalCoordinatesScaleWithTheSpotRadius() {
        val kept = listOf(peakAt(0.52 + 0.24, 0.5, 0.9))

        val placement = LearnedSelection.place(kept, edge, threeSpot, expectedShots = 1, maxPerSpot = 1)

        val c = placement.accepted.single().candidate
        assertThat(c.faceIndex).isEqualTo(2)
        assertThat(c.local.distanceTo(Vec2(0.5, 0.0))).isLessThan(1e-9)
    }
}
```

- [ ] **Step 2: Fehlschlag sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedSelectionTest*"`
Expected: Kompilierfehler.

- [ ] **Step 3: `LearnedSelection` und `LearnedAnalysis`**

`LearnedSelection.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.SpotMapping
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.FaceWarp

/** A peak the detector placed on a spot: the candidate carries the spot-local position and the heatmap value. */
class LearnedFind(val peak: Peak, val candidate: Candidate)

/**
 * Step 6 of design 3d. The peaks arrive cut to expectedShots by value (decision
 * 4: no gap rule, so AMBIGUOUS_SURPLUS never occurs). A peak outside every spot
 * took a place and counts for nothing; a spot over its cap drops its lowest
 * peaks and the reason says so.
 */
object LearnedSelection {

    class Placement(
        /** Of the kept peaks, those on a spot, in the order they came. */
        val onFace: List<LearnedFind>,
        /** Of the kept peaks, those in the band beyond radius one or beside the face. */
        val outsideFace: List<Peak>,
        /** [onFace] after the per-spot cap: the shots; the same instances. */
        val accepted: List<LearnedFind>,
        val reason: SelectionReason
    )

    fun place(kept: List<Peak>, inputSize: Int, layout: FaceLayout, expectedShots: Int, maxPerSpot: Int): Placement {
        require(maxPerSpot > 0) { "a spot holds at least one arrow" }
        val onFace = ArrayList<LearnedFind>()
        val outside = ArrayList<Peak>()
        for (peak in kept) {
            val target = FaceWarp.targetOf(Vec2(peak.u, peak.v), inputSize)
            val located = SpotMapping.locate(target, layout)
            if (located == null) {
                outside += peak
            } else {
                onFace += LearnedFind(peak, Candidate(located.faceIndex, located.local, peak.value))
            }
        }
        val perSpot = HashMap<Int, Int>()
        val accepted = ArrayList<LearnedFind>()
        var overflow = false
        for (find in onFace) {
            val n = perSpot.getOrDefault(find.candidate.faceIndex, 0)
            if (n >= maxPerSpot) {
                overflow = true
            } else {
                perSpot[find.candidate.faceIndex] = n + 1
                accepted += find
            }
        }
        val reason = when {
            overflow -> SelectionReason.SPOT_OVERFLOW
            accepted.size == expectedShots -> SelectionReason.COMPLETE
            else -> SelectionReason.FEWER_THAN_EXPECTED
        }
        return Placement(onFace, outside, accepted, reason)
    }
}
```

`LearnedAnalysis.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.registration.RegistrationOutcome

/** Wall time per stage in milliseconds; the corpus report shows them as registration / warp / search / walk. */
class LearnedTimings(val registrationMs: Long, val warpMs: Long, val networkMs: Long, val peaksMs: Long)

/** Everything the learned detector produced, for the corpus run and the debug screen; the app gets only the DetectionResult. */
sealed interface LearnedAnalysis {
    val timings: LearnedTimings

    class NotRegistered(
        val registration: RegistrationOutcome.Failed,
        override val timings: LearnedTimings
    ) : LearnedAnalysis

    class Analysed(
        val registration: RegistrationOutcome.Registered,
        /** Every peak at or above the threshold, highest first. */
        val peaks: List<Peak>,
        /** The first expectedShots of [peaks]: what the PoC measured (decision 4). */
        val kept: List<Peak>,
        /** Of [kept], those on a spot. */
        val onFace: List<LearnedFind>,
        /** Of [kept], those outside every spot: they took a place and count for nothing. */
        val outsideFace: List<Peak>,
        /** [onFace] after the per-spot cap: the shots. */
        val accepted: List<LearnedFind>,
        val reason: SelectionReason,
        override val timings: LearnedTimings
    ) : LearnedAnalysis
}
```

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedSelectionTest*"`
Expected: PASS, 5 Tests.

- [ ] **Step 4: Tests des Detektors**

Sie laufen gegen den Probeordner (Task 8) und überspringen sich ohne `DETECTION_MODEL_DIR`. Die Zahlen des Netzes prüft Task 11; hier zählt der Weg: Registrierung, Warp, Netz, Spitzen, Zuordnung, Zeiten, `detect`, die Fehlerfälle.

Zuerst der Stub, den auch der Paritätstest (Task 11) nutzt. `StubRegistrar.kt` (Testquellen):

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.registration.FaceRegistrar
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RegistrationRequest
import org.opencv.core.Mat

/** Answers every photograph with the same outcome: a failure, or the homography a sidecar carries. */
class StubRegistrar(private val outcome: RegistrationOutcome) : FaceRegistrar {
    override fun register(image: Mat, request: RegistrationRequest, debug: DebugSink): RegistrationOutcome = outcome
}
```

`LearnedArrowDetectorTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The learned detector's plumbing on a synthetic photograph: the model of the
 * configured folder runs, the stages report, detect() maps the analysis. What
 * the network makes of a drawn face is not asserted; the parity test checks
 * its numbers on corpus photographs.
 */
class LearnedArrowDetectorTest {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var model: ArrowModel

    @Before
    fun requireModel() {
        val configured = System.getProperty("detection.model.dir")
        assumeTrue("DETECTION_MODEL_DIR is not configured", configured != null)
        val dir = File(configured!!)
        assumeTrue("model directory does not exist: $configured", dir.isDirectory)
        model = ArrowModel.load(dir)
    }

    private val camera = SyntheticCamera(30.0, 650.0, Vec2(1000.0, 750.0))
    private val entries = listOf(Vec2(0.3, 0.35), Vec2(0.1, -0.05), Vec2(-0.3, -0.3))

    private fun request(shots: Int) = DetectionRequest(
        FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, shots, camera.intrinsics
    )

    @Test
    fun analysesASyntheticPhotographThroughEveryStage() {
        val detector = LearnedArrowDetector(model)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val analysis = try {
            detector.analyse(photo, request(entries.size))
        } finally {
            photo.release()
        }

        assertWithMessage("outcome of the registration").that(analysis).isInstanceOf(LearnedAnalysis.Analysed::class.java)
        analysis as LearnedAnalysis.Analysed
        assertThat(analysis.peaks.map { it.value }).isInOrder(Comparator.reverseOrder<Double>())
        assertThat(analysis.peaks.all { it.value >= model.meta.threshold }).isTrue()
        assertThat(analysis.kept.size).isAtMost(entries.size)
        assertThat(analysis.kept).containsExactlyElementsIn(analysis.peaks.take(analysis.kept.size)).inOrder()
        assertThat(analysis.onFace.size + analysis.outsideFace.size).isEqualTo(analysis.kept.size)
        assertThat(analysis.accepted.all { it.candidate.local.length <= 1.0 }).isTrue()
        for (peak in analysis.peaks) {
            assertThat(peak.u).isAtLeast(0.0)
            assertThat(peak.u).isAtMost(model.meta.inputSize.toDouble())
        }
        with(analysis.timings) {
            assertThat(registrationMs).isAtLeast(0L)
            assertThat(warpMs).isAtLeast(0L)
            assertThat(networkMs).isGreaterThan(0L)
            assertThat(peaksMs).isAtLeast(0L)
        }
    }

    @Test
    fun detectMapsTheAcceptedFindsAndTheFaceConfidence() {
        val detector = LearnedArrowDetector(model)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val (analysis, result) = try {
            detector.analyse(photo, request(entries.size)) to detector.detect(photo, request(entries.size))
        } finally {
            photo.release()
        }
        analysis as LearnedAnalysis.Analysed

        assertThat(result.failure).isNull()
        assertThat(result.reason).isEqualTo(analysis.reason)
        assertThat(result.shots).hasSize(analysis.accepted.size)
        for ((shot, find) in result.shots.zip(analysis.accepted)) {
            assertThat(shot.faceIndex).isEqualTo(find.candidate.faceIndex)
            assertThat(shot.x).isWithin(1e-6f).of(find.candidate.local.x.toFloat())
            assertThat(shot.y).isWithin(1e-6f).of(find.candidate.local.y.toFloat())
            assertThat(shot.confidence).isWithin(1e-6f).of(find.candidate.confidence.toFloat())
        }
        assertThat(result.faceConfidence)
            .isWithin(1e-6f).of(OpenCvArrowDetector.faceConfidence(analysis.registration).toFloat())
    }

    @Test
    fun aFailedRegistrationFailsTheDetectionLikeTheClassicalFinder() {
        val failing = StubRegistrar(RegistrationOutcome.Failed(DetectionFailure.FACE_NOT_FOUND, "test"))
        val detector = LearnedArrowDetector(model, registrar = failing)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, emptyList())
        val (analysis, result) = try {
            detector.analyse(photo, request(3)) to detector.detect(photo, request(3))
        } finally {
            photo.release()
        }

        assertThat(analysis).isInstanceOf(LearnedAnalysis.NotRegistered::class.java)
        assertThat(result.failure).isEqualTo(DetectionFailure.FACE_NOT_FOUND)
        assertThat(result.shots).isEmpty()
    }

    @Test
    fun aSidecarWithTheWrongInputSizeIsRejectedByName() {
        // The export is fixed to one size (design 3d, Ablageformat): a 768 model with 512 in the sidecar fails at the forward pass.
        val wrong = ArrowModel(
            model.onnx,
            ArrowModelMeta(512, model.meta.stride, model.meta.kernel, model.meta.preShrinkMaxSide, model.meta.threshold, null, null, null, emptyMap())
        )
        val detector = LearnedArrowDetector(wrong)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        try {
            detector.analyse(photo, request(3))
            throw AssertionError("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().contains("inputSize 512")
        } finally {
            photo.release()
        }
    }

    @Test
    fun anUnreadableModelFailsAtConstruction() {
        val broken = ArrowModel(File("does-not-exist.onnx"), model.meta)
        try {
            LearnedArrowDetector(broken)
            throw AssertionError("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().contains("does-not-exist.onnx")
        }
    }
}
```

- [ ] **Step 5: Fehlschlag sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedArrowDetectorTest*"`
Expected: Kompilierfehler, `LearnedArrowDetector` fehlt.

- [ ] **Step 6: Den Detektor schreiben**

`LearnedArrowDetector.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.ArrowDetector
import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectedShot
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.DetectionResult
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.FaceRegistrar
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvFaceRegistrar
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RegistrationRequest
import de.dreier.mytargets.detection.show
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net

/**
 * The learned arrow finder (design 3d): register like the classical finder,
 * shrink and rectify onto the model's input square, run the heatmap network
 * through OpenCV DNN, take the local maxima, place them on spots. No shaft
 * search, no CandidateSelection: the corpus number of the app stays the one
 * of the PoC.
 *
 * The network is read once and lives as long as the instance; its native
 * memory is freed by Net's finalizer, so hold one instance per process, not
 * per photograph. Not thread-safe: one detection at a time.
 */
class LearnedArrowDetector(
    private val model: ArrowModel,
    private val registrar: FaceRegistrar = OpenCvFaceRegistrar(),
    winograd: Boolean = true
) : ArrowDetector {

    private val net: Net = try {
        Dnn.readNetFromONNX(model.onnx.absolutePath)
    } catch (e: CvException) {
        throw IllegalStateException("cannot read ${model.onnx.path}: ${e.message}", e)
    }

    init {
        check(!net.empty()) { "${model.onnx.path}: OpenCV read an empty network" }
        // Winograd trades roughly 300 MB for a 2.3 times faster pass (app-path findings of 2026-09-17).
        net.enableWinograd(winograd)
    }

    fun analyse(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): LearnedAnalysis {
        val started = System.nanoTime()
        val outcome = registrar.register(image, RegistrationRequest(request.layout, request.transitions), debug)
        val registered = System.nanoTime()
        val registration = when (outcome) {
            is RegistrationOutcome.Failed ->
                return LearnedAnalysis.NotRegistered(outcome, LearnedTimings(millis(started, registered), 0, 0, 0))
            is RegistrationOutcome.Registered -> outcome
        }
        val meta = model.meta
        val warped = rectify(image, registration.imageToTarget)
        try {
            val rectified = System.nanoTime()
            val heat = forward(warped)
            val forwarded = System.nanoTime()
            val peaks = HeatmapPeaks.find(heat, meta.stride, meta.kernel, meta.threshold)
            val kept = peaks.take(request.expectedShots)
            val placement = LearnedSelection.place(
                kept, meta.inputSize, request.layout, request.expectedShots, request.maxArrowsPerSpot
            )
            val placed = System.nanoTime()
            debug.show(LearnedDebugImages.HEATMAP) { LearnedDebugImages.heatmap(warped, heat, meta.stride) }
            debug.show(LearnedDebugImages.PEAKS) {
                LearnedDebugImages.peaks(warped, peaks, placement.accepted.map { it.peak }, placement.outsideFace)
            }
            return LearnedAnalysis.Analysed(
                registration, peaks, kept, placement.onFace, placement.outsideFace, placement.accepted, placement.reason,
                LearnedTimings(
                    millis(started, registered), millis(registered, rectified),
                    millis(rectified, forwarded), millis(forwarded, placed)
                )
            )
        } finally {
            warped.release()
        }
    }

    override fun detect(image: Mat, request: DetectionRequest, debug: DebugSink): DetectionResult =
        when (val analysis = analyse(image, request, debug)) {
            is LearnedAnalysis.NotRegistered -> DetectionResult.failed(analysis.registration.failure)
            is LearnedAnalysis.Analysed -> DetectionResult(
                shots = analysis.accepted.map {
                    val c = it.candidate
                    DetectedShot(c.faceIndex, c.local.x.toFloat(), c.local.y.toFloat(), c.confidence.toFloat())
                },
                faceConfidence = OpenCvArrowDetector.faceConfidence(analysis.registration).toFloat(),
                reason = analysis.reason,
                failure = null
            )
        }

    /** Step 2 of the design: shrink like prepare.py, then warp onto the model's input square. */
    private fun rectify(image: Mat, imageToTarget: Mat3): Mat {
        val size = model.meta.inputSize
        val shrink = PreShrink.of(image.cols(), image.rows(), model.meta.preShrinkMaxSide)
        if (shrink.isIdentity) return FaceWarp.warp(image, imageToTarget, size)
        val small = shrink.shrink(image)
        try {
            return FaceWarp.warp(small, shrink.imageToTarget(imageToTarget), size)
        } finally {
            small.release()
        }
    }

    /** Steps 3 and 4: RGB in [0, 1] into the graph, channel 0 of the probabilities out. */
    private fun forward(warped: Mat): Heatmap {
        val size = model.meta.inputSize
        val blob = Dnn.blobFromImage(
            warped, 1.0 / 255.0, Size(size.toDouble(), size.toDouble()), Scalar(0.0, 0.0, 0.0), true, false
        )
        try {
            net.setInput(blob)
            val out = try {
                net.forward()
            } catch (e: CvException) {
                throw IllegalStateException(
                    "${model.onnx.path}: the forward pass failed at inputSize $size; the export is fixed to one " +
                        "input size, check model.json against the export: ${e.message}", e
                )
            }
            try {
                val expected = model.meta.outputSize
                check(
                    out.dims() == 4 && out.size(0) == 1 && out.size(1) >= 1 &&
                        out.size(2) == expected && out.size(3) == expected && out.type() == CvType.CV_32F
                ) {
                    "${model.onnx.path}: expected a float output of 1 x 2 x $expected x $expected for inputSize $size " +
                        "and stride ${model.meta.stride}, got ${shapeOf(out)}"
                }
                val height = out.size(2)
                val width = out.size(3)
                // The output is continuous; as one 2D matrix its first height rows are channel 0.
                val values = FloatArray(width * height)
                val flat = out.reshape(1, out.size(1) * height)
                try {
                    flat.get(0, 0, values)
                } finally {
                    flat.release()
                }
                return Heatmap(width, height, values)
            } finally {
                out.release()
            }
        } finally {
            blob.release()
        }
    }

    private fun shapeOf(m: Mat) = (0 until m.dims()).joinToString(" x ") { m.size(it).toString() } + " of type ${m.type()}"

    private companion object {
        fun millis(from: Long, to: Long) = (to - from) / 1_000_000
    }
}
```

`LearnedDebugImages` kommt in Task 10; damit dieser Task allein kompiliert, hier zunächst die Datei `LearnedDebugImages.kt` mit den beiden Konstanten und zwei Funktionen anlegen, die `warped.clone()` zurückgeben:

```kotlin
package de.dreier.mytargets.detection.arrows

import org.opencv.core.Mat

object LearnedDebugImages {
    const val HEATMAP = "5-heatmap"
    const val PEAKS = "6-spitzen"

    fun heatmap(warped: Mat, heat: Heatmap, stride: Int): Mat = warped.clone()

    fun peaks(warped: Mat, peaks: List<Peak>, accepted: List<Peak>, outsideFace: List<Peak>): Mat = warped.clone()
}
```

- [ ] **Step 7: Grün sehen, samt Importregel**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedArrowDetectorTest*" --tests "*LearnedSelectionTest*" --tests "*OpenCvImportRuleTest*"`
Expected: PASS; die Importregel akzeptiert `org.opencv.dnn` in `arrows/LearnedArrowDetector.kt`.

Schlägt `aSidecarWithTheWrongInputSizeIsRejectedByName` fehl, weil OpenCV bei 512 px keine Ausnahme wirft, sondern eine andere Ausgabegröße liefert, greift die `check` danach mit „expected a float output of 1 x 2 x 256 x 256 for inputSize 512“; die Meldung enthält `inputSize 512` in beiden Wegen.

- [ ] **Step 8: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedSelection.kt detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedAnalysis.kt detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetector.kt detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedDebugImages.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/StubRegistrar.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedSelectionTest.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetectorTest.kt
git commit -m "detection: LearnedArrowDetector runs the heatmap model behind ArrowDetector"
```

---

## Task 10: Die Debug-Bilder 5 und 6

**Files:**
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedDebugImages.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedDebugImagesTest.kt`

**Interfaces:**
- Produces: `LearnedDebugImages.heatmap(warped: Mat, heat: Heatmap, stride: Int): Mat` (rotes Glühen), `LearnedDebugImages.peaks(warped: Mat, peaks: List<Peak>, accepted: List<Peak>, outsideFace: List<Peak>): Mat` (grün angenommen, gelb weggefallen, grau außerhalb, mit Rang und Wert beschriftet); beide liefern eine neue `Mat` der Größe von `warped`

- [ ] **Step 1: Die Tests schreiben**

`LearnedDebugImagesTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

class LearnedDebugImagesTest {

    @get:Rule
    val openCv = OpenCvRule()

    private fun grey(edge: Int) = Mat(edge, edge, CvType.CV_8UC3, Scalar(100.0, 100.0, 100.0))

    private fun pixel(mat: Mat, x: Int, y: Int): List<Int> {
        val bgr = ByteArray(3)
        mat.get(y, x, bgr)
        return bgr.map { it.toInt() and 0xFF }
    }

    @Test
    fun theHeatmapGlowsRedWhereItIsHotAndLeavesTheRestAlone() {
        val warped = grey(16)
        val values = FloatArray(8 * 8)
        values[3 * 8 + 2] = 1.0f   // heat pixel (2, 3) covers warped pixels x 4..5, y 6..7
        val image = LearnedDebugImages.heatmap(warped, Heatmap(8, 8, values), stride = 2)
        try {
            assertThat(image.cols()).isEqualTo(16)
            assertThat(image.rows()).isEqualTo(16)
            assertThat(image.nativeObj).isNotEqualTo(warped.nativeObj)
            assertThat(pixel(image, 5, 7)).containsExactly(0, 0, 255).inOrder()
            assertThat(pixel(image, 0, 0)).containsExactly(100, 100, 100).inOrder()
        } finally {
            image.release()
            warped.release()
        }
    }

    @Test
    fun halfHeatBlendsHalfway() {
        val warped = grey(4)
        val values = floatArrayOf(0.5f, 0f, 0f, 0f)
        val image = LearnedDebugImages.heatmap(warped, Heatmap(2, 2, values), stride = 2)
        try {
            // 100 * 0.5 for blue and green, 100 * 0.5 + 255 * 0.5 = 177.5 for red, rounded half up.
            assertThat(pixel(image, 0, 0)).containsExactly(50, 50, 178).inOrder()
        } finally {
            image.release()
            warped.release()
        }
    }

    @Test
    fun peaksAreDrawnInTheirColours() {
        val warped = grey(200)
        val accepted = Peak(50.0, 50.0, 0.9)
        val dropped = Peak(150.0, 50.0, 0.3)
        val outside = Peak(50.0, 150.0, 0.5)
        val image = LearnedDebugImages.peaks(warped, listOf(accepted, outside, dropped), listOf(accepted), listOf(outside))
        try {
            assertThat(image.cols()).isEqualTo(200)
            // The ring of radius 10 passes through (x + 10, y); its colour names the class.
            assertThat(pixel(image, 60, 50)).containsExactly(0, 200, 0).inOrder()      // green
            assertThat(pixel(image, 160, 50)).containsExactly(0, 255, 255).inOrder()   // yellow
            assertThat(pixel(image, 60, 150)).containsExactly(128, 128, 128).inOrder() // grey
        } finally {
            image.release()
            warped.release()
        }
    }
}
```

- [ ] **Step 2: Fehlschlag sehen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedDebugImagesTest*"`
Expected: FAIL, die Platzhalter aus Task 9 geben Kopien ohne Zeichnung zurück.

- [ ] **Step 3: Implementieren**

`LearnedDebugImages.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The learned finder's stage images (design 3d, Debug-Bilder), drawn on the
 * rectified input of the network. Each call returns a new Mat; the detector
 * releases it after the sink.
 */
object LearnedDebugImages {

    const val HEATMAP = "5-heatmap"
    const val PEAKS = "6-spitzen"

    private val WHITE = Scalar(255.0, 255.0, 255.0)
    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val YELLOW = Scalar(0.0, 255.0, 255.0)
    private val GREY = Scalar(128.0, 128.0, 128.0)

    /** The tip heatmap as a red glow: each pixel blends towards pure red by its probability. */
    fun heatmap(warped: Mat, heat: Heatmap, stride: Int): Mat =
        warped.clone().releaseIfThrows { mat ->
            val width = mat.cols()
            val height = mat.rows()
            val bytes = ByteArray(width * height * 3)
            mat.get(0, 0, bytes)
            for (y in 0 until height) {
                val hy = min(y / stride, heat.height - 1)
                for (x in 0 until width) {
                    val p = heat[min(x / stride, heat.width - 1), hy].coerceIn(0f, 1f)
                    if (p <= 0f) continue
                    val i = 3 * (y * width + x)
                    val keep = 1f - p
                    bytes[i] = ((bytes[i].toInt() and 0xFF) * keep).roundToInt().toByte()
                    bytes[i + 1] = ((bytes[i + 1].toInt() and 0xFF) * keep).roundToInt().toByte()
                    bytes[i + 2] = ((bytes[i + 2].toInt() and 0xFF) * keep + 255f * p).roundToInt().toByte()
                }
            }
            mat.put(0, 0, bytes)
        }

    /**
     * Every peak at or above the threshold, numbered by rank with its value:
     * green when accepted, grey when outside every spot, yellow when it fell
     * under the count or the spot cap.
     */
    fun peaks(warped: Mat, peaks: List<Peak>, accepted: List<Peak>, outsideFace: List<Peak>): Mat =
        warped.clone().releaseIfThrows { mat ->
            peaks.forEachIndexed { rank, p ->
                val colour = when {
                    accepted.any { it === p } -> GREEN
                    outsideFace.any { it === p } -> GREY
                    else -> YELLOW
                }
                val at = Point(p.u, p.v)
                Imgproc.circle(mat, at, 10, colour, 2)
                Imgproc.putText(
                    mat, "${rank + 1} ${String.format(Locale.ROOT, "%.2f", p.value)}", Point(p.u + 12, p.v - 12),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, WHITE, 2
                )
            }
        }
}
```

Die Identitätsprüfung `===` verlangt, dass `accepted` und `outsideFace` dieselben `Peak`-Instanzen tragen wie `peaks`; `LearnedSelection.place` reicht sie unverändert durch, und `take()` kopiert keine Elemente.

- [ ] **Step 4: Grün sehen, samt Bildfolge im Detektor**

In `LearnedArrowDetectorTest` einen Test ergänzen, nach dem Muster von `ArrowDebugImagesTest`:

```kotlin
    @Test
    fun theDetectorShowsTheHeatmapAndThePeaksOnTheRectifiedInput() {
        val shown = ArrayList<Triple<String, Int, Int>>()
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        try {
            LearnedArrowDetector(model).analyse(photo, request(entries.size), DebugSink { stage, image ->
                shown += Triple(stage, image.cols(), image.rows())
            })
        } finally {
            photo.release()
        }

        val size = model.meta.inputSize
        assertThat(shown.map { it.first }).containsExactly(
            DebugImages.CLASSES, DebugImages.DISCS, DebugImages.RINGS,
            LearnedDebugImages.HEATMAP, LearnedDebugImages.PEAKS
        ).inOrder()
        assertThat(shown.drop(3).map { it.second to it.third }).containsExactly(size to size, size to size)
    }
```

(Importe `de.dreier.mytargets.detection.DebugSink` und `de.dreier.mytargets.detection.registration.DebugImages`.)

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedDebugImagesTest*" --tests "*LearnedArrowDetectorTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedDebugImages.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedDebugImagesTest.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetectorTest.kt
git commit -m "detection: the learned finder's stage images, heatmap and peaks"
```

---

## Task 11: Der Paritätstest gegen den PoC

Mit der Homographie des PoC (Sidecar, über einen Stub-Registrar) muss der Kotlin-Weg dieselben Spitzen auf 0,5 Eingabepixel und denselben Wert auf 0,01 liefern wie `export_onnx.py --peaks` (Design, *Messung und Tests*). Läuft zunächst gegen den Probeordner, in Task 12 gegen das ausgelieferte Modell.

**Files:**
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedParityTest.kt`

**Interfaces:**
- Consumes: `parity/<name>.json` (Task 8), `CorpusLoader`, `CorpusPhotos.filesByName`/`imageFileOf`, `PreShrink`, `FaceWarp.warp`, `LearnedArrowDetector`, `LearnedAnalysis.Analysed.kept`
- Produces: nichts für spätere Tasks; der Test ist der Zweck

- [ ] **Step 1: Den Test schreiben**

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Kotlin path against the PoC (design 3d, Paritaetstest): with the
 * homography of the sidecar fed through a stub registrar, pre-shrink, warp,
 * network and maxima must give the peaks export_onnx.py --peaks wrote for the
 * same model, to 0.5 input pixels and 0.01 in value. The registration itself
 * is measured by the corpus run, not here.
 */
class LearnedParityTest {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var modelDir: File
    private lateinit var references: List<File>

    /** parity/<view>.json as export_onnx.py writes it. */
    private class Reference {
        var view: String? = null
        var inputSize: Int? = null
        var threshold: Double? = null
        var maxCount: Int? = null
        var imageToTarget: List<List<Double>>? = null
        var imageMeanBgr: List<Double>? = null
        var peaks: List<RefPeak>? = null
    }

    private class RefPeak {
        var u = 0.0
        var v = 0.0
        var value = 0.0
    }

    @Before
    fun requireCorpusAndModel() {
        val corpus = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", corpus != null)
        root = File(corpus!!)
        assumeTrue("corpus directory does not exist: $corpus", root.isDirectory)
        val configured = System.getProperty("detection.model.dir")
        assumeTrue("DETECTION_MODEL_DIR is not configured", configured != null)
        modelDir = File(configured!!)
        assumeTrue("model directory does not exist: $configured", modelDir.isDirectory)
        references = File(modelDir, "parity").listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no parity/*.json in $configured", references.isNotEmpty())
    }

    private fun read(file: File): Reference = Gson().fromJson(file.readText(), Reference::class.java)

    private fun homography(ref: Reference): Mat3 {
        val rows = checkNotNull(ref.imageToTarget) { "${ref.view}: no imageToTarget" }
        return Mat3.of(*rows.flatten().toDoubleArray())
    }

    private fun photograph(view: String): File {
        val entries = CorpusLoader.load(root).entries
        val files = CorpusPhotos.filesByName(root, entries)
        return CorpusPhotos.imageFileOf(root, "$view.jpg", files["$view.jpg"].orEmpty())
    }

    @Test
    fun theRectifiedInputMatchesPreparePy() {
        val model = ArrowModel.load(modelDir)
        for (file in references) {
            val ref = read(file)
            val image = Imgcodecs.imread(photograph(ref.view!!).absolutePath)
            try {
                check(!image.empty()) { "${ref.view}: cannot be decoded" }
                val shrink = PreShrink.of(image.cols(), image.rows(), model.meta.preShrinkMaxSide)
                val small = shrink.shrink(image)
                val warped = try {
                    FaceWarp.warp(small, shrink.imageToTarget(homography(ref)), model.meta.inputSize)
                } finally {
                    small.release()
                }
                try {
                    val mean = Core.mean(warped).`val`
                    val expected = ref.imageMeanBgr!!
                    for (c in 0 until 3) {
                        assertWithMessage("${ref.view}: mean of channel $c (BGR) of the rectified input")
                            .that(mean[c]).isWithin(0.5).of(expected[c])
                    }
                } finally {
                    warped.release()
                }
            } finally {
                image.release()
            }
        }
    }

    @Test
    fun theKotlinPathReproducesThePeaksOfThePoC() {
        val model = ArrowModel.load(modelDir)
        for (file in references) {
            val ref = read(file)
            assertWithMessage("${file.name}: inputSize of the reference").that(ref.inputSize).isEqualTo(model.meta.inputSize)
            assertWithMessage("${file.name}: threshold of the reference").that(ref.threshold).isWithin(1e-9).of(model.meta.threshold)
            val h = homography(ref)
            val stub = StubRegistrar(RegistrationOutcome.Registered(h, Vec2(0.0, 0.0), emptyList()))
            val detector = LearnedArrowDetector(model, registrar = stub)
            val image = Imgcodecs.imread(photograph(ref.view!!).absolutePath)
            val analysis = try {
                val request = DetectionRequest(
                    FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, ref.maxCount!!,
                    CameraIntrinsics.approximate(image.cols(), image.rows())
                )
                detector.analyse(image, request) as LearnedAnalysis.Analysed
            } finally {
                image.release()
            }

            val expected = ref.peaks!!
            assertWithMessage("${ref.view}: number of peaks after threshold and count").that(analysis.kept).hasSize(expected.size)
            val unmatched = analysis.kept.toMutableList()
            for (e in expected) {
                val nearest = unmatched.minByOrNull { hypot(it.u - e.u, it.v - e.v) }
                assertWithMessage("${ref.view}: a peak near (${e.u}, ${e.v}) with value ${e.value}").that(nearest).isNotNull()
                val d = hypot(nearest!!.u - e.u, nearest.v - e.v)
                assertWithMessage("${ref.view}: distance of the peak at (${e.u}, ${e.v})").that(d).isAtMost(0.5)
                assertWithMessage("${ref.view}: value of the peak at (${e.u}, ${e.v})").that(abs(nearest.value - e.value)).isAtMost(0.01)
                unmatched.remove(nearest)
            }
        }
    }
}
```

- [ ] **Step 2: Laufen lassen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedParityTest*" --rerun`
Expected: PASS, 2 Tests ausgeführt (Probeordner mit zwei Referenzen).

Schlägt `theRectifiedInputMatchesPreparePy` fehl, liegt der Unterschied vor dem Netz: zuerst `PreShrink` gegen `round(w0 * pre)` in `prepare.rectify` und die Skalierung der Homographie vergleichen, dann `FaceWarp.warp` (bilinear, Rahmen `k·(t + 1,1) − 0,5`) gegen `face_to_px`. Schlägt nur `theKotlinPathReproducesThePeaksOfThePoC` fehl und nur im Wert um mehr als 0,01, den Vergleich der fp16-Datei in der Ausgabe von `export_onnx.py` lesen (`max |prob diff| vs torch`); liegt er unter 0,001, ist der Unterschied im Blob (Kanalreihenfolge `swapRB`, Skalierung) zu suchen, nicht im Netz.

- [ ] **Step 3: Commit**

```bash
git add detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedParityTest.kt
git commit -m "detection: parity test of the learned finder against export_onnx.py --peaks"
```

---

## Task 12: Das ausgelieferte Modell im Korpus-Repo

Voraussetzung: Das Training aus Task 7 ist fertig (`tail -1 ../MyTargets-learn/runs_all.log` endet mit `-> …model_all.pt`).

**Files:**
- Create (Korpus-Repo): `models/arrows/r4-all-2026-09/model.onnx`, `model.json`, `parity/2026-09-15_bedeckt_stark-schraeg_07.json`, `parity/2026-08-15_bedeckt_frontal_02.json`
- Modify: eigene `gradle-local.properties` (nicht im Repo)

**Interfaces:**
- Produces: der Modellordner, den `DETECTION_MODEL_DIR` in `gradle-local.properties.example` nennt

- [ ] **Step 1: Export**

Die Schwelle ist der Median der fünf FP-begrenzten Fold-Schwellen von r4 (0,12, 0,12, 0,10, 0,12, 0,12), die Kreuzvalidierungszahlen stehen in `learn/README.md`, Abschnitt r4.

```bash
cd ../MyTargets-corpus/learn && .venv/Scripts/python export_onnx.py ../../MyTargets-learn/runs/all-2026-09-17 --all --res 768 --fp16 \
  --out ../models/arrows/r4-all-2026-09 --threshold 0.12 \
  --threshold-from "median of r4 FP-limited fold thresholds (0.12, 0.12, 0.10, 0.12, 0.12)" \
  --cv-oblique "r4 cross-validation: 71.3 % of oblique arrows at 1.06 FP/view (F1 thresholds)" \
  --cv-fp-limited "r4 cross-validation: 63.3 % at 0.64 FP/view (FP-limited thresholds), ring 91.7 %, median error 0.0052" \
  --peaks 2026-09-15_bedeckt_stark-schraeg_07 2026-08-15_bedeckt_frontal_02
```

Expected: wie in Task 8, Step 3; `model.json` nennt `"training": "../../MyTargets-learn/runs/all-2026-09-17"` und den Korpus-Commit. Die fp32-Datei `model_all_768.onnx` bleibt im Run-Ordner (außerhalb des Korpus).

- [ ] **Step 2: Prüfen, was in den Korpus geht**

```bash
ls -l ../MyTargets-corpus/models/arrows/r4-all-2026-09 ../MyTargets-corpus/models/arrows/r4-all-2026-09/parity
git -C ../MyTargets-corpus status --short
```

Expected: `model.onnx` (rund 28,8 MB), `model.json`, zwei Paritätsdateien; `git status` zeigt nur `models/`.

- [ ] **Step 3: `DETECTION_MODEL_DIR` umstellen und die Tests wiederholen**

In der eigenen `gradle-local.properties`:

```properties
DETECTION_MODEL_DIR=../MyTargets-corpus/models/arrows/r4-all-2026-09
```

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedModelSmokeTest*" --tests "*LearnedArrowDetectorTest*" --tests "*LearnedParityTest*" --rerun`
Expected: PASS, alle ausgeführt. Dazu `JAVA_HOME=<jdk17> ./gradlew :detection-corpus:test --rerun`: `RealCorpusTest` weiter grün (`models/` wird übersprungen, Task 6).

- [ ] **Step 4: Commit und Push im Korpus-Repo**

```bash
git -C ../MyTargets-corpus add models
git -C ../MyTargets-corpus commit -m "models: r4-all-2026-09, the learned finder trained on all 100 views, fp16 ONNX with parity references"
git -C ../MyTargets-corpus push
```

Der Probeordner `../MyTargets-learn/models-smoke` kann bleiben; er liegt außerhalb beider Repos.

---

## Task 13: Gemeinsamer Code der Korpusläufe und der Vorspann des Berichts

Aus `ArrowCorpusRun` wandert nach `ArrowCorpusRuns` (Testquellen), was beide Läufe brauchen: Verzeichnisse, die Schleife über die Fotos, die Anfrage, der Datensatz eines Funds, Bild 7. Der klassische Lauf ändert sein Verhalten nicht; seine Pins bleiben grün.

**Files:**
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRuns.kt`
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRun.kt`
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ArrowReport.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ArrowReportTest.kt`

**Interfaces:**
- Produces:
  - `ArrowCorpusRuns.Dirs(val root: File, val reportDir: File)`; `ArrowCorpusRuns.dirs(): Dirs` (überspringt per `assumeTrue` ohne Korpus, verlangt `detection.report.dir`)
  - `ArrowCorpusRuns.Photograph(val entry: CorpusEntry, val image: Mat, val folder: File)`
  - `ArrowCorpusRuns.forEachInScope(dirs: Dirs, imagesSubdir: String, action: (Photograph) -> Unit): List<Pair<String, String>>` — läuft über jedes annotierte Foto im Umfang, legt `<reportDir>/<imagesSubdir>/<name>/` an, gibt das Bild nach `action` frei, liefert die Liste außerhalb des Umfangs
  - `ArrowCorpusRuns.requestFor(entry: CorpusEntry, width: Int, height: Int): DetectionRequest`
  - `ArrowCorpusRuns.record(c: Candidate): DetectedShotRecord`
  - `ArrowCorpusRuns.writeTruth(image: Mat, imageToTarget: Mat3, outcome: EntryOutcome, file: File)`
  - `ArrowReport.render(title, rows, truths, photos, outcomes, outOfScope, preface: String? = null)`: der Vorspann steht als eigener Absatz direkt unter der Überschrift

- [ ] **Step 1: Test des Vorspanns**

In `ArrowReportTest` ergänzen:

```kotlin
    @Test
    fun aPrefaceStandsRightUnderTheTitle() {
        val report = ArrowReport.render("Learned", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            preface = "Trained on these photographs: an upper bound.")

        assertThat(report).startsWith("# Learned\n\nTrained on these photographs: an upper bound.\n\n0 photographs in scope")
    }

    @Test
    fun withoutAPrefaceTheReportIsUnchanged() {
        val report = ArrowReport.render("Arrows", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        assertThat(report).startsWith("# Arrows\n\n0 photographs in scope")
    }
```

Run: `JAVA_HOME=<jdk17> ./gradlew :detection-corpus:test --tests "*ArrowReportTest*"`
Expected: Kompilierfehler (`preface`).

Mag `MetricsReport.render` keine leeren Listen, in beiden Tests eine Zeile und ein Ergebnis mit den Helfern `row`, `entry` und `outcome` dieser Testklasse bauen; geprüft wird nur der Anfang des Texts.

- [ ] **Step 2: Den Vorspann einbauen**

In `ArrowReport.render` den Parameter `preface: String? = null` anhängen und nach `sb.appendLine()` unter der Überschrift einfügen:

```kotlin
        if (preface != null) {
            sb.appendLine(preface)
            sb.appendLine()
        }
```

Run: `JAVA_HOME=<jdk17> ./gradlew :detection-corpus:test --tests "*ArrowReportTest*"`
Expected: PASS.

- [ ] **Step 3: `ArrowCorpusRuns` anlegen**

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.DetectedShotRecord
import de.dreier.mytargets.detection.metrics.EntryOutcome
import de.dreier.mytargets.detection.metrics.ShotMatching
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.roundToInt

/**
 * What the classical and the learned arrow run share (design 3d, Messung und
 * Tests): where the corpus and the report are, the loop over the photographs
 * in scope, the request of a photograph, the record of a find, and stage
 * image 7. Nothing here knows either detector.
 */
object ArrowCorpusRuns {

    class Dirs(val root: File, val reportDir: File)

    class Photograph(val entry: CorpusEntry, val image: Mat, val folder: File)

    /** Skips the calling test without DETECTION_CORPUS_DIR; the report dir comes from testDevDebugUnitTest. */
    fun dirs(): Dirs {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        val root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)
        val reportDir = File(
            checkNotNull(System.getProperty("detection.report.dir")) {
                "detection.report.dir is not set; run this through testDevDebugUnitTest"
            }
        )
        return Dirs(root, reportDir)
    }

    /**
     * Decodes every annotated photograph in scope, hands it to [action] with a
     * fresh folder <reportDir>/[imagesSubdir]/<name>/ for its stage images, and
     * releases it. Returns the photographs out of scope with their reasons.
     */
    fun forEachInScope(dirs: Dirs, imagesSubdir: String, action: (Photograph) -> Unit): List<Pair<String, String>> {
        val entries = CorpusLoader.load(dirs.root).entries
        val files = CorpusPhotos.filesByName(dirs.root, entries)
        val imagesDir = File(dirs.reportDir, imagesSubdir)
        imagesDir.deleteRecursively()
        imagesDir.mkdirs()
        val outOfScope = ArrayList<Pair<String, String>>()
        for (entry in entries) {
            val reason = entry.outOfScope
            if (reason != null) {
                outOfScope += entry.imageName to reason
                continue
            }
            if (!entry.isAnnotated) continue
            val file = CorpusPhotos.imageFileOf(dirs.root, entry.imageName, files[entry.imageName].orEmpty())
            val image = Imgcodecs.imread(file.absolutePath)
            try {
                check(!image.empty()) { "${entry.imageName}: cannot be decoded" }
                CorpusPhotos.checkDecodedSize(entry, image)
                val folder = File(imagesDir, file.nameWithoutExtension).apply { mkdirs() }
                action(Photograph(entry, image, folder))
            } finally {
                image.release()
            }
        }
        return outOfScope
    }

    /** Arrow design, Korpuslauf: the size of the end, not the number of listed hits; the fallback focal length without EXIF. */
    fun requestFor(entry: CorpusEntry, width: Int, height: Int): DetectionRequest {
        val focal = entry.camera?.focalLength35mm
        val intrinsics = if (focal != null) {
            CameraIntrinsics.from35mmEquivalent(width, height, focal)
        } else {
            CameraIntrinsics.approximate(width, height)
        }
        return DetectionRequest(
            FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL,
            checkNotNull(entry.shotsPerEnd) { "${entry.imageName}: the sidecar names no shotsPerEnd" },
            intrinsics
        )
    }

    /** A find as the metrics take it, its ring value from the pure radius like the sidecars'. */
    fun record(c: Candidate) = DetectedShotRecord(
        scoringRing = WaFullZones.zoneOf(c.local.length),
        printedScore = null,
        position = SpotPosition(c.faceIndex, c.local.x, c.local.y),
        confidence = c.confidence
    )

    /**
     * Stage image 7: the rectified face with every listed hit and its budget as
     * a cyan circle, every accepted find as a green dot, and matched pairs joined
     * in white. Only the run knows the truth.
     */
    fun writeTruth(image: Mat, imageToTarget: Mat3, outcome: EntryOutcome, file: File) {
        val edge = FaceWarp.edgeFor(imageToTarget)
        val warped = FaceWarp.warp(image, imageToTarget, edge)
        try {
            val k = edge / (2.0 * FaceWarp.EXTENT)
            for (shot in outcome.entry.shots) {
                val position = shot.position ?: continue
                val budget = ShotMatching.DEFAULT_POSITION_TOLERANCE + (shot.positionTolerance ?: 0.0)
                Imgproc.circle(warped, point(position, edge), (budget * k).roundToInt(), CYAN, 2)
            }
            for (found in outcome.detected) {
                Imgproc.circle(warped, point(found.position, edge), 6, GREEN, Imgproc.FILLED)
            }
            for (pair in outcome.match.pairs) {
                val truth = outcome.entry.shots[pair.truthIndex].position ?: continue
                Imgproc.line(
                    warped, point(truth, edge), point(outcome.detected[pair.detectedIndex].position, edge), WHITE, 2
                )
            }
            check(Imgcodecs.imwrite(file.absolutePath, warped)) { "cannot write $file" }
        } finally {
            warped.release()
        }
    }

    /** On WAFull, spot-local and target coordinates coincide. */
    private fun point(p: SpotPosition, edge: Int): Point {
        val px = FaceWarp.pixelOf(Vec2(p.x, p.y), edge)
        return Point(px.x, px.y)
    }

    private val CYAN = Scalar(255.0, 255.0, 0.0)
    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val WHITE = Scalar(255.0, 255.0, 255.0)
}
```

- [ ] **Step 4: `ArrowCorpusRun` darauf umstellen**

In `ArrowCorpusRun`:

- Die Felder `root`, `reportDir` und `requireCorpus()` durch `private lateinit var dirs: ArrowCorpusRuns.Dirs` und `@Before fun requireCorpus() { dirs = ArrowCorpusRuns.dirs() }` ersetzen.
- Der Rumpf von `findsTheArrowsAndWritesTheReport` beginnt mit den vier Listen und dann:

```kotlin
        val outOfScope = ArrowCorpusRuns.forEachInScope(dirs, "arrows") { photo ->
            val entry = photo.entry
            val image = photo.image
            val request = ArrowCorpusRuns.requestFor(entry, image.cols(), image.rows())
            val analysis = detector.analyse(image, request, PngDebugSink(photo.folder))
            val measured = measure(entry, analysis, request, image.cols(), image.rows())
            rows += measured.row
            truths += measured.truths
            photos += measured.photo
            outcomes += measured.outcome
            if (analysis is ArrowAnalysis.Analysed) {
                val reference = entry.registration?.imageToTarget
                if (reference != null) {
                    CorpusPhotos.writeRectified(
                        image, analysis.registration.imageToTarget, reference, entry.imageName,
                        File(photo.folder, "4-entzerrt.png")
                    )
                }
                ArrowCorpusRuns.writeTruth(
                    image, analysis.registration.imageToTarget, measured.outcome, File(photo.folder, "7-wahrheit.png")
                )
            }
        }
```

- `File(reportDir, "arrows.md")` wird `File(dirs.reportDir, "arrows.md")`.
- `requestFor`, `record`, `writeTruth`, `point`, `CYAN`, `GREEN`, `WHITE` und die dazu nicht mehr nötigen Importe fallen aus `ArrowCorpusRun` heraus; `record(it)` wird `ArrowCorpusRuns.record(it)` (drei Stellen in `analysed`).
- Alles andere (Messung, Diagnose, `PINS`, Schranken) bleibt wörtlich.

- [ ] **Step 5: Der klassische Lauf ist unverändert**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*ArrowCorpusRun" --rerun`
Expected: PASS; `detection/build/reports/detection/arrows.md` zeigt unter *Bounds, oblique photographs* dieselbe Zeile wie `PINS` (70 / 401 / 95 / 53 / 48 / 51 / 0.012174 / 0.058149).

- [ ] **Step 6: Commit**

```bash
git add detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRuns.kt detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRun.kt detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ArrowReport.kt detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ArrowReportTest.kt
git commit -m "detection: the corpus runs share their loop, request and truth image; the report takes a preface"
```

---

## Task 14: `LearnedArrowCorpusRun` und die erste Messung

Derselbe Bericht, dieselben Kennzahlen, ein eigener Vorspann mit den Vermerken des Designs. Noch ohne Schranken; die kommen in Task 15 aus dem Bericht.

**Files:**
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowCorpusRun.kt`

**Interfaces:**
- Consumes: `ArrowCorpusRuns`, `LearnedArrowDetector`, `LearnedAnalysis`, `ArrowReport`, `ArrowRow`, `TruthDiagnosis`, `PhotoDiagnosis`, `EntryOutcome`, `Metrics`, `ShotMatching`, `ArrowBounds`, `ArrowMeasurement`, `RegistrationError`, `CorpusPhotos`
- Produces: `detection/build/reports/detection/learned-arrows.md` und `learned-arrows/<name>/` mit den Bildern 1 bis 7

- [ ] **Step 1: Den Lauf schreiben**

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.ArrowBounds
import de.dreier.mytargets.detection.metrics.ArrowGroups
import de.dreier.mytargets.detection.metrics.ArrowMeasurement
import de.dreier.mytargets.detection.metrics.ArrowReport
import de.dreier.mytargets.detection.metrics.ArrowRow
import de.dreier.mytargets.detection.metrics.EntryOutcome
import de.dreier.mytargets.detection.metrics.Metrics
import de.dreier.mytargets.detection.metrics.PhotoDiagnosis
import de.dreier.mytargets.detection.metrics.RegistrationError
import de.dreier.mytargets.detection.metrics.ShotMatching
import de.dreier.mytargets.detection.metrics.StageMillis
import de.dreier.mytargets.detection.metrics.TruthDiagnosis
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.PngDebugSink
import de.dreier.mytargets.detection.SpotMapping
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Finds the arrows with the learned finder in every annotated photograph in
 * scope and writes the report and the stage images (design 3d, Messung und
 * Tests). The model was trained on these photographs: the numbers are an
 * upper bound and a regression guard, the estimate on new photographs is the
 * PoC's cross-validation, both named in the report.
 */
class LearnedArrowCorpusRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var dirs: ArrowCorpusRuns.Dirs
    private lateinit var modelDir: File
    private lateinit var model: ArrowModel
    private lateinit var detector: LearnedArrowDetector

    @Before
    fun requireCorpusAndModel() {
        dirs = ArrowCorpusRuns.dirs()
        val configured = System.getProperty("detection.model.dir")
        assumeTrue("DETECTION_MODEL_DIR is not configured", configured != null)
        modelDir = File(configured!!)
        assumeTrue("model directory does not exist: $configured", modelDir.isDirectory)
        model = ArrowModel.load(modelDir)
        detector = LearnedArrowDetector(model)
    }

    @Test
    fun findsTheArrowsAndWritesTheReport() {
        val rows = ArrayList<ArrowRow>()
        val truths = ArrayList<TruthDiagnosis>()
        val photos = ArrayList<PhotoDiagnosis>()
        val outcomes = ArrayList<EntryOutcome>()

        val outOfScope = ArrowCorpusRuns.forEachInScope(dirs, "learned-arrows") { photo ->
            val entry = photo.entry
            val image = photo.image
            val request = ArrowCorpusRuns.requestFor(entry, image.cols(), image.rows())
            val analysis = detector.analyse(image, request, PngDebugSink(photo.folder))
            val measured = measure(entry, analysis, request.layout, image.cols(), image.rows())
            rows += measured.row
            truths += measured.truths
            photos += measured.photo
            outcomes += measured.outcome
            if (analysis is LearnedAnalysis.Analysed) {
                val reference = entry.registration?.imageToTarget
                if (reference != null) {
                    CorpusPhotos.writeRectified(
                        image, analysis.registration.imageToTarget, reference, entry.imageName,
                        File(photo.folder, "4-entzerrt.png")
                    )
                }
                ArrowCorpusRuns.writeTruth(
                    image, analysis.registration.imageToTarget, measured.outcome, File(photo.folder, "7-wahrheit.png")
                )
            }
        }

        val oblique = outcomes.filter { ArrowGroups.of(it.entry) == ArrowGroups.OBLIQUE }
        val measurement = ArrowMeasurement.of(rows.count { it.group == ArrowGroups.OBLIQUE }, Metrics.over(oblique))
        val worstBudget = ShotMatching.DEFAULT_POSITION_TOLERANCE +
            (oblique.flatMap { it.entry.shots }.mapNotNull { it.positionTolerance }.maxOrNull() ?: 0.0)
        val listedInScope = outcomes.flatMap { it.entry.shots }
        val ownTips = listedInScope.count { it.tipPixel != null }
        val report = File(dirs.reportDir, "learned-arrows.md")
        report.writeText(
            ArrowReport.render(
                "Learned finder against the corpus", rows, truths, photos, outcomes, outOfScope, preface(rows.size)
            ) +
                "\n## Truth per view\n\n$ownTips of ${listedInScope.size} listed hits in scope " +
                "carry a clicked tip of their own view (`shots[i].tipPx`) and are matched where this run's " +
                "homography puts that tip; the rest are matched against the truth of the end, carried from " +
                "the steepest view. See docs/design/2026-09-16-roll-in-the-measurement.md.\n" +
                ArrowReport.pinsSection(ArrowBounds.pinsFor(measurement, worstBudget))
        )
        println("Learned arrow report: ${report.absolutePath}")
    }

    /** The preface of the report: what this number is and is not (design 3d, Guete und Messung sind getrennt). */
    private fun preface(photographs: Int): String {
        val m = model.meta
        val cv = m.crossValidation.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        return "Model `${modelDir.name}` (training `${m.training}`, corpus `${m.corpus}`, ${m.inputSize} px, " +
            "threshold ${m.threshold}, ${m.thresholdFrom}).\n\n" +
            "**The model was trained on these photographs.** Every number below is an upper bound and a " +
            "regression guard for the chain warp, network, peaks; it is no estimate of the quality on new " +
            "photographs. That estimate is the PoC's cross-validation: $cv.\n\n" +
            "Two differences to the PoC's metric: the PoC leaves out frontal views whose truth is inherited " +
            "from a sibling without a clicked tip, this run measures all $photographs annotated photographs " +
            "in scope against the truth of the end; and a peak outside the face counted as a false positive " +
            "in the PoC, while here it takes one of the expected places and never reaches the metrics, so the " +
            "false positive count can be lower than the PoC's.\n\n" +
            "Columns: Candidates are the peaks at or above the threshold, Accepted the ones kept by count and " +
            "placed on a spot. Q, registration Q shift, line offsets, common point, NO_SHAFT and RAN_OUT do not " +
            "apply to this finder. The times are registration / warp (with pre-shrink) / network / peaks."
    }

    private class Measured(
        val row: ArrowRow,
        val truths: List<TruthDiagnosis>,
        val photo: PhotoDiagnosis,
        val outcome: EntryOutcome
    )

    private fun measure(
        entry: CorpusEntry,
        analysis: LearnedAnalysis,
        layout: de.dreier.mytargets.detection.FaceLayout,
        width: Int,
        height: Int
    ): Measured {
        val group = ArrowGroups.of(entry)
        val millis = with(analysis.timings) { StageMillis(registrationMs, warpMs, networkMs, peaksMs) }
        return when (analysis) {
            is LearnedAnalysis.NotRegistered -> notRegistered(entry, group, analysis, millis)
            is LearnedAnalysis.Analysed -> analysed(entry, group, analysis, layout, width, height, millis)
        }
    }

    private fun notRegistered(
        entry: CorpusEntry,
        group: String,
        analysis: LearnedAnalysis.NotRegistered,
        millis: StageMillis
    ): Measured {
        val row = ArrowRow(
            imageName = entry.imageName, group = group, angleDegrees = entry.capture?.angleDegrees,
            outcome = analysis.registration.failure.name, detail = analysis.registration.detail,
            footPointFromCentre = null, registrationError = null, footPointShift = null,
            largestLineOffset = null, commonPointFromFoot = null,
            listed = entry.shots.size, unresolved = entry.unresolvedArrows,
            candidates = 0, accepted = 0, matched = 0, falsePositives = 0, selection = null,
            largestPositionError = null, noShaft = 0, ranOut = 0, millis = millis
        )
        val truths = entry.shots.indices.map { TruthDiagnosis(entry.imageName, group, it, null, null, null, false) }
        val outcome = EntryOutcome(entry, ShotMatching.match(entry, emptyList()), emptyList())
        return Measured(row, truths, PhotoDiagnosis(entry.imageName, group, null), outcome)
    }

    /**
     * Like the classical run's diagnosis: step 1 pairs the metrics made with
     * accepted finds; step 2 matches the remaining hits against the peaks the
     * finder had but did not accept (dropped by the count, by the spot cap);
     * step 3 counts the rest as false. A peak outside the face is neither.
     */
    private fun analysed(
        entry: CorpusEntry,
        group: String,
        analysis: LearnedAnalysis.Analysed,
        layout: de.dreier.mytargets.detection.FaceLayout,
        width: Int,
        height: Int,
        millis: StageMillis
    ): Measured {
        val accepted = analysis.accepted
        val detected = accepted.map { ArrowCorpusRuns.record(it.candidate) }
        val entry = entry.truthInView(CorpusPhotos.values(analysis.registration.imageToTarget).toDoubleArray())
        val match = ShotMatching.match(entry, detected)
        val outcome = EntryOutcome(entry, match, detected)

        // Step 1.
        val foundAndAccepted = match.pairs.associate { pair -> pair.truthIndex to (accepted[pair.detectedIndex] to true) }

        // Step 2: the peaks on the face the finder did not accept -- kept but over
        // the spot cap, or beyond expectedShots by value -- placed like step 6 would.
        val notAccepted = analysis.onFace.filter { f -> accepted.none { it === f } } +
            analysis.peaks.drop(analysis.kept.size).mapNotNull { peak ->
                val located = SpotMapping.locate(FaceWarp.targetOf(Vec2(peak.u, peak.v), model.meta.inputSize), layout)
                located?.let { LearnedFind(peak, de.dreier.mytargets.detection.Candidate(it.faceIndex, it.local, peak.value)) }
            }
        val remainingIndices = match.unmatchedTruth
        val remainingEntry = entry.copy(shots = remainingIndices.map { entry.shots[it] })
        val remainingMatch = ShotMatching.match(remainingEntry, notAccepted.map { ArrowCorpusRuns.record(it.candidate) })
        val foundButLost = remainingMatch.pairs.associate { pair ->
            remainingIndices[pair.truthIndex] to (notAccepted[pair.detectedIndex] to false)
        }

        val hits = foundAndAccepted + foundButLost
        val truths = entry.shots.indices.map { t ->
            val hit = hits[t]
            TruthDiagnosis(
                imageName = entry.imageName,
                group = group,
                truthIndex = t,
                rank = hit?.let { (find, _) -> analysis.peaks.indexOfFirst { it === find.peak } + 1 },
                confidence = hit?.first?.peak?.value,
                lineOffset = null,
                accepted = hit?.second ?: false
            )
        }

        // Step 3.
        val falseFinds = match.unmatchedDetected.map { accepted[it] } +
            remainingMatch.unmatchedDetected.map { notAccepted[it] }
        val reference = entry.registration?.imageToTarget
        val row = ArrowRow(
            imageName = entry.imageName,
            group = group,
            angleDegrees = entry.capture?.angleDegrees,
            outcome = ArrowRow.REGISTERED,
            detail = null,
            footPointFromCentre = null,
            registrationError = reference?.let {
                RegistrationError.between(it, CorpusPhotos.values(analysis.registration.imageToTarget), width, height)?.max
            },
            footPointShift = null,
            largestLineOffset = null,
            commonPointFromFoot = null,
            listed = entry.shots.size,
            unresolved = entry.unresolvedArrows,
            candidates = analysis.peaks.size,
            accepted = accepted.size,
            matched = match.pairs.size,
            falsePositives = Metrics.over(listOf(outcome)).falsePositives,
            selection = analysis.reason.name,
            largestPositionError = match.pairs.mapNotNull { it.distance }.maxOrNull(),
            noShaft = 0,
            ranOut = 0,
            millis = millis
        )
        return Measured(
            row, truths,
            PhotoDiagnosis(entry.imageName, group, falseFinds.maxOfOrNull { it.peak.value }, 0, falseFinds.size),
            outcome
        )
    }
}
```

Die voll qualifizierten Namen `de.dreier.mytargets.detection.FaceLayout` und `…Candidate` durch Importe ersetzen (`import de.dreier.mytargets.detection.Candidate`, `import de.dreier.mytargets.detection.FaceLayout`).

- [ ] **Step 2: Laufen lassen**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedArrowCorpusRun" --rerun`
Expected: PASS (noch ohne Schranken); die Konsole nennt den Pfad des Berichts. Dauer: rund 100 Fotos mal (Registrierung rund 1 s, Netz rund 1,2 s auf einem PC-Kern, Debug-Bilder), also einige Minuten; bei mehr als 10 Minuten `run_in_background` oder `Start-Process`.

- [ ] **Step 3: Den Bericht lesen und festhalten**

`detection/build/reports/detection/learned-arrows.md`: Tabelle *Groups* (schräg: Fotos, gelistete Treffer, gefunden, Rate, Fehlfunde, Ring, Median, p95), den Block *Bounds, oblique photographs*, und *Candidates before the selection*. Einordnen:

- Rate schräg deutlich über den 71 % der Kreuzvalidierung ist zu erwarten (Trainingsfotos); liegt sie **darunter**, ist die Kette gestört (Warp, Normierung, Schwelle), zuerst den Paritätstest und zwei Bilder `5-heatmap` ansehen.
- Fehlfunde je Ansicht gegen die 0,64 der Kreuzvalidierung: niedriger ist zu erwarten (Trainingsfotos, Spitzen außerhalb der Auflage zählen nicht).
- Registrierungsausfälle wie beim klassischen Lauf (derselbe Registrar).

Drei Bilder stichprobenartig öffnen (`5-heatmap.png`, `6-spitzen.png`, `7-wahrheit.png` eines schrägen Fotos): Glühen auf den Spitzen, grüne Ringe auf den cyanfarbenen Budgets.

- [ ] **Step 4: Commit**

```bash
git add detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowCorpusRun.kt
git commit -m "detection: LearnedArrowCorpusRun measures the learned finder against the corpus"
```

---

## Task 15: Schranken und Dokumentation

**Files:**
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowCorpusRun.kt`
- Modify: `BUILDING.md` (Abschnitt 4)
- Modify: `detection/src/androidTest/README.md`
- Modify: `docs/design/2026-09-17-learned-arrow-detector-design.md` (Nachtrag)
- Modify (Korpus-Repo): `learn/README.md`

- [ ] **Step 1: Die Pins übernehmen**

Aus dem Block *Bounds, oblique photographs* des Berichts von Task 14 die Zeile `ArrowPins(...)` kopieren und in `LearnedArrowCorpusRun` einsetzen:

```kotlin
    private companion object {
        /**
         * Pinned from the report of <Datum>, model r4-all-2026-09. The model
         * trained on these photographs: the pins are an upper bound and a
         * regression guard for the chain warp, network, peaks, not the quality
         * on new photographs (that is the PoC's cross-validation, 71.3 % at
         * 1.06 FP/view and 63.3 % at 0.64). When the corpus or the model
         * changes the run fails and says so; set them again against a new report.
         */
        val PINS = ArrowPins(photographs = <n>, listed = <n>, matched = <n>, falsePositives = <n>, correctScores = <n>, comparableScores = <n>, medianErrorBound = <x>, p95ErrorBound = <x>)
    }
```

(`import de.dreier.mytargets.detection.metrics.ArrowPins`, `import com.google.common.truth.Truth.assertWithMessage`.) Am Ende von `findsTheArrowsAndWritesTheReport`, nach dem `println`:

```kotlin
        // Checked after writing, so a failing run still leaves its report.
        val broken = ArrowBounds.violations(measurement, PINS)
        assertWithMessage("bounds of the oblique photographs (training photographs, upper bound):\n" + broken.joinToString("\n"))
            .that(broken).isEmpty()
```

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest --tests "*LearnedArrowCorpusRun" --rerun`
Expected: PASS.

- [ ] **Step 2: `BUILDING.md`**

In Abschnitt 4 nach dem Absatz zum Pfeillauf (`arrows.md`):

````markdown
The learned arrow finder (`docs/design/2026-09-17-learned-arrow-detector-design.md`)
reads its weights from a model folder of the corpus repository. Point to it
from `gradle-local.properties`:

```properties
DETECTION_MODEL_DIR=../MyTargets-corpus/models/arrows/r4-all-2026-09
```

Without the entry the tests that need the model skip themselves. With it, the
learned run measures the finder against the corpus and writes
`detection/build/reports/detection/learned-arrows.md`:

```
./gradlew :detection:testDevDebugUnitTest --tests '*LearnedArrowCorpusRun' --rerun
```

The model was trained on the corpus photographs, so this report is an upper
bound and a regression guard; the report's preface says so and names the
cross-validation numbers that estimate the quality on new photographs.
````

- [ ] **Step 3: README des Zeittests**

In `detection/src/androidTest/README.md` nach dem Codeblock mit den `cp`-Befehlen:

```markdown
Since design 3d, `export_onnx.py` exports the wrapper with ImageNet
normalisation and sigmoid inside the graph. Assets produced with it are
normalised a second time by this test's own `blobFromImage`; the timing is
unaffected, the printed maximum is then a probability rather than a logit.
`export_onnx.py` now needs `--fold N` or `--all` explicitly.
```

- [ ] **Step 4: Nachtrag im Design**

Am Ende von `docs/design/2026-09-17-learned-arrow-detector-design.md`:

```markdown
## Nachtrag <Datum>: umgesetzt und gemessen

Plan `docs/plans/2026-09-17-detection-learned-arrows.md`. Der Rauchtest im
Desktop-Jar 4.9 lief <grün / Befund>. Der Paritätstest hält auf beiden
Ansichten (<größter Abstand> px, <größte Wertdifferenz>). Modell
`r4-all-2026-09` (Korpus-Commit <hash>), Schwelle 0,12.

Korpuslauf, schräge Gruppe (<n> Fotos, <n> gelistete Treffer, Trainingsfotos,
Obergrenze): <n> gefunden (<x> %), <n> Fehlfunde (<x> je Ansicht), Ring
<x> %, Fehler Median <x>, p95 <x>. Frontal: <x> % bei <x> Fehlfunden je
Ansicht. Kreuzvalidierung des PoC daneben: 71,3 % bei 1,06 und 63,3 % bei
0,64. Zeiten je Foto auf dem PC: Registrierung <x> ms, Warp <x> ms, Netz
<x> ms, Spitzen <x> ms.

<Ein Absatz Lesart: passt die Schwelle 0,12 (Fehlfundrate), was zeigen die
Heatmaps der Fehlfunde, was heißt das für Schritt 8.>
```

Die Platzhalter mit den Werten aus dem Bericht füllen.

- [ ] **Step 5: `learn/README.md` im Korpus-Repo**

Nach dem Abschnitt *Der Weg in die App, beziffert*:

````markdown
## Das ausgelieferte Modell (Plan 3d, <Datum>)

`train.py --all` trainiert ohne Folds auf allen Ansichten mit Wahrheit
(Konfiguration von r4), `export_onnx.py --all --out …` exportiert das
Hüllmodul `(x − mean)/std → UNet → Sigmoid` für RGB in [0, 1] als fp16-ONNX
samt Beilage `model.json` und, mit `--peaks`, die Paritätsreferenz. Der
Ordner `models/arrows/r4-all-2026-09/` im Korpus ist, was die App liest
(`DETECTION_MODEL_DIR` im Hauptrepo); der Loader der App überspringt `models/`.

```
.venv/Scripts/python train.py --all --epochs 60 --res 768 --crop 512 --run ../../MyTargets-learn/runs/all-2026-09-17
.venv/Scripts/python export_onnx.py ../../MyTargets-learn/runs/all-2026-09-17 --all --res 768 --fp16 \
  --out ../models/arrows/r4-all-2026-09 --threshold 0.12 --threshold-from "…" \
  --peaks 2026-09-15_bedeckt_stark-schraeg_07 2026-08-15_bedeckt_frontal_02
```

Die Schwelle 0,12 ist der Median der FP-begrenzten Fold-Schwellen von r4; sie
wird nicht auf den Trainingsfotos gewählt. Der Korpuslauf der App auf diesem
Modell misst Trainingsfotos und ist eine Obergrenze: <Zahlen aus dem Bericht>.
Die Güte auf neuen Fotos bleibt die Kreuzvalidierung von r4.
````

Im Abschnitt *Ordner* die Zeile für `export_onnx.py` ergänzen (`export_onnx.py  Modell -> ONNX für die App, model.json, parity/`).

```bash
git -C ../MyTargets-corpus add learn/README.md
git -C ../MyTargets-corpus commit -m "learn: README, the shipped model and how it is exported"
git -C ../MyTargets-corpus push
```

- [ ] **Step 6: Alles noch einmal, dann Commit**

Run: `JAVA_HOME=<jdk17> ./gradlew :detection:testDevDebugUnitTest :detection-corpus:test --rerun`
Expected: PASS; `git status --short` zeigt kein `.onnx` und kein `.pt`.

```bash
git add detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowCorpusRun.kt BUILDING.md detection/src/androidTest/README.md docs/design/2026-09-17-learned-arrow-detector-design.md
git commit -m "detection: pins of the learned corpus run; docs for the model folder and the measurement"
```

Dann Push des Branches und Pull Request gegen `master` mit den Zahlen aus dem Nachtrag.

---

## Was dieser Plan nicht liefert

- Den Einbau in die App (Assets kopieren, `LearnedArrowDetector` hinter der Kamera, Winograd nach Gerätespeicher, Rückfall auf den klassischen Finder), Schritt 8 der Haupt-Spec.
- Den Roll-Anker im Registrar, Voraussetzung für die Gruppenanzeige; eigener Plan.
- Eine Lückenregel auf dem Heatmap-Wert; der Bericht liefert die Werte, an denen sie zu prüfen wäre.
- Ein kleineres Rückgrat, Copy-Paste-Augmentierung, neue Fotos, die Trennung dichter Gruppen.
- Eine Bytes-Variante von `ArrowModel`, eine dynamische Eingabegröße, eine Schwellenwahl zur Laufzeit.
- 3-Spot-Auflagen im Korpuslauf; `LearnedSelection` kann sie, der Korpus hat keine.

## Self-Review

**Abdeckung des Designs:**

| Design | Task |
|---|---|
| Ziel: zweite Implementierung, derselbe Korpuslauf, klassischer bleibt | 9, 13, 14 |
| Entscheidung 1: Gewichte im Korpus-Repo per Build-Property | 1, 6, 12 |
| Entscheidung 2: eigene Klasse, kein Mischbetrieb | 9 |
| Entscheidung 3: OpenCV DNN, `org.opencv.dnn` erlaubt | 1, 2 |
| Entscheidung 4: Überschuss nach Wert, keine Lückenregel | 3, 9 (`take(expectedShots)`, `LearnedSelection`) |
| Entscheidung 5: Normierung und Sigmoid im Graphen, `blobFromImage` 1/255 RGB | 8, 9 |
| Datenfluss 1 Registrierung, `Failed` | 9 |
| Datenfluss 2 Vorverkleinerung 2000 px INTER_AREA, Homographie skaliert, Warp auf `inputSize` | 4, 9 |
| Datenfluss 3 Eingabe | 9 (`forward`) |
| Datenfluss 4 Vorwärtslauf, Kanal 0 | 9 |
| Datenfluss 5 Spitzen wie `peaks()` | 3 |
| Datenfluss 6 Rückrechnung, Spot, außerhalb, Gründe | 4 (`targetOf`), 9 (`LearnedSelection`) |
| `LearnedAnalysis` mit Zeiten | 9 |
| Debug-Bilder | 10 |
| `train.py --all` | 7 |
| `export_onnx.py` Hülle, `--peaks` | 8 |
| Güte und Messung getrennt, Obergrenze im Bericht und in den Pins | 14 (Vorspann), 15 |
| Schwelle 0,12 | 12 |
| Ablageformat `models/arrows/<name>/` | 8, 12 |
| `ArrowModel.load`, Beilage einzige Quelle | 5 |
| Auffinden per `DETECTION_MODEL_DIR`, überspringen ohne | 1 |
| `LearnedArrowCorpusRun`, eigene Pins, gemeinsame Hilfsklasse, zwei Abweichungen im Kopf | 13, 14, 15 |
| Paritätstest mit Stub-Registrar, 0,5 px, 0,01 | 11 |
| JVM-Tests `HeatmapPeaks` | 3 |
| `ArrowModel`-Tests, falsche Größe benannt | 5, 9 |
| `OpenCvImportRuleTest` | 2 |
| Zeittest bleibt | 15 (README-Hinweis) |
| Fehlerfälle: Modell nicht lesbar, keine Spitze, Speicher | 9 (Konstruktor, `FEWER_THAN_EXPECTED`, Doku im Klassenkommentar) |
| Risiko 4.9 gegen 4.14: Rauchtest zuerst | 1 |
| Risiko Parität: Rundung und Homographie zuerst prüfen | 11 (Step 2) |

**Platzhalter:** Die offenen Werte sind Messwerte: die Pins (Task 15, Step 1), die Zahlen des Nachtrags (Step 4) und der README (Step 5). Alle stehen im Bericht von Task 14, und die Schritte sagen, wo.

**Typen:** `Peak(u, v, value)` und `Heatmap(width, height, values)` (Task 3) werden in Task 9, 10, 11 und 14 so gelesen. `PreShrink.of/shrink/imageToTarget/isIdentity` (Task 4) wie in Task 9 und 11. `ArrowModelMeta` mit neun Parametern in dieser Reihenfolge (Task 5) wie der Aufruf in Task 9 (`aSidecarWithTheWrongInputSizeIsRejectedByName`). `LearnedSelection.Placement(onFace, outsideFace, accepted, reason)` und `LearnedAnalysis.Analysed(registration, peaks, kept, onFace, outsideFace, accepted, reason, timings)` (Task 9) wie in Task 10 und 14. `LearnedDebugImages.peaks(warped, peaks, accepted, outsideFace)` (Task 9 Platzhalter, Task 10) wie der Aufruf im Detektor. `ArrowCorpusRuns.dirs/forEachInScope/requestFor/record/writeTruth` (Task 13) wie in Task 14. `ArrowReport.render(..., preface)` (Task 13) wie in Task 14. Die Paritätsdatei (Task 8: `view, inputSize, stride, kernel, threshold, maxCount, imageToTarget, imageMeanBgr, peaks[u, v, value]`) wie `Reference` in Task 11 sie liest.
