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

package de.dreier.mytargets.detection.registration

import org.junit.rules.ExternalResource

/**
 * Loads the native libraries that org.openpnp:opencv bundles for the desktop,
 * once per test JVM. loadLocally rather than loadShared: loadShared patches
 * java.library.path by reflection, which JDK 17 refuses.
 */
class OpenCvRule : ExternalResource() {

    override fun before() = load()

    companion object {
        @Volatile
        private var loaded = false

        @Synchronized
        fun load() {
            if (!loaded) {
                nu.pattern.OpenCV.loadLocally()
                loaded = true
            }
        }
    }
}
