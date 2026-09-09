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
                "${overall.expectedShots} ${plural(overall.expectedShots, "arrow", "arrows")}, " +
                "${overall.entriesWithPositions} with annotated positions."
        )
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Detection rate | ${percent(overall.detectionRate)} |")
        sb.appendLine("| False positives | ${percent(overall.falsePositiveRate)} |")
        sb.appendLine("| Ring accuracy | ${percent(overall.scoreAccuracy)} |")
        sb.appendLine("| Position error, median | ${spotRadii(overall.medianPositionError)} |")
        sb.appendLine("| Position error, 95th pct | ${spotRadii(overall.p95PositionError)} |")

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

    // Locale.ROOT on purpose: a report that says "97,3 %" on one machine and
    // "97.3 %" on another cannot be diffed between runs.
    private fun percent(value: Double) =
        String.format(java.util.Locale.ROOT, "%.1f %%", value * 100.0)

    /** Position errors are in spot radii, so a bare number would be ambiguous. */
    private fun spotRadii(value: Double?) =
        if (value == null) {
            "not measured"
        } else {
            String.format(java.util.Locale.ROOT, "%.4f spot radii", value)
        }
}
