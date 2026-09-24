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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One detector per process (app integration 8a): loaded on first use, handed
 * out one call at a time because the detector is not thread-safe. A failed
 * load leaves the holder empty and is retried on the next call; low memory
 * can pass.
 */
class DetectorHolder<T : Any>(private val load: () -> T) {
    private val mutex = Mutex()
    private var loaded: T? = null

    suspend fun <R> withLoaded(onLoadFailure: (Throwable) -> R, block: (T) -> R): R = mutex.withLock {
        val value = loaded ?: try {
            load().also { loaded = it }
        } catch (e: Throwable) {
            // Throwable on purpose: a missing native library is an UnsatisfiedLinkError,
            // a device without memory an OutOfMemoryError; both mean "no model", not a crash.
            return@withLock onLoadFailure(e)
        }
        block(value)
    }
}
