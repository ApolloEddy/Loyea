package com.loyea.plugin.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.isActive
import androidx.compose.runtime.withFrameNanos

/** 神经元核心的舞台外壳：暗底 + 椭圆暖光晕 + 可拖拽画布 + 底部提示语（Web 版 lv-stage/lv-hint）。 */
@Composable
fun NeuralLivingStage(
    modifier: Modifier = Modifier,
    targetActivity: Float = NeuralLivingScene.ACTIVITY_REST,
    showHint: Boolean = true,
) {
    Box(
        modifier = modifier.background(Color(0xFF050608))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to Color(0xFF32200D).copy(alpha = 0.14f),
                        1f to Color(0xFF32200D).copy(alpha = 0f)
                    )
                )
        )
        NeuralLivingCanvas(modifier = Modifier.fillMaxSize(), targetActivity = targetActivity)
        if (showHint) {
            Text(
                text = "拖动，换个角度看我",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                color = Color(0xFF998771),
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 「Loyea Neural Living」画布：NeuralLivingScene 的 Compose 渲染。
 * 帧循环经 withFrameNanos 驱动；绘制读取 frameDt 状态触发 draw 相位失效（无重组开销）。
 * 加性混色（BlendMode.Plus）下绘制顺序不影响合成结果，与 Web 版逐帧排序等效。
 */
@Composable
fun NeuralLivingCanvas(
    modifier: Modifier = Modifier,
    targetActivity: Float = NeuralLivingScene.ACTIVITY_REST,
    interactive: Boolean = true,
) {
    val scene = remember { NeuralLivingScene() }
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var frameDt by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(targetActivity) { scene.targetActivity = targetActivity }

    // NFR-04：页面不可见即暂停节律，后台无遗留高频工作
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> scene.setPaused(true)
                Lifecycle.Event.ON_RESUME -> scene.setPaused(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                scene.advance(if (scene.paused) 0f else dt)
                frameDt = if (scene.paused) 0f else dt
            }
        }
    }

    Canvas(
        modifier = modifier
            .then(if (interactive) {
                Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            // Web 版：ty += dx*.009；tx += dy*.008（极角限幅 ±1.35）
                            scene.targetRotationY += amount.x * 0.009f
                            scene.targetRotationX =
                                (scene.targetRotationX + amount.y * 0.008f).coerceIn(-1.35f, 1.35f)
                            if (scene.paused) {
                                scene.snapRotation()
                                frameDt = -1f // 强制静态重绘
                            }
                        }
                    )
                }
            } else Modifier)
    ) {
        @Suppress("UNUSED_EXPRESSION") frameDt // 建立绘制失效依赖
        val d = density.density
        val w = size.width / d
        val h = size.height / d
        scene.setLayout(w, h)
        scene.computeWorlds()
        scene.projectAllNodes()
        withTransform({ scale(d, d, pivot = Offset.Zero) }) {
            renderScene(this, scene, frameDt.coerceAtLeast(0f))
        }
    }
}

private val WARM_DOT = Color(0xFFFFAF37)
private val BRIGHT_DOT = Color(0xFFFFE4AF)
private val LINE_COLOR = Color(0xFFFFB338)

private fun renderScene(scope: DrawScope, scene: NeuralLivingScene, dt: Float) {
    val w = scene.width()
    val h = scene.height()
    val center = Offset(scene.centerX(), scene.centerY())

    // 背景光晕（source-over，Web 版 halo）
    scope.drawRect(
        brush = Brush.radialGradient(
            0f to Color(0xFFFF991E).copy(alpha = 0.075f),
            0.5f to Color(0xFFA0530A).copy(alpha = 0.03f),
            1f to Color(0xFF5A320A).copy(alpha = 0f),
            center = center,
            radius = w * 0.37f
        ),
        topLeft = Offset.Zero,
        size = Size(w, h),
        blendMode = BlendMode.SrcOver
    )

    // ---- 以下全部加性混色（Web 版 globalCompositeOperation='lighter'） ----

    // 尘埃
    for (i in 0 until scene.dustCount()) {
        val p = scene.dustAt(i)
        dot(scope, p.x, p.y, 0.45f * p.scale, 0.035f + 0.045f * p.front, bright = false)
    }

    // 近邻边
    val edges = scene.edges
    var e = 0
    while (e < edges.size) {
        val a = edges[e]; val b = edges[e + 1]
        val f = (scene.nodeProjFront(a) + scene.nodeProjFront(b)) * 0.5f
        drawSegment(
            scope,
            scene.nodeProjX(a), scene.nodeProjY(a),
            scene.nodeProjX(b), scene.nodeProjY(b),
            alpha = 0.16f, width = 0.55f, f = f
        )
        e += 2
    }

    // 桥接路径：两段二次贝塞尔共享移动中锚点与连续切线
    val bridgePts = FloatArray(25 * 3)
    val wa = FloatArray(3); val wb = FloatArray(3); val wc = FloatArray(3)
    for (k in 0 until 28) {
        scene.worldPoint(wa, scene.bridgeAnchors[k * 3])
        scene.worldPoint(wb, scene.bridgeAnchors[k * 3 + 1])
        scene.worldPoint(wc, scene.bridgeAnchors[k * 3 + 2])
        val tx = (wc[0] - wa[0]) * 0.15f
        val ty = (wc[1] - wa[1]) * 0.15f
        val tz = (wc[2] - wa[2]) * 0.15f
        var n = 0
        for (segment in 0..1) {
            val sx = if (segment == 1) wb[0] else wa[0]
            val sy = if (segment == 1) wb[1] else wa[1]
            val sz = if (segment == 1) wb[2] else wa[2]
            val ex = if (segment == 1) wc[0] else wb[0]
            val ey = if (segment == 1) wc[1] else wb[1]
            val ez = if (segment == 1) wc[2] else wb[2]
            val cx = wb[0] + (if (segment == 1) 1 else -1) * tx
            val cy = wb[1] + (if (segment == 1) 1 else -1) * ty
            val cz = wb[2] + (if (segment == 1) 1 else -1) * tz
            var j = if (segment == 1) 1 else 0
            while (j <= 12) {
                val u = j / 12f
                val v = 1f - u
                bridgePts[n * 3] = v * v * sx + 2 * v * u * cx + u * u * ex
                bridgePts[n * 3 + 1] = v * v * sy + 2 * v * u * cy + u * u * ey
                bridgePts[n * 3 + 2] = v * v * sz + 2 * v * u * cz + u * u * ez
                n++
                j++
            }
        }
        var prev = scene.project(bridgePts[0], bridgePts[1], bridgePts[2])
        var ax = prev.x; var ay = prev.y; var fa = prev.front
        for (i in 1 until n) {
            val p = scene.project(bridgePts[i * 3], bridgePts[i * 3 + 1], bridgePts[i * 3 + 2])
            drawSegment(scope, ax, ay, p.x, p.y, alpha = 0.085f, width = 0.55f, f = (fa + p.front) * 0.5f)
            ax = p.x; ay = p.y; fa = p.front
        }
    }

    // 轨道弧线：基点过层帧形变后再投影视角
    val def = FloatArray(3)
    for (arc in scene.orbits) {
        val layer = arc.layer
        var prevX = 0f; var prevY = 0f; var prevF = 0f
        for (j in 0 until 29) {
            scene.deformPoint(def, arc.pts[j * 3], arc.pts[j * 3 + 1], arc.pts[j * 3 + 2], layer)
            val p = scene.project(def[0], def[1], def[2])
            if (j > 0) {
                drawSegment(scope, prevX, prevY, p.x, p.y, alpha = 0.10f, width = 0.65f, f = (prevF + p.front) * 0.5f)
            }
            prevX = p.x; prevY = p.y; prevF = p.front
        }
    }

    // 节点：亮度与半径随深度与重要度呼吸
    for (i in 0 until scene.nodeCount) {
        val f = scene.nodeProjFront(i)
        val phase = scene.nodePhaseAt(i)
        val importance = scene.nodeImportanceAt(i)
        val alpha = (0.24f + 0.58f * f) * (0.88f + 0.12f * sin(scene.time * 1.7f + phase)) * 0.88f
        val r = (0.58f + importance * 0.65f) * (0.8f + 0.4f * f) * scene.nodeProjScale(i)
        if (importance > 0.62f) {
            dot(scope, scene.nodeProjX(i), scene.nodeProjY(i), r * 3.4f, alpha * 0.045f, bright = false)
            dot(scope, scene.nodeProjX(i), scene.nodeProjY(i), r * 2.0f, alpha * 0.12f, bright = false)
        }
        dot(scope, scene.nodeProjX(i), scene.nodeProjY(i), r, alpha, bright = importance > 0.82f)
    }

    // 电火花：沿边游走，正弦淡入淡出
    for (i in 0 until scene.sparkCount()) {
        val p = scene.sparkAt(i, dt)
        val u = scene.lastSparkU
        val fade = sin(u * Math.PI.toFloat())
        val r = (1f + 0.8f * p.front) * 0.77f * p.scale
        val alpha = (0.35f + 0.45f * p.front) * 0.73f * fade
        dot(scope, p.x, p.y, r * 3.0f, 0.035f * fade, bright = false)
        dot(scope, p.x, p.y, r * 1.8f, 0.10f * fade, bright = false)
        dot(scope, p.x, p.y, r, alpha, bright = true)
    }

    // 核心余烬（小面积暖光，保持核心后方细节；Web 版 ember）
    val cr = min(w, h) * 0.026f * scene.frameScaleX(0)
    scope.drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFFFFEBBD).copy(alpha = 0.22f),
            0.2f to Color(0xFFFFBF50).copy(alpha = 0.13f),
            0.55f to Color(0xFFFF8F1E).copy(alpha = 0.045f),
            1f to Color(0xFFFF8F1E).copy(alpha = 0f),
            center = center,
            radius = cr * 2.8f
        ),
        radius = cr * 2.8f,
        center = center,
        blendMode = BlendMode.Plus
    )
}

private fun drawSegment(
    scope: DrawScope,
    x1: Float, y1: Float, x2: Float, y2: Float,
    alpha: Float, width: Float, f: Float,
) {
    scope.drawLine(
        color = LINE_COLOR,
        start = Offset(x1, y1),
        end = Offset(x2, y2),
        strokeWidth = width * (0.55f + 0.7f * f),
        alpha = (alpha * (0.25f + f * 1.65f)).coerceIn(0f, 1f),
        blendMode = BlendMode.Plus
    )
}

private fun dot(scope: DrawScope, x: Float, y: Float, radius: Float, alpha: Float, bright: Boolean) {
    if (alpha <= 0.0025f || radius <= 0.05f) return
    scope.drawCircle(
        color = if (bright) BRIGHT_DOT else WARM_DOT,
        radius = radius,
        center = Offset(x, y),
        alpha = alpha.coerceAtMost(1f),
        blendMode = BlendMode.Plus
    )
}
