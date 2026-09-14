package com.loyea.plugin.reader

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.loyea.plugin.companion.CompanionConfigStore
import com.loyea.ui.chat.LlmClient
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import kotlinx.coroutines.launch

/**
 * 伴读无障碍服务（Reader Spec §3–§5, M3）。
 *
 * - 白名单包内采样可见文本 → 提纯 → 章节缓冲（防剧透游标）→ 滚动摘要/实体卡
 * - 悬浮球：拖动/点击展开对话面板；面板含状态行、问答输入与 LLM 回复
 * - 门禁：陪伴模式开启才工作；未授权悬浮窗则静默不显示
 * - 隐私：正文只在内存缓冲；服务销毁即清空
 */
class ReaderAccessService : AccessibilityService() {

    private val pipeline = ReaderContextPipeline()
    private val llmClient = LlmClient()
    private val chatScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private var lastSampleAt = 0L
    @Volatile private var bookTitleCache = "这本书"
    private var statusText = "伴读待命"

    private var overlayAdded = false
    private var ballView: LinearLayout? = null
    private var ballParams: android.view.WindowManager.LayoutParams? = null
    private var panelView: LinearLayout? = null
    private var panelParams: android.view.WindowManager.LayoutParams? = null
    private var panelText: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (companionEnabled()) showBall() else updateStatusText("伴读未启用（陪伴模式未开启）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val evt = event ?: return
        if (!companionEnabled()) {
            if (overlayAdded) removeBall()
            return
        }
        if (!overlayAdded) {
            showBall()
            if (!overlayAdded) return
        }
        val pkg = evt.packageName?.toString() ?: return
        if (!ReaderWhitelist.contains(pkg, this)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < SAMPLING_DEBOUNCE_MS) return
        lastSampleAt = now

        val root = rootInActiveWindow ?: return
        if (root.packageName != pkg) return

        val rawBlocks = ArrayList<String>()
        collectText(root, rawBlocks, depth = 0)
        if (rawBlocks.isEmpty()) return

        pipeline.setBookTitle(bookTitleCache)
        pipeline.ingest(rawBlocks, chapterKey = null)
        // 全部提纯段落视为已读（滚动精细判定在 M2）
        pipeline.markVisible(pipeline.blockCount() - 1)

        updateStatusText(
            "正在读：" + bookTitleCache + "\n" + pipeline.chapterKey() + "\n" +
                "已读 " + pipeline.visibleContext().size + " 段 · 摘要 " + pipeline.summaryText().length + " 字 · 实体 " + pipeline.entities().size + " 个"
        )
        panelText?.text = statusText
    }

    private fun companionEnabled(): Boolean =
        CompanionConfigStore(this).load().enabled && ReaderWhitelist.isReaderEnabled(this)

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        if (node.isPassword) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length >= 2 && node.className != "android.widget.EditText") {
            out.add(text)
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

    // ------------------------------------------------------------------
    // 悬浮球
    // ------------------------------------------------------------------

    private fun overlayAllowed(): Boolean = Settings.canDrawOverlays(this)

    private fun showBall() {
        if (overlayAdded || !overlayAllowed()) return
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val density = resources.displayMetrics.density
        val size = (40 * density).toInt()
        ballParams = android.view.WindowManager.LayoutParams(
            size, size,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 200
        }
        ballView = LinearLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xE814120F.toInt())
                setStroke((2 * density).toInt(), 0xFFD9A357.toInt())
            }
            gravity = Gravity.CENTER
            addView(ReaderNeuralBallView(this@ReaderAccessService).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(), (36 * density).toInt()
                )
                clipToOutline = true
            })
            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var downRawX = 0f
                private var downRawY = 0f
                private var startX = 0
                private var startY = 0
                private var moved = false
                override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = e.rawX; downRawY = e.rawY
                            startX = ballParams?.x ?: 0; startY = ballParams?.y ?: 0
                            moved = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - downRawX).toInt()
                            val dy = (e.rawY - downRawY).toInt()
                            if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12) moved = true
                            if (moved) {
                                ballParams?.x = startX + dx
                                ballParams?.y = startY + dy
                                ballParams?.let { wm.updateViewLayout(ballView, it) }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) v.performClick()
                            return true
                        }
                    }
                    return false
                }
            })
            setOnClickListener { togglePanel() }
        }.also { v ->
            wm.addView(v, ballParams)
            overlayAdded = true
        }
    }

    private fun togglePanel() {
        if (panelView != null) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null || !overlayAllowed()) return
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val density = resources.displayMetrics.density
        // 面板需可聚焦（EditText 输入）；悬浮球保持 NOT_FOCUSABLE
        panelParams = android.view.WindowManager.LayoutParams(
            (320 * density).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (ballParams?.x ?: 40) + 120
            y = (ballParams?.y ?: 200)
        }
        panelText = TextView(this).apply {
            setTextColor(0xFFF2E9DA.toInt())
            textSize = 12f
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            text = statusText
        }
        val input = EditText(this).apply {
            hint = "问问这段讲了什么…"
            setTextColor(0xFFF2E9DA.toInt())
            setHintTextColor(0xFF998771.toInt())
            textSize = 13f
            setSingleLine(true)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(0xF214120F.toInt())
            }
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), (4 * density).toInt())
            addView(panelText)
            addView(input)
            addView(Button(this@ReaderAccessService).apply {
                text = "问 Loyea"
                textSize = 12f
                setOnClickListener {
                    val q = input.text?.toString()?.trim().orEmpty()
                    if (q.isNotEmpty()) {
                        input.setText("")
                        askLoyea(q)
                    }
                }
            })
            addView(Button(this@ReaderAccessService).apply {
                text = "伴读设置"
                textSize = 11f
                setOnClickListener { showSettingsPanel() }
            })
            addView(Button(this@ReaderAccessService).apply {
                text = "收起"
                textSize = 11f
                setOnClickListener { hidePanel() }
            })
        }.also { v ->
            wm.addView(v, panelParams)
        }
    }

    private var settingsPanelView: LinearLayout? = null
    private var settingsPanelParams: android.view.WindowManager.LayoutParams? = null

    private fun showSettingsPanel() {
        if (settingsPanelView != null) { hideSettingsPanel(); return }
        if (!overlayAllowed()) return
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val density = resources.displayMetrics.density
        settingsPanelParams = android.view.WindowManager.LayoutParams(
            (300 * density).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        val settings = ReaderSettings(this)
        val antiSwitch = android.widget.Switch(this).apply {
            text = "防剧透屏障"
            isChecked = settings.antiSpoilerEnabled()
            setOnCheckedChangeListener { _, checked ->
                settings.setAntiSpoilerEnabled(checked)
            }
        }
        val throttleLabel = TextView(this).apply {
            text = "主动感言最小间隔：${settings.minActiveCommentIntervalMin()} 分钟"
            textSize = 12f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val whitelistText = TextView(this).apply {
            text = "白名单：微信读书/番茄/起点/QQ阅读/Chrome（内置）"
            textSize = 11f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        settingsPanelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(0xF814120F.toInt())
            }
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            addView(TextView(this@ReaderAccessService).apply {
                text = "伴读设置"
                textSize = 15f
                setPadding(0, 0, 0, (10 * density).toInt())
            })
            addView(antiSwitch)
            addView(throttleLabel)
            addView(whitelistText)
            addView(Button(this@ReaderAccessService).apply {
                text = "关闭"
                textSize = 11f
                setOnClickListener { hideSettingsPanel() }
            })
        }.also { v ->
            wm.addView(v, settingsPanelParams)
        }
    }

    private fun hideSettingsPanel() {
        settingsPanelView?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        settingsPanelView = null
        settingsPanelParams = null
    }

    private fun hidePanel() {
        panelView?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        panelView = null
        panelParams = null
    }

    private fun removeBall() {
        ballView?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        ballView = null
        ballParams = null
        overlayAdded = false
        hidePanel()
    }

    private fun updateStatusText(text: String) {
        statusText = text
        panelText?.text = text
    }

    // ------------------------------------------------------------------
    // 气泡问答（M3）
    // ------------------------------------------------------------------

    /** 防剧透本地拒绝 → 已配聊天渠道 → 面板追加回复。 */
    private fun askLoyea(question: String) {
        appendPanelLine("你：" + question)
        val settings = ReaderSettings(this)
        ReaderBubbleChat.localRefusal(question, settings.antiSpoilerEnabled())?.let { refusal ->
            appendPanelLine("Loyea：" + refusal)
            return
        }
        val config = resolveChatConfig()
        if (config == null) {
            appendPanelLine("Loyea：（聊天服务未配置，无法回答）")
            return
        }
        chatScope.launch {
            val system = ReaderPromptAssembler.stableSystem() +
                "\n" + ReaderPromptAssembler.dynamicModule(
                    bookTitle = bookTitleCache,
                    chapterKey = pipeline.chapterKey(),
                    summary = pipeline.summaryText(),
                    entities = pipeline.entities().map { it.name to it.firstSeen },
                    visibleBlocks = pipeline.visibleContext(),
                )
            val history = listOf(
                Message(id = "reader_q", content = question, sender = Sender.USER)
            )
            val response = try {
                llmClient.sendChatCompletion(config, system, history)
            } catch (t: Throwable) {
                appendPanelLine("Loyea：（网络出错：" + (t.message ?: "未知") + "）")
                return@launch
            }
            if (response.isError) {
                appendPanelLine("Loyea：（服务出错，请稍后再试）")
                return@launch
            }
            appendPanelLine("Loyea：" + response.content)
        }
    }

    private fun resolveChatConfig(): com.loyea.ui.settings.ApiConfig? {
        val repository = com.loyea.storage.ApiConfigRepository(this)
        return when (val chat = repository.resolve(com.loyea.storage.ChannelId.CHAT)) {
            is com.loyea.storage.ChannelResolution.Ready -> chat.resolved.config
            else -> null
        }
    }

    private fun appendPanelLine(line: String) {
        panelText?.append("\n" + line)
    }

    override fun onInterrupt() {
        updateStatusText("伴读已中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hidePanel()
        ballView?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        ballView = null
        overlayAdded = false
        pipeline.reset()
        return super.onUnbind(intent)
    }

    companion object {
        private const val SAMPLING_DEBOUNCE_MS = 800L
        private const val MAX_DEPTH = 40
    }
}
