# Registrierung aus dem Bild (Plan 3a) — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:detection` registriert eine `WAFull`-Auflage aus dem Originalfoto — Homographie vom Bild in spot-lokale Auflagenkoordinaten — und ein Korpuslauf misst den Fehler gegen die Referenz-Homographien der Sidecars.

**Architecture:** Neues Paket `de.dreier.mytargets.detection.registration` in `:detection`, gegen die OpenCV-Java-API geschrieben und auf der Desktop-JVM mit `org.openpnp:opencv` getestet. OpenCV macht nur Flächenoperationen (`resize`, `cvtColor`, `boxFilter`, `warpPerspective`, Zeichnen); alles je Pixel läuft in Kotlin über einmal geholte Arrays. Die vorhandene Geometrie (`Conic`, `Rectification`, `Mat3`) bleibt reines Kotlin. `:detection-corpus` bekommt die Fehlerkennzahl und den Bericht, ohne ein Bild anzufassen.

**Tech Stack:** Kotlin, Android-Library-Modul, `org.openpnp:opencv:4.9.0-0` (compileOnly und Test), JUnit 4, Truth.

**Spec:** `docs/design/2026-09-10-detection-registration-design.md` (bindend für diesen Plan), dazu `docs/design/2026-09-09-arrow-detection-design.md` (Haupt-Spec) und `<DETECTION_CORPUS_DIR>/tools/register.py`, dessen Verfahren portiert wird.

## Präzisierungen gegenüber dem Design

Beim Ausarbeiten sind vier Stellen genauer geworden. Keine ändert eine Entscheidung des Designs.

1. **Die Umrechnung klein → Original ist affin.** `cv::resize` bildet Pixelmitten ab: `x_klein = sx·x_orig + (0,5·sx − 0,5)`. `S` ist deshalb nicht `diag(s, s, 1)`, sondern
   `[[sx, 0, 0,5·sx − 0,5], [0, sy, 0,5·sy − 0,5], [0, 0, 1]]`, mit getrennten `sx`, `sy`, weil gerundete Arbeitsgrößen das Seitenverhältnis minimal ändern. Die Formeln des Designs, `H_orig = H_klein · S` und `C_orig = Sᵀ · C_klein · S`, bleiben.
2. **Strahlen runden auf das nächste Pixel.** `register.py` schneidet ab (`astype(int)`), was jeden Ring um einen halben Pixel verschiebt.
3. **Nach einem verworfenen Ring sucht der nächste relativ zum letzten angenommenen.** `register.py` rechnet das Fenster aus dem unmittelbar vorigen Nennradius; fällt ein Ring aus, erreicht das Fenster den nächsten nicht mehr.
4. **Das vierte Debug-Bild zeichnet der Runner.** Die Sollringe in Magenta brauchen die Referenz, die nur der Runner kennt. Die Bilder 1 bis 3 kommen vom Registrar.

## Global Constraints

- **Sprache:** Code, Bezeichner, Kommentare, Detail- und Berichtstexte Englisch.
- **Lizenzkopf:** Jede neue Kotlin-Datei beginnt mit dem GPLv2-Kopf aus `detection/src/test/java/de/dreier/mytargets/detection/geometry/RectificationTest.kt` (Zeilen 1–14, „Copyright (C) 2026 MyTargets contributors“). Die Codeblöcke unten lassen ihn weg.
- **Pfade:** Hauptcode `detection/src/main/java/de/dreier/mytargets/detection/...`, Tests `detection/src/test/java/de/dreier/mytargets/detection/...` (Kotlin-Dateien liegen dort unter `java/`). Korpusmodul `detection-corpus/src/main/kotlin/...` und `detection-corpus/src/test/kotlin/...`.
- **OpenCV:** Hauptcode `compileOnly libs.opencv.desktop` (`org.openpnp:opencv:4.9.0-0`) und importiert daraus nur `org.opencv.core` und `org.opencv.imgproc`. Tests laden die nativen Bibliotheken über `OpenCvRule` (Task 1).
- **Pixelarbeit:** Pixel werden einmal per `Mat.get(0, 0, array)` geholt. `Mat.get(row, col)` in einer Schleife ist verboten, es ist ein JNI-Aufruf je Pixel.
- **Speicher:** Jede selbst erzeugte `Mat` wird in `try`/`finally` mit `release()` freigegeben.
- **Pixelschwellen:** Wert von `register.py` bei 2000 px mal `f`, `f = lange Kante des Arbeitsbilds / 2000`. Zählschwellen und Schwellen in Radien skalieren nicht.
- **Arbeitsbild:** lange Kante höchstens 1600 px, nie vergrößert.
- **Gleitkomma:** `Double`.
- **Berichte:** Zahlen mit `Locale.ROOT`.
- **Keine Schranken auf Korpuszahlen.** Der Korpuslauf prüft hart nur `FACE_MISMATCH` für `a6_x99999_multiple_targets.jpg`.
- **JDK 17:** Gradle braucht `JAVA_HOME` auf ein JDK 17 (`BUILDING.md`).
- **Testbefehle:** `./gradlew :detection:testDevDebugUnitTest` und `./gradlew :detection-corpus:test`. Korpustests überspringen sich ohne `DETECTION_CORPUS_DIR`.

---

## File Structure

`registration/` steht für `detection/src/main/java/de/dreier/mytargets/detection/registration/`, `registration-test/` für das Gegenstück unter `src/test/java`.

| Datei | Task | Verantwortung |
|---|---|---|
| `gradle/libs.versions.toml` | 1 | Eintrag `opencv-desktop` |
| `detection/build.gradle` | 1 | OpenCV compileOnly und Test, `:detection-corpus` im Test, Korpus- und Berichtspfad nur in `testDevDebugUnitTest` |
| `registration-test/OpenCvRule.kt` | 1 | lädt die nativen Bibliotheken einmal je JVM |
| `registration-test/OpenCvImports.kt` | 1 | prüft, dass der Hauptcode nur `core` und `imgproc` importiert |
| `detection-corpus/.../metrics/RegistrationError.kt` | 2 | Fehlerkennzahl, `Homography`-Hilfen |
| `detection-corpus/.../corpus/CaptureMetadata.kt` | 2 | `ImageInfo.matchesDecodedSize` |
| `detection-corpus/.../metrics/RegistrationReport.kt` | 3 | Markdown-Bericht des Laufs |
| `detection/.../geometry/Orientation.kt` | 4 | Spiegelung und Bildaufrechte, aus `Rectification` herausgelöst |
| `detection/.../geometry/Rectification.kt` | 4 | `attempt` mit Grund statt `null` |
| `registration/RobustConic.kt` | 5 | Sampson-Abstand, robuster Fit |
| `registration/LinearSystem.kt` | 6 | kleines Gleichungssystem lösen |
| `registration/HomographyRefinement.kt` | 6 | Levenberg-Marquardt, Ringe verwerfen |
| `registration/ClassImage.kt` | 7 | `ColourClass`, Klassenkarte |
| `registration/HsvPixels.kt` | 7 | HSV einmal aus OpenCV holen |
| `registration/SmallKMeans.kt` | 7 | deterministisches k-means in 2D |
| `registration/ColourClassifier.kt` | 7 | Farbklassen, Scheibenreferenz |
| `registration/RayTransitions.kt` | 8 | Übergänge entlang von Strahlen |
| `registration/YellowDiscs.kt` | 9 | Scheiben suchen, vermessen, zusammenfassen, zählen |
| `registration/WorkingScale.kt` | 10 | Arbeitsgröße, `f`, `S` |
| `registration/Registration.kt` | 10 | Schnittstelle aus dem Design |
| `registration/RingSearch.kt` | 10 | Ringe nacheinander finden und annehmen |
| `registration/OpenCvFaceRegistrar.kt` | 10, 11 | die Pipeline der Stufen 1 und 2 |
| `registration-test/SyntheticFace.kt` | 10 | Testfotos mit exakt bekannter Homographie |
| `registration/DebugImages.kt` | 11 | Debug-Bilder 1 bis 3 |
| `registration/OpenCvMats.kt` | 10 | `Mat3` → OpenCV-`Mat` |
| `registration/FaceWarp.kt` | 12 | Stufe 3: Entzerren |
| `registration-test/PngDebugSink.kt` | 13 | Debug-Bilder als PNG |
| `registration-test/RegistrationCorpusRun.kt` | 13 | der Korpuslauf |
| `BUILDING.md` | 13 | wie man den Lauf startet |

---

## Task 1: OpenCV im Build und in den Tests

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `detection/build.gradle`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvRule.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvImports.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvSmokeTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvImportRuleTest.kt`

**Interfaces:**
- Consumes: nichts
- Produces:
  - Katalogeintrag `libs.opencv.desktop`
  - `class OpenCvRule : ExternalResource` mit `companion fun load()`, Nutzung `@get:Rule val openCv = OpenCvRule()`
  - `object OpenCvImports { fun forbidden(sources: Map<String, String>): List<String> }`
  - System-Properties in `testDevDebugUnitTest`: `detection.corpus.dir` (falls konfiguriert), `detection.report.dir`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`OpenCvSmokeTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

class OpenCvSmokeTest {

    @get:Rule
    val openCv = OpenCvRule()

    @Test
    fun theDesktopLibraryIsVersion49() {
        // The main code compiles against 4.9 and the app ships 4.14; this pins
        // which side of that gap the tests run on.
        assertThat(Core.VERSION).startsWith("4.9")
    }

    @Test
    fun aMatRoundTripsThroughOneBulkByteArray() {
        val mat = Mat(2, 3, CvType.CV_8UC3)
        try {
            val data = ByteArray(2 * 3 * 3) { it.toByte() }
            mat.put(0, 0, data)
            val back = ByteArray(data.size)
            mat.get(0, 0, back)
            assertThat(back.toList()).isEqualTo(data.toList())
        } finally {
            mat.release()
        }
    }
}
```

`OpenCvImportRuleTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class OpenCvImportRuleTest {

    @Test
    fun reportsAnImportOutsideCoreAndImgproc() {
        val sources = mapOf(
            "Ok.kt" to "import org.opencv.core.Mat\nimport org.opencv.imgproc.Imgproc\n",
            "Bad.kt" to "import org.opencv.core.Mat\nimport org.opencv.highgui.HighGui\n"
        )

        assertThat(OpenCvImports.forbidden(sources))
            .containsExactly("Bad.kt: org.opencv.highgui.HighGui")
    }

    @Test
    fun aWildcardImportOfCoreIsAllowed() {
        assertThat(OpenCvImports.forbidden(mapOf("W.kt" to "import org.opencv.core.*\n")))
            .isEmpty()
    }

    @Test
    fun theMainSourcesImportOnlyCoreAndImgproc() {
        // Gradle runs unit tests with the module directory as working directory.
        val root = File("src/main/java")
        assertThat(root.isDirectory).isTrue()
        val sources = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to it.readText() }
        assertThat(sources).isNotEmpty()

        assertThat(OpenCvImports.forbidden(sources)).isEmpty()
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*OpenCv*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'opencv'`, `'OpenCvRule'`, `'OpenCvImports'`.

- [ ] **Step 3: Katalog und Build**

In `gradle/libs.versions.toml` unter `[versions]`, alphabetisch passend:

```toml
opencv-desktop = "4.9.0-0"
```

und unter `[libraries]`:

```toml
opencv-desktop = { module = "org.openpnp:opencv", version.ref = "opencv-desktop" }
```

In `detection/build.gradle` den `dependencies`-Block ersetzen und den Test-Block anhängen:

```groovy
dependencies {
    implementation libs.kotlin.stdlib.jdk7

    // The Java bindings of the desktop build. The app ships
    // org.opencv:opencv 4.14.0; compiling against 4.9 means an API that 4.9
    // lacks fails here rather than in a test (registration design,
    // "OpenCV-Version"). Only org.opencv.core and org.opencv.imgproc may be
    // imported: this jar has modules the Android AAR does not.
    compileOnly libs.opencv.desktop

    testImplementation libs.opencv.desktop
    testImplementation project(':detection-corpus')
    testImplementation libs.junit4
    testImplementation libs.truth
}

// The corpus run and its report are wired into one variant only; otherwise
// ./gradlew test would measure the corpus in all six.
tasks.withType(Test).configureEach { task ->
    if (task.name == 'testDevDebugUnitTest') {
        def corpusDir = rootProject.findProperty('DETECTION_CORPUS_DIR')
        if (corpusDir != null) {
            task.systemProperty 'detection.corpus.dir',
                rootProject.file(corpusDir).absolutePath
        }
        task.systemProperty 'detection.report.dir',
            layout.buildDirectory.dir('reports/detection').get().asFile.absolutePath
    }
}
```

Das openpnp-Jar ist 110 MB groß; der erste Build danach dauert länger, weil Jetifier es einmal durchsieht.

- [ ] **Step 4: `OpenCvRule` und `OpenCvImports`**

`OpenCvRule.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import org.junit.rules.ExternalResource

/**
 * Loads the native libraries that org.openpnp:opencv bundles for the desktop,
 * once per test JVM. loadLocally rather than loadShared: loadShared patches
 * java.library.path by reflection, which JDK 17 refuses.
 */
class OpenCvRule : ExternalResource() {

    override fun before() = load()

    companion object {
        @Volatile
        private var loaded = false

        @Synchronized
        fun load() {
            if (!loaded) {
                nu.pattern.OpenCV.loadLocally()
                loaded = true
            }
        }
    }
}
```

`OpenCvImports.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

/**
 * The main code compiles against the desktop jar, which carries modules the
 * Android AAR lacks (org.opencv.highgui). Only these two packages are safe on
 * both.
 */
object OpenCvImports {

    private val allowed = listOf("org.opencv.core.", "org.opencv.imgproc.")
    private val importLine =
        Regex("""^\s*import\s+(org\.opencv\.[A-Za-z0-9_.]*)""", RegexOption.MULTILINE)

    /** "file: imported name" for every import outside the allowed packages. */
    fun forbidden(sources: Map<String, String>): List<String> =
        sources.flatMap { (name, text) ->
            importLine.findAll(text)
                .map { it.groupValues[1] }
                .filter { imported -> allowed.none { imported.startsWith(it) } }
                .map { "$name: $it" }
                .toList()
        }.sorted()
}
```

- [ ] **Step 5: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*OpenCv*'`
Expected: PASS, 5 Tests. Danach die ganze Suite: `./gradlew :detection:testDevDebugUnitTest` — alle vorhandenen Geometrietests bleiben grün.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml detection/build.gradle detection/src/test/java/de/dreier/mytargets/detection/registration
git commit -m "detection: build against the desktop OpenCV bindings and load them in tests"
```

---

## Task 2: Registrierungsfehler und Größenprüfung im Korpusmodul

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/RegistrationError.kt`
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CaptureMetadata.kt` (`ImageInfo`)
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/RegistrationErrorTest.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusEntryTest.kt`

**Interfaces:**
- Consumes: `ImageInfo(fileName, width, height, exifOrientation: Int?)`
- Produces:
  - `data class RegistrationError(median: Double, max: Double, visiblePoints: Int)` mit `companion fun between(reference: List<Double>, predicted: List<Double>, imageWidth: Int, imageHeight: Int): RegistrationError?`, `RING_RADII`, `ANGLES`
  - `object Homography { fun map(h: DoubleArray, x: Double, y: Double): DoubleArray?; fun inverse(h: DoubleArray): DoubleArray?; fun scaleTarget(h: List<Double>, factor: Double): List<Double> }`
  - `ImageInfo.matchesDecodedSize(width: Int, height: Int): Boolean`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`RegistrationErrorTest.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RegistrationErrorTest {

    /** Face centre at (1000, 900), 400 px per spot radius, seen head on. */
    private val frontal = listOf(
        1.0 / 400, 0.0, -1000.0 / 400,
        0.0, 1.0 / 400, -900.0 / 400,
        0.0, 0.0, 1.0
    )

    /** A view with perspective: the inverse of a target-to-image map. */
    private val tilted = Homography.inverse(
        doubleArrayOf(400.0, 30.0, 1000.0, -20.0, 380.0, 900.0, 0.05, 0.08, 1.0)
    )!!.toList()

    private fun times(a: List<Double>, b: List<Double>): List<Double> =
        List(9) { i -> (0..2).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] } }

    @Test
    fun identicalHomographiesHaveNoError() {
        val e = RegistrationError.between(tilted, tilted, 2400, 2000)!!

        assertThat(e.max).isLessThan(1e-9)
        assertThat(e.visiblePoints).isEqualTo(5 * 72)
    }

    @Test
    fun aShiftOfOneHundredthIsAnErrorOfOneHundredthEverywhere() {
        val shift = listOf(1.0, 0.0, 0.01, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val e = RegistrationError.between(tilted, times(shift, tilted), 2400, 2000)!!

        assertThat(e.median).isWithin(1e-9).of(0.01)
        assertThat(e.max).isWithin(1e-9).of(0.01)
    }

    @Test
    fun aRotationOfFiveDegreesIsTheChordOfEachRing() {
        val a = 5.0 * PI / 180
        val rotation = listOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)

        val e = RegistrationError.between(tilted, times(rotation, tilted), 2400, 2000)!!

        // The chord 2 r sin(a/2) is largest on the rim. Five rings of 72
        // points each put the median on ring 0.6.
        assertThat(e.max).isWithin(1e-9).of(2.0 * sin(a / 2))
        assertThat(e.median).isWithin(1e-9).of(2.0 * 0.6 * sin(a / 2))
    }

    @Test
    fun onlyPointsInsideTheImageCount() {
        // The image ends at x = 999, one pixel left of the face centre. Of the
        // 72 angles of each ring, 95 to 265 degrees lie left of it: 35.
        val e = RegistrationError.between(frontal, frontal, 1000, 2000)!!

        assertThat(e.visiblePoints).isEqualTo(5 * 35)
    }

    @Test
    fun noVisiblePointGivesNull() {
        assertThat(RegistrationError.between(frontal, frontal, 100, 100)).isNull()
    }

    @Test
    fun aWa6RingReferenceScaledToWaFullUnitsMatchesTheWaFullRegistration() {
        // register.py stores WA6Ring references in WA6Ring units, where radius
        // 1.0 sits at 0.6 of the full face: H6 = diag(1/0.6, 1/0.6, 1) · H.
        val wa6 = Homography.scaleTarget(frontal, 1.0 / 0.6)

        val e = RegistrationError.between(
            Homography.scaleTarget(wa6, 0.6), frontal, 2400, 2000
        )!!

        assertThat(e.max).isLessThan(1e-9)
    }
}
```

In `CorpusEntryTest.kt` vor der letzten schließenden Klammer der Klasse einfügen:

```kotlin
    @Test
    fun orientationSixMatchesTheSwappedDecodedSize() {
        // The sidecar records the raw size; a decoder that applies the EXIF
        // orientation turns 4000 x 2252 at orientation 6 into 2252 x 4000.
        val info = ImageInfo("t.jpg", 4000, 2252, 6)

        assertThat(info.matchesDecodedSize(2252, 4000)).isTrue()
        assertThat(info.matchesDecodedSize(4000, 2252)).isFalse()
    }

    @Test
    fun anImageWithoutOrientationMatchesItsRecordedSize() {
        val info = ImageInfo("t.jpg", 3120, 4160, null)

        assertThat(info.matchesDecodedSize(3120, 4160)).isTrue()
        assertThat(info.matchesDecodedSize(4160, 3120)).isFalse()
    }
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection-corpus:test`
Expected: FAIL beim Kompilieren — `Unresolved reference 'Homography'`, `'RegistrationError'`, `'matchesDecodedSize'`.

- [ ] **Step 3: Implementieren**

`RegistrationError.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How far a predicted registration is from the reference, in spot radii.
 *
 * Both homographies map pixels of the EXIF-rotated original to spot-local
 * target coordinates, nine values row by row. Points on the rings 0.2 to 1.0
 * go to the image with the reference and come back with the prediction; the
 * distance to where they started is the error. Only points that land inside
 * the image count, so a cropped face is measured where it can be seen.
 */
data class RegistrationError(
    val median: Double,
    val max: Double,
    val visiblePoints: Int
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
            require(reference.size == 9 && predicted.size == 9) {
                "a homography has nine values"
            }
            val toImage = requireNotNull(Homography.inverse(reference.toDoubleArray())) {
                "the reference homography is singular"
            }
            val back = predicted.toDoubleArray()

            val errors = ArrayList<Double>(RING_RADII.size * ANGLES)
            for (radius in RING_RADII) {
                for (k in 0 until ANGLES) {
                    val angle = 2.0 * PI * k / ANGLES
                    val tx = radius * cos(angle)
                    val ty = radius * sin(angle)
                    val p = Homography.map(toImage, tx, ty) ?: continue
                    if (p[0] < 0.0 || p[0] > imageWidth - 1.0) continue
                    if (p[1] < 0.0 || p[1] > imageHeight - 1.0) continue
                    val q = Homography.map(back, p[0], p[1]) ?: continue
                    errors += hypot(q[0] - tx, q[1] - ty)
                }
            }
            if (errors.isEmpty()) return null

            errors.sort()
            val n = errors.size
            val median = if (n % 2 == 1) {
                errors[n / 2]
            } else {
                0.5 * (errors[n / 2 - 1] + errors[n / 2])
            }
            return RegistrationError(median, errors.last(), n)
        }
    }
}

/** 3x3 homographies as nine values, row by row. */
object Homography {

    /** Null when the point maps to infinity. */
    fun map(h: DoubleArray, x: Double, y: Double): DoubleArray? {
        val w = h[6] * x + h[7] * y + h[8]
        if (abs(w) < 1e-12) return null
        return doubleArrayOf(
            (h[0] * x + h[1] * y + h[2]) / w,
            (h[3] * x + h[4] * y + h[5]) / w
        )
    }

    /** Null when [h] is singular; the guard is relative to the largest entry. */
    fun inverse(h: DoubleArray): DoubleArray? {
        require(h.size == 9) { "a homography has nine values, got ${h.size}" }
        val c00 = h[4] * h[8] - h[5] * h[7]
        val c01 = h[5] * h[6] - h[3] * h[8]
        val c02 = h[3] * h[7] - h[4] * h[6]
        val det = h[0] * c00 + h[1] * c01 + h[2] * c02
        val scale = h.maxOf { abs(it) }
        if (scale == 0.0 || abs(det) < 1e-15 * scale * scale * scale) return null
        return doubleArrayOf(
            c00 / det, (h[2] * h[7] - h[1] * h[8]) / det, (h[1] * h[5] - h[2] * h[4]) / det,
            c01 / det, (h[0] * h[8] - h[2] * h[6]) / det, (h[2] * h[3] - h[0] * h[5]) / det,
            c02 / det, (h[1] * h[6] - h[0] * h[7]) / det, (h[0] * h[4] - h[1] * h[3]) / det
        )
    }

    /**
     * diag(factor, factor, 1) · h: the same registration with target
     * coordinates scaled by [factor]. A WA6Ring reference times 0.6 is in
     * WAFull units, because WA6Ring's radius 1.0 lies at 0.6 of the full face.
     */
    fun scaleTarget(h: List<Double>, factor: Double): List<Double> =
        h.mapIndexed { i, v -> if (i < 6) v * factor else v }
}
```

In `CaptureMetadata.kt` in `ImageInfo` nach `longEdge` einfügen:

```kotlin
    /**
     * Whether a decoder that applies the EXIF orientation produced [width] x
     * [height]. The sidecar records the raw size of the file; orientations 5
     * to 8 turn the image by 90 degrees and swap the sides.
     */
    fun matchesDecodedSize(width: Int, height: Int): Boolean {
        val swapped = (exifOrientation ?: 1) in 5..8
        return if (swapped) {
            width == this.height && height == this.width
        } else {
            width == this.width && height == this.height
        }
    }
```

- [ ] **Step 4: Laufen lassen, grün prüfen**

Run: `./gradlew :detection-corpus:test`
Expected: PASS, alle bisherigen Tests plus 8 neue.

- [ ] **Step 5: Commit**

```bash
git add detection-corpus/src
git commit -m "detection-corpus: measure a registration against the reference homography"
```

---

## Task 3: Der Registrierungsbericht

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/RegistrationReport.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/RegistrationReportTest.kt`

**Interfaces:**
- Consumes: `RegistrationError` (Task 2)
- Produces:
  - `class RegistrationRow(imageName: String, outOfScope: String?, outcome: String, detail: String?, ringsUsed: List<Double>, worstRingRms: Double?, error: RegistrationError?)` mit `registered: Boolean` und `companion const val REGISTERED = "registered"`
  - `object RegistrationReport { fun render(rows: List<RegistrationRow>, title: String): String }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`RegistrationReportTest.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class RegistrationReportTest {

    private fun registered(name: String, max: Double, outOfScope: String? = null) =
        RegistrationRow(
            imageName = name, outOfScope = outOfScope,
            outcome = RegistrationRow.REGISTERED, detail = null,
            ringsUsed = listOf(0.2, 0.4, 0.6), worstRingRms = 0.004,
            error = RegistrationError(max / 2, max, 360)
        )

    private fun failed(name: String, outcome: String, detail: String, outOfScope: String? = null) =
        RegistrationRow(name, outOfScope, outcome, detail, emptyList(), null, null)

    @Test
    fun theHeaderCountsInScopeRegisteredAndFailedByReason() {
        val report = RegistrationReport.render(
            listOf(
                registered("a.jpg", 0.003),
                failed("b.jpg", "FACE_NOT_FOUND", "no yellow disc"),
                failed("c.jpg", "FACE_MISMATCH", "3 yellow discs, expected 1", "three faces")
            ),
            title = "Run"
        )

        assertThat(report).contains(
            "3 photographs, 2 in scope: 1 registered, 1 failed (FACE_NOT_FOUND: 1)."
        )
    }

    @Test
    fun failuresComeFirstThenTheWorstRegistration() {
        val report = RegistrationReport.render(
            listOf(
                registered("good.jpg", 0.001),
                registered("bad.jpg", 0.010),
                failed("lost.jpg", "FACE_NOT_FOUND", "no yellow disc")
            ),
            title = "Run"
        )

        val lost = report.indexOf("| lost.jpg |")
        val bad = report.indexOf("| bad.jpg |")
        val good = report.indexOf("| good.jpg |")
        assertThat(lost).isLessThan(bad)
        assertThat(bad).isLessThan(good)
    }

    @Test
    fun aRegisteredRowCarriesItsRingsAndErrors() {
        val report = RegistrationReport.render(listOf(registered("a.jpg", 0.003)), "Run")

        assertThat(report).contains(
            "| a.jpg | registered | 0.2, 0.4, 0.6 | 0.0040 | 0.0015 | 0.0030 | 360 |"
        )
    }

    @Test
    fun aFailedRowCarriesItsDetail() {
        val report = RegistrationReport.render(
            listOf(failed("b.jpg", "FACE_NOT_FOUND", "no yellow disc")), "Run"
        )

        assertThat(report).contains("| b.jpg | FACE_NOT_FOUND: no yellow disc | - | - | - | - | - |")
    }

    @Test
    fun theSummaryIsTheMedianAndLargestOfThePerPhotographMaxima() {
        val report = RegistrationReport.render(
            listOf(registered("a.jpg", 0.001), registered("b.jpg", 0.003), registered("c.jpg", 0.010)),
            title = "Run"
        )

        assertThat(report).contains(
            "Median of the per-photograph maxima: 0.0030 spot radii. " +
                "Largest: 0.0100 spot radii (c.jpg)."
        )
    }

    @Test
    fun outOfScopeRowsAreListedApartAndStayOutOfTheSummary() {
        val report = RegistrationReport.render(
            listOf(registered("in.jpg", 0.002), registered("w.jpg", 0.5, outOfScope = "WA6Ring face")),
            title = "Run"
        )

        assertThat(report).contains("Largest: 0.0020 spot radii (in.jpg).")
        assertThat(report).contains("## Out of scope")
        assertThat(report).contains("| w.jpg | WA6Ring face | registered | 0.5000 |")
    }

    @Test
    fun numbersDoNotFollowTheDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val report = RegistrationReport.render(listOf(registered("a.jpg", 0.003)), "Run")
            assertThat(report).contains("0.0030")
            assertThat(report).doesNotContain("0,0030")
        } finally {
            Locale.setDefault(original)
        }
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection-corpus:test --tests '*RegistrationReportTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'RegistrationRow'`.

- [ ] **Step 3: Implementieren**

`RegistrationReport.kt`:

```kotlin
package de.dreier.mytargets.detection.metrics

import java.util.Locale

/** One photograph of a registration run, as the report shows it. */
class RegistrationRow(
    val imageName: String,
    /** The reason from out-of-scope.json, or null when the photograph is in scope. */
    val outOfScope: String?,
    /** [REGISTERED], or the name of the failure. */
    val outcome: String,
    /** Why it failed; null for a registered row. */
    val detail: String?,
    /** Nominal radii of the rings that went into the final fit. */
    val ringsUsed: List<Double>,
    val worstRingRms: Double?,
    val error: RegistrationError?
) {
    val registered: Boolean
        get() = outcome == REGISTERED

    companion object {
        const val REGISTERED = "registered"
    }
}

/**
 * Renders a registration run as Markdown. Photographs outside the scope get a
 * table of their own and count for no summary. There is no verdict: the
 * report measures, and bounds are set against its numbers later.
 */
object RegistrationReport {

    fun render(rows: List<RegistrationRow>, title: String): String {
        val inScope = rows.filter { it.outOfScope == null }
        val outOfScope = rows.filter { it.outOfScope != null }
        val registered = inScope.filter { it.registered }
        val failed = inScope.filterNot { it.registered }

        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        val byReason = failed.groupingBy { it.outcome }.eachCount().toSortedMap()
        val reasons = if (byReason.isEmpty()) {
            ""
        } else {
            byReason.entries.joinToString(prefix = " (", postfix = ")") { "${it.key}: ${it.value}" }
        }
        sb.appendLine(
            "${rows.size} photographs, ${inScope.size} in scope: " +
                "${registered.size} registered, ${failed.size} failed$reasons."
        )
        sb.appendLine()
        sb.appendLine(
            "Errors are in spot radii, measured on the rings 0.2 to 1.0 where they lie " +
                "inside the image."
        )

        sb.appendLine()
        sb.appendLine("## In scope")
        sb.appendLine()
        sb.appendLine(
            "| Photograph | Outcome | Rings | Worst ring RMS | Error, median | Error, max | " +
                "Visible points |"
        )
        sb.appendLine("|---|---|---|---|---|---|---|")
        val ordered = failed.sortedBy { it.imageName } +
            registered.sortedByDescending { it.error?.max ?: Double.MAX_VALUE }
        for (row in ordered) {
            sb.appendLine(inScopeLine(row))
        }

        sb.appendLine()
        val maxima = registered.mapNotNull { row -> row.error?.let { row to it.max } }
            .sortedBy { it.second }
        if (maxima.isEmpty()) {
            sb.appendLine("No photograph in scope was measured.")
        } else {
            val values = maxima.map { it.second }
            val (worstName, worst) = maxima.last().let { it.first.imageName to it.second }
            sb.appendLine(
                "Median of the per-photograph maxima: ${number(median(values))} spot radii. " +
                    "Largest: ${number(worst)} spot radii ($worstName)."
            )
        }

        if (outOfScope.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Out of scope")
            sb.appendLine()
            sb.appendLine("These count for no summary.")
            sb.appendLine()
            sb.appendLine("| Photograph | Reason | Outcome | Error, max |")
            sb.appendLine("|---|---|---|---|")
            for (row in outOfScope.sortedBy { it.imageName }) {
                sb.appendLine(
                    "| ${row.imageName} | ${row.outOfScope} | ${outcomeText(row)} | " +
                        "${row.error?.let { number(it.max) } ?: "-"} |"
                )
            }
        }
        return sb.toString()
    }

    private fun inScopeLine(row: RegistrationRow): String {
        if (!row.registered) {
            return "| ${row.imageName} | ${outcomeText(row)} | - | - | - | - | - |"
        }
        val rings = row.ringsUsed.joinToString { String.format(Locale.ROOT, "%.1f", it) }
        val rms = row.worstRingRms?.let { number(it) } ?: "-"
        val error = row.error
        val errors = if (error == null) {
            "not measured | not measured | 0"
        } else {
            "${number(error.median)} | ${number(error.max)} | ${error.visiblePoints}"
        }
        return "| ${row.imageName} | ${RegistrationRow.REGISTERED} | $rings | $rms | $errors |"
    }

    private fun outcomeText(row: RegistrationRow) =
        if (row.registered || row.detail == null) row.outcome else "${row.outcome}: ${row.detail}"

    private fun median(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }

    // Locale.ROOT on purpose, as in MetricsReport: reports are diffed between runs.
    private fun number(value: Double) = String.format(Locale.ROOT, "%.4f", value)
}
```

- [ ] **Step 4: Laufen lassen, grün prüfen**

Run: `./gradlew :detection-corpus:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add detection-corpus/src
git commit -m "detection-corpus: render a registration run as a report"
```

---

## Task 4: `Rectification` nennt den Grund, `Orientation` wird geteilt

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Orientation.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Rectification.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/OrientationTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/RectificationTest.kt`

Das Verhalten im Erfolgsfall ändert sich nicht; `fromConcentricCircles` bleibt als dünne Hülle, damit die neun Erfolgstests unverändert laufen.

**Interfaces:**
- Consumes: `Mat3`, `Vec2`, `Conic`, `VanishingLine`
- Produces:
  - `object Orientation { val MIRROR_Y: Mat3; fun orient(mapping: Mat3, at: Vec2, imageUp: Vec2 = Vec2(0.0, -1.0)): Mat3?; fun isMirrored(mapping: Mat3, at: Vec2): Boolean; fun rotationAligning(mapping: Mat3, imagedCentre: Vec2, imageUp: Vec2): Mat3? }`
  - `Rectification.Reason` (Enum), `Rectification.Attempt` mit `Rectified(result: Result)` und `Rejected(reason: Reason, value: Double? = null, limit: Double? = null)` samt `describe(): String`
  - `Rectification.attempt(outer: Conic, outerRadius: Double, inner: Conic, innerRadius: Double, imageUp: Vec2 = Vec2(0.0, -1.0)): Attempt`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`OrientationTest.kt`:

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OrientationTest {

    /** Head on: face centre at (640, 480), 300 px per target unit. */
    private val frontal = Mat3.of(
        1.0 / 300, 0.0, -640.0 / 300,
        0.0, 1.0 / 300, -480.0 / 300,
        0.0, 0.0, 1.0
    )
    private val centre = Vec2(640.0, 480.0)
    private val right = Vec2(940.0, 480.0)
    private val up = Vec2(640.0, 180.0)

    @Test
    fun anUprightMappingStaysAsItIs() {
        val oriented = Orientation.orient(frontal, centre)!!

        assertThat(oriented.mapPoint(right)!!.distanceTo(Vec2(1.0, 0.0))).isLessThan(1e-12)
        assertThat(oriented.mapPoint(up)!!.distanceTo(Vec2(0.0, -1.0))).isLessThan(1e-12)
    }

    @Test
    fun aMappingMirroredInXIsRepaired() {
        // Needs both steps: the reflection repair leaves a half turn, which
        // the rotation then undoes.
        val mirrored = Mat3.of(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) * frontal
        assertThat(Orientation.isMirrored(mirrored, centre)).isTrue()

        val oriented = Orientation.orient(mirrored, centre)!!

        assertThat(Orientation.isMirrored(oriented, centre)).isFalse()
        assertThat(oriented.mapPoint(right)!!.distanceTo(Vec2(1.0, 0.0))).isLessThan(1e-9)
        assertThat(oriented.mapPoint(up)!!.distanceTo(Vec2(0.0, -1.0))).isLessThan(1e-9)
    }

    @Test
    fun aRotatedMappingIsTurnedBackToImageUp() {
        val oriented = Orientation.orient(Mat3.rotation(0.7) * frontal, centre)!!

        assertThat(oriented.mapPoint(up)!!.distanceTo(Vec2(0.0, -1.0))).isLessThan(1e-9)
    }
}
```

In `RectificationTest.kt` den Test `returnsNullWhenTheConicsAreNotConcentricCircles` durch diesen ersetzen:

```kotlin
    @Test
    fun rejectsConicsThatAreNotConcentricCircles() {
        val notACircle = Conic.fit(
            (0 until 12).map { i ->
                val a = 2.0 * PI * i / 12
                Vec2(300.0 * cos(a), 40.0 * sin(a) + 900.0 * cos(a))
            }
        )!!
        val other = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)

        assertThat(Rectification.attempt(notACircle, 1.0, other, 0.4))
            .isInstanceOf(Rectification.Attempt.Rejected::class.java)
        assertThat(Rectification.fromConcentricCircles(notACircle, 1.0, other, 0.4)).isNull()
    }
```

und `rejectsRingsWhoseRadiusRatioDisagreesWithTheDeclaredOne` durch diesen:

```kotlin
    @Test
    fun rejectsRingsWhoseRadiusRatioDisagreesWithTheDeclaredOne() {
        // True ratio 0.4, declared 0.9. This has to reach the ratio guard rather
        // than short-circuiting in the pencil.
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(h)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)

        val attempt = Rectification.attempt(outer, 1.0, inner, 0.9)

        assertThat(attempt).isInstanceOf(Rectification.Attempt.Rejected::class.java)
        val rejected = attempt as Rectification.Attempt.Rejected
        assertThat(rejected.reason).isEqualTo(Rectification.Reason.RATIO_MISMATCH)
        assertThat(rejected.value!!).isWithin(1e-6).of(0.4)
        assertThat(rejected.limit!!).isWithin(1e-12).of(0.9)
        assertThat(rejected.describe()).isEqualTo("radius ratio 0.400, expected 0.900")
    }

    @Test
    fun aSuccessfulAttemptCarriesTheResult() {
        val outer = Conic.circle(Vec2(640.0, 480.0), 300.0)
        val inner = Conic.circle(Vec2(640.0, 480.0), 120.0)

        val attempt = Rectification.attempt(outer, 1.0, inner, 0.4)

        assertThat(attempt).isInstanceOf(Rectification.Attempt.Rectified::class.java)
        val result = (attempt as Rectification.Attempt.Rectified).result
        val recovered = result.imageToTarget.mapPoint(Vec2(940.0, 480.0))!!
        assertThat(recovered.distanceTo(Vec2(1.0, 0.0))).isLessThan(1e-6)
    }
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*geometry*'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'Orientation'`, `'attempt'`.

- [ ] **Step 3: `Orientation.kt` anlegen**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.PI
import kotlin.math.atan2

/**
 * Handedness and rotation of an image-to-target mapping. Concentric circles fix
 * neither: a reflection and every rotation about the face centre map them onto
 * themselves. Shared by [Rectification] and by the registration, which needs
 * both again after refining the homography.
 */
object Orientation {

    /** Reflection in the x axis of target coordinates. */
    val MIRROR_Y: Mat3 = Mat3.of(
        1.0, 0.0, 0.0,
        0.0, -1.0, 0.0,
        0.0, 0.0, 1.0
    )

    /**
     * [mapping] with its handedness repaired at [at] and [imageUp], taken at
     * [at], pointing along -y: upwards on the face. Null when a direction
     * cannot be mapped at [at].
     */
    fun orient(mapping: Mat3, at: Vec2, imageUp: Vec2 = Vec2(0.0, -1.0)): Mat3? {
        val handed = if (isMirrored(mapping, at)) MIRROR_Y * mapping else mapping
        val rotation = rotationAligning(handed, at, imageUp) ?: return null
        return rotation * handed
    }

    /**
     * Whether [mapping] reverses orientation at [at].
     *
     * The rest of a rectification constrains rotation but not handedness, and
     * no assertion phrased in radii, dot products or distances can see a
     * reflection, because all of those are reflection invariant.
     *
     * Target coordinates share the image's handedness -- x right, y downwards,
     * up on the face being negative y -- so a correct map has a positive
     * Jacobian determinant.
     */
    fun isMirrored(mapping: Mat3, at: Vec2): Boolean {
        val jx = mapping.mapDirection(at, Vec2(1.0, 0.0)) ?: return false
        val jy = mapping.mapDirection(at, Vec2(0.0, 1.0)) ?: return false
        return jx.x * jy.y - jx.y * jy.x < 0.0
    }

    /**
     * Rotation that makes [imageUp], taken at [imagedCentre] and seen through
     * [mapping], point along the negative y axis of target coordinates.
     *
     * The direction has to be taken at the centre of the face: [mapping] is
     * projective, and the image of a direction depends on where it is attached.
     * At the image corner the answer would differ by several degrees for a
     * moderately tilted view.
     */
    fun rotationAligning(mapping: Mat3, imagedCentre: Vec2, imageUp: Vec2): Mat3? {
        val direction = mapping.mapDirection(imagedCentre, imageUp) ?: return null
        if (direction.length < 1e-12) return null
        // We want direction to end up pointing at -90 degrees.
        val current = atan2(direction.y, direction.x)
        return Mat3.rotation(-PI / 2.0 - current)
    }
}
```

- [ ] **Step 4: `Rectification.kt` umbauen**

Die Datei ab `object Rectification {` bis zum Ende ersetzen. Die privaten `MIRROR_Y`, `isMirrored` und `rotationAligning` fallen weg, sie leben jetzt in `Orientation`; `metricFromEllipse`, `centreOf` und `radiusOf` bleiben wörtlich. Die Imports `kotlin.math.PI` und `kotlin.math.atan2` entfallen, `java.util.Locale` kommt dazu.

```kotlin
object Rectification {

    class Result(
        val imageToTarget: Mat3,
        val vanishingLine: Vec3,
        val imagedCentre: Vec2
    )

    /** Why a pair of conics gave no rectification. */
    enum class Reason {
        /** The outer conic has no unique centre, so there is no working frame. */
        DEGENERATE_OUTER,

        /** The pencil of the two conics has no usable degenerate member. */
        NO_PENCIL,

        /** The vanishing line passes through the origin of the frame. */
        SINGULAR_AFFINE,

        /** After affine rectification the outer conic is not an ellipse. */
        NOT_AN_ELLIPSE,

        /** A rectified conic has no unique centre. */
        NO_CENTRE,

        /** The rectified outer circle has no radius. */
        ZERO_RADIUS,

        /** Centre distance in outer radii, against [CONCENTRIC_TOLERANCE]. */
        NOT_CONCENTRIC,

        /** Measured radius ratio, against the declared one. */
        RATIO_MISMATCH,

        /** Image up cannot be mapped at the imaged centre. */
        NO_ORIENTATION
    }

    sealed interface Attempt {
        class Rectified(val result: Result) : Attempt

        /**
         * [value] and [limit] are set for [Reason.NOT_CONCENTRIC] (centre
         * distance in outer radii, tolerance) and [Reason.RATIO_MISMATCH]
         * (measured ratio, declared ratio).
         */
        class Rejected(
            val reason: Reason,
            val value: Double? = null,
            val limit: Double? = null
        ) : Attempt {
            fun describe(): String = when (reason) {
                Reason.NOT_CONCENTRIC ->
                    "centres ${format(value)} outer radii apart, limit ${format(limit)}"
                Reason.RATIO_MISMATCH ->
                    "radius ratio ${format(value)}, expected ${format(limit)}"
                else -> reason.name.lowercase().replace('_', ' ')
            }

            private fun format(v: Double?) =
                if (v == null) "?" else String.format(Locale.ROOT, "%.3f", v)
        }
    }

    /**
     * @param outer image of the ring with the larger nominal [outerRadius]
     * @param inner image of the ring with the smaller nominal [innerRadius]
     * @param imageUp the direction that should end up pointing up in target
     *        coordinates; the default assumes the phone was held upright, with
     *        image y growing downwards
     */
    fun fromConcentricCircles(
        outer: Conic,
        outerRadius: Double,
        inner: Conic,
        innerRadius: Double,
        imageUp: Vec2 = Vec2(0.0, -1.0)
    ): Result? =
        (attempt(outer, outerRadius, inner, innerRadius, imageUp) as? Attempt.Rectified)?.result

    /** As [fromConcentricCircles], but says why when there is no result. */
    fun attempt(
        outer: Conic,
        outerRadius: Double,
        inner: Conic,
        innerRadius: Double,
        imageUp: Vec2 = Vec2(0.0, -1.0)
    ): Attempt {
        require(outerRadius > innerRadius) { "outer radius must be the larger one" }

        // Every step below is conic arithmetic, and at pixel scale that mixes a
        // constant term of order 10^7 with quadratic ones of order one: the
        // guards in metricFromEllipse, centreOf and radiusOf then see rounding
        // noise rather than the conic. Work in a frame where the outer ring is
        // roughly the unit circle about the origin -- the same treatment
        // VanishingLine already gives itself -- and convert back at the end.
        val frame = outer.normalisingFrame() ?: return Attempt.Rejected(Reason.DEGENERATE_OUTER)
        val outerInFrame = outer.transformedBy(frame)
        val innerInFrame = inner.transformedBy(frame)

        val pencil = VanishingLine.fromConcentricCircles(outerInFrame, innerInFrame)
            ?: return Attempt.Rejected(Reason.NO_PENCIL)
        // Both of these are in frame coordinates, not image coordinates.
        val lineInFrame = pencil.line
        val centreInFrame = pencil.imagedCentre

        // Affine rectification: send the vanishing line to (0, 0, 1).
        val affine = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            lineInFrame.x, lineInFrame.y, lineInFrame.z
        )
        if (affine.inverse() == null) return Attempt.Rejected(Reason.SINGULAR_AFFINE)

        val affineOuter = outerInFrame.transformedBy(affine)
        val affineInner = innerInFrame.transformedBy(affine)

        // After affine rectification the conics are ellipses that differ from
        // circles by one common linear map. Recover it from the outer one.
        val metric = metricFromEllipse(affineOuter) ?: return Attempt.Rejected(Reason.NOT_AN_ELLIPSE)

        val metricOuter = affineOuter.transformedBy(metric)
        val metricInner = affineInner.transformedBy(metric)

        val centre = centreOf(metricOuter) ?: return Attempt.Rejected(Reason.NO_CENTRE)
        val centreInner = centreOf(metricInner) ?: return Attempt.Rejected(Reason.NO_CENTRE)
        val radius = radiusOf(metricOuter, centre)
        if (radius < 1e-9) return Attempt.Rejected(Reason.ZERO_RADIUS)
        val offset = centre.distanceTo(centreInner) / radius
        if (offset > CONCENTRIC_TOLERANCE) {
            return Attempt.Rejected(Reason.NOT_CONCENTRIC, offset, CONCENTRIC_TOLERANCE)
        }

        // Check the radius ratio; if it is wrong these were not the rings we
        // were told they were.
        val expectedRatio = innerRadius / outerRadius
        val actualRatio = radiusOf(metricInner, centreInner) / radius
        if (abs(actualRatio - expectedRatio) > RATIO_TOLERANCE * expectedRatio) {
            return Attempt.Rejected(Reason.RATIO_MISMATCH, actualRatio, expectedRatio)
        }

        val scale = outerRadius / radius
        val toOrigin = Mat3.translation(Vec2(-centre.x, -centre.y))
        val scaling = Mat3.of(
            scale, 0.0, 0.0,
            0.0, scale, 0.0,
            0.0, 0.0, 1.0
        )

        // This chain starts in frame coordinates, so it must be probed there.
        val withoutRotation = scaling * toOrigin * metric * affine

        // [imageUp] is a direction and [frame] is a similarity, so it passes
        // through unchanged up to a positive scale factor, which leaves the
        // angle -- and hence the rotation -- the same. Only the point the
        // direction is attached to has to be moved into the frame.
        val frameToTarget = Orientation.orient(withoutRotation, centreInFrame, imageUp)
            ?: return Attempt.Rejected(Reason.NO_ORIENTATION)

        // Out of the frame again: image points pass through the frame first,
        // lines transform contragrediently, and the centre needs the inverse.
        val imagedCentre = frame.inverse()?.mapPoint(centreInFrame)
            ?: return Attempt.Rejected(Reason.NO_CENTRE)
        return Attempt.Rectified(
            Result(
                imageToTarget = frameToTarget * frame,
                vanishingLine = (frame.transpose() * lineInFrame).normalized(),
                imagedCentre = imagedCentre
            )
        )
    }

    private const val CONCENTRIC_TOLERANCE = 0.05
    // Relative tolerance on the dimensionless radius ratio; an absolute
    // tolerance would weaken as the rings diverge -- at a ratio of 0.1 it
    // would accept 80 percent relative error.
    private const val RATIO_TOLERANCE = 0.08

    // metricFromEllipse, centreOf and radiusOf unchanged from here on.
}
```

Der Kommentar `// metricFromEllipse, ...` steht nur hier im Plan: In der Datei folgen an dieser Stelle die drei unveränderten privaten Funktionen. Die KDoc der Klasse (Zeilen 23–33) bleibt, ergänzt um den Satz: „[attempt] says why a pair was rejected; the registration reports that per pair.“ Die Prüfung auf `radius < 1e-9` steht jetzt vor der Konzentrizität, weil deren Wert durch den Radius teilt; das Ergebnis `null` oder nicht `null` ändert sich dadurch in keinem Fall.

- [ ] **Step 5: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*geometry*'`
Expected: PASS, alle Geometrietests einschließlich der vier neuen.

- [ ] **Step 6: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/geometry detection/src/test/java/de/dreier/mytargets/detection/geometry
git commit -m "detection: Rectification reports why it rejects a pair; share the orientation helpers"
```

---

## Task 5: Robuster Kegelschnittfit

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/RobustConic.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/RobustConicTest.kt`

**Interfaces:**
- Consumes: `Conic.fit(points: List<Vec2>): Conic?`, `Conic.matrix`
- Produces:
  - `class RobustFit(conic: Conic, inliers: List<Vec2>, medianDistance: Double)`
  - `object RobustConic { fun sampsonDistance(conic: Conic, p: Vec2): Double; fun threshold(medianDistance: Double, floorPx: Double): Double; fun fit(points: List<Vec2>, floorPx: Double): RobustFit?; internal fun median(values: List<Double>): Double }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RobustConicTest {

    private val centre = Vec2(800.0, 600.0)

    private fun onRing(radius: Double, angle: Double) =
        Vec2(centre.x + radius * cos(angle), centre.y + radius * sin(angle))

    private fun ring(radius: Double, count: Int) =
        (0 until count).map { onRing(radius, 2.0 * PI * it / count) }

    /** Most points exactly on radius 150, every tenth one ray step (0.5 px) out. */
    private val quantised = (0 until 400).map { i ->
        onRing(if (i % 10 == 0) 150.5 else 150.0, 2.0 * PI * i / 400)
    }

    @Test
    fun theSampsonDistanceOfACircleIsExact() {
        // For a circle it is (r^2 - R^2) / 2r.
        val d = RobustConic.sampsonDistance(
            Conic.circle(centre, 100.0), Vec2(centre.x + 103.0, centre.y)
        )

        assertThat(d).isWithin(1e-9).of((103.0 * 103.0 - 100.0 * 100.0) / (2 * 103.0))
    }

    @Test
    fun recoversACircleDespiteShaftLikeOutliers() {
        val clean = ring(200.0, 360)
        // Three shafts crossing the ring: runs of points along a ray, outside it.
        val shafts = listOf(0.3, 2.1, 4.4).flatMap { a ->
            (1..20).map { k -> onRing(205.0 + 3.0 * k, a) }
        }

        val fit = RobustConic.fit(clean + shafts, floorPx = 0.4)!!

        assertThat(fit.conic.centre()!!.distanceTo(centre)).isLessThan(0.01)
        assertThat(fit.inliers).containsNoneIn(shafts)
        assertThat(fit.inliers).hasSize(clean.size)
    }

    @Test
    fun theFloorKeepsPointsOneRayStepOff() {
        // With 0.4 px (0.5 px at 2000 px, times f = 0.8) the threshold is 1 px.
        val fit = RobustConic.fit(quantised, floorPx = 0.4)!!

        assertThat(fit.inliers).hasSize(quantised.size)
    }

    @Test
    fun withoutTheFloorThoseSamePointsWouldBeDropped() {
        // Why the floor exists: the median distance nearly vanishes on a clean
        // edge, and the half-pixel points look like outliers.
        val fit = RobustConic.fit(quantised, floorPx = 0.0)!!

        assertThat(fit.inliers.size).isLessThan(quantised.size)
    }

    @Test
    fun theThresholdIsTwoAndAHalfSigmaButNeverBelowTheFloor() {
        assertThat(RobustConic.threshold(0.0, 0.4)).isWithin(1e-6).of(1.0)
        assertThat(RobustConic.threshold(1.0, 0.4)).isWithin(1e-6).of(2.5 * 1.4826)
    }

    @Test
    fun fewerThanFivePointsGiveNoFit() {
        assertThat(RobustConic.fit(ring(100.0, 4), floorPx = 0.4)).isNull()
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RobustConicTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'RobustConic'`.

- [ ] **Step 3: Implementieren**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A conic fitted to boundary points after outliers were removed. */
class RobustFit(
    val conic: Conic,
    val inliers: List<Vec2>,
    /** Median Sampson distance of the inliers, in the units of the points. */
    val medianDistance: Double
)

/**
 * The robust fit of register.py: fit, measure every point's Sampson distance,
 * keep those within 2.5 sigma, four times over. Sigma comes from the median
 * distance and has a floor, because on a clean edge the median nearly
 * vanishes and the half-pixel steps of the ray sampling would then read as
 * outliers.
 */
object RobustConic {

    const val ITERATIONS = 4
    const val KEEP = 2.5
    private const val MEDIAN_TO_SIGMA = 1.4826
    private const val MIN_KEPT = 10

    /** First-order approximation of the geometric distance from [p] to [conic]. */
    fun sampsonDistance(conic: Conic, p: Vec2): Double {
        val h = p.homogeneous()
        val cp = conic.matrix * h
        return abs(h.dot(cp)) / max(2.0 * hypot(cp.x, cp.y), 1e-12)
    }

    /** The distance below which a point is kept. */
    fun threshold(medianDistance: Double, floorPx: Double): Double =
        KEEP * max(medianDistance * MEDIAN_TO_SIGMA + 1e-9, floorPx)

    /**
     * @param floorPx the smallest sigma in pixels: 0.5 px at 2000 px, times f
     * @return null for fewer than five points or when a fit degenerates
     */
    fun fit(points: List<Vec2>, floorPx: Double): RobustFit? {
        if (points.size < 5) return null
        var kept = points
        for (iteration in 0 until ITERATIONS) {
            val conic = Conic.fit(kept) ?: return null
            val limit = threshold(median(kept.map { sampsonDistance(conic, it) }), floorPx)
            val next = points.filter { sampsonDistance(conic, it) < limit }
            if (next.size < MIN_KEPT) break
            kept = next
        }
        val conic = Conic.fit(kept) ?: return null
        return RobustFit(conic, kept, median(kept.map { sampsonDistance(conic, it) }))
    }

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }
}
```

- [ ] **Step 4: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RobustConicTest'`
Expected: PASS, 6 Tests.

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/registration/RobustConic.kt detection/src/test/java/de/dreier/mytargets/detection/registration/RobustConicTest.kt
git commit -m "detection: robust conic fit with a floor under the outlier threshold"
```

---

## Task 6: Die Homographie über alle Ringe verfeinern

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/LinearSystem.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/HomographyRefinement.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/LinearSystemTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/HomographyRefinementTest.kt`

**Interfaces:**
- Consumes: `Mat3`, `Vec2`, `Orientation.orient` (Task 4, nur im Test)
- Produces:
  - `internal object LinearSystem { fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? }`
  - `class RingPoints(radius: Double, points: List<Vec2>)`
  - `class Refined(homography: Mat3, rings: List<RingPoints>)`
  - `object HomographyRefinement { const val DROP_RMS = 0.012; fun refine(start: Mat3, rings: List<RingPoints>): Mat3; fun refineDroppingRings(start: Mat3, rings: List<RingPoints>): Refined; fun radialRms(h: Mat3, ring: RingPoints): Double }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`LinearSystemTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinearSystemTest {

    @Test
    fun solvesAThreeByThreeSystem() {
        val a = arrayOf(
            doubleArrayOf(2.0, 1.0, -1.0),
            doubleArrayOf(-3.0, -1.0, 2.0),
            doubleArrayOf(-2.0, 1.0, 2.0)
        )

        val x = LinearSystem.solve(a, doubleArrayOf(8.0, -11.0, -3.0))!!

        assertThat(x[0]).isWithin(1e-12).of(2.0)
        assertThat(x[1]).isWithin(1e-12).of(3.0)
        assertThat(x[2]).isWithin(1e-12).of(-1.0)
    }

    @Test
    fun aSingularSystemHasNoSolution() {
        val a = arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0))

        assertThat(LinearSystem.solve(a, doubleArrayOf(1.0, 2.0))).isNull()
    }
}
```

`HomographyRefinementTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Orientation
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HomographyRefinementTest {

    /** Target to image: a tilted view, about 300 px per unit. */
    private val targetToImage = Mat3.of(
        300.0, 40.0, 700.0,
        -25.0, 280.0, 500.0,
        0.06, 0.09, 1.0
    )
    private val truth = targetToImage.inverse()!!
    private val imagedCentre = targetToImage.mapPoint(Vec2(0.0, 0.0))!!

    private fun ring(radius: Double, count: Int = 180) = RingPoints(
        radius,
        (0 until count).map { i ->
            val a = 2.0 * PI * i / count
            targetToImage.mapPoint(Vec2(radius * cos(a), radius * sin(a)))!!
        }
    )

    private val rings = listOf(0.2, 0.4, 0.6, 0.8).map { ring(it) }

    /** A few percent off the truth, as a two-ring rectification leaves it. */
    private val start = Mat3.of(
        1.03, 0.02, 0.01,
        -0.015, 0.98, -0.02,
        0.0, 0.0, 1.0
    ) * truth

    @Test
    fun theRadialResidualVanishesOnExactPoints() {
        val refined = HomographyRefinement.refine(start, rings)

        for (ring in rings) {
            assertThat(HomographyRefinement.radialRms(refined, ring)).isLessThan(1e-7)
        }
    }

    @Test
    fun afterAligningImageUpTheRefinedHomographyIsTheTruth() {
        // The radial residual leaves the rotation free, so the raw result may be
        // turned about the face centre; only the oriented ones are comparable.
        val refined = Orientation.orient(HomographyRefinement.refine(start, rings), imagedCentre)!!
        val expected = Orientation.orient(truth, imagedCentre)!!

        for (ring in rings) {
            for (p in ring.points) {
                assertThat(refined.mapPoint(p)!!.distanceTo(expected.mapPoint(p)!!))
                    .isLessThan(1e-6)
            }
        }
    }

    @Test
    fun aRingOnTheWrongRadiusIsDropped() {
        // Points of radius 0.66 claimed to lie on 0.6: no homography fits them
        // together with the others, so that ring goes.
        val wrong = RingPoints(0.6, ring(0.66).points)

        val result = HomographyRefinement.refineDroppingRings(
            start, listOf(ring(0.2), ring(0.4), wrong, ring(0.8))
        )

        assertThat(result.rings.map { it.radius }).containsExactly(0.2, 0.4, 0.8).inOrder()
    }

    @Test
    fun twoRingsAreNeverDropped() {
        val wrong = RingPoints(0.4, ring(0.44).points)

        val result = HomographyRefinement.refineDroppingRings(start, listOf(ring(0.2), wrong))

        assertThat(result.rings).hasSize(2)
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*LinearSystemTest' --tests '*HomographyRefinementTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'LinearSystem'`, `'RingPoints'`.

- [ ] **Step 3: `LinearSystem.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import kotlin.math.abs

/** Small dense linear systems: enough for the eight unknowns of a homography. */
internal object LinearSystem {

    /**
     * x with a x = b, by Gaussian elimination with partial pivoting. Null
     * when [a] is singular relative to its largest entry. Neither argument is
     * modified.
     */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        require(a.size == n && a.all { it.size == n }) { "a must be $n x $n" }
        val scale = a.maxOf { row -> row.maxOf { abs(it) } }
        if (scale == 0.0) return null

        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(m[it][col]) }
            if (abs(m[pivot][col]) < 1e-14 * scale) return null
            val swap = m[col]
            m[col] = m[pivot]
            m[pivot] = swap
            for (r in col + 1 until n) {
                val factor = m[r][col] / m[col][col]
                if (factor == 0.0) continue
                for (c in col..n) {
                    m[r][c] -= factor * m[col][c]
                }
            }
        }

        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var sum = m[r][n]
            for (c in r + 1 until n) {
                sum -= m[r][c] * x[c]
            }
            x[r] = sum / m[r][r]
        }
        return x
    }
}
```

- [ ] **Step 4: `HomographyRefinement.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Image points that should lie on the ring of nominal [radius] once mapped to the target. */
class RingPoints(val radius: Double, val points: List<Vec2>)

/** The refined homography and the rings that stayed in the fit. */
class Refined(val homography: Mat3, val rings: List<RingPoints>)

/**
 * Levenberg-Marquardt on the radial residual |H p| - r over all ring points,
 * as register.py's refine_homography. The residual does not change when the
 * target turns about its centre, so the Jacobian has rank 7 of 8: the damping
 * copes with that, plain Gauss-Newton would not, and the rotation may drift
 * during the iteration. Callers fix it afterwards with Orientation.orient.
 */
object HomographyRefinement {

    private const val MAX_ITERATIONS = 60
    private const val MAX_DAMPING_STEPS = 12

    /** register.py drops a ring whose radial RMS exceeds this, while more than two remain. */
    const val DROP_RMS = 0.012

    /** Refines [start], normalised so that H[2,2] = 1. */
    fun refine(start: Mat3, rings: List<RingPoints>): Mat3 {
        var h = parameters(start)
        var r = residuals(h, rings)
        var cost = sumOfSquares(r)
        var lambda = 1e-3

        for (iteration in 0 until MAX_ITERATIONS) {
            val j = jacobian(h, rings, r)
            val a = Array(8) { p -> DoubleArray(8) { q -> dot(j[p], j[q]) } }
            val g = DoubleArray(8) { p -> dot(j[p], r) }

            var improved = false
            var lastStep: DoubleArray? = null
            for (attempt in 0 until MAX_DAMPING_STEPS) {
                val damped = Array(8) { p ->
                    DoubleArray(8) { q ->
                        if (p == q) a[p][q] + lambda * (a[p][p] + 1e-12) else a[p][q]
                    }
                }
                val step = LinearSystem.solve(damped, DoubleArray(8) { -g[it] })
                if (step == null) {
                    lambda *= 10.0
                    continue
                }
                lastStep = step
                val candidate = DoubleArray(8) { h[it] + step[it] }
                val candidateResiduals = residuals(candidate, rings)
                val candidateCost = sumOfSquares(candidateResiduals)
                if (candidateCost < cost) {
                    h = candidate
                    r = candidateResiduals
                    cost = candidateCost
                    lambda = max(lambda * 0.3, 1e-12)
                    improved = true
                    break
                }
                lambda *= 10.0
            }
            if (!improved || lastStep == null || sqrt(sumOfSquares(lastStep)) < 1e-12) break
        }
        return matrix(h)
    }

    /**
     * [refine] over all [rings]; while the worst ring's radial RMS exceeds
     * [DROP_RMS] and more than two rings remain, that ring leaves and the fit
     * starts again from [start].
     */
    fun refineDroppingRings(start: Mat3, rings: List<RingPoints>): Refined {
        var kept = rings
        while (true) {
            val h = refine(start, kept)
            val worst = kept.maxBy { radialRms(h, it) }
            if (kept.size > 2 && radialRms(h, worst) > DROP_RMS) {
                kept = kept - worst
                continue
            }
            return Refined(h, kept)
        }
    }

    fun radialRms(h: Mat3, ring: RingPoints): Double {
        var sum = 0.0
        for (p in ring.points) {
            val q = h.mapPoint(p)
            val d = if (q == null) Double.MAX_VALUE else hypot(q.x, q.y) - ring.radius
            sum += d * d
        }
        return sqrt(sum / ring.points.size)
    }

    private fun parameters(h: Mat3): DoubleArray {
        val w = h[2, 2]
        require(w != 0.0) { "a homography with H[2,2] = 0 cannot be normalised" }
        return DoubleArray(8) { h[it / 3, it % 3] / w }
    }

    private fun matrix(h: DoubleArray) = Mat3.of(
        h[0], h[1], h[2],
        h[3], h[4], h[5],
        h[6], h[7], 1.0
    )

    private fun residuals(h: DoubleArray, rings: List<RingPoints>): DoubleArray {
        val out = DoubleArray(rings.sumOf { it.points.size })
        var i = 0
        for (ring in rings) {
            for (p in ring.points) {
                val x = h[0] * p.x + h[1] * p.y + h[2]
                val y = h[3] * p.x + h[4] * p.y + h[5]
                val w = h[6] * p.x + h[7] * p.y + 1.0
                out[i++] = hypot(x / w, y / w) - ring.radius
            }
        }
        return out
    }

    /** Forward differences, one column per parameter, as register.py. */
    private fun jacobian(h: DoubleArray, rings: List<RingPoints>, r: DoubleArray): Array<DoubleArray> =
        Array(8) { p ->
            val step = 1e-7 * max(1.0, kotlin.math.abs(h[p]))
            val shifted = h.copyOf()
            shifted[p] += step
            val rp = residuals(shifted, rings)
            DoubleArray(r.size) { (rp[it] - r[it]) / step }
        }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }

    private fun sumOfSquares(v: DoubleArray) = dot(v, v)
}
```

- [ ] **Step 5: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*LinearSystemTest' --tests '*HomographyRefinementTest'`
Expected: PASS, 6 Tests.

- [ ] **Step 6: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/registration detection/src/test/java/de/dreier/mytargets/detection/registration
git commit -m "detection: refine the homography over all rings and drop a ring that does not fit"
```

---

## Task 7: Farbklassen

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/ClassImage.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/HsvPixels.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/SmallKMeans.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/ColourClassifier.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/SmallKMeansTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/ColourClassifierTest.kt`

**Interfaces:**
- Consumes: `OpenCvRule` (Task 1), `RobustConic.median` (Task 5), `Vec2`
- Produces:
  - `enum class ColourClass { YELLOW, RED, BLUE, BLACK, WHITE, OTHER }`
  - `class ClassImage(width: Int, height: Int, codes: ByteArray)` mit `operator fun get(x: Int, y: Int): ColourClass`, `isInside(x: Int, y: Int): Boolean`, `count(c: ColourClass): Int`, `mask(c: ColourClass): FloatArray`, `companion fun of(width, height, classAt: (Int, Int) -> ColourClass)`
  - `class HsvPixels(width: Int, height: Int, data: ByteArray)` mit `size`, `hue(i)`, `saturation(i)`, `value(i)`, `valuePercentile(fraction: Double): Double`, `companion fun fromBgr(bgr: Mat): HsvPixels`
  - `object SmallKMeans { fun run(xs: DoubleArray, ys: DoubleArray, start: Array<DoubleArray>, iterations: Int = 20, minMembers: Int = 50): Array<DoubleArray>; fun nearest(x: Double, y: Double, centres: Array<DoubleArray>): Int }`
  - `class DiscReference(yellowSaturation: Double, yellowValue: Double, redSaturation: Double, redValue: Double)`
  - `object ColourClassifier { fun classify(hsv: HsvPixels, reference: DiscReference? = null): ClassImage; fun discReference(hsv: HsvPixels, classes: ClassImage, centre: Vec2, radius: Double): DiscReference? }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

`SmallKMeansTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmallKMeansTest {

    /** 100 points symmetric about (0.1, 0.1) and 100 about (0.9, 0.9). */
    private val xs = DoubleArray(200) { i -> (if (i < 100) 0.1 else 0.9) + 0.002 * (i % 10 - 4.5) }
    private val ys = DoubleArray(200) { i -> (if (i < 100) 0.1 else 0.9) + 0.002 * (i / 10 % 10 - 4.5) }

    @Test
    fun eachCentreMovesToTheMeanOfItsCluster() {
        val centres = SmallKMeans.run(
            xs, ys, arrayOf(doubleArrayOf(0.3, 0.3), doubleArrayOf(0.7, 0.7))
        )

        assertThat(centres[0][0]).isWithin(1e-12).of(0.1)
        assertThat(centres[0][1]).isWithin(1e-12).of(0.1)
        assertThat(centres[1][0]).isWithin(1e-12).of(0.9)
        assertThat(centres[1][1]).isWithin(1e-12).of(0.9)
    }

    @Test
    fun aCentreWithTooFewMembersStaysWhereItStarted() {
        val centres = SmallKMeans.run(
            xs, ys,
            arrayOf(doubleArrayOf(0.3, 0.3), doubleArrayOf(0.7, 0.7), doubleArrayOf(5.0, 5.0))
        )

        assertThat(centres[2].toList()).containsExactly(5.0, 5.0).inOrder()
    }

    @Test
    fun theStartIsNotModifiedAndTheResultRepeats() {
        val start = arrayOf(doubleArrayOf(0.3, 0.3), doubleArrayOf(0.7, 0.7))

        val first = SmallKMeans.run(xs, ys, start)
        val second = SmallKMeans.run(xs, ys, start)

        assertThat(start[0].toList()).containsExactly(0.3, 0.3).inOrder()
        assertThat(first.map { it.toList() }).isEqualTo(second.map { it.toList() })
    }
}
```

`ColourClassifierTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class ColourClassifierTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val yellow = Scalar(40.0, 220.0, 245.0)
    private val red = Scalar(50.0, 50.0, 220.0)
    private val white = Scalar(235.0, 235.0, 235.0)

    private fun classify(mat: Mat, reference: DiscReference? = null): ClassImage =
        try {
            ColourClassifier.classify(HsvPixels.fromBgr(mat), reference)
        } finally {
            mat.release()
        }

    @Test
    fun hsvComesBackInDegreesAndFractions() {
        val mat = Mat(1, 2, CvType.CV_8UC3)
        try {
            mat.put(0, 0, byteArrayOf(0, 0, 255.toByte(), 255.toByte(), 0, 0))
            val hsv = HsvPixels.fromBgr(mat)

            assertThat(hsv.hue(0)).isEqualTo(0.0)
            assertThat(hsv.hue(1)).isEqualTo(240.0)
            assertThat(hsv.saturation(0)).isEqualTo(1.0)
            assertThat(hsv.value(1)).isEqualTo(1.0)
        } finally {
            mat.release()
        }
    }

    @Test
    fun classifiesSixPatches() {
        val colours = listOf(
            yellow, red,
            Scalar(220.0, 150.0, 40.0), // blue
            Scalar(30.0, 30.0, 30.0), // black
            white,
            Scalar(60.0, 160.0, 60.0) // green: none of the face colours
        )
        val mat = Mat(20, 20 * colours.size, CvType.CV_8UC3)
        colours.forEachIndexed { k, c ->
            val patch = mat.submat(0, 20, 20 * k, 20 * (k + 1))
            patch.setTo(c)
            patch.release()
        }

        val classes = classify(mat)

        assertThat((0 until colours.size).map { classes[20 * it + 10, 10] }).containsExactly(
            ColourClass.YELLOW, ColourClass.RED, ColourClass.BLUE,
            ColourClass.BLACK, ColourClass.WHITE, ColourClass.OTHER
        ).inOrder()
    }

    @Test
    fun theSecondPassTakesItsFloorsFromTheDisc() {
        // A pale yellow (saturation 0.23) passes the generous first-pass floor of
        // 0.15, but not half of a strongly saturated disc's 0.8.
        val pale = { Mat(10, 10, CvType.CV_8UC3, Scalar(180.0, 225.0, 235.0)) }

        assertThat(classify(pale())[5, 5]).isEqualTo(ColourClass.YELLOW)
        assertThat(classify(pale(), DiscReference(0.8, 1.0, 0.8, 0.9))[5, 5])
            .isNotEqualTo(ColourClass.YELLOW)
    }

    @Test
    fun theDiscReferenceIsTheMedianOfTheDiscAndOfTheRedRingAroundIt() {
        val mat = Mat(120, 120, CvType.CV_8UC3, white)
        Imgproc.circle(mat, Point(60.0, 60.0), 45, red, -1)
        Imgproc.circle(mat, Point(60.0, 60.0), 20, yellow, -1)
        try {
            val hsv = HsvPixels.fromBgr(mat)
            val classes = ColourClassifier.classify(hsv)

            val reference = ColourClassifier.discReference(hsv, classes, Vec2(60.0, 60.0), 20.0)!!

            // Saturation (max - min) / max: yellow 205/245, red 170/220. Value is
            // normalised by the 98th percentile, which the yellow disc sets.
            assertThat(reference.yellowSaturation).isWithin(0.01).of(205.0 / 245)
            assertThat(reference.yellowValue).isWithin(0.01).of(1.0)
            assertThat(reference.redSaturation).isWithin(0.01).of(170.0 / 220)
            assertThat(reference.redValue).isWithin(0.01).of(220.0 / 245)
        } finally {
            mat.release()
        }
    }

    @Test
    fun noDiscReferenceWithoutEnoughYellowAndRed() {
        val mat = Mat(60, 60, CvType.CV_8UC3, white)
        try {
            val hsv = HsvPixels.fromBgr(mat)

            assertThat(
                ColourClassifier.discReference(hsv, ColourClassifier.classify(hsv), Vec2(30.0, 30.0), 10.0)
            ).isNull()
        } finally {
            mat.release()
        }
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*SmallKMeansTest' --tests '*ColourClassifierTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'SmallKMeans'`, `'HsvPixels'`, `'ColourClassifier'`.

- [ ] **Step 3: `ClassImage.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

/** The colour classes of a WA face, and everything else. */
enum class ColourClass { YELLOW, RED, BLUE, BLACK, WHITE, OTHER }

/** One colour class per pixel, row by row. Pixel (x, y) has its centre at (x, y). */
class ClassImage(val width: Int, val height: Int, private val codes: ByteArray) {

    init {
        require(codes.size == width * height) {
            "expected ${width * height} classes, got ${codes.size}"
        }
    }

    operator fun get(x: Int, y: Int): ColourClass = CLASSES[codes[y * width + x].toInt()]

    fun isInside(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun count(c: ColourClass): Int = codes.count { it.toInt() == c.ordinal }

    /** 1 where the class is [c], 0 elsewhere, row by row, for OpenCV filters. */
    fun mask(c: ColourClass): FloatArray =
        FloatArray(codes.size) { if (codes[it].toInt() == c.ordinal) 1f else 0f }

    companion object {
        private val CLASSES = ColourClass.entries.toTypedArray()

        fun of(width: Int, height: Int, classAt: (x: Int, y: Int) -> ColourClass) =
            ClassImage(
                width, height,
                ByteArray(width * height) { classAt(it % width, it / width).ordinal.toByte() }
            )
    }
}
```

- [ ] **Step 4: `HsvPixels.kt` und `SmallKMeans.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The working image in HSV, fetched from OpenCV in one call. OpenCV's 8-bit HSV
 * halves the hue to fit a byte; [hue] gives it back in degrees, [saturation]
 * and [value] in 0..1 -- the definitions of register.py's rgb_to_hsv.
 */
class HsvPixels(val width: Int, val height: Int, private val data: ByteArray) {

    init {
        require(data.size == width * height * 3) { "expected ${width * height * 3} bytes" }
    }

    val size: Int
        get() = width * height

    fun hue(i: Int): Double = (data[3 * i].toInt() and 0xFF) * 2.0

    fun saturation(i: Int): Double = (data[3 * i + 1].toInt() and 0xFF) / 255.0

    fun value(i: Int): Double = (data[3 * i + 2].toInt() and 0xFF) / 255.0

    /** The value below which [fraction] of all pixels lie, from a 256-bin histogram. */
    fun valuePercentile(fraction: Double): Double {
        val counts = IntArray(256)
        for (i in 0 until size) {
            counts[data[3 * i + 2].toInt() and 0xFF]++
        }
        val target = fraction * size
        var cumulative = 0
        for (bin in 0 until 256) {
            cumulative += counts[bin]
            if (cumulative >= target) return bin / 255.0
        }
        return 1.0
    }

    companion object {
        fun fromBgr(bgr: Mat): HsvPixels {
            require(bgr.type() == CvType.CV_8UC3) { "expected 8-bit BGR, got type ${bgr.type()}" }
            val hsv = Mat()
            try {
                Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
                val data = ByteArray(hsv.rows() * hsv.cols() * 3)
                hsv.get(0, 0, data)
                return HsvPixels(hsv.cols(), hsv.rows(), data)
            } finally {
                hsv.release()
            }
        }
    }
}
```

```kotlin
package de.dreier.mytargets.detection.registration

/**
 * Lloyd's k-means in two dimensions from fixed starting centres, as the colour
 * split of register.py. No random start and no random sample, so a run
 * repeats exactly.
 */
object SmallKMeans {

    /** A centre moves only when more than [minMembers] points chose it. [start] is not modified. */
    fun run(
        xs: DoubleArray,
        ys: DoubleArray,
        start: Array<DoubleArray>,
        iterations: Int = 20,
        minMembers: Int = 50
    ): Array<DoubleArray> {
        require(xs.size == ys.size) { "xs and ys differ in length" }
        val centres = Array(start.size) { start[it].copyOf() }
        repeat(iterations) {
            val sumX = DoubleArray(centres.size)
            val sumY = DoubleArray(centres.size)
            val members = IntArray(centres.size)
            for (i in xs.indices) {
                val k = nearest(xs[i], ys[i], centres)
                sumX[k] += xs[i]
                sumY[k] += ys[i]
                members[k]++
            }
            for (k in centres.indices) {
                if (members[k] > minMembers) {
                    centres[k][0] = sumX[k] / members[k]
                    centres[k][1] = sumY[k] / members[k]
                }
            }
        }
        return centres
    }

    /** Index of the nearest centre; the lower index wins a tie. */
    fun nearest(x: Double, y: Double, centres: Array<DoubleArray>): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (k in centres.indices) {
            val dx = x - centres[k][0]
            val dy = y - centres[k][1]
            val d = dx * dx + dy * dy
            if (d < bestDistance) {
                bestDistance = d
                best = k
            }
        }
        return best
    }
}
```

- [ ] **Step 5: `ColourClassifier.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Median saturation and normalised value of the yellow disc and of the red ring around it. */
class DiscReference(
    val yellowSaturation: Double,
    val yellowValue: Double,
    val redSaturation: Double,
    val redValue: Double
)

/**
 * register.py's classify. Yellow and red are hue bands with a saturation
 * floor. Blue, black and white share a hue in a blue-cast photograph and are
 * split by a small k-means in saturation and value, the value normalised to
 * the image's brightness so an evening shot separates like a bright one. The
 * first pass uses generous floors; the second takes them from the disc itself,
 * so a pale print keeps its yellow and a warm white ring does not become one.
 */
object ColourClassifier {

    private const val MAX_SAMPLES = 60_000

    /** Starting centres in (saturation, normalised value): blue, black, white. */
    private val START = arrayOf(
        doubleArrayOf(0.9, 0.65),
        doubleArrayOf(0.4, 0.12),
        doubleArrayOf(0.2, 0.9)
    )
    private val SPLIT = arrayOf(ColourClass.BLUE, ColourClass.BLACK, ColourClass.WHITE)

    fun classify(hsv: HsvPixels, reference: DiscReference? = null): ClassImage {
        val valueScale = valueScale(hsv)
        val yellowSaturation = reference?.let { 0.5 * it.yellowSaturation } ?: 0.15
        val redSaturation = reference?.let { 0.5 * it.redSaturation } ?: 0.35
        val yellowValue = reference?.let { 0.5 * it.yellowValue } ?: 0.3
        val redValue = reference?.let { 0.4 * it.redValue } ?: 0.2

        val codes = ByteArray(hsv.size) { ColourClass.OTHER.ordinal.toByte() }
        val rest = IntArray(hsv.size)
        var restCount = 0
        for (i in 0 until hsv.size) {
            val h = hsv.hue(i)
            val s = hsv.saturation(i)
            val v = min(hsv.value(i) / valueScale, 1.2)
            val isYellow = h in 30.0..95.0 && s > yellowSaturation && v > yellowValue
            val isRed = !isYellow && (h >= 285.0 || h <= 30.0) && s > redSaturation && v > redValue
            when {
                isYellow -> codes[i] = ColourClass.YELLOW.ordinal.toByte()
                isRed -> codes[i] = ColourClass.RED.ordinal.toByte()
                h in 175.0..250.0 || s < 0.3 -> rest[restCount++] = i
            }
        }
        if (restCount == 0) return ClassImage(hsv.width, hsv.height, codes)

        // Every stride-th pixel instead of register.py's seeded random choice:
        // the same spread, and no random state.
        val stride = max(1, ceil(restCount / MAX_SAMPLES.toDouble()).toInt())
        val samples = (restCount + stride - 1) / stride
        val xs = DoubleArray(samples) { hsv.saturation(rest[it * stride]) }
        val ys = DoubleArray(samples) { min(hsv.value(rest[it * stride]) / valueScale, 1.2) }
        val centres = SmallKMeans.run(xs, ys, START)

        for (n in 0 until restCount) {
            val i = rest[n]
            val s = hsv.saturation(i)
            val v = min(hsv.value(i) / valueScale, 1.2)
            val k = SmallKMeans.nearest(s, v, centres)
            // Blue needs real saturation and brightness: a black or white
            // centroid that drifted would otherwise take the name.
            val drifted = k == 0 && !(s > 0.4 * centres[0][0] && v > 0.5 * centres[0][1])
            codes[i] = (if (drifted) ColourClass.OTHER else SPLIT[k]).ordinal.toByte()
        }
        return ClassImage(hsv.width, hsv.height, codes)
    }

    /**
     * Median saturation and normalised value of the yellow pixels within 0.8
     * [radius] of [centre] and of the red pixels between 1.3 and 1.8 [radius].
     * Null when either has fewer than 20 pixels.
     */
    fun discReference(
        hsv: HsvPixels,
        classes: ClassImage,
        centre: Vec2,
        radius: Double
    ): DiscReference? {
        val valueScale = valueScale(hsv)
        val yellowS = ArrayList<Double>()
        val yellowV = ArrayList<Double>()
        val redS = ArrayList<Double>()
        val redV = ArrayList<Double>()

        val reach = 1.8 * radius
        val x0 = max(0, floor(centre.x - reach).toInt())
        val x1 = min(hsv.width - 1, ceil(centre.x + reach).toInt())
        val y0 = max(0, floor(centre.y - reach).toInt())
        val y1 = min(hsv.height - 1, ceil(centre.y + reach).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val d = hypot(x - centre.x, y - centre.y)
                val i = y * hsv.width + x
                val c = classes[x, y]
                if (c == ColourClass.YELLOW && d < 0.8 * radius) {
                    yellowS += hsv.saturation(i)
                    yellowV += min(hsv.value(i) / valueScale, 1.2)
                } else if (c == ColourClass.RED && d > 1.3 * radius && d < 1.8 * radius) {
                    redS += hsv.saturation(i)
                    redV += min(hsv.value(i) / valueScale, 1.2)
                }
            }
        }
        if (yellowS.size < 20 || redS.size < 20) return null
        return DiscReference(
            RobustConic.median(yellowS), RobustConic.median(yellowV),
            RobustConic.median(redS), RobustConic.median(redV)
        )
    }

    /** Brightness normaliser: the 98th percentile of value, at least 0.2. */
    private fun valueScale(hsv: HsvPixels) = max(hsv.valuePercentile(0.98), 0.2)
}
```

- [ ] **Step 6: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*SmallKMeansTest' --tests '*ColourClassifierTest' --tests '*OpenCvImportRuleTest'`
Expected: PASS. Der Importtest ist jetzt nicht mehr leer: `HsvPixels` importiert `org.opencv.core` und `org.opencv.imgproc`.

- [ ] **Step 7: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/registration detection/src/test/java/de/dreier/mytargets/detection/registration
git commit -m "detection: colour classes of the face, as register.py classifies them"
```

---

## Task 8: Übergänge entlang von Strahlen

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/RayTransitions.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/RayTransitionsTest.kt`

**Interfaces:**
- Consumes: `ClassImage`, `ColourClass` (Task 7), `Vec2`
- Produces: `object RayTransitions { fun find(classes: ClassImage, centre: Vec2, innerRadius: (angle: Double) -> Double?, inside: ColourClass, outside: ColourClass, rays: Int, lo: Double, hi: Double, maxGapPx: Double): List<Vec2> }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class RayTransitionsTest {

    private val centre = Vec2(100.0, 100.0)

    private fun distance(x: Int, y: Int) = hypot(x - centre.x, y - centre.y)

    /** Yellow to radius 30, red to 60, white beyond. */
    private val face = ClassImage.of(200, 200) { x, y ->
        val d = distance(x, y)
        when {
            d < 30 -> ColourClass.YELLOW
            d < 60 -> ColourClass.RED
            else -> ColourClass.WHITE
        }
    }

    private fun yellowToRed(classes: ClassImage, inner: (Double) -> Double?) =
        RayTransitions.find(
            classes, centre, inner, ColourClass.YELLOW, ColourClass.RED,
            rays = 72, lo = 0.6, hi = 1.7, maxGapPx = 12.0
        )

    @Test
    fun findsTheYellowRedBoundaryOnEveryRay() {
        val points = yellowToRed(face) { 30.0 }

        assertThat(points).hasSize(72)
        for (p in points) {
            assertThat(p.distanceTo(centre)).isWithin(1.0).of(30.0)
        }
    }

    @Test
    fun aRayWithTooWideAGapCountsAsOccluded() {
        // A band of OTHER from radius 30 to 45 where x >= 100 and y >= 100:
        // 15 px between yellow and red, more than 12. The rays at 0, 5, ...,
        // 90 degrees run through it -- 19 of 72.
        val occluded = ClassImage.of(200, 200) { x, y ->
            val d = distance(x, y)
            when {
                d < 30 -> ColourClass.YELLOW
                x >= 100 && y >= 100 && d < 45 -> ColourClass.OTHER
                d < 60 -> ColourClass.RED
                else -> ColourClass.WHITE
            }
        }

        val points = yellowToRed(occluded) { 30.0 }

        assertThat(points).hasSize(72 - 19)
    }

    @Test
    fun aRayWithoutAnInnerRadiusIsSkipped() {
        val points = yellowToRed(face) { angle -> if (angle < PI) 30.0 else null }

        assertThat(points).hasSize(36)
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RayTransitionsTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'RayTransitions'`.

- [ ] **Step 3: Implementieren**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * register.py's boundary_points: along each ray from a centre, the transition
 * from one colour class to the next. It lies midway between the last sample
 * of the inner class and the first sample of the outer class after it.
 * Samples are one pixel apart and taken at the nearest pixel -- register.py
 * truncates, which shifts every ring by half a pixel.
 */
object RayTransitions {

    private const val MIN_SAMPLES = 10

    /**
     * @param innerRadius for a ray angle, the radius the window is relative to;
     *        null skips the ray
     * @param lo start of the window, as a multiple of the inner radius
     * @param hi end of the window, exclusive, as a multiple of the inner radius
     * @param maxGapPx a ray with more than this between the two classes counts
     *        as occluded and gives no point
     */
    fun find(
        classes: ClassImage,
        centre: Vec2,
        innerRadius: (angle: Double) -> Double?,
        inside: ColourClass,
        outside: ColourClass,
        rays: Int,
        lo: Double,
        hi: Double,
        maxGapPx: Double
    ): List<Vec2> {
        val points = ArrayList<Vec2>(rays)
        for (k in 0 until rays) {
            val angle = 2.0 * PI * k / rays
            val inner = innerRadius(angle) ?: continue
            val c = cos(angle)
            val s = sin(angle)
            val first = max((inner * lo).toInt(), 1)
            val last = (inner * hi).toInt()

            val sampleRadii = ArrayList<Int>()
            val sampleClasses = ArrayList<ColourClass>()
            for (r in first until last) {
                val x = (centre.x + r * c).roundToInt()
                val y = (centre.y + r * s).roundToInt()
                if (classes.isInside(x, y)) {
                    sampleRadii += r
                    sampleClasses += classes[x, y]
                }
            }
            if (sampleRadii.size < MIN_SAMPLES) continue

            val lastIn = sampleClasses.lastIndexOf(inside)
            if (lastIn < 0) continue
            var firstOut = -1
            for (j in lastIn + 1 until sampleClasses.size) {
                if (sampleClasses[j] == outside) {
                    firstOut = j
                    break
                }
            }
            if (firstOut < 0 || firstOut - lastIn > maxGapPx) continue

            val r = 0.5 * (sampleRadii[lastIn] + sampleRadii[firstOut])
            points += Vec2(centre.x + r * c, centre.y + r * s)
        }
        return points
    }
}
```

- [ ] **Step 4: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RayTransitionsTest'`
Expected: PASS, 3 Tests.

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/registration/RayTransitions.kt detection/src/test/java/de/dreier/mytargets/detection/registration/RayTransitionsTest.kt
git commit -m "detection: find colour transitions along rays"
```

---

## Task 9: Gelbe Scheiben finden und zählen

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/YellowDiscs.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/YellowDiscsTest.kt`

**Interfaces:**
- Consumes: `ClassImage`, `RayTransitions`, `RobustConic.median`, `OpenCvRule`
- Produces:
  - `class Disc(centre: Vec2, radius: Double, transitionPoints: Int)`
  - `class DiscSearch(measured: List<Disc>, counted: List<Disc>)`
  - `object YellowDiscs { fun find(classes: ClassImage, f: Double): DiscSearch; internal fun density(mask: FloatArray, width: Int, height: Int, window: Int): FloatArray; internal fun merge(discs: List<Disc>): List<Disc> }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import kotlin.math.hypot

class YellowDiscsTest {

    @get:Rule
    val openCv = OpenCvRule()

    /** Faces of yellow up to r and red up to 2r, on white. */
    private fun faces(width: Int, height: Int, vararg faces: Pair<Vec2, Double>) =
        ClassImage.of(width, height) { x, y ->
            var c = ColourClass.WHITE
            for ((centre, r) in faces) {
                val d = hypot(x - centre.x, y - centre.y)
                if (d < r) c = ColourClass.YELLOW else if (d < 2 * r && c == ColourClass.WHITE) c = ColourClass.RED
            }
            c
        }

    @Test
    fun theDensityIsTheShareOfTheWindowThatLiesInsideTheImage() {
        // A zero-padded box sum over the full window would read 36/121 = 0.30
        // in a corner; register.py divides by the part of the window in the image.
        val d = YellowDiscs.density(FloatArray(20 * 20) { 1f }, 20, 20, 5)

        assertThat(d[0]).isWithin(1e-6f).of(1f)
        assertThat(d[20 * 20 - 1]).isWithin(1e-6f).of(1f)
    }

    @Test
    fun oneDiscFoundAtSeveralScalesCountsOnce() {
        val search = YellowDiscs.find(faces(400, 400, Vec2(200.0, 200.0) to 40.0), f = 1.0)

        // Without merging, each of these would count as a disc of its own.
        assertThat(search.measured.size).isAtLeast(2)
        assertThat(search.counted).hasSize(1)
        val disc = search.counted.single()
        assertThat(disc.centre.distanceTo(Vec2(200.0, 200.0))).isLessThan(1.0)
        assertThat(disc.radius).isWithin(1.5).of(40.0)
    }

    @Test
    fun twoSeparateDiscsCountTwice() {
        val search = YellowDiscs.find(
            faces(800, 400, Vec2(200.0, 200.0) to 40.0, Vec2(600.0, 200.0) to 40.0), f = 1.0
        )

        assertThat(search.counted).hasSize(2)
    }

    @Test
    fun aDiscLessThanHalfAsLargeAsTheLargestDoesNotCount() {
        val search = YellowDiscs.find(
            faces(800, 400, Vec2(200.0, 200.0) to 40.0, Vec2(600.0, 200.0) to 15.0), f = 1.0
        )

        assertThat(search.counted).hasSize(1)
        assertThat(search.counted.single().radius).isWithin(1.5).of(40.0)
    }

    @Test
    fun yellowWithoutRedAroundItIsNoDisc() {
        val classes = ClassImage.of(300, 300) { x, y ->
            if (hypot(x - 150.0, y - 150.0) < 40) ColourClass.YELLOW else ColourClass.WHITE
        }

        assertThat(YellowDiscs.find(classes, f = 1.0).counted).isEmpty()
    }

    @Test
    fun mergingKeepsTheDiscWithMoreTransitionPoints() {
        val a = Disc(Vec2(100.0, 100.0), 40.0, 60)
        val b = Disc(Vec2(103.0, 101.0), 38.0, 70)
        val c = Disc(Vec2(300.0, 100.0), 40.0, 50)

        assertThat(YellowDiscs.merge(listOf(a, b, c))).containsExactly(b, c).inOrder()
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*YellowDiscsTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'YellowDiscs'`, `'Disc'`.

- [ ] **Step 3: Implementieren**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A yellow disc with red around it, measured on its yellow-to-red transition. */
class Disc(val centre: Vec2, val radius: Double, val transitionPoints: Int)

/** Every disc the search measured, and the ones it counts after merging and the size rule. */
class DiscSearch(val measured: List<Disc>, val counted: List<Disc>)

/**
 * register.py's find_yellow_disc, extended to count. Candidates are density
 * peaks of the yellow class at six scales where red is near; each is measured
 * on 72 rays at the yellow-to-red transition. register.py suppresses peaks
 * only within one scale and keeps the largest disc; to count, the same disc
 * found at several scales has to be merged first (registration design,
 * stage 3 of "Verfahren").
 */
object YellowDiscs {

    private val WINDOWS_AT_2000 = intArrayOf(6, 12, 24, 48, 96, 160)
    private const val PEAKS_PER_WINDOW = 12
    private const val MIN_DENSITY = 0.6f
    private const val MIN_RED_DENSITY = 0.08f
    private const val RAYS = 72
    private const val MIN_TRANSITIONS = 36
    private const val MIN_RADIUS_AT_2000 = 4.0
    private const val GAP_AT_2000 = 12.0
    private const val MIN_YELLOW_INSIDE = 0.7

    fun find(classes: ClassImage, f: Double): DiscSearch {
        val width = classes.width
        val height = classes.height
        val yellow = classes.mask(ColourClass.YELLOW)
        val red = classes.mask(ColourClass.RED)

        val measured = ArrayList<Disc>()
        for (windowAt2000 in WINDOWS_AT_2000) {
            val window = max(1, (windowAt2000 * f).roundToInt())
            val density = density(yellow, width, height, window)
            val redNear = density(red, width, height, 2 * window)
            for (i in density.indices) {
                if (redNear[i] <= MIN_RED_DENSITY) density[i] = 0f
            }
            for (peak in 0 until PEAKS_PER_WINDOW) {
                val i = argmax(density)
                if (density[i] < MIN_DENSITY) break
                val px = i % width
                val py = i / width
                measure(classes, Vec2(px.toDouble(), py.toDouble()), window.toDouble(), f)
                    ?.let { measured += it }
                // Suppress this peak's neighbourhood, as register.py does.
                for (y in max(0, py - 3 * window) until min(height, py + 3 * window)) {
                    for (x in max(0, px - 3 * window) until min(width, px + 3 * window)) {
                        density[y * width + x] = 0f
                    }
                }
            }
        }

        val merged = merge(measured)
        val largest = merged.maxOfOrNull { it.radius } ?: return DiscSearch(measured, emptyList())
        return DiscSearch(measured, merged.filter { it.radius >= 0.5 * largest })
    }

    /**
     * Share of the (2 [window] + 1)^2 box around each pixel that is 1 in
     * [mask], counting only the part of the box inside the image, as
     * register.py's box_density. The sum is OpenCV's; the division by the
     * in-image area is done here, because boxFilter can only divide by the
     * whole box.
     */
    internal fun density(mask: FloatArray, width: Int, height: Int, window: Int): FloatArray {
        val source = Mat(height, width, CvType.CV_32F)
        val sum = Mat()
        try {
            source.put(0, 0, mask)
            val side = (2 * window + 1).toDouble()
            Imgproc.boxFilter(
                source, sum, -1, Size(side, side), Point(-1.0, -1.0), false, Core.BORDER_CONSTANT
            )
            val out = FloatArray(width * height)
            sum.get(0, 0, out)
            for (y in 0 until height) {
                val rows = min(height, y + window + 1) - max(0, y - window)
                for (x in 0 until width) {
                    val cols = min(width, x + window + 1) - max(0, x - window)
                    out[y * width + x] /= (rows * cols).toFloat()
                }
            }
            return out
        } finally {
            source.release()
            sum.release()
        }
    }

    /**
     * The same disc found at several scales: two discs whose centres are
     * closer than the smaller radius. The one with more transition points stays.
     */
    internal fun merge(discs: List<Disc>): List<Disc> {
        val kept = ArrayList<Disc>()
        for (disc in discs.sortedByDescending { it.transitionPoints }) {
            if (kept.none { it.centre.distanceTo(disc.centre) < min(it.radius, disc.radius) }) {
                kept += disc
            }
        }
        return kept
    }

    /** Three rounds of: transitions on 72 rays, radius from their median distance, centre from their mean. */
    private fun measure(classes: ClassImage, start: Vec2, window: Double, f: Double): Disc? {
        var centre = start
        var radius = window
        var points: List<Vec2> = emptyList()
        var measuredOnce = false
        for (round in 0 until 3) {
            val r = radius
            points = RayTransitions.find(
                classes, centre, { r }, ColourClass.YELLOW, ColourClass.RED,
                RAYS, lo = 0.3, hi = 4.0, maxGapPx = GAP_AT_2000 * f
            )
            if (points.size < MIN_TRANSITIONS) break
            radius = RobustConic.median(points.map { it.distanceTo(centre) })
            centre = Vec2(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
            measuredOnce = true
        }
        if (!measuredOnce || radius < MIN_RADIUS_AT_2000 * f || points.size < MIN_TRANSITIONS) {
            return null
        }

        // The disc has to be yellow inside: sample a circle at 0.6 of the radius.
        var inside = 0
        var yellow = 0
        for (k in 0 until RAYS) {
            val a = 2.0 * PI * k / RAYS
            val x = (centre.x + 0.6 * radius * cos(a)).roundToInt()
            val y = (centre.y + 0.6 * radius * sin(a)).roundToInt()
            if (classes.isInside(x, y)) {
                inside++
                if (classes[x, y] == ColourClass.YELLOW) yellow++
            }
        }
        if (inside <= RAYS / 2 || yellow.toDouble() / inside <= MIN_YELLOW_INSIDE) return null
        return Disc(centre, radius, points.size)
    }

    private fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) {
            if (values[i] > values[best]) best = i
        }
        return best
    }
}
```

- [ ] **Step 4: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*YellowDiscsTest'`
Expected: PASS, 6 Tests.

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/registration/YellowDiscs.kt detection/src/test/java/de/dreier/mytargets/detection/registration/YellowDiscsTest.kt
git commit -m "detection: find, merge and count the yellow discs"
```

---

## Task 10: Der Registrar

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/WorkingScale.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/Registration.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/RingSearch.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/OpenCvMats.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/OpenCvFaceRegistrar.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/registration/SyntheticFace.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/WorkingScaleTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/RingSearchTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/OpenCvFaceRegistrarTest.kt`

**Interfaces:**
- Consumes: alles aus Task 4 bis 9; `FaceLayout`, `DetectionFailure` aus `de.dreier.mytargets.detection`; `RegistrationError` aus `:detection-corpus` (nur Test)
- Produces:
  - `class WorkingScale(originalWidth: Int, originalHeight: Int)` mit `width`, `height`, `isOriginal`, `f`, `smallFromOriginal: Mat3`, `companion const val MAX_LONG_EDGE = 1600`
  - aus dem Design: `RingTransition`, `RingTransitions.WA_FULL`, `RegistrationRequest`, `RingFit`, `RegistrationOutcome` (`Registered`, `Failed`), `FaceRegistrar`, `DebugSink` mit `NONE`
  - `class RingAttempt(transition: RingTransition, points: List<Vec2>, fit: RobustFit?, rejection: String?)` mit `accepted`
  - `object RingSearch { fun find(classes: ClassImage, disc: Disc, transitions: List<RingTransition>, f: Double): List<RingAttempt>; internal fun radiusAlong(conic: Conic, centre: Vec2, angle: Double): Double? }`
  - `object OpenCvMats { fun of(m: Mat3): Mat }`
  - `class OpenCvFaceRegistrar : FaceRegistrar`
  - Test-Hilfe `object SyntheticFace { YELLOW, RED, BLUE, BLACK, WHITE, BACKGROUND; fun view(angleDegrees: Double, pxPerRadius: Double, centre: Vec2): Mat3; fun photograph(width: Int, height: Int, targetToPhoto: Mat3): Mat; fun threeFaces(): Mat; fun blank(width: Int, height: Int): Mat; fun values(m: Mat3): List<Double> }`

- [ ] **Step 1: Die fehlschlagenden Tests für Arbeitsgröße und Ringsuche schreiben**

`WorkingScaleTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test

class WorkingScaleTest {

    @Test
    fun anInheritedPhotographShrinksTo1600() {
        val scale = WorkingScale(3120, 4160)

        assertThat(scale.width).isEqualTo(1200)
        assertThat(scale.height).isEqualTo(1600)
        assertThat(scale.f).isWithin(1e-12).of(0.8)
    }

    @Test
    fun aSmallPhotographIsNeverEnlarged() {
        val scale = WorkingScale(960, 1280)

        assertThat(scale.isOriginal).isTrue()
        assertThat(scale.f).isWithin(1e-12).of(0.64)
    }

    @Test
    fun pixelEdgesMapToPixelEdgesAsResizeMapsThem() {
        // cv::resize maps pixel centres: the outer edge of the original,
        // x = -0.5 and x = W - 0.5, lands on the outer edge of the working image.
        val scale = WorkingScale(3120, 4160)
        val s = scale.smallFromOriginal

        assertThat(s.mapPoint(Vec2(-0.5, -0.5))!!.distanceTo(Vec2(-0.5, -0.5))).isLessThan(1e-9)
        assertThat(s.mapPoint(Vec2(3119.5, 4159.5))!!.distanceTo(Vec2(1199.5, 1599.5)))
            .isLessThan(1e-9)
    }

    @Test
    fun withoutShrinkingTheMappingIsTheIdentity() {
        val s = WorkingScale(1600, 1200).smallFromOriginal

        assertThat(s.mapPoint(Vec2(10.0, 20.0))!!.distanceTo(Vec2(10.0, 20.0))).isLessThan(1e-12)
    }
}
```

`RingSearchTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class RingSearchTest {

    private val centre = Vec2(450.0, 450.0)
    private val disc = Disc(centre, 80.0, 72)

    /** Head on, 400 px per spot radius; [band] may replace part of a ring. */
    private fun face(band: (d: Double, angle: Double) -> ColourClass? = { _, _ -> null }) =
        ClassImage.of(900, 900) { x, y ->
            val d = hypot(x - centre.x, y - centre.y)
            val angle = atan2(y - centre.y, x - centre.x)
            band(d, angle) ?: when {
                d < 80 -> ColourClass.YELLOW
                d < 160 -> ColourClass.RED
                d < 240 -> ColourClass.BLUE
                d < 320 -> ColourClass.BLACK
                d < 400 -> ColourClass.WHITE
                else -> ColourClass.OTHER
            }
        }

    @Test
    fun findsAllFourRingsOfAFaceHeadOn() {
        val attempts = RingSearch.find(face(), disc, RingTransitions.WA_FULL, f = 0.8)

        assertThat(attempts.map { it.accepted }).containsExactly(true, true, true, true).inOrder()
        attempts.zip(listOf(80.0, 160.0, 240.0, 320.0)).forEach { (attempt, radius) ->
            assertThat(RingSearch.radiusAlong(attempt.fit!!.conic, centre, 0.0)!!)
                .isWithin(1.0).of(radius)
        }
    }

    @Test
    fun aRejectedRingDoesNotHideTheNextOne() {
        // Blue and black are 15 px apart except within 5 degrees of the x
        // axis, so 0.6 has too few points. 0.8 must still be found: its window
        // follows ring 0.4, the last one accepted. Following the table's
        // neighbour 0.6, as register.py does, it would end at 277 px, short
        // of 320.
        val attempts = RingSearch.find(
            face { d, angle ->
                if (d >= 240 && d < 255 && abs(angle) > Math.toRadians(5.0)) ColourClass.OTHER else null
            },
            disc, RingTransitions.WA_FULL, f = 0.8
        )

        assertThat(attempts.map { it.accepted }).containsExactly(true, true, false, true).inOrder()
        assertThat(RingSearch.radiusAlong(attempts[3].fit!!.conic, centre, 0.0)!!)
            .isWithin(1.0).of(320.0)
    }

    @Test
    fun theRadiusAlongARayOfACircleIsItsDistanceToTheEdge() {
        val circle = Conic.circle(Vec2(0.0, 0.0), 100.0)

        assertThat(RingSearch.radiusAlong(circle, Vec2(0.0, 0.0), 1.0)!!).isWithin(1e-9).of(100.0)
        assertThat(RingSearch.radiusAlong(circle, Vec2(30.0, 0.0), 0.0)!!).isWithin(1e-9).of(70.0)
    }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*WorkingScaleTest' --tests '*RingSearchTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'WorkingScale'`, `'RingSearch'`, `'RingTransitions'`.

- [ ] **Step 3: `WorkingScale.kt` und `Registration.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Mat3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The working image: the original shrunk to a long edge of at most 1600 px, never enlarged. */
class WorkingScale(val originalWidth: Int, val originalHeight: Int) {

    private val factor =
        min(1.0, MAX_LONG_EDGE.toDouble() / max(originalWidth, originalHeight))

    val width: Int = max(1, (originalWidth * factor).roundToInt())
    val height: Int = max(1, (originalHeight * factor).roundToInt())

    val isOriginal: Boolean
        get() = width == originalWidth && height == originalHeight

    /** register.py's pixel thresholds hold at a long edge of 2000 px; multiply them by this. */
    val f: Double
        get() = max(width, height) / 2000.0

    /**
     * Working pixel from original pixel. cv::resize maps pixel centres, so
     * x_small = sx * x + (0.5 sx - 0.5), not sx * x; sx and sy differ slightly
     * because the working size is rounded.
     */
    val smallFromOriginal: Mat3
        get() {
            val sx = width.toDouble() / originalWidth
            val sy = height.toDouble() / originalHeight
            return Mat3.of(
                sx, 0.0, 0.5 * sx - 0.5,
                0.0, sy, 0.5 * sy - 0.5,
                0.0, 0.0, 1.0
            )
        }

    companion object {
        const val MAX_LONG_EDGE = 1600
    }
}
```

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.Mat

/** A colour transition at a known spot-local radius. */
class RingTransition(
    val radius: Double,
    val inside: ColourClass,
    val outside: ColourClass
)

object RingTransitions {
    /**
     * Haupt-Spec, stage 2. The transition at 1.0, white to boss, is left out:
     * on a light boss it is unreliable.
     */
    val WA_FULL = listOf(
        RingTransition(0.2, ColourClass.YELLOW, ColourClass.RED),
        RingTransition(0.4, ColourClass.RED, ColourClass.BLUE),
        RingTransition(0.6, ColourClass.BLUE, ColourClass.BLACK),
        RingTransition(0.8, ColourClass.BLACK, ColourClass.WHITE)
    )
}

class RegistrationRequest(
    val layout: FaceLayout,
    val transitions: List<RingTransition>
)

/** A ring in the final fit. [conic] is in pixels of the original, like everything a registrar returns. */
class RingFit(
    val radius: Double,
    val conic: Conic,
    val points: Int,
    val radialRms: Double
)

sealed interface RegistrationOutcome {
    /**
     * [imageToTarget] maps pixels of the original to spot-local target
     * coordinates, the convention of registration.imageToTarget in the
     * sidecars; [imagedCentre] is the face centre in the same pixels.
     */
    class Registered(
        val imageToTarget: Mat3,
        val imagedCentre: Vec2,
        val rings: List<RingFit>
    ) : RegistrationOutcome

    /** [detail] is for reports and debug images, never for the user. */
    class Failed(
        val failure: DetectionFailure,
        val detail: String
    ) : RegistrationOutcome
}

interface FaceRegistrar {
    /** @param image the original as 8-bit BGR, already turned by its EXIF orientation */
    fun register(
        image: Mat,
        request: RegistrationRequest,
        debug: DebugSink = DebugSink.NONE
    ): RegistrationOutcome
}

fun interface DebugSink {
    /** [image] belongs to the registrar and may be released after the call: write it now or copy it. */
    fun image(stage: String, image: Mat)

    companion object {
        val NONE = DebugSink { _, _ -> }
    }
}
```

- [ ] **Step 4: `RingSearch.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.geometry.Vec3
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One transition's search: the points found, the fit, and why the ring is not used, if it is not. */
class RingAttempt(
    val transition: RingTransition,
    val points: List<Vec2>,
    val fit: RobustFit?,
    val rejection: String?
) {
    val accepted: Boolean
        get() = rejection == null
}

/**
 * register.py's ring loop: each transition in turn, outward from the disc,
 * the search window following the conic of the last accepted ring.
 */
object RingSearch {

    const val RAYS = 720
    const val MIN_POINTS = 40
    const val MIN_INLIERS = 60
    private const val MAX_MEDIAN_AT_2000 = 3.0
    private const val FLOOR_AT_2000 = 0.5
    private const val GAP_AT_2000 = 12.0

    fun find(
        classes: ClassImage,
        disc: Disc,
        transitions: List<RingTransition>,
        f: Double
    ): List<RingAttempt> {
        var centre = disc.centre
        var innerRadius: (Double) -> Double? = { disc.radius }
        var lo = 0.6
        var hi = 1.7
        var lastAccepted: RingAttempt? = null
        val attempts = ArrayList<RingAttempt>()

        for (transition in transitions) {
            lastAccepted?.let { previous ->
                val conic = previous.fit!!.conic
                val conicCentre = conic.centre() ?: centre
                innerRadius = { angle -> radiusAlong(conic, conicCentre, angle) }
                lo = 1.02
                // Relative to the last accepted ring, not to the table's
                // neighbour: with a ring missing, the neighbour's window would
                // not reach this one.
                hi = transition.radius / previous.transition.radius * 1.3
            }
            val points = RayTransitions.find(
                classes, centre, innerRadius, transition.inside, transition.outside,
                RAYS, lo, hi, GAP_AT_2000 * f
            )
            val attempt = assess(transition, points, f)
            attempts += attempt
            if (attempt.accepted) {
                lastAccepted = attempt
                centre = attempt.fit!!.conic.centre() ?: centre
            }
        }
        return attempts
    }

    private fun assess(transition: RingTransition, points: List<Vec2>, f: Double): RingAttempt {
        if (points.size < MIN_POINTS) {
            return RingAttempt(transition, points, null, "${points.size} points")
        }
        val fit = RobustConic.fit(points, FLOOR_AT_2000 * f)
            ?: return RingAttempt(transition, points, null, "degenerate fit")
        val maxMedian = MAX_MEDIAN_AT_2000 * f
        val rejection = when {
            fit.inliers.size < MIN_INLIERS -> "${fit.inliers.size} inliers"
            fit.medianDistance > maxMedian ->
                "median distance ${format(fit.medianDistance)} px, limit ${format(maxMedian)}"
            else -> null
        }
        return RingAttempt(transition, points, fit, rejection)
    }

    /**
     * Distance from [centre] along [angle] to [conic], the nearer positive
     * root; register.py's ellipse_radius_at.
     */
    internal fun radiusAlong(conic: Conic, centre: Vec2, angle: Double): Double? {
        val m = conic.normalized().matrix
        val c = Vec3(centre.x, centre.y, 1.0)
        val d = Vec3(cos(angle), sin(angle), 0.0)
        val a = d.dot(m * d)
        val b = 2.0 * c.dot(m * d)
        val c0 = c.dot(m * c)
        val discriminant = b * b - 4.0 * a * c0
        if (discriminant < 0.0 || a == 0.0) return null
        val root = sqrt(discriminant)
        return listOf((-b + root) / (2.0 * a), (-b - root) / (2.0 * a))
            .filter { it > 0.0 }
            .minOrNull()
    }

    private fun format(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}
```

- [ ] **Step 5: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*WorkingScaleTest' --tests '*RingSearchTest'`
Expected: PASS, 7 Tests.

- [ ] **Step 6: Die fehlschlagenden Registrar-Tests und `SyntheticFace` schreiben**

`SyntheticFace.kt` (Test-Hilfe):

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * Photographs of a WAFull face with an exactly known homography, in print-like
 * colours -- the model's colours are display colours. The face is drawn head
 * on at 1000 px per spot radius and warped into the photograph, so its edges
 * are anti-aliased much as a camera blurs them.
 */
object SyntheticFace {

    val YELLOW = Scalar(40.0, 220.0, 245.0)
    val RED = Scalar(50.0, 50.0, 220.0)
    val BLUE = Scalar(220.0, 150.0, 40.0)
    val BLACK = Scalar(30.0, 30.0, 30.0)
    val WHITE = Scalar(235.0, 235.0, 235.0)

    /** A light grey boss: next to the white ring it reads as white or other, never as black. */
    val BACKGROUND = Scalar(185.0, 190.0, 195.0)

    private const val CANONICAL = 1000.0
    private val RINGS = listOf(1.0 to WHITE, 0.8 to BLACK, 0.6 to BLUE, 0.4 to RED, 0.2 to YELLOW)

    /**
     * Target to photograph for a pinhole camera with a focal length of 1500 px,
     * turned by [angleDegrees] about the vertical axis, [pxPerRadius] across
     * the face centre vertically, and the face centre on [centre]. That is
     * K [r1 r2 t] with r1 = (cos a, 0, -sin a), r2 = (0, 1, 0), t = (0, 0, d),
     * divided by d.
     */
    fun view(angleDegrees: Double, pxPerRadius: Double, centre: Vec2): Mat3 {
        val focal = 1500.0
        val d = focal / pxPerRadius
        val a = Math.toRadians(angleDegrees)
        val c = cos(a)
        val s = sin(a)
        return Mat3.of(
            (focal * c - centre.x * s) / d, 0.0, centre.x,
            -centre.y * s / d, focal / d, centre.y,
            -s / d, 0.0, 1.0
        )
    }

    /** The face seen through [targetToPhoto] on a [width] x [height] photograph. The caller releases it. */
    fun photograph(width: Int, height: Int, targetToPhoto: Mat3): Mat {
        val side = (2.2 * CANONICAL).toInt() + 1
        val canvas = Mat(side, side, CvType.CV_8UC3, BACKGROUND)
        val middle = Point(1.1 * CANONICAL, 1.1 * CANONICAL)
        val canvasFromTarget = Mat3.of(
            CANONICAL, 0.0, 1.1 * CANONICAL,
            0.0, CANONICAL, 1.1 * CANONICAL,
            0.0, 0.0, 1.0
        )
        val map = OpenCvMats.of(canvasFromTarget * targetToPhoto.inverse()!!)
        val photo = Mat()
        try {
            for ((r, colour) in RINGS) {
                Imgproc.circle(canvas, middle, (r * CANONICAL).toInt(), colour, Imgproc.FILLED, Imgproc.LINE_AA)
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

    /** Three faces head on, 200 px per spot radius, side by side on 1600 x 600. */
    fun threeFaces(): Mat {
        val photo = Mat(600, 1600, CvType.CV_8UC3, BACKGROUND)
        for (k in 0 until 3) {
            val middle = Point(300.0 + 500.0 * k, 300.0)
            for ((r, colour) in RINGS) {
                Imgproc.circle(photo, middle, (r * 200).toInt(), colour, Imgproc.FILLED, Imgproc.LINE_AA)
            }
        }
        return photo
    }

    fun blank(width: Int, height: Int): Mat = Mat(height, width, CvType.CV_8UC3, BACKGROUND)

    /** The nine values of [m], row by row, as RegistrationError takes them. */
    fun values(m: Mat3): List<Double> = List(9) { m[it / 3, it % 3] }
}
```

`OpenCvFaceRegistrarTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.RegistrationError
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat

class OpenCvFaceRegistrarTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val registrar = OpenCvFaceRegistrar()
    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)

    private fun register(photo: Mat): RegistrationOutcome =
        try {
            registrar.register(photo, request)
        } finally {
            photo.release()
        }

    /** Largest registration error against the exact truth, in spot radii, as the corpus run measures it. */
    private fun maxError(targetToPhoto: Mat3, outcome: RegistrationOutcome, width: Int, height: Int): Double {
        assertWithMessage("outcome of the registration")
            .that(outcome).isInstanceOf(RegistrationOutcome.Registered::class.java)
        val registered = outcome as RegistrationOutcome.Registered
        return RegistrationError.between(
            SyntheticFace.values(targetToPhoto.inverse()!!),
            SyntheticFace.values(registered.imageToTarget),
            width, height
        )!!.max
    }

    private fun errorSeenAt(angleDegrees: Double): Double {
        val view = SyntheticFace.view(angleDegrees, 400.0, Vec2(800.0, 600.0))
        return maxError(view, register(SyntheticFace.photograph(1600, 1200, view)), 1600, 1200)
    }

    @Test
    fun registersAFaceHeadOn() {
        assertThat(errorSeenAt(0.0)).isAtMost(0.005)
    }

    @Test
    fun registersAFaceSeenAt30Degrees() {
        assertThat(errorSeenAt(30.0)).isAtMost(0.005)
    }

    @Test
    fun registersAFaceSeenAt45Degrees() {
        assertThat(errorSeenAt(45.0)).isAtMost(0.005)
    }

    @Test
    fun threeFacesAreAMismatch() {
        val outcome = register(SyntheticFace.threeFaces())

        assertThat(outcome).isInstanceOf(RegistrationOutcome.Failed::class.java)
        outcome as RegistrationOutcome.Failed
        assertThat(outcome.failure).isEqualTo(DetectionFailure.FACE_MISMATCH)
        assertThat(outcome.detail).contains("3 yellow discs")
    }

    @Test
    fun aPhotographWithoutAFaceIsFaceNotFound() {
        val outcome = register(SyntheticFace.blank(1600, 1200))

        assertThat(outcome).isInstanceOf(RegistrationOutcome.Failed::class.java)
        assertThat((outcome as RegistrationOutcome.Failed).failure)
            .isEqualTo(DetectionFailure.FACE_NOT_FOUND)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anImageThatIsNotEightBitBgrIsAProgrammingError() {
        register(Mat(100, 100, CvType.CV_8UC1))
    }

    @Test
    fun centreAndRingConicsComeBackInPixelsOfTheOriginal() {
        // The original is twice the working size, so anything left in working
        // pixels would be off by a factor of two.
        val view = SyntheticFace.view(0.0, 800.0, Vec2(1600.0, 1200.0))

        val outcome = register(SyntheticFace.photograph(3200, 2400, view))

        assertThat(outcome).isInstanceOf(RegistrationOutcome.Registered::class.java)
        outcome as RegistrationOutcome.Registered
        assertThat(outcome.imagedCentre.distanceTo(Vec2(1600.0, 1200.0))).isLessThan(1.0)
        val ring = outcome.rings.first { it.radius == 0.4 }
        assertThat(RobustConic.sampsonDistance(ring.conic, Vec2(1600.0 + 0.4 * 800.0, 1200.0)))
            .isLessThan(1.0)
    }
}
```

- [ ] **Step 7: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*OpenCvFaceRegistrarTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'OpenCvFaceRegistrar'`, `'OpenCvMats'`.

- [ ] **Step 8: `OpenCvMats.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Mat3
import org.opencv.core.CvType
import org.opencv.core.Mat

object OpenCvMats {
    /** A new 3x3 CV_64F matrix holding [m]; the caller releases it. */
    fun of(m: Mat3): Mat {
        val mat = Mat(3, 3, CvType.CV_64F)
        mat.put(0, 0, *DoubleArray(9) { m[it / 3, it % 3] })
        return mat
    }
}
```

- [ ] **Step 9: `OpenCvFaceRegistrar.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Orientation
import de.dreier.mytargets.detection.geometry.Rectification
import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale

/**
 * Stages 1 and 2 of the Haupt-Spec with the method of register.py: shrink to
 * the working size, classify the colours, find and count the yellow discs,
 * find the rings, rectify from two of them, refine over all, orient, and map
 * back to pixels of the original.
 */
class OpenCvFaceRegistrar : FaceRegistrar {

    override fun register(
        image: Mat,
        request: RegistrationRequest,
        debug: DebugSink
    ): RegistrationOutcome {
        require(!image.empty()) { "the image is empty" }
        require(image.type() == CvType.CV_8UC3) {
            "the image must be 8-bit BGR, got type ${image.type()}"
        }
        val scale = WorkingScale(image.cols(), image.rows())
        val small = Mat()
        try {
            if (scale.isOriginal) {
                image.copyTo(small)
            } else {
                Imgproc.resize(
                    image, small, Size(scale.width.toDouble(), scale.height.toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA
                )
            }
            return registerWorkingImage(small, scale, request)
        } finally {
            small.release()
        }
    }

    private fun registerWorkingImage(
        small: Mat,
        scale: WorkingScale,
        request: RegistrationRequest
    ): RegistrationOutcome {
        val hsv = HsvPixels.fromBgr(small)
        var classes = ColourClassifier.classify(hsv)
        var discs = YellowDiscs.find(classes, scale.f)

        // Second pass, with thresholds taken from the disc itself.
        discs.counted.maxByOrNull { it.radius }?.let { first ->
            ColourClassifier.discReference(hsv, classes, first.centre, first.radius)?.let { reference ->
                classes = ColourClassifier.classify(hsv, reference)
                discs = YellowDiscs.find(classes, scale.f)
            }
        }
        if (discs.counted.isEmpty()) {
            return failed(DetectionFailure.FACE_NOT_FOUND, "no yellow disc with red around it")
        }
        if (discs.counted.size != request.layout.faceCount) {
            return failed(
                DetectionFailure.FACE_MISMATCH,
                "${discs.counted.size} yellow discs, expected ${request.layout.faceCount}"
            )
        }

        val disc = discs.counted.maxBy { it.radius }
        val rings = RingSearch.find(classes, disc, request.transitions, scale.f)
        val accepted = rings.filter { it.accepted }
        if (accepted.size < 2) {
            return failed(
                DetectionFailure.FACE_NOT_FOUND,
                "${accepted.size} usable rings: " +
                    rings.joinToString { "${radius(it.transition.radius)} ${it.rejection ?: "ok"}" }
            )
        }

        val start = startingHomography(accepted)
        val rectified = start.result ?: return failed(
            DetectionFailure.FACE_NOT_FOUND,
            "no starting homography: " + start.rejections.joinToString("; ")
        )

        val refined = HomographyRefinement.refineDroppingRings(
            rectified.imageToTarget,
            accepted.map { RingPoints(it.transition.radius, it.fit!!.inliers) }
        )
        val oriented = Orientation.orient(refined.homography, rectified.imagedCentre)
            ?: return failed(DetectionFailure.FACE_NOT_FOUND, "the refined homography cannot be oriented")

        // Back to the original: H_orig = H_small * S; conics follow the point
        // mapping small -> original, which is S^-1.
        val s = scale.smallFromOriginal
        val originalFromSmall = requireNotNull(s.inverse()) { "the working scale is singular" }
        val imageToTarget = normalised(oriented * s)
        val imagedCentre = imageToTarget.inverse()?.mapPoint(Vec2(0.0, 0.0))
            ?: return failed(DetectionFailure.FACE_NOT_FOUND, "the face centre maps to infinity")
        val fits = refined.rings.map { ring ->
            val attempt = accepted.first { it.transition.radius == ring.radius }
            RingFit(
                radius = ring.radius,
                conic = attempt.fit!!.conic.transformedBy(originalFromSmall),
                points = ring.points.size,
                radialRms = HomographyRefinement.radialRms(oriented, ring)
            )
        }
        return RegistrationOutcome.Registered(imageToTarget, imagedCentre, fits)
    }

    private class Start(val result: Rectification.Result?, val rejections: List<String>)

    /**
     * register.py's starting pair: the two best-supported rings whose radius
     * ratio is at most 0.75; when Rectification rejects a pair, the next.
     * Every rejection is kept for the detail.
     */
    private fun startingHomography(accepted: List<RingAttempt>): Start {
        val order = accepted.sortedByDescending { it.fit!!.inliers.size }
        val rejections = ArrayList<String>()
        for (i in order.indices) {
            for (j in i + 1 until order.size) {
                val (inner, outer) = listOf(order[i], order[j]).sortedBy { it.transition.radius }
                if (inner.transition.radius / outer.transition.radius > MAX_PAIR_RATIO) continue
                val attempt = Rectification.attempt(
                    outer.fit!!.conic, outer.transition.radius,
                    inner.fit!!.conic, inner.transition.radius
                )
                when (attempt) {
                    is Rectification.Attempt.Rectified -> return Start(attempt.result, rejections)
                    is Rectification.Attempt.Rejected -> rejections +=
                        "pair ${radius(inner.transition.radius)}/${radius(outer.transition.radius)}: " +
                        attempt.describe()
                }
            }
        }
        if (rejections.isEmpty()) rejections += "no two rings with a radius ratio of at most $MAX_PAIR_RATIO"
        return Start(null, rejections)
    }

    private fun failed(failure: DetectionFailure, detail: String) =
        RegistrationOutcome.Failed(failure, detail)

    private fun normalised(m: Mat3) = m.scaled(1.0 / m[2, 2])

    private fun radius(r: Double) = String.format(Locale.ROOT, "%.1f", r)

    private companion object {
        const val MAX_PAIR_RATIO = 0.75
    }
}
```

`debug` bleibt in diesem Task ungenutzt; Task 11 schreibt die Stufenbilder.

- [ ] **Step 10: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS, alle Tests des Moduls. Scheitert einer der drei Winkel an 0,005 Radien: nicht an Schwellen drehen, sondern mit dem gemessenen Fehler melden. Die Schwellen sind die von `register.py` und werden am Korpusbericht nachgestellt, nicht an synthetischen Bildern.

- [ ] **Step 11: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/registration detection/src/test/java/de/dreier/mytargets/detection/registration
git commit -m "detection: register a WAFull face from the photograph"
```

---

## Task 11: Schwierige Fotos und die Debug-Bilder des Registrars

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/DebugImages.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/registration/OpenCvFaceRegistrar.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/RegistrationRobustnessTest.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/RegistrarDebugImagesTest.kt`

**Interfaces:**
- Consumes: `OpenCvFaceRegistrar`, `SyntheticFace`, `ClassImage`, `DiscSearch`, `RingAttempt`, `RingSearch.radiusAlong`
- Produces:
  - `object DebugImages { const val CLASSES = "1-farbklassen"; const val DISCS = "2-scheiben"; const val RINGS = "3-ringe"; fun classes(classes: ClassImage): Mat; fun discs(small: Mat, search: DiscSearch): Mat; fun rings(small: Mat, attempts: List<RingAttempt>): Mat }`
  - `OpenCvFaceRegistrar` ruft `debug.image` für die drei Stufen auf, sobald `debug !== DebugSink.NONE`

- [ ] **Step 1: Die Robustheitstests schreiben**

`RegistrationRobustnessTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.RegistrationError
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * The harder cases of the registration design, each against exact truth. A
 * failure here is reported, not tuned away: the thresholds are register.py's
 * and are adjusted against the corpus report.
 */
class RegistrationRobustnessTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val registrar = OpenCvFaceRegistrar()
    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)
    private val tilted = SyntheticFace.view(30.0, 400.0, Vec2(800.0, 600.0))

    private fun maxError(targetToPhoto: Mat3, photo: Mat): Double {
        val outcome = try {
            registrar.register(photo, request)
        } finally {
            photo.release()
        }
        assertWithMessage("outcome of the registration")
            .that(outcome).isInstanceOf(RegistrationOutcome.Registered::class.java)
        return RegistrationError.between(
            SyntheticFace.values(targetToPhoto.inverse()!!),
            SyntheticFace.values((outcome as RegistrationOutcome.Registered).imageToTarget),
            1600, 1200
        )!!.max
    }

    @Test
    fun aCroppedFaceRegistersFromItsInnerTwoTransitions() {
        // 1700 px per spot radius: ring 0.6 (1020 px) lies wholly outside the
        // 1600 x 1200 photograph, ring 0.4 (680 px) is cut at top and bottom.
        val view = SyntheticFace.view(0.0, 1700.0, Vec2(800.0, 600.0))

        assertThat(maxError(view, SyntheticFace.photograph(1600, 1200, view))).isAtMost(0.005)
    }

    @Test
    fun aDarkPhotographRegisters() {
        val photo = SyntheticFace.photograph(1600, 1200, tilted)
        Core.multiply(photo, Scalar(0.35, 0.35, 0.35), photo)

        assertThat(maxError(tilted, photo)).isAtMost(0.005)
    }

    @Test
    fun aBlueColourCastRegisters() {
        val photo = SyntheticFace.photograph(1600, 1200, tilted)
        Core.add(photo, Scalar(40.0, 0.0, -20.0), photo)

        assertThat(maxError(tilted, photo)).isAtMost(0.005)
    }

    @Test
    fun shaftsAcrossTheRingsDoNotPullTheFit() {
        val photo = SyntheticFace.photograph(1600, 1200, tilted)
        // Six dark shafts, slanted rather than radial, from near the centre to
        // the black ring -- across every transition up to 0.8.
        for (k in 0 until 6) {
            val a = 0.3 + k * 1.0
            val from = tilted.mapPoint(Vec2(0.1 * cos(a), 0.1 * sin(a)))!!
            val to = tilted.mapPoint(Vec2(0.9 * cos(a + 0.3), 0.9 * sin(a + 0.3)))!!
            Imgproc.line(photo, Point(from.x, from.y), Point(to.x, to.y), Scalar(25.0, 25.0, 25.0), 5)
        }

        assertThat(maxError(tilted, photo)).isAtMost(0.005)
    }
}
```

- [ ] **Step 2: Laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RegistrationRobustnessTest'`
Expected: PASS ohne Codeänderung — Task 10 hat das Verfahren schon vollständig; diese Tests schreiben die schwierigen Fälle des Designs fest. Schlägt einer fehl, mit dem gemessenen Fehler oder dem `detail` des Fehlschlags melden und nicht an Schwellen drehen (siehe KDoc der Testklasse).

- [ ] **Step 3: Den fehlschlagenden Debug-Bild-Test schreiben**

`RegistrarDebugImagesTest.kt`:

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat

class RegistrarDebugImagesTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)

    /** Stage name and image width of everything the registrar shows. */
    private fun stagesOf(photo: Mat): List<Pair<String, Int>> {
        val stages = ArrayList<Pair<String, Int>>()
        try {
            OpenCvFaceRegistrar().register(photo, request) { stage, image ->
                stages += stage to image.cols()
            }
        } finally {
            photo.release()
        }
        return stages
    }

    @Test
    fun showsTheFirstThreeStagesInOrderAtTheWorkingSize() {
        val view = SyntheticFace.view(30.0, 800.0, Vec2(1600.0, 1200.0))

        val stages = stagesOf(SyntheticFace.photograph(3200, 2400, view))

        assertThat(stages.map { it.first }).containsExactly(
            DebugImages.CLASSES, DebugImages.DISCS, DebugImages.RINGS
        ).inOrder()
        assertThat(stages.map { it.second }).containsExactly(1600, 1600, 1600)
    }

    @Test
    fun aFailedRegistrationStillShowsWhatItSaw() {
        val stages = stagesOf(SyntheticFace.blank(1600, 1200))

        assertThat(stages.map { it.first })
            .containsExactly(DebugImages.CLASSES, DebugImages.DISCS).inOrder()
    }
}
```

- [ ] **Step 4: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RegistrarDebugImagesTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'DebugImages'`.

- [ ] **Step 5: `DebugImages.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.PI

/**
 * The registrar's stage images (Haupt-Spec, Debug-Ansicht). Each call returns
 * a new Mat at the working size; the registrar releases it after the sink.
 */
object DebugImages {

    const val CLASSES = "1-farbklassen"
    const val DISCS = "2-scheiben"
    const val RINGS = "3-ringe"

    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val RED = Scalar(0.0, 0.0, 255.0)
    private val GREY = Scalar(128.0, 128.0, 128.0)
    private val YELLOW = Scalar(0.0, 255.0, 255.0)
    private val MAGENTA = Scalar(255.0, 0.0, 255.0)

    /** BGR per class, in the order of [ColourClass]. */
    private val PALETTE = arrayOf(
        byteArrayOf(0, 255.toByte(), 255.toByte()), // yellow
        byteArrayOf(0, 0, 255.toByte()), // red
        byteArrayOf(255.toByte(), 0, 0), // blue
        byteArrayOf(0, 0, 0), // black
        byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()), // white
        byteArrayOf(128.toByte(), 128.toByte(), 128.toByte()) // other
    )

    fun classes(classes: ClassImage): Mat {
        val bytes = ByteArray(classes.width * classes.height * 3)
        for (y in 0 until classes.height) {
            for (x in 0 until classes.width) {
                val colour = PALETTE[classes[x, y].ordinal]
                val i = 3 * (y * classes.width + x)
                bytes[i] = colour[0]
                bytes[i + 1] = colour[1]
                bytes[i + 2] = colour[2]
            }
        }
        val mat = Mat(classes.height, classes.width, CvType.CV_8UC3)
        mat.put(0, 0, bytes)
        return mat
    }

    /** Every measured disc thin and grey, the counted ones thick and green. */
    fun discs(small: Mat, search: DiscSearch): Mat {
        val mat = small.clone()
        for (disc in search.measured) {
            Imgproc.circle(mat, point(disc.centre), disc.radius.toInt(), GREY, 1)
        }
        for (disc in search.counted) {
            Imgproc.circle(mat, point(disc.centre), disc.radius.toInt(), GREEN, 3)
            Imgproc.circle(mat, point(disc.centre), 3, GREEN, Imgproc.FILLED)
        }
        return mat
    }

    /**
     * Boundary points green when the fit kept them, red when it did not; the
     * fitted conic yellow for an accepted ring, magenta for a rejected one.
     */
    fun rings(small: Mat, attempts: List<RingAttempt>): Mat {
        val mat = small.clone()
        for (attempt in attempts) {
            val kept = attempt.fit?.inliers?.toHashSet() ?: emptySet()
            for (p in attempt.points) {
                Imgproc.circle(mat, point(p), 1, if (p in kept) GREEN else RED, Imgproc.FILLED)
            }
            val conic = attempt.fit?.conic ?: continue
            val centre = conic.centre() ?: continue
            val outline = (0 until 360).mapNotNull { k ->
                val angle = 2.0 * PI * k / 360
                RingSearch.radiusAlong(conic, centre, angle)?.let { r ->
                    Point(centre.x + r * kotlin.math.cos(angle), centre.y + r * kotlin.math.sin(angle))
                }
            }
            if (outline.size < 2) continue
            val polyline = MatOfPoint(*outline.toTypedArray())
            Imgproc.polylines(mat, listOf(polyline), true, if (attempt.accepted) YELLOW else MAGENTA, 2)
            polyline.release()
        }
        return mat
    }

    private fun point(v: Vec2) = Point(v.x, v.y)
}
```

- [ ] **Step 6: Den Registrar die Bilder zeigen lassen**

In `OpenCvFaceRegistrar.kt`: `registerWorkingImage` bekommt `debug: DebugSink` als vierten Parameter, `register` reicht ihn durch. Direkt nach dem zweiten Durchgang, vor den Prüfungen auf die Scheibenzahl:

```kotlin
        show(debug, DebugImages.CLASSES) { DebugImages.classes(classes) }
        show(debug, DebugImages.DISCS) { DebugImages.discs(small, discs) }
```

direkt nach `RingSearch.find`:

```kotlin
        show(debug, DebugImages.RINGS) { DebugImages.rings(small, rings) }
```

und als private Methode:

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

Die Zeile „`debug` bleibt in diesem Task ungenutzt“ aus Task 10 gilt damit nicht mehr.

- [ ] **Step 7: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS, alle Tests des Moduls.

- [ ] **Step 8: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/registration detection/src/test/java/de/dreier/mytargets/detection/registration
git commit -m "detection: hard synthetic cases and the registrar's stage images"
```

---

## Task 12: Entzerren (Stufe 3)

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/registration/FaceWarp.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/registration/FaceWarpTest.kt`

**Interfaces:**
- Consumes: `Mat3`, `Vec2`, `OpenCvMats.of` (Task 10), `SyntheticFace` (Test)
- Produces: `object FaceWarp { const val EXTENT = 1.1; const val MAX_EDGE = 3000; fun edgeFor(imageToTarget: Mat3): Int; fun pixelOf(target: Vec2, edge: Int): Vec2; fun warp(original: Mat, imageToTarget: Mat3, edge: Int = edgeFor(imageToTarget)): Mat }`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Scalar
import kotlin.math.abs
import kotlin.math.roundToInt

class FaceWarpTest {

    @get:Rule
    val openCv = OpenCvRule()

    @Test
    fun theEdgeFollowsThePixelDensityAtTheFaceCentre() {
        val imageToTarget = SyntheticFace.view(0.0, 400.0, Vec2(800.0, 600.0)).inverse()!!

        assertThat(FaceWarp.edgeFor(imageToTarget)).isEqualTo(880)
    }

    @Test
    fun theEdgeIsCappedAt3000() {
        val imageToTarget = SyntheticFace.view(0.0, 2000.0, Vec2(800.0, 600.0)).inverse()!!

        assertThat(FaceWarp.edgeFor(imageToTarget)).isEqualTo(FaceWarp.MAX_EDGE)
    }

    @Test
    fun theCornersOfTheSquareAreTheOuterEdgesOfTheImage() {
        assertThat(FaceWarp.pixelOf(Vec2(-1.1, -1.1), 880).distanceTo(Vec2(-0.5, -0.5)))
            .isLessThan(1e-9)
        assertThat(FaceWarp.pixelOf(Vec2(1.1, 1.1), 880).distanceTo(Vec2(879.5, 879.5)))
            .isLessThan(1e-9)
    }

    @Test
    fun theRectifiedFaceHasEachColourAtItsRadius() {
        val view = SyntheticFace.view(30.0, 400.0, Vec2(800.0, 600.0))
        val photo = SyntheticFace.photograph(1600, 1200, view)
        val warped = FaceWarp.warp(photo, view.inverse()!!)
        try {
            val expected = listOf(
                0.1 to SyntheticFace.YELLOW, 0.3 to SyntheticFace.RED, 0.5 to SyntheticFace.BLUE,
                0.7 to SyntheticFace.BLACK, 0.9 to SyntheticFace.WHITE
            )
            for ((radius, colour) in expected) {
                // Sampled below the centre, where the 30 degree turn does not foreshorten.
                val p = FaceWarp.pixelOf(Vec2(0.0, radius), warped.cols())
                val bgr = warped.get(p.y.roundToInt(), p.x.roundToInt())
                assertThat(close(bgr, colour)).isTrue()
            }
        } finally {
            photo.release()
            warped.release()
        }
    }

    private fun close(bgr: DoubleArray, colour: Scalar) =
        (0..2).all { abs(bgr[it] - colour.`val`[it]) < 30.0 }
}
```

- [ ] **Step 2: Laufen lassen, Fehlschlag prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FaceWarpTest'`
Expected: FAIL beim Kompilieren — `Unresolved reference 'FaceWarp'`.

- [ ] **Step 3: Implementieren**

```kotlin
package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 3 of the Haupt-Spec: the original resampled into the square
 * [-EXTENT, EXTENT]^2 of target coordinates. The edge length follows the
 * original's pixel density at the face centre rather than a fixed value, and
 * is capped to bound the memory a 12-MP photograph can take.
 */
object FaceWarp {

    const val EXTENT = 1.1
    const val MAX_EDGE = 3000

    fun edgeFor(imageToTarget: Mat3): Int {
        val targetToImage = requireNotNull(imageToTarget.inverse()) { "the homography is singular" }
        val centre = Vec2(0.0, 0.0)
        val alongX = targetToImage.mapDirection(centre, Vec2(1.0, 0.0))?.length ?: 0.0
        val alongY = targetToImage.mapDirection(centre, Vec2(0.0, 1.0))?.length ?: 0.0
        val pxPerUnit = max(alongX, alongY)
        return min(MAX_EDGE, ceil(2.0 * EXTENT * pxPerUnit).toInt()).coerceAtLeast(1)
    }

    /** Pixel of [target] in a warped image of [edge] px; pixel centres at integers. */
    fun pixelOf(target: Vec2, edge: Int): Vec2 {
        val k = edge / (2.0 * EXTENT)
        return Vec2(k * (target.x + EXTENT) - 0.5, k * (target.y + EXTENT) - 0.5)
    }

    /** A new [edge] x [edge] image; the caller releases it. */
    fun warp(original: Mat, imageToTarget: Mat3, edge: Int = edgeFor(imageToTarget)): Mat {
        val k = edge / (2.0 * EXTENT)
        val warpedFromTarget = Mat3.of(
            k, 0.0, k * EXTENT - 0.5,
            0.0, k, k * EXTENT - 0.5,
            0.0, 0.0, 1.0
        )
        val map = OpenCvMats.of(warpedFromTarget * imageToTarget)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(
                original, warped, map, Size(edge.toDouble(), edge.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0)
            )
            return warped
        } finally {
            map.release()
        }
    }
}
```

- [ ] **Step 4: Laufen lassen, grün prüfen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*FaceWarpTest'`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Commit**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/registration/FaceWarp.kt detection/src/test/java/de/dreier/mytargets/detection/registration/FaceWarpTest.kt
git commit -m "detection: rectify the face into target coordinates (stage 3)"
```

---

## Task 13: Der Korpuslauf

**Files:**
- Create: `detection/src/test/java/de/dreier/mytargets/detection/registration/PngDebugSink.kt`
- Create: `detection/src/test/java/de/dreier/mytargets/detection/registration/RegistrationCorpusRun.kt`
- Modify: `BUILDING.md` (Abschnitt „4. Photo corpus (optional)“)

Die Bausteine dieses Laufs — Fehlerkennzahl, Größenprüfung, WA6Ring-Umrechnung, Bericht — sind in Task 2 und 3 testgetrieben entstanden. Der Lauf selbst ist das Messgerät; er hat keinen roten Schritt, sein Ergebnis ist der Bericht.

**Interfaces:**
- Consumes: `CorpusLoader`, `CorpusEntry` (`imageName`, `image`, `target`, `registration`, `outOfScope`), `ImageInfo.matchesDecodedSize`, `Homography`, `RegistrationError`, `RegistrationReport`, `RegistrationRow`, `OpenCvFaceRegistrar`, `FaceWarp`, System-Properties `detection.corpus.dir` und `detection.report.dir` (Task 1)
- Produces: `detection/build/reports/detection/registration.md` und je Foto `detection/build/reports/detection/registration/<foto>/` mit `1-farbklassen.png`, `2-scheiben.png`, `3-ringe.png`, `4-entzerrt.png`

- [ ] **Step 1: `PngDebugSink.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/** Writes each stage image as <stage>.png into [folder], synchronously as DebugSink requires. */
class PngDebugSink(private val folder: File) : DebugSink {

    override fun image(stage: String, image: Mat) {
        val file = File(folder, "$stage.png")
        check(Imgcodecs.imwrite(file.absolutePath, image)) { "cannot write $file" }
    }
}
```

- [ ] **Step 2: `RegistrationCorpusRun.kt`**

```kotlin
package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.Homography
import de.dreier.mytargets.detection.metrics.RegistrationError
import de.dreier.mytargets.detection.metrics.RegistrationReport
import de.dreier.mytargets.detection.metrics.RegistrationRow
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
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

/**
 * Registers every photograph of the corpus and writes the report and the stage
 * images (registration design, "Korpuslauf, Bericht und Debug-Bilder"). It
 * measures and does not judge: the only hard check is that the photograph
 * with three faces is a mismatch, which the Haupt-Spec requires.
 */
class RegistrationCorpusRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var reportDir: File

    private val registrar = OpenCvFaceRegistrar()
    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)

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
    fun registersTheCorpusAndWritesTheReport() {
        val entries = CorpusLoader.load(root).entries
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .associateBy { it.name }
        val imagesDir = File(reportDir, "registration")
        imagesDir.deleteRecursively()
        imagesDir.mkdirs()

        val rows = ArrayList<RegistrationRow>()
        val outcomes = HashMap<String, RegistrationOutcome>()
        for (entry in entries) {
            val file = checkNotNull(files[entry.imageName]) {
                "${entry.imageName}: no image file under $root"
            }
            val image = Imgcodecs.imread(file.absolutePath)
            try {
                check(!image.empty()) { "${entry.imageName}: cannot be decoded" }
                checkDecodedSize(entry, image)
                val folder = File(imagesDir, file.nameWithoutExtension).apply { mkdirs() }
                val outcome = registrar.register(image, request, PngDebugSink(folder))
                outcomes[entry.imageName] = outcome
                val reference = referenceFor(entry)
                if (outcome is RegistrationOutcome.Registered && reference != null) {
                    writeRectified(image, outcome, reference, File(folder, "4-entzerrt.png"))
                }
                rows += rowFor(entry, outcome, reference, image.cols(), image.rows())
            } finally {
                image.release()
            }
        }

        val report = File(reportDir, "registration.md")
        report.writeText(RegistrationReport.render(rows, "Registration against the corpus"))
        println("Registration report: ${report.absolutePath}")

        // Checked after writing, so a failing run still leaves its report.
        val multiple = outcomes[MULTIPLE_TARGETS] as? RegistrationOutcome.Failed
        assertWithMessage("$MULTIPLE_TARGETS shows three faces and must be a mismatch")
            .that(multiple?.failure).isEqualTo(DetectionFailure.FACE_MISMATCH)
    }

    /** A decoder that ignores EXIF shows up here rather than as a wild registration error. */
    private fun checkDecodedSize(entry: CorpusEntry, image: Mat) {
        val info = entry.image ?: return
        check(info.matchesDecodedSize(image.cols(), image.rows())) {
            "${entry.imageName}: decoded as ${image.cols()} x ${image.rows()}, the sidecar " +
                "records ${info.width} x ${info.height} at orientation ${info.exifOrientation}"
        }
    }

    /**
     * The reference in WAFull units. WA6Ring references are stored in WA6Ring
     * units, whose radius 1.0 lies at 0.6 of the full face. The photograph
     * with three faces gets no error value: a mismatch is its correct result.
     */
    private fun referenceFor(entry: CorpusEntry): List<Double>? {
        if (entry.imageName == MULTIPLE_TARGETS) return null
        val reference = entry.registration?.imageToTarget ?: return null
        return if (entry.target?.model == WA6RING) {
            Homography.scaleTarget(reference, WA6RING_IN_WAFULL_UNITS)
        } else {
            reference
        }
    }

    private fun rowFor(
        entry: CorpusEntry,
        outcome: RegistrationOutcome,
        reference: List<Double>?,
        width: Int,
        height: Int
    ): RegistrationRow = when (outcome) {
        is RegistrationOutcome.Registered -> RegistrationRow(
            imageName = entry.imageName,
            outOfScope = entry.outOfScope,
            outcome = RegistrationRow.REGISTERED,
            detail = null,
            ringsUsed = outcome.rings.map { it.radius },
            worstRingRms = outcome.rings.maxOfOrNull { it.radialRms },
            error = reference?.let {
                RegistrationError.between(it, values(outcome.imageToTarget), width, height)
            }
        )
        is RegistrationOutcome.Failed -> RegistrationRow(
            entry.imageName, entry.outOfScope, outcome.failure.name, outcome.detail,
            emptyList(), null, null
        )
    }

    /**
     * The fourth stage image: the rectified face with the nominal rings in
     * green and, in magenta, the rings where the reference puts them. Where
     * the two lie on each other, the registration agrees with the reference;
     * the gap between them is the error.
     */
    private fun writeRectified(
        image: Mat,
        outcome: RegistrationOutcome.Registered,
        reference: List<Double>,
        file: File
    ) {
        val edge = FaceWarp.edgeFor(outcome.imageToTarget)
        val warped = FaceWarp.warp(image, outcome.imageToTarget, edge)
        try {
            val k = edge / (2.0 * FaceWarp.EXTENT)
            val centre = FaceWarp.pixelOf(Vec2(0.0, 0.0), edge)
            val toImage = checkNotNull(Homography.inverse(reference.toDoubleArray())) {
                "the reference of ${file.parentFile.name} is singular"
            }
            for (r in RegistrationError.RING_RADII) {
                Imgproc.circle(warped, Point(centre.x, centre.y), (r * k).roundToInt(), GREEN, 2)
                val outline = (0 until 360).mapNotNull { step ->
                    val a = 2.0 * PI * step / 360
                    val q = Homography.map(toImage, r * cos(a), r * sin(a)) ?: return@mapNotNull null
                    val p = outcome.imageToTarget.mapPoint(Vec2(q[0], q[1])) ?: return@mapNotNull null
                    val px = FaceWarp.pixelOf(p, edge)
                    Point(px.x, px.y)
                }
                if (outline.size < 2) continue
                val polyline = MatOfPoint(*outline.toTypedArray())
                Imgproc.polylines(warped, listOf(polyline), true, MAGENTA, 1)
                polyline.release()
            }
            check(Imgcodecs.imwrite(file.absolutePath, warped)) { "cannot write $file" }
        } finally {
            warped.release()
        }
    }

    private fun values(m: Mat3): List<Double> = List(9) { m[it / 3, it % 3] }

    private companion object {
        const val MULTIPLE_TARGETS = "a6_x99999_multiple_targets.jpg"
        const val WA6RING = "WA6Ring"
        const val WA6RING_IN_WAFULL_UNITS = 0.6
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
        val GREEN = Scalar(0.0, 200.0, 0.0)
        val MAGENTA = Scalar(255.0, 0.0, 255.0)
    }
}
```

- [ ] **Step 3: Den Lauf gegen den Korpus starten**

Voraussetzung: `DETECTION_CORPUS_DIR` steht in `gradle-local.properties`.

Run: `./gradlew :detection:testDevDebugUnitTest --tests '*RegistrationCorpusRun'`
Expected: PASS. Danach existiert `detection/build/reports/detection/registration.md`; sein Kopf lautet „20 photographs, 16 in scope: …“, und im Abschnitt „Out of scope“ steht `a6_x99999_multiple_targets.jpg` mit `FACE_MISMATCH`. Je Foto liegt ein Ordner mit drei Stufenbildern, bei registrierten Fotos mit Referenz ein viertes.

- [ ] **Step 4: Den Bericht lesen, nicht bewerten**

Zusammenfassung und die drei schlechtesten Fotos notieren, dazu jedes gescheiterte Foto mit seinem `detail`. Für jedes gescheiterte oder auffällige Foto die Stufenbilder öffnen und den ersten Schritt benennen, an dem es falsch wird (Farbklassen, Scheiben oder Ringe). Die Zahlen gehören in die PR-Beschreibung, nicht ins Repo; Schranken setzt dieser Plan keine.

- [ ] **Step 5: `BUILDING.md`**

Im Abschnitt „4. Photo corpus (optional)“ den letzten Absatz (Zeilen 98–101) ersetzen. Er ist zugleich veraltet: Die geerbten Fotos tragen ihre Wahrheit seit 2026-09-10 nicht mehr nur im Dateinamen.

~~~markdown
The corpus is read by `:detection-corpus`. Without the property its tests skip
themselves, so a fresh clone builds green. The 16 photographs under
`inherited-249/` come from the 2017 prototype branch; since 2026-09-10 each has
a sidecar like the others, and the scores in their file names remain as a
cross-check.

The registration run measures how well `:detection` finds the face in each
photograph of the corpus:

```
./gradlew :detection:testDevDebugUnitTest --tests '*RegistrationCorpusRun'
```

It writes `detection/build/reports/detection/registration.md`, and beside it
one folder of stage images per photograph. The run fails only when the
photograph with three faces is not reported as a face mismatch; everything
else is measured, not judged.
~~~

- [ ] **Step 6: Alle Tests beider Module**

Run: `./gradlew :detection:testDevDebugUnitTest :detection-corpus:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add detection/src/test/java/de/dreier/mytargets/detection/registration/PngDebugSink.kt detection/src/test/java/de/dreier/mytargets/detection/registration/RegistrationCorpusRun.kt BUILDING.md
git commit -m "detection: register the corpus and report the error against its references"
```

---

## Was dieser Plan nicht liefert

- **Keine Pfeile.** Farbabgleich über die Fläche, Residuum, Schäfte und Einschusspunkte sind Plan 3b; er arbeitet auf `FaceWarp`.
- **Nichts in `:app`.** Das OpenCV-AAR 4.14.0, die Umwandlung `Bitmap` → `Mat`, der Debug-Bildschirm und der kleine Gerätetest für den Versionsabstand 4.9/4.14 gehören zum Integrationsplan.
- **Keine Formel für `faceConfidence`.** Der Bericht stellt radialen RMS und echten Fehler nebeneinander; die Güte folgt in 3b.
- **Keine Schranken.** Erst messen, dann festschreiben.
- **Keine 3-Spot- und keine WA6Ring-Registrierung.** Die WA6Ring-Fotos laufen mit der `WAFull`-Tabelle mit und werden außerhalb des Umfangs berichtet.
- **Keine Laufzeitmessung auf dem Gerät**, und keine verkanteten Fotos: Referenz und Pipeline setzen oben im Bild mit oben auf der Scheibe gleich.

## Self-Review

**Abdeckung des Designs.**

| Design | Task |
|---|---|
| Module, OpenCV compileOnly 4.9, Tests mit openpnp, `:detection-corpus` im Test, Korpuspfad nur in `testDevDebugUnitTest` | 1 |
| Nur `core` und `imgproc` im Hauptcode | 1 (Test), eingehalten ab 7 |
| Pixel einmal holen, `Mat`s freigeben | 7, 9, 10, 11, 12 |
| Schnittstelle `FaceRegistrar`, `RegistrationOutcome`, `RingFit`, `DebugSink` | 10 |
| Umrechnung klein → Original für Homographie und Kegelschnitte | 10 (`WorkingScale`, Registrar) |
| `Rectification` nennt das verletzte Tor, Hilfen geteilt | 4 |
| Verfahren 1 Vorverarbeitung, `f` | 10 |
| Verfahren 2 Farbklassen, zweiter Durchgang | 7, 10 |
| Verfahren 3 Scheiben mit Zusammenfassen und Zählregel | 9 |
| Verfahren 4 Randpunkte, Lücke 12 px · `f` | 8, 10 (`RingSearch`) |
| Verfahren 5 robuster Fit mit Boden, 40/60 Punkte, 3 px · `f` | 5, 10 |
| Verfahren 6 Startpaar, LM, Ring verwerfen, Orientierung | 6, 10 |
| Verfahren 7 Entzerren mit Deckel 3000 px | 12 |
| Messung, sichtbare Punkte, Größenprüfung, WA6Ring-Umrechnung | 2, 13 |
| Bericht, Debug-Bilder 1–4 | 3, 11, 13 |
| Fehlerfälle und `detail` | 10 |
| Tests: Logik ohne Bild | 2, 4, 5, 6, 8 |
| Tests: synthetische Bilder, 0,005 Radien | 10, 11 |
| Tests: Korpuslauf, harte Prüfung `FACE_MISMATCH` | 13 |

**Risiko.** Die synthetischen Fälle in Task 10 und 11 sind gegen die Schwellen von `register.py` geschrieben, ohne dass sie vorher gelaufen wären. Schlägt einer fehl, meldet der Ausführende den gemessenen Fehler statt Schwellen zu verschieben; die Schwellen werden am Korpusbericht nachgestellt.

