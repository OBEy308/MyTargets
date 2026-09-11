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

/** The colour classes of a WA face, and everything else. */
enum class ColourClass { YELLOW, RED, BLUE, BLACK, WHITE, OTHER }

/** One colour class per pixel, row by row. Pixel (x, y) has its centre at (x, y). */
class ClassImage(val width: Int, val height: Int, private val codes: ByteArray) {

    init {
        require(codes.size == width * height) {
            "expected ${width * height} classes, got ${codes.size}"
        }
    }

    operator fun get(x: Int, y: Int): ColourClass = CLASSES[codes[y * width + x].toInt()]

    fun isInside(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun count(c: ColourClass): Int = codes.count { it.toInt() == c.ordinal }

    /** 1 where the class is [c], 0 elsewhere, row by row, for OpenCV filters. */
    fun mask(c: ColourClass): FloatArray =
        FloatArray(codes.size) { if (codes[it].toInt() == c.ordinal) 1f else 0f }

    companion object {
        private val CLASSES = ColourClass.entries.toTypedArray()

        fun of(width: Int, height: Int, classAt: (x: Int, y: Int) -> ColourClass) =
            ClassImage(
                width, height,
                ByteArray(width * height) { classAt(it % width, it / width).ordinal.toByte() }
            )
    }
}
