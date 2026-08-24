package com.loyea.plugins.tavern.storage

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Plugin-private file layout. Session metadata/messages intentionally stay outside this root.
 * Every path returned by this class is constrained to [root], so imported IDs cannot escape the
 * plugin data directory.
 */
data class TavernStorageLayout(
    val root: File
) {
    val registryRelativePath: String = "registry/tavern_resources.json"
    val cardsRelativeDirectory: String = "cards"
    val assetsRelativeDirectory: String = "assets"
    val migrationMarkerRelativePath: String = ".migration-v1.marker"

    val registryFile: File
        get() = resolve(registryRelativePath)

    val cardsDirectory: File
        get() = resolve(cardsRelativeDirectory)

    val assetsDirectory: File
        get() = resolve(assetsRelativeDirectory)

    val migrationMarkerFile: File
        get() = resolve(migrationMarkerRelativePath)

    fun ensureDirectories() {
        check(root.exists() || root.mkdirs() || root.isDirectory) {
            "Unable to create Tavern storage root: ${root.absolutePath}"
        }
        listOf(cardsDirectory, assetsDirectory, registryFile.parentFile!!).forEach { directory ->
            check(directory.exists() || directory.mkdirs() || directory.isDirectory) {
                "Unable to create Tavern storage directory: ${directory.absolutePath}"
            }
        }
    }

    /** Maps a stable card ID to a path without exposing arbitrary IDs as filesystem names. */
    fun cardDocumentRelativePath(cardId: String): String {
        require(cardId.isNotBlank()) { "Card ID must not be blank" }
        return "$cardsRelativeDirectory/${sha256Hex(cardId.trim())}.json"
    }

    fun resolve(relativePath: String): File {
        val normalized = TavernStoragePath.normalize(relativePath)
        val canonicalRoot = root.canonicalFile
        val resolved = File(canonicalRoot, normalized).canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(resolved.path.startsWith(rootPrefix)) {
            "Tavern storage path escaped root: $relativePath"
        }
        return resolved
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

data class TavernStorageFileSpec(
    val sourceRelativePath: String,
    val targetRelativePath: String,
    val required: Boolean = true
) {
    init {
        TavernStoragePath.normalize(sourceRelativePath)
        TavernStoragePath.normalize(targetRelativePath)
    }
}

data class TavernStorageFileRecord(
    val relativePath: String,
    val byteSize: Long,
    val sha256: String
) {
    init {
        TavernStoragePath.normalize(relativePath)
        require(byteSize >= 0L) { "File size must not be negative" }
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" }) {
            "File SHA-256 must be a lowercase 64-character hex digest"
        }
    }
}

object TavernStoragePath {
    fun normalize(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        require(normalized.isNotBlank()) { "Storage path must not be blank" }
        require(!normalized.startsWith('/') && !normalized.contains(':')) {
            "Storage path must be relative: $path"
        }
        require(!normalized.contains('\u0000')) { "Storage path must not contain NUL" }
        val segments = normalized.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Storage path contains an unsafe segment: $path"
        }
        return segments.joinToString("/")
    }
}
