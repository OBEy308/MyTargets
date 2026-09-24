# Instrumented tests of `:app`

## `EndPhotoScannerDeviceTest` (app integration 8a)

Runs the arrow scan in the real APK: the model from the app's assets, OpenCV
from the AAR, `imread` with EXIF. It needs one corpus photo that is not in git
(`assets/scan/.gitignore`):

```
cp ../MyTargets-corpus/wa-full/2026-08-15_bedeckt_frontal_02.jpg app/src/androidTest/assets/scan/
```

Without it the scan tests skip themselves; the grey, non-image and
unsupported cases still run. The orchestrator runs every test method in its
own process (`clearPackageData`), so each one loads the model again; the warm
second scan is measured inside `scansTheCorpusPhotoLikeThePc` only. The
expected shots are pinned on the PC by `ScanReferenceRun` in `:detection`; if
the model or the pipeline changes, re-pin there and copy the list here.

Run with the phone attached (USB debugging on, rear USB port):

```
JAVA_HOME=<jdk17> ./gradlew :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.dreier.mytargets.features.detection.EndPhotoScannerDeviceTest
adb logcat -d -s EndPhotoScanner
```
