package com.hatake716.linuxdesktop.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes data-changing operations and backup snapshots for each Linux environment. */
object ContainerOperationLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withLock(id: String, operation: suspend () -> T): T =
        locks.getOrPut(id) { Mutex() }.withLock { operation() }

    /** A display switch changes both environments. Acquire their locks in a stable order. */
    suspend fun <T> withLocks(ids: Collection<String>, operation: suspend () -> T): T {
        val ordered = ids.distinct().sorted()
        suspend fun acquire(index: Int): T =
            if (index == ordered.size) operation()
            else withLock(ordered[index]) { acquire(index + 1) }
        return acquire(0)
    }
}
