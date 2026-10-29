package com.cameohandwrite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cameohandwrite.data.SettingsRepository
import com.cameohandwrite.data.bluetooth.BleDiagnostics
import com.cameohandwrite.domain.*
import com.cameohandwrite.simulator.PlotterSimulator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HandwriteViewModel(app:Application):AndroidViewModel(app) {
    data class State(val settings:Settings=Settings(),val document:Document=Document(emptyList()),val error:String?=null,val busy:Boolean=true,val profiles:List<Paper> = emptyList(),val message:String="")
    private val repository=SettingsRepository(app)
    private val fontEngine=StrokeFontEngine(listOf("futural","scripts").associateWith { name -> StrokeFontEngine.parse(app.assets.open("$name.tsv").bufferedReader().use { it.readText() }) })
    private val layout=PageLayoutEngine(fontEngine)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    val simulator=PlotterSimulator()
    val ble=BleDiagnostics(app)
    private var renderJob:Job?=null
    private var saveJob:Job?=null
    private var simulationJob:Job?=null
    init { viewModelScope.launch {
        val settings=try { repository.load() } catch(e:Exception) { mutable.value=mutable.value.copy(message="Configuração não carregada: ${e.message}");Settings() }
        mutable.value=mutable.value.copy(settings=settings,profiles=repository.profiles)
        rebuild(settings)
    } }
    fun update(change:(Settings)->Settings) {
        val next=change(mutable.value.settings)
        simulator.cancel();simulationJob?.cancel()
        mutable.value=mutable.value.copy(settings=next,busy=true)
        rebuild(next)
        saveJob?.cancel();saveJob=viewModelScope.launch { delay(450);try { repository.save(next) } catch(e:Exception) { message("Não foi possível salvar: ${e.message}") } }
    }
    private fun rebuild(settings:Settings) {
        renderJob?.cancel();renderJob=viewModelScope.launch {
            delay(180)
            try {
                val doc=withContext(Dispatchers.Default) {
                    val raw=layout.layout(settings)
                    raw.copy(pages=raw.pages.map { it.copy(strokes=PlotPlanner.plan(it,settings).strokes) })
                }
                mutable.value=mutable.value.copy(document=doc,error=null,busy=false)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { mutable.value=mutable.value.copy(document=Document(emptyList()),error=e.message ?: "Layout inválido",busy=false) }
        }
    }
    fun saveProfile() {
        try { repository.saveProfile(mutable.value.settings.paper);mutable.value=mutable.value.copy(profiles=repository.profiles);update { it };message("Perfil salvo neste celular") }
        catch(e:Exception) { message(e.message ?: "Perfil inválido") }
    }
    fun identifyDevice() { viewModelScope.launch {
        try { ble.identify() } catch(e:Exception) { message("Identificação BLE falhou: ${e.message}") }
    } }
    fun exportSettings()=repository.encode(mutable.value.settings)
    fun importSettings(raw:String) {
        try { val s=repository.decode(raw);layout.layout(s);mutable.value=mutable.value.copy(profiles=repository.profiles);update { s };message("Configurações importadas") }
        catch(e:Exception) { message("Importação recusada: ${e.message}") }
    }
    fun simulate(page:Int,alignment:Boolean=false) {
        try {
            val s=mutable.value.settings
            val p=if(alignment) PlotPlanner.alignment(s.paper) else mutable.value.document.pages.getOrNull(page) ?: return
            val plan=PlotPlanner.plan(p,s)
            simulationJob?.cancel();simulationJob=viewModelScope.launch { simulator.run(plan) }
        } catch(e:Exception) { message(e.message ?: "Não foi possível simular") }
    }
    fun message(value:String) { mutable.value=mutable.value.copy(message=value) }
    override fun onCleared() { ble.close();simulator.cancel() }
}
