package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class HardwareStage(val title:String,val expected:String,val page:Page)
data class HardwareSuite(val paper:Paper,val stages:List<HardwareStage>) {
    val preview get()=Page(stages.flatMap { it.page.strokes },0)
}
data class PenSetup(val force:Int=5,val speed:Int=5) {
    fun commands():List<ByteArray> {
        require(force in 1..20 && speed in 1..10) { "Teste limitado a força 1–20 e velocidade 1–10 na escala GPGL" }
        // Graphtec.py, SilhouetteCameoTool: holder 1, small force/speed, pen offset,
        // no corner sharpening. No depth, acceleration, origin reset or media commands.
        return listOf("J1","FX$force,1","!$speed,1","FC0,1,1","FE0,1","FF0,0,1").map { (it+Cameo5Driver.ETX).toByteArray(Charsets.US_ASCII) }
    }
}
object CameoTestSuite {
    fun build(p:Paper,fonts:StrokeFontEngine):HardwareSuite {
        p.validate()
        val x=p.startX+3
        val y=p.firstLine
        fun pt(dx:Double,dy:Double)=Pt(x+dx+p.offsetX,y+dy+p.offsetY)
        fun stage(title:String,expected:String,paths:List<Stroke>)=HardwareStage(title,expected,Page(paths,0))
        val stages=mutableListOf<HardwareStage>()
        stages+=stage("1. Traço curto","Um traço horizontal de 5 mm",listOf(listOf(pt(0.0,0.0),pt(5.0,0.0))))
        val row=p.lineSpacing
        stages+=stage("2. Levantamento","Dois traços de 5 mm separados por 5 mm sem tinta no intervalo",listOf(listOf(pt(0.0,row),pt(5.0,row)),listOf(pt(10.0,row),pt(15.0,row))))
        val squareY=2*row
        stages+=stage("3. Escala X/Y","Quadrado com lados de 10 mm; meça largura e altura",listOf(listOf(pt(0.0,squareY),pt(10.0,squareY),pt(10.0,squareY+10),pt(0.0,squareY+10),pt(0.0,squareY))))
        // Keep the crosses clear of the square even for tightly spaced ruled paper.
        val crossY=maxOf(4*row,squareY+15)
        val crosses=listOf(0.0 to crossY,30.0 to crossY,0.0 to crossY+row).flatMap { (dx,dy) ->
            listOf(listOf(pt(dx-1,dy),pt(dx+1,dy)),listOf(pt(dx,dy-1),pt(dx,dy+1)))
        }
        stages+=stage("4. Alinhamento","Três cruzes: separação horizontal de 30 mm; vertical igual à distância entre pautas",crosses)
        val textY=crossY+3*row
        val sample=Settings(text="Olá ação 123",paper=p.copy(firstLine=y+textY),style=Style.SIMPLE,letterHeight=minOf(4.0,row/1.8),irregularity=0)
        val doc=PageLayoutEngine(fonts).layout(sample)
        require(doc.pages.size==1 && doc.pages.single().lines==1) { "A amostra precisa caber em uma linha; ajuste o perfil" }
        // Move sample's nominal left edge to the suite's shared left edge.
        val text=doc.pages.single().strokes.map { s -> s.map { Pt(it.x+3,it.y) } }
        stages+=stage("5. Escrita e acentos","Amostra: Olá ação 123, sobre uma pauta",text)
        val suite=HardwareSuite(p,stages)
        validate(suite)
        return suite
    }
    fun validate(suite:HardwareSuite) {
        suite.paper.validate()
        require(suite.stages.size in 1..8)
        val points=suite.preview.strokes.flatten()
        require(points.isNotEmpty() && points.size<=5000) { "Bateria vazia ou excessiva" }
        points.forEach { point ->
            require(point.x.isFinite() && point.y.isFinite())
            require(point.x in 0.0..suite.paper.width && point.y in 0.0..suite.paper.height)
            require(point.x>=suite.paper.startX+suite.paper.offsetX-0.001 && point.x<=suite.paper.width-suite.paper.right+suite.paper.offsetX+0.001 && point.y>=suite.paper.top+suite.paper.offsetY && point.y<=suite.paper.height-suite.paper.bottom+suite.paper.offsetY) { "Bateria não cabe nas margens; ajuste a folha" }
        }
        require(points.maxOf { it.x }-points.minOf { it.x }<=85 && points.maxOf { it.y }-points.minOf { it.y }<=110) { "Bateria limitada a 85 × 110 mm" }
        suite.stages.forEach { stage -> require(stage.page.strokes.all { it.size>=2 });Cameo5Driver.encode(stage.page,suite.paper) }
    }
    /** Bounded pen-up blocks preserve every original draw segment, without per-segment idle waits.
     * Link.write acknowledges the full stream; status is checked between blocks, not ATT fragments.
     */
    suspend fun run(
        model:String,suite:HardwareSuite,setup:PenSetup,link:CameoMotionProbe.Link,
        stop:()->Boolean={false},paused:()->Boolean={false},
        onStage:(Int,String)->Unit={_,_->},onStroke:(Int,Int)->Unit={_,_->},
        pollMillis:Long=50,timeoutMillis:Long=30000
    ):Boolean {
        require(Cameo5Driver.identifiesOriginalCameo5(model)) { "Identifique uma Cameo 5 original" }
        validate(suite);val setupCommands=setup.commands().fold(byteArrayOf()) { a,b->a+b }
        // Compile and validate all blocks before configuring or moving the machine.
        val stageBlocks=suite.stages.map { CameoStrokeBlocks.build(it.page,suite.paper) }
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
        suspend fun gate():Boolean {
            while(paused() && !stop()) delay(50)
            if(stop()) return false
            check(link.status()==Cameo5Driver.Status.READY) { "Máquina não está pronta" }
            return !stop()
        }
        if(!gate()) return false
        onStage(-1,"Configurando suporte 1 para teste de caneta")
        send(setupCommands)
        val total=suite.stages.sumOf { it.page.strokes.size };var done=0
        for((index,stage) in suite.stages.withIndex()) {
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
        return true
    }
}
