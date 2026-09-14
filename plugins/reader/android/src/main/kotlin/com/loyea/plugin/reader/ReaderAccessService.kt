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
import android.widget.LinearLayout
import android.widget.TextView
import com.loyea.plugin.companion.CompanionConfigStore

/**
 * 伴读无障碍服务（Reader Spec §3–§5, M1）。
 *
 * - 白名单包内采样可见文本 → 提纯 → 章节缓冲（防剧透游标）
 * - 悬浮球：拖动/吸边由用户手势决定；点击展开状态气泡
 * - 门禁：陪伴模式开启才工作；未授权悬浮窗则静默不显示
 * - 隐私：正文只在内存缓冲；服务销毁即清空
 */
class ReaderAccessService : AccessibilityService() {

    private val session = ReaderPurifierSession()
    private val buffer = ReaderChapterBuffer()
    private var lastSampleAt = 0L
    private var lastBookTitle = ""
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
        // 门禁自愈：陪伴开启→确保悬浮球；关闭→移除。服务常驻，状态可随时翻转。
        if (!companionEnabled()) {
            if (overlayAdded) removeBall()
            return
        }
        if (!overlayAdded) {
            showBall()
            if (!overlayAdded) return // 无悬浮窗授权
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

        val purified = session.purify(rawBlocks)
        if (ReaderTextPurifier.isTrivialSample(purified)) return

        val chapterFromText = purified.firstOrNull { ReaderTextPurifier.chapterTitleOf(it) != null }
            ?.let { ReaderTextPurifier.chapterTitleOf(it) }
        val chapterKey = chapterFromText ?: lastBookTitle.ifEmpty { "未命名章节" }

        val switched = buffer.ingest(purified, chapterKey)
        if (switched) session.reset()

        // M1 简化：本次采样最后一段视为可见（滚动精细判定在 M2）
        buffer.markVisible(buffer.currentBlocks.size - 1)

        updateStatusText(
            "正在读：${bookTitle()}\n${buffer.currentKey}\n已读 ${buffer.visibleContext().size}/${buffer.currentBlocks.size} 段"
        )
        panelText?.text = statusText
    }

    private fun bookTitle(): String {
        if (lastBookTitle.isNotEmpty()) return lastBookTitle
        return "这本书"
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
    // 悬浮球（M1）
    // ------------------------------------------------------------------

    private fun overlayAllowed(): Boolean = Settings.canDrawOverlays(this)

    private fun removeBall() {
        ballView?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        ballView = null
        ballParams = null
        overlayAdded = false
        hidePanel()
    }

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
                setColor(0xCC14120F.toInt())
                setStroke((2 * density).toInt(), 0xFFD9A357.toInt())
            }
            gravity = Gravity.CENTER
            addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams((10 * density).toInt(), (10 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFFD9A357.toInt())
                }
            })
            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var downRawX = 0f; private var downRawY = 0f
                private var startX = 0; private var startY = 0
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
                            val dx = (e.rawX - downRawX).toInt(); val dy = (e.rawY - downRawY).toInt()
                            if (abs(dx) + abs(dy) > 12) moved = true
                            if (moved) {
                                ballParams?.x = startX + dx
                                ballParams?.y = startY + dy
                                ballParams?.let { wm.updateViewLayout(ballView, it) }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) v.performClick() // 停留即点击
                            return true
                        }
                    }
                    return false
                }
            })
            setOnClickListener { 
                android.util.Log.i("ReaderAccess", "ball clicked, overlayAllowed=" + overlayAllowed())
                togglePanel()
            }
        }.also { v ->
            wm.addView(v, ballParams)
            overlayAdded = true
        }
    }

    private fun abs(v: Int): Int = if (v < 0) -v else v

    private fun togglePanel() {
        android.util.Log.i("ReaderAccess", "togglePanel panelView=" + (panelView != null) + " allowed=" + overlayAllowed())
        if (panelView != null) hidePanel() else showPanel()
    }

    private fun showPanel() {
        android.util.Log.i("ReaderAccess", "showPanel enter")
        if (panelView != null || !overlayAllowed()) return
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val density = resources.displayMetrics.density
        panelParams = android.view.WindowManager.LayoutParams(
            (300 * density).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (ballParams?.x ?: 40) + 120
            y = ballParams?.y ?: 200
        }
        panelText = TextView(this).apply {
            setTextColor(0xFFF2E9DA.toInt())
            textSize = 13f
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            text = statusText
        }
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(0xF214120F.toInt())
            }
            addView(panelText)
            addView(Button(this@ReaderAccessService).apply {
                text = "收起"
                textSize = 11f
                setOnClickListener { hidePanel() }
            })
        }.also { v ->
            wm.addView(v, panelParams)
        }
    }

    private fun hidePanel() {
        panelView?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        panelView = null
        panelParams = null
    }

    private fun updateStatusText(text: String) {
        statusText = text
        panelText?.text = text
    }

    override fun onInterrupt() {
        updateStatusText("伴读已中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hidePanel()
        ballView?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        ballView = null
        overlayAdded = false
        buffer.clear()
        return super.onUnbind(intent)
    }

    companion object {
        private const val SAMPLING_DEBOUNCE_MS = 800L
        private const val MAX_DEPTH = 40
    }
}
