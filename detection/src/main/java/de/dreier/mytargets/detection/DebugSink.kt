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

package de.dreier.mytargets.detection

import org.opencv.core.Mat

/**
 * Receives the stage images of a detection (Haupt-Spec, Debug-Ansicht). The
 * registration and the arrow search both show their stages through it.
 */
fun interface DebugSink {
    /** [image] belongs to the stage and may be released after the call: write it now or copy it. */
    fun image(stage: String, image: Mat)

    companion object {
        val NONE = DebugSink { _, _ -> }
    }
}
