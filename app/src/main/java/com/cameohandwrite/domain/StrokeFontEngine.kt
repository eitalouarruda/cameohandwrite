package com.cameohandwrite.domain

import java.text.Normalizer
import kotlin.random.Random
import kotlin.math.sin

data class Glyph(val width: Double, val strokes: List<Stroke>)
/** Hershey M/L data; no outline fonts, raster tracing or platform text rendering. */
class StrokeFontEngine(private val fonts: Map<String, Map<Char,Glyph>>) {
    companion object {
        fun parse(data: String): Map<Char,Glyph> = data.lineSequence().filter { it.isNotBlank() }.associate { line ->
            val parts = line.split('\t', limit=3)
            val paths = mutableListOf<Stroke>()
            var path = mutableListOf<Pt>()
            val tokens = Regex("[ML]|-?[0-9]+(?:\\.[0-9]+)?").findAll(parts[2]).map { it.value }.toList()
            var i=0
            while(i < tokens.size) {
                when(tokens[i]) {
                    "M" -> { if(path.isNotEmpty()) paths.add(path); path=mutableListOf(); i++ }
                    "L" -> i++
                    else -> { path.add(Pt(tokens[i].toDouble(),tokens[i+1].toDouble()-22.0)); i+=2 }
                }
            }
            if(path.isNotEmpty()) paths.add(path)
            parts[0].toInt().toChar() to Glyph(parts[1].toDouble(),paths)
        }
    }
    fun glyph(char: Char, style: Style): Glyph? {
        if(char == ' ') return Glyph(10.0,emptyList())
        val font = fonts[if(style == Style.CURSIVE || style == Style.SIMPLE) "scripts" else "futural"]!!
        font[char]?.let { return it }
        if(char == '¡') return font['!']?.let { g -> g.copy(strokes=g.strokes.map { s -> s.map { Pt(it.x,-14-it.y) } }) }
        if(char == '¿') return font['?']?.let { g -> g.copy(strokes=g.strokes.map { s -> s.map { Pt(g.width-it.x,-14-it.y) } }) }
        val replacements=mapOf('“' to '"','”' to '"','‘' to '\'','’' to '\'','–' to '-','—' to '-')
        replacements[char]?.let { return font[it] }
        val n = Normalizer.normalize(char.toString(),Normalizer.Form.NFD)
        if(n.length != 2) return null
        val base=font[n[0]] ?: return null
        val x=base.width/2
        val mark: Stroke = when(n[1]) {
            '\u0301' -> listOf(Pt(x-2,-25.0),Pt(x+2,-29.0))
            '\u0300' -> listOf(Pt(x-2,-29.0),Pt(x+2,-25.0))
            '\u0302' -> listOf(Pt(x-3,-25.0),Pt(x,-29.0),Pt(x+3,-25.0))
            '\u0303' -> listOf(Pt(x-4,-26.0),Pt(x-2,-28.0),Pt(x+2,-25.0),Pt(x+4,-27.0))
            '\u0308' -> listOf(Pt(x-3,-27.0),Pt(x-2.5,-27.0))
            '\u0327' -> listOf(Pt(x,0.0),Pt(x+1,3.0),Pt(x-2,5.0))
            else -> return null
        }
        val marks=if(n[1]=='\u0308') listOf(mark,listOf(Pt(x+3,-27.0),Pt(x+3.5,-27.0))) else listOf(mark)
        return base.copy(strokes=base.strokes+marks)
    }
    fun render(char: Char, settings: Settings, random: Random): Glyph? {
        val messy=settings.style==Style.MESSY
        val alternate=messy && settings.irregularity>0 && char.isLetter() && random.nextDouble()<0.3*settings.irregularity/100.0
        val raw = (if(alternate) glyph(char,Style.CURSIVE) else null) ?: glyph(char,settings.style) ?: return null
        val amount=settings.irregularity.coerceIn(0,100)/100.0 * when(settings.style) { Style.MESSY -> 1.0; Style.UNEVEN -> 0.7; else -> 0.35 }
        val width=1+random.nextDouble(if(messy) -0.24 else -0.15,if(messy) 0.24 else 0.15)*amount
        val height=1+random.nextDouble(-0.12,0.12)*amount
        val shear=(if(settings.style==Style.PRINT) 0.0 else 0.06)+random.nextDouble(if(messy) -0.38 else -0.2,if(messy) 0.38 else 0.2)*amount
        val dy=random.nextDouble(-0.7,0.7)*amount
        val scale=settings.letterHeight/21.0
        val phase=if(messy && amount>0) random.nextDouble(0.0,6.28) else 0.0
        val bend=if(messy && amount>0) random.nextDouble(-1.0,1.0)*amount else 0.0
        val transformed=raw.strokes.map { s -> s.map { Pt((it.x*width-it.y*shear+bend*(it.y/21.0)*(it.y/21.0)+(if(messy) sin(it.y*0.55+phase)*0.45*amount else 0.0))*scale,(it.y*height+dy+(if(messy) sin(it.x*0.4+phase)*0.25*amount else 0.0))*scale) } }
        val left=minOf(0.0,transformed.flatten().minOfOrNull { it.x } ?: 0.0)
        return Glyph(raw.width*width*scale-left,transformed.map { s -> s.map { Pt(it.x-left,it.y) } })
    }
}
