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

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.features.settings.SettingsManager
import de.dreier.mytargets.shared.models.Dimension
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.models.db.End
import de.dreier.mytargets.shared.models.db.Round
import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.shared.targets.models.WAFull
import de.dreier.mytargets.shared.views.TargetViewBase
import de.dreier.mytargets.test.utils.rules.DbTestRuleBase
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.Random

/**
 * The scan from the end input (app integration 8b) in the real activity, with
 * the camera stubbed by espresso-intents. The full scan needs the corpus photo
 * (README next to the assets); without it those tests skip themselves.
 */
@RunWith(AndroidJUnit4::class)
class EndScanFlowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val endDAO get() = ApplicationInstance.db.endDAO()
    private lateinit var round: Round

    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule
    val db = object : DbTestRuleBase() {
        override fun addDatabaseContent() {
            val training = saveDefaultTraining(null, Random(8))
            round = Round(
                trainingId = training.id,
                index = 0,
                shotsPerEnd = 6,
                maxEndCount = 3,
                distance = Dimension(18f, Dimension.Unit.METER),
                target = Target(WAFull.ID, 0)
            )
            round.id = ApplicationInstance.db.roundDAO().insertRound(round)
        }
    }

    @Before
    fun setUp() {
        // With the keyboard setting, "shown as plot" is the new rule at work, not the default.
        SettingsManager.inputMethod = TargetViewBase.EInputMethod.KEYBOARD
        SettingsManager.timerEnabled = false
        Intents.init()
    }

    @After
    fun tearDown() = Intents.release()

    @Test
    fun aCancelledCameraLeavesNothing() {
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        launch().use {
            scanFromMenu()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            val end = endDAO.loadEnds(round.id).single()
            assertThat(endDAO.loadEndImages(end.id)).isEmpty()
            assertThat(endDAO.loadShots(end.id).all { it.scoringRing == Shot.NOTHING_SELECTED }).isTrue()
            assertThat(cameraFiles()).isEmpty()
        }
    }

    @Test
    fun decliningToReplaceTakesNoPhoto() {
        val shots = (0 until 6).map { i -> Shot(i).apply { if (i < 2) scoringRing = 0 } }
        endDAO.insertCompleteEnd(End(index = 0, roundId = round.id), emptyList(), shots)
        launch().use {
            scanFromMenu()
            onView(withText(android.R.string.cancel)).perform(click())

            intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE), times(0))
        }
    }

    @Test
    fun aScannedEndLandsInTheDatabaseWithItsPhoto() {
        cameraDelivers(corpusPhoto())
        launch().use { scenario ->
            scanFromMenu()

            val end = waitFor(60_000) { scannedEnd() }
            val shots = endDAO.loadShots(end.id)
            // The corpus photo has five arrows (EndPhotoScannerDeviceTest asserts all five); the round expects six.
            assertThat(shots.count { it.scoringRing != Shot.NOTHING_SELECTED }).isEqualTo(5)
            assertThat(shots).hasSize(6)
            assertThat(end.exact).isTrue()
            val images = endDAO.loadEndImages(end.id)
            assertThat(images).hasSize(1)
            assertThat(File(context.filesDir, images[0].fileName).exists()).isTrue()
            assertThat(cameraFiles()).isEmpty()
            scenario.onActivity {
                assertThat(it.findViewById<TargetView>(R.id.targetViewContainer).inputMode)
                    .isEqualTo(TargetViewBase.EInputMethod.PLOTTING)
                assertThat(it.data!!.currentEnd.images.map { image -> image.fileName }).containsExactly(images[0].fileName)
            }
        }
    }

    @Test
    fun undoIsGoneAfterMovingToTheNextEnd() {
        cameraDelivers(corpusPhoto())
        launch().use { scenario ->
            scanFromMenu()
            waitFor(60_000) { scannedEnd() }

            scenario.onActivity { it.findViewById<android.view.View>(R.id.next).performClick() }

            // dismiss() animates out; the view is removed a moment later.
            waitFor(5_000) { runCatching { onView(withText(R.string.undo)).check(doesNotExist()) }.getOrNull() }
            val second = waitFor(10_000) { endDAO.loadEnds(round.id).getOrNull(1) }
            assertThat(endDAO.loadShots(second.id).all { it.scoringRing == Shot.NOTHING_SELECTED }).isTrue()
        }
    }

    @Test
    fun undoRestoresTheShotsButKeepsThePhoto() {
        cameraDelivers(corpusPhoto())
        launch().use { scenario ->
            scanFromMenu()
            val scanned = waitFor(60_000) { scannedEnd() }

            // Espresso does not wait for the snackbar's slide-in; a click during it misses the action.
            waitFor(10_000) { snackbarSettled(scenario, R.string.undo).takeIf { it } }
            waitFor(10_000) { runCatching { onView(withText(R.string.undo)).perform(click()) }.getOrNull() }

            waitFor(10_000) {
                endDAO.loadShots(scanned.id).all { it.scoringRing == Shot.NOTHING_SELECTED }.takeIf { it }
            }
            // The fresh end was created with the keyboard setting, so it was not exact before the scan.
            val end = endDAO.loadEnds(round.id).single { it.id == scanned.id }
            assertThat(end.exact).isFalse()
            assertThat(endDAO.loadEndImages(end.id)).hasSize(1)
        }
    }

    @Test
    fun aCameraThatWritesNothingEntersNothing() {
        // RESULT_OK, but EasyImage's file stays empty.
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        launch().use {
            scanFromMenu()

            // The scan job runs outside Espresso's idling; poll for its message.
            waitFor(30_000) { runCatching { onView(withText(R.string.scan_photo_unreadable)).check(matches(isDisplayed())) }.getOrNull() }
            val end = endDAO.loadEnds(round.id).single()
            assertThat(endDAO.loadEndImages(end.id)).isEmpty()
            assertThat(endDAO.loadShots(end.id).all { it.scoringRing == Shot.NOTHING_SELECTED }).isTrue()
            waitFor(10_000) { cameraFiles().isEmpty().takeIf { it } }
        }
    }

    // --- helpers, shared with Task 7 ---

    private fun launch(): ActivityScenario<InputActivity> {
        val scenario = ActivityScenario.launch<InputActivity>(Intent(context, InputActivity::class.java).apply {
            putExtra(InputActivity.TRAINING_ID, round.trainingId)
            putExtra(InputActivity.ROUND_ID, round.id)
            putExtra(InputActivity.END_INDEX, 0)
        })
        waitFor(10_000) { var loaded = false; scenario.onActivity { loaded = it.data != null }; loaded.takeIf { it } }
        return scenario
    }

    private fun scanFromMenu() {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.scan_end)).perform(click())
    }

    /** What EasyImage left in its camera directory. */
    private fun cameraFiles(): List<File> =
        File(context.cacheDir, "EasyImage").listFiles().orEmpty().filter { it.extension == "jpg" }

    private fun <T : Any> waitFor(timeoutMs: Long, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            probe()?.let { return it }
            check(SystemClock.elapsedRealtime() < deadline) { "timed out after $timeoutMs ms" }
            SystemClock.sleep(200)
        }
    }

    /** The view with [text] is on screen and neither it nor a parent is still moving or fading in. */
    private fun snackbarSettled(scenario: ActivityScenario<InputActivity>, text: Int): Boolean {
        var settled = false
        scenario.onActivity { activity ->
            val found = ArrayList<android.view.View>()
            activity.window.decorView.findViewsWithText(found, activity.getString(text), android.view.View.FIND_VIEWS_WITH_TEXT)
            var view: android.view.View? = found.singleOrNull()?.takeIf { it.isShown }
            settled = view != null
            while (view != null) {
                if (view.translationY != 0f || view.alpha != 1f) settled = false
                view = view.parent as? android.view.View
            }
        }
        return settled
    }

    /** The first end once the scan's shots are persisted and the original is gone. */
    private fun scannedEnd(): End? {
        val end = endDAO.loadEnds(round.id).firstOrNull() ?: return null
        val entered = endDAO.loadShots(end.id).any { it.scoringRing != Shot.NOTHING_SELECTED }
        return end.takeIf { entered && cameraFiles().isEmpty() }
    }

    /** The camera "takes" [photo]: copied into EasyImage's EXTRA_OUTPUT, then RESULT_OK. */
    private fun cameraDelivers(photo: File) {
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWithFunction { intent ->
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
            context.contentResolver.openOutputStream(uri)!!.use { out -> photo.inputStream().use { it.copyTo(out) } }
            Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }

    private fun corpusPhoto(): File {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val file = File(context.cacheDir, "$VIEW.jpg")
        try {
            testAssets.open("scan/$VIEW.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            assumeTrue("scan/$VIEW.jpg is not in the test assets, see app/src/androidTest/README.md", false)
        }
        return file
    }

    private companion object {
        const val VIEW = "2026-08-15_bedeckt_frontal_02"
    }
}
