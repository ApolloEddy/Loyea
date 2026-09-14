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
 * 复用 NeuralLivingScene（与陪伴聊天页同一数学模型）的低帧率模式：
 * Handler 15fps 驱动 advance+project，onDraw 只画投影结果。
 * 40dp 球内可见 ~610 节点的迷你旋转神经网。
 */
class ReaderNeuralBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val scene = NeuralLivingScene()
    private val handler = Handler(Looper.getMainLooper())
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9A357.toInt() }
    private val edgePaint = Paint().apply {
        color = 0x55D9A357
        strokeWidth = 1f
    }
    private var frameCount = 0

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            scene.advance(1f / 15f)       // 15fps 低帧率省电模式
            scene.computeWorlds()
            scene.projectAllNodes()
            invalidate()
            frameCount++
            if (frameCount % 3 == 0) {    // 实际重绘 5fps（球面小无需更高）
                handler.postDelayed(this, 66)
            } else {
                handler.postDelayed(this, 16)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val dp = resources.displayMetrics.density
        scene.setLayout(width.toFloat().coerceAtLeast(dp * 40), height.toFloat().coerceAtLeast(dp * 40))
        handler.post(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scene.projectAllNodes()
        // 边（edges = [a0,b0,a1,b1,...]；node 索引成对）
        for (i in 0 until scene.edgeCount) {
            val aIdx = scene.edges[i * 2]
            val bIdx = scene.edges[i * 2 + 1]
            canvas.drawLine(
                scene.nodeProjX(aIdx), scene.nodeProjY(aIdx),
                scene.nodeProjX(bIdx), scene.nodeProjY(bIdx),
                edgePaint
            )
        }
        // 节点
        for (i in 0 until scene.nodeCount) {
            val r = 1.2f * scene.nodeProjScale(i)
            canvas.drawCircle(scene.nodeProjX(i), scene.nodeProjY(i), r, nodePaint)
        }
    }
}
