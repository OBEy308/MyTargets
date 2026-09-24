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

package de.dreier.mytargets.features.detection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DetectorHolderTest {

    @Test
    fun loadsOnceAcrossCalls() = runBlocking {
        val loads = AtomicInteger()
        val holder = DetectorHolder { loads.incrementAndGet(); "net" }

        repeat(3) { assertEquals("net", holder.withLoaded({ "failed" }) { it }) }

        assertEquals(1, loads.get())
    }

    @Test
    fun aFailedLoadIsRetriedOnTheNextCall() = runBlocking {
        var attempts = 0
        val holder = DetectorHolder {
            attempts++
            if (attempts == 1) throw UnsatisfiedLinkError("no libopencv_java4") else "net"
        }

        assertEquals("failed: no libopencv_java4", holder.withLoaded({ "failed: ${it.message}" }) { it })
        assertEquals("net", holder.withLoaded({ "failed" }) { it })
        assertEquals(2, attempts)
    }

    @Test
    fun callsRunOneAtATime() = runBlocking {
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val holder = DetectorHolder { "net" }

        (1..8).map {
            launch(Dispatchers.Default) {
                holder.withLoaded({ Unit }) {
                    val now = inside.incrementAndGet()
                    most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    Thread.sleep(5)
                    inside.decrementAndGet()
                }
            }
        }.joinAll()

        assertEquals(1, most.get())
    }

    @Test
    fun anExceptionInsideTheBlockLeavesTheHolderUsable() = runBlocking {
        val loads = AtomicInteger()
        val holder = DetectorHolder { loads.incrementAndGet(); "net" }

        assertFailsWith<IllegalStateException> { holder.withLoaded({ "failed" }) { error("forward pass failed") } }

        assertEquals("net", holder.withLoaded({ "failed" }) { it })
        assertEquals(1, loads.get())
    }
}
