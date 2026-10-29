package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CameoTestSuiteTest {
    private val fonts=StrokeFontEngine(listOf("scripts","futural").associateWith { StrokeFontEngine.parse(File("src/main/assets/$it.tsv").readText()) })
    private val model="CAMEO 5 V1.04"
    private fun smallSuite()=HardwareSuite(Paper(),listOf(HardwareStage("Teste","Traço",Page(listOf(listOf(Pt(30.0,20.0),Pt(35.0,20.0),Pt(40.0,20.0))),1))))
    private class Link:CameoMotionProbe.Link {
        val sent=mutableListOf<String>();var onWrite:(String)->Unit={};var statusValue=Cameo5Driver.Status.READY
        override suspend fun status()=statusValue
        override suspend fun write(command:ByteArray) {val text=command.toString(Charsets.US_ASCII);sent.add(text);onWrite(text)}
    }
    @Test fun buildsFiveMeasuredPatternsWithRealSamplePaths() {
        val s=CameoTestSuite.build(Paper(),fonts)
        assertEquals(5,s.stages.size)
        assertEquals(5.0,s.stages[0].page.strokes.single().let { it.first().distance(it.last()) },0.0001)
        val separated=s.stages[1].page.strokes
        assertEquals(5.0,separated[0].last().distance(separated[1].first()),0.0001)
        val square=s.stages[2].page.strokes.single()
        square.zipWithNext().forEach { assertEquals(10.0,it.first.distance(it.second),0.0001) }
        assertEquals(6,s.stages[3].page.strokes.size)
        assertTrue(s.stages.last().page.strokes.size>20)
        assertEquals(s.stages.flatMap {it.page.strokes},s.preview.strokes)
        CameoTestSuite.validate(s)
    }
    @Test fun calibrationTranslatesEveryStageOnce() {
        val a=CameoTestSuite.build(Paper(),fonts)
        val b=CameoTestSuite.build(Paper(offsetX=-0.1,offsetY=0.2),fonts)
        a.preview.strokes.flatten().zip(b.preview.strokes.flatten()).forEach { (x,y)->assertEquals(x.x-0.1,y.x,0.00001);assertEquals(x.y+0.2,y.y,0.00001) }
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsTooSmallSheet() {CameoTestSuite.build(Paper(height=60.0),fonts)}
    @Test fun setupUsesDocumentedHolderOneWithoutBladeDepthOrAcceleration() {
        val setup=PenSetup().commands().map {it.toString(Charsets.US_ASCII).trimEnd(Cameo5Driver.ETX)}
        assertEquals(listOf("J1","FX5,1","!5,1","FC0,1,1","FE0,1","FF0,0,1"),setup)
        assertFalse(setup.any {it.startsWith("TF") || it.startsWith("TJ")})
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsExcessivePressure() {PenSetup(force=21).commands()}
    @Test fun sendsCompleteStrokeAndLiftsAtEndpoint() = runBlocking {
        val link=Link()
        assertTrue(CameoTestSuite.run(model,smallSuite(),PenSetup(),link,pollMillis=1))
        assertEquals(listOf("M400,600\u0003","D400,700\u0003","D400,800\u0003","M400,800\u0003"),link.sent.drop(6))
        assertTrue(link.sent.all {it.toByteArray().size<=20})
    }
    @Test fun stopDuringDrawingLiftsAtLastConfirmedPointAndSendsNoMoreDrawCommands() = runBlocking {
        val link=Link();var stop=false
        link.onWrite={if(it.startsWith("D")) stop=true}
        assertFalse(CameoTestSuite.run(model,smallSuite(),PenSetup(),link,stop={stop},pollMillis=1))
        assertEquals(listOf("M400,600\u0003","D400,700\u0003","M400,700\u0003"),link.sent.drop(6))
    }
    @Test fun pauseHoldsBetweenStrokesWithPenUp() = runBlocking {
        val link=Link();var paused=false
        val base=smallSuite();val suite=base.copy(stages=listOf(base.stages.single().copy(page=Page(base.preview.strokes+base.preview.strokes,1))))
        val task=async {CameoTestSuite.run(model,suite,PenSetup(),link,paused={paused},onStroke={done,_->if(done==1) paused=true},pollMillis=1)}
        withTimeout(5000) { while(!paused) delay(1) }
        val before=link.sent.size;delay(30)
        assertEquals(before,link.sent.size);assertTrue(link.sent.last().startsWith("M"))
        paused=false;assertTrue(task.await())
    }
    @Test fun busyBeforeStartSendsNothing() = runBlocking {
        val link=Link();link.statusValue=Cameo5Driver.Status.MOVING
        try {CameoTestSuite.run(model,smallSuite(),PenSetup(),link,pollMillis=1);fail("busy accepted")} catch(_:IllegalStateException) {}
        assertTrue(link.sent.isEmpty())
    }
    @Test fun unknownStatusDoesNotRetryOrSendFurtherCommands() = runBlocking {
        val link=Link();link.onWrite={if(it.startsWith("D")) link.statusValue=Cameo5Driver.Status.UNKNOWN}
        try {CameoTestSuite.run(model,smallSuite(),PenSetup(),link,pollMillis=1);fail("unknown accepted")} catch(_:IllegalStateException) {}
        assertEquals(1,link.sent.count {it.startsWith("D")})
        assertTrue(link.sent.last().startsWith("D")) // Uncertain state: no guessed recovery commands.
    }
    @Test fun wrongModelSendsNothing() = runBlocking {
        val link=Link()
        try {CameoTestSuite.run("CAMEO 5 ALPHA",smallSuite(),PenSetup(),link);fail("model accepted")} catch(_:IllegalArgumentException) {}
        assertTrue(link.sent.isEmpty())
    }
}
