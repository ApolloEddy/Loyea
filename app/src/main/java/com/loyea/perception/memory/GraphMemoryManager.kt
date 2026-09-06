package com.loyea.perception.memory

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 端侧语义图谱记忆存储管理器，使用本地文件存储 (符合整体项目文件存储哲学)
 */
class GraphMemoryManager(private val context: Context) {
    private val gson = Gson()

    companion object {
        private val fileMutex = Mutex()
        /** 过期清理节流间隔：24 小时内最多执行一次，避免高频读写下反复全量扫描 */
        private const val PURGE_THROTTLE_MS = 24L * 3600 * 1000L
        /** 记忆过期窗口：90 天未被提及即视为过期遗忘 */
        private const val PURGE_EXPIRE_MS = 90L * 24 * 3600 * 1000L
    }

    // 图谱记忆随迁移进入 rebuild_storage_v1 根；首次访问前确保迁移完成
    private val memoriesFile = File(File(context.filesDir, com.loyea.storage.RebuildStorageMigrator.ROOT_NAME), "graph_memories.json")

    init {
        // 构造即触发（幂等、进程内已串行）迁移门禁，避免任何读写绕过迁移直接落到空文件
        runBlocking {
            runCatching { com.loyea.storage.RebuildStorageMigrator.ensureMigrated(context.filesDir) }
        }
    }

    private var lastPurgeTime = 0L

    /**
     * 从本地 JSON 文件加载所有三元组列表，带有损坏自愈（重命名备份而非直接删除，保留恢复可能）
     */
    private suspend fun loadTriplesInternal(): List<MemoryTriple> = fileMutex.withLock {
        if (!memoriesFile.exists()) return emptyList()
        return try {
            val json = memoriesFile.readText()
            val type = object : TypeToken<List<MemoryTriple>>() {}.type
            gson.fromJson<List<MemoryTriple>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            // 发生反序列化异常时重命名备份自愈，防范死循环闪退的同时不丢失原始数据
            try { memoriesFile.renameTo(File(memoriesFile.parentFile, "${memoriesFile.name}.corrupt")) } catch (ex: Exception) {}
            emptyList()
        }
    }

    /**
     * 保存三元组列表到本地 JSON 文件（原子写：临时文件 + 重命名，防止中途崩溃产生半截 JSON）
     */
    private suspend fun saveTriplesInternal(triples: List<MemoryTriple>) = fileMutex.withLock {
        try {
            val json = gson.toJson(triples)
            val tmpFile = File(memoriesFile.parentFile, "${memoriesFile.name}.tmp")
            tmpFile.writeText(json)
            if (!tmpFile.renameTo(memoriesFile)) {
                tmpFile.delete()
                memoriesFile.writeText(json)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 惰性过期清理（节流）：90 天未被提及的三元组视为过期遗忘并删除。
     * 每次调用任何记忆入口时兜底执行一次（24h 节流），防止图谱文件无限膨胀。
     * 注意：loadTriplesInternal 与 saveTriplesInternal 各自持锁，此处顺序调用无重入问题。
     */
    suspend fun purgeExpiredIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastPurgeTime < PURGE_THROTTLE_MS) return
        lastPurgeTime = now
        val expireTime = now - PURGE_EXPIRE_MS
        val currentList = loadTriplesInternal()
        val filtered = currentList.filter { it.lastMentionedTime >= expireTime }
        if (filtered.size != currentList.size) {
            saveTriplesInternal(filtered)
        }
    }

    /**
     * 新增或更新一条三元组记录，执行合并与计数强化
     */
    suspend fun upsertTriple(
        characterId: String,
        sessionId: String,
        subject: String,
        predicate: String,
        `object`: String
    ) {
        purgeExpiredIfNeeded() // 每次写入前兜底执行过期遗忘（24h 节流）
        val currentList = loadTriplesInternal().toMutableList()
        val currentTime = System.currentTimeMillis()
        
        // 查找是否已存在相同角色的相同语义关系
        val index = currentList.indexOfFirst {
            it.characterId == characterId &&
            it.sessionId == sessionId &&
            it.subject.equals(subject, ignoreCase = true) &&
            it.predicate.equals(predicate, ignoreCase = true) &&
            it.`object`.equals(`object`, ignoreCase = true)
        }

        if (index != -1) {
            val existing = currentList[index]
            currentList[index] = existing.copy(
                lastMentionedTime = currentTime,
                mentionCount = existing.mentionCount + 1
            )
        } else {
            val newId = (currentList.maxOfOrNull { it.id } ?: 0L) + 1L
            currentList.add(
                MemoryTriple(
                    id = newId,
                    characterId = characterId,
                    sessionId = sessionId,
                    subject = subject,
                    predicate = predicate,
                    `object` = `object`,
                    creationTime = currentTime,
                    lastMentionedTime = currentTime,
                    mentionCount = 1,
                    baseWeight = 1.0f
                )
            )
        }
        saveTriplesInternal(currentList)
    }

    /**
     * 加载当前会话隔离的长程三元组
     */
    suspend fun getTriplesForSession(characterId: String, sessionId: String): List<MemoryTriple> {
        return loadTriplesInternal().filter { it.characterId == characterId && it.sessionId == sessionId }
    }

    /**
     * 删除单条三元组记录，确保不破坏其他数据
     */
    suspend fun deleteTriple(id: Long) {
        val currentList = loadTriplesInternal().toMutableList()
        currentList.removeAll { it.id == id }
        saveTriplesInternal(currentList)
    }

    /**
     * 拓扑检索：双路（1-Hop 与 2-Hop）排序，剪枝最多返回 8 条，防范 Token 膨胀
     */
    suspend fun retrieveRelationalContext(
        characterId: String,
        sessionId: String,
        userInput: String
    ): String {
        purgeExpiredIfNeeded() // 读取路径兜底（节流后几乎零开销）
        val allTriples = loadTriplesInternal()
        // 1. 过滤当前角色及会话的图谱数据，完全阻断信息混用
        val sessionTriples = allTriples.filter { it.characterId == characterId && it.sessionId == sessionId }
        if (sessionTriples.isEmpty()) return ""

        val currentTime = System.currentTimeMillis()
        
        // 2. 简单的端侧词语/实体包含匹配
        val matchedEntities = mutableSetOf<String>()
        val entitiesInDatabase = sessionTriples.flatMap { listOf(it.subject, it.`object`) }.distinct()
        for (entity in entitiesInDatabase) {
            if (entity.length >= 2 && userInput.contains(entity, ignoreCase = true)) {
                matchedEntities.add(entity)
            }
        }

        if (matchedEntities.isEmpty()) return ""

        // 3. 计算 1-Hop 和 2-Hop 关联并结合艾宾浩斯曲线乘上衰减因子
        val candidateTriples = mutableMapOf<MemoryTriple, Float>()
        for (entity in matchedEntities) {
            // 1-Hop 直接相关
            val hop1 = sessionTriples.filter { it.subject == entity || it.`object` == entity }
            for (triple in hop1) {
                val score = triple.getCalculatedWeight(currentTime) * 1.0f
                candidateTriples[triple] = Math.max(candidateTriples[triple] ?: 0f, score)
                
                // 2-Hop 拓扑跳转
                val nextEntity = if (triple.subject == entity) triple.`object` else triple.subject
                val hop2 = sessionTriples.filter { (it.subject == nextEntity || it.`object` == nextEntity) && it != triple }
                for (t2 in hop2) {
                    val score2 = t2.getCalculatedWeight(currentTime) * 0.4f // 2-Hop 降级因子 0.4
                    candidateTriples[t2] = Math.max(candidateTriples[t2] ?: 0f, score2)
                }
            }
        }

        // 4. 排序并裁剪保留最高权重的 8 条
        val prunedTriples = candidateTriples.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { it.key }

        if (prunedTriples.isEmpty()) return ""

        // 5. 序列化为适合 AI 理解的 Recall Memory 格式
        val sb = StringBuilder("[Recall Memory:\n")
        prunedTriples.forEach {
            sb.append("- Relationship: ${it.subject} -> ${it.predicate} -> ${it.`object`}\n")
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * 清理过期记忆
     */
    suspend fun deleteExpiredMemories(characterId: String, sessionId: String, expireTime: Long) {
        val currentList = loadTriplesInternal()
        val filtered = currentList.filter {
            !(it.characterId == characterId && it.sessionId == sessionId && it.lastMentionedTime < expireTime)
        }
        saveTriplesInternal(filtered)
    }

    /**
     * 强行清空某个会话的所有记忆 (切换/删除会话时调用，防止缓存泄露)
     */
    suspend fun clearMemoriesForSession(characterId: String, sessionId: String) {
        val currentList = loadTriplesInternal()
        val filtered = currentList.filter {
            !(it.characterId == characterId && it.sessionId == sessionId)
        }
        saveTriplesInternal(filtered)
    }
}
