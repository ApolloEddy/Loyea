package com.loyea.plugin.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.loyea.plugin.companion.NeuralLivingScene

/**
 * Loyea 神经网迷你画布（悬浮球内嵌；Reader Spec §9）。
 *
 * 复用 NeuralLivingScene（与陪伴聊天页同一数学模型）。
 * Handler 15fps 驱动 advance+computeWorlds+projectAllNodes，onDraw 绘制
 * 节点+边。球面 40dp 下节点以 1.5px 半径渲染，肉眼可见旋转动画。
 */
class ReaderNeuralBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val scene = NeuralLivingScene()
    private val handler = Handler(Looper.getMainLooper())
    private var frameRunnable: Runnable? = null
    private var initialised = false

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD9A357.toInt()
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40D9A357
        strokeWidth = 0.8f
    }
    private val bgPaint = Paint().apply {
        color = 0xE814120F.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !initialised) {
            scene.setLayout(w.toFloat(), h.toFloat())
            scene.advance(0.1f) // 初始投影
            initialised = true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFrames()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        super.onDetachedFromWindow()
    }

    private fun startFrames() {
        if (frameRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                scene.advance(1f / 15f)
                invalidate()
                handler.postDelayed(this, 66) // 15fps
            }
        }
        frameRunnable = r
        handler.post(r)
    }

    private fun stopFrames() {
        frameRunnable?.let { handler.removeCallbacks(it) }
        frameRunnable = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 背景
        canvas.drawColor(bgPaint.color)
        if (!initialised) return
        scene.computeWorlds()
        scene.projectAllNodes()
        // 边
        for (i in 0 until scene.edgeCount) {
            val aIdx = scene.edges[i * 2]
            val bIdx = scene.edges[i * 2 + 1]
            canvas.drawLine(
                scene.nodeProjX(aIdx), scene.nodeProjY(aIdx),
                scene.nodeProjX(bIdx), scene.nodeProjY(bIdx),
                edgePaint
            )
        }
        // 节点（40dp 球内 1.5f 半径可见）
        for (i in 0 until scene.nodeCount) {
            val px = scene.nodeProjX(i)
            val py = scene.nodeProjY(i)
            val r = 1.5f * scene.nodeProjScale(i)
            canvas.drawCircle(px, py, r, nodePaint)
        }
    }
}
