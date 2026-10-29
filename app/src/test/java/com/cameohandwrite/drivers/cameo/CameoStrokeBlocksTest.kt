package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CameoStrokeBlocksTest {
    private val paper=Paper()
    private fun commands(bytes:ByteArray)=bytes.toString(Charsets.US_ASCII).split(Cameo5Driver.ETX).filter {it.isNotEmpty()}
    @Test fun batchesStrokesWithoutChangingDrawCommandsAndEndsEveryBlockPenUp() {
        val page=Page((0 until 30).map {i->listOf(Pt(30.0+i,20.0),Pt(31.0+i,20.0),Pt(31.0+i,21.0))},1)
        val blocks=CameoStrokeBlocks.build(page,paper)
        assertTrue(blocks.size<page.strokes.size)
        assertEquals(page.strokes.size,blocks.sumOf {it.completedStrokes})
        assertEquals(commands(Cameo5Driver.encode(page,paper)),blocks.flatMap {commands(it.bytes)})
        blocks.forEach {assertTrue(it.bytes.size<=512);assertTrue(commands(it.bytes).first().startsWith("M"));assertTrue(commands(it.bytes).last().startsWith("M"))}
    }
    @Test fun longStrokeSplitsAtKnownEndpointsWithoutDroppingOrConnectingSegments() {
        val stroke=(0 until 500).map {i->Pt(30.0+i*0.1,20.0+(i%2))}
        val page=Page(listOf(stroke),1)
        val blocks=CameoStrokeBlocks.build(page,paper,maxBytes=64)
        assertTrue(blocks.size>1);assertEquals(1,blocks.sumOf {it.completedStrokes})
        val expected=commands(Cameo5Driver.encode(page,paper)).filter {it.startsWith("D")}
        assertEquals(expected,blocks.flatMap {commands(it.bytes)}.filter {it.startsWith("D")})
        blocks.zipWithNext().forEach { (a,b)->assertEquals(commands(a.bytes).last(),commands(b.bytes).first()) }
        blocks.forEach {assertTrue(it.bytes.size<=64);assertTrue(commands(it.bytes).last().startsWith("M"))}
    }
    @Test(expected=IllegalArgumentException::class) fun validatesWholePageBeforeTransmission() {
        CameoStrokeBlocks.build(Page(listOf(listOf(Pt(30.0,20.0),Pt(31.0,20.0)),listOf(Pt(400.0,20.0),Pt(401.0,20.0))),1),paper)
    }
    @Test fun attFragmentsPreserveExactByteStreamAndWaitForEveryAck() = runBlocking {
        val payload=CameoStrokeBlocks.build(Page(listOf((0..30).map {Pt(30.0+it,20.0)}),1),paper).single().bytes
        val received=mutableListOf<ByteArray>();var inFlight=0
        CameoBleWriter.write(payload) { fragment ->
            inFlight++;assertEquals(1,inFlight);delay(1)
            assertTrue(fragment.size in 1..20);received.add(fragment.copyOf());inFlight--
        }
        assertTrue(received.size>1)
        assertArrayEquals(payload,received.fold(byteArrayOf()) {a,b->a+b})
    }
    @Test fun uncertainFragmentIsNeverRetriedOrSkipped() = runBlocking {
        var calls=0
        val payload=("M400,600\u0003"+"D400,620\u0003".repeat(10)+"M400,620\u0003").toByteArray()
        try {CameoBleWriter.write(payload) {calls++;if(calls==2) error("Lost ACK")};fail("error ignored")} catch(_:IllegalStateException) {}
        assertEquals(2,calls)
    }
    @Test fun timeoutWhileMovingDoesNotResendQueuedBlock() = runBlocking {
        var writes=0
        val link=object:CameoMotionProbe.Link {
            override suspend fun status()=if(writes<2) Cameo5Driver.Status.READY else Cameo5Driver.Status.MOVING
            override suspend fun write(command:ByteArray) {writes++}
        }
        val suite=HardwareSuite(paper,listOf(HardwareStage("stroke","test",Page(listOf(listOf(Pt(30.0,20.0),Pt(31.0,20.0))),1))))
        try {CameoTestSuite.run("CAMEO 5 V1.04",suite,PenSetup(),link,pollMillis=1,timeoutMillis=20);fail("no timeout")} catch(_:TimeoutCancellationException) {}
        assertEquals(2,writes) // Setup + one motion block only.
    }
}
