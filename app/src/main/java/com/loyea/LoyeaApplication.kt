package com.loyea

import android.app.Application
import android.util.Log
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PreparedPersonaTurn
import com.loyea.plugin.host.PersonaRuntimeLease
import com.loyea.plugin.host.PluginManager
import com.loyea.plugin.host.PluginState
import com.loyea.plugin.host.PluginStatus
import com.loyea.ui.chat.AppTavernPersonaRepository
import com.loyea.plugins.tavern.core.TavernPlugin
import com.loyea.plugins.tavern.core.TavernPluginDefinition
import com.loyea.plugins.tavern.core.TavernTurnSpec

/**
 * Application-wide plugin composition root shared by UI and background workers.
 *
 * 插件控制器在 onCreate 提前初始化：任何插件装配阶段的类初始化异常（如 ExceptionInInitializerError）
 * 都被捕获并“记忆化”，此后整个会话永久降级为原生模式——Loyea 自带人格与对话继续可用，
 * 只是酒馆扩展能力关闭，并记录原始 cause 供诊断。绝不把崩溃留到用户首次点击会话时才触发。
 */
class LoyeaApplication : Application() {
    private val pluginEnablementStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginEnablementStore(this)
    }

    private val tavernPersonaRepositoryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppTavernPersonaRepository(this)
    }

    private val tavernPersonaRepository: AppTavernPersonaRepository by tavernPersonaRepositoryDelegate

    private val pluginControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val manager = PluginManager().apply {
            register(
                TavernPlugin(tavernPersonaRepository),
                enabled = pluginEnablementStore.isEnabled(
                    TavernPluginDefinition.ID,
                    defaultEnabled = true
                )
            )
        }
        PersistentPluginController(pluginEnablementStore, manager)
    }

    private val pluginController: PersistentPluginController by pluginControllerDelegate

    /** 首次初始化失败的真实原因（Error/Exception 均记忆）。非空时后续全部走原生降级。 */
    @Volatile
    private var pluginInitFailure: Throwable? = null

    /** 获取插件控制器；装配失败后永久返回 null（记忆化降级），绝不再抛。 */
    private fun pluginControllerOrNull(): PersistentPluginController? {
        pluginInitFailure?.let { return null }
        return try {
            pluginController
        } catch (t: Throwable) {
            pluginInitFailure = t
            Log.e(
                TAG,
                "Tavern 插件控制器初始化失败，已降级为原生模式；酒馆扩展能力不可用",
                t
            )
            null
        }
    }

    internal fun acquirePersonaRuntime(pluginId: PluginId): PersonaRuntimeLease? =
        pluginControllerOrNull()?.acquirePersonaRuntime(pluginId)

    internal suspend fun prepareTavernPersonaTurn(
        lease: PersonaRuntimeLease,
        ref: PersonaRef,
        input: PluginTurnInput,
        spec: TavernTurnSpec
    ): PreparedPersonaTurn = tavernPersonaRepository.prepareStagedTurn(lease, ref, input, spec)

    internal fun pluginState(pluginId: PluginId): PersistentPluginState =
        pluginControllerOrNull()
            ?.state(pluginId, defaultEnabled = pluginId == TavernPluginDefinition.ID)
            ?: degradedState(pluginId)

    /** Persists desired state before mutating the live host, so process restart cannot re-enable it. */
    internal fun setPluginEnabled(pluginId: PluginId, enabled: Boolean): PersistentPluginState =
        pluginControllerOrNull()
            ?.setEnabled(
                pluginId = pluginId,
                enabled = enabled,
                defaultEnabled = pluginId == TavernPluginDefinition.ID
            )
            ?: degradedState(pluginId)

    /** 插件控制器不可用时返回的固定降级状态：插件视为已禁用。 */
    private fun degradedState(pluginId: PluginId) = PersistentPluginState(
        desiredEnabled = false,
        effective = PluginState(
            id = pluginId,
            descriptor = null,
            status = PluginStatus.DISABLED,
            failureType = "plugin-controller-init-failed"
        )
    )

    override fun onCreate() {
        super.onCreate()
        // 提前在安全点触发插件控制器装配：若装配抛异常，在这里降级而非在首次交互时崩溃。
        pluginControllerOrNull()
    }

    override fun onTerminate() {
        if (pluginControllerDelegate.isInitialized()) {
            try {
                pluginController.close()
            } catch (t: Throwable) {
                Log.w(TAG, "关闭插件控制器时出错", t)
            }
        }
        if (tavernPersonaRepositoryDelegate.isInitialized()) {
            tavernPersonaRepository.clearPendingTurns()
        }
        super.onTerminate()
    }

    private companion object {
        const val TAG = "LoyeaPlugin"
    }
}
