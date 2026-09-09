# Geometriekern der Pfeilerkennung — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein neues Modul `:detection` enthält die vollständige, testgetriebene Geometrie, die aus den Bildern konzentrischer Zielscheibenringe die Homographie der Scheibenebene, den Fluchtpunkt der Scheibennormalen und daraus spot-lokale Trefferkoordinaten berechnet.

**Architecture:** Reine Kotlin-Geometrie ohne Android- und ohne Fremdabhängigkeiten. Kegelschnitte werden als symmetrische 3×3-Matrizen geführt. Aus dem Büschel zweier konzentrischer Kreisbilder folgt in geschlossener Form das Bild des Scheibenzentrums; seine Polare ist die Fluchtlinie, daraus folgt die metrische Rektifizierung. Alle Tests sind JVM-Unit-Tests mit synthetischen Eingaben: bekannte Homographie auf bekannte Kreise anwenden, Rückrechnung prüfen — exakt und mit Pixelrauschen.

**Stand:** Überarbeitet nach Review am 2026-09-09. Die Änderungen sind am Ende unter *Änderungen nach Review* zusammengefasst.

**Tech Stack:** Kotlin, Android-Library-Modul (`com.android.library`), JUnit 4, Truth. Keine neue Abhängigkeit — weder OpenCV noch eine Lineare-Algebra-Bibliothek.

**Spec:** `docs/design/2026-09-09-arrow-detection-design.md`

## Einordnung

Dies ist **Plan 1 von 4**. Die Spec zerfällt in vier nacheinander lauffähige Stücke:

1. **Geometriekern** (dieser Plan) — synthetisch testbar, braucht weder Fotos noch OpenCV
2. Korpus und Messwerkzeug — braucht Fotos
3. Wahrnehmungsstufen — braucht Korpus und die Messwerte daraus
4. App-Integration — braucht eine funktionierende Pipeline

Die Pläne 2 bis 4 werden geschrieben, wenn ihre Voraussetzungen vorliegen. Insbesondere lassen sich die Schwellwerte in Plan 3 nicht erfinden, bevor Plan 2 gemessen hat.

## Global Constraints

- **Sprache:** Code und Bezeichner Englisch, wie im übrigen Projekt. Kommentare Englisch.
- **Lizenzkopf:** Jede neue Kotlin-Datei bekommt den GPLv2-Kopf der übrigen Dateien (siehe `shared/src/main/java/de/dreier/mytargets/shared/models/db/Shot.kt`).
- **Keine Android-Typen im Paket `geometry`.** Kein `PointF`, kein `Bitmap`, kein `Context`. Grund: Diese Klassen sind in JVM-Unit-Tests nur Stubs und werfen `RuntimeException("Stub!")`.
- **Keine neue Abhängigkeit.** Wer eine Bibliothek für lineare Algebra hinzufügen will, hat den Plan verlassen.
- **Gleitkomma:** durchgehend `Double`, nicht `Float`. Kegelschnittfits sind schlecht konditioniert; `Float` reicht nicht.
- **Java/Kotlin-Ziel:** 17, wie `:shared`.
- **`minSdk`/`compileSdk`/`targetSdk`:** aus `gradle/libs.versions.toml`, nicht hart schreiben. Für `targetSdk` gibt es dort keinen eigenen Eintrag; `compileSdk` wird verwendet, wie `:app` es faktisch auch tut.
- **Flavors:** `:detection` muss die Dimension `mode` mit `dev`, `regular`, `screengrab` deklarieren, weil `:shared` sie hat. Ohne das schlägt die Auflösung der Modulabhängigkeit fehl.
- **Testbefehl:** `./gradlew :detection:testDevDebugUnitTest`
- **Toleranz in Tests:** Vergleiche von Gleitkommawerten immer mit expliziter Toleranz. Für Homographie-Rückrechnungen `1e-6`, für Kegelschnittkoeffizienten nach Normierung `1e-8`.
- **Rauschen in Tests:** Jede Stufe, die gefittete Kegelschnitte verarbeitet (Task 5 und 6), hat neben den exakten Tests mindestens einen Test mit verrauschten Punkten: gleichverteilt ±0.5 px, 60 Punkte pro Ring, fester Seed `Random(42)`. Die Toleranzen dieser Tests wurden mit einem Prototyp über 500 Durchläufe ermittelt und liegen mindestens Faktor fünf über dem beobachteten Maximum.

---

## File Structure

Alle Pfade unterhalb von `detection/`.

| Datei | Verantwortung |
|---|---|
| `build.gradle` | Modulkonfiguration |
| `src/main/AndroidManifest.xml` | leeres Manifest mit Namespace |
| `src/main/java/de/dreier/mytargets/detection/geometry/Vec2.kt` | Punkt in der Ebene |
| `src/main/java/de/dreier/mytargets/detection/geometry/Vec3.kt` | homogener Punkt bzw. Gerade |
| `src/main/java/de/dreier/mytargets/detection/geometry/Mat3.kt` | 3×3-Matrix, Homographien |
| `src/main/java/de/dreier/mytargets/detection/geometry/SymmetricEigen.kt` | Jacobi-Eigenzerlegung |
| `src/main/java/de/dreier/mytargets/detection/geometry/Cubic.kt` | reelle Nullstellen einer kubischen Gleichung |
| `src/main/java/de/dreier/mytargets/detection/geometry/Conic.kt` | Kegelschnitt, Fit, Transformation |
| `src/main/java/de/dreier/mytargets/detection/geometry/VanishingLine.kt` | Zentrum und Fluchtlinie aus dem Büschel |
| `src/main/java/de/dreier/mytargets/detection/geometry/Rectification.kt` | metrische Rektifizierung, volle Homographie |
| `src/main/java/de/dreier/mytargets/detection/geometry/NormalVanishingPoint.kt` | Fluchtpunkt der Normalen, Einschussende |
| `src/main/java/de/dreier/mytargets/detection/FaceLayout.kt` | Spot-Anordnung, entkoppelt von `:shared` |
| `src/main/java/de/dreier/mytargets/detection/SpotMapping.kt` | Spot-Zuordnung, spot-lokale Umrechnung |
| `src/main/java/de/dreier/mytargets/detection/CandidateSelection.kt` | Auswahl nach `shotsPerEnd` mit Abstandsregel |
| `src/main/java/de/dreier/mytargets/detection/ArrowDetector.kt` | Schnittstelle und Datentypen |
| `src/test/java/de/dreier/mytargets/detection/...` | je eine Testdatei pro obiger Klasse |

Die Aufteilung folgt der Rechenkette, nicht technischen Schichten: Jede Datei ist eine Stufe, die für sich prüfbar ist.

`FaceLayout` ist bewusst ein eigener Typ und **nicht** `TargetModelBase`: Letzteres liefert `facePositions` als `List<PointF>`, also einen Android-Typ, der JVM-Tests unmöglich macht. Die Umwandlung `TargetModelBase → FaceLayout` ist ein Dreizeiler und gehört in Plan 4, wo Android ohnehin im Spiel ist.

---

## Task 1: Modul `:detection` mit Vektor- und Matrixtypen

**Files:**
- Create: `detection/build.gradle`
- Create: `detection/src/main/AndroidManifest.xml`
- Modify: `settings.gradle:31` (nach `include ':shared'`)
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Vec2.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Vec3.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Mat3.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/Mat3Test.kt`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `Vec2(val x: Double, val y: Double)` mit `plus`, `minus`, `times(Double)`, `length: Double`, `distanceTo(Vec2): Double`
  - `Vec3(val x: Double, val y: Double, val z: Double)` mit `dot(Vec3): Double`, `cross(Vec3): Vec3`, `normalized(): Vec3`, `toVec2(): Vec2?` (null wenn `z` ≈ 0)
  - `Mat3` mit `get(row, col): Double`, `times(Mat3): Mat3`, `times(Vec3): Vec3`, `transpose(): Mat3`, `inverse(): Mat3?`, `det(): Double`, `mapPoint(Vec2): Vec2?`, `mapDirection(at: Vec2, direction: Vec2): Vec2?`, `scaled(Double): Mat3`
  - `Mat3.identity()`, `Mat3.of(vararg Double)` (9 Werte, zeilenweise), `Mat3.translation(Vec2)`, `Mat3.rotation(Double)`

- [ ] **Step 1: Modul in `settings.gradle` eintragen**

Nach der Zeile `include ':shared'` einfügen:

```groovy
include ':detection'
```

- [ ] **Step 2: `detection/build.gradle` anlegen**

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

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "de.dreier.mytargets.detection"
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }
    defaultConfig {
        minSdkVersion libs.versions.minSdk.get().toInteger()
        compileSdk libs.versions.compileSdk.get().toInteger()
    }

    // Must mirror the flavors of :shared, otherwise the dependency cannot be resolved.
    flavorDimensions "mode"
    productFlavors {
        dev { dimension "mode" }
        regular { dimension "mode" }
        screengrab { dimension "mode" }
    }

    // The catalog has no separate targetSdk entry; :app effectively uses compileSdk too.
    lint {
        targetSdk libs.versions.compileSdk.get().toInteger()
    }
    testOptions {
        targetSdk libs.versions.compileSdk.get().toInteger()
    }
}

dependencies {
    implementation libs.kotlin.stdlib.jdk7

    testImplementation libs.junit4
    testImplementation libs.truth
}
```

Hinweis: Es gibt hier **noch keine** Abhängigkeit auf `:shared`. Der Geometriekern braucht sie nicht, und ohne sie laufen die Tests schneller. Sie kommt erst in Plan 4 dazu.

- [ ] **Step 3: `detection/src/main/AndroidManifest.xml` anlegen**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: Sync prüfen**

Run: `./gradlew :detection:tasks --all -q`
Expected: Läuft durch, in der Liste steht `testDevDebugUnitTest`.

- [ ] **Step 5: Den fehlschlagenden Test schreiben**

`detection/src/test/java/de/dreier/mytargets/detection/geometry/Mat3Test.kt`:

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Mat3Test {

    private val tolerance = 1e-9

    @Test
    fun identityMapsPointToItself() {
        val p = Vec2(3.0, -7.0)
        val mapped = Mat3.identity().mapPoint(p)!!
        assertThat(mapped.x).isWithin(tolerance).of(3.0)
        assertThat(mapped.y).isWithin(tolerance).of(-7.0)
    }

    @Test
    fun translationMovesPoint() {
        val mapped = Mat3.translation(Vec2(2.0, 5.0)).mapPoint(Vec2(1.0, 1.0))!!
        assertThat(mapped.x).isWithin(tolerance).of(3.0)
        assertThat(mapped.y).isWithin(tolerance).of(6.0)
    }

    @Test
    fun inverseUndoesMapping() {
        val h = Mat3.of(
            1.4, 0.3, 12.0,
            -0.2, 1.1, -4.0,
            0.0007, 0.0011, 1.0
        )
        val p = Vec2(37.0, -12.5)
        val roundTrip = h.inverse()!!.mapPoint(h.mapPoint(p)!!)!!
        assertThat(roundTrip.x).isWithin(1e-6).of(p.x)
        assertThat(roundTrip.y).isWithin(1e-6).of(p.y)
    }

    @Test
    fun singularMatrixHasNoInverse() {
        val singular = Mat3.of(
            1.0, 2.0, 3.0,
            2.0, 4.0, 6.0,
            1.0, 1.0, 1.0
        )
        assertThat(singular.inverse()).isNull()
    }

    @Test
    fun pointOnVanishingLineHasNoAffineImage() {
        // Third row maps (1, 0) to w = 0.
        val h = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            -1.0, 0.0, 1.0
        )
        assertThat(h.mapPoint(Vec2(1.0, 0.0))).isNull()
    }

    @Test
    fun directionFollowsTheJacobianOfThePointMapping() {
        // A projective map does not preserve directions globally, so a
        // direction only makes sense together with the point it is taken at.
        val h = Mat3.of(
            1.4, 0.3, 12.0,
            -0.2, 1.1, -4.0,
            0.0007, 0.0011, 1.0
        )
        val at = Vec2(37.0, -12.5)
        val direction = Vec2(0.0, -1.0)
        val eps = 1e-5
        val finiteDifference =
            (h.mapPoint(at + direction * eps)!! - h.mapPoint(at)!!) * (1.0 / eps)
        val analytic = h.mapDirection(at, direction)!!
        assertThat(analytic.x).isWithin(1e-6).of(finiteDifference.x)
        assertThat(analytic.y).isWithin(1e-6).of(finiteDifference.y)
    }
}
```

- [ ] **Step 6: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: FAIL — `Unresolved reference: Vec2` / `Mat3`.

- [ ] **Step 7: `Vec2.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.hypot

/** A point or direction in the plane. */
data class Vec2(val x: Double, val y: Double) {

    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)

    operator fun times(scale: Double) = Vec2(x * scale, y * scale)

    val length: Double
        get() = hypot(x, y)

    fun distanceTo(other: Vec2): Double = (this - other).length

    /** Homogeneous representation with w = 1. */
    fun homogeneous() = Vec3(x, y, 1.0)
}
```

- [ ] **Step 8: `Vec3.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/** A homogeneous point, or equivalently a line, in the projective plane. */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    val norm: Double
        get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = norm
        return if (n == 0.0) this else Vec3(x / n, y / n, z / n)
    }

    /**
     * The inhomogeneous point, or null when this is a point at infinity and
     * therefore has no image in the affine plane.
     */
    fun toVec2(): Vec2? = if (abs(z) < 1e-12) null else Vec2(x / z, y / z)
}
```

- [ ] **Step 9: `Mat3.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A 3x3 matrix in row-major order, used for homographies and conics. */
class Mat3(private val m: DoubleArray) {

    init {
        require(m.size == 9) { "Mat3 needs exactly 9 values, got ${m.size}" }
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    operator fun times(other: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                var sum = 0.0
                for (k in 0..2) {
                    sum += this[i, k] * other[k, j]
                }
                r[i * 3 + j] = sum
            }
        }
        return Mat3(r)
    }

    operator fun times(v: Vec3) = Vec3(
        this[0, 0] * v.x + this[0, 1] * v.y + this[0, 2] * v.z,
        this[1, 0] * v.x + this[1, 1] * v.y + this[1, 2] * v.z,
        this[2, 0] * v.x + this[2, 1] * v.y + this[2, 2] * v.z
    )

    fun transpose(): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                r[i * 3 + j] = this[j, i]
            }
        }
        return Mat3(r)
    }

    fun scaled(factor: Double) = Mat3(DoubleArray(9) { m[it] * factor })

    fun det(): Double =
        this[0, 0] * (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) -
            this[0, 1] * (this[1, 0] * this[2, 2] - this[1, 2] * this[2, 0]) +
            this[0, 2] * (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0])

    /** Null when the matrix is singular. */
    fun inverse(): Mat3? {
        val d = det()
        // Scale-relative threshold: a matrix of small entries has a small
        // determinant without being singular.
        val scale = m.maxOf { abs(it) }
        if (scale == 0.0 || abs(d) < 1e-12 * scale * scale * scale) return null
        val r = DoubleArray(9)
        r[0] = (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) / d
        r[1] = (this[0, 2] * this[2, 1] - this[0, 1] * this[2, 2]) / d
        r[2] = (this[0, 1] * this[1, 2] - this[0, 2] * this[1, 1]) / d
        r[3] = (this[1, 2] * this[2, 0] - this[1, 0] * this[2, 2]) / d
        r[4] = (this[0, 0] * this[2, 2] - this[0, 2] * this[2, 0]) / d
        r[5] = (this[0, 2] * this[1, 0] - this[0, 0] * this[1, 2]) / d
        r[6] = (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0]) / d
        r[7] = (this[0, 1] * this[2, 0] - this[0, 0] * this[2, 1]) / d
        r[8] = (this[0, 0] * this[1, 1] - this[0, 1] * this[1, 0]) / d
        return Mat3(r)
    }

    /** Null when the point maps onto the vanishing line. */
    fun mapPoint(p: Vec2): Vec2? = (this * p.homogeneous()).toVec2()

    /**
     * The image of [direction] attached at the point [at]: the Jacobian of the
     * point mapping applied to the direction. For p' = (H p) / w the derivative
     * along d is (H d * w - (H p) * (H d).z) / w^2, with d taken homogeneous
     * with z = 0. Null when [at] maps to infinity.
     */
    fun mapDirection(at: Vec2, direction: Vec2): Vec2? {
        val u = this * at.homogeneous()
        if (abs(u.z) < 1e-12) return null
        val du = this * Vec3(direction.x, direction.y, 0.0)
        val w2 = u.z * u.z
        return Vec2(
            (du.x * u.z - u.x * du.z) / w2,
            (du.y * u.z - u.y * du.z) / w2
        )
    }

    companion object {
        fun of(vararg values: Double) = Mat3(values.copyOf())

        fun identity() = of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )

        fun translation(t: Vec2) = of(
            1.0, 0.0, t.x,
            0.0, 1.0, t.y,
            0.0, 0.0, 1.0
        )

        fun rotation(radians: Double): Mat3 {
            val c = cos(radians)
            val s = sin(radians)
            return of(
                c, -s, 0.0,
                s, c, 0.0,
                0.0, 0.0, 1.0
            )
        }
    }
}
```

- [ ] **Step 10: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS, 6 Tests.

- [ ] **Step 11: Committen**

```bash
git add settings.gradle detection/
git commit -m "Add :detection module with plane geometry primitives"
```

---

## Task 2: Symmetrische Eigenzerlegung nach Jacobi

Wird dreimal gebraucht: für den Kegelschnittfit (6×6), für den Nullvektor eines Rang-2-Büschelmitglieds (3×3) und für die Wurzel des Ellipsenblocks bei der metrischen Rektifizierung (2×2).

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/SymmetricEigen.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/SymmetricEigenTest.kt`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `SymmetricEigen.Result(val values: DoubleArray, val vectors: Array<DoubleArray>)` — `values[i]` gehört zu `vectors[i]`; absteigend nach Betrag sortiert
  - `SymmetricEigen.decompose(a: Array<DoubleArray>): Result`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class SymmetricEigenTest {

    @Test
    fun diagonalMatrixReturnsItsDiagonalSortedByMagnitude() {
        val a = arrayOf(
            doubleArrayOf(2.0, 0.0, 0.0),
            doubleArrayOf(0.0, -5.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        )
        val result = SymmetricEigen.decompose(a)
        assertThat(result.values[0]).isWithin(1e-9).of(-5.0)
        assertThat(abs(result.values[1])).isWithin(1e-9).of(2.0)
        assertThat(abs(result.values[2])).isWithin(1e-9).of(1.0)
    }

    @Test
    fun eigenvectorsSatisfyTheDefiningEquation() {
        val a = arrayOf(
            doubleArrayOf(4.0, 1.0, -2.0),
            doubleArrayOf(1.0, 2.0, 0.0),
            doubleArrayOf(-2.0, 0.0, 3.0)
        )
        val result = SymmetricEigen.decompose(a)
        for (i in 0..2) {
            val v = result.vectors[i]
            val lambda = result.values[i]
            for (row in 0..2) {
                val av = (0..2).sumOf { col -> a[row][col] * v[col] }
                assertThat(av).isWithin(1e-8).of(lambda * v[row])
            }
        }
    }

    @Test
    fun rankOneMatrixYieldsItsGeneratingVector() {
        // a = l * l^T with l = (1, 2, -1), so the dominant eigenvector is l
        // normalised and the dominant eigenvalue is |l|^2 = 6.
        val l = doubleArrayOf(1.0, 2.0, -1.0)
        val a = Array(3) { r -> DoubleArray(3) { c -> l[r] * l[c] } }
        val result = SymmetricEigen.decompose(a)

        assertThat(result.values[0]).isWithin(1e-8).of(6.0)
        assertThat(abs(result.values[1])).isLessThan(1e-8)
        assertThat(abs(result.values[2])).isLessThan(1e-8)

        // Eigenvectors are unique only up to sign.
        val v = result.vectors[0]
        val sign = if (v[0] < 0) -1.0 else 1.0
        val norm = kotlin.math.sqrt(6.0)
        assertThat(v[0] * sign).isWithin(1e-8).of(1.0 / norm)
        assertThat(v[1] * sign).isWithin(1e-8).of(2.0 / norm)
        assertThat(v[2] * sign).isWithin(1e-8).of(-1.0 / norm)
    }

    @Test
    fun worksForSixBySixMatrices() {
        val n = 6
        val a = Array(n) { r -> DoubleArray(n) { c -> 1.0 / (r + c + 1.0) } }
        val result = SymmetricEigen.decompose(a)
        for (i in 0 until n) {
            val v = result.vectors[i]
            val lambda = result.values[i]
            for (row in 0 until n) {
                val av = (0 until n).sumOf { col -> a[row][col] * v[col] }
                assertThat(av).isWithin(1e-8).of(lambda * v[row])
            }
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*SymmetricEigenTest*"`
Expected: FAIL — `Unresolved reference: SymmetricEigen`.

- [ ] **Step 3: `SymmetricEigen.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Eigen decomposition of a real symmetric matrix using the cyclic Jacobi
 * method.
 *
 * Jacobi is chosen over faster algorithms because the matrices here are tiny
 * (2x2 to 6x6), it needs no external library, and it is accurate for the
 * near-degenerate cases that matter: a conic pencil member that has dropped to
 * rank two, and a scatter matrix whose smallest eigenvalue carries the fit.
 */
object SymmetricEigen {

    class Result(val values: DoubleArray, val vectors: Array<DoubleArray>)

    /**
     * @param a symmetric matrix; not modified
     * @return eigenvalues and eigenvectors, sorted by descending magnitude of
     *         the eigenvalue. `vectors[i]` is the unit eigenvector for
     *         `values[i]`.
     */
    fun decompose(a: Array<DoubleArray>, maxSweeps: Int = 100): Result {
        val n = a.size
        require(n > 0) { "matrix must not be empty" }
        a.forEach { require(it.size == n) { "matrix must be square" } }

        val work = Array(n) { r -> a[r].copyOf() }
        // v holds eigenvectors as columns while sweeping.
        val v = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }

        for (sweep in 0 until maxSweeps) {
            var off = 0.0
            for (p in 0 until n) {
                for (q in p + 1 until n) {
                    off += work[p][q] * work[p][q]
                }
            }
            if (off < 1e-30) break

            for (p in 0 until n) {
                for (q in p + 1 until n) {
                    if (abs(work[p][q]) < 1e-300) continue

                    val theta = (work[q][q] - work[p][p]) / (2.0 * work[p][q])
                    val t = sign(theta) / (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0)
                    val s = t * c

                    for (k in 0 until n) {
                        val akp = work[k][p]
                        val akq = work[k][q]
                        work[k][p] = c * akp - s * akq
                        work[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = work[p][k]
                        val aqk = work[q][k]
                        work[p][k] = c * apk - s * aqk
                        work[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until n) {
                        val vkp = v[k][p]
                        val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
        }

        val values = DoubleArray(n) { work[it][it] }
        val order = (0 until n).sortedByDescending { abs(values[it]) }
        val sortedValues = DoubleArray(n) { values[order[it]] }
        val sortedVectors = Array(n) { i -> DoubleArray(n) { k -> v[k][order[i]] } }
        return Result(sortedValues, sortedVectors)
    }

    private fun sign(x: Double) = if (x >= 0.0) 1.0 else -1.0
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*SymmetricEigenTest*"`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/geometry/SymmetricEigen.kt \
        detection/src/test/java/de/dreier/mytargets/detection/geometry/SymmetricEigenTest.kt
git commit -m "Add Jacobi eigen decomposition for symmetric matrices"
```

---

## Task 3: Kegelschnitt mit Fit und Transformation

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Conic.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/ConicTest.kt`

**Interfaces:**
- Consumes: `Vec2`, `Vec3`, `Mat3` (Task 1), `SymmetricEigen` (Task 2)
- Produces:
  - `Conic(val matrix: Mat3)` mit `evaluate(Vec2): Double`, `transformedBy(h: Mat3): Conic`, `polarLine(point: Vec3): Vec3`, `pole(line: Vec3): Vec3?`, `normalized(): Conic`
  - `Conic.circle(center: Vec2, radius: Double): Conic`
  - `Conic.fit(points: List<Vec2>): Conic?` — null bei weniger als 5 Punkten oder entarteter Lage

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ConicTest {

    private fun circlePoints(center: Vec2, radius: Double, count: Int): List<Vec2> =
        (0 until count).map { i ->
            val a = 2.0 * PI * i / count
            Vec2(center.x + radius * cos(a), center.y + radius * sin(a))
        }

    @Test
    fun circleEvaluatesToZeroOnItsOwnPoints() {
        val c = Conic.circle(Vec2(3.0, -1.0), 5.0)
        for (p in circlePoints(Vec2(3.0, -1.0), 5.0, 16)) {
            assertThat(abs(c.evaluate(p))).isLessThan(1e-9)
        }
    }

    @Test
    fun circleEvaluatesNegativeInsideAndPositiveOutside() {
        val c = Conic.circle(Vec2(0.0, 0.0), 2.0)
        assertThat(c.evaluate(Vec2(0.0, 0.0))).isLessThan(0.0)
        assertThat(c.evaluate(Vec2(10.0, 0.0))).isGreaterThan(0.0)
    }

    @Test
    fun fitRecoversACircle() {
        val center = Vec2(120.0, -40.0)
        val radius = 33.0
        val fitted = Conic.fit(circlePoints(center, radius, 24))!!
        for (p in circlePoints(center, radius, 37)) {
            // Scale-free residual: the conic is only defined up to scale.
            assertThat(abs(fitted.normalized().evaluate(p))).isLessThan(1e-6)
        }
    }

    @Test
    fun fitRecoversAnEllipse() {
        val points = (0 until 20).map { i ->
            val a = 2.0 * PI * i / 20
            Vec2(200.0 + 90.0 * cos(a), 150.0 + 30.0 * sin(a))
        }
        val fitted = Conic.fit(points)!!.normalized()
        for (p in points) {
            assertThat(abs(fitted.evaluate(p))).isLessThan(1e-6)
        }
    }

    @Test
    fun fitNeedsAtLeastFivePoints() {
        assertThat(Conic.fit(circlePoints(Vec2(0.0, 0.0), 1.0, 4))).isNull()
    }

    @Test
    fun transformedConicVanishesOnTransformedPoints() {
        val h = Mat3.of(
            1.3, 0.2, 40.0,
            -0.15, 0.95, -25.0,
            0.0004, 0.0009, 1.0
        )
        val center = Vec2(0.0, 0.0)
        val radius = 100.0
        val transformed = Conic.circle(center, radius).transformedBy(h).normalized()

        for (p in circlePoints(center, radius, 24)) {
            val image = h.mapPoint(p)!!
            assertThat(abs(transformed.evaluate(image))).isLessThan(1e-6)
        }
    }

    @Test
    fun poleOfPolarLineIsTheOriginalPoint() {
        val c = Conic.circle(Vec2(2.0, 3.0), 7.0)
        val point = Vec3(11.0, -4.0, 1.0)
        val recovered = c.pole(c.polarLine(point))!!.toVec2()!!
        assertThat(recovered.x).isWithin(1e-7).of(11.0)
        assertThat(recovered.y).isWithin(1e-7).of(-4.0)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*ConicTest*"`
Expected: FAIL — `Unresolved reference: Conic`.

- [ ] **Step 3: `Conic.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A conic section as a symmetric 3x3 matrix C, so that a homogeneous point p
 * lies on the conic exactly when p^T C p = 0.
 *
 * For the coefficients of a x^2 + b xy + c y^2 + d x + e y + f = 0 the matrix is
 *
 *     [ a    b/2  d/2 ]
 *     [ b/2  c    e/2 ]
 *     [ d/2  e/2  f   ]
 *
 * A conic is only defined up to a non-zero scale factor. Anything that compares
 * conics must normalise first.
 */
class Conic(val matrix: Mat3) {

    fun evaluate(p: Vec2): Double {
        val h = p.homogeneous()
        return h.dot(matrix * h)
    }

    /**
     * The image of this conic under the point mapping [h], which is
     * H^-T C H^-1.
     */
    fun transformedBy(h: Mat3): Conic {
        val inv = requireNotNull(h.inverse()) { "homography must be invertible" }
        return Conic(inv.transpose() * matrix * inv)
    }

    /** The polar line of [point] with respect to this conic: C p. */
    fun polarLine(point: Vec3): Vec3 = matrix * point

    /** The pole of [line]: C^-1 l. Null for a degenerate conic. */
    fun pole(line: Vec3): Vec3? = matrix.inverse()?.times(line)

    /** Scaled so the Frobenius norm is one, which makes residuals comparable. */
    fun normalized(): Conic {
        var sum = 0.0
        for (i in 0..2) {
            for (j in 0..2) {
                sum += matrix[i, j] * matrix[i, j]
            }
        }
        val n = sqrt(sum)
        return if (n == 0.0) this else Conic(matrix.scaled(1.0 / n))
    }

    companion object {

        fun circle(center: Vec2, radius: Double): Conic = Conic(
            Mat3.of(
                1.0, 0.0, -center.x,
                0.0, 1.0, -center.y,
                -center.x, -center.y,
                center.x * center.x + center.y * center.y - radius * radius
            )
        )

        /**
         * Least squares fit through [points], at least five of them.
         *
         * The points are first normalised the way Hartley recommends -- centroid
         * to the origin, mean distance sqrt(2) -- because the design matrix mixes
         * squared pixel coordinates with linear ones. Without that the smallest
         * eigenvalue drowns in rounding error at image scale.
         */
        fun fit(points: List<Vec2>): Conic? {
            if (points.size < 5) return null

            val t = normalisation(points) ?: return null
            val normalisedPoints = points.map { t.mapPoint(it) ?: return null }

            // Rows of [x^2, xy, y^2, x, y, 1]; solve A v = 0 in the least
            // squares sense, which is the eigenvector of A^T A for the smallest
            // eigenvalue.
            val ata = Array(6) { DoubleArray(6) }
            for (p in normalisedPoints) {
                val row = doubleArrayOf(
                    p.x * p.x, p.x * p.y, p.y * p.y, p.x, p.y, 1.0
                )
                for (i in 0..5) {
                    for (j in 0..5) {
                        ata[i][j] += row[i] * row[j]
                    }
                }
            }

            val eigen = SymmetricEigen.decompose(ata)
            // Sorted by descending magnitude, so the last one is the smallest:
            // the least squares solution of A v = 0.
            val v = eigen.vectors[5]
            val normalisedConic = Mat3.of(
                v[0], v[1] / 2.0, v[3] / 2.0,
                v[1] / 2.0, v[2], v[4] / 2.0,
                v[3] / 2.0, v[4] / 2.0, v[5]
            )
            if (normalisedConic.det().isNaN()) return null

            // Undo the normalisation: C = T^T C_norm T
            return Conic(t.transpose() * normalisedConic * t)
        }

        /** Similarity that centres [points] and scales mean distance to sqrt(2). */
        private fun normalisation(points: List<Vec2>): Mat3? {
            val n = points.size.toDouble()
            val cx = points.sumOf { it.x } / n
            val cy = points.sumOf { it.y } / n
            val centre = Vec2(cx, cy)
            val meanDistance = points.sumOf { it.distanceTo(centre) } / n
            if (meanDistance < 1e-12) return null
            val s = sqrt(2.0) / meanDistance
            return Mat3.of(
                s, 0.0, -s * cx,
                0.0, s, -s * cy,
                0.0, 0.0, 1.0
            )
        }
    }
}
```

Hinweis zur Reihenfolge im Eigenvektor: `v` trägt die Koeffizienten in der Reihenfolge der Designmatrix, also `[a, b, c, d, e, f]` für `a·x² + b·xy + c·y² + d·x + e·y + f`. In der Matrix stehen die gemischten Terme deshalb halbiert.

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*ConicTest*"`
Expected: PASS, 7 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/geometry/Conic.kt \
        detection/src/test/java/de/dreier/mytargets/detection/geometry/ConicTest.kt
git commit -m "Add conic type with normalised least squares fit"
```

---

## Task 4: Reelle Nullstellen kubischer Gleichungen

Wird in Task 5 gebraucht, um im Kegelschnittbüschel das λ zu finden, bei dem der Rang fällt.

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Cubic.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/CubicTest.kt`

**Interfaces:**
- Consumes: nichts
- Produces: `Cubic.realRoots(a: Double, b: Double, c: Double, d: Double): List<Double>` für `a·x³ + b·x² + c·x + d = 0`, aufsteigend sortiert, Doppelnullstellen einfach aufgeführt

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CubicTest {

    private fun assertRootsAre(actual: List<Double>, vararg expected: Double) {
        assertThat(actual).hasSize(expected.size)
        expected.sorted().forEachIndexed { i, e ->
            assertThat(actual[i]).isWithin(1e-7).of(e)
        }
    }

    @Test
    fun threeDistinctRoots() {
        // (x - 1)(x - 2)(x + 3) = x^3 - 7x + 6
        assertRootsAre(Cubic.realRoots(1.0, 0.0, -7.0, 6.0), -3.0, 1.0, 2.0)
    }

    @Test
    fun oneRealRootAndTwoComplex() {
        // (x - 2)(x^2 + 1) = x^3 - 2x^2 + x - 2
        assertRootsAre(Cubic.realRoots(1.0, -2.0, 1.0, -2.0), 2.0)
    }

    @Test
    fun doubleRootIsReportedOnce() {
        // (x - 4)^2 (x + 1) = x^3 - 7x^2 + 8x + 16
        val roots = Cubic.realRoots(1.0, -7.0, 8.0, 16.0)
        assertThat(roots).hasSize(2)
        assertThat(roots[0]).isWithin(1e-6).of(-1.0)
        assertThat(roots[1]).isWithin(1e-6).of(4.0)
    }

    @Test
    fun degeneratesToQuadratic() {
        // 0*x^3 + x^2 - 5x + 6 = 0 has roots 2 and 3
        assertRootsAre(Cubic.realRoots(0.0, 1.0, -5.0, 6.0), 2.0, 3.0)
    }

    @Test
    fun degeneratesToLinear() {
        assertRootsAre(Cubic.realRoots(0.0, 0.0, 2.0, -8.0), 4.0)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*CubicTest*"`
Expected: FAIL — `Unresolved reference: Cubic`.

- [ ] **Step 3: `Cubic.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sqrt

/** Real roots of polynomials up to degree three, by closed form. */
object Cubic {

    private const val EPS = 1e-12

    /**
     * Real roots of a x^3 + b x^2 + c x + d = 0, ascending. Roots that
     * coincide are reported once; the caller cannot distinguish a double root
     * from two roots that happen to be close, which is the right behaviour for
     * noisy input.
     */
    fun realRoots(a: Double, b: Double, c: Double, d: Double): List<Double> {
        if (abs(a) < EPS) return quadraticRoots(b, c, d)

        // Depressed cubic t^3 + p t + q, with x = t - b / (3a)
        val shift = b / (3.0 * a)
        val p = (3.0 * a * c - b * b) / (3.0 * a * a)
        val q = (2.0 * b * b * b - 9.0 * a * b * c + 27.0 * a * a * d) /
            (27.0 * a * a * a)

        val discriminant = q * q / 4.0 + p * p * p / 27.0
        val roots = when {
            abs(discriminant) < EPS -> {
                // Multiple root.
                if (abs(q) < EPS) {
                    listOf(0.0)
                } else {
                    val u = cbrt(-q / 2.0)
                    listOf(2.0 * u, -u)
                }
            }
            discriminant > 0.0 -> {
                val sq = sqrt(discriminant)
                listOf(cbrt(-q / 2.0 + sq) + cbrt(-q / 2.0 - sq))
            }
            else -> {
                // Three distinct real roots, trigonometric form.
                val r = sqrt(-p * p * p / 27.0)
                val phi = acos(clamp(-q / (2.0 * r)))
                val m = 2.0 * sqrt(-p / 3.0)
                (0..2).map { k -> m * cos((phi + 2.0 * PI * k) / 3.0) }
            }
        }
        return roots.map { it - shift }.sorted()
    }

    private fun quadraticRoots(a: Double, b: Double, c: Double): List<Double> {
        if (abs(a) < EPS) {
            return if (abs(b) < EPS) emptyList() else listOf(-c / b)
        }
        val disc = b * b - 4.0 * a * c
        return when {
            disc < -EPS -> emptyList()
            abs(disc) <= EPS -> listOf(-b / (2.0 * a))
            else -> {
                val sq = sqrt(disc)
                listOf((-b - sq) / (2.0 * a), (-b + sq) / (2.0 * a)).sorted()
            }
        }
    }

    private fun clamp(x: Double) = when {
        x < -1.0 -> -1.0
        x > 1.0 -> 1.0
        else -> x
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*CubicTest*"`
Expected: PASS, 5 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/geometry/Cubic.kt \
        detection/src/test/java/de/dreier/mytargets/detection/geometry/CubicTest.kt
git commit -m "Add closed form real root solver for cubics"
```

---

## Task 5: Zentrum und Fluchtlinie aus dem Büschel zweier Kreisbilder

Das ist der Kern der Registrierung und der Punkt, an dem dieser Plan von der Spec abweicht. Begründung im Code-Kommentar.

Warum nicht das Rang-1-Mitglied: Für zwei konzentrische Kreise ist `det(A − λB) ∝ (1−λ)²·(λ·r2² − r1²)`. Das Rang-1-Mitglied `l·lᵀ` sitzt bei der **doppelten** Nullstelle. Unter Rauschen spaltet die sich zufällig in zwei reelle oder zwei komplexe Wurzeln; im komplexen Fall ist sie für einen reellen Wurzelfinder unsichtbar. Die **einfache** Nullstelle `λ₁ = r1²/r2²` ist davon klar getrennt. Ihr Büschelmitglied hat Rang 2, und sein Nullvektor ist das Bild des gemeinsamen Zentrums. Die Polare dieses Punkts bezüglich eines der Kegelschnitte ist die Fluchtlinie. Ein Prototyp mit 500 verrauschten Durchläufen hat für diesen Weg keinen Ausfall gezeigt; der Weg über die Doppelnullstelle fiel bereits bei exakten Eingaben aus.

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/VanishingLine.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/VanishingLineTest.kt`

**Interfaces:**
- Consumes: `Conic` (Task 3), `Cubic` (Task 4), `SymmetricEigen` (Task 2), `Mat3`/`Vec2`/`Vec3` (Task 1)
- Produces:
  - `VanishingLine.Result(val line: Vec3, val imagedCentre: Vec2)` — Fluchtlinie der Scheibenebene, normiert, und das Bild des gemeinsamen Zentrums in Bildkoordinaten
  - `VanishingLine.fromConcentricCircles(c1: Conic, c2: Conic): Result?` — null, wenn kein Büschelmitglied einen Nullvektor im Inneren beider Kegelschnitte hat, also wenn die Eingaben keine Bilder zweier verschiedener konzentrischer Kreise sind

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class VanishingLineTest {

    /** A homography with a genuine perspective part. */
    private val h = Mat3.of(
        1.20, 0.18, 300.0,
        -0.10, 0.94, 220.0,
        0.00035, 0.00062, 1.0
    )

    /** The same view at pixel scale: a unit circle becomes a ring of ~480 px. */
    private val hPixels = h * Mat3.of(
        400.0, 0.0, 0.0,
        0.0, 400.0, 0.0,
        0.0, 0.0, 1.0
    )

    /** The image of the line at infinity under [m] is M^-T * (0, 0, 1). */
    private fun expectedLine(m: Mat3): Vec3 =
        (m.inverse()!!.transpose() * Vec3(0.0, 0.0, 1.0)).normalized()

    private fun assertParallel(a: Vec3, b: Vec3, tolerance: Double = 1e-6) {
        // Lines are defined up to scale and sign.
        val cross = a.normalized().cross(b.normalized())
        assertThat(cross.norm).isLessThan(tolerance)
    }

    private fun circlePoints(radius: Double, count: Int): List<Vec2> =
        (0 until count).map { i ->
            val a = 2.0 * PI * i / count
            Vec2(radius * cos(a), radius * sin(a))
        }

    @Test
    fun recoversTheVanishingLineFromTwoCircles() {
        val c1 = Conic.circle(Vec2(0.0, 0.0), 40.0).transformedBy(h)
        val c2 = Conic.circle(Vec2(0.0, 0.0), 80.0).transformedBy(h)

        val result = VanishingLine.fromConcentricCircles(c1, c2)!!
        assertParallel(result.line, expectedLine(h))
    }

    @Test
    fun recoversTheImagedCentre() {
        val c1 = Conic.circle(Vec2(0.0, 0.0), 40.0).transformedBy(h)
        val c2 = Conic.circle(Vec2(0.0, 0.0), 80.0).transformedBy(h)

        val result = VanishingLine.fromConcentricCircles(c1, c2)!!
        // The common centre is the origin, whose image is the last column of h.
        assertThat(result.imagedCentre.x).isWithin(1e-6).of(300.0)
        assertThat(result.imagedCentre.y).isWithin(1e-6).of(220.0)
    }

    @Test
    fun isIndependentOfTheScaleOfTheFittedConics() {
        // Conic fits return an arbitrary scale; the result must not depend on it.
        val c1 = Conic(Conic.circle(Vec2(0.0, 0.0), 40.0).transformedBy(h).matrix.scaled(7.3))
        val c2 = Conic(Conic.circle(Vec2(0.0, 0.0), 80.0).transformedBy(h).matrix.scaled(-0.4))

        val result = VanishingLine.fromConcentricCircles(c1, c2)!!
        assertParallel(result.line, expectedLine(h))
        assertThat(result.imagedCentre.x).isWithin(1e-6).of(300.0)
        assertThat(result.imagedCentre.y).isWithin(1e-6).of(220.0)
    }

    @Test
    fun frontalViewYieldsTheLineAtInfinity() {
        val c1 = Conic.circle(Vec2(500.0, 400.0), 100.0)
        val c2 = Conic.circle(Vec2(500.0, 400.0), 200.0)

        val result = VanishingLine.fromConcentricCircles(c1, c2)!!
        assertThat(abs(result.line.x)).isLessThan(1e-6)
        assertThat(abs(result.line.y)).isLessThan(1e-6)
        assertThat(abs(result.line.z)).isGreaterThan(0.9)
        assertThat(result.imagedCentre.x).isWithin(1e-6).of(500.0)
        assertThat(result.imagedCentre.y).isWithin(1e-6).of(400.0)
    }

    @Test
    fun identicalCirclesHaveNoSolution() {
        val c = Conic.circle(Vec2(0.0, 0.0), 40.0).transformedBy(h)
        assertThat(VanishingLine.fromConcentricCircles(c, c)).isNull()
    }

    @Test
    fun survivesHalfPixelNoiseOnFittedRings() {
        // The exact tests above cannot tell a numerically fragile method from a
        // robust one. This one can: fitted conics from jittered points.
        val random = Random(42)
        fun noisyRing(radius: Double): Conic = Conic.fit(
            circlePoints(radius, 60).map { p ->
                val q = hPixels.mapPoint(p)!!
                Vec2(q.x + random.nextDouble() - 0.5, q.y + random.nextDouble() - 0.5)
            }
        )!!

        val result = VanishingLine.fromConcentricCircles(noisyRing(1.0), noisyRing(0.4))!!

        // Prototype over 500 seeds: centre error below 0.3 px, line cross norm
        // below 2e-6. Tolerances leave a wide margin.
        assertThat(result.imagedCentre.x).isWithin(2.0).of(300.0)
        assertThat(result.imagedCentre.y).isWithin(2.0).of(220.0)
        assertParallel(result.line, expectedLine(hPixels), tolerance = 1e-4)
    }
}
```


- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*VanishingLineTest*"`
Expected: FAIL — `Unresolved reference: VanishingLine`.

- [ ] **Step 3: `VanishingLine.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Recovers the imaged centre and the vanishing line of the target plane from
 * the images of two concentric circles.
 *
 * Why this works. In the plane of the target two concentric circles are
 *
 *     C1 = diag(1, 1, -r1^2)      C2 = diag(1, 1, -r2^2)
 *
 * and the pencil C1 - lambda C2 has two degenerate members:
 *
 *     lambda = 1            -> diag(0, 0, r2^2 - r1^2), rank one, the line at
 *                              infinity counted twice; a DOUBLE root of the
 *                              determinant
 *     lambda = r1^2 / r2^2  -> diag(1 - lambda, 1 - lambda, 0), rank two, whose
 *                              null vector (0, 0, 1) is the common centre; a
 *                              SIMPLE root
 *
 * A homography preserves all of that. We use the simple root: under noise a
 * double root splits into either two real or two complex roots, and in the
 * complex case a real root finder does not see it at all. The simple root is
 * well separated and its null vector is the imaged centre c. The vanishing line
 * is then the polar of c with respect to either conic, l = C1' c, because in
 * the target plane C1 (0,0,1)^T is proportional to (0,0,1), the line at
 * infinity, and pole/polar relations survive homographies.
 *
 * The right root is recognised by where its null vector lies: the imaged centre
 * is inside both ellipses. The null vectors of the (near) rank one members lie
 * on the vanishing line, far outside. That test is scale free and needs no
 * tuned tolerance.
 *
 * Everything is computed in a normalised frame in which the first conic is
 * roughly the unit circle about the origin. At pixel scale the determinant
 * cubic has coefficients spanning many orders of magnitude and the eigenvalue
 * ratios lose their meaning.
 */
object VanishingLine {

    class Result(val line: Vec3, val imagedCentre: Vec2)

    /**
     * @return the vanishing line as a unit vector and the imaged centre, or null
     *         when the inputs were not the images of two distinct concentric
     *         circles.
     */
    fun fromConcentricCircles(c1: Conic, c2: Conic): Result? {
        val frame = normalisingFrame(c1) ?: return null
        val a = c1.transformedBy(frame).normalized().matrix
        val b = c2.transformedBy(frame).normalized().matrix
        if (areTheSameConic(a, b)) return null

        val roots = Cubic.realRoots(
            cubicA(b), cubicB(a, b), cubicC(a, b), a.det()
        )

        var best: Vec3? = null
        var bestResidual = Double.MAX_VALUE

        for (lambda in roots) {
            val m = arrayOf(
                doubleArrayOf(a[0, 0] - lambda * b[0, 0], a[0, 1] - lambda * b[0, 1], a[0, 2] - lambda * b[0, 2]),
                doubleArrayOf(a[1, 0] - lambda * b[1, 0], a[1, 1] - lambda * b[1, 1], a[1, 2] - lambda * b[1, 2]),
                doubleArrayOf(a[2, 0] - lambda * b[2, 0], a[2, 1] - lambda * b[2, 1], a[2, 2] - lambda * b[2, 2])
            )
            val eigen = SymmetricEigen.decompose(m)
            if (abs(eigen.values[0]) < 1e-12) continue

            // Null vector: eigenvector of the eigenvalue smallest in magnitude.
            val candidate = Vec3(
                eigen.vectors[2][0],
                eigen.vectors[2][1],
                eigen.vectors[2][2]
            )
            if (!isInside(candidate, a) || !isInside(candidate, b)) continue

            // Among admissible members prefer the one closest to rank two.
            val residual = abs(eigen.values[2]) / abs(eigen.values[1])
            if (residual < bestResidual) {
                bestResidual = residual
                best = candidate
            }
        }

        val centre = best ?: return null
        val lineInFrame = a * centre

        // Back to image coordinates: points go through the inverse frame,
        // lines through the transpose.
        val imagedCentre = frame.inverse()?.times(centre)?.toVec2() ?: return null
        val line = (frame.transpose() * lineInFrame).normalized()
        return Result(line, imagedCentre)
    }

    /**
     * Similarity that moves the centre of [conic] to the origin and scales its
     * geometric mean radius to one. Null for a degenerate conic.
     */
    private fun normalisingFrame(conic: Conic): Mat3? {
        val m = conic.normalized().matrix
        val centre = m.inverse()?.times(Vec3(0.0, 0.0, 1.0))?.toVec2() ?: return null
        // Constant term after moving the centre to the origin.
        val ch = centre.homogeneous()
        val f = ch.dot(m * ch)
        val blockDet = m[0, 0] * m[1, 1] - m[0, 1] * m[1, 0]
        if (abs(blockDet) < 1e-18 || abs(f) < 1e-18) return null
        // Semi-axes squared are -f / eigenvalue, so the geometric mean radius is
        // sqrt(|f| / sqrt(|det block|)).
        val size = sqrt(abs(f) / sqrt(abs(blockDet)))
        if (size < 1e-12) return null
        val s = 1.0 / size
        return Mat3.of(
            s, 0.0, -s * centre.x,
            0.0, s, -s * centre.y,
            0.0, 0.0, 1.0
        )
    }

    /** Both inputs are normalised to unit Frobenius norm, up to sign. */
    private fun areTheSameConic(a: Mat3, b: Mat3): Boolean {
        var minus = 0.0
        var plus = 0.0
        for (i in 0..2) {
            for (j in 0..2) {
                val d = a[i, j] - b[i, j]
                val s = a[i, j] + b[i, j]
                minus += d * d
                plus += s * s
            }
        }
        return min(minus, plus) < 1e-18
    }

    /**
     * Whether [p] lies inside the ellipse [conic]: its quadratic form there has
     * the same sign as at the ellipse's own centre. Scale of [p] and of the
     * conic do not matter.
     */
    private fun isInside(p: Vec3, conic: Mat3): Boolean {
        val centre = conic.inverse()?.times(Vec3(0.0, 0.0, 1.0)) ?: return false
        val interior = centre.dot(conic * centre)
        val value = p.dot(conic * p)
        return interior != 0.0 && value * interior > 0.0
    }

    /*
     * Coefficients of det(A - lambda B), expanded by multilinearity in the
     * columns. Choosing j of the three columns from -lambda B contributes
     * (-lambda)^j times the sum of the corresponding mixed determinants:
     *
     *   lambda^3 : -det(B)
     *   lambda^2 : sum_k det(B with column k taken from A)
     *   lambda^1 : -sum_k det(A with column k taken from B)
     *   lambda^0 : det(A)
     */
    private fun cubicA(b: Mat3) = -b.det()

    private fun cubicB(a: Mat3, b: Mat3): Double {
        var sum = 0.0
        for (k in 0..2) {
            sum += cofactorMixed(base = b, replacement = a, k = k)
        }
        return sum
    }

    private fun cubicC(a: Mat3, b: Mat3): Double {
        var sum = 0.0
        for (k in 0..2) {
            sum -= cofactorMixed(base = a, replacement = b, k = k)
        }
        return sum
    }

    /**
     * Determinant of the matrix built from [replacement]'s column k and
     * [base]'s other columns.
     */
    private fun cofactorMixed(base: Mat3, replacement: Mat3, k: Int): Double {
        val values = DoubleArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                values[row * 3 + col] =
                    if (col == k) replacement[row, col] else base[row, col]
            }
        }
        return Mat3(values).det()
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*VanishingLineTest*"`
Expected: PASS, 6 Tests.

Wenn `recoversTheImagedCentre` mit einem Punkt weit außerhalb fehlschlägt, wurde der falsche Eigenvektor genommen: Der Nullvektor gehört zum betragsmäßig **kleinsten** Eigenwert, also `vectors[2]`. Wenn `isIndependentOfTheScaleOfTheFittedConics` fehlschlägt, ist das Vorzeichen von `cubicB` oder `cubicC` vertauscht. Prüfe die Expansion, indem du `det(A - lambda B)` für drei feste λ numerisch auswertest und mit der kubischen Form vergleichst. Wenn nur `survivesHalfPixelNoiseOnFittedRings` fehlschlägt, fehlt vermutlich die Normierung über `normalisingFrame`.

- [ ] **Step 5: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/geometry/VanishingLine.kt \
        detection/src/test/java/de/dreier/mytargets/detection/geometry/VanishingLineTest.kt
git commit -m "Recover the imaged centre and vanishing line from a conic pencil"
```

---

## Task 6: Metrische Rektifizierung und volle Homographie

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/Rectification.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/RectificationTest.kt`

**Interfaces:**
- Consumes: `Conic`, `VanishingLine`, `Mat3`, `Vec2`, `Vec3`, `SymmetricEigen`
- Produces:
  - `Rectification.Result(val imageToTarget: Mat3, val vanishingLine: Vec3, val imagedCentre: Vec2)`
  - `Rectification.fromConcentricCircles(outer: Conic, outerRadius: Double, inner: Conic, innerRadius: Double, imageUp: Vec2 = Vec2(0.0, -1.0)): Result?`

`imageToTarget` bildet Bildpunkte auf Auflagenkoordinaten ab, in denen die Ringe konzentrische Kreise um `(0,0)` mit ihren nominellen Radien sind.

Zur Drehung: "Kamera aufrecht" heißt, dass die Bildvertikale **am Scheibenzentrum** der Scheibenoben-Richtung entspricht. Die Abbildung ist projektiv, ihre lokale Richtung hängt vom Ort ab. Die Aufrechte wird deshalb mit `Mat3.mapDirection` am abgebildeten Zentrum gemessen, nicht an der Bildecke. Ein eigener Test (`alignsImageUpAtTheFaceCentreWithTargetUp`) sichert das, weil die Radien- und Winkeltests eine falsche Drehung nicht bemerken.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class RectificationTest {

    private val h = Mat3.of(
        1.20, 0.18, 300.0,
        -0.10, 0.94, 220.0,
        0.00035, 0.00062, 1.0
    )

    /** The same view at pixel scale: a unit circle becomes a ring of ~480 px. */
    private val hPixels = h * Mat3.of(
        400.0, 0.0, 0.0,
        0.0, 400.0, 0.0,
        0.0, 0.0, 1.0
    )

    private fun circlePoints(radius: Double, count: Int): List<Vec2> =
        (0 until count).map { i ->
            val a = 2.0 * PI * i / count
            Vec2(radius * cos(a), radius * sin(a))
        }

    @Test
    fun mapsImagedRingsBackToConcentricCirclesOfNominalRadius() {
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(h)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)

        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!

        for (p in circlePoints(1.0, 24)) {
            val recovered = result.imageToTarget.mapPoint(h.mapPoint(p)!!)!!
            assertThat(hypot(recovered.x, recovered.y)).isWithin(1e-5).of(1.0)
        }
        for (p in circlePoints(0.4, 24)) {
            val recovered = result.imageToTarget.mapPoint(h.mapPoint(p)!!)!!
            assertThat(hypot(recovered.x, recovered.y)).isWithin(1e-5).of(0.4)
        }
    }

    @Test
    fun putsTheCommonCentreAtTheOrigin() {
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(h)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)

        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!
        val centre = result.imageToTarget.mapPoint(h.mapPoint(Vec2(0.0, 0.0))!!)!!

        assertThat(abs(centre.x)).isLessThan(1e-6)
        assertThat(abs(centre.y)).isLessThan(1e-6)
    }

    @Test
    fun preservesAnglesAroundTheCentre() {
        // Rotation is fixed by the image up direction, but the angle between two
        // target directions must survive regardless of which rotation was chosen.
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(h)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)
        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!

        val a = result.imageToTarget.mapPoint(h.mapPoint(Vec2(0.7, 0.0))!!)!!
        val b = result.imageToTarget.mapPoint(h.mapPoint(Vec2(0.0, 0.7))!!)!!
        val dot = a.x * b.x + a.y * b.y
        assertThat(abs(dot)).isLessThan(1e-5)
    }

    @Test
    fun frontalViewIsRecoveredAsAPureSimilarity() {
        val outer = Conic.circle(Vec2(640.0, 480.0), 300.0)
        val inner = Conic.circle(Vec2(640.0, 480.0), 120.0)

        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!
        val recovered = result.imageToTarget.mapPoint(Vec2(640.0 + 300.0, 480.0))!!

        assertThat(hypot(recovered.x, recovered.y)).isWithin(1e-6).of(1.0)
    }

    @Test
    fun returnsNullWhenTheConicsAreNotConcentricCircles() {
        val notACircle = Conic.fit(
            (0 until 12).map { i ->
                val a = 2.0 * PI * i / 12
                Vec2(300.0 * cos(a), 40.0 * sin(a) + 900.0 * cos(a))
            }
        )!!
        val other = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)
        assertThat(
            Rectification.fromConcentricCircles(notACircle, 1.0, other, 0.4)
        ).isNull()
    }

    @Test
    fun alignsImageUpAtTheFaceCentreWithTargetUp() {
        // Directions are not preserved globally by a projective map, so "image
        // up" has to be taken at the imaged centre of the face. A probe at the
        // image corner gives a rotation that is off by several degrees here.
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(hPixels)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(hPixels)
        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!

        val imagedCentre = hPixels.mapPoint(Vec2(0.0, 0.0))!!
        val up = result.imageToTarget.mapDirection(imagedCentre, Vec2(0.0, -1.0))!!

        // Straight up on the face is the negative y axis.
        assertThat(abs(up.x)).isLessThan(1e-6 * up.length)
        assertThat(up.y).isLessThan(0.0)
    }

    @Test
    fun toleratesHalfPixelNoiseOnFittedRings() {
        val random = Random(42)
        fun noisyRing(radius: Double): Conic = Conic.fit(
            circlePoints(radius, 60).map { p ->
                val q = hPixels.mapPoint(p)!!
                Vec2(q.x + random.nextDouble() - 0.5, q.y + random.nextDouble() - 0.5)
            }
        )!!

        val result = Rectification.fromConcentricCircles(
            noisyRing(1.0), 1.0, noisyRing(0.4), 0.4
        )!!

        // Prototype over 500 seeds: radius error below 0.002 target radii.
        for (radius in listOf(1.0, 0.4)) {
            for (p in circlePoints(radius, 37)) {
                val recovered = result.imageToTarget.mapPoint(hPixels.mapPoint(p)!!)!!
                assertThat(hypot(recovered.x, recovered.y)).isWithin(0.01).of(radius)
            }
        }
    }
}
```


- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*RectificationTest*"`
Expected: FAIL — `Unresolved reference: Rectification`.

- [ ] **Step 3: `Rectification.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Turns the images of two concentric target rings into the homography that maps
 * the photograph to target coordinates.
 *
 * The chain is: imaged centre and vanishing line from the pencil, affine
 * rectification from that line, metric rectification from the outer ellipse,
 * then scale and translation from the known radii and the centre. Rotation
 * about the target axis is not observable from concentric circles -- the face
 * is rotationally symmetric -- and is fixed by [imageUp], measured at the
 * imaged centre.
 */
object Rectification {

    class Result(
        val imageToTarget: Mat3,
        val vanishingLine: Vec3,
        val imagedCentre: Vec2
    )

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
    ): Result? {
        require(outerRadius > innerRadius) { "outer radius must be the larger one" }

        val pencil = VanishingLine.fromConcentricCircles(outer, inner) ?: return null
        val line = pencil.line

        // Affine rectification: send the vanishing line to (0, 0, 1).
        val affine = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            line.x, line.y, line.z
        )
        if (affine.inverse() == null) return null

        val affineOuter = outer.transformedBy(affine)
        val affineInner = inner.transformedBy(affine)

        // After affine rectification the conics are ellipses that differ from
        // circles by one common linear map. Recover it from the outer one.
        val metric = metricFromEllipse(affineOuter) ?: return null

        val metricOuter = affineOuter.transformedBy(metric)
        val metricInner = affineInner.transformedBy(metric)

        val centre = centreOf(metricOuter) ?: return null
        val centreInner = centreOf(metricInner) ?: return null
        if (centre.distanceTo(centreInner) > CONCENTRIC_TOLERANCE * radiusOf(metricOuter, centre)) {
            return null
        }

        val radius = radiusOf(metricOuter, centre)
        if (radius < 1e-9) return null

        // Check the radius ratio; if it is wrong these were not the rings we
        // were told they were.
        val expectedRatio = innerRadius / outerRadius
        val actualRatio = radiusOf(metricInner, centreInner) / radius
        if (abs(actualRatio - expectedRatio) > RATIO_TOLERANCE) return null

        val scale = outerRadius / radius
        val toOrigin = Mat3.translation(Vec2(-centre.x, -centre.y))
        val scaling = Mat3.of(
            scale, 0.0, 0.0,
            0.0, scale, 0.0,
            0.0, 0.0, 1.0
        )

        val withoutRotation = scaling * toOrigin * metric * affine

        // Fix the remaining rotation so that imageUp, taken at the imaged
        // centre, points along -y in target coordinates: upwards on the face.
        val rotation = rotationAligning(withoutRotation, pencil.imagedCentre, imageUp)
            ?: return null

        return Result(
            imageToTarget = rotation * withoutRotation,
            vanishingLine = line,
            imagedCentre = pencil.imagedCentre
        )
    }

    private const val CONCENTRIC_TOLERANCE = 0.05
    private const val RATIO_TOLERANCE = 0.08

    /**
     * A linear map taking the given ellipse to a circle. The ellipse matrix
     * restricted to its upper 2x2 block is symmetric positive definite; its
     * inverse square root is the map we want.
     */
    private fun metricFromEllipse(conic: Conic): Mat3? {
        val c = conic.normalized().matrix
        val block = arrayOf(
            doubleArrayOf(c[0, 0], c[0, 1]),
            doubleArrayOf(c[1, 0], c[1, 1])
        )
        val eigen = SymmetricEigen.decompose(block)
        val l0 = eigen.values[0]
        val l1 = eigen.values[1]
        // Both eigenvalues must share a sign for the conic to be an ellipse.
        if (l0 * l1 <= 0.0) return null
        val s = if (l0 > 0) 1.0 else -1.0
        val a0 = sqrt(s * l0)
        val a1 = sqrt(s * l1)
        if (a0 < 1e-12 || a1 < 1e-12) return null

        // block = V diag(l) V^T, so the map is diag(sqrt(l)) V^T.
        val v = eigen.vectors
        return Mat3.of(
            a0 * v[0][0], a0 * v[0][1], 0.0,
            a1 * v[1][0], a1 * v[1][1], 0.0,
            0.0, 0.0, 1.0
        )
    }

    /** Centre of a conic: the pole of the line at infinity. */
    private fun centreOf(conic: Conic): Vec2? =
        conic.pole(Vec3(0.0, 0.0, 1.0))?.toVec2()

    /** Radius of a circle-shaped conic, measured from its centre. */
    private fun radiusOf(conic: Conic, centre: Vec2): Double {
        val c = conic.normalized().matrix
        // For a x^2 + a y^2 + ... the radius follows from the constant term
        // after translating the centre to the origin.
        val a = c[0, 0]
        if (abs(a) < 1e-15) return 0.0
        val f = c[2, 2] + a * (centre.x * centre.x + centre.y * centre.y) +
            2.0 * (c[0, 2] * centre.x + c[1, 2] * centre.y)
        val r2 = -f / a
        return if (r2 <= 0.0) 0.0 else sqrt(r2)
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
    private fun rotationAligning(mapping: Mat3, imagedCentre: Vec2, imageUp: Vec2): Mat3? {
        val direction = mapping.mapDirection(imagedCentre, imageUp) ?: return null
        if (direction.length < 1e-12) return null
        // We want direction to end up pointing at -90 degrees.
        val current = atan2(direction.y, direction.x)
        return Mat3.rotation(-PI / 2.0 - current)
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*RectificationTest*"`
Expected: PASS, 7 Tests.

Der wahrscheinlichste Fehlschlag ist `preservesAnglesAroundTheCentre` mit einer Abweichung um Faktor zwei oder mit vertauschten Achsen. Ursache ist dann `metricFromEllipse`: Prüfe, ob `diag(sqrt(l)) V^T` statt `V diag(sqrt(l))` gebildet wurde. Schlägt nur `alignsImageUpAtTheFaceCentreWithTargetUp` fehl, wurde die Aufrechte nicht am Zentrum gemessen.

- [ ] **Step 5: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/geometry/Rectification.kt \
        detection/src/test/java/de/dreier/mytargets/detection/geometry/RectificationTest.kt
git commit -m "Rectify the target plane from two imaged concentric rings"
```

---

## Task 7: Fluchtpunkt der Scheibennormalen und Einschussende

Der Fluchtpunkt ist bei gegebener Fluchtlinie **eindeutig**: `v = (K·Kᵀ)·l`. Es gibt keine Vorzeichenwahl. Was unsicher ist, ist die *Entfernung* der Fluchtlinie bei nahezu frontaler Aufnahme; dann liegt `v` nahe dem Hauptpunkt und seine genaue Lage ist schlecht bestimmt. Damit umzugehen ist Aufgabe von Plan 3, der die Streifen gegen einen unsicheren Fluchtpunkt prüft. Dieser Task liefert die Rechnung und die Regel.

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/geometry/NormalVanishingPoint.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/geometry/NormalVanishingPointTest.kt`

**Interfaces:**
- Consumes: `Mat3`, `Vec2`, `Vec3`
- Produces:
  - `CameraIntrinsics(val focalLengthPx: Double, val principalPoint: Vec2)` mit `CameraIntrinsics.approximate(imageWidth: Int, imageHeight: Int, focalLengthPx: Double? = null)` und `CameraIntrinsics.from35mmEquivalent(imageWidth: Int, imageHeight: Int, focalLength35mm: Double)`
  - `Streak(val endA: Vec2, val endB: Vec2)` — die beiden Enden eines Schaftstreifens, Reihenfolge unbekannt
  - `NormalVanishingPoint.compute(vanishingLine: Vec3, intrinsics: CameraIntrinsics): Vec2?`
  - `NormalVanishingPoint.entryPoints(streaks: List<Streak>, vanishingPoint: Vec2): List<Vec2>` — für jeden Streifen das vom Fluchtpunkt weiter entfernte Ende

Zur Brennweite: Handy-Hauptkameras liegen bei 24 bis 28 mm Kleinbild-Äquivalent. Die Bilddiagonale als Rückfall entspräche etwa 45 mm und läge um Faktor 1.7 daneben. Plan 3 liest deshalb `FocalLengthIn35mmFilm` aus EXIF und rechnet `f_px = f35 / 36 · lange Kante`; der Rückfall ohne EXIF ist `0.75 · lange Kante`, das entspricht 27 mm.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NormalVanishingPointTest {

    @Test
    fun approximateIntrinsicsPutThePrincipalPointAtTheImageCentre() {
        val k = CameraIntrinsics.approximate(4000, 3000)
        assertThat(k.principalPoint.x).isWithin(1e-9).of(2000.0)
        assertThat(k.principalPoint.y).isWithin(1e-9).of(1500.0)
        // Fallback focal length: 0.75 times the long edge, about a 27 mm lens
        // in 35 mm terms, which is where phone main cameras sit.
        assertThat(k.focalLengthPx).isWithin(1e-9).of(3000.0)
    }

    @Test
    fun focalLengthFollowsFromThe35mmEquivalent() {
        // A 36 mm wide film frame spans the long edge of the image.
        val k = CameraIntrinsics.from35mmEquivalent(4000, 3000, focalLength35mm = 26.0)
        assertThat(k.focalLengthPx).isWithin(1e-6).of(26.0 / 36.0 * 4000.0)
        assertThat(k.principalPoint.x).isWithin(1e-9).of(2000.0)
    }

    @Test
    fun frontalViewHasItsNormalVanishingPointAtThePrincipalPoint() {
        val k = CameraIntrinsics.approximate(4000, 3000)
        // A frontal plane has the line at infinity as its vanishing line.
        val v = NormalVanishingPoint.compute(Vec3(0.0, 0.0, 1.0), k)!!
        assertThat(v.x).isWithin(1e-6).of(2000.0)
        assertThat(v.y).isWithin(1e-6).of(1500.0)
    }

    @Test
    fun aPlaneTiltedAboutTheHorizontalAxisMovesTheVanishingPointVertically() {
        val k = CameraIntrinsics.approximate(4000, 3000)
        // The line (0, 1, 1000) is y = -1000: horizontal, above the image
        // (image y grows downwards). The normal then vanishes on the opposite
        // side, below the principal point. With f = 3000 the value is
        // v = (2000, 5100).
        val v = NormalVanishingPoint.compute(Vec3(0.0, 1.0, 1000.0), k)!!
        assertThat(v.x).isWithin(1e-6).of(2000.0)
        assertThat(v.y).isWithin(1e-6).of(5100.0)
    }

    @Test
    fun entryPointIsTheEndFurtherFromTheVanishingPoint() {
        val vanishing = Vec2(0.0, 0.0)
        val streaks = listOf(
            Streak(endA = Vec2(10.0, 0.0), endB = Vec2(30.0, 0.0)),
            Streak(endA = Vec2(-40.0, 0.0), endB = Vec2(-15.0, 0.0))
        )
        val entries = NormalVanishingPoint.entryPoints(streaks, vanishing)

        assertThat(entries[0].x).isWithin(1e-9).of(30.0)
        assertThat(entries[1].x).isWithin(1e-9).of(-40.0)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*NormalVanishingPointTest*"`
Expected: FAIL — `Unresolved reference: CameraIntrinsics`.

- [ ] **Step 3: `NormalVanishingPoint.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection.geometry

import kotlin.math.max

/**
 * A pinhole camera reduced to what this pipeline needs: square pixels, no skew.
 */
class CameraIntrinsics(val focalLengthPx: Double, val principalPoint: Vec2) {

    /** K K^T, the dual image of the absolute conic for square pixels. */
    fun dualAbsoluteConic(): Mat3 {
        val f = focalLengthPx
        val cx = principalPoint.x
        val cy = principalPoint.y
        return Mat3.of(
            f * f + cx * cx, cx * cy, cx,
            cx * cy, f * f + cy * cy, cy,
            cx, cy, 1.0
        )
    }

    companion object {
        /**
         * Fallback focal length as a fraction of the long image edge. 0.75
         * corresponds to a 27 mm lens in 35 mm terms, which is where phone main
         * cameras sit (24 to 28 mm). The image diagonal, a common default, would
         * be a 45 mm lens and off by a factor of 1.7.
         */
        private const val FALLBACK_FOCAL_FRACTION = 0.75

        /** Width of a 35 mm film frame, the reference for EXIF's
         *  FocalLengthIn35mmFilm. */
        private const val FILM_WIDTH_MM = 36.0

        /**
         * Principal point at the image centre. [focalLengthPx] should come from
         * EXIF where available, see [from35mmEquivalent]; without it the long
         * edge times [FALLBACK_FOCAL_FRACTION] is used.
         */
        fun approximate(
            imageWidth: Int,
            imageHeight: Int,
            focalLengthPx: Double? = null
        ): CameraIntrinsics = CameraIntrinsics(
            focalLengthPx
                ?: FALLBACK_FOCAL_FRACTION * max(imageWidth, imageHeight),
            Vec2(imageWidth / 2.0, imageHeight / 2.0)
        )

        /**
         * From EXIF's FocalLengthIn35mmFilm: the film frame's 36 mm span the
         * long edge of the image.
         */
        fun from35mmEquivalent(
            imageWidth: Int,
            imageHeight: Int,
            focalLength35mm: Double
        ): CameraIntrinsics {
            require(focalLength35mm > 0.0) { "focal length must be positive" }
            val longEdge = max(imageWidth, imageHeight).toDouble()
            return approximate(
                imageWidth, imageHeight,
                focalLengthPx = focalLength35mm / FILM_WIDTH_MM * longEdge
            )
        }
    }
}

/** The two ends of an imaged arrow shaft, in no particular order. */
data class Streak(val endA: Vec2, val endB: Vec2)

/**
 * The vanishing point of the target plane's normal, and what it is for.
 *
 * An arrow sticks out of the face towards the camera. Any point raised above
 * the plane is imaged displaced *towards* the vanishing point of the normal, so
 * along a shaft the nock sits nearer that point than the entry hole does.
 *
 * Note that the homography alone does not give this point. It gives the
 * vanishing line l of the plane; the vanishing point of the normal is
 * v = (K K^T) l, so the camera intrinsics are required. The spec's phrasing
 * "follows from the homography" is a shortcut.
 *
 * Given l, v is unique -- there is no sign to choose. What is uncertain for a
 * nearly frontal view is how far away l is; v then sits close to the principal
 * point and its exact position is poorly determined. Checking the streaks
 * against an uncertain v is the perception pipeline's job.
 */
object NormalVanishingPoint {

    /** Null when the normal is parallel to the image plane, which cannot occur
     *  for a face that is visible at all. */
    fun compute(vanishingLine: Vec3, intrinsics: CameraIntrinsics): Vec2? =
        (intrinsics.dualAbsoluteConic() * vanishingLine).toVec2()

    /**
     * For each streak the end further from [vanishingPoint], which is the entry
     * point of the arrow.
     */
    fun entryPoints(streaks: List<Streak>, vanishingPoint: Vec2): List<Vec2> =
        streaks.map { streak ->
            if (streak.endA.distanceTo(vanishingPoint) >
                streak.endB.distanceTo(vanishingPoint)
            ) {
                streak.endA
            } else {
                streak.endB
            }
        }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*NormalVanishingPointTest*"`
Expected: PASS, 5 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/geometry/NormalVanishingPoint.kt \
        detection/src/test/java/de/dreier/mytargets/detection/geometry/NormalVanishingPointTest.kt
git commit -m "Compute the normal vanishing point and pick arrow entry ends"
```

---

## Task 8: Spot-Zuordnung und spot-lokale Umrechnung

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/FaceLayout.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/SpotMapping.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/SpotMappingTest.kt`

**Interfaces:**
- Consumes: `Vec2`
- Produces:
  - `FaceLayout(val facePositions: List<Vec2>, val faceRadius: Double)` mit `faceCount: Int`
  - `FaceLayout.singleSpot()` — eine Position `(0,0)`, Radius `1.0`
  - `SpotMapping.Located(val faceIndex: Int, val local: Vec2)`
  - `SpotMapping.locate(pointOnFace: Vec2, layout: FaceLayout): Located?` — null wenn der Punkt in keinem Spot liegt

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test

class SpotMappingTest {

    /** Mirrors WAVegas3Spot: three spots, radius 0.48. */
    private val threeSpot = FaceLayout(
        facePositions = listOf(
            Vec2(-0.52, 0.5),
            Vec2(0.0, -0.5),
            Vec2(0.52, 0.5)
        ),
        faceRadius = 0.48
    )

    @Test
    fun singleSpotLayoutIsTheIdentity() {
        val located = SpotMapping.locate(Vec2(0.3, -0.7), FaceLayout.singleSpot())!!
        assertThat(located.faceIndex).isEqualTo(0)
        assertThat(located.local.x).isWithin(1e-9).of(0.3)
        assertThat(located.local.y).isWithin(1e-9).of(-0.7)
    }

    @Test
    fun centreOfASpotMapsToTheSpotOrigin() {
        val located = SpotMapping.locate(Vec2(0.0, -0.5), threeSpot)!!
        assertThat(located.faceIndex).isEqualTo(1)
        assertThat(located.local.x).isWithin(1e-9).of(0.0)
        assertThat(located.local.y).isWithin(1e-9).of(0.0)
    }

    @Test
    fun edgeOfASpotMapsToUnitRadius() {
        // 0.48 to the right of the centre of spot 2.
        val located = SpotMapping.locate(Vec2(0.52 + 0.48, 0.5), threeSpot)!!
        assertThat(located.faceIndex).isEqualTo(2)
        assertThat(located.local.x).isWithin(1e-9).of(1.0)
        assertThat(located.local.y).isWithin(1e-9).of(0.0)
    }

    @Test
    fun pointBetweenSpotsIsOffTheFace() {
        assertThat(SpotMapping.locate(Vec2(0.0, 0.5), threeSpot)).isNull()
    }

    @Test
    fun picksTheNearestSpotWhenSpotsWouldOverlap() {
        val overlapping = FaceLayout(
            facePositions = listOf(Vec2(-0.2, 0.0), Vec2(0.2, 0.0)),
            faceRadius = 0.5
        )
        val located = SpotMapping.locate(Vec2(0.15, 0.0), overlapping)!!
        assertThat(located.faceIndex).isEqualTo(1)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*SpotMappingTest*"`
Expected: FAIL — `Unresolved reference: FaceLayout`.

- [ ] **Step 3: `FaceLayout.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection

import de.dreier.mytargets.detection.geometry.Vec2

/**
 * How the spots of a target face are arranged, in face coordinates.
 *
 * This deliberately mirrors `TargetModelBase.facePositions` and
 * `TargetModelBase.faceRadius` instead of using that class: those fields are
 * `List<PointF>`, and `PointF` is an Android type whose stub throws in plain
 * JVM unit tests. The conversion lives in the app module, where Android is
 * available anyway.
 */
data class FaceLayout(
    val facePositions: List<Vec2>,
    val faceRadius: Double
) {
    init {
        require(facePositions.isNotEmpty()) { "a face needs at least one spot" }
        require(faceRadius > 0.0) { "face radius must be positive" }
    }

    val faceCount: Int
        get() = facePositions.size

    companion object {
        /** A full face: one spot at the origin filling the whole face. */
        fun singleSpot() = FaceLayout(listOf(Vec2(0.0, 0.0)), 1.0)
    }
}
```

- [ ] **Step 4: `SpotMapping.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection

import de.dreier.mytargets.detection.geometry.Vec2

/**
 * Assigns a point on the face to a spot and converts it to that spot's local
 * coordinates, where the spot centre is the origin and its outermost ring has
 * radius one. Those are the coordinates `Shot.x` and `Shot.y` carry.
 */
object SpotMapping {

    class Located(val faceIndex: Int, val local: Vec2)

    /**
     * @return the spot containing [pointOnFace] and the local coordinates
     *         within it, or null when the point lies outside every spot -- an
     *         arrow next to the face.
     */
    fun locate(pointOnFace: Vec2, layout: FaceLayout): Located? {
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE

        layout.facePositions.forEachIndexed { index, centre ->
            val distance = pointOnFace.distanceTo(centre)
            if (distance <= layout.faceRadius && distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        if (bestIndex < 0) return null

        val centre = layout.facePositions[bestIndex]
        val local = (pointOnFace - centre) * (1.0 / layout.faceRadius)
        return Located(bestIndex, local)
    }
}
```

- [ ] **Step 5: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*SpotMappingTest*"`
Expected: PASS, 5 Tests.

- [ ] **Step 6: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/FaceLayout.kt \
        detection/src/main/java/de/dreier/mytargets/detection/SpotMapping.kt \
        detection/src/test/java/de/dreier/mytargets/detection/SpotMappingTest.kt
git commit -m "Assign detected points to spots and convert to spot local coordinates"
```

---

## Task 9: Kandidatenauswahl mit Abstandsregel

Setzt den Leitsatz der Spec um: Bei Unsicherheit lieber nichts eintragen als etwas Falsches.

Zwei Regeln, beide aus dem Review:

- **Pro Spot gilt eine Obergrenze, nicht "einer".** Das Datenmodell leitet den Spot aus `shot.index % faceCount` ab. Bei sechs Pfeilen auf einem 3-Spot gehören Index 0 und 3 legitim zum selben Spot. Die Grenze ist `ceil(shotsPerEnd / faceCount)`; bei der Vollauflage ist das `shotsPerEnd` und damit wirkungslos.
- **Die Abstandsregel läuft von hinten nach vorn.** Bei 0.70, 0.69, 0.68, 0.67 und zwei erwarteten Pfeilen ist der erste Platz genauso umstritten wie der zweite. Angenommen wird das größte `k ≤ expectedShots`, bei dem Kandidat `k` deutlich über Kandidat `k+1` liegt — notfalls `k = 0`.

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/CandidateSelection.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/CandidateSelectionTest.kt`

**Interfaces:**
- Consumes: `SpotMapping.Located`, `Vec2`
- Produces:
  - `Candidate(val faceIndex: Int, val local: Vec2, val confidence: Double)`
  - `SelectionOutcome(val accepted: List<Candidate>, val reason: SelectionReason)`
  - `enum class SelectionReason { COMPLETE, FEWER_THAN_EXPECTED, AMBIGUOUS_SURPLUS, SPOT_OVERFLOW }`
  - `CandidateSelection.select(candidates: List<Candidate>, expectedShots: Int, maxPerSpot: Int): SelectionOutcome`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test

class CandidateSelectionTest {

    private fun candidate(confidence: Double, faceIndex: Int = 0) =
        Candidate(faceIndex, Vec2(0.0, 0.0), confidence)

    @Test
    fun exactlyEnoughCandidatesAreAllAccepted() {
        val outcome = CandidateSelection.select(
            listOf(candidate(0.9), candidate(0.8), candidate(0.7)),
            expectedShots = 3,
            maxPerSpot = 3
        )
        assertThat(outcome.accepted).hasSize(3)
        assertThat(outcome.reason).isEqualTo(SelectionReason.COMPLETE)
    }

    @Test
    fun fewerCandidatesLeaveTheRestOpen() {
        val outcome = CandidateSelection.select(
            listOf(candidate(0.9), candidate(0.8)),
            expectedShots = 3,
            maxPerSpot = 3
        )
        assertThat(outcome.accepted).hasSize(2)
        assertThat(outcome.reason).isEqualTo(SelectionReason.FEWER_THAN_EXPECTED)
    }

    @Test
    fun surplusIsDroppedWhenTheConfidenceGapIsClear() {
        val outcome = CandidateSelection.select(
            listOf(candidate(0.95), candidate(0.92), candidate(0.20)),
            expectedShots = 2,
            maxPerSpot = 2
        )
        assertThat(outcome.accepted).hasSize(2)
        assertThat(outcome.reason).isEqualTo(SelectionReason.COMPLETE)
    }

    @Test
    fun surplusWithoutAClearGapLeavesTheContestedPlaceOpen() {
        val outcome = CandidateSelection.select(
            listOf(candidate(0.95), candidate(0.61), candidate(0.60)),
            expectedShots = 2,
            maxPerSpot = 2
        )
        // The second place is contested, so only the uncontested one is set.
        assertThat(outcome.accepted).hasSize(1)
        assertThat(outcome.accepted[0].confidence).isWithin(1e-9).of(0.95)
        assertThat(outcome.reason).isEqualTo(SelectionReason.AMBIGUOUS_SURPLUS)
    }

    @Test
    fun everythingContestedLeavesTheWholeEndOpen() {
        // Any two of these could be the real arrows; picking the first would be
        // as much of a coin toss as picking the second.
        val outcome = CandidateSelection.select(
            listOf(candidate(0.70), candidate(0.69), candidate(0.68), candidate(0.67)),
            expectedShots = 2,
            maxPerSpot = 2
        )
        assertThat(outcome.accepted).isEmpty()
        assertThat(outcome.reason).isEqualTo(SelectionReason.AMBIGUOUS_SURPLUS)
    }

    @Test
    fun perSpotCapDropsTheSurplusOnThatSpot() {
        val outcome = CandidateSelection.select(
            listOf(
                candidate(0.9, faceIndex = 0),
                candidate(0.8, faceIndex = 0),
                candidate(0.7, faceIndex = 1)
            ),
            expectedShots = 3,
            maxPerSpot = 1
        )
        assertThat(outcome.accepted).hasSize(2)
        assertThat(outcome.accepted.map { it.faceIndex }).containsExactly(0, 1)
        assertThat(outcome.reason).isEqualTo(SelectionReason.SPOT_OVERFLOW)
    }

    @Test
    fun twoArrowsPerSpotAreFineWhenTheEndHasSixShots() {
        // Six arrows on a three spot face: shot indices 0 and 3 share spot 0.
        val outcome = CandidateSelection.select(
            (0 until 6).map { i -> candidate(0.9 - 0.05 * i, faceIndex = i % 3) },
            expectedShots = 6,
            maxPerSpot = 2
        )
        assertThat(outcome.accepted).hasSize(6)
        assertThat(outcome.reason).isEqualTo(SelectionReason.COMPLETE)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*CandidateSelectionTest*"`
Expected: FAIL — `Unresolved reference: Candidate`.

- [ ] **Step 3: `CandidateSelection.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection

import de.dreier.mytargets.detection.geometry.Vec2

/** A possible arrow, already located on a spot. */
data class Candidate(
    val faceIndex: Int,
    val local: Vec2,
    val confidence: Double
)

enum class SelectionReason {
    /** As many arrows as the round expects, all of them unambiguous. */
    COMPLETE,

    /** Fewer arrows found than expected; the remaining places stay open. */
    FEWER_THAN_EXPECTED,

    /** More candidates than places, and the surplus is too close to call. */
    AMBIGUOUS_SURPLUS,

    /**
     * A spot had more candidates than the end can store on it. The data model
     * derives the spot from the shot index, so a spot holds at most
     * ceil(shotsPerEnd / faceCount) arrows.
     */
    SPOT_OVERFLOW
}

class SelectionOutcome(
    val accepted: List<Candidate>,
    val reason: SelectionReason
)

/**
 * Decides which candidates actually become shots.
 *
 * The guiding rule from the spec: when in doubt write nothing rather than
 * something wrong. A missed arrow costs the archer two seconds; an invented one
 * corrupts the statistics for good.
 */
object CandidateSelection {

    /**
     * A candidate is only accepted over the next one down when it is clearly
     * more confident. "Clearly" is this much of the confidence scale.
     */
    private const val REQUIRED_CONFIDENCE_GAP = 0.15

    /**
     * @param maxPerSpot how many arrows one spot can hold in this end,
     *        ceil(shotsPerEnd / faceCount); equal to [expectedShots] for a
     *        single spot face, where it has no effect
     */
    fun select(
        candidates: List<Candidate>,
        expectedShots: Int,
        maxPerSpot: Int
    ): SelectionOutcome {
        require(maxPerSpot > 0) { "a spot holds at least one arrow" }
        val ranked = candidates.sortedByDescending { it.confidence }

        // Per spot cap first, keeping the most confident candidates per spot.
        val perSpotCount = mutableMapOf<Int, Int>()
        val capped = mutableListOf<Candidate>()
        var overflow = false
        for (c in ranked) {
            val n = perSpotCount.getOrDefault(c.faceIndex, 0)
            if (n >= maxPerSpot) {
                overflow = true
            } else {
                perSpotCount[c.faceIndex] = n + 1
                capped += c
            }
        }

        val outcome = selectByConfidence(capped, expectedShots)
        return if (overflow) {
            // The overflow is what the user needs to hear about; it explains
            // both an open place and a surplus.
            SelectionOutcome(outcome.accepted, SelectionReason.SPOT_OVERFLOW)
        } else {
            outcome
        }
    }

    private fun selectByConfidence(
        ranked: List<Candidate>,
        expectedShots: Int
    ): SelectionOutcome {
        if (ranked.size < expectedShots) {
            return SelectionOutcome(ranked, SelectionReason.FEWER_THAN_EXPECTED)
        }
        if (ranked.size == expectedShots) {
            return SelectionOutcome(ranked, SelectionReason.COMPLETE)
        }

        // Walk down from the last place: accept the largest k for which the
        // k-th candidate is clearly above the (k+1)-th. Every place below a
        // contested one is contested as well, so nothing above k is safe
        // either until a clear gap appears.
        var k = expectedShots
        while (k > 0 && ranked[k - 1].confidence - ranked[k].confidence < REQUIRED_CONFIDENCE_GAP) {
            k--
        }

        return if (k == expectedShots) {
            SelectionOutcome(ranked.take(k), SelectionReason.COMPLETE)
        } else {
            // Contested places stay open rather than being filled by a coin toss.
            SelectionOutcome(ranked.take(k), SelectionReason.AMBIGUOUS_SURPLUS)
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*CandidateSelectionTest*"`
Expected: PASS, 7 Tests.

- [ ] **Step 5: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/CandidateSelection.kt \
        detection/src/test/java/de/dreier/mytargets/detection/CandidateSelectionTest.kt
git commit -m "Select candidates by confidence with a margin rule"
```

---

## Task 10: Schnittstelle `ArrowDetector` und Abschluss

Legt die Typen fest, an denen sich die Pläne 3 und 4 orientieren. Noch ohne Implementierung — die kommt aus den Wahrnehmungsstufen.

**Files:**
- Create: `detection/src/main/java/de/dreier/mytargets/detection/ArrowDetector.kt`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/ArrowDetectorContractTest.kt`

**Interfaces:**
- Consumes: `Candidate`, `SelectionReason`, `FaceLayout`, `CameraIntrinsics`
- Produces:
  - `DetectedShot(val faceIndex: Int, val x: Float, val y: Float, val confidence: Float)`
  - `enum class DetectionFailure { FACE_NOT_FOUND, FACE_MISMATCH }`
  - `DetectionResult(val shots: List<DetectedShot>, val faceConfidence: Float, val reason: SelectionReason?, val failure: DetectionFailure?)` mit `DetectionResult.failed(DetectionFailure)`
  - `interface ArrowDetector { fun detect(request: DetectionRequest): DetectionResult }`
  - `DetectionRequest(val layout: FaceLayout, val zoneRadii: List<Double>, val expectedShots: Int, val intrinsics: CameraIntrinsics)` mit abgeleitetem `maxArrowsPerSpot: Int`

Das Bitmap fehlt hier bewusst: `DetectionRequest` bleibt android-frei, damit die Vertragstests im JVM laufen. Plan 3 ergänzt eine Unterschnittstelle mit dem Bild.

`maxArrowsPerSpot` wird nicht übergeben, sondern aus `expectedShots` und `layout.faceCount` gerechnet: `ceil(expectedShots / faceCount)`. Es gibt nur eine richtige Antwort, und die soll niemand von Hand falsch setzen.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

```kotlin
package de.dreier.mytargets.detection

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test

class ArrowDetectorContractTest {

    private val request = DetectionRequest(
        layout = FaceLayout.singleSpot(),
        zoneRadii = listOf(0.2, 0.4, 0.6, 0.8, 1.0),
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
            override fun detect(request: DetectionRequest) = DetectionResult(
                shots = listOf(DetectedShot(0, 0.1f, -0.2f, 0.9f)),
                faceConfidence = 0.8f,
                reason = SelectionReason.FEWER_THAN_EXPECTED,
                failure = null
            )
        }
        val result = stub.detect(request)
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
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*ArrowDetectorContractTest*"`
Expected: FAIL — `Unresolved reference: DetectionRequest`.

- [ ] **Step 3: `ArrowDetector.kt` schreiben**

```kotlin
package de.dreier.mytargets.detection

import de.dreier.mytargets.detection.geometry.CameraIntrinsics

/**
 * One detected arrow, in the coordinates `Shot` stores: spot local, centre at
 * the origin, outermost ring at radius one.
 */
data class DetectedShot(
    val faceIndex: Int,
    val x: Float,
    val y: Float,
    val confidence: Float
)

enum class DetectionFailure {
    /** No target face could be located in the photo. */
    FACE_NOT_FOUND,

    /** A face was found, but its spot count does not match the configured target. */
    FACE_MISMATCH
}

class DetectionResult(
    val shots: List<DetectedShot>,
    val faceConfidence: Float,
    val reason: SelectionReason?,
    val failure: DetectionFailure?
) {
    companion object {
        fun failed(failure: DetectionFailure) = DetectionResult(
            shots = emptyList(),
            faceConfidence = 0f,
            reason = null,
            failure = failure
        )
    }
}

/**
 * Everything the detector needs that is not the image itself.
 *
 * Deliberately free of Android types so the contract can be exercised in plain
 * JVM tests. The image is supplied by the platform specific sub interface that
 * plan 3 adds.
 */
data class DetectionRequest(
    val layout: FaceLayout,
    val zoneRadii: List<Double>,
    val expectedShots: Int,
    val intrinsics: CameraIntrinsics
) {
    init {
        require(zoneRadii.isNotEmpty()) { "at least one zone radius is required" }
        require(zoneRadii.all { it > 0.0 }) { "zone radii must be positive" }
        require(expectedShots > 0) { "an end has at least one shot" }
    }

    /**
     * How many arrows one spot can hold in this end. The app derives the spot
     * of a shot from `index % faceCount`, so with six shots on three spots the
     * indices 0 and 3 share spot 0. Rounded up: four shots on three spots put
     * two on one of them.
     */
    val maxArrowsPerSpot: Int
        get() = (expectedShots + layout.faceCount - 1) / layout.faceCount
}

/**
 * Finds arrows in a photograph of a target face.
 *
 * The interface is narrow on purpose: it is the seam along which the classical
 * pipeline can later be replaced by a learned detector without touching the
 * app.
 */
interface ArrowDetector {
    fun detect(request: DetectionRequest): DetectionResult
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg bestätigen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*ArrowDetectorContractTest*"`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Alle Tests des Moduls laufen lassen**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS, 56 Tests insgesamt (6 + 4 + 7 + 5 + 6 + 7 + 5 + 5 + 7 + 4).

- [ ] **Step 6: Prüfen, dass die App weiterhin baut**

Run: `./gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL. Das Modul hängt noch an keinem anderen, aber ein neuer Gradle-Eintrag kann die Auflösung stören; das fällt hier auf, nicht erst in Plan 4.

- [ ] **Step 7: Committen**

```bash
git add detection/src/main/java/de/dreier/mytargets/detection/ArrowDetector.kt \
        detection/src/test/java/de/dreier/mytargets/detection/ArrowDetectorContractTest.kt
git commit -m "Define the ArrowDetector seam and its android free request type"
```

---

## Was dieser Plan nicht liefert

Damit beim Abnehmen klar ist, wo die Grenze verläuft:

- **Kein Bild wird verarbeitet.** Segmentierung, Farbabgleich, Residuum und Schaftfindung sind Plan 3.
- **Der Umgang mit einem unsicheren Fluchtpunkt ist nicht implementiert.** Task 7 rechnet den Fluchtpunkt aus Fluchtlinie und Kameramatrix; bei nahezu frontaler Aufnahme ist die Fluchtlinie schlecht bestimmt und der Punkt entsprechend unsicher. Die Prüfung der Streifen dagegen (Konsistenz der Richtungen, Befiederungsmerkmal) braucht echte Streifen und gehört zu Plan 3.
- **Die Anbindung an `:shared` fehlt.** `FaceLayout` ist noch nicht aus `TargetModelBase` befüllt, `zoneRadii` nicht aus den Zonen gelesen, die Brennweite nicht aus EXIF. Das ist Plan 4 beziehungsweise Plan 3.
- **Keine Kennzahlen.** Ohne Korpus gibt es nichts zu messen.

## Self-Review

**Spec-Abdeckung.** Von den Stufen der Spec deckt dieser Plan die Geometrie ab: Stufe 2 (Kegelschnittfit, Registrierung) in Task 3, 5 und 6, Stufe 6 (Fluchtpunkt) in Task 7, Stufe 7 (Spot-Zuordnung, Auswahl) in Task 8 und 9. Die Schnittstelle aus dem Abschnitt *Schnittstelle* steht in Task 10, angepasst um `intrinsics` und `zoneRadii`, die in der Spec fehlten. Stufen 1, 3, 4, 5 sowie Integration und Korpus sind ausdrücklich den Plänen 2 bis 4 zugewiesen.

**Abweichungen von der Spec, jeweils im Code begründet:**
1. Zentrum und Fluchtlinie über das einfache Mitglied des Kegelschnittbüschels statt über den Schnitt zweier Kegelschnitte (Task 5).
2. `CameraIntrinsics` als Pflichtparameter für den Fluchtpunkt (Task 7) — die Spec unterschlägt, dass `K` gebraucht wird.
3. `FaceLayout` statt `TargetModelBase`, um Android aus den Tests zu halten (Task 8).
4. Obergrenze pro Spot statt "ein Pfeil pro Spot" (Task 9 und 10); die Spec wurde entsprechend nachgezogen.

**Typkonsistenz.** `Vec2`/`Vec3`/`Mat3` durchgehend `Double`; `DetectedShot` wechselt bewusst auf `Float`, weil `Shot.x`/`Shot.y` `Float` sind — die Umwandlung passiert an genau einer Stelle, in Task 10. `SelectionReason` wird in Task 9 definiert und in Task 10 verwendet, nicht umgekehrt. `Candidate` (intern, `Double`) und `DetectedShot` (Ausgabe, `Float`) sind absichtlich verschieden.

**Offener Punkt, der beim Ausführen auffallen wird.** `REQUIRED_CONFIDENCE_GAP = 0.15` ist geraten, im Code als solches markiert und gehört zu den Werten, die Plan 2 am Korpus festschreibt. Die Toleranzen der Rauschtests sind dagegen gemessen, nicht geraten.

## Änderungen nach Review

Am 2026-09-09 gegen den Code und mit einem numerischen Prototyp geprüft. Geändert wurde:

1. **Task 5** sucht nicht mehr das Rang-1-Mitglied an der Doppelnullstelle, sondern das Rang-2-Mitglied an der einfachen Nullstelle. Der alte Weg fiel im Prototyp schon bei exakten Eingaben aus, weil die Doppelnullstelle durch Rundung zu einem komplexen Paar wurde. Der neue Weg liefert nebenbei das abgebildete Zentrum, arbeitet in einem normierten Koordinatenrahmen und braucht keine geratene Toleranz mehr.
2. **Task 6** misst die Bildaufrechte am abgebildeten Zentrum statt an der Bildecke; dafür bekam `Mat3` in Task 1 ein `mapDirection`. Neuer Test dafür und ein Rauschtest.
3. **Task 7:** Der Test zur geneigten Ebene hatte die Fluchtlinie auf der falschen Seite und wäre fehlgeschlagen. Brennweiten-Rückfall von Bilddiagonale auf 0.75·lange Kante, dazu `from35mmEquivalent` für EXIF. `Streak`-Felder heißen `endA`/`endB`. Die Rede von einer "Vorzeichenwahl" ist gestrichen; der Fluchtpunkt ist eindeutig, unsicher ist nur seine Lage bei frontaler Aufnahme.
4. **Task 9 und 10:** Obergrenze pro Spot `ceil(shotsPerEnd / faceCount)` statt "höchstens einer", abgeleitet im `DetectionRequest`. Abstandsregel läuft von hinten nach vorn.
5. **Kleinigkeiten:** `targetSdk` aus dem Katalog, `testInstrumentationRunner` entfernt, Sweep-Schleife in `SymmetricEigen` mit `break`, Testname in Task 3.
