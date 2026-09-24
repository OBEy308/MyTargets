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
