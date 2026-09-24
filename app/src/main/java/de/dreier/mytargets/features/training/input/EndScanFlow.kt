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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.snackbar.Snackbar
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.features.detection.EndPhotoScanner
import de.dreier.mytargets.features.detection.EndPhotoStore
import de.dreier.mytargets.features.detection.ScanApplication
import de.dreier.mytargets.features.detection.ScanMessage
import de.dreier.mytargets.features.detection.ScanSupport
import de.dreier.mytargets.shared.models.db.EndImage
import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.aprilapps.easyphotopicker.DefaultCallback
import pl.aprilapps.easyphotopicker.EasyImage
import timber.log.Timber
import java.io.File

/**
 * Scans the current end from a photo (app integration 8b): question, camera,
 * attaching the photo, the scan, entering and the message. The only state is
 * the [PendingScan]; a run cut off by rotation or process death starts again
 * from it, and attaching the photo is idempotent so that costs no second image.
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

    /** One run of store, check, scan, enter; [cancelledByUser] tells "Abbrechen" from the activity going away. */
    private class Run(val dialog: MaterialDialog) {
        lateinit var job: Job
        var cancelledByUser = false
    }

    /** What "Rückgängig" puts back, bound to its end. */
    private class Undo(val endId: Long, val shots: List<Shot>, val exact: Boolean)

    private enum class Action { UNDO, RETAKE }

    private val endDAO by lazy { ApplicationInstance.db.endDAO() }
    private var pending: PendingScan? = null
    private var current: Run? = null
    private var undo: Undo? = null
    private var undoBar: Snackbar? = null

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

    /**
     * Starts the run once the photo is there and the training is loaded; called
     * at the end of onLoadFinished and from onResume. Does nothing while a run
     * is going.
     */
    fun resume() {
        val p = pending ?: return
        val path = p.originalPath ?: return
        if (current != null || host.loadedData == null) return
        val original = File(path)
        if (!original.exists()) {
            // A run finished after this state was saved (e.g. home during the scan, then process death).
            Timber.i("Pending scan of end ${p.endId} has no photo any more, dropping it")
            pending = null
            return
        }
        val dialog = MaterialDialog.Builder(activity)
            .content(R.string.scan_progress)
            .progress(true, 0)
            .cancelable(false)
            .negativeText(android.R.string.cancel)
            .onNegative { _, _ -> cancelByUser() }
            .show()
        val run = Run(dialog)
        current = run
        run.job = activity.lifecycleScope.launch { execute(p, original, run) }
    }

    /** The end on screen changes: the scan's Undo no longer applies. */
    fun onEndChanged() {
        undoBar?.dismiss()
        undoBar = null
        undo = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // The job dies with the activity; its dialog must not leak the window.
        current?.dialog?.dismiss()
    }

    private fun cancelByUser() {
        val run = current ?: return
        run.cancelledByUser = true
        run.job.cancel()
        // A new scan may start at once; this run cleans up when its current step returns.
        current = null
    }

    private suspend fun execute(start: PendingScan, original: File, run: Run) {
        var done = false
        try {
            var p = start
            if (p.storedImageName == null) {
                val name = attach(original, p.endId)
                if (name != null) {
                    p = p.copy(storedImageName = name)
                    if (pending?.originalPath == p.originalPath) pending = p
                }
            }
            val d = host.loadedData
            if (d == null || d.currentEnd.end.id != p.endId) {
                showOtherEnd(d, p.endId)
                done = true
                return
            }
            val round = d.currentRound.round
            val outcome = EndPhotoScanner.get(activity).scan(original, round.target, round.shotsPerEnd)
            val report = ScanApplication.apply(outcome, d.currentEnd.shots, host::zoneAt)
            report.shots?.let { enter(it, p.endId) }
            show(report.message, round.target.name)
            done = true
        } finally {
            if (done || run.cancelledByUser) {
                original.delete()
                if (pending?.originalPath == original.path) pending = null
            }
            // Otherwise the activity is going away: the saved pending scan starts the run again.
            run.dialog.dismiss()
            if (current === run) current = null
        }
    }

    /**
     * Puts the 2048 px copy on the end, at most once per photo: the name is fixed
     * per original and the database is asked first, under one lock for the
     * whole process, because a run cut off by rotation may still be writing.
     * @return the file name, or null if attaching failed (logged; the scan goes on)
     */
    private suspend fun attach(original: File, endId: Long): String? {
        val name = EndPhotoStore.nameFor(original)
        val images = withContext(Dispatchers.IO) {
            STORE_LOCK.withLock {
                val file = File(activity.filesDir, name)
                try {
                    if (endDAO.countEndImages(endId, name) == 0) {
                        try {
                            EndPhotoStore.store(original, activity.filesDir)
                            endDAO.insertEndImage(EndImage(fileName = name, endId = endId))
                        } catch (e: Throwable) {
                            // Written but not attached (e.g. the end was deleted): nothing refers to it.
                            file.delete()
                            throw e
                        }
                    }
                    endDAO.loadEndImages(endId)
                } catch (e: Exception) {
                    Timber.e(e, "Could not attach the scan photo to end $endId")
                    null
                } catch (e: OutOfMemoryError) {
                    Timber.e(e, "Out of memory attaching the scan photo to end $endId")
                    null
                }
            }
        } ?: return null
        val d = host.loadedData
        if (d != null && d.currentEnd.end.id == endId) {
            // From the database, not appended: the gallery writes this list back as the truth.
            d.currentEnd.images = images.toMutableList()
            host.onImagesChanged()
        }
        return name
    }

    private fun enter(shots: List<Shot>, endId: Long) {
        val d = host.loadedData ?: return
        undo = Undo(endId, d.currentEnd.shots.map { it.copy() }, d.currentEnd.end.exact)
        host.enterShots(shots, exact = true)
    }

    private fun undoScan() {
        val u = undo ?: return
        undo = null
        val d = host.loadedData ?: return
        // Never write the old shots into another end.
        if (d.currentEnd.end.id != u.endId) return
        host.enterShots(u.shots, u.exact)
    }

    private fun showOtherEnd(data: LoaderResult?, endId: Long) {
        val number = data?.training?.rounds
            ?.flatMap { it.ends }
            ?.firstOrNull { it.end.id == endId }
            ?.end?.index?.plus(1)
        val text = if (number != null) {
            activity.getString(R.string.scan_other_end, number)
        } else {
            activity.getString(R.string.scan_end_gone)
        }
        Snackbar.make(host.messageAnchor, text, Snackbar.LENGTH_LONG).show()
    }

    private fun show(message: ScanMessage, targetName: String = "") {
        val (text, action) = when (message) {
            is ScanMessage.Complete ->
                activity.resources.getQuantityString(R.plurals.scan_complete, message.found, message.found) to Action.UNDO
            is ScanMessage.Fewer ->
                activity.getString(R.string.scan_fewer, message.found, message.expected) to Action.UNDO
            is ScanMessage.Uncertain -> activity.getString(
                if (message.reason == SelectionReason.SPOT_OVERFLOW) R.string.scan_spot_overflow else R.string.scan_ambiguous,
                message.sure
            ) to Action.UNDO
            ScanMessage.NoArrows -> activity.getString(R.string.scan_no_arrows) to Action.RETAKE
            ScanMessage.FaceNotFound -> activity.getString(R.string.scan_face_not_found) to Action.RETAKE
            ScanMessage.FaceMismatch -> activity.getString(R.string.scan_face_mismatch, targetName) to null
            ScanMessage.PhotoUnreadable -> activity.getString(R.string.scan_photo_unreadable) to null
            is ScanMessage.Failed -> {
                Timber.e(message.cause, "The scan failed")
                activity.getString(R.string.scan_failed) to null
            }
            ScanMessage.Unsupported -> {
                Timber.w("A scan ran for a face that is not supported")
                return
            }
        }
        val bar = Snackbar.make(host.messageAnchor, text, Snackbar.LENGTH_LONG)
        when (action) {
            Action.UNDO -> {
                bar.setAction(R.string.undo) { undoScan() }
                bar.duration = ACTION_DURATION_MS
                undoBar = bar
            }
            // Nothing was entered, so no question again; an earlier "replace" still stands.
            Action.RETAKE -> {
                bar.setAction(R.string.scan_retake) { requestCamera() }
                bar.duration = ACTION_DURATION_MS
            }
            null -> Unit
        }
        bar.show()
    }

    private companion object {
        const val KEY_PENDING_SCAN = "pending_scan"

        /**
         * A message with an action stays long enough to act on it; LENGTH_LONG is
         * under three seconds. Not indefinite: the bar covers the buttons below.
         */
        const val ACTION_DURATION_MS = 8_000

        /** One attach at a time in the whole process, see [attach]. */
        val STORE_LOCK = Mutex()
    }
}
