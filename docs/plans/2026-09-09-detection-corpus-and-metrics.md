# Korpus und Messwerkzeug der Pfeilerkennung — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein neues Modul `:detection-corpus` liest einen Korpus aus Scheibenfotos samt Wahrheitsdaten, ordnet erkannte Treffer den echten zu und errechnet daraus die vier Kennzahlen, an denen die Erkennungspipeline gemessen wird.

**Architecture:** Reines Kotlin auf der JVM, ohne Android und ohne OpenCV. Das Modul verarbeitet **keine Bilder** — es liest Dateinamen und JSON-Beiblätter und rechnet über Zahlen. Dadurch laufen alle Tests in Millisekunden, und die Messapparatur ist fertig und bewiesen, bevor es einen Detektor zu messen gibt.

**Tech Stack:** Kotlin (JVM-Plugin), gson für die Beiblätter, JUnit 4, Truth. Alle drei stehen bereits im Versionskatalog.

**Spec:** `docs/design/2026-09-09-arrow-detection-design.md`

## Einordnung

Dies ist **Plan 2 von 4**. Plan 1 (Geometriekern, Modul `:detection`) ist umgesetzt und gemergt.

Was dieser Plan bewusst **nicht** tut: ein Bild öffnen, OpenCV einbinden, oder einen Detektor bauen. Er baut das Maßband, nicht das Gemessene. Plan 3 bringt die Wahrnehmungsstufen und ruft dieses Modul auf, um sich selbst zu bewerten.

Der Nutzen schon vor Plan 3: Die 16 geerbten Fotos tragen ihre Wahrheit im Dateinamen. Nach diesem Plan sind sie maschinell eingelesen, und die erste Zahl, die Plan 3 produziert, ist sofort einordenbar.

## Global Constraints

- **Sprache:** Code, Bezeichner und Kommentare Englisch, wie im übrigen Projekt.
- **Lizenzkopf:** Jede neue Kotlin-Datei bekommt den GPLv2-Kopf aus `detection/src/main/java/de/dreier/mytargets/detection/geometry/Mat3.kt`, Jahr 2026, Halter „MyTargets contributors".
- **Kein Android.** Kein `Bitmap`, kein `PointF`, kein `Context`, keine Android-Gradle-Plugins. Das Modul ist eine reine JVM-Bibliothek.
- **Keine Bildverarbeitung.** Wer OpenCV, ImageIO oder einen Decoder hinzufügt, hat den Plan verlassen.
- **Nur vorhandene Abhängigkeiten:** `libs.gson`, `libs.junit4`, `libs.truth`, `libs.kotlin.stdlib.jdk7`. Neue Versionen kommen nicht dazu.
- **Gleitkomma:** `Double`.
- **Punktzahlen sind Zeichenketten, keine Zonenindizes.** `"X"`, `"10"` … `"1"`, `"M"` für Fehlschuss — genau das, was `Target.zoneToString()` in der App liefert. Grund im Abschnitt *Warum Zeichenketten*.
- **Testbefehl:** `./gradlew :detection-corpus:test`
- **Toleranz in Tests:** Gleitkommavergleiche immer mit expliziter Toleranz, `1e-9` genügt hier durchgehend.

---

## Warum Zeichenketten statt Zonenindizes

`Shot.scoringRing` ist ein **Zonenindex**, und der bedeutet je Auflagenmodell etwas anderes. Bei `WAFull` ist Index 0 der X-Ring, Index 2 die Neun. Bei `WA6Ring` beginnt die Zonenliste erst bei der Fünf, also verschiebt sich alles.

Der geerbte Korpus enthält beide Modelle. Würde die Wahrheit als Index gespeichert, müsste dieses Modul die Modelltabellen aus `:shared` kennen — also Android, also keine JVM-Tests mehr.

Die gedruckte Zahl ist dagegen modellunabhängig eindeutig: Eine Neun ist eine Neun. Die App kann sie über das vorhandene `Target.zoneToString(zone, arrow)` erzeugen; die Umrechnung passiert damit dort, wo `:shared` ohnehin verfügbar ist, und dieses Modul vergleicht nur Zeichenketten.

---

## File Structure

Alle Pfade unterhalb von `detection-corpus/`.

| Datei | Verantwortung |
|---|---|
| `build.gradle` | Modulkonfiguration, reines JVM-Kotlin |
| `src/main/kotlin/.../corpus/Score.kt` | Punktzahl als Zeichenkette, mit Prüfung |
| `src/main/kotlin/.../corpus/CorpusEntry.kt` | Ein Foto mit seiner Wahrheit |
| `src/main/kotlin/.../corpus/FilenameTruth.kt` | Wahrheit aus dem geerbten Namensschema |
| `src/main/kotlin/.../corpus/SidecarTruth.kt` | Wahrheit aus JSON-Beiblatt, gson |
| `src/main/kotlin/.../corpus/CorpusLoader.kt` | Verzeichnis einlesen, beide Quellen zusammenführen |
| `src/main/kotlin/.../metrics/ShotMatching.kt` | Zuordnung erkannter zu echten Treffern |
| `src/main/kotlin/.../metrics/Metrics.kt` | Die vier Kennzahlen |
| `src/main/kotlin/.../metrics/MetricsReport.kt` | Bericht als Markdown |
| `src/test/kotlin/...` | je eine Testdatei pro Klasse |

Paketwurzel: `de.dreier.mytargets.detection.corpus` beziehungsweise `.metrics`.

Die Trennung folgt der Datenrichtung: `corpus` liest Wahrheit ein, `metrics` vergleicht sie mit Ergebnissen. Die beiden Hälften kennen einander nur über `TruthShot` und `SpotPosition`.

---

## Task 1: Modul `:detection-corpus` und die Kerntypen

**Files:**
- Modify: `gradle/libs.versions.toml` (Plugin-Alias `kotlin-jvm`)
- Modify: `settings.gradle` (nach `include ':detection'`)
- Create: `detection-corpus/build.gradle`
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/Score.kt`
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CorpusEntry.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusEntryTest.kt`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `Score` — `@JvmInline value class Score(val text: String)` mit `isMiss: Boolean`, companion `MISS`, `X`, `of(String): Score`, `parseFilenameChar(Char): Score?`
  - `SpotPosition(val faceIndex: Int, val x: Double, val y: Double)` mit `distanceTo(SpotPosition): Double?` (null bei verschiedenen Spots)
  - `TruthShot(val score: Score, val position: SpotPosition? = null)`
  - `CorpusEntry(val imageName: String, val targetModel: String?, val shots: List<TruthShot>, val tags: Set<String>)` mit `hasPositions: Boolean`, `expectedShots: Int`

- [ ] **Step 1: Plugin-Alias in den Versionskatalog eintragen**

In `gradle/libs.versions.toml`, im Abschnitt `[plugins]`, direkt nach der Zeile `kotlin-android = ...`:

```toml
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

Es kommt keine neue Version dazu — `version.ref = "kotlin"` zeigt auf die bereits genutzte.

- [ ] **Step 2: Modul in `settings.gradle` eintragen**

Direkt nach `include ':detection'`:

```groovy
include ':detection-corpus'
```

- [ ] **Step 3: `detection-corpus/build.gradle` anlegen**

```groovy
/*
 * Copyright (C) 2026 MyTargets contributors
 *
 * This file is part of MyTargets.
 *
 * MyTargets is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 *
 * MyTargets is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

// A plain JVM library on purpose: it reads file names and JSON and does
// arithmetic. Keeping Android out means the tests run in milliseconds and the
// measuring apparatus can be finished before there is a detector to measure.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation libs.kotlin.stdlib.jdk7
    implementation libs.gson

    testImplementation libs.junit4
    testImplementation libs.truth
}
```

- [ ] **Step 4: Sync prüfen**

Run: `./gradlew :detection-corpus:tasks --all -q`
Expected: Läuft durch, in der Liste steht `test`.

- [ ] **Step 5: Den fehlschlagenden Test schreiben**

`detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusEntryTest.kt`:

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CorpusEntryTest {

    @Test
    fun scoreCharactersMapToPrintedValues() {
        assertThat(Score.parseFilenameChar('x')).isEqualTo(Score.X)
        assertThat(Score.parseFilenameChar('X')).isEqualTo(Score.X)
        assertThat(Score.parseFilenameChar('9')).isEqualTo(Score.of("9"))
        assertThat(Score.parseFilenameChar('1')).isEqualTo(Score.of("1"))
        assertThat(Score.parseFilenameChar('m')).isEqualTo(Score.MISS)
    }

    @Test
    fun zeroAndUnknownCharactersAreRejected() {
        // '0' would be ambiguous: a miss is written 'm', and a plain ten has no
        // character in the inherited scheme at all.
        assertThat(Score.parseFilenameChar('0')).isNull()
        assertThat(Score.parseFilenameChar('!')).isNull()
    }

    @Test
    fun missIsRecognised() {
        assertThat(Score.MISS.isMiss).isTrue()
        assertThat(Score.X.isMiss).isFalse()
        assertThat(Score.of("7").isMiss).isFalse()
    }

    @Test
    fun distanceIsOnlyDefinedWithinOneSpot() {
        val a = SpotPosition(faceIndex = 0, x = 0.0, y = 0.0)
        val b = SpotPosition(faceIndex = 0, x = 3.0, y = 4.0)
        val other = SpotPosition(faceIndex = 1, x = 3.0, y = 4.0)

        assertThat(a.distanceTo(b)!!).isWithin(1e-9).of(5.0)
        assertThat(a.distanceTo(other)).isNull()
    }

    @Test
    fun anEntryKnowsWhetherItCarriesPositions() {
        val ringsOnly = CorpusEntry(
            imageName = "a6_998877.jpg",
            targetModel = null,
            shots = listOf(TruthShot(Score.of("9")), TruthShot(Score.of("8"))),
            tags = emptySet()
        )
        assertThat(ringsOnly.hasPositions).isFalse()
        assertThat(ringsOnly.expectedShots).isEqualTo(2)

        val annotated = ringsOnly.copy(
            shots = listOf(
                TruthShot(Score.of("9"), SpotPosition(0, 0.1, 0.1)),
                TruthShot(Score.of("8"), SpotPosition(0, 0.3, 0.0))
            )
        )
        assertThat(annotated.hasPositions).isTrue()
    }

    @Test
    fun anEntryWithSomePositionsCountsAsRingsOnly() {
        // A half annotated entry would make the position error depend on which
        // arrows happened to be annotated. Either all of them or none.
        val half = CorpusEntry(
            imageName = "half.jpg",
            targetModel = null,
            shots = listOf(
                TruthShot(Score.of("9"), SpotPosition(0, 0.1, 0.1)),
                TruthShot(Score.of("8"))
            ),
            tags = emptySet()
        )
        assertThat(half.hasPositions).isFalse()
    }
}
```

- [ ] **Step 6: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test`
Expected: FAIL — `Unresolved reference: Score`.

- [ ] **Step 7: `Score.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

/**
 * A score as it is printed on the target face: "X", "10", "9" down to "1", and
 * "M" for a miss.
 *
 * Deliberately not the zone index that `Shot.scoringRing` stores. That index
 * means different things on different faces -- on a full face index 0 is the X
 * ring and index 2 the nine, while a six ring face starts its zone list at the
 * five. The corpus holds both kinds, so an index would force this module to
 * know the target models, which live in an Android library. The printed value
 * is unambiguous without them.
 */
@JvmInline
value class Score(val text: String) {

    val isMiss: Boolean
        get() = text == MISS_TEXT

    override fun toString(): String = text

    companion object {
        private const val MISS_TEXT = "M"

        val MISS = Score(MISS_TEXT)
        val X = Score("X")

        fun of(text: String): Score {
            require(text.isNotBlank()) { "a score needs a value" }
            return Score(text)
        }

        /**
         * One character of the inherited file name scheme, where a name like
         * `a6_x99765_noise.jpg` spells six scores as `x`, `9`, `9`, `7`, `6`, `5`.
         *
         * Returns null for anything the scheme does not define. Note that the
         * scheme has no character for a plain ten as distinct from an X, and
         * none for a zero -- a miss is written `m`.
         */
        fun parseFilenameChar(c: Char): Score? = when {
            c == 'x' || c == 'X' -> X
            c == 'm' || c == 'M' -> MISS
            c in '1'..'9' -> Score(c.toString())
            else -> null
        }
    }
}
```

- [ ] **Step 8: `CorpusEntry.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import kotlin.math.hypot

/**
 * A hit in spot local coordinates: the spot's centre is the origin and its
 * outermost ring has radius one, matching what `Shot.x` and `Shot.y` store.
 */
data class SpotPosition(val faceIndex: Int, val x: Double, val y: Double) {

    /** Null when the two positions sit on different spots and are incomparable. */
    fun distanceTo(other: SpotPosition): Double? =
        if (faceIndex != other.faceIndex) null else hypot(x - other.x, y - other.y)
}

/** One arrow of the ground truth, with its position when it is known. */
data class TruthShot(val score: Score, val position: SpotPosition? = null)

/**
 * One photograph and everything known to be true about it.
 *
 * @param targetModel the target model's class name, for example "WAFull".
 *        Null when it has not been recorded; the metrics do not need it, but a
 *        detector does, so an entry without one cannot be run.
 * @param tags free form markers from the file name, such as "dark" or "overlap",
 *        used to break the metrics down by difficulty.
 */
data class CorpusEntry(
    val imageName: String,
    val targetModel: String?,
    val shots: List<TruthShot>,
    val tags: Set<String>
) {
    init {
        require(imageName.isNotBlank()) { "an entry needs an image name" }
        require(shots.isNotEmpty()) { "an entry needs at least one shot" }
    }

    val expectedShots: Int
        get() = shots.size

    /**
     * Whether every shot carries a position. Partial annotation counts as none:
     * otherwise the position error would depend on which arrows happened to be
     * annotated, which is not a property of the detector.
     */
    val hasPositions: Boolean
        get() = shots.all { it.position != null }
}
```

- [ ] **Step 9: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection-corpus:test`
Expected: PASS, 6 Tests.

- [ ] **Step 10: Committen**

```bash
git add gradle/libs.versions.toml settings.gradle detection-corpus/
git commit -m "Add :detection-corpus module with ground truth types"
```

---

## Task 2: Wahrheit aus dem geerbten Dateinamen

Das Schema stammt aus `feature/249_auto_detect_arrows`: `a<Pfeilzahl>_<Punktzahlen>[_<Marker>].jpg`.

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/FilenameTruth.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/FilenameTruthTest.kt`

**Interfaces:**
- Consumes: `Score`, `TruthShot`, `CorpusEntry` (Task 1)
- Produces: `FilenameTruth.parse(fileName: String): CorpusEntry?` — null, wenn der Name dem Schema nicht folgt

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilenameTruthTest {

    private fun scoresOf(entry: CorpusEntry) = entry.shots.map { it.score.text }

    @Test
    fun parsesSixArrowsWithoutATag() {
        val entry = FilenameTruth.parse("a6_998877.jpg")!!
        assertThat(entry.imageName).isEqualTo("a6_998877.jpg")
        assertThat(entry.expectedShots).isEqualTo(6)
        assertThat(scoresOf(entry)).containsExactly("9", "9", "8", "8", "7", "7").inOrder()
        assertThat(entry.tags).isEmpty()
        assertThat(entry.hasPositions).isFalse()
        assertThat(entry.targetModel).isNull()
    }

    @Test
    fun parsesXRingsAndATag() {
        val entry = FilenameTruth.parse("a6_x99765_noise.jpg")!!
        assertThat(scoresOf(entry)).containsExactly("X", "9", "9", "7", "6", "5").inOrder()
        assertThat(entry.tags).containsExactly("noise")
    }

    @Test
    fun parsesEightArrowsAndAMultiWordTag() {
        val entry = FilenameTruth.parse("a8_xxx99988_front.jpg")!!
        assertThat(entry.expectedShots).isEqualTo(8)
        assertThat(scoresOf(entry))
            .containsExactly("X", "X", "X", "9", "9", "9", "8", "8").inOrder()
        assertThat(entry.tags).containsExactly("front")
    }

    @Test
    fun parsesAnUnderscoredTag() {
        val entry = FilenameTruth.parse("a6_x99999_multiple_targets.jpg")!!
        assertThat(entry.expectedShots).isEqualTo(6)
        assertThat(entry.tags).containsExactly("multiple_targets")
    }

    @Test
    fun rejectsAMismatchBetweenTheCountAndTheScores() {
        // Says six arrows, spells five scores.
        assertThat(FilenameTruth.parse("a6_99887.jpg")).isNull()
    }

    @Test
    fun rejectsNamesOutsideTheScheme() {
        assertThat(FilenameTruth.parse("IMG_20260909.jpg")).isNull()
        assertThat(FilenameTruth.parse("a6.jpg")).isNull()
        assertThat(FilenameTruth.parse("b6_998877.jpg")).isNull()
        assertThat(FilenameTruth.parse("a6_9908877.jpg")).isNull()
    }

    @Test
    fun acceptsAnyImageExtensionAndIsCaseInsensitiveOnIt() {
        assertThat(FilenameTruth.parse("a6_998877.JPG")).isNotNull()
        assertThat(FilenameTruth.parse("a6_998877.png")).isNotNull()
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*FilenameTruthTest*"`
Expected: FAIL — `Unresolved reference: FilenameTruth`.

- [ ] **Step 3: `FilenameTruth.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

/**
 * Ground truth read straight out of a file name.
 *
 * The scheme comes from the 2017 prototype branch
 * `feature/249_auto_detect_arrows` and looks like
 *
 *     a<arrow count>_<one character per score>[_<tag>].<extension>
 *
 * so `a6_x99765_noise.jpg` is six arrows scoring X, 9, 9, 7, 6, 5 on a noisy
 * photograph. The scheme carries no positions and no target model; both have to
 * come from a sidecar if they are wanted.
 */
object FilenameTruth {

    private val PATTERN = Regex(
        "^a(\\d+)_([A-Za-z0-9]+?)(?:_([A-Za-z0-9_]+))?\\.[A-Za-z0-9]+$"
    )

    /** Null when [fileName] does not follow the scheme, or contradicts itself. */
    fun parse(fileName: String): CorpusEntry? {
        val match = PATTERN.matchEntire(fileName) ?: return null

        val declaredCount = match.groupValues[1].toIntOrNull() ?: return null
        if (declaredCount <= 0) return null

        val scoreText = match.groupValues[2]
        if (scoreText.length != declaredCount) return null

        val shots = scoreText.map { c ->
            val score = Score.parseFilenameChar(c) ?: return null
            TruthShot(score)
        }

        val tag = match.groupValues[3]
        return CorpusEntry(
            imageName = fileName,
            targetModel = null,
            shots = shots,
            tags = if (tag.isEmpty()) emptySet() else setOf(tag)
        )
    }
}
```

Hinweis zum regulären Ausdruck: Die Punktzahlgruppe ist absichtlich genügsam (`+?`), damit ein angehängter Marker nicht in sie hineinläuft. Der Längenvergleich gegen die angekündigte Pfeilzahl fängt ab, was dann noch schiefgehen kann.

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*FilenameTruthTest*"`
Expected: PASS, 7 Tests.

Schlägt `rejectsNamesOutsideTheScheme` beim Fall `a6_9908877.jpg` fehl, ist die Längenprüfung übersprungen worden: Der Name kündigt sechs Pfeile an und schreibt sieben Zeichen.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/FilenameTruth.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/FilenameTruthTest.kt
git commit -m "Read inherited ground truth from the file name scheme"
```

---

## Task 3: Wahrheit aus dem JSON-Beiblatt

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruth.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruthTest.kt`

**Interfaces:**
- Consumes: `Score`, `SpotPosition`, `TruthShot`, `CorpusEntry` (Task 1)
- Produces:
  - `SidecarTruth.parse(imageName: String, json: String): CorpusEntry` — wirft `IllegalArgumentException` mit lesbarer Meldung bei fehlerhaftem Inhalt
  - `SidecarTruth.defaultsTargetModel(json: String): String?` für die Verzeichnisvorgabe

Das Format, absichtlich flach:

```json
{
  "targetModel": "WAFull",
  "tags": ["oblique"],
  "shots": [
    { "score": "X", "faceIndex": 0, "x": 0.031, "y": -0.017 },
    { "score": "9", "faceIndex": 0, "x": -0.142, "y": 0.088 },
    { "score": "M" }
  ]
}
```

`faceIndex`, `x` und `y` gehören zusammen: entweder alle drei oder keins.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SidecarTruthTest {

    @Test
    fun parsesAFullyAnnotatedEntry() {
        val json = """
            {
              "targetModel": "WAFull",
              "tags": ["oblique", "sun"],
              "shots": [
                { "score": "X", "faceIndex": 0, "x": 0.031, "y": -0.017 },
                { "score": "9", "faceIndex": 0, "x": -0.142, "y": 0.088 }
              ]
            }
        """.trimIndent()

        val entry = SidecarTruth.parse("shot.jpg", json)

        assertThat(entry.imageName).isEqualTo("shot.jpg")
        assertThat(entry.targetModel).isEqualTo("WAFull")
        assertThat(entry.tags).containsExactly("oblique", "sun")
        assertThat(entry.hasPositions).isTrue()
        assertThat(entry.shots[0].score.text).isEqualTo("X")
        assertThat(entry.shots[0].position!!.x).isWithin(1e-9).of(0.031)
        assertThat(entry.shots[1].position!!.y).isWithin(1e-9).of(0.088)
    }

    @Test
    fun parsesAnEntryWithScoresOnly() {
        val json = """{ "shots": [ { "score": "9" }, { "score": "M" } ] }"""
        val entry = SidecarTruth.parse("rings.jpg", json)

        assertThat(entry.hasPositions).isFalse()
        assertThat(entry.targetModel).isNull()
        assertThat(entry.tags).isEmpty()
        assertThat(entry.shots[1].score.isMiss).isTrue()
    }

    @Test
    fun defaultsFaceIndexToZeroWhenOnlyCoordinatesAreGiven() {
        val json = """{ "shots": [ { "score": "9", "x": 0.2, "y": 0.1 } ] }"""
        val entry = SidecarTruth.parse("single.jpg", json)
        assertThat(entry.shots[0].position!!.faceIndex).isEqualTo(0)
    }

    @Test
    fun rejectsAHalfGivenPosition() {
        val json = """{ "shots": [ { "score": "9", "x": 0.2 } ] }"""
        val error = runCatching { SidecarTruth.parse("bad.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("bad.jpg")
        assertThat(error).hasMessageThat().contains("x")
    }

    @Test
    fun rejectsAnEmptyShotList() {
        val error = runCatching {
            SidecarTruth.parse("empty.jpg", """{ "shots": [] }""")
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("empty.jpg")
    }

    @Test
    fun rejectsAMissingScore() {
        val error = runCatching {
            SidecarTruth.parse("noscore.jpg", """{ "shots": [ { "x": 0.1, "y": 0.1 } ] }""")
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("score")
    }

    @Test
    fun rejectsMalformedJson() {
        val error = runCatching { SidecarTruth.parse("broken.jpg", "{ not json") }
            .exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("broken.jpg")
    }

    @Test
    fun readsTheDirectoryDefault() {
        assertThat(SidecarTruth.defaultsTargetModel("""{ "targetModel": "WA6Ring" }"""))
            .isEqualTo("WA6Ring")
        assertThat(SidecarTruth.defaultsTargetModel("{}")).isNull()
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*SidecarTruthTest*"`
Expected: FAIL — `Unresolved reference: SidecarTruth`.

- [ ] **Step 3: `SidecarTruth.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Ground truth read from a JSON sidecar next to the image.
 *
 * The format is deliberately flat, because it is written by hand while
 * annotating photographs:
 *
 *     {
 *       "targetModel": "WAFull",
 *       "tags": ["oblique"],
 *       "shots": [
 *         { "score": "X", "faceIndex": 0, "x": 0.031, "y": -0.017 },
 *         { "score": "M" }
 *       ]
 *     }
 *
 * Coordinates are spot local, so the spot centre is the origin and its
 * outermost ring has radius one. `faceIndex`, `x` and `y` belong together:
 * either all three or none, with `faceIndex` defaulting to 0 for a single spot
 * face.
 */
object SidecarTruth {

    private val gson = Gson()

    private class ShotJson {
        var score: String? = null
        var faceIndex: Int? = null
        var x: Double? = null
        var y: Double? = null
    }

    private class EntryJson {
        var targetModel: String? = null
        var tags: List<String>? = null
        var shots: List<ShotJson>? = null
    }

    private class DefaultsJson {
        var targetModel: String? = null
    }

    /**
     * @throws IllegalArgumentException with the image name in the message, so a
     *         corpus of a hundred photographs still says which one is broken.
     */
    fun parse(imageName: String, json: String): CorpusEntry {
        val parsed = try {
            gson.fromJson(json, EntryJson::class.java)
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("$imageName: malformed JSON sidecar", e)
        } ?: throw IllegalArgumentException("$imageName: empty JSON sidecar")

        val shotsJson = parsed.shots
            ?: throw IllegalArgumentException("$imageName: sidecar has no shots")
        require(shotsJson.isNotEmpty()) { "$imageName: sidecar has an empty shot list" }

        val shots = shotsJson.mapIndexed { index, shot ->
            val scoreText = shot.score
                ?: throw IllegalArgumentException("$imageName: shot $index has no score")

            val hasX = shot.x != null
            val hasY = shot.y != null
            require(hasX == hasY) {
                "$imageName: shot $index gives only one of x and y; " +
                    "a position needs both"
            }

            val position = if (hasX) {
                SpotPosition(shot.faceIndex ?: 0, shot.x!!, shot.y!!)
            } else {
                require(shot.faceIndex == null) {
                    "$imageName: shot $index gives faceIndex without x and y"
                }
                null
            }

            TruthShot(Score.of(scoreText), position)
        }

        return CorpusEntry(
            imageName = imageName,
            targetModel = parsed.targetModel,
            shots = shots,
            tags = parsed.tags?.toSet() ?: emptySet()
        )
    }

    /** The `targetModel` from a directory level defaults file, or null. */
    fun defaultsTargetModel(json: String): String? = try {
        gson.fromJson(json, DefaultsJson::class.java)?.targetModel
    } catch (e: JsonSyntaxException) {
        throw IllegalArgumentException("malformed defaults.json", e)
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*SidecarTruthTest*"`
Expected: PASS, 8 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruth.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/SidecarTruthTest.kt
git commit -m "Read ground truth from JSON sidecars"
```

---

## Task 4: Korpusverzeichnis einlesen

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoader.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoaderTest.kt`

**Interfaces:**
- Consumes: `FilenameTruth` (Task 2), `SidecarTruth` (Task 3), `CorpusEntry` (Task 1)
- Produces:
  - `CorpusLoader.LoadResult(val entries: List<CorpusEntry>, val ignored: List<String>)`
  - `CorpusLoader.load(root: java.io.File): LoadResult`

Regeln, in dieser Reihenfolge:
1. Ein Beiblatt `<bild>.json` gewinnt immer über den Dateinamen.
2. Ohne Beiblatt greift das Namensschema.
3. Passt beides nicht, landet die Datei in `ignored` — der Lauf bricht nicht ab.
4. Ein `defaults.json` je Verzeichnis füllt `targetModel`, wo der Eintrag keines hat.
5. Unterverzeichnisse werden mitgelesen; `defaults.json` gilt für sein eigenes Verzeichnis.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

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

    @Test
    fun readsTruthFromFileNames() {
        write("a6_998877.jpg")
        write("a8_xxx99988_front.jpg")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName })
            .containsExactly("a6_998877.jpg", "a8_xxx99988_front.jpg")
        assertThat(result.ignored).isEmpty()
    }

    @Test
    fun aSidecarWinsOverTheFileName() {
        write("a6_998877.jpg")
        write(
            "a6_998877.jpg.json",
            """{ "targetModel": "WAFull",
                 "shots": [ { "score": "X", "x": 0.0, "y": 0.0 } ] }"""
        )

        val result = CorpusLoader.load(folder.root)
        val entry = result.entries.single()

        assertThat(entry.expectedShots).isEqualTo(1)
        assertThat(entry.shots[0].score.text).isEqualTo("X")
        assertThat(entry.hasPositions).isTrue()
        assertThat(entry.targetModel).isEqualTo("WAFull")
    }

    @Test
    fun theDirectoryDefaultFillsAMissingTargetModel() {
        write("defaults.json", """{ "targetModel": "WA6Ring" }""")
        write("a6_998877.jpg")
        write("a6_x99976.jpg")
        write(
            "a6_x99976.jpg.json",
            """{ "targetModel": "WAFull", "shots": [ { "score": "X" } ] }"""
        )

        val entries = CorpusLoader.load(folder.root).entries.associateBy { it.imageName }

        // Taken from the directory default.
        assertThat(entries["a6_998877.jpg"]!!.targetModel).isEqualTo("WA6Ring")
        // The sidecar states its own and keeps it.
        assertThat(entries["a6_x99976.jpg"]!!.targetModel).isEqualTo("WAFull")
    }

    @Test
    fun unrecognisedFilesAreReportedRatherThanFatal() {
        write("a6_998877.jpg")
        write("holiday.jpg")
        write("notes.txt")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries).hasSize(1)
        assertThat(result.ignored).containsExactly("holiday.jpg")
    }

    @Test
    fun subdirectoriesAreReadAndKeepTheirOwnDefaults() {
        write("defaults.json", """{ "targetModel": "WAFull" }""")
        write("a6_998877.jpg")
        write("inherited-249/defaults.json", """{ "targetModel": "WA6Ring" }""")
        write("inherited-249/a6_x99976.jpg")

        val entries = CorpusLoader.load(folder.root).entries.associateBy { it.imageName }

        assertThat(entries).hasSize(2)
        assertThat(entries["a6_998877.jpg"]!!.targetModel).isEqualTo("WAFull")
        assertThat(entries["a6_x99976.jpg"]!!.targetModel).isEqualTo("WA6Ring")
    }

    @Test
    fun aBrokenSidecarNamesItsFile() {
        write("a6_998877.jpg")
        write("a6_998877.jpg.json", "{ not json")

        val error = runCatching { CorpusLoader.load(folder.root) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("a6_998877.jpg")
    }

    @Test
    fun aMissingRootYieldsAnEmptyResultRatherThanAnError() {
        val absent = File(folder.root, "nope")
        val result = CorpusLoader.load(absent)
        assertThat(result.entries).isEmpty()
        assertThat(result.ignored).isEmpty()
    }

    @Test
    fun entriesComeBackInAStableOrder() {
        write("a6_x99976.jpg")
        write("a6_998877.jpg")
        write("a8_xxx99988_front.jpg")

        val names = CorpusLoader.load(folder.root).entries.map { it.imageName }
        assertThat(names).isInOrder()
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*CorpusLoaderTest*"`
Expected: FAIL — `Unresolved reference: CorpusLoader`.

- [ ] **Step 3: `CorpusLoader.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.corpus

import java.io.File

/**
 * Reads a corpus directory into entries.
 *
 * A photograph gets its truth from a sidecar `<image>.json` when one exists,
 * and otherwise from the inherited file name scheme. Anything neither can read
 * is reported in [LoadResult.ignored] rather than aborting the run: a corpus
 * grows by dropping photographs into a folder, and one holiday snap should not
 * stop a measurement.
 *
 * A broken sidecar IS fatal, because it means someone tried to state the truth
 * and got it wrong. Silently ignoring that would quietly drop an annotated
 * photograph out of the metrics.
 */
object CorpusLoader {

    private const val DEFAULTS_FILE = "defaults.json"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

    class LoadResult(val entries: List<CorpusEntry>, val ignored: List<String>)

    fun load(root: File): LoadResult {
        if (!root.isDirectory) {
            return LoadResult(emptyList(), emptyList())
        }
        val entries = mutableListOf<CorpusEntry>()
        val ignored = mutableListOf<String>()
        loadDirectory(root, entries, ignored)
        return LoadResult(
            entries.sortedBy { it.imageName },
            ignored.sorted()
        )
    }

    private fun loadDirectory(
        directory: File,
        entries: MutableList<CorpusEntry>,
        ignored: MutableList<String>
    ) {
        val children = directory.listFiles() ?: return

        val defaultsFile = File(directory, DEFAULTS_FILE)
        val defaultModel = if (defaultsFile.isFile) {
            SidecarTruth.defaultsTargetModel(defaultsFile.readText())
        } else {
            null
        }

        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                loadDirectory(child, entries, ignored)
                continue
            }
            if (!child.isImage()) {
                continue
            }

            val sidecar = File(directory, child.name + ".json")
            val entry = if (sidecar.isFile) {
                SidecarTruth.parse(child.name, sidecar.readText())
            } else {
                FilenameTruth.parse(child.name)
            }

            if (entry == null) {
                ignored.add(child.name)
            } else {
                entries.add(
                    if (entry.targetModel == null && defaultModel != null) {
                        entry.copy(targetModel = defaultModel)
                    } else {
                        entry
                    }
                )
            }
        }
    }

    private fun File.isImage(): Boolean =
        extension.lowercase() in IMAGE_EXTENSIONS
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*CorpusLoaderTest*"`
Expected: PASS, 8 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoader.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/CorpusLoaderTest.kt
git commit -m "Load a corpus directory from sidecars and file names"
```

---

## Task 5: Erkannte Treffer den echten zuordnen

Die Zuordnung ist der Kern der Messung. Ohne sie ist „ein Treffer wurde gefunden" nicht definiert.

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ShotMatching.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ShotMatchingTest.kt`

**Interfaces:**
- Consumes: `Score`, `SpotPosition`, `TruthShot`, `CorpusEntry` (Task 1)
- Produces:
  - `DetectedShotRecord(val score: Score, val position: SpotPosition, val confidence: Double)`
  - `MatchedPair(val truthIndex: Int, val detectedIndex: Int, val distance: Double?)`
  - `MatchResult(val pairs: List<MatchedPair>, val unmatchedTruth: List<Int>, val unmatchedDetected: List<Int>)`
  - `ShotMatching.match(entry: CorpusEntry, detected: List<DetectedShotRecord>, positionGate: Double = DEFAULT_POSITION_GATE): MatchResult`
  - `ShotMatching.DEFAULT_POSITION_GATE = 0.05`

Zwei Verfahren, je nach Wahrheit:

- **Mit Positionen:** Alle Paare nach Abstand aufsteigend, gierig übernehmen, solange beide Seiten frei sind und der Abstand das Tor unterschreitet. Paare über verschiedene Spots sind unzulässig.
- **Nur Ringwerte:** Zuordnung über die Punktzahl als Multimenge, in der Reihenfolge absteigender Konfidenz. Der Abstand des Paares ist dann `null`.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.metrics

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.Score
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class ShotMatchingTest {

    private fun entry(vararg shots: TruthShot) = CorpusEntry(
        imageName = "t.jpg", targetModel = "WAFull",
        shots = shots.toList(), tags = emptySet()
    )

    private fun truth(score: String, x: Double, y: Double, face: Int = 0) =
        TruthShot(Score.of(score), SpotPosition(face, x, y))

    private fun found(score: String, x: Double, y: Double, face: Int = 0, conf: Double = 0.9) =
        DetectedShotRecord(Score.of(score), SpotPosition(face, x, y), conf)

    @Test
    fun pairsNearestPositionsFirst() {
        val e = entry(truth("X", 0.0, 0.0), truth("9", 0.5, 0.0))
        val detected = listOf(found("9", 0.49, 0.0), found("X", 0.01, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(2)
        assertThat(result.unmatchedTruth).isEmpty()
        assertThat(result.unmatchedDetected).isEmpty()
        val byTruth = result.pairs.associateBy { it.truthIndex }
        assertThat(byTruth[0]!!.detectedIndex).isEqualTo(1)
        assertThat(byTruth[1]!!.detectedIndex).isEqualTo(0)
        assertThat(byTruth[0]!!.distance!!).isWithin(1e-9).of(0.01)
    }

    @Test
    fun aDetectionBeyondTheGateIsNotAMatch() {
        val e = entry(truth("X", 0.0, 0.0))
        val detected = listOf(found("X", 0.5, 0.0))

        val result = ShotMatching.match(e, detected, positionGate = 0.05)

        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0)
        assertThat(result.unmatchedDetected).containsExactly(0)
    }

    @Test
    fun positionsOnDifferentSpotsNeverPair() {
        val e = entry(truth("X", 0.0, 0.0, face = 0))
        val detected = listOf(found("X", 0.0, 0.0, face = 1))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0)
    }

    @Test
    fun aSurplusDetectionStaysUnmatched() {
        val e = entry(truth("X", 0.0, 0.0))
        val detected = listOf(found("X", 0.0, 0.0), found("9", 0.4, 0.4))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.unmatchedDetected).containsExactly(1)
    }

    @Test
    fun ringOnlyTruthPairsByScore() {
        val e = entry(
            TruthShot(Score.of("9")),
            TruthShot(Score.of("9")),
            TruthShot(Score.of("7"))
        )
        val detected = listOf(
            found("7", 0.6, 0.0, conf = 0.5),
            found("9", 0.2, 0.0, conf = 0.9),
            found("8", 0.4, 0.0, conf = 0.7)
        )

        val result = ShotMatching.match(e, detected)

        // Two of the three detections carry a score the truth contains.
        assertThat(result.pairs).hasSize(2)
        assertThat(result.pairs.all { it.distance == null }).isTrue()
        // The eight matches nothing; one of the two nines stays unmatched.
        assertThat(result.unmatchedDetected).containsExactly(2)
        assertThat(result.unmatchedTruth).hasSize(1)
    }

    @Test
    fun ringOnlyMatchingPrefersConfidentDetections() {
        val e = entry(TruthShot(Score.of("9")))
        val detected = listOf(
            found("9", 0.0, 0.0, conf = 0.2),
            found("9", 0.3, 0.0, conf = 0.8)
        )

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].detectedIndex).isEqualTo(1)
    }

    @Test
    fun noDetectionsLeavesEveryTruthUnmatched() {
        val e = entry(truth("X", 0.0, 0.0), truth("9", 0.5, 0.0))
        val result = ShotMatching.match(e, emptyList())
        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0, 1).inOrder()
    }

    @Test
    fun theGreedyChoiceIsGloballyNearestFirst() {
        // Truth A at 0.00, truth B at 0.03. One detection at 0.02.
        // Nearest first pairs it with B at 0.01, not with A at 0.02.
        val e = entry(truth("9", 0.0, 0.0), truth("9", 0.03, 0.0))
        val detected = listOf(found("9", 0.02, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].truthIndex).isEqualTo(1)
        assertThat(result.pairs[0].distance!!).isWithin(1e-9).of(0.01)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*ShotMatchingTest*"`
Expected: FAIL — `Unresolved reference: DetectedShotRecord`.

- [ ] **Step 3: `ShotMatching.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.metrics

import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.Score
import de.dreier.mytargets.detection.corpus.SpotPosition

/**
 * One arrow a detector claims to have found. Unlike a [de.dreier.mytargets
 * .detection.corpus.TruthShot] it always carries a position — a detector that
 * cannot say where an arrow is has not detected it.
 */
data class DetectedShotRecord(
    val score: Score,
    val position: SpotPosition,
    val confidence: Double
)

/** A truth shot and the detection assigned to it. [distance] is null for
 *  ring only truth, where no position is known to compare against. */
data class MatchedPair(
    val truthIndex: Int,
    val detectedIndex: Int,
    val distance: Double?
)

class MatchResult(
    val pairs: List<MatchedPair>,
    val unmatchedTruth: List<Int>,
    val unmatchedDetected: List<Int>
)

/**
 * Assigns detected arrows to true ones, which is what makes "found" mean
 * anything at all.
 *
 * With positions the assignment is greedy over globally nearest pairs: sort
 * every admissible pair by distance and take them while both sides are still
 * free. That is the usual choice in detection benchmarks and it is
 * deterministic, which matters more here than optimality — an end holds a
 * handful of arrows, and a pathological case where greedy loses to an optimal
 * assignment needs two arrows closer to each other than to their own truth,
 * which is already a detection failure.
 *
 * Without positions, matching falls back to the score as a multiset, taking the
 * most confident detection for each true score. Such an entry can support the
 * detection rate, the false positive count and the ring accuracy, but not the
 * position error.
 */
object ShotMatching {

    /**
     * How far a detection may sit from its true position and still count as the
     * same arrow, in spot radii. 0.05 of an 80 cm face is 2 cm, roughly two
     * arrow diameters. This is one of the values the corpus is meant to settle;
     * until it has, it is an informed guess.
     */
    const val DEFAULT_POSITION_GATE = 0.05

    fun match(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        positionGate: Double = DEFAULT_POSITION_GATE
    ): MatchResult = if (entry.hasPositions) {
        matchByPosition(entry, detected, positionGate)
    } else {
        matchByScore(entry, detected)
    }

    private fun matchByPosition(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        positionGate: Double
    ): MatchResult {
        val candidates = mutableListOf<MatchedPair>()
        entry.shots.forEachIndexed { truthIndex, truth ->
            val truthPosition = truth.position ?: return@forEachIndexed
            detected.forEachIndexed { detectedIndex, record ->
                val distance = truthPosition.distanceTo(record.position)
                if (distance != null && distance <= positionGate) {
                    candidates.add(MatchedPair(truthIndex, detectedIndex, distance))
                }
            }
        }

        // Nearest first, with the indices as a tie break so the result does not
        // depend on the order the candidates happened to be built in.
        candidates.sortWith(
            compareBy({ it.distance }, { it.truthIndex }, { it.detectedIndex })
        )

        return takeGreedily(candidates, entry.shots.size, detected.size)
    }

    private fun matchByScore(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>
    ): MatchResult {
        val byConfidence = detected.indices.sortedWith(
            compareByDescending<Int> { detected[it].confidence }.thenBy { it }
        )

        val remainingTruth = entry.shots.indices.toMutableList()
        val pairs = mutableListOf<MatchedPair>()

        for (detectedIndex in byConfidence) {
            val score = detected[detectedIndex].score
            val hit = remainingTruth.firstOrNull { entry.shots[it].score == score }
            if (hit != null) {
                remainingTruth.remove(hit)
                pairs.add(MatchedPair(hit, detectedIndex, null))
            }
        }

        val matchedDetected = pairs.map { it.detectedIndex }.toSet()
        return MatchResult(
            pairs = pairs.sortedBy { it.truthIndex },
            unmatchedTruth = remainingTruth.sorted(),
            unmatchedDetected = detected.indices.filterNot { it in matchedDetected }
        )
    }

    private fun takeGreedily(
        candidates: List<MatchedPair>,
        truthCount: Int,
        detectedCount: Int
    ): MatchResult {
        val usedTruth = mutableSetOf<Int>()
        val usedDetected = mutableSetOf<Int>()
        val pairs = mutableListOf<MatchedPair>()

        for (candidate in candidates) {
            if (candidate.truthIndex in usedTruth) continue
            if (candidate.detectedIndex in usedDetected) continue
            usedTruth.add(candidate.truthIndex)
            usedDetected.add(candidate.detectedIndex)
            pairs.add(candidate)
        }

        return MatchResult(
            pairs = pairs.sortedBy { it.truthIndex },
            unmatchedTruth = (0 until truthCount).filterNot { it in usedTruth },
            unmatchedDetected = (0 until detectedCount).filterNot { it in usedDetected }
        )
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*ShotMatchingTest*"`
Expected: PASS, 8 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/ShotMatching.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/ShotMatchingTest.kt
git commit -m "Assign detected arrows to true ones by position or by score"
```

---

## Task 6: Die vier Kennzahlen

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/Metrics.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/MetricsTest.kt`

**Interfaces:**
- Consumes: `CorpusEntry` (Task 1), `ShotMatching`, `DetectedShotRecord`, `MatchResult` (Task 5)
- Produces:
  - `EntryOutcome(val entry: CorpusEntry, val match: MatchResult, val detected: List<DetectedShotRecord>)`
  - `Metrics(val expectedShots: Int, val matchedShots: Int, val falsePositives: Int, val correctScores: Int, val positionErrors: List<Double>)` mit `detectionRate: Double`, `falsePositiveRate: Double`, `scoreAccuracy: Double`, `medianPositionError: Double?`, `p95PositionError: Double?`, `entriesWithPositions: Int`
  - `Metrics.over(outcomes: List<EntryOutcome>): Metrics`
  - `Metrics.byTag(outcomes: List<EntryOutcome>): Map<String, Metrics>`

Definitionen, alle über den ganzen Korpus summiert:

| Kennzahl | Definition |
|---|---|
| Erkennungsrate | zugeordnete Treffer / erwartete Treffer |
| Falsch-Positive | nicht zugeordnete Erkennungen / erwartete Treffer |
| Ringtreue | zugeordnete Paare mit gleicher Punktzahl / erwartete Treffer |
| Positionsfehler | Median und 95. Perzentil über alle Paarabstände, nur aus Einträgen mit Positionen |

Die Ringtreue misst gegen die **erwarteten** Treffer, nicht gegen die zugeordneten. Sonst würde ein Detektor, der fünf von sechs Pfeilen gar nicht findet, für den einen gefundenen mit 100 % belohnt.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.metrics

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.Score
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class MetricsTest {

    private fun entry(
        name: String,
        tags: Set<String> = emptySet(),
        vararg shots: TruthShot
    ) = CorpusEntry(name, "WAFull", shots.toList(), tags)

    private fun truth(score: String, x: Double, y: Double) =
        TruthShot(Score.of(score), SpotPosition(0, x, y))

    private fun found(score: String, x: Double, y: Double, conf: Double = 0.9) =
        DetectedShotRecord(Score.of(score), SpotPosition(0, x, y), conf)

    private fun outcome(e: CorpusEntry, detected: List<DetectedShotRecord>) =
        EntryOutcome(e, ShotMatching.match(e, detected), detected)

    @Test
    fun aPerfectRunScoresOneEverywhere() {
        val e = entry("p.jpg", shots = arrayOf(truth("X", 0.0, 0.0), truth("9", 0.3, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found("X", 0.0, 0.0), found("9", 0.3, 0.0)))))

        assertThat(m.detectionRate).isWithin(1e-9).of(1.0)
        assertThat(m.falsePositiveRate).isWithin(1e-9).of(0.0)
        assertThat(m.scoreAccuracy).isWithin(1e-9).of(1.0)
        assertThat(m.medianPositionError!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun aMissedArrowLowersDetectionAndScoreAccuracyTogether() {
        val e = entry("m.jpg", shots = arrayOf(truth("X", 0.0, 0.0), truth("9", 0.3, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found("X", 0.0, 0.0)))))

        assertThat(m.detectionRate).isWithin(1e-9).of(0.5)
        assertThat(m.scoreAccuracy).isWithin(1e-9).of(0.5)
        assertThat(m.falsePositiveRate).isWithin(1e-9).of(0.0)
    }

    @Test
    fun anInventedArrowRaisesTheFalsePositiveRate() {
        val e = entry("f.jpg", shots = arrayOf(truth("X", 0.0, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found("X", 0.0, 0.0), found("9", 0.8, 0.0)))))

        assertThat(m.detectionRate).isWithin(1e-9).of(1.0)
        assertThat(m.falsePositiveRate).isWithin(1e-9).of(1.0)
    }

    @Test
    fun aMatchedArrowWithTheWrongScoreCountsAsFoundButNotAsCorrect() {
        val e = entry("w.jpg", shots = arrayOf(truth("X", 0.0, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found("9", 0.01, 0.0)))))

        assertThat(m.detectionRate).isWithin(1e-9).of(1.0)
        assertThat(m.scoreAccuracy).isWithin(1e-9).of(0.0)
    }

    @Test
    fun scoreAccuracyIsMeasuredAgainstExpectedNotAgainstFound() {
        // Five of six arrows missed; the one found is scored correctly.
        // Rewarding that with 100 percent would be a lie.
        val shots = Array(6) { truth("9", 0.1 * it, 0.0) }
        val e = entry("s.jpg", shots = shots)
        val m = Metrics.over(listOf(outcome(e, listOf(found("9", 0.0, 0.0)))))

        assertThat(m.scoreAccuracy).isWithin(1e-9).of(1.0 / 6.0)
    }

    @Test
    fun positionErrorsUseTheMedianAndThe95thPercentile() {
        val shots = arrayOf(
            truth("9", 0.0, 0.0), truth("9", 0.2, 0.0),
            truth("9", 0.4, 0.0), truth("9", 0.6, 0.0)
        )
        val e = entry("q.jpg", shots = shots)
        val detected = listOf(
            found("9", 0.000, 0.0),   // error 0.000
            found("9", 0.210, 0.0),   // error 0.010
            found("9", 0.420, 0.0),   // error 0.020
            found("9", 0.630, 0.0)    // error 0.030
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        // Errors are 0, 0.01, 0.02, 0.03. With linear interpolation between
        // order statistics the median sits at rank 0.5 * 3 = 1.5, so
        // 0.01 * 0.5 + 0.02 * 0.5 = 0.015.
        assertThat(m.medianPositionError!!).isWithin(1e-9).of(0.015)
        // The 95th percentile sits at rank 0.95 * 3 = 2.85, so
        // 0.02 * 0.15 + 0.03 * 0.85 = 0.0285.
        assertThat(m.p95PositionError!!).isWithin(1e-9).of(0.0285)
    }

    @Test
    fun ringOnlyEntriesContributeEverythingButThePositionError() {
        val e = entry("r.jpg", shots = arrayOf(TruthShot(Score.of("9")), TruthShot(Score.of("8"))))
        val m = Metrics.over(listOf(outcome(e, listOf(found("9", 0.0, 0.0), found("8", 0.5, 0.0)))))

        assertThat(m.detectionRate).isWithin(1e-9).of(1.0)
        assertThat(m.scoreAccuracy).isWithin(1e-9).of(1.0)
        assertThat(m.entriesWithPositions).isEqualTo(0)
        assertThat(m.medianPositionError).isNull()
        assertThat(m.p95PositionError).isNull()
    }

    @Test
    fun anEmptyCorpusYieldsZerosAndNoPositionError() {
        val m = Metrics.over(emptyList())
        assertThat(m.expectedShots).isEqualTo(0)
        assertThat(m.detectionRate).isWithin(1e-9).of(0.0)
        assertThat(m.scoreAccuracy).isWithin(1e-9).of(0.0)
        assertThat(m.medianPositionError).isNull()
    }

    @Test
    fun tagsSplitTheCorpusIntoComparableGroups() {
        val dark = entry("d.jpg", tags = setOf("dark"), shots = arrayOf(truth("9", 0.0, 0.0)))
        val plain = entry("p.jpg", shots = arrayOf(truth("9", 0.0, 0.0)))

        val byTag = Metrics.byTag(
            listOf(
                outcome(dark, emptyList()),
                outcome(plain, listOf(found("9", 0.0, 0.0)))
            )
        )

        assertThat(byTag.keys).containsExactly("dark", "untagged")
        assertThat(byTag["dark"]!!.detectionRate).isWithin(1e-9).of(0.0)
        assertThat(byTag["untagged"]!!.detectionRate).isWithin(1e-9).of(1.0)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*MetricsTest*"`
Expected: FAIL — `Unresolved reference: EntryOutcome`.

- [ ] **Step 3: `Metrics.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.metrics

import de.dreier.mytargets.detection.corpus.CorpusEntry

/** What a detector produced for one photograph, and how it lined up. */
class EntryOutcome(
    val entry: CorpusEntry,
    val match: MatchResult,
    val detected: List<DetectedShotRecord>
)

/**
 * The four numbers the design spec measures the pipeline by.
 *
 * All rates are relative to the EXPECTED shots, never to the matched ones. A
 * detector that misses five of six arrows and scores the sixth correctly must
 * not come out at a hundred percent ring accuracy.
 */
class Metrics(
    val expectedShots: Int,
    val matchedShots: Int,
    val falsePositives: Int,
    val correctScores: Int,
    val positionErrors: List<Double>,
    val entriesWithPositions: Int
) {

    /** Share of true arrows that were found at all. */
    val detectionRate: Double
        get() = ratio(matchedShots)

    /** Invented arrows per expected arrow. Can exceed one. */
    val falsePositiveRate: Double
        get() = ratio(falsePositives)

    /** Share of true arrows found AND given the right score. The number that
     *  matters to the archer. */
    val scoreAccuracy: Double
        get() = ratio(correctScores)

    /** Null when no entry in the corpus carried positions. */
    val medianPositionError: Double?
        get() = percentile(0.50)

    /** Null when no entry in the corpus carried positions. */
    val p95PositionError: Double?
        get() = percentile(0.95)

    private fun ratio(count: Int): Double =
        if (expectedShots == 0) 0.0 else count.toDouble() / expectedShots

    /** Linear interpolation between order statistics, the common definition. */
    private fun percentile(fraction: Double): Double? {
        if (positionErrors.isEmpty()) return null
        val sorted = positionErrors.sorted()
        if (sorted.size == 1) return sorted[0]
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
            var withPositions = 0
            val errors = mutableListOf<Double>()

            for (outcome in outcomes) {
                expected += outcome.entry.expectedShots
                matched += outcome.match.pairs.size
                falsePositives += outcome.match.unmatchedDetected.size
                if (outcome.entry.hasPositions) {
                    withPositions++
                }
                for (pair in outcome.match.pairs) {
                    val truthShot = outcome.entry.shots[pair.truthIndex]
                    val detectedShot = outcome.detected[pair.detectedIndex]
                    if (truthShot.score == detectedShot.score) {
                        correct++
                    }
                    pair.distance?.let { errors.add(it) }
                }
            }

            return Metrics(expected, matched, falsePositives, correct, errors, withPositions)
        }

        /**
         * The same numbers per tag, so "dark" and "overlap" can be compared
         * against the rest. An entry with several tags counts in each of them;
         * an entry with none lands under [UNTAGGED].
         */
        fun byTag(outcomes: List<EntryOutcome>): Map<String, Metrics> {
            val groups = mutableMapOf<String, MutableList<EntryOutcome>>()
            for (outcome in outcomes) {
                val keys = outcome.entry.tags.ifEmpty { setOf(UNTAGGED) }
                for (key in keys) {
                    groups.getOrPut(key) { mutableListOf() }.add(outcome)
                }
            }
            return groups.toSortedMap().mapValues { (_, group) -> over(group) }
        }

        const val UNTAGGED = "untagged"
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*MetricsTest*"`
Expected: PASS, 9 Tests.

Schlägt `positionErrorsUseTheMedianAndThe95thPercentile` fehl, liegt es an der Perzentildefinition. Die erwarteten Werte oben sind von Hand nachgerechnet: Rang `fraction · (n − 1)`, dann linear zwischen den beiden benachbarten Ordnungsstatistiken interpoliert. Weicht die Implementierung ab, ist sie zu korrigieren, nicht der Test.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/Metrics.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/MetricsTest.kt
git commit -m "Compute the four detection metrics over a corpus"
```

---

## Task 7: Bericht

**Files:**
- Create: `detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/MetricsReport.kt`
- Test: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/MetricsReportTest.kt`

**Interfaces:**
- Consumes: `Metrics`, `EntryOutcome` (Task 6)
- Produces: `MetricsReport.render(outcomes: List<EntryOutcome>, title: String): String`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.metrics

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.Score
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class MetricsReportTest {

    private fun outcome(
        name: String,
        tags: Set<String>,
        truthScores: List<String>,
        detectedScores: List<String>
    ): EntryOutcome {
        val entry = CorpusEntry(
            name, "WAFull",
            truthScores.mapIndexed { i, s ->
                TruthShot(Score.of(s), SpotPosition(0, 0.1 * i, 0.0))
            },
            tags
        )
        val detected = detectedScores.mapIndexed { i, s ->
            DetectedShotRecord(Score.of(s), SpotPosition(0, 0.1 * i, 0.0), 0.9)
        }
        return EntryOutcome(entry, ShotMatching.match(entry, detected), detected)
    }

    @Test
    fun theReportNamesTheFourMetricsAndTheCorpusSize() {
        val report = MetricsReport.render(
            listOf(outcome("a.jpg", emptySet(), listOf("9", "8"), listOf("9", "8"))),
            title = "Baseline"
        )

        assertThat(report).contains("Baseline")
        assertThat(report).contains("Detection rate")
        assertThat(report).contains("False positives")
        assertThat(report).contains("Ring accuracy")
        assertThat(report).contains("Position error")
        assertThat(report).contains("1 photograph")
        assertThat(report).contains("2 arrows")
    }

    @Test
    fun aTagBreakdownAppearsWhenThereAreTags() {
        val report = MetricsReport.render(
            listOf(
                outcome("d.jpg", setOf("dark"), listOf("9"), emptyList()),
                outcome("p.jpg", emptySet(), listOf("9"), listOf("9"))
            ),
            title = "Baseline"
        )

        assertThat(report).contains("dark")
        assertThat(report).contains("untagged")
    }

    @Test
    fun theWorstEntriesAreListedSoTheyCanBeLookedAt() {
        val report = MetricsReport.render(
            listOf(
                outcome("good.jpg", emptySet(), listOf("9"), listOf("9")),
                outcome("bad.jpg", emptySet(), listOf("9", "8", "7"), emptyList())
            ),
            title = "Baseline"
        )

        assertThat(report).contains("bad.jpg")
    }

    @Test
    fun anAbsentPositionErrorIsSaidPlainlyRatherThanShownAsZero() {
        val entry = CorpusEntry(
            "r.jpg", "WAFull", listOf(TruthShot(Score.of("9"))), emptySet()
        )
        val detected = listOf(
            DetectedShotRecord(Score.of("9"), SpotPosition(0, 0.0, 0.0), 0.9)
        )
        val report = MetricsReport.render(
            listOf(EntryOutcome(entry, ShotMatching.match(entry, detected), detected)),
            title = "Rings only"
        )

        assertThat(report).contains("not measured")
        assertThat(report).doesNotContain("Position error | 0.000")
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*MetricsReportTest*"`
Expected: FAIL — `Unresolved reference: MetricsReport`.

- [ ] **Step 3: `MetricsReport.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.metrics

/**
 * Renders a measurement run as Markdown.
 *
 * A number without its corpus is not a result, so the report always states how
 * many photographs and arrows it rests on, breaks the metrics down by tag so
 * "dark" can be compared against the rest, and names the worst entries — those
 * are the ones worth opening in the debug view.
 */
object MetricsReport {

    private const val WORST_ENTRIES = 5

    fun render(outcomes: List<EntryOutcome>, title: String): String {
        val overall = Metrics.over(outcomes)
        val photographs = outcomes.size

        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        sb.appendLine(
            "$photographs ${plural(photographs, "photograph", "photographs")}, " +
                "${overall.expectedShots} ${plural(overall.expectedShots, "arrow", "arrows")}, " +
                "${overall.entriesWithPositions} with annotated positions."
        )
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Detection rate | ${percent(overall.detectionRate)} |")
        sb.appendLine("| False positives | ${percent(overall.falsePositiveRate)} |")
        sb.appendLine("| Ring accuracy | ${percent(overall.scoreAccuracy)} |")
        sb.appendLine("| Position error, median | ${spotRadii(overall.medianPositionError)} |")
        sb.appendLine("| Position error, 95th pct | ${spotRadii(overall.p95PositionError)} |")

        val byTag = Metrics.byTag(outcomes)
        if (byTag.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## By tag")
            sb.appendLine()
            sb.appendLine("| Tag | Arrows | Detection | Ring accuracy |")
            sb.appendLine("|---|---|---|---|")
            for ((tag, metrics) in byTag) {
                sb.appendLine(
                    "| $tag | ${metrics.expectedShots} | " +
                        "${percent(metrics.detectionRate)} | " +
                        "${percent(metrics.scoreAccuracy)} |"
                )
            }
        }

        val worst = outcomes
            .sortedWith(
                compareBy({ Metrics.over(listOf(it)).scoreAccuracy }, { it.entry.imageName })
            )
            .take(WORST_ENTRIES)
        if (worst.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Worst entries")
            sb.appendLine()
            sb.appendLine("| Photograph | Arrows | Found | Correct | Invented |")
            sb.appendLine("|---|---|---|---|---|")
            for (outcome in worst) {
                val m = Metrics.over(listOf(outcome))
                sb.appendLine(
                    "| ${outcome.entry.imageName} | ${m.expectedShots} | " +
                        "${m.matchedShots} | ${m.correctScores} | ${m.falsePositives} |"
                )
            }
        }

        return sb.toString()
    }

    private fun plural(count: Int, one: String, many: String) = if (count == 1) one else many

    // Locale.ROOT on purpose: a report that says "97,3 %" on one machine and
    // "97.3 %" on another cannot be diffed between runs.
    private fun percent(value: Double) =
        String.format(java.util.Locale.ROOT, "%.1f %%", value * 100.0)

    /** Position errors are in spot radii, so a bare number would be ambiguous. */
    private fun spotRadii(value: Double?) =
        if (value == null) {
            "not measured"
        } else {
            String.format(java.util.Locale.ROOT, "%.4f spot radii", value)
        }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection-corpus:test --tests "*MetricsReportTest*"`
Expected: PASS, 4 Tests.

Die Zahlenformatierung nutzt bewusst `Locale.ROOT`: Ein Bericht, der auf einer Maschine „97,3 %" und auf einer anderen „97.3 %" schreibt, lässt sich zwischen Läufen nicht vergleichen.

- [ ] **Step 5: Committen**

```bash
git add detection-corpus/src/main/kotlin/de/dreier/mytargets/detection/metrics/MetricsReport.kt \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/metrics/MetricsReportTest.kt
git commit -m "Render a measurement run as a Markdown report"
```

---

## Task 8: Den echten Korpus anbinden

Der erste Lauf gegen die 16 geerbten Fotos — ohne Detektor, also nur die Wahrheitsseite.

**Files:**
- Modify: `detection-corpus/build.gradle`
- Create: `detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/InheritedCorpusTest.kt`
- Modify: `BUILDING.md` (Abschnitt 4)

**Interfaces:**
- Consumes: `CorpusLoader` (Task 4)
- Produces: nichts für spätere Tasks; der Test ist der Zweck

- [ ] **Step 1: `DETECTION_CORPUS_DIR` an die Tests durchreichen**

In `detection-corpus/build.gradle`, nach dem `dependencies`-Block:

```groovy
// The corpus lives outside the repository -- it is large, it never changes and
// it is not covered by the project's licence. Tests that need it skip
// themselves when the property is absent, so a fresh clone still builds green.
tasks.named('test') {
    def corpusDir = providers.gradleProperty('DETECTION_CORPUS_DIR').orNull
    if (corpusDir != null) {
        systemProperty 'detection.corpus.dir', file(corpusDir).absolutePath
    }
}
```

- [ ] **Step 2: Den Test schreiben, der ohne Korpus überspringt**

```kotlin
package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Reads the real corpus, when there is one.
 *
 * Skips itself when `DETECTION_CORPUS_DIR` is not set, so a fresh clone builds
 * green without a forty megabyte download. See BUILDING.md.
 */
class InheritedCorpusTest {

    private lateinit var root: File

    @Before
    fun requireCorpus() {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)
    }

    @Test
    fun everyInheritedPhotographIsUnderstood() {
        val inherited = File(root, "inherited-249")
        assumeTrue("inherited-249 is not present", inherited.isDirectory)

        val result = CorpusLoader.load(inherited)

        assertThat(result.ignored).isEmpty()
        assertThat(result.entries).hasSize(16)
        assertThat(result.entries.all { it.expectedShots in 6..8 }).isTrue()
        assertThat(result.entries.none { it.hasPositions }).isTrue()
    }

    @Test
    fun theInheritedTagsAreTheExpectedOnes() {
        val inherited = File(root, "inherited-249")
        assumeTrue("inherited-249 is not present", inherited.isDirectory)

        val tags = CorpusLoader.load(inherited).entries.flatMap { it.tags }.toSet()

        assertThat(tags).containsExactly(
            "dark", "noise", "overlap", "front", "multiple_targets"
        )
    }

    @Test
    fun aKnownEntryHasTheScoresItsNamePromises() {
        val inherited = File(root, "inherited-249")
        assumeTrue("inherited-249 is not present", inherited.isDirectory)

        val entry = CorpusLoader.load(inherited).entries
            .single { it.imageName == "a8_xxx99988_front.jpg" }

        assertThat(entry.shots.map { it.score.text })
            .containsExactly("X", "X", "X", "9", "9", "9", "8", "8").inOrder()
        assertThat(entry.tags).containsExactly("front")
    }
}
```

- [ ] **Step 3: Ohne Korpus laufen lassen**

Run: `./gradlew :detection-corpus:test --tests "*InheritedCorpusTest*"`
Expected: PASS — die Tests überspringen sich, wenn `DETECTION_CORPUS_DIR` nicht gesetzt ist. In der Ausgabe erscheinen sie als übersprungen, nicht als fehlgeschlagen.

- [ ] **Step 4: Mit Korpus laufen lassen**

Setze in `gradle-local.properties`:

```properties
DETECTION_CORPUS_DIR=../MyTargets-corpus
```

Run: `./gradlew :detection-corpus:test --tests "*InheritedCorpusTest*"`
Expected: PASS, 3 Tests tatsächlich ausgeführt.

Schlägt `everyInheritedPhotographIsUnderstood` mit einer nicht leeren `ignored`-Liste fehl, nennt die Meldung die Datei; prüfe deren Namen gegen das Schema in Task 2.

- [ ] **Step 5: `BUILDING.md` ergänzen**

Im Abschnitt zum Foto-Korpus, nach dem Beispiel für `gradle-local.properties`, einfügen:

```markdown
The corpus is read by `:detection-corpus`. Without the property its tests skip
themselves, so a fresh clone builds green. The 16 photographs under
`inherited-249/` come from the 2017 prototype branch and carry their ground
truth in their file names; see the design document for the scheme.
```

- [ ] **Step 6: Alle Tests des Moduls laufen lassen**

Run: `./gradlew :detection-corpus:test`
Expected: PASS, 53 Tests insgesamt (6 + 7 + 8 + 8 + 8 + 9 + 4 + 3). Mit gesetztem Korpuspfad laufen alle 53; ohne ihn laufen 50 und 3 überspringen sich.

- [ ] **Step 7: Prüfen, dass die App weiterhin baut**

Run: `./gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL. Das neue Modul hängt an nichts, aber ein neuer `settings.gradle`-Eintrag und ein neues Gradle-Plugin können die Auflösung stören.

- [ ] **Step 8: Committen**

```bash
git add detection-corpus/build.gradle \
        detection-corpus/src/test/kotlin/de/dreier/mytargets/detection/corpus/InheritedCorpusTest.kt \
        BUILDING.md
git commit -m "Read the real corpus, skipping when it is not configured"
```

---

## Was dieser Plan nicht liefert

- **Kein Bild wird geöffnet.** Das Modul liest Namen und JSON.
- **Kein Detektor.** Es gibt nichts zu messen, bis Plan 3 einen liefert; dieser Plan baut das Maßband.
- **Keine Regressionsschranken.** Die Spec verlangt, sie erst nach der ersten Messung festzuschreiben — und die erste Messung braucht einen Detektor.
- **Keine Positionen für die geerbten Fotos.** Deren Wahrheit sind Ringwerte. Wer den Positionsfehler messen will, annotiert sie nach; das Format dafür steht in Task 3.
- **Keine Umrechnung von `DetectedShot` auf `DetectedShotRecord`.** Die braucht `Target.zoneToString()` aus `:shared` und gehört zu Plan 3.

## Self-Review

**Spec-Abdeckung.** Der Abschnitt *Testkorpus* ist von Task 1 bis 4 abgedeckt, einschließlich der Teilwahrheit und der geerbten Fotos. *Kennzahlen* liegt in Task 6, die Aufschlüsselung nach Erschwernis in `Metrics.byTag`. Der Satz „Diese Werte werden als Regressionsschranke festgeschrieben, nachdem sie das erste Mal gemessen wurden" ist bewusst offen gelassen — er gehört zu Plan 3. Die *Zwei Teststufen* betreffen dieses Modul nicht: Es hat keine Wahrnehmungsstufen, alles darin ist deterministisch.

**Abweichungen von der Spec, jeweils begründet:**
1. Punktzahlen als Zeichenkette statt als `scoringRing`-Index (Abschnitt *Warum Zeichenketten*). Die Spec sagt „dasselbe Format wie `Shot`"; ein Index wäre modellabhängig und würde Android in dieses Modul ziehen.
2. Teilweise annotierte Einträge zählen als „ohne Positionen". Die Spec lässt Teilwahrheit zu, sagt aber nicht, was bei halb annotierten Einträgen gilt; alles andere machte den Positionsfehler von der Auswahl der annotierten Pfeile abhängig.
3. Eigenes Modul statt `:tools`. Die Spec nennt `:tools` als Ort für ein Kommandozeilenwerkzeug — das braucht OpenCV und kommt mit Plan 3. Die Messarithmetik braucht es nicht und ist ohne Android schneller testbar.

**Typkonsistenz.** `Score` ist durchgehend der Typ, nie ein roher `String`; `SpotPosition` trägt immer `faceIndex`. `TruthShot.position` ist nullbar, `DetectedShotRecord.position` nicht — das ist der Unterschied zwischen Wahrheit und Erkennung und absichtlich. `MatchedPair.distance` ist nullbar, weil eine Zuordnung über Ringwerte keinen Abstand hat.

**Offener Punkt, der beim Ausführen auffallen wird.** `DEFAULT_POSITION_GATE = 0.05` ist geraten und im Code als solches vermerkt. Es gehört zu den Werten, die Plan 3 an der ersten echten Messung festschreibt.
