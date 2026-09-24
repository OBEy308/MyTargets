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

package de.dreier.mytargets.features.detection

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanOutcomeTest {

    @Test
    fun aFailureShowsItsCaseAndCause() {
        val cause = IllegalStateException("forward pass failed")

        val text = ScanOutcome.ScanFailed(cause).toString()

        assertTrue(text.startsWith("ScanFailed("), text)
        assertTrue("forward pass failed" in text, text)
    }

    @Test
    fun theSameCaseWithTheSameCauseIsEqual() {
        val cause = UnsatisfiedLinkError("no libopencv_java4")

        assertEquals(ScanOutcome.ModelUnavailable(cause), ScanOutcome.ModelUnavailable(cause))
        assertEquals("Unsupported", ScanOutcome.Unsupported.toString())
    }
}
