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

package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.FootPoint
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.max

/**
 * The foot point from the reference homography against register.py's pose
 * decomposition (arrow design, Tests, Korpusgeometrie). register.py normalises
 * the two columns of the pose separately, so where the EXIF focal length does
 * not quite fit the homography its pose comes out skew, and the two ways
 * drift apart in proportion to how far the foot point lies from the centre:
 * up to 0.03 radii on the near-frontal views, 7.3 % of the distance on the
 * steep ones of the 2026-09-14 series (0.22 radii at 54 degrees). Fitting a
 * focal length per view does not explain it -- the fitted factor scatters
 * from 0.77 to 1.10 -- so the homographies themselves are not quite rigid
 * there. The bound is the search's window of 0.04 where the reference holds
 * and 10 % of the foot point's distance beyond it; the app's value is the
 * exact one for a pinhole, checked against the formula in Python.
 */
class FootPointCorpusTest {

    @Test
    fun theFootPointAgreesWithRegisterPyWithinTheSearchWindow() {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        val root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)

        var checked = 0
        for (entry in CorpusLoader.load(root).entries) {
            if (entry.outOfScope != null) continue
            val registration = entry.registration ?: continue
            val camera = registration.cameraPositionFaceUnits ?: continue
            val focal = checkNotNull(entry.camera?.focalLength35mm) {
                "${entry.imageName}: a view block without a focal length"
            }
            val image = checkNotNull(entry.image) { "${entry.imageName}: no image block" }
            val turned = (image.exifOrientation ?: 1) in 5..8
            val width = if (turned) image.height else image.width
            val height = if (turned) image.width else image.height
            val intrinsics = CameraIntrinsics.from35mmEquivalent(width, height, focal)

            val q = checkNotNull(
                FootPoint.of(Mat3.of(*registration.imageToTarget.toDoubleArray()), intrinsics)
            ) { "${entry.imageName}: no foot point" }

            val reference = Vec2(camera[0], camera[1])
            assertWithMessage("${entry.imageName}: foot point against register.py")
                .that(q.distanceTo(reference)).isAtMost(max(SEARCH_WINDOW, RELATIVE_BOUND * reference.length))
            checked++
        }
        // 105 photographs in scope carry a view block at the committed corpus state of 2026-09-17 (a8a5149).
        assertThat(checked).isEqualTo(105)
    }

    private companion object {
        /** The shaft search's lateral window (arrow design, Verfahren, step 2). */
        const val SEARCH_WINDOW = 0.04
        /** Measured 7.3 % at most on the steep views; a rounding away from red. */
        const val RELATIVE_BOUND = 0.10
    }
}
