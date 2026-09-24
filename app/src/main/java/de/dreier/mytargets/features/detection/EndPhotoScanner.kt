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

import android.content.Context
import de.dreier.mytargets.detection.DetectionRequests
import de.dreier.mytargets.shared.models.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one entry point 8b calls (app integration 8a): photo, face and end size
 * in, a ScanOutcome out. Runs on Dispatchers.Default; detections run one at a
 * time on the one detector of the process. Nothing a photo can do reaches the
 * caller as an exception; only a non-positive [shotsPerEnd] does, because
 * that is the caller's error, not the photo's.
 */
class EndPhotoScanner internal constructor(private val holder: DetectorHolder<LoadedDetector>) {

    suspend fun scan(photo: File, target: Target, shotsPerEnd: Int): ScanOutcome = withContext(Dispatchers.Default) {
        require(shotsPerEnd > 0) { "an end has at least one shot, got $shotsPerEnd" }
        if (!ScanSupport.supports(target)) return@withContext ScanOutcome.Unsupported
        holder.withLoaded(onLoadFailure = { ScanOutcome.ModelUnavailable(it) }) { loaded ->
            scanWith(loaded, photo, shotsPerEnd)
        }
    }

    private fun scanWith(loaded: LoadedDetector, photo: File, shotsPerEnd: Int): ScanOutcome {
        val decoded = try {
            PhotoInput.read(photo)
        } catch (e: PhotoUnreadableException) {
            return ScanOutcome.PhotoUnreadable(e)
        }
        return try {
            val result = loaded.detector.detect(decoded.image, DetectionRequests.waFull(shotsPerEnd, decoded.intrinsics))
            ScanOutcome.Detected(result, loaded.modelName)
        } catch (e: RuntimeException) {
            // CvException from OpenCV, IllegalStateException from a failed forward pass,
            // IllegalArgumentException from a require inside the pipeline: all "the
            // detection broke off", none a reason to crash on a photo.
            ScanOutcome.ScanFailed(e)
        } catch (e: OutOfMemoryError) {
            ScanOutcome.ScanFailed(e)
        } finally {
            decoded.image.release()
        }
    }

    companion object {
        @Volatile
        private var instance: EndPhotoScanner? = null

        fun get(context: Context): EndPhotoScanner = instance ?: synchronized(this) {
            instance ?: EndPhotoScanner(DetectorHolder { ModelLoading.load(context.applicationContext) })
                .also { instance = it }
        }
    }
}
