# Korpusmodul auf das echte Format ziehen — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:detection-corpus` liest den tatsächlich vorhandenen Korpus — nicht das Format, das Plan 2 erfunden hat — und misst darüber alle vier Kennzahlen einschließlich des Positionsfehlers.

**Architecture:** Das Modul bleibt reines JVM-Kotlin ohne Android und ohne Bildverarbeitung. Geändert wird das Datenmodell: Punktzahlen sind Zonenindizes statt gedruckter Zeichenketten, die Positionstoleranz steht je Treffer statt global, eine leere Trefferliste ist ein gültiger Registrierungseintrag statt eines Fehlers, und jede Kennzahl bekommt ihren eigenen Nenner.

**Tech Stack:** Kotlin (JVM), gson, JUnit 4, Truth. Keine neue Abhängigkeit.

**Spec:** `docs/design/2026-09-09-arrow-detection-design.md`, sowie — für alles, was den Korpus selbst betrifft — `<DETECTION_CORPUS_DIR>/README.md`, das der Korpus mitbringt und das für sein Format die bindende Autorität ist.

## Warum es diesen Plan gibt

Plan 2 hat ein Sidecar-Format entworfen, ohne nachzusehen, ob eines existiert. Es existierte. Der Korpus unter `DETECTION_CORPUS_DIR` bringt ein eigenes Repo, ein README, Annotationswerkzeuge und vier von Hand geprüfte WA-Vollauflagen mit — und das Modul aus Plan 2 ignoriert alle vier. Gemessen: 16 Einträge gelesen, alle aus `inherited-249`, vier Dateien in `ignored`.

Der Unterschied ist nicht kosmetisch:

| Plan 2 nahm an | Der Korpus tut |
|---|---|
| Sidecar heißt `<bild>.jpg.json` | `<bild>.json` |
| Flaches `{targetModel, tags, shots}` | Verschachtelt: `image`, `camera`, `capture`, `target`, `end`, `shots`, `annotation`, `registration` |
| Punktzahl als gedruckter Text | `scoringRing` als Zonenindex, dazu `points` |
| Eine globale Distanzschwelle | `positionTolerance` je Treffer |
| Leere Trefferliste ist ein Fehler | Leere Trefferliste heißt „nicht annotiert", zählt nur für Registrierung |
| Jeder nicht zugeordnete Fund ist falsch-positiv | Nur wenn `annotation.unresolvedArrows` null ist |
| `tags`-Feld | Erschwernis steckt in `capture.lighting` und `capture.angle` |

Der Zonenindex löst nebenbei Plan 2s zentrales Argument auf. Gedruckte Zeichenketten existierten, um modellabhängige Indizes zu vermeiden — aber jedes Sidecar trägt `target.model`, also vergleichen sich Indizes direkt. Der Umweg war die Lösung eines Problems, das es nicht gibt.

## Das echte Schema

Aus allen vier vorhandenen Sidecars erhoben, das Schema ist über alle identisch:

```json
{
  "image":   { "file": "...", "originalName": "...", "width": 4000, "height": 2252, "exifOrientation": 6 },
  "camera":  { "model": "Galaxy S25", "focalLengthMm": 5.4, "focalLength35mm": 23 },
  "capture": { "takenAt": "...", "lighting": "sonne", "angle": "leicht-schraeg",
               "angleDegrees": null, "cameraSide": "links-unten" },
  "target":  { "model": "WAFull", "diameterCm": 80, "faceCount": 1 },
  "end":     { "shotsPerEnd": 6 },
  "shots": [
    { "faceIndex": 0, "x": -0.195, "y": -0.201, "scoringRing": 3, "points": 8,
      "radius": 0.28, "positionTolerance": 0.015,
      "nearRingBoundary": false, "uncertain": false, "note": "..." }
  ],
  "notes": "...",
  "annotation": { "method": "...", "date": "...", "coordinateSystem": "...",
                  "scoringRing": "...", "unresolvedArrows": 2, "note": "..." },
  "registration": {
    "imageToTarget": [[..3..],[..3..],[..3..]],
    "imageToTargetNote": "...",
    "imagedCentre": [1145.77, 1775.66],
    "rings": { "0.2": { "points": 639, "radialRms": 0.0036, "meanRadius": 0.1966 } }
  }
}
```

`shots[].nearRingBoundary`, `uncertain` und `note` sind optional; alle übrigen Trefferfelder sind vorhanden.

**Nachgerechnet, nicht angenommen:** `shots.size + unresolvedArrows` ergibt bei drei der vier Dateien genau `shotsPerEnd`, bei `2026-08-15_bedeckt_frontal_02` aber 5 gegen 6. Entweder wurden dort nur fünf Pfeile geschossen, oder ein sechster ist weder gelistet noch als unaufgelöst vermerkt. Der Plan darf die Gleichheit deshalb **nicht** als Invariante prüfen; belastbar ist nur `gelistet + unaufgelöst ≤ shotsPerEnd`. Task 8 prüft die schwächere Form und meldet die Abweichung, statt an ihr zu scheitern.

## Global Constraints

- **Sprache:** Code, Bezeichner und Kommentare Englisch.
- **Lizenzkopf:** Jede neue Kotlin-Datei bekommt den GPLv2-Kopf aus `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/Score.kt`.
- **Kein Android, keine Bildverarbeitung.** Das Modul liest Namen und JSON.
- **Keine neue Abhängigkeit.** Vorhanden sind `libs.gson`, `libs.junit4`, `libs.truth`, `libs.kotlin.stdlib.jdk7`.
- **Gleitkomma:** `Double`.
- **Unbekannte JSON-Felder werden ignoriert, fehlende Pflichtfelder sind ein Fehler.** Der Korpus wächst; ein neues Feld darf einen Lauf nicht abbrechen, ein fehlendes Pflichtfeld muss ihn abbrechen.
- **Jede Fehlermeldung nennt die Datei.** Ein Korpus hat hundert Fotos; „malformed JSON" allein ist nicht handhabbar.
- **Testbefehl:** `./gradlew :detection-corpus:test`
- **Korpustests überspringen sich**, wenn `DETECTION_CORPUS_DIR` fehlt.

---

## File Structure

Alle Pfade unterhalb von `detection-corpus/src/`.

| Datei | Änderung | Verantwortung |
|---|---|---|
| `main/kotlin/.../corpus/Score.kt` | **löschen** | Gedruckte Punktzahl, durch den Zonenindex ersetzt |
| `main/kotlin/.../corpus/CorpusEntry.kt` | ersetzen | Wahrheitsmodell: Eintrag, Treffer, Position, Metadaten |
| `main/kotlin/.../corpus/CaptureMetadata.kt` | **neu** | `ImageInfo`, `CameraInfo`, `CaptureInfo`, `TargetInfo`, `Registration` |
| `main/kotlin/.../corpus/SidecarTruth.kt` | ersetzen | Das verschachtelte Schema lesen |
| `main/kotlin/.../corpus/FilenameTruth.kt` | anpassen | Geerbtes `a6_...`-Schema auf das neue Trefferm odell |
| `main/kotlin/.../corpus/CorpusLoader.kt` | anpassen | `<basis>.json`, leere Trefferliste, verwaiste Sidecars |
| `main/kotlin/.../metrics/ShotMatching.kt` | anpassen | Toleranz je Treffer, Vergleich über Index oder gedruckten Wert |
| `main/kotlin/.../metrics/Metrics.kt` | anpassen | Eigener Nenner je Kennzahl, `unresolvedArrows` |
| `main/kotlin/.../metrics/MetricsReport.kt` | anpassen | Nenner je Kennzahl ausweisen |
| `test/kotlin/...` | mitziehen | je Datei ihre Tests |

`CaptureMetadata.kt` ist neu, weil `CorpusEntry.kt` sonst sieben Datenklassen trüge und niemand mehr fände, was er sucht. Die Trennung folgt der Frage: Wahrheit über die Treffer gegen Umstände der Aufnahme.

---

## Task 1: Das Wahrheitsmodell

**Files:**
- Delete: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/Score.kt`
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CaptureMetadata.kt`
- Replace: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CorpusEntry.kt`
- Replace: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusEntryTest.kt`

Dieser Task bricht alles andere im Modul. Das ist beabsichtigt: Der Compiler zeigt danach jede Stelle, die nachgezogen werden muss, und die Tasks 2 bis 7 ziehen sie der Reihe nach nach. **Am Ende dieses Tasks kompiliert das Modul nicht** — committe trotzdem, mit dem Hinweis im Commit-Text.

**Interfaces:**
- Consumes: nichts
- Produces:
  - `PrintedScore(val text: String)` mit `isMiss`, companion `MISS`, `X`, `of(String)`, `parseFilenameChar(Char): PrintedScore?`
  - `SpotPosition(faceIndex: Int, x: Double, y: Double)` mit `distanceTo(SpotPosition): Double?`
  - `TruthShot(scoringRing: Int?, printedScore: PrintedScore?, position: SpotPosition?, positionTolerance: Double?, nearRingBoundary: Boolean, uncertain: Boolean)`
  - `ImageInfo(fileName: String, width: Int, height: Int, exifOrientation: Int?)`
  - `CameraInfo(model: String?, focalLength35mm: Double?)`
  - `CaptureInfo(lighting: String?, angle: String?, angleDegrees: Double?)`
  - `TargetInfo(model: String, diameterCm: Double?, faceCount: Int)`
  - `Registration(imageToTarget: List<Double>, imagedCentre: SpotPosition?)` — neun Werte zeilenweise
  - `CorpusEntry(imageName, image, camera, capture, target, end, shots, unresolvedArrows, registration)` mit `expectedShots`, `hasPositions`, `isAnnotated`, `tags`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Ersetze `CorpusEntryTest.kt` vollständig durch:

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CorpusEntryTest {

    private fun entry(
        shots: List<TruthShot>,
        unresolved: Int = 0,
        shotsPerEnd: Int = 6,
        lighting: String? = "sonne",
        angle: String? = "leicht-schraeg"
    ) = CorpusEntry(
        imageName = "t.jpg",
        image = ImageInfo("t.jpg", 4000, 2252, 6),
        camera = CameraInfo("Galaxy S25", 23.0),
        capture = CaptureInfo(lighting, angle, null),
        target = TargetInfo("WAFull", 80.0, 1),
        shotsPerEnd = shotsPerEnd,
        shots = shots,
        unresolvedArrows = unresolved,
        registration = null
    )

    private fun ringShot(ring: Int) = TruthShot(scoringRing = ring)

    private fun placedShot(ring: Int, x: Double, y: Double, tolerance: Double? = null) =
        TruthShot(
            scoringRing = ring,
            position = SpotPosition(0, x, y),
            positionTolerance = tolerance
        )

    @Test
    fun printedScoreCharactersMapToPrintedValues() {
        assertThat(PrintedScore.parseFilenameChar('x')).isEqualTo(PrintedScore.X)
        assertThat(PrintedScore.parseFilenameChar('X')).isEqualTo(PrintedScore.X)
        assertThat(PrintedScore.parseFilenameChar('9')).isEqualTo(PrintedScore.of("9"))
        assertThat(PrintedScore.parseFilenameChar('m')).isEqualTo(PrintedScore.MISS)
        assertThat(PrintedScore.parseFilenameChar('0')).isNull()
        assertThat(PrintedScore.parseFilenameChar('!')).isNull()
    }

    @Test
    fun printedScoreNormalisesCaseAndWhitespace() {
        assertThat(PrintedScore.of("x")).isEqualTo(PrintedScore.X)
        assertThat(PrintedScore.of(" 9 ")).isEqualTo(PrintedScore.of("9"))
        assertThat(PrintedScore.MISS.isMiss).isTrue()
        assertThat(PrintedScore.X.isMiss).isFalse()
    }

    @Test
    fun aShotNeedsAtLeastOneWayToBeScored() {
        try {
            TruthShot()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // A shot with neither a zone index nor a printed value cannot be
            // compared with anything a detector produces.
        }
    }

    @Test
    fun distanceIsOnlyDefinedWithinOneSpot() {
        val a = SpotPosition(0, 0.0, 0.0)
        assertThat(a.distanceTo(SpotPosition(0, 3.0, 4.0))!!).isWithin(1e-9).of(5.0)
        assertThat(a.distanceTo(SpotPosition(1, 3.0, 4.0))).isNull()
    }

    @Test
    fun expectedShotsIsTheListedTruthNotTheEndSize() {
        // The corpus README: the detection rate and the position error refer to
        // the LISTED hits. Four listed of six shot means four expected.
        val e = entry(listOf(ringShot(0), ringShot(2), ringShot(3), ringShot(3)), unresolved = 2)
        assertThat(e.expectedShots).isEqualTo(4)
        assertThat(e.shotsPerEnd).isEqualTo(6)
        assertThat(e.unresolvedArrows).isEqualTo(2)
    }

    @Test
    fun anEmptyShotListIsARegistrationOnlyEntryRatherThanAnError() {
        val e = entry(emptyList())
        assertThat(e.isAnnotated).isFalse()
        assertThat(e.expectedShots).isEqualTo(0)
        assertThat(e.hasPositions).isFalse()
    }

    @Test
    fun anEntryKnowsWhetherEveryShotCarriesAPosition() {
        assertThat(entry(listOf(placedShot(0, 0.1, 0.1), placedShot(2, 0.3, 0.0))).hasPositions)
            .isTrue()
        assertThat(entry(listOf(placedShot(0, 0.1, 0.1), ringShot(2))).hasPositions)
            .isFalse()
        assertThat(entry(listOf(ringShot(0))).hasPositions).isFalse()
    }

    @Test
    fun tagsComeFromTheCaptureConditions() {
        assertThat(entry(listOf(ringShot(0))).tags)
            .containsExactly("sonne", "leicht-schraeg")
        assertThat(entry(listOf(ringShot(0)), lighting = null, angle = null).tags).isEmpty()
    }

    @Test
    fun aRegistrationHoldsNineValuesRowByRow() {
        val r = Registration(
            imageToTarget = listOf(
                0.00089867, -5e-08, -1.02884717,
                -3.186e-05, 0.00086714, -1.50279171,
                0.00011846, -7.073e-05, 1.0
            ),
            imagedCentre = SpotPosition(0, 1145.77, 1775.66)
        )
        assertThat(r.imageToTarget).hasSize(9)
        assertThat(r.imageToTarget[8]).isWithin(1e-12).of(1.0)
    }

    @Test
    fun aRegistrationRejectsAMatrixOfTheWrongSize() {
        try {
            Registration(imageToTarget = listOf(1.0, 2.0, 3.0), imagedCentre = null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // A homography has nine values.
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test`
Expected: FAIL — `Unresolved reference: PrintedScore`, und weitere Fehler in den noch nicht angepassten Dateien.

- [ ] **Step 3: `Score.kt` löschen und `PrintedScore` in `CorpusEntry.kt` neu anlegen**

```bash
git rm detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/Score.kt
```

- [ ] **Step 4: `CaptureMetadata.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

/** The photograph itself, as the sidecar records it. */
data class ImageInfo(
    val fileName: String,
    val width: Int,
    val height: Int,
    val exifOrientation: Int?
) {
    init {
        require(width > 0 && height > 0) { "an image has a positive size" }
    }

    /** The long edge, which is what a focal length in 35 mm terms scales against. */
    val longEdge: Int
        get() = maxOf(width, height)
}

/**
 * What is known about the camera. [focalLength35mm] is the value the detection
 * pipeline needs to build its intrinsics; without it a fallback is used.
 */
data class CameraInfo(val model: String?, val focalLength35mm: Double?)

/** The conditions the photograph was taken under, which is how the metrics are
 *  broken down by difficulty. */
data class CaptureInfo(
    val lighting: String?,
    val angle: String?,
    val angleDegrees: Double?
)

/** Which target face is in the photograph. [model] names a class in
 *  `de.dreier.mytargets.shared.targets.models`, for example "WAFull". */
data class TargetInfo(val model: String, val diameterCm: Double?, val faceCount: Int) {
    init {
        require(model.isNotBlank()) { "a target needs a model name" }
        require(faceCount > 0) { "a face has at least one spot" }
    }
}

/**
 * The registration the annotator arrived at, kept so the pipeline's own
 * registration can be checked against it independently of whether it finds any
 * arrows.
 *
 * [imageToTarget] holds the nine values of a 3x3 homography row by row, mapping
 * pixels of the EXIF-rotated original into target coordinates.
 */
data class Registration(
    val imageToTarget: List<Double>,
    val imagedCentre: SpotPosition?
) {
    init {
        require(imageToTarget.size == 9) {
            "a homography has nine values, got ${imageToTarget.size}"
        }
    }
}
```

- [ ] **Step 5: `CorpusEntry.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import kotlin.math.hypot

/**
 * A score as it is printed on the face: "X", "10" down to "1", "M" for a miss.
 *
 * Only the inherited photographs use this. Their file names spell printed
 * values and record no target model, so their scores cannot be turned into zone
 * indices. Everything annotated properly carries [TruthShot.scoringRing]
 * instead, which is what a detector produces directly.
 */
@JvmInline
value class PrintedScore(val text: String) {

    val isMiss: Boolean
        get() = text == MISS_TEXT

    override fun toString(): String = text

    companion object {
        private const val MISS_TEXT = "M"

        val MISS = PrintedScore(MISS_TEXT)
        val X = PrintedScore("X")

        /** Normalises case and whitespace, so a hand written "x" equals "X". */
        fun of(text: String): PrintedScore {
            val normalised = text.trim().uppercase()
            require(normalised.isNotEmpty()) { "a score needs a value" }
            return PrintedScore(normalised)
        }

        /**
         * One character of the inherited naming scheme, where `a6_x99765.jpg`
         * spells six scores. The scheme has no character for a plain ten as
         * distinct from an X, and none for a zero; a miss is written `m`.
         */
        fun parseFilenameChar(c: Char): PrintedScore? = when {
            c == 'x' || c == 'X' -> X
            c == 'm' || c == 'M' -> MISS
            c in '1'..'9' -> PrintedScore(c.toString())
            else -> null
        }
    }
}

/**
 * A hit in spot local coordinates: the spot's centre is the origin, its
 * outermost ring has radius one, and y grows downwards — the same system
 * `Shot.x` and `Shot.y` use in the app.
 */
data class SpotPosition(val faceIndex: Int, val x: Double, val y: Double) {

    /** Null across different spots, where a distance would be meaningless. */
    fun distanceTo(other: SpotPosition): Double? =
        if (faceIndex != other.faceIndex) null else hypot(x - other.x, y - other.y)
}

/**
 * One arrow of the ground truth.
 *
 * [scoringRing] is the zone index from `TargetModelBase.zones`, which is what a
 * detector produces from `getZoneFromPoint`. [printedScore] is the fallback for
 * the inherited photographs, whose file names give printed values and no target
 * model. A shot needs at least one of the two.
 *
 * [positionTolerance] is how far a detection may sit from this hit and still be
 * the same arrow, in spot radii. It belongs to the shot rather than to the run:
 * the annotator records a larger value where shafts overlap and the entry point
 * had to be estimated.
 */
data class TruthShot(
    val scoringRing: Int? = null,
    val printedScore: PrintedScore? = null,
    val position: SpotPosition? = null,
    val positionTolerance: Double? = null,
    val nearRingBoundary: Boolean = false,
    val uncertain: Boolean = false
) {
    init {
        require(scoringRing != null || printedScore != null) {
            "a shot needs a scoring ring or a printed score"
        }
        require(scoringRing == null || scoringRing >= 0) {
            "a zone index is not negative, got $scoringRing"
        }
        require(positionTolerance == null || positionTolerance > 0.0) {
            "a position tolerance is positive, got $positionTolerance"
        }
    }
}

/**
 * One photograph and everything known to be true about it.
 *
 * @param shots the hits whose entry point the annotator could determine. This
 *        may be fewer than [shotsPerEnd]; the remainder is [unresolvedArrows].
 *        An EMPTY list is not an error — it marks a photograph that is
 *        registered but not annotated, which still serves the registration
 *        tests.
 * @param unresolvedArrows arrows visible in the photograph whose entry point
 *        could not be determined. A detection matching no listed hit only
 *        counts as a false positive when this is zero.
 */
data class CorpusEntry(
    val imageName: String,
    val image: ImageInfo?,
    val camera: CameraInfo?,
    val capture: CaptureInfo?,
    val target: TargetInfo?,
    val shotsPerEnd: Int?,
    val shots: List<TruthShot>,
    val unresolvedArrows: Int,
    val registration: Registration?
) {
    init {
        require(imageName.isNotBlank()) { "an entry needs an image name" }
        require(unresolvedArrows >= 0) {
            "unresolved arrows cannot be negative, got $unresolvedArrows"
        }
    }

    /**
     * How many hits this entry expects a detector to find — the LISTED ones,
     * not the size of the end. The corpus README is explicit: the detection
     * rate and the position error refer to the listed hits.
     */
    val expectedShots: Int
        get() = shots.size

    /** False for a registration only entry, which contributes to no hit metric. */
    val isAnnotated: Boolean
        get() = shots.isNotEmpty()

    /**
     * Whether every listed shot carries a position. Partial annotation counts
     * as none: otherwise the position error would depend on which arrows
     * happened to be placed.
     */
    val hasPositions: Boolean
        get() = isAnnotated && shots.all { it.position != null }

    /** The capture conditions, used to break the metrics down by difficulty. */
    val tags: Set<String>
        get() = setOfNotNull(capture?.lighting, capture?.angle)
}
```

- [ ] **Step 6: Test laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*CorpusEntryTest*"`
Expected: Die zehn Tests dieser Datei kompilieren und bestehen. Der Gesamtlauf schlägt weiterhin fehl, weil `SidecarTruth`, `FilenameTruth`, `CorpusLoader`, `ShotMatching`, `Metrics` und `MetricsReport` noch das alte Modell erwarten. Das ist der erwartete Zwischenstand.

Falls Gradle den Testlauf gar nicht erst startet, weil das Modul nicht übersetzt, kommentiere die noch nicht angepassten Dateien **nicht** aus — gehe stattdessen zu Task 2 weiter und führe diesen Test dort erneut aus. Halte das im Bericht fest.

- [ ] **Step 7: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/ \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusEntryTest.kt
git commit -m "Replace the corpus truth model with the real one

The corpus stores a zone index, a per shot position tolerance and an
unresolved arrow count, none of which the invented format had. This
commit changes the model alone; the module does not compile until the
readers and the metrics follow in the next tasks."
```

---

## Task 2: Das verschachtelte Sidecar lesen

**Files:**
- Replace: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruth.kt`
- Replace: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruthTest.kt`

**Interfaces:**
- Consumes: alles aus Task 1
- Produces: `SidecarTruth.parse(imageName: String, json: String): CorpusEntry`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SidecarTruthTest {

    private val full = """
        {
          "image": { "file": "a.jpg", "width": 4000, "height": 2252, "exifOrientation": 6 },
          "camera": { "model": "Galaxy S25", "focalLengthMm": 5.4, "focalLength35mm": 23 },
          "capture": { "lighting": "sonne", "angle": "leicht-schraeg", "angleDegrees": null },
          "target": { "model": "WAFull", "diameterCm": 80, "faceCount": 1 },
          "end": { "shotsPerEnd": 6 },
          "shots": [
            { "faceIndex": 0, "x": -0.195, "y": -0.201, "scoringRing": 3, "points": 8,
              "positionTolerance": 0.015 },
            { "faceIndex": 0, "x": 0.028, "y": 0.039, "scoringRing": 0, "points": 10,
              "positionTolerance": 0.01, "nearRingBoundary": true }
          ],
          "annotation": { "unresolvedArrows": 2 },
          "registration": {
            "imageToTarget": [[0.0009, 0.0, -1.03], [0.0, 0.00087, -1.5], [0.00012, -7.1e-05, 1.0]],
            "imagedCentre": [1145.77, 1775.66]
          }
        }
    """.trimIndent()

    @Test
    fun readsEveryPartOfARealSidecar() {
        val e = SidecarTruth.parse("a.jpg", full)

        assertThat(e.imageName).isEqualTo("a.jpg")
        assertThat(e.image!!.width).isEqualTo(4000)
        assertThat(e.image!!.longEdge).isEqualTo(4000)
        assertThat(e.camera!!.focalLength35mm!!).isWithin(1e-9).of(23.0)
        assertThat(e.target!!.model).isEqualTo("WAFull")
        assertThat(e.target!!.faceCount).isEqualTo(1)
        assertThat(e.shotsPerEnd).isEqualTo(6)
        assertThat(e.unresolvedArrows).isEqualTo(2)
        assertThat(e.tags).containsExactly("sonne", "leicht-schraeg")
    }

    @Test
    fun readsTheShotsWithRingToleranceAndFlags() {
        val e = SidecarTruth.parse("a.jpg", full)

        assertThat(e.expectedShots).isEqualTo(2)
        assertThat(e.hasPositions).isTrue()

        val first = e.shots[0]
        assertThat(first.scoringRing).isEqualTo(3)
        assertThat(first.position!!.x).isWithin(1e-9).of(-0.195)
        assertThat(first.positionTolerance!!).isWithin(1e-9).of(0.015)
        assertThat(first.nearRingBoundary).isFalse()

        assertThat(e.shots[1].scoringRing).isEqualTo(0)
        assertThat(e.shots[1].nearRingBoundary).isTrue()
    }

    @Test
    fun readsTheRegistrationAsNineValuesRowByRow() {
        val r = SidecarTruth.parse("a.jpg", full).registration!!

        assertThat(r.imageToTarget).hasSize(9)
        assertThat(r.imageToTarget[0]).isWithin(1e-12).of(0.0009)
        assertThat(r.imageToTarget[3]).isWithin(1e-12).of(0.0)
        assertThat(r.imageToTarget[8]).isWithin(1e-12).of(1.0)
        assertThat(r.imagedCentre!!.x).isWithin(1e-6).of(1145.77)
    }

    @Test
    fun anEmptyShotListIsARegistrationOnlyEntry() {
        val json = """
            {
              "image": { "file": "r.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": []
            }
        """.trimIndent()

        val e = SidecarTruth.parse("r.jpg", json)

        assertThat(e.isAnnotated).isFalse()
        assertThat(e.expectedShots).isEqualTo(0)
        assertThat(e.unresolvedArrows).isEqualTo(0)
    }

    @Test
    fun unknownFieldsAreIgnoredSoTheCorpusCanGrow() {
        val json = """
            {
              "image": { "file": "u.jpg", "width": 100, "height": 100, "somethingNew": 7 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 0, "x": 0.0, "y": 0.0, "scoringRing": 0 } ],
              "aFieldFromNextYear": { "nested": true }
            }
        """.trimIndent()

        val e = SidecarTruth.parse("u.jpg", json)
        assertThat(e.expectedShots).isEqualTo(1)
    }

    @Test
    fun aShotWithoutAScoringRingIsRejected() {
        val json = """
            {
              "image": { "file": "n.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 0, "x": 0.0, "y": 0.0 } ]
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("n.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("n.jpg")
        assertThat(error!!).hasMessageThat().contains("shot 0")
    }

    @Test
    fun aHalfGivenPositionIsRejected() {
        val json = """
            {
              "image": { "file": "h.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 0, "x": 0.1, "scoringRing": 0 } ]
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("h.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("h.jpg")
    }

    @Test
    fun aDecimalCommaNamesTheImageRatherThanThrowingBare() {
        val json = """
            {
              "image": { "file": "c.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 0, "x": "0,031", "y": 0.1, "scoringRing": 0 } ]
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("c.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("c.jpg")
    }

    @Test
    fun malformedJsonNamesTheImage() {
        val error = runCatching { SidecarTruth.parse("b.jpg", "{ not json") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("b.jpg")
    }

    @Test
    fun aMissingShotsArrayIsRejected() {
        val json = """{ "target": { "model": "WAFull", "faceCount": 1 } }"""
        val error = runCatching { SidecarTruth.parse("m.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("m.jpg")
    }

    @Test
    fun aRegistrationMatrixOfTheWrongShapeNamesTheImage() {
        val json = """
            {
              "image": { "file": "w.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [],
              "registration": { "imageToTarget": [[1.0, 2.0], [3.0, 4.0]] }
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("w.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("w.jpg")
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*SidecarTruthTest*"`
Expected: FAIL — die alte `SidecarTruth` kennt das Schema nicht.

- [ ] **Step 3: `SidecarTruth.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.gson.Gson

/**
 * Reads the ground truth of a photograph from its JSON sidecar.
 *
 * The schema is the corpus's own, documented in its README, which is the
 * binding authority for it. Unknown fields are ignored on purpose — the corpus
 * grows, and a field added next year must not break a measurement today — while
 * a missing required field is an error, because silently measuring against half
 * a truth is worse than stopping.
 */
object SidecarTruth {

    private val gson = Gson()

    private class ShotJson {
        var faceIndex: Int? = null
        var x: Double? = null
        var y: Double? = null
        var scoringRing: Int? = null
        var positionTolerance: Double? = null
        var nearRingBoundary: Boolean? = null
        var uncertain: Boolean? = null
    }

    private class ImageJson {
        var width: Int? = null
        var height: Int? = null
        var exifOrientation: Int? = null
    }

    private class CameraJson {
        var model: String? = null
        var focalLength35mm: Double? = null
    }

    private class CaptureJson {
        var lighting: String? = null
        var angle: String? = null
        var angleDegrees: Double? = null
    }

    private class TargetJson {
        var model: String? = null
        var diameterCm: Double? = null
        var faceCount: Int? = null
    }

    private class EndJson {
        var shotsPerEnd: Int? = null
    }

    private class AnnotationJson {
        var unresolvedArrows: Int? = null
    }

    private class RegistrationJson {
        var imageToTarget: List<List<Double>>? = null
        var imagedCentre: List<Double>? = null
    }

    private class SidecarJson {
        var image: ImageJson? = null
        var camera: CameraJson? = null
        var capture: CaptureJson? = null
        var target: TargetJson? = null
        var end: EndJson? = null
        var shots: List<ShotJson>? = null
        var annotation: AnnotationJson? = null
        var registration: RegistrationJson? = null
    }

    /**
     * @throws IllegalArgumentException with [imageName] in every message, so a
     *         corpus of a hundred photographs still says which one is broken.
     */
    fun parse(imageName: String, json: String): CorpusEntry {
        val parsed = try {
            gson.fromJson(json, SidecarJson::class.java)
        } catch (e: RuntimeException) {
            // Covers gson's JsonSyntaxException and the bare NumberFormatException
            // it throws for a hand typed decimal comma.
            throw IllegalArgumentException("$imageName: cannot read JSON sidecar", e)
        } ?: throw IllegalArgumentException("$imageName: empty JSON sidecar")

        val shotsJson = parsed.shots
            ?: throw IllegalArgumentException("$imageName: sidecar has no shots array")

        val shots = shotsJson.mapIndexed { index, shot -> readShot(imageName, index, shot) }

        return CorpusEntry(
            imageName = imageName,
            image = parsed.image?.let { readImage(imageName, it) },
            camera = parsed.camera?.let { CameraInfo(it.model, it.focalLength35mm) },
            capture = parsed.capture?.let { CaptureInfo(it.lighting, it.angle, it.angleDegrees) },
            target = parsed.target?.let { readTarget(imageName, it) },
            shotsPerEnd = parsed.end?.shotsPerEnd,
            shots = shots,
            unresolvedArrows = parsed.annotation?.unresolvedArrows ?: 0,
            registration = parsed.registration?.let { readRegistration(imageName, it) }
        )
    }

    private fun readShot(imageName: String, index: Int, shot: ShotJson): TruthShot {
        val hasX = shot.x != null
        val hasY = shot.y != null
        require(hasX == hasY) {
            "$imageName: shot $index gives only one of x and y; a position needs both"
        }
        require(shot.scoringRing != null) {
            "$imageName: shot $index has no scoringRing"
        }

        val position = if (hasX) SpotPosition(shot.faceIndex ?: 0, shot.x!!, shot.y!!) else null
        require(position != null || shot.faceIndex == null) {
            "$imageName: shot $index gives faceIndex without x and y"
        }

        return TruthShot(
            scoringRing = shot.scoringRing,
            printedScore = null,
            position = position,
            positionTolerance = shot.positionTolerance,
            nearRingBoundary = shot.nearRingBoundary ?: false,
            uncertain = shot.uncertain ?: false
        )
    }

    private fun readImage(imageName: String, image: ImageJson): ImageInfo {
        val width = image.width
            ?: throw IllegalArgumentException("$imageName: image block has no width")
        val height = image.height
            ?: throw IllegalArgumentException("$imageName: image block has no height")
        return ImageInfo(imageName, width, height, image.exifOrientation)
    }

    private fun readTarget(imageName: String, target: TargetJson): TargetInfo {
        val model = target.model
            ?: throw IllegalArgumentException("$imageName: target block has no model")
        return TargetInfo(model, target.diameterCm, target.faceCount ?: 1)
    }

    private fun readRegistration(
        imageName: String,
        registration: RegistrationJson
    ): Registration? {
        val rows = registration.imageToTarget ?: return null
        require(rows.size == 3 && rows.all { it.size == 3 }) {
            "$imageName: imageToTarget must be three rows of three, got " +
                "${rows.size} rows of ${rows.map { it.size }}"
        }
        val centre = registration.imagedCentre
        require(centre == null || centre.size == 2) {
            "$imageName: imagedCentre must be two values, got ${centre?.size}"
        }
        return Registration(
            imageToTarget = rows.flatten(),
            imagedCentre = centre?.let { SpotPosition(0, it[0], it[1]) }
        )
    }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*SidecarTruthTest*"`
Expected: PASS, 11 Tests. Der Gesamtlauf schlägt weiterhin fehl.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruth.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruthTest.kt
git commit -m "Read the corpus's own nested sidecar schema"
```

---

## Task 3: Das geerbte Namensschema nachziehen

**Files:**
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/FilenameTruth.kt`
- Modify: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/FilenameTruthTest.kt`

Die Zerlegung des Namens bleibt unverändert; nur das erzeugte Modell ändert sich.

**Interfaces:**
- Consumes: `PrintedScore`, `TruthShot`, `CorpusEntry` (Task 1)
- Produces: `FilenameTruth.parse(fileName: String): CorpusEntry?`

- [ ] **Step 1: Den Test anpassen**

Ersetze in `FilenameTruthTest.kt` den Helfer und die Zusicherungen auf das neue Modell. Der Helfer wird zu:

```kotlin
    private fun scoresOf(entry: CorpusEntry) =
        entry.shots.map { it.printedScore!!.text }
```

und ergänze diesen Test:

```kotlin
    @Test
    fun anInheritedEntryCarriesNoRingIndexAndNoMetadata() {
        // The file name gives printed values and no target model, so these
        // entries cannot support ring accuracy against a zone index, and they
        // have no capture conditions to group by.
        val entry = FilenameTruth.parse("a6_998877.jpg")!!

        assertThat(entry.shots.all { it.scoringRing == null }).isTrue()
        assertThat(entry.shots.all { it.printedScore != null }).isTrue()
        assertThat(entry.target).isNull()
        assertThat(entry.image).isNull()
        assertThat(entry.tags).isEmpty()
        assertThat(entry.unresolvedArrows).isEqualTo(0)
        assertThat(entry.hasPositions).isFalse()
    }
```

Der bisherige Test `parsesSixArrowsWithoutATag` prüft `entry.tags`; die Erschwernis des geerbten Namens ist jetzt keine `capture`-Angabe mehr. Ändere seine Tag-Zusicherung zu `assertThat(entry.tags).isEmpty()` und verschiebe die Prüfung des Markers in einen eigenen Test über `FilenameTruth.tagOf`:

```kotlin
    @Test
    fun theInheritedMarkerIsAvailableSeparately() {
        assertThat(FilenameTruth.tagOf("a6_x99765_noise.jpg")).isEqualTo("noise")
        assertThat(FilenameTruth.tagOf("a6_998877.jpg")).isNull()
        assertThat(FilenameTruth.tagOf("holiday.jpg")).isNull()
    }
```

Passe die übrigen Tests, die `entry.tags` prüften, entsprechend an: Sie prüfen nun `FilenameTruth.tagOf(name)`.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*FilenameTruthTest*"`
Expected: FAIL — `Unresolved reference: tagOf`, und `printedScore` fehlt am alten Modell.

- [ ] **Step 3: `FilenameTruth.kt` anpassen**

Behalte den regulären Ausdruck und die Längenprüfung unverändert. Ersetze den Rumpf von `parse` und ergänze `tagOf`:

```kotlin
    /** Null when [fileName] does not follow the scheme, or contradicts itself. */
    fun parse(fileName: String): CorpusEntry? {
        val match = PATTERN.matchEntire(fileName) ?: return null

        val declaredCount = match.groupValues[1].toIntOrNull() ?: return null
        if (declaredCount <= 0) return null

        val scoreText = match.groupValues[2]
        if (scoreText.length != declaredCount) return null

        val shots = scoreText.map { c ->
            val score = PrintedScore.parseFilenameChar(c) ?: return null
            TruthShot(printedScore = score)
        }

        return CorpusEntry(
            imageName = fileName,
            image = null,
            camera = null,
            capture = null,
            target = null,
            shotsPerEnd = declaredCount,
            shots = shots,
            unresolvedArrows = 0,
            registration = null
        )
    }

    /**
     * The difficulty marker the inherited scheme appends, such as "dark" or
     * "overlap", or null when there is none.
     *
     * It is not a capture condition in the sense the annotated photographs use,
     * so it does not become a [CorpusEntry] tag automatically; the loader
     * decides what to do with it.
     */
    fun tagOf(fileName: String): String? {
        val match = PATTERN.matchEntire(fileName) ?: return null
        val tag = match.groupValues[3]
        return tag.ifEmpty { null }
    }
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*FilenameTruthTest*"`
Expected: PASS, 9 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/FilenameTruth.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/FilenameTruthTest.kt
git commit -m "Carry inherited file name truth into the new shot model"
```

---

## Task 4: Den Loader auf die echte Ablage ziehen

**Files:**
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoader.kt`
- Modify: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoaderTest.kt`

Drei Änderungen: Das Sidecar heißt `<basis>.json`, ein verwaistes Sidecar wird gemeldet statt still ignoriert, und der Marker des geerbten Namens wird als Tag übernommen.

**Interfaces:**
- Consumes: `FilenameTruth.parse`, `FilenameTruth.tagOf`, `SidecarTruth.parse`, `CorpusEntry` (Tasks 1 bis 3)
- Produces:
  - `CorpusLoader.LoadResult(entries: List<CorpusEntry>, ignored: List<String>, orphanSidecars: List<String>)`
  - `CorpusLoader.load(root: java.io.File): LoadResult`

`defaults.json` entfällt: Das Auflagenmodell steht jetzt im Sidecar jedes annotierten Fotos, und die geerbten Fotos haben keines. Entferne die Vorgabenlogik samt `SidecarTruth.defaultsTargetModel`.

- [ ] **Step 1: Den Test anpassen**

Ersetze `CorpusLoaderTest.kt` durch:

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CorpusLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun write(relative: String, content: String = "") {
        val file = File(folder.root, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun sidecar(model: String = "WAFull", shots: String = "[]") = """
        {
          "image": { "file": "x.jpg", "width": 100, "height": 100 },
          "capture": { "lighting": "bedeckt", "angle": "frontal" },
          "target": { "model": "$model", "faceCount": 1 },
          "shots": $shots
        }
    """.trimIndent()

    @Test
    fun readsTruthFromFileNames() {
        write("a6_998877.jpg")
        write("a8_xxx99988_front.jpg")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName })
            .containsExactly("a6_998877.jpg", "a8_xxx99988_front.jpg")
        assertThat(result.ignored).isEmpty()
        assertThat(result.orphanSidecars).isEmpty()
    }

    @Test
    fun theInheritedMarkerBecomesATag() {
        write("a6_x99765_noise.jpg")
        val entry = CorpusLoader.load(folder.root).entries.single()
        assertThat(entry.tags).containsExactly("noise")
    }

    @Test
    fun aSidecarIsFoundBesideTheImageUnderTheSameBaseName() {
        write("2026-06-06_sonne_leicht-schraeg_01.jpg")
        write(
            "2026-06-06_sonne_leicht-schraeg_01.json",
            sidecar(shots = """[ { "faceIndex": 0, "x": 0.1, "y": 0.0, "scoringRing": 2 } ]""")
        )

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.expectedShots).isEqualTo(1)
        assertThat(entry.target!!.model).isEqualTo("WAFull")
        assertThat(entry.tags).containsExactly("bedeckt", "frontal")
    }

    @Test
    fun aSidecarWinsOverTheFileName() {
        write("a6_998877.jpg")
        write(
            "a6_998877.json",
            sidecar(shots = """[ { "faceIndex": 0, "x": 0.0, "y": 0.0, "scoringRing": 0 } ]""")
        )

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.expectedShots).isEqualTo(1)
        assertThat(entry.shots[0].scoringRing).isEqualTo(0)
        assertThat(entry.target).isNotNull()
    }

    @Test
    fun aRegistrationOnlyEntryIsLoadedRatherThanRejected() {
        write("reg.jpg")
        write("reg.json", sidecar())

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.isAnnotated).isFalse()
        assertThat(entry.expectedShots).isEqualTo(0)
    }

    @Test
    fun anOrphanSidecarIsReportedRatherThanSilentlyUnused() {
        // The natural mistake, and the one that would make a hand annotation
        // silently have no effect at all.
        write("a6_998877.jpg")
        write("typo.json", sidecar())

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries).hasSize(1)
        assertThat(result.orphanSidecars).containsExactly("typo.json")
    }

    @Test
    fun unrecognisedImagesAreReportedRatherThanFatal() {
        write("a6_998877.jpg")
        write("holiday.jpg")
        write("notes.txt")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries).hasSize(1)
        assertThat(result.ignored).containsExactly("holiday.jpg")
    }

    @Test
    fun subdirectoriesAreRead() {
        write("wa-full/2026-06-06_sonne_frontal_01.jpg")
        write("wa-full/2026-06-06_sonne_frontal_01.json", sidecar())
        write("inherited-249/a6_998877.jpg")

        val names = CorpusLoader.load(folder.root).entries.map { it.imageName }

        assertThat(names).hasSize(2)
    }

    @Test
    fun aBrokenSidecarNamesItsFile() {
        write("a6_998877.jpg")
        write("a6_998877.json", "{ not json")

        val error = runCatching { CorpusLoader.load(folder.root) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("a6_998877.jpg")
    }

    @Test
    fun aMissingRootYieldsAnEmptyResultRatherThanAnError() {
        val result = CorpusLoader.load(File(folder.root, "nope"))
        assertThat(result.entries).isEmpty()
        assertThat(result.ignored).isEmpty()
        assertThat(result.orphanSidecars).isEmpty()
    }

    @Test
    fun everyListComesBackInAStableOrder() {
        write("a6_x99976.jpg")
        write("a6_998877.jpg")
        write("zzz.jpg")
        write("aaa.jpg")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName }).isInOrder()
        assertThat(result.ignored).isInOrder()
        assertThat(result.ignored).containsExactly("aaa.jpg", "zzz.jpg").inOrder()
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*CorpusLoaderTest*"`
Expected: FAIL — `orphanSidecars` gibt es nicht, und der Sidecar-Name stimmt nicht.

- [ ] **Step 3: `CorpusLoader.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import java.io.File

/**
 * Reads a corpus directory into entries.
 *
 * A photograph takes its truth from a sidecar `<base>.json` beside it, and
 * otherwise from the inherited file name scheme. An image neither can read is
 * reported in [LoadResult.ignored] rather than aborting: a corpus grows by
 * dropping photographs into a folder, and one holiday snap must not stop a
 * measurement.
 *
 * A broken sidecar IS fatal, because someone stated the truth and got it wrong,
 * and ignoring that would quietly drop an annotated photograph out of the
 * metrics. A sidecar with no matching image is reported in
 * [LoadResult.orphanSidecars] — it is almost always a misspelt name, and
 * silently ignoring it would make a hand annotation have no effect at all.
 */
object CorpusLoader {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

    class LoadResult(
        val entries: List<CorpusEntry>,
        val ignored: List<String>,
        val orphanSidecars: List<String>
    )

    fun load(root: File): LoadResult {
        if (!root.isDirectory) {
            return LoadResult(emptyList(), emptyList(), emptyList())
        }
        val entries = mutableListOf<CorpusEntry>()
        val ignored = mutableListOf<String>()
        val orphans = mutableListOf<String>()
        loadDirectory(root, entries, ignored, orphans)
        return LoadResult(
            entries.sortedBy { it.imageName },
            ignored.sorted(),
            orphans.sorted()
        )
    }

    private fun loadDirectory(
        directory: File,
        entries: MutableList<CorpusEntry>,
        ignored: MutableList<String>,
        orphans: MutableList<String>
    ) {
        val children = directory.listFiles() ?: return
        val images = children.filter { it.isFile && it.isImage() }
        val usedSidecars = mutableSetOf<String>()

        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                loadDirectory(child, entries, ignored, orphans)
            }
        }

        for (image in images.sortedBy { it.name }) {
            val sidecar = File(directory, image.nameWithoutExtension + ".json")
            val entry = if (sidecar.isFile) {
                usedSidecars.add(sidecar.name)
                SidecarTruth.parse(image.name, sidecar.readText())
            } else {
                FilenameTruth.parse(image.name)
            }

            if (entry == null) {
                ignored.add(image.name)
            } else {
                entries.add(withInheritedTag(entry))
            }
        }

        for (child in children) {
            if (child.isFile && child.extension.lowercase() == "json" &&
                child.name !in usedSidecars
            ) {
                orphans.add(child.name)
            }
        }
    }

    /**
     * The inherited scheme's difficulty marker becomes a tag, so those
     * photographs can still be grouped by difficulty even though they carry no
     * capture block.
     */
    private fun withInheritedTag(entry: CorpusEntry): CorpusEntry {
        if (entry.capture != null) return entry
        val tag = FilenameTruth.tagOf(entry.imageName) ?: return entry
        return entry.copy(capture = CaptureInfo(lighting = tag, angle = null, angleDegrees = null))
    }

    private fun File.isImage(): Boolean = extension.lowercase() in IMAGE_EXTENSIONS
}
```

Entferne `defaultsTargetModel` aus `SidecarTruth.kt` und den zugehörigen Test aus `SidecarTruthTest.kt`, falls Task 2 sie noch stehen ließ.

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*CorpusLoaderTest*"`
Expected: PASS, 11 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/ \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/
git commit -m "Find sidecars under the corpus's own naming and report orphans"
```

---

## Task 5: Zuordnung mit Toleranz je Treffer

**Files:**
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ShotMatching.kt`
- Modify: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ShotMatchingTest.kt`

**Interfaces:**
- Consumes: `CorpusEntry`, `TruthShot`, `SpotPosition`, `PrintedScore` (Task 1)
- Produces:
  - `DetectedShotRecord(scoringRing: Int?, printedScore: PrintedScore?, position: SpotPosition, confidence: Double)`
  - `MatchedPair(truthIndex: Int, detectedIndex: Int, distance: Double?)`
  - `MatchResult(pairs, unmatchedTruth, unmatchedDetected)`
  - `ShotMatching.match(entry, detected, defaultPositionTolerance: Double = DEFAULT_POSITION_TOLERANCE): MatchResult`
  - `ShotMatching.DEFAULT_POSITION_TOLERANCE = 0.05`

- [ ] **Step 1: Den Test ergänzen**

Passe den Helfer in `ShotMatchingTest.kt` auf das neue Modell an und ergänze:

```kotlin
    @Test
    fun eachTruthShotBringsItsOwnTolerance() {
        // The annotator records a larger tolerance where shafts overlap and the
        // entry point had to be estimated. A single global gate would either
        // reject that hit or admit sloppiness everywhere else.
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(scoringRing = 0, position = SpotPosition(0, 0.0, 0.0),
                    positionTolerance = 0.002),
                TruthShot(scoringRing = 2, position = SpotPosition(0, 0.5, 0.0),
                    positionTolerance = 0.04)
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            DetectedShotRecord(0, null, SpotPosition(0, 0.01, 0.0), 0.9),
            DetectedShotRecord(2, null, SpotPosition(0, 0.53, 0.0), 0.9)
        )

        val result = ShotMatching.match(e, detected)

        // 0.01 exceeds the tight tolerance of 0.002; 0.03 fits inside 0.04.
        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].truthIndex).isEqualTo(1)
        assertThat(result.unmatchedTruth).containsExactly(0)
    }

    @Test
    fun aShotWithoutItsOwnToleranceFallsBackToTheDefault() {
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(TruthShot(scoringRing = 0, position = SpotPosition(0, 0.0, 0.0))),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(DetectedShotRecord(0, null, SpotPosition(0, 0.04, 0.0), 0.9))

        assertThat(ShotMatching.match(e, detected).pairs).hasSize(1)
        assertThat(
            ShotMatching.match(e, detected, defaultPositionTolerance = 0.01).pairs
        ).isEmpty()
    }

    @Test
    fun ringOnlyTruthPairsByPrintedScore() {
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(printedScore = PrintedScore.of("9")),
                TruthShot(printedScore = PrintedScore.of("7"))
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            DetectedShotRecord(null, PrintedScore.of("7"), SpotPosition(0, 0.6, 0.0), 0.5),
            DetectedShotRecord(null, PrintedScore.of("9"), SpotPosition(0, 0.2, 0.0), 0.9)
        )

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(2)
        assertThat(result.pairs.all { it.distance == null }).isTrue()
    }
```

Behalte die bestehenden Tests zu Reihenfolge, Determinismus und Spot-Trennung; passe nur ihre Konstruktion an das neue Modell an.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*ShotMatchingTest*"`
Expected: FAIL — `DetectedShotRecord` hat die alte Signatur.

- [ ] **Step 3: `ShotMatching.kt` anpassen**

Ersetze `DetectedShotRecord` und die Kandidatenbildung; alles Übrige bleibt.

```kotlin
/**
 * One arrow a detector claims to have found. It always carries a position — a
 * detector that cannot say where an arrow is has not found it — and whichever
 * of the two score forms it can produce.
 */
data class DetectedShotRecord(
    val scoringRing: Int?,
    val printedScore: PrintedScore?,
    val position: SpotPosition,
    val confidence: Double
)
```

In `match`, ersetze den Parameter und die Weitergabe:

```kotlin
    /**
     * The tolerance used for a truth shot that states none of its own, in spot
     * radii. The corpus records a tolerance per hit, so this only applies to
     * entries that predate that.
     */
    const val DEFAULT_POSITION_TOLERANCE = 0.05

    fun match(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        defaultPositionTolerance: Double = DEFAULT_POSITION_TOLERANCE
    ): MatchResult = if (entry.hasPositions) {
        matchByPosition(entry, detected, defaultPositionTolerance)
    } else {
        matchByScore(entry, detected)
    }
```

In `matchByPosition`, nimm die Toleranz je Treffer:

```kotlin
        entry.shots.forEachIndexed { truthIndex, truth ->
            // hasPositions guarantees this, so the elvis can never fire; kept
            // null safe rather than asserted.
            val truthPosition = truth.position ?: return@forEachIndexed
            val tolerance = truth.positionTolerance ?: defaultPositionTolerance
            detected.forEachIndexed { detectedIndex, record ->
                val distance = truthPosition.distanceTo(record.position)
                if (distance != null && distance <= tolerance) {
                    candidates.add(MatchedPair(truthIndex, detectedIndex, distance))
                }
            }
        }
```

In `matchByScore`, vergleiche über den gedruckten Wert, mit dem Zonenindex als Vorrang:

```kotlin
        for (detectedIndex in byConfidence) {
            val record = detected[detectedIndex]
            val hit = remainingTruth.firstOrNull { scoresAgree(entry.shots[it], record) }
            if (hit != null) {
                remainingTruth.remove(hit)
                pairs.add(MatchedPair(hit, detectedIndex, null))
            }
        }
```

und ergänze:

```kotlin
    /**
     * Whether a truth shot and a detection carry the same score. The zone index
     * wins where both sides have one, because it is exact; the printed value is
     * the fallback for the inherited photographs, which have no target model
     * and therefore no index.
     */
    internal fun scoresAgree(truth: TruthShot, detected: DetectedShotRecord): Boolean = when {
        truth.scoringRing != null && detected.scoringRing != null ->
            truth.scoringRing == detected.scoringRing
        truth.printedScore != null && detected.printedScore != null ->
            truth.printedScore == detected.printedScore
        else -> false
    }
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*ShotMatchingTest*"`
Expected: PASS, 11 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ShotMatching.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ShotMatchingTest.kt
git commit -m "Match with the tolerance each hit brings and compare by zone index"
```

---

## Task 6: Kennzahlen mit eigenem Nenner

**Files:**
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/Metrics.kt`
- Modify: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/MetricsTest.kt`

Drei Änderungen:

1. **Nicht annotierte Einträge zählen nirgends mit.** Ein Registrierungseintrag hat keine Treffer und darf keine Kennzahl verwässern.
2. **`unresolvedArrows` entschärft die Falsch-Positiven.** Der Korpus-README: Ein gefundener Pfeil, der zu keinem gelisteten Treffer passt, zählt nur dann als falsch-positiv, wenn `unresolvedArrows` null ist. Das ist die wörtliche Regel; sie ist großzügig, weil sie bei einem einzigen unaufgelösten Pfeil beliebig viele Erfindungen durchgehen lässt. Sie wird trotzdem so umgesetzt, weil der Korpus-README die Autorität für sein eigenes Format ist. Der Code hält die Alternative fest.
3. **Die Ringtreue hat ihren eigenen Nenner:** nur Einträge, deren Wahrheit mit dem vergleichbar ist, was der Detektor liefert.

**Interfaces:**
- Consumes: `CorpusEntry`, `MatchResult`, `DetectedShotRecord`, `ShotMatching.scoresAgree` (Tasks 1 und 5)
- Produces: `Metrics` mit `expectedShots`, `matchedShots`, `falsePositives`, `correctScores`, `scoreComparableShots`, `entriesWithPositions`, `annotatedEntries`, `forgivenEntries`, sowie `detectionRate`, `falsePositiveRate`, `scoreAccuracy`, `medianPositionError`, `p95PositionError`, alle nullbar

- [ ] **Step 1: Den Test ergänzen**

```kotlin
    @Test
    fun aRegistrationOnlyEntryContributesToNothing() {
        val registrationOnly = CorpusEntry(
            imageName = "r.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6, shots = emptyList(),
            unresolvedArrows = 0, registration = null
        )
        val m = Metrics.over(
            listOf(EntryOutcome(registrationOnly, ShotMatching.match(registrationOnly, emptyList()), emptyList()))
        )

        assertThat(m.expectedShots).isEqualTo(0)
        assertThat(m.annotatedEntries).isEqualTo(0)
        assertThat(m.detectionRate).isNull()
    }

    @Test
    fun unresolvedArrowsForgiveUnmatchedDetections() {
        // Six arrows are in the photograph, four could be annotated. A detector
        // finding all six must not be charged two inventions for the two the
        // annotator could not place.
        val e = entryWithUnresolved(unresolved = 2)
        val detected = listOf(
            found(0, 0.0, 0.0), found(2, 0.3, 0.0),
            found(3, 0.6, 0.0), found(3, 0.9, 0.0),
            found(2, 0.05, 0.05), found(2, 0.06, 0.06)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(4)
        assertThat(m.falsePositives).isEqualTo(0)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(0.0)
        assertThat(m.forgivenEntries).isEqualTo(1)
    }

    @Test
    fun withoutUnresolvedArrowsAnUnmatchedDetectionIsAnInvention() {
        val e = entryWithUnresolved(unresolved = 0)
        val detected = listOf(
            found(0, 0.0, 0.0), found(2, 0.3, 0.0),
            found(3, 0.6, 0.0), found(3, 0.9, 0.0),
            found(2, 0.05, 0.05)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(4)
        assertThat(m.falsePositives).isEqualTo(1)
        assertThat(m.forgivenEntries).isEqualTo(0)
    }

    @Test
    fun ringAccuracyCountsOnlyComparableTruth() {
        // An inherited entry carries printed values; a detector that reports only
        // zone indices cannot be scored against it, so it must not drag the ring
        // accuracy down.
        val inherited = CorpusEntry(
            imageName = "i.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(printedScore = PrintedScore.of("9")),
                TruthShot(printedScore = PrintedScore.of("8"))
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            DetectedShotRecord(2, null, SpotPosition(0, 0.1, 0.0), 0.9),
            DetectedShotRecord(3, null, SpotPosition(0, 0.3, 0.0), 0.9)
        )
        val m = Metrics.over(listOf(outcome(inherited, detected)))

        assertThat(m.scoreComparableShots).isEqualTo(0)
        assertThat(m.scoreAccuracy).isNull()
    }
```

Ergänze die Helfer:

```kotlin
    private fun entryWithUnresolved(unresolved: Int) = CorpusEntry(
        imageName = "u.jpg", image = null, camera = null, capture = null,
        target = null, shotsPerEnd = 6,
        shots = listOf(
            TruthShot(scoringRing = 0, position = SpotPosition(0, 0.0, 0.0)),
            TruthShot(scoringRing = 2, position = SpotPosition(0, 0.3, 0.0)),
            TruthShot(scoringRing = 3, position = SpotPosition(0, 0.6, 0.0)),
            TruthShot(scoringRing = 3, position = SpotPosition(0, 0.9, 0.0))
        ),
        unresolvedArrows = unresolved, registration = null
    )

    private fun found(ring: Int, x: Double, y: Double) =
        DetectedShotRecord(ring, null, SpotPosition(0, x, y), 0.9)
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*MetricsTest*"`
Expected: FAIL — `scoreComparableShots`, `annotatedEntries` und `forgivenEntries` gibt es nicht.

- [ ] **Step 3: `Metrics.kt` anpassen**

```kotlin
class Metrics(
    val expectedShots: Int,
    val matchedShots: Int,
    val falsePositives: Int,
    val correctScores: Int,
    val scoreComparableShots: Int,
    val positionErrors: List<Double>,
    val entriesWithPositions: Int,
    val annotatedEntries: Int,
    val forgivenEntries: Int
) {

    /** Null when nothing was measured, never zero — a zero reads as a result. */
    val detectionRate: Double?
        get() = ratio(matchedShots, expectedShots)

    val falsePositiveRate: Double?
        get() = ratio(falsePositives, expectedShots)

    /**
     * Measured against the shots whose truth is comparable with what the
     * detector reports, not against every expected shot. An inherited entry
     * carries printed values and no target model; scoring a zone index against
     * it is not possible, and counting it as wrong would be a lie.
     */
    val scoreAccuracy: Double?
        get() = ratio(correctScores, scoreComparableShots)

    val medianPositionError: Double?
        get() = percentile(0.50)

    val p95PositionError: Double?
        get() = percentile(0.95)

    private fun ratio(count: Int, total: Int): Double? =
        if (total == 0) null else count.toDouble() / total

    private fun percentile(fraction: Double): Double? {
        if (positionErrors.isEmpty()) return null
        val sorted = positionErrors.sorted()
        val rank = fraction * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val weight = rank - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    companion object {

        fun over(outcomes: List<EntryOutcome>): Metrics {
            var expected = 0
            var matched = 0
            var falsePositives = 0
            var correct = 0
            var comparable = 0
            var withPositions = 0
            var annotated = 0
            var forgiven = 0
            val errors = mutableListOf<Double>()

            for (outcome in outcomes) {
                val entry = outcome.entry
                if (!entry.isAnnotated) {
                    // A registration only entry has no hits to be right or wrong
                    // about. Counting it would dilute every rate.
                    continue
                }
                annotated++
                expected += entry.expectedShots
                matched += outcome.match.pairs.size

                if (entry.unresolvedArrows > 0) {
                    // The corpus README: a detection matching no listed hit counts
                    // as a false positive only when unresolvedArrows is zero. The
                    // arrows are in the photograph; finding them is not an
                    // invention. This is the literal rule, and it is generous --
                    // a single unresolved arrow forgives any number of surplus
                    // detections. Forgiving only as many as are unresolved would
                    // be tighter; changing that is a decision for the corpus's
                    // author, not for this code.
                    forgiven++
                } else {
                    falsePositives += outcome.match.unmatchedDetected.size
                }

                if (entry.hasPositions) {
                    withPositions++
                }

                for (pair in outcome.match.pairs) {
                    val truthShot = entry.shots[pair.truthIndex]
                    val detectedShot = outcome.detected[pair.detectedIndex]
                    if (isScoreComparable(truthShot, detectedShot)) {
                        comparable++
                        if (ShotMatching.scoresAgree(truthShot, detectedShot)) {
                            correct++
                        }
                    }
                    pair.distance?.let { errors.add(it) }
                }
            }

            return Metrics(
                expectedShots = expected,
                matchedShots = matched,
                falsePositives = falsePositives,
                correctScores = correct,
                scoreComparableShots = comparable,
                positionErrors = errors,
                entriesWithPositions = withPositions,
                annotatedEntries = annotated,
                forgivenEntries = forgiven
            )
        }

        /** Whether the two carry the same KIND of score and can be compared. */
        private fun isScoreComparable(
            truth: TruthShot,
            detected: DetectedShotRecord
        ): Boolean =
            (truth.scoringRing != null && detected.scoringRing != null) ||
                (truth.printedScore != null && detected.printedScore != null)

        const val UNTAGGED = "untagged"
    }
}
```

Die bestehende Funktion `byTag` und die Klasse `EntryOutcome` bleiben **wörtlich unverändert** — sie stehen oben nicht, weil an ihnen nichts zu ändern ist, nicht weil sie zu löschen wären. `byTag` ruft `over` auf und profitiert damit automatisch von den neuen Nennern. Ergänze am Kopf der Datei die Importe für `TruthShot` und `PrintedScore`, falls der Compiler sie verlangt.

Bestehende Tests in `MetricsTest.kt` bauen ihre Fixtures über die privaten Helfer `entry`, `truth`, `found` und `outcome`. Ziehe diese Helfer auf das neue Modell nach — `truth` erzeugt jetzt `TruthShot(scoringRing = …, position = …)` statt eines gedruckten Werts, und `found` erzeugt `DetectedShotRecord(ring, null, position, confidence)`. Wo ein bestehender Test die Ringtreue prüft, muss die Wahrheit einen `scoringRing` tragen, sonst zählt sie nach der neuen Regel nicht als vergleichbar und `scoreAccuracy` wird null.

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*MetricsTest*"`
Expected: PASS. Bestehende Tests, die `scoreAccuracy` prüfen, brauchen jetzt vergleichbare Wahrheit; passe ihre Fixtures auf `scoringRing` an, wo sie `printedScore` benutzten.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/Metrics.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/MetricsTest.kt
git commit -m "Give each metric its own denominator and honour unresolved arrows"
```

---

## Task 7: Der Bericht sagt, worauf er sich stützt

**Files:**
- Modify: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/MetricsReport.kt`
- Modify: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/MetricsReportTest.kt`

Jede Kennzahl hat jetzt einen eigenen Nenner, und der Bericht muss ihn nennen — sonst liest sich eine Ringtreue über drei Treffer wie eine über hundert.

- [ ] **Step 1: Den Test ergänzen**

```kotlin
    @Test
    fun eachMetricStatesWhatItRestsOn() {
        val report = MetricsReport.render(
            listOf(outcomeWithRings("a.jpg", listOf(0, 2), listOf(0, 2))),
            title = "Denominators"
        )

        assertThat(report).contains("Detection rate")
        assertThat(report).contains("of 2 listed hits")
        assertThat(report).contains("Ring accuracy")
        assertThat(report).contains("of 2 comparable hits")
    }

    @Test
    fun forgivenEntriesAreCalledOutSoTheFalsePositiveRateIsReadable() {
        val e = CorpusEntry(
            imageName = "f.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6,
            shots = listOf(TruthShot(scoringRing = 0, position = SpotPosition(0, 0.0, 0.0))),
            unresolvedArrows = 2, registration = null
        )
        val detected = listOf(
            DetectedShotRecord(0, null, SpotPosition(0, 0.0, 0.0), 0.9),
            DetectedShotRecord(2, null, SpotPosition(0, 0.5, 0.0), 0.9)
        )
        val report = MetricsReport.render(
            listOf(EntryOutcome(e, ShotMatching.match(e, detected), detected)),
            title = "Forgiven"
        )

        assertThat(report).contains("unresolved arrows")
    }
```

Ergänze den Helfer:

```kotlin
    private fun outcomeWithRings(
        name: String,
        truthRings: List<Int>,
        detectedRings: List<Int>
    ): EntryOutcome {
        val entry = CorpusEntry(
            imageName = name, image = null, camera = null, capture = null,
            target = null, shotsPerEnd = truthRings.size,
            shots = truthRings.mapIndexed { i, r ->
                TruthShot(scoringRing = r, position = SpotPosition(0, 0.1 * i, 0.0))
            },
            unresolvedArrows = 0, registration = null
        )
        val detected = detectedRings.mapIndexed { i, r ->
            DetectedShotRecord(r, null, SpotPosition(0, 0.1 * i, 0.0), 0.9)
        }
        return EntryOutcome(entry, ShotMatching.match(entry, detected), detected)
    }
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*MetricsReportTest*"`
Expected: FAIL — der Bericht nennt die Nenner nicht.

- [ ] **Step 3: `MetricsReport.kt` anpassen**

Ersetze den Kopf und die Kennzahlentabelle:

```kotlin
        sb.appendLine(
            "$photographs ${plural(photographs, "photograph", "photographs")}, " +
                "${overall.annotatedEntries} annotated, " +
                "${overall.expectedShots} listed " +
                "${plural(overall.expectedShots, "hit", "hits")}, " +
                "${overall.entriesWithPositions} with positions."
        )
        if (overall.forgivenEntries > 0) {
            sb.appendLine()
            sb.appendLine(
                "${overall.forgivenEntries} " +
                    "${plural(overall.forgivenEntries, "entry", "entries")} " +
                    "declare unresolved arrows, so surplus detections there are not " +
                    "counted as false positives."
            )
        }
        sb.appendLine()
        sb.appendLine("| Metric | Value | Measured over |")
        sb.appendLine("|---|---|---|")
        sb.appendLine(
            "| Detection rate | ${percent(overall.detectionRate)} | " +
                "${overall.expectedShots} listed hits |"
        )
        sb.appendLine(
            "| False positives | ${percent(overall.falsePositiveRate)} | " +
                "${overall.expectedShots} listed hits |"
        )
        sb.appendLine(
            "| Ring accuracy | ${percent(overall.scoreAccuracy)} | " +
                "${overall.scoreComparableShots} comparable hits |"
        )
        sb.appendLine(
            "| Position error, median | ${spotRadii(overall.medianPositionError)} | " +
                "${overall.positionErrors.size} placed hits |"
        )
        sb.appendLine(
            "| Position error, 95th pct | ${spotRadii(overall.p95PositionError)} | " +
                "${overall.positionErrors.size} placed hits |"
        )
```

Belasse `percent`, `spotRadii`, `measured`, den Tag-Abschnitt und die Worst-Entries-Tabelle unverändert.

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*MetricsReportTest*"`
Expected: PASS.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/MetricsReport.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/MetricsReportTest.kt
git commit -m "State what each metric rests on in the report"
```

---

## Task 8: Gegen den echten Korpus

**Files:**
- Replace: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/InheritedCorpusTest.kt` → `RealCorpusTest.kt`

Der entscheidende Test dieses Plans: Der Korpus **wird ganz** gelesen, ab der Wurzel, nicht nur `inherited-249`. Genau das hat Plan 2 versäumt, und deshalb ist ihm nicht aufgefallen, dass er vier Fotos verwirft.

- [ ] **Step 1: Den Test schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Reads the whole corpus, when there is one.
 *
 * Deliberately loads the ROOT, not a subfolder. The previous version of this
 * test loaded only `inherited-249`, which is why nobody noticed that every
 * annotated photograph was being dropped.
 */
class RealCorpusTest {

    private lateinit var root: File
    private lateinit var result: CorpusLoader.LoadResult

    @Before
    fun requireCorpus() {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)
        result = CorpusLoader.load(root)
    }

    @Test
    fun everyPhotographInTheCorpusIsUnderstood() {
        assertThat(result.ignored).isEmpty()
        assertThat(result.orphanSidecars).isEmpty()
        assertThat(result.entries).isNotEmpty()
    }

    @Test
    fun theAnnotatedPhotographsAreRead() {
        val annotated = result.entries.filter { it.target != null }

        assertThat(annotated).isNotEmpty()
        assertThat(annotated.all { it.isAnnotated }).isTrue()
        assertThat(annotated.all { it.hasPositions }).isTrue()
        assertThat(annotated.all { it.shots.all { s -> s.scoringRing != null } }).isTrue()
        assertThat(annotated.all { it.camera?.focalLength35mm != null }).isTrue()
        assertThat(annotated.all { it.registration != null }).isTrue()
    }

    @Test
    fun noAnnotatedEntryClaimsMoreArrowsThanWereShot() {
        // The defensible invariant, and only this one. Equality does NOT hold
        // across the corpus: 2026-08-15_bedeckt_frontal_02 lists five hits with
        // none unresolved against a six arrow end. Either five arrows were shot
        // or a sixth is unaccounted for; that is the annotator's call, not this
        // test's, so the test asserts what must always be true and the report
        // below names the entries where the two disagree.
        for (entry in result.entries.filter { it.target != null }) {
            val perEnd = entry.shotsPerEnd
            assertThat(perEnd).isNotNull()
            assertThat(entry.expectedShots + entry.unresolvedArrows)
                .isAtMost(perEnd!!)
        }
    }

    @Test
    fun entriesThatDoNotAccountForTheirWholeEndAreListed() {
        // Not a failure — a visible list, so an annotation slip does not hide.
        val incomplete = result.entries
            .filter { it.target != null && it.shotsPerEnd != null }
            .filter { it.expectedShots + it.unresolvedArrows < it.shotsPerEnd!! }
            .map { "${it.imageName}: ${it.expectedShots} listed + " +
                "${it.unresolvedArrows} unresolved of ${it.shotsPerEnd}" }

        // Pinned at the value observed on 2026-09-09. Raise it deliberately if
        // more such entries are added; do not delete the test.
        assertThat(incomplete).hasSize(1)
    }

    @Test
    fun theInheritedPhotographsStillLoadAsRingOnlyTruth() {
        val inherited = result.entries.filter { it.target == null }

        assertThat(inherited).hasSize(16)
        assertThat(inherited.all { !it.hasPositions }).isTrue()
        assertThat(inherited.all { it.shots.all { s -> s.printedScore != null } }).isTrue()
    }

    @Test
    fun aKnownInheritedEntryHasTheScoresItsNamePromises() {
        val entry = result.entries.single { it.imageName == "a8_xxx99988_front.jpg" }

        assertThat(entry.shots.map { it.printedScore!!.text })
            .containsExactly("X", "X", "X", "9", "9", "9", "8", "8").inOrder()
        assertThat(entry.tags).containsExactly("front")
    }

    @Test
    fun everyPositionLiesOnItsFace() {
        // Spot local coordinates put the outermost ring at radius one, so a
        // hit outside that would mean the annotation or the coordinate system
        // is wrong.
        for (entry in result.entries) {
            for (shot in entry.shots) {
                val p = shot.position ?: continue
                val radius = kotlin.math.hypot(p.x, p.y)
                assertThat(radius).isLessThan(1.0)
            }
        }
    }
}
```

- [ ] **Step 2: Ohne Korpus laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*RealCorpusTest*"`
Expected: PASS mit übersprungenen Tests, wenn `DETECTION_CORPUS_DIR` fehlt.

- [ ] **Step 3: Mit Korpus laufen lassen**

Setze `DETECTION_CORPUS_DIR=../MyTargets-corpus` in `gradle-local.properties`.

Run: `./gradlew :detection-corpus:test --tests "*RealCorpusTest*"`
Expected: PASS mit **sechs tatsächlich ausgeführten** Tests. Prüfe `detection-corpus/build/test-results/test/*.xml` auf `skipped="0"` — die Konsolenzusammenfassung unterscheidet übersprungen und bestanden nicht deutlich genug.

Schlägt `everyPhotographInTheCorpusIsUnderstood` mit nicht leerem `ignored` oder `orphanSidecars` fehl, nennt die Meldung die Datei. Lockere die Zusicherung **nicht** — melde, welche Datei und warum sie nicht gelesen wird.

- [ ] **Step 4: Den ganzen Modullauf und die App**

Run: `./gradlew :detection-corpus:test`
Expected: PASS.

Run: `./gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Committen**

```bash
git rm detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/InheritedCorpusTest.kt
git add detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/RealCorpusTest.kt
git commit -m "Read the whole corpus, not just the inherited subfolder"
```

---

## Was dieser Plan nicht liefert

- **Keine Bildverarbeitung.** Unverändert: Das Modul liest Namen und JSON.
- **Kein Detektor.** Plan 3.
- **Keine Registrierungsprüfung.** `registration.imageToTarget` wird gelesen und vorgehalten; es damit die Homographie der Pipeline zu vergleichen, braucht die Pipeline.
- **Keine Umrechnung von Zonenindex auf gedruckten Wert.** Sie bräuchte die Modelltabellen aus `:shared` und gehört zum Läufer in Plan 3.
- **Keine Regressionsschranken.** Erst messen, dann festschreiben.

## Self-Review

**Spec-Abdeckung.** Der Abschnitt *Testkorpus* der Spec ist über die Tasks 1 bis 4 und 8 abgedeckt, einschließlich der Teilwahrheit und der geerbten Fotos. *Kennzahlen* liegt in Task 6, wobei die Spec keinen eigenen Nenner je Kennzahl verlangt — das folgt aus dem Korpus-README und geht darüber hinaus, ohne ihr zu widersprechen. Der Korpus-README ist über die Tasks 2, 4, 5 und 6 abgedeckt: Schema, Ablage, Toleranz je Treffer, `unresolvedArrows`, leere Trefferliste.

**Bewusste Abweichungen:**
1. `defaults.json` entfällt (Task 4). Es war eine Erfindung von Plan 2; das echte Format trägt das Modell je Foto.
2. Die `unresolvedArrows`-Regel wird wörtlich umgesetzt, obwohl die engere Lesart — nur so viele Erfindungen verzeihen, wie Pfeile unaufgelöst sind — verteidigbarer wäre. Der Korpus-README ist die Autorität für sein Format; der Code hält die Alternative fest.
3. Der geerbte Marker (`dark`, `overlap`) wird in `CaptureInfo.lighting` abgelegt, obwohl er nicht immer eine Lichtangabe ist. Die Alternative wäre ein eigenes Tag-Feld nur für geerbte Einträge; das erschien mehr Maschinerie als Nutzen. Sichtbar in `CorpusLoader.withInheritedTag`.

**Typkonsistenz.** `PrintedScore` ersetzt `Score` überall; `TruthShot` und `DetectedShotRecord` tragen beide `scoringRing` und `printedScore` in dieser Reihenfolge. `ShotMatching.scoresAgree` ist `internal`, weil `Metrics` es braucht — beide liegen im selben Modul. `Registration.imageToTarget` ist eine flache Liste von neun Werten, nicht drei Zeilen; das Sidecar liefert Zeilen, `SidecarTruth.readRegistration` flacht ab.

**Offener Punkt.** `DEFAULT_POSITION_TOLERANCE = 0.05` gilt nur noch für Einträge ohne eigene Toleranz, also für die geerbten. Da diese keine Positionen tragen, ist der Wert derzeit unerreichbar; er bleibt als Rückfall stehen, falls später Positionen nachannotiert werden.
