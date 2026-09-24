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

import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.targets.models.WAFull
import de.dreier.mytargets.shared.targets.models.WAVertical3Spot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.IOException
import kotlin.math.hypot

/**
 * The scanner in the real APK on a real phone (app integration 8a): the model
 * from the app's assets, OpenCV from the AAR, imread with EXIF. The expected
 * shots are the PC reference of ScanReferenceRun in :detection; the photo is
 * a corpus photo that is not in git (README next to this directory).
 *
 * Uses org.junit.Assert, not Truth: :app pins Guava to 27.0.1-android
 * (app/build.gradle), but Truth 1.4.5 needs Guava 31.1+ for Subject's static
 * initializer, so any Truth.assertThat crashes with a NoSuchMethodError on a
 * real device (confirmed on-device, plan 8a Task 6).
 */
@RunWith(AndroidJUnit4::class)
class EndPhotoScannerDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scanner = EndPhotoScanner.get(context)
    private val waFull = Target(WAFull.ID, 0)

    @Before
    // The orchestrator gives every method its own process, and aReducedDecodeKeepsTheExifRotation
    // reads without a scan loading OpenCV first.
    fun loadOpenCv() {
        assertTrue("OpenCV's native library did not load", OpenCVLoader.initLocal())
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

    @Test
    // Explicit Unit return type: the block's last statement is Log.i, which returns Int, and
    // without this JUnit4's instrumentation runner rejects the whole class at "should be void"
    // (confirmed on-device, plan 8a Task 6) before any test method runs.
    fun scansTheCorpusPhotoLikeThePc(): Unit = runBlocking {
        val photo = corpusPhoto()
        val started = SystemClock.elapsedRealtime()
        val outcome = scanner.scan(photo, waFull, 6)
        val millis = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "scan of $VIEW took $millis ms (first call includes loading), native heap ${Debug.getNativeHeapAllocatedSize() shr 20} MB")

        assertTrue("outcome is Detected: $outcome", outcome is ScanOutcome.Detected)
        val detected = outcome as ScanOutcome.Detected
        assertEquals(ModelLoading.MODEL_NAME, detected.modelName)
        val result = detected.result
        assertNull(result.failure)
        // Rotated by imread from EXIF 6: raw 4000 x 2252.
        assertEquals(2252, result.face!!.imageWidth)
        assertEquals(4000, result.face!!.imageHeight)
        assertEquals(REFERENCE.size, result.shots.size)
        val deviations = REFERENCE.map { (x, y) -> result.shots.minOf { hypot(it.x - x, it.y - y) } }
        Log.i(TAG, "largest deviation from the PC reference: %.5f".format(deviations.max()))
        for (i in REFERENCE.indices) {
            val (x, y) = REFERENCE[i]
            // OpenCV 4.14 on the phone against 4.9 on the PC: registration and network may differ in the last digits.
            assertTrue("a shot near the PC's ($x, $y): ${deviations[i]}", deviations[i] <= 0.01)
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
            assertEquals(1126, decoded.image.cols())
            assertEquals(2000, decoded.image.rows())
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

        assertTrue("outcome is Detected: $outcome", outcome is ScanOutcome.Detected)
        assertEquals(DetectionFailure.FACE_NOT_FOUND, (outcome as ScanOutcome.Detected).result.failure)
        assertNull(outcome.result.face)
    }

    @Test
    fun aFileThatIsNoImageIsUnreadable() = runBlocking {
        val text = File(context.cacheDir, "not-an-image.jpg").apply { writeText("not an image") }

        assertTrue(
            "scan of a non-image is PhotoUnreadable",
            scanner.scan(text, waFull, 6) is ScanOutcome.PhotoUnreadable
        )
    }

    @Test
    fun anotherFaceIsUnsupported() = runBlocking {
        val threeSpot = Target(WAVertical3Spot.ID, 0)

        assertSame(ScanOutcome.Unsupported, scanner.scan(File("unused.jpg"), threeSpot, 3))
    }

    private companion object {
        const val TAG = "EndPhotoScanner"
        const val VIEW = "2026-08-15_bedeckt_frontal_02"

        /** The same list as ScanReferenceRun.REFERENCE in :detection. */
        val REFERENCE: List<Pair<Double, Double>> = listOf(
            -0.0383 to -0.0848,
            -0.0046 to 0.0931,
            -0.0340 to 0.0122,
            -0.0742 to -0.0321,
            -0.0860 to 0.0411
        )
    }
}
