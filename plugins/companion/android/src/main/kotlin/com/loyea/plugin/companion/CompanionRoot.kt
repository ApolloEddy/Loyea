package com.loyea.plugin.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.loyea.ui.chat.ChatViewModel

private enum class BindState { BINDING, READY, MISSING }

private enum class CompanionSubPage { CHAT, SETTINGS, DATA, MEMORY, SEARCH }

/**
 * 陪伴模式外壳（enabled=true 时由 MainActivity 渲染，替代普通导航外壳）。
 * 装载即绑定唯一陪伴会话（FUN-01/FUN-08），不闪现普通首页、不触发模型调用。
 * 子页面（S-03～S-06）在本外壳内切换：只改变界面，不取消聊天请求、不重建会话（NAV-04）。
 */
@Composable
fun CompanionRoot(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { CompanionConfigStore(context) }
    var config by remember { mutableStateOf(store.load()) }
    var bindState by remember { mutableStateOf(BindState.BINDING) }
    var bindRetry by remember { mutableStateOf(0) }
    var subPage by remember { mutableStateOf(CompanionSubPage.CHAT) }
    var moreOpen by remember { mutableStateOf(false) }
    var focusMessageId by remember { mutableStateOf<String?>(null) }

    CompanionDarkWindowEffect()

    val setConfig: (CompanionConfig) -> Unit = { next ->
        store.save(next)
        config = next
    }

    LaunchedEffect(config.sessionId, bindRetry) {
        bindState = BindState.BINDING
        // 内部资料独立副本（FUN-03）：陪伴卡按当前显示名就位，供请求组装与 activeCharacterCard 解析
        val card = CompanionPersona.buildCard(config.displayName, config.avatarUri)
        val existing = viewModel.characterCardList.value
            .firstOrNull { CompanionContract.isCompanionCharacter(it.id) }
        if (existing == null || existing.name != card.name) {
            viewModel.saveCharacterCardList(
                viewModel.characterCardList.value
                    .filterNot { CompanionContract.isCompanionCharacter(it.id) } + card
            )
        }
        // FUN-01/FUN-09：已有绑定复用；绑定声明存在但记录缺失不静默重建，进入恢复态
        val allowCreate = config.sessionId.isBlank()
        val sessionId = viewModel.ensureCompanionSession(
            characterId = CompanionContract.COMPANION_CHARACTER_ID,
            title = "陪伴",
            allowCreate = allowCreate
        )
        if (sessionId == null) {
            bindState = BindState.MISSING
            return@LaunchedEffect
        }
        if (sessionId != config.sessionId) {
            setConfig(config.copy(sessionId = sessionId))
        }
        viewModel.selectSession(sessionId)
        bindState = BindState.READY
    }

    when (bindState) {
        BindState.BINDING -> Box(
            modifier
                .fillMaxSize()
                .background(CompanionPalette.Bg),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = CompanionPalette.AmberDot,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    "正在恢复本地记录…",
                    fontSize = 12.sp,
                    color = CompanionPalette.Hint,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        BindState.MISSING -> MissingRecordRecovery(
            onRetry = { bindRetry++ },
            onRestart = {
                // 明确的重新开始：仅清除绑定声明，创建全新陪伴会话；旧文件保留可找回
                setConfig(config.copy(sessionId = ""))
                bindRetry++
            }
        )
        BindState.READY -> when (subPage) {
            CompanionSubPage.CHAT -> Box(modifier.fillMaxSize()) {
                CompanionChatScreen(
                    viewModel = viewModel,
                    config = config,
                    focusMessageId = focusMessageId,
                    onFocusConsumed = { focusMessageId = null },
                    onOpenMore = { moreOpen = true }
                )
                if (moreOpen) {
                    // S-03：点外部关闭（背景层吞掉点击）
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color0FBlack)
                            .clickable(onClick = { moreOpen = false })
                    )
                    CompanionMoreSheet(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        perceptionEnabled = config.perceptionEnabled,
                        onPerceptionChange = { setConfig(config.copy(perceptionEnabled = it)) },
                        onOpenMemory = { moreOpen = false; subPage = CompanionSubPage.MEMORY },
                        onOpenSearch = { moreOpen = false; subPage = CompanionSubPage.SEARCH },
                        onOpenSettings = { moreOpen = false; subPage = CompanionSubPage.SETTINGS },
                        onDismiss = { moreOpen = false }
                    )
                }
            }
            CompanionSubPage.SETTINGS -> CompanionSettingsScreen(
                config = config,
                onConfigChange = {
                    setConfig(it)
                    // 显示名变化同步陪伴内部资料（FUN-03 独立副本）
                    val card = CompanionPersona.buildCard(it.displayName, it.avatarUri)
                    viewModel.saveCharacterCardList(
                        viewModel.characterCardList.value
                            .filterNot { c -> CompanionContract.isCompanionCharacter(c.id) } + card
                    )
                },
                onBack = { subPage = CompanionSubPage.CHAT },
                onOpenData = { subPage = CompanionSubPage.DATA },
                onDisableCompanion = { disableCompanionMode(context, viewModel) }
            )
            CompanionSubPage.DATA -> CompanionDataScreen(
                viewModel = viewModel,
                config = config,
                onBack = { subPage = CompanionSubPage.CHAT },
                onRestored = { newId ->
                    // DATA-04：换到重新映射后的会话；bind 依赖 config.sessionId 自动重跑并 selectSession，
                    // 旧会话文件已删除、进行中请求已被 stopResponse，晚到写回无法复活旧记录（DATA-05）
                    setConfig(config.copy(sessionId = newId))
                },
                onRestarted = {
                    // 明确的重新开始：清空绑定声明，bind 将以全新会话重建（原文件已删除）
                    setConfig(config.copy(sessionId = ""))
                    bindRetry++
                }
            )
            CompanionSubPage.MEMORY -> CompanionMemoryScreen(
                viewModel = viewModel,
                sessionId = config.sessionId,
                onBack = { subPage = CompanionSubPage.CHAT }
            )
            CompanionSubPage.SEARCH -> CompanionSearchScreen(
                viewModel = viewModel,
                onBack = { subPage = CompanionSubPage.CHAT },
                onOpenMessage = { id ->
                    focusMessageId = id
                    subPage = CompanionSubPage.CHAT
                }
            )
        }
    }
}

private val Color0FBlack = androidx.compose.ui.graphics.Color(0x66000000)

/** FUN-09 恢复态：绑定存在但记录缺失——重试或明确重新开始，不冒充原记录。 */
@Composable
private fun MissingRecordRecovery(onRetry: () -> Unit, onRestart: () -> Unit) {
    var confirmRestart by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(CompanionPalette.Bg)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("找不到陪伴记录", fontSize = 18.sp, color = CompanionPalette.Brand)
            Text(
                "本地记录可能被移动或损坏，原数据仍保留在设备上",
                fontSize = 13.sp,
                color = CompanionPalette.Hint,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .background(CompanionPalette.GlassFill, CircleShape)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("重试", fontSize = 14.sp, color = CompanionPalette.TextPrimary)
            }
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .background(CompanionPalette.GlassFill, CircleShape)
                    .clickable {
                        if (confirmRestart) {
                            onRestart()
                        } else {
                            confirmRestart = true
                        }
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    if (confirmRestart) "再点一次，确认重新开始" else "重新开始陪伴",
                    fontSize = 14.sp,
                    color = CompanionPalette.TextPrimary
                )
            }
            if (confirmRestart) {
                Text(
                    "将创建新的陪伴记录（原数据文件保留在设备上）",
                    fontSize = 12.sp,
                    color = CompanionPalette.Hint,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

/**
 * S-01 首次开启浮层：宿主设置页「陪伴模式」入口挂载。
 * 提交时按 FUN-06 停止前台生成、记录最近的普通会话（NAV-03），随后由模式刷新切到陪伴外壳。
 */
@Composable
fun CompanionSetupOverlay(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { CompanionConfigStore(context) }
    CompanionDarkWindowEffect()
    Box(
        Modifier
            .fillMaxSize()
            .background(CompanionPalette.Bg)
            .zIndex(10f)
    ) {
        CompanionSetupScreen(
            initial = store.load(),
            chatConfigured = viewModel.activeApiConfig.value.apiKey.isNotBlank(),
            onStart = { next ->
                // FUN-06：切换前停止前台生成；录音/播放由会话切换逻辑处理
                viewModel.stopResponse()
                val current = store.load()
                val isFirstCreation = current.createdAt == 0L
                // 记录 NAV-03 的恢复点：当前会话若是陪伴会话（历史污染），改记最近的普通会话
                val currentId = viewModel.currentSessionId.value
                val sessions = viewModel.sessions.value
                val currentIsCompanion = sessions.firstOrNull { it.id == currentId }
                    ?.let { CompanionContract.isCompanionCharacter(it.characterId) } == true
                val lastNormal = if (currentIsCompanion) {
                    sessions.filter { !CompanionContract.isCompanionCharacter(it.characterId) }
                        .maxByOrNull { it.lastActiveTime }?.id ?: ""
                } else {
                    currentId
                }
                store.save(
                    next.copy(
                        enabled = true,
                        createdAt = if (isFirstCreation) System.currentTimeMillis() else current.createdAt,
                        // FUN-05 默认值只在首次创建时固化的语义已在 initial 中保证（默认 true），
                        // 用户显式关闭后保存为关，之后不再回改
                        lastNormalSessionId = lastNormal
                    )
                )
                CompanionModeState.refresh(context) // NAV-02：同一模式解析逻辑驱动外壳切换
                onDismiss()
            },
            onCancel = onDismiss,
            onConfigureChat = onDismiss
        )
    }
}

/** NAV-03：关闭陪伴模式（陪伴设置内）。仅切换入口，永不清空数据（DATA-06）。 */
fun disableCompanionMode(context: android.content.Context, viewModel: ChatViewModel) {
    val store = CompanionConfigStore(context)
    val config = store.load()
    if (!config.enabled) return
    viewModel.stopResponse()
    store.save(config.copy(enabled = false))
    // 恢复目标必须是普通会话：lastNormalSessionId 被污染（历史版本/异常路径写入陪伴会话）时
    // 落回最近普通会话，绝不把陪伴会话当作普通模式恢复点（NAV-03/FUN-02）
    val normalSessions = viewModel.sessions.value
        .filter { !CompanionContract.isCompanionCharacter(it.characterId) }
    val target = normalSessions.firstOrNull { it.id == config.lastNormalSessionId }
        ?: normalSessions.maxByOrNull { it.lastActiveTime }
    target?.let { viewModel.selectSession(it.id) }
    CompanionModeState.refresh(context)
}
