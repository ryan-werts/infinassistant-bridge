package com.w6tech.infinassistant.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.w6tech.infinassistant.core.domain.WatchFaceLayoutPayload
import com.w6tech.infinassistant.core.domain.WatchScreen
import com.w6tech.infinassistant.core.domain.WatchTile
import com.w6tech.infinassistant.core.ui.theme.AppColors

/** Whether the preview of [payload] is the dashboard, which it is when the first screen is one. */
fun previewsDashboard(payload: WatchFaceLayoutPayload): Boolean = payload.screens.firstOrNull()?.dashboardOrNull() != null

private fun WatchScreen.dashboardOrNull() = if (screenType == 0) dashboard else null

/**
 * Compose Canvas rendering of a watch face layout preview.
 * Approximates the PineTime 240x240 display for a visual preview before pushing.
 * A dashboard first screen is drawn as the watch's dashboard v8 with sample values
 * (DashboardPreviewModel); a tile screen as the header, the tiles and the footer.
 */
@Composable
fun WatchFacePreview(
    payload: WatchFaceLayoutPayload,
    modifier: Modifier = Modifier,
) {
    val dashboard = payload.screens.firstOrNull()?.dashboardOrNull()
    if (dashboard != null) {
        val ops = remember(payload) {
            DashboardPreviewModel.build(
                dashboard = dashboard,
                screenIndex = 0,
                screenCount = payload.screens.size,
                timeFormat = payload.header.timeFormat,
                nowMs = System.currentTimeMillis(),
            )
        }
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(1.dp, AppColors.previewBoundary)
        ) {
            drawDashboard(ops, size.width / 240f)
        }
        return
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(1.dp, AppColors.previewBoundary)
    ) {
        val w = size.width
        val h = size.height
        val scale = w / 240f

        // Background
        val bgColor = parseHexColor(payload.theme.background, Color(0xFF1A1A1A))
        drawRect(bgColor, Offset.Zero, Size(w, h))

        // Time
        val timeY = 4f * scale
        drawText("12:00", w / 2, timeY + 20f * scale, 20f * scale, Color.White, Paint.Align.CENTER)

        // Accent line
        val accentColor = parseHexColor(payload.theme.accent, Color(0xFFFFD700))
        drawRect(accentColor, Offset(0f, 30f * scale), Size(w, 2f * scale))

        // Tiles from screen[0]
        val screen0 = payload.screens.firstOrNull()
        val tiles = screen0?.tiles ?: emptyList()
        val tileCount = tiles.size.coerceAtMost(4)

        if (tileCount > 0) {
            val gridY = 46f * scale
            val gridH = 148f * scale
            val tileH = gridH / 2
            val tileW = w / 2
            val gap = 4f * scale

            for (i in 0 until tileCount) {
                val row = i / 2
                val col = i % 2
                val tile = tiles[i]
                val tileColor = parseHexColor(tile.color, Color(0xFF333333))

                val x = col * tileW + gap / 2
                val y = gridY + row * tileH
                val tw = tileW - gap
                val th = tileH - gap

                drawRoundRect(
                    color = tileColor,
                    topLeft = Offset(x, y),
                    size = Size(tw, th),
                    cornerRadius = CornerRadius(8f * scale),
                )

                // Tile label
                drawText(
                    tile.label,
                    x + tw / 2,
                    y + th / 3,
                    12f * scale,
                    Color.White,
                    Paint.Align.CENTER,
                )

                // Tile state placeholder
                drawText(
                    "---",
                    x + tw / 2,
                    y + th * 2 / 3,
                    10f * scale,
                    Color.White.copy(alpha = 0.7f),
                    Paint.Align.CENTER,
                )
            }
        }

        // Status bar placeholder
        val statusY = 226f * scale
        drawText("100%", 10f * scale, statusY, 8f * scale, Color.Gray, Paint.Align.LEFT)
        drawText("BT", w - 10f * scale, statusY, 8f * scale, Color.Gray, Paint.Align.RIGHT)
    }
}

private fun DrawScope.drawDashboard(ops: List<DashboardPreviewModel.Op>, scale: Float) {
    drawRect(Color(DashboardPreviewModel.BACKGROUND), Offset.Zero, size)
    for (op in ops) {
        when (op) {
            is DashboardPreviewModel.Op.Box -> {
                val topLeft = Offset(op.x * scale, op.y * scale)
                val boxSize = Size(op.w * scale, op.h * scale)
                if (op.radius == 0) drawRect(Color(op.color), topLeft, boxSize)
                else drawRoundRect(Color(op.color), topLeft, boxSize, CornerRadius(op.radius * scale))
            }
            is DashboardPreviewModel.Op.Rule ->
                drawRect(Color(op.color), Offset(op.x1 * scale, op.y * scale), Size((op.x2 - op.x1) * scale, scale))
            is DashboardPreviewModel.Op.Text -> {
                val fontSize = when (op.font) {
                    DashboardPreviewModel.Font.M14 -> 14f
                    DashboardPreviewModel.Font.M18 -> 18f
                    DashboardPreviewModel.Font.TIME -> 76f
                    DashboardPreviewModel.Font.BOLD -> 20f
                }
                val typeface = when (op.font) {
                    DashboardPreviewModel.Font.M14, DashboardPreviewModel.Font.M18 ->
                        Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    DashboardPreviewModel.Font.TIME -> Typeface.MONOSPACE
                    DashboardPreviewModel.Font.BOLD -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
                val align = when (op.align) {
                    DashboardPreviewModel.Align.LEFT -> Paint.Align.LEFT
                    DashboardPreviewModel.Align.CENTER -> Paint.Align.CENTER
                    DashboardPreviewModel.Align.RIGHT -> Paint.Align.RIGHT
                }
                drawText(op.text, op.x * scale, op.baseline * scale, fontSize * scale, Color(op.color), align, typeface)
            }
            is DashboardPreviewModel.Op.Glyph -> drawGlyph(op, scale)
        }
    }
}

/** The watch's icons (Font Awesome there) as plain shapes in their boxes. */
private fun DrawScope.drawGlyph(op: DashboardPreviewModel.Op.Glyph, scale: Float) {
    val color = Color(op.color)
    val ox = op.left * scale
    val oy = op.top * scale
    fun p(x: Float, y: Float) = Offset(ox + x * scale, oy + y * scale)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) =
        drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = width * scale, cap = StrokeCap.Round)
    fun triangle(a: Offset, b: Offset, c: Offset) {
        val path = Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            close()
        }
        drawPath(path, color)
    }
    when (op.icon) {
        DashboardPreviewModel.Icon.CHECK -> {
            line(3f, 10f, 8f, 15f, 3f)
            line(8f, 15f, 17f, 4f, 3f)
        }
        DashboardPreviewModel.Icon.HOME -> {
            triangle(p(1f, 10f), p(10f, 2f), p(19f, 10f))
            drawRect(color, p(4f, 10f), Size(12f * scale, 8f * scale))
        }
        DashboardPreviewModel.Icon.BATTERY -> {
            // Lying down, its tip to the right: the outline, the tip, and the charge as the fill.
            drawRoundRect(color, p(1f, 4f), Size(22f * scale, 12f * scale), CornerRadius(2f * scale), Stroke(1.5f * scale))
            drawRect(color, p(23f, 8f), Size(2f * scale, 4f * scale))
            drawRect(color, p(3f, 6f), Size(18f * op.level / 100f * scale, 8f * scale))
        }
        DashboardPreviewModel.Icon.STEPS -> {
            drawOval(color, p(3f, 2f), Size(7f * scale, 11f * scale))
            drawOval(color, p(11f, 8f), Size(7f * scale, 11f * scale))
        }
        DashboardPreviewModel.Icon.HEART -> {
            drawCircle(color, 4.5f * scale, p(6.5f, 7f))
            drawCircle(color, 4.5f * scale, p(13.5f, 7f))
            triangle(p(2.3f, 9f), p(17.7f, 9f), p(10f, 18f))
        }
        DashboardPreviewModel.Icon.WEATHER -> {
            // Partly cloudy: a cloud of three discs on a flat base.
            drawCircle(color, 5f * scale, p(8f, 15f))
            drawCircle(color, 7f * scale, p(14f, 12f))
            drawCircle(color, 5f * scale, p(20f, 16f))
            drawRect(color, p(8f, 15f), Size(12f * scale, 6f * scale))
        }
    }
}

private fun DrawScope.drawText(
    text: String,
    x: Float,
    y: Float,
    textSize: Float,
    color: Color,
    align: Paint.Align,
    typeface: Typeface = Typeface.MONOSPACE,
) {
    drawContext.canvas.nativeCanvas.drawText(
        text,
        x,
        y,
        Paint().apply {
            this.textSize = textSize
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(),
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt(),
            )
            this.textAlign = align
            this.isAntiAlias = true
            this.typeface = typeface
        }
    )
}

private fun parseHexColor(hex: String, default: Color): Color {
    return try {
        val clean = hex.removePrefix("#")
        when (clean.length) {
            6 -> Color(android.graphics.Color.parseColor("#$clean"))
            3 -> {
                val expanded = clean.map { "$it$it" }.joinToString("")
                Color(android.graphics.Color.parseColor("#$expanded"))
            }
            else -> {
                if (hex.equals("charcoal", ignoreCase = true)) Color(0xFF2C2C2C)
                else if (hex.equals("black", ignoreCase = true)) Color(0xFF000000)
                else default
            }
        }
    } catch (_: Exception) {
        if (hex.equals("charcoal", ignoreCase = true)) Color(0xFF2C2C2C)
        else if (hex.equals("black", ignoreCase = true)) Color(0xFF000000)
        else default
    }
}
