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

import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.targets.models.WAFull

/**
 * Which faces the scan is offered for: only the WA full face, in every
 * diameter and scoring style. It is the only face with colour transitions for
 * the registration and the only one the model has seen; others come with
 * their own corpus photos.
 */
object ScanSupport {
    fun supports(targetId: Long): Boolean = targetId == WAFull.ID

    fun supports(target: Target): Boolean = supports(target.id)
}
