# Pfeilfindung (Plan 3b) — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:detection` findet auf einem registrierten Foto einer `WAFull`-Auflage die steckenden Pfeile und liefert ein vollständiges `DetectionResult`; ein Korpuslauf misst Erkennung, Fehlfunde, Ringtreue und Positionsfehler gegen die Sidecars und schreibt für die schrägen Fotos Regressionsschranken fest.

**Architecture:** Neues Paket `de.dreier.mytargets.detection.arrows`. Der Detektor registriert mit dem Registrar aus 3a, entzerrt mit `FaceWarp`, rechnet den Fußpunkt `Q` der Kamera (reine Geometrie), sucht Schäfte als Linien durch `Q` (Portierung von `radial.py`) auf einem `FloatArray` der Helligkeit, läuft jeden Schaft bis zum Einschuss ab (Portierung von `tips.py`), legt Kandidaten zusammen, bewertet sie, ordnet sie Spots zu und wählt mit der vorhandenen `CandidateSelection` aus. `:detection-corpus` rendert den Bericht und rechnet die Schranken, ohne ein Bild anzufassen.

**Tech Stack:** Kotlin, Android-Library-Modul, `org.openpnp:opencv:4.9.0-0` (compileOnly und Test), JUnit 4, Truth; im Korpusmodul Gson.

**Spec:** `docs/design/2026-09-11-detection-arrows-design.md` (bindend für diesen Plan), dazu `docs/design/2026-09-09-arrow-detection-design.md` (Haupt-Spec), `docs/design/2026-09-10-detection-registration-design.md` (Registrierungsdesign) und `<DETECTION_CORPUS_DIR>/tools/radial.py` und `tools/tips.py`, deren Verfahren portiert wird.

**Voraussetzung:** Branch `plan/detection-arrows`. Er enthält `cc2b2b14`, den Pin des Korpustests auf 34 Einträge; ohne ihn schlägt `RealCorpusTest` gegen den Korpus vom 2026-09-11 fehl.

## Präzisierungen gegenüber dem Design

Beim Ausarbeiten sind diese Stellen genauer geworden. Keine ändert eine Entscheidung des Designs.

1. **`ArrowAnalysis` trägt die Laufzeiten.** Der Bericht verlangt die Laufzeit je Stufe, und messen kann sie nur `analyse`. `NotRegistered` und `Analysed` tragen deshalb `StageTimings`.
2. **Das Vorzeichen des homogenen Anteils bezieht sich auf das Scheibenzentrum.** `H⁻¹` ist nur bis auf einen Faktor bestimmt. Gültig ist ein Pixel, dessen Urbild im homogenen Anteil dasselbe Vorzeichen hat wie das Urbild des Scheibenzentrums.
3. **Ungültige Pixel im Kontrast:** Ist an einer Probe irgendeine Mittellinie oder Flanke ungültig, ist ihr Kontrast 0. `radial.py` ließ ungültige Mittellinien per `nanmin` aus; die Regel des Designs ist einfacher und strenger.
4. **Genauigkeit des Schaftlaufs im Test:** auf 0,002, zwei Pixel des Testbilds bei 1000 px je Radius, nicht auf eine Probe (0,0005). Das gemalte Streifenende liegt auf einer Pixelgrenze, und die Schwelle von 35 % schneidet die bilineare Rampe innerhalb eines Pixels.
5. **Die Hilfen der Korpusläufe** wandern aus `RegistrationCorpusRun` nach `CorpusPhotos` (Testquellen), damit beide Läufe Dateisuche, Größenprüfung und Bild 4 teilen.
6. **Gruppen:** `ArrowGroups` im Korpusmodul ordnet ein Foto nach `capture.angle` der Gruppe schräg oder frontal zu; Bericht und Schranken verwenden dieselbe Funktion.
7. **Der Bericht druckt die festzuschreibenden Werte** (Task 14), damit die Schranken abgeschrieben und nicht von Hand gerechnet werden. Er druckt sie mit sechs Nachkommastellen, damit Runden eine Schranke nie unter den eigenen Messwert drückt.
8. **Die synthetische Kamera ist die von `SyntheticFace.view`:** Brennweite 1500 px, Hauptpunkt im Scheibenzentrum, Kamera bei `(d · sin a, 0, −d · cos a)` mit `d = 1500 / pxPerRadius`. Die Szenen: 30° bei 650 px je Radius, Kamerahöhe 2,0 Radien wie in den Neigungsfällen des Designs, und 45° bei 530 px je Radius, ebenfalls Höhe 2,0.
9. **Der synthetische Pfeil:** Schaft 0,014 Radien breit (5,5 mm auf der 80er), Nocke 0,8 Radien über der Auflage, Befiederung über das letzte Fünftel, 0,05 breit.
10. **`DetectionRequest` verlangt eine nicht leere Übergangstabelle**, wie es schon nicht leere Zonenradien verlangt.
11. **Die Zonenradien von `WAFull`** stehen für Tests und Korpuslauf in `WaFullZones` (Testquellen); die App übersetzt sie später aus `TargetModelBase`.
12. **Ringwert eines Funds:** der erste Zonenindex, dessen Radius größer ist als der Abstand des Einschusses vom Zentrum, wie `zone()` in `tips.py`; jenseits des letzten Rings keiner.
13. **Die Zuversicht im Test** rechnet mit den Konstanten aus `ArrowCandidates`, damit ein Nachstellen in Task 13 den Test nicht bricht.
14. **Die synthetischen Einschüsse liegen nicht im schwarzen Ring** (0,6 < r ≤ 0,8). Ein dunkler Schaft (V 0,157) hat auf Schwarz (V 0,118) einen Kontrast von 0,04, weit unter der Schwelle 0,15; die Suche sieht ihn dort nicht. Das ist die bekannte Grenze der Werkzeuge, kein Testfall. Der einzige Pfeil im schwarzen Ring ist der graue in Task 10. Geprüft ist auch der Weg jedes Schafts im entzerrten Bild, bei 30°, 45° und im Neigungsfall: Er beginnt auf einer hellen Zone, und wo er den schwarzen Ring kreuzt, bleibt die Lücke unter 0,3, damit das Zusammenlegen sie schließt. Die Lücke zählt, wie die Suche sie sieht: von der Stelle, an der die erste Flanke (±0,016) Schwarz erreicht, bis zu der, an der beide es verlassen haben; wo vor der Befiederung ein Stück Schaft dahinter bleibt, sind das 0,21 bis 0,23. Die eine Ausnahme ist (0,25; −0,42) bei 45°: Lücke 0,30, dahinter aber nur 0,04 Schaft, bevor die Befiederung ihn verdeckt. Das Stück fällt schon in der Suche unter die Mindestlänge 0,08.
15. **Der Fußpunkt im synthetischen Test stimmt auf 0,03**, nicht auf 0,01. Die Ringe legen die dritte Zeile der Homographie kaum fest (Design, *Korpuslauf und Bericht*), und die Suche verkraftet 0,04. Was der Registrar wirklich schafft, zeigt danach die Spalte *Q shift* im Bericht.

## Global Constraints

- **Sprache:** Code, Bezeichner, Kommentare, Detail- und Berichtstexte Englisch.
- **Lizenzkopf:** Jede neue Kotlin-Datei beginnt mit dem GPLv2-Kopf aus `detection/src/test/java/de/dreier/mytargets/detection/geometry/RectificationTest.kt` (Zeilen 1–14, „Copyright (C) 2026 MyTargets contributors“). Die Codeblöcke unten lassen ihn weg.
- **Pfade:** `:detection` Hauptcode unter `detection/src/main/java/de/dreier/mytargets/detection/...`, Tests unter `detection/src/test/java/de/dreier/mytargets/detection/...` (Kotlin-Dateien liegen dort unter `java/`). `:detection-corpus` unter `detection-corpus/src/main/kotlin/...` und `detection-corpus/src/test/kotlin/...`.
- **OpenCV:** Der Hauptcode importiert aus OpenCV nur `org.opencv.core` und `org.opencv.imgproc`. `OpenCvImportRuleTest` prüft alle Hauptquellen, also auch `arrows/`.
- **Pixelarbeit:** Pixel werden einmal per `Mat.get(0, 0, array)` geholt. `Mat.get(row, col)` in einer Schleife ist verboten.
- **Speicher:** Jede selbst erzeugte `Mat` wird in `try`/`finally` mit `release()` freigegeben; eine Funktion, die eine neue `Mat` zurückgibt, füllt sie in `releaseIfThrows`.
- **Einheiten:** Längen in Auflageneinheiten (Spot-Radien der Vollauflage), Winkel in Grad, wo das Design Grad nennt. Die Schwellen stehen wörtlich im Design und in diesem Plan.
- **Gleitkomma:** `Double`; nur der Helligkeitskanal ist ein `FloatArray`.
- **Berichte:** Zahlen mit `Locale.ROOT`.
- **Schranken erst in Task 14.** Bis dahin prüft der Pfeillauf nichts hart.
- **JDK 17:** Gradle braucht `JAVA_HOME` auf ein JDK 17 (`BUILDING.md`).
- **Testbefehle:** `./gradlew :detection:testDevDebugUnitTest` und `./gradlew :detection-corpus:test`. Korpustests brauchen `DETECTION_CORPUS_DIR`, in `gradle-local.properties` oder als `-PDETECTION_CORPUS_DIR=../MyTargets-corpus`, und überspringen sich ohne. Nach einer Änderung am Korpus `--rerun` direkt hinter den Task setzen; es gilt nur für den Task davor.
- **Commits:** einer je Task, Nachricht Englisch im Stil `detection: …` bzw. `detection-corpus: …`, mit den Attributionszeilen, die die Umgebung vorgibt.
- **`:app` bleibt unberührt.**

---

## File Structure

`arrows/` steht für `detection/src/main/java/de/dreier/mytargets/detection/arrows/`, `arrows-test/` für das Gegenstück unter `src/test/java`; entsprechend `geometry/`, `registration/`, `registration-test/`. `metrics/` und `corpus/` meinen `:detection-corpus`.

| Datei | Task | Verantwortung |
|---|---|---|
| `detection/.../DebugSink.kt` | 1 | Senke für Stufenbilder, aus `registration/` ins Hauptpaket |
| `detection/.../ArrowDetector.kt` | 1 | Bild in der Schnittstelle, Übergangstabelle in der Anfrage |
| `geometry/FootPoint.kt` | 2 | Fußpunkt der Kamera aus Homographie und Kameramatrix |
| `geometry/Lines.kt` | 2 | `Line2` mit Abstand und Projektion, `CommonPoint` |
| `corpus/CaptureMetadata.kt`, `corpus/SidecarTruth.kt` | 3 | `registration.view.cameraPositionFaceUnits` lesen |
| `arrows-test/FootPointCorpusTest.kt` | 3 | Fußpunkt gegen `register.py` auf dem Korpus |
| `arrows/RectifiedFace.kt` | 4 | Helligkeitskanal und Gültigkeit des entzerrten Bildes |
| `arrows-test/FacePainter.kt` | 4 | gemalte entzerrte Bilder für Tests ohne OpenCV |
| `arrows/FlankContrast.kt` | 5 | Kontrast einer Linie gegen ihre Flanken |
| `arrows/Runs.kt` | 5 | Läufe über der Schwelle, Regeln von `radial.py` |
| `arrows/Numbers.kt` | 6 | `steps` wie `numpy.arange`, Median |
| `arrows/ShaftSearch.kt` | 6 | Suche durch `Q`, Doppelte, Zusammenlegen |
| `arrows/TipWalk.kt` | 7 | Schaftlauf, `TipRefinement`, `WalkResult` |
| `arrows/ArrowCandidates.kt` | 8 | `ArrowCandidate`, Zusammenlegen nach dem Lauf, Zuversicht |
| `arrows/ArrowAnalysis.kt` | 9 | `ArrowAnalysis`, `StageTimings` |
| `arrows/OpenCvArrowDetector.kt` | 9, 10 | der Detektor |
| `arrows-test/SyntheticArrows.kt` | 9, 10 | Kamera, Pfeile als Stäbe im Raum, Streifen auf der Auflage |
| `arrows-test/WaFullZones.kt` | 9 | Zonenradien und Zonenindex von `WAFull` |
| `arrows/ArrowDebugImages.kt` | 10 | Debug-Bilder 5 und 6 |
| `metrics/ArrowReport.kt` | 11, 14 | Gruppen, Zeilen, Diagnose, Bericht, Block der Schranken |
| `registration-test/CorpusPhotos.kt` | 12 | geteilte Hilfen der Korpusläufe |
| `arrows-test/ArrowCorpusRun.kt` | 12, 14 | der Pfeillauf, Bild 7, Schranken |
| `BUILDING.md` | 12, 14 | wie man den Lauf startet |
| `metrics/Percentiles.kt` | 14 | Perzentil, geteilt von `Metrics` und Schranken |
| `metrics/ArrowBounds.kt` | 14 | `PositionBound`, `ArrowPins`, `ArrowMeasurement`, `ArrowBounds` |

---

## Task 1: Das Bild in der Schnittstelle

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/DebugSink.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/ArrowDetector.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/registration/Registration.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/registration/OpenCvFaceRegistrar.kt`
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/registration/PngDebugSink.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/ArrowDetectorContractTest.kt`

**Interfaces:**
- Consumes: `RingTransition`, `RingTransitions.WA_FULL` (`registration/Registration.kt`), `OpenCvRule` (Testquellen)
- Produces:
  - `fun interface DebugSink { fun image(stage: String, image: Mat); companion object { val NONE: DebugSink } }` im Paket `de.dreier.mytargets.detection`
  - `interface ArrowDetector { fun detect(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): DetectionResult }`
  - `data class DetectionRequest(layout: FaceLayout, zoneRadii: List<Double>, transitions: List<RingTransition>, expectedShots: Int, intrinsics: CameraIntrinsics)`

- [ ] **Step 1: Den Vertragstest auf die neue Schnittstelle umschreiben**

`ArrowDetectorContractTest.kt` vollständig ersetzen (Lizenzkopf bleibt):

```kotlin
package de.dreier.mytargets.detection

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat

class ArrowDetectorContractTest {

    // The detector takes a Mat, and even an empty Mat is created natively.
    @get:Rule
    val openCv = OpenCvRule()

    private val request = DetectionRequest(
        layout = FaceLayout.singleSpot(),
        zoneRadii = listOf(0.2, 0.4, 0.6, 0.8, 1.0),
        transitions = RingTransitions.WA_FULL,
        expectedShots = 3,
        intrinsics = CameraIntrinsics.approximate(4000, 3000)
    )

    private val threeSpot = FaceLayout(
        facePositions = listOf(Vec2(-0.52, 0.5), Vec2(0.0, -0.5), Vec2(0.52, 0.5)),
        faceRadius = 0.48
    )

    @Test
    fun capsArrowsPerSpotFromTheEndSize() {
        // Single spot: the cap equals the end size and never bites.
        assertThat(request.maxArrowsPerSpot).isEqualTo(3)
        // Three spots, three arrows: one per spot.
        assertThat(request.copy(layout = threeSpot).maxArrowsPerSpot).isEqualTo(1)
        // Three spots, six arrows: indices 0 and 3 share a spot.
        assertThat(request.copy(layout = threeSpot, expectedShots = 6).maxArrowsPerSpot).isEqualTo(2)
        // Rounds up: four arrows on three spots need two on one of them.
        assertThat(request.copy(layout = threeSpot, expectedShots = 4).maxArrowsPerSpot).isEqualTo(2)
    }

    @Test
    fun failedResultCarriesNoShots() {
        val result = DetectionResult.failed(DetectionFailure.FACE_NOT_FOUND)
        assertThat(result.shots).isEmpty()
        assertThat(result.failure).isEqualTo(DetectionFailure.FACE_NOT_FOUND)
        assertThat(result.faceConfidence).isWithin(1e-9f).of(0f)
    }

    @Test
    fun aDetectorCanBeSubstituted() {
        // The whole point of the interface: plan 3 swaps the implementation and
        // nothing else changes.
        val stub = object : ArrowDetector {
            override fun detect(image: Mat, request: DetectionRequest, debug: DebugSink) = DetectionResult(
                shots = listOf(DetectedShot(0, 0.1f, -0.2f, 0.9f)),
                faceConfidence = 0.8f,
                reason = SelectionReason.FEWER_THAN_EXPECTED,
                failure = null
            )
        }
        val image = Mat()
        val result = try {
            stub.detect(image, request)
        } finally {
            image.release()
        }
        assertThat(result.shots).hasSize(1)
        assertThat(result.shots[0].faceIndex).isEqualTo(0)
        assertThat(result.failure).isNull()
    }

    @Test
    fun requestRejectsAnEmptyZoneList() {
        try {
            request.copy(zoneRadii = emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The radii drive the ring fitting; without them there is nothing to fit.
        }
    }

    @Test
    fun requestRejectsAnEmptyTransitionTable() {
        try {
            request.copy(transitions = emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The registration finds the face from these transitions; without them there is no face.
        }
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowDetectorContractTest*'`
Expected: FAIL beim Kompilieren — `No parameter with name 'transitions' found` und `'detect' overrides nothing`.

- [ ] **Step 3: `DebugSink` ins Hauptpaket**

`DebugSink.kt` anlegen:

```kotlin
package de.dreier.mytargets.detection

import org.opencv.core.Mat

/**
 * Receives the stage images of a detection (Haupt-Spec, Debug-Ansicht). The
 * registration and the arrow search both show their stages through it.
 */
fun interface DebugSink {
    /** [image] belongs to the stage and may be released after the call: write it now or copy it. */
    fun image(stage: String, image: Mat)

    companion object {
        val NONE = DebugSink { _, _ -> }
    }
}
```

In `registration/Registration.kt` den Block `fun interface DebugSink { … }` am Dateiende löschen und bei den Importen ergänzen:

```kotlin
import de.dreier.mytargets.detection.DebugSink
```

Denselben Import in `registration/OpenCvFaceRegistrar.kt` und in `registration-test/PngDebugSink.kt` ergänzen.

- [ ] **Step 4: Anfrage und Schnittstelle**

In `ArrowDetector.kt` bei den Importen ergänzen:

```kotlin
import de.dreier.mytargets.detection.registration.RingTransition
import org.opencv.core.Mat
```

Den Kopf von `DetectionRequest` samt KDoc und `init` ersetzen durch:

```kotlin
/**
 * Everything the detector needs that is not the image itself.
 *
 * Deliberately free of Android types so the contract can be exercised in plain
 * JVM tests. [transitions] are the colour transitions the registration fits
 * rings to (Haupt-Spec, stage 2); the app translates them from the target
 * model.
 */
data class DetectionRequest(
    val layout: FaceLayout,
    val zoneRadii: List<Double>,
    val transitions: List<RingTransition>,
    val expectedShots: Int,
    val intrinsics: CameraIntrinsics
) {
    init {
        require(zoneRadii.isNotEmpty()) { "at least one zone radius is required" }
        require(zoneRadii.all { it > 0.0 }) { "zone radii must be positive" }
        require(transitions.isNotEmpty()) { "the registration needs colour transitions" }
        require(expectedShots > 0) { "an end has at least one shot" }
    }
```

`maxArrowsPerSpot` bleibt. Die Schnittstelle am Dateiende ersetzen durch:

```kotlin
/**
 * Finds arrows in a photograph of a target face.
 *
 * The interface is narrow on purpose: it is the seam along which the classical
 * pipeline can later be replaced by a learned detector without touching the
 * app. The image comes as an OpenCV Mat so the contract stays testable on the
 * JVM; the app converts its Bitmap (arrow design, Schnittstelle).
 */
interface ArrowDetector {
    /** @param image the original as 8-bit BGR, already turned by its EXIF orientation */
    fun detect(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): DetectionResult
}
```

- [ ] **Step 5: Die ganze Suite laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS, auch `OpenCvImportRuleTest` (`DebugSink.kt` importiert nur `org.opencv.core`).

- [ ] **Step 6: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/DebugSink.kt \
  detection/src/main/java/de/dreier/mytargets/detection/ArrowDetector.kt \
  detection/src/main/java/de/dreier/mytargets/detection/registration/Registration.kt \
  detection/src/main/java/de/dreier/mytargets/detection/registration/OpenCvFaceRegistrar.kt \
  detection/src/test/java/de/dreier/mytargets/detection/registration/PngDebugSink.kt \
  detection/src/test/java/de/dreier/mytargets/detection/ArrowDetectorContractTest.kt
git commit -m "detection: the image and the transitions go into the detector's contract"
```

---

## Task 2: Fußpunkt und gemeinsamer Punkt

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/FootPoint.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Lines.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/FootPointTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/LinesTest.kt`

**Interfaces:**
- Consumes: `Mat3`, `Vec2`, `Vec3`, `CameraIntrinsics`, `NormalVanishingPoint.compute(vanishingLine: Vec3, intrinsics): Vec2?`
- Produces:
  - `object FootPoint { fun of(imageToTarget: Mat3, intrinsics: CameraIntrinsics): Vec2? }`
  - `class Line2(point: Vec2, direction: Vec2)` mit `val point`, `val direction` (Einheitsvektor), `fun signedDistanceTo(p: Vec2): Double`, `fun project(p: Vec2): Vec2`, `companion fun through(from: Vec2, to: Vec2): Line2`
  - `object CommonPoint { fun of(lines: List<Line2>, minAngleDegrees: Double = 2.0): Vec2? }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`FootPointTest.kt`:

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FootPointTest {

    /**
     * A pinhole camera with R = Rx(pitch) Ry(yaw): a face point X, z = 0,
     * sits at R X + t in camera coordinates. The camera centre is -R^T t in
     * target coordinates (z into the face); its x and y are the foot point.
     */
    private class Pose(yawDegrees: Double, pitchDegrees: Double, private val t: Vec3) {
        private val r: Mat3

        init {
            val a = Math.toRadians(yawDegrees)
            val b = Math.toRadians(pitchDegrees)
            val ry = Mat3.of(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
            val rx = Mat3.of(1.0, 0.0, 0.0, 0.0, cos(b), -sin(b), 0.0, sin(b), cos(b))
            r = rx * ry
        }

        val intrinsics = CameraIntrinsics(1500.0, Vec2(2000.0, 1500.0))

        val imageToTarget: Mat3
            get() {
                val k = Mat3.of(1500.0, 0.0, 2000.0, 0.0, 1500.0, 1500.0, 0.0, 0.0, 1.0)
                val columns = Mat3.of(
                    r[0, 0], r[0, 1], t.x,
                    r[1, 0], r[1, 1], t.y,
                    r[2, 0], r[2, 1], t.z
                )
                return requireNotNull((k * columns).inverse())
            }

        val footPoint: Vec2
            get() {
                val back = r.transpose() * t
                return Vec2(-back.x, -back.y)
            }
    }

    @Test
    fun aFrontalCameraStandsOverItsFootPoint() {
        val pose = Pose(0.0, 0.0, Vec3(0.3, -0.2, 3.0))

        val q = FootPoint.of(pose.imageToTarget, pose.intrinsics)!!

        assertThat(q.x).isWithin(1e-7).of(-0.3)
        assertThat(q.y).isWithin(1e-7).of(0.2)
    }

    @Test
    fun at30DegreesTheFootPointLiesOutsideTheFaceOnTheCameraSide() {
        // SyntheticFace.view's camera: turned about the vertical axis, 3.75 radii from the centre.
        val pose = Pose(30.0, 0.0, Vec3(0.0, 0.0, 3.75))

        val q = FootPoint.of(pose.imageToTarget, pose.intrinsics)!!

        assertThat(q.x).isWithin(1e-7).of(3.75 * sin(Math.toRadians(30.0)))
        assertThat(q.y).isWithin(1e-7).of(0.0)
    }

    @Test
    fun aGeneralPoseGivesTheFootOfItsPerpendicular() {
        val pose = Pose(20.0, -15.0, Vec3(0.1, 0.05, 2.5))

        val q = FootPoint.of(pose.imageToTarget, pose.intrinsics)!!

        assertThat(q.distanceTo(pose.footPoint)).isLessThan(1e-7)
    }

    @Test
    fun theScaleOfTheHomographyDoesNotMatter() {
        val pose = Pose(20.0, -15.0, Vec3(0.1, 0.05, 2.5))

        val q = FootPoint.of(pose.imageToTarget.scaled(-2.5), pose.intrinsics)!!

        assertThat(q.distanceTo(pose.footPoint)).isLessThan(1e-7)
    }
}
```

`LinesTest.kt`:

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LinesTest {

    private fun direction(degrees: Double) =
        Vec2(cos(Math.toRadians(degrees)), sin(Math.toRadians(degrees)))

    private fun normal(d: Vec2) = Vec2(-d.y, d.x)

    @Test
    fun theSignedDistanceHasTheSignOfTheCrossProduct() {
        val line = Line2(Vec2(0.0, 0.0), Vec2(2.0, 0.0))

        assertThat(line.signedDistanceTo(Vec2(0.5, 0.3))).isWithin(1e-12).of(0.3)
        assertThat(line.signedDistanceTo(Vec2(0.5, -0.3))).isWithin(1e-12).of(-0.3)
    }

    @Test
    fun aPointIsProjectedOntoTheLine() {
        val p = Line2.through(Vec2(0.0, 0.0), Vec2(1.0, 1.0)).project(Vec2(1.0, 0.0))

        assertThat(p.x).isWithin(1e-12).of(0.5)
        assertThat(p.y).isWithin(1e-12).of(0.5)
    }

    @Test
    fun threeLinesThroughOnePointMeetExactlyThere() {
        val p = Vec2(1.2, -0.1)
        val lines = listOf(10.0, 40.0, 75.0).map { direction(it) }.map { Line2(p + it * 0.7, it) }

        assertThat(CommonPoint.of(lines)!!.distanceTo(p)).isLessThan(1e-9)
    }

    @Test
    fun noisyLinesMeetNearThePoint() {
        val p = Vec2(1.2, -0.1)
        val shifts = listOf(0.002, -0.002, 0.001)
        val lines = listOf(10.0, 40.0, 75.0).mapIndexed { i, degrees ->
            val d = direction(degrees)
            Line2(p + d * 0.7 + normal(d) * shifts[i], d)
        }

        assertThat(CommonPoint.of(lines)!!.distanceTo(p)).isAtMost(0.005)
    }

    @Test
    fun linesCrossingAtTwoDegreesOrLessGiveNoPoint() {
        val a = Line2(Vec2(0.0, 0.0), direction(0.0))

        assertThat(CommonPoint.of(listOf(a, Line2(Vec2(0.0, 0.1), direction(1.5))))).isNull()
        assertThat(CommonPoint.of(listOf(a))).isNull()
        assertThat(CommonPoint.of(listOf(a, Line2(Vec2(0.0, 0.1), direction(3.0))))).isNotNull()
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FootPointTest*' --tests '*LinesTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'FootPoint'`, `'Line2'`, `'CommonPoint'`.

- [ ] **Step 3: Implementieren**

`FootPoint.kt`:

```kotlin
package de.dreier.mytargets.detection.geometry

/**
 * The foot of the perpendicular from the camera onto the face plane, in target
 * coordinates (arrow design, Verfahren step 2). In the rectified image an arrow
 * standing perpendicular in the face lies on a line through this point, and
 * its entry point is the end nearer it.
 *
 * It is the image under the homography of the vanishing point of the face
 * normal: the vanishing line is l = H^T (0, 0, 1), the vanishing point
 * v = (K K^T) l, and Q = H v.
 */
object FootPoint {

    /**
     * @param imageToTarget pixels of the original to target coordinates, any scale
     * @return null when the vanishing point or its image lies at infinity, which
     *         a visible face does not produce
     */
    fun of(imageToTarget: Mat3, intrinsics: CameraIntrinsics): Vec2? {
        val vanishingLine = imageToTarget.transpose() * Vec3(0.0, 0.0, 1.0)
        val vanishingPoint = NormalVanishingPoint.compute(vanishingLine, intrinsics) ?: return null
        return imageToTarget.mapPoint(vanishingPoint)
    }
}
```

`Lines.kt`:

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.abs
import kotlin.math.sin

/** A line through [point] along [direction], which is stored as a unit vector. */
class Line2(val point: Vec2, direction: Vec2) {

    val direction: Vec2 = run {
        val length = direction.length
        require(length > 0.0) { "a line needs a direction" }
        direction * (1.0 / length)
    }

    /**
     * The distance of [p] from the line, with the sign of
     * cross(direction, p - point): positive on the side the direction turns to
     * when rotated by +90 degrees.
     */
    fun signedDistanceTo(p: Vec2): Double {
        val d = p - point
        return direction.x * d.y - direction.y * d.x
    }

    fun project(p: Vec2): Vec2 {
        val d = p - point
        return point + direction * (direction.x * d.x + direction.y * d.y)
    }

    companion object {
        fun through(from: Vec2, to: Vec2) = Line2(from, to - from)
    }
}

/**
 * The point with the least sum of squared distances to a set of lines: where
 * arrows leaning the same way meet in the rectified image (arrow design,
 * Kandidatendiagnose).
 */
object CommonPoint {

    /**
     * Null for fewer than two lines, or when no two of them cross at more
     * than [minAngleDegrees], where the point is not determined.
     */
    fun of(lines: List<Line2>, minAngleDegrees: Double = 2.0): Vec2? {
        if (lines.size < 2) return null
        val minSine = sin(Math.toRadians(minAngleDegrees))
        val crossing = lines.indices.any { i ->
            (i + 1 until lines.size).any { j ->
                val a = lines[i].direction
                val b = lines[j].direction
                abs(a.x * b.y - a.y * b.x) > minSine
            }
        }
        if (!crossing) return null
        // The normal equations of sum (n . x - n . p)^2 with n the unit normal of each line.
        var a11 = 0.0
        var a12 = 0.0
        var a22 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        for (line in lines) {
            val nx = -line.direction.y
            val ny = line.direction.x
            val c = nx * line.point.x + ny * line.point.y
            a11 += nx * nx
            a12 += nx * ny
            a22 += ny * ny
            b1 += nx * c
            b2 += ny * c
        }
        val det = a11 * a22 - a12 * a12
        if (abs(det) < 1e-12) return null
        return Vec2((a22 * b1 - a12 * b2) / det, (a11 * b2 - a12 * b1) / det)
    }
}
```

- [ ] **Step 4: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FootPointTest*' --tests '*LinesTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/geometry/FootPoint.kt \
  detection/src/main/java/de/dreier/mytargets/detection/geometry/Lines.kt \
  detection/src/test/java/de/dreier/mytargets/detection/geometry/FootPointTest.kt \
  detection/src/test/java/de/dreier/mytargets/detection/geometry/LinesTest.kt
git commit -m "detection: the camera's foot point on the face and the common point of lines"
```

---

## Task 3: Der Fußpunkt aus dem Sidecar und die Korpusgeometrie

**Files:**
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CaptureMetadata.kt`
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruth.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruthTest.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/FootPointCorpusTest.kt`
- Modify (Korpus-Repo): `../MyTargets-corpus/README.md`

**Interfaces:**
- Consumes: `FootPoint.of` (Task 2), `CorpusLoader.load(root).entries`
- Produces: `Registration.cameraPositionFaceUnits: List<Double>?` — drei Werte, x rechts, y unten, z in die Auflage; null ohne `view`-Block

- [ ] **Step 1: Die fehlschlagenden Tests im Korpusmodul**

In `SidecarTruthTest.kt` ergänzen:

```kotlin
    private val identityHomography = "[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]"

    @Test
    fun readsTheCameraPositionOfTheView() {
        val json = """
            { "shots": [], "registration": {
                "imageToTarget": [$identityHomography],
                "view": { "focalLength35mm": 23.0, "cameraPositionFaceUnits": [0.998, 0.145, -3.303] }
            } }
        """.trimIndent()

        val registration = SidecarTruth.parse("a.jpg", json).registration!!

        assertThat(registration.cameraPositionFaceUnits).containsExactly(0.998, 0.145, -3.303).inOrder()
    }

    @Test
    fun aRegistrationWithoutAViewHasNoCameraPosition() {
        val json = """{ "shots": [], "registration": { "imageToTarget": [$identityHomography] } }"""

        assertThat(SidecarTruth.parse("a.jpg", json).registration!!.cameraPositionFaceUnits).isNull()
    }

    @Test
    fun aCameraPositionOfTheWrongLengthNamesThePhotograph() {
        val json = """
            { "shots": [], "registration": {
                "imageToTarget": [$identityHomography], "view": { "cameraPositionFaceUnits": [1.0, 2.0] }
            } }
        """.trimIndent()

        try {
            SidecarTruth.parse("a.jpg", json)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("a.jpg")
            assertThat(expected).hasMessageThat().contains("cameraPositionFaceUnits")
        }
    }
```

`identityHomography` heißt bewusst nicht wie die lokale Variable `identityRows` eines vorhandenen Tests, damit nichts verschattet wird.

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection-corpus:test --tests '*SidecarTruthTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'cameraPositionFaceUnits'`.

- [ ] **Step 3: Lesen**

In `CaptureMetadata.kt` den KDoc von `Registration` um einen Absatz ergänzen und das Feld anhängen:

```kotlin
/**
 * The registration the annotator arrived at, kept so the pipeline's own
 * registration can be checked against it independently of whether it finds any
 * arrows.
 *
 * [imageToTarget] holds the nine values of a 3x3 homography row by row, mapping
 * pixels of the EXIF-rotated original into target coordinates. [imagedCentre] is
 * where the face's centre lies in that image.
 *
 * [cameraPositionFaceUnits] is where register.py put the camera, in target units:
 * x right, y down, z into the face, so the camera sits at z < 0. Its x and y are
 * the camera's foot point on the face (arrow design, Verfahren step 2).
 */
data class Registration(
    val imageToTarget: List<Double>,
    val imagedCentre: ImagePoint?,
    val cameraPositionFaceUnits: List<Double>? = null
) {
```

In `SidecarTruth.kt` neben `RegistrationJson` die Klasse `ViewJson` anlegen und `RegistrationJson` erweitern:

```kotlin
    private class ViewJson {
        var cameraPositionFaceUnits: List<Double?>? = null
    }

    // The list elements are nullable because gson reads leniently: a trailing
    // comma in a hand edited list, [a, b,], comes back as [a, b, null].
    private class RegistrationJson {
        var imageToTarget: List<List<Double?>?>? = null
        var imagedCentre: List<Double?>? = null
        var view: ViewJson? = null
    }
```

In `readRegistration` vor dem `return` prüfen und das Feld übergeben:

```kotlin
        val camera = registration.view?.cameraPositionFaceUnits
        require(camera == null || (camera.size == 3 && null !in camera)) {
            "$imageName: cameraPositionFaceUnits must be three numbers, got $camera"
        }
        // Neither requireNoNulls() can throw any more; they only narrow the type.
        return Registration(
            imageToTarget = rows.requireNoNulls().flatMap { it.requireNoNulls() },
            imagedCentre = centre?.requireNoNulls()?.let { ImagePoint(it[0], it[1]) },
            cameraPositionFaceUnits = camera?.requireNoNulls()
        )
```

- [ ] **Step 4: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection-corpus:test`
Expected: PASS

- [ ] **Step 5: Der Korpustest des Fußpunkts**

`FootPointCorpusTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.FootPoint
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The foot point from the reference homography against register.py's pose
 * decomposition (arrow design, Tests, Korpusgeometrie). register.py normalises
 * the two columns of the pose separately, so the two ways differ by up to
 * 0.028 radii on the corpus; the bound is the search's window.
 */
class FootPointCorpusTest {

    @Test
    fun theFootPointAgreesWithRegisterPyWithinTheSearchWindow() {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        val root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)

        var checked = 0
        for (entry in CorpusLoader.load(root).entries) {
            if (entry.outOfScope != null) continue
            val registration = entry.registration ?: continue
            val camera = registration.cameraPositionFaceUnits ?: continue
            val focal = checkNotNull(entry.camera?.focalLength35mm) {
                "${entry.imageName}: a view block without a focal length"
            }
            val image = checkNotNull(entry.image) { "${entry.imageName}: no image block" }
            val turned = (image.exifOrientation ?: 1) in 5..8
            val width = if (turned) image.height else image.width
            val height = if (turned) image.width else image.height
            val intrinsics = CameraIntrinsics.from35mmEquivalent(width, height, focal)

            val q = checkNotNull(
                FootPoint.of(Mat3.of(*registration.imageToTarget.toDoubleArray()), intrinsics)
            ) { "${entry.imageName}: no foot point" }

            assertWithMessage("${entry.imageName}: foot point against register.py")
                .that(q.distanceTo(Vec2(camera[0], camera[1]))).isAtMost(0.04)
            checked++
        }
        // 23 photographs in scope carry a view block at the corpus state of 2026-09-11.
        assertThat(checked).isEqualTo(23)
    }
}
```

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FootPointCorpusTest*' --rerun -PDETECTION_CORPUS_DIR=../MyTargets-corpus`
Expected: PASS. Ohne `DETECTION_CORPUS_DIR` wird der Test übersprungen.

- [ ] **Step 6: Die README des Korpus**

In `../MyTargets-corpus/README.md` in der Tabelle *Zusätzliche Felder* nach der Zeile zu `registration.imageToTarget` einfügen:

```markdown
| `registration.view.cameraPositionFaceUnits` | Lage der Kamera in Auflageneinheiten (x rechts, y unten, z in die Auflage; die Kamera steht bei z < 0), aus Homographie und EXIF-Brennweite gerechnet. Die ersten beiden Werte sind der Fußpunkt, durch den im entzerrten Bild die Schäfte laufen |
```

Im Korpus-Repo committen, nicht pushen; das entscheidet der Nutzer:

```bash
git -C ../MyTargets-corpus add README.md
git -C ../MyTargets-corpus commit -m "README: name registration.view.cameraPositionFaceUnits in the field table"
```

- [ ] **Step 7: Commit**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CaptureMetadata.kt \
  detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruth.kt \
  detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruthTest.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/FootPointCorpusTest.kt
git commit -m "detection-corpus: read the camera position of the view; check the foot point against it"
```

---

## Task 4: Das Arbeitsbild

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/RectifiedFace.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/FacePainter.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/RectifiedFaceTest.kt`

**Interfaces:**
- Consumes: `FaceWarp.EXTENT`, `FaceWarp.warp(original: Mat, imageToTarget: Mat3, edge: Int = edgeFor(imageToTarget)): Mat`, `Mat3`
- Produces:
  - `class RectifiedFace(edge: Int, value: FloatArray, valid: BooleanArray)` mit `val edge: Int`, `val pxPerUnit: Double`, `fun nearest(x: Double, y: Double): Float`, `fun bilinear(x: Double, y: Double): Float` — beide `Float.NaN` außerhalb des Quadrats oder an ungültigen Pixeln
  - `RectifiedFace.fromWarped(warped: Mat, imageToTarget: Mat3, originalWidth: Int, originalHeight: Int): RectifiedFace`
  - Test-Helfer `class FacePainter(background: Float, edge: Int = 2200)` mit `ring(inner, outer, v)`, `stripe(from: Vec2, to: Vec2, width: Double, v: Float)`, `below(y: Double, v: Float)`, `invalidLeftOf(x: Double)`, `build(): RectifiedFace`

- [ ] **Step 1: Den Test-Helfer anlegen**

`FacePainter.kt` (Testquellen):

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.FaceWarp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rectified faces painted pixel by pixel, for the tests that need no OpenCV.
 * [edge] px span [-1.1, 1.1]^2 like FaceWarp's square; the default gives
 * 1000 px per spot radius, the resolution radial.py and tips.py worked at.
 */
class FacePainter(background: Float, private val edge: Int = 2200) {

    private val value = FloatArray(edge * edge) { background }
    private val valid = BooleanArray(edge * edge) { true }
    private val k = edge / (2.0 * FaceWarp.EXTENT)

    private fun centre(i: Int) = (i + 0.5) / k - FaceWarp.EXTENT

    private fun pixel(t: Double) = k * (t + FaceWarp.EXTENT) - 0.5

    /** Calls [action] for every pixel in the box whose centre satisfies [inside]. */
    private fun each(
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        inside: (Double, Double) -> Boolean,
        action: (Int) -> Unit
    ) {
        val i0 = max(0, floor(pixel(xMin)).toInt())
        val i1 = min(edge - 1, ceil(pixel(xMax)).toInt())
        val j0 = max(0, floor(pixel(yMin)).toInt())
        val j1 = min(edge - 1, ceil(pixel(yMax)).toInt())
        for (j in j0..j1) {
            val y = centre(j)
            for (i in i0..i1) {
                if (inside(centre(i), y)) action(j * edge + i)
            }
        }
    }

    /** Paints the ring between [inner] and [outer]. */
    fun ring(inner: Double, outer: Double, v: Float) = apply {
        each(-outer, outer, -outer, outer, { x, y -> hypot(x, y).let { it >= inner && it < outer } }) {
            value[it] = v
        }
    }

    /** Paints a straight stripe of [width] from [from] to [to], cut square at both ends. */
    fun stripe(from: Vec2, to: Vec2, width: Double, v: Float) = apply {
        val half = width / 2
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        each(
            min(from.x, to.x) - half, max(from.x, to.x) + half,
            min(from.y, to.y) - half, max(from.y, to.y) + half,
            { x, y ->
                val along = ((x - from.x) * dx + (y - from.y) * dy) / (length * length)
                val across = ((x - from.x) * dy - (y - from.y) * dx) / length
                along in 0.0..1.0 && abs(across) <= half
            }
        ) { value[it] = v }
    }

    /** Paints everything with y greater than [y]. */
    fun below(y: Double, v: Float) = apply {
        each(-FaceWarp.EXTENT, FaceWarp.EXTENT, y, FaceWarp.EXTENT, { _, py -> py > y }) { value[it] = v }
    }

    /** Marks every pixel with x less than [x] as showing no part of the photograph. */
    fun invalidLeftOf(x: Double) = apply {
        each(-FaceWarp.EXTENT, x, -FaceWarp.EXTENT, FaceWarp.EXTENT, { px, _ -> px < x }) { valid[it] = false }
    }

    fun build() = RectifiedFace(edge, value.copyOf(), valid.copyOf())
}
```

- [ ] **Step 2: Die fehlschlagenden Tests schreiben**

`RectifiedFaceTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class RectifiedFaceTest {

    @get:Rule
    val openCv = OpenCvRule()

    /**
     * A 200 x 150 photograph maps to target coordinates by (p - (100, 75)) / 100,
     * so it covers x in [-1, 0.99] and y in [-0.75, 0.74] of the square
     * [-1.1, 1.1]^2. FaceWarp gives it 220 px, 100 per unit.
     */
    private val imageToTarget = Mat3.of(0.01, 0.0, -1.0, 0.0, 0.01, -0.75, 0.0, 0.0, 1.0)

    /** BGR (100, 150, 200) with a black 21 x 21 square around the face centre. */
    private fun face(): RectifiedFace {
        val photo = Mat(150, 200, CvType.CV_8UC3, Scalar(100.0, 150.0, 200.0))
        try {
            Imgproc.rectangle(photo, Point(90.0, 65.0), Point(110.0, 85.0), Scalar(0.0, 0.0, 0.0), Imgproc.FILLED)
            val warped = FaceWarp.warp(photo, imageToTarget)
            try {
                return RectifiedFace.fromWarped(warped, imageToTarget, 200, 150)
            } finally {
                warped.release()
            }
        } finally {
            photo.release()
        }
    }

    @Test
    fun theBrightnessIsTheLargestChannel() {
        assertThat(face().nearest(0.5, 0.0).toDouble()).isWithin(1e-6).of(200.0 / 255.0)
    }

    @Test
    fun aBlackPixelOfThePhotographIsValid() {
        val v = face().nearest(0.0, 0.0)

        assertThat(v.isNaN()).isFalse()
        assertThat(v.toDouble()).isWithin(1e-6).of(0.0)
    }

    @Test
    fun aPixelOutsideThePhotographIsNotValid() {
        val face = face()

        assertThat(face.nearest(0.0, 0.9).isNaN()).isTrue()
        assertThat(face.nearest(-1.05, 0.0).isNaN()).isTrue()
    }

    @Test
    fun thePreImageMustLieWithinTheLastPixelCentre() {
        // Pixel centres sit at target (i + 0.5) / 100 - 1.1: pixel 208 at 0.985 has
        // its pre-image at x = 198.5, pixel 209 at 0.995 at x = 199.5 > 199.
        val face = face()

        assertThat(face.nearest(0.985, 0.0).isNaN()).isFalse()
        assertThat(face.nearest(0.995, 0.0).isNaN()).isTrue()
    }

    private fun centreOf(i: Int, edge: Int) = (i + 0.5) * (2.0 * FaceWarp.EXTENT / edge) - FaceWarp.EXTENT

    @Test
    fun bilinearReturnsThePixelAtItsCentreAndInterpolatesBetween() {
        val face = RectifiedFace(4, FloatArray(16) { it.toFloat() }, BooleanArray(16) { true })
        val y = centreOf(1, 4)

        assertThat(face.bilinear(centreOf(1, 4), y).toDouble()).isWithin(1e-5).of(5.0)
        assertThat(face.bilinear((centreOf(1, 4) + centreOf(2, 4)) / 2, y).toDouble()).isWithin(1e-5).of(5.5)
        assertThat(face.bilinear(1.2, 0.0).isNaN()).isTrue()
    }

    @Test
    fun bilinearIsNotANumberNextToAnInvalidPixel() {
        val valid = BooleanArray(16) { true }.also { it[6] = false }
        val face = RectifiedFace(4, FloatArray(16) { it.toFloat() }, valid)

        assertThat(face.bilinear((centreOf(1, 4) + centreOf(2, 4)) / 2, centreOf(1, 4)).isNaN()).isTrue()
        assertThat(face.nearest(centreOf(1, 4), centreOf(1, 4)).isNaN()).isFalse()
    }
}
```

- [ ] **Step 3: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RectifiedFaceTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'RectifiedFace'`.

- [ ] **Step 4: Implementieren**

`RectifiedFace.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.FaceWarp
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.floor
import kotlin.math.max

/**
 * The working image of the arrow search (arrow design, Verfahren step 1): the
 * brightness V = max(B, G, R) / 255 of the rectified face, one float per pixel,
 * and which pixels show the original at all. It spans [-EXTENT, EXTENT]^2 of
 * target coordinates like FaceWarp's square, with pixel centres where
 * FaceWarp.pixelOf puts them.
 */
class RectifiedFace(val edge: Int, private val value: FloatArray, private val valid: BooleanArray) {

    init {
        require(edge > 1) { "a face needs at least 2 x 2 pixels" }
        require(value.size == edge * edge && valid.size == edge * edge) {
            "expected ${edge * edge} pixels"
        }
    }

    /** Pixels per target unit. */
    val pxPerUnit: Double = edge / (2.0 * FaceWarp.EXTENT)

    private fun pixel(t: Double) = pxPerUnit * (t + FaceWarp.EXTENT) - 0.5

    /** V at the pixel nearest to (x, y); NaN off the square or on a pixel outside the original. */
    fun nearest(x: Double, y: Double): Float {
        val px = floor(pixel(x) + 0.5).toInt()
        val py = floor(pixel(y) + 0.5).toInt()
        if (px < 0 || py < 0 || px >= edge || py >= edge) return Float.NaN
        val i = py * edge + px
        return if (valid[i]) value[i] else Float.NaN
    }

    /** V between the four pixels around (x, y); NaN when any of them is off the square or invalid. */
    fun bilinear(x: Double, y: Double): Float {
        val fx = pixel(x)
        val fy = pixel(y)
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= edge || y0 + 1 >= edge) return Float.NaN
        val i = y0 * edge + x0
        if (!valid[i] || !valid[i + 1] || !valid[i + edge] || !valid[i + edge + 1]) return Float.NaN
        val ax = (fx - x0).toFloat()
        val ay = (fy - y0).toFloat()
        val top = value[i] * (1f - ax) + value[i + 1] * ax
        val bottom = value[i + edge] * (1f - ax) + value[i + edge + 1] * ax
        return top * (1f - ay) + bottom * ay
    }

    companion object {

        /**
         * From [warped], the original rectified by FaceWarp.warp with
         * [imageToTarget]. The original was [originalWidth] x [originalHeight].
         */
        fun fromWarped(warped: Mat, imageToTarget: Mat3, originalWidth: Int, originalHeight: Int): RectifiedFace {
            require(warped.type() == CvType.CV_8UC3) { "expected 8-bit BGR, got type ${warped.type()}" }
            require(warped.rows() == warped.cols()) {
                "the rectified face is square, got ${warped.cols()} x ${warped.rows()}"
            }
            val toImage = requireNotNull(imageToTarget.inverse()) { "the homography is singular" }
            val edge = warped.cols()
            val bytes = ByteArray(edge * edge * 3)
            warped.get(0, 0, bytes)
            val value = FloatArray(edge * edge) { i ->
                val b = bytes[3 * i].toInt() and 0xFF
                val g = bytes[3 * i + 1].toInt() and 0xFF
                val r = bytes[3 * i + 2].toInt() and 0xFF
                max(b, max(g, r)) / 255f
            }
            return RectifiedFace(edge, value, validity(toImage, edge, originalWidth, originalHeight))
        }

        /**
         * A pixel is valid when its pre-image under H^-1 lies in
         * [0, width - 1] x [0, height - 1], so the warp's bilinear sampling never
         * reached the black border, and when its homogeneous coordinate has the
         * sign of the face centre's: H^-1 is known only up to a factor, and a
         * point behind the camera has the other sign.
         */
        private fun validity(toImage: Mat3, edge: Int, width: Int, height: Int): BooleanArray {
            val k = edge / (2.0 * FaceWarp.EXTENT)
            val m00 = toImage[0, 0]
            val m01 = toImage[0, 1]
            val m02 = toImage[0, 2]
            val m10 = toImage[1, 0]
            val m11 = toImage[1, 1]
            val m12 = toImage[1, 2]
            val m20 = toImage[2, 0]
            val m21 = toImage[2, 1]
            val m22 = toImage[2, 2]
            // The face centre, target (0, 0), has the homogeneous coordinate m22.
            val sign = if (m22 >= 0.0) 1.0 else -1.0
            val valid = BooleanArray(edge * edge)
            for (j in 0 until edge) {
                val ty = (j + 0.5) / k - FaceWarp.EXTENT
                for (i in 0 until edge) {
                    val tx = (i + 0.5) / k - FaceWarp.EXTENT
                    val w = m20 * tx + m21 * ty + m22
                    if (w * sign <= 0.0) continue
                    val x = (m00 * tx + m01 * ty + m02) / w
                    val y = (m10 * tx + m11 * ty + m12) / w
                    valid[j * edge + i] = x >= 0.0 && x <= width - 1.0 && y >= 0.0 && y <= height - 1.0
                }
            }
            return valid
        }
    }
}
```

- [ ] **Step 5: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RectifiedFaceTest*' --tests '*OpenCvImportRuleTest*'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/RectifiedFace.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/FacePainter.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/RectifiedFaceTest.kt
git commit -m "detection: the rectified brightness and which pixels show the photograph"
```

---

## Task 5: Flankenkontrast und Läufe

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/FlankContrast.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/Runs.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/FlankContrastTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/RunsTest.kt`

**Interfaces:**
- Consumes: `RectifiedFace` (Task 4), `FacePainter` (Task 4, Test)
- Produces:
  - `class FlankContrast` mit `fun profile(face: RectifiedFace, origin: Vec2, direction: Vec2, offset: Double, along: DoubleArray): DoubleArray` und `companion val SEARCH`, `val WALK`
  - `class Run(val first: Int, val last: Int, val darkSamples: Int)` mit `val span: Int`, `val fill: Double`
  - `object Runs { fun find(contrast: DoubleArray, threshold: Double, maxIndexGap: Int): List<Run> }`
  - `object RunRules { fun keep(run: Run, length: Double, radii: DoubleArray, ringRadii: List<Double>): Boolean }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`FlankContrastTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test

class FlankContrastTest {

    private val along = doubleArrayOf(0.0, 0.1, 0.2)
    private val horizontal = Vec2(1.0, 0.0)
    private val vertical = Vec2(0.0, 1.0)

    @Test
    fun aDarkStripeIsDarkerThanBothFlanks() {
        val face = FacePainter(0.9f).stripe(Vec2(-0.5, 0.0), Vec2(0.5, 0.0), 0.012, 0.1f).build()

        for (profile in listOf(
            FlankContrast.SEARCH.profile(face, Vec2(-0.3, 0.0), horizontal, 0.0, along),
            FlankContrast.WALK.profile(face, Vec2(-0.3, 0.0), horizontal, 0.0, along)
        )) {
            for (v in profile) assertThat(v).isWithin(1e-5).of(0.8)
        }
    }

    @Test
    fun aGreyStripeOnBlackIsLighterThanBothFlanks() {
        val face = FacePainter(0.1f).stripe(Vec2(-0.5, 0.0), Vec2(0.5, 0.0), 0.012, 0.5f).build()

        for (v in FlankContrast.SEARCH.profile(face, Vec2(-0.3, 0.0), horizontal, 0.0, along)) {
            assertThat(v).isWithin(1e-5).of(0.4)
        }
    }

    @Test
    fun anEdgeBetweenTwoAreasHasNoContrast() {
        val face = FacePainter(0.9f).below(0.0, 0.3f).build()

        for (v in FlankContrast.SEARCH.profile(face, Vec2(-0.3, 0.0), horizontal, 0.0, along)) {
            assertThat(v).isWithin(1e-5).of(0.0)
        }
    }

    @Test
    fun anOffsetShiftsTheLineToTheLeftOfItsDirection() {
        // The left normal of (1, 0) is (0, 1): an offset of 0.05 puts the line at y = 0.05.
        val face = FacePainter(0.9f).stripe(Vec2(-0.5, 0.05), Vec2(0.5, 0.05), 0.012, 0.1f).build()

        assertThat(FlankContrast.SEARCH.profile(face, Vec2(-0.3, 0.0), horizontal, 0.05, along)[1])
            .isWithin(1e-5).of(0.8)
    }

    @Test
    fun aSampleTouchingAnInvalidPixelHasNoContrast() {
        val stripe = FacePainter(0.9f).stripe(Vec2(0.0, -0.5), Vec2(0.0, 0.5), 0.012, 0.1f)
        val withEdge = FacePainter(0.9f).stripe(Vec2(0.0, -0.5), Vec2(0.0, 0.5), 0.012, 0.1f).invalidLeftOf(-0.01)

        assertThat(FlankContrast.SEARCH.profile(stripe.build(), Vec2(0.0, -0.3), vertical, 0.0, along)[0])
            .isWithin(1e-5).of(0.8)
        // The flank on the side of x < 0 lies at x = -0.016, beyond the edge of the photograph.
        assertThat(FlankContrast.SEARCH.profile(withEdge.build(), Vec2(0.0, -0.3), vertical, 0.0, along)[0])
            .isWithin(1e-9).of(0.0)
    }
}
```

`RunsTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunsTest {

    private val rings = listOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)

    @Test
    fun gapsUpToTheLimitAreBridged() {
        val contrast = doubleArrayOf(0.0, 0.2, 0.2, 0.0, 0.2)

        val apart = Runs.find(contrast, 0.15, 1)
        assertThat(apart.map { it.first to it.last }).containsExactly(1 to 2, 4 to 4).inOrder()

        val joined = Runs.find(contrast, 0.15, 2).single()
        assertThat(joined.first to joined.last).isEqualTo(1 to 4)
        assertThat(joined.darkSamples).isEqualTo(3)
        assertThat(joined.fill).isWithin(1e-12).of(0.75)
    }

    @Test
    fun theThresholdIsStrict() {
        assertThat(Runs.find(doubleArrayOf(0.15, 0.15), 0.15, 10)).isEmpty()
    }

    @Test
    fun tenMissingStepsOfPointZeroZeroTwoAreStillOneRun() {
        // radial.py: a gap of 0.02 at steps of 0.002 is ten indices.
        val contrast = DoubleArray(30).also { it[0] = 0.5; it[10] = 0.5; it[22] = 0.5 }

        assertThat(Runs.find(contrast, 0.15, 10).map { it.first to it.last })
            .containsExactly(0 to 10, 22 to 22).inOrder()
    }

    private fun run(span: Int, dark: Int) = Run(0, span - 1, dark)

    @Test
    fun aLongFullRunAwayFromTheRingsIsKept() {
        assertThat(RunRules.keep(run(50, 45), 0.098, DoubleArray(50) { 0.35 }, rings)).isTrue()
    }

    @Test
    fun aShortOrSparseRunIsDropped() {
        assertThat(RunRules.keep(run(50, 45), 0.07, DoubleArray(50) { 0.35 }, rings)).isFalse()
        assertThat(RunRules.keep(run(50, 25), 0.098, DoubleArray(50) { 0.35 }, rings)).isFalse()
    }

    @Test
    fun aRunWhollyOffTheFaceIsDropped() {
        assertThat(RunRules.keep(run(50, 45), 0.098, DoubleArray(50) { 1.03 }, rings)).isFalse()
    }

    @Test
    fun aRunAlongARingLineIsDropped() {
        // More than 40 % of the samples within 0.009 of ring 0.4 is a ring line, 40 % is not yet.
        val half = DoubleArray(50) { if (it < 25) 0.405 else 0.35 }
        val forty = DoubleArray(50) { if (it < 20) 0.405 else 0.35 }

        assertThat(RunRules.keep(run(50, 45), 0.098, half, rings)).isFalse()
        assertThat(RunRules.keep(run(50, 45), 0.098, forty, rings)).isTrue()
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FlankContrastTest*' --tests '*RunsTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'FlankContrast'`, `'Runs'`, `'Run'`, `'RunRules'`.

- [ ] **Step 3: Implementieren**

`FlankContrast.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * How much darker a line is than both its flanks, or how much lighter, per
 * sample along it (arrow design, Verfahren steps 3 and 4). A dark shaft on a
 * coloured ring is darker than both flanks; a grey shaft on the black ring is
 * lighter than both. An edge between two areas is neither, because one flank is
 * as dark as the line. The contrast is local, so it needs no correction of the
 * lighting.
 */
class FlankContrast private constructor(
    private val centreOffsets: DoubleArray,
    private val flank: Double,
    private val bilinear: Boolean
) {

    /**
     * The contrast at origin + s * [direction] for each s in [along], on the
     * line shifted by [offset] along the left normal (-direction.y, direction.x).
     * [direction] must be a unit vector. A sample that touches a pixel outside
     * the photograph has contrast 0.
     */
    fun profile(
        face: RectifiedFace,
        origin: Vec2,
        direction: Vec2,
        offset: Double,
        along: DoubleArray
    ): DoubleArray {
        val ux = direction.x
        val uy = direction.y
        val nx = -uy
        val ny = ux
        val out = DoubleArray(along.size)
        for (k in along.indices) {
            val bx = origin.x + offset * nx + along[k] * ux
            val by = origin.y + offset * ny + along[k] * uy
            var lowest = Double.MAX_VALUE
            var highest = -Double.MAX_VALUE
            var inside = true
            for (c in centreOffsets) {
                val v = sample(face, bx + c * nx, by + c * ny)
                if (v.isNaN()) {
                    inside = false
                    break
                }
                lowest = min(lowest, v)
                highest = max(highest, v)
            }
            if (!inside) continue
            val left = sample(face, bx - flank * nx, by - flank * ny)
            val right = sample(face, bx + flank * nx, by + flank * ny)
            if (left.isNaN() || right.isNaN()) continue
            val darker = min(left, right) - lowest
            val lighter = highest - max(left, right)
            out[k] = max(darker, lighter)
        }
        return out
    }

    private fun sample(face: RectifiedFace, x: Double, y: Double): Double =
        (if (bilinear) face.bilinear(x, y) else face.nearest(x, y)).toDouble()

    companion object {
        /** radial.py: three lines at -0.003, 0, +0.003, flanks at 0.016, the nearest pixel. */
        val SEARCH = FlankContrast(doubleArrayOf(-0.003, 0.0, 0.003), 0.016, bilinear = false)

        /** tips.py: seven lines from -0.003 to +0.003, flanks at 0.012, bilinear. */
        val WALK = FlankContrast(DoubleArray(7) { -0.003 + 0.001 * it }, 0.012, bilinear = true)
    }
}
```

`Runs.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import kotlin.math.abs

/** Samples [first] to [last] along a line, of which [darkSamples] lie above the threshold. */
class Run(val first: Int, val last: Int, val darkSamples: Int) {
    val span: Int
        get() = last - first + 1

    val fill: Double
        get() = darkSamples.toDouble() / span
}

object Runs {

    /**
     * radial.py: the samples whose contrast exceeds [threshold], joined into one
     * run while consecutive ones are at most [maxIndexGap] apart.
     */
    fun find(contrast: DoubleArray, threshold: Double, maxIndexGap: Int): List<Run> {
        val runs = ArrayList<Run>()
        var first = -1
        var last = -1
        var dark = 0
        for (i in contrast.indices) {
            if (contrast[i] <= threshold) continue
            if (first >= 0 && i - last <= maxIndexGap) {
                last = i
                dark++
            } else {
                if (first >= 0) runs += Run(first, last, dark)
                first = i
                last = i
                dark = 1
            }
        }
        if (first >= 0) runs += Run(first, last, dark)
        return runs
    }
}

/** radial.py's rules for one run (arrow design, Verfahren step 3). */
object RunRules {
    const val MIN_LENGTH = 0.08
    const val MIN_FILL = 0.6
    const val OFF_FACE = 1.02
    const val NEAR_RING = 0.009
    const val MAX_RING_SHARE = 0.4

    /**
     * Keeps a run at least MIN_LENGTH long and MIN_FILL full, not wholly beyond
     * radius OFF_FACE, and with at most MAX_RING_SHARE of its samples closer than
     * NEAR_RING to a ring line. [radii] holds the distance from the face centre of
     * every sample of the line the run lies on.
     */
    fun keep(run: Run, length: Double, radii: DoubleArray, ringRadii: List<Double>): Boolean {
        if (length < MIN_LENGTH || run.fill < MIN_FILL) return false
        var nearest = Double.MAX_VALUE
        var onRing = 0
        for (i in run.first..run.last) {
            val r = radii[i]
            if (r < nearest) nearest = r
            if (ringRadii.any { abs(r - it) < NEAR_RING }) onRing++
        }
        if (nearest > OFF_FACE) return false
        return onRing.toDouble() / run.span <= MAX_RING_SHARE
    }
}
```

- [ ] **Step 4: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FlankContrastTest*' --tests '*RunsTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/FlankContrast.kt \
  detection/src/main/java/de/dreier/mytargets/detection/arrows/Runs.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/FlankContrastTest.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/RunsTest.kt
git commit -m "detection: flank contrast along a line, and the runs radial.py keeps"
```

---

## Task 6: Die Schaftsuche

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/Numbers.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/ShaftSearch.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ShaftSearchTest.kt`

**Interfaces:**
- Consumes: `RectifiedFace`, `FacePainter` (Task 4), `FlankContrast.SEARCH`, `Runs`, `RunRules` (Task 5)
- Produces:
  - `internal object Numbers { fun steps(from: Double, until: Double, step: Double): DoubleArray; fun median(values: DoubleArray): Double }`
  - `class SearchRun(val tip: Vec2, val far: Vec2, val contrast: Double, val score: Double)` mit `val length: Double`
  - `object ShaftSearch { fun find(face: RectifiedFace, foot: Vec2, ringRadii: List<Double>): List<SearchRun> }`, dazu `internal fun dropDuplicates(byScore: List<SearchRun>): List<SearchRun>` und `internal fun merge(runs: List<SearchRun>, foot: Vec2): List<SearchRun>`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`ShaftSearchTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShaftSearchTest {

    private val foot = Vec2(1.3, 0.0)
    private val rings = listOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)

    /** The point [distance] from [entry], away from the foot point. */
    private fun away(entry: Vec2, distance: Double): Vec2 {
        val d = entry - foot
        return entry + d * (distance / d.length)
    }

    private fun rotate(v: Vec2, degrees: Double): Vec2 {
        val a = Math.toRadians(degrees)
        return Vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
    }

    @Test
    fun aShaftThroughTheFootPointIsFoundOnceWithItsEntryNearerTheFootPoint() {
        val entry = Vec2(0.2, 0.1)
        val face = FacePainter(0.9f).stripe(entry, away(entry, 0.5), 0.012, 0.15f).build()

        val runs = ShaftSearch.find(face, foot, rings)

        assertThat(runs).hasSize(1)
        assertThat(runs[0].tip.distanceTo(entry)).isAtMost(0.01)
        assertThat(runs[0].far.distanceTo(away(entry, 0.5))).isAtMost(0.01)
    }

    @Test
    fun aStripeThatDoesNotPointAtTheFootPointIsNotFound() {
        val start = Vec2(0.1, -0.05)
        val turned = rotate((start - foot) * (1.0 / (start - foot).length), 30.0)
        val face = FacePainter(0.9f).stripe(start, start + turned * 0.3, 0.014, 0.25f).build()

        assertThat(ShaftSearch.find(face, foot, rings)).isEmpty()
    }

    @Test
    fun printedRingLinesAreNotShafts() {
        val painter = FacePainter(0.9f)
        for (r in rings) painter.ring(r - 0.0015, r + 0.0015, 0.2f)

        assertThat(ShaftSearch.find(painter.build(), foot, rings)).isEmpty()
    }

    @Test
    fun twoPiecesOfOneShaftAreJoinedWithTheEntryOfTheNearerOne() {
        val entry = Vec2(-0.2, 0.05)
        val face = FacePainter(0.9f)
            .stripe(entry, away(entry, 0.2), 0.012, 0.15f)
            .stripe(away(entry, 0.4), away(entry, 0.7), 0.012, 0.15f)
            .build()

        val runs = ShaftSearch.find(face, foot, rings)

        assertThat(runs).hasSize(1)
        assertThat(runs[0].tip.distanceTo(entry)).isAtMost(0.01)
        assertThat(runs[0].far.distanceTo(away(entry, 0.7))).isAtMost(0.01)
    }

    @Test
    fun aGreyShaftOnTheBlackRingIsFound() {
        val entry = Vec2(-0.65, 0.2)
        val face = FacePainter(0.9f)
            .ring(0.6, 0.8, 0.12f)
            .stripe(entry, away(entry, 0.4), 0.012, 0.59f)
            .build()

        val runs = ShaftSearch.find(face, foot, rings)

        assertThat(runs).hasSize(1)
        assertThat(runs[0].tip.distanceTo(entry)).isAtMost(0.01)
    }

    private fun parallel(y: Double, score: Double) = SearchRun(Vec2(0.0, y), Vec2(-0.5, y), 0.5, score)

    @Test
    fun parallelShaftsThreeHundredthsApartStayTwoAndTwoHundredthsApartBecomeOne() {
        // The limit of the duplicate rule, 0.025 across.
        assertThat(ShaftSearch.dropDuplicates(listOf(parallel(0.0, 0.3), parallel(0.03, 0.2)))).hasSize(2)
        assertThat(ShaftSearch.dropDuplicates(listOf(parallel(0.0, 0.3), parallel(0.02, 0.2)))).hasSize(1)
    }

    @Test
    fun mergingTakesTheEndsNearestAndFarthestFromTheFootPointAndAddsTheScores() {
        val near = SearchRun(Vec2(0.0, 0.0), Vec2(-0.2, 0.0), 0.5, 0.1)
        val beyond = SearchRun(Vec2(-0.45, 0.0), Vec2(-0.8, 0.0), 0.4, 0.14)

        val merged = ShaftSearch.merge(listOf(near, beyond), foot).single()

        assertThat(merged.tip).isEqualTo(Vec2(0.0, 0.0))
        assertThat(merged.far).isEqualTo(Vec2(-0.8, 0.0))
        assertThat(merged.score).isWithin(1e-12).of(0.24)
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ShaftSearchTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'ShaftSearch'`, `'SearchRun'`.

- [ ] **Step 3: Implementieren**

`Numbers.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import kotlin.math.ceil
import kotlin.math.max

internal object Numbers {

    /**
     * from, from + step, ... below [until], like numpy's arange. The count is
     * rounded against floating noise, so steps(-0.1, 0.035, 0.0005) has 270
     * values as in tips.py.
     */
    fun steps(from: Double, until: Double, step: Double): DoubleArray {
        val n = ceil((until - from) / step - 1e-9).toInt()
        return DoubleArray(max(0, n)) { from + it * step }
    }

    /** The median as numpy computes it: the mean of the two middle values for an even count; NaN for none. */
    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }
}
```

`ShaftSearch.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** One shaft the search found: [tip] is the end nearer the foot point, [far] the other. */
class SearchRun(val tip: Vec2, val far: Vec2, val contrast: Double, val score: Double) {
    val length: Double
        get() = tip.distanceTo(far)
}

/**
 * radial.py (arrow design, Verfahren step 3). In the rectified image an arrow
 * standing perpendicular in the face lies on a line through the camera's foot
 * point Q, with its entry point at the end nearer Q. The search sweeps lines
 * through Q, with small sideways offsets for arrows that lean a little, and
 * keeps the stretches darker than both flanks -- or lighter, for a grey shaft on
 * the black ring. A shadow does not point at Q and crosses these lines only
 * briefly.
 */
object ShaftSearch {

    const val ANGLE_STEP = 0.25
    const val SAMPLE_STEP = 0.002
    const val MAX_REACH = 3.5
    const val SQUARE = 1.05
    val OFFSETS = DoubleArray(17) { -0.04 + 0.005 * it }
    const val THRESHOLD = 0.15
    const val MAX_GAP = 0.02
    const val DUPLICATE_ANGLE = 6.0
    const val DUPLICATE_DISTANCE = 0.025
    const val DUPLICATE_SLACK = 0.05
    const val MERGE_ANGLE = 2.5
    const val MERGE_DISTANCE = 0.02
    const val MERGE_GAP = 0.3
    const val KEEP = 20

    /** At most KEEP runs, the best scored first. */
    fun find(face: RectifiedFace, foot: Vec2, ringRadii: List<Double>): List<SearchRun> {
        val swept = sweep(face, foot, ringRadii).sortedByDescending { it.score }
        return merge(dropDuplicates(swept), foot).sortedByDescending { it.score }.take(KEEP)
    }

    private fun sweep(face: RectifiedFace, foot: Vec2, ringRadii: List<Double>): List<SearchRun> {
        val maxIndexGap = (MAX_GAP / SAMPLE_STEP).roundToInt()
        val reach = (MAX_REACH / SAMPLE_STEP).roundToInt()
        val directions = (360.0 / ANGLE_STEP).roundToInt()
        val found = ArrayList<SearchRun>()
        for (a in 0 until directions) {
            val theta = Math.toRadians(a * ANGLE_STEP)
            val u = Vec2(cos(theta), sin(theta))
            val normal = Vec2(-u.y, u.x)
            // The stretch of the ray from Q that lies in the square; the square is
            // convex, so it is one piece.
            var first = -1
            var last = -1
            for (i in 0 until reach) {
                val d = i * SAMPLE_STEP
                if (abs(foot.x + d * u.x) < SQUARE && abs(foot.y + d * u.y) < SQUARE) {
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first < 0) continue
            val along = DoubleArray(last - first + 1) { (first + it) * SAMPLE_STEP }
            for (offset in OFFSETS) {
                val contrast = FlankContrast.SEARCH.profile(face, foot, u, offset, along)
                val runs = Runs.find(contrast, THRESHOLD, maxIndexGap)
                if (runs.isEmpty()) continue
                val base = foot + normal * offset
                val radii = DoubleArray(along.size) { hypot(base.x + along[it] * u.x, base.y + along[it] * u.y) }
                for (run in runs) {
                    val length = along[run.last] - along[run.first]
                    if (!RunRules.keep(run, length, radii, ringRadii)) continue
                    val dark = (run.first..run.last)
                        .filter { contrast[it] > THRESHOLD }
                        .map { contrast[it] }
                        .toDoubleArray()
                    val median = Numbers.median(dark)
                    found += SearchRun(
                        tip = base + u * along[run.first],
                        far = base + u * along[run.last],
                        contrast = median,
                        score = length * median
                    )
                }
            }
        }
        return found
    }

    /**
     * radial.py: in order of score, drops a run that repeats a kept one -- within
     * DUPLICATE_ANGLE of its direction, both ends closer than DUPLICATE_DISTANCE
     * to its line, and overlapping it along the line with DUPLICATE_SLACK to spare.
     */
    internal fun dropDuplicates(byScore: List<SearchRun>): List<SearchRun> {
        val cosLimit = cos(Math.toRadians(DUPLICATE_ANGLE))
        val kept = ArrayList<SearchRun>()
        for (run in byScore) {
            val u = unit(run)
            val duplicate = kept.any { other ->
                val u2 = unit(other)
                if (abs(dot(u, u2)) < cosLimit) return@any false
                val n2 = Vec2(-u2.y, u2.x)
                if (abs(dot(run.tip - other.tip, n2)) >= DUPLICATE_DISTANCE) return@any false
                if (abs(dot(run.far - other.tip, n2)) >= DUPLICATE_DISTANCE) return@any false
                val a = dot(run.tip - other.tip, u2)
                val b = dot(run.far - other.tip, u2)
                maxOf(a, b) > -DUPLICATE_SLACK && minOf(a, b) < other.length + DUPLICATE_SLACK
            }
            if (!duplicate) kept += run
        }
        return kept
    }

    /**
     * radial.py: joins collinear pieces of one shaft, split by the black ring or a
     * crossing shaft -- within MERGE_ANGLE, both ends closer than MERGE_DISTANCE to
     * the line, at most MERGE_GAP apart along it. The joined run keeps the end
     * nearest Q as its entry point and the scores add up.
     */
    internal fun merge(runs: List<SearchRun>, foot: Vec2): List<SearchRun> {
        val cosLimit = cos(Math.toRadians(MERGE_ANGLE))
        val merged = ArrayList<SearchRun>()
        for (run in runs) {
            val u = unit(run)
            val index = merged.indexOfFirst { m ->
                val u2 = unit(m)
                if (abs(dot(u, u2)) < cosLimit) return@indexOfFirst false
                val n2 = Vec2(-u2.y, u2.x)
                if (abs(dot(run.tip - m.tip, n2)) > MERGE_DISTANCE) return@indexOfFirst false
                if (abs(dot(run.far - m.tip, n2)) > MERGE_DISTANCE) return@indexOfFirst false
                val a = dot(run.tip - m.tip, u2)
                val b = dot(run.far - m.tip, u2)
                !(b < -MERGE_GAP || a > m.length + MERGE_GAP)
            }
            if (index < 0) {
                merged += run
                continue
            }
            val m = merged[index]
            val u2 = unit(m)
            val ends = listOf(m.tip, m.far, run.tip, run.far)
            merged[index] = SearchRun(
                tip = ends.minBy { dot(it - foot, u2) },
                far = ends.maxBy { dot(it - foot, u2) },
                contrast = m.contrast,
                score = m.score + run.score
            )
        }
        return merged
    }

    private fun unit(run: SearchRun): Vec2 = (run.far - run.tip) * (1.0 / run.length)

    private fun dot(a: Vec2, b: Vec2) = a.x * b.x + a.y * b.y
}
```

- [ ] **Step 4: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ShaftSearchTest*'`
Expected: PASS. Jeder Test mit gemaltem Bild fegt 1440 · 17 Linien und braucht rund eine Sekunde.

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/Numbers.kt \
  detection/src/main/java/de/dreier/mytargets/detection/arrows/ShaftSearch.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/ShaftSearchTest.kt
git commit -m "detection: search shafts as lines through the camera's foot point (radial.py)"
```

---

## Task 7: Der Schaftlauf

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/TipWalk.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/TipWalkTest.kt`

**Interfaces:**
- Consumes: `RectifiedFace`, `FacePainter` (Task 4), `FlankContrast.WALK` (Task 5), `Numbers` (Task 6), `Line2` (Task 2)
- Produces:
  - `enum class TipRefinement { REFINED, NO_SHAFT, RAN_OUT }`
  - `class WalkResult(val tip: Vec2, val line: Line2, val shaftContrast: Double, val refinement: TipRefinement)`
  - `object TipWalk { fun walk(face: RectifiedFace, seed: Vec2, direction: Vec2): WalkResult }` — `direction` zeigt von der Nocke zum Einschuss

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`TipWalkTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TipWalkTest {

    private val foot = Vec2(1.3, 0.0)
    private val entry = Vec2(0.2, 0.1)

    /** From the entry towards the nock. */
    private val outward = (entry - foot) * (1.0 / (entry - foot).length)
    private val across = Vec2(-outward.y, outward.x)

    private fun rotate(v: Vec2, degrees: Double): Vec2 {
        val a = Math.toRadians(degrees)
        return Vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
    }

    /** Towards the entry, three degrees off, as a seed from the search can be. */
    private val seedDirection = rotate(outward * -1.0, 3.0)

    private fun shaft() = FacePainter(0.9f).stripe(entry, entry + outward * 0.4, 0.012, 0.15f).build()

    @Test
    fun findsTheEndOfTheShaft() {
        val seed = entry + outward * 0.01 + across * 0.004

        val result = TipWalk.walk(shaft(), seed, seedDirection)

        assertThat(result.refinement).isEqualTo(TipRefinement.REFINED)
        assertThat(result.tip.distanceTo(entry)).isAtMost(0.002)
        assertThat(abs(result.line.signedDistanceTo(entry + outward * 0.3))).isAtMost(0.002)
    }

    @Test
    fun withoutAShaftTheSeedStays() {
        val result = TipWalk.walk(FacePainter(0.9f).build(), entry, seedDirection)

        assertThat(result.refinement).isEqualTo(TipRefinement.NO_SHAFT)
        assertThat(result.tip).isEqualTo(entry)
    }

    @Test
    fun aShaftRunningPastTheWindowRunsOutOnTheRefinedLine() {
        // The seed sits 0.05 behind the end; the walk looks only 0.035 ahead.
        val seed = entry + outward * 0.05

        val result = TipWalk.walk(shaft(), seed, seedDirection)

        assertThat(result.refinement).isEqualTo(TipRefinement.RAN_OUT)
        assertThat(result.tip.distanceTo(seed)).isAtMost(0.002)
        assertThat(abs(result.line.signedDistanceTo(entry))).isAtMost(0.002)
    }

    @Test
    fun aShortGapReachingTheEndOfTheWindowStillEndsTheShaftBeforeIt() {
        // The end lies 0.03 ahead of the seed. After it only 0.005 of the window
        // remain, less than the 0.012 an end needs, but the gap reaches the end of
        // the window, and tips.py ends the shaft there.
        val seed = entry + outward * 0.03

        val result = TipWalk.walk(shaft(), seed, seedDirection)

        assertThat(result.refinement).isEqualTo(TipRefinement.REFINED)
        assertThat(result.tip.distanceTo(entry)).isAtMost(0.002)
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*TipWalkTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'TipWalk'`, `'TipRefinement'`.

- [ ] **Step 3: Implementieren**

`TipWalk.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.geometry.Line2
import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class TipRefinement {
    /** The walk found the shaft and where it ends. */
    REFINED,

    /** The walk saw no shaft behind the seed; the entry point is the search's. */
    NO_SHAFT,

    /** The walk found the shaft but no end within its window; the entry point is the search's, on the refined line. */
    RAN_OUT
}

/**
 * One walked shaft. [line] is the refined line when the walk saw a shaft
 * (REFINED, RAN_OUT) and the search's line for NO_SHAFT; [tip] lies on it.
 */
class WalkResult(val tip: Vec2, val line: Line2, val shaftContrast: Double, val refinement: TipRefinement)

/**
 * tips.py's refine (arrow design, Verfahren step 4): fit the line of the shaft
 * behind the seed, then walk along it until the shaft stops being darker (or
 * lighter) than its flanks.
 */
object TipWalk {

    const val BEHIND_FROM = -0.10
    const val BEHIND_TO = -0.012
    const val BEHIND_STEP = 0.0015
    const val ANGLE_WINDOW = 12
    const val OFFSET_STEPS = 6
    const val OFFSET_STEP = 0.002
    const val FINE_STEPS = 4
    const val FINE_ANGLE_STEP = 0.25
    const val FINE_OFFSET_STEP = 0.0005
    const val WALK_STEP = 0.0005
    const val AHEAD = 0.035
    const val SHAFT_FROM = -0.03
    const val END_FRACTION = 0.35
    const val END_GAP = 0.012
    const val NO_SHAFT_BELOW = 0.08

    /** @param direction from the nock towards the tip; any length */
    fun walk(face: RectifiedFace, seed: Vec2, direction: Vec2): WalkResult {
        val seedAngle = Math.toDegrees(atan2(direction.y, direction.x))
        val behind = Numbers.steps(BEHIND_FROM, BEHIND_TO, BEHIND_STEP)

        // Robust mean of the contrast behind the seed: the lowest quarter is
        // dropped so gaps and crossing shafts do not pull the line away.
        fun goodness(angle: Double, offset: Double): Double {
            val v = FlankContrast.WALK.profile(face, seed, unit(angle), offset, behind)
            v.sort()
            return v.copyOfRange(v.size / 4, v.size).average()
        }

        var bestAngle = seedAngle
        var bestOffset = 0.0
        var best = Double.NEGATIVE_INFINITY
        for (a in -ANGLE_WINDOW..ANGLE_WINDOW) {
            for (o in -OFFSET_STEPS..OFFSET_STEPS) {
                val g = goodness(seedAngle + a, o * OFFSET_STEP)
                if (g > best) {
                    best = g
                    bestAngle = seedAngle + a
                    bestOffset = o * OFFSET_STEP
                }
            }
        }
        repeat(2) {
            val angle0 = bestAngle
            val offset0 = bestOffset
            for (a in -FINE_STEPS..FINE_STEPS) {
                for (o in -FINE_STEPS..FINE_STEPS) {
                    val angle = angle0 + a * FINE_ANGLE_STEP
                    val offset = offset0 + o * FINE_OFFSET_STEP
                    val g = goodness(angle, offset)
                    if (g > best) {
                        best = g
                        bestAngle = angle
                        bestOffset = offset
                    }
                }
            }
        }

        val u = unit(bestAngle)
        val s = Numbers.steps(BEHIND_FROM, AHEAD, WALK_STEP)
        val v = FlankContrast.WALK.profile(face, seed, u, bestOffset, s)
        val start = ((SHAFT_FROM - BEHIND_FROM) / WALK_STEP).roundToInt()
        val shaft = Numbers.median(v.copyOfRange(0, start))
        if (shaft < NO_SHAFT_BELOW) {
            return WalkResult(seed, Line2(seed, direction), shaft, TipRefinement.NO_SHAFT)
        }

        val origin = seed + Vec2(-u.y, u.x) * bestOffset
        val line = Line2(origin, u)
        val threshold = END_FRACTION * shaft
        val gap = (END_GAP / WALK_STEP).roundToInt()
        var tipIndex = -1
        var i = start
        while (i < s.size) {
            if (v[i] > threshold) {
                i++
                continue
            }
            var j = i
            while (j < s.size && v[j] <= threshold) j++
            // An end needs a gap of END_GAP -- or a shorter one that reaches the
            // end of the window, as in tips.py.
            if (j - i >= gap || j >= s.size) {
                tipIndex = i - 1
                break
            }
            i = j
        }
        if (tipIndex < 0) {
            return WalkResult(line.project(seed), line, shaft, TipRefinement.RAN_OUT)
        }
        return WalkResult(origin + u * s[tipIndex], line, shaft, TipRefinement.REFINED)
    }

    private fun unit(degrees: Double): Vec2 {
        val r = Math.toRadians(degrees)
        return Vec2(cos(r), sin(r))
    }
}
```

- [ ] **Step 4: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*TipWalkTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/TipWalk.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/TipWalkTest.kt
git commit -m "detection: walk each shaft to its end (tips.py)"
```

---

## Task 8: Kandidaten, Zusammenlegen und Zuversicht

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowCandidates.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCandidatesTest.kt`

**Interfaces:**
- Consumes: `SearchRun` (Task 6), `WalkResult`, `TipRefinement` (Task 7), `Line2` (Task 2), `SpotMapping.locate(pointOnFace: Vec2, layout: FaceLayout): Located?`, `Candidate(faceIndex: Int, local: Vec2, confidence: Double)`
- Produces:
  - `class ArrowCandidate(tip, far, contrast, shaftContrast, score, refinement, offsetFromFoot, confidence, located: Candidate?)` mit `val line: Line2`
  - `object ArrowCandidates { const val LENGTH_SATURATION = 0.3; const val UNREFINED_FACTOR = 0.5; fun build(runs: List<SearchRun>, walks: List<WalkResult>, foot: Vec2, layout: FaceLayout): List<ArrowCandidate> }` — nach `score` absteigend

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`ArrowCandidatesTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Line2
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.hypot

class ArrowCandidatesTest {

    private val foot = Vec2(1.3, 0.0)
    private val layout = FaceLayout.singleSpot()

    private class Shaft(val run: SearchRun, val walk: WalkResult)

    private fun shaft(
        tip: Vec2,
        far: Vec2,
        refinement: TipRefinement = TipRefinement.REFINED,
        score: Double = 0.3,
        shaftContrast: Double = 0.6
    ) = Shaft(SearchRun(tip, far, 0.6, score), WalkResult(tip, Line2.through(tip, far), shaftContrast, refinement))

    private fun build(vararg shafts: Shaft) =
        ArrowCandidates.build(shafts.map { it.run }, shafts.map { it.walk }, foot, layout)

    @Test
    fun twoChordsOfOneShaftBecomeOneAndTheRefinedStays() {
        // A leaning shaft: its line does not pass through the foot point.
        val tip = Vec2(0.2, 0.1)
        val far = Vec2(-0.3, 0.2)
        val chordTip = tip + (far - tip) * (0.1 / (far - tip).length)

        val candidates = build(
            shaft(tip, far, TipRefinement.REFINED, score = 0.2),
            shaft(chordTip, far, TipRefinement.RAN_OUT, score = 0.4)
        )

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].refinement).isEqualTo(TipRefinement.REFINED)
        assertThat(candidates[0].tip).isEqualTo(tip)
    }

    @Test
    fun twoRefinedArrowsOnOneLineThroughTheFootPointStayTwo() {
        // Seen from Q, one arrow stands behind the other.
        val candidates = build(
            shaft(Vec2(0.3, 0.0), Vec2(0.2, 0.0)),
            shaft(Vec2(0.1, 0.0), Vec2(-0.3, 0.0))
        )

        assertThat(candidates).hasSize(2)
    }

    @Test
    fun tipsWithinAHundredthAreOneArrowAndTheBetterScoredStays() {
        val candidates = build(
            shaft(Vec2(0.2, 0.1), Vec2(-0.3, 0.2), score = 0.2),
            shaft(Vec2(0.205, 0.1), Vec2(-0.3, 0.1), score = 0.4)
        )

        assertThat(candidates.single().score).isWithin(1e-12).of(0.4)
    }

    @Test
    fun confidenceSaturatesInLengthAndHalvesForAWalkThatRanOut() {
        val s = ArrowCandidates.LENGTH_SATURATION
        val candidates = build(
            shaft(Vec2(0.2, 0.3), Vec2(0.2 - s / 2, 0.3), score = 0.1),
            shaft(Vec2(0.2, -0.3), Vec2(0.2 - 2 * s, -0.3), score = 0.3),
            shaft(Vec2(0.2, 0.0), Vec2(0.2 - 2 * s, 0.0), TipRefinement.RAN_OUT, score = 0.2)
        )

        // Highest score first: the long refined one, the one that ran out, the short one.
        assertThat(candidates[0].confidence).isWithin(1e-9).of(1.0)
        assertThat(candidates[1].confidence).isWithin(1e-9).of(ArrowCandidates.UNREFINED_FACTOR)
        assertThat(candidates[2].confidence).isWithin(1e-9).of(0.5)
    }

    @Test
    fun aNegativeShaftContrastGivesNoConfidence() {
        val candidates = build(
            shaft(Vec2(0.2, 0.3), Vec2(-0.3, 0.3)),
            shaft(Vec2(0.2, -0.3), Vec2(-0.3, -0.3), TipRefinement.NO_SHAFT, score = 0.1, shaftContrast = -0.02)
        )

        assertThat(candidates.first { it.refinement == TipRefinement.NO_SHAFT }.confidence).isEqualTo(0.0)
    }

    @Test
    fun theOffsetFromTheFootPointHasTheSignOfTheCrossProduct() {
        // cross(far - tip, Q - tip) / |far - tip| = ((-0.5)(-0.1) - (0.1)(1.1)) / |(-0.5, 0.1)|
        val candidate = build(shaft(Vec2(0.2, 0.1), Vec2(-0.3, 0.2))).single()

        assertThat(candidate.offsetFromFoot).isWithin(1e-9).of(-0.06 / hypot(0.5, 0.1))
    }

    @Test
    fun aTipNextToTheFaceIsNotLocated() {
        val candidates = build(
            shaft(Vec2(0.2, 0.1), Vec2(-0.3, 0.2)),
            shaft(Vec2(-1.02, 0.3), Vec2(-1.3, 0.35))
        )

        val inside = candidates.first { it.tip == Vec2(0.2, 0.1) }
        assertThat(inside.located!!.faceIndex).isEqualTo(0)
        assertThat(inside.located!!.local).isEqualTo(Vec2(0.2, 0.1))
        assertThat(inside.located!!.confidence).isEqualTo(inside.confidence)
        assertThat(candidates.first { it.tip == Vec2(-1.02, 0.3) }.located).isNull()
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCandidatesTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'ArrowCandidates'`.

- [ ] **Step 3: Implementieren**

`ArrowCandidates.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SpotMapping
import de.dreier.mytargets.detection.geometry.Line2
import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A candidate arrow after the walk (arrow design, Schnittstelle). */
class ArrowCandidate(
    /** The entry point, in target coordinates. */
    val tip: Vec2,
    /** The other end, towards the nock, on the candidate's line. */
    val far: Vec2,
    /** The run's contrast from the search. */
    val contrast: Double,
    /** The shaft contrast from the walk. */
    val shaftContrast: Double,
    /** Length times contrast, as radial.py rates a run. */
    val score: Double,
    val refinement: TipRefinement,
    /** Distance of the line tip-far from the foot point, with the sign of cross(far - tip, Q - tip). */
    val offsetFromFoot: Double,
    /** Arrow design, Verfahren step 5. */
    val confidence: Double,
    /** Spot and spot-local position; null next to the face. */
    val located: Candidate?
) {
    val line: Line2
        get() = Line2.through(tip, far)
}

/**
 * Verfahren step 5, what the annotator did by hand in the tools: one
 * candidate per arrow, and how sure each one is.
 */
object ArrowCandidates {

    const val SAME_TIP = 0.01
    const val SAME_LINE_ANGLE = 2.0
    const val SAME_LINE_DISTANCE = 0.01

    /** Start values (arrow design, Offene Punkte); set against the candidate diagnosis in Task 13. */
    const val LENGTH_SATURATION = 0.3
    const val UNREFINED_FACTOR = 0.5

    /** @param walks one per run, in the same order */
    fun build(runs: List<SearchRun>, walks: List<WalkResult>, foot: Vec2, layout: FaceLayout): List<ArrowCandidate> {
        require(runs.size == walks.size) { "one walk per run" }
        val drafts = runs.indices.mapNotNull { draft(runs[it], walks[it]) }
        val kept = ArrayList<Draft>()
        for (d in drafts.sortedWith(compareBy<Draft>({ preference(it.refinement) }, { -it.score }))) {
            if (kept.none { sameArrow(it, d) }) kept += d
        }
        val raw = kept.map { rawConfidence(it) }
        val best = raw.maxOrNull() ?: 0.0
        return kept.indices.map { i ->
            val d = kept[i]
            val confidence = if (best > 0.0) raw[i] / best else 0.0
            ArrowCandidate(
                tip = d.tip,
                far = d.far,
                contrast = d.contrast,
                shaftContrast = d.shaftContrast,
                score = d.score,
                refinement = d.refinement,
                offsetFromFoot = d.line.signedDistanceTo(foot),
                confidence = confidence,
                located = SpotMapping.locate(d.tip, layout)?.let { Candidate(it.faceIndex, it.local, confidence) }
            )
        }.sortedByDescending { it.score }
    }

    private class Draft(
        val tip: Vec2,
        val far: Vec2,
        val contrast: Double,
        val shaftContrast: Double,
        val score: Double,
        val refinement: TipRefinement,
        val line: Line2
    )

    /** far lies on the walk's line: the refined one, or the search's for NO_SHAFT. */
    private fun draft(run: SearchRun, walk: WalkResult): Draft? {
        val far = walk.line.project(run.far)
        if (far.distanceTo(walk.tip) < 1e-9) return null
        return Draft(walk.tip, far, run.contrast, walk.shaftContrast, run.score, walk.refinement, Line2.through(walk.tip, far))
    }

    /** REFINED before RAN_OUT before NO_SHAFT. */
    private fun preference(refinement: TipRefinement) = when (refinement) {
        TipRefinement.REFINED -> 0
        TipRefinement.RAN_OUT -> 1
        TipRefinement.NO_SHAFT -> 2
    }

    /**
     * The same arrow: entry points closer than SAME_TIP; or, when at least one
     * walk failed, lines that coincide. Two refined candidates on one line stay
     * two: seen from Q, one arrow can stand behind another exactly so.
     */
    private fun sameArrow(a: Draft, b: Draft): Boolean {
        if (a.tip.distanceTo(b.tip) < SAME_TIP) return true
        if (a.refinement == TipRefinement.REFINED && b.refinement == TipRefinement.REFINED) return false
        val cosine = a.line.direction.x * b.line.direction.x + a.line.direction.y * b.line.direction.y
        return abs(cosine) > cos(Math.toRadians(SAME_LINE_ANGLE)) &&
            abs(a.line.signedDistanceTo(b.tip)) < SAME_LINE_DISTANCE &&
            abs(b.line.signedDistanceTo(a.tip)) < SAME_LINE_DISTANCE
    }

    /**
     * Length times contrast, with the length saturating at LENGTH_SATURATION,
     * the walk's shaft contrast, and a failed walk counting UNREFINED_FACTOR.
     * NO_SHAFT has a shaft contrast below 0.08 by definition and ends at the
     * bottom of the scale, as intended.
     */
    private fun rawConfidence(d: Draft): Double {
        val length = min(d.tip.distanceTo(d.far), LENGTH_SATURATION) / LENGTH_SATURATION
        val factor = if (d.refinement == TipRefinement.REFINED) 1.0 else UNREFINED_FACTOR
        return length * max(0.0, d.shaftContrast) * factor
    }
}
```

- [ ] **Step 4: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCandidatesTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowCandidates.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCandidatesTest.kt
git commit -m "detection: one candidate per arrow, its line's offset from Q and its confidence"
```

---

## Task 9: Synthetische Pfeile und der Detektor

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowAnalysis.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/OpenCvArrowDetector.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/SyntheticArrows.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/WaFullZones.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/OpenCvArrowDetectorTest.kt`

**Interfaces:**
- Consumes: `ArrowDetector`, `DetectionRequest`, `DebugSink` (Task 1), `FootPoint` (Task 2), `RectifiedFace` (Task 4), `ShaftSearch` (Task 6), `TipWalk` (Task 7), `ArrowCandidates` (Task 8), `OpenCvFaceRegistrar`, `FaceRegistrar`, `RegistrationRequest`, `RegistrationOutcome`, `FaceWarp`, `CandidateSelection.select(candidates, expectedShots, maxPerSpot): SelectionOutcome`, `SyntheticFace.view`, `SyntheticFace.photograph`, `releaseIfThrows` (intern, aus den Testquellen desselben Moduls sichtbar)
- Produces:
  - `class StageTimings(val registrationMs: Long, val warpMs: Long, val searchMs: Long, val walkMs: Long)`
  - `sealed interface ArrowAnalysis { val timings: StageTimings }` mit `class NotRegistered(val registration: RegistrationOutcome.Failed, ...)` und `class Analysed(val registration: RegistrationOutcome.Registered, val footPoint: Vec2?, val candidates: List<ArrowCandidate>, val selection: SelectionOutcome, ...)`
  - `class OpenCvArrowDetector(registrar: FaceRegistrar = OpenCvFaceRegistrar()) : ArrowDetector` mit `fun analyse(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): ArrowAnalysis` und `companion fun faceConfidence(registered: RegistrationOutcome.Registered): Double`
  - Test-Helfer `SyntheticCamera(angleDegrees, pxPerRadius, centre)` mit `height`, `footPoint`, `targetToPhoto`, `intrinsics`, `depth(p: Vec3)`, `project(p: Vec3): Vec2`, `onFace(p: Vec3): Vec2`; `SyntheticArrow(entry, tilt, shaft, height, width)` mit `nock: Vec3`; `SyntheticArrows.photograph(camera, width, height, arrows): Mat`, `SyntheticArrows.stripeOnFace(photo, camera, from, to, width, colour)`
  - Test-Helfer `object WaFullZones { val RADII: List<Double>; fun zoneOf(radius: Double): Int? }`

- [ ] **Step 1: Die Test-Helfer anlegen**

`WaFullZones.kt` (Testquellen):

```kotlin
package de.dreier.mytargets.detection.arrows

/**
 * The zone radii of WAFull, spot local: X, 10, 9, ... 1. The app will
 * translate them from TargetModelBase; tests and the corpus run use these.
 */
object WaFullZones {
    val RADII = listOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)

    /**
     * The zone index of a hit [radius] from the centre, like zone() in tips.py:
     * the first zone reaching beyond it, from the pure radius as the sidecars
     * count. Null beyond the last ring.
     */
    fun zoneOf(radius: Double): Int? = RADII.indexOfFirst { radius < it }.takeIf { it >= 0 }
}
```

`SyntheticArrows.kt` (Testquellen):

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.geometry.Vec3
import de.dreier.mytargets.detection.registration.SyntheticFace
import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pinhole camera SyntheticFace.view builds: focal length FOCAL px, the
 * principal point on the face centre at [centre], turned by [angleDegrees]
 * about the vertical axis, [pxPerRadius] across the face centre vertically.
 * Target coordinates run x right, y down, z into the face; the camera stands
 * at (d sin a, 0, -d cos a) with d = FOCAL / pxPerRadius.
 */
class SyntheticCamera(angleDegrees: Double, pxPerRadius: Double, val centre: Vec2) {

    private val c = cos(Math.toRadians(angleDegrees))
    private val s = sin(Math.toRadians(angleDegrees))
    private val distance = FOCAL / pxPerRadius

    /** Height of the camera above the face plane, in spot radii. */
    val height = distance * c

    /** The foot of the perpendicular from the camera onto the face. */
    val footPoint = Vec2(distance * s, 0.0)

    val targetToPhoto: Mat3 = SyntheticFace.view(angleDegrees, pxPerRadius, centre)

    val intrinsics = CameraIntrinsics(FOCAL, centre)

    /** Depth of [p] in front of the camera. */
    fun depth(p: Vec3) = -p.x * s + p.z * c + distance

    /** Where [p] appears in the photograph. */
    fun project(p: Vec3): Vec2 {
        val z = depth(p)
        require(z > 1e-9) { "$p is not in front of the camera" }
        return Vec2(FOCAL * (p.x * c + p.z * s) / z + centre.x, FOCAL * p.y / z + centre.y)
    }

    /** Where the ray from the camera through [p] meets the face: what the rectified image shows at [p]. */
    fun onFace(p: Vec3): Vec2 {
        val k = -height / (-height - p.z)
        return Vec2(footPoint.x + (p.x - footPoint.x) * k, footPoint.y + (p.y - footPoint.y) * k)
    }

    companion object {
        /** SyntheticFace.view's focal length. */
        const val FOCAL = 1500.0
    }
}

/**
 * An arrow in the face: [entry] on the face, the nock [height] out of it
 * towards the camera and displaced by [tilt] per unit of height. Zero tilt
 * stands perpendicular; |tilt| is the tangent of the lean against the normal.
 */
class SyntheticArrow(
    val entry: Vec2,
    val tilt: Vec2 = Vec2(0.0, 0.0),
    val shaft: Scalar = DARK,
    val height: Double = 0.8,
    val width: Double = 0.014
) {
    val base: Vec3
        get() = Vec3(entry.x, entry.y, 0.0)

    val nock: Vec3
        get() = Vec3(entry.x + tilt.x * height, entry.y + tilt.y * height, -height)

    companion object {
        val DARK = Scalar(35.0, 35.0, 40.0)
        val GREY = Scalar(150.0, 150.0, 150.0)
        val FLETCHING = Scalar(60.0, 180.0, 60.0)
    }
}

object SyntheticArrows {

    private const val FLETCHING_WIDTH = 0.05
    private const val FLETCHING_FROM = 0.8
    private const val SHIFT = 4

    /** The face with [arrows] in it, seen by [camera]; the caller releases it. */
    fun photograph(camera: SyntheticCamera, width: Int, height: Int, arrows: List<SyntheticArrow>): Mat =
        SyntheticFace.photograph(width, height, camera.targetToPhoto).releaseIfThrows { photo ->
            for (arrow in arrows) draw(photo, camera, arrow)
        }

    /** The shaft from the entry to the nock, cut square at the entry, with fletching over its last fifth. */
    fun draw(photo: Mat, camera: SyntheticCamera, arrow: SyntheticArrow) {
        bar(photo, camera, arrow.base, arrow.nock, arrow.width, arrow.shaft)
        bar(photo, camera, lerp(arrow.base, arrow.nock, FLETCHING_FROM), arrow.nock, FLETCHING_WIDTH, SyntheticArrow.FLETCHING)
    }

    /** A stripe lying on the face from [from] to [to]: a shadow, or a mark. */
    fun stripeOnFace(photo: Mat, camera: SyntheticCamera, from: Vec2, to: Vec2, width: Double, colour: Scalar) {
        bar(photo, camera, Vec3(from.x, from.y, 0.0), Vec3(to.x, to.y, 0.0), width, colour)
    }

    /** A bar of [width] from [from] to [to] in space, its width shrinking with depth, anti-aliased. */
    private fun bar(photo: Mat, camera: SyntheticCamera, from: Vec3, to: Vec3, width: Double, colour: Scalar) {
        val a = camera.project(from)
        val b = camera.project(to)
        val along = (b - a) * (1.0 / (b - a).length)
        val across = Vec2(-along.y, along.x)
        val halfA = width * SyntheticCamera.FOCAL / camera.depth(from) / 2
        val halfB = width * SyntheticCamera.FOCAL / camera.depth(to) / 2
        val corners = listOf(a + across * halfA, b + across * halfB, b - across * halfB, a - across * halfA)
        val scale = (1 shl SHIFT).toDouble()
        val polygon = MatOfPoint(*corners.map { Point(Math.round(it.x * scale).toDouble(), Math.round(it.y * scale).toDouble()) }.toTypedArray())
        try {
            Imgproc.fillConvexPoly(photo, polygon, colour, Imgproc.LINE_AA, SHIFT)
        } finally {
            polygon.release()
        }
    }

    private fun lerp(p: Vec3, q: Vec3, t: Double) =
        Vec3(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t, p.z + (q.z - p.z) * t)
}
```

- [ ] **Step 2: Die fehlschlagenden Tests schreiben**

`OpenCvArrowDetectorTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import de.dreier.mytargets.detection.registration.SyntheticFace
import org.junit.Rule
import org.junit.Test
import kotlin.math.hypot

/**
 * The detector against arrows drawn with a real pinhole projection. The entry
 * point is where the arrow was put into the face in space, so the end of the
 * streak that counts follows from the projection and not from an assumption of
 * the test: a detector with the rule of stage 6 reversed would report the far
 * end of each visible shaft and fail here (Haupt-Spec, Stufe 6, correction of
 * 2026-09-09).
 */
class OpenCvArrowDetectorTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val detector = OpenCvArrowDetector()

    /**
     * Entry points off the black ring, where a dark shaft has no contrast, whose
     * shafts stay apart as seen from either foot point: at 45 degrees two pairs
     * lie under the duplicate rule's 6 degrees, but their ends are at least
     * 0.17 from each other's lines, far beyond its 0.025.
     */
    private val entries = listOf(
        Vec2(0.3, 0.35), Vec2(0.1, -0.05), Vec2(-0.3, -0.3), Vec2(0.25, -0.42), Vec2(-0.25, 0.15)
    )

    /** Both cameras stand 2.0 radii above the face. */
    private val at30 = SyntheticCamera(30.0, 650.0, Vec2(1000.0, 750.0))
    private val at45 = SyntheticCamera(45.0, 530.0, Vec2(1000.0, 750.0))

    private fun request(camera: SyntheticCamera, shots: Int) = DetectionRequest(
        FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, shots, camera.intrinsics
    )

    private fun analyse(camera: SyntheticCamera): ArrowAnalysis.Analysed {
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val analysis = try {
            detector.analyse(photo, request(camera, entries.size))
        } finally {
            photo.release()
        }
        assertWithMessage("outcome of the registration").that(analysis).isInstanceOf(ArrowAnalysis.Analysed::class.java)
        return analysis as ArrowAnalysis.Analysed
    }

    private fun assertFindsEveryArrow(camera: SyntheticCamera) {
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val result = try {
            detector.detect(photo, request(camera, entries.size))
        } finally {
            photo.release()
        }

        assertThat(result.failure).isNull()
        assertThat(result.reason).isEqualTo(SelectionReason.COMPLETE)
        assertThat(result.shots).hasSize(entries.size)
        for (entry in entries) {
            val nearest = result.shots.minOf { hypot(it.x - entry.x, it.y - entry.y) }
            assertWithMessage("entry point of the arrow at $entry").that(nearest).isAtMost(0.01)
        }
    }

    @Test
    fun findsEveryArrowAt30Degrees() = assertFindsEveryArrow(at30)

    @Test
    fun findsEveryArrowAt45Degrees() = assertFindsEveryArrow(at45)

    @Test
    fun theFootPointComesOutWhereTheCameraStands() {
        // The rings barely pin the third row of the homography, which Q hangs on;
        // 0.03 stays inside the search's window of 0.04.
        assertThat(analyse(at30).footPoint!!.distanceTo(at30.footPoint)).isAtMost(0.03)
    }

    @Test
    fun theFaceConfidenceFollowsTheWorstRing() {
        val registration = analyse(at30).registration
        val worst = registration.rings.maxOf { it.radialRms }

        assertThat(OpenCvArrowDetector.faceConfidence(registration))
            .isWithin(1e-12).of((1.0 - worst / 0.012).coerceIn(0.0, 1.0))
        assertThat(OpenCvArrowDetector.faceConfidence(registration)).isGreaterThan(0.0)
    }

    @Test
    fun aBlankPhotographIsNotRegistered() {
        val photo = SyntheticFace.blank(2000, 1500)
        val result = try {
            detector.detect(photo, request(at30, 5))
        } finally {
            photo.release()
        }

        assertThat(result.failure).isEqualTo(DetectionFailure.FACE_NOT_FOUND)
        assertThat(result.shots).isEmpty()
    }
}
```

- [ ] **Step 3: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*OpenCvArrowDetectorTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'OpenCvArrowDetector'`, `'ArrowAnalysis'`.

- [ ] **Step 4: Implementieren**

`ArrowAnalysis.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.SelectionOutcome
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.RegistrationOutcome

/** Wall time per stage in milliseconds, for the report (arrow design, Korpuslauf und Bericht). */
class StageTimings(val registrationMs: Long, val warpMs: Long, val searchMs: Long, val walkMs: Long)

/** Everything a detection produced, for the corpus run; the app gets only the DetectionResult. */
sealed interface ArrowAnalysis {
    val timings: StageTimings

    class NotRegistered(
        val registration: RegistrationOutcome.Failed,
        override val timings: StageTimings
    ) : ArrowAnalysis

    class Analysed(
        val registration: RegistrationOutcome.Registered,
        /** The camera's foot point in target coordinates; null only when it cannot be computed. */
        val footPoint: Vec2?,
        /** Highest score first. */
        val candidates: List<ArrowCandidate>,
        val selection: SelectionOutcome,
        override val timings: StageTimings
    ) : ArrowAnalysis
}
```

`OpenCvArrowDetector.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.ArrowDetector
import de.dreier.mytargets.detection.CandidateSelection
import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectedShot
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.DetectionResult
import de.dreier.mytargets.detection.geometry.FootPoint
import de.dreier.mytargets.detection.registration.FaceRegistrar
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvFaceRegistrar
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RegistrationRequest
import org.opencv.core.Mat

/**
 * The pipeline for a face the registration knows (arrow design): register,
 * rectify, compute the camera's foot point, search the shafts, walk each one to
 * its end, merge and rate the candidates, place them on spots, select.
 */
class OpenCvArrowDetector(
    private val registrar: FaceRegistrar = OpenCvFaceRegistrar()
) : ArrowDetector {

    fun analyse(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): ArrowAnalysis {
        val started = System.nanoTime()
        val outcome = registrar.register(image, RegistrationRequest(request.layout, request.transitions), debug)
        val registered = System.nanoTime()
        val registration = when (outcome) {
            is RegistrationOutcome.Failed ->
                return ArrowAnalysis.NotRegistered(outcome, StageTimings(millis(started, registered), 0, 0, 0))
            is RegistrationOutcome.Registered -> outcome
        }
        val foot = FootPoint.of(registration.imageToTarget, request.intrinsics)
        val warped = FaceWarp.warp(image, registration.imageToTarget)
        try {
            val face = RectifiedFace.fromWarped(warped, registration.imageToTarget, image.cols(), image.rows())
            val rectified = System.nanoTime()
            val runs = if (foot == null) emptyList() else ShaftSearch.find(face, foot, request.zoneRadii)
            val searched = System.nanoTime()
            val walks = runs.map { TipWalk.walk(face, it.tip, it.tip - it.far) }
            val candidates = if (foot == null) emptyList() else ArrowCandidates.build(runs, walks, foot, request.layout)
            val selection = CandidateSelection.select(
                candidates.mapNotNull { it.located }, request.expectedShots, request.maxArrowsPerSpot
            )
            val walked = System.nanoTime()
            return ArrowAnalysis.Analysed(
                registration, foot, candidates, selection,
                StageTimings(
                    millis(started, registered), millis(registered, rectified),
                    millis(rectified, searched), millis(searched, walked)
                )
            )
        } finally {
            warped.release()
        }
    }

    override fun detect(image: Mat, request: DetectionRequest, debug: DebugSink): DetectionResult =
        when (val analysis = analyse(image, request, debug)) {
            is ArrowAnalysis.NotRegistered -> DetectionResult.failed(analysis.registration.failure)
            is ArrowAnalysis.Analysed -> DetectionResult(
                shots = analysis.selection.accepted.map {
                    DetectedShot(it.faceIndex, it.local.x.toFloat(), it.local.y.toFloat(), it.confidence.toFloat())
                },
                faceConfidence = faceConfidence(analysis.registration).toFloat(),
                reason = analysis.selection.reason,
                failure = null
            )
        }

    companion object {
        /** Above this radial RMS the registration drops a ring. */
        const val RING_RMS_LIMIT = 0.012

        /**
         * Arrow design, Schnittstelle: 1 minus the worst radial RMS over
         * RING_RMS_LIMIT, clamped to 0..1. A definition, not a calibration.
         */
        fun faceConfidence(registered: RegistrationOutcome.Registered): Double {
            val worst = registered.rings.maxOfOrNull { it.radialRms } ?: return 0.0
            return (1.0 - worst / RING_RMS_LIMIT).coerceIn(0.0, 1.0)
        }

        private fun millis(from: Long, to: Long) = (to - from) / 1_000_000
    }
}
```

Das leere oder nicht-BGR-Bild weist der Registrar mit `IllegalArgumentException` ab (Design, *Fehlerfälle*); der Detektor reicht das durch.

- [ ] **Step 5: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*OpenCvArrowDetectorTest*' --tests '*OpenCvImportRuleTest*'`
Expected: PASS. Jeder Test registriert ein 3-MP-Foto und fegt einmal; rechne mit ein bis zwei Sekunden je Test.

Scheitert `findsEveryArrow…` an einem einzelnen Pfeil, zuerst `analyse` mit einem `PngDebugSink` in ein temporäres Verzeichnis ansehen (ab Task 10 mit den Bildern 5 und 6) und berichten; die Schwellen sind die der Werkzeuge und werden nicht an den synthetischen Fällen gedreht.

- [ ] **Step 6: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowAnalysis.kt \
  detection/src/main/java/de/dreier/mytargets/detection/arrows/OpenCvArrowDetector.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/SyntheticArrows.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/WaFullZones.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/OpenCvArrowDetectorTest.kt
git commit -m "detection: the arrow detector, checked against arrows under a pinhole projection"
```

---

## Task 10: Schwierige synthetische Fälle und die Debug-Bilder 5 und 6

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowDebugImages.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/arrows/OpenCvArrowDetector.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowRobustnessTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowDebugImagesTest.kt`

**Interfaces:**
- Consumes: alles aus Task 9, `CommonPoint` und `Line2` (Task 2), `DebugImages.CLASSES`, `DISCS`, `RINGS`
- Produces: `object ArrowDebugImages { const val CANDIDATES = "5-kandidaten"; const val ENTRY_POINTS = "6-einschuesse"; fun candidates(warped: Mat, foot: Vec2?, candidates: List<ArrowCandidate>): Mat; fun entryPoints(warped: Mat, candidates: List<ArrowCandidate>, accepted: List<Candidate>): Mat }`

- [ ] **Step 1: Die Tests der schwierigen Fälle schreiben**

`ArrowRobustnessTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.CommonPoint
import de.dreier.mytargets.detection.geometry.Line2
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import de.dreier.mytargets.detection.registration.SyntheticFace
import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The harder cases of the arrow design, each against exact truth. A failure
 * here is reported, not tuned away: the thresholds are the tools' and are set
 * against the corpus report.
 */
class ArrowRobustnessTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val detector = OpenCvArrowDetector()

    /** 2.0 radii above the face, as the design's leaning cases assume. */
    private val camera = SyntheticCamera(30.0, 650.0, Vec2(1000.0, 750.0))

    private fun analyse(photo: Mat, shots: Int, seenBy: SyntheticCamera = camera): ArrowAnalysis.Analysed {
        val request = DetectionRequest(
            FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, shots, seenBy.intrinsics
        )
        val analysis = try {
            detector.analyse(photo, request)
        } finally {
            photo.release()
        }
        assertWithMessage("outcome of the registration").that(analysis).isInstanceOf(ArrowAnalysis.Analysed::class.java)
        return analysis as ArrowAnalysis.Analysed
    }

    private fun photograph(vararg arrows: SyntheticArrow) =
        SyntheticArrows.photograph(camera, 2000, 1500, arrows.toList())

    /** A lean of [degrees] across the direction from the foot point to [entry]. */
    private fun leanAcross(entry: Vec2, degrees: Double): Vec2 {
        val away = (entry - camera.footPoint) * (1.0 / (entry - camera.footPoint).length)
        return Vec2(-away.y, away.x) * tan(Math.toRadians(degrees))
    }

    /** The shaft's line in the rectified image, from the entry towards the nock. */
    private fun shaftLine(arrow: SyntheticArrow, seenBy: SyntheticCamera = camera) =
        Line2.through(arrow.entry, seenBy.onFace(arrow.nock))

    /** The candidates lying along [arrow]'s shaft: both ends within 0.01 of its line. */
    private fun alongShaft(analysis: ArrowAnalysis.Analysed, arrow: SyntheticArrow): List<ArrowCandidate> {
        val line = shaftLine(arrow)
        return analysis.candidates.filter {
            abs(line.signedDistanceTo(it.tip)) <= 0.01 && abs(line.signedDistanceTo(it.far)) <= 0.01
        }
    }

    private fun rotate(v: Vec2, degrees: Double): Vec2 {
        val a = Math.toRadians(degrees)
        return Vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
    }

    @Test
    fun anArrowLeaningOneDegreeIsFoundWhole() {
        // At a height of 2.0 a lean of 1 degree moves the line at most 0.035 from Q,
        // inside the search's window of 0.04 in any direction.
        val arrow = SyntheticArrow(Vec2(0.1, -0.05), tilt = leanAcross(Vec2(0.1, -0.05), 1.0))

        val analysis = analyse(photograph(arrow), 1)

        assertThat(analysis.selection.accepted.single().local.distanceTo(arrow.entry)).isAtMost(0.01)
    }

    @Test
    fun anArrowLeaningThreeDegreesShowsItsOffset() {
        val arrow = SyntheticArrow(Vec2(0.1, -0.05), tilt = leanAcross(Vec2(0.1, -0.05), 3.0))

        val analysis = analyse(photograph(arrow), 1)
        // From the projection of the shaft, not d tan(alpha): that holds only for a lean across.
        val expected = shaftLine(arrow).signedDistanceTo(analysis.footPoint!!)

        assertWithMessage("the case lies outside the window, as it claims").that(abs(expected)).isGreaterThan(0.04)
        val seen = alongShaft(analysis, arrow).filter { it.refinement != TipRefinement.NO_SHAFT }
        assertWithMessage("a candidate with a refined line along the leaning shaft").that(seen).isNotEmpty()
        assertThat(seen.minOf { abs(it.offsetFromFoot - expected) }).isAtMost(0.01)
    }

    @Test
    fun threeArrowsLeaningAlikeMeetInOnePoint() {
        val tilt = Vec2(0.0, tan(Math.toRadians(3.0)))
        // Off the black ring, like the entries of OpenCvArrowDetectorTest.
        val arrows = listOf(Vec2(0.3, 0.35), Vec2(0.1, -0.05), Vec2(0.25, -0.42)).map { SyntheticArrow(it, tilt = tilt) }

        val analysis = analyse(photograph(*arrows.toTypedArray()), 3)
        val lines = arrows.map { arrow ->
            val seen = alongShaft(analysis, arrow).filter { it.refinement != TipRefinement.NO_SHAFT }
            assertWithMessage("a refined candidate along the arrow at ${arrow.entry}").that(seen).isNotEmpty()
            seen.maxBy { it.score }.line
        }

        // Q - d t with d the camera's height (arrow design, Verfahren step 3).
        val expected = camera.footPoint - tilt * camera.height
        assertThat(CommonPoint.of(lines)!!.distanceTo(expected)).isAtMost(0.01)
    }

    @Test
    fun aShadowThatDoesNotPointAtTheFootPointIsNotFound() {
        val start = Vec2(0.1, -0.05)
        val turned = rotate((start - camera.footPoint) * (1.0 / (start - camera.footPoint).length), 30.0)
        val photo = SyntheticFace.photograph(2000, 1500, camera.targetToPhoto).releaseIfThrows {
            SyntheticArrows.stripeOnFace(it, camera, start, start + turned * 0.3, 0.014, Scalar(60.0, 60.0, 60.0))
        }

        assertThat(analyse(photo, 1).candidates).isEmpty()
    }

    @Test
    fun aGreyShaftOnTheBlackRingIsFound() {
        val arrow = SyntheticArrow(Vec2(-0.65, 0.2), shaft = SyntheticArrow.GREY)

        val analysis = analyse(photograph(arrow), 1)

        assertThat(analysis.selection.accepted.single().local.distanceTo(arrow.entry)).isAtMost(0.01)
    }

    @Test
    fun aShaftAcrossTheBlackRingIsFoundOnce() {
        // A dark shaft vanishes on the black ring and splits into two pieces.
        val arrow = SyntheticArrow(Vec2(-0.3, -0.3))

        val along = alongShaft(analyse(photograph(arrow), 1), arrow)

        assertThat(along).hasSize(1)
        assertThat(along.single().tip.distanceTo(arrow.entry)).isAtMost(0.01)
    }

    @Test
    fun aShaftLeavingACutPhotographIsFoundAndTheEdgeIsNot() {
        // The face centre sits at x = 400 of a 1600 px wide photograph: its left
        // edge cuts the face near x = -0.84, and the shaft runs out of the image.
        val cut = SyntheticCamera(30.0, 650.0, Vec2(400.0, 750.0))
        val arrow = SyntheticArrow(Vec2(-0.3, -0.3))

        val analysis = analyse(SyntheticArrows.photograph(cut, 1600, 1500, listOf(arrow)), 1, cut)

        assertThat(analysis.selection.accepted.single().local.distanceTo(arrow.entry)).isAtMost(0.01)
        val toTarget = requireNotNull(cut.targetToPhoto.inverse())
        val edge = Line2.through(toTarget.mapPoint(Vec2(0.0, 0.0))!!, toTarget.mapPoint(Vec2(0.0, 1499.0))!!)
        val alongEdge = analysis.candidates.filter {
            abs(edge.signedDistanceTo(it.tip)) < 0.02 && abs(edge.signedDistanceTo(it.far)) < 0.02
        }
        assertThat(alongEdge).isEmpty()
    }
}
```

- [ ] **Step 2: Laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowRobustnessTest*'`
Expected: PASS — die Fälle prüfen das Verfahren aus Task 5 bis 9, der Code steht schon. Scheitert ein Fall, nicht an Schwellen drehen, sondern mit den Debug-Bildern aus Step 5 untersuchen und berichten.

- [ ] **Step 3: Den Test der Debug-Bilder schreiben**

`ArrowDebugImagesTest.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.DebugImages
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Rule
import org.junit.Test

class ArrowDebugImagesTest {

    @get:Rule
    val openCv = OpenCvRule()

    @Test
    fun theDetectorShowsTheCandidatesAndTheEntryPointsOnTheRectifiedFace() {
        val camera = SyntheticCamera(30.0, 650.0, Vec2(1000.0, 750.0))
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, listOf(SyntheticArrow(Vec2(0.1, -0.05))))
        val request = DetectionRequest(
            FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, 1, camera.intrinsics
        )
        val shown = ArrayList<Triple<String, Int, Int>>()

        val analysis = try {
            OpenCvArrowDetector().analyse(photo, request, DebugSink { stage, image ->
                shown += Triple(stage, image.cols(), image.rows())
            })
        } finally {
            photo.release()
        }

        val edge = FaceWarp.edgeFor((analysis as ArrowAnalysis.Analysed).registration.imageToTarget)
        assertThat(shown.map { it.first }).containsExactly(
            DebugImages.CLASSES, DebugImages.DISCS, DebugImages.RINGS,
            ArrowDebugImages.CANDIDATES, ArrowDebugImages.ENTRY_POINTS
        ).inOrder()
        assertThat(shown.drop(3).map { it.second to it.third }).containsExactly(edge to edge, edge to edge)
    }
}
```

- [ ] **Step 4: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowDebugImagesTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'ArrowDebugImages'`.

- [ ] **Step 5: Die Debug-Bilder**

`ArrowDebugImages.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * The arrow search's stage images (arrow design, Debug-Bilder), drawn on the
 * rectified face. Each call returns a new Mat; the detector releases it after
 * the sink.
 */
object ArrowDebugImages {

    const val CANDIDATES = "5-kandidaten"
    const val ENTRY_POINTS = "6-einschuesse"

    private val WHITE = Scalar(255.0, 255.0, 255.0)
    private val MAGENTA = Scalar(255.0, 0.0, 255.0)
    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val YELLOW = Scalar(0.0, 255.0, 255.0)
    private val RED = Scalar(0.0, 0.0, 255.0)
    private val ORANGE = Scalar(0.0, 140.0, 255.0)

    /** The foot point, or an arrow towards it when it lies off the square, and every candidate numbered by rank. */
    fun candidates(warped: Mat, foot: Vec2?, candidates: List<ArrowCandidate>): Mat =
        warped.clone().releaseIfThrows { mat ->
            val edge = mat.cols()
            if (foot != null) {
                if (abs(foot.x) < FaceWarp.EXTENT && abs(foot.y) < FaceWarp.EXTENT) {
                    Imgproc.circle(mat, point(foot, edge), 12, WHITE, 3)
                } else {
                    val towards = foot * (1.0 / foot.length)
                    Imgproc.arrowedLine(mat, point(towards * 0.9, edge), point(towards * 1.05, edge), WHITE, 3)
                }
            }
            candidates.forEachIndexed { rank, c ->
                Imgproc.line(mat, point(c.tip, edge), point(c.far, edge), MAGENTA, 2)
                Imgproc.putText(mat, "${rank + 1}", point(c.tip, edge), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, WHITE, 2)
            }
        }

    /**
     * Every entry point: green when the selection accepted it, yellow when not;
     * a red cross where the walk saw no shaft, an orange triangle where it ran out.
     */
    fun entryPoints(warped: Mat, candidates: List<ArrowCandidate>, accepted: List<Candidate>): Mat =
        warped.clone().releaseIfThrows { mat ->
            val edge = mat.cols()
            for (c in candidates) {
                val p = point(c.tip, edge)
                val taken = c.located != null && accepted.any { it === c.located }
                Imgproc.circle(mat, p, 10, if (taken) GREEN else YELLOW, 3)
                when (c.refinement) {
                    TipRefinement.NO_SHAFT -> Imgproc.drawMarker(mat, p, RED, Imgproc.MARKER_CROSS, 24, 2)
                    TipRefinement.RAN_OUT -> Imgproc.drawMarker(mat, p, ORANGE, Imgproc.MARKER_TRIANGLE_UP, 24, 2)
                    TipRefinement.REFINED -> Unit
                }
            }
        }

    private fun point(p: Vec2, edge: Int): Point {
        val px = FaceWarp.pixelOf(p, edge)
        return Point(px.x, px.y)
    }
}
```

In `OpenCvArrowDetector.analyse` zwischen `val walked = System.nanoTime()` und `return ArrowAnalysis.Analysed(…)` einfügen:

```kotlin
            show(debug, ArrowDebugImages.CANDIDATES) { ArrowDebugImages.candidates(warped, foot, candidates) }
            show(debug, ArrowDebugImages.ENTRY_POINTS) {
                ArrowDebugImages.entryPoints(warped, candidates, selection.accepted)
            }
```

und in der Klasse, vor dem `companion object`:

```kotlin
    /** Renders a stage only when someone looks, and releases it after the sink. */
    private fun show(debug: DebugSink, stage: String, render: () -> Mat) {
        if (debug === DebugSink.NONE) return
        val image = render()
        try {
            debug.image(stage, image)
        } finally {
            image.release()
        }
    }
```

Die Bilder entstehen nach `walked`; ihre Zeit zählt zu keiner Stufe.

- [ ] **Step 6: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS, die ganze Suite.

- [ ] **Step 7: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowDebugImages.kt \
  detection/src/main/java/de/dreier/mytargets/detection/arrows/OpenCvArrowDetector.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowRobustnessTest.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowDebugImagesTest.kt
git commit -m "detection: leaning, shadowed, grey, split and cut shafts; stage images 5 and 6"
```

---

## Task 11: Der Pfeilbericht

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ArrowReport.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ArrowReportTest.kt`

**Interfaces:**
- Consumes: `CorpusEntry` (`capture?.angle`), `EntryOutcome`, `Metrics.over`, `MetricsReport.render(outcomes, title)`, `ShotMatching.match`, `DetectedShotRecord`
- Produces:
  - `object ArrowGroups { const val OBLIQUE = "schraeg"; const val FRONTAL = "frontal"; const val UNKNOWN = "ohne Winkel"; val ORDER; fun of(entry: CorpusEntry): String }`
  - `class StageMillis(registration: Long, warp: Long, search: Long, walk: Long)`
  - `class ArrowRow(imageName, group, outcome, detail, footPointFromCentre, registrationError, footPointShift, largestLineOffset, commonPointFromFoot, listed, unresolved, candidates, accepted, matched, falsePositives, selection, largestPositionError, noShaft, ranOut, millis)` mit `companion const val REGISTERED = "registered"`
  - `class TruthDiagnosis(imageName, group, truthIndex, rank: Int?, confidence: Double?, lineOffset: Double?, accepted: Boolean)`
  - `class PhotoDiagnosis(imageName, group, bestFalseConfidence: Double?)`
  - `object ArrowReport { fun render(title, rows, truths, photos, outcomes, outOfScope: List<Pair<String, String>>): String }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`ArrowReportTest.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.corpus.CaptureInfo
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class ArrowReportTest {

    private fun entry(name: String, angle: String?, vararg shots: TruthShot) = CorpusEntry(
        imageName = name,
        image = null,
        camera = null,
        capture = CaptureInfo(lighting = "bedeckt", angle = angle, angleDegrees = null),
        target = null,
        shotsPerEnd = 6,
        shots = shots.toList(),
        unresolvedArrows = 0,
        registration = null
    )

    private fun truth(x: Double, y: Double) =
        TruthShot(scoringRing = 3, position = SpotPosition(0, x, y), positionTolerance = 0.01)

    private fun found(x: Double, y: Double) = DetectedShotRecord(3, null, SpotPosition(0, x, y), 1.0)

    private fun outcome(entry: CorpusEntry, vararg detected: DetectedShotRecord) =
        EntryOutcome(entry, ShotMatching.match(entry, detected.toList()), detected.toList())

    private fun row(
        name: String,
        group: String,
        listed: Int,
        matched: Int,
        outcome: String = ArrowRow.REGISTERED
    ) = ArrowRow(
        imageName = name,
        group = group,
        outcome = outcome,
        detail = if (outcome == ArrowRow.REGISTERED) null else "no yellow disc",
        footPointFromCentre = 1.2,
        registrationError = 0.001,
        footPointShift = 0.002,
        largestLineOffset = 0.01,
        commonPointFromFoot = null,
        listed = listed,
        unresolved = 0,
        candidates = 6,
        accepted = matched,
        matched = matched,
        falsePositives = 0,
        selection = "COMPLETE",
        largestPositionError = 0.004,
        noShaft = 0,
        ranOut = 0,
        millis = StageMillis(900, 60, 700, 80)
    )

    private fun render(
        rows: List<ArrowRow>,
        truths: List<TruthDiagnosis> = emptyList(),
        outcomes: List<EntryOutcome> = emptyList(),
        outOfScope: List<Pair<String, String>> = emptyList()
    ) = ArrowReport.render("Run", rows, truths, emptyList(), outcomes, outOfScope)

    @Test
    fun theGroupOfAPhotographFollowsItsAngle() {
        assertThat(ArrowGroups.of(entry("a", "stark-schraeg"))).isEqualTo(ArrowGroups.OBLIQUE)
        assertThat(ArrowGroups.of(entry("b", "leicht-schraeg"))).isEqualTo(ArrowGroups.OBLIQUE)
        assertThat(ArrowGroups.of(entry("c", "frontal"))).isEqualTo(ArrowGroups.FRONTAL)
        assertThat(ArrowGroups.of(entry("d", null))).isEqualTo(ArrowGroups.UNKNOWN)
    }

    @Test
    fun groupsAreSummedSeparately() {
        val oblique = entry("o.jpg", "leicht-schraeg", truth(0.1, 0.1), truth(0.3, 0.0))
        val frontal = entry("f.jpg", "frontal", truth(0.2, 0.2))

        val report = render(
            listOf(row("o.jpg", ArrowGroups.OBLIQUE, 2, 1), row("f.jpg", ArrowGroups.FRONTAL, 1, 0)),
            outcomes = listOf(outcome(oblique, found(0.1, 0.1)), outcome(frontal))
        )

        assertThat(report).contains("| schraeg | 1 | 2 | 1 | 50.0 % | 0 |")
        assertThat(report).contains("| frontal | 1 | 1 | 0 | 0.0 % | 0 |")
    }

    @Test
    fun anUnregisteredPhotographComesFirstThenTheOneMissingMost() {
        val report = render(
            listOf(
                row("fine.jpg", ArrowGroups.OBLIQUE, 6, 6),
                row("poor.jpg", ArrowGroups.OBLIQUE, 6, 2),
                row("lost.jpg", ArrowGroups.OBLIQUE, 6, 0, outcome = "FACE_NOT_FOUND")
            )
        )

        assertThat(report.indexOf("| lost.jpg |")).isLessThan(report.indexOf("| poor.jpg |"))
        assertThat(report.indexOf("| poor.jpg |")).isLessThan(report.indexOf("| fine.jpg |"))
        assertThat(report).contains("| lost.jpg | FACE_NOT_FOUND: no yellow disc |")
    }

    @Test
    fun theDiagnosisSeparatesFindingFromSelecting() {
        val truths = listOf(
            TruthDiagnosis("o.jpg", ArrowGroups.OBLIQUE, 0, rank = 1, confidence = 1.0, lineOffset = 0.01, accepted = true),
            TruthDiagnosis("o.jpg", ArrowGroups.OBLIQUE, 1, rank = 4, confidence = 0.4, lineOffset = -0.035, accepted = false),
            TruthDiagnosis("o.jpg", ArrowGroups.OBLIQUE, 2, rank = null, confidence = null, lineOffset = null, accepted = false)
        )

        val report = render(listOf(row("o.jpg", ArrowGroups.OBLIQUE, 3, 1)), truths = truths)

        assertThat(report).contains(
            "**schraeg:** 2 of 3 listed hits had a candidate within the budget (66.7 %); " +
                "the selection lost 1 of them. Line offsets from Q of those candidates: " +
                "median 0.0225, above 0.03: 1 of 2."
        )
        assertThat(report).contains("| o.jpg | 1 | 4 | 0.4000 | -0.0350 | no |")
    }

    @Test
    fun photographsOutOfScopeAreOnlyListed() {
        val report = render(emptyList(), outOfScope = listOf("a6_x.jpg" to "three faces"))

        assertThat(report).contains("| a6_x.jpg | three faces |")
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection-corpus:test --tests '*ArrowReportTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'ArrowReport'`, `'ArrowRow'`, `'ArrowGroups'`.

- [ ] **Step 3: Implementieren**

`ArrowReport.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

import de.dreier.mytargets.detection.corpus.CorpusEntry
import java.util.Locale
import kotlin.math.abs

/** Which group a photograph falls in for the arrow report and its bounds (arrow design, Schranken). */
object ArrowGroups {
    const val OBLIQUE = "schraeg"
    const val FRONTAL = "frontal"
    const val UNKNOWN = "ohne Winkel"

    /** The order the report shows them in. */
    val ORDER = listOf(OBLIQUE, FRONTAL, UNKNOWN)

    fun of(entry: CorpusEntry): String = when (entry.capture?.angle) {
        "leicht-schraeg", "stark-schraeg" -> OBLIQUE
        "frontal" -> FRONTAL
        else -> UNKNOWN
    }
}

/** Wall time per stage in milliseconds. */
class StageMillis(val registration: Long, val warp: Long, val search: Long, val walk: Long)

/** One photograph of an arrow run, as the report shows it. */
class ArrowRow(
    val imageName: String,
    val group: String,
    /** REGISTERED, or the name of the registration's failure. */
    val outcome: String,
    /** Why the registration failed; null for a registered row. */
    val detail: String?,
    /** Distance of the camera's foot point from the face centre. */
    val footPointFromCentre: Double?,
    /** Largest registration error against the sidecar's reference, as in the registration report. */
    val registrationError: Double?,
    /** Distance between the pipeline's foot point and the one from the reference homography. */
    val footPointShift: Double?,
    /** Largest distance from Q of the line of a candidate matched to a listed hit. */
    val largestLineOffset: Double?,
    /** Distance from Q of the common point of the refined lines of the matched candidates. */
    val commonPointFromFoot: Double?,
    val listed: Int,
    val unresolved: Int,
    val candidates: Int,
    val accepted: Int,
    val matched: Int,
    val falsePositives: Int,
    /** The selection's reason; null when the face was not registered. */
    val selection: String?,
    val largestPositionError: Double?,
    val noShaft: Int,
    val ranOut: Int,
    val millis: StageMillis
) {
    val registered: Boolean
        get() = outcome == REGISTERED

    companion object {
        const val REGISTERED = "registered"
    }
}

/** One listed hit and what the candidates before the selection had for it. */
class TruthDiagnosis(
    val imageName: String,
    val group: String,
    val truthIndex: Int,
    /** 1-based rank by score of the candidate matched to this hit; null when none lay within the budget. */
    val rank: Int?,
    val confidence: Double?,
    val lineOffset: Double?,
    val accepted: Boolean
)

/** The best confidence on a photograph of a candidate that matches no listed hit. */
class PhotoDiagnosis(val imageName: String, val group: String, val bestFalseConfidence: Double?)

/**
 * Renders an arrow run as Markdown (arrow design, Korpuslauf und Bericht): the
 * groups, the metrics, a table per photograph, and the candidates before the
 * selection, which separate finding from selecting.
 */
object ArrowReport {

    /** A line offset worth a note: close to the search's window of 0.04. */
    const val LINE_OFFSET_NOTE = 0.03

    fun render(
        title: String,
        rows: List<ArrowRow>,
        truths: List<TruthDiagnosis>,
        photos: List<PhotoDiagnosis>,
        outcomes: List<EntryOutcome>,
        outOfScope: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        val registered = rows.count { it.registered }
        sb.appendLine(
            "${rows.size} photographs in scope, $registered registered, ${rows.size - registered} not. " +
                "Lengths, offsets and errors are in spot radii."
        )
        groups(sb, rows, outcomes)
        sb.appendLine()
        // One heading level down, its own sections included.
        sb.append(
            MetricsReport.render(outcomes, "Metrics over every photograph in scope")
                .replace(Regex("^#", RegexOption.MULTILINE), "##")
        )
        photographs(sb, rows)
        diagnosis(sb, truths, photos)
        if (outOfScope.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Out of scope")
            sb.appendLine()
            sb.appendLine("These are not run; they count for no metric.")
            sb.appendLine()
            sb.appendLine("| Photograph | Reason |")
            sb.appendLine("|---|---|")
            for ((name, reason) in outOfScope.sortedBy { it.first }) sb.appendLine("| $name | $reason |")
        }
        return sb.toString()
    }

    private fun groups(sb: StringBuilder, rows: List<ArrowRow>, outcomes: List<EntryOutcome>) {
        sb.appendLine()
        sb.appendLine("## Groups")
        sb.appendLine()
        sb.appendLine(
            "Ring accuracy stands with its denominator and beside the detection rate, as the " +
                "Haupt-Spec requires."
        )
        sb.appendLine()
        sb.appendLine(
            "| Group | Photographs | Listed hits | Matched | Detection rate | False positives | " +
                "Ring accuracy | Median error | p95 error |"
        )
        sb.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (group in ArrowGroups.ORDER) {
            val photographs = rows.count { it.group == group }
            if (photographs == 0) continue
            val m = Metrics.over(outcomes.filter { ArrowGroups.of(it.entry) == group })
            sb.appendLine(
                "| $group | $photographs | ${m.expectedShots} | ${m.matchedShots} | " +
                    "${percent(m.detectionRate)} | ${m.falsePositives} | " +
                    "${percent(m.scoreAccuracy)} (${m.correctScores}/${m.scoreComparableShots}) | " +
                    "${number(m.medianPositionError)} | ${number(m.p95PositionError)} |"
            )
        }
    }

    private fun photographs(sb: StringBuilder, rows: List<ArrowRow>) {
        for (group in ArrowGroups.ORDER) {
            val inGroup = rows.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            sb.appendLine()
            sb.appendLine("## Photographs, $group")
            sb.appendLine()
            sb.appendLine(
                "| Photograph | Outcome | Q from centre | Registration error | Q shift | " +
                    "Largest line offset | Common point from Q | Listed | Unresolved | Candidates | " +
                    "Accepted | Matched | False positives | Selection | Largest error | NO_SHAFT | " +
                    "RAN_OUT | ms: registration / warp / search / walk |"
            )
            sb.appendLine("|" + "---|".repeat(18))
            // Failures first, then the photograph missing most, then the one inventing most.
            val ordered = inGroup.sortedWith(
                compareBy<ArrowRow>({ it.registered }, { -(it.listed - it.matched) }, { -it.falsePositives }, { it.imageName })
            )
            for (row in ordered) sb.appendLine(line(row))
        }
    }

    private fun line(row: ArrowRow): String {
        val outcome = if (row.registered || row.detail == null) row.outcome else "${row.outcome}: ${row.detail}"
        val t = row.millis
        return "| ${row.imageName} | $outcome | ${number(row.footPointFromCentre)} | " +
            "${number(row.registrationError)} | ${number(row.footPointShift)} | " +
            "${number(row.largestLineOffset)} | ${number(row.commonPointFromFoot)} | ${row.listed} | " +
            "${row.unresolved} | ${row.candidates} | ${row.accepted} | ${row.matched} | " +
            "${row.falsePositives} | ${row.selection ?: "-"} | ${number(row.largestPositionError)} | " +
            "${row.noShaft} | ${row.ranOut} | ${t.registration} / ${t.warp} / ${t.search} / ${t.walk} |"
    }

    private fun diagnosis(sb: StringBuilder, truths: List<TruthDiagnosis>, photos: List<PhotoDiagnosis>) {
        sb.appendLine()
        sb.appendLine("## Candidates before the selection")
        sb.appendLine()
        sb.appendLine(
            "The matching of the metrics, run over every candidate on the face instead of the " +
                "accepted ones. A hit whose candidate the selection did not accept was lost by the " +
                "selection; a hit without a candidate was never found."
        )
        for (group in ArrowGroups.ORDER) {
            val inGroup = truths.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            val found = inGroup.count { it.rank != null }
            val lost = inGroup.count { it.rank != null && !it.accepted }
            val offsets = inGroup.mapNotNull { it.lineOffset }.map { abs(it) }.sorted()
            val over = offsets.count { it > LINE_OFFSET_NOTE }
            sb.appendLine()
            sb.appendLine(
                "**$group:** $found of ${inGroup.size} listed hits had a candidate within the budget " +
                    "(${percent(ratio(found, inGroup.size))}); the selection lost $lost of them. " +
                    "Line offsets from Q of those candidates: median ${number(median(offsets))}, " +
                    "above $LINE_OFFSET_NOTE: $over of ${offsets.size}."
            )
        }
        sb.appendLine()
        sb.appendLine("| Photograph | Best confidence of a candidate matching no hit |")
        sb.appendLine("|---|---|")
        for (p in photos.sortedBy { it.imageName }) {
            sb.appendLine("| ${p.imageName} | ${number(p.bestFalseConfidence)} |")
        }
        sb.appendLine()
        sb.appendLine("| Photograph | Hit | Rank | Confidence | Line offset | Accepted |")
        sb.appendLine("|---|---|---|---|---|---|")
        for (t in truths.sortedWith(compareBy<TruthDiagnosis>({ it.imageName }, { it.truthIndex }))) {
            sb.appendLine(
                "| ${t.imageName} | ${t.truthIndex} | ${t.rank ?: "-"} | ${number(t.confidence)} | " +
                    "${number(t.lineOffset)} | ${if (t.accepted) "yes" else "no"} |"
            )
        }
    }

    private fun ratio(count: Int, total: Int): Double? = if (total == 0) null else count.toDouble() / total

    private fun median(sorted: List<Double>): Double? = when {
        sorted.isEmpty() -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> 0.5 * (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2])
    }

    private fun number(value: Double?) = value?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "-"

    private fun percent(value: Double?) = value?.let { String.format(Locale.ROOT, "%.1f %%", it * 100.0) } ?: "-"
}
```

- [ ] **Step 4: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection-corpus:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ArrowReport.kt \
  detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ArrowReportTest.kt
git commit -m "detection-corpus: the arrow report, by group, with the candidates before the selection"
```

---

## Task 12: Der Korpuslauf

**Files:**
- Create: `detection/src/test/java/de/dreier/mytargets/detection/registration/CorpusPhotos.kt`
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/registration/RegistrationCorpusRun.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRun.kt`
- Modify: `BUILDING.md`

**Interfaces:**
- Consumes: `OpenCvArrowDetector.analyse` (Task 9, 10), `ArrowReport`, `ArrowRow`, `TruthDiagnosis`, `PhotoDiagnosis`, `StageMillis`, `ArrowGroups` (Task 11), `FootPoint`, `CommonPoint` (Task 2), `WaFullZones` (Task 9), `RegistrationError.between`, `ShotMatching.match`, `Metrics.over`, `PngDebugSink`
- Produces:
  - `object CorpusPhotos { fun filesByName(root: File, entries: List<CorpusEntry>): Map<String, List<File>>; fun imageFileOf(root: File, imageName: String, candidates: List<File>): File; fun checkDecodedSize(entry: CorpusEntry, image: Mat); fun writeRectified(image: Mat, imageToTarget: Mat3, reference: List<Double>, imageName: String, file: File); fun values(m: Mat3): List<Double> }`
  - `detection/build/reports/detection/arrows.md` und `detection/build/reports/detection/arrows/<foto>/` mit den Bildern 1 bis 7

- [ ] **Step 1: Die geteilten Hilfen**

`CorpusPhotos.kt` (Testquellen, Paket `registration`). Der Inhalt von `imageFileOf`, `checkDecodedSize` und `writeRectified` ist der aus `RegistrationCorpusRun`, mit `root` als Parameter und `imageToTarget` statt `outcome`:

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.Homography
import de.dreier.mytargets.detection.metrics.RegistrationError
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the corpus runs share: the file of a photograph, its decoded size, and stage image 4. */
object CorpusPhotos {

    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val MAGENTA = Scalar(255.0, 0.0, 255.0)

    /** Every file under [root] that one of [entries] names, by name. */
    fun filesByName(root: File, entries: List<CorpusEntry>): Map<String, List<File>> {
        val names = entries.mapTo(HashSet()) { it.imageName }
        return root.walkTopDown().filter { it.isFile && it.name in names }.groupBy { it.name }
    }

    /**
     * The one file under the root that an entry names. Matched by name alone,
     * a second file of that name in another folder would be picked silently.
     */
    fun imageFileOf(root: File, imageName: String, candidates: List<File>): File {
        check(candidates.isNotEmpty()) { "$imageName: no image file under $root" }
        check(candidates.size == 1) {
            "$imageName: ${candidates.size} image files under $root: " +
                candidates.map { it.path }.sorted().joinToString()
        }
        return candidates.single()
    }

    /** A decoder that ignores EXIF shows up here rather than as a wild registration error. */
    fun checkDecodedSize(entry: CorpusEntry, image: Mat) {
        val info = entry.image ?: return
        check(info.matchesDecodedSize(image.cols(), image.rows())) {
            "${entry.imageName}: decoded as ${image.cols()} x ${image.rows()}, the sidecar " +
                "records ${info.width} x ${info.height} at orientation ${info.exifOrientation}"
        }
    }

    /**
     * Stage image 4: the rectified face with the nominal rings in green and, in
     * magenta, the rings where the reference puts them. Where the two lie on
     * each other, the registration agrees with the reference; the gap between
     * them is the error.
     */
    fun writeRectified(image: Mat, imageToTarget: Mat3, reference: List<Double>, imageName: String, file: File) {
        val edge = FaceWarp.edgeFor(imageToTarget)
        val warped = FaceWarp.warp(image, imageToTarget, edge)
        try {
            val k = edge / (2.0 * FaceWarp.EXTENT)
            val centre = FaceWarp.pixelOf(Vec2(0.0, 0.0), edge)
            val toImage = checkNotNull(Homography.inverse(reference.toDoubleArray())) {
                "the reference of $imageName is singular"
            }
            for (r in RegistrationError.RING_RADII) {
                Imgproc.circle(warped, Point(centre.x, centre.y), (r * k).roundToInt(), GREEN, 2)
                val outline = (0 until 360).mapNotNull { step ->
                    val a = 2.0 * PI * step / 360
                    val q = Homography.map(toImage, r * cos(a), r * sin(a)) ?: return@mapNotNull null
                    val p = imageToTarget.mapPoint(Vec2(q[0], q[1])) ?: return@mapNotNull null
                    val px = FaceWarp.pixelOf(p, edge)
                    Point(px.x, px.y)
                }
                if (outline.size < 2) continue
                val polyline = MatOfPoint(*outline.toTypedArray())
                try {
                    Imgproc.polylines(warped, listOf(polyline), true, MAGENTA, 1)
                } finally {
                    polyline.release()
                }
            }
            check(Imgcodecs.imwrite(file.absolutePath, warped)) { "cannot write $file" }
        } finally {
            warped.release()
        }
    }

    /** The nine values of [m], row by row, as RegistrationError takes them. */
    fun values(m: Mat3): List<Double> = List(9) { m[it / 3, it % 3] }
}
```

In `RegistrationCorpusRun.kt`:

- `val names = …` und `val files = root.walkTopDown()…` ersetzen durch `val files = CorpusPhotos.filesByName(root, entries)`.
- `imageFileOf(entry.imageName, …)` → `CorpusPhotos.imageFileOf(root, entry.imageName, …)`.
- `checkDecodedSize(entry, image)` → `CorpusPhotos.checkDecodedSize(entry, image)`.
- `writeRectified(image, outcome, reference, …)` → `CorpusPhotos.writeRectified(image, outcome.imageToTarget, reference, …)`.
- `values(outcome.imageToTarget)` in `rowFor` → `CorpusPhotos.values(outcome.imageToTarget)`.
- Die privaten Funktionen `checkDecodedSize`, `writeRectified`, `values`, `imageFileOf` und die Konstanten `GREEN`, `MAGENTA` löschen, danach die ungenutzten Importe.

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RegistrationCorpusRun*' --rerun -PDETECTION_CORPUS_DIR=../MyTargets-corpus`
Expected: PASS; der Bericht `registration.md` bleibt inhaltlich gleich (28 im Umfang, 28 registriert).

- [ ] **Step 2: Den Pfeillauf schreiben**

`ArrowCorpusRun.kt`:

```kotlin
package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.CommonPoint
import de.dreier.mytargets.detection.geometry.FootPoint
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.ArrowGroups
import de.dreier.mytargets.detection.metrics.ArrowReport
import de.dreier.mytargets.detection.metrics.ArrowRow
import de.dreier.mytargets.detection.metrics.DetectedShotRecord
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
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finds the arrows in every annotated photograph in scope and writes the
 * report and the stage images (arrow design, Korpuslauf und Bericht). It
 * measures and does not judge until the bounds are pinned.
 */
class ArrowCorpusRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var reportDir: File

    private val detector = OpenCvArrowDetector()

    @Before
    fun requireCorpus() {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)
        reportDir = File(
            checkNotNull(System.getProperty("detection.report.dir")) {
                "detection.report.dir is not set; run this through testDevDebugUnitTest"
            }
        )
    }

    @Test
    fun findsTheArrowsAndWritesTheReport() {
        val entries = CorpusLoader.load(root).entries
        val files = CorpusPhotos.filesByName(root, entries)
        val imagesDir = File(reportDir, "arrows")
        imagesDir.deleteRecursively()
        imagesDir.mkdirs()

        val rows = ArrayList<ArrowRow>()
        val truths = ArrayList<TruthDiagnosis>()
        val photos = ArrayList<PhotoDiagnosis>()
        val outcomes = ArrayList<EntryOutcome>()
        val outOfScope = ArrayList<Pair<String, String>>()

        for (entry in entries) {
            val reason = entry.outOfScope
            if (reason != null) {
                outOfScope += entry.imageName to reason
                continue
            }
            if (!entry.isAnnotated) continue
            val file = CorpusPhotos.imageFileOf(root, entry.imageName, files[entry.imageName].orEmpty())
            val image = Imgcodecs.imread(file.absolutePath)
            try {
                check(!image.empty()) { "${entry.imageName}: cannot be decoded" }
                CorpusPhotos.checkDecodedSize(entry, image)
                val folder = File(imagesDir, file.nameWithoutExtension).apply { mkdirs() }
                val request = requestFor(entry, image.cols(), image.rows())
                val analysis = detector.analyse(image, request, PngDebugSink(folder))
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
                            File(folder, "4-entzerrt.png")
                        )
                    }
                    writeTruth(image, analysis, measured.outcome, File(folder, "7-wahrheit.png"))
                }
            } finally {
                image.release()
            }
        }

        val report = File(reportDir, "arrows.md")
        report.writeText(ArrowReport.render("Arrows against the corpus", rows, truths, photos, outcomes, outOfScope))
        println("Arrow report: ${report.absolutePath}")
    }

    private class Measured(
        val row: ArrowRow,
        val truths: List<TruthDiagnosis>,
        val photo: PhotoDiagnosis,
        val outcome: EntryOutcome
    )

    /** Arrow design, Korpuslauf: the size of the end, not the number of listed hits; the fallback focal length without EXIF. */
    private fun requestFor(entry: CorpusEntry, width: Int, height: Int): DetectionRequest {
        val focal = entry.camera?.focalLength35mm
        val intrinsics = if (focal != null) {
            CameraIntrinsics.from35mmEquivalent(width, height, focal)
        } else {
            CameraIntrinsics.approximate(width, height)
        }
        return DetectionRequest(
            FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL,
            entry.shotsPerEnd ?: entry.shots.size, intrinsics
        )
    }

    /** A find as the metrics take it, its ring value from the pure radius like the sidecars'. */
    private fun record(c: Candidate) = DetectedShotRecord(
        scoringRing = WaFullZones.zoneOf(c.local.length),
        printedScore = null,
        position = SpotPosition(c.faceIndex, c.local.x, c.local.y),
        confidence = c.confidence
    )

    private fun measure(
        entry: CorpusEntry,
        analysis: ArrowAnalysis,
        request: DetectionRequest,
        width: Int,
        height: Int
    ): Measured {
        val group = ArrowGroups.of(entry)
        val millis = with(analysis.timings) { StageMillis(registrationMs, warpMs, searchMs, walkMs) }
        return when (analysis) {
            is ArrowAnalysis.NotRegistered -> notRegistered(entry, group, analysis, millis)
            is ArrowAnalysis.Analysed -> analysed(entry, group, analysis, request, width, height, millis)
        }
    }

    private fun notRegistered(
        entry: CorpusEntry,
        group: String,
        analysis: ArrowAnalysis.NotRegistered,
        millis: StageMillis
    ): Measured {
        val row = ArrowRow(
            imageName = entry.imageName, group = group,
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

    private fun analysed(
        entry: CorpusEntry,
        group: String,
        analysis: ArrowAnalysis.Analysed,
        request: DetectionRequest,
        width: Int,
        height: Int,
        millis: StageMillis
    ): Measured {
        val accepted = analysis.selection.accepted
        val detected = accepted.map { record(it) }
        val match = ShotMatching.match(entry, detected)
        val outcome = EntryOutcome(entry, match, detected)

        // The matching of the metrics over every candidate on the face: what the
        // search had before the selection. Ranks count all candidates by score.
        val located = analysis.candidates.withIndex().filter { it.value.located != null }
        val all = ShotMatching.match(entry, located.map { record(it.value.located!!) })
        val byTruth = all.pairs.associateBy { it.truthIndex }
        val truths = entry.shots.indices.map { t ->
            val hit = byTruth[t]?.let { located[it.detectedIndex] }
            TruthDiagnosis(
                imageName = entry.imageName,
                group = group,
                truthIndex = t,
                rank = hit?.let { it.index + 1 },
                confidence = hit?.value?.confidence,
                lineOffset = hit?.value?.offsetFromFoot,
                accepted = hit != null && accepted.any { it === hit.value.located }
            )
        }
        val matchedCandidates = all.pairs.map { located[it.detectedIndex].value }
        val bestFalse = all.unmatchedDetected.maxOfOrNull { located[it].value.confidence }

        val foot = analysis.footPoint
        val reference = entry.registration?.imageToTarget
        val referenceFoot = reference?.let { FootPoint.of(Mat3.of(*it.toDoubleArray()), request.intrinsics) }
        val common = CommonPoint.of(
            matchedCandidates.filter { it.refinement != TipRefinement.NO_SHAFT }.map { it.line }
        )
        val row = ArrowRow(
            imageName = entry.imageName,
            group = group,
            outcome = ArrowRow.REGISTERED,
            detail = null,
            footPointFromCentre = foot?.length,
            registrationError = reference?.let {
                RegistrationError.between(it, CorpusPhotos.values(analysis.registration.imageToTarget), width, height)?.max
            },
            footPointShift = if (foot != null && referenceFoot != null) foot.distanceTo(referenceFoot) else null,
            largestLineOffset = matchedCandidates.maxOfOrNull { abs(it.offsetFromFoot) },
            commonPointFromFoot = if (foot != null && common != null) common.distanceTo(foot) else null,
            listed = entry.shots.size,
            unresolved = entry.unresolvedArrows,
            candidates = analysis.candidates.size,
            accepted = accepted.size,
            matched = match.pairs.size,
            falsePositives = Metrics.over(listOf(outcome)).falsePositives,
            selection = analysis.selection.reason.name,
            largestPositionError = match.pairs.mapNotNull { it.distance }.maxOrNull(),
            noShaft = analysis.candidates.count { it.refinement == TipRefinement.NO_SHAFT },
            ranOut = analysis.candidates.count { it.refinement == TipRefinement.RAN_OUT },
            millis = millis
        )
        return Measured(row, truths, PhotoDiagnosis(entry.imageName, group, bestFalse), outcome)
    }

    /**
     * Stage image 7: the rectified face with every listed hit and its budget as
     * a cyan circle, every accepted find as a green dot, and matched pairs joined
     * in white. Only the run knows the truth.
     */
    private fun writeTruth(image: Mat, analysis: ArrowAnalysis.Analysed, outcome: EntryOutcome, file: File) {
        val imageToTarget = analysis.registration.imageToTarget
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

    private companion object {
        val CYAN = Scalar(255.0, 255.0, 0.0)
        val GREEN = Scalar(0.0, 200.0, 0.0)
        val WHITE = Scalar(255.0, 255.0, 255.0)
    }
}
```

- [ ] **Step 3: Laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCorpusRun*' --rerun -PDETECTION_CORPUS_DIR=../MyTargets-corpus`
Expected: PASS, der Pfad des Berichts steht in der Ausgabe. Prüfen: `detection/build/reports/detection/arrows.md` hat die Abschnitte *Groups*, *Metrics over every photograph in scope*, *Photographs, schraeg*, *Photographs, frontal*, *Candidates before the selection* und *Out of scope*; unter `arrows/` liegt für jedes der 28 Fotos ein Ordner mit `1-farbklassen.png` bis `3-ringe.png`, `4-entzerrt.png`, `5-kandidaten.png`, `6-einschuesse.png` und `7-wahrheit.png`. Ohne `DETECTION_CORPUS_DIR` wird der Test übersprungen.

- [ ] **Step 4: `BUILDING.md`**

Nach dem Absatz über den Registrierungslauf, der mit „…and nothing is written.“ endet, einfügen:

````markdown
The arrow run finds the arrows in every photograph in scope and measures
them against the listed hits:

```
./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCorpusRun' --rerun
```

It writes `detection/build/reports/detection/arrows.md` and, beside it, one
folder of stage images per photograph. It measures and does not judge; the
bounds for the oblique photographs come in a later step.
````

- [ ] **Step 5: Commit**

```bash
git add detection/src/test/java/de/dreier/mytargets/detection/registration/CorpusPhotos.kt \
  detection/src/test/java/de/dreier/mytargets/detection/registration/RegistrationCorpusRun.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRun.kt \
  BUILDING.md
git commit -m "detection: the arrow run over the corpus, its report and stage image 7"
```

---

## Task 13: Der erste Bericht und die Zuversicht

Diese Aufgabe schreibt keinen neuen Code, sofern der Bericht keinen verlangt. Sie endet mit einem Halt beim Nutzer: Das Design legt die Startwerte der Zuversicht fest und verlangt, sie vor den Schranken am Bericht nachzustellen. Das ist eine Abwägung an 15 Fotos, und die trifft der Nutzer.

**Files:**
- Modify: `docs/design/2026-09-11-detection-arrows-design.md` (Abschnitt *Offene Punkte*)
- Modify, nur nach Zustimmung: `detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowCandidates.kt` (`LENGTH_SATURATION`, `UNREFINED_FACTOR`)

**Interfaces:**
- Consumes: den Bericht aus Task 12
- Produces: den Stand der Zuversicht, mit dem Task 14 die Schranken festschreibt

- [ ] **Step 1: Den Lauf starten**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCorpusRun*' --rerun -PDETECTION_CORPUS_DIR=../MyTargets-corpus`
Expected: PASS, der Bericht ist neu geschrieben.

- [ ] **Step 2: Den Bericht lesen**

Aus `arrows.md` für die Gruppe `schraeg` notieren:

1. aus *Groups*: gelistete Treffer, zugeordnete, Erkennungsrate, Fehlfunde, Ringtreue mit Nenner, Median und 95. Perzentil des Positionsfehlers
2. aus *Candidates before the selection*: wie viele Treffer einen Kandidaten hatten, wie viele erst die Auswahl verloren hat, Median der Linienabstände und wie viele über 0,03
3. für jedes Foto mit einem von der Auswahl verlorenen Treffer: die Zuversicht dieses Treffers aus der Tabelle je Treffer und die beste Zuversicht eines falschen Kandidaten auf demselben Foto
4. aus *Photographs, schraeg*: die Spalte *Common point from Q* und die Laufzeiten, Median je Stufe
5. aus *Photographs, schraeg*: jedes Foto mit weniger Kandidaten als `expectedShots` und seine Fehlfunde. `CandidateSelection` nimmt dann alle Kandidaten an, und die Abstandsregel aus Stufe 7 läuft gar nicht; die relative Zuversicht hat keine Untergrenze, ein `NO_SHAFT`-Rest mit Zuversicht nahe 0 wird so zum Treffer. Das Design nimmt an, solche Kandidaten bestünden die Abstandsregel praktisch nie; das gilt nur bei Überschuss.

- [ ] **Step 3: Entscheiden**

- **Startwerte bleiben,** wenn in der Gruppe `schraeg` kein Treffer von der Auswahl verloren wurde, auf jedem Foto die beste falsche Zuversicht um mindestens 0,15 unter der niedrigsten angenommenen echten liegt und kein Fehlfund von einem Foto mit weniger Kandidaten als `expectedShots` stammt.
- **Sonst Halt beim Nutzer:** die Zahlen aus Step 2 vorlegen und benennen, welcher Hebel sie trennen würde — die Sättigungslänge, der Faktor für einen gescheiterten Lauf, die Abstandsregel aus Stufe 7 selbst oder eine Untergrenze der Zuversicht, unter der ein Kandidat nicht in die Auswahl geht. Die Untergrenze kennt das Design nicht; sie ist der einzige Hebel gegen Fehlfunde aus Punkt 5 und wäre eine Änderung am Design und an `CandidateSelection`, nicht an `ArrowCandidates`. Ohne Zustimmung ändert sich nichts. Mit Zustimmung die Konstante in `ArrowCandidates.kt` ändern, `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCandidatesTest*'` laufen lassen (der Test rechnet mit den Konstanten, siehe Präzisierung 13) und Step 1 und 2 wiederholen.

- [ ] **Step 4: Die erste Messung ins Design**

In `docs/design/2026-09-11-detection-arrows-design.md` unter *Offene Punkte* als ersten Punkt einfügen und mit den Zahlen aus Step 2 füllen; die Ringtreue steht nie ohne Nenner und Erkennungsrate (Haupt-Spec, *Kennzahlen*):

```markdown
- **Erste Messung (<Datum des Laufs>).** Schräg: <zugeordnet> von <gelistet> Treffern
  gefunden (<Erkennungsrate>), <Fehlfunde> Fehlfunde, Ringtreue <Rate> (<richtig>/<vergleichbar>),
  Positionsfehler Median <m>, 95. Perzentil <p>. <mit Kandidat> Treffer hatten einen Kandidaten,
  <verloren> hat erst die Auswahl verloren; Linienabstände im Median <a>, <über> über 0,03.
  Frontal: <dieselben Zahlen>. Laufzeit je Foto im Median: Registrierung <r> ms, Entzerren
  <w> ms, Suche <s> ms, Lauf <l> ms. Die Startwerte der Zuversicht <sind geblieben | wurden
  auf … geändert, weil …>.
```

- [ ] **Step 5: Commit**

```bash
git add docs/design/2026-09-11-detection-arrows-design.md
git commit -m "Design 3b: record the first arrow measurement"
```

Wurde eine Konstante geändert, gehört `ArrowCandidates.kt` in denselben Commit.

---

## Task 14: Die Schranken

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/Percentiles.kt`
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/Metrics.kt`
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ArrowBounds.kt`
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ArrowReport.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ArrowBoundsTest.kt`
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRun.kt`
- Modify: `BUILDING.md`

**Interfaces:**
- Consumes: `Metrics` (`expectedShots`, `matchedShots`, `falsePositives`, `correctScores`, `scoreComparableShots`, `positionErrors`), `ShotMatching.DEFAULT_POSITION_TOLERANCE`, den Lauf aus Task 12
- Produces:
  - `object Percentiles { fun of(values: List<Double>, fraction: Double): Double? }`
  - `object PositionBound { const val NOISE = 0.002; fun of(errors: List<Double>, fraction: Double, worstBudget: Double): Double? }`
  - `data class ArrowPins(photographs, listed, matched, falsePositives, correctScores, comparableScores, medianErrorBound: Double?, p95ErrorBound: Double?)`
  - `class ArrowMeasurement(photographs, listed, matched, falsePositives, correctScores, comparableScores, positionErrors)` mit `companion fun of(photographs: Int, metrics: Metrics)`
  - `object ArrowBounds { fun pinsFor(measurement, worstBudget): ArrowPins; fun violations(measurement, pins): List<String> }`
  - `ArrowReport.pinsSection(pins: ArrowPins): String`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`ArrowBoundsTest.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class ArrowBoundsTest {

    @Test
    fun thePositionBoundCoversEverySingleFlippedMatch() {
        // A match that flips adds an error, loses one, or swaps one for another.
        val random = Random(7)
        val budget = 0.08
        repeat(200) {
            val n = 21 + random.nextInt(80)
            val errors = List(n) { random.nextDouble() * 0.04 }
            for (fraction in listOf(0.5, 0.95)) {
                val bound = PositionBound.of(errors, fraction, budget)!! - PositionBound.NOISE
                val changed = buildList {
                    for (x in listOf(0.0, 0.02, 0.04, budget)) add(errors + x)
                    for (i in errors.indices) add(errors.filterIndexed { j, _ -> j != i })
                    for (i in errors.indices step 3) {
                        for (x in listOf(0.0, 0.04, budget)) add(errors.filterIndexed { j, _ -> j != i } + x)
                    }
                }
                for (c in changed) assertThat(Percentiles.of(c, fraction)!!).isAtMost(bound + 1e-12)
            }
        }
    }

    @Test
    fun fromTwentyOneErrorsTheBudgetDoesNotMatter() {
        val errors = List(21) { 0.001 * (it + 1) }

        assertThat(PositionBound.of(errors, 0.95, 0.06)).isEqualTo(PositionBound.of(errors, 0.95, 0.08))
    }

    @Test
    fun withFewErrorsTheBudgetCounts() {
        // [0.01, 0.02] becomes [0.02, 0.08]; its 95th percentile is 0.02 + 0.95 * 0.06.
        assertThat(PositionBound.of(listOf(0.01, 0.02), 0.95, 0.08)!!)
            .isWithin(1e-12).of(0.077 + PositionBound.NOISE)
        assertThat(PositionBound.of(emptyList(), 0.95, 0.08)).isNull()
    }

    private val pins = ArrowPins(
        photographs = 15, listed = 86, matched = 60, falsePositives = 5,
        correctScores = 50, comparableScores = 55, medianErrorBound = 0.010, p95ErrorBound = 0.030
    )

    private fun measurement(
        matched: Int = 60,
        falsePositives: Int = 5,
        correct: Int = 50,
        comparable: Int = 55,
        errors: List<Double> = List(60) { 0.005 },
        photographs: Int = 15,
        listed: Int = 86
    ) = ArrowMeasurement(photographs, listed, matched, falsePositives, correct, comparable, errors)

    @Test
    fun oneArrowOfSlackPasses() {
        val within = measurement(matched = 59, falsePositives = 6, correct = 49, comparable = 54)

        assertThat(ArrowBounds.violations(within, pins)).isEmpty()
    }

    @Test
    fun twoArrowsBreakTheBounds() {
        val broken = ArrowBounds.violations(
            measurement(matched = 58, falsePositives = 7, correct = 48, comparable = 53), pins
        )

        assertThat(broken).hasSize(4)
        assertThat(broken[0]).isEqualTo("matched hits: 58, the bound is at least 59")
    }

    @Test
    fun aChangedCorpusIsReportedInsteadOfTheBounds() {
        val broken = ArrowBounds.violations(measurement(photographs = 16, listed = 92), pins)

        assertThat(broken).hasSize(1)
        assertThat(broken[0]).contains("set the bounds again against a new report")
    }

    @Test
    fun anErrorAboveItsBoundBreaksIt() {
        val broken = ArrowBounds.violations(measurement(errors = List(60) { 0.02 }), pins)

        assertThat(broken).containsExactly("median position error: 0.0200, the bound is at most 0.0100")
    }

    @Test
    fun thePinsAreTheCountsAndThePositionBounds() {
        val errors = List(30) { 0.001 * (it + 1) }

        val p = ArrowBounds.pinsFor(measurement(errors = errors), worstBudget = 0.08)

        assertThat(p.matched).isEqualTo(60)
        assertThat(p.listed).isEqualTo(86)
        assertThat(p.medianErrorBound!!).isWithin(1e-12).of(PositionBound.of(errors, 0.5, 0.08)!!)
        assertThat(p.p95ErrorBound!!).isWithin(1e-12).of(PositionBound.of(errors, 0.95, 0.08)!!)
    }

    @Test
    fun theReportPrintsThePinsToCopy() {
        assertThat(ArrowReport.pinsSection(pins)).contains(
            "ArrowPins(photographs = 15, listed = 86, matched = 60, falsePositives = 5, " +
                "correctScores = 50, comparableScores = 55, medianErrorBound = 0.010000, " +
                "p95ErrorBound = 0.030000)"
        )
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection-corpus:test --tests '*ArrowBoundsTest*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'PositionBound'`, `'Percentiles'`, `'ArrowPins'`, `'ArrowBounds'`, `'ArrowMeasurement'`, `'pinsSection'`.

- [ ] **Step 3: Das Perzentil teilen**

`Percentiles.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

/** Linear interpolation between order statistics, the common definition; shared by the metrics and their bounds. */
object Percentiles {
    fun of(values: List<Double>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = fraction * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val weight = rank - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }
}
```

In `Metrics.kt` die private Funktion `percentile` samt KDoc löschen und ihre drei Aufrufe in `medianPositionError`, `p95PositionError` und `medianRejectedDistance` von `percentile(` auf `Percentiles.of(` umstellen.

- [ ] **Step 4: Die Schranken**

`ArrowBounds.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

import java.util.Locale

/** Arrow design, Schranken: the percentile a bound on the position error allows. */
object PositionBound {

    /** Noise between platforms: 1.4 to 2.7 px of the rectified image. */
    const val NOISE = 0.002

    /**
     * The [fraction] percentile after the smallest of [errors] is swapped for
     * [worstBudget] -- the worst a single flipped match can do -- plus NOISE.
     * From 21 errors on, the value of [worstBudget] no longer matters. Null
     * when nothing was measured.
     */
    fun of(errors: List<Double>, fraction: Double, worstBudget: Double): Double? {
        if (errors.isEmpty()) return null
        val swapped = errors.sorted().drop(1) + worstBudget
        return Percentiles.of(swapped, fraction)!! + NOISE
    }
}

/** What the arrow run pins for the oblique photographs. */
data class ArrowPins(
    val photographs: Int,
    val listed: Int,
    val matched: Int,
    val falsePositives: Int,
    val correctScores: Int,
    val comparableScores: Int,
    val medianErrorBound: Double?,
    val p95ErrorBound: Double?
)

/** One measurement of the oblique group: the numbers the bounds look at. */
class ArrowMeasurement(
    val photographs: Int,
    val listed: Int,
    val matched: Int,
    val falsePositives: Int,
    val correctScores: Int,
    val comparableScores: Int,
    val positionErrors: List<Double>
) {
    val medianError: Double?
        get() = Percentiles.of(positionErrors, 0.5)

    val p95Error: Double?
        get() = Percentiles.of(positionErrors, 0.95)

    companion object {
        fun of(photographs: Int, metrics: Metrics) = ArrowMeasurement(
            photographs, metrics.expectedShots, metrics.matchedShots, metrics.falsePositives,
            metrics.correctScores, metrics.scoreComparableShots, metrics.positionErrors
        )
    }
}

/**
 * Arrow design, Schranken. Counts are pinned, not rates: with the number of
 * listed hits pinned the rates follow, and the slack is exactly one arrow.
 * Numerator and denominator of the ring accuracy are held separately, because
 * a ratio can rise while both fall.
 */
object ArrowBounds {

    /** The pins for [measurement]: its counts, and the error bounds of PositionBound. */
    fun pinsFor(measurement: ArrowMeasurement, worstBudget: Double) = ArrowPins(
        photographs = measurement.photographs,
        listed = measurement.listed,
        matched = measurement.matched,
        falsePositives = measurement.falsePositives,
        correctScores = measurement.correctScores,
        comparableScores = measurement.comparableScores,
        medianErrorBound = PositionBound.of(measurement.positionErrors, 0.5, worstBudget),
        p95ErrorBound = PositionBound.of(measurement.positionErrors, 0.95, worstBudget)
    )

    /** Every bound [measurement] breaks, one sentence each; empty when it keeps them all. */
    fun violations(measurement: ArrowMeasurement, pins: ArrowPins): List<String> {
        if (measurement.photographs != pins.photographs || measurement.listed != pins.listed) {
            return listOf(
                "the oblique group has ${measurement.photographs} photographs and ${measurement.listed} " +
                    "listed hits, the bounds were pinned at ${pins.photographs} and ${pins.listed}: " +
                    "the corpus changed, so set the bounds again against a new report"
            )
        }
        val broken = ArrayList<String>()
        if (measurement.matched < pins.matched - 1) {
            broken += "matched hits: ${measurement.matched}, the bound is at least ${pins.matched - 1}"
        }
        if (measurement.falsePositives > pins.falsePositives + 1) {
            broken += "false positives: ${measurement.falsePositives}, the bound is at most ${pins.falsePositives + 1}"
        }
        if (measurement.correctScores < pins.correctScores - 1) {
            broken += "correct ring values: ${measurement.correctScores}, the bound is at least ${pins.correctScores - 1}"
        }
        if (measurement.comparableScores < pins.comparableScores - 1) {
            broken += "hits comparable for the ring accuracy: ${measurement.comparableScores}, " +
                "the bound is at least ${pins.comparableScores - 1}"
        }
        errorBound("median position error", measurement.medianError, pins.medianErrorBound)?.let { broken += it }
        errorBound("95th percentile of the position error", measurement.p95Error, pins.p95ErrorBound)?.let { broken += it }
        return broken
    }

    private fun errorBound(name: String, value: Double?, bound: Double?): String? = when {
        bound == null -> null
        value == null -> "$name: nothing measured, the bound is at most ${format(bound)}"
        value > bound -> "$name: ${format(value)}, the bound is at most ${format(bound)}"
        else -> null
    }

    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)
}
```

In `ArrowReport` vor den privaten Hilfen ergänzen:

````kotlin
    /**
     * The block to copy into ArrowCorpusRun's PINS (arrow design, Schranken).
     * Six decimals, so rounding never moves a bound below the value it came from.
     */
    fun pinsSection(pins: ArrowPins): String {
        fun bound(v: Double?) = v?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "null"
        return buildString {
            appendLine()
            appendLine("## Bounds, oblique photographs")
            appendLine()
            appendLine(
                "What this run would pin. The check allows one arrow of slack on each count; " +
                    "the error bounds already contain theirs."
            )
            appendLine()
            appendLine("```kotlin")
            appendLine(
                "ArrowPins(photographs = ${pins.photographs}, listed = ${pins.listed}, " +
                    "matched = ${pins.matched}, falsePositives = ${pins.falsePositives}, " +
                    "correctScores = ${pins.correctScores}, comparableScores = ${pins.comparableScores}, " +
                    "medianErrorBound = ${bound(pins.medianErrorBound)}, p95ErrorBound = ${bound(pins.p95ErrorBound)})"
            )
            appendLine("```")
        }
    }
````

- [ ] **Step 5: Laufen lassen, bestehen prüfen**

Run: `./gradlew :detection-corpus:test`
Expected: PASS, auch `MetricsTest` und `MetricsReportTest` unverändert.

- [ ] **Step 6: Den Block der Schranken in den Bericht**

In `ArrowCorpusRun.findsTheArrowsAndWritesTheReport` die Zeile `report.writeText(…)` ersetzen durch:

```kotlin
        val oblique = outcomes.filter { ArrowGroups.of(it.entry) == ArrowGroups.OBLIQUE }
        val measurement = ArrowMeasurement.of(rows.count { it.group == ArrowGroups.OBLIQUE }, Metrics.over(oblique))
        // The worst a matched error can be: the detector's budget plus the largest annotation tolerance.
        val worstBudget = ShotMatching.DEFAULT_POSITION_TOLERANCE +
            (oblique.flatMap { it.entry.shots }.mapNotNull { it.positionTolerance }.maxOrNull() ?: 0.0)
        report.writeText(
            ArrowReport.render("Arrows against the corpus", rows, truths, photos, outcomes, outOfScope) +
                ArrowReport.pinsSection(ArrowBounds.pinsFor(measurement, worstBudget))
        )
```

Importe ergänzen: `de.dreier.mytargets.detection.metrics.ArrowBounds`, `ArrowMeasurement`, `ArrowPins`.

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCorpusRun*' --rerun -PDETECTION_CORPUS_DIR=../MyTargets-corpus`
Expected: PASS; `arrows.md` endet mit dem Abschnitt *Bounds, oblique photographs* und einem Block `ArrowPins(…)`. Die Werte müssen zur ersten Messung aus Task 13 passen; weichen sie ab, zuerst klären, warum.

- [ ] **Step 7: Festschreiben und prüfen**

Im `companion object` von `ArrowCorpusRun` ergänzen, mit der Zeile `ArrowPins(…)` wörtlich aus dem Bericht:

```kotlin
        /**
         * Pinned from the report of <Datum des Laufs> (arrow design, Schranken):
         * the block under "Bounds, oblique photographs". When the corpus changes
         * the run fails and says so; set them again against a new report.
         */
        val PINS = ArrowPins(photographs = …, listed = …, matched = …, falsePositives = …, correctScores = …, comparableScores = …, medianErrorBound = …, p95ErrorBound = …)
```

Die Auslassungspunkte sind die Werte aus dem Bericht; sonst ändert sich an der Zeile nichts. Nach `println("Arrow report: …")` anhängen:

```kotlin
        // Checked after writing, so a failing run still leaves its report.
        val broken = ArrowBounds.violations(measurement, PINS)
        assertWithMessage("bounds of the oblique photographs:\n" + broken.joinToString("\n"))
            .that(broken).isEmpty()
```

Import ergänzen: `com.google.common.truth.Truth.assertWithMessage`. Den KDoc der Klasse im letzten Satz ändern in: „It fails when a metric of the oblique photographs gets worse than its bound.“

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCorpusRun*' --rerun -PDETECTION_CORPUS_DIR=../MyTargets-corpus`
Expected: PASS

- [ ] **Step 8: `BUILDING.md`**

Im Absatz aus Task 12 den Satz „It measures and does not judge; the bounds for the oblique photographs come in a later step.“ ersetzen durch:

```markdown
It fails when a metric of the oblique photographs gets worse than its bound,
or when the oblique group of the corpus has changed; the end of the report
then shows the values to pin anew.
```

- [ ] **Step 9: Die ganze Suite**

Run: `./gradlew :detection:testDevDebugUnitTest :detection-corpus:test --rerun -PDETECTION_CORPUS_DIR=../MyTargets-corpus`
Expected: PASS, mit `RegistrationCorpusRun`, `ArrowCorpusRun`, `FootPointCorpusTest` und `RealCorpusTest`.

- [ ] **Step 10: Commit**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/Percentiles.kt \
  detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/Metrics.kt \
  detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ArrowBounds.kt \
  detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ArrowReport.kt \
  detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ArrowBoundsTest.kt \
  detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRun.kt \
  BUILDING.md
git commit -m "detection: pin the metrics of the oblique photographs, one arrow of slack"
```

---

## Was dieser Plan nicht liefert

- Frontale Fotos als Ziel; die Stufen 4 und 5 der Haupt-Spec und Befiederung und Nocke als Merkmal bleiben Wege für einen Folgeplan.
- Die Schätzung des Fluchtpunkts aus den Streifen für geneigte Pfeile. Der Bericht liefert mit dem gemeinsamen Punkt die Grundlage für die Entscheidung.
- 3-Spot-Auflagen und `WA6Ring`.
- Alles in `:app`: die Umwandlung `Bitmap` → `Mat`, die Brennweite aus EXIF, die Linienregel mit Pfeilradius, den Debug-Bildschirm.
- Laufzeit auf dem Gerät und eine gröbere Suche; der Bericht nennt die Zeiten auf dem Desktop.
- Auflagen unter 80 cm; woher der Maßstab der Flanken kommt, entscheidet die Integration.
- Ein dunkler Schaft, der den schwarzen Ring so schräg kreuzt, dass die Lücke 0,3 übersteigt. Das Stück dahinter wird ein eigener Kandidat mit `REFINED` und Einschuss am Ringrand bei 0,8, und die Regel „zwei `REFINED` auf einer Geraden bleiben zwei" behält ihn. Auf dem Korpus laufen die Schäfte fast radial durch den Ring, die Lücke liegt bei 0,2 bis 0,27; ein solcher Fall zeigt sich im Bericht als Fehlfund bei Radius 0,8.

## Self-Review

**Abdeckung des Designs:**

| Design | Task |
|---|---|
| Ziel, *Fertig ist 3b* | 12, 13, 14 |
| Umfang: Stufen 6 und 7 für `WAFull` | 5 bis 9 |
| Umfang: Bild in der Schnittstelle | 1 |
| Umfang: Korpuslauf, Bericht, Kandidatendiagnose, Debug-Bilder | 10, 11, 12 |
| Umfang: Schranken der schrägen Fotos | 14 |
| Entscheidungen: frontal ohne Schranke, Verfahren, Stufen 4 und 5, eigene Registrierung, Arbeitsbild, `Mat`, Schranken | 11 und 14, 5 bis 7, —, 9 und 12, 4, 1, 14 |
| Aufbau: `arrows/`, Fußpunkt und gemeinsamer Punkt in `geometry/`, Korpusmodul liest `view`, README des Korpus, `:app` unberührt | 4 bis 10, 2, 3, 3, — |
| Schnittstelle: `transitions`, `DebugSink`, `analyse`/`detect`, `faceConfidence`, Vertragstest mit `OpenCvRule` | 1, 9 |
| Verfahren 1 Arbeitsbild samt Maske | 4 |
| Verfahren 2 Fußpunkt | 2, 3 |
| Verfahren 3 Suche samt Neigungsgrenze | 5, 6 |
| Verfahren 4 Lauf samt Gerade des Kandidaten | 7, 8 |
| Verfahren 5 Zusammenlegen, Zuversicht | 8, 13 |
| Verfahren 6 Spot und Auswahl | 9 |
| Abweichungen von den Werkzeugen | 2, 4, 6, 7, 8 |
| Korpuslauf: Anfrage je Foto, Ringwert, Zuordnung | 12 |
| Bericht 1 bis 3, gemeinsamer Punkt | 11, 12 |
| Debug-Bilder 1 bis 7 | 10, 12 |
| Schranken samt Tausch-Regel | 14 |
| Fehlerfälle | 9 (Registrar, `CandidateSelection` bei null Kandidaten) |
| Tests 1 Logik ohne Bild | 2, 5, 6, 7, 8, 14 |
| Tests 2 synthetische Bilder | 9, 10 |
| Tests 3 Korpusgeometrie | 3 |
| Tests 4 Korpuslauf | 12 |

**Platzhalter:** Die einzigen offenen Werte sind Messwerte: das Datum und die Zahlen der ersten Messung (Task 13, Step 4) und die festzuschreibenden Schranken (Task 14, Step 7). Beide stehen im Bericht, und die Schritte sagen, wo.

**Typen:** `ArrowCandidate` (Task 8) trägt `tip`, `far`, `refinement`, `offsetFromFoot`, `confidence`, `located` und `line`, wie Task 9, 10 und 12 sie lesen. `TruthDiagnosis`, `PhotoDiagnosis`, `ArrowRow` und `StageMillis` (Task 11) haben die Parameter, mit denen Task 12 sie baut. `ArrowMeasurement.of` (Task 14) liest die Felder von `Metrics`, die es gibt.
