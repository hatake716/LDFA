package com.hatake716.linuxdesktop.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ContainerOperationLocksTest {
    @Test fun startingWaitsForSnapshotButAnotherContainerDoesNot() = runBlocking {
        val snapshotStarted = CompletableDeferred<Unit>()
        val snapshotDone = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val snapshot = async {
            ContainerOperationLocks.withLock("one") {
                snapshotStarted.complete(Unit)
                snapshotDone.await()
                events += "snapshot complete"
            }
        }
        snapshotStarted.await()
        val start = async { ContainerOperationLocks.withLock("one") { events += "start" } }
        val other = async { ContainerOperationLocks.withLock("two") { events += "other" } }
        yield()
        other.await()
        assertFalse(start.isCompleted)
        snapshotDone.complete(Unit)
        snapshot.await()
        start.await()
        assertEquals(listOf("other", "snapshot complete", "start"), events)
    }

    @Test fun switchingAlsoWaitsForThePreviousEnvironmentAndReleasesLocksOnCancellation() = runBlocking {
        withTimeout(2_000) {
            val snapshotStarted = CompletableDeferred<Unit>()
            val snapshotDone = CompletableDeferred<Unit>()
            val snapshot = async {
                ContainerOperationLocks.withLock("previous") {
                    snapshotStarted.complete(Unit)
                    snapshotDone.await()
                }
            }
            snapshotStarted.await()
            val switch = async {
                ContainerOperationLocks.withLocks(listOf("next", "previous")) { fail("Snapshot is still active") }
            }
            yield()
            assertFalse(switch.isCompleted)
            switch.cancel()
            switch.join()
            // The cancelled switch had acquired "next" before waiting for "previous".
            ContainerOperationLocks.withLock("next") { }
            snapshotDone.complete(Unit)
            snapshot.await()
            ContainerOperationLocks.withLocks(listOf("previous", "next", "previous")) { }
        }
    }
}
