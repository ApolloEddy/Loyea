package com.loyea.plugin.companion

import android.content.Context
import com.loyea.perception.memory.GraphMemoryManager
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 陪伴范围数据操作（S-08 / DATA-03~05，审计 R-04/R-05 事务化重写）。
 *
 * 恢复事务顺序——任何一步失败都保留旧数据并向上报告，绝不"伪报成功后继续删旧"：
 *   1. stopResponse：使进行中响应与转写等附属任务失效；
 *   2. staging：新消息文件先落盘（会话尚未入列表，防复活护栏不拦 raw 写入）；
 *   3. 提交：原子换会话列表（旧陪伴条目移除、新条目挂入，bindingRevision 递增）；
 *   4. 清理：旧消息文件、旧图谱、草稿；第 2 步失败/第 3 步失败分别回滚，不触碰旧数据。
 *
 * 重新开始：停响应 → 清图谱（R-05：图谱独立存储，deleteSession 不会触达）→
 * 清草稿 → 删会话；防复活护栏保证迟到的后台写回无法重建已删会话。
 */
internal object CompanionDataOps {

    sealed class RestoreOutcome {
        data class Done(val newId: String, val config: CompanionConfig) : RestoreOutcome()
        data class Failed(val reason: String) : RestoreOutcome()
    }

    /** 恢复陪伴备份。成功返回新会话 ID 与已落盘的合并配置；失败返回原因，旧数据保持原样。 */
    suspend fun restore(
        context: Context,
        viewModel: ChatViewModel,
        preview: CompanionBackupCodec.BackupPreview
    ): RestoreOutcome = withContext(Dispatchers.IO) {
        val storage = ChatStorageManager(context)
        val graph = GraphMemoryManager(context)

        // 1. 冻结旧任务：响应流、转写等附属任务与进行中的记忆整理全部取消；
        //    旧代际写回经防复活护栏失效
        viewModel.stopResponse()
        viewModel.cancelSessionAuxTasks()
        val old = viewModel.sessions.value.firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
            ?: storage.loadSessionList().firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
        old?.let { viewModel.cancelMemoryConsolidation(it.id) }
        val newId = System.currentTimeMillis().toString()

        // 2. staging：新消息文件先落盘并校验（R-04：写入失败不得继续后续步骤）
        if (!storage.saveSessionMessages(newId, preview.messages)) {
            storage.deleteSession(newId) // 会话未入列表：仅清掉可能的 staging 残留文件
            return@withContext RestoreOutcome.Failed("写入新消息失败（磁盘空间或权限问题），当前数据未被改动")
        }

        // staging：图谱记忆重映射到新会话（R-05：恢复必须带走图谱，否则下一轮召回断裂）
        val companionId = CompanionContract.COMPANION_CHARACTER_ID
        for (t in preview.graphTriples) {
            graph.insertTriple(
                characterId = companionId,
                sessionId = newId,
                subject = t.s,
                predicate = t.p,
                `object` = t.o,
                creationTime = t.creationTime,
                lastMentionedTime = t.lastMentionedTime,
                mentionCount = t.mentionCount,
                baseWeight = t.baseWeight
            )
        }

        // 3. 提交：原子换列表。失败 → 回滚 staging（消息文件 + 图谱），旧数据原样
        val restoredSession = preview.session.copy(
            id = newId,
            characterId = companionId,
            lastActiveTime = System.currentTimeMillis(),
            bindingRevision = preview.session.bindingRevision + 1 // DATA-04：代际递增，旧写回失效
        )
        val swapped = storage.updateSessionList { list ->
            (list.filter { !CompanionContract.isCompanionCharacter(it.characterId) } + restoredSession)
                .sortedByDescending { it.lastActiveTime }
        }
        if (!swapped) {
            storage.deleteSession(newId) // 未入列表，仅删 staging 消息文件
            graph.clearSession(companionId, newId)
            return@withContext RestoreOutcome.Failed("更新会话索引失败，当前数据未被改动")
        }

        // 4. 清理旧数据（新状态已完整提交；此后任何失败只影响空间，不影响一致性）
        old?.let {
            viewModel.clearDraft(it.id)
            if (it.id != newId) {
                graph.clearSession(companionId, it.id)
                storage.deleteSession(it.id)
            }
        }

        // 5. 落盘合并配置（R-05：称呼/免打扰/开关随备份恢复；enabled 与普通模式恢复点保持本机状态；
        //    头像为本地媒体不随备份迁移，恢复后重新设置）
        val store = CompanionConfigStore(context)
        val current = store.load()
        val restoredConfig = (preview.companionConfig ?: current).copy(
            enabled = current.enabled,
            avatarUri = "",
            sessionId = newId,
            lastNormalSessionId = current.lastNormalSessionId
        )
        store.save(restoredConfig)
        RestoreOutcome.Done(newId, restoredConfig)
    }

    /** 重新开始：清聊天/固定记忆/摘要/草稿/图谱（DATA-05），保留普通模式与公共服务配置。 */
    suspend fun restart(context: Context, viewModel: ChatViewModel): Unit = withContext(Dispatchers.IO) {
        viewModel.stopResponse() // DATA-05：使进行中的写回失效
        viewModel.cancelSessionAuxTasks()
        val storage = ChatStorageManager(context)
        val graph = GraphMemoryManager(context)
        val old = viewModel.sessions.value.firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
            ?: storage.loadSessionList().firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
        old?.let {
            // 撤销进行中的记忆整理：任务里的图谱写回会复活刚清掉的旧图谱
            viewModel.cancelMemoryConsolidation(it.id)
            viewModel.clearDraft(it.id)
            graph.clearSession(CompanionContract.COMPANION_CHARACTER_ID, it.id)
            storage.deleteSession(it.id)
        }
    }
}
