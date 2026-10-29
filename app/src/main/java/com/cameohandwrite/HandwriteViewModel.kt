package com.cameohandwrite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cameohandwrite.data.SettingsRepository
import com.cameohandwrite.data.HardwareReportStore
import com.cameohandwrite.data.HardwareTestReport
import com.cameohandwrite.drivers.cameo.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.cameohandwrite.data.bluetooth.BleDiagnostics
import com.cameohandwrite.domain.*
import com.cameohandwrite.simulator.PlotterSimulator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HandwriteViewModel(app:Application):AndroidViewModel(app) {
    data class State(val settings:Settings=Settings(),val document:Document=Document(emptyList()),val error:String?=null,val busy:Boolean=true,val profiles:List<Paper> = emptyList(),val message:String="",val connecting:Boolean=false,val initializing:Boolean=true)
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
    private var printJob:Job?=null
    init { viewModelScope.launch {
        val settings=try { repository.load().forOpening() } catch(e:Exception) { mutable.value=mutable.value.copy(message="Configuração não carregada: ${e.message}");Settings() }
        mutable.value=mutable.value.copy(settings=settings,profiles=repository.profiles,initializing=false)
        rebuild(settings)
    } }
    fun update(change:(Settings)->Settings) {
        if(ble.state.value.testing) {message("Aguarde o envio terminar para alterar a página");return}
        val next=change(mutable.value.settings)
        if(next!=mutable.value.settings) ble.clearPrintStatus()
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
        if(ble.state.value.testing) return
        try { repository.saveProfile(mutable.value.settings.paper);mutable.value=mutable.value.copy(profiles=repository.profiles);update { it };message("Perfil salvo neste celular") }
        catch(e:Exception) { message(e.message ?: "Perfil inválido") }
    }
    fun connectPrinter(address:String) {
        if(mutable.value.connecting || ble.state.value.testing) return
        mutable.value=mutable.value.copy(connecting=true,message="Conectando à Cameo…")
        viewModelScope.launch {
            try {
                ble.connect(address)
                withTimeout(20000) {ble.state.first {it.connected && it.knownService}}
                ble.identify()
                check(Cameo5Driver.identifiesOriginalCameo5(ble.state.value.model)) {"Este dispositivo não foi identificado como Cameo 5"}
                ble.queryStatus()
                update {it.copy(preferredDevice=address)}
                message(if(ble.state.value.readyForProbe) "Cameo conectada e pronta" else "Cameo conectada. Carregue a folha para escrever.")
            } catch(e:CancellationException) {
                ble.disconnect();message("Conexão não concluída. Confira se a Cameo está ligada e ociosa.")
                if(e !is TimeoutCancellationException) throw e
            } catch(e:Exception) {ble.disconnect();message("Não foi possível conectar: ${e.message}")}
            finally {mutable.value=mutable.value.copy(connecting=false)}
        }
    }
    fun printPage(index:Int) {
        val snapshot=mutable.value
        if(ble.state.value.testing || snapshot.connecting || printJob?.isActive==true) return
        try {
            require(!snapshot.busy && snapshot.error==null) {"Aguarde a prévia ficar pronta"}
            val page=snapshot.document.pages.getOrNull(index) ?: error("Página não encontrada")
            val job=PagePrintJob(snapshot.settings.paper,page,index+1,snapshot.document.pages.size)
            val setup=PenSetup(snapshot.settings.penForce,snapshot.settings.penSpeed)
            simulator.cancel();simulationJob?.cancel()
            message("")
            printJob=viewModelScope.launch {
                try {ble.printPage(job,setup)}
                catch(e:CancellationException) {throw e}
                catch(e:Exception) {message("Não foi possível concluir a página: ${e.message}")}
            }
        } catch(e:Exception) {message(e.message ?: "Página inválida")}
    }
    fun identifyDevice() { viewModelScope.launch {
        try { ble.identify() } catch(e:Exception) { message("Identificação BLE falhou: ${e.message}") }
    } }
    fun queryDeviceStatus() { viewModelScope.launch {
        try { ble.queryStatus() } catch(e:CancellationException) { throw e } catch(e:Exception) { message("Consulta de estado falhou: ${e.message}") }
    } }
    fun testDeviceMotion() { viewModelScope.launch {
        try { ble.testMotion() } catch(e:CancellationException) { throw e } catch(e:Exception) { message("Teste de movimento interrompido: ${e.message}") }
    } }
    private val reportStore=HardwareReportStore(app)
    init { viewModelScope.launch {
        try { ble.restoreReport(withContext(Dispatchers.IO) { reportStore.load() }) } catch(e:Exception) { message("Relatório anterior não carregado: ${e.message}") }
        ble.report.filterNotNull().collect { report ->
            try { withContext(Dispatchers.IO) { reportStore.save(report) } } catch(e:Exception) { message("Relatório não salvo: ${e.message}") }
            delay(300)
        }
    } }
    fun suitePreview(paper:Paper=mutable.value.settings.paper,comparison:Boolean=false):HardwareSuite=if(comparison) CameoTestSuite.speedComparison(paper,fontEngine) else CameoTestSuite.build(paper,fontEngine)
    fun runHardwareSuite(setup:PenSetup,comparison:Boolean=false) { viewModelScope.launch {
        try { ble.runSuite(suitePreview(comparison=comparison),setup) } catch(e:CancellationException) { throw e } catch(e:Exception) { message("Bateria interrompida: ${e.message}") }
    } }
    fun exportHardwareReport()=ble.report.value?.encode() ?: error("Execute uma bateria primeiro")
    fun hardwareReportSummary():String {
        val r=ble.report.value ?: return "Nenhuma bateria executada"
        return buildString {
            appendLine("Cameo Handwrite · ${r.model.trim()} · ${r.startedAt}")
            appendLine(r.outcome)
            appendLine("Envio: ${r.sendMode}")
            r.elapsedMillis?.let { appendLine("Duração: ${it/1000.0} s; blocos=${r.streamBlocks}; consultas=${r.statusQueries}; escritas GATT=${r.gattWrites}") }
            appendLine("Força ${r.setup.force}; velocidade ${r.setup.speed} (escala GPGL)")
            appendLine("Dados de comandos: ${r.streamBytes} bytes; espera por ACK GATT=${r.gattWriteMillis/1000.0} s; consultas completas=${r.statusWaitMillis/1000.0} s (tempos sobrepostos)")
            r.stages.forEach { s ->
                appendLine("${s.title}: ${s.execution}; observação: ${s.observation}"+(s.speed?.let {"; velocidade=$it"} ?: "")+(s.elapsedMillis?.let {"; duração=${it/1000.0} s"} ?: ""))
            }
            if(!r.comparison && r.pageNumber==null) {
                appendLine("Quadrado: largura=${r.squareWidth} mm; altura=${r.squareHeight} mm")
                appendLine("Erro medido: X=${r.errorX} mm; Y=${r.errorY} mm")
            }
            appendLine("Notas: ${r.notes}")
        }
    }
    fun applySuiteCalibration() {
        val r=ble.report.value ?: return
        try {
            require(!ble.state.value.testing)
            val x=r.errorX.replace(',','.').toDoubleOrNull() ?: error("Informe o erro X medido")
            val y=r.errorY.replace(',','.').toDoubleOrNull() ?: error("Informe o erro Y medido")
            require(x.isFinite() && y.isFinite())
            val paper=r.paper.copy(offsetX=kotlin.math.round((r.paper.offsetX-x)*10)/10,offsetY=kotlin.math.round((r.paper.offsetY-y)*10)/10)
            paper.validate();layout.layout(mutable.value.settings.copy(paper=paper))
            update { it.copy(paper=paper) };saveProfile()
            message("Correção aplicada ao perfil ${paper.name}; próxima bateria usará a nova calibração")
        } catch(e:Exception) { message("Calibração não aplicada: ${e.message}") }
    }
    fun exportSettings()=repository.encode(mutable.value.settings)
    fun importSettings(raw:String) {
        if(ble.state.value.testing) return
        try { val s=repository.decode(raw,commitProfiles=false);layout.layout(s);repository.decode(raw);mutable.value=mutable.value.copy(profiles=repository.profiles);update { s };message("Configurações importadas") }
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
