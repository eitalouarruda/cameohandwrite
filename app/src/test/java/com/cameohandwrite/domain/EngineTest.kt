package com.cameohandwrite.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class EngineTest {
    private val fonts=StrokeFontEngine(listOf("scripts","futural").associateWith { StrokeFontEngine.parse(File("src/main/assets/$it.tsv").readText()) })
    private val engine=PageLayoutEngine(fonts)
    @Test fun realSingleStrokeA() {
        val a=fonts.glyph('A',Style.PRINT)!!
        assertEquals(3,a.strokes.size)
        assertEquals(2,a.strokes.first().size)
        assertEquals(21.0,a.strokes.first().first().distance(a.strokes.first().last()).let { kotlin.math.sqrt(it*it-64) },0.001)
    }
    @Test fun portugueseAndSpanishComposeMarks() {
        for(c in "áàâãäéêíóôõúüçÁÀÂÃÉÊÍÓÔÕÚÜÇñÑ¡¿") assertNotNull("$c",fonts.glyph(c,Style.SIMPLE))
        assertTrue(fonts.glyph('ç',Style.PRINT)!!.strokes.size>fonts.glyph('c',Style.PRINT)!!.strokes.size)
        assertTrue(fonts.glyph('á',Style.PRINT)!!.strokes.flatten().minOf { it.y } < -21)
    }
    @Test(expected=IllegalArgumentException::class) fun unknownCharacterIsNotSilentlyDropped() { engine.layout(Settings(text="Hello 🦊")) }
    @Test fun defaultDocumentAndEveryAsciiGlyphFitMargins() {
        val doc=engine.layout(Settings())
        assertTrue(doc.pages.first().strokes.isEmpty())
        val all=engine.layout(Settings(text=(33..126).map { it.toChar() }.joinToString(" ")))
        all.pages.flatMap { it.strokes }.flatten().forEach { assertTrue(it.x>=25 && it.x<=195 && it.y>=10 && it.y<=282) }
    }
    @Test fun unicodeWhitespaceIsAcceptedWithoutMissingGlyphCrash() {
        val plain=engine.layout(Settings(text="Olá mundo hoje"))
        val nbsp=engine.layout(Settings(text="Olá\u00a0mundo\u2003hoje"))
        assertEquals(plain,nbsp)
    }
    @Test fun wrapsAndPaginates() {
        val s=Settings(text=(1..300).joinToString(" ") { "palavra" },paper=Paper(width=90.0,height=70.0,left=20.0,right=10.0,bottom=10.0))
        val result=engine.layout(s)
        assertTrue(result.pages.size>2)
        assertEquals(5,result.pages.first().lines)
        assertTrue(result.pages.all { it.lines<=s.paper.lineCount })
    }
    @Test fun explicitBlankLinesAndParagraphsRetained() {
        val doc=engine.layout(Settings(text="a\n\nb"))
        assertEquals(3,doc.pages.single().lines)
    }
    @Test fun scalesInMillimeters() {
        val one=fonts.render('A',Settings(letterHeight=3.0,irregularity=0,style=Style.PRINT),Random(1))!!
        val two=fonts.render('A',Settings(letterHeight=6.0,irregularity=0,style=Style.PRINT),Random(1))!!
        assertEquals(one.width*2,two.width,0.00001)
        assertEquals(3.0,one.strokes.flatten().maxOf { it.y }-one.strokes.flatten().minOf { it.y },0.00001)
    }
    @Test fun calibratedGeometryMovesOnceAndSvgUsesMillimeters() {
        val s=Settings(text="Abc",irregularity=0)
        val a=engine.layout(s).pages.single()
        val p=s.paper.copy(offsetX=0.1,offsetY=-0.2)
        val b=engine.layout(s.copy(paper=p)).pages.single()
        assertEquals(a.strokes.first().first().x+0.1,b.strokes.first().first().x,0.00001)
        assertEquals(a.strokes.first().first().y-0.2,b.strokes.first().first().y,0.00001)
        val svg=PlotPlanner.svg(b,p)
        assertTrue(svg.contains("210.0000mm"));assertTrue(svg.contains("<path"));assertFalse(svg.contains("<text"))
    }
    @Test fun negativeCalibrationIsAppliedRatherThanCancelled() {
        val s=Settings(text="Abc",irregularity=0)
        val a=engine.layout(s).pages.single().strokes.flatten()
        val b=engine.layout(s.copy(paper=s.paper.copy(offsetX=-0.1,offsetY=-0.1))).pages.single().strokes.flatten()
        a.zip(b).forEach { (before,after) -> assertEquals(before.x-0.1,after.x,0.00001);assertEquals(before.y-0.1,after.y,0.00001) }
    }
    @Test fun pageVariationIsDeterministic() {
        val s=Settings(text="Ação e café. ".repeat(600),style=Style.MESSY,irregularity=100)
        assertEquals(engine.layout(s),engine.layout(s))
        assertNotEquals(engine.layout(s),engine.layout(s.copy(seed=32)))
    }
    @Test fun centerAndJustifyStayInsideMargins() {
        for(alignment in Align.entries) {
            val s=Settings(text="Texto para testar os limites das margens. ".repeat(30),alignment=alignment,irregularity=100)
            engine.layout(s).pages.flatMap { it.strokes }.flatten().forEach { assertTrue(it.x>=s.paper.startX && it.x<=s.paper.width-s.paper.right+0.001) }
        }
    }
    @Test fun simplificationPreservesEndpointsAndRemovesCollinearPoints() {
        val line=listOf(Pt(1.0,2.0),Pt(2.0,2.0),Pt(3.0,2.0))
        assertEquals(listOf(line.first(),line.last()),PlotPlanner.simplify(line,0.1))
        val triangle=listOf(Pt(0.0,0.0),Pt(1.0,1.0),Pt(2.0,0.0))
        assertEquals(triangle,PlotPlanner.simplify(triangle,0.1))
    }
    @Test fun removesReverseDuplicateSegmentsWithoutJoiningAcrossPenLifts() {
        val line=listOf(Pt(25.0,20.0),Pt(26.0,20.0))
        val plan=PlotPlanner.plan(Page(listOf(line,line.reversed()),1),Settings())
        assertEquals(1,plan.strokes.size);assertEquals(1.0,plan.writingMm,0.00001)
        assertTrue(plan.seconds>0)
    }
    @Test fun alignmentTestFitsCalibratedArea() {
        val page=PlotPlanner.alignment(Paper(offsetX=0.1,offsetY=0.1))
        assertEquals(6,page.strokes.size)
        assertEquals(27.1,page.strokes.first().first().x,0.00001)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidMargins() { engine.layout(Settings(paper=Paper(left=200.0))) }
    @Test(expected=IllegalArgumentException::class) fun rejectsOversizedLetters() { engine.layout(Settings(letterHeight=10.0)) }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnsafeCalibration() { engine.layout(Settings(text="Teste de limites",paper=Paper(offsetY=-20.0))) }
    @Test fun allFiveStylesGenerateRealPaths() { for(style in Style.entries) assertTrue(engine.layout(Settings(text="Olá ação 123",style=style)).pages.first().strokes.isNotEmpty()) }
}
