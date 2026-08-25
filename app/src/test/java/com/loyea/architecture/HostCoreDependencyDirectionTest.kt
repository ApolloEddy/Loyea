package com.loyea.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.jar.JarFile

/**
 * 批次4：依赖方向架构测试。
 *
 * 不依赖 import 门禁（那是源码文本 grep），而是直接检查编译产物的方法/字段/构造器签名：
 * - 稳定基座（plugin-api / plugin-host / knowledge-core）不得在任何签名中引用 Tavern 插件具体类型。
 * - 宿主核心类（持久化 / 提示词配置 / Worker / 设置与聊天渲染面）的 public+internal 签名不得携带
 *   tavern-core 具体类型，即使这些文件因内部实现被允许 import tavern 包。
 * - 任何 app 类的签名若引用 tavern 类型，其源文件必须是白名单适配器 / 组合根 / 控制面 / 迁移桥。
 *
 * 说明：
 * - Kotlin `internal` 成员在字节码里是 public（无模块名混淆时），因此 `Modifier.isPublic` 同时覆盖
 *   public 与 internal 两类宿主签名；`private` 成员视为实现细节，不检查。
 * - 跳过合成成员（`$default` 桥、accessor 等）与 lambda/匿名类（名字含 `$<digit>`，是私有实现）。
 * - 类文件可能以目录或 jar 形式出现在测试 classpath，两者统一处理；SourceFile 属性从 class 字节读取，
 *   以精确映射“编译类 -> 源文件”，避免按文件名猜测。
 */
class HostCoreDependencyDirectionTest {

    private val tavernPackagePrefix = "com.loyea.plugins.tavern."

    private data class ClassRecord(val className: String, val sourceFile: String?)

    // ------------------------------------------------------------------
    // classpath 定位与类文件枚举（目录 / jar 统一）
    // ------------------------------------------------------------------

    private fun classpathEntryFor(marker: String): Any {
        val entries = System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map { File(it) }
        entries.firstOrNull { it.isDirectory && File(it, marker).isFile }?.let { return it }
        entries.firstOrNull { it.isFile && it.name.endsWith(".jar") && jarContains(it, marker) }
            ?.let { return it }
        error("classpath entry not found for $marker among ${entries.size} entries")
    }

    private fun jarContains(jarFile: File, entryName: String): Boolean =
        runCatching { JarFile(jarFile).use { it.getJarEntry(entryName) != null } }.getOrDefault(false)

    private fun scanClasses(marker: String): List<ClassRecord> {
        val packagePrefix = marker.substringBeforeLast('/') // e.g. com/loyea/ui/chat
        val entry = classpathEntryFor(marker)
        val records = mutableListOf<ClassRecord>()
        if (entry is File && entry.isDirectory) {
            val root = entry
            File(root, packagePrefix).walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".class")) {
                    val className = file.relativeTo(root).invariantSeparatorsPath
                        .removeSuffix(".class")
                        .replace('/', '.')
                    records += ClassRecord(className, sourceFileOf(file.readBytes()))
                }
            }
        } else {
            val jar = JarFile(entry as File)
            jar.use { jf ->
                val enumeration = jf.entries()
                while (enumeration.hasMoreElements()) {
                    val entryItem = enumeration.nextElement()
                    if (entryItem.name.endsWith(".class") && entryItem.name.startsWith("$packagePrefix/")) {
                        val bytes = jf.getInputStream(entryItem).readBytes()
                        val className = entryItem.name.removeSuffix(".class").replace('/', '.')
                        records += ClassRecord(className, sourceFileOf(bytes))
                    }
                }
            }
        }
        return records
    }

    private fun appClasses(): List<ClassRecord> = scanClasses("com/loyea/ui/chat/ChatStorageManager.class")

    // ------------------------------------------------------------------
    // class 字节解析：SourceFile 属性
    // ------------------------------------------------------------------

    private fun sourceFileOf(data: ByteArray): String? {
        var pos = 8 // magic(4) + minor(2) + major(2)
        val cpCount = readU2(data, pos); pos += 2
        val utf8 = HashMap<Int, String>()
        var index = 1
        while (index < cpCount) {
            val tag = data[pos].toInt() and 0xFF; pos += 1
            when (tag) {
                1 -> { // CONSTANT_Utf8
                    val len = readU2(data, pos); pos += 2
                    utf8[index] = String(data, pos, len, Charsets.UTF_8)
                    pos += len
                }
                3, 4, 9, 10, 11, 12, 17, 18, 19, 20 -> pos += 4
                5, 6 -> { pos += 8; index += 1 } // long/double take two pool slots
                7, 8, 16 -> pos += 2
                15 -> pos += 3 // MethodHandle
                else -> return null
            }
            index += 1
        }
        pos += 6 // access_flags(2) + this_class(2) + super_class(2)
        val interfacesCount = readU2(data, pos); pos += 2
        pos += interfacesCount * 2
        val fieldsCount = readU2(data, pos); pos += 2
        repeat(fieldsCount) { pos += 6 + attributeBlockSize(data, pos + 6) }
        val methodsCount = readU2(data, pos); pos += 2
        repeat(methodsCount) { pos += 6 + attributeBlockSize(data, pos + 6) }
        val attributesCount = readU2(data, pos); pos += 2
        repeat(attributesCount) {
            val nameIndex = readU2(data, pos); pos += 2
            val len = readU4(data, pos); pos += 4
            if (utf8[nameIndex] == "SourceFile") {
                return utf8[readU2(data, pos)]
            }
            pos += len
        }
        return null
    }

    private fun attributeBlockSize(data: ByteArray, start: Int): Int {
        var pos = start
        val count = readU2(data, pos); pos += 2
        repeat(count) {
            pos += 2 // name_index
            val len = readU4(data, pos); pos += 4
            pos += len
        }
        return pos - start
    }

    private fun readU2(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)

    private fun readU4(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)

    // ------------------------------------------------------------------
    // 签名反射
    // ------------------------------------------------------------------

    private fun isLambdaOrAnonymous(name: String): Boolean =
        Regex("""\$[0-9]""").containsMatchIn(name)

    private fun collectTypeRefs(type: Type, sink: (String) -> Unit) {
        when (type) {
            is Class<*> -> sink(type.name)
            is ParameterizedType -> {
                collectTypeRefs(type.rawType, sink)
                type.actualTypeArguments.forEach { collectTypeRefs(it, sink) }
            }
            is GenericArrayType -> collectTypeRefs(type.genericComponentType, sink)
            is WildcardType -> {
                type.upperBounds.forEach { collectTypeRefs(it, sink) }
                type.lowerBounds.forEach { collectTypeRefs(it, sink) }
            }
            is TypeVariable<*> -> type.bounds.forEach { collectTypeRefs(it, sink) }
            else -> Unit
        }
    }

    private fun Class<*>.signatureTavernRefs(where: String, violations: MutableList<String>) {
        val superRefs = mutableListOf<String>()
        runCatching { genericSuperclass }.getOrNull()?.let { collectTypeRefs(it, superRefs::add) }
        runCatching { genericInterfaces }.getOrNull()?.forEach { collectTypeRefs(it, superRefs::add) }
        superRefs.firstOrNull { it.startsWith(tavernPackagePrefix) }?.let {
            violations += "$where . extends -> $it"
        }
        declaredMethods
            .filterNot { it.isSynthetic }
            .filter { Modifier.isPublic(it.modifiers) }
            .forEach { method ->
                runCatching {
                    val local = mutableListOf<String>()
                    collectTypeRefs(method.genericReturnType, local::add)
                    method.genericParameterTypes.forEach { collectTypeRefs(it, local::add) }
                    method.genericExceptionTypes.forEach { collectTypeRefs(it, local::add) }
                    local.firstOrNull { it.startsWith(tavernPackagePrefix) }?.let {
                        violations += "$where . ${method.name} -> $it"
                    }
                }
            }
        declaredFields
            .filterNot { it.isSynthetic }
            .filter { Modifier.isPublic(it.modifiers) }
            .forEach { field ->
                runCatching {
                    val local = mutableListOf<String>()
                    collectTypeRefs(field.genericType, local::add)
                    local.firstOrNull { it.startsWith(tavernPackagePrefix) }?.let {
                        violations += "$where . field ${field.name} -> $it"
                    }
                }
            }
        declaredConstructors
            .filterNot { it.isSynthetic }
            .forEach { ctor ->
                runCatching {
                    val local = mutableListOf<String>()
                    ctor.genericParameterTypes.forEach { collectTypeRefs(it, local::add) }
                    ctor.genericExceptionTypes.forEach { collectTypeRefs(it, local::add) }
                    local.firstOrNull { it.startsWith(tavernPackagePrefix) }?.let {
                        violations += "$where . <init> -> $it"
                    }
                }
            }
    }

    private fun load(className: String): Class<*>? =
        runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull()

    private fun checkSignature(record: ClassRecord, where: String, violations: MutableList<String>) {
        if (isLambdaOrAnonymous(record.className)) return
        val clazz = load(record.className)
        if (clazz == null) {
            // 加载失败意味着无法验证其签名——宁可大声失败，也不静默漏检。
            violations += "$where : UNABLE TO LOAD ${record.className}"
            return
        }
        // Kotlin private 顶层类编译为包私有（无 ACC_PUBLIC），仅在源文件内可见，属实现细节，
        // 其成员即使引用 tavern 类型也不构成对外签名泄漏。
        if (!Modifier.isPublic(clazz.modifiers)) return
        clazz.signatureTavernRefs("$where : ${clazz.simpleName}", violations)
    }

    // ------------------------------------------------------------------
    // 1. 稳定基座不得引用 Tavern 具体类型
    // ------------------------------------------------------------------

    @Test
    fun stableContractsNeverReferenceTavernTypes() {
        val markers = mapOf(
            "com/loyea/plugin/api/PluginDescriptor.class" to "plugin-api",
            "com/loyea/plugin/host/PluginManager.class" to "plugin-host",
            "com/loyea/context/core/WorldInfoEntry.class" to "knowledge-core"
        )
        val violations = mutableListOf<String>()
        markers.forEach { (marker, module) ->
            scanClasses(marker).forEach { record ->
                checkSignature(record, "$module : ${record.className}", violations)
            }
        }
        assertTrue(
            "Stable contracts must never reference Tavern plugin types in signatures:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ------------------------------------------------------------------
    // 2. 宿主核心（持久化 / Worker / 设置 / 聊天渲染面）签名不得携带 Tavern 类型
    // ------------------------------------------------------------------

    @Test
    fun hostCoreSignaturesDoNotExposeTavernTypes() {
        val cleanSources = setOf(
            "ChatStorageManager.kt",
            "WorldInfoConfig.kt",
            "GreetingWorker.kt",
            "WorldInfoSettings.kt",
            "ChatScreen.kt"
        )
        val violations = mutableListOf<String>()
        appClasses().forEach { record ->
            val source = record.sourceFile ?: return@forEach
            if (source !in cleanSources) return@forEach
            checkSignature(record, "${record.className} [$source]", violations)
        }
        assertTrue(
            "Host core signatures must not carry Tavern plugin types:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ------------------------------------------------------------------
    // 3. 引用 Tavern 类型的 app 签名只能来自白名单适配面
    // ------------------------------------------------------------------

    @Test
    fun onlyAdapterSourcesExposeTavernTypesInSignatures() {
        // 允许在 public/internal 签名携带 tavern 具体类型的宿主源文件：
        // 适配器 / 组合根 / Tavern 控制面 / 迁移桥（见 docs/tavern_plugin_refactor.md）。
        val allowedSources = setOf(
            "TavernCardParser.kt",
            "TavernCharacterCardAdapter.kt",
            "TavernCardPresetAdapter.kt",
            "TavernCardRegexAdapter.kt",
            "TavernCardResourceBindings.kt",
            "TavernCardDownloader.kt",
            "TavernChatSessionCodec.kt",
            "TavernGroupReplyCoordinator.kt",
            "LegacyTavernTurnAdapter.kt",
            "AppTavernPersonaRepository.kt",
            "CharacterPersonaOwnership.kt",
            "TavernFieldDropMigration.kt",
            "TavernForkTitlePolicy.kt",
            "BackgroundPromptTemplates.kt",
            "LoyeaApplication.kt",
            "MainActivity.kt",
            "ChatViewModel.kt",
            "ChatScreen.kt",
            "TavernScreen.kt",
            "WorldInfoSettings.kt",
            "PromptAssembler.kt"
        )
        val violations = mutableListOf<String>()
        appClasses().forEach { record ->
            val source = record.sourceFile ?: return@forEach
            if (source in allowedSources) return@forEach
            checkSignature(record, "${record.className} [$source]", violations)
        }
        assertTrue(
            "Only adapter/composition/control-surface sources may expose Tavern types:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
