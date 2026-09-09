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

import java.io.File

/**
 * Reads a corpus directory into entries.
 *
 * A photograph takes its truth from a sidecar `<base>.json` beside it, and
 * otherwise from the inherited file name scheme. An image neither can read is
 * reported in [LoadResult.ignored] rather than aborting: a corpus grows by
 * dropping photographs into a folder, and one holiday snap must not stop a
 * measurement.
 *
 * A broken sidecar IS fatal, because someone stated the truth and got it wrong,
 * and ignoring that would quietly drop an annotated photograph out of the
 * metrics. A sidecar with no matching image is reported in
 * [LoadResult.orphanSidecars] — it is almost always a misspelt name, and
 * silently ignoring it would make a hand annotation have no effect at all.
 */
object CorpusLoader {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

    class LoadResult(
        val entries: List<CorpusEntry>,
        val ignored: List<String>,
        val orphanSidecars: List<String>
    )

    fun load(root: File): LoadResult {
        if (!root.isDirectory) {
            return LoadResult(emptyList(), emptyList(), emptyList())
        }
        val entries = mutableListOf<CorpusEntry>()
        val ignored = mutableListOf<String>()
        val orphans = mutableListOf<String>()
        loadDirectory(root, entries, ignored, orphans)
        return LoadResult(
            entries.sortedBy { it.imageName },
            ignored.sorted(),
            orphans.sorted()
        )
    }

    private fun loadDirectory(
        directory: File,
        entries: MutableList<CorpusEntry>,
        ignored: MutableList<String>,
        orphans: MutableList<String>
    ) {
        val children = directory.listFiles() ?: return
        val images = children.filter { it.isFile && it.isImage() }
        val usedSidecars = mutableSetOf<String>()

        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                loadDirectory(child, entries, ignored, orphans)
            }
        }

        for (image in images.sortedBy { it.name }) {
            val sidecar = File(directory, image.nameWithoutExtension + ".json")
            val entry = if (sidecar.isFile) {
                usedSidecars.add(sidecar.name)
                SidecarTruth.parse(image.name, sidecar.readText())
            } else {
                FilenameTruth.parse(image.name)
            }

            if (entry == null) {
                ignored.add(image.name)
            } else {
                entries.add(withInheritedTag(entry))
            }
        }

        for (child in children) {
            if (child.isFile && child.extension.lowercase() == "json" &&
                child.name !in usedSidecars
            ) {
                orphans.add(child.name)
            }
        }
    }

    /**
     * The inherited scheme's difficulty marker becomes a tag, so those
     * photographs can still be grouped by difficulty even though they carry no
     * capture block.
     */
    private fun withInheritedTag(entry: CorpusEntry): CorpusEntry {
        if (entry.capture != null) return entry
        val tag = FilenameTruth.tagOf(entry.imageName) ?: return entry
        return entry.copy(capture = CaptureInfo(lighting = tag, angle = null, angleDegrees = null))
    }

    private fun File.isImage(): Boolean = extension.lowercase() in IMAGE_EXTENSIONS
}
