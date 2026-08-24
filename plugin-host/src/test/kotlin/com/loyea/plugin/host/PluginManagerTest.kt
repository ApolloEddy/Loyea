package com.loyea.plugin.host

import com.loyea.plugin.api.LOYEA_PLUGIN_API_VERSION
import com.loyea.plugin.api.LoyeaPlugin
import com.loyea.plugin.api.PluginCapability
import com.loyea.plugin.api.PluginDescriptor
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginRuntime
import com.loyea.plugin.api.PluginRuntimeGeneration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerTest {
    private val pluginId = PluginId.of("com.loyea.tavern")

    @Test
    fun `incompatible plugin is visible but never started`() {
        val plugin = FakePlugin(apiVersion = LOYEA_PLUGIN_API_VERSION + 1)
        val manager = PluginManager()

        val state = manager.register(plugin, enabled = true)

        assertEquals(PluginStatus.INCOMPATIBLE, state.status)
        assertEquals(0, plugin.created.size)
        assertNull(manager.acquire(pluginId))
    }

    @Test
    fun `disable rejects new requests and drains an existing lease`() {
        val plugin = FakePlugin()
        val manager = PluginManager()
        manager.register(plugin, enabled = true)
        val lease = requireNotNull(manager.acquire(pluginId))
        val runtime = lease.runtime as FakeRuntime

        val disabled = manager.disable(pluginId)

        assertEquals(PluginStatus.DISABLED, disabled.status)
        assertNull(manager.acquire(pluginId))
        assertEquals(0, runtime.closeCount.get())

        lease.close()
        lease.close()

        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun `reenable creates a new generation while the old one drains`() {
        val plugin = FakePlugin()
        val manager = PluginManager()
        manager.register(plugin, enabled = true)
        val oldLease = requireNotNull(manager.acquire(pluginId))
        val oldRuntime = oldLease.runtime as FakeRuntime

        manager.disable(pluginId)
        val enabled = manager.enable(pluginId)
        val newLease = requireNotNull(manager.acquire(pluginId))
        val newRuntime = newLease.runtime as FakeRuntime

        assertEquals(PluginStatus.ENABLED, enabled.status)
        assertEquals(0L, oldLease.generation.revision)
        assertEquals(1L, newLease.generation.revision)
        assertEquals(0, oldRuntime.closeCount.get())

        oldLease.close()
        assertEquals(1, oldRuntime.closeCount.get())
        assertEquals(0, newRuntime.closeCount.get())

        newLease.close()
        manager.disable(pluginId)
        assertEquals(1, newRuntime.closeCount.get())
    }

    @Test
    fun `concurrent leases close a draining runtime exactly once`() {
        val plugin = FakePlugin()
        val manager = PluginManager()
        manager.register(plugin, enabled = true)
        val leases = List(32) { requireNotNull(manager.acquire(pluginId)) }
        val runtime = leases.first().runtime as FakeRuntime
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)

        manager.disable(pluginId)
        leases.forEach { lease ->
            pool.submit {
                start.await()
                lease.close()
                lease.close()
            }
        }
        start.countDown()
        pool.shutdown()

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, runtime.closeCount.get())
        assertEquals(0, manager.state(pluginId).activeLeases)
    }

    @Test
    fun `runtime creation failure is isolated and can be retried`() {
        val plugin = FakePlugin(failuresBeforeSuccess = 1)
        val manager = PluginManager()

        val failed = manager.register(plugin, enabled = true)
        val recovered = manager.enable(pluginId)

        assertEquals(PluginStatus.FAILED, failed.status)
        assertEquals("IllegalStateException", failed.failureType)
        assertEquals(PluginStatus.ENABLED, recovered.status)
        assertEquals(0L, recovered.generation?.revision)
        manager.close()
        assertEquals(1, plugin.created.single().closeCount.get())
    }

    @Test
    fun `duplicate registration cannot replace a live plugin`() {
        val manager = PluginManager()
        manager.register(FakePlugin(), enabled = false)

        assertThrows(IllegalArgumentException::class.java) {
            manager.register(FakePlugin(), enabled = false)
        }
    }

    @Test
    fun `invalid runtime generation is rejected and closed`() {
        val descriptor = descriptor()
        val badRuntime = FakeRuntime(
            descriptor = descriptor,
            generation = PluginRuntimeGeneration(pluginId, 99L)
        )
        val plugin = object : LoyeaPlugin {
            override val descriptor: PluginDescriptor = descriptor

            override fun createRuntime(generation: PluginRuntimeGeneration): PluginRuntime = badRuntime
        }
        val manager = PluginManager()

        val state = manager.register(plugin, enabled = true)

        assertEquals(PluginStatus.FAILED, state.status)
        assertEquals(1, badRuntime.closeCount.get())
        assertNull(manager.acquire(pluginId))
    }

    @Test
    fun `closing the host drains active leases and blocks acquisition`() {
        val plugin = FakePlugin()
        val manager = PluginManager()
        manager.register(plugin, enabled = true)
        val lease = requireNotNull(manager.acquire(pluginId))
        val runtime = lease.runtime as FakeRuntime

        manager.close()

        assertEquals(PluginStatus.HOST_CLOSED, manager.state(pluginId).status)
        assertNull(manager.acquire(pluginId))
        assertEquals(0, runtime.closeCount.get())

        lease.close()
        assertEquals(1, runtime.closeCount.get())
    }

    private fun descriptor(apiVersion: Int = LOYEA_PLUGIN_API_VERSION) = PluginDescriptor(
        id = pluginId,
        displayName = "Tavern compatibility",
        apiVersion = apiVersion,
        capabilities = setOf(PluginCapability.PERSONAS, PluginCapability.PROMPT_PIPELINE)
    )

    private inner class FakePlugin(
        apiVersion: Int = LOYEA_PLUGIN_API_VERSION,
        failuresBeforeSuccess: Int = 0
    ) : LoyeaPlugin {
        override val descriptor = descriptor(apiVersion)
        val created = Collections.synchronizedList(mutableListOf<FakeRuntime>())
        private val remainingFailures = AtomicInteger(failuresBeforeSuccess)

        override fun createRuntime(generation: PluginRuntimeGeneration): PluginRuntime {
            if (remainingFailures.getAndUpdate { value -> (value - 1).coerceAtLeast(0) } > 0) {
                throw IllegalStateException("expected test failure")
            }
            return FakeRuntime(descriptor, generation).also(created::add)
        }
    }

    private class FakeRuntime(
        override val descriptor: PluginDescriptor,
        override val generation: PluginRuntimeGeneration
    ) : PluginRuntime {
        val closeCount = AtomicInteger(0)

        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
