package com.loyea.plugins.tavern.storage

import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** 冲突类型。 */
enum class TavernStorageConflictType {
    /** 目标位置已存在与期望不一致的内容（SHA-256 不同）。 */
    CONTENT_DIFFERS,

    /** 迁移标记的 schemaVersion 高于当前实现，说明目标由更新版本的插件写入。 */
    MARKER_VERSION_MISMATCH
}

/** 一次冲突探测的详细信息（用于日志/UI 报告）。 */
data class TavernStorageConflict(
    val relativePath: String,
    val type: TavernStorageConflictType,
    val expectedSha256: String?,
    val onDiskSha256: String?,
    val markerSchemaVersionOnDisk: Int?
)

/** 被保留下来的冲突内容定位（对齐现有迁移标记的"冲突保留"语义）。 */
data class TavernStoragePreservedRecord(
    val originalRelativePath: String,
    val preservedRelativePath: String,
    val sha256: String
)

/** 孤儿文件的种类。 */
enum class TavernStorageOrphanKind {
    /** 模块原子写残留的临时文件（`.name.<uuid>.tmp`），可安全清理。 */
    ORPHAN_TEMP,

    /** 已被移动到相邻位置的损坏备份（`<name>.corrupt`），仅报告、不自动删除。 */
    CORRUPT_BACKUP
}

/** 一次孤儿文件扫描命中。 */
data class TavernStorageOrphan(
    val file: File,
    val kind: TavernStorageOrphanKind
)

/**
 * 对存储根目录做一遍一致性检查/修复后的结果。所有动作都是可观测的、非破坏性的，
 * 以便宿主或后续阶段据此决定是否从源文件重新触发迁移。
 */
data class TavernStorageRepairResult(
    val removedOrphanTempFiles: List<File> = emptyList(),
    val preservedConflicts: List<TavernStoragePreservedRecord> = emptyList(),
    val verifiedRecords: List<TavernStorageFileRecord> = emptyList(),
    /** 目标缺失、且无磁盘内容可保留，需要宿主再次从源文件迁移的记录。 */
    val missingRecords: List<TavernStorageFileRecord> = emptyList(),
    val markerVersionMismatch: TavernStorageConflict? = null
)

/**
 * 资源文件接管后的恢复与冲突可观测性。这个对象只做"检测 + 保留 + 清理"，不主动改写
 * 尚未确认的用户内容：
 *  - 原子写失败残留的 `.tmp` 临时文件会被扫描并清理；
 *  - 损坏备份 `.corrupt` 只报告、不删除；
 *  - 与期望记录 SHA-256 不一致的目标内容通过冲突保留命名约定 `.conflict` 副本留存；
 *  - 迁移标记 schemaVersion 不匹配会被单独报告。
 *
 * 所有函数都限定在纯 Kotlin/JVM，最终路径都经由 [TavernStorageLayout.resolve] 约束在
 * 存储根目录内，不会逃逸到插件私有目录之外。
 */
object TavernStorageRecovery {
    /** 与现有迁移标记一致的冲突保留后缀。 */
    const val CONFLICT_SUFFIX = ".conflict"
    const val CORRUPT_SUFFIX = ".corrupt"
    const val TEMP_SUFFIX = ".tmp"

    private val tempNamePattern by lazy { Regex("^\\..+\\.tmp$") }
    private val corruptNamePattern by lazy { Regex("^(.+)\\.corrupt$") }

    /** 读取迁移标记中的 schemaVersion；标记缺失或字段非法时返回 null。 */
    fun readMarkerSchemaVersion(layout: TavernStorageLayout): Int? {
        val file = layout.migrationMarkerFile
        if (!file.isFile) return null
        return file.readLines(StandardCharsets.UTF_8)
            .map(String::trim)
            .firstOrNull { it.startsWith("schemaVersion=") }
            ?.substringAfter("schemaVersion=")
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toIntOrNull()
    }

    /**
     * 探测单一目标的"内容冲突"：目标存在且其 SHA-256 与期望记录不一致时返回冲突。
     * 期望记录为 null 或目标缺失时无法判定，返回 null（此时不算冲突）。
     */
    fun detectContentConflict(
        layout: TavernStorageLayout,
        relativePath: String,
        expected: TavernStorageFileRecord?
    ): TavernStorageConflict? {
        if (expected == null) return null
        val target = layout.resolve(relativePath)
        if (!target.isFile) return null
        val onDisk = fingerprint(relativePath, target)
        if (onDisk == expected) return null
        return TavernStorageConflict(
            relativePath = relativePath,
            type = TavernStorageConflictType.CONTENT_DIFFERS,
            expectedSha256 = expected.sha256,
            onDiskSha256 = onDisk.sha256,
            markerSchemaVersionOnDisk = null
        )
    }

    /** 探测迁移标记 schemaVersion 是否与当前实现不匹配（目标由更新版本插件写入）。 */
    fun detectMarkerVersionMismatch(layout: TavernStorageLayout): TavernStorageConflict? {
        val version = readMarkerSchemaVersion(layout) ?: return null
        if (version == TavernStorageMigrator.SCHEMA_VERSION) return null
        return TavernStorageConflict(
            relativePath = layout.migrationMarkerRelativePath,
            type = TavernStorageConflictType.MARKER_VERSION_MISMATCH,
            expectedSha256 = null,
            onDiskSha256 = null,
            markerSchemaVersionOnDisk = version
        )
    }

    /** 扫描存储根目录（含子目录）内的孤儿文件：`.tmp` 残留与 `.corrupt` 损坏备份。 */
    fun scanOrphans(layout: TavernStorageLayout): List<TavernStorageOrphan> {
        val root = layout.root
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                when {
                    tempNamePattern.matches(file.name) ->
                        TavernStorageOrphan(file, TavernStorageOrphanKind.ORPHAN_TEMP)
                    corruptNamePattern.matches(file.name) ->
                        TavernStorageOrphan(file, TavernStorageOrphanKind.CORRUPT_BACKUP)
                    else -> null
                }
            }
            .toList()
    }

    /** 只清理模块原子写残留的 `.tmp` 孤儿文件（破坏性下限：绝不触碰 `.corrupt` 与 `.conflict`）。 */
    fun removeOrphanTempFiles(layout: TavernStorageLayout): List<File> =
        scanOrphans(layout)
            .filter { it.kind == TavernStorageOrphanKind.ORPHAN_TEMP }
            .map { it.file }
            .filter(File::delete)

    /**
     * 把目标当前内容按冲突保留命名约定复制成相邻的 `.conflict` 副本（非破坏性，目标不动），
     * 返回新副本的定位。目标不存在时返回 null。
     */
    fun preserveConflictingContent(
        layout: TavernStorageLayout,
        relativePath: String
    ): TavernStoragePreservedRecord? {
        val target = layout.resolve(relativePath)
        if (!target.isFile) return null
        val dir = target.parentFile ?: return null
        val preserved = File(dir, ".${target.name}.${UUID.randomUUID()}$CONFLICT_SUFFIX")
        target.copyTo(preserved, overwrite = true)
        val sha = sha256File(preserved)
        return TavernStoragePreservedRecord(
            originalRelativePath = relativePath,
            preservedRelativePath = relativeTo(preserved, layout.root),
            sha256 = sha
        )
    }

    /**
     * 高阶修复/清理入口：清理孤儿 `.tmp`、按期望记录核对目标并保留冲突内容、
     * 报告标记版本不匹配。不会重写用户内容——无法从 SHA-256 还原正文，因此内容冲突
     * 只做保留（写 `.conflict` 副本），真正恢复需由宿主从源文件再次触发 [TavernStorageMigrator.migrate]。
     */
    fun reconcileRegistry(
        layout: TavernStorageLayout,
        expectedRecords: List<TavernStorageFileRecord>
    ): TavernStorageRepairResult {
        layout.ensureDirectories()
        val removed = removeOrphanTempFiles(layout)
        val verified = mutableListOf<TavernStorageFileRecord>()
        val preserved = mutableListOf<TavernStoragePreservedRecord>()
        val missing = mutableListOf<TavernStorageFileRecord>()
        expectedRecords.distinctBy(TavernStorageFileRecord::relativePath).forEach { expected ->
            when {
                TavernStorageMigrator.verify(layout, expected) -> verified += expected
                layout.resolve(expected.relativePath).isFile -> {
                    preserveConflictingContent(layout, expected.relativePath)?.let(preserved::add)
                }
                else -> missing += expected
            }
        }
        return TavernStorageRepairResult(
            removedOrphanTempFiles = removed,
            preservedConflicts = preserved,
            verifiedRecords = verified,
            missingRecords = missing,
            markerVersionMismatch = detectMarkerVersionMismatch(layout)
        )
    }

    private fun fingerprint(relativePath: String, file: File): TavernStorageFileRecord =
        TavernStorageFileRecord(relativePath, file.length(), sha256File(file))

    private fun relativeTo(file: File, root: File): String {
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(canonicalFile.path.startsWith(rootPrefix)) {
            "Tavern recovery preserved file escaped root: ${file.absolutePath}"
        }
        return canonicalRoot.toPath()
            .relativize(canonicalFile.toPath())
            .toString()
            .replace('\\', '/')
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}