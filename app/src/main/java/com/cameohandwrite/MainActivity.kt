package com.cameohandwrite

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cameohandwrite.domain.*
import com.cameohandwrite.data.usb.UsbDiagnostics
import com.cameohandwrite.ui.PaperPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState);setContent {
        MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xff36594c),secondary=Color(0xff816943),background=Color(0xfff4f5ef))) { HandwriteApp() }
    } }
}

@Composable fun HandwriteApp(vm:HandwriteViewModel=viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val simulation by vm.simulator.state.collectAsStateWithLifecycle()
    val ble by vm.ble.state.collectAsStateWithLifecycle()
    val s=state.settings
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var trajectory by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val currentPage=page.coerceIn(0,maxOf(0,state.document.pages.lastIndex))
    val exportSvg=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if(uri!=null) scope.launch {
            val p=state.document.pages.getOrNull(currentPage)
            if(p!=null) try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(PlotPlanner.svg(p,s.paper)) } };vm.message("SVG da página ${currentPage+1} exportado") } catch(e:Exception) { vm.message("Falha ao exportar: ${e.message}") }
        }
    }
    val exportConfig=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) scope.launch { try { val data=vm.exportSettings();withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(data) } };vm.message("Configurações exportadas") } catch(e:Exception) { vm.message("Falha: ${e.message}") } }
    }
    val importConfig=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch { try { val text=withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readTextLimited() } ?: error("Arquivo inacessível") };vm.importSettings(text) } catch(e:Exception) { vm.message("Falha: ${e.message}") } }
    }
    val exportLogs=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri!=null) scope.launch { try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ble.log) } };vm.message("Diagnóstico BLE exportado") } catch(e:Exception) { vm.message("Falha: ${e.message}") } }
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if(results.values.all { it }) vm.ble.scan() else vm.message("Permissão de Bluetooth/localização necessária para descobrir dispositivos")
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.padding(horizontal=20.dp,vertical=12.dp)) {
                Text("Cameo Handwrite",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Text("Do texto ao papel pautado",color=MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Editor","Folha","Prévia","Escrita","Ajustes").forEachIndexed { index,label -> FilterChip(selected=tab==index,onClick={tab=index},label={Text(label)}) }
            }
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error) }
            if(state.message.isNotBlank()) Text(state.message,Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                when(tab) {
                    0 -> {
                        SectionTitle("Seu texto")
                        OutlinedTextField(value=s.text,onValueChange={ text -> if(text.length<=200000) vm.update { it.copy(text=text) } else vm.message("Limite de 200 mil caracteres") },label={Text("Cole ou escreva aqui")},modifier=Modifier.fillMaxWidth().heightIn(min=210.dp,max=400.dp))
                        Text("${s.text.length} caracteres · ${state.document.pages.size} página(s)")
                        Choices("Estilo",Style.entries.toList(),s.style,{it.label}) { value -> vm.update { it.copy(style=value) } }
                        NumberField("Altura das letras (mm)",s.letterHeight) { value -> vm.update { it.copy(letterHeight=value) } }
                        NumberField("Espaço entre palavras (mm)",s.wordSpace) { value -> vm.update { it.copy(wordSpace=value) } }
                        Choices("Alinhamento",Align.entries.toList(),s.alignment,{it.label}) { value -> vm.update { it.copy(alignment=value) } }
                        Text("Irregularidade: ${s.irregularity}%")
                        Slider(value=s.irregularity.toFloat(),onValueChange={v->vm.update { it.copy(irregularity=v.toInt()) }},valueRange=0f..100f)
                        Text("Acentos e cedilha usam composição vetorial. Caracteres ausentes bloqueiam o trabalho com uma mensagem explícita.",style=MaterialTheme.typography.bodySmall)
                        state.document.pages.getOrNull(currentPage)?.let { PaperPreview(it,s.paper,Modifier.fillMaxWidth().height(310.dp)) }
                        Button(onClick={tab=2}) { Text("Abrir prévia e exportar") }
                    }
                    1 -> {
                        SectionTitle("Folha e pautas físicas")
                        Choices("Tamanho",listOf("A4","A5","Personalizado"),if(s.paper.width==210.0 && s.paper.height==297.0) "A4" else if(s.paper.width==148.0 && s.paper.height==210.0) "A5" else "Personalizado",{it}) { value ->
                            vm.update { it.copy(paper=when(value) { "A4"->it.paper.copy(width=210.0,height=297.0);"A5"->it.paper.copy(width=148.0,height=210.0);else->it.paper }) }
                        }
                        OutlinedTextField(value=s.paper.name,onValueChange={v->vm.update { it.copy(paper=it.paper.copy(name=v)) }},label={Text("Nome do perfil")},modifier=Modifier.fillMaxWidth())
                        PaperFields(s.paper) { value -> vm.update { it.copy(paper=value) } }
                        Text("${s.paper.lineCount} pautas por página · altura útil ${fmt(s.paper.usableHeight)} mm")
                        Button(onClick=vm::saveProfile) { Text("Salvar perfil de folha") }
                        state.profiles.forEach { profile -> OutlinedButton(onClick={vm.update { it.copy(paper=profile) }}) { Text("Usar ${profile.name}") } }
                        Calibration(s.paper,{p->vm.update { it.copy(paper=p) }},{ vm.simulate(currentPage,true);tab=3 },vm::saveProfile)
                    }
                    2 -> {
                        SectionTitle("Prévia física")
                        Text("Pautas e furos são guias visuais; o SVG contém apenas escrita. Arraste e use dois dedos para ampliar.",style=MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick={page=(currentPage-1).coerceAtLeast(0)},enabled=currentPage>0) { Text("‹") }
                            Text("${if(state.document.pages.isEmpty()) 0 else currentPage+1} / ${state.document.pages.size}")
                            OutlinedButton(onClick={page=currentPage+1},enabled=currentPage<state.document.pages.lastIndex) { Text("›") }
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) { Switch(trajectory,{trajectory=it});Text("Trajetória e ordem dos traços") }
                        state.document.pages.getOrNull(currentPage)?.let { p ->
                            PaperPreview(p,s.paper,Modifier.fillMaxWidth().height(480.dp),trajectory)
                            val plan=remember(p,s) { runCatching { PlotPlanner.plan(p,s) }.getOrNull() }
                            plan?.let { Text("${it.strokes.size} traços · ${fmt(it.writingMm/1000)} m de escrita · estimativa ${fmt(it.seconds/60)} min") }
                            Text("Estimativa geométrica; aceleração e comportamento real da Cameo não medidos.",style=MaterialTheme.typography.bodySmall)
                            Button(onClick={exportSvg.launch("cameo-pagina-${currentPage+1}.svg")},enabled=!state.busy && state.error==null) { Text("Exportar esta página em SVG") }
                        }
                        state.document.warnings.forEach { Text(it) }
                    }
                    3 -> {
                        SectionTitle("Planejamento e simulação")
                        Choices("Perfil",Quality.entries.toList(),s.quality,{it.label}) { q -> vm.update { it.copy(quality=q,tolerance=q.tolerance,writeSpeed=q.speed) } }
                        NumberField("Velocidade estimada de escrita (mm/s)",s.writeSpeed) { v->if(v in 1.0..100.0) vm.update { it.copy(writeSpeed=v) } }
                        NumberField("Velocidade estimada de deslocamento (mm/s)",s.travelSpeed) { v->if(v in 1.0..200.0) vm.update { it.copy(travelSpeed=v) } }
                        NumberField("Simplificação geométrica (mm, 0–0,5)",s.tolerance) { v->if(v in 0.0..0.5) vm.update { it.copy(tolerance=v) } }
                        Text("Velocidades são usadas na estimativa. Força, levantamento, aceleração e velocidades físicas ainda não são configurados no equipamento.",style=MaterialTheme.typography.bodySmall)
                        Button(onClick={vm.simulate(currentPage)},enabled=!state.busy && state.error==null && !simulation.running) { Text("Simular escrita da página ${currentPage+1}") }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick=vm.simulator::pause,enabled=simulation.running) { Text(if(simulation.paused) "Retomar" else "Pausar") }
                            OutlinedButton(onClick=vm.simulator::cancel,enabled=simulation.running) { Text("Cancelar") }
                        }
                        Text(simulation.log)
                        if(simulation.total>0) LinearProgressIndicator(progress={simulation.completed.toFloat()/simulation.total},modifier=Modifier.fillMaxWidth())
                        Text("${simulation.completed} / ${simulation.total} traços")
                        if(simulation.total>0) PaperPreview(Page(vm.simulator.recorded.toList(),0),s.paper,Modifier.fillMaxWidth().height(300.dp))
                        HorizontalDivider()
                        SectionTitle("Cameo 5 · diagnóstico BLE")
                        Text("Esta versão não envia movimentos à Cameo. Descoberta GATT e serialização GPGL estão implementadas; origem, configuração da caneta e controle de execução exigem validação física.")
                        Text("Preferido: ${s.preferredDevice.ifEmpty { "nenhum" }}",style=MaterialTheme.typography.bodySmall)
                        Button(onClick={
                            permissions.launch(if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                        },enabled=!ble.scanning) { Text(if(ble.scanning) "Buscando…" else "Buscar dispositivos BLE") }
                        ble.devices.forEach { d -> OutlinedButton(onClick={vm.update { it.copy(preferredDevice=d.address) };vm.ble.connect(d.address)}) { Text("${d.name} · ${d.rssi} dBm\n${d.address}") } }
                        if(ble.connected) OutlinedButton(onClick=vm.ble::disconnect) { Text("Desconectar GATT") }
                        Text(ble.log,style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={exportLogs.launch("cameo-diagnostico-ble.txt")}) { Text("Exportar diagnóstico BLE") }
                        var usb by remember { mutableStateOf("") }
                        OutlinedButton(onClick={usb=UsbDiagnostics.inspect(context)}) { Text("Inspecionar USB OTG") }
                        if(usb.isNotBlank()) Text(usb,style=MaterialTheme.typography.bodySmall)
                        Button(onClick={},enabled=false) { Text("Escrever na Cameo · aguarda validação") }
                    }
                    4 -> {
                        SectionTitle("Configurações locais")
                        Text("Texto, folhas, calibração e preferências são salvos automaticamente neste celular. O aplicativo funciona sem conta e sem internet.")
                        Button(onClick={exportConfig.launch("cameo-configuracoes.json")}) { Text("Exportar configurações e texto") }
                        OutlinedButton(onClick={importConfig.launch(arrayOf("application/json","text/plain"))}) { Text("Importar configurações") }
                        NumberField("Semente das variações",s.seed.toDouble()) {v->vm.update { it.copy(seed=v.toInt()) } }
                        Text("Mesma semente, texto e configurações produzem os mesmos traços. A prévia e o SVG usam a geometria calibrada.")
                        SectionTitle("Fontes e licença")
                        Text("Hershey futural e scripts, a partir de techninja/hersheytextjs (MIT). Estilos irregular e feio são transformações determinísticas. Acentos portugueses e espanhóis são compostos sobre glifos básicos. Não há variantes alternativas de glifos nesta versão.")
                        Text("Código-fonte e documentação: github.com/eitalouarruda/cameohandwrite",style=MaterialTheme.typography.bodySmall)
                        Text("Teste físico necessário: Cameo 5 original, adaptador de caneta, origem da base, carregamento, limites e calibração. Comece sem caneta; depois use papel descartável.")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
private fun java.io.Reader.readTextLimited():String { val b=CharArray(2000001);var n=0;while(n<b.size) {val read=read(b,n,b.size-n);if(read<0) break;n+=read};require(n<=2000000){"Arquivo muito grande"};return String(b,0,n) }
fun fmt(v:Double)=String.format(Locale.forLanguageTag("pt-BR"),"%.1f",v)
@Composable fun SectionTitle(text:String) { Text(text,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
@Composable fun <T> Choices(label:String,items:List<T>,selected:T,text:(T)->String,onSelect:(T)->Unit) {
    Column { Text(label,style=MaterialTheme.typography.labelLarge);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { items.forEach { item -> FilterChip(item==selected,{onSelect(item)},label={Text(text(item))}) } } }
}
@Composable fun NumberField(label:String,value:Double,onChange:(Double)->Unit) {
    var raw by remember(value) { mutableStateOf(if(value==value.toLong().toDouble()) value.toLong().toString() else value.toString()) }
    OutlinedTextField(value=raw,onValueChange={v->raw=v;v.replace(',','.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onChange)},label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
}
@Composable fun PaperFields(p:Paper,onChange:(Paper)->Unit) {
    NumberField("Largura (mm)",p.width) {onChange(p.copy(width=it))}
    NumberField("Altura (mm)",p.height) {onChange(p.copy(height=it))}
    NumberField("Margem esquerda (mm)",p.left) {onChange(p.copy(left=it))}
    NumberField("Margem direita (mm)",p.right) {onChange(p.copy(right=it))}
    NumberField("Margem superior (mm)",p.top) {onChange(p.copy(top=it))}
    NumberField("Margem inferior (mm)",p.bottom) {onChange(p.copy(bottom=it))}
    NumberField("Faixa reservada aos furos (mm)",p.holes) {onChange(p.copy(holes=it))}
    NumberField("Primeira pauta desde o topo (mm)",p.firstLine) {onChange(p.copy(firstLine=it))}
    NumberField("Distância entre pautas (mm)",p.lineSpacing) {onChange(p.copy(lineSpacing=it))}
}
@Composable fun Calibration(p:Paper,onChange:(Paper)->Unit,onTest:()->Unit,onSave:()->Unit) {
    var step by remember { mutableIntStateOf(0) }
    var measuredX by remember { mutableStateOf(0.0) };var measuredY by remember { mutableStateOf(0.0) }
    SectionTitle("Assistente de calibração · ${step+1}/5")
    Text(listOf("Posicione a folha na base; registre a origem e a orientação. O app não presume alinhamento perfeito das pautas.","Simule três cruzes nas pautas. Exporte o teste SVG para executar com um fluxo já validado. O app ainda não envia o teste físico.","Meça deslocamento: positivo se o traço saiu à direita ou abaixo do alvo. Se a inclinação variar entre os cantos, reposicione a folha.","Aplique a correção oposta ao erro medido, em passos de 0,1 mm. Confirme com novo teste.","Salve a calibração no perfil desta folha. Repita após trocar base, caneta ou carregamento.")[step])
    NumberField("Calibração X (mm)",p.offsetX) {onChange(p.copy(offsetX=kotlin.math.round(it*10)/10))}
    NumberField("Calibração Y (mm)",p.offsetY) {onChange(p.copy(offsetY=kotlin.math.round(it*10)/10))}
    if(step==1) OutlinedButton(onClick=onTest) {Text("Simular teste de alinhamento")}
    if(step==2 || step==3) {
        NumberField("Erro medido X (mm)",measuredX) {measuredX=it}
        NumberField("Erro medido Y (mm)",measuredY) {measuredY=it}
        OutlinedButton(onClick={onChange(p.copy(offsetX=kotlin.math.round((p.offsetX-measuredX)*10)/10,offsetY=kotlin.math.round((p.offsetY-measuredY)*10)/10));measuredX=0.0;measuredY=0.0;step=3}) {Text("Aplicar correção")}
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={step=(step-1).coerceAtLeast(0)},enabled=step>0) {Text("Voltar")}
        Button(onClick={if(step==4) onSave() else step++}) {Text(if(step==4) "Salvar perfil" else "Próximo")}
    }
}
