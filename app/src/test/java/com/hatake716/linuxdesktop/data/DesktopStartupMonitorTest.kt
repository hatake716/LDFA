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
                phase(DesktopStartupStage.STARTING_XFCE)
                log.appendText("current run output\n")
                throw IllegalStateException("startup failed")
            }
        } catch (_: IllegalStateException) { }
        val result = monitor.progress.value
        assertFalse(result.busy)
        assertTrue(result.visible)
        assertEquals("startup failed", result.error)
        assertEquals(82, result.percent)
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
        assertEquals(42, monitor.track { phase -> phase(DesktopStartupStage.STARTING_XFCE); 42 })
        assertFalse(monitor.progress.value.visible)
        assertEquals(100, monitor.progress.value.percent)
        monitor.begin("second", "Second desktop")
        assertTrue(monitor.progress.value.busy)
        assertEquals(0, monitor.progress.value.percent)
        assertFalse(monitor.progress.value.logs.contains(DesktopStartupStage.STARTING_XFCE.message))
    }

    @Test fun retryKeepsProgressAndOnlySuccessfulCompletionReaches100() = runBlocking {
        val monitor = DesktopStartupMonitor(temporary.root)
        monitor.begin("test", "My desktop")
        monitor.track { phase ->
            phase(DesktopStartupStage.CHECKING_X11_FRAME)
            assertEquals(55, monitor.progress.value.percent)
            phase(DesktopStartupStage.CONNECTING_VIEWER)
            assertEquals(55, monitor.progress.value.percent)
            phase(DesktopStartupStage.READY)
            assertEquals(99, monitor.progress.value.percent)
            assertTrue(monitor.progress.value.busy)
        }
        assertEquals(100, monitor.progress.value.percent)
    }

    @Test fun cleanupAndFailureNeverReportCompletion() = runBlocking {
        val monitor = DesktopStartupMonitor(temporary.root)
        monitor.begin("test", "My desktop")
        runCatching {
            monitor.track { phase ->
                phase(DesktopStartupStage.VERIFYING_DESKTOP)
                phase(DesktopStartupStage.CLEANING_UP)
                throw IllegalStateException("no frame")
            }
        }
        assertEquals(92, monitor.progress.value.percent)
        assertTrue(monitor.progress.value.visible)
        assertFalse(monitor.progress.value.busy)
    }
}
