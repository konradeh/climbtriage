package app.climbtriage.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import app.climbtriage.contracts.Hold
import app.climbtriage.contracts.PoseSample
import app.climbtriage.contracts.Validity
import app.climbtriage.geometry.Polygons
import app.climbtriage.geometry.Transform2D
import app.climbtriage.perception.SKELETON_EDGES
import app.climbtriage.perception.visibleEnough

/** Drawing helpers. All inputs are normalised; `toView` is the explicit transform chain to pixels. */
object Overlay {
    val routeColor = Color(0xFF00E676)
    val startColor = Color(0xFF2979FF)
    val finishColor = Color(0xFFFF1744)
    val wallHoldColor = Color(0x99FFFFFF)
    val selectedColor = Color(0xFFFFEA00)
    val heuristicColor = Color(0xFFFFAB40)

    fun DrawScope.hold(
        hold: Hold, toView: Transform2D, color: Color, width: Float, fillAlpha: Float = 0f, dashed: Boolean = false,
    ) {
        if (hold.polygon.size < 3) return
        val path = Path()
        hold.polygon.forEachIndexed { i, p ->
            val v = toView.apply(p[0], p[1])
            if (i == 0) path.moveTo(v.x.toFloat(), v.y.toFloat()) else path.lineTo(v.x.toFloat(), v.y.toFloat())
        }
        path.close()
        if (fillAlpha > 0f) drawPath(path, color.copy(alpha = fillAlpha))
        drawPath(path, color, style = Stroke(width = width,
            pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
    }

    fun DrawScope.label(hold: Hold, toView: Transform2D, text: String, paint: android.graphics.Paint) {
        val c = Polygons.centroid(Polygons.fromLists(hold.polygon))
        val v = toView.apply(c)
        drawContext.canvas.nativeCanvas.drawText(text, v.x.toFloat(), v.y.toFloat() - 12f, paint)
    }

    /** Skeleton of one sample; nothing is drawn for non-valid samples (missing is not zero). */
    fun DrawScope.skeleton(sample: PoseSample, toView: Transform2D, color: Color, width: Float = 6f) {
        if (sample.validity != Validity.VALID && sample.validity != Validity.LOW_CONFIDENCE) return
        val alpha = if (sample.validity == Validity.VALID) 1f else 0.45f
        val byName = sample.landmarks.associateBy { it.name }
        for ((a, b) in SKELETON_EDGES) {
            val la = byName[a]?.takeIf { it.visibleEnough(0.4) } ?: continue
            val lb = byName[b]?.takeIf { it.visibleEnough(0.4) } ?: continue
            val va = toView.apply(la.x, la.y)
            val vb = toView.apply(lb.x, lb.y)
            drawLine(color.copy(alpha = alpha), Offset(va.x.toFloat(), va.y.toFloat()), Offset(vb.x.toFloat(), vb.y.toFloat()), width)
        }
        for (lm in sample.landmarks) {
            if (!lm.visibleEnough(0.4) || lm.name.contains("eye") || lm.name.contains("mouth") || lm.name.contains("ear")) continue
            val v = toView.apply(lm.x, lm.y)
            drawCircle(Color.White.copy(alpha = alpha), 5f, Offset(v.x.toFloat(), v.y.toFloat()))
        }
    }
}
