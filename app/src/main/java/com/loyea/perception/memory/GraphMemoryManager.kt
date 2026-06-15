package com.loyea.perception.memory

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 端侧语义图谱记忆存储管理器，使用本地文件存储 (符合整体项目文件存储哲学)
 */
class GraphMemoryManager(private val context: Context) {
    private val gson = Gson()
    private val memoriesFile = File(context.filesDir, "graph_memories.json")
    
    companion object {
        private val fileMutex = Mutex()
    }

    /**
     * 从本地 JSON 文件加载所有三元组列表，带有损坏自愈
     */
    private suspend fun loadTriplesInternal(): List<MemoryTriple> = fileMutex.withLock {
        if (!memoriesFile.exists()) return emptyList()
        return try {
            val json = memoriesFile.readText()
            val type = object : TypeToken<List<MemoryTriple>>() {}.type
            gson.fromJson<List<MemoryTriple>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            // 发生反序列化异常自动擦除自愈，防范死循环闪退
            try { memoriesFile.delete() } catch (ex: Exception) {}
            emptyList()
        }
    }

    /**
     * 保存三元组列表到本地 JSON 文件
     */
    private suspend fun saveTriplesInternal(triples: List<MemoryTriple>) = fileMutex.withLock {
        try {
            val json = gson.toJson(triples)
            memoriesFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
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
            currentList.add(
                MemoryTriple(
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
     * 拓扑检索：双路（1-Hop 与 2-Hop）排序，剪枝最多返回 8 条，防范 Token 膨胀
     */
    suspend fun retrieveRelationalContext(
        characterId: String,
        sessionId: String,
        userInput: String
    ): String {
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
