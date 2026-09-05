package com.hatake716.linuxdesktop.data

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopStartupMonitorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retainsFailureAndActualLogOutputUntilDismissed() = runBlocking {
        val log = File(temporary.root, "home/.local/share/linux-desktop-for-android/logs/test.log")
        log.parentFile!!.mkdirs()
        log.writeText("previous run\n")
        val monitor = DesktopStartupMonitor(temporary.root)
        monitor.begin("test", "My desktop")
        try {
            monitor.track { phase ->
                phase("Starting XFCE")
                log.appendText("current run output\n")
                throw IllegalStateException("startup failed")
            }
        } catch (_: IllegalStateException) { }
        val result = monitor.progress.value
        assertFalse(result.busy)
        assertTrue(result.visible)
        assertEquals("startup failed", result.error)
        assertTrue(result.logs.contains("current run output"))
        assertFalse(result.logs.contains("previous run"))
        monitor.dismissFailure()
        assertFalse(monitor.progress.value.visible)
    }

    @Test fun cancellationClearsBusyStateAndStopsSampling() = runBlocking {
        val monitor = DesktopStartupMonitor(temporary.root)
        monitor.begin("test", "My desktop")
        val started = CompletableDeferred<Unit>()
        val job = launch {
            monitor.track {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(monitor.progress.value.busy)
        assertNotNull(monitor.progress.value.error)
    }

    @Test fun successfulLaunchHidesOverlayAndNextLaunchClearsTheOldLog() = runBlocking {
        val monitor = DesktopStartupMonitor(temporary.root)
        monitor.begin("first", "First desktop")
        assertEquals(42, monitor.track { phase -> phase("first-only detail"); 42 })
        assertFalse(monitor.progress.value.visible)
        monitor.begin("second", "Second desktop")
        assertTrue(monitor.progress.value.busy)
        assertFalse(monitor.progress.value.logs.contains("first-only detail"))
    }
}
