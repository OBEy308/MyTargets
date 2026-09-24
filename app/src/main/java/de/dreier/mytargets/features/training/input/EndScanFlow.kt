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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.snackbar.Snackbar
import de.dreier.mytargets.R
import de.dreier.mytargets.features.detection.ScanMessage
import de.dreier.mytargets.features.detection.ScanSupport
import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.utils.PermissionUtils
import pl.aprilapps.easyphotopicker.DefaultCallback
import pl.aprilapps.easyphotopicker.EasyImage
import timber.log.Timber
import java.io.File

/**
 * Scans the current end from a photo (app integration 8b): question, camera,
 * attaching the photo, the scan, entering and the message. The only state is
 * the [PendingScan]; a run cut off by rotation or process death starts again
 * from it.
 */
class EndScanFlow(private val activity: AppCompatActivity, private val host: Host) : DefaultLifecycleObserver {

    interface Host {
        /** The loaded training; null until the loader delivered it. */
        val loadedData: LoaderResult?

        /** Where the snackbars go. */
        val messageAnchor: View

        /** The ring for a position, as when the shot is plotted by hand. */
        fun zoneAt(x: Float, y: Float): Int

        /** Replaces the current end's shots and exact flag in view and database. */
        fun enterShots(shots: List<Shot>, exact: Boolean)

        /** The current end's images changed. */
        fun onImagesChanged()
    }

    private var pending: PendingScan? = null

    fun restore(savedInstanceState: Bundle?) {
        pending = PendingScan.fromBundle(savedInstanceState?.getBundle(KEY_PENDING_SCAN))
    }

    fun save(outState: Bundle) {
        pending?.let { outState.putBundle(KEY_PENDING_SCAN, it.toBundle()) }
    }

    fun isOffered(data: LoaderResult): Boolean = ScanSupport.supports(data.currentRound.round.target)

    fun onMenuSelected() {
        val d = host.loadedData ?: return
        val set = d.currentEnd.shots.count { it.scoringRing != Shot.NOTHING_SELECTED }
        if (set == 0) {
            requestCamera()
            return
        }
        // Asked before the camera, so no dialog has to survive a process death.
        MaterialDialog.Builder(activity)
            .content(activity.resources.getQuantityString(R.plurals.scan_replace_question, set, set))
            .positiveText(R.string.scan_replace)
            .negativeText(android.R.string.cancel)
            .onPositive { _, _ -> requestCamera() }
            .show()
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) openCamera()
    }

    private fun requestCamera() {
        // Without the runtime permission the capture intent throws a SecurityException.
        if (PermissionUtils.hasCameraPermission(activity)) {
            openCamera()
        } else {
            PermissionUtils.requestCameraPermission(activity)
        }
    }

    private fun openCamera() {
        val d = host.loadedData ?: return
        // Fixed before the camera opens: after a process death the end would be unknown.
        pending = PendingScan(endId = d.currentEnd.end.id, originalPath = null, storedImageName = null)
        try {
            EasyImage.openCameraForImage(activity, 0)
        } catch (e: ActivityNotFoundException) {
            pending = null
            Toast.makeText(activity, R.string.no_camera_app, Toast.LENGTH_LONG).show()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        EasyImage.handleActivityResult(requestCode, resultCode, data, activity, object : DefaultCallback() {
            override fun onImagesPicked(imageFiles: List<File>, source: EasyImage.ImageSource, type: Int) {
                val photo = imageFiles.firstOrNull() ?: return
                val p = pending
                if (p == null) {
                    // No end to put it on.
                    photo.delete()
                    return
                }
                pending = p.copy(originalPath = photo.path)
            }

            override fun onCanceled(source: EasyImage.ImageSource?, type: Int) {
                if (source == EasyImage.ImageSource.CAMERA_IMAGE) {
                    EasyImage.lastlyTakenButCanceledPhoto(activity.applicationContext)?.delete()
                }
                pending = null
            }

            override fun onImagePickerError(e: Exception, source: EasyImage.ImageSource?, type: Int) {
                Timber.e(e, "The camera returned no usable picture")
                pending = null
                show(ScanMessage.PhotoUnreadable)
            }
        })
    }

    private fun show(message: ScanMessage) {
        val text = when (message) {
            ScanMessage.PhotoUnreadable -> activity.getString(R.string.scan_photo_unreadable)
            else -> return
        }
        Snackbar.make(host.messageAnchor, text, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val KEY_PENDING_SCAN = "pending_scan"
    }
}
