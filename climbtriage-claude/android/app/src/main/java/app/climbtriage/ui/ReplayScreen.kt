package app.climbtriage.ui

import android.graphics.Paint
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.climbtriage.container
import app.climbtriage.contracts.Capture
import app.climbtriage.contracts.PersonTrack
import app.climbtriage.contracts.PoseSample
import app.climbtriage.contracts.Validity
import app.climbtriage.geometry.Viewport
import app.climbtriage.holds.DisplayNumbering
import app.climbtriage.holds.HoldMapState
import app.climbtriage.ui.Overlay.hold
import app.climbtriage.ui.Overlay.label
import app.climbtriage.ui.Overlay.skeleton
import kotlin.math.abs

/** Nearest pose sample within [toleranceUs] of t, or null. Sample-and-hold only: no interpolation. */
fun nearestSample(samples: List<PoseSample>, tUs: Long, toleranceUs: Long): PoseSample? {
    if (samples.isEmpty()) return null
    var lo = 0
    var hi = samples.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (samples[mid].tUs < tUs) lo = mid + 1 else hi = mid
    }
    val candidates = listOfNotNull(samples.getOrNull(lo), samples.getOrNull(lo - 1))
    return candidates.minByOrNull { abs(it.tUs - tUs) }?.takeIf { abs(it.tUs - tUs) <= toleranceUs }
}

@OptIn(UnstableApi::class)
@Composable
fun ReplayScreen(id: String, back: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    var capture by remember { mutableStateOf<Capture?>(null) }
    var track by remember { mutableStateOf<PersonTrack?>(null) }
    var holds by remember { mutableStateOf<HoldMapState?>(null) }
    var positionUs by remember { mutableLongStateOf(0L) }
    var showSkeleton by remember { mutableStateOf(true) }
    var routeOnly by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        capture = c.repository.capture(id)
        track = c.repository.track(id)
        holds = c.repository.holdMap(id)
    }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(c.repository.mediaFile(id).toURI().toString()))
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(player) {
        while (true) withFrameNanos { positionUs = player.currentPosition * 1000L }
    }
    val cap = capture ?: return
    val firstPts = cap.timebase.ptsUs.firstOrNull() ?: 0L
    val tUs = positionUs + firstPts
    val tolerance = 75_000L
    val sample = track?.samples?.let { nearestSample(it, tUs, tolerance) }
    val moved = cap.stillness?.movedFrameTimesUs.orEmpty()
    val window = cap.video.durationUs / 15
    val holdsSuppressed = moved.any { abs(it - tUs) <= window }
    val paint = remember { Paint().apply { color = android.graphics.Color.WHITE; textSize = 30f; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK) } }
    val state = holds
    val numbers = state?.let { DisplayNumbering.number(it.holds, it.route.members) } ?: emptyMap()

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = back) { Text("Back") }
            FilterChip(showSkeleton, { showSkeleton = !showSkeleton }, { Text("Skeleton") })
            FilterChip(routeOnly, { routeOnly = !routeOnly }, { Text("Route only") })
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView({ ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            }, Modifier.matchParentSize())
            Canvas(Modifier.matchParentSize()) {
                val dw = (cap.video.displayWidthPx ?: cap.video.widthPx).toDouble()
                val dh = (cap.video.displayHeightPx ?: cap.video.heightPx).toDouble()
                val toView = Viewport.fit(dw, dh, size.width.toDouble(), size.height.toDouble()).fromFrameNorm()
                // wall_norm → frame_norm is the identity only for a verified-still capture; where the
                // camera moved, holds are not drawn rather than drawn in the wrong place.
                if (state != null && !holdsSuppressed) {
                    for (h in state.live) {
                        val member = h.id in state.route.members
                        if (routeOnly && !member) continue
                        val color = when {
                            h.id in state.route.finish -> Overlay.finishColor
                            h.id in state.route.start -> Overlay.startColor
                            member -> Overlay.routeColor
                            else -> Overlay.wallHoldColor
                        }
                        hold(h, toView, color, if (member) 4f else 2f, fillAlpha = if (member) 0.2f else 0f, dashed = !member)
                        numbers[h.id]?.let { label(h, toView, it.toString(), paint) }
                    }
                }
                if (showSkeleton && sample != null) skeleton(sample, toView, Color(0xFF00E5FF))
            }
        }
        val status = when {
            track == null -> "No climber selected"
            sample == null -> "No analysed sample near this time"
            sample.validity == Validity.LOST -> "Climber not found here (tracking loss — not a fall)"
            sample.validity == Validity.AMBIGUOUS -> "Ambiguous: another person is too close to tell apart"
            sample.validity == Validity.NOT_ANALYZED -> "Frame not analysed"
            sample.validity == Validity.LOW_CONFIDENCE -> "Low-confidence pose (drawn faded)"
            else -> "Tracked"
        } + if (holdsSuppressed) " · holds hidden: camera moved near here" else ""
        Text("t = %.2f s · %s".format(tUs / 1e6, status), style = MaterialTheme.typography.bodySmall)
        Timeline(cap, track, tUs)
    }
}

@Composable
private fun Timeline(cap: Capture, track: PersonTrack?, tUs: Long) {
    val start = cap.timebase.ptsUs.firstOrNull() ?: 0L
    val span = maxOf(1L, cap.video.durationUs)
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        fun x(t: Long) = ((t - start).toFloat() / span) * size.width
        drawRect(Color(0xFF2E7D32), Offset.Zero, size)
        track?.lostIntervals?.forEach { iv ->
            val color = when (iv.reason) { "ambiguous" -> Color(0xFFFF9100); "not_analyzed" -> Color.Gray; else -> Color(0xFFD50000) }
            drawRect(color, Offset(x(iv.startUs), 0f), Size(maxOf(2f, x(iv.endUs) - x(iv.startUs)), size.height))
        }
        if (track == null) drawRect(Color.DarkGray, Offset.Zero, size)
        cap.stillness?.movedFrameTimesUs?.forEach { drawRect(Color.Yellow, Offset(x(it) - 1f, 0f), Size(3f, size.height)) }
        drawRect(Color.White, Offset(x(tUs) - 1.5f, 0f), Size(3f, size.height))
    }
}
