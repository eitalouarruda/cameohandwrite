package com.cameohandwrite.domain

import java.util.Locale
import kotlin.math.abs

data class Plan(val strokes: List<Stroke>,val writingMm: Double,val travelMm: Double,val seconds: Double)
object PlotPlanner {
    private fun distanceToSegment(p:Pt,a:Pt,b:Pt):Double {
        val dx=b.x-a.x;val dy=b.y-a.y
        if(dx==0.0 && dy==0.0) return p.distance(a)
        val t=(((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
        return p.distance(Pt(a.x+t*dx,a.y+t*dy))
    }
    fun simplify(points:Stroke,tolerance:Double):Stroke {
        require(tolerance.isFinite() && tolerance>=0)
        if(points.size<3 || tolerance==0.0) return points
        // Iterative Douglas–Peucker avoids stack overflow on long imported paths.
        val keep=BooleanArray(points.size);keep[0]=true;keep[points.lastIndex]=true
        val stack=ArrayDeque<Pair<Int,Int>>();stack.add(0 to points.lastIndex)
        while(stack.isNotEmpty()) {
            val (a,b)=stack.removeLast();var d=0.0;var at=-1
            for(i in a+1 until b) { val v=distanceToSegment(points[i],points[a],points[b]);if(v>d) { d=v;at=i } }
            if(d>tolerance && at>=0) { keep[at]=true;stack.add(a to at);stack.add(at to b) }
        }
        return points.filterIndexed { i,_ -> keep[i] }
    }
    fun plan(page:Page,settings:Settings):Plan {
        require(settings.writeSpeed>0 && settings.travelSpeed>0 && settings.writeSpeed.isFinite() && settings.travelSpeed.isFinite())
        val seen=mutableSetOf<Pair<Pt,Pt>>()
        val out=mutableListOf<Stroke>();var cursor=Pt(0.0,0.0);var travel=0.0;var writing=0.0
        page.strokes.forEach { source ->
            val simplified=simplify(source,settings.tolerance)
            var current=mutableListOf<Pt>()
            fun finish() {
                if(current.size>1) {
                    var s:Stroke=current.toList()
                    // Reverse only this stroke; never reorder letters or words.
                    if(cursor.distance(s.last())<cursor.distance(s.first())) s=s.reversed()
                    travel+=cursor.distance(s.first());writing+=s.zipWithNext().sumOf { (a,b)->a.distance(b) }
                    cursor=s.last();out.add(s)
                }
                current=mutableListOf()
            }
            simplified.zipWithNext().forEach { (a,b) ->
                if(a!=b && !seen.contains(a to b) && !seen.contains(b to a)) {
                    seen.add(a to b)
                    if(current.isEmpty()) current.add(a)
                    current.add(b)
                } else finish()
            }
            finish()
        }
        return Plan(out,writing,travel,writing/settings.writeSpeed+travel/settings.travelSpeed+out.size*0.18)
    }
    fun svg(page:Page,paper:Paper):String {
        fun n(v:Double)=String.format(Locale.US,"%.4f",v)
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${n(paper.width)}mm\" height=\"${n(paper.height)}mm\" viewBox=\"0 0 ${n(paper.width)} ${n(paper.height)}\"><g fill=\"none\" stroke=\"black\" stroke-width=\"0.3\" stroke-linecap=\"round\" stroke-linejoin=\"round\">")
            page.strokes.forEach { s -> if(s.isNotEmpty()) { append("<path d=\"M");s.forEachIndexed { i,p -> if(i>0) append(" L");append("${n(p.x)},${n(p.y)}") };append("\"/>") } }
            append("</g></svg>")
        }
    }
    fun alignment(p:Paper):Page {
        p.validate()
        val points=listOf(Pt(p.startX+3,p.firstLine),Pt(p.width-p.right-3,p.firstLine),Pt(p.startX+3,p.firstLine+p.lineSpacing))
        val strokes=points.flatMap { c -> listOf(listOf(Pt(c.x-1+p.offsetX,c.y+p.offsetY),Pt(c.x+1+p.offsetX,c.y+p.offsetY)),listOf(Pt(c.x+p.offsetX,c.y-1+p.offsetY),Pt(c.x+p.offsetX,c.y+1+p.offsetY))) }
        require(strokes.flatten().all { it.x in p.startX..(p.width-p.right) && it.y in p.top..(p.height-p.bottom) }) { "Teste fora das margens" }
        return Page(strokes,2)
    }
}
