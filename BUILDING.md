# Building

A fresh clone does not build as-is. Three files are deliberately kept out of
version control (see `.gitignore`) and have to be supplied locally. A fourth
item, the photo corpus for the arrow detection, is optional.

## Requirements

- Android Studio, or a JDK plus the Android command line tools
- A JDK 17, since the modules declare `sourceCompatibility`/`targetCompatibility`
  17 and Gradle fails outright without one
- An Android SDK matching `compileSdk` in `gradle/libs.versions.toml`

If you build from the command line, point `JAVA_HOME` at a JDK 17. The one
bundled with Android Studio works if it is version 17:

```
# Windows
set JAVA_HOME=<Android Studio>\jbr

# macOS / Linux
export JAVA_HOME=<Android Studio>/jbr
```

## 1. `local.properties`

Tells Gradle where the SDK is. Android Studio writes this file on first open;
for command line builds create it yourself:

```properties
sdk.dir=/path/to/Android/Sdk
```

On Windows use forward slashes (`sdk.dir=D:/AndroidSDK`) or escaped backslashes.

## 2. `gradle-local.properties`

Holds the signing configuration. Copy the template and edit it:

```
cp gradle-local.properties.example gradle-local.properties
```

The template points at `../debug.keystore` and `../keystore.jks`, neither of
which is in the repository. For debug builds, point `DEBUG_KEYSTORE_*` at the
standard Android debug keystore instead:

```properties
DEBUG_KEYSTORE_NAME=/path/to/home/.android/debug.keystore
DEBUG_KEYSTORE_PASSWORD=android
DEBUG_KEY_ALIAS=androiddebugkey
DEBUG_KEY_PASSWORD=android
```

If that keystore does not exist yet, create it:

```
keytool -genkeypair -v \
  -keystore ~/.android/debug.keystore \
  -storepass android -keypass android \
  -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

`KEYSTORE_*` is only needed for release builds. Point it at the debug keystore
too if you only build debug variants — the values must resolve, but they are not
used.

## 3. `app/google-services.json`

The Google Services plugin is applied through the `plugins` block in
`app/build.gradle` and refuses to run without this file. (The commented-out
`apply plugin` further down in the same file is a leftover and misleading.)

If you have a Firebase project, download its `google-services.json` into `app/`.
Otherwise a placeholder is enough to build. It needs one `client` entry per
application id — `de.dreier.mytargets` and, for debug builds,
`de.dreier.mytargets.debug`. Firebase and Crashlytics will not work with a
placeholder, which does not matter for development.

## 4. Photo corpus (optional)

The arrow detection (`docs/design/2026-09-09-arrow-detection-design.md`) is
measured against real photos of target faces. They are not in the repository:
they are large, they never change, and they are not covered by the project's
licence. Keep them in a folder next to the checkout and point to it from
`gradle-local.properties`:

```properties
DETECTION_CORPUS_DIR=../MyTargets-corpus
```

Without the entry the corpus tests are skipped and everything else builds as
usual. The folder layout, the naming scheme and the ground truth format are
described in the corpus's own `README.md`.

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

## Build

```
./gradlew :app:assembleDevDebug
```

The APK lands in `app/build/outputs/apk/dev/debug/`.

There are three product flavors — `dev`, `regular` and `screengrab`. Plain
`assembleDebug` builds all three; name the flavor to build just one.
