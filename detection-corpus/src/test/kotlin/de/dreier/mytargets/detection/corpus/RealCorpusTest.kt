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
import com.google.common.truth.Truth.assertWithMessage
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
        // have passed for it too. Pin the exact total (16 inherited + 16
        // wa-full + 2 placeholder photographs of a 3-spot face, at the corpus
        // state observed on 2026-09-11) and name files from three folders
        // explicitly, so a loader that quietly reverted to reading only a
        // subfolder fails here instead of nowhere.
        assertThat(result.entries).hasSize(34)
        assertThat(result.entries.map { it.imageName }).containsAtLeast(
            "2026-06-06_sonne_leicht-schraeg_01.jpg",
            "2026-08-04_sonne_stark-schraeg_01.jpg",
            "2026-08-15_bedeckt_frontal_02.jpg",
            "2026-08-15_bedeckt_stark-schraeg_01.jpg",
            "2026-09-10_bedeckt_frontal_01.jpg",
            "2026-09-10_bedeckt_leicht-schraeg_07.jpg",
            "a6_877652.jpg"
        )
    }

    @Test
    fun theAnnotatedPhotographsAreRead() {
        val annotated = result.entries.filter { it.target != null }

        assertThat(annotated).isNotEmpty()

        // Pins the exact count, so a loader that finds only some of the
        // annotated photographs (rather than none) still fails visibly. Since
        // 2026-09-10 every photograph in the corpus carries a sidecar; the two
        // 3-spot placeholders (wa-3spot-vertikal, 2026-09-11) deliberately
        // carry no target block, because the tools cannot register that face
        // yet, so they are entries but not annotated ones: 16 wa-full plus 16
        // inherited.
        assertThat(annotated).hasSize(32)
        assertThat(result.entries.count { it.target == null }).isEqualTo(2)

        assertThat(annotated.all { it.isAnnotated }).isTrue()
        // Not `hasPositions`, which change 4 retired: requiring every listed
        // shot to carry a position would fail the day a photograph like the
        // owner's (a shaft hidden behind another, entry point unplaceable)
        // is added -- exactly the case change 4 exists to support; see
        // SidecarTruthTest for that scenario read directly. What holds today,
        // and must keep holding, is that the annotated photographs actually
        // carry the positions the truth records: every one of them has at
        // least one positioned shot, and reading a mix of positioned and
        // unpositioned shots in the same entry is not an error.
        assertThat(annotated.all { it.shots.any { s -> s.position != null } }).isTrue()
        assertThat(annotated.all { it.shots.all { s -> s.scoringRing != null } }).isTrue()
        assertThat(annotated.all { it.registration != null }).isTrue()
        // Five inherited photographs (the four WA6Ring ones and the hall shot)
        // have no EXIF at all; every other photograph names its focal length.
        assertThat(annotated.count { it.camera?.focalLength35mm != null }).isEqualTo(27)
    }

    @Test
    fun noAnnotatedEntryClaimsMoreArrowsThanWereShot() {
        // The defensible invariant, and only this one. Equality does NOT hold
        // across the corpus: 2026-08-15_bedeckt_frontal_02 lists five hits and
        // declares one unresolved -- a sixth arrow whose shaft disappears
        // behind another, entry point unplaceable -- against a six arrow end.
        // That five-plus-one is the annotator's call, not this test's, so the
        // test asserts what must always be true and the report below names
        // the entries where the two disagree.
        for (entry in result.entries.filter { it.target != null }) {
            val perEnd = entry.shotsPerEnd
            assertThat(perEnd).isNotNull()
            assertThat(entry.expectedShots + entry.unresolvedArrows)
                .isAtMost(perEnd!!)
        }
    }

    @Test
    fun entriesThatDoNotAccountForTheirWholeEndAreListed() {
        // A shortfall -- `listed + unresolved < shotsPerEnd` -- is the
        // annotator's own call to leave an arrow entirely unaccounted for,
        // not necessarily wrong (see noAnnotatedEntryClaimsMoreArrowsThanWereShot
        // above), but it must never go unnoticed. The corpus has none as of
        // 2026-09-09, so this asserts the list is EMPTY, with a message that
        // names any offender: a new sidecar that quietly drops a hit without
        // declaring it unresolved must fail loudly here, not pass silently
        // the way an unconditional `<= shotsPerEnd` loop would. Raise this
        // deliberately if more such entries are legitimately added; do not
        // delete this assertion.
        val annotated = result.entries.filter { it.target != null && it.shotsPerEnd != null }

        val incomplete = annotated.filter {
            it.expectedShots + it.unresolvedArrows < it.shotsPerEnd!!
        }

        assertWithMessage(
            "entries short of their shotsPerEnd: " +
                incomplete.joinToString {
                    "${it.imageName} (${it.expectedShots} listed + " +
                        "${it.unresolvedArrows} unresolved of ${it.shotsPerEnd})"
                }
        ).that(incomplete).isEmpty()
    }

    @Test
    fun theInheritedPhotographsCarryPositionsAndKeepTheirPrintedScores() {
        // Since 2026-09-10 the 16 inherited photographs have sidecars with
        // positions and zone indices, registered and annotated with the
        // corpus tools. The file name is no longer the truth, but it is still
        // a check on it: each sidecar shot also records its printed value.
        val inherited = result.entries.filter { FilenameTruth.parse(it.imageName) != null }

        assertThat(inherited).hasSize(16)
        // Not `hasPositions`, which change 4 retired -- see
        // theAnnotatedPhotographsAreRead above for why.
        assertThat(inherited.all { it.isAnnotated && it.shots.all { s -> s.position != null } })
            .isTrue()
        assertThat(inherited.all { it.registration != null }).isTrue()
        assertThat(inherited.all { it.shots.all { s -> s.scoringRing != null && s.printedScore != null } }).isTrue()
        assertThat(inherited.map { it.shots.size }).containsExactlyElementsIn(
            inherited.map { it.shotsPerEnd }
        )
    }

    @Test
    fun theInheritedSidecarsAgreeWithTheirFileNamesExceptWhereDocumented() {
        // The scheme writes X and a plain 10 both as "x", so the comparison is
        // by points. A hit within its position tolerance of a ring line may
        // match either neighbouring value: the scorer in 2017 gave a line
        // cutter the higher ring, the sidecar gives the pure radius. Three
        // photographs disagree beyond that; each sidecar's annotation block
        // says why. A fourth disagreement means a sidecar changed without its
        // note, or a file name is wrong.
        fun points(score: PrintedScore): Int = if (score == PrintedScore.X) 10 else score.text.toIntOrNull() ?: 0

        val disagreeing = result.entries
            .filter { FilenameTruth.parse(it.imageName) != null }
            .filter { entry ->
                val wanted = FilenameTruth.parse(entry.imageName)!!.shots.map { points(it.printedScore!!) }.sorted()
                val got = entry.shots.map { points(it.printedScore!!) }
                // An X on a ring line is still worth ten either side of it,
                // so only a plain value gets the one-ring leeway.
                val near = entry.shots.map { it.nearRingBoundary && it.printedScore != PrintedScore.X }
                !assignable(got, near, wanted)
            }
            .map { it.imageName }

        println("Inherited sidecars whose printed scores contradict the file name:")
        disagreeing.forEach { println("  $it") }

        assertThat(disagreeing).containsExactly(
            "a6_998887_dark.jpg",
            "a6_x98887.jpg",
            "a6_x99999_multiple_targets.jpg"
        )
    }

    /**
     * Whether every value in [got] can be paired with one in [wanted], where a
     * shot flagged near a ring line may also count as one ring higher or lower.
     */
    private fun assignable(got: List<Int>, near: List<Boolean>, wanted: List<Int>): Boolean {
        if (got.size != wanted.size) return false
        fun go(i: Int, remaining: List<Int>): Boolean {
            if (i == got.size) return remaining.isEmpty()
            val options = if (near[i]) setOf(got[i], got[i] + 1, got[i] - 1) else setOf(got[i])
            return options.any { v ->
                v in remaining && go(i + 1, remaining.toMutableList().also { it.remove(v) })
            }
        }
        return go(0, wanted)
    }

    @Test
    fun aKnownInheritedEntryIsReadFromItsSidecar() {
        val entry = result.entries.single { it.imageName == "a8_xxx99988_front.jpg" }

        assertThat(entry.shots).hasSize(8)
        assertThat(entry.target!!.model).isEqualTo("WA6Ring")
        assertThat(entry.outOfScope).isNotNull()
        assertThat(entry.tags).containsExactly("halle", "frontal")
        // Not `hasPositions`, which change 4 retired -- see
        // theAnnotatedPhotographsAreRead above for why.
        assertThat(entry.isAnnotated && entry.shots.all { it.position != null }).isTrue()
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
