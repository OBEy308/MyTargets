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

import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Median saturation and normalised value of the yellow disc and of the red ring around it. */
class DiscReference(
    val yellowSaturation: Double,
    val yellowValue: Double,
    val redSaturation: Double,
    val redValue: Double
)

/**
 * register.py's classify. Yellow and red are hue bands with a saturation
 * floor. Blue, black and white share a hue in a blue-cast photograph and are
 * split by a small k-means in saturation and value, the value normalised to
 * the image's brightness so an evening shot separates like a bright one. The
 * first pass uses generous floors; the second takes them from the disc itself,
 * so a pale print keeps its yellow and a warm white ring does not become one.
 */
object ColourClassifier {

    private const val MAX_SAMPLES = 60_000

    /** Starting centres in (saturation, normalised value): blue, black, white. */
    private val START = arrayOf(
        doubleArrayOf(0.9, 0.65),
        doubleArrayOf(0.4, 0.12),
        doubleArrayOf(0.2, 0.9)
    )
    private val SPLIT = arrayOf(ColourClass.BLUE, ColourClass.BLACK, ColourClass.WHITE)

    fun classify(hsv: HsvPixels, reference: DiscReference? = null): ClassImage {
        val valueScale = valueScale(hsv)
        val yellowSaturation = reference?.let { 0.5 * it.yellowSaturation } ?: 0.15
        val redSaturation = reference?.let { 0.5 * it.redSaturation } ?: 0.35
        val yellowValue = reference?.let { 0.5 * it.yellowValue } ?: 0.3
        val redValue = reference?.let { 0.4 * it.redValue } ?: 0.2

        val codes = ByteArray(hsv.size) { ColourClass.OTHER.ordinal.toByte() }
        val rest = IntArray(hsv.size)
        var restCount = 0
        for (i in 0 until hsv.size) {
            val h = hsv.hue(i)
            val s = hsv.saturation(i)
            val v = min(hsv.value(i) / valueScale, 1.2)
            val isYellow = h in 30.0..95.0 && s > yellowSaturation && v > yellowValue
            val isRed = !isYellow && (h >= 285.0 || h <= 30.0) && s > redSaturation && v > redValue
            when {
                isYellow -> codes[i] = ColourClass.YELLOW.ordinal.toByte()
                isRed -> codes[i] = ColourClass.RED.ordinal.toByte()
                h in 175.0..250.0 || s < 0.3 -> rest[restCount++] = i
            }
        }
        if (restCount == 0) return ClassImage(hsv.width, hsv.height, codes)

        // Every stride-th pixel instead of register.py's seeded random choice:
        // the same spread, and no random state.
        val stride = max(1, ceil(restCount / MAX_SAMPLES.toDouble()).toInt())
        val samples = (restCount + stride - 1) / stride
        val xs = DoubleArray(samples) { hsv.saturation(rest[it * stride]) }
        val ys = DoubleArray(samples) { min(hsv.value(rest[it * stride]) / valueScale, 1.2) }
        val centres = SmallKMeans.run(xs, ys, START)

        for (n in 0 until restCount) {
            val i = rest[n]
            val s = hsv.saturation(i)
            val v = min(hsv.value(i) / valueScale, 1.2)
            val k = SmallKMeans.nearest(s, v, centres)
            // Blue needs real saturation and brightness: a black or white
            // centroid that drifted would otherwise take the name.
            val drifted = k == 0 && !(s > 0.4 * centres[0][0] && v > 0.5 * centres[0][1])
            codes[i] = (if (drifted) ColourClass.OTHER else SPLIT[k]).ordinal.toByte()
        }
        return ClassImage(hsv.width, hsv.height, codes)
    }

    /**
     * Median saturation and normalised value of the yellow pixels within 0.8
     * [radius] of [centre] and of the red pixels between 1.3 and 1.8 [radius].
     * Null when either has fewer than 20 pixels.
     */
    fun discReference(
        hsv: HsvPixels,
        classes: ClassImage,
        centre: Vec2,
        radius: Double
    ): DiscReference? {
        val valueScale = valueScale(hsv)
        val yellowS = ArrayList<Double>()
        val yellowV = ArrayList<Double>()
        val redS = ArrayList<Double>()
        val redV = ArrayList<Double>()

        val reach = 1.8 * radius
        val x0 = max(0, floor(centre.x - reach).toInt())
        val x1 = min(hsv.width - 1, ceil(centre.x + reach).toInt())
        val y0 = max(0, floor(centre.y - reach).toInt())
        val y1 = min(hsv.height - 1, ceil(centre.y + reach).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val d = hypot(x - centre.x, y - centre.y)
                val i = y * hsv.width + x
                val c = classes[x, y]
                if (c == ColourClass.YELLOW && d < 0.8 * radius) {
                    yellowS += hsv.saturation(i)
                    yellowV += min(hsv.value(i) / valueScale, 1.2)
                } else if (c == ColourClass.RED && d > 1.3 * radius && d < 1.8 * radius) {
                    redS += hsv.saturation(i)
                    redV += min(hsv.value(i) / valueScale, 1.2)
                }
            }
        }
        if (yellowS.size < 20 || redS.size < 20) return null
        return DiscReference(
            RobustConic.median(yellowS), RobustConic.median(yellowV),
            RobustConic.median(redS), RobustConic.median(redV)
        )
    }

    /** Brightness normaliser: the 98th percentile of value, at least 0.2. */
    private fun valueScale(hsv: HsvPixels) = max(hsv.valuePercentile(0.98), 0.2)
}
