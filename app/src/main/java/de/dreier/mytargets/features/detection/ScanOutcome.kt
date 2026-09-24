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

import de.dreier.mytargets.detection.DetectionResult

/**
 * What a scan came to (app integration 8a). Each case gets its own answer in
 * 8b (arrow design, Fehlerfälle); a face that was not found or does not match
 * is a Detected with result.failure set.
 */
sealed class ScanOutcome {
    class Detected(val result: DetectionResult, val modelName: String) : ScanOutcome()

    /** The face is not a WA full face; 8b does not offer the scan for it. */
    object Unsupported : ScanOutcome()

    /** The file is missing, empty, cut off or in a format imread cannot decode. */
    class PhotoUnreadable(val cause: Throwable) : ScanOutcome()

    /** OpenCV or the network did not load; the next scan tries again. */
    class ModelUnavailable(val cause: Throwable) : ScanOutcome()

    /** The detection itself broke off, e.g. out of memory in the forward pass. */
    class ScanFailed(val cause: Throwable) : ScanOutcome()
}
