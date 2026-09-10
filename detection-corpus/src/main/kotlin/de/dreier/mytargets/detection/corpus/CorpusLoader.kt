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

import com.google.gson.Gson
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
 *
 * `out-of-scope.json` at the root names photographs that stay in the corpus
 * but out of the metrics — see [CorpusEntry.outOfScope]. It lives at the root
 * rather than beside an image because it also has to cover the inherited
 * photographs, which carry no sidecar at all. A listed photograph that cannot
 * be read stays in [LoadResult.ignored]; only a name with no image behind it
 * is an error.
 *
 * An image name has to be unique across all folders, because entries,
 * `out-of-scope.json` and the report identify a photograph by its name alone.
 * A second image of the same name is fatal.
 */
object CorpusLoader {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    private const val OUT_OF_SCOPE_FILE = "out-of-scope.json"

    private val gson = Gson()

    class LoadResult(
        val entries: List<CorpusEntry>,
        val ignored: List<String>,
        val orphanSidecars: List<String>
    )

    fun load(root: File): LoadResult {
        if (!root.isDirectory) {
            return LoadResult(emptyList(), emptyList(), emptyList())
        }
        val outOfScope = readOutOfScope(root)
        val entries = mutableListOf<CorpusEntry>()
        val ignored = mutableListOf<String>()
        val orphans = mutableListOf<String>()
        loadDirectory(
            root, entries, ignored, orphans, folderOfImage = mutableMapOf(),
            exemptFromOrphanCheck = setOf(OUT_OF_SCOPE_FILE)
        )

        val matchedNames = mutableSetOf<String>()
        val markedEntries = entries.map { entry ->
            val reason = outOfScope[entry.imageName] ?: return@map entry
            matchedNames.add(entry.imageName)
            entry.copy(outOfScope = reason)
        }
        val unmatched = (outOfScope.keys - matchedNames - ignored.toSet()).sorted()
        require(unmatched.isEmpty()) {
            "$OUT_OF_SCOPE_FILE: lists ${unmatched.joinToString()} with no matching image"
        }

        return LoadResult(
            markedEntries.sortedBy { it.imageName },
            ignored.sorted(),
            orphans.sorted()
        )
    }

    /**
     * Reads `out-of-scope.json` from [root], if it exists. Its value maps an
     * image file name to the reason it is excluded from the metrics.
     */
    private fun readOutOfScope(root: File): Map<String, String> {
        val file = File(root, OUT_OF_SCOPE_FILE)
        if (!file.isFile) return emptyMap()

        val parsed = try {
            gson.fromJson(file.readText(), Map::class.java)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("$OUT_OF_SCOPE_FILE: cannot read JSON", e)
        } ?: throw IllegalArgumentException("$OUT_OF_SCOPE_FILE: empty JSON")

        return parsed.entries.associate { (key, value) ->
            require(key is String && value is String) {
                "$OUT_OF_SCOPE_FILE: expected a name-to-reason mapping of strings"
            }
            key to value
        }
    }

    private fun loadDirectory(
        directory: File,
        entries: MutableList<CorpusEntry>,
        ignored: MutableList<String>,
        orphans: MutableList<String>,
        folderOfImage: MutableMap<String, File>,
        exemptFromOrphanCheck: Set<String> = emptySet()
    ) {
        val children = directory.listFiles() ?: return
        val images = children.filter { it.isFile && it.isImage() }
        val usedSidecars = mutableSetOf<String>()
        usedSidecars.addAll(exemptFromOrphanCheck)

        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                loadDirectory(child, entries, ignored, orphans, folderOfImage)
            }
        }

        for (image in images.sortedBy { it.name }) {
            val earlier = folderOfImage.putIfAbsent(image.name, directory)
            require(earlier == null) {
                "${image.name} appears in both ${earlier!!.path} and ${directory.path}; " +
                    "an image name has to be unique across the corpus"
            }

            // Found by exact name rather than by File.isFile, which ignores
            // case on Windows: it would pair photo.json with Photo.jpg there
            // and not on Linux.
            val sidecarName = image.nameWithoutExtension + ".json"
            val sidecar = children.firstOrNull { it.isFile && it.name == sidecarName }
            val entry = if (sidecar != null) {
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
