package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.Page
import com.cameohandwrite.domain.Paper

/** A block always ends with pen up; pause/stop is honored between bounded blocks. */
data class StrokeBlock(val bytes:ByteArray,val completedStrokes:Int)
object CameoStrokeBlocks {
    const val MAX_BYTES=512 // Upstream safe_write permits 1024; keep a smaller motion queue.
    private data class GridPoint(val y:Int,val x:Int) {
        fun command(type:Char)="$type$y,$x"+Cameo5Driver.ETX
    }
    /** Only remove points that lie exactly on the directed line, at native 0.05-mm resolution.
     * Preserve reversals, corners and isolated dots; no approximate curve simplification.
     */
    private fun compact(points:List<GridPoint>):List<GridPoint> {
        val out=mutableListOf<GridPoint>()
        for(p in points) {
            if(out.lastOrNull()==p) continue
            while(out.size>=2) {
                val a=out[out.lastIndex-1];val b=out.last()
                val cross=(b.x-a.x).toLong()*(p.y-a.y)-(b.y-a.y).toLong()*(p.x-a.x)
                val between=(b.x-a.x).toLong()*(b.x-p.x)+(b.y-a.y).toLong()*(b.y-p.y)<=0
                if(cross!=0L || !between) break
                out.removeAt(out.lastIndex)
            }
            out.add(p)
        }
        return if(out.size==1) listOf(out.single(),out.single()) else out
    }
    fun build(page:Page,paper:Paper,maxBytes:Int=MAX_BYTES):List<StrokeBlock> {
        require(maxBytes in 64..1024)
        Cameo5Driver.encode(page,paper) // Validate every original point before transmission.
        val blocks=mutableListOf<StrokeBlock>()
        var data=StringBuilder();var completed=0;var lift=""
        fun flush() {
            if(data.isNotEmpty()) {
                data.append(lift)
                require(data.length<=maxBytes)
                blocks.add(StrokeBlock(data.toString().toByteArray(Charsets.US_ASCII),completed))
            }
            data=StringBuilder();completed=0;lift=""
        }
        for(stroke in page.strokes.filter {it.size>=2}) {
            val commands=Cameo5Driver.encode(Page(listOf(stroke),0),paper).toString(Charsets.US_ASCII)
                .split(Cameo5Driver.ETX).filter {it.isNotEmpty()}.dropLast(1)
            val points=compact(commands.map { c->val pair=c.drop(1).split(',');GridPoint(pair[0].toInt(),pair[1].toInt()) })
            val start=points.first().command('M')
            val firstDraw=points[1].command('D');val firstLift=points[1].command('M')
            if(data.length+start.length+firstDraw.length+firstLift.length>maxBytes) flush()
            // This M raises the pen before moving to the next stroke: a preceding zero-travel M
            // is unnecessary. Only block boundaries need an explicit lift at the last endpoint.
            data.append(start);lift=start
            for(point in points.drop(1)) {
                val draw=point.command('D');val nextLift=point.command('M')
                if(data.length+draw.length+nextLift.length>maxBytes) {
                    val resume=lift
                    flush();data.append(resume);lift=resume
                }
                require(data.length+draw.length+nextLift.length<=maxBytes)
                data.append(draw);lift=nextLift
            }
            completed++
        }
        flush()
        return blocks
    }
}

/** Serialize ATT writes: each 20-byte fragment must be acknowledged before sending the next.
 * An error is propagated without replay: even an unacknowledged fragment may have arrived.
 */
object CameoBleWriter {
    suspend fun write(payload:ByteArray,writeConfirmed:suspend (ByteArray)->Unit) {
        require(payload.size in 1..1024 && payload.last()==Cameo5Driver.ETX.code.toByte())
        var offset=0
        while(offset<payload.size) {
            val end=minOf(offset+20,payload.size)
            writeConfirmed(payload.copyOfRange(offset,end))
            offset=end
        }
    }
}
