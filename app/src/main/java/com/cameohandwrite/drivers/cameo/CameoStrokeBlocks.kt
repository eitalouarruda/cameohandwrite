package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.Page
import com.cameohandwrite.domain.Paper

/** A block always ends with pen up; pause/stop is honored between bounded blocks. */
data class StrokeBlock(val bytes:ByteArray,val completedStrokes:Int)
object CameoStrokeBlocks {
    const val MAX_BYTES=512 // Upstream safe_write permits 1024; keep a smaller motion queue.
    fun build(page:Page,paper:Paper,maxBytes:Int=MAX_BYTES):List<StrokeBlock> {
        require(maxBytes in 64..1024)
        // Validate the entire page before any transmission, including calibration and margins.
        Cameo5Driver.encode(page,paper)
        val units=mutableListOf<StrokeBlock>()
        for(stroke in page.strokes.filter { it.size>=2 }) {
            val commands=Cameo5Driver.encode(Page(listOf(stroke),0),paper).toString(Charsets.US_ASCII)
                .split(Cameo5Driver.ETX).filter { it.isNotEmpty() }.map { it+Cameo5Driver.ETX }
            var start=commands.first()
            var current=StringBuilder(start)
            var lift=start
            for(draw in commands.drop(1).dropLast(1)) {
                val nextLift="M"+draw.drop(1)
                if(current.length+draw.length+nextLift.length>maxBytes) {
                    current.append(lift)
                    units.add(StrokeBlock(current.toString().toByteArray(Charsets.US_ASCII),0))
                    start=lift;current=StringBuilder(start)
                }
                require(current.length+draw.length+nextLift.length<=maxBytes)
                current.append(draw);lift=nextLift
            }
            current.append(lift)
            units.add(StrokeBlock(current.toString().toByteArray(Charsets.US_ASCII),1))
        }
        val blocks=mutableListOf<StrokeBlock>()
        var data=StringBuilder();var completed=0
        fun flush() {
            if(data.isNotEmpty()) blocks.add(StrokeBlock(data.toString().toByteArray(Charsets.US_ASCII),completed))
            data=StringBuilder();completed=0
        }
        for(unit in units) {
            if(data.length+unit.bytes.size>maxBytes) flush()
            data.append(unit.bytes.toString(Charsets.US_ASCII));completed+=unit.completedStrokes
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
