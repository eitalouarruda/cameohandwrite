package com.cameohandwrite.domain

import kotlin.random.Random

class PageLayoutEngine(private val fonts: StrokeFontEngine) {
    fun layout(settings: Settings): Document {
        val p=settings.paper; p.validate()
        require(settings.letterHeight.isFinite() && settings.letterHeight in 1.0..20.0) { "Altura deve estar entre 1 e 20 mm" }
        require(settings.wordSpace.isFinite() && settings.wordSpace in 0.0..20.0) { "Espaçamento inválido" }
        require(settings.letterHeight*1.65 < p.lineSpacing) { "A letra com acentos precisa caber entre as pautas" }
        require(settings.text.length<=200000) { "Limite de 200 mil caracteres por documento" }
        val unsupported=settings.text.filter { !it.isWhitespace() && fonts.glyph(it,settings.style)==null }.toSet()
        require(unsupported.isEmpty()) { "Caracteres sem traço: " + unsupported.joinToString(" ") }
        val pages=mutableListOf<Page>()
        var strokes=mutableListOf<Stroke>(); var row=0; var page=0
        var random=Random(settings.seed+page*7919)
        data class Item(val char: Char,val glyph: Glyph)
        var line=mutableListOf<Item>(); var lineWidth=0.0
        // Conservative reservation for accents, descenders and per-glyph width variation.
        val minX=maxOf(p.startX,p.startX-p.offsetX)
        val maxX=minOf(p.width-p.right,p.width-p.right-p.offsetX)
        val width=maxX-minX
        require(width>settings.letterHeight*2) { "Calibração elimina a largura útil" }
        val warnings=mutableSetOf<String>()
        fun flush(paragraphEnd: Boolean) {
            val baseline=p.firstLine+row*p.lineSpacing
            val extra=if(settings.alignment==Align.JUSTIFY && !paragraphEnd && line.any { it.char==' ' }) (width-lineWidth).coerceAtLeast(0.0)/line.count { it.char==' ' } else 0.0
            var x=minX+if(settings.alignment==Align.CENTER) (width-lineWidth)/2 else 0.0
            for(item in line) {
                item.glyph.strokes.forEach { s ->
                    val transformed=s.map { Pt(it.x+x+p.offsetX,it.y+baseline+p.offsetY) }
                    require(transformed.all { it.x >= p.startX-0.001 && it.x<=p.width-p.right+0.001 && it.y>=p.top && it.y<=p.height-p.bottom }) { "Traços fora das margens: ajuste primeira pauta, altura ou calibração" }
                    if(transformed.size>=2) strokes.add(transformed)
                }
                x+=item.glyph.width+if(item.char==' ') extra else 0.0
            }
            line=mutableListOf();lineWidth=0.0;row++
            if(row>=p.lineCount) {
                pages.add(Page(strokes,row)); strokes=mutableListOf();row=0;page++
                random=Random(settings.seed+page*7919)
            }
        }
        fun measured(c:Char): Item {
            val g=fonts.render(c,settings,random)!!
            val visual=g.strokes.flatten().maxOfOrNull { it.x } ?: 0.0
            // Advance includes all visible points; avoids overrunning line at the last glyph.
            return Item(c,g.copy(width=if(c==' ') settings.wordSpace else maxOf(g.width,visual)+settings.letterHeight*0.045))
        }
        val paragraphs=settings.text.replace("\r\n","\n").replace('\r','\n').split('\n')
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            val words=paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            for(word in words) {
                var items=word.map(::measured)
                var w=items.sumOf { it.glyph.width }
                val space=if(line.isEmpty()) 0.0 else settings.wordSpace
                if(line.isNotEmpty() && lineWidth+space+w>width) {
                    flush(false)
                    // regenerate in the page's deterministic random stream after any page break
                    items=word.map(::measured);w=items.sumOf { it.glyph.width }
                }
                if(line.isNotEmpty()) { line.add(measured(' '));lineWidth+=settings.wordSpace }
                for(item in items) {
                    require(item.glyph.width <= width) { "Letra maior que a largura útil" }
                    if(lineWidth+item.glyph.width>width) { warnings.add("Palavra longa dividida entre linhas");flush(false) }
                    line.add(item);lineWidth+=item.glyph.width
                }
            }
            if(line.isNotEmpty() || paragraphIndex<paragraphs.lastIndex) flush(true)
        }
        if(strokes.isNotEmpty() || pages.isEmpty() || row>0) pages.add(Page(strokes,row))
        return Document(pages,warnings.toList())
    }
}
