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

package de.dreier.mytargets.detection.metrics

import de.dreier.mytargets.detection.corpus.CorpusEntry
import java.util.Locale
import kotlin.math.abs

/** Which group a photograph falls in for the arrow report and its bounds (arrow design, Schranken). */
object ArrowGroups {
    const val OBLIQUE = "schraeg"
    const val FRONTAL = "frontal"
    const val UNKNOWN = "ohne Winkel"

    /** The order the report shows them in. */
    val ORDER = listOf(OBLIQUE, FRONTAL, UNKNOWN)

    fun of(entry: CorpusEntry): String = when (entry.capture?.angle) {
        "leicht-schraeg", "stark-schraeg" -> OBLIQUE
        "frontal" -> FRONTAL
        else -> UNKNOWN
    }
}

/** Wall time per stage in milliseconds. */
class StageMillis(val registration: Long, val warp: Long, val search: Long, val walk: Long)

/** One photograph of an arrow run, as the report shows it. */
class ArrowRow(
    val imageName: String,
    val group: String,
    /** The camera's angle to the face normal from the capture metadata, in degrees; null when the sidecar has none. */
    val angleDegrees: Double?,
    /** REGISTERED, or the name of the registration's failure. */
    val outcome: String,
    /** Why the registration failed; null for a registered row. */
    val detail: String?,
    /** Distance of the camera's foot point from the face centre. */
    val footPointFromCentre: Double?,
    /** Largest registration error against the sidecar's reference, as in the registration report. */
    val registrationError: Double?,
    /** Distance between the pipeline's foot point and the one from the reference homography. */
    val footPointShift: Double?,
    /** Largest distance from Q of the line of a candidate matched to a listed hit. */
    val largestLineOffset: Double?,
    /** Distance from Q of the common point of the refined lines of the matched candidates. */
    val commonPointFromFoot: Double?,
    val listed: Int,
    val unresolved: Int,
    val candidates: Int,
    val accepted: Int,
    val matched: Int,
    val falsePositives: Int,
    /** The selection's reason; null when the face was not registered. */
    val selection: String?,
    val largestPositionError: Double?,
    val noShaft: Int,
    val ranOut: Int,
    val millis: StageMillis
) {
    val registered: Boolean
        get() = outcome == REGISTERED

    companion object {
        const val REGISTERED = "registered"
    }
}

/** One listed hit and what the candidates before the selection had for it. */
class TruthDiagnosis(
    val imageName: String,
    val group: String,
    val truthIndex: Int,
    /** 1-based rank by score of the candidate matched to this hit; null when none lay within the budget. */
    val rank: Int?,
    val confidence: Double?,
    val lineOffset: Double?,
    val accepted: Boolean
)

/** The best confidence on a photograph of a candidate that matches no listed hit. */
class PhotoDiagnosis(val imageName: String, val group: String, val bestFalseConfidence: Double?)

/**
 * Renders an arrow run as Markdown (arrow design, Korpuslauf und Bericht): the
 * groups, the metrics, a table per photograph, and the candidates before the
 * selection, which separate finding from selecting.
 */
object ArrowReport {

    /** A line offset worth a note: close to the search's window of 0.04. */
    const val LINE_OFFSET_NOTE = 0.03

    fun render(
        title: String,
        rows: List<ArrowRow>,
        truths: List<TruthDiagnosis>,
        photos: List<PhotoDiagnosis>,
        outcomes: List<EntryOutcome>,
        outOfScope: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        val registered = rows.count { it.registered }
        sb.appendLine(
            "${rows.size} photographs in scope, $registered registered, ${rows.size - registered} not. " +
                "Lengths, offsets and errors are in spot radii."
        )
        groups(sb, rows, outcomes)
        sb.appendLine()
        // One heading level down, its own sections included.
        sb.append(
            MetricsReport.render(outcomes, "Metrics over every photograph in scope")
                .replace(Regex("^#", RegexOption.MULTILINE), "##")
        )
        photographs(sb, rows)
        diagnosis(sb, truths, photos)
        if (outOfScope.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Out of scope")
            sb.appendLine()
            sb.appendLine("These are not run; they count for no metric.")
            sb.appendLine()
            sb.appendLine("| Photograph | Reason |")
            sb.appendLine("|---|---|")
            for ((name, reason) in outOfScope.sortedBy { it.first }) sb.appendLine("| $name | $reason |")
        }
        return sb.toString()
    }

    private fun groups(sb: StringBuilder, rows: List<ArrowRow>, outcomes: List<EntryOutcome>) {
        sb.appendLine()
        sb.appendLine("## Groups")
        sb.appendLine()
        sb.appendLine(
            "Ring accuracy stands with its denominator and beside the detection rate, as the " +
                "Haupt-Spec requires."
        )
        sb.appendLine()
        sb.appendLine(
            "| Group | Photographs | Listed hits | Matched | Detection rate | False positives | " +
                "Ring accuracy | Median error | p95 error |"
        )
        sb.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (group in ArrowGroups.ORDER) {
            val photographs = rows.count { it.group == group }
            if (photographs == 0) continue
            val m = Metrics.over(outcomes.filter { ArrowGroups.of(it.entry) == group })
            sb.appendLine(
                "| $group | $photographs | ${m.expectedShots} | ${m.matchedShots} | " +
                    "${percent(m.detectionRate)} | ${m.falsePositives} | " +
                    "${percent(m.scoreAccuracy)} (${m.correctScores}/${m.scoreComparableShots}) | " +
                    "${number(m.medianPositionError)} | ${number(m.p95PositionError)} |"
            )
        }
    }

    private fun photographs(sb: StringBuilder, rows: List<ArrowRow>) {
        for (group in ArrowGroups.ORDER) {
            val inGroup = rows.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            sb.appendLine()
            sb.appendLine("## Photographs, $group")
            sb.appendLine()
            sb.appendLine(
                "| Photograph | Outcome | Angle (deg) | Q from centre | Registration error | Q shift | " +
                    "Largest line offset | Common point from Q | Listed | Unresolved | Candidates | " +
                    "Accepted | Matched | False positives | Selection | Largest error | NO_SHAFT | " +
                    "RAN_OUT | ms: registration / warp / search / walk |"
            )
            sb.appendLine("|" + "---|".repeat(19))
            // Failures first, then the photograph missing most, then the one inventing most.
            val ordered = inGroup.sortedWith(
                compareBy<ArrowRow>({ it.registered }, { -(it.listed - it.matched) }, { -it.falsePositives }, { it.imageName })
            )
            for (row in ordered) sb.appendLine(line(row))
        }
    }

    private fun line(row: ArrowRow): String {
        val outcome = if (row.registered || row.detail == null) row.outcome else "${row.outcome}: ${row.detail}"
        val t = row.millis
        return "| ${row.imageName} | $outcome | ${degrees(row.angleDegrees)} | ${number(row.footPointFromCentre)} | " +
            "${number(row.registrationError)} | ${number(row.footPointShift)} | " +
            "${number(row.largestLineOffset)} | ${number(row.commonPointFromFoot)} | ${row.listed} | " +
            "${row.unresolved} | ${row.candidates} | ${row.accepted} | ${row.matched} | " +
            "${row.falsePositives} | ${row.selection ?: "-"} | ${number(row.largestPositionError)} | " +
            "${row.noShaft} | ${row.ranOut} | ${t.registration} / ${t.warp} / ${t.search} / ${t.walk} |"
    }

    private fun diagnosis(sb: StringBuilder, truths: List<TruthDiagnosis>, photos: List<PhotoDiagnosis>) {
        sb.appendLine()
        sb.appendLine("## Candidates before the selection")
        sb.appendLine()
        sb.appendLine(
            "The matching of the metrics, run over every candidate on the face instead of the " +
                "accepted ones. A hit whose candidate the selection did not accept was lost by the " +
                "selection; a hit without a candidate was never found."
        )
        for (group in ArrowGroups.ORDER) {
            val inGroup = truths.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            val found = inGroup.count { it.rank != null }
            val lost = inGroup.count { it.rank != null && !it.accepted }
            val offsets = inGroup.mapNotNull { it.lineOffset }.map { abs(it) }.sorted()
            val over = offsets.count { it > LINE_OFFSET_NOTE }
            sb.appendLine()
            sb.appendLine(
                "**$group:** $found of ${inGroup.size} listed hits had a candidate within the budget " +
                    "(${percent(ratio(found, inGroup.size))}); the selection lost $lost of them. " +
                    "Line offsets from Q of those candidates: median ${number(median(offsets))}, " +
                    "above $LINE_OFFSET_NOTE: $over of ${offsets.size}."
            )
        }
        sb.appendLine()
        sb.appendLine("| Photograph | Best confidence of a candidate matching no hit |")
        sb.appendLine("|---|---|")
        for (p in photos.sortedBy { it.imageName }) {
            sb.appendLine("| ${p.imageName} | ${number(p.bestFalseConfidence)} |")
        }
        sb.appendLine()
        sb.appendLine("| Photograph | Hit | Rank | Confidence | Line offset | Accepted |")
        sb.appendLine("|---|---|---|---|---|---|")
        for (t in truths.sortedWith(compareBy<TruthDiagnosis>({ it.imageName }, { it.truthIndex }))) {
            sb.appendLine(
                "| ${t.imageName} | ${t.truthIndex} | ${t.rank ?: "-"} | ${number(t.confidence)} | " +
                    "${number(t.lineOffset)} | ${if (t.accepted) "yes" else "no"} |"
            )
        }
    }

    private fun ratio(count: Int, total: Int): Double? = if (total == 0) null else count.toDouble() / total

    private fun median(sorted: List<Double>): Double? = when {
        sorted.isEmpty() -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> 0.5 * (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2])
    }

    private fun number(value: Double?) = value?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "-"

    private fun degrees(value: Double?) = value?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "-"

    private fun percent(value: Double?) = value?.let { String.format(Locale.ROOT, "%.1f %%", it * 100.0) } ?: "-"
}
