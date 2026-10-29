package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CameoPageJobTest {
    private val fonts=StrokeFontEngine(listOf("scripts","futural").associateWith {StrokeFontEngine.parse(File("src/main/assets/$it.tsv").readText())})
    @Test fun sendsActualEditorTextUsingTheSamePlannedPreviewGeometry() = runBlocking {
        val settings=Settings(text="Esta é minha própria redação. Não é uma amostra fixa!\nCoração, amanhã, café e España.")
        val raw=PageLayoutEngine(fonts).layout(settings)
        val page=raw.pages.single().let {it.copy(strokes=PlotPlanner.plan(it,settings).strokes)}
        val job=CameoPageJob.prepare(settings.paper,page,1,1)
        val sent=mutableListOf<ByteArray>()
        val link=object:CameoMotionProbe.Link {
            override suspend fun status()=Cameo5Driver.Status.READY
            override suspend fun write(command:ByteArray) {sent.add(command.copyOf())}
        }
        assertTrue(CameoJobRunner.run("CAMEO 5 V1.04",job.paper,listOf(job.stage),PenSetup(1,10),link))
        assertSameInk(Cameo5Driver.encode(page,settings.paper),sent.drop(1).fold(byteArrayOf()) {a,b->a+b})
        assertEquals(page.strokes,job.page.strokes)
        assertTrue(sent.all {it.size<=512})
    }
    @Test fun supportsFullSheetInsteadOfTheOldSmallTestArea() {
        val page=Page(listOf(listOf(Pt(25.0,20.0),Pt(190.0,275.0))),1)
        val job=CameoPageJob.prepare(Paper(),page,2,3)
        assertEquals(2,job.number);assertEquals(3,job.totalPages)
        try {CameoTestSuite.validate(HardwareSuite(Paper(),listOf(job.stage)));fail("test-area limit should remain")}
        catch(_:IllegalArgumentException) {}
    }
    @Test fun pageSelectionDoesNotSendOtherPages() = runBlocking {
        val settings=Settings(text="Meu texto de fichário. ".repeat(500))
        val doc=PageLayoutEngine(fonts).layout(settings)
        assertTrue(doc.pages.size>1)
        val job=CameoPageJob.prepare(settings.paper,doc.pages[1],2,doc.pages.size)
        assertEquals(doc.pages[1].strokes,job.page.strokes)
        assertNotEquals(doc.pages[0].strokes,job.page.strokes)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsBlankPage() {CameoPageJob.prepare(Paper(),Page(emptyList(),0),1,1)}
    @Test(expected=IllegalArgumentException::class) fun rejectsOutOfBoundsPage() {CameoPageJob.prepare(Paper(),Page(listOf(listOf(Pt(25.0,20.0),Pt(211.0,20.0))),1),1,1)}
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidPageNumber() {CameoPageJob.prepare(Paper(),Page(listOf(listOf(Pt(25.0,20.0),Pt(26.0,20.0))),1),0,1)}
    @Test fun unloadedSheetSendsNoPlottingOrSetupCommands() = runBlocking {
        var writes=0
        val link=object:CameoMotionProbe.Link {
            override suspend fun status()=Cameo5Driver.Status.UNLOADED
            override suspend fun write(command:ByteArray) {writes++}
        }
        val job=CameoPageJob.prepare(Paper(),Page(listOf(listOf(Pt(25.0,20.0),Pt(26.0,20.0))),1),1,1)
        try {CameoJobRunner.run("CAMEO 5 V1.04",job.paper,listOf(job.stage),PenSetup(1,10),link);fail("unloaded accepted")}
        catch(e:PlotterNotReadyException) {assertEquals(Cameo5Driver.Status.UNLOADED,e.status)}
        assertEquals(0,writes)
    }
    @Test fun openingDefaultsPreserveTextPaperAndNativePrinterSettings() {
        val old=Settings(text="Meu conteúdo salvo",paper=Paper(offsetX=-0.2),style=Style.CURSIVE,quality=Quality.CAREFUL,penForce=2,penSpeed=10)
        val opened=old.forOpening()
        assertEquals(old.text,opened.text);assertEquals(old.paper,opened.paper)
        assertEquals(Style.MESSY,opened.style);assertEquals(Quality.FAST,opened.quality)
        assertEquals(80,opened.irregularity);assertEquals(2,opened.penForce);assertEquals(10,opened.penSpeed)
    }
    @Test fun messyFontIsDeterministicDistinctAndKeepsOnlyStrokePaths() {
        val settings=Settings(text="Minha letra feia: ação, café, amanhã e España!",style=Style.MESSY,irregularity=100)
        val engine=PageLayoutEngine(fonts)
        assertEquals(engine.layout(settings),engine.layout(settings))
        assertNotEquals(engine.layout(settings),engine.layout(settings.copy(style=Style.PRINT)))
        assertTrue(engine.layout(settings).pages.single().strokes.all {it.size>=2})
        assertFalse(PlotPlanner.svg(engine.layout(settings).pages.single(),settings.paper).contains("<text"))
    }
}
