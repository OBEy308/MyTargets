# Roll-Anker im Registrar — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `OpenCvFaceRegistrar` legt die Drehung um den Scheibenmittelpunkt aus den geraden Kanten um die Auflage fest (Papier, Stroh), statt aus „oben im Bild“, sodass alle Ansichten einer Passe auf höchstens 5 Grad dieselbe Richtung liefern; ohne klaren Befund bleibt alles wie heute.

**Architecture:** Nach `Orientation.orient` entzerrt `RollAnchor.measure` das Arbeitsbild bis Radius 1,6, sammelt Sobel-Gradienten im Kreisring 1,02 bis 1,6, zählt nur Kanten quer zum Radius (Schäfte fallen heraus) und faltet ihre Richtung in ein Histogramm modulo 90 Grad. Liegt die Spitze deutlich über dem Mittel, dreht der Registrar die Homographie um den gefundenen Winkel (in (−45, 45] Grad). `Registered.roll` berichtet Messung, Stärke und ob verankert wurde. Ein neuer Korpuslauf misst die Übereinstimmung der Ansichten einer Passe und setzt die Stärke-Schwelle.

**Tech Stack:** Kotlin, Android-Library-Modul `:detection`, OpenCV-Java (`org.openpnp:opencv` im Test), JUnit 4, Truth; `:detection-corpus` für Metriken und Korpus.

**Spec:** `docs/design/2026-09-23-registration-roll-anchor-design.md` (bindend), dazu `docs/design/2026-09-16-roll-in-the-measurement.md` (Rolle gemessen) und `docs/design/2026-09-10-detection-registration-design.md` (Registrar).

**Voraussetzung:** Branch `plan/roll-anchor` von `master`, nachdem der Design-Branch `design/roll-anchor` (Spec und dieser Plan) gemergt ist. Korpus-Repo auf `ac64539` oder später. Gradle braucht `JAVA_HOME` auf ein JDK 17 (siehe `BUILDING.md`); alle Testbefehle unten laufen aus der Wurzel des Repos.

## Global Constraints

- Kriterium: höchstens **5 Grad** zwischen je zwei verankerten Ansichten derselben Passe.
- Korrektur immer in (−45, 45] Grad; „oben im Bild“ bleibt der Startwert.
- Der Anker lässt die Registrierung nie scheitern; ohne Befund Homographie wie heute.
- Entzerrung bis Ausdehnung **1,6**, Kreisring **1,02 bis 1,6**, Querregel **30 Grad**, Histogramm **90 Fächer** zu 1 Grad.
- Bestehende Aufrufer von `FaceWarp` und `RegistrationError.between` bleiben unverändert (Vorgabewerte).
- Code, Kommentare und Testnamen auf Englisch wie im Modul; Berichte und Pläne wie bisher.

## Präzisierungen gegenüber dem Design

1. **`measure` statt `find`.** `RollAnchor.measure` liefert die Messung immer, wenn überhaupt Pixel gezählt wurden (Winkel, Stärke, Pixelzahl); `Measurement.isAnchor` entscheidet mit `MIN_PIXELS` und `MIN_STRENGTH`. So kann der Korpuslauf jede Schwelle nachrechnen, ohne neu zu registrieren.
2. **`Roll` trägt die Messung auch unverankert** (`measuredDegrees`, `strength`, `pixels`); `correctionDegrees` ist 0, wenn nicht verankert.
3. **Die synthetischen Registrar-Tests bleiben beim strengen `between`.** Ohne Papier gibt es keinen Anker, der Fehler ist dort derselbe wie heute. Nur die Korpusläufe (`RegistrationCorpusRun`, `ArrowCorpusRun`, `LearnedArrowCorpusRun`) wechseln auf `betweenUpToRoll`.
4. **Die Drehspalte steht im Bericht des neuen Laufs**, nicht in `registration.md`: Dessen Tabelle bleibt unverändert, ihre Fehler werden drehfrei.
5. **Debugbild `4-rollanker`**: entzerrtes Graubild bis 1,6, gezählte Pixel grün, gefundene Richtungen rot durch die Mitte, darunter das Histogramm.

## Dateien

| Datei | Aufgabe |
|---|---|
| `detection-corpus/.../metrics/RegistrationError.kt` | Ändern: `rollDegrees`, `betweenUpToRoll` |
| `detection/.../registration/FaceWarp.kt` | Ändern: Ausdehnung als Parameter |
| `detection/src/test/.../registration/SyntheticFace.kt` | Ändern: Papierquadrat und Schäfte |
| `detection/.../registration/RollAnchor.kt` | Neu: Messung |
| `detection/.../registration/RollAnchorImages.kt` | Neu: Debugbild |
| `detection/.../registration/Registration.kt` | Ändern: `Roll`, `Registered.roll` |
| `detection/.../registration/OpenCvFaceRegistrar.kt` | Ändern: Anker einbauen |
| `detection/src/test/.../registration/RollAnchorTest.kt` | Neu |
| `detection/src/test/.../registration/RollAnchorCorpusRun.kt` | Neu: Kriterium, Schwelle, Pins |
| `detection/src/test/.../arrows/ArrowCorpusRun.kt`, `LearnedArrowCorpusRun.kt` | Ändern: drehfreier Fehler, neue Pins |

`detection/...` steht für `detection/src/main/java/de/dreier/mytargets/detection`, `detection/src/test/...` für `detection/src/test/java/de/dreier/mytargets/detection`, `detection-corpus/...` für `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection`.

---

### Task 1: Registrierungsfehler ohne Rolle

**Files:**
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/RegistrationError.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/RegistrationErrorTest.kt`

**Interfaces:**
- Produces: `RegistrationError(median, max, visiblePoints, rollDegrees: Double = 0.0)`; `RegistrationError.betweenUpToRoll(reference: List<Double>, predicted: List<Double>, imageWidth: Int, imageHeight: Int): RegistrationError?` — Fehler nach der besten Drehung der Vorhersage um die Mitte, `rollDegrees` ist diese Drehung (Vorhersage = `rotation(rollDegrees) * Referenz`).

- [ ] **Step 1: Failing tests schreiben** (in `RegistrationErrorTest`, die Helfer `frontal`, `tilted`, `times` gibt es dort schon)

```kotlin
    private fun rotation(degrees: Double): List<Double> {
        val a = Math.toRadians(degrees)
        return listOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)
    }

    @Test
    fun aRotatedPredictionHasNoErrorUpToRollAndReportsTheRoll() {
        val predicted = times(rotation(10.0), tilted)

        val error = RegistrationError.betweenUpToRoll(tilted, predicted, 2000, 1800)!!

        assertThat(error.max).isLessThan(1e-9)
        assertThat(error.rollDegrees).isWithin(1e-6).of(10.0)
    }

    @Test
    fun thePlainErrorStillSeesTheRoll() {
        val predicted = times(rotation(10.0), frontal)

        val error = RegistrationError.between(frontal, predicted, 2000, 1800)!!

        // 2 sin(5 deg) at radius 1.0
        assertThat(error.max).isWithin(1e-6).of(2.0 * sin(Math.toRadians(5.0)))
        assertThat(error.rollDegrees).isEqualTo(0.0)
    }

    @Test
    fun aScaledPredictionKeepsItsErrorUpToRoll() {
        val scale = listOf(1.01, 0.0, 0.0, 0.0, 1.01, 0.0, 0.0, 0.0, 1.0)
        val predicted = times(rotation(-20.0), times(scale, frontal))

        val error = RegistrationError.betweenUpToRoll(frontal, predicted, 2000, 1800)!!

        assertThat(error.max).isWithin(1e-6).of(0.01)
        assertThat(error.rollDegrees).isWithin(1e-6).of(-20.0)
    }
```

- [ ] **Step 2: Laufen lassen, Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests '*RegistrationErrorTest'`
Expected: FAIL, `betweenUpToRoll` und `rollDegrees` unbekannt.

- [ ] **Step 3: Implementieren.** Datenklasse und Companion ersetzen durch:

```kotlin
data class RegistrationError(
    val median: Double,
    val max: Double,
    val visiblePoints: Int,
    /** The rotation about the face centre taken out before measuring; 0 for [between]. */
    val rollDegrees: Double = 0.0
) {
    companion object {
        val RING_RADII = listOf(0.2, 0.4, 0.6, 0.8, 1.0)
        const val ANGLES = 72

        /** Null when not a single ring point lies inside the image. */
        fun between(
            reference: List<Double>,
            predicted: List<Double>,
            imageWidth: Int,
            imageHeight: Int
        ): RegistrationError? {
            val pairs = roundTrips(reference, predicted, imageWidth, imageHeight)
            if (pairs.isEmpty()) return null
            return summary(pairs.map { (t, q) -> hypot(q[0] - t[0], q[1] - t[1]) }, 0.0)
        }

        /**
         * Like [between], after turning the prediction about the face centre
         * by the rotation that fits it best to the reference. The registration
         * pins that rotation with its roll anchor, the sidecars with "up in the
         * image"; this compares the shape of the two maps, and [rollDegrees]
         * says by how much they are turned against each other.
         */
        fun betweenUpToRoll(
            reference: List<Double>,
            predicted: List<Double>,
            imageWidth: Int,
            imageHeight: Int
        ): RegistrationError? {
            val pairs = roundTrips(reference, predicted, imageWidth, imageHeight)
            if (pairs.isEmpty()) return null
            var cross = 0.0
            var dot = 0.0
            for ((t, q) in pairs) {
                cross += t[0] * q[1] - t[1] * q[0]
                dot += t[0] * q[0] + t[1] * q[1]
            }
            val roll = atan2(cross, dot)
            val c = cos(-roll)
            val s = sin(-roll)
            val errors = pairs.map { (t, q) ->
                hypot(c * q[0] - s * q[1] - t[0], s * q[0] + c * q[1] - t[1])
            }
            return summary(errors, Math.toDegrees(roll))
        }

        /** Ring points (target) and where they come back through the image with [predicted]. */
        private fun roundTrips(
            reference: List<Double>,
            predicted: List<Double>,
            imageWidth: Int,
            imageHeight: Int
        ): List<Pair<DoubleArray, DoubleArray>> {
            require(reference.size == 9 && predicted.size == 9) {
                "a homography has nine values"
            }
            val toImage = requireNotNull(Homography.inverse(reference.toDoubleArray())) {
                "the reference homography is singular"
            }
            val back = predicted.toDoubleArray()
            val pairs = ArrayList<Pair<DoubleArray, DoubleArray>>(RING_RADII.size * ANGLES)
            for (radius in RING_RADII) {
                for (k in 0 until ANGLES) {
                    val angle = 2.0 * PI * k / ANGLES
                    val t = doubleArrayOf(radius * cos(angle), radius * sin(angle))
                    val p = Homography.map(toImage, t[0], t[1]) ?: continue
                    if (p[0] < 0.0 || p[0] > imageWidth - 1.0) continue
                    if (p[1] < 0.0 || p[1] > imageHeight - 1.0) continue
                    val q = Homography.map(back, p[0], p[1]) ?: continue
                    pairs += t to q
                }
            }
            return pairs
        }

        private fun summary(unsorted: List<Double>, rollDegrees: Double): RegistrationError {
            val errors = unsorted.sorted()
            val n = errors.size
            val median = if (n % 2 == 1) errors[n / 2] else 0.5 * (errors[n / 2 - 1] + errors[n / 2])
            return RegistrationError(median, errors.last(), n, rollDegrees)
        }
    }
}
```

`import kotlin.math.atan2` ergänzen.

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :detection-corpus:test`
Expected: PASS, alle bestehenden Tests unverändert grün.

- [ ] **Step 5: Commit**

```bash
git add detection-corpus/src
git commit -m "metrics: the registration error up to the roll about the face centre"
```

---

### Task 2: `FaceWarp` mit wählbarer Ausdehnung

**Files:**
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/registration/FaceWarp.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/FaceWarpTest.kt`

**Interfaces:**
- Produces: `FaceWarp.pixelOf(target: Vec2, edge: Int, extent: Double = EXTENT)`, `FaceWarp.targetOf(px: Vec2, edge: Int, extent: Double = EXTENT)`, `FaceWarp.warp(original: Mat, imageToTarget: Mat3, edge: Int = edgeFor(imageToTarget), extent: Double = EXTENT): Mat`.

- [ ] **Step 1: Failing tests schreiben**

```kotlin
    @Test
    fun aWiderExtentPutsItsCornersAtTheOuterEdges() {
        assertThat(FaceWarp.pixelOf(Vec2(-1.6, -1.6), 640, 1.6).distanceTo(Vec2(-0.5, -0.5)))
            .isLessThan(1e-9)
        assertThat(FaceWarp.pixelOf(Vec2(1.6, 1.6), 640, 1.6).distanceTo(Vec2(639.5, 639.5)))
            .isLessThan(1e-9)
        assertThat(FaceWarp.targetOf(Vec2(319.5, 319.5), 640, 1.6).length).isLessThan(1e-9)
    }

    @Test
    fun aWiderExtentWarpsTheCentreToTheMiddle() {
        // 100 px per radius, centre at (500, 400); a white dot at the centre.
        val imageToTarget = Mat3.of(0.01, 0.0, -5.0, 0.0, 0.01, -4.0, 0.0, 0.0, 1.0)
        val image = Mat(800, 1000, CvType.CV_8UC1, Scalar(0.0))
        Imgproc.circle(image, Point(500.0, 400.0), 3, Scalar(255.0), Imgproc.FILLED)
        val warped = FaceWarp.warp(image, imageToTarget, 320, 1.6)
        try {
            assertThat(warped.get(159, 159)[0]).isGreaterThan(200.0)
            assertThat(warped.get(10, 10)[0]).isEqualTo(0.0)
        } finally {
            image.release()
            warped.release()
        }
    }
```

Imports ergänzen, falls nicht da: `org.opencv.core.CvType`, `Mat`, `Point`, `Scalar`, `org.opencv.imgproc.Imgproc`, `de.dreier.mytargets.detection.geometry.Mat3`. Die Klasse braucht `@get:Rule val openCv = OpenCvRule()`, falls sie es noch nicht hat.

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FaceWarpTest'`
Expected: FAIL, keine Überladung mit `extent`.

- [ ] **Step 3: Implementieren.** In `pixelOf`, `targetOf` und `warp` einen letzten Parameter `extent: Double = EXTENT` ergänzen und jedes `EXTENT` im Rumpf durch `extent` ersetzen:

```kotlin
    fun pixelOf(target: Vec2, edge: Int, extent: Double = EXTENT): Vec2 {
        val k = edge / (2.0 * extent)
        return Vec2(k * (target.x + extent) - 0.5, k * (target.y + extent) - 0.5)
    }

    fun targetOf(px: Vec2, edge: Int, extent: Double = EXTENT): Vec2 {
        val k = edge / (2.0 * extent)
        return Vec2((px.x + 0.5) / k - extent, (px.y + 0.5) / k - extent)
    }

    fun warp(original: Mat, imageToTarget: Mat3, edge: Int = edgeFor(imageToTarget), extent: Double = EXTENT): Mat {
        val k = edge / (2.0 * extent)
        val warpedFromTarget = Mat3.of(
            k, 0.0, k * extent - 0.5,
            0.0, k, k * extent - 0.5,
            0.0, 0.0, 1.0
        )
        // Rest unverändert.
```

Die KDoc von `warp` um einen Satz ergänzen: „[extent] is the half side of the square in spot radii; [edgeFor] assumes [EXTENT].“

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FaceWarpTest' --tests '*Learned*Test' --tests '*OpenCvArrowDetectorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add detection/src
git commit -m "registration: FaceWarp takes the extent of its square"
```

---

### Task 3: Papier und Schäfte im synthetischen Foto

**Files:**
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/registration/SyntheticFace.kt`

**Interfaces:**
- Produces: `SyntheticFace.photograph(width: Int, height: Int, targetToPhoto: Mat3, paperDegrees: Double? = null, shafts: List<Pair<Vec2, Vec2>> = emptyList()): Mat`; `SyntheticFace.PAPER_HALF_SIDE = 1.1`; `SyntheticFace.SHAFT = Scalar(25.0, 25.0, 25.0)`. Ohne die neuen Argumente ist das Foto pixelgleich zu heute.

- [ ] **Step 1: Implementieren.** `photograph` ersetzen durch:

```kotlin
    const val PAPER_HALF_SIDE = 1.1
    val SHAFT = Scalar(25.0, 25.0, 25.0)

    /**
     * [paperDegrees]: a white square paper of half side [PAPER_HALF_SIDE]
     * behind the face, turned by that angle in target coordinates, as the
     * roll anchor sees it on real photographs; null leaves the background
     * bare as before. [shafts]: dark lines drawn over the face, from and to
     * in target coordinates.
     */
    fun photograph(
        width: Int,
        height: Int,
        targetToPhoto: Mat3,
        paperDegrees: Double? = null,
        shafts: List<Pair<Vec2, Vec2>> = emptyList()
    ): Mat {
        // Computed before any Mat exists, so a singular matrix leaves nothing to release.
        val photoToTarget = requireNotNull(targetToPhoto.inverse()) { "targetToPhoto is singular" }
        // Without paper the canvas is the one of before, so old photographs stay pixel for pixel.
        val extent = if (paperDegrees == null && shafts.isEmpty()) 1.1 else 1.7
        val side = (2.0 * extent * CANONICAL).toInt() + 1
        val middle = Point(extent * CANONICAL, extent * CANONICAL)
        val canvasFromTarget = Mat3.of(
            CANONICAL, 0.0, extent * CANONICAL,
            0.0, CANONICAL, extent * CANONICAL,
            0.0, 0.0, 1.0
        )
        val canvas = Mat(side, side, CvType.CV_8UC3, BACKGROUND)
        val map = OpenCvMats.of(canvasFromTarget * photoToTarget)
        val photo = Mat()
        try {
            if (paperDegrees != null) {
                val turn = Mat3.rotation(Math.toRadians(paperDegrees))
                val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
                    val p = requireNotNull(
                        (canvasFromTarget * turn).mapPoint(Vec2(sx * PAPER_HALF_SIDE, sy * PAPER_HALF_SIDE))
                    )
                    Point(p.x, p.y)
                }
                val polygon = MatOfPoint(*corners.toTypedArray())
                try {
                    Imgproc.fillConvexPoly(canvas, polygon, WHITE, Imgproc.LINE_AA)
                } finally {
                    polygon.release()
                }
            }
            for ((r, colour) in RINGS) {
                Imgproc.circle(canvas, middle, (r * CANONICAL).toInt(), colour, Imgproc.FILLED, Imgproc.LINE_AA)
            }
            for ((from, to) in shafts) {
                val a = requireNotNull(canvasFromTarget.mapPoint(from))
                val b = requireNotNull(canvasFromTarget.mapPoint(to))
                Imgproc.line(
                    canvas, Point(a.x, a.y), Point(b.x, b.y), SHAFT,
                    (0.015 * CANONICAL).toInt(), Imgproc.LINE_AA
                )
            }
            // WARP_INVERSE_MAP: the matrix takes a photograph pixel to the canvas.
            Imgproc.warpPerspective(
                canvas, photo, map, Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_CONSTANT, BACKGROUND
            )
            return photo
        } finally {
            canvas.release()
            map.release()
        }
    }
```

Import `org.opencv.core.MatOfPoint` ergänzen. Hinweis: `side` war vorher `(2.2 * CANONICAL).toInt() + 1`; `2.0 * 1.1 * 1000` ergibt in Double 2200.0000000000005 und `toInt()` 2200, also dieselbe Zahl.

- [ ] **Step 2: Bestehende Registrar-Tests laufen lassen** (sie benutzen `photograph` ohne die neuen Argumente)

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*registration*'`
Expected: PASS, unverändert.

- [ ] **Step 3: Commit**

```bash
git add detection/src/test
git commit -m "test: synthetic photographs with a turned paper and shafts"
```

---

### Task 4: `RollAnchor.measure`

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/RollAnchor.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/RollAnchorImages.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/RollAnchorTest.kt`

**Interfaces:**
- Consumes: `FaceWarp.warp(..., edge, extent)`, `FaceWarp.targetOf(..., extent)` (Task 2); `SyntheticFace.photograph(..., paperDegrees, shafts)` (Task 3).
- Produces:
  - `RollAnchor.measure(workingImage: Mat, imageToTarget: Mat3, debug: DebugSink = DebugSink.NONE): RollAnchor.Measurement?` — null nur, wenn kein einziges Pixel gezählt wurde.
  - `class RollAnchor.Measurement(val radians: Double, val strength: Double, val pixels: Int, val histogram: DoubleArray)` mit `val degrees: Double` und `val isAnchor: Boolean`.
  - `internal fun RollAnchor.peakOf(bins: DoubleArray, pixels: Int): Measurement`.
  - Konstanten `EXTENT = 1.6`, `EDGE = 640`, `INNER_RADIUS = 1.02`, `MIN_GRADIENT = 40.0`, `MAX_ANGLE_TO_RADIUS_DEGREES = 30.0`, `MIN_PIXELS = 300`, `BINS = 90`, `MIN_STRENGTH` (vorläufig 2.0, Task 7 setzt sie), `STAGE = "4-rollanker"`.

- [ ] **Step 1: Failing tests schreiben**

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat
import kotlin.math.cos
import kotlin.math.sin

class RollAnchorTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val tilted = SyntheticFace.view(30.0, 300.0, Vec2(800.0, 600.0))

    private fun measure(photo: Mat): RollAnchor.Measurement? =
        try {
            RollAnchor.measure(photo, tilted.inverse()!!)
        } finally {
            photo.release()
        }

    private fun from(radius: Double, degrees: Double) =
        Vec2(radius * cos(Math.toRadians(degrees)), radius * sin(Math.toRadians(degrees)))

    @Test
    fun aSpikeInTheHistogramIsItsBinCentre() {
        val bins = DoubleArray(RollAnchor.BINS) { 1.0 }
        bins[30] = 50.0
        val peak = RollAnchor.peakOf(bins, 1000)
        assertThat(peak.degrees).isWithin(0.05).of(30.5)
        assertThat(peak.strength).isGreaterThan(3.0)
    }

    @Test
    fun aSpikeAbove45DegreesFoldsToTheNearestAxis() {
        val bins = DoubleArray(RollAnchor.BINS) { 1.0 }
        bins[80] = 50.0
        assertThat(RollAnchor.peakOf(bins, 1000).degrees).isWithin(0.05).of(-9.5)
    }

    @Test
    fun aFlatHistogramHasStrengthOne() {
        val peak = RollAnchor.peakOf(DoubleArray(RollAnchor.BINS) { 1.0 }, 1000)
        assertThat(peak.strength).isWithin(1e-9).of(1.0)
    }

    @Test
    fun findsTheTurnOfThePaper() {
        for (paper in listOf(-30.0, 0.0, 12.0, 30.0)) {
            val m = measure(SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = paper))!!
            assertThat(m.degrees).isWithin(1.0).of(paper)
            assertThat(m.isAnchor).isTrue()
        }
    }

    @Test
    fun aPaperTurnedBy60DegreesIsCorrectedToTheNearestAxis() {
        val m = measure(SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = 60.0))!!
        assertThat(m.degrees).isWithin(1.0).of(-30.0)
    }

    @Test
    fun radialShaftsDoNotMoveTheAngle() {
        val shafts = (0 until 6).map { k -> from(0.1, 200.0 + 12.0 * k) to from(1.55, 200.0 + 12.0 * k) }
        val m = measure(SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = 12.0, shafts = shafts))!!
        assertThat(m.degrees).isWithin(1.0).of(12.0)
    }

    @Test
    fun parallelShaftsFromTheGoldDoNotMoveTheAngle() {
        // Six shafts leaning the same way, as in 2026-09-14_sonne_stark-schraeg_08.
        val lean = from(1.5, -65.0)
        val shafts = (0 until 6).map { k ->
            val hit = Vec2(-0.15 + 0.06 * k, 0.05 * (k % 2))
            hit to hit + lean
        }
        val m = measure(SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = -8.0, shafts = shafts))!!
        assertThat(m.degrees).isWithin(1.0).of(-8.0)
    }

    @Test
    fun aBareBackgroundIsNoAnchor() {
        val m = measure(SyntheticFace.photograph(1600, 1200, tilted))
        assertThat(m?.isAnchor ?: false).isFalse()
    }

    @Test
    fun theEdgeOfTheWarpIsNoAnchor() {
        // Face in the corner of the photograph: most of the ring 1.02..1.6 lies
        // outside, and the black border of the warp would be a perfect straight line.
        val corner = SyntheticFace.view(0.0, 300.0, Vec2(250.0, 250.0))
        val photo = SyntheticFace.photograph(1600, 1200, corner)
        val m = try {
            RollAnchor.measure(photo, corner.inverse()!!)
        } finally {
            photo.release()
        }
        assertThat(m?.isAnchor ?: false).isFalse()
    }
}
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RollAnchorTest'`
Expected: FAIL, `RollAnchor` unbekannt.

- [ ] **Step 3: `RollAnchor.kt` schreiben** (Lizenzkopf wie in den Nachbardateien)

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.show
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The roll anchor of the roll-anchor design (2026-09-23): the rotation about
 * the face centre, modulo 90 degrees, of the straight edges around the face.
 *
 * Concentric rings leave that rotation open; [Orientation] pins it with "up
 * in the image". The paper and the straw lie in the plane of the rings, so the
 * rectification maps their edges to straight lines whose angle is exactly the
 * roll of the camera. Only edges across the radius count: shafts run from
 * their hit outwards, almost along the radius, and would otherwise vote with
 * their common lean.
 */
object RollAnchor {

    const val EXTENT = 1.6
    const val EDGE = 640
    const val INNER_RADIUS = 1.02
    /** Sobel 3x3 on 8-bit grey; a sharp step of 10 grey levels gives 40. */
    const val MIN_GRADIENT = 40.0
    const val MAX_ANGLE_TO_RADIUS_DEGREES = 30.0
    const val MIN_PIXELS = 300
    const val BINS = 90
    /** Peak over mean of the smoothed histogram. Provisional; set from RollAnchorCorpusRun. */
    const val MIN_STRENGTH = 2.0
    const val STAGE = "4-rollanker"

    private const val ERODE_ITERATIONS = 3
    private val SMOOTHING = doubleArrayOf(1.0, 2.0, 3.0, 2.0, 1.0)

    /**
     * [radians] in (-pi/4, pi/4]: the edges lie at this angle in target
     * coordinates, and `Mat3.rotation(-radians)` turns them onto the axes.
     */
    class Measurement(
        val radians: Double,
        val strength: Double,
        val pixels: Int,
        val histogram: DoubleArray
    ) {
        val degrees: Double
            get() = Math.toDegrees(radians)

        val isAnchor: Boolean
            get() = pixels >= MIN_PIXELS && strength >= MIN_STRENGTH
    }

    /**
     * Measures the edges around the face in [workingImage] (8-bit BGR), seen
     * through [imageToTarget], a map from its pixels to target coordinates.
     * Null when not a single pixel counted.
     */
    fun measure(workingImage: Mat, imageToTarget: Mat3, debug: DebugSink = DebugSink.NONE): Measurement? {
        val grey = Mat()
        try {
            Imgproc.cvtColor(workingImage, grey, Imgproc.COLOR_BGR2GRAY)
            val warped = FaceWarp.warp(grey, imageToTarget, EDGE, EXTENT)
            try {
                val valid = validPixels(grey, imageToTarget)
                val (x, y) = gradients(warped)
                val counted = BooleanArray(EDGE * EDGE)
                val result = histogramOf(x, y, valid, counted)
                debug.show(STAGE) { RollAnchorImages.render(warped, counted, result) }
                return result
            } finally {
                warped.release()
            }
        } finally {
            grey.release()
        }
    }

    /** 255 where the warp saw the photograph, eroded: the warp's border is black and perfectly straight. */
    private fun validPixels(grey: Mat, imageToTarget: Mat3): ByteArray {
        val ones = Mat(grey.rows(), grey.cols(), CvType.CV_8UC1, Scalar(255.0))
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            val mask = FaceWarp.warp(ones, imageToTarget, EDGE, EXTENT)
            try {
                Imgproc.erode(mask, mask, kernel, Point(-1.0, -1.0), ERODE_ITERATIONS)
                return ByteArray(EDGE * EDGE).also { mask.get(0, 0, it) }
            } finally {
                mask.release()
            }
        } finally {
            ones.release()
            kernel.release()
        }
    }

    private fun gradients(warped: Mat): Pair<FloatArray, FloatArray> {
        val gx = Mat()
        val gy = Mat()
        try {
            Imgproc.Sobel(warped, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(warped, gy, CvType.CV_32F, 0, 1, 3)
            return FloatArray(EDGE * EDGE).also { gx.get(0, 0, it) } to
                FloatArray(EDGE * EDGE).also { gy.get(0, 0, it) }
        } finally {
            gx.release()
            gy.release()
        }
    }

    private fun histogramOf(gx: FloatArray, gy: FloatArray, mask: ByteArray, counted: BooleanArray): Measurement? {
        val bins = DoubleArray(BINS)
        val across = cos(Math.toRadians(MAX_ANGLE_TO_RADIUS_DEGREES))
        var pixels = 0
        for (row in 0 until EDGE) {
            for (col in 0 until EDGE) {
                val i = row * EDGE + col
                if (mask[i].toInt() and 0xFF != 255) continue
                val t = FaceWarp.targetOf(Vec2(col.toDouble(), row.toDouble()), EDGE, EXTENT)
                val r = t.length
                if (r < INNER_RADIUS || r > EXTENT) continue
                val x = gx[i].toDouble()
                val y = gy[i].toDouble()
                val magnitude = hypot(x, y)
                if (magnitude < MIN_GRADIENT) continue
                // The gradient of an edge across the radius points along the radius.
                if (abs(x * t.x + y * t.y) < across * magnitude * r) continue
                // A gradient and its edge differ by 90 degrees, which the fold removes.
                var folded = Math.toDegrees(atan2(y, x)) % 90.0
                if (folded < 0.0) folded += 90.0
                bins[minOf(BINS - 1, (folded * BINS / 90.0).toInt())] += magnitude
                counted[i] = true
                pixels++
            }
        }
        if (pixels == 0) return null
        return peakOf(bins, pixels)
    }

    /** The smoothed, cyclic histogram's peak, refined by a parabola through its neighbours. */
    internal fun peakOf(bins: DoubleArray, pixels: Int): Measurement {
        val half = SMOOTHING.size / 2
        val weight = SMOOTHING.sum()
        val smooth = DoubleArray(BINS) { k ->
            (-half..half).sumOf { d -> SMOOTHING[d + half] * bins[Math.floorMod(k + d, BINS)] } / weight
        }
        val k = smooth.indices.maxBy { smooth[it] }
        val left = smooth[Math.floorMod(k - 1, BINS)]
        val centre = smooth[k]
        val right = smooth[Math.floorMod(k + 1, BINS)]
        val curvature = left - 2.0 * centre + right
        val offset = if (curvature < 0.0) 0.5 * (left - right) / curvature else 0.0
        var degrees = ((k + 0.5 + offset) * 90.0 / BINS) % 90.0
        if (degrees < 0.0) degrees += 90.0
        if (degrees > 45.0) degrees -= 90.0
        val mean = smooth.average()
        val strength = if (mean > 0.0) centre / mean else 0.0
        return Measurement(Math.toRadians(degrees), strength, pixels, smooth)
    }
}
```

- [ ] **Step 4: `RollAnchorImages.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.registration

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/** Stage [RollAnchor.STAGE]: the warped surroundings, the counted pixels, the found axes, the histogram. */
internal object RollAnchorImages {

    private const val HISTOGRAM_HEIGHT = 120
    private val COUNTED = Scalar(0.0, 200.0, 0.0)
    private val AXIS = Scalar(0.0, 0.0, 255.0)
    private val BAR = Scalar(200.0, 200.0, 200.0)

    fun render(warped: Mat, counted: BooleanArray, measurement: RollAnchor.Measurement?): Mat {
        val edge = RollAnchor.EDGE
        return Mat(edge + HISTOGRAM_HEIGHT, edge, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).releaseIfThrows { out ->
            val top = out.submat(0, edge, 0, edge)
            try {
                Imgproc.cvtColor(warped, top, Imgproc.COLOR_GRAY2BGR)
                val green = byteArrayOf(0, 200.toByte(), 0)
                for (i in counted.indices) {
                    if (counted[i]) top.put(i / edge, i % edge, green)
                }
            } finally {
                top.release()
            }
            val centre = Point(edge / 2.0, edge / 2.0)
            Imgproc.circle(out, centre, (edge / 2.0 * RollAnchor.INNER_RADIUS / RollAnchor.EXTENT).toInt(), COUNTED, 1)
            if (measurement != null) {
                for (quarter in 0 until 2) {
                    val a = measurement.radians + quarter * Math.PI / 2.0
                    val d = Point(cos(a) * edge / 2.0, sin(a) * edge / 2.0)
                    Imgproc.line(out, Point(centre.x - d.x, centre.y - d.y), Point(centre.x + d.x, centre.y + d.y), AXIS, 2)
                }
                val peak = measurement.histogram.maxOrNull() ?: 0.0
                val width = edge.toDouble() / measurement.histogram.size
                for ((k, value) in measurement.histogram.withIndex()) {
                    val h = if (peak > 0.0) value / peak * (HISTOGRAM_HEIGHT - 10) else 0.0
                    Imgproc.rectangle(
                        out,
                        Point(k * width, (edge + HISTOGRAM_HEIGHT).toDouble()),
                        Point((k + 1) * width - 1, edge + HISTOGRAM_HEIGHT - h),
                        BAR, Imgproc.FILLED
                    )
                }
                Imgproc.putText(
                    out,
                    String.format(java.util.Locale.ROOT, "%.1f deg  strength %.2f  %d px", measurement.degrees, measurement.strength, measurement.pixels),
                    Point(8.0, edge + 20.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, AXIS, 1
                )
            }
        }
    }
}
```

`releaseIfThrows` ist die Erweiterung aus `OpenCvMats.kt` im selben Paket.

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RollAnchorTest'`
Expected: PASS. Schlägt `aBareBackgroundIsNoAnchor` oder `theEdgeOfTheWarpIsNoAnchor` fehl, zuerst mit einem `PngDebugSink` das Bild `4-rollanker` ansehen und die Ursache benennen (Ring 1,0 zu nah an `INNER_RADIUS`, Maske zu wenig erodiert), nicht blind Konstanten verstellen; eine geänderte Konstante gehört mit Begründung in den Commit.

- [ ] **Step 6: Commit**

```bash
git add detection/src
git commit -m "registration: the roll anchor measures the edges around the face"
```

---

### Task 5: Anker im Registrar, `Registered.roll`

**Files:**
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/registration/Registration.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/registration/OpenCvFaceRegistrar.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvFaceRegistrarTest.kt`

**Interfaces:**
- Consumes: `RollAnchor.measure`, `Measurement.isAnchor/degrees/radians/strength/pixels` (Task 4); `RegistrationError.betweenUpToRoll` (Task 1).
- Produces: `class Roll(val anchored: Boolean, val measuredDegrees: Double?, val strength: Double?, val pixels: Int)` mit `val correctionDegrees: Double` und `Roll.NOT_MEASURED`; `RegistrationOutcome.Registered(imageToTarget, imagedCentre, rings, roll: Roll = Roll.NOT_MEASURED)`.

- [ ] **Step 1: Failing tests schreiben** (in `OpenCvFaceRegistrarTest`)

```kotlin
    private fun registeredWithPaper(paperDegrees: Double): Pair<Mat3, RegistrationOutcome.Registered> {
        val view = SyntheticFace.view(30.0, 300.0, Vec2(800.0, 600.0))
        val outcome = register(SyntheticFace.photograph(1600, 1200, view, paperDegrees = paperDegrees))
        assertThat(outcome).isInstanceOf(RegistrationOutcome.Registered::class.java)
        return view to (outcome as RegistrationOutcome.Registered)
    }

    @Test
    fun thePaperAnchorsTheRoll() {
        val (view, registered) = registeredWithPaper(20.0)

        assertThat(registered.roll.anchored).isTrue()
        assertThat(registered.roll.correctionDegrees).isWithin(1.0).of(20.0)
        // The paper's frame: the true map turned back by the paper's angle.
        val paperFrame = Mat3.rotation(Math.toRadians(-20.0)) * view.inverse()!!
        val error = RegistrationError.betweenUpToRoll(
            SyntheticFace.values(paperFrame), SyntheticFace.values(registered.imageToTarget), 1600, 1200
        )!!
        assertThat(error.rollDegrees).isWithin(1.0).of(0.0)
        assertThat(error.max).isAtMost(0.005)
    }

    @Test
    fun withoutPaperTheRegistrationIsTheOldOne() {
        val registered = register(
            SyntheticFace.photograph(1600, 1200, SyntheticFace.view(30.0, 400.0, Vec2(800.0, 600.0)))
        ) as RegistrationOutcome.Registered

        assertThat(registered.roll.anchored).isFalse()
        assertThat(registered.roll.correctionDegrees).isEqualTo(0.0)
    }
```

Die bestehenden Tests bleiben wie sie sind; `registersAFaceSeenAt30Degrees` usw. messen weiter mit `between` und ohne Papier.

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*OpenCvFaceRegistrarTest'`
Expected: FAIL, `roll` unbekannt.

- [ ] **Step 3: `Roll` und `Registered.roll`** in `Registration.kt`:

```kotlin
/**
 * What the roll anchor made of the photograph. [measuredDegrees], [strength]
 * and [pixels] are the measurement whether or not it anchored; the
 * registration was turned by [correctionDegrees], which is 0 when [anchored]
 * is false and "up in the image" still pins the rotation.
 */
class Roll(
    val anchored: Boolean,
    val measuredDegrees: Double?,
    val strength: Double?,
    val pixels: Int
) {
    val correctionDegrees: Double
        get() = if (anchored) measuredDegrees ?: 0.0 else 0.0

    companion object {
        val NOT_MEASURED = Roll(anchored = false, measuredDegrees = null, strength = null, pixels = 0)
    }
}
```

und in `Registered` als viertes Feld `val roll: Roll = Roll.NOT_MEASURED` samt einem Satz in der KDoc: „[roll] says whether the rotation about the centre comes from the roll anchor or from "up in the image".“

- [ ] **Step 4: Einbau in `OpenCvFaceRegistrar.registerWorkingImage`.** Nach `val oriented = ...`:

```kotlin
        val measurement = RollAnchor.measure(small, oriented, debug)
        val anchored = measurement != null && measurement.isAnchor
        val turned = if (anchored) Mat3.rotation(-measurement!!.radians) * oriented else oriented
        val roll = Roll(anchored, measurement?.degrees, measurement?.strength, measurement?.pixels ?: 0)
```

Danach `oriented` durch `turned` ersetzen in `val imageToTarget = normalised(turned * s)` und in `HomographyRefinement.radialRms(turned, ring)`; am Ende `RegistrationOutcome.Registered(imageToTarget, imagedCentre, fits, roll)`. Die Klassen-KDoc um „anchor the roll,“ nach „orient,“ ergänzen.

- [ ] **Step 5: Alle Tests des Moduls laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS. Die Korpusläufe laufen dabei mit, wenn `DETECTION_CORPUS_DIR` gesetzt ist; `ArrowCorpusRun` und `LearnedArrowCorpusRun` dürfen hier an ihren Pins scheitern — das ist Task 8. Alles andere muss grün sein.

- [ ] **Step 6: Commit**

```bash
git add detection/src
git commit -m "registration: turn the registration by the roll anchor"
```

---

### Task 6: Korpusläufe messen die Form drehfrei

**Files:**
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/registration/RegistrationCorpusRun.kt:135`
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRun.kt:253`
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowCorpusRun.kt:256`

**Interfaces:**
- Consumes: `RegistrationError.betweenUpToRoll` (Task 1).

- [ ] **Step 1:** An den drei Stellen `RegistrationError.between(` durch `RegistrationError.betweenUpToRoll(` ersetzen und im KDoc des jeweiligen Berichts bzw. der Methode einen Satz ergänzen: „Up to the roll: the registrar anchors it, the sidecar reference assumes up in the image.“

- [ ] **Step 2: Registrierungslauf laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RegistrationCorpusRun' --rerun`
Expected: PASS. Im Bericht `registration.md` darf der Median der Maxima gegenüber master nur wenig steigen; ein Sprung heißt, dass der Anker die Form verändert hat, und ist vor dem Weitermachen zu klären.

- [ ] **Step 3: Commit**

```bash
git add detection/src/test
git commit -m "test: the corpus runs measure the registration up to its roll"
```

---

### Task 7: `RollAnchorCorpusRun` — Kriterium und Schwelle

**Files:**
- Create: `detection/src/test/java/de/dreier/mytargets/detection/registration/RollAnchorCorpusRun.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/registration/RollAnchor.kt` (`MIN_STRENGTH`)
- Modify: `BUILDING.md` (Befehl neben den anderen Korpusläufen)

**Interfaces:**
- Consumes: `Registered.roll` (Task 5); `CorpusLoader`, `CorpusPhotos`, `PngDebugSink`, `OpenCvRule` wie `RegistrationCorpusRun`.

- [ ] **Step 1: Lauf schreiben**

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.Locale
import kotlin.math.atan2

/**
 * The roll anchor against the corpus (roll-anchor design, Messung): do the
 * views of one end agree on the rotation about the centre?
 *
 * Every view with clicked tips maps them through its own registration; the
 * best rotation from the carried truth to that reading is the view's roll.
 * The truth is the same for every view of an end, so the spread of the rolls
 * over an end's anchored views is what the anchor leaves over. Without the
 * anchor it is the relative roll of the 16.9. measurement, up to 36 degrees.
 */
class RollAnchorCorpusRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var reportDir: File

    private val registrar = OpenCvFaceRegistrar()
    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)

    private class ViewRoll(
        val name: String,
        val end: String,
        val roll: Roll,
        /** Roll of the view's reading against the carried truth, as registered. */
        val degrees: Double
    ) {
        /** The same without the anchor's correction: "up in the image". */
        val before: Double get() = degrees + roll.correctionDegrees

        /** What an anchor would leave at [threshold]; null when it would not anchor. */
        fun anchoredAt(threshold: Double): Double? {
            val measured = roll.measuredDegrees ?: return null
            val strength = roll.strength ?: return null
            if (roll.pixels < RollAnchor.MIN_PIXELS || strength < threshold) return null
            return before - measured
        }
    }

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
    fun anchorsTheViewsOfAnEndAlike() {
        val entries = CorpusLoader.load(root).entries.filter { entry ->
            entry.outOfScope == null && entry.shots.any { it.tipPixel != null && it.position != null }
        }
        val files = CorpusPhotos.filesByName(root, entries)
        val imagesDir = File(reportDir, "roll").apply { deleteRecursively(); mkdirs() }

        val views = ArrayList<ViewRoll>()
        val unregistered = ArrayList<String>()
        for (entry in entries) {
            val file = CorpusPhotos.imageFileOf(root, entry.imageName, files[entry.imageName].orEmpty())
            val image = Imgcodecs.imread(file.absolutePath)
            try {
                check(!image.empty()) { "${entry.imageName}: cannot be decoded" }
                CorpusPhotos.checkDecodedSize(entry, image)
                val folder = File(imagesDir, file.nameWithoutExtension).apply { mkdirs() }
                val outcome = registrar.register(image, request, PngDebugSink(folder))
                if (outcome !is RegistrationOutcome.Registered) {
                    unregistered += entry.imageName
                    continue
                }
                views += ViewRoll(entry.imageName, endKey(entry), outcome.roll, rollOf(entry, outcome.imageToTarget))
            } finally {
                image.release()
            }
        }

        val ends = views.groupBy { it.end }.values.filter { it.size >= 2 }
        fun worstSpread(threshold: Double): Double =
            ends.maxOfOrNull { end -> spread(end.mapNotNull { it.anchoredAt(threshold) }) } ?: 0.0
        val candidates = views.mapNotNull { it.roll.strength }.distinct().sorted()
        val safe = candidates.firstOrNull { worstSpread(it) <= MAX_SPREAD_DEGREES }
        val worst = worstSpread(RollAnchor.MIN_STRENGTH)
        val anchored = views.count { it.roll.anchored }

        val report = File(reportDir, "roll.md")
        report.writeText(render(views, ends, unregistered, safe, worst))
        println("Roll report: ${report.absolutePath}")

        assertWithMessage("largest spread of the anchored rolls within an end, degrees")
            .that(worst).isAtMost(MAX_SPREAD_DEGREES)
        assertWithMessage("views with clicked tips").that(views.size).isEqualTo(PINS.views)
        assertWithMessage("anchored views").that(anchored).isAtLeast(PINS.anchored)
    }

    /** The views of one end carry the same truth; its positions are the key. */
    private fun endKey(entry: CorpusEntry): String =
        entry.shots.joinToString(";") { shot ->
            shot.position?.let { String.format(Locale.ROOT, "%.4f,%.4f", it.x, it.y) } ?: "-"
        }

    /** Best rotation, in degrees, from the carried truth to the view's own reading of its tips. */
    private fun rollOf(entry: CorpusEntry, imageToTarget: Mat3): Double {
        var cross = 0.0
        var dot = 0.0
        for (shot in entry.shots) {
            val tip = shot.tipPixel ?: continue
            val p = shot.position ?: continue
            val o = imageToTarget.mapPoint(Vec2(tip.x, tip.y)) ?: continue
            cross += p.x * o.y - p.y * o.x
            dot += p.x * o.x + p.y * o.y
        }
        return Math.toDegrees(atan2(cross, dot))
    }

    private fun spread(degrees: List<Double>): Double {
        if (degrees.size < 2) return 0.0
        val first = degrees.first()
        val relative = degrees.map { wrap(it - first) }
        return relative.max() - relative.min()
    }

    private fun wrap(d: Double): Double {
        var a = d % 360.0
        if (a > 180.0) a -= 360.0
        if (a <= -180.0) a += 360.0
        return a
    }

    private fun number(d: Double?) = d?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"

    private fun render(
        views: List<ViewRoll>,
        ends: List<List<ViewRoll>>,
        unregistered: List<String>,
        safe: Double?,
        worst: Double
    ): String = buildString {
        appendLine("# Roll anchor against the corpus")
        appendLine()
        appendLine("${views.size} views with clicked tips, ${views.count { it.roll.anchored }} anchored at strength ${number(RollAnchor.MIN_STRENGTH)}; ${ends.size} ends with at least two views.")
        appendLine("Largest spread within an end: ${number(worst)} degrees anchored, ${number(ends.maxOfOrNull { e -> spread(e.map { it.before }) })} degrees before.")
        appendLine("Lowest strength that keeps every end within $MAX_SPREAD_DEGREES degrees: ${safe?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "none"}.")
        if (unregistered.isNotEmpty()) appendLine("Not registered: ${unregistered.joinToString()}.")
        appendLine()
        appendLine("## Ends")
        appendLine()
        appendLine("| End | Views | Spread before | Spread anchored | Views anchored |")
        appendLine("|---|---|---|---|---|")
        for ((index, end) in ends.withIndex()) {
            val kept = end.mapNotNull { it.anchoredAt(RollAnchor.MIN_STRENGTH) }
            appendLine("| ${index + 1} | ${end.joinToString { it.name.substringBeforeLast('.') }} | ${number(spread(end.map { it.before }))} | ${number(spread(kept))} | ${kept.size} of ${end.size} |")
        }
        appendLine()
        appendLine("## Views")
        appendLine()
        appendLine("| View | Measured | Strength | Pixels | Anchored | Roll before | Roll anchored |")
        appendLine("|---|---|---|---|---|---|---|")
        for (v in views.sortedBy { it.roll.strength ?: 0.0 }) {
            appendLine("| ${v.name} | ${number(v.roll.measuredDegrees)} | ${v.roll.strength?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"} | ${v.roll.pixels} | ${if (v.roll.anchored) "yes" else "no"} | ${number(v.before)} | ${number(v.degrees)} |")
        }
    }

    private class RollPins(val views: Int, val anchored: Int)

    private companion object {
        const val MAX_SPREAD_DEGREES = 5.0

        /** Set from the first run; see the KDoc of RollAnchor.MIN_STRENGTH. */
        val PINS = RollPins(views = 0, anchored = 0)
    }
}
```

- [ ] **Step 2: Erster Lauf, Bericht lesen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RollAnchorCorpusRun' --rerun`
Expected: FAIL an den Pins (0), der Bericht `roll.md` steht im Berichtsordner des Moduls (Pfad auf der Konsole). Lesen:
- „Largest spread … before“ muss die bekannte Größenordnung zeigen (um 30 Grad oder mehr). Ist sie klein, stimmt die Gruppierung der Passen nicht; zuerst das klären.
- „Lowest strength that keeps every end within 5 degrees“: die sichere Schwelle.
- Die Debugbilder `roll/<Ansicht>/4-rollanker.png` der Ansichten mit der größten Abweichung ansehen: Liegen die roten Achsen auf dem Papierrand?

- [ ] **Step 3: Schwelle und Pins setzen.** `RollAnchor.MIN_STRENGTH` auf die sichere Schwelle, aufgerundet auf zwei Nachkommastellen, plus einen Abstand von 10 % (also `ceil(safe * 1.1 * 100) / 100`), und die KDoc der Konstante um Datum, Korpusstand und die Zahl der verankerten Ansichten ergänzen. Gibt es keine sichere Schwelle („none“) oder verankert sie weniger als die Hälfte der Ansichten, hier **anhalten** und mit dem Bericht und den Debugbildern der Ausreißer zurückmelden: Dann trägt Ansatz A so nicht, und die Spec ist neu zu besprechen. Sonst `PINS` auf `views` und `anchored` aus dem zweiten Lauf setzen und einen KDoc-Absatz über die Pins mit Datum und den Zahlen „vorher/verankert“ schreiben.

- [ ] **Step 4: Zweiter Lauf**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RollAnchorCorpusRun' --tests '*RollAnchorTest' --tests '*OpenCvFaceRegistrarTest' --rerun`
Expected: PASS. Bleibt ein synthetischer Test mit der neuen Schwelle rot (Papier nicht mehr verankert), ist die Schwelle für eine saubere, synthetische Kante zu hoch: Befund notieren und melden, nicht den Test aufweichen.

- [ ] **Step 5: `BUILDING.md`** bei den Korpusläufen ergänzen:

```
./gradlew :detection:testDevDebugUnitTest --tests '*RollAnchorCorpusRun' --rerun
```

mit einem Satz: „Misst, ob die Ansichten einer Passe nach dem Roll-Anker dieselbe Drehung haben; Bericht `roll.md`, Debugbilder unter `roll/`.“

- [ ] **Step 6: Commit**

```bash
git add detection/src BUILDING.md
git commit -m "registration: set the roll anchor's threshold from the corpus and pin it"
```

---

### Task 8: Pfeilläufe neu setzen, Nachtrag in der Spec

**Files:**
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRun.kt` (`PINS`, Zeile 90, und die KDoc darüber)
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowCorpusRun.kt` (`PINS`, Zeile 298, und die KDoc darüber)
- Modify: `docs/design/2026-09-23-registration-roll-anchor-design.md` (Nachtrag)

- [ ] **Step 1: Beide Läufe ausführen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*ArrowCorpusRun' --tests '*LearnedArrowCorpusRun' --rerun`
Expected: FAIL an den Pins, sofern sich etwas verschoben hat. Aus beiden Berichten die Werte der schrägen Gruppe ablesen: Fotos, gelistete, gefundene, Fehlfunde, richtige/vergleichbare Ringwerte, Median und p95 des Positionsfehlers.

- [ ] **Step 2: Verschiebung einordnen.** Für jede Ansicht, deren Treffer sich gegenüber master ändern, prüfen, ob sie Treffer **ohne** eigene Spitze hat (übernommene Wahrheit) oder ob das gelernte Modell dort anders findet. Eine Verschlechterung um mehr als zwei gefundene Pfeile in einem Lauf ist vor dem Neusetzen zu melden, mit den betroffenen Ansichten.

- [ ] **Step 3: Pins neu setzen.** `PINS` in beiden Läufen auf die neuen Werte, je ein KDoc-Absatz „Re-pinned 2026-09-23 after the roll anchor: … (vorher …)“ mit der Begründung aus Step 2.

- [ ] **Step 4: Alles laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest :detection-corpus:test --rerun`
Expected: PASS.

- [ ] **Step 5: Nachtrag in der Spec.** Am Ende von `docs/design/2026-09-23-registration-roll-anchor-design.md` einen Abschnitt `## Nachtrag 2026-09-xx: umgesetzt und gemessen` mit: gesetzter Schwelle, verankerte Ansichten von allen, größte Spannweite je Passe vorher/nachher, Ausreißer mit Ursache, Verschiebung der Pfeilpins in beiden Läufen und was sie erklärt.

- [ ] **Step 6: Commit**

```bash
git add detection/src docs/design
git commit -m "detection: re-pin the arrow runs after the roll anchor; addendum with the numbers"
```

---

## Abschluss

Nach Task 8: Branch pushen, PR gegen `master` mit den Zahlen aus dem Nachtrag. Danach die Projektnotiz im Obsidian-Vault (`01 Projects/MyTargets Pfeilerkennung`) unter Punkt 1 um einen Nachtrag in Alltagssprache ergänzen.
