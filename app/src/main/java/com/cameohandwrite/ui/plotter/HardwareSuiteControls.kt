package com.cameohandwrite.ui.plotter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.cameohandwrite.data.HardwareTestReport
import com.cameohandwrite.data.bluetooth.BleDiagnostics
import com.cameohandwrite.drivers.cameo.*
import com.cameohandwrite.ui.PaperPreview

@Composable fun HardwareSuiteControls(
    state:BleDiagnostics.State,suite:HardwareSuite?,error:String?,report:HardwareTestReport?,
    onRun:(PenSetup)->Unit,onPause:()->Unit,onStop:()->Unit,onExport:()->Unit,onCopy:()->Unit,
    onReport:((HardwareTestReport)->HardwareTestReport)->Unit,onCalibrate:()->Unit
) {
    var prepared by remember(state.model,state.connected,state.suiteRunning) { mutableStateOf(false) }
    var force by remember { mutableIntStateOf(5) };var speed by remember { mutableIntStateOf(5) }
    val recognized=state.connected && Cameo5Driver.identifiesOriginalCameo5(state.model)
    val idle=!state.testing && !state.identifying && !state.queryingStatus
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Bateria completa com caneta",style=MaterialTheme.typography.titleLarge)
        Text("Uma execução: traço de 5 mm, dois traços separados, quadrado de 10 mm, cruzes de alinhamento e Olá ação 123. Avalie tudo ao final; o relatório reúne os comandos e suas observações.")
        Text("Use papel de descarte preso à base, caneta no suporte 1 e suporte 2 vazio. Retire qualquer lâmina. Confira a altura do adaptador e a origem após carregar a base. A prévia usa o perfil da aba Folha e sua calibração.")
        suite?.let { PaperPreview(it.preview,it.paper,Modifier.fillMaxWidth().height(280.dp)) }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Text("Força experimental: $force · velocidade: $speed (escala do equipamento, não mm/s)")
        Text("Ajuste no mesmo APK se não marcar. Não há ajuste de profundidade de lâmina.",style=MaterialTheme.typography.bodySmall)
        Slider(force.toFloat(),{force=it.toInt()},valueRange=1f..10f,steps=8,enabled=idle)
        Slider(speed.toFloat(),{speed=it.toInt()},valueRange=1f..10f,steps=8,enabled=idle)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(prepared,{prepared=it},enabled=recognized && idle)
            Text("Caneta no suporte 1, sem lâminas, papel preso e origem/área da prévia conferidos",modifier=Modifier.weight(1f))
        }
        Button(onClick={onRun(PenSetup(force,speed))},enabled=recognized && idle && state.readyForProbe && prepared && suite!=null) { Text("Executar bateria completa") }
        if(!recognized) Text("Conecte e consulte o modelo. Depois consulte o estado da Cameo.")
        if(state.suiteRunning) {
            Text(state.suiteStage)
            Text("${state.suiteDone} / ${state.suiteTotal} traços")
            LinearProgressIndicator(progress={if(state.suiteTotal>0) state.suiteDone.toFloat()/state.suiteTotal else 0f},modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onPause) { Text(if(state.suitePaused) "Retomar bateria" else "Pausar após traço") }
                OutlinedButton(onClick=onStop) { Text("Interromper bateria") }
            }
            Text("Pausa/interrupção ocorre após levantar a caneta no ponto confirmado. Não aborta o comando que a máquina já recebeu. Em falha de comunicação, confira a máquina: a caneta pode ficar baixada.",style=MaterialTheme.typography.bodySmall)
        }
        report?.let { r ->
            HorizontalDivider();Text("Resultado da bateria",style=MaterialTheme.typography.titleMedium)
            Text(r.outcome);Text("Perfil usado: ${r.paper.name}; ${r.model.trim()}")
            r.stages.forEachIndexed { index,s ->
                Text(s.title+" — "+s.execution)
                Text(s.expected,style=MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    listOf("Não avaliado","OK","Problema").forEach { value -> FilterChip(s.observation==value,{onReport { old -> old.copy(stages=old.stages.mapIndexed { i,stage -> if(i==index) stage.copy(observation=value) else stage }) }},enabled=!state.suiteRunning,label={Text(value)}) }
                }
            }
            ReportField("Largura medida do quadrado (mm)",r.squareWidth,!state.suiteRunning) { value -> onReport { it.copy(squareWidth=value) } }
            ReportField("Altura medida do quadrado (mm)",r.squareHeight,!state.suiteRunning) { value -> onReport { it.copy(squareHeight=value) } }
            Text("Erro em relação ao alvo da cruz: positivo à direita/abaixo, negativo à esquerda/acima. Informe 0 quando não houver erro.")
            ReportField("Erro X medido (mm)",r.errorX,!state.suiteRunning) { value -> onReport { it.copy(errorX=value) } }
            ReportField("Erro Y medido (mm)",r.errorY,!state.suiteRunning) { value -> onReport { it.copy(errorY=value) } }
            OutlinedButton(onClick=onCalibrate,enabled=!state.suiteRunning && r.errorX.isNotBlank() && r.errorY.isNotBlank()) { Text("Aplicar correção ao perfil e salvar") }
            OutlinedTextField(r.notes,{value->onReport { it.copy(notes=value.take(10000)) }},enabled=!state.suiteRunning,label={Text("Observações: tinta, falhas, levantamento, alinhamento")},modifier=Modifier.fillMaxWidth())
            Text("Salvo automaticamente no celular. A conclusão de envio não marca os testes como OK; essa avaliação é sua.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onCopy,enabled=!state.suiteRunning) { Text("Copiar resumo") }
                OutlinedButton(onClick=onExport,enabled=!state.suiteRunning) { Text("Exportar relatório único") }
            }
        }
    }
}
@Composable private fun ReportField(label:String,value:String,enabled:Boolean,onChange:(String)->Unit) {
    OutlinedTextField(value,{onChange(it.take(30))},label={Text(label)},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth())
}
