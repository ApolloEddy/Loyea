package com.loyea.plugins.tavern.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

enum class TavernStorageMigrationStatus {
    COPIED,
    ALREADY_CURRENT,
    RECONCILED_EXISTING
}

data class TavernStorageMigrationResult(
    val status: TavernStorageMigrationStatus,
    val sourceSignature: String,
    val records: List<TavernStorageFileRecord>,
    val missingOptionalSources: List<String> = emptyList(),
    val preservedConflicts: List<String> = emptyList()
)

data class TavernStorageMigrationMarker(
    val schemaVersion: Int,
    val createdAtEpochMillis: Long,
    val sourceSignature: String,
    val records: List<TavernStorageFileRecord>
)

/**
 * Non-destructive legacy migration for plugin-owned files. Sources are never deleted. A marker is
 * written only after every copied file has a matching size and SHA-256 fingerprint; rerunning the
 * migration is therefore safe after process death or a partially written target.
 */
object TavernStorageMigrator {
    const val SCHEMA_VERSION = 1

    private val migrationLock = Any()

    fun migrate(
        sourceRoot: File,
        layout: TavernStorageLayout,
        specs: List<TavernStorageFileSpec>,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): TavernStorageMigrationResult = synchronized(migrationLock) {
        require(specs.isNotEmpty()) { "At least one Tavern storage migration spec is required" }
        require(specs.map { TavernStoragePath.normalize(it.targetRelativePath) }.distinct().size == specs.size) {
            "Tavern storage migration target paths must be unique"
        }
        layout.ensureDirectories()

        val states = specs.map { spec ->
            val sourceFile = resolveUnderRoot(sourceRoot, spec.sourceRelativePath)
            val sourceRecord = when {
                !sourceFile.exists() -> null
                !sourceFile.isFile -> error("Tavern migration source is not a file: ${sourceFile.absolutePath}")
                else -> fingerprint(spec.sourceRelativePath, sourceFile)
            }
            SourceState(spec, sourceFile, sourceRecord)
        }
        val sourceSignature = sourceSignature(states)
        val marker = readMarker(layout.migrationMarkerFile)
        if (marker != null && marker.schemaVersion == SCHEMA_VERSION &&
            marker.sourceSignature == sourceSignature && marker.records.all { verify(layout, it) }
        ) {
            return@synchronized TavernStorageMigrationResult(
                status = TavernStorageMigrationStatus.ALREADY_CURRENT,
                sourceSignature = sourceSignature,
                records = marker.records,
                missingOptionalSources = states.filter { it.sourceRecord == null && !it.spec.required }
                    .map { it.spec.sourceRelativePath }
            )
        }

        val previousRecords = marker?.records.orEmpty().associateBy { it.relativePath }
        val records = mutableListOf<TavernStorageFileRecord>()
        val missingOptional = mutableListOf<String>()
        val preservedConflicts = mutableListOf<String>()
        var copiedAny = false

        states.forEach { state ->
            val targetPath = TavernStoragePath.normalize(state.spec.targetRelativePath)
            val targetFile = layout.resolve(targetPath)
            val existingTarget = targetFile.takeIf { it.isFile }?.let { fingerprint(targetPath, it) }
            val sourceRecord = state.sourceRecord
            if (sourceRecord == null) {
                if (state.spec.required) {
                    error("Required Tavern migration source is missing: ${state.spec.sourceRelativePath}")
                }
                existingTarget?.let(records::add)
                missingOptional += state.spec.sourceRelativePath
                return@forEach
            }

            val previousTarget = previousRecords[targetPath]
            val targetChangedSinceMarker = previousTarget != null && existingTarget != null &&
                previousTarget != existingTarget
            val canReplaceExisting = existingTarget == null ||
                (marker != null && previousTarget != null && !targetChangedSinceMarker)

            when {
                existingTarget == null -> {
                    copyAndVerify(state.sourceFile, targetFile, sourceRecord)
                    records += sourceRecord.copy(relativePath = targetPath)
                    copiedAny = true
                }
                canReplaceExisting && existingTarget != sourceRecord.copy(relativePath = targetPath) -> {
                    copyAndVerify(state.sourceFile, targetFile, sourceRecord)
                    records += sourceRecord.copy(relativePath = targetPath)
                    copiedAny = true
                }
                else -> {
                    records += existingTarget
                    if (existingTarget != sourceRecord.copy(relativePath = targetPath)) {
                        preservedConflicts += targetPath
                    }
                }
            }
        }

        writeMarker(
            layout.migrationMarkerFile,
            TavernStorageMigrationMarker(
                schemaVersion = SCHEMA_VERSION,
                createdAtEpochMillis = nowEpochMillis,
                sourceSignature = sourceSignature,
                records = records.distinctBy { it.relativePath }.sortedBy { it.relativePath }
            )
        )
        TavernStorageMigrationResult(
            status = if (copiedAny) TavernStorageMigrationStatus.COPIED
            else TavernStorageMigrationStatus.RECONCILED_EXISTING,
            sourceSignature = sourceSignature,
            records = records.distinctBy { it.relativePath }.sortedBy { it.relativePath },
            missingOptionalSources = missingOptional,
            preservedConflicts = preservedConflicts
        )
    }

    fun readUtf8(layout: TavernStorageLayout, relativePath: String): String? {
        val file = layout.resolve(relativePath)
        return file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    /** Writes a plugin document atomically; it does not alter the migration marker. */
    fun writeUtf8(layout: TavernStorageLayout, relativePath: String, content: String): TavernStorageFileRecord {
        layout.ensureDirectories()
        val normalized = TavernStoragePath.normalize(relativePath)
        val target = layout.resolve(normalized)
        atomicWrite(target, content.toByteArray(StandardCharsets.UTF_8))
        return fingerprint(normalized, target)
    }

    fun verify(layout: TavernStorageLayout, record: TavernStorageFileRecord): Boolean {
        val file = layout.resolve(record.relativePath)
        return file.isFile && fingerprint(record.relativePath, file) == record
    }

    fun readMarker(file: File): TavernStorageMigrationMarker? = runCatching {
        if (!file.isFile) return@runCatching null
        val values = file.readLines(Charsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val schemaVersion = values["schemaVersion"]?.toIntOrNull() ?: return@runCatching null
        val createdAt = values["createdAtEpochMillis"]?.toLongOrNull() ?: return@runCatching null
        val sourceSignature = values["sourceSignature"]?.takeIf { it.length == 64 } ?: return@runCatching null
        val count = values["recordCount"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return@runCatching null
        val records = (0 until count).map { index ->
            val path = decodeToken(values["record.$index.path"] ?: error("Missing marker path"))
            val size = values["record.$index.size"]?.toLongOrNull() ?: error("Missing marker size")
            val sha = values["record.$index.sha256"] ?: error("Missing marker digest")
            TavernStorageFileRecord(path, size, sha)
        }
        TavernStorageMigrationMarker(schemaVersion, createdAt, sourceSignature, records)
    }.getOrNull()

    private data class SourceState(
        val spec: TavernStorageFileSpec,
        val sourceFile: File,
        val sourceRecord: TavernStorageFileRecord?
    )

    private fun sourceSignature(states: List<SourceState>): String {
        val material = states.sortedBy { it.spec.targetRelativePath }.joinToString("\n") { state ->
            val record = state.sourceRecord
            listOf(
                TavernStoragePath.normalize(state.spec.sourceRelativePath),
                TavernStoragePath.normalize(state.spec.targetRelativePath),
                record?.byteSize?.toString() ?: "MISSING",
                record?.sha256 ?: "MISSING"
            ).joinToString("|")
        }
        return sha256Hex(material.toByteArray(StandardCharsets.UTF_8))
    }

    private fun resolveUnderRoot(root: File, relativePath: String): File {
        val normalized = TavernStoragePath.normalize(relativePath)
        val canonicalRoot = root.canonicalFile
        val resolved = File(canonicalRoot, normalized).canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(resolved.path.startsWith(rootPrefix)) { "Migration source escaped root: $relativePath" }
        return resolved
    }

    private fun fingerprint(relativePath: String, file: File): TavernStorageFileRecord =
        TavernStorageFileRecord(relativePath, file.length(), sha256File(file))

    private fun copyAndVerify(source: File, target: File, expected: TavernStorageFileRecord) {
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        target.parentFile?.mkdirs()
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(fingerprint(expected.relativePath, temporary).copy(relativePath = expected.relativePath) == expected) {
                "Tavern migration copy verification failed: ${source.absolutePath}"
            }
            moveAtomically(temporary, target)
            check(fingerprint(expected.relativePath, target) == expected) {
                "Tavern migration target verification failed: ${target.absolutePath}"
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun writeMarker(file: File, marker: TavernStorageMigrationMarker) {
        val content = buildString {
            appendLine("# Loyea Tavern storage migration marker")
            appendLine("schemaVersion=${marker.schemaVersion}")
            appendLine("createdAtEpochMillis=${marker.createdAtEpochMillis}")
            appendLine("sourceSignature=${marker.sourceSignature}")
            appendLine("recordCount=${marker.records.size}")
            marker.records.forEachIndexed { index, record ->
                appendLine("record.$index.path=${encodeToken(record.relativePath)}")
                appendLine("record.$index.size=${record.byteSize}")
                appendLine("record.$index.sha256=${record.sha256}")
            }
        }
        atomicWrite(file, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            moveAtomically(temporary, file)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun encodeToken(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeToken(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8
    )
}
