package com.cameohandwrite.ui.plotter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cameohandwrite.data.bluetooth.BleDiagnostics
import com.cameohandwrite.drivers.cameo.Cameo5Driver

@Composable fun MotionProbeControls(state:BleDiagnostics.State,onQuery:()->Unit,onTest:()->Unit,onStop:()->Unit) {
    var noTools by remember(state.model,state.connected,state.testAttempted) { mutableStateOf(false) }
    var loaded by remember(state.model,state.connected,state.testAttempted) { mutableStateOf(false) }
    var origin by remember(state.model,state.connected,state.testAttempted) { mutableStateOf(false) }
    val recognized=state.connected && Cameo5Driver.identifiesOriginalCameo5(state.model)
    val idle=!state.identifying && !state.queryingStatus && !state.testing
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Teste físico experimental · sem ferramenta",style=MaterialTheme.typography.titleMedium)
        Text("Retire a caneta e qualquer lâmina dos dois suportes. Carregue a base e confirme a origem de carregamento; recarregue a base se a origem foi alterada.")
        Text("O teste usa coordenadas absolutas da origem carregada: (1,1) → (2,1) → (1,1) mm. Não é um movimento relativo à posição atual. Não configura força, ferramenta ou velocidade.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick=onQuery,enabled=recognized && idle) { Text(if(state.queryingStatus) "Consultando estado…" else "Consultar estado da Cameo") }
        if(state.deviceStatus.isNotBlank()) Text("Estado: ${state.deviceStatus}")
        if(!recognized) Text("Conecte e consulte o modelo antes de liberar o teste.")
        CheckLine("Retirei caneta e lâminas dos dois suportes",noTools,recognized && idle && !state.testAttempted) { noTools=it }
        CheckLine("Base carregada e máquina ociosa",loaded,recognized && idle && !state.testAttempted) { loaded=it }
        CheckLine("Origem confirmada e área de 2 × 2 mm livre",origin,recognized && idle && !state.testAttempted) { origin=it }
        Button(onClick=onTest,enabled=recognized && idle && state.readyForProbe && !state.testAttempted && noTools && loaded && origin) { Text("Testar movimento de 1 mm sem ferramenta") }
        if(state.testing) {
            Text("Etapas com confirmação: ${state.testSteps} / 3")
            OutlinedButton(onClick=onStop) { Text("Interromper próximas etapas") }
            Text("Isso não aborta um movimento já recebido pela Cameo. Não há retorno automático ao interromper.",style=MaterialTheme.typography.bodySmall)
        }
        if(state.testAttempted && !state.testing) Text("Para repetir, desconecte, confira a base e identifique a máquina novamente.")
        if(state.testMessage.isNotBlank()) Text(state.testMessage)
        Text("Observe e registre se o carro realmente se moveu. Uma resposta pronta não comprova o movimento. Depois exporte o diagnóstico BLE.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun CheckLine(label:String,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=checked,onCheckedChange=onChange,enabled=enabled)
        Text(label,modifier=Modifier.weight(1f))
    }
}
