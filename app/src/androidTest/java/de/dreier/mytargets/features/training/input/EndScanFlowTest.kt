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
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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
}
