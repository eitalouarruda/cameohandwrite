package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.*

data class PagePrintJob(val paper:Paper,val page:Page,val number:Int,val totalPages:Int) {
    val stage get()=HardwareStage("Página $number de $totalPages","Texto do editor; confirme o resultado na folha",page)
}
object CameoPageJob {
    fun prepare(paper:Paper,page:Page,number:Int,totalPages:Int):PagePrintJob {
        require(number in 1..totalPages) { "Página inválida" }
        paper.validate()
        require(page.strokes.isNotEmpty() && page.strokes.all {it.size>=2}) { "Esta página não tem escrita" }
        require(page.strokes.sumOf {it.size}<=200000) { "Página densa demais; aumente a letra ou divida o texto" }
        Cameo5Driver.encode(page,paper) // Includes final physical/calibrated bounds and finite checks.
        return PagePrintJob(paper,page.copy(strokes=page.strokes.map {it.toList()}),number,totalPages)
    }
}
