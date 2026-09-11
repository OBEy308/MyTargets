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

import java.util.Locale

/** One photograph of a registration run, as the report shows it. */
class RegistrationRow(
    val imageName: String,
    /** The reason from out-of-scope.json, or null when the photograph is in scope. */
    val outOfScope: String?,
    /** [REGISTERED], or the name of the failure. */
    val outcome: String,
    /** Why it failed; null for a registered row. */
    val detail: String?,
    /** Nominal radii of the rings that went into the final fit. */
    val ringsUsed: List<Double>,
    val worstRingRms: Double?,
    val error: RegistrationError?
) {
    val registered: Boolean
        get() = outcome == REGISTERED

    companion object {
        const val REGISTERED = "registered"
    }
}

/**
 * Renders a registration run as Markdown. Photographs outside the scope get a
 * table of their own and count for no summary. There is no verdict: the
 * report measures, and bounds are set against its numbers later.
 */
object RegistrationReport {

    fun render(rows: List<RegistrationRow>, title: String): String {
        val inScope = rows.filter { it.outOfScope == null }
        val outOfScope = rows.filter { it.outOfScope != null }
        val registered = inScope.filter { it.registered }
        val failed = inScope.filterNot { it.registered }

        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        val byReason = failed.groupingBy { it.outcome }.eachCount().toSortedMap()
        val reasons = if (byReason.isEmpty()) {
            ""
        } else {
            byReason.entries.joinToString(prefix = " (", postfix = ")") { "${it.key}: ${it.value}" }
        }
        sb.appendLine(
            "${rows.size} photographs, ${inScope.size} in scope: " +
                "${registered.size} registered, ${failed.size} failed$reasons."
        )
        sb.appendLine()
        sb.appendLine(
            "Errors are in spot radii, measured on the rings 0.2 to 1.0 where they lie " +
                "inside the image."
        )

        sb.appendLine()
        sb.appendLine("## In scope")
        sb.appendLine()
        sb.appendLine(
            "| Photograph | Outcome | Rings | Worst ring RMS | Error, median | Error, max | " +
                "Visible points |"
        )
        sb.appendLine("|---|---|---|---|---|---|---|")
        val ordered = failed.sortedBy { it.imageName } +
            registered.sortedByDescending { it.error?.max ?: Double.MAX_VALUE }
        for (row in ordered) {
            sb.appendLine(inScopeLine(row))
        }

        sb.appendLine()
        val maxima = registered.mapNotNull { row -> row.error?.let { row to it.max } }
            .sortedBy { it.second }
        if (maxima.isEmpty()) {
            sb.appendLine("No photograph in scope was measured.")
        } else {
            val values = maxima.map { it.second }
            val (worstName, worst) = maxima.last().let { it.first.imageName to it.second }
            sb.appendLine(
                "Median of the per-photograph maxima: ${number(median(values))} spot radii. " +
                    "Largest: ${number(worst)} spot radii ($worstName)."
            )
        }

        if (outOfScope.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Out of scope")
            sb.appendLine()
            sb.appendLine("These count for no summary.")
            sb.appendLine()
            sb.appendLine("| Photograph | Reason | Outcome | Error, max |")
            sb.appendLine("|---|---|---|---|")
            for (row in outOfScope.sortedBy { it.imageName }) {
                sb.appendLine(
                    "| ${row.imageName} | ${row.outOfScope} | ${outcomeText(row)} | " +
                        "${row.error?.let { number(it.max) } ?: "-"} |"
                )
            }
        }
        return sb.toString()
    }

    private fun inScopeLine(row: RegistrationRow): String {
        if (!row.registered) {
            return "| ${row.imageName} | ${outcomeText(row)} | - | - | - | - | - |"
        }
        val rings = row.ringsUsed.joinToString { String.format(Locale.ROOT, "%.1f", it) }
        val rms = row.worstRingRms?.let { number(it) } ?: "-"
        val error = row.error
        val errors = if (error == null) {
            "not measured | not measured | 0"
        } else {
            "${number(error.median)} | ${number(error.max)} | ${error.visiblePoints}"
        }
        return "| ${row.imageName} | ${RegistrationRow.REGISTERED} | $rings | $rms | $errors |"
    }

    private fun outcomeText(row: RegistrationRow) =
        if (row.registered || row.detail == null) row.outcome else "${row.outcome}: ${row.detail}"

    private fun median(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }

    // Locale.ROOT on purpose, as in MetricsReport: reports are diffed between runs.
    private fun number(value: Double) = String.format(Locale.ROOT, "%.4f", value)
}
