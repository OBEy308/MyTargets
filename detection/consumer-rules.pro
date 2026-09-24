# Gson fills the model sidecar by reflection (ArrowModel.parseMeta).
-keep class de.dreier.mytargets.detection.arrows.ArrowModel$MetaJson {
    <init>();
    <fields>;
}

# OpenCV's native code reaches its Java classes by name (exceptions, Mat
# fields, callbacks); the AAR 4.14.0 brings no keep rules of its own.
-keep class org.opencv.** { *; }
