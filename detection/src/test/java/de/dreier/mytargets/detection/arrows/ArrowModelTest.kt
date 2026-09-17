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

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArrowModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val complete = """
        {
          "inputSize": 768, "stride": 2, "kernel": 5,
          "preShrinkMaxSide": 2000,
          "threshold": 0.12,
          "training": "runs/all-2026-09-17", "corpus": "e4fb964",
          "thresholdFrom": "median of r4 FP-limited fold thresholds",
          "crossValidation": {"oblique": "71.3 % at 1.06 FP/view", "fpLimited": "63.3 % at 0.64"},
          "precision": "fp16"
        }
    """.trimIndent()

    private fun failure(json: String): IllegalArgumentException =
        try {
            ArrowModel.parseMeta(json, "model.json")
            throw AssertionError("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }

    @Test
    fun readsEveryFieldOfTheSidecar() {
        val meta = ArrowModel.parseMeta(complete)

        assertThat(meta.inputSize).isEqualTo(768)
        assertThat(meta.stride).isEqualTo(2)
        assertThat(meta.kernel).isEqualTo(5)
        assertThat(meta.preShrinkMaxSide).isEqualTo(2000)
        assertThat(meta.threshold).isWithin(1e-12).of(0.12)
        assertThat(meta.outputSize).isEqualTo(384)
        assertThat(meta.training).isEqualTo("runs/all-2026-09-17")
        assertThat(meta.corpus).isEqualTo("e4fb964")
        assertThat(meta.thresholdFrom).isEqualTo("median of r4 FP-limited fold thresholds")
        assertThat(meta.crossValidation).containsEntry("fpLimited", "63.3 % at 0.64")
    }

    @Test
    fun theDescriptiveFieldsAreOptional() {
        val meta = ArrowModel.parseMeta(
            """{"inputSize": 512, "stride": 2, "kernel": 5, "preShrinkMaxSide": 2000, "threshold": 0.1}"""
        )

        assertThat(meta.training).isNull()
        assertThat(meta.crossValidation).isEmpty()
    }

    @Test
    fun aMissingFieldIsNamed() {
        val e = failure("""{"inputSize": 768, "stride": 2, "kernel": 5, "preShrinkMaxSide": 2000}""")

        assertThat(e).hasMessageThat().contains("model.json: missing threshold")
    }

    @Test
    fun brokenJsonIsNamed() {
        assertThat(failure("{ this is not json")).hasMessageThat().contains("model.json: cannot read JSON")
        assertThat(failure("")).hasMessageThat().contains("model.json: empty JSON")
    }

    @Test
    fun theValuesAreChecked() {
        fun with(field: String, value: String) = complete.replace(Regex(""""$field": [^,\n]*"""), """"$field": $value""")

        assertThat(failure(with("threshold", "0"))).hasMessageThat().contains("threshold")
        assertThat(failure(with("threshold", "1.5"))).hasMessageThat().contains("threshold")
        assertThat(failure(with("kernel", "4"))).hasMessageThat().contains("kernel")
        assertThat(failure(with("inputSize", "767"))).hasMessageThat().contains("stride")
        assertThat(failure(with("preShrinkMaxSide", "500"))).hasMessageThat().contains("preShrinkMaxSide")
    }

    @Test
    fun loadFindsBothFilesInTheFolder() {
        val dir = folder.newFolder("r4-all")
        File(dir, "model.onnx").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "model.json").writeText(complete)

        val model = ArrowModel.load(dir)

        assertThat(model.onnx).isEqualTo(File(dir, "model.onnx"))
        assertThat(model.meta.inputSize).isEqualTo(768)
    }

    @Test
    fun loadNamesTheMissingFile() {
        val dir = folder.newFolder("empty")
        File(dir, "model.json").writeText(complete)

        val e = try {
            ArrowModel.load(dir)
            throw AssertionError("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e
        }
        assertThat(e).hasMessageThat().contains("model.onnx")
        assertThat(e).hasMessageThat().contains(dir.name)
    }
}
