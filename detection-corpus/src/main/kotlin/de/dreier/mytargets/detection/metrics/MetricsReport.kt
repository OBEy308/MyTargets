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

/**
 * Renders a measurement run as Markdown.
 *
 * A number without its corpus is not a result, so the report always states how
 * many photographs and arrows it rests on, breaks the metrics down by tag so
 * "dark" can be compared against the rest, and names the worst entries — those
 * are the ones worth opening in the debug view.
 */
object MetricsReport {

    private const val WORST_ENTRIES = 5

    fun render(outcomes: List<EntryOutcome>, title: String): String {
        val overall = Metrics.over(outcomes)
        val photographs = outcomes.size

        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        sb.appendLine(
            "$photographs ${plural(photographs, "photograph", "photographs")}, " +
                "${overall.annotatedEntries} annotated, " +
                "${overall.expectedShots} listed " +
                "${plural(overall.expectedShots, "hit", "hits")}, " +
                "${overall.entriesWithPositions} with positions."
        )
        if (overall.forgivenEntries > 0) {
            sb.appendLine()
            sb.appendLine(
                "${overall.forgivenEntries} " +
                    "${plural(overall.forgivenEntries, "entry", "entries")} " +
                    "declare unresolved arrows, so surplus detections there are not " +
                    "counted as false positives."
            )
        }
        sb.appendLine()
        sb.appendLine("| Metric | Value | Measured over |")
        sb.appendLine("|---|---|---|")
        sb.appendLine(
            "| Detection rate | ${percent(overall.detectionRate)} | " +
                "of ${overall.expectedShots} listed hits |"
        )
        sb.appendLine(
            "| False positives | ${percent(overall.falsePositiveRate)} | " +
                "of ${overall.expectedShots} listed hits |"
        )
        sb.appendLine(
            "| Ring accuracy | ${percent(overall.scoreAccuracy)} | " +
                "of ${overall.scoreComparableShots} comparable hits |"
        )
        sb.appendLine(
            "| Position error, median | ${spotRadii(overall.medianPositionError)} | " +
                "of ${overall.positionErrors.size} placed hits |"
        )
        sb.appendLine(
            "| Position error, 95th pct | ${spotRadii(overall.p95PositionError)} | " +
                "of ${overall.positionErrors.size} placed hits |"
        )

        val byTag = Metrics.byTag(outcomes)
        if (byTag.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## By tag")
            sb.appendLine()
            sb.appendLine("| Tag | Arrows | Detection | Ring accuracy |")
            sb.appendLine("|---|---|---|---|")
            for ((tag, metrics) in byTag) {
                sb.appendLine(
                    "| $tag | ${metrics.expectedShots} | " +
                        "${percent(metrics.detectionRate)} | " +
                        "${percent(metrics.scoreAccuracy)} |"
                )
            }
        }

        val worst = outcomes
            .sortedWith(
                compareBy({ Metrics.over(listOf(it)).scoreAccuracy }, { it.entry.imageName })
            )
            .take(WORST_ENTRIES)
        if (worst.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Worst entries")
            sb.appendLine()
            sb.appendLine("| Photograph | Arrows | Found | Correct | Invented |")
            sb.appendLine("|---|---|---|---|---|")
            for (outcome in worst) {
                val m = Metrics.over(listOf(outcome))
                sb.appendLine(
                    "| ${outcome.entry.imageName} | ${m.expectedShots} | " +
                        "${m.matchedShots} | ${m.correctScores} | ${m.falsePositives} |"
                )
            }
        }

        return sb.toString()
    }

    private fun plural(count: Int, one: String, many: String) = if (count == 1) one else many

    /** Renders a value that may be absent -- an empty corpus, or one with no
     *  annotated positions -- as "not measured" rather than a number that
     *  could be mistaken for a real zero. */
    private fun measured(value: Double?, format: (Double) -> String) =
        if (value == null) "not measured" else format(value)

    // Locale.ROOT on purpose: a report that says "97,3 %" on one machine and
    // "97.3 %" on another cannot be diffed between runs.
    private fun percent(value: Double?) = measured(value) {
        String.format(java.util.Locale.ROOT, "%.1f %%", it * 100.0)
    }

    /** Position errors are in spot radii, so a bare number would be ambiguous. */
    private fun spotRadii(value: Double?) = measured(value) {
        String.format(java.util.Locale.ROOT, "%.4f spot radii", it)
    }
}
