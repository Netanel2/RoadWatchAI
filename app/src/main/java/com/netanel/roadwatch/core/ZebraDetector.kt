package com.netanel.roadwatch.core

import kotlin.math.*

/** Small-image connected stripe components; handles oblique zebra markings without a trained model. */
class ZebraDetector {
    private data class Stripe(val x: Float, val y: Float, val angle: Float, val length: Float, val thickness: Float, val box: Box)
    fun detect(pixels: IntArray, width: Int, height: Int): CrosswalkEstimate? {
        require(pixels.size == width * height)
        val mask = BooleanArray(pixels.size) { i ->
            val c=pixels[i]; val r=(c shr 16) and 255; val g=(c shr 8) and 255; val b=c and 255
            minOf(r,g,b) >= 130 && maxOf(r,g,b)-minOf(r,g,b) <= 48
        }
        val seen=BooleanArray(pixels.size); val queue=IntArray(pixels.size); val stripes=mutableListOf<Stripe>()
        for (seed in pixels.indices) {
            if (!mask[seed] || seen[seed]) continue
            var head=0; var tail=1; queue[0]=seed; seen[seed]=true
            var sx=0.0; var sy=0.0; var sxx=0.0; var syy=0.0; var sxy=0.0
            var left=width; var top=height; var right=0; var bottom=0
            while (head<tail) {
                val index=queue[head++]; val x=index%width; val y=index/width
                sx+=x; sy+=y; sxx+=x*x; syy+=y*y; sxy+=x*y
                left=min(left,x); right=max(right,x); top=min(top,y); bottom=max(bottom,y)
                for (dy in -1..1) for (dx in -1..1) {
                    val xx=x+dx; val yy=y+dy
                    if (xx !in 0 until width || yy !in 0 until height) continue
                    val j=yy*width+xx
                    if (mask[j] && !seen[j]) { seen[j]=true; queue[tail++]=j }
                }
            }
            if (tail < 10 || tail > width*height*.055) continue
            val mx=sx/tail; val my=sy/tail
            val xx=sxx/tail-mx*mx; val yy=syy/tail-my*my; val xy=sxy/tail-mx*my
            val root=sqrt((xx-yy)*(xx-yy)+4*xy*xy)
            val major=(xx+yy+root)/2; val minor=max(.3,(xx+yy-root)/2)
            val length=sqrt(12*major).toFloat(); val thickness=sqrt(12*minor).toFloat()
            if (length < 9 || length/thickness !in 2f..18f || tail/(length*thickness) < .48f) continue
            stripes += Stripe(mx.toFloat(),my.toFloat(),(.5*atan2(2*xy,xx-yy)).toFloat(),length,thickness,
                Box(left.toFloat()/width,top.toFloat()/height,(right+1f)/width,(bottom+1f)/height))
        }
        var best: CrosswalkEstimate?=null
        val candidates = stripes.sortedByDescending { it.length }.take(180)
        for (anchor in candidates) {
            val ux=cos(anchor.angle); val uy=sin(anchor.angle)
            val group=candidates.filter { s ->
                abs(cos(s.angle-anchor.angle)) > .94f && min(s.length,anchor.length)/max(s.length,anchor.length) > .48f &&
                    abs((s.x-anchor.x)*ux+(s.y-anchor.y)*uy) < anchor.length*.65f
            }.sortedBy { -it.x*uy+it.y*ux }
            for (start in group.indices) for (end in start+3..min(group.lastIndex,start+9)) {
                val g=group.subList(start,end+1)
                val gaps=g.zipWithNext { a,b -> -(b.x-a.x)*uy+(b.y-a.y)*ux }
                val median=gaps.sorted()[gaps.size/2]
                if (median < 3 || median > anchor.length*.9f || gaps.any { abs(it-median)>median*.5f }) continue
                if (g.zipWithNext().any { (a,b) -> median < (a.thickness+b.thickness)*.6f }) continue
                val box=Box(g.minOf{it.box.left},g.minOf{it.box.top},g.maxOf{it.box.right},g.maxOf{it.box.bottom})
                if (box.area !in .003f.. .4f || box.width > .94f || box.height > .85f) continue
                val score=(.67f+(g.size-4)*.035f + .06f*(1f-gaps.maxOf{abs(it-median)}/median)).coerceAtMost(.94f)
                if (score > (best?.confidence ?: 0f)) best=CrosswalkEstimate(box,score)
            }
        }
        return best
    }
}
