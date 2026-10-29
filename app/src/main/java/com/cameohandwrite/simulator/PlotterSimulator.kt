package com.cameohandwrite.simulator

import com.cameohandwrite.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlotterSimulator {
    data class State(val running:Boolean=false,val paused:Boolean=false,val completed:Int=0,val total:Int=0,val log:String="Pronto para simular")
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    val recorded=mutableListOf<Stroke>()
    private var generation=0
    suspend fun run(plan:Plan) {
        val id=++generation;recorded.clear()
        mutable.value=State(true,false,0,plan.strokes.size,"Simulação; nenhum comando enviado")
        for((i,stroke) in plan.strokes.withIndex()) {
            while(mutable.value.paused && generation==id) delay(50)
            if(generation!=id) return
            delay(12)
            if(generation!=id) return
            recorded.add(stroke)
            mutable.value=mutable.value.copy(completed=i+1)
        }
        mutable.value=mutable.value.copy(running=false,log="Simulação concluída: ${recorded.size} traços registrados")
    }
    fun pause() { if(mutable.value.running) mutable.value=mutable.value.copy(paused=!mutable.value.paused) }
    fun cancel() { generation++;mutable.value=mutable.value.copy(running=false,paused=false,log="Simulação cancelada") }
}
