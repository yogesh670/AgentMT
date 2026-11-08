package com.inyogeshwar.agentmt.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The AgentMT mark drawn in code so it follows the theme:
 * light theme → ink tile with white chevron; dark theme → raised graphite tile with a hairline border.
 * Cursor and spark keep the brand accents in both. Colours animate when the theme changes.
 */
@Composable
fun Logo(size: Dp = 32.dp, modifier: Modifier = Modifier, tile: Boolean = true) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tileColor by animateColorAsState(if (dark) Color(0xFF1C2230) else Color(0xFF0B1026), tween(300), label = "logoTile")
    val border by animateColorAsState(if (dark) Color(0xFF343D50) else Color.Transparent, tween(300), label = "logoBorder")
    val chevron by animateColorAsState(
        if (tile) Color.White else MaterialTheme.colorScheme.onBackground, tween(300), label = "logoChevron")
    val cursor = if (dark || tile) Color(0xFF22D3EE) else Color(0xFF0891B2)
    val spark = if (dark || tile) Color(0xFFFBBF24) else Color(0xFFD97706)
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 108f
        if (tile) {
            drawRoundRect(tileColor, Offset(4 * k, 4 * k), Size(100 * k, 100 * k), CornerRadius(26 * k))
            if (border.alpha > 0f) drawRoundRect(border, Offset(4.5f * k, 4.5f * k), Size(99 * k, 99 * k), CornerRadius(26 * k), style = Stroke(1.2f * k.coerceAtLeast(1f)))
        }
        val w = 7.5f * k
        drawPath(Path().apply { moveTo(37 * k, 40 * k); lineTo(51 * k, 54 * k); lineTo(37 * k, 68 * k) }, chevron,
            style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(cursor, Offset(57 * k, 68 * k), Offset(73 * k, 68 * k), w, StrokeCap.Round)
        drawPath(Path().apply {
            moveTo(71 * k, 28 * k)
            quadraticTo(72.6f * k, 37.4f * k, 82 * k, 39 * k)
            quadraticTo(72.6f * k, 40.6f * k, 71 * k, 50 * k)
            quadraticTo(69.4f * k, 40.6f * k, 60 * k, 39 * k)
            quadraticTo(69.4f * k, 37.4f * k, 71 * k, 28 * k)
            close()
        }, spark)
    }
}
