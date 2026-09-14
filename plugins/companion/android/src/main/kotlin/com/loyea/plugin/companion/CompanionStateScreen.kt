package com.loyea.plugin.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.plugin.companion.runtime.CompanionRuntimeCoordinator
import com.loyea.plugin.modulator.ModulatorVocab
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * 内心状态可视化（S-C1）。
 *
 * 设计借 April 感知台"暖色仪表 + 分层信息"的巧思，但展示的是 Loyea 生态
 * 自己的量：调制器 v1.1.0 的三列状态表、快/背景四轴、证据分量痕迹。
 * 只读快照（stateSnapshot 不推进任何状态）；解释语义沿用 INTERPRETATION。
 */
@Composable
fun CompanionStateScreen(
    sessionId: String,
    incarnationId: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val coordinator = remember { CompanionRuntimeCoordinator.getInstance(app) }
    val view = remember(sessionId, incarnationId) {
        runBlocking {
            coordinator.stateSnapshot(
                CompanionContract.COMPANION_CHARACTER_ID, sessionId, incarnationId,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CompanionPalette.Bg)
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = CompanionPalette.Brand)
            }
            Text("内心状态", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.Brand)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (view == null) {
                Text(
                    "状态暂不可用（会话尚未产生运行状态，或检查点等待恢复）。",
                    fontSize = 13.sp, color = CompanionPalette.Hint,
                    modifier = Modifier.padding(top = 24.dp)
                )
                return@Column
            }

            GroupCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(CompanionPalette.AmberDot))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "此刻 · ${ModulatorVocab.STATE_ZH[view.moodLabel] ?: view.moodLabel}",
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.TextPrimary
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (view.rows.isEmpty()) {
                    Text("还没有已提交的状态；说句话，我开始感受。", fontSize = 13.sp, color = CompanionPalette.Hint)
                } else {
                    StateTableRow("维度", "状态", "强度", intensity = null, header = true)
                    for (row in view.rows) {
                        StateTableRow(
                            ModulatorVocab.ASPECT_ZH[row.aspect] ?: row.aspect,
                            ModulatorVocab.STATE_ZH[row.state] ?: row.state,
                            ModulatorVocab.LEVEL_ZH[row.intensity] ?: row.intensity,
                            intensity = intensityFraction(row.intensity).takeIf { row.intensity != "—" && row.intensity != "none" },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(ModulatorVocab.INTERPRETATION, fontSize = 11.sp, color = CompanionPalette.Hint)
            }

            Spacer(Modifier.height(16.dp))

            GroupCard {
                GroupTitle("即时感受轴", "几秒到几分钟内回落")
                AxisBar("愉悦", view.fast[0], symmetric = true)
                AxisBar("唤起", view.fast[1])
                AxisBar("紧张", view.fast[2])
                AxisBar("恼火", view.fast[3])
            }

            Spacer(Modifier.height(16.dp))

            GroupCard {
                GroupTitle("背景心境轴", "几十分钟里慢慢沉淀")
                AxisBar("愉悦", view.mood[0], symmetric = true)
                AxisBar("唤起", view.mood[1])
                AxisBar("紧张", view.mood[2])
                AxisBar("恼火", view.mood[3])
            }

            Spacer(Modifier.height(16.dp))

            GroupCard {
                GroupTitle("事件痕迹", "由具体对话留下，会自然淡去")
                if (view.traces.isEmpty()) {
                    Text("暂时没有留下痕迹。", fontSize = 13.sp, color = CompanionPalette.Hint)
                } else {
                    view.traces.forEachIndexed { index, trace ->
                        if (index > 0) Spacer(Modifier.height(14.dp))
                        TraceRow(trace)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "累计 ${view.interactionCount} 次真实交流 · 观测序号 ${view.acceptedSeq}",
                fontSize = 11.sp, color = CompanionPalette.Hint
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF14120F))
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun GroupTitle(title: String, sub: String) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.TextPrimary)
    Text(sub, fontSize = 11.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun StateTableRow(aspect: String, state: String, intensityText: String, intensity: Double?, header: Boolean = false) {
    val aspectColor = if (header) CompanionPalette.Hint else CompanionPalette.BrandMuted
    val stateColor = if (header) CompanionPalette.Hint else CompanionPalette.TextPrimary
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(aspect, fontSize = 13.sp, color = aspectColor, modifier = Modifier.width(88.dp))
        Text(state, fontSize = 14.sp, color = stateColor, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f)) {
            if (intensity != null && intensity > 0.0) {
                Bar(fraction = intensity)
            } else {
                Text(intensityText, fontSize = 13.sp, color = CompanionPalette.Hint)
            }
        }
    }
}

@Composable
private fun Bar(fraction: Double) {
    val f = fraction.coerceIn(0.0, 1.0).toFloat()
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x22D9A357))
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(CompanionPalette.AmberDot)
        )
    }
}

/** 数值条；symmetric=true 时以中心为 0，负值向左（蓝灰），正值向右（琥珀）。 */
@Composable
private fun AxisBar(label: String, value: Double, symmetric: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = CompanionPalette.BrandMuted, modifier = Modifier.width(40.dp))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0x1AD9A357))
        ) {
            val half = maxWidth / 2
            if (symmetric) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = half)
                        .width(1.dp)
                        .height(10.dp)
                        .background(Color(0x44FFFFFF))
                )
                val magnitude = (abs(value).coerceAtMost(1.0)).toFloat()
                val fill = half * magnitude
                if (magnitude > 0.005f) {
                    val color = if (value >= 0) CompanionPalette.AmberDot else Color(0xFF8FA3B8)
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = if (value >= 0) half else half - fill)
                            .width(fill)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(color)
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat())
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(CompanionPalette.AmberDot)
                )
            }
        }
        Text(
            String.format("%+.2f", value),
            fontSize = 11.sp, color = CompanionPalette.Hint,
            modifier = Modifier.width(46.dp).padding(start = 8.dp)
        )
    }
}

@Composable
private fun TraceRow(trace: CompanionRuntimeCoordinator.TraceView) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trace.label, fontSize = 14.sp, color = CompanionPalette.TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(String.format("%.2f", trace.strength), fontSize = 11.sp, color = CompanionPalette.Hint)
        }
        Spacer(Modifier.height(6.dp))
        Bar(trace.strength)
        if (trace.components.isNotEmpty()) {
            Text(
                "来自 " + trace.components.joinToString("、") {
                    it.evidenceId.substringAfter("obs:").substringBefore(':')
                },
                fontSize = 10.sp, color = CompanionPalette.Hint,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun intensityFraction(level: String): Double = when (level) {
    "mild" -> 0.25
    "moderate" -> 0.5
    "strong" -> 0.75
    "very_strong" -> 1.0
    else -> 0.0
}
