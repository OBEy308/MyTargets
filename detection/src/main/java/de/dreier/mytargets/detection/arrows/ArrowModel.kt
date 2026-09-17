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

package de.dreier.mytargets.detection.arrows

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File

/**
 * The sidecar model.json of a model folder (design 3d, Ablageformat): the only
 * source of the input size, the pre-shrink and the threshold. Nothing of it is
 * repeated in Kotlin code.
 */
class ArrowModelMeta(
    /** Edge of the square network input in pixels; the ONNX export is fixed to it. */
    val inputSize: Int,
    /** Input pixels per heatmap pixel. */
    val stride: Int,
    /** Window of the local maximum search, odd. */
    val kernel: Int,
    /** Longest side of the original before the warp, the constant of prepare.py. */
    val preShrinkMaxSide: Int,
    /** A heatmap value at or above this is a peak. */
    val threshold: Double,
    val training: String?,
    val corpus: String?,
    val thresholdFrom: String?,
    val crossValidation: Map<String, String>
) {
    init {
        require(stride >= 1) { "stride is at least 1, got $stride" }
        require(inputSize > 0 && inputSize % stride == 0) {
            "inputSize is a positive multiple of the stride $stride, got $inputSize"
        }
        require(kernel >= 1 && kernel % 2 == 1) { "kernel is odd and at least 1, got $kernel" }
        require(preShrinkMaxSide >= inputSize) {
            "preShrinkMaxSide ($preShrinkMaxSide) is at least the inputSize ($inputSize)"
        }
        require(threshold > 0.0 && threshold < 1.0) { "threshold lies in (0, 1), got $threshold" }
    }

    /** Edge of the heatmap the network returns. */
    val outputSize: Int
        get() = inputSize / stride
}

/** A model folder: the ONNX weights and their sidecar. */
class ArrowModel(val onnx: File, val meta: ArrowModelMeta) {

    companion object {
        const val ONNX_FILE = "model.onnx"
        const val META_FILE = "model.json"

        /** Reads [dir]/model.json and checks that [dir]/model.onnx exists; the network itself is read by the detector. */
        fun load(dir: File): ArrowModel {
            val onnx = File(dir, ONNX_FILE)
            val meta = File(dir, META_FILE)
            require(onnx.isFile) { "${dir.path}: no $ONNX_FILE" }
            require(meta.isFile) { "${dir.path}: no $META_FILE" }
            return ArrowModel(onnx, parseMeta(meta.readText(), meta.path))
        }

        /** Parses a sidecar; every error names [source] and the field. */
        fun parseMeta(json: String, source: String = META_FILE): ArrowModelMeta {
            val raw = try {
                Gson().fromJson(json, MetaJson::class.java)
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("$source: cannot read JSON", e)
            } ?: throw IllegalArgumentException("$source: empty JSON")

            fun <T> need(name: String, value: T?): T = requireNotNull(value) { "$source: missing $name" }
            val inputSize = need("inputSize", raw.inputSize)
            val stride = need("stride", raw.stride)
            val kernel = need("kernel", raw.kernel)
            val preShrinkMaxSide = need("preShrinkMaxSide", raw.preShrinkMaxSide)
            val threshold = need("threshold", raw.threshold)
            // Only the value checks of the constructor are prefixed here; a missing field already names the source.
            try {
                return ArrowModelMeta(
                    inputSize, stride, kernel, preShrinkMaxSide, threshold,
                    raw.training, raw.corpus, raw.thresholdFrom, raw.crossValidation ?: emptyMap()
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$source: ${e.message}", e)
            }
        }
    }

    // Nullable, so a missing field is reported by name rather than as a zero.
    private class MetaJson {
        var inputSize: Int? = null
        var stride: Int? = null
        var kernel: Int? = null
        var preShrinkMaxSide: Int? = null
        var threshold: Double? = null
        var training: String? = null
        var corpus: String? = null
        var thresholdFrom: String? = null
        var crossValidation: Map<String, String>? = null
    }
}
