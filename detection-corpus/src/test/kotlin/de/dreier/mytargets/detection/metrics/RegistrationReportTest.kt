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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class RegistrationReportTest {

    private fun registered(name: String, max: Double, outOfScope: String? = null) =
        RegistrationRow(
            imageName = name, outOfScope = outOfScope,
            outcome = RegistrationRow.REGISTERED, detail = null,
            ringsUsed = listOf(0.2, 0.4, 0.6), worstRingRms = 0.004,
            error = RegistrationError(max / 2, max, 360)
        )

    private fun failed(name: String, outcome: String, detail: String, outOfScope: String? = null) =
        RegistrationRow(name, outOfScope, outcome, detail, emptyList(), null, null)

    @Test
    fun theHeaderCountsInScopeRegisteredAndFailedByReason() {
        val report = RegistrationReport.render(
            listOf(
                registered("a.jpg", 0.003),
                failed("b.jpg", "FACE_NOT_FOUND", "no yellow disc"),
                failed("c.jpg", "FACE_MISMATCH", "3 yellow discs, expected 1", "three faces")
            ),
            title = "Run"
        )

        assertThat(report).contains(
            "3 photographs, 2 in scope: 1 registered, 1 failed (FACE_NOT_FOUND: 1)."
        )
    }

    @Test
    fun failuresComeFirstThenTheWorstRegistration() {
        val report = RegistrationReport.render(
            listOf(
                registered("good.jpg", 0.001),
                registered("bad.jpg", 0.010),
                failed("lost.jpg", "FACE_NOT_FOUND", "no yellow disc")
            ),
            title = "Run"
        )

        val lost = report.indexOf("| lost.jpg |")
        val bad = report.indexOf("| bad.jpg |")
        val good = report.indexOf("| good.jpg |")
        assertThat(lost).isLessThan(bad)
        assertThat(bad).isLessThan(good)
    }

    @Test
    fun aRegisteredRowCarriesItsRingsAndErrors() {
        val report = RegistrationReport.render(listOf(registered("a.jpg", 0.003)), "Run")

        assertThat(report).contains(
            "| a.jpg | registered | 0.2, 0.4, 0.6 | 0.0040 | 0.0015 | 0.0030 | 360 |"
        )
    }

    @Test
    fun aFailedRowCarriesItsDetail() {
        val report = RegistrationReport.render(
            listOf(failed("b.jpg", "FACE_NOT_FOUND", "no yellow disc")), "Run"
        )

        assertThat(report).contains("| b.jpg | FACE_NOT_FOUND: no yellow disc | - | - | - | - | - |")
    }

    @Test
    fun theSummaryIsTheMedianAndLargestOfThePerPhotographMaxima() {
        val report = RegistrationReport.render(
            listOf(registered("a.jpg", 0.001), registered("b.jpg", 0.003), registered("c.jpg", 0.010)),
            title = "Run"
        )

        assertThat(report).contains(
            "Median of the per-photograph maxima: 0.0030 spot radii. " +
                "Largest: 0.0100 spot radii (c.jpg)."
        )
    }

    @Test
    fun outOfScopeRowsAreListedApartAndStayOutOfTheSummary() {
        val report = RegistrationReport.render(
            listOf(registered("in.jpg", 0.002), registered("w.jpg", 0.5, outOfScope = "WA6Ring face")),
            title = "Run"
        )

        assertThat(report).contains("Largest: 0.0020 spot radii (in.jpg).")
        assertThat(report).contains("## Out of scope")
        assertThat(report).contains("| w.jpg | WA6Ring face | registered | 0.5000 |")
    }

    @Test
    fun numbersDoNotFollowTheDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val report = RegistrationReport.render(listOf(registered("a.jpg", 0.003)), "Run")
            assertThat(report).contains("0.0030")
            assertThat(report).doesNotContain("0,0030")
        } finally {
            Locale.setDefault(original)
        }
    }
}
