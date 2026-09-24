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

/**
 * Winograd costs about 300 MB of native memory for a 2.3 times faster pass
 * (app-path findings 3a: about 700 MB with, 400 MB without at 768 px). The
 * measure is the device's total memory, not memoryClass: that is the Java
 * heap limit, and the network lives in native memory. totalMem reports less
 * than the nominal RAM (a 6 GB phone about 5.5 GB, an 8 GB phone about
 * 7.4 GB), so 6 GiB here means: on from nominally 8 GB, off on 6 GB phones.
 */
object WinogradPolicy {
    const val MIN_TOTAL_MEM: Long = 6L * 1024 * 1024 * 1024

    fun enabled(isLowRamDevice: Boolean, totalMem: Long): Boolean =
        !isLowRamDevice && totalMem >= MIN_TOTAL_MEM
}
