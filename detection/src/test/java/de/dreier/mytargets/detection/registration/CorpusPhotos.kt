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

package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.Homography
import de.dreier.mytargets.detection.metrics.RegistrationError
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the corpus runs share: the file of a photograph, its decoded size, and stage image 4. */
object CorpusPhotos {

    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val MAGENTA = Scalar(255.0, 0.0, 255.0)

    /** Every file under [root] that one of [entries] names, by name. */
    fun filesByName(root: File, entries: List<CorpusEntry>): Map<String, List<File>> {
        val names = entries.mapTo(HashSet()) { it.imageName }
        return root.walkTopDown().filter { it.isFile && it.name in names }.groupBy { it.name }
    }

    /**
     * The one file under the root that an entry names. Matched by name alone,
     * a second file of that name in another folder would be picked silently.
     */
    fun imageFileOf(root: File, imageName: String, candidates: List<File>): File {
        check(candidates.isNotEmpty()) { "$imageName: no image file under $root" }
        check(candidates.size == 1) {
            "$imageName: ${candidates.size} image files under $root: " +
                candidates.map { it.path }.sorted().joinToString()
        }
        return candidates.single()
    }

    /** A decoder that ignores EXIF shows up here rather than as a wild registration error. */
    fun checkDecodedSize(entry: CorpusEntry, image: Mat) {
        val info = entry.image ?: return
        check(info.matchesDecodedSize(image.cols(), image.rows())) {
            "${entry.imageName}: decoded as ${image.cols()} x ${image.rows()}, the sidecar " +
                "records ${info.width} x ${info.height} at orientation ${info.exifOrientation}"
        }
    }

    /**
     * Stage image 4: the rectified face with the nominal rings in green and, in
     * magenta, the rings where the reference puts them. Where the two lie on
     * each other, the registration agrees with the reference; the gap between
     * them is the error.
     */
    fun writeRectified(image: Mat, imageToTarget: Mat3, reference: List<Double>, imageName: String, file: File) {
        val edge = FaceWarp.edgeFor(imageToTarget)
        val warped = FaceWarp.warp(image, imageToTarget, edge)
        try {
            val k = edge / (2.0 * FaceWarp.EXTENT)
            val centre = FaceWarp.pixelOf(Vec2(0.0, 0.0), edge)
            val toImage = checkNotNull(Homography.inverse(reference.toDoubleArray())) {
                "the reference of $imageName is singular"
            }
            for (r in RegistrationError.RING_RADII) {
                Imgproc.circle(warped, Point(centre.x, centre.y), (r * k).roundToInt(), GREEN, 2)
                val outline = (0 until 360).mapNotNull { step ->
                    val a = 2.0 * PI * step / 360
                    val q = Homography.map(toImage, r * cos(a), r * sin(a)) ?: return@mapNotNull null
                    val p = imageToTarget.mapPoint(Vec2(q[0], q[1])) ?: return@mapNotNull null
                    val px = FaceWarp.pixelOf(p, edge)
                    Point(px.x, px.y)
                }
                if (outline.size < 2) continue
                val polyline = MatOfPoint(*outline.toTypedArray())
                try {
                    Imgproc.polylines(warped, listOf(polyline), true, MAGENTA, 1)
                } finally {
                    polyline.release()
                }
            }
            check(Imgcodecs.imwrite(file.absolutePath, warped)) { "cannot write $file" }
        } finally {
            warped.release()
        }
    }

    /** The nine values of [m], row by row, as RegistrationError takes them. */
    fun values(m: Mat3): List<Double> = List(9) { m[it / 3, it % 3] }
}
