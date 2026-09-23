# App-Integration 8a: Fundament — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:app` kann ein Passenfoto mit dem gelernten Finder erkennen: `EndPhotoScanner.scan(photo, target, shotsPerEnd)` liefert Treffer samt Registrierung (Homographie, Bildgröße, Roll) oder einen von vier unterscheidbaren Fehlerfällen, nachgewiesen auf dem S25 im echten APK — noch ohne Oberfläche.

**Architecture:** `:detection` bekommt drei kleine Erweiterungen: `DetectionResult.face` (die Registrierung), `DetectionRequests.waFull` (eine Übersetzung für App und Korpuslauf) und `ArrowModel.fromBytes` (Modell aus dem Asset, ohne Kopie auf die Platte). `:app` bekommt das Paket `features.detection`: reine, JVM-getestete Bausteine (Verkleinerungsfaktor, Winograd-Wahl, Auflagen-Filter, Kameramatrix aus EXIF, ein Halter „einmal laden, nacheinander benutzen“) und darüber die Android-Anbindung (`PhotoInput` mit `imread`, `ModelLoading`, `EndPhotoScanner`). Ein Instrumentierungstest vergleicht das Ergebnis auf dem Telefon mit einer am PC gepinnten Referenz.

**Tech Stack:** Kotlin, Android (AGP, minSdk 23), OpenCV 4.14.0 AAR (`org.opencv:opencv`, mit `dnn` und `imgcodecs`) in `:app`, OpenCV 4.9 Desktop-Jar (`org.openpnp:opencv`) in den JVM-Tests von `:detection`, kotlinx-coroutines (`Mutex`), `androidx.exifinterface`, JUnit 4, Truth (`:detection`), kotlin.test (`:app`), AndroidX Test.

**Spec:** `docs/design/2026-09-23-app-integration-foundation-design.md` (bindend), dazu das Haupt-Design `docs/design/2026-09-09-arrow-detection-design.md` (*Integration in die App*) und `docs/design/2026-09-17-learned-finder-app-path.md` (Speicher, Winograd, Zeiten).

**Voraussetzung:** Branch `plan/app-integration-foundation` von `master`, nachdem der Design-Branch `design/app-integration-foundation` (Spec und dieser Plan) gemergt ist. Korpus-Repo `../MyTargets-corpus` auf `ac64539` oder später, `gradle-local.properties` mit `DETECTION_CORPUS_DIR` (und optional `DETECTION_MODEL_DIR`). Gradle braucht `JAVA_HOME` auf ein JDK 17 (siehe `BUILDING.md`); alle Befehle laufen aus der Wurzel des Repos. `adb` für Task 6: `D:/AndroidSDK/platform-tools/adb.exe`, Telefon an einem hinteren USB-Port.

## Global Constraints

- Nur der **gelernte Finder** läuft in der App; kein Rückfall auf `OpenCvArrowDetector`.
- Nur **WA-Vollauflagen**: `target.id == WAFull.ID` (alle Durchmesser, jede Wertungsart).
- Modell `r4-all-2026-09` als Asset unter `app/src/main/assets/arrows/r4-all-2026-09/` (`model.onnx`, `model.json`, **ohne** `parity/`), gelesen als Bytes, nie auf die Platte kopiert.
- Dekodieren mit `Imgcodecs.imread` (EXIF-Drehung durch `imread`, nie ein zweites Mal); im Dekoder halbieren, solange die lange Kante **über 4200 px** liegt, Faktor höchstens **8**.
- Winograd **aus** bei `isLowRamDevice()` oder `MemoryInfo.totalMem` **unter 6 GB** (6 · 1024³ Byte), sonst an.
- `ScanOutcome` hat genau fünf Fälle: `Detected`, `Unsupported`, `PhotoUnreadable`, `ModelUnavailable`, `ScanFailed`.
- `DetectionResult.face` ist `null` genau dann, wenn `failure != null`.
- ABI: Release `arm64-v8a`, `armeabi-v7a`; Debug zusätzlich `x86_64`.
- Die Importregel von `:detection` (`OpenCvImportRuleTest`: `core`, `imgproc`, `dnn` nur unter `arrows/`) bleibt; `:app` darf aus dem AAR auch `imgcodecs` und `android` nutzen.
- Die Pins der Korpusläufe ändern sich in diesem Plan **nicht**.
- Code, Kommentare, Testnamen auf Englisch wie im Modul; neue Dateien mit dem GPL-Kopf „Copyright (C) 2026 MyTargets contributors“ wie `ArrowDetector.kt`.

## Präzisierungen gegenüber der Spec

1. **`DetectorHolder` ist generisch** (`DetectorHolder<T>(load: () -> T)`), damit Laden, Wiederholen nach Fehlschlag und das Nacheinander ohne Android im JVM-Test geprüft werden. Das Laden selbst (OpenCV, Asset, Winograd) steht in `ModelLoading.load(context)`.
2. **`ExifIntrinsics.of(width, height, focal35mm)`** behandelt `0` wie „fehlt“: `ExifInterface.getAttributeInt(..., 0)` liefert 0, wenn der Tag fehlt, und manche Kameras schreiben 0; `CameraIntrinsics.from35mmEquivalent` verlangt > 0.
3. **`ArrowModel` bekommt `source`** (Pfad der Datei oder Name des Assets) für alle Fehlermeldungen; `onnx` wird `File?` (null bei Bytes). `LearnedArrowDetector` hält danach nur `source` und `meta`, nicht das `ArrowModel`, damit die 29 MB Bytes nach dem Bau des Netzes frei werden.
4. **Testmodell ohne lokale Property:** Fehlt `DETECTION_MODEL_DIR`, zeigt `detection.model.dir` auf das App-Asset. Die Modelltests von `:detection` laufen damit auf jedem Klon; `LearnedParityTest` überspringt sich dort mangels `parity/` wie bisher.
5. **Gerätetest ohne Sidecar:** Das Foto `2026-08-15_bedeckt_frontal_02.jpg` (EXIF 6, roh 4000 × 2252, gedreht 2252 × 4000, fünf Pfeile, Parity-Referenz vorhanden) kommt als nicht eingechecktes Test-Asset; die Erwartung ist eine **am PC gepinnte Referenz** (Treffer aus demselben Detektor über denselben Weg), nicht die Korpuswahrheit. Das prüft, dass App und Korpuslauf dasselbe rechnen; wie gut der Finder ist, messen die Korpusläufe.
6. **Keep-Regeln** kommen als `detection/consumer-rules.pro`, dazu `-keep class org.opencv.** { *; }`: OpenCVs nativer Code greift per Namen auf seine Java-Klassen zu, und ob das AAR eigene Regeln mitbringt, ist nicht geprüft.

## Review Focus

1. **EXIF-Orientierung 3 oder 8** (nicht 6): `imread` dreht selbst; `PhotoInput` darf nie zusätzlich drehen (kein `Core.rotate`, kein zweites Auslegen des Orientierungstags). Erwartung: gedrehte Größe wie in jeder Kamera-Galerie. Gepinnt durch den Größen-Check im Gerätetest (Task 6) und die Regel in Task 5.
2. **Doppelter Aufruf** (Nutzer tippt zweimal, zwei Scans gleichzeitig): das Netz wird einmal geladen, Erkennungen laufen nacheinander. Gepinnt in `DetectorHolderTest` (Task 4).
3. **Datei leer, abgeschnitten, kein Bild oder HEIC**: `PhotoUnreadable`, kein Absturz, kein geladenes Netz verloren. Gepinnt in `DecodeReductionTest` (Länge ≤ 0) und im Gerätetest (Datei ohne Bild) — Task 4 und 6.
4. **EXIF ohne oder mit `0` als 35-mm-Brennweite**: Rückfall `0,75 · lange Kante` statt `IllegalArgumentException`. Gepinnt in `ExifIntrinsicsTest` (Task 4).
5. **Fehler mitten in der Erkennung** (`CvException`, `OutOfMemoryError`, `IllegalStateException` aus dem Vorwärtslauf): `ScanFailed`, der Halter bleibt benutzbar, das Mat wird freigegeben. Gepinnt in `DetectorHolderTest.anExceptionInsideTheBlockLeavesTheHolderUsable` (Task 4) und im `finally` von Task 5.

## Dateien

| Datei | Aufgabe |
|---|---|
| `detection/.../ArrowDetector.kt` | Ändern: `DetectedFace`, `DetectionResult.face` |
| `detection/.../arrows/OpenCvArrowDetector.kt`, `LearnedArrowDetector.kt` | Ändern: `face` durchreichen; Netz aus Datei oder Bytes |
| `detection/.../arrows/WaFullZones.kt` | Verschieben aus `src/test` nach `src/main` |
| `detection/.../DetectionRequests.kt` | Neu: `waFull` |
| `detection/.../arrows/ArrowModel.kt` | Ändern: `source`, `fromBytes`, `Weights` |
| `detection/build.gradle`, `detection/consumer-rules.pro` | Ändern/Neu: Modell-Fallback, Keep-Regeln |
| `app/src/main/assets/arrows/r4-all-2026-09/` | Neu: Modell |
| `app/build.gradle` | Ändern: Abhängigkeiten, ABI-Filter |
| `app/.../features/detection/*.kt` | Neu: `DecodeReduction`, `WinogradPolicy`, `ScanSupport`, `ExifIntrinsics`, `DetectorHolder`, `PhotoInput`, `ModelLoading`, `ScanOutcome`, `EndPhotoScanner` |
| `app/src/test/.../features/detection/*Test.kt` | Neu: JVM-Tests |
| `detection/src/test/.../arrows/ScanReferenceRun.kt` | Neu: PC-Referenz für den Gerätetest |
| `app/src/androidTest/.../features/detection/EndPhotoScannerDeviceTest.kt`, `app/src/androidTest/assets/scan/.gitignore`, `app/src/androidTest/README.md` | Neu: Gerätetest |

`detection/...` steht für `detection/src/main/java/de/dreier/mytargets/detection`, `detection/src/test/...` für `detection/src/test/java/de/dreier/mytargets/detection`, `app/...` für `app/src/main/java/de/dreier/mytargets`, `app/src/test/...` für `app/src/test/java/de/dreier/mytargets`.

---

### Task 1: Das Ergebnis trägt die Registrierung

**Files:**
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/ArrowDetector.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/arrows/OpenCvArrowDetector.kt:83-94`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetector.kt:104-116`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/ArrowDetectorContractTest.kt`, `detection/src/test/java/de/dreier/mytargets/detection/arrows/OpenCvArrowDetectorTest.kt`, `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetectorTest.kt`

**Interfaces:**
- Produces: `class DetectedFace(val imageToTarget: Mat3, val imageWidth: Int, val imageHeight: Int, val roll: Roll)`; `class DetectionResult(shots, faceConfidence, reason, failure, face: DetectedFace?)` mit `require((face == null) == (failure != null))`; `DetectionResult.failed(failure)` setzt `face = null`.

- [ ] **Step 1: Failing tests schreiben**

In `ArrowDetectorContractTest` den Stub in `aDetectorCanBeSubstituted` um `face` ergänzen und zwei Tests anhängen (Importe `de.dreier.mytargets.detection.geometry.Mat3`, `de.dreier.mytargets.detection.registration.Roll`):

```kotlin
            override fun detect(image: Mat, request: DetectionRequest, debug: DebugSink) = DetectionResult(
                shots = listOf(DetectedShot(0, 0.1f, -0.2f, 0.9f)),
                faceConfidence = 0.8f,
                reason = SelectionReason.FEWER_THAN_EXPECTED,
                failure = null,
                face = DetectedFace(Mat3.identity(), 4000, 3000, Roll.NOT_MEASURED)
            )
```

```kotlin
    @Test
    fun aFailedResultCarriesNoFace() {
        assertThat(DetectionResult.failed(DetectionFailure.FACE_MISMATCH).face).isNull()
    }

    @Test
    fun aSuccessfulResultMustCarryItsFace() {
        try {
            DetectionResult(emptyList(), 1f, SelectionReason.COMPLETE, failure = null, face = null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 8c draws the photo through this homography; a result without it cannot be shown.
        }
    }
```

In `OpenCvArrowDetectorTest` (Import `com.google.common.truth.Truth.assertThat` ist da):

```kotlin
    @Test
    fun aDetectionCarriesTheRegistrationItWasMadeIn() {
        val photo = SyntheticArrows.photograph(at30, 2000, 1500, entries.map { SyntheticArrow(it) })
        val (analysis, result) = try {
            detector.analyse(photo, request(at30, entries.size)) to detector.detect(photo, request(at30, entries.size))
        } finally {
            photo.release()
        }
        val registration = (analysis as ArrowAnalysis.Analysed).registration
        val face = checkNotNull(result.face) { "a successful detection carries its face" }

        assertThat(face.imageWidth).isEqualTo(2000)
        assertThat(face.imageHeight).isEqualTo(1500)
        for (r in 0..2) for (c in 0..2) {
            assertThat(face.imageToTarget[r, c]).isWithin(1e-12).of(registration.imageToTarget[r, c])
        }
        assertThat(face.roll.anchored).isEqualTo(registration.roll.anchored)
        assertThat(face.roll.correctionDegrees).isWithin(1e-12).of(registration.roll.correctionDegrees)
    }
```

In `LearnedArrowDetectorTest` (das Modell lädt `requireModel()` in `@Before`):

```kotlin
    @Test
    fun aDetectionCarriesTheRegistrationItWasMadeIn() {
        val detector = LearnedArrowDetector(model)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val (analysis, result) = try {
            detector.analyse(photo, request(entries.size)) to detector.detect(photo, request(entries.size))
        } finally {
            photo.release()
        }
        val registration = (analysis as LearnedAnalysis.Analysed).registration
        val face = checkNotNull(result.face) { "a successful detection carries its face" }

        assertThat(face.imageWidth).isEqualTo(2000)
        assertThat(face.imageHeight).isEqualTo(1500)
        for (r in 0..2) for (c in 0..2) {
            assertThat(face.imageToTarget[r, c]).isWithin(1e-12).of(registration.imageToTarget[r, c])
        }
    }
```

- [ ] **Step 2: Tests laufen lassen, sie scheitern**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*ArrowDetectorContractTest*" --tests "*OpenCvArrowDetectorTest*" --tests "*LearnedArrowDetectorTest*"`
Expected: Kompilierfehler `No parameter with name 'face'` / `Unresolved reference: DetectedFace`.

- [ ] **Step 3: Implementieren**

In `ArrowDetector.kt` (Importe `de.dreier.mytargets.detection.geometry.Mat3`, `de.dreier.mytargets.detection.registration.Roll`), vor `DetectionResult`:

```kotlin
/**
 * Where the face lay in the photograph the detector was given: [imageToTarget]
 * maps its pixels to spot-local target coordinates, [imageWidth] and
 * [imageHeight] are its size, i.e. the decoded image after EXIF rotation and
 * any reduction, not the file on disk. The correction layer (8c) draws the
 * stored photo through it.
 */
class DetectedFace(
    val imageToTarget: Mat3,
    val imageWidth: Int,
    val imageHeight: Int,
    val roll: Roll
)
```

`DetectionResult` ersetzen durch:

```kotlin
class DetectionResult(
    val shots: List<DetectedShot>,
    val faceConfidence: Float,
    val reason: SelectionReason?,
    val failure: DetectionFailure?,
    val face: DetectedFace?
) {
    init {
        require((face == null) == (failure != null)) {
            "a result carries its face exactly when the detection did not fail"
        }
    }

    companion object {
        fun failed(failure: DetectionFailure) = DetectionResult(
            shots = emptyList(),
            faceConfidence = 0f,
            reason = null,
            failure = failure,
            face = null
        )
    }
}
```

In `OpenCvArrowDetector.detect` (Import `de.dreier.mytargets.detection.DetectedFace`) im `Analysed`-Zweig nach `failure = null` ergänzen:

```kotlin
                failure = null,
                face = DetectedFace(
                    analysis.registration.imageToTarget, image.cols(), image.rows(), analysis.registration.roll
                )
```

In `LearnedArrowDetector.detect` genauso (Import `de.dreier.mytargets.detection.DetectedFace`):

```kotlin
                failure = null,
                face = DetectedFace(
                    analysis.registration.imageToTarget, image.cols(), image.rows(), analysis.registration.roll
                )
```

- [ ] **Step 4: Tests laufen lassen, sie bestehen; ganze Suite des Moduls**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS (Korpusläufe laufen mit, Pins unverändert; die Laufzeit liegt bei mehreren Minuten).

- [ ] **Step 5: Commit**

```bash
git add detection/src
git commit -m "detection: the result carries the registration it was made in"
```

---

### Task 2: Eine Übersetzung der WA-Vollauflage für App und Korpus

**Files:**
- Move: `detection/src/test/java/de/dreier/mytargets/detection/arrows/WaFullZones.kt` → `detection/src/main/java/de/dreier/mytargets/detection/arrows/WaFullZones.kt`
- Create: `detection/src/main/java/de/dreier/mytargets/detection/DetectionRequests.kt`
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowCorpusRuns.kt:102-115`
- Test: `detection/src/test/java/de/dreier/mytargets/detection/DetectionRequestsTest.kt`

**Interfaces:**
- Consumes: `DetectionRequest`, `FaceLayout.singleSpot()`, `RingTransitions.WA_FULL`, `CameraIntrinsics`.
- Produces: `object DetectionRequests { fun waFull(shotsPerEnd: Int, intrinsics: CameraIntrinsics): DetectionRequest }`; `de.dreier.mytargets.detection.arrows.WaFullZones` in `main` (`RADII`, `zoneOf`), Paket unverändert.

- [ ] **Step 1: Failing test schreiben**

`detection/src/test/java/de/dreier/mytargets/detection/DetectionRequestsTest.kt` (mit GPL-Kopf):

```kotlin
package de.dreier.mytargets.detection

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.arrows.WaFullZones
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Test

class DetectionRequestsTest {

    @Test
    fun theWaFullRequestIsTheOneTheCorpusRunsUse() {
        val intrinsics = CameraIntrinsics.approximate(4000, 3000)

        val request = DetectionRequests.waFull(6, intrinsics)

        assertThat(request.layout.faceCount).isEqualTo(1)
        assertThat(request.zoneRadii).isEqualTo(WaFullZones.RADII)
        assertThat(request.transitions).isEqualTo(RingTransitions.WA_FULL)
        assertThat(request.expectedShots).isEqualTo(6)
        assertThat(request.intrinsics).isSameInstanceAs(intrinsics)
    }
}
```

- [ ] **Step 2: Test laufen lassen, er scheitert**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*DetectionRequestsTest*"`
Expected: FAIL, `Unresolved reference: DetectionRequests`.

- [ ] **Step 3: Verschieben und implementieren**

```bash
git mv detection/src/test/java/de/dreier/mytargets/detection/arrows/WaFullZones.kt detection/src/main/java/de/dreier/mytargets/detection/arrows/WaFullZones.kt
```

Im KDoc von `WaFullZones` den Satz „The app will translate them from TargetModelBase; tests and the corpus run use these.“ ersetzen durch „The app and the corpus run both build their request from these (DetectionRequests.waFull).“

`detection/src/main/java/de/dreier/mytargets/detection/DetectionRequests.kt` (mit GPL-Kopf):

```kotlin
package de.dreier.mytargets.detection

import de.dreier.mytargets.detection.arrows.WaFullZones
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.registration.RingTransitions

/**
 * The requests the detector is measured with. One place, so the app asks
 * exactly what the corpus runs pin (app integration 8a): only the WA full
 * face, which is the only face with colour transitions and the only one the
 * model has seen.
 */
object DetectionRequests {
    fun waFull(shotsPerEnd: Int, intrinsics: CameraIntrinsics) = DetectionRequest(
        FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, shotsPerEnd, intrinsics
    )
}
```

In `ArrowCorpusRuns.requestFor` den Rückgabewert ersetzen:

```kotlin
        return DetectionRequests.waFull(
            checkNotNull(entry.shotsPerEnd) { "${entry.imageName}: the sidecar names no shotsPerEnd" },
            intrinsics
        )
```

Import `de.dreier.mytargets.detection.DetectionRequests` ergänzen; Importe von `FaceLayout` und `RingTransitions` entfernen, falls die Datei sie sonst nicht mehr braucht.

- [ ] **Step 4: Tests laufen lassen; Korpusläufe mit unveränderten Pins**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS, insbesondere `ArrowCorpusRun` und `LearnedArrowCorpusRun` mit ihren bisherigen Pins (das ist der Beleg, dass die Übersetzung nur umgezogen ist) und `OpenCvImportRuleTest` (die neue Datei importiert kein OpenCV).

- [ ] **Step 5: Commit**

```bash
git add detection/src
git commit -m "detection: DetectionRequests.waFull, one translation for the app and the corpus runs"
```

---

### Task 3: Das Modell im Repo, lesbar aus Bytes

**Files:**
- Create: `app/src/main/assets/arrows/r4-all-2026-09/model.onnx`, `app/src/main/assets/arrows/r4-all-2026-09/model.json`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/arrows/ArrowModel.kt`
- Modify: `detection/src/main/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetector.kt`
- Modify: `detection/build.gradle` (Modell-Fallback)
- Modify: `detection/src/test/java/de/dreier/mytargets/detection/arrows/LearnedArrowDetectorTest.kt:138-139`, `LearnedModelSmokeTest.kt:57` (`onnx` ist jetzt nullable)
- Test: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ArrowModelTest.kt`, `LearnedArrowDetectorTest.kt`

**Interfaces:**
- Produces: `class ArrowModel` mit `val source: String`, `val meta: ArrowModelMeta`, `val onnx: File?`, `internal val weights: ArrowModel.Weights`; Konstruktor `ArrowModel(onnx: File, meta: ArrowModelMeta)` bleibt; neu `ArrowModel.fromBytes(onnx: ByteArray, metaJson: String, source: String): ArrowModel`. Fehlermeldungen von `LearnedArrowDetector` nennen `source`.

- [ ] **Step 1: Modell ins Repo legen**

```bash
mkdir -p app/src/main/assets/arrows/r4-all-2026-09
cp ../MyTargets-corpus/models/arrows/r4-all-2026-09/model.onnx ../MyTargets-corpus/models/arrows/r4-all-2026-09/model.json app/src/main/assets/arrows/r4-all-2026-09/
ls -l app/src/main/assets/arrows/r4-all-2026-09/
```

Expected: `model.onnx` 28783802 Byte, `model.json` 587 Byte, kein `parity/`.

In `detection/build.gradle` im `testDevDebugUnitTest`-Block den Modellteil ersetzen:

```groovy
        // Without a local property the model the app ships is the one tested
        // (app integration 8a); a DETECTION_MODEL_DIR still wins, e.g. a new
        // export before it replaces the asset.
        def modelDir = rootProject.findProperty('DETECTION_MODEL_DIR') ?: 'app/src/main/assets/arrows/r4-all-2026-09'
        task.systemProperty 'detection.model.dir',
            rootProject.file(modelDir).absolutePath
```

- [ ] **Step 2: Failing tests schreiben**

In `ArrowModelTest` (Truth und `File` sind importiert; die Klasse hat schon die `TemporaryFolder`-Regel `folder` und den gültigen Sidecar `complete` mit `inputSize` 768):

```kotlin
    @Test
    fun aModelFromBytesNamesItsSource() {
        val model = ArrowModel.fromBytes(byteArrayOf(1, 2, 3), complete, "assets/arrows/r4")

        assertThat(model.source).isEqualTo("assets/arrows/r4")
        assertThat(model.onnx).isNull()
        assertThat(model.meta.inputSize).isEqualTo(768)
    }

    @Test
    fun aBrokenSidecarFromBytesNamesTheAsset() {
        val e = try {
            ArrowModel.fromBytes(byteArrayOf(1, 2, 3), "{", "assets/arrows/r4")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }
        assertThat(e).hasMessageThat().contains("assets/arrows/r4/model.json")
    }

    @Test
    fun emptyWeightsAreRejectedByName() {
        val e = try {
            ArrowModel.fromBytes(ByteArray(0), complete, "assets/arrows/r4")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }
        assertThat(e).hasMessageThat().contains("assets/arrows/r4")
        assertThat(e).hasMessageThat().contains("empty")
    }

    @Test
    fun aModelFromAFolderNamesItsFile() {
        val dir = folder.newFolder("named")
        File(dir, "model.onnx").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "model.json").writeText(complete)

        assertThat(ArrowModel.load(dir).source).isEqualTo(File(dir, "model.onnx").path)
    }
```

In `LearnedArrowDetectorTest`:

```kotlin
    @Test
    fun aModelReadFromBytesDetectsLikeTheFolder() {
        val onnx = checkNotNull(model.onnx)
        val fromBytes = ArrowModel.fromBytes(
            onnx.readBytes(), File(onnx.parentFile, ArrowModel.META_FILE).readText(), "bytes of ${onnx.name}"
        )
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val (fromFile, fromMemory) = try {
            LearnedArrowDetector(model).detect(photo, request(entries.size)) to
                LearnedArrowDetector(fromBytes).detect(photo, request(entries.size))
        } finally {
            photo.release()
        }

        assertThat(fromMemory.shots).hasSize(fromFile.shots.size)
        fromFile.shots.zip(fromMemory.shots).forEach { (a, b) ->
            assertThat(b.faceIndex).isEqualTo(a.faceIndex)
            assertThat(b.x).isWithin(1e-6f).of(a.x)
            assertThat(b.y).isWithin(1e-6f).of(a.y)
            assertThat(b.confidence).isWithin(1e-6f).of(a.confidence)
        }
    }

    @Test
    fun unreadableBytesFailAtConstructionByName() {
        val garbage = ArrowModel.fromBytes(
            byteArrayOf(1, 2, 3), File(checkNotNull(model.onnx).parentFile, ArrowModel.META_FILE).readText(), "garbage-asset"
        )
        try {
            LearnedArrowDetector(garbage)
            throw AssertionError("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().contains("garbage-asset")
        }
    }
```

Bestehende Stellen an `onnx: File?` anpassen: `LearnedArrowDetectorTest` Zeile 139 `model.onnx` → `checkNotNull(model.onnx)`; `LearnedModelSmokeTest` Zeile 57 `model.onnx.absolutePath` → `checkNotNull(model.onnx).absolutePath`.

- [ ] **Step 3: Tests laufen lassen, sie scheitern**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*ArrowModelTest*" --tests "*LearnedArrowDetectorTest*"`
Expected: Kompilierfehler `Unresolved reference: fromBytes` / `source`.

- [ ] **Step 4: `ArrowModel` implementieren**

Die Klasse `ArrowModel` (nicht `ArrowModelMeta`) ersetzen durch:

```kotlin
/**
 * The ONNX weights and their sidecar, from a folder (tests, corpus runs) or
 * from memory (the app reads its asset, app integration 8a). [source] names
 * the weights in every error message.
 */
class ArrowModel private constructor(
    val source: String,
    val meta: ArrowModelMeta,
    internal val weights: Weights
) {
    constructor(onnx: File, meta: ArrowModelMeta) : this(onnx.path, meta, Weights.InFile(onnx))

    /** The weights file, or null for a model read from memory. */
    val onnx: File?
        get() = (weights as? Weights.InFile)?.file

    internal sealed class Weights {
        class InFile(val file: File) : Weights()
        class InMemory(val bytes: ByteArray) : Weights()
    }

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

        /**
         * The weights as bytes and the sidecar as text, e.g. from an asset.
         * [source] is the folder they came from; the sidecar's errors name
         * [source]/model.json.
         */
        fun fromBytes(onnx: ByteArray, metaJson: String, source: String): ArrowModel {
            require(onnx.isNotEmpty()) { "$source: empty $ONNX_FILE" }
            return ArrowModel(source, parseMeta(metaJson, "$source/$META_FILE"), Weights.InMemory(onnx))
        }

        // parseMeta and MetaJson unchanged below.
```

`parseMeta` und `MetaJson` bleiben unverändert im Companion bzw. in der Klasse (die private Klasse `MetaJson` wandert mit in die neue Klasse).

- [ ] **Step 5: `LearnedArrowDetector` auf `source` und beide Quellen umstellen**

Konstruktor und Kopf ersetzen (Importe `org.opencv.core.MatOfByte` ergänzen):

```kotlin
class LearnedArrowDetector(
    model: ArrowModel,
    private val registrar: FaceRegistrar = OpenCvFaceRegistrar(),
    winograd: Boolean = true
) : ArrowDetector {

    // Only the name and the sidecar are kept: a model read from memory holds
    // 29 MB of weights that are dead once the network is built.
    private val source: String = model.source
    private val meta: ArrowModelMeta = model.meta

    private val net: Net = readNet(model)

    init {
        check(!net.empty()) { "$source: OpenCV read an empty network" }
        // Winograd trades roughly 300 MB for a 2.3 times faster pass (app-path findings of 2026-09-17).
        net.enableWinograd(winograd)
    }
```

Im Rest der Klasse: jedes `model.meta` durch `meta`, jedes `model.onnx.path` durch `source` ersetzen; in `analyse` die Zeile `val meta = model.meta` löschen. Im Companion ergänzen:

```kotlin
        fun readNet(model: ArrowModel): Net = try {
            when (val weights = model.weights) {
                is ArrowModel.Weights.InFile -> Dnn.readNetFromONNX(weights.file.absolutePath)
                is ArrowModel.Weights.InMemory -> {
                    val buffer = Mat(1, weights.bytes.size, CvType.CV_8U)
                    try {
                        buffer.put(0, 0, weights.bytes)
                        val bytes = MatOfByte(buffer)
                        try {
                            Dnn.readNetFromONNX(bytes)
                        } finally {
                            bytes.release()
                        }
                    } finally {
                        buffer.release()
                    }
                }
            }
        } catch (e: CvException) {
            throw IllegalStateException("cannot read ${model.source}: ${e.message}", e)
        }
```

(`private companion object` bleibt privat; `readNet` darin ist für die Klasse sichtbar.)

- [ ] **Step 6: Tests laufen lassen, sie bestehen; ganze Suite**

Run: `./gradlew :detection:testDevDebugUnitTest`
Expected: PASS, auch ohne `DETECTION_MODEL_DIR` in `gradle-local.properties` (dann über das Asset); `LearnedArrowCorpusRun`-Pins unverändert; `OpenCvImportRuleTest` grün (`MatOfByte` ist `org.opencv.core`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/assets/arrows detection/build.gradle detection/src
git commit -m "detection: the model r4-all-2026-09 ships as an app asset and reads from bytes"
```

---

### Task 4: Die reinen Bausteine in `:app`

**Files:**
- Modify: `app/build.gradle` (Abhängigkeiten, ABI-Filter)
- Create: `app/src/main/java/de/dreier/mytargets/features/detection/DecodeReduction.kt`, `WinogradPolicy.kt`, `ScanSupport.kt`, `ExifIntrinsics.kt`, `DetectorHolder.kt`
- Test: `app/src/test/java/de/dreier/mytargets/features/detection/DecodeReductionTest.kt`, `WinogradPolicyTest.kt`, `ScanSupportTest.kt`, `ExifIntrinsicsTest.kt`, `DetectorHolderTest.kt`

**Interfaces:**
- Consumes: `CameraIntrinsics.approximate/from35mmEquivalent` aus `:detection`, `WAFull.ID` und `Target` aus `:shared`.
- Produces:
  - `object DecodeReduction { const val MAX_LONG_EDGE = 4200; const val MAX_FACTOR = 8; fun factorFor(longEdge: Int): Int }`
  - `object WinogradPolicy { const val MIN_TOTAL_MEM: Long = 6L * 1024 * 1024 * 1024; fun enabled(isLowRamDevice: Boolean, totalMem: Long): Boolean }`
  - `object ScanSupport { fun supports(targetId: Long): Boolean; fun supports(target: Target): Boolean }`
  - `object ExifIntrinsics { fun of(width: Int, height: Int, focalLength35mm: Int): CameraIntrinsics }`
  - `class DetectorHolder<T : Any>(load: () -> T) { suspend fun <R> withLoaded(onLoadFailure: (Throwable) -> R, block: (T) -> R): R }`

- [ ] **Step 1: Abhängigkeiten und ABI-Filter**

In `app/build.gradle` im `dependencies`-Block nach `implementation project(':shared')`:

```groovy
    // Arrow detection (app integration 8a): the pipeline and OpenCV with dnn and imgcodecs.
    implementation project(':detection')
    implementation libs.opencv.android
```

In `buildTypes`, `debug` und `release` je um `ndk` ergänzen:

```groovy
        debug {
            // ... bestehende Zeilen bleiben
            // x86_64 keeps the detection running in the emulator (arrow design, APK-Größe).
            ndk { abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64' }
        }
        release {
            // ... bestehende Zeilen bleiben
            ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }
        }
```

Run: `./gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Failing tests schreiben**

Alle Testdateien im Paket `de.dreier.mytargets.features.detection`, GPL-Kopf, `import org.junit.Test`, `import kotlin.test.assertEquals` usw.

`DecodeReductionTest.kt`:

```kotlin
class DecodeReductionTest {

    @Test
    fun halvesWhileTheLongEdgeIsAbove4200() {
        val cases = mapOf(
            1280 to 1, 4000 to 1, 4160 to 1, 4200 to 1, 4201 to 2,
            8160 to 2, 8400 to 2, 8401 to 4, 16320 to 4, 40000 to 8
        )
        for ((longEdge, factor) in cases) {
            assertEquals(factor, DecodeReduction.factorFor(longEdge), "long edge $longEdge")
        }
    }

    @Test
    fun noEdgeIsNoImage() {
        // BitmapFactory reports -1 for a file it cannot read; that is PhotoUnreadable, not a factor.
        assertFailsWith<IllegalArgumentException> { DecodeReduction.factorFor(0) }
        assertFailsWith<IllegalArgumentException> { DecodeReduction.factorFor(-1) }
    }
}
```

`WinogradPolicyTest.kt`:

```kotlin
class WinogradPolicyTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun onWithPlentyOfMemory() = assertTrue(WinogradPolicy.enabled(isLowRamDevice = false, totalMem = 12 * gib))

    @Test
    fun offBelowSixGigabytes() = assertFalse(WinogradPolicy.enabled(isLowRamDevice = false, totalMem = 4 * gib))

    @Test
    fun onAtExactlySixGigabytes() = assertTrue(WinogradPolicy.enabled(isLowRamDevice = false, totalMem = 6 * gib))

    @Test
    fun offOnALowRamDeviceWhateverItReports() = assertFalse(WinogradPolicy.enabled(isLowRamDevice = true, totalMem = 12 * gib))
}
```

`ScanSupportTest.kt` (Importe `de.dreier.mytargets.shared.targets.models.WAFull`, `WAVertical3Spot`, `WAField`):

```kotlin
class ScanSupportTest {
    @Test
    fun theWaFullFaceIsSupported() = assertTrue(ScanSupport.supports(WAFull.ID))

    @Test
    fun otherFacesAreNot() {
        assertFalse(ScanSupport.supports(WAVertical3Spot.ID))
        assertFalse(ScanSupport.supports(WAField.ID))
    }
}
```

`ExifIntrinsicsTest.kt`:

```kotlin
class ExifIntrinsicsTest {

    @Test
    fun theEquivalentFocalLengthSpansTheLongEdge() {
        // 26 mm on 36 mm film across 4000 px.
        val k = ExifIntrinsics.of(2252, 4000, 26)
        assertEquals(26.0 / 36.0 * 4000.0, k.focalLengthPx, 1e-9)
        assertEquals(1126.0, k.principalPoint.x, 1e-9)
        assertEquals(2000.0, k.principalPoint.y, 1e-9)
    }

    @Test
    fun zeroMeansTheTagIsMissing() {
        // getAttributeInt returns the default 0 without the tag, and some cameras write 0.
        assertEquals(0.75 * 4000.0, ExifIntrinsics.of(4000, 3000, 0).focalLengthPx, 1e-9)
    }

    @Test
    fun aNegativeValueIsTreatedAsMissing() {
        assertEquals(0.75 * 4000.0, ExifIntrinsics.of(4000, 3000, -5).focalLengthPx, 1e-9)
    }
}
```

`DetectorHolderTest.kt` (Importe `kotlinx.coroutines.*`, `java.util.concurrent.atomic.AtomicInteger`):

```kotlin
class DetectorHolderTest {

    @Test
    fun loadsOnceAcrossCalls() = runBlocking {
        val loads = AtomicInteger()
        val holder = DetectorHolder { loads.incrementAndGet(); "net" }

        repeat(3) { assertEquals("net", holder.withLoaded({ "failed" }) { it }) }

        assertEquals(1, loads.get())
    }

    @Test
    fun aFailedLoadIsRetriedOnTheNextCall() = runBlocking {
        var attempts = 0
        val holder = DetectorHolder {
            attempts++
            if (attempts == 1) throw UnsatisfiedLinkError("no libopencv_java4") else "net"
        }

        assertEquals("failed: no libopencv_java4", holder.withLoaded({ "failed: ${it.message}" }) { it })
        assertEquals("net", holder.withLoaded({ "failed" }) { it })
        assertEquals(2, attempts)
    }

    @Test
    fun callsRunOneAtATime() = runBlocking {
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val holder = DetectorHolder { "net" }

        (1..8).map {
            launch(Dispatchers.Default) {
                holder.withLoaded({ Unit }) {
                    val now = inside.incrementAndGet()
                    most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    Thread.sleep(5)
                    inside.decrementAndGet()
                }
            }
        }.joinAll()

        assertEquals(1, most.get())
    }

    @Test
    fun anExceptionInsideTheBlockLeavesTheHolderUsable() = runBlocking {
        val loads = AtomicInteger()
        val holder = DetectorHolder { loads.incrementAndGet(); "net" }

        assertFailsWith<IllegalStateException> { holder.withLoaded({ "failed" }) { error("forward pass failed") } }

        assertEquals("net", holder.withLoaded({ "failed" }) { it })
        assertEquals(1, loads.get())
    }
}
```

- [ ] **Step 3: Tests laufen lassen, sie scheitern**

Run: `./gradlew :app:testDevDebugUnitTest --tests "de.dreier.mytargets.features.detection.*"`
Expected: Kompilierfehler, die Objekte fehlen.

- [ ] **Step 4: Implementieren**

Alle Dateien im Paket `de.dreier.mytargets.features.detection`, GPL-Kopf.

`DecodeReduction.kt`:

```kotlin
/**
 * How much the decoder shrinks a photo (app integration 8a, Foto lesen):
 * halve while the long edge is above [MAX_LONG_EDGE], at most [MAX_FACTOR]
 * times. Nothing in the pipeline uses more than 2000 px; the bound only has to
 * leave every corpus photo (up to 4160 px) untouched, so the app sees the
 * pixels the pins were measured on.
 */
object DecodeReduction {
    const val MAX_LONG_EDGE = 4200
    const val MAX_FACTOR = 8

    fun factorFor(longEdge: Int): Int {
        require(longEdge > 0) { "an image has a positive size, got a long edge of $longEdge" }
        var factor = 1
        while (longEdge > MAX_LONG_EDGE * factor && factor < MAX_FACTOR) factor *= 2
        return factor
    }
}
```

`WinogradPolicy.kt`:

```kotlin
/**
 * Winograd costs about 300 MB of native memory for a 2.3 times faster pass
 * (app-path findings 3a: about 700 MB with, 400 MB without at 768 px). The
 * measure is the device's total memory, not memoryClass: that is the Java
 * heap limit, and the network lives in native memory.
 */
object WinogradPolicy {
    const val MIN_TOTAL_MEM: Long = 6L * 1024 * 1024 * 1024

    fun enabled(isLowRamDevice: Boolean, totalMem: Long): Boolean =
        !isLowRamDevice && totalMem >= MIN_TOTAL_MEM
}
```

`ScanSupport.kt`:

```kotlin
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.targets.models.WAFull

/**
 * Which faces the scan is offered for: only the WA full face, in every
 * diameter and scoring style. It is the only face with colour transitions for
 * the registration and the only one the model has seen; others come with
 * their own corpus photos.
 */
object ScanSupport {
    fun supports(targetId: Long): Boolean = targetId == WAFull.ID

    fun supports(target: Target): Boolean = supports(target.id)
}
```

`ExifIntrinsics.kt`:

```kotlin
import de.dreier.mytargets.detection.geometry.CameraIntrinsics

/**
 * The camera matrix from EXIF's FocalLengthIn35mmFilm for an image of the
 * decoded size. A value of 0 or less means the tag is missing
 * (ExifInterface's default) and falls back to 0.75 of the long edge.
 */
object ExifIntrinsics {
    fun of(width: Int, height: Int, focalLength35mm: Int): CameraIntrinsics =
        if (focalLength35mm > 0) {
            CameraIntrinsics.from35mmEquivalent(width, height, focalLength35mm.toDouble())
        } else {
            CameraIntrinsics.approximate(width, height)
        }
}
```

`DetectorHolder.kt`:

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One detector per process (app integration 8a): loaded on first use, handed
 * out one call at a time because the detector is not thread-safe. A failed
 * load leaves the holder empty and is retried on the next call; low memory
 * can pass.
 */
class DetectorHolder<T : Any>(private val load: () -> T) {
    private val mutex = Mutex()
    private var loaded: T? = null

    suspend fun <R> withLoaded(onLoadFailure: (Throwable) -> R, block: (T) -> R): R = mutex.withLock {
        val value = loaded ?: try {
            load().also { loaded = it }
        } catch (e: Throwable) {
            // Throwable on purpose: a missing native library is an UnsatisfiedLinkError,
            // a device without memory an OutOfMemoryError; both mean "no model", not a crash.
            return@withLock onLoadFailure(e)
        }
        block(value)
    }
}
```

- [ ] **Step 5: Tests laufen lassen, sie bestehen**

Run: `./gradlew :app:testDevDebugUnitTest --tests "de.dreier.mytargets.features.detection.*"`
Expected: PASS (13 Tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle app/src/main/java/de/dreier/mytargets/features/detection app/src/test/java/de/dreier/mytargets/features/detection
git commit -m "app: detection dependency and the pure pieces of the scanner"
```

---

### Task 5: Die Android-Anbindung — `PhotoInput`, `ModelLoading`, `EndPhotoScanner`

**Files:**
- Create: `app/src/main/java/de/dreier/mytargets/features/detection/PhotoInput.kt`, `ModelLoading.kt`, `ScanOutcome.kt`, `EndPhotoScanner.kt`
- Create: `detection/consumer-rules.pro`
- Modify: `detection/build.gradle` (`consumerProguardFiles`)

**Interfaces:**
- Consumes: alles aus Task 1 bis 4; `ArrowModel.fromBytes`, `LearnedArrowDetector(model, winograd = …)`, `DetectionRequests.waFull`, `DecodeReduction.factorFor`, `ExifIntrinsics.of`, `WinogradPolicy.enabled`, `ScanSupport.supports(target)`, `DetectorHolder`.
- Produces:
  - `class DecodedPhoto(val image: Mat, val intrinsics: CameraIntrinsics)`; `class PhotoUnreadableException(message: String, cause: Throwable? = null) : Exception`
  - `object PhotoInput { fun read(photo: File): DecodedPhoto; fun read(photo: File, factor: Int): DecodedPhoto }`
  - `class LoadedDetector(val detector: ArrowDetector, val modelName: String)`; `object ModelLoading { const val MODEL_NAME = "r4-all-2026-09"; fun load(context: Context): LoadedDetector }`
  - `sealed class ScanOutcome` mit `Detected(result: DetectionResult, modelName: String)`, `object Unsupported`, `PhotoUnreadable(cause: Throwable)`, `ModelUnavailable(cause: Throwable)`, `ScanFailed(cause: Throwable)`
  - `class EndPhotoScanner { suspend fun scan(photo: File, target: Target, shotsPerEnd: Int): ScanOutcome; companion object { fun get(context: Context): EndPhotoScanner } }`

Diese Klassen brauchen Android und OpenCVs native Bibliothek; ihr Test ist der Gerätetest in Task 6. Hier wird nur gebaut.

- [ ] **Step 1: `ScanOutcome.kt`**

```kotlin
import de.dreier.mytargets.detection.DetectionResult

/**
 * What a scan came to (app integration 8a). Each case gets its own answer in
 * 8b (arrow design, Fehlerfälle); a face that was not found or does not match
 * is a Detected with result.failure set.
 */
sealed class ScanOutcome {
    class Detected(val result: DetectionResult, val modelName: String) : ScanOutcome()

    /** The face is not a WA full face; 8b does not offer the scan for it. */
    object Unsupported : ScanOutcome()

    /** The file is missing, empty, cut off or in a format imread cannot decode. */
    class PhotoUnreadable(val cause: Throwable) : ScanOutcome()

    /** OpenCV or the network did not load; the next scan tries again. */
    class ModelUnavailable(val cause: Throwable) : ScanOutcome()

    /** The detection itself broke off, e.g. out of memory in the forward pass. */
    class ScanFailed(val cause: Throwable) : ScanOutcome()
}
```

- [ ] **Step 2: `PhotoInput.kt`**

```kotlin
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import org.opencv.core.CvException
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.IOException
import kotlin.math.max

class PhotoUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A photo as the detector takes it; the caller releases [image]. */
class DecodedPhoto(val image: Mat, val intrinsics: CameraIntrinsics)

/**
 * Reads a photo like the corpus run does (app integration 8a, Foto lesen):
 * Imgcodecs.imread, which applies the EXIF orientation itself and returns
 * BGR. Never rotate here as well. Large photos are reduced in the decoder.
 */
object PhotoInput {

    fun read(photo: File): DecodedPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PhotoUnreadableException("${photo.path}: not a decodable image")
        }
        return read(photo, DecodeReduction.factorFor(max(bounds.outWidth, bounds.outHeight)))
    }

    /** With a given reduction; the app always lets [read] choose, the device test forces one. */
    fun read(photo: File, factor: Int): DecodedPhoto {
        val flag = when (factor) {
            1 -> Imgcodecs.IMREAD_COLOR
            2 -> Imgcodecs.IMREAD_REDUCED_COLOR_2
            4 -> Imgcodecs.IMREAD_REDUCED_COLOR_4
            8 -> Imgcodecs.IMREAD_REDUCED_COLOR_8
            else -> throw IllegalArgumentException("the decoder reduces by 1, 2, 4 or 8, not $factor")
        }
        val image = try {
            Imgcodecs.imread(photo.path, flag)
        } catch (e: CvException) {
            throw PhotoUnreadableException("${photo.path}: ${e.message}", e)
        }
        if (image.empty()) {
            image.release()
            throw PhotoUnreadableException("${photo.path}: imread returned no image")
        }
        return DecodedPhoto(image, ExifIntrinsics.of(image.cols(), image.rows(), focalLength35mm(photo)))
    }

    /** The only reason to read EXIF here; 0 when the tag or the file's EXIF is missing. */
    private fun focalLength35mm(photo: File): Int = try {
        ExifInterface(photo.path).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
    } catch (e: IOException) {
        0
    }
}
```

- [ ] **Step 3: `ModelLoading.kt`**

```kotlin
import android.app.ActivityManager
import android.content.Context
import de.dreier.mytargets.detection.ArrowDetector
import de.dreier.mytargets.detection.arrows.ArrowModel
import de.dreier.mytargets.detection.arrows.LearnedArrowDetector
import org.opencv.android.OpenCVLoader
import timber.log.Timber

class LoadedDetector(val detector: ArrowDetector, val modelName: String)

/**
 * Builds the one detector of the process: OpenCV's native library, the model
 * read from the asset into memory (never copied to disk), Winograd by the
 * device's memory. Throws on any failure; DetectorHolder turns that into
 * ModelUnavailable and retries next time.
 */
object ModelLoading {
    const val MODEL_NAME = "r4-all-2026-09"
    private const val MODEL_DIR = "arrows/$MODEL_NAME"

    fun load(context: Context): LoadedDetector {
        check(OpenCVLoader.initLocal()) { "OpenCV's native library did not load" }
        val assets = context.assets
        // open(), not openFd(): openFd fails on an asset stored compressed.
        val onnx = assets.open("$MODEL_DIR/${ArrowModel.ONNX_FILE}").use { it.readBytes() }
        val meta = assets.open("$MODEL_DIR/${ArrowModel.META_FILE}").use { it.readBytes().toString(Charsets.UTF_8) }
        val model = ArrowModel.fromBytes(onnx, meta, "assets/$MODEL_DIR")

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val winograd = WinogradPolicy.enabled(activityManager.isLowRamDevice, memory.totalMem)
        Timber.i("arrow model %s, winograd %s (total memory %d MB)", MODEL_NAME, winograd, memory.totalMem shr 20)

        return LoadedDetector(LearnedArrowDetector(model, winograd = winograd), MODEL_NAME)
    }
}
```

- [ ] **Step 4: `EndPhotoScanner.kt`**

```kotlin
import android.content.Context
import de.dreier.mytargets.detection.DetectionRequests
import de.dreier.mytargets.shared.models.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.CvException
import java.io.File

/**
 * The one entry point 8b calls (app integration 8a): photo, face and end size
 * in, a ScanOutcome out. Runs on Dispatchers.Default; detections run one at a
 * time on the one detector of the process.
 */
class EndPhotoScanner internal constructor(private val holder: DetectorHolder<LoadedDetector>) {

    suspend fun scan(photo: File, target: Target, shotsPerEnd: Int): ScanOutcome = withContext(Dispatchers.Default) {
        if (!ScanSupport.supports(target)) return@withContext ScanOutcome.Unsupported
        holder.withLoaded(onLoadFailure = { ScanOutcome.ModelUnavailable(it) }) { loaded ->
            scanWith(loaded, photo, shotsPerEnd)
        }
    }

    private fun scanWith(loaded: LoadedDetector, photo: File, shotsPerEnd: Int): ScanOutcome {
        val decoded = try {
            PhotoInput.read(photo)
        } catch (e: PhotoUnreadableException) {
            return ScanOutcome.PhotoUnreadable(e)
        }
        return try {
            val result = loaded.detector.detect(decoded.image, DetectionRequests.waFull(shotsPerEnd, decoded.intrinsics))
            ScanOutcome.Detected(result, loaded.modelName)
        } catch (e: CvException) {
            ScanOutcome.ScanFailed(e)
        } catch (e: IllegalStateException) {
            // LearnedArrowDetector wraps a failed forward pass in an IllegalStateException.
            ScanOutcome.ScanFailed(e)
        } catch (e: OutOfMemoryError) {
            ScanOutcome.ScanFailed(e)
        } finally {
            decoded.image.release()
        }
    }

    companion object {
        @Volatile
        private var instance: EndPhotoScanner? = null

        fun get(context: Context): EndPhotoScanner = instance ?: synchronized(this) {
            instance ?: EndPhotoScanner(DetectorHolder { ModelLoading.load(context.applicationContext) })
                .also { instance = it }
        }
    }
}
```

- [ ] **Step 5: Keep-Regeln**

`detection/consumer-rules.pro`:

```
# Gson fills the model sidecar by reflection (ArrowModel.parseMeta).
-keep class de.dreier.mytargets.detection.arrows.ArrowModel$MetaJson {
    <init>();
    <fields>;
}

# OpenCV's native code reaches its Java classes by name (exceptions, Mat
# fields, callbacks); whether the AAR brings its own rules is not checked.
-keep class org.opencv.** { *; }
```

In `detection/build.gradle` im `defaultConfig`: `consumerProguardFiles 'consumer-rules.pro'`.

- [ ] **Step 6: Bauen**

Run: `./gradlew :app:assembleDevDebug :app:testDevDebugUnitTest --tests "de.dreier.mytargets.features.detection.*" :detection:testDevDebugUnitTest --tests "*OpenCvImportRuleTest*"`
Expected: BUILD SUCCESSFUL, Tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/de/dreier/mytargets/features/detection detection/consumer-rules.pro detection/build.gradle
git commit -m "app: EndPhotoScanner, the scan of an end photo without UI"
```

---

### Task 6: PC-Referenz und Gerätetest

**Files:**
- Create: `detection/src/test/java/de/dreier/mytargets/detection/arrows/ScanReferenceRun.kt`
- Create: `app/src/androidTest/java/de/dreier/mytargets/features/detection/EndPhotoScannerDeviceTest.kt`
- Create: `app/src/androidTest/assets/scan/.gitignore`, `app/src/androidTest/README.md`

**Interfaces:**
- Consumes: `EndPhotoScanner.get`, `PhotoInput.read(photo, factor)`, `ScanOutcome`, `DetectionResult.face`; im PC-Test `ArrowCorpusRuns.requestFor`, `CorpusLoader`, `CorpusPhotos`.
- Produces: `ScanReferenceRun.VIEW = "2026-08-15_bedeckt_frontal_02"`, `ScanReferenceRun.REFERENCE: List<Pair<Double, Double>>` (dieselbe Liste steht im Gerätetest).

- [ ] **Step 1: PC-Referenz schreiben (zunächst ohne Pin)**

`ScanReferenceRun.kt` (GPL-Kopf; Aufbau wie `LearnedParityTest`: `OpenCvRule`, Korpus- und Modell-Property mit `assumeTrue`):

```kotlin
package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.hypot

/**
 * The reference the device test (app, EndPhotoScannerDeviceTest) is held
 * against: the learned finder on one corpus photo, decoded with imread like
 * the app, through ArrowCorpusRuns.requestFor like the corpus runs. The same
 * list stands in the device test; if this one moves, both move.
 */
class ScanReferenceRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var modelDir: File

    @Before
    fun requireCorpusAndModel() {
        val corpus = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", corpus != null)
        root = File(corpus!!)
        assumeTrue("corpus directory does not exist: $corpus", root.isDirectory)
        val model = System.getProperty("detection.model.dir")
        assumeTrue("no model directory", model != null)
        modelDir = File(model!!)
        assumeTrue("model directory does not exist: $model", modelDir.isDirectory)
    }

    @Test
    fun theLearnedFinderFindsTheReferenceShots() {
        val entries = CorpusLoader.load(root).entries
        val entry = entries.single { it.imageName == "$VIEW.jpg" }
        val files = CorpusPhotos.filesByName(root, entries)
        val file = CorpusPhotos.imageFileOf(root, entry.imageName, files[entry.imageName].orEmpty())
        val image = Imgcodecs.imread(file.absolutePath)
        val result = try {
            check(!image.empty()) { "$VIEW: cannot be decoded" }
            LearnedArrowDetector(ArrowModel.load(modelDir))
                .detect(image, ArrowCorpusRuns.requestFor(entry, image.cols(), image.rows()))
        } finally {
            image.release()
        }

        println("$VIEW: face ${result.face?.imageWidth} x ${result.face?.imageHeight}, shots " +
            result.shots.joinToString { "%.4f to %.4f".format(it.x, it.y) })
        assertThat(result.failure).isNull()
        assertThat(result.face!!.imageWidth).isEqualTo(2252)
        assertThat(result.face!!.imageHeight).isEqualTo(4000)
        assertThat(result.shots).hasSize(REFERENCE.size)
        for ((x, y) in REFERENCE) {
            val nearest = result.shots.minOf { hypot(it.x - x, it.y - y) }
            assertWithMessage("a shot near ($x, $y)").that(nearest).isAtMost(1e-3)
        }
    }

    companion object {
        const val VIEW = "2026-08-15_bedeckt_frontal_02"

        /** Pinned from the first run of this test (plan 8a, Task 6); spot-local x, y. */
        val REFERENCE: List<Pair<Double, Double>> = emptyList()
    }
}
```

`"%.4f to %.4f"` erzeugt kein Kotlin-Paar-Literal, nur eine lesbare Zeile; das Pinnen geschieht von Hand in Step 2.

- [ ] **Step 2: Laufen lassen und pinnen**

Run: `./gradlew :detection:testDevDebugUnitTest --tests "*ScanReferenceRun*" -i`
Expected: FAIL an `hasSize(0)` (die Liste ist leer), die Zeile `2026-08-15_bedeckt_frontal_02: face 2252 x 4000, shots …` steht im Log. Die Positionen auf vier Stellen als `REFERENCE = listOf(0.1234 to -0.5678, …)` eintragen, erneut laufen lassen.
Expected: PASS. Ist die Fläche nicht 2252 × 4000, stoppen: dann dreht `imread` am PC anders als die Spec annimmt, und das ist vor dem Gerätetest zu klären.

- [ ] **Step 3: Gerätetest schreiben**

`app/src/androidTest/assets/scan/.gitignore`:

```
*.jpg
```

`app/src/androidTest/java/de/dreier/mytargets/features/detection/EndPhotoScannerDeviceTest.kt` (GPL-Kopf):

```kotlin
package de.dreier.mytargets.features.detection

import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.targets.models.WAFull
import de.dreier.mytargets.shared.targets.models.WAVertical3Spot
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.math.hypot

/**
 * The scanner in the real APK on a real phone (app integration 8a): the model
 * from the app's assets, OpenCV from the AAR, imread with EXIF. The expected
 * shots are the PC reference of ScanReferenceRun in :detection; the photo is
 * a corpus photo that is not in git (README next to this directory).
 */
@RunWith(AndroidJUnit4::class)
class EndPhotoScannerDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scanner = EndPhotoScanner.get(context)
    private val waFull = Target(WAFull.ID, 0)

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

    @Test
    fun scansTheCorpusPhotoLikeThePc() = runBlocking {
        val photo = corpusPhoto()
        val started = SystemClock.elapsedRealtime()
        val outcome = scanner.scan(photo, waFull, 6)
        val millis = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "scan of $VIEW took $millis ms (first call includes loading), native heap ${Debug.getNativeHeapAllocatedSize() shr 20} MB")

        assertThat(outcome).isInstanceOf(ScanOutcome.Detected::class.java)
        val detected = outcome as ScanOutcome.Detected
        assertThat(detected.modelName).isEqualTo(ModelLoading.MODEL_NAME)
        val result = detected.result
        assertThat(result.failure).isNull()
        // Rotated by imread from EXIF 6: raw 4000 x 2252.
        assertThat(result.face!!.imageWidth).isEqualTo(2252)
        assertThat(result.face!!.imageHeight).isEqualTo(4000)
        assertThat(result.shots).hasSize(REFERENCE.size)
        for ((x, y) in REFERENCE) {
            val nearest = result.shots.minOf { hypot(it.x - x, it.y - y) }
            // OpenCV 4.14 on the phone against 4.9 on the PC: registration and network may differ in the last digits.
            assertWithMessage("a shot near the PC's ($x, $y)").that(nearest).isAtMost(0.01)
        }

        val again = SystemClock.elapsedRealtime()
        scanner.scan(photo, waFull, 6)
        Log.i(TAG, "second scan took ${SystemClock.elapsedRealtime() - again} ms, native heap ${Debug.getNativeHeapAllocatedSize() shr 20} MB")
    }

    @Test
    fun aReducedDecodeKeepsTheExifRotation() {
        val decoded = PhotoInput.read(corpusPhoto(), 2)
        try {
            // libjpeg scales by 1/2 rounding up; 2252 and 4000 are even.
            assertThat(decoded.image.cols()).isEqualTo(1126)
            assertThat(decoded.image.rows()).isEqualTo(2000)
        } finally {
            decoded.image.release()
        }
    }

    @Test
    fun aPhotoWithoutAFaceIsDetectedAsNotFound() = runBlocking {
        val grey = File(context.cacheDir, "grey.jpg")
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        grey.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()

        val outcome = scanner.scan(grey, waFull, 6)

        assertThat(outcome).isInstanceOf(ScanOutcome.Detected::class.java)
        assertThat((outcome as ScanOutcome.Detected).result.failure).isEqualTo(DetectionFailure.FACE_NOT_FOUND)
        assertThat(outcome.result.face).isNull()
    }

    @Test
    fun aFileThatIsNoImageIsUnreadable() = runBlocking {
        val text = File(context.cacheDir, "not-an-image.jpg").apply { writeText("not an image") }

        assertThat(scanner.scan(text, waFull, 6)).isInstanceOf(ScanOutcome.PhotoUnreadable::class.java)
    }

    @Test
    fun anotherFaceIsUnsupported() = runBlocking {
        val threeSpot = Target(WAVertical3Spot.ID, 0)

        assertThat(scanner.scan(File("unused.jpg"), threeSpot, 3)).isSameInstanceAs(ScanOutcome.Unsupported)
    }

    private companion object {
        const val TAG = "EndPhotoScanner"
        const val VIEW = "2026-08-15_bedeckt_frontal_02"

        /** The same list as ScanReferenceRun.REFERENCE in :detection. */
        val REFERENCE: List<Pair<Double, Double>> = emptyList()
    }
}
```

`REFERENCE` mit denselben Werten füllen wie in Step 2.

`app/src/androidTest/README.md`:

````markdown
# Instrumented tests of `:app`

## `EndPhotoScannerDeviceTest` (app integration 8a)

Runs the arrow scan in the real APK: the model from the app's assets, OpenCV
from the AAR, `imread` with EXIF. It needs one corpus photo that is not in git
(`assets/scan/.gitignore`):

```
cp ../MyTargets-corpus/wa-full/2026-08-15_bedeckt_frontal_02.jpg app/src/androidTest/assets/scan/
```

Without it the scan tests skip themselves; the grey, non-image and
unsupported cases still run. The expected shots are pinned on the PC by
`ScanReferenceRun` in `:detection`; if the model or the pipeline changes,
re-pin there and copy the list here.

Run with the phone attached (USB debugging on, rear USB port):

```
JAVA_HOME=<jdk17> ./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.detection.EndPhotoScannerDeviceTest
adb logcat -d -s EndPhotoScanner
```
````

- [ ] **Step 4: Auf dem Telefon laufen lassen**

```bash
cp ../MyTargets-corpus/wa-full/2026-08-15_bedeckt_frontal_02.jpg app/src/androidTest/assets/scan/
D:/AndroidSDK/platform-tools/adb.exe devices
./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.detection.EndPhotoScannerDeviceTest
D:/AndroidSDK/platform-tools/adb.exe logcat -d -s EndPhotoScanner
```

Expected: 5 Tests PASS; im Log zwei Zeilen mit Zeit und nativem Heap. Zahlen notieren (Task 7). Scheitert `scansTheCorpusPhotoLikeThePc` an der Größe (4000 × 2252), wendet `imread` im AAR die EXIF-Drehung nicht an: stoppen und melden, nicht in `PhotoInput` drehen, ohne die Spec anzupassen. Liegen die Treffer weiter als 0,01 von der Referenz, die Abweichungen notieren und melden, nicht die Schranke erhöhen.

Ist kein Telefon angeschlossen, stoppen und den Nutzer bitten, es anzuschließen; der Emulator (x86_64) ist kein Ersatz für die Speicher- und Zeitmessung.

- [ ] **Step 5: Commit**

```bash
git add detection/src/test/java/de/dreier/mytargets/detection/arrows/ScanReferenceRun.kt app/src/androidTest
git status --short   # das JPEG darf nicht auftauchen
git commit -m "test: the scanner on the phone against a PC reference"
```

---

### Task 7: Release-Build, Messwerte, Nachtrag

**Files:**
- Modify: `docs/design/2026-09-23-app-integration-foundation-design.md` (Nachtrag, Status)
- Modify: `docs/design/2026-09-09-arrow-detection-design.md` (Schritt 8: Verweis auf 8a)

- [ ] **Step 1: Release bauen**

Run: `./gradlew :app:assembleDevRelease :app:bundleDevRelease`
Expected: BUILD SUCCESSFUL. Scheitert R8 an fehlenden Klassen aus OpenCV (`Missing class …`), die Meldung lesen: fehlende optionale Klassen mit `-dontwarn` für genau diese Pakete in `detection/consumer-rules.pro` ergänzen und begründen, nichts pauschal.

- [ ] **Step 2: Keep-Regel prüfen**

```bash
grep -n "ArrowModel\$MetaJson" app/build/outputs/mapping/devRelease/mapping.txt
grep -n "ArrowModel\$MetaJson" app/build/outputs/mapping/devRelease/usage.txt
```

Expected: In `mapping.txt` steht `de.dreier.mytargets.detection.arrows.ArrowModel$MetaJson -> de.dreier.mytargets.detection.arrows.ArrowModel$MetaJson:` (Name erhalten), darunter die Felder `inputSize`, `stride`, `kernel`, `preShrinkMaxSide`, `threshold` jeweils `-> ` gleicher Name; in `usage.txt` (entfernter Code) steht die Klasse nicht. Ohne Treffer in `mapping.txt` wurde die Klasse entfernt: Regel prüfen.

- [ ] **Step 3: Größen messen**

```bash
ls -l app/build/outputs/apk/dev/release/*.apk app/build/outputs/bundle/devRelease/*.aab
```

Falls `bundletool` vorhanden ist, die Downloadgröße für arm64 (`bundletool get-size total --apks=… --dimensions=ABI`); sonst die APK-Größe und die AAB-Größe notieren und sagen, dass die Downloadgröße geschätzt bleibt.

- [ ] **Step 4: Nachtrag in die Spec**

Am Ende von `docs/design/2026-09-23-app-integration-foundation-design.md`:

```markdown
## Nachtrag <Datum>: umgesetzt und gemessen

Branch `plan/app-integration-foundation`. <Was vom Design abweicht, sonst „Nichts“.>

| Messung | Wert |
|---|---|
| Erster Scan auf dem S25 (mit Laden) | <ms> |
| Zweiter Scan | <ms> |
| Nativer Heap nach dem Scan | <MB> |
| Winograd auf dem S25 | an (totalMem <GB>) |
| Release-APK (zwei ABIs) | <MB> |
| AAB | <MB> |
| Referenz `2026-08-15_bedeckt_frontal_02` | <n> Treffer, größte Abweichung Telefon gegen PC <Wert> |
```

Status-Zeile oben auf „Design umgesetzt am <Datum>, siehe Nachtrag“ ändern. Im Haupt-Design unter *Reihenfolge der Umsetzung*, Punkt 8, einen Satz anhängen: „Aufgeteilt in 8a bis 8d; 8a (Fundament) siehe `2026-09-23-app-integration-foundation-design.md`.“

- [ ] **Step 5: Ganze Suiten**

Run: `./gradlew :detection:testDevDebugUnitTest :detection-corpus:test :app:testDevDebugUnitTest`
Expected: PASS. Rote Tests in `:app`, die schon auf `master` rot sind, vorher mit `git stash`/`master` gegenprüfen und im Nachtrag nennen.

- [ ] **Step 6: Commit**

```bash
git add docs/design
git commit -m "docs: 8a addendum with the phone and release numbers"
```
