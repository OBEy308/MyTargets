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
