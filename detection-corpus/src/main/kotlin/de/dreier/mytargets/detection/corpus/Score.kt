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

package de.dreier.mytargets.detection.corpus

/**
 * A score as it is printed on the target face: "X", "10", "9" down to "1", and
 * "M" for a miss.
 *
 * Deliberately not the zone index that `Shot.scoringRing` stores. That index
 * means different things on different faces -- on a full face index 0 is the X
 * ring and index 2 the nine, while a six ring face starts its zone list at the
 * five. The corpus holds both kinds, so an index would force this module to
 * know the target models, which live in an Android library. The printed value
 * is unambiguous without them.
 */
@JvmInline
value class Score(val text: String) {

    val isMiss: Boolean
        get() = text == MISS_TEXT

    override fun toString(): String = text

    companion object {
        private const val MISS_TEXT = "M"

        val MISS = Score(MISS_TEXT)
        val X = Score("X")

        fun of(text: String): Score {
            require(text.isNotBlank()) { "a score needs a value" }
            return Score(text)
        }

        /**
         * One character of the inherited file name scheme, where a name like
         * `a6_x99765_noise.jpg` spells six scores as `x`, `9`, `9`, `7`, `6`, `5`.
         *
         * Returns null for anything the scheme does not define. Note that the
         * scheme has no character for a plain ten as distinct from an X, and
         * none for a zero -- a miss is written `m`.
         */
        fun parseFilenameChar(c: Char): Score? = when {
            c == 'x' || c == 'X' -> X
            c == 'm' || c == 'M' -> MISS
            c in '1'..'9' -> Score(c.toString())
            else -> null
        }
    }
}
