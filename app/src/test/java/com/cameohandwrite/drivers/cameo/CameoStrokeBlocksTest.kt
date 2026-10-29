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
        assertSameInk(Cameo5Driver.encode(page,paper),blocks.fold(byteArrayOf()) {a,b->a+b.bytes})
        blocks.forEach {assertTrue(it.bytes.size<=512);assertTrue(commands(it.bytes).first().startsWith("M"));assertTrue(commands(it.bytes).last().startsWith("M"))}
    }
    @Test fun removesOnlyZeroTravelLiftsAndExactlyCollinearVertices() {
        val page=Page(listOf(listOf(Pt(30.0,20.0),Pt(31.0,20.0),Pt(31.0,20.0),Pt(32.0,20.0)),listOf(Pt(40.0,20.0),Pt(41.0,20.0))),1)
        val data=CameoStrokeBlocks.build(page,paper).single().bytes
        assertEquals(listOf("M400,600","D400,640","M400,800","D400,820","M400,820"),commands(data))
        assertSameInk(Cameo5Driver.encode(page,paper),data)
    }
    @Test fun preservesReversalsCornersAndIsolatedDots() {
        val page=Page(listOf(listOf(Pt(30.0,20.0),Pt(35.0,20.0),Pt(32.0,20.0),Pt(32.0,21.0)),listOf(Pt(40.0,20.0),Pt(40.0,20.0))),1)
        val data=CameoStrokeBlocks.build(page,paper).single().bytes
        assertEquals(listOf("D400,700","D400,640","D420,640","D400,800"),commands(data).filter {it.startsWith("D")})
        assertSameInk(Cameo5Driver.encode(page,paper),data)
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


/** Independent geometric check: the union of directed native-grid line intervals must match.
 * Detects gaps accidentally inked by removing M, as well as omissions, reversals and new lines.
 */
internal fun assertSameInk(reference:ByteArray,actual:ByteArray) {
    data class P(val x:Int,val y:Int)
    data class S(val a:P,val b:P)
    fun segments(bytes:ByteArray):List<S> {
        val out=mutableListOf<S>();var previous:P?=null
        for(command in bytes.toString(Charsets.US_ASCII).split(Cameo5Driver.ETX).filter {it.isNotEmpty()}) {
            val pair=command.drop(1).split(',');val p=P(pair[1].toInt(),pair[0].toInt())
            if(command.first()=='D') out.add(S(previous!!,p))
            previous=p
        }
        return out
    }
    fun cross(a:P,b:P,p:P)=(b.x-a.x).toLong()*(p.y-a.y)-(b.y-a.y).toLong()*(p.x-a.x)
    fun contains(s:S,p:P)=cross(s.a,s.b,p)==0L && (p.x-s.a.x).toLong()*(p.x-s.b.x)+(p.y-s.a.y).toLong()*(p.y-s.b.y)<=0
    fun covered(target:S,source:List<S>):Boolean {
        if(target.a==target.b) return source.any {contains(it,target.a)}
        val dx=target.b.x-target.a.x;val dy=target.b.y-target.a.y
        val horizontal=dx!=0
        fun project(p:P)=if(horizontal) p.x else p.y
        val ranges=source.filter {s->cross(target.a,target.b,s.a)==0L && cross(target.a,target.b,s.b)==0L && dx.toLong()*(s.b.x-s.a.x)+dy.toLong()*(s.b.y-s.a.y)>0}
            .map {s->minOf(project(s.a),project(s.b)) to maxOf(project(s.a),project(s.b))}.sortedBy {it.first}
        var cursor=minOf(project(target.a),project(target.b));val end=maxOf(project(target.a),project(target.b))
        for((a,b) in ranges) {
            if(b<cursor) continue
            if(a>cursor) return false
            cursor=maxOf(cursor,b)
            if(cursor>=end) return true
        }
        return false
    }
    val old=segments(reference);val optimized=segments(actual)
    old.forEach {org.junit.Assert.assertTrue("Missing original ink segment $it",covered(it,optimized))}
    optimized.forEach {org.junit.Assert.assertTrue("New or reversed ink segment $it",covered(it,old))}
}
