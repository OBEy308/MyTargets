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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WinogradPolicyTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun onWithPlentyOfMemory() = assertTrue(WinogradPolicy.enabled(isLowRamDevice = false, totalMem = 12 * gib))

    @Test
    fun offBelowSixGigabytes() = assertFalse(WinogradPolicy.enabled(isLowRamDevice = false, totalMem = 4 * gib))

    @Test
    fun onAtExactlySixGigabytes() = assertTrue(WinogradPolicy.enabled(isLowRamDevice = false, totalMem = 6 * gib))

    @Test
    fun offOnALowRamDeviceWhateverItReports() = assertFalse(WinogradPolicy.enabled(isLowRamDevice = true, totalMem = 12 * gib))
}
