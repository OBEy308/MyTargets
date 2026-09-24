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

package de.dreier.mytargets.features.training.input

import android.os.Bundle

/**
 * The only state of a scan across the trip to the camera (app integration 8b):
 * written to outState, so it survives rotation and process death.
 *
 * @param endId the end the photo belongs to, fixed before the camera opens
 * @param originalPath the camera's file; null while the camera is open
 * @param storedImageName set once the 2048 px copy hangs on the end
 */
data class PendingScan(
    val endId: Long,
    val originalPath: String?,
    val storedImageName: String?
) {
    fun toBundle(): Bundle = Bundle().apply {
        putLong(END_ID, endId)
        putString(ORIGINAL_PATH, originalPath)
        putString(STORED_IMAGE_NAME, storedImageName)
    }

    companion object {
        private const val END_ID = "end_id"
        private const val ORIGINAL_PATH = "original_path"
        private const val STORED_IMAGE_NAME = "stored_image_name"

        fun fromBundle(bundle: Bundle?): PendingScan? {
            if (bundle == null || !bundle.containsKey(END_ID)) return null
            return PendingScan(bundle.getLong(END_ID), bundle.getString(ORIGINAL_PATH), bundle.getString(STORED_IMAGE_NAME))
        }
    }
}
