package app.climbtriage.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.climbtriage.geometry.Pt
import app.climbtriage.geometry.Transform2D
import app.climbtriage.geometry.Viewport

/**
 * A bitmap fitted into its box (letterboxed), with an overlay drawn through the same explicit
 * `frame_norm → view` transform that taps are inverted through. One transform, both directions.
 */
@Composable
fun FittedImage(
    bitmap: Bitmap?,
    contentWidth: Int,
    contentHeight: Int,
    modifier: Modifier = Modifier,
    onTap: ((Pt) -> Unit)? = null,
    onDrag: ((from: Pt, to: Pt, end: Boolean) -> Unit)? = null,
    overlay: DrawScope.(toView: Transform2D) -> Unit = {},
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val viewport = if (size.width > 0 && size.height > 0 && contentWidth > 0 && contentHeight > 0)
        Viewport.fit(contentWidth.toDouble(), contentHeight.toDouble(), size.width.toDouble(), size.height.toDouble()) else null
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    var dragStart by remember { mutableStateOf<Pt?>(null) }
    var dragLast by remember { mutableStateOf<Pt?>(null) }
    Box(modifier.onSizeChanged { size = it }) {
        Canvas(
            Modifier.matchParentSize()
                .pointerInput(viewport, onTap) {
                    if (onTap != null && viewport != null) detectTapGestures { o -> onTap(viewport.toFrameNorm().apply(o.x.toDouble(), o.y.toDouble())) }
                }
                .pointerInput(viewport, onDrag) {
                    if (onDrag != null && viewport != null) detectDragGestures(
                        onDragStart = { o ->
                            dragStart = viewport.toFrameNorm().apply(o.x.toDouble(), o.y.toDouble()); dragLast = dragStart
                        },
                        onDragEnd = { val s = dragStart; val l = dragLast; if (s != null && l != null) onDrag(s, l, true); dragStart = null },
                        onDragCancel = { dragStart = null },
                        onDrag = { change, _ ->
                            val p = viewport.toFrameNorm().apply(change.position.x.toDouble(), change.position.y.toDouble())
                            dragLast = p
                            dragStart?.let { onDrag(it, p, false) }
                        },
                    )
                },
        ) {
            if (viewport == null) return@Canvas
            image?.let {
                drawImage(it, dstOffset = IntOffset(viewport.left.toInt(), viewport.top.toInt()),
                    dstSize = IntSize(viewport.width.toInt(), viewport.height.toInt()))
            }
            overlay(viewport.fromFrameNorm())
        }
    }
}

fun Pt.offset(t: Transform2D): Offset = t.apply(this).let { Offset(it.x.toFloat(), it.y.toFloat()) }
