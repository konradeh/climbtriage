package app.climbtriage.perception

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.get
import app.climbtriage.domain.*
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Unvalidated color/geometry hint. Acceptance is an explicit user correction.
 * Same-color neighbors and shadow/occlusion cannot be resolved by this rule.
 */
object RouteSuggester {
    fun suggest(image:File,holds:List<Hold>,seedId:String):Set<String> {
        val bitmap=BitmapFactory.decodeFile(image.absolutePath) ?: error("Wall frame unavailable")
        try {
            fun center(h:Hold):Point {
                val points=h.parts.flatten()
                return Point(points.map { it.x }.average().toFloat(),points.map { it.y }.average().toFloat())
            }
            fun color(h:Hold):FloatArray? {
                val samples=mutableListOf<Int>()
                h.parts.forEach { ring ->
                    val left=(ring.minOf { it.x }*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
                    val right=(ring.maxOf { it.x }*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
                    val top=(ring.minOf { it.y }*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
                    val bottom=(ring.maxOf { it.y }*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
                    val step=maxOf(1,maxOf(right-left,bottom-top)/12)
                    for(y in top..bottom step step) for(x in left..right step step) {
                        if(Geometry.contains(ring,Point((x+.5f)/bitmap.width,(y+.5f)/bitmap.height))) samples+=bitmap[x,y]
                    }
                }
                if(samples.size<3) return null
                fun median(channel:(Int)->Int)=samples.map(channel).sorted()[samples.size/2]
                return FloatArray(3).also { Color.RGBToHSV(median(Color::red),median(Color::green),median(Color::blue),it) }
            }
            val seed=holds.first { it.id==seedId }
            val reference=color(seed) ?: error("Too few visible pixels for a color suggestion")
            require(reference[1]>.15f) { "Low-saturation holds need manual route membership" }
            val origin=center(seed)
            return holds.filter { h ->
                val c=color(h) ?: return@filter false
                val hue=abs(c[0]-reference[0]).let { min(it,360-it) }
                h.kind=="hold" && hue<=22 && abs(c[1]-reference[1])<=.3f && abs(c[2]-reference[2])<=.4f && abs(center(h).x-origin.x)<=.45f
            }.map { it.id }.toSet()+seedId
        } finally { bitmap.recycle() }
    }
}
