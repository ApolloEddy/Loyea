package com.loyea

import com.loyea.plugin.api.LOYEA_PLUGIN_API_VERSION
import com.loyea.plugin.api.LoyeaPlugin
import com.loyea.plugin.api.PluginCapability
import com.loyea.plugin.api.PluginDescriptor
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginRuntime
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.host.PluginManager
import com.loyea.plugin.host.PluginStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPluginControllerTest {
    private val plugin = FakePlugin()

    @Test
    fun `concurrent enable changes keep persisted and live state in the same order`() {
        val persistence = BlockingPersistence(initial = true)
        val manager = PluginManager().apply { register(plugin, enabled = true) }
        val controller = PersistentPluginController(persistence, manager)
        val disableDone = CountDownLatch(1)
        val enableDone = CountDownLatch(1)

        val disableThread = thread(start = true) {
            controller.setEnabled(plugin.descriptor.id, false, defaultEnabled = true)
            disableDone.countDown()
        }
        assertTrue(persistence.firstWriteEntered.await(2, TimeUnit.SECONDS))

        val enableThread = thread(start = true) {
            controller.setEnabled(plugin.descriptor.id, true, defaultEnabled = true)
            enableDone.countDown()
        }
        assertFalse("second mutation must wait for the first live transition", enableDone.await(100, TimeUnit.MILLISECONDS))

        persistence.releaseFirstWrite.countDown()
        assertTrue(disableDone.await(2, TimeUnit.SECONDS))
        assertTrue(enableDone.await(2, TimeUnit.SECONDS))
        disableThread.join()
        enableThread.join()

        val finalState = controller.state(plugin.descriptor.id, defaultEnabled = true)
        assertTrue(finalState.desiredEnabled)
        assertTrue(finalState.effective.status == PluginStatus.ENABLED)
        controller.close()
    }

    @Test
    fun `persisted disabled state rebuilds a disabled runtime after restart`() {
        val persistence = ImmediatePersistence(enabled = false)
        val firstManager = PluginManager().apply {
            register(plugin, enabled = persistence.isEnabled(plugin.descriptor.id, true))
        }
        val firstController = PersistentPluginController(persistence, firstManager)
        assertFalse(firstController.state(plugin.descriptor.id, true).desiredEnabled)
        assertTrue(firstController.state(plugin.descriptor.id, true).effective.status == PluginStatus.DISABLED)
        firstController.close()

        val restartedManager = PluginManager().apply {
            register(plugin, enabled = persistence.isEnabled(plugin.descriptor.id, true))
        }
        val restarted = PersistentPluginController(persistence, restartedManager)

        assertFalse(restarted.state(plugin.descriptor.id, true).desiredEnabled)
        assertTrue(restarted.state(plugin.descriptor.id, true).effective.status == PluginStatus.DISABLED)
        restarted.close()
    }

    private class ImmediatePersistence(@Volatile var enabled: Boolean) : PluginEnablementPersistence {
        override fun isEnabled(pluginId: PluginId, defaultEnabled: Boolean): Boolean = enabled
        override fun setEnabled(pluginId: PluginId, enabled: Boolean) {
            this.enabled = enabled
        }
    }

    private class BlockingPersistence(initial: Boolean) : PluginEnablementPersistence {
        @Volatile
        private var enabled = initial
        private val writeCount = AtomicInteger(0)
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)

        override fun isEnabled(pluginId: PluginId, defaultEnabled: Boolean): Boolean = enabled

        override fun setEnabled(pluginId: PluginId, enabled: Boolean) {
            this.enabled = enabled
            if (writeCount.incrementAndGet() == 1) {
                firstWriteEntered.countDown()
                check(releaseFirstWrite.await(2, TimeUnit.SECONDS))
            }
        }
    }

    private class FakePlugin : LoyeaPlugin {
        override val descriptor = PluginDescriptor(
            id = PluginId.of("com.loyea.controller-test"),
            displayName = "Controller Test",
            apiVersion = LOYEA_PLUGIN_API_VERSION,
            capabilities = setOf(PluginCapability.PERSONAS)
        )

        override fun createRuntime(generation: PluginRuntimeGeneration): PluginRuntime =
            object : PluginRuntime {
                override val descriptor: PluginDescriptor = this@FakePlugin.descriptor
                override val generation: PluginRuntimeGeneration = generation
                override fun close() = Unit
            }
    }
}
