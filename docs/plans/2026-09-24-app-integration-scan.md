# App-Integration 8b: Scan aus der Eingabe — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aus der Passeneingabe heraus ein Foto der Auflage machen, es als 2048-px-Kopie an die Passe hängen, die Pfeile erkennen und in die aktuelle Passe eintragen — mit Rückfrage, sperrendem Fortschrittsdialog, Meldung je Ergebnis und Rückgängig, robust gegen Drehen und Prozesstod.

**Architecture:** Drei reine, JVM-getestete Bausteine (`EndPhotoSize` für die Größenrechnung, `ScanApplication` für „was wird eingetragen, was wird gemeldet“, `InputMethodChoice` für die Plotting-Regel), zwei kleine Android-Bausteine (`EndPhotoStore` schreibt die 2048-px-Kopie, `PendingScan` ist der einzige Zustand über den Kamera-Ausflug) und darüber `EndScanFlow`, das den Ablauf in `InputActivity` führt. `InputActivity` ruft `EndScanFlow` an wenigen Stellen und stellt ihm über `EndScanFlow.Host` Daten, Ringwert und Eintragen bereit. Der Scan selbst ist `EndPhotoScanner` aus 8a, unverändert.

**Tech Stack:** Kotlin, Android (minSdk 23), EasyImage 2.1.0 (Kamera), material-dialogs 0.9.6 (`MaterialDialog.Builder`), Material `Snackbar`, `androidx.exifinterface`, `lifecycleScope` + kotlinx-coroutines (`Mutex`), Room, JUnit 4 + kotlin.test (JVM), AndroidX Test + Truth + espresso-intents (Gerät).

**Spec:** `docs/design/2026-09-24-app-integration-scan-design.md` (bindend), dazu das Haupt-Design `docs/design/2026-09-09-arrow-detection-design.md` (*Integration in die App*) und die 8a-Spec `docs/design/2026-09-23-app-integration-foundation-design.md` (`ScanOutcome`, Abbruchlogik).

**Voraussetzung:** Branch `plan/app-integration-scan` von `master`, nachdem der Design-Branch `design/app-integration-scan` (Spec und dieser Plan) gemergt ist. Gradle braucht `JAVA_HOME` auf ein JDK 17 (siehe `BUILDING.md`); alle Befehle laufen aus der Wurzel des Repos. Gerätetests auf dem S25 (USB-Debugging, hinterer USB-Port), `adb` unter `D:/AndroidSDK/platform-tools/adb.exe`. Das Korpusfoto `2026-08-15_bedeckt_frontal_02.jpg` liegt (nicht eingecheckt) in `app/src/androidTest/assets/scan/`, siehe `app/src/androidTest/README.md`.

## Global Constraints

- Leitsatz: **Bei Unsicherheit lieber nichts eintragen als etwas Falsches.**
- Menüpunkt `action_scan_end` im Überlauf (`showAsAction="never"`), sichtbar genau dann, wenn `action_photo` sichtbar ist (`Utils.hasCameraHardware`) **und** `ScanSupport.supports(d.currentRound.round.target)`.
- Aufnahme nur über `EasyImage.openCameraForImage(activity, 0)`; `ActivityNotFoundException` → Toast `no_camera_app`.
- Kamera-Berechtigung wie `GalleryActivity`: `PermissionUtils.hasCameraPermission` / `requestCameraPermission`, Fortsetzung in `onRequestPermissionsResult` bei `PermissionUtils.REQUEST_CAMERA`. Der pending scan entsteht erst nach erteilter Erlaubnis.
- Einziger Zustand über den Kamera-Ausflug ist `PendingScan(endId, originalPath, storedImageName)` in `outState`; kein ViewModel, kein WorkManager.
- Foto immer an der Passe als `EndImage`, JPEG Qualität **90**, lange Kante genau **2048 px** (kleinere Fotos behalten ihre Größe), EXIF-Orientierung in die Pixel gedreht, **kein** Orientierungstag in der Datei; kurze Kante mit `Math.round`.
- `inSampleSize` = größte Zweierpotenz, bei der die lange Kante noch **mindestens 2048 px** hat (ab 4096 px Faktor 2).
- Erst ablegen, dann scannen; die Ablage läuft über `BitmapFactory`, nie über OpenCV.
- Sperrender Dialog: modal, `cancelable(false)`, nur „Abbrechen“ beendet ihn.
- Eingetragene Passen bekommen `exact = true`; Passen mit `exact` und gesetzten Treffern zeigen immer Plotting.
- Rückgängig nimmt nur die Treffer (und `exact`) zurück, nie das Foto.
- Strings auf Englisch (`values/`) und Deutsch (`values-de/`); die übrigen Sprachen fallen auf Englisch zurück.
- Keine neue Abhängigkeit (espresso-intents, `androidx.exifinterface`, `lifecycle-runtime-ktx`, Timber sind da).
- Außerhalb von 8b: Sidecar/Homographie (8c), Nachscannen aus der Galerie (8d), andere Auflagen, Konfidenz-Einfärbung.
- Code, Kommentare, Testnamen auf Englisch; neue Dateien mit dem GPL-Kopf „Copyright (C) 2026 MyTargets contributors“ wie `ScanSupport.kt`.

## Präzisierungen gegenüber der Spec

Ergebnis der Durchsicht der überarbeiteten Spec gegen den Code. Die Punkte 1 bis 5 schließen Lücken, in denen die Spec wörtlich umgesetzt Daten verlöre oder falsch meldete; Task 8 trägt sie als Nachtrag in die Spec.

1. **`ScanApplication` bekommt die aktuellen Treffer, nicht `shotsPerEnd`.** `EndDAO.updateShots` ist ein `@Update` über den Primärschlüssel. Neue `Shot`-Objekte (id 0) würden nie geschrieben; die Passe sähe richtig aus und wäre nach dem Neuladen leer. `ScanApplication.apply(outcome, current, zoneAt)` kopiert deshalb die vorhandenen Treffer (`id`, `index`, `endId` bleiben) und setzt nur `x`, `y`, `scoringRing`, `arrowNumber = null`. Die Liste hat damit so viele Einträge wie die Passe, also `shotsPerEnd`.
2. **Das Ablegen ist idempotent, nicht nur über `storedImageName`.** `withContext(Dispatchers.IO)` lässt sich nicht abbrechen, und `onSaveInstanceState` läuft vor dem Ende des Jobs: Wird beim Ablegen gedreht (oder der Prozess stirbt, nachdem der Zustand gesichert war), steht im Bundle `storedImageName == null`, obwohl der alte Job das Bild noch einfügt — ein zweites Bild. Deshalb: Der Dateiname ist fest aus dem Original abgeleitet (`EndPhotoStore.nameFor(original) = "scan-<Originalname>.jpg"`), vor dem Ablegen fragt `EndDAO.countEndImages(endId, name)`, und ein prozessweiter `Mutex` macht Prüfen, Schreiben und Einfügen zu einem Schritt. `storedImageName` bleibt als Abkürzung.
3. **Ein Lauf ohne Original wird still verworfen.** Drückt der Nutzer während des Scans „Home“, sichert `onSaveInstanceState` den pending scan, der Job läuft zu Ende, trägt ein und löscht das Original. Stirbt danach der Prozess, startet der gesicherte pending scan neu und meldete „Foto konnte nicht gelesen werden“, obwohl alles eingetragen ist. `resume()` verwirft einen pending scan, dessen Original fehlt, ohne Meldung (`Timber.i`).
4. **EasyImage kann auch einen Fehler melden** (`onImagePickerError`, z. B. wenn der Pfad aus den `SharedPreferences` nicht mehr stimmt). Dann: pending scan verwerfen, „Foto konnte nicht gelesen werden“. Sonst bliebe ein pending scan ohne Foto stehen.
5. **Rückgängig ist an seine Passe gebunden.** Die Snackbar überlebt ein Blättern zur nächsten Passe; die Aktion hätte dann die alten Treffer in die neue Passe geschrieben. `showEnd` schließt die Snackbar (`EndScanFlow.onEndChanged()`), und die Aktion prüft zusätzlich die `endId`.
6. **Eintragen läuft über `replaceWithEnd`, ohne eigenes `saveCurrentEnd()`.** `TargetView.replaceWithEnd` ruft `notifyTargetShotsChanged` → `onEndUpdated`, und das setzt `d.currentEnd.shots`, speichert und rechnet die Summen. `InputActivity.enterShots` setzt vorher `end.exact` und ruft danach nur noch Wear, Navigationsknöpfe und Menü wie `onEndFinished`. Rückgängig nimmt denselben Weg.
7. **Die Bilder der Passe kommen nach dem Ablegen aus der Datenbank.** Die Galerie schreibt mit `replaceImages` die Liste im Speicher als Wahrheit zurück; fehlte darin das Scan-Bild, löschte sie dessen Zeile. Nach dem Ablegen setzt `EndScanFlow` `d.currentEnd.images = endDAO.loadEndImages(endId)`, wenn `endId` die aktuelle Passe ist.
8. **`inputMethodFor` wohnt in `InputMethodChoice`, nicht in `TargetView`.** Ein Aufruf über `TargetView` lädt die Klasse, und deren statische `Property`-Objekte werfen in JVM-Tests „Stub!“.
9. **Die Größenrechnung ist das eigene Objekt `EndPhotoSize`**, `EndPhotoStore` nutzt sie. Die Orientierungsmatrix (alle acht Werte) prüft ein Gerätetest mit einem synthetischen Bild je Orientierung, nicht nur das Korpusfoto (Orientierung 6).
10. **Abbrechen schließt den Dialog sofort**, die Aufräumarbeit (Original löschen, pending scan verwerfen) läuft, wenn der laufende Schritt zurückkehrt (Ablegen oder Vorwärtslauf, bis rund 3 s). Ein neuer Scan kann sofort beginnen; der alte Lauf räumt nur seinen eigenen pending scan und sein eigenes Original weg.
11. **„Foto an Passe N abgelegt“** zählt N als `index + 1` der Passe mit `endId` über alle Runden des Trainings. Gibt es sie nicht mehr, heißt es „Die Passe gibt es nicht mehr, nichts eingetragen“ (`scan_end_gone`).
12. **Mengenangaben mit Plural:** `scan_replace_question` und `scan_complete` sind `<plurals>`.
13. **Meldungen mit Aktion bleiben acht Sekunden.** `LENGTH_LONG` sind knapp drei Sekunden, zu kurz für Rückgängig oder Neu aufnehmen; die Undo-Kopie lebt so lange wie die Snackbar. Nicht `LENGTH_INDEFINITE`, weil die Snackbar die Knopfleiste unten überdeckt. Meldungen ohne Aktion bleiben bei `LENGTH_LONG`.
14. **Handtest mit ausgeschaltetem Timer.** Nach Prozesstod mit aktivem Timer öffnet `showEnd` den Timer, bevor `resume()` den Scan startet, weil die Passe ohne Bild noch als leer gilt; beides läuft dann parallel. Der Gerätetest schaltet den Timer aus, der Handtest ebenso.

## Review Focus

1. **Blättern zur nächsten Passe, solange „Rückgängig“ noch zu sehen ist**: Erwartung: die Snackbar verschwindet, die neue Passe bleibt unberührt. Gepinnt in `EndScanFlowTest.undoIsGoneAfterMovingToTheNextEnd` (Task 7).
2. **Kamera meldet `RESULT_OK`, liefert aber kein Bild** (leere Datei, Kamera-App bricht ab): Erwartung: nichts eingetragen, kein Bild an der Passe, Meldung „Foto konnte nicht gelesen werden“, kein Absturz. Gepinnt in `EndScanFlowTest.aCameraThatWritesNothingEntersNothing` (Task 7).
3. **Drehen oder Prozesstod mitten im Ablegen**: Erwartung: genau ein Bild an der Passe. Gepinnt in `EndPhotoStoreDeviceTest.storingTwiceWritesOneFile` (Task 2) und `EndDAOTest.countEndImagesFindsAnImageByEndAndName` (Task 5); das Zusammenspiel prüft die Handtest-Checkliste (Task 8).
4. **Gespiegelte oder um 180° gedrehte Fotos** (EXIF 2, 3, 4, 5, 7): Erwartung: das abgelegte Foto sieht aus wie in jeder Galerie. Gepinnt in `EndPhotoStoreDeviceTest.everyExifOrientationEndsUpright` (Task 2).
5. **Eine Passe mit Treffern und vergebener Datenbank-id**: Erwartung: nach dem Scan stehen die neuen Treffer in genau diesen Zeilen, nach Neustart der Eingabe noch da. Gepinnt in `ScanApplicationTest.theShotsKeepTheirIdentity` (Task 4) und im Datenbankcheck von `EndScanFlowTest.aScannedEndLandsInTheDatabaseWithItsPhoto` (Task 7).

## Dateien

| Datei | Aufgabe |
|---|---|
| `app/.../features/detection/EndPhotoSize.kt` | Neu: Größenrechnung (rein) |
| `app/.../features/detection/EndPhotoStore.kt` | Neu: 2048-px-Kopie schreiben |
| `app/.../features/detection/ScanApplication.kt` | Neu: `ScanApplication`, `ScanReport`, `ScanMessage` (rein) |
| `app/.../features/training/input/InputMethodChoice.kt` | Neu: Plotting-Regel (rein) |
| `app/.../features/training/input/TargetView.kt` | Ändern: `replaceWithEnd` über `InputMethodChoice`, `zoneAt` |
| `app/.../features/training/input/PendingScan.kt` | Neu: Zustand mit `toBundle`/`fromBundle` |
| `app/.../features/training/input/EndScanFlow.kt` | Neu: der Ablauf |
| `app/.../features/training/input/InputActivity.kt` | Ändern: Host, Menü, Ergebnisse, Zustand |
| `app/.../base/db/dao/EndDAO.kt` | Ändern: `countEndImages` |
| `app/src/main/res/menu/input_end.xml` | Ändern: `action_scan_end` |
| `app/src/main/res/values/strings.xml`, `values-de/strings.xml` | Ändern: Scan-Texte |
| `app/src/test/.../features/detection/EndPhotoSizeTest.kt`, `ScanApplicationTest.kt` | Neu: JVM-Tests |
| `app/src/test/.../features/training/input/InputMethodChoiceTest.kt` | Neu: JVM-Test |
| `app/src/androidTest/.../features/detection/EndPhotoStoreDeviceTest.kt` | Neu: Gerätetest |
| `app/src/androidTest/.../features/training/input/PendingScanTest.kt`, `EndScanFlowTest.kt` | Neu: Gerätetests |
| `app/src/androidTest/.../dao/EndDAOTest.kt` | Ändern: `countEndImages` |
| `app/src/androidTest/README.md` | Ändern: neue Gerätetests |
| `docs/design/2026-09-24-app-integration-scan-design.md` | Ändern: Nachtrag (Task 8) |

`app/...` steht für `app/src/main/java/de/dreier/mytargets`, `app/src/test/...` für `app/src/test/java/de/dreier/mytargets`, `app/src/androidTest/...` für `app/src/androidTest/java/de/dreier/mytargets`.

Befehle (aus der Wurzel, `JAVA_HOME` auf JDK 17):
- JVM: `./gradlew :app:testDevDebugUnitTest --tests "<Muster>"`
- Gerät: `./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<voller Klassenname>`

---

### Task 1: Die Größenrechnung der Ablage

**Files:**
- Create: `app/src/main/java/de/dreier/mytargets/features/detection/EndPhotoSize.kt`
- Test: `app/src/test/java/de/dreier/mytargets/features/detection/EndPhotoSizeTest.kt`

**Interfaces:**
- Produces: `object EndPhotoSize { const val LONG_EDGE = 2048; data class Plan(val inSampleSize: Int, val width: Int, val height: Int); fun plan(width: Int, height: Int): Plan }` — `width`/`height` sind die **gedrehte** Größe (nach EXIF), `Plan.width`/`height` die Zielgröße in derselben Lage.

- [ ] **Step 1: Failing test schreiben**

```kotlin
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

package de.dreier.mytargets.features.detection

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EndPhotoSizeTest {

    @Test
    fun aSmallPhotoKeepsItsSize() =
        assertEquals(EndPhotoSize.Plan(1, 1280, 720), EndPhotoSize.plan(1280, 720))

    @Test
    fun aPhotoOfExactly2048IsLeftAlone() =
        assertEquals(EndPhotoSize.Plan(1, 2048, 1152), EndPhotoSize.plan(2048, 1152))

    @Test
    fun onePixelMoreIsScaledDownWithTheShortEdgeRounded() =
        // 1153 * 2048 / 2049 = 1152.44
        assertEquals(EndPhotoSize.Plan(1, 2048, 1152), EndPhotoSize.plan(2049, 1153))

    @Test
    fun theCorpusPhotoTurnedUprightBecomes1153By2048() =
        // 2252 * 2048 / 4000 = 1153.02; 4000 < 4096, so no subsampling
        assertEquals(EndPhotoSize.Plan(1, 1153, 2048), EndPhotoSize.plan(2252, 4000))

    @Test
    fun subsamplingStartsAt4096() {
        assertEquals(1, EndPhotoSize.plan(4095, 3071).inSampleSize)
        assertEquals(EndPhotoSize.Plan(2, 2048, 1536), EndPhotoSize.plan(4096, 3072))
    }

    @Test
    fun aFiftyMegapixelPhotoIsHalvedOnce() =
        // 8160 / 2 = 4080 >= 2048, 8160 / 4 = 2040 < 2048
        assertEquals(EndPhotoSize.Plan(2, 2048, 1536), EndPhotoSize.plan(8160, 6120))

    @Test
    fun anEmptySizeIsRejected() {
        assertFailsWith<IllegalArgumentException> { EndPhotoSize.plan(0, 100) }
    }
}
```

- [ ] **Step 2: Test laufen lassen, er scheitert**

Run: `./gradlew :app:testDevDebugUnitTest --tests "*EndPhotoSizeTest*"`
Expected: FAIL, `Unresolved reference: EndPhotoSize`.

- [ ] **Step 3: Implementieren**

```kotlin
/*
 * (GPL-Kopf wie in Step 1)
 */

package de.dreier.mytargets.features.detection

/**
 * The size of the photo a scan attaches to its end (app integration 8b,
 * EndPhotoStore): long edge exactly [LONG_EDGE] px, smaller photos keep
 * theirs. Pure, so the arithmetic is tested on the JVM.
 */
object EndPhotoSize {
    const val LONG_EDGE = 2048

    /** [width] and [height] in the same lie as the input, i.e. already turned upright. */
    data class Plan(val inSampleSize: Int, val width: Int, val height: Int)

    /**
     * @param width the photo's width after its EXIF orientation is applied
     * @param height its height after the EXIF orientation is applied
     */
    fun plan(width: Int, height: Int): Plan {
        require(width > 0 && height > 0) { "a photo has a positive size, got $width x $height" }
        val longEdge = maxOf(width, height)
        // The largest power of two that still leaves at least LONG_EDGE px to scale down from.
        var sample = 1
        while (longEdge / (sample * 2) >= LONG_EDGE) sample *= 2
        if (longEdge <= LONG_EDGE) return Plan(sample, width, height)
        val scale = LONG_EDGE.toDouble() / longEdge
        return if (width >= height) {
            Plan(sample, LONG_EDGE, Math.round(height * scale).toInt())
        } else {
            Plan(sample, Math.round(width * scale).toInt(), LONG_EDGE)
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen, er besteht**

Run: `./gradlew :app:testDevDebugUnitTest --tests "*EndPhotoSizeTest*"`
Expected: PASS (7 Tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/dreier/mytargets/features/detection/EndPhotoSize.kt app/src/test/java/de/dreier/mytargets/features/detection/EndPhotoSizeTest.kt
git commit -m "app: EndPhotoSize, the 2048 px size of the photo a scan attaches"
```

---

### Task 2: Die 2048-px-Kopie schreiben

**Files:**
- Create: `app/src/main/java/de/dreier/mytargets/features/detection/EndPhotoStore.kt`
- Test: `app/src/androidTest/java/de/dreier/mytargets/features/detection/EndPhotoStoreDeviceTest.kt`

**Interfaces:**
- Consumes: `EndPhotoSize.plan(width, height): EndPhotoSize.Plan` (Task 1).
- Produces: `object EndPhotoStore { const val JPEG_QUALITY = 90; fun nameFor(original: File): String; @Throws(IOException::class) fun store(original: File, dir: File): String }` — `store` schreibt nach `File(dir, nameFor(original))` (überschreibt), gibt den Dateinamen zurück, wirft `IOException`, wenn das Foto nicht dekodierbar ist oder nicht geschrieben werden kann, und lässt dann keine Datei zurück. `OutOfMemoryError` fängt der Aufrufer (Task 7).

- [ ] **Step 1: Failing test schreiben**

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * The photo a scan attaches to its end (app integration 8b): upright pixels,
 * 2048 px long edge, no orientation tag. The corpus photo is not in git
 * (README next to this directory); the synthetic cases always run.
 */
@RunWith(AndroidJUnit4::class)
class EndPhotoStoreDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun freshDirectory() {
        dir = File(context.cacheDir, "end-photo-store-test").apply { deleteRecursively(); mkdirs() }
    }

    @Test
    fun theCorpusPhotoIsStoredUprightAt2048() {
        val original = corpusPhoto()

        val name = EndPhotoStore.store(original, dir)

        assertThat(name).isEqualTo("scan-$VIEW.jpg")
        val stored = File(dir, name)
        val bounds = boundsOf(stored)
        // Raw 4000 x 2252 with EXIF 6: upright 2252 x 4000, scaled to 1153 x 2048.
        assertThat(bounds.outWidth).isEqualTo(1153)
        assertThat(bounds.outHeight).isEqualTo(2048)
        assertThat(ExifInterface(stored.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            .isEqualTo(ExifInterface.ORIENTATION_UNDEFINED)
    }

    @Test
    fun storingTwiceWritesOneFile() {
        val original = syntheticPhoto(ExifInterface.ORIENTATION_NORMAL)

        val first = EndPhotoStore.store(original, dir)
        val second = EndPhotoStore.store(original, dir)

        assertThat(second).isEqualTo(first)
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly(first, original.name)
    }

    @Test
    fun everyExifOrientationEndsUpright() {
        // Where the raw top-left corner (red) is seen once the orientation is applied.
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to Corner.TOP_LEFT,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to Corner.TOP_RIGHT,
            ExifInterface.ORIENTATION_ROTATE_180 to Corner.BOTTOM_RIGHT,
            ExifInterface.ORIENTATION_FLIP_VERTICAL to Corner.BOTTOM_LEFT,
            ExifInterface.ORIENTATION_TRANSPOSE to Corner.TOP_LEFT,
            ExifInterface.ORIENTATION_ROTATE_90 to Corner.TOP_RIGHT,
            ExifInterface.ORIENTATION_TRANSVERSE to Corner.BOTTOM_RIGHT,
            ExifInterface.ORIENTATION_ROTATE_270 to Corner.BOTTOM_LEFT
        )
        for ((orientation, corner) in expected) {
            val original = syntheticPhoto(orientation)
            val stored = BitmapFactory.decodeFile(File(dir, EndPhotoStore.store(original, dir)).path)
            val quarterTurn = orientation >= ExifInterface.ORIENTATION_TRANSPOSE
            assertWithMessage("size for orientation $orientation")
                .that(stored.width to stored.height).isEqualTo(if (quarterTurn) 20 to 40 else 40 to 20)
            for (c in Corner.values()) {
                val (x, y) = c.pixel(stored.width, stored.height)
                val red = Color.red(stored.getPixel(x, y)) > 200 && Color.blue(stored.getPixel(x, y)) < 80
                assertWithMessage("orientation $orientation, corner $c").that(red).isEqualTo(c == corner)
            }
            stored.recycle()
        }
    }

    @Test
    fun somethingThatIsNotAnImageLeavesNoFile() {
        val original = File(dir, "not-a-photo.jpg").apply { writeText("not a jpeg") }

        try {
            EndPhotoStore.store(original, dir)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            // the caller logs it and scans anyway
        }

        assertThat(File(dir, EndPhotoStore.nameFor(original)).exists()).isFalse()
    }

    private enum class Corner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        /** A pixel three pixels in from this corner, inside its quadrant. */
        fun pixel(width: Int, height: Int): Pair<Int, Int> = when (this) {
            TOP_LEFT -> 3 to 3
            TOP_RIGHT -> width - 4 to 3
            BOTTOM_LEFT -> 3 to height - 4
            BOTTOM_RIGHT -> width - 4 to height - 4
        }
    }

    /** 40 x 20 px, top-left quadrant red, the rest blue, with [orientation] as EXIF tag. */
    private fun syntheticPhoto(orientation: Int): File {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (y in 0 until 20) for (x in 0 until 40) {
            bitmap.setPixel(x, y, if (x < 20 && y < 10) Color.RED else Color.BLUE)
        }
        val file = File(dir, "synthetic-$orientation.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    private fun corpusPhoto(): File {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val file = File(dir, "$VIEW.jpg")
        try {
            testAssets.open("scan/$VIEW.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            assumeTrue("scan/$VIEW.jpg is not in the test assets, see app/src/androidTest/README.md", false)
        }
        return file
    }

    private fun boundsOf(file: File) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, this)
    }

    private companion object {
        const val VIEW = "2026-08-15_bedeckt_frontal_02"
    }
}
```

- [ ] **Step 2: Test laufen lassen, er scheitert**

Run: `./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.detection.EndPhotoStoreDeviceTest`
Expected: FAIL beim Kompilieren, `Unresolved reference: EndPhotoStore`.

- [ ] **Step 3: Implementieren**

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes the copy of a scan photo that stays at the end (app integration 8b):
 * EXIF orientation turned into the pixels, long edge 2048 px, JPEG 90, no
 * orientation tag (Bitmap.compress writes no EXIF). Through BitmapFactory, not
 * OpenCV, so attaching the photo depends neither on the model nor on OpenCV.
 * The pixels differ slightly from imread's (Skia against libjpeg), the
 * geometry does not; 8c scales the homography by the ratio of the widths.
 */
object EndPhotoStore {
    const val JPEG_QUALITY = 90

    /**
     * Fixed per original, so a run cut off by rotation or process death writes
     * the same file again instead of a second one.
     */
    fun nameFor(original: File): String = "scan-${original.nameWithoutExtension}.jpg"

    /** @return the file name in [dir] */
    @Throws(IOException::class)
    fun store(original: File, dir: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not a decodable image: $original")
        val orientation = ExifInterface(original.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val quarterTurn = orientation in QUARTER_TURNS
        val plan = EndPhotoSize.plan(
            if (quarterTurn) bounds.outHeight else bounds.outWidth,
            if (quarterTurn) bounds.outWidth else bounds.outHeight
        )
        val decoded = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = plan.inSampleSize })
            ?: throw IOException("could not decode $original")
        val upright = try {
            render(decoded, orientation, plan)
        } finally {
            decoded.recycle()
        }
        val file = File(dir, nameFor(original))
        try {
            FileOutputStream(file).use { out ->
                if (!upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("could not encode $file")
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        } finally {
            upright.recycle()
        }
        return file.name
    }

    private val QUARTER_TURNS = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270
    )

    /** Maps the raw pixels to the upright image, about the origin; all eight EXIF values. */
    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    private fun render(decoded: Bitmap, orientation: Int, plan: EndPhotoSize.Plan): Bitmap {
        val matrix = orientationMatrix(orientation)
        val turned = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat())
        matrix.mapRect(turned)
        matrix.postTranslate(-turned.left, -turned.top)
        matrix.postScale(plan.width / turned.width(), plan.height / turned.height())
        val out = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
```

- [ ] **Step 4: Test laufen lassen, er besteht**

Run: wie Step 2.
Expected: PASS (4 Tests; ohne Korpusfoto überspringt sich `theCorpusPhotoIsStoredUprightAt2048`, das ist hier **nicht** akzeptabel — das Foto muss für diesen Lauf in `app/src/androidTest/assets/scan/` liegen).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/dreier/mytargets/features/detection/EndPhotoStore.kt app/src/androidTest/java/de/dreier/mytargets/features/detection/EndPhotoStoreDeviceTest.kt
git commit -m "app: EndPhotoStore writes the upright 2048 px copy of a scan photo"
```

---

### Task 3: Gescannte Passen zeigen Plotting

**Files:**
- Create: `app/src/main/java/de/dreier/mytargets/features/training/input/InputMethodChoice.kt`
- Modify: `app/src/main/java/de/dreier/mytargets/features/training/input/TargetView.kt:164-171`
- Test: `app/src/test/java/de/dreier/mytargets/features/training/input/InputMethodChoiceTest.kt`

**Interfaces:**
- Produces: `object InputMethodChoice { fun inputMethodFor(shots: List<Shot>, exact: Boolean, setting: TargetViewBase.EInputMethod): TargetViewBase.EInputMethod }`; `TargetView.zoneAt(x: Float, y: Float): Int` (Ring mit Pfeilradius und Linienregel, wie beim Setzen von Hand).

- [ ] **Step 1: Failing test schreiben**

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.training.input

import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod.KEYBOARD
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod.PLOTTING
import org.junit.Test
import kotlin.test.assertEquals

class InputMethodChoiceTest {

    private val empty = listOf(Shot(0), Shot(1))
    private val set = listOf(Shot(0).apply { scoringRing = 2 }, Shot(1))

    @Test
    fun anEmptyEndFollowsItsExactFlag() {
        for (setting in listOf(KEYBOARD, PLOTTING)) {
            assertEquals(PLOTTING, InputMethodChoice.inputMethodFor(empty, exact = true, setting = setting))
            assertEquals(KEYBOARD, InputMethodChoice.inputMethodFor(empty, exact = false, setting = setting))
        }
    }

    @Test
    fun anExactEndWithShotsIsAlwaysPlotted() {
        // New in 8b (decision 6): a scanned end has positions, whatever the setting says.
        assertEquals(PLOTTING, InputMethodChoice.inputMethodFor(set, exact = true, setting = KEYBOARD))
        assertEquals(PLOTTING, InputMethodChoice.inputMethodFor(set, exact = true, setting = PLOTTING))
    }

    @Test
    fun aKeyboardEndWithShotsFollowsTheSetting() {
        assertEquals(KEYBOARD, InputMethodChoice.inputMethodFor(set, exact = false, setting = KEYBOARD))
        assertEquals(PLOTTING, InputMethodChoice.inputMethodFor(set, exact = false, setting = PLOTTING))
    }
}
```

- [ ] **Step 2: Test laufen lassen, er scheitert**

Run: `./gradlew :app:testDevDebugUnitTest --tests "*InputMethodChoiceTest*"`
Expected: FAIL, `Unresolved reference: InputMethodChoice`.

- [ ] **Step 3: Implementieren**

`InputMethodChoice.kt`:

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.training.input

import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod.KEYBOARD
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod.PLOTTING

/**
 * How an end is shown in the input (app integration 8b, decision 6). An
 * object of its own, not a member of TargetView: loading TargetView on the JVM
 * creates its android.util.Property constants, which throw "Stub!" there.
 */
object InputMethodChoice {
    fun inputMethodFor(shots: List<Shot>, exact: Boolean, setting: EInputMethod): EInputMethod = when {
        shots.all { it.scoringRing == Shot.NOTHING_SELECTED } -> if (exact) PLOTTING else KEYBOARD
        // Positions are set: show them, also for ends plotted before the keyboard was switched on.
        exact -> PLOTTING
        else -> setting
    }
}
```

In `TargetView.kt` `replaceWithEnd` ersetzen und `zoneAt` direkt darunter einfügen:

```kotlin
    override fun replaceWithEnd(shots: List<Shot>, exact: Boolean) {
        inputMethod = InputMethodChoice.inputMethodFor(shots, exact, SettingsManager.inputMethod)
        super.replaceWithEnd(shots, exact)
    }

    /** The ring for a position, with arrow radius and line rule, as when a shot is plotted by hand. */
    fun zoneAt(x: Float, y: Float): Int = targetDrawable!!.getZoneFromPoint(x, y)
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :app:testDevDebugUnitTest --tests "*InputMethodChoiceTest*" && ./gradlew :app:assembleDevDebug`
Expected: PASS (3 Tests), Build erfolgreich.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/dreier/mytargets/features/training/input/InputMethodChoice.kt app/src/main/java/de/dreier/mytargets/features/training/input/TargetView.kt app/src/test/java/de/dreier/mytargets/features/training/input/InputMethodChoiceTest.kt
git commit -m "app: an exact end with shots is always shown as plot"
```

---

### Task 4: Was eingetragen und was gemeldet wird

**Files:**
- Create: `app/src/main/java/de/dreier/mytargets/features/detection/ScanApplication.kt`
- Test: `app/src/test/java/de/dreier/mytargets/features/detection/ScanApplicationTest.kt`

**Interfaces:**
- Consumes: `ScanOutcome` (8a), `DetectionResult`, `DetectedShot`, `DetectionFailure`, `SelectionReason` (`:detection`), `Shot`.
- Produces:
  - `sealed class ScanMessage` mit `Complete(found: Int)`, `Fewer(found: Int, expected: Int)`, `Uncertain(sure: Int, reason: SelectionReason)`, `NoArrows`, `FaceNotFound`, `FaceMismatch`, `PhotoUnreadable`, `Failed(cause: Throwable)`, `Unsupported`.
  - `data class ScanReport(val shots: List<Shot>?, val message: ScanMessage)` — `shots == null`: nichts eintragen.
  - `object ScanApplication { fun apply(outcome: ScanOutcome, current: List<Shot>, zoneAt: (Float, Float) -> Int): ScanReport }`.

- [ ] **Step 1: Failing test schreiben**

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.detection

import de.dreier.mytargets.detection.DetectedFace
import de.dreier.mytargets.detection.DetectedShot
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.DetectionResult
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.Roll
import de.dreier.mytargets.shared.models.db.Shot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanApplicationTest {

    /** Six places as the database hands them out: ids, indices, the end, one arrow number. */
    private fun current() = (0 until 6).map { Shot(id = 100L + it, index = it, endId = 7L, arrowNumber = "3") }

    /** The ring is whatever the target view says; here a marker that shows it was asked. */
    private val zoneAt: (Float, Float) -> Int = { x, _ -> if (x > 0f) 9 else 4 }

    private fun detected(reason: SelectionReason, vararg xs: Float) = ScanOutcome.Detected(
        DetectionResult(
            shots = xs.map { DetectedShot(0, it, 0.1f, 0.9f) },
            faceConfidence = 0.9f,
            reason = reason,
            failure = null,
            face = DetectedFace(Mat3.identity(), 2252, 4000, Roll.NOT_MEASURED)
        ),
        "r4-all-2026-09"
    )

    @Test
    fun aCompleteEndFillsEveryPlace() {
        val report = ScanApplication.apply(detected(SelectionReason.COMPLETE, 0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f), current(), zoneAt)

        assertEquals(ScanMessage.Complete(6), report.message)
        assertEquals(listOf(9, 4, 9, 4, 9, 4), report.shots!!.map { it.scoringRing })
        assertEquals(0.3f, report.shots!![4].x)
        assertEquals(0.1f, report.shots!![4].y)
    }

    @Test
    fun theShotsKeepTheirIdentity() {
        val report = ScanApplication.apply(detected(SelectionReason.FEWER_THAN_EXPECTED, 0.1f, 0.2f), current(), zoneAt)

        val shots = report.shots!!
        // EndDAO.updateShots updates by primary key; new ids would never reach the database.
        assertEquals((100L until 106L).toList(), shots.map { it.id })
        assertEquals((0 until 6).toList(), shots.map { it.index })
        assertEquals(List(6) { 7L }, shots.map { it.endId })
        assertEquals(List(6) { null }, shots.map { it.arrowNumber })
    }

    @Test
    fun fewerArrowsLeaveTheRestOpen() {
        val report = ScanApplication.apply(detected(SelectionReason.FEWER_THAN_EXPECTED, 0.1f, 0.2f, 0.3f, 0.4f), current(), zoneAt)

        assertEquals(ScanMessage.Fewer(4, 6), report.message)
        assertEquals(6, report.shots!!.size)
        assertEquals(List(2) { Shot.NOTHING_SELECTED }, report.shots!!.drop(4).map { it.scoringRing })
        assertEquals(List(2) { 0f }, report.shots!!.drop(4).map { it.x })
    }

    @Test
    fun noArrowsEntersNothing() {
        // The detector says FEWER_THAN_EXPECTED with an empty list; the count decides before the reason.
        val report = ScanApplication.apply(detected(SelectionReason.FEWER_THAN_EXPECTED), current(), zoneAt)

        assertNull(report.shots)
        assertEquals(ScanMessage.NoArrows, report.message)
    }

    @Test
    fun anAmbiguousSurplusEntersOnlyTheSureOnes() {
        val report = ScanApplication.apply(detected(SelectionReason.AMBIGUOUS_SURPLUS, 0.1f, 0.2f, 0.3f, 0.4f), current(), zoneAt)

        assertEquals(ScanMessage.Uncertain(4, SelectionReason.AMBIGUOUS_SURPLUS), report.message)
        assertEquals(4, report.shots!!.count { it.scoringRing != Shot.NOTHING_SELECTED })
    }

    @Test
    fun aSpotOverflowCarriesItsReason() {
        val report = ScanApplication.apply(detected(SelectionReason.SPOT_OVERFLOW, 0.1f, 0.2f, 0.3f), current(), zoneAt)

        assertEquals(ScanMessage.Uncertain(3, SelectionReason.SPOT_OVERFLOW), report.message)
        assertEquals(3, report.shots!!.count { it.scoringRing != Shot.NOTHING_SELECTED })
    }

    @Test
    fun neverMorePlacesThanTheEndHas() {
        val report = ScanApplication.apply(detected(SelectionReason.COMPLETE, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f), current(), zoneAt)

        assertEquals(6, report.shots!!.size)
        assertEquals(ScanMessage.Complete(6), report.message)
    }

    @Test
    fun aFaceThatIsNotFoundOrDoesNotMatchEntersNothing() {
        val notFound = ScanApplication.apply(ScanOutcome.Detected(DetectionResult.failed(DetectionFailure.FACE_NOT_FOUND), "m"), current(), zoneAt)
        val mismatch = ScanApplication.apply(ScanOutcome.Detected(DetectionResult.failed(DetectionFailure.FACE_MISMATCH), "m"), current(), zoneAt)

        assertEquals(ScanReport(null, ScanMessage.FaceNotFound), notFound)
        assertEquals(ScanReport(null, ScanMessage.FaceMismatch), mismatch)
    }

    @Test
    fun theOtherOutcomesEnterNothing() {
        val cause = IllegalStateException("boom")

        assertEquals(ScanReport(null, ScanMessage.PhotoUnreadable), ScanApplication.apply(ScanOutcome.PhotoUnreadable(cause), current(), zoneAt))
        assertEquals(ScanReport(null, ScanMessage.Failed(cause)), ScanApplication.apply(ScanOutcome.ModelUnavailable(cause), current(), zoneAt))
        assertEquals(ScanReport(null, ScanMessage.Failed(cause)), ScanApplication.apply(ScanOutcome.ScanFailed(cause), current(), zoneAt))
        assertEquals(ScanReport(null, ScanMessage.Unsupported), ScanApplication.apply(ScanOutcome.Unsupported, current(), zoneAt))
    }

    @Test
    fun theCurrentShotsAreNotChanged() {
        val shots = current()

        ScanApplication.apply(detected(SelectionReason.COMPLETE, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f), shots, zoneAt)

        // They are the undo copy's source and the target view's list.
        assertEquals(List(6) { Shot.NOTHING_SELECTED }, shots.map { it.scoringRing })
    }
}
```

- [ ] **Step 2: Test laufen lassen, er scheitert**

Run: `./gradlew :app:testDevDebugUnitTest --tests "*ScanApplicationTest*"`
Expected: FAIL, `Unresolved reference: ScanApplication`.

- [ ] **Step 3: Implementieren**

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.detection

import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.DetectionResult
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.shared.models.db.Shot

/** What the input tells the user after a scan (app integration 8b, Meldungen). */
sealed class ScanMessage {
    data class Complete(val found: Int) : ScanMessage()
    data class Fewer(val found: Int, val expected: Int) : ScanMessage()
    /** Only the sure ones were entered; [reason] is AMBIGUOUS_SURPLUS or SPOT_OVERFLOW. */
    data class Uncertain(val sure: Int, val reason: SelectionReason) : ScanMessage()
    data object NoArrows : ScanMessage()
    data object FaceNotFound : ScanMessage()
    data object FaceMismatch : ScanMessage()
    data object PhotoUnreadable : ScanMessage()
    data class Failed(val cause: Throwable) : ScanMessage()
    /** Cannot happen, the menu item is hidden; logged, not shown. */
    data object Unsupported : ScanMessage()
}

/** [shots] is null when nothing is entered and the end keeps what it has. */
data class ScanReport(val shots: List<Shot>?, val message: ScanMessage)

/**
 * The table of the 8b design as a pure function: the activity only sets text
 * and action. Leitsatz: rather enter nothing than something wrong.
 */
object ScanApplication {

    /**
     * @param current the end's shots as loaded; the result copies them so ids,
     *   indices and the end stay (EndDAO.updateShots updates by primary key)
     * @param zoneAt the ring for a position, from the target view
     */
    fun apply(outcome: ScanOutcome, current: List<Shot>, zoneAt: (Float, Float) -> Int): ScanReport = when (outcome) {
        is ScanOutcome.Detected -> applyDetected(outcome.result, current, zoneAt)
        is ScanOutcome.PhotoUnreadable -> ScanReport(null, ScanMessage.PhotoUnreadable)
        is ScanOutcome.ModelUnavailable -> ScanReport(null, ScanMessage.Failed(outcome.cause))
        is ScanOutcome.ScanFailed -> ScanReport(null, ScanMessage.Failed(outcome.cause))
        ScanOutcome.Unsupported -> ScanReport(null, ScanMessage.Unsupported)
    }

    private fun applyDetected(result: DetectionResult, current: List<Shot>, zoneAt: (Float, Float) -> Int): ScanReport {
        when (result.failure) {
            DetectionFailure.FACE_NOT_FOUND -> return ScanReport(null, ScanMessage.FaceNotFound)
            DetectionFailure.FACE_MISMATCH -> return ScanReport(null, ScanMessage.FaceMismatch)
            null -> Unit
        }
        // "No arrows" is no reason of its own: FEWER_THAN_EXPECTED with an empty list.
        if (result.shots.isEmpty()) return ScanReport(null, ScanMessage.NoArrows)
        val placed = result.shots.take(current.size)
        val shots = current.mapIndexed { i, shot ->
            val detected = placed.getOrNull(i)
            if (detected == null) {
                shot.copy(x = 0f, y = 0f, scoringRing = Shot.NOTHING_SELECTED, arrowNumber = null)
            } else {
                shot.copy(x = detected.x, y = detected.y, scoringRing = zoneAt(detected.x, detected.y), arrowNumber = null)
            }
        }
        val uncertain = result.reason?.takeIf {
            it == SelectionReason.AMBIGUOUS_SURPLUS || it == SelectionReason.SPOT_OVERFLOW
        }
        val message = when {
            uncertain != null -> ScanMessage.Uncertain(placed.size, uncertain)
            placed.size == current.size -> ScanMessage.Complete(placed.size)
            else -> ScanMessage.Fewer(placed.size, current.size)
        }
        return ScanReport(shots, message)
    }
}
```

- [ ] **Step 4: Test laufen lassen, er besteht**

Run: `./gradlew :app:testDevDebugUnitTest --tests "*ScanApplicationTest*"`
Expected: PASS (10 Tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/dreier/mytargets/features/detection/ScanApplication.kt app/src/test/java/de/dreier/mytargets/features/detection/ScanApplicationTest.kt
git commit -m "app: ScanApplication, what a scan enters and what the input says"
```

---

### Task 5: Der Zustand über den Kamera-Ausflug und die Bildabfrage

**Files:**
- Create: `app/src/main/java/de/dreier/mytargets/features/training/input/PendingScan.kt`
- Modify: `app/src/main/java/de/dreier/mytargets/base/db/dao/EndDAO.kt` (nach `loadEndImages`, Zeile 30)
- Test: `app/src/androidTest/java/de/dreier/mytargets/features/training/input/PendingScanTest.kt`, `app/src/androidTest/java/de/dreier/mytargets/dao/EndDAOTest.kt`

**Interfaces:**
- Produces: `data class PendingScan(val endId: Long, val originalPath: String?, val storedImageName: String?) { fun toBundle(): Bundle; companion object { fun fromBundle(bundle: Bundle?): PendingScan? } }`; `EndDAO.countEndImages(endId: Long, fileName: String): Int`.

- [ ] **Step 1: Failing tests schreiben**

`PendingScanTest.kt`:

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.training.input

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingScanTest {

    @Test
    fun aScanWithTheCameraOpenSurvivesTheBundle() {
        val scan = PendingScan(endId = 42, originalPath = null, storedImageName = null)

        assertThat(PendingScan.fromBundle(throughParcel(scan.toBundle()))).isEqualTo(scan)
    }

    @Test
    fun aScanWithPhotoAndStoredCopySurvivesTheBundle() {
        val scan = PendingScan(endId = 42, originalPath = "/data/cache/EasyImage/a.jpg", storedImageName = "scan-a.jpg")

        assertThat(PendingScan.fromBundle(throughParcel(scan.toBundle()))).isEqualTo(scan)
    }

    @Test
    fun noBundleMeansNoScan() {
        assertThat(PendingScan.fromBundle(null)).isNull()
        assertThat(PendingScan.fromBundle(Bundle())).isNull()
    }

    /** What process death does to a saved state: written to a parcel and read back. */
    private fun throughParcel(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return parcel.readBundle(javaClass.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }
}
```

In `EndDAOTest.kt` anhängen (vor der schließenden Klammer):

```kotlin
    @Test
    fun countEndImagesFindsAnImageByEndAndName() {
        val end = EndFactory.makeSimpleEnd(1)
        appDatabase.endDAO().insertCompleteEnd(end.end, end.images, end.shots)
        appDatabase.endDAO().insertEndImage(EndImage(fileName = "scan-a.jpg", endId = end.id))

        assertThat(appDatabase.endDAO().countEndImages(end.id, "scan-a.jpg")).isEqualTo(1)
        assertThat(appDatabase.endDAO().countEndImages(end.id, "scan-b.jpg")).isEqualTo(0)
        assertThat(appDatabase.endDAO().countEndImages(end.id + 1, "scan-a.jpg")).isEqualTo(0)
    }
```

- [ ] **Step 2: Tests laufen lassen, sie scheitern**

Run: `./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.training.input.PendingScanTest`
Expected: FAIL beim Kompilieren, `Unresolved reference: PendingScan` und `countEndImages`.

- [ ] **Step 3: Implementieren**

`PendingScan.kt`:

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.training.input

import android.os.Bundle

/**
 * The only state of a scan across the trip to the camera (app integration 8b):
 * written to outState, so it survives rotation and process death.
 *
 * @param endId the end the photo belongs to, fixed before the camera opens
 * @param originalPath the camera's file; null while the camera is open
 * @param storedImageName set once the 2048 px copy hangs on the end
 */
data class PendingScan(
    val endId: Long,
    val originalPath: String?,
    val storedImageName: String?
) {
    fun toBundle(): Bundle = Bundle().apply {
        putLong(END_ID, endId)
        putString(ORIGINAL_PATH, originalPath)
        putString(STORED_IMAGE_NAME, storedImageName)
    }

    companion object {
        private const val END_ID = "end_id"
        private const val ORIGINAL_PATH = "original_path"
        private const val STORED_IMAGE_NAME = "stored_image_name"

        fun fromBundle(bundle: Bundle?): PendingScan? {
            if (bundle == null || !bundle.containsKey(END_ID)) return null
            return PendingScan(bundle.getLong(END_ID), bundle.getString(ORIGINAL_PATH), bundle.getString(STORED_IMAGE_NAME))
        }
    }
}
```

In `EndDAO.kt` nach `loadEndImages`:

```kotlin
    @Query("SELECT COUNT(*) FROM `EndImage` WHERE `endId` = :endId AND `fileName` = :fileName")
    abstract fun countEndImages(endId: Long, fileName: String): Int
```

- [ ] **Step 4: Tests laufen lassen, sie bestehen**

Run: `./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.training.input.PendingScanTest` und danach mit `...class=de.dreier.mytargets.dao.EndDAOTest`
Expected: PASS (3 Tests; EndDAOTest alle grün einschließlich des neuen).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/dreier/mytargets/features/training/input/PendingScan.kt app/src/main/java/de/dreier/mytargets/base/db/dao/EndDAO.kt app/src/androidTest/java/de/dreier/mytargets/features/training/input/PendingScanTest.kt app/src/androidTest/java/de/dreier/mytargets/dao/EndDAOTest.kt
git commit -m "app: PendingScan across the camera trip, EndDAO.countEndImages"
```

---

### Task 6: Menüpunkt, Rückfrage, Berechtigung und Kamera

Diese Task baut den Weg bis zum Foto: Menüpunkt, Rückfrage, Erlaubnis, Kamera, Ergebnis, Zustand in `outState`. Der Scan selbst folgt in Task 7; nach dieser Task bleibt ein geliefertes Foto noch liegen.

**Files:**
- Create: `app/src/main/java/de/dreier/mytargets/features/training/input/EndScanFlow.kt`
- Modify: `app/src/main/java/de/dreier/mytargets/features/training/input/InputActivity.kt`
- Modify: `app/src/main/res/menu/input_end.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values-de/strings.xml`
- Test: `app/src/androidTest/java/de/dreier/mytargets/features/training/input/EndScanFlowTest.kt`

**Interfaces:**
- Consumes: `PendingScan` (Task 5), `ScanSupport.supports(target)` (8a), `ScanMessage` (Task 4), `TargetView.zoneAt` (Task 3).
- Produces: `class EndScanFlow(activity: AppCompatActivity, host: EndScanFlow.Host) : DefaultLifecycleObserver` mit `restore(savedInstanceState: Bundle?)`, `save(outState: Bundle)`, `isOffered(data: LoaderResult): Boolean`, `onMenuSelected()`, `onCameraPermissionResult(granted: Boolean)`, `onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)`; `interface EndScanFlow.Host { val loadedData: LoaderResult?; val messageAnchor: View; fun zoneAt(x: Float, y: Float): Int; fun enterShots(shots: List<Shot>, exact: Boolean); fun onImagesChanged() }`, implementiert von `InputActivity`. Task 7 ergänzt `resume()` und `onEndChanged()`.

- [ ] **Step 1: Failing test schreiben**

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.training.input

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.features.settings.SettingsManager
import de.dreier.mytargets.shared.models.Dimension
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.models.db.End
import de.dreier.mytargets.shared.models.db.Round
import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.shared.targets.models.WAFull
import de.dreier.mytargets.shared.views.TargetViewBase
import de.dreier.mytargets.test.utils.rules.DbTestRuleBase
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

/**
 * The scan from the end input (app integration 8b) in the real activity, with
 * the camera stubbed by espresso-intents. The full scan needs the corpus photo
 * (README next to the assets); without it those tests skip themselves.
 */
@RunWith(AndroidJUnit4::class)
class EndScanFlowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val endDAO get() = ApplicationInstance.db.endDAO()
    private lateinit var round: Round

    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule
    val db = object : DbTestRuleBase() {
        override fun addDatabaseContent() {
            val training = saveDefaultTraining(null, Random(8))
            round = Round(
                trainingId = training.id,
                index = 0,
                shotsPerEnd = 6,
                maxEndCount = 3,
                distance = Dimension(18f, Dimension.Unit.METER),
                target = Target(WAFull.ID, 0)
            )
            round.id = ApplicationInstance.db.roundDAO().insertRound(round)
        }
    }

    @Before
    fun setUp() {
        // With the keyboard setting, "shown as plot" is the new rule at work, not the default.
        SettingsManager.inputMethod = TargetViewBase.EInputMethod.KEYBOARD
        SettingsManager.timerEnabled = false
        Intents.init()
    }

    @After
    fun tearDown() = Intents.release()

    @Test
    fun aCancelledCameraLeavesNothing() {
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        launch().use {
            scanFromMenu()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            val end = endDAO.loadEnds(round.id).single()
            assertThat(endDAO.loadEndImages(end.id)).isEmpty()
            assertThat(endDAO.loadShots(end.id).all { it.scoringRing == Shot.NOTHING_SELECTED }).isTrue()
            assertThat(cameraFiles()).isEmpty()
        }
    }

    @Test
    fun decliningToReplaceTakesNoPhoto() {
        val shots = (0 until 6).map { i -> Shot(i).apply { if (i < 2) scoringRing = 0 } }
        endDAO.insertCompleteEnd(End(index = 0, roundId = round.id), emptyList(), shots)
        launch().use {
            scanFromMenu()
            onView(withText(android.R.string.cancel)).perform(click())

            intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE), times(0))
        }
    }

    // --- helpers, shared with Task 7 ---

    private fun launch(): ActivityScenario<InputActivity> {
        val scenario = ActivityScenario.launch<InputActivity>(Intent(context, InputActivity::class.java).apply {
            putExtra(InputActivity.TRAINING_ID, round.trainingId)
            putExtra(InputActivity.ROUND_ID, round.id)
            putExtra(InputActivity.END_INDEX, 0)
        })
        waitFor(10_000) { var loaded = false; scenario.onActivity { loaded = it.data != null }; loaded.takeIf { it } }
        return scenario
    }

    private fun scanFromMenu() {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.scan_end)).perform(click())
    }

    /** What EasyImage left in its camera directory. */
    private fun cameraFiles(): List<File> =
        File(context.cacheDir, "EasyImage").listFiles().orEmpty().filter { it.extension == "jpg" }

    private fun <T : Any> waitFor(timeoutMs: Long, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            check(SystemClock.elapsedRealtime() < deadline) { "timed out after $timeoutMs ms" }
            SystemClock.sleep(200)
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen, er scheitert**

Run: `./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.training.input.EndScanFlowTest`
Expected: FAIL beim Kompilieren, `Unresolved reference: scan_end`.

- [ ] **Step 3: Menüpunkt und Texte**

In `input_end.xml` nach `action_new_round`:

```xml
    <item
        android:id="@+id/action_scan_end"
        android:title="@string/scan_end"
        app:showAsAction="never"/>
```

In `values/strings.xml` vor `</resources>`:

```xml
    <!-- Scan an end from a photo (app integration 8b) -->
    <string name="scan_end">Scan end</string>
    <plurals name="scan_replace_question">
        <item quantity="one">The end already has %d shot. Replace it with the scan?</item>
        <item quantity="other">The end already has %d shots. Replace them with the scan?</item>
    </plurals>
    <string name="scan_replace">Replace</string>
    <string name="scan_progress">Detecting arrows…</string>
    <plurals name="scan_complete">
        <item quantity="one">%d arrow detected</item>
        <item quantity="other">%d arrows detected</item>
    </plurals>
    <string name="scan_fewer">%1$d of %2$d arrows detected, please set the rest</string>
    <string name="scan_ambiguous">%d detected for sure, more are unclear</string>
    <string name="scan_spot_overflow">%d detected for sure, too many on one spot</string>
    <string name="scan_no_arrows">No arrows detected</string>
    <string name="scan_face_not_found">Target face not found</string>
    <string name="scan_face_mismatch">The photo does not match the target face %s</string>
    <string name="scan_photo_unreadable">The photo could not be read</string>
    <string name="scan_failed">Detection failed</string>
    <string name="scan_other_end">Photo attached to end %d, not entered</string>
    <string name="scan_end_gone">The end no longer exists, nothing entered</string>
    <string name="scan_retake">Retake</string>
```

In `values-de/strings.xml` vor `</resources>`:

```xml
    <!-- Passe aus einem Foto scannen (App-Integration 8b) -->
    <string name="scan_end">Passe scannen</string>
    <plurals name="scan_replace_question">
        <item quantity="one">Die Passe hat schon %d Treffer. Beim Scan ersetzen?</item>
        <item quantity="other">Die Passe hat schon %d Treffer. Beim Scan ersetzen?</item>
    </plurals>
    <string name="scan_replace">Ersetzen</string>
    <string name="scan_progress">Pfeile werden erkannt …</string>
    <plurals name="scan_complete">
        <item quantity="one">%d Pfeil erkannt</item>
        <item quantity="other">%d Pfeile erkannt</item>
    </plurals>
    <string name="scan_fewer">%1$d von %2$d Pfeilen erkannt, Rest bitte setzen</string>
    <string name="scan_ambiguous">%d sicher erkannt, weitere unklar</string>
    <string name="scan_spot_overflow">%d sicher erkannt, zu viele auf einem Spot</string>
    <string name="scan_no_arrows">Keine Pfeile erkannt</string>
    <string name="scan_face_not_found">Auflage nicht gefunden</string>
    <string name="scan_face_mismatch">Foto passt nicht zur Auflage %s</string>
    <string name="scan_photo_unreadable">Foto konnte nicht gelesen werden</string>
    <string name="scan_failed">Erkennung fehlgeschlagen</string>
    <string name="scan_other_end">Foto an Passe %d abgelegt, nicht eingetragen</string>
    <string name="scan_end_gone">Die Passe gibt es nicht mehr, nichts eingetragen</string>
    <string name="scan_retake">Neu aufnehmen</string>
```

- [ ] **Step 4: `EndScanFlow`, erster Teil**

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.training.input

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.snackbar.Snackbar
import de.dreier.mytargets.R
import de.dreier.mytargets.features.detection.ScanMessage
import de.dreier.mytargets.features.detection.ScanSupport
import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.utils.PermissionUtils
import pl.aprilapps.easyphotopicker.DefaultCallback
import pl.aprilapps.easyphotopicker.EasyImage
import timber.log.Timber
import java.io.File

/**
 * Scans the current end from a photo (app integration 8b): question, camera,
 * attaching the photo, the scan, entering and the message. The only state is
 * the [PendingScan]; a run cut off by rotation or process death starts again
 * from it.
 */
class EndScanFlow(private val activity: AppCompatActivity, private val host: Host) : DefaultLifecycleObserver {

    interface Host {
        /** The loaded training; null until the loader delivered it. */
        val loadedData: LoaderResult?

        /** Where the snackbars go. */
        val messageAnchor: View

        /** The ring for a position, as when the shot is plotted by hand. */
        fun zoneAt(x: Float, y: Float): Int

        /** Replaces the current end's shots and exact flag in view and database. */
        fun enterShots(shots: List<Shot>, exact: Boolean)

        /** The current end's images changed. */
        fun onImagesChanged()
    }

    private var pending: PendingScan? = null

    fun restore(savedInstanceState: Bundle?) {
        pending = PendingScan.fromBundle(savedInstanceState?.getBundle(KEY_PENDING_SCAN))
    }

    fun save(outState: Bundle) {
        pending?.let { outState.putBundle(KEY_PENDING_SCAN, it.toBundle()) }
    }

    fun isOffered(data: LoaderResult): Boolean = ScanSupport.supports(data.currentRound.round.target)

    fun onMenuSelected() {
        val d = host.loadedData ?: return
        val set = d.currentEnd.shots.count { it.scoringRing != Shot.NOTHING_SELECTED }
        if (set == 0) {
            requestCamera()
            return
        }
        // Asked before the camera, so no dialog has to survive a process death.
        MaterialDialog.Builder(activity)
            .content(activity.resources.getQuantityString(R.plurals.scan_replace_question, set, set))
            .positiveText(R.string.scan_replace)
            .negativeText(android.R.string.cancel)
            .onPositive { _, _ -> requestCamera() }
            .show()
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) openCamera()
    }

    private fun requestCamera() {
        // Without the runtime permission the capture intent throws a SecurityException.
        if (PermissionUtils.hasCameraPermission(activity)) {
            openCamera()
        } else {
            PermissionUtils.requestCameraPermission(activity)
        }
    }

    private fun openCamera() {
        val d = host.loadedData ?: return
        // Fixed before the camera opens: after a process death the end would be unknown.
        pending = PendingScan(endId = d.currentEnd.end.id, originalPath = null, storedImageName = null)
        try {
            EasyImage.openCameraForImage(activity, 0)
        } catch (e: ActivityNotFoundException) {
            pending = null
            Toast.makeText(activity, R.string.no_camera_app, Toast.LENGTH_LONG).show()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        EasyImage.handleActivityResult(requestCode, resultCode, data, activity, object : DefaultCallback() {
            override fun onImagesPicked(imageFiles: List<File>, source: EasyImage.ImageSource, type: Int) {
                val photo = imageFiles.firstOrNull() ?: return
                val p = pending
                if (p == null) {
                    // No end to put it on.
                    photo.delete()
                    return
                }
                pending = p.copy(originalPath = photo.path)
            }

            override fun onCanceled(source: EasyImage.ImageSource?, type: Int) {
                if (source == EasyImage.ImageSource.CAMERA_IMAGE) {
                    EasyImage.lastlyTakenButCanceledPhoto(activity.applicationContext)?.delete()
                }
                pending = null
            }

            override fun onImagePickerError(e: Exception, source: EasyImage.ImageSource?, type: Int) {
                Timber.e(e, "The camera returned no usable picture")
                pending = null
                show(ScanMessage.PhotoUnreadable)
            }
        })
    }

    private fun show(message: ScanMessage) {
        val text = when (message) {
            ScanMessage.PhotoUnreadable -> activity.getString(R.string.scan_photo_unreadable)
            else -> return
        }
        Snackbar.make(host.messageAnchor, text, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val KEY_PENDING_SCAN = "pending_scan"
    }
}
```

`show` ist hier bewusst nur für den einen Fall da, den dieser Teil braucht; Task 7 ersetzt die Datei durch die vollständige Fassung mit allen Meldungen.

- [ ] **Step 5: `InputActivity` anbinden**

Klassenkopf:

```kotlin
class InputActivity : ChildActivityBase(), TargetViewBase.OnEndFinishedListener,
    TargetView.OnEndUpdatedListener, LoaderManager.LoaderCallbacks<LoaderResult>, EndScanFlow.Host {
```

Feld neben `targetView`:

```kotlin
    private lateinit var scanFlow: EndScanFlow
```

In `onCreate` direkt nach `super.onCreate(savedInstanceState)`:

```kotlin
        // Before anything else: onActivityResult after a process death needs the pending scan.
        scanFlow = EndScanFlow(this, this)
        lifecycle.addObserver(scanFlow)
        scanFlow.restore(savedInstanceState)
```

In `onSaveInstanceState` vor `data = null`:

```kotlin
        scanFlow.save(outState)
```

`onActivityResult` ersetzen:

```kotlin
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GALLERY_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val d = this.data ?: return
                val imageList = GalleryActivity.getResult(data)
                val currentEnd = d.currentEnd
                currentEnd.images = imageList.toEndImageList()
                for (image in imageList.removedImages) {
                    File(filesDir, image).delete()
                }
                endDAO.replaceImages(currentEnd.end, currentEnd.images)
                updateEnd()
                invalidateOptionsMenu()
            }
            return
        }
        scanFlow.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.REQUEST_CAMERA) {
            scanFlow.onCameraPermissionResult(PermissionUtils.isPermissionGranted(grantResults))
        }
    }
```

In `onPrepareOptionsMenu`: nach `val takePicture = ...` `val scanEnd = menu.findItem(R.id.action_scan_end)`; im `if`-Zweig `scanEnd.isVisible = false`; im `else`-Zweig nach dem zweiten `takePicture.isVisible = ...`:

```kotlin
            scanEnd.isVisible = takePicture.isVisible && scanFlow.isOffered(d)
```

In `onOptionsItemSelected` vor `else ->`:

```kotlin
            R.id.action_scan_end -> scanFlow.onMenuSelected()
```

Host-Implementierung, direkt vor `onEndUpdated`:

```kotlin
    override val loadedData: LoaderResult?
        get() = data

    override val messageAnchor: View
        get() = binding.root

    override fun zoneAt(x: Float, y: Float): Int =
        checkNotNull(targetView) { "the target view exists once the data is loaded" }.zoneAt(x, y)

    override fun enterShots(shots: List<Shot>, exact: Boolean) {
        val d = data ?: return
        d.currentEnd.end.exact = exact
        d.currentEnd.shots = shots.toMutableList()
        // replaceWithEnd reports back through onEndUpdated, which saves the end and updates the sums.
        targetView?.replaceWithEnd(d.currentEnd.shots, exact)
        updateWearNotification()
        updateNavigationButtons()
        invalidateOptionsMenu()
    }

    override fun onImagesChanged() = invalidateOptionsMenu()
```

Importe ergänzen: `android.view.View`, `de.dreier.mytargets.utils.PermissionUtils` (liegt in `de.dreier.mytargets.utils.*`, schon importiert — dann nichts tun).

- [ ] **Step 6: Tests laufen lassen**

Run: `./gradlew :app:testDevDebugUnitTest && ./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.training.input.EndScanFlowTest`
Expected: JVM alle grün; Gerät PASS (2 Tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/de/dreier/mytargets/features/training/input/EndScanFlow.kt app/src/main/java/de/dreier/mytargets/features/training/input/InputActivity.kt app/src/main/res/menu/input_end.xml app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml app/src/androidTest/java/de/dreier/mytargets/features/training/input/EndScanFlowTest.kt
git commit -m "app: scan end menu item, question, camera permission and the trip to the camera"
```

---

### Task 7: Ablegen, Scannen, Eintragen, Melden, Rückgängig

**Files:**
- Modify: `app/src/main/java/de/dreier/mytargets/features/training/input/EndScanFlow.kt` (vollständige Fassung unten)
- Modify: `app/src/main/java/de/dreier/mytargets/features/training/input/InputActivity.kt` (`onResume`, `onLoadFinished`, `showEnd`)
- Test: `app/src/androidTest/java/de/dreier/mytargets/features/training/input/EndScanFlowTest.kt`

**Interfaces:**
- Consumes: `EndPhotoStore.nameFor/store` (Task 2), `EndDAO.countEndImages` (Task 5), `ScanApplication.apply` (Task 4), `EndPhotoScanner.get(context).scan(photo, target, shotsPerEnd)` (8a), `Host` (Task 6).
- Produces: `EndScanFlow.resume()` (idempotent, startet den Lauf, wenn Foto und Daten da sind), `EndScanFlow.onEndChanged()` (schließt die Rückgängig-Snackbar).

- [ ] **Step 1: Failing tests anhängen**

In `EndScanFlowTest` (Importe `android.net.Uri`, `androidx.test.espresso.assertion.ViewAssertions.doesNotExist`, `org.junit.Assume.assumeTrue`, `java.io.IOException` ergänzen; die Referenzliste des 8a-Gerätetests ist privat, deshalb steht die Zahl 5 im Test):

```kotlin
    @Test
    fun aScannedEndLandsInTheDatabaseWithItsPhoto() {
        cameraDelivers(corpusPhoto())
        launch().use { scenario ->
            scanFromMenu()

            val end = waitFor(60_000) { scannedEnd() }
            val shots = endDAO.loadShots(end.id)
            // The corpus photo has five arrows (EndPhotoScannerDeviceTest asserts all five); the round expects six.
            assertThat(shots.count { it.scoringRing != Shot.NOTHING_SELECTED }).isEqualTo(5)
            assertThat(shots).hasSize(6)
            assertThat(end.exact).isTrue()
            val images = endDAO.loadEndImages(end.id)
            assertThat(images).hasSize(1)
            assertThat(File(context.filesDir, images[0].fileName).exists()).isTrue()
            assertThat(cameraFiles()).isEmpty()
            scenario.onActivity {
                assertThat(it.findViewById<TargetView>(R.id.targetViewContainer).inputMode)
                    .isEqualTo(TargetViewBase.EInputMethod.PLOTTING)
                assertThat(it.data!!.currentEnd.images.map { image -> image.fileName }).containsExactly(images[0].fileName)
            }
        }
    }

    @Test
    fun undoIsGoneAfterMovingToTheNextEnd() {
        cameraDelivers(corpusPhoto())
        launch().use { scenario ->
            scanFromMenu()
            waitFor(60_000) { scannedEnd() }

            scenario.onActivity { it.findViewById<android.view.View>(R.id.next).performClick() }

            // dismiss() animates out; the view is removed a moment later.
            waitFor(5_000) { runCatching { onView(withText(R.string.undo)).check(doesNotExist()) }.getOrNull() }
            val second = waitFor(10_000) { endDAO.loadEnds(round.id).getOrNull(1) }
            assertThat(endDAO.loadShots(second.id).all { it.scoringRing == Shot.NOTHING_SELECTED }).isTrue()
        }
    }

    @Test
    fun aCameraThatWritesNothingEntersNothing() {
        // RESULT_OK, but EasyImage's file stays empty.
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        launch().use {
            scanFromMenu()

            // The scan job runs outside Espresso's idling; poll for its message.
            waitFor(30_000) { runCatching { onView(withText(R.string.scan_photo_unreadable)).check(matches(isDisplayed())) }.getOrNull() }
            val end = endDAO.loadEnds(round.id).single()
            assertThat(endDAO.loadEndImages(end.id)).isEmpty()
            assertThat(endDAO.loadShots(end.id).all { it.scoringRing == Shot.NOTHING_SELECTED }).isTrue()
            waitFor(10_000) { cameraFiles().isEmpty().takeIf { it } }
        }
    }

    /** The first end once the scan's shots are persisted and the original is gone. */
    private fun scannedEnd(): End? {
        val end = endDAO.loadEnds(round.id).firstOrNull() ?: return null
        val entered = endDAO.loadShots(end.id).any { it.scoringRing != Shot.NOTHING_SELECTED }
        return end.takeIf { entered && cameraFiles().isEmpty() }
    }

    /** The camera "takes" [photo]: copied into EasyImage's EXTRA_OUTPUT, then RESULT_OK. */
    private fun cameraDelivers(photo: File) {
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWithFunction { intent ->
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
            context.contentResolver.openOutputStream(uri)!!.use { out -> photo.inputStream().use { it.copyTo(out) } }
            Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }

    private fun corpusPhoto(): File {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val file = File(context.cacheDir, "$VIEW.jpg")
        try {
            testAssets.open("scan/$VIEW.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            assumeTrue("scan/$VIEW.jpg is not in the test assets, see app/src/androidTest/README.md", false)
        }
        return file
    }

    private companion object {
        const val VIEW = "2026-08-15_bedeckt_frontal_02"
    }
```

Zusätzliche Importe für diese Tests: `androidx.test.espresso.assertion.ViewAssertions.matches`, `androidx.test.espresso.matcher.ViewMatchers.isDisplayed`.

- [ ] **Step 2: Tests laufen lassen, sie scheitern**

Run: `./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.training.input.EndScanFlowTest`
Expected: `aScannedEndLandsInTheDatabaseWithItsPhoto` und `undoIsGoneAfterMovingToTheNextEnd` scheitern mit `timed out after 60000 ms` (das Foto bleibt liegen), `aCameraThatWritesNothingEntersNothing` mit `timed out after 30000 ms` (die Meldung erscheint nie; `runCatching` schluckt die `NoMatchingViewException` bis zum Ablauf der Frist).

- [ ] **Step 3: `EndScanFlow` vollständig**

Die Datei aus Task 6 durch diese Fassung ersetzen:

```kotlin
/*
 * (GPL-Kopf wie in Task 1)
 */

package de.dreier.mytargets.features.training.input

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.snackbar.Snackbar
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.features.detection.EndPhotoScanner
import de.dreier.mytargets.features.detection.EndPhotoStore
import de.dreier.mytargets.features.detection.ScanApplication
import de.dreier.mytargets.features.detection.ScanMessage
import de.dreier.mytargets.features.detection.ScanSupport
import de.dreier.mytargets.shared.models.db.EndImage
import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.aprilapps.easyphotopicker.DefaultCallback
import pl.aprilapps.easyphotopicker.EasyImage
import timber.log.Timber
import java.io.File

/**
 * Scans the current end from a photo (app integration 8b): question, camera,
 * attaching the photo, the scan, entering and the message. The only state is
 * the [PendingScan]; a run cut off by rotation or process death starts again
 * from it, and attaching the photo is idempotent so that costs no second image.
 */
class EndScanFlow(private val activity: AppCompatActivity, private val host: Host) : DefaultLifecycleObserver {

    interface Host {
        /** The loaded training; null until the loader delivered it. */
        val loadedData: LoaderResult?

        /** Where the snackbars go. */
        val messageAnchor: View

        /** The ring for a position, as when the shot is plotted by hand. */
        fun zoneAt(x: Float, y: Float): Int

        /** Replaces the current end's shots and exact flag in view and database. */
        fun enterShots(shots: List<Shot>, exact: Boolean)

        /** The current end's images changed. */
        fun onImagesChanged()
    }

    /** One run of store, check, scan, enter; [cancelledByUser] tells "Abbrechen" from the activity going away. */
    private class Run(val dialog: MaterialDialog) {
        lateinit var job: Job
        var cancelledByUser = false
    }

    /** What "Rückgängig" puts back, bound to its end. */
    private class Undo(val endId: Long, val shots: List<Shot>, val exact: Boolean)

    private enum class Action { UNDO, RETAKE }

    private val endDAO by lazy { ApplicationInstance.db.endDAO() }
    private var pending: PendingScan? = null
    private var current: Run? = null
    private var undo: Undo? = null
    private var undoBar: Snackbar? = null

    fun restore(savedInstanceState: Bundle?) {
        pending = PendingScan.fromBundle(savedInstanceState?.getBundle(KEY_PENDING_SCAN))
    }

    fun save(outState: Bundle) {
        pending?.let { outState.putBundle(KEY_PENDING_SCAN, it.toBundle()) }
    }

    fun isOffered(data: LoaderResult): Boolean = ScanSupport.supports(data.currentRound.round.target)

    fun onMenuSelected() {
        val d = host.loadedData ?: return
        val set = d.currentEnd.shots.count { it.scoringRing != Shot.NOTHING_SELECTED }
        if (set == 0) {
            requestCamera()
            return
        }
        // Asked before the camera, so no dialog has to survive a process death.
        MaterialDialog.Builder(activity)
            .content(activity.resources.getQuantityString(R.plurals.scan_replace_question, set, set))
            .positiveText(R.string.scan_replace)
            .negativeText(android.R.string.cancel)
            .onPositive { _, _ -> requestCamera() }
            .show()
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) openCamera()
    }

    private fun requestCamera() {
        // Without the runtime permission the capture intent throws a SecurityException.
        if (PermissionUtils.hasCameraPermission(activity)) {
            openCamera()
        } else {
            PermissionUtils.requestCameraPermission(activity)
        }
    }

    private fun openCamera() {
        val d = host.loadedData ?: return
        // Fixed before the camera opens: after a process death the end would be unknown.
        pending = PendingScan(endId = d.currentEnd.end.id, originalPath = null, storedImageName = null)
        try {
            EasyImage.openCameraForImage(activity, 0)
        } catch (e: ActivityNotFoundException) {
            pending = null
            Toast.makeText(activity, R.string.no_camera_app, Toast.LENGTH_LONG).show()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        EasyImage.handleActivityResult(requestCode, resultCode, data, activity, object : DefaultCallback() {
            override fun onImagesPicked(imageFiles: List<File>, source: EasyImage.ImageSource, type: Int) {
                val photo = imageFiles.firstOrNull() ?: return
                val p = pending
                if (p == null) {
                    // No end to put it on.
                    photo.delete()
                    return
                }
                pending = p.copy(originalPath = photo.path)
            }

            override fun onCanceled(source: EasyImage.ImageSource?, type: Int) {
                if (source == EasyImage.ImageSource.CAMERA_IMAGE) {
                    EasyImage.lastlyTakenButCanceledPhoto(activity.applicationContext)?.delete()
                }
                pending = null
            }

            override fun onImagePickerError(e: Exception, source: EasyImage.ImageSource?, type: Int) {
                Timber.e(e, "The camera returned no usable picture")
                pending = null
                show(ScanMessage.PhotoUnreadable)
            }
        })
    }

    /**
     * Starts the run once the photo is there and the training is loaded; called
     * at the end of onLoadFinished and from onResume. Does nothing while a run
     * is going.
     */
    fun resume() {
        val p = pending ?: return
        val path = p.originalPath ?: return
        if (current != null || host.loadedData == null) return
        val original = File(path)
        if (!original.exists()) {
            // A run finished after this state was saved (e.g. home during the scan, then process death).
            Timber.i("Pending scan of end ${p.endId} has no photo any more, dropping it")
            pending = null
            return
        }
        val dialog = MaterialDialog.Builder(activity)
            .content(R.string.scan_progress)
            .progress(true, 0)
            .cancelable(false)
            .negativeText(android.R.string.cancel)
            .onNegative { _, _ -> cancelByUser() }
            .show()
        val run = Run(dialog)
        current = run
        run.job = activity.lifecycleScope.launch { execute(p, original, run) }
    }

    /** The end on screen changes: the scan's Undo no longer applies. */
    fun onEndChanged() {
        undoBar?.dismiss()
        undoBar = null
        undo = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // The job dies with the activity; its dialog must not leak the window.
        current?.dialog?.dismiss()
    }

    private fun cancelByUser() {
        val run = current ?: return
        run.cancelledByUser = true
        run.job.cancel()
        // A new scan may start at once; this run cleans up when its current step returns.
        current = null
    }

    private suspend fun execute(start: PendingScan, original: File, run: Run) {
        var done = false
        try {
            var p = start
            if (p.storedImageName == null) {
                val name = attach(original, p.endId)
                if (name != null) {
                    p = p.copy(storedImageName = name)
                    if (pending?.originalPath == p.originalPath) pending = p
                }
            }
            val d = host.loadedData
            if (d == null || d.currentEnd.end.id != p.endId) {
                showOtherEnd(d, p.endId)
                done = true
                return
            }
            val round = d.currentRound.round
            val outcome = EndPhotoScanner.get(activity).scan(original, round.target, round.shotsPerEnd)
            val report = ScanApplication.apply(outcome, d.currentEnd.shots, host::zoneAt)
            report.shots?.let { enter(it, p.endId) }
            show(report.message, round.target.name)
            done = true
        } finally {
            if (done || run.cancelledByUser) {
                original.delete()
                if (pending?.originalPath == original.path) pending = null
            }
            // Otherwise the activity is going away: the saved pending scan starts the run again.
            run.dialog.dismiss()
            if (current === run) current = null
        }
    }

    /**
     * Puts the 2048 px copy on the end, at most once per photo: the name is fixed
     * per original and the database is asked first, under one lock for the
     * whole process, because a run cut off by rotation may still be writing.
     * @return the file name, or null if attaching failed (logged; the scan goes on)
     */
    private suspend fun attach(original: File, endId: Long): String? {
        val name = EndPhotoStore.nameFor(original)
        val images = withContext(Dispatchers.IO) {
            STORE_LOCK.withLock {
                val file = File(activity.filesDir, name)
                try {
                    if (endDAO.countEndImages(endId, name) == 0) {
                        try {
                            EndPhotoStore.store(original, activity.filesDir)
                            endDAO.insertEndImage(EndImage(fileName = name, endId = endId))
                        } catch (e: Throwable) {
                            // Written but not attached (e.g. the end was deleted): nothing refers to it.
                            file.delete()
                            throw e
                        }
                    }
                    endDAO.loadEndImages(endId)
                } catch (e: Exception) {
                    Timber.e(e, "Could not attach the scan photo to end $endId")
                    null
                } catch (e: OutOfMemoryError) {
                    Timber.e(e, "Out of memory attaching the scan photo to end $endId")
                    null
                }
            }
        } ?: return null
        val d = host.loadedData
        if (d != null && d.currentEnd.end.id == endId) {
            // From the database, not appended: the gallery writes this list back as the truth.
            d.currentEnd.images = images.toMutableList()
            host.onImagesChanged()
        }
        return name
    }

    private fun enter(shots: List<Shot>, endId: Long) {
        val d = host.loadedData ?: return
        undo = Undo(endId, d.currentEnd.shots.map { it.copy() }, d.currentEnd.end.exact)
        host.enterShots(shots, exact = true)
    }

    private fun undoScan() {
        val u = undo ?: return
        undo = null
        val d = host.loadedData ?: return
        // Never write the old shots into another end.
        if (d.currentEnd.end.id != u.endId) return
        host.enterShots(u.shots, u.exact)
    }

    private fun showOtherEnd(data: LoaderResult?, endId: Long) {
        val number = data?.training?.rounds
            ?.flatMap { it.ends }
            ?.firstOrNull { it.end.id == endId }
            ?.end?.index?.plus(1)
        val text = if (number != null) {
            activity.getString(R.string.scan_other_end, number)
        } else {
            activity.getString(R.string.scan_end_gone)
        }
        Snackbar.make(host.messageAnchor, text, Snackbar.LENGTH_LONG).show()
    }

    private fun show(message: ScanMessage, targetName: String = "") {
        val (text, action) = when (message) {
            is ScanMessage.Complete ->
                activity.resources.getQuantityString(R.plurals.scan_complete, message.found, message.found) to Action.UNDO
            is ScanMessage.Fewer ->
                activity.getString(R.string.scan_fewer, message.found, message.expected) to Action.UNDO
            is ScanMessage.Uncertain -> activity.getString(
                if (message.reason == SelectionReason.SPOT_OVERFLOW) R.string.scan_spot_overflow else R.string.scan_ambiguous,
                message.sure
            ) to Action.UNDO
            ScanMessage.NoArrows -> activity.getString(R.string.scan_no_arrows) to Action.RETAKE
            ScanMessage.FaceNotFound -> activity.getString(R.string.scan_face_not_found) to Action.RETAKE
            ScanMessage.FaceMismatch -> activity.getString(R.string.scan_face_mismatch, targetName) to null
            ScanMessage.PhotoUnreadable -> activity.getString(R.string.scan_photo_unreadable) to null
            is ScanMessage.Failed -> {
                Timber.e(message.cause, "The scan failed")
                activity.getString(R.string.scan_failed) to null
            }
            ScanMessage.Unsupported -> {
                Timber.w("A scan ran for a face that is not supported")
                return
            }
        }
        val bar = Snackbar.make(host.messageAnchor, text, Snackbar.LENGTH_LONG)
        when (action) {
            Action.UNDO -> {
                bar.setAction(R.string.undo) { undoScan() }
                bar.duration = ACTION_DURATION_MS
                undoBar = bar
            }
            // Nothing was entered, so no question again; an earlier "replace" still stands.
            Action.RETAKE -> {
                bar.setAction(R.string.scan_retake) { requestCamera() }
                bar.duration = ACTION_DURATION_MS
            }
            null -> Unit
        }
        bar.show()
    }

    private companion object {
        const val KEY_PENDING_SCAN = "pending_scan"

        /**
         * A message with an action stays long enough to act on it; LENGTH_LONG is
         * under three seconds. Not indefinite: the bar covers the buttons below.
         */
        const val ACTION_DURATION_MS = 8_000

        /** One attach at a time in the whole process, see [attach]. */
        val STORE_LOCK = Mutex()
    }
}
```

- [ ] **Step 4: `InputActivity` ruft den Lauf**

In `onResume` den `if`-Block erweitern:

```kotlin
        if (data != null) {
            onDataLoadFinished()
            updateEnd()
            scanFlow.resume()
        }
```

In `onLoadFinished` als letzte Zeile (nach `showEnd(data.endIndex)`):

```kotlin
        scanFlow.resume()
```

In `showEnd` als erste Zeile:

```kotlin
        scanFlow.onEndChanged()
```

- [ ] **Step 5: Tests laufen lassen, sie bestehen**

Run: `./gradlew :app:testDevDebugUnitTest && ./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.training.input.EndScanFlowTest`
Expected: JVM grün; Gerät PASS (5 Tests). Das Korpusfoto muss in `app/src/androidTest/assets/scan/` liegen, sonst überspringen sich zwei Tests — für diesen Lauf nicht akzeptabel.

- [ ] **Step 6: Gesamter Gerätelauf der betroffenen Klassen**

Run: `./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=de.dreier.mytargets.features`
Expected: `EndPhotoScannerDeviceTest`, `EndPhotoStoreDeviceTest`, `PendingScanTest`, `EndScanFlowTest` grün; `InputActivityTest` bleibt `@Ignore`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/de/dreier/mytargets/features/training/input/EndScanFlow.kt app/src/main/java/de/dreier/mytargets/features/training/input/InputActivity.kt app/src/androidTest/java/de/dreier/mytargets/features/training/input/EndScanFlowTest.kt
git commit -m "app: scan the end, attach the photo once, enter, report, undo"
```

---

### Task 8: Handtest auf dem S25, README, Nachtrag in der Spec

**Files:**
- Modify: `app/src/androidTest/README.md`
- Modify: `docs/design/2026-09-24-app-integration-scan-design.md`

- [ ] **Step 1: README ergänzen**

An `app/src/androidTest/README.md` anhängen:

````markdown
## `EndPhotoStoreDeviceTest`, `PendingScanTest`, `EndScanFlowTest` (app integration 8b)

`EndPhotoStoreDeviceTest` writes the 2048 px copy a scan attaches to its end:
the corpus photo (same file as above) and a synthetic 40 x 20 photo for each
of the eight EXIF orientations. `EndScanFlowTest` drives `InputActivity` with
the camera stubbed by espresso-intents: the stub copies the corpus photo into
EasyImage's `EXTRA_OUTPUT`. Without the photo the scanning tests skip
themselves; the cancel, decline and empty-camera cases still run.

```
JAVA_HOME=<jdk17> ./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.training.input.EndScanFlowTest
```
````

- [ ] **Step 2: Handtest auf dem S25**

Debug-APK installieren (`./gradlew :app:installDevDebug`), eine Trainingsrunde mit WA-Vollauflage anlegen, den Timer in den Eingabe-Einstellungen ausschalten (Präzisierung 14), dann jeden Punkt prüfen und das Ergebnis (ja/nein, Auffälligkeiten) notieren:

1. Prozesstod während der Kamera (Entwickleroptionen „Aktivitäten nicht behalten“): Treffer landen in der richtigen Passe, genau ein Bild.
2. Drehen während des Scans: Scan startet neu, genau ein Bild.
3. Drehen **gleich nach dem Auslösen** (während „Pfeile werden erkannt“ erscheint, also im Ablegen): genau ein Bild (Präzisierung 2).
4. Home während des Scans, Prozess per `adb shell am kill de.dreier.mytargets` beenden, Eingabe wieder öffnen: Treffer da, keine Meldung „Foto konnte nicht gelesen werden“ (Präzisierung 3). Paketname vorher mit `adb shell pm list packages | grep mytargets` prüfen.
5. Abbrechen im Dialog: nichts eingetragen, Foto abgelegt. Direkt danach erneut scannen: der zweite Scan läuft normal (Präzisierung 10).
6. Rückgängig: vorherige Treffer in der Ansicht und nach Neustart der Eingabe.
7. Rückgängig, dann zur nächsten Passe blättern: keine Snackbar mehr, nächste Passe unberührt (Präzisierung 5).
8. Rückfrage bei vorhandenen Treffern; Ablehnen macht kein Foto.
9. Kamera-Berechtigung zurückgesetzt (App-Info → Berechtigungen): Erlaubnisdialog, nach Erteilen öffnet die Kamera; nach Ablehnen bleibt kein pending scan (erneutes Öffnen der Eingabe startet nichts).
10. Foto einer Wand: „Auflage nicht gefunden“ · Neu aufnehmen.
11. Tastatur-Einstellung: gescannte Passe erscheint als Plot, auch nach Verlassen und Wiederkehren.
12. Galerie der gescannten Passe öffnen und schließen: das Scan-Bild bleibt (Präzisierung 7).

Scheitert ein Punkt: nicht weiter, sondern mit `superpowers:systematic-debugging` die Ursache suchen.

- [ ] **Step 3: Nachtrag in der Spec**

In `docs/design/2026-09-24-app-integration-scan-design.md` den Status auf „umgesetzt“ setzen und am Ende anhängen:

```markdown
## Nachtrag <Datum>: umgesetzt

Plan `docs/plans/2026-09-24-app-integration-scan.md`. Abweichungen und
Schärfungen gegenüber dem Text oben:

1. `ScanApplication` bekommt die aktuellen Treffer statt `shotsPerEnd` und
   kopiert sie: `EndDAO.updateShots` aktualisiert über die id, neue `Shot`s
   würden nie geschrieben.
2. Ablegen ist idempotent: Dateiname `scan-<Original>.jpg`, vorher
   `EndDAO.countEndImages`, ein prozessweiter `Mutex`. `storedImageName`
   allein reichte nicht, weil der IO-Schritt nicht abbricht und
   `onSaveInstanceState` vor dem Ende des Jobs läuft.
3. Ein pending scan ohne Original wird still verworfen (der Lauf war schon
   fertig, als der Zustand gesichert wurde).
4. `onImagePickerError` von EasyImage: pending scan verwerfen, „Foto konnte
   nicht gelesen werden“.
5. Rückgängig ist an seine Passe gebunden; Blättern schließt die Snackbar.
6. Eintragen über `replaceWithEnd`, das über `onEndUpdated` speichert und
   die Summen setzt; kein zweites `saveCurrentEnd()`.
7. Die Bilder der Passe werden nach dem Ablegen aus der Datenbank gelesen,
   weil die Galerie die Liste im Speicher als Wahrheit zurückschreibt.
8. `inputMethodFor` liegt in `InputMethodChoice`, die Größenrechnung in
   `EndPhotoSize`.
9. Abbrechen schließt den Dialog sofort; aufgeräumt wird, wenn der laufende
   Schritt zurückkehrt.
10. Neue Meldung `scan_end_gone`, wenn die Passe des Fotos nicht mehr existiert.
11. Meldungen mit Rückgängig oder Neu aufnehmen bleiben acht Sekunden statt
    `LENGTH_LONG`.

Handtest S25 (<Datum>): <je Punkt der Checkliste aus Task 8 Step 2 ein Satz>.
```

Die Platzhalter `<Datum>` und die Handtest-Zeile füllt, wer Step 2 ausgeführt hat, mit den tatsächlichen Ergebnissen.

- [ ] **Step 4: Abschließender Lauf**

Run: `./gradlew :app:testDevDebugUnitTest :detection:testDevDebugUnitTest :app:assembleDevRelease`
Expected: alles grün, Release-Build erfolgreich (R8 mit den Keep-Regeln aus 8a; EasyImage und material-dialogs laufen schon im Release).

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/README.md docs/design/2026-09-24-app-integration-scan-design.md
git commit -m "docs: 8b addendum, hand test on the S25, device test README"
```
