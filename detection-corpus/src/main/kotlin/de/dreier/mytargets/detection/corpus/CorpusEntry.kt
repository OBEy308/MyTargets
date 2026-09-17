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

import kotlin.math.abs
import kotlin.math.hypot

/**
 * A score as it is printed on the face: "X", "10" down to "1", "M" for a miss.
 *
 * Only the inherited photographs use this. Their file names spell printed
 * values and record no target model, so their scores cannot be turned into zone
 * indices. Everything annotated properly carries [TruthShot.scoringRing]
 * instead, which is what a detector produces directly.
 */
@JvmInline
value class PrintedScore(val text: String) {

    val isMiss: Boolean
        get() = text == MISS_TEXT

    override fun toString(): String = text

    companion object {
        private const val MISS_TEXT = "M"

        val MISS = PrintedScore(MISS_TEXT)
        val X = PrintedScore("X")

        /** Normalises case and whitespace, so a hand written "x" equals "X". */
        fun of(text: String): PrintedScore {
            val normalised = text.trim().uppercase()
            require(normalised.isNotEmpty()) { "a score needs a value" }
            return PrintedScore(normalised)
        }

        /**
         * One character of the inherited naming scheme, where `a6_x99765.jpg`
         * spells six scores. The scheme has no character for a plain ten as
         * distinct from an X, and none for a zero; a miss is written `m`.
         */
        fun parseFilenameChar(c: Char): PrintedScore? = when {
            c == 'x' || c == 'X' -> X
            c == 'm' || c == 'M' -> MISS
            c in '1'..'9' -> PrintedScore(c.toString())
            else -> null
        }
    }
}

/**
 * A hit in spot local coordinates: the spot's centre is the origin, its
 * outermost ring has radius one, and y grows downwards — the same system
 * `Shot.x` and `Shot.y` use in the app.
 */
data class SpotPosition(val faceIndex: Int, val x: Double, val y: Double) {

    /** Null across different spots, where a distance would be meaningless. */
    fun distanceTo(other: SpotPosition): Double? =
        if (faceIndex != other.faceIndex) null else hypot(x - other.x, y - other.y)
}

/**
 * One arrow of the ground truth.
 *
 * [scoringRing] is the zone index from `TargetModelBase.zones`, which is what a
 * detector produces from `getZoneFromPoint`. [printedScore] is the fallback for
 * the inherited photographs, whose file names give printed values and no target
 * model. A shot needs at least one of the two.
 *
 * [positionTolerance] is how precisely the ANNOTATOR could place this hit, in
 * spot radii -- 0.01 for a freely visible tip, larger where shafts overlap and
 * the entry point had to be estimated. It is annotation uncertainty, not a
 * detector's matching budget: when matching a detection against this hit, it
 * is added to the detector's own budget rather than replacing it -- see
 * `ShotMatching.DEFAULT_POSITION_TOLERANCE`.
 */
data class TruthShot(
    val scoringRing: Int? = null,
    val printedScore: PrintedScore? = null,
    val position: SpotPosition? = null,
    val positionTolerance: Double? = null,
    val nearRingBoundary: Boolean = false,
    val uncertain: Boolean = false,
    /**
     * This view's own reading of the same arrow: its tip in pixels of the
     * EXIF-turned original photograph (`shots[i].tipPx`), where the
     * annotator clicked it. [position] is the truth of the END, measured in
     * the steepest view and carried to every view of that end; through a
     * registration turned by the camera's roll it lands beside the arrow.
     * The tip does not: mapped through the homography of the run that is
     * being measured, it is where that run can see the arrow. See
     * [CorpusEntry.truthInView].
     */
    val tipPixel: ImagePoint? = null
) {
    init {
        require(scoringRing != null || printedScore != null) {
            "a shot needs a scoring ring or a printed score"
        }
        require(scoringRing == null || scoringRing >= 0) {
            "a zone index is not negative, got $scoringRing"
        }
        require(positionTolerance == null || positionTolerance > 0.0) {
            "a position tolerance is positive, got $positionTolerance"
        }
    }
}

/**
 * One photograph and everything known to be true about it.
 *
 * @param shots the hits whose entry point the annotator could determine. This
 *        may be fewer than [shotsPerEnd]; the remainder is [unresolvedArrows].
 *        An EMPTY list is not an error — it marks a photograph that is
 *        registered but not annotated, which still serves the registration
 *        tests.
 * @param unresolvedArrows arrows visible in the photograph whose entry point
 *        could not be determined. A detection matching no listed hit only
 *        counts as a false positive when this is zero.
 * @param outOfScope the reason this photograph is excluded from the metrics,
 *        or null when it is in scope. Set by [CorpusLoader] from the
 *        corpus's `out-of-scope.json`, never by a sidecar or a file name: an
 *        entry marked here still loads and still counts as a photograph --
 *        it stays available for registration tests -- but contributes to no
 *        hit metric at all.
 */
data class CorpusEntry(
    val imageName: String,
    val image: ImageInfo?,
    val camera: CameraInfo?,
    val capture: CaptureInfo?,
    val target: TargetInfo?,
    val shotsPerEnd: Int?,
    val shots: List<TruthShot>,
    val unresolvedArrows: Int,
    val registration: Registration?,
    val outOfScope: String? = null
) {
    init {
        require(imageName.isNotBlank()) { "an entry needs an image name" }
        require(unresolvedArrows >= 0) {
            "unresolved arrows cannot be negative, got $unresolvedArrows"
        }
    }

    /**
     * How many hits this entry expects a detector to find — the LISTED ones,
     * not the size of the end. The corpus README is explicit: the detection
     * rate and the position error refer to the listed hits.
     */
    val expectedShots: Int
        get() = shots.size

    /** False for a registration only entry, which contributes to no hit metric. */
    val isAnnotated: Boolean
        get() = shots.isNotEmpty()

    /** The capture conditions, used to break the metrics down by difficulty. */
    val tags: Set<String>
        get() = setOfNotNull(capture?.lighting, capture?.angle)

    /**
     * The truth as THIS view shows it: every shot that carries a
     * [TruthShot.tipPixel] gets its position from that tip through
     * [imageToTarget], the row-major 3x3 homography of the run being
     * measured (pixels of the EXIF-turned original to spot-local face
     * coordinates); shots without a tip keep the carried truth. The face
     * index is kept, the entry is otherwise unchanged.
     *
     * Why: the truth of an end lives in the frame of the steepest view. A
     * registration turned by the camera's roll puts it beside the arrow, and
     * a correctly found arrow then counts as a miss plus a false positive.
     * Matching against the view's own tip takes the roll out of the
     * measurement without touching the registrar.
     *
     * @throws IllegalArgumentException naming the photograph when a tip maps
     *         to infinity, which means the homography is not one of this
     *         photograph.
     */
    fun truthInView(imageToTarget: DoubleArray): CorpusEntry {
        require(imageToTarget.size == 9) { "$imageName: a homography has nine values, got ${imageToTarget.size}" }
        val h = imageToTarget
        return copy(shots = shots.map { shot ->
            val tip = shot.tipPixel ?: return@map shot
            val w = h[6] * tip.x + h[7] * tip.y + h[8]
            require(abs(w) > 1e-12) { "$imageName: the tip at (${tip.x}, ${tip.y}) maps to infinity; not this photograph's homography" }
            shot.copy(
                position = SpotPosition(
                    faceIndex = shot.position?.faceIndex ?: 0,
                    x = (h[0] * tip.x + h[1] * tip.y + h[2]) / w,
                    y = (h[3] * tip.x + h[4] * tip.y + h[5]) / w
                )
            )
        })
    }
}
