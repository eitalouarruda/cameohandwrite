package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Shared, validated 0.3.2 sender. Same 512-byte pen-up blocks and acknowledged 20-byte writes. */
class PlotterNotReadyException(val status:Cameo5Driver.Status):IllegalStateException(
    if(status==Cameo5Driver.Status.UNLOADED) "Carregue a folha na Cameo e tente escrever novamente" else "A Cameo está ocupada; aguarde antes de escrever"
)
object CameoJobRunner {
    suspend fun run(
        model:String,paper:Paper,stages:List<HardwareStage>,setup:PenSetup,link:CameoMotionProbe.Link,
        stop:()->Boolean={false},paused:()->Boolean={false},
        onStage:(Int,String)->Unit={_,_->},onStroke:(Int,Int)->Unit={_,_->},
        pollMillis:Long=50,timeoutMillis:Long=30000
    ):Boolean {
        require(Cameo5Driver.identifiesOriginalCameo5(model)) { "Identifique uma Cameo 5 original" }
        paper.validate()
        require(stages.isNotEmpty() && stages.all {it.speedOverride==null || it.speedOverride in 1..30})
        require(stages.sumOf {it.page.strokes.sumOf {stroke->stroke.size}}<=200000) { "Página densa demais; aumente a letra ou divida o texto" }
        stages.forEach {Cameo5Driver.encode(it.page,paper)}
        val setupCommands=setup.commands().fold(byteArrayOf()) { a,b->a+b }
        // Compile and validate all blocks before configuring or moving the machine.
        val stageBlocks=stages.map { CameoStrokeBlocks.build(it.page,paper) }
        require(pollMillis>0 && timeoutMillis>0)
        suspend fun ready() { withTimeout(timeoutMillis) {
            while(true) when(link.status()) {
                Cameo5Driver.Status.READY->return@withTimeout
                Cameo5Driver.Status.MOVING->delay(pollMillis)
                Cameo5Driver.Status.UNLOADED->error("Base descarregada; bateria interrompida")
                Cameo5Driver.Status.UNKNOWN->error("Estado desconhecido; bateria interrompida")
            }
        } }
        suspend fun send(bytes:ByteArray) { require(bytes.size<=CameoStrokeBlocks.MAX_BYTES);link.write(bytes);ready() }
        suspend fun gate(initial:Boolean=false):Boolean {
            var waited=false
            while(paused() && !stop()) {waited=true;delay(50)}
            if(stop()) return false
            // The previous block already ended READY. Query again only on initial start/resume,
            // where the operator may have changed the base or used the machine while paused.
            if(initial || waited) when(val status=link.status()) {
                Cameo5Driver.Status.READY -> Unit
                Cameo5Driver.Status.UNLOADED,Cameo5Driver.Status.MOVING -> throw PlotterNotReadyException(status)
                Cameo5Driver.Status.UNKNOWN -> error("Estado desconhecido; confira a conexão")
            }
            return !stop()
        }
        if(!gate(initial=true)) return false
        onStage(-1,"Preparando a caneta no suporte 1")
        send(setupCommands)
        val total=stages.sumOf { it.page.strokes.size };var done=0
        var activeSpeed=setup.speed
        for((index,stage) in stages.withIndex()) {
            if(!gate()) return false
            val speed=stage.speedOverride ?: setup.speed
            if(speed!=activeSpeed) {
                send(("!$speed,1"+Cameo5Driver.ETX).toByteArray(Charsets.US_ASCII))
                activeSpeed=speed
            }
            onStage(index,stage.title)
            for(block in stageBlocks[index]) {
                if(!gate()) return false
                // Finish this bounded block even if pause/stop arrives mid-write: it ends with M.
                // Truncating a byte stream could leave an incomplete command or the pen down.
                send(block.bytes)
                done+=block.completedStrokes;onStroke(done,total)
                if(stop()) return false
            }
            onStage(index,stage.title+" · envio concluído; observação pendente")
        }
        if(activeSpeed!=setup.speed && !stop()) send(("!${setup.speed},1"+Cameo5Driver.ETX).toByteArray(Charsets.US_ASCII))
        return !stop()
    }
}
