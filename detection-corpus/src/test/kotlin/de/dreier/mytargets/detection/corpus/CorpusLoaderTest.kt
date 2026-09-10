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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CorpusLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun write(relative: String, content: String = "") {
        val file = File(folder.root, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun sidecar(model: String = "WAFull", shots: String = "[]") = """
        {
          "image": { "file": "x.jpg", "width": 100, "height": 100 },
          "capture": { "lighting": "bedeckt", "angle": "frontal" },
          "target": { "model": "$model", "faceCount": 1 },
          "shots": $shots
        }
    """.trimIndent()

    @Test
    fun readsTruthFromFileNames() {
        write("a6_998877.jpg")
        write("a8_xxx99988_front.jpg")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName })
            .containsExactly("a6_998877.jpg", "a8_xxx99988_front.jpg")
        assertThat(result.ignored).isEmpty()
        assertThat(result.orphanSidecars).isEmpty()
    }

    @Test
    fun theInheritedMarkerBecomesATag() {
        write("a6_x99765_noise.jpg")
        val entry = CorpusLoader.load(folder.root).entries.single()
        assertThat(entry.tags).containsExactly("noise")
    }

    @Test
    fun aSidecarIsFoundBesideTheImageUnderTheSameBaseName() {
        write("2026-06-06_sonne_leicht-schraeg_01.jpg")
        write(
            "2026-06-06_sonne_leicht-schraeg_01.json",
            sidecar(shots = """[ { "faceIndex": 0, "x": 0.1, "y": 0.0, "scoringRing": 2 } ]""")
        )

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.expectedShots).isEqualTo(1)
        assertThat(entry.target!!.model).isEqualTo("WAFull")
        assertThat(entry.tags).containsExactly("bedeckt", "frontal")
    }

    @Test
    fun aSidecarsCaptureBlockIsNotOverwrittenByTheFileNameTag() {
        // The inherited scheme's tag becomes a capture-based tag only when
        // the entry has no capture block of its own. Pairing an
        // inherited-looking file name with a sidecar that DOES carry a real
        // capture block -- a pairing nothing else in this suite creates --
        // must keep that real capture, not have it replaced by the file
        // name's marker.
        write("a6_998877_noise.jpg")
        write(
            "a6_998877_noise.json",
            sidecar(shots = """[ { "faceIndex": 0, "x": 0.0, "y": 0.0, "scoringRing": 0 } ]""")
        )

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.tags).containsExactly("bedeckt", "frontal")
        assertThat(entry.tags).doesNotContain("noise")
    }

    @Test
    fun aSidecarWinsOverTheFileName() {
        write("a6_998877.jpg")
        write(
            "a6_998877.json",
            sidecar(shots = """[ { "faceIndex": 0, "x": 0.0, "y": 0.0, "scoringRing": 0 } ]""")
        )

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.expectedShots).isEqualTo(1)
        assertThat(entry.shots[0].scoringRing).isEqualTo(0)
        assertThat(entry.target).isNotNull()
    }

    @Test
    fun aRegistrationOnlyEntryIsLoadedRatherThanRejected() {
        write("reg.jpg")
        write("reg.json", sidecar())

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.isAnnotated).isFalse()
        assertThat(entry.expectedShots).isEqualTo(0)
    }

    @Test
    fun anOrphanSidecarIsReportedRatherThanSilentlyUnused() {
        // The natural mistake, and the one that would make a hand annotation
        // silently have no effect at all.
        write("a6_998877.jpg")
        write("typo.json", sidecar())

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries).hasSize(1)
        assertThat(result.orphanSidecars).containsExactly("typo.json")
    }

    @Test
    fun anOrphanSidecarDoesNotBecomeAnEntry() {
        // Stronger than the assertion above: an orphan reported but also
        // smuggled into entries would still corrupt the metrics silently.
        write("a6_998877.jpg")
        write("typo.json", sidecar())

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName }).containsExactly("a6_998877.jpg")
    }

    @Test
    fun unrecognisedImagesAreReportedRatherThanFatal() {
        write("a6_998877.jpg")
        write("holiday.jpg")
        write("notes.txt")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries).hasSize(1)
        assertThat(result.ignored).containsExactly("holiday.jpg")
    }

    @Test
    fun unrecognisedImageDoesNotThrowAndIsNotTreatedAsAnEntry() {
        // Stronger than the assertion above: a loader that threw, or one that
        // quietly turned the unrecognised image into an entry with nulled-out
        // truth, would still satisfy "hasSize(1)" / "containsExactly" above by
        // accident if entries and ignored were mixed up. Pin both facts down.
        write("holiday.jpg")

        val result = runCatching { CorpusLoader.load(folder.root) }

        assertThat(result.isSuccess).isTrue()
        val loaded = result.getOrThrow()
        assertThat(loaded.entries).isEmpty()
        assertThat(loaded.ignored).containsExactly("holiday.jpg")
    }

    @Test
    fun subdirectoriesAreRead() {
        write("wa-full/2026-06-06_sonne_frontal_01.jpg")
        write("wa-full/2026-06-06_sonne_frontal_01.json", sidecar())
        write("inherited-249/a6_998877.jpg")

        val names = CorpusLoader.load(folder.root).entries.map { it.imageName }

        assertThat(names).hasSize(2)
    }

    @Test
    fun aBrokenSidecarNamesItsFile() {
        write("a6_998877.jpg")
        write("a6_998877.json", "{ not json")

        val error = runCatching { CorpusLoader.load(folder.root) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("a6_998877.jpg")
    }

    @Test
    fun aMissingRootYieldsAnEmptyResultRatherThanAnError() {
        val result = CorpusLoader.load(File(folder.root, "nope"))
        assertThat(result.entries).isEmpty()
        assertThat(result.ignored).isEmpty()
        assertThat(result.orphanSidecars).isEmpty()
    }

    @Test
    fun anEntryListedInOutOfScopeIsMarkedWithItsReason() {
        write("a6_998877.jpg")
        write(
            "out-of-scope.json",
            """{ "a6_998877.jpg": "Drei Auflagen nebeneinander." }"""
        )

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.outOfScope).isEqualTo("Drei Auflagen nebeneinander.")
    }

    @Test
    fun anEntryNotListedInOutOfScopeIsNotMarked() {
        write("a6_998877.jpg")
        write("out-of-scope.json", """{}""")

        val entry = CorpusLoader.load(folder.root).entries.single()

        assertThat(entry.outOfScope).isNull()
    }

    @Test
    fun outOfScopeStillLoadsAsAPhotograph() {
        write("a6_998877.jpg")
        write(
            "out-of-scope.json",
            """{ "a6_998877.jpg": "not covered by v1" }"""
        )

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName }).containsExactly("a6_998877.jpg")
        assertThat(result.ignored).isEmpty()
    }

    @Test
    fun outOfScopeJsonIsNotReportedAsAnOrphanSidecar() {
        // out-of-scope.json is a .json at the root with no image beside it --
        // exactly the shape the orphan check looks for -- but it is the
        // mechanism itself, not a mistake.
        write("a6_998877.jpg")
        write(
            "out-of-scope.json",
            """{ "a6_998877.jpg": "not covered by v1" }"""
        )

        val result = CorpusLoader.load(folder.root)

        assertThat(result.orphanSidecars).isEmpty()
    }

    @Test
    fun anOutOfScopeEntryWithNoMatchingImageIsAnError() {
        write("a6_998877.jpg")
        write(
            "out-of-scope.json",
            """{ "typo-name.jpg": "not covered by v1" }"""
        )

        val error = runCatching { CorpusLoader.load(folder.root) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("typo-name.jpg")
        assertThat(error).hasMessageThat().contains("out-of-scope.json")
    }

    @Test
    fun aBrokenOutOfScopeFileNamesItself() {
        write("a6_998877.jpg")
        write("out-of-scope.json", "{ not json")

        val error = runCatching { CorpusLoader.load(folder.root) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("out-of-scope.json")
    }

    @Test
    fun aSubdirectorysOutOfScopeNamedFileIsStillAnOrdinarySidecarCheck() {
        // The mechanism lives at the corpus ROOT only. A file that happens to
        // be named the same inside a subdirectory is an ordinary sidecar, and
        // an ordinary one with no image beside it is still an orphan.
        write("wa-full/a6_998877.jpg")
        write("wa-full/out-of-scope.json", """{ "a6_998877.jpg": "x" }""")

        val error = runCatching { CorpusLoader.load(folder.root) }.exceptionOrNull()
        assertThat(error).isNull()
        val result = CorpusLoader.load(folder.root)
        assertThat(result.orphanSidecars).containsExactly("out-of-scope.json")
    }

    @Test
    fun everyListComesBackInAStableOrder() {
        write("a6_x99976.jpg")
        write("a6_998877.jpg")
        write("zzz.jpg")
        write("aaa.jpg")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName }).isInOrder()
        assertThat(result.ignored).isInOrder()
        assertThat(result.ignored).containsExactly("aaa.jpg", "zzz.jpg").inOrder()
    }

    @Test
    fun anOutOfScopePhotographThatCannotBeReadIsNotReportedAsMissing() {
        // A photograph of an unsupported face, listed before anyone wrote its
        // sidecar: it exists, it merely cannot be read, and covering such a
        // photograph is what out-of-scope.json is for. It stays in ignored and
        // the load goes on.
        write("unsupported-face.jpg")
        write("out-of-scope.json", """{ "unsupported-face.jpg": "not covered by v1" }""")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.ignored).containsExactly("unsupported-face.jpg")
    }

    @Test
    fun theSameImageNameInTwoFoldersIsAnError() {
        // The naming scheme carries no face type, so a WAFull and a 3-spot
        // photograph taken on the same day under the same light and angle get
        // the same name. Entries, out-of-scope.json and the report identify a
        // photograph by that name alone, so the two could not be told apart.
        write("wa-full/2026-10-01_halle_frontal_01.jpg")
        write("wa-full/2026-10-01_halle_frontal_01.json", sidecar())
        write("wa-3spot-vertikal/2026-10-01_halle_frontal_01.jpg")
        write("wa-3spot-vertikal/2026-10-01_halle_frontal_01.json", sidecar())

        val error = runCatching { CorpusLoader.load(folder.root) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("2026-10-01_halle_frontal_01.jpg")
        assertThat(error).hasMessageThat().contains("wa-full")
        assertThat(error).hasMessageThat().contains("wa-3spot-vertikal")
    }

    @Test
    fun aSidecarMustMatchItsImageNameExactlyIncludingCase() {
        // Windows finds photo.json for Photo.jpg and Linux does not. Matching
        // the name exactly makes both behave like Linux: the photograph has no
        // sidecar, and the sidecar is reported as the misspelling it is.
        write("Photo.jpg")
        write(
            "photo.json",
            sidecar(shots = """[ { "faceIndex": 0, "x": 0.0, "y": 0.0, "scoringRing": 0 } ]""")
        )

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries).isEmpty()
        assertThat(result.ignored).containsExactly("Photo.jpg")
        assertThat(result.orphanSidecars).containsExactly("photo.json")
    }
}
