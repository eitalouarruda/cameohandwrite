package com.cameohandwrite.drivers.cameo

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CameoMotionProbeTest {
    private val model="CAMEO 5 V1.04    "
    private class FakeLink: CameoMotionProbe.Link {
        val sent=mutableListOf<String>()
        var statusReads=0
        var onStatus:()->Cameo5Driver.Status = { Cameo5Driver.Status.READY }
        var onWrite:()->Unit = {}
        override suspend fun status():Cameo5Driver.Status { statusReads++;return onStatus() }
        override suspend fun write(command:ByteArray) { sent.add(command.toString(Charsets.US_ASCII));onWrite() }
    }
    @Test fun onlyFixedPenUpCommandsInsideTwoMillimeters() {
        assertEquals(listOf("M20,20\u0003","M20,40\u0003","M20,20\u0003"),CameoMotionProbe.commands.map { it.toString(Charsets.US_ASCII) })
        assertTrue(CameoMotionProbe.commands.all { it.size<=20 && it.first()=='M'.code.toByte() && it.last()==3.toByte() })
    }
    @Test fun executesSequentiallyAndPollsReadiness() = runBlocking {
        val link=FakeLink();val progress=mutableListOf<Int>()
        assertTrue(CameoMotionProbe.run(model,link,onProgress={progress.add(it)},pollMillis=1))
        assertEquals(3,link.sent.size);assertEquals(listOf(1,2,3),progress);assertEquals(7,link.statusReads)
    }
    @Test fun wrongModelNeverQueriesOrMoves() = runBlocking {
        val link=FakeLink()
        try { CameoMotionProbe.run("CAMEO 5 ALPHA",link);fail("modelo aceito") } catch(_:IllegalArgumentException) { }
        assertTrue(link.sent.isEmpty());assertEquals(0,link.statusReads)
    }
    @Test fun unloadedBusyAndUnknownStatesNeverStartMotion() = runBlocking {
        for(status in listOf(Cameo5Driver.Status.UNLOADED,Cameo5Driver.Status.MOVING,Cameo5Driver.Status.UNKNOWN)) {
            val link=FakeLink();link.onStatus={status}
            try { CameoMotionProbe.run(model,link);fail("estado aceito: $status") } catch(_:IllegalStateException) { }
            assertTrue(link.sent.isEmpty())
        }
    }
    @Test fun waitsThroughMovingStatusBeforeNextCommand() = runBlocking {
        val link=FakeLink();var polls=0
        link.onStatus={ if(link.sent.isNotEmpty() && polls++%2==0) Cameo5Driver.Status.MOVING else Cameo5Driver.Status.READY }
        // Explicit sequence avoids reporting busy on a pre-move recheck.
        val states=ArrayDeque(listOf(Cameo5Driver.Status.READY,Cameo5Driver.Status.READY,Cameo5Driver.Status.MOVING,Cameo5Driver.Status.READY,Cameo5Driver.Status.READY,Cameo5Driver.Status.READY,Cameo5Driver.Status.READY,Cameo5Driver.Status.READY))
        link.onStatus={states.removeFirst()}
        assertTrue(CameoMotionProbe.run(model,link,pollMillis=1));assertEquals(3,link.sent.size);assertTrue(states.isEmpty())
    }
    @Test fun unknownReplyAfterFirstMovePreventsNextMove() = runBlocking {
        val link=FakeLink();link.onStatus={if(link.sent.isEmpty()) Cameo5Driver.Status.READY else Cameo5Driver.Status.UNKNOWN}
        try { CameoMotionProbe.run(model,link,pollMillis=1);fail("estado desconhecido aceito") } catch(_:IllegalStateException) { }
        assertEquals(1,link.sent.size)
    }
    @Test fun stopBeforeStartingSendsNothing() = runBlocking {
        val link=FakeLink()
        assertFalse(CameoMotionProbe.run(model,link,stopRequested={true}))
        assertTrue(link.sent.isEmpty());assertEquals(0,link.statusReads)
    }
    @Test fun stopDuringCommandDoesNotSendAReturnMove() = runBlocking {
        val link=FakeLink();var stop=false;link.onWrite={stop=true}
        assertFalse(CameoMotionProbe.run(model,link,stopRequested={stop},pollMillis=1))
        assertEquals(listOf("M20,20\u0003"),link.sent)
    }
    @Test fun movingTimeoutDoesNotRetryMotion() = runBlocking {
        val link=FakeLink();link.onStatus={if(link.sent.isEmpty()) Cameo5Driver.Status.READY else Cameo5Driver.Status.MOVING}
        try { CameoMotionProbe.run(model,link,pollMillis=1,readyTimeoutMillis=20);fail("sem timeout") } catch(_:TimeoutCancellationException) { }
        assertEquals(1,link.sent.size)
    }
    @Test fun uncertainWriteIsNotRetried() = runBlocking {
        val link=FakeLink();link.onWrite={error("GATT disconnected")}
        try { CameoMotionProbe.run(model,link,pollMillis=1);fail("falha ignorada") } catch(_:IllegalStateException) { }
        assertEquals(1,link.sent.size)
    }
    @Test fun stateChangeBeforeNextMoveStopsTest() = runBlocking {
        val link=FakeLink();link.onStatus={if(link.statusReads>=4) Cameo5Driver.Status.UNLOADED else Cameo5Driver.Status.READY}
        try { CameoMotionProbe.run(model,link,pollMillis=1);fail("descarregamento ignorado") } catch(_:IllegalStateException) { }
        assertEquals(1,link.sent.size)
    }
}
