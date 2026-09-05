package com.hatake716.linuxdesktop.runtime

import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.runner.app.AppShell
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AppShellCancellationTest {
    @Test
    fun cancellationTerminatesOnlyTheOwnedProcessWithoutAPid() {
        val owned = ProcessBuilder("sleep", "30").start()
        val unrelated = ProcessBuilder("sleep", "30").start()
        try {
            // Reflection here constructs our own class, not an Android framework object.
            val constructor = AppShell::class.java.getDeclaredConstructor(
                Process::class.java, ExecutionCommand::class.java, AppShell.AppShellClient::class.java,
            ).apply { isAccessible = true }
            val shell = constructor.newInstance(owned, ExecutionCommand(), null)
            shell.kill()
            assertTrue("Cancelled child must terminate", owned.waitFor(5, TimeUnit.SECONDS))
            assertFalse(owned.isAlive)
            assertTrue("A different child must remain alive", unrelated.isAlive)
            shell.kill() // Repeated cancellation of an exited child is harmless.
            assertTrue(unrelated.isAlive)
        } finally {
            owned.destroyForcibly()
            unrelated.destroyForcibly()
            owned.waitFor(5, TimeUnit.SECONDS)
            unrelated.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
