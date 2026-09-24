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
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingScanTest {

    @Test
    fun aScanWithTheCameraOpenSurvivesTheBundle() {
        val scan = PendingScan(endId = 42, originalPath = null, storedImageName = null)

        assertThat(PendingScan.fromBundle(throughParcel(scan.toBundle()))).isEqualTo(scan)
    }

    @Test
    fun aScanWithPhotoAndStoredCopySurvivesTheBundle() {
        val scan = PendingScan(endId = 42, originalPath = "/data/cache/EasyImage/a.jpg", storedImageName = "scan-a.jpg")

        assertThat(PendingScan.fromBundle(throughParcel(scan.toBundle()))).isEqualTo(scan)
    }

    @Test
    fun noBundleMeansNoScan() {
        assertThat(PendingScan.fromBundle(null)).isNull()
        assertThat(PendingScan.fromBundle(Bundle())).isNull()
    }

    /** What process death does to a saved state: written to a parcel and read back. */
    private fun throughParcel(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return parcel.readBundle(javaClass.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }
}
