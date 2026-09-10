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

package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.metrics.EntryOutcome
import de.dreier.mytargets.detection.metrics.Metrics
import de.dreier.mytargets.detection.metrics.ShotMatching
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Reads the whole corpus, when there is one.
 *
 * Deliberately loads the ROOT, not a subfolder. The previous version of this
 * test loaded only `inherited-249`, which is why nobody noticed that every
 * annotated photograph was being dropped.
 */
class RealCorpusTest {

    private lateinit var root: File
    private lateinit var result: CorpusLoader.LoadResult

    @Before
    fun requireCorpus() {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)
        result = CorpusLoader.load(root)
    }

    @Test
    fun everyPhotographInTheCorpusIsUnderstood() {
        assertThat(result.ignored).isEmpty()
        assertThat(result.orphanSidecars).isEmpty()
        assertThat(result.entries).isNotEmpty()

        // Stronger than the brief's isNotEmpty(): the failure this task exists
        // to catch loaded 16 entries perfectly happily while dropping the four
        // annotated wa-full photographs, so an isNotEmpty() assertion would
        // have passed for it too. Pin the exact total (16 inherited + 4
        // wa-full, at the corpus state observed on 2026-09-09) and name the
        // four annotated files explicitly, so a loader that quietly reverted
        // to reading only a subfolder fails here instead of nowhere.
        assertThat(result.entries).hasSize(20)
        assertThat(result.entries.map { it.imageName }).containsAtLeast(
            "2026-06-06_sonne_leicht-schraeg_01.jpg",
            "2026-08-04_sonne_stark-schraeg_01.jpg",
            "2026-08-15_bedeckt_frontal_02.jpg",
            "2026-08-15_bedeckt_stark-schraeg_01.jpg"
        )
    }

    @Test
    fun theAnnotatedPhotographsAreRead() {
        val annotated = result.entries.filter { it.target != null }

        assertThat(annotated).isNotEmpty()

        // Stronger than the brief's isNotEmpty(): pins the exact count, so a
        // loader that finds only some of the annotated photographs (rather
        // than none) still fails visibly.
        assertThat(annotated).hasSize(4)

        assertThat(annotated.all { it.isAnnotated }).isTrue()
        assertThat(annotated.all { it.hasPositions }).isTrue()
        assertThat(annotated.all { it.shots.all { s -> s.scoringRing != null } }).isTrue()
        assertThat(annotated.all { it.camera?.focalLength35mm != null }).isTrue()
        assertThat(annotated.all { it.registration != null }).isTrue()
    }

    @Test
    fun noAnnotatedEntryClaimsMoreArrowsThanWereShot() {
        // The defensible invariant, and only this one. Equality does NOT hold
        // across the corpus: 2026-08-15_bedeckt_frontal_02 lists five hits with
        // none unresolved against a six arrow end. Either five arrows were shot
        // or a sixth is unaccounted for; that is the annotator's call, not this
        // test's, so the test asserts what must always be true and the report
        // below names the entries where the two disagree.
        for (entry in result.entries.filter { it.target != null }) {
            val perEnd = entry.shotsPerEnd
            assertThat(perEnd).isNotNull()
            assertThat(entry.expectedShots + entry.unresolvedArrows)
                .isAtMost(perEnd!!)
        }
    }

    @Test
    fun entriesThatDoNotAccountForTheirWholeEndAreListed() {
        // Not a failure — a visible list, so an annotation slip does not hide.
        // The rule every annotated entry must obey is
        // `listed + unresolved <= shotsPerEnd`; this asserts exactly that,
        // for every one of them, and separately prints whichever entries fall
        // strictly short of it. The corpus may legitimately have none such --
        // it does, as of 2026-09-09 -- so this must never assert that a
        // shortfall exists, only that the rule itself holds.
        val annotated = result.entries.filter { it.target != null && it.shotsPerEnd != null }

        val incomplete = annotated.filter {
            it.expectedShots + it.unresolvedArrows < it.shotsPerEnd!!
        }
        println("Entries short of their shotsPerEnd:")
        incomplete.forEach {
            println(
                "  ${it.imageName}: ${it.expectedShots} listed + " +
                    "${it.unresolvedArrows} unresolved of ${it.shotsPerEnd}"
            )
        }

        for (entry in annotated) {
            assertThat(entry.expectedShots + entry.unresolvedArrows)
                .isAtMost(entry.shotsPerEnd!!)
        }
    }

    @Test
    fun theInheritedPhotographsStillLoadAsRingOnlyTruth() {
        val inherited = result.entries.filter { it.target == null }

        assertThat(inherited).hasSize(16)
        assertThat(inherited.all { !it.hasPositions }).isTrue()
        assertThat(inherited.all { it.shots.all { s -> s.printedScore != null } }).isTrue()
    }

    @Test
    fun aKnownInheritedEntryHasTheScoresItsNamePromises() {
        val entry = result.entries.single { it.imageName == "a8_xxx99988_front.jpg" }

        assertThat(entry.shots.map { it.printedScore!!.text })
            .containsExactly("X", "X", "X", "9", "9", "9", "8", "8").inOrder()
        assertThat(entry.tags).containsExactly("front")
    }

    @Test
    fun outOfScopePhotographsLoadButAreExcludedFromTheMetrics() {
        // Not hard-coding the corpus's total size, which grows: this only
        // asserts that whatever out-of-scope entries the corpus currently
        // lists (at least the one named in the spec) load, are marked, and
        // leave the aggregate metrics exactly as they would read without
        // them at all.
        val outOfScope = result.entries.filter { it.outOfScope != null }
        assertThat(outOfScope).isNotEmpty()
        assertThat(outOfScope.map { it.imageName })
            .contains("a6_x99999_multiple_targets.jpg")

        val outcomes = result.entries.map { entry ->
            EntryOutcome(entry, ShotMatching.match(entry, emptyList()), emptyList())
        }
        val overall = Metrics.over(outcomes)
        val withoutOutOfScope = Metrics.over(outcomes.filter { it.entry.outOfScope == null })

        assertThat(overall.expectedShots).isEqualTo(withoutOutOfScope.expectedShots)
        assertThat(overall.annotatedEntries).isEqualTo(withoutOutOfScope.annotatedEntries)
        assertThat(overall.falsePositiveDenominator)
            .isEqualTo(withoutOutOfScope.falsePositiveDenominator)
    }

    @Test
    fun everyPositionLiesOnItsFace() {
        // Spot local coordinates put the outermost ring at radius one, so a
        // hit outside that would mean the annotation or the coordinate system
        // is wrong.
        for (entry in result.entries) {
            for (shot in entry.shots) {
                val p = shot.position ?: continue
                val radius = kotlin.math.hypot(p.x, p.y)
                assertThat(radius).isLessThan(1.0)
            }
        }
    }
}
