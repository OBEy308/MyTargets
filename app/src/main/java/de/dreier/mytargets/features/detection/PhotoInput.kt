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
