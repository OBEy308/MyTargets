# Instrumented tests of `:detection`

## `LearnedFinderTimingTest`

Measures on a real phone what the learned arrow finder of the PoC would cost:
seconds per forward pass and memory at 768 and 512 px, through the OpenCV
`dnn` module of the AAR the app ships. Background and PC numbers:
`docs/design/2026-09-17-learned-finder-app-path.md`.

The assets are not in git (`assets/.gitignore`), they are 29 MB each. Produce
them from the corpus repo and copy them here:

```
cd ../MyTargets-corpus/learn
.venv/Scripts/python export_onnx.py ../../MyTargets-learn/runs/r4 --fold 3 --res 768 --fp16
.venv/Scripts/python export_onnx.py ../../MyTargets-learn/runs/r4 --fold 3 --res 512 --fp16
cp ../../MyTargets-learn/runs/r4/model_fold3_768_fp16.onnx ../../MyTargets/detection/src/androidTest/assets/
cp ../../MyTargets-learn/runs/r4/model_fold3_512_fp16.onnx ../../MyTargets/detection/src/androidTest/assets/
cp ../../MyTargets-learn/data/2026-09-15_bedeckt_stark-schraeg_07.png ../../MyTargets/detection/src/androidTest/assets/face_768.png
```

Since design 3d, `export_onnx.py` exports the wrapper with ImageNet
normalisation and sigmoid inside the graph. Assets produced with it are
normalised a second time by this test's own `blobFromImage`; the timing is
unaffected, the printed maximum is then a probability rather than a logit.
`export_onnx.py` now needs `--fold N` or `--all` explicitly.

Run with the phone attached (USB debugging on):

```
JAVA_HOME=<jdk17> ./gradlew :detection:connectedDevDebugAndroidTest --tests "*LearnedFinderTimingTest*"
adb logcat -d -s LearnedFinderTiming
```

The same lines land in the app's external files directory as
`learned-finder-timing.txt`.
