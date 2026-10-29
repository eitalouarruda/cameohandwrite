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
        val sent=mutableListOf<String>()
        val commands get()=sent.flatMap { data -> data.split(Cameo5Driver.ETX).filter {it.isNotEmpty()}.map {it+Cameo5Driver.ETX} }
        var statusReads=0
        var onWrite:(String)->Unit={};var statusValue=Cameo5Driver.Status.READY
        override suspend fun status():Cameo5Driver.Status {statusReads++;return statusValue}
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
        assertEquals(listOf("M400,600\u0003","D400,700\u0003","D400,800\u0003","M400,800\u0003"),link.commands.drop(6))
        assertTrue(link.sent.all {it.toByteArray().size<=512})
    }
    @Test fun stopDuringBlockFinishesPenUpBlockAndSendsNoNextStage() = runBlocking {
        val link=Link();var stop=false
        link.onWrite={if(it.contains("D")) stop=true}
        val base=smallSuite();val suite=base.copy(stages=base.stages+base.stages)
        assertFalse(CameoTestSuite.run(model,suite,PenSetup(),link,stop={stop},pollMillis=1))
        assertEquals(listOf("M400,600\u0003","D400,700\u0003","D400,800\u0003","M400,800\u0003"),link.commands.drop(6))
        assertEquals(2,link.sent.size) // Setup + one bounded block; no guessed recovery or return.
    }
    @Test fun pauseHoldsBetweenBlocksWithPenUp() = runBlocking {
        val link=Link();var paused=false
        val base=smallSuite();val suite=base.copy(stages=base.stages+base.stages)
        val task=async {CameoTestSuite.run(model,suite,PenSetup(),link,paused={paused},onStroke={done,_->if(done==1) paused=true},pollMillis=1)}
        withTimeout(5000) { while(!paused) delay(1) }
        val before=link.sent.size;delay(30)
        assertEquals(before,link.sent.size);assertTrue(link.commands.last().startsWith("M"))
        paused=false;assertTrue(task.await())
    }
    @Test fun busyBeforeStartSendsNothing() = runBlocking {
        val link=Link();link.statusValue=Cameo5Driver.Status.MOVING
        try {CameoTestSuite.run(model,smallSuite(),PenSetup(),link,pollMillis=1);fail("busy accepted")} catch(_:IllegalStateException) {}
        assertTrue(link.sent.isEmpty())
    }
    @Test fun unknownStatusDoesNotRetryOrSendFurtherCommands() = runBlocking {
        val link=Link();link.onWrite={if(it.contains("D")) link.statusValue=Cameo5Driver.Status.UNKNOWN}
        try {CameoTestSuite.run(model,smallSuite(),PenSetup(),link,pollMillis=1);fail("unknown accepted")} catch(_:IllegalStateException) {}
        assertEquals(1,link.sent.count {it.contains("D")})
        assertEquals(2,link.sent.size) // No retry or recovery after an uncertain block status.
    }
    @Test fun sampleAvoidsPerSegmentStatusRoundTrips() = runBlocking {
        val suite=CameoTestSuite.build(Paper(),fonts)
        val link=Link();var done=0
        assertTrue(CameoTestSuite.run(model,suite,PenSetup(),link,onStroke={n,_->done=n}))
        val oldCommands=6+suite.preview.strokes.sumOf { it.size+1 }
        assertTrue("Status requests ${link.statusReads} vs old command count $oldCommands",link.statusReads*5<oldCommands)
        assertEquals(suite.preview.strokes.size,done)
        val encoded=suite.stages.flatMap { Cameo5Driver.encode(it.page,suite.paper).toString(Charsets.US_ASCII).split(Cameo5Driver.ETX).filter { it.isNotEmpty() } }
        assertEquals(encoded.filter {it.startsWith("D")},link.commands.filter {it.startsWith("D")}.map {it.trimEnd(Cameo5Driver.ETX)})
        println("SAMPLE PERF: old mandatory waits=${oldCommands*100} ms; ready queries now=${link.statusReads}; stream blocks=${link.sent.size}; fixed waits now=0")
    }
    @Test fun wrongModelSendsNothing() = runBlocking {
        val link=Link()
        try {CameoTestSuite.run("CAMEO 5 ALPHA",smallSuite(),PenSetup(),link);fail("model accepted")} catch(_:IllegalArgumentException) {}
        assertTrue(link.sent.isEmpty())
    }
}
