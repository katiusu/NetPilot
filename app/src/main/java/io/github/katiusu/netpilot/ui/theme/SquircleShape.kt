package io.github.katiusu.netpilot.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon

fun squircleShape(radius: Dp, smoothing: Float = 0.6f): Shape = SquircleShape(radius, smoothing)

class SquircleShape(
    private val radius: Dp,
    private val smoothing: Float = 0.6f,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path()
        if (size.minDimension <= 0f) return Outline.Generic(path)
        val r = with(density) { radius.toPx() }.coerceAtMost(size.minDimension / 2f)
        val polygon = RoundedPolygon(
            floatArrayOf(
                0f, 0f,
                size.width, 0f,
                size.width, size.height,
                0f, size.height,
            ),
            CornerRounding(r, smoothing.coerceIn(0f, 1f)),
            emptyList(),
            0.05f,
        )
        val cubics = polygon.cubics
        if (cubics.isEmpty()) return Outline.Generic(path)
        path.moveTo(cubics[0].anchor0X, cubics[0].anchor0Y)
        cubics.forEach { c ->
            path.cubicTo(c.point1X, c.point1Y, c.point2X, c.point2Y, c.anchor1X, c.anchor1Y)
        }
        path.close()
        return Outline.Generic(path)
    }
}
