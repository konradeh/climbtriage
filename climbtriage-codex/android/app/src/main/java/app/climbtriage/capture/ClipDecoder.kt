package app.climbtriage.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import app.climbtriage.domain.Capture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Sequential, bounded recorded analysis. Recording is a separate adapter.
 * RGB is rotated exactly once; callback coordinates are upright normalized.
 */
class ClipDecoder {
    suspend fun decode(file: File, id: String, sha: String, onFrame: (Bitmap, Long, Long) -> Unit,
                       progress: (Long, Long) -> Unit): Capture {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("No video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val duration = format.getLong(MediaFormat.KEY_DURATION)
            require(duration in 1..120_000_000) { "First slice supports clips up to two minutes" }
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            require(width.toLong()*height <= 8_500_000) { "Import an SDR clip at 4K or below" }
            val rotation = if(format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            require(rotation in listOf(0,90,180,270)) { "Unsupported rotation" }
            val sarW = if(format.containsKey("sar-width")) format.getInteger("sar-width") else 1
            val sarH = if(format.containsKey("sar-height")) format.getInteger("sar-height") else 1
            require(sarW == sarH) { "Non-square pixels require a verified transform; transcode to square pixels" }
            val transfer = if(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
            require(transfer !in listOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)) {
                "HDR import needs validated tone mapping; export an SDR copy first"
            }
            val standard = if(format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else MediaFormat.COLOR_STANDARD_BT709
            require(standard != MediaFormat.COLOR_STANDARD_BT2020) { "BT.2020 input not supported in this slice" }
            val full = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE)==MediaFormat.COLOR_RANGE_FULL
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputEnded=false; var outputEnded=false
            var firstPts: Long? = null; var previousPts=Long.MIN_VALUE
            var nextAnalysis=0L; var decoded=0; var analyzed=0
            var lastOutputAt = System.nanoTime()
            val info=MediaCodec.BufferInfo()
            while(!outputEnded) {
                currentCoroutineContext().ensureActive()
                if(!inputEnded) {
                    val index=decoder.dequeueInputBuffer(10_000)
                    if(index>=0) {
                        val buffer=decoder.getInputBuffer(index)!!
                        val size=extractor.readSampleData(buffer,0)
                        if(size<0) {
                            decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded=true
                        } else {
                            decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0)
                            extractor.advance()
                        }
                    }
                }
                val index=decoder.dequeueOutputBuffer(info,10_000)
                if(index>=0) {
                    try {
                        if(info.size>0) {
                            lastOutputAt = System.nanoTime()
                            val pts=info.presentationTimeUs
                            require(pts > previousPts) { "Decoder timestamps are not strictly increasing; unsupported clip" }
                            previousPts=pts
                            if(firstPts==null) firstPts=pts
                            val time=pts-firstPts!!
                            decoded++
                            if(time>=nextAnalysis) {
                                val image=decoder.getOutputImage(index) ?: error("This decoder cannot expose RGB analysis frames; try an SDR H.264 clip")
                                val bitmap=try {
                                    require(image.cropRect.width()==width && image.cropRect.height()==height) { "Decoded crop changed; refusing misaligned overlays" }
                                    toBitmap(image, rotation, standard, full)
                                } finally { image.close() }
                                try { onFrame(bitmap,time,pts) } finally { bitmap.recycle() }
                                analyzed++
                                do { nextAnalysis+=66_667 } while(nextAnalysis<=time)
                                progress(time,duration)
                            }
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index,false) }
                }
                check(System.nanoTime()-lastOutputAt < 30_000_000_000L) { "Video decoder stalled" }
            }
            require(analyzed>0) { "No decodable frames" }
            val uprightW=if(rotation%180==0) width else height
            val uprightH=if(rotation%180==0) height else width
            return Capture(id=id, content_sha256=sha, width=uprightW, height=uprightH,
                rotation_degrees=rotation, first_pts_us=firstPts!!, duration_us=duration,
                decoded_frames=decoded, analyzed_frames=analyzed)
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private fun toBitmap(image: Image, rotation: Int, standard: Int, full: Boolean): Bitmap {
        val crop=image.cropRect
        // Downsample while reading YUV: keeps CPU/memory bounded without decoding a second bitmap.
        val step=maxOf(1, (maxOf(crop.width(),crop.height())+959)/960)
        val w=(crop.width()+step-1)/step; val h=(crop.height()+step-1)/step
        val pixels=IntArray(w*h)
        val planes=image.planes
        fun value(p: Int,x: Int,y: Int): Int {
            val plane=planes[p]
            return plane.buffer.get(plane.buffer.position()+y*plane.rowStride+x*plane.pixelStride).toInt() and 255
        }
        val kr=if(standard==MediaFormat.COLOR_STANDARD_BT709) .2126 else .299
        val kb=if(standard==MediaFormat.COLOR_STANDARD_BT709) .0722 else .114
        val kg=1-kr-kb
        for(y in 0 until h) for(x in 0 until w) {
            val px=crop.left+((x+.5f)*crop.width()/w).toInt().coerceAtMost(crop.width()-1)
            val py=crop.top+((y+.5f)*crop.height()/h).toInt().coerceAtMost(crop.height()-1)
            val luma=(value(0,px,py)-(if(full) 0 else 16))*(if(full) 1.0 else 255.0/219)
            val u=(value(1,px/2,py/2)-128)*(if(full) 1.0 else 255.0/224)
            val v=(value(2,px/2,py/2)-128)*(if(full) 1.0 else 255.0/224)
            val r=(luma+2*(1-kr)*v).toInt().coerceIn(0,255)
            val b=(luma+2*(1-kb)*u).toInt().coerceIn(0,255)
            val g=(luma-2*kb*(1-kb)/kg*u-2*kr*(1-kr)/kg*v).toInt().coerceIn(0,255)
            pixels[y*w+x]=(255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val base=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        if(rotation==0) return base
        val rotated=Bitmap.createBitmap(base,0,0,w,h,Matrix().apply { postRotate(rotation.toFloat()) },true)
        if(rotated!==base) base.recycle()
        return rotated
    }
}
