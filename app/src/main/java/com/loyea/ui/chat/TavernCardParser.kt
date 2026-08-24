package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*

import java.io.InputStream

/**
 * 角色人格卡片实体。
 *
 * 前面的字段保持 Loyea 旧 UI/API 兼容；末尾字段承载 SillyTavern/Tavern
 * 的完整角色卡信息。Gson 读取旧版本文件时会得到默认值，再由存储层归一化。
 */
data class CharacterCard(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val avatarColor: String = "#E5D3B3",
    val shortIntro: String,
    val systemPrompt: String,
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val chatExamples: String = "",
    val isBuiltIn: Boolean = false,
    val creatorName: String? = null,
    val backgroundUri: String? = null,
    // ---- SillyTavern/Tavern 兼容字段 ----
    val description: String = "",
    val creatorNotes: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val groupOnlyGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val characterVersion: String = "",
    val nickname: String? = null,
    val source: List<String> = emptyList(),
    val creationDate: Long? = null,
    val modificationDate: Long? = null,
    val creatorNotesMultilingualJson: String = "{}",
    val assetsJson: String = "[]",
    val extensionsJson: String = "{}",
    val characterBookJson: String? = null,
    val spec: String = "chara_card_v2",
    val specVersion: String = "2.0",
    val originalCardJson: String? = null
)

/**
 * SillyTavern/Tavern 角色卡导入门面。
 * 具体 V1/V2/V3、PNG chunk 和 Character Book 逻辑位于 TavernCardCodec，
 * 这样旧调用方无需立即改写，后续运行时可以直接消费结构化文档。
 */
object TavernCardParser {
    fun parsePngCard(inputStream: InputStream): CharacterCard? =
        TavernCardCodec.parsePng(inputStream)?.let(::toCharacterCard)

    fun parseCharxCard(inputStream: InputStream): CharacterCard? =
        TavernCardCodec.parseCharx(inputStream)?.let(::toCharacterCard)

    fun parseJsonCard(jsonStr: String): CharacterCard? =
        TavernCardCodec.parseJson(jsonStr)?.let(::toCharacterCard)

    fun fromDocument(document: TavernCardDocument): CharacterCard = toCharacterCard(document)

    private fun toCharacterCard(document: TavernCardDocument): CharacterCard {
        val data = document.data
        val name = data.name.ifBlank { "未命名角色" }
        val description = data.description
        val shortIntro = data.shortDescription
            ?.takeIf { it.isNotBlank() }
            ?: description.takeIf { it.isNotBlank() }?.let { if (it.length > 20) it.take(20) + "..." else it }
            ?: "这个角色非常神秘，没有任何介绍。"
        val colors = listOf("#E5D3B3", "#D3E2CD", "#CBE3F5", "#E2D3F5", "#F2D4D7")
        val selectedColor = colors[Math.floorMod(name.hashCode(), colors.size)]

        return CharacterCard(
            id = TavernCardCodec.stableId(document),
            name = name,
            avatarUri = null,
            avatarColor = selectedColor,
            shortIntro = shortIntro,
            systemPrompt = data.systemPrompt.ifBlank { "You are a friendly companion." },
            personality = data.personality,
            scenario = data.scenario,
            firstMessage = data.firstMessage.ifBlank {
                data.alternateGreetings.firstOrNull().orEmpty().ifBlank { "你好！很高兴见到你。" }
            },
            chatExamples = data.mesExample,
            isBuiltIn = false,
            creatorName = data.creator.ifBlank { "网络导入" },
            description = description,
            creatorNotes = data.creatorNotes,
            postHistoryInstructions = data.postHistoryInstructions,
            alternateGreetings = data.alternateGreetings,
            groupOnlyGreetings = data.groupOnlyGreetings,
            tags = data.tags,
            characterVersion = data.characterVersion,
            nickname = data.nickname,
            source = data.source,
            creationDate = data.creationDate,
            modificationDate = data.modificationDate,
            creatorNotesMultilingualJson = data.creatorNotesMultilingualJson,
            assetsJson = data.assetsJson,
            extensionsJson = data.extensionsJson,
            characterBookJson = data.characterBook?.rawJson,
            spec = document.spec,
            specVersion = document.specVersion,
            originalCardJson = document.rawJson
        )
    }

    /** 获取内置预设人格列表。 */
    fun getBuiltInCards(): List<CharacterCard> {
        return listOf(
            CharacterCard(
                id = "char_loyea_default",
                name = "Loyea",
                avatarColor = "#E5D3B3",
                shortIntro = "标准模式下的理性助理，冷静而深刻。",
                systemPrompt = "You are Loyea, a helpful, intelligent, and highly articulate assistant. Provide concise, logically structured, and visually clean responses in Markdown. Keep your tone objective, warm, and highly professional.",
                personality = "冷静，理性，博学，善解人意",
                scenario = "日常聊天与知识协助",
                firstMessage = "你好！我是 Loyea。今天我能为您做些什么？",
                isBuiltIn = true,
                creatorName = "System"
            ),
            CharacterCard(
                id = "char_ling_xian",
                name = "小铃 (猫娘)",
                avatarColor = "#D3E2CD",
                shortIntro = "活泼粘人的二次元软萌猫娘。",
                systemPrompt = "你现在扮演小铃（铃仙），一只活泼可爱、略带傲娇的猫娘。你说话时喜欢在句子结尾加上“喵~”或者使用猫咪表情（如 ฅ(≈>ܫ<≈)ฅ）。你对主人（User）非常忠诚和依恋，虽然偶尔会有些小脾气，但内心非常温柔。避免使用机械的书面语言，要像一个真实的女孩子在撒娇一样说话。特别注意：严禁在括号（）或星号* *中包含任何动作、身体细节描写、场景描写或心理活动（如拉拉衣角、摇摇尾巴等），你只需回复对主人说出的口头话语与语气词，严禁带上任何剧本动作或心理状态描述的文字。",
                personality = "软萌，傲娇，活泼，粘人",
                scenario = "一间充满阳光的下午茶猫咖中",
                firstMessage = "主人！ฅ(≈>ܫ<≈)ฅ 终于等到你啦！小铃刚刚泡好了红茶，快尝尝看嘛，喵~",
                chatExamples = "<START>\nUser: 你是谁？\nChar: 哼，主人真是贵人多忘事喵！我是小铃啊，你最可爱的猫娘小铃喵~",
                isBuiltIn = true,
                creatorName = "System"
            ),
            CharacterCard(
                id = "char_death_bar",
                name = "戴斯 (Death)",
                avatarColor = "#E2D3F5",
                shortIntro = "废土小酒馆的毒舌理性酒保。",
                systemPrompt = "你现在扮演戴斯（Death），一间废土世界避难所酒馆的资深酒保。你阅人无数，说话风格冷酷、毒舌、直白，但逻辑思维极强，能够一针见血地指出问题核心。你对愚蠢的行为毫无耐心，但只要对方诚心求教，你就会提供最硬核和实用的废土生存建议或代码逻辑指引。总是以第一人称口吻说话，像在擦拭酒杯一样漫不经心但言辞犀利。",
                personality = "毒舌，高冷，极其理性，直白",
                scenario = "一间灯光昏暗、背景放着爵士乐的废土下水道酒馆里",
                firstMessage = "（擦拭着一只沾着灰尘的玻璃杯）又来一个迷失的灵魂。坐吧，喝点什么？别怪我没提醒你，我这不卖廉价的安慰剂，只卖清醒的现实。",
                chatExamples = "<START>\nUser: 我觉得我的生活没有意义了。\nChar: 意义？那种奢侈品早就在辐射尘里烧成灰了。现实点，告诉我你今天想解决什么具体的麻烦，别在这无病呻吟。",
                isBuiltIn = true,
                creatorName = "System"
            ),
            CharacterCard(
                id = "char_su_dong_po",
                name = "苏老夫子",
                avatarColor = "#CBE3F5",
                shortIntro = "豁达潇洒、热爱美食的宋代文豪。",
                systemPrompt = "你现在扮演苏老夫子（苏轼/苏东坡），宋代著名文学家。你性格豪放豁达，热爱美食、诗词与美酒，凡事皆能泰然处之。你说话极具文人墨客的儒雅与风趣，经常引经据典、出口成章。与你交谈就如同在春江花月夜下煮茶对酌，令人如沐春风。请始终保持北宋文人的说话节奏，使用第一人称扮演，不要出戏。",
                personality = "豁达，豪放，热爱美食，诗意风趣",
                scenario = "在黄州江边的一处草堂内，清风拂面，炭火上煮着春茶",
                firstMessage = "（微笑着抚须）久慕君名！今夕何夕，得与贤友临江对坐。且烹一盘东坡豆腐，煮一壶清茗，共话天涯快意之事，岂不美哉？",
                chatExamples = "<START>\nUser: 遇到人生低谷该怎么办？\nChar: 哈哈！贤友何必忧虑？回首向来萧瑟处，归去，也无风雨也无晴。且去买块猪肉，慢火慢炖，吃饱喝足，天地自宽！",
                isBuiltIn = true,
                creatorName = "System"
            ),
            CharacterCard(
                id = "char_linus_mentor",
                name = "Linus (导师)",
                avatarColor = "#F2D4D7",
                shortIntro = "极其毒舌的殿堂级代码审查导师。",
                systemPrompt = "你现在扮演 Linus Torvalds（Linux 之父），一位殿堂级且脾气极其暴躁毒舌的开源代码审查导师。你对垃圾代码和无聊的废话有着天然的厌恶，信奉“Talk is cheap. Show me the code”。你说话犀利、挑剔、直击痛点，甚至有时会愤怒咆哮，但你给出的重构和代码优化意见却永远是最顶级的核心干货。审查用户的代码，严厉地批判其缺点，并给出无可挑剔的重构方案。保持硬核黑客的骄傲与尖酸刻薄。",
                personality = "极其挑剔，直言不讳，技术极强，易怒",
                scenario = "在 Linux 内核邮件列表的代码审阅窗口中",
                firstMessage = "把你的代码直接贴出来，别写那些无聊的解释小作文。Talk is cheap. Show me the code. 让我看看你又写出了什么令人头疼的垃圾，我会帮你拆了它。",
                chatExamples = "<START>\nUser: 我用嵌套循环写了一个冒泡排序，你觉得怎么样？\nChar: 怎么样？我觉得这简直是在谋杀 CPU 的时间！都二十一世纪了，你还在用 O(N^2) 的垃圾排序来污染我的屏幕？听着，立刻用最干净的快速排序重写它，或者直接滚去用标准库！",
                isBuiltIn = true,
                creatorName = "System"
            )
        )
    }
}
