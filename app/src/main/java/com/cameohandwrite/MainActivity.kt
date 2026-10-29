package com.cameohandwrite

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cameohandwrite.domain.*
import com.cameohandwrite.drivers.cameo.Cameo5Driver
import com.cameohandwrite.ui.PaperPreview
import com.cameohandwrite.ui.plotter.DiagnosticsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState);setContent {
        MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xff36594c),secondary=Color(0xff816943),background=Color(0xfff4f5ef))) { HandwriteApp() }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HandwriteApp(vm:HandwriteViewModel=viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ble by vm.ble.state.collectAsStateWithLifecycle()
    val report by vm.ble.report.collectAsStateWithLifecycle()
    val simulation by vm.simulator.state.collectAsStateWithLifecycle()
    val s=state.settings
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope()
    var tab by remember {mutableIntStateOf(0)}
    var selectedPage by remember {mutableIntStateOf(0)}
    val page=selectedPage.coerceIn(0,maxOf(0,state.document.pages.lastIndex))
    val current=state.document.pages.getOrNull(page)
    val ready=ble.connected && Cameo5Driver.identifiesOriginalCameo5(ble.model)
    var devicesOpen by remember {mutableStateOf(false)}
    var awaitingConnection by remember {mutableStateOf(false)}
    var connectAfterPermission by remember {mutableStateOf<String?>(null)}
    var pendingPage by remember {mutableStateOf<Int?>(null)}
    var advanced by remember {mutableStateOf(false)}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(devicesOpen) {
            val address=connectAfterPermission;connectAfterPermission=null
            if(grants.values.all {it}) {
                if(address!=null) {awaitingConnection=true;vm.connectPrinter(address)} else vm.ble.scan()
            } else vm.message("Permita Bluetooth/localização para encontrar a Cameo")
        }
    }
    fun search(address:String?=null) {
        connectAfterPermission=address
        permissions.launch(if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }
    BackHandler(enabled=ble.testing) {vm.message("Use Cancelar envio antes de sair da escrita")}
    LaunchedEffect(ready,state.connecting,state.busy,devicesOpen,pendingPage,awaitingConnection) {
        if(awaitingConnection && ready && !state.connecting && !state.busy && devicesOpen) {
            devicesOpen=false;awaitingConnection=false
            val target=pendingPage;pendingPage=null
            if(target!=null) {focus.clearFocus();keyboard?.hide();vm.printPage(target)}
        }
    }
    DisposableEffect(ble.testing) {
        val window=(context as? Activity)?.window
        if(ble.testing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }
    val svg=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if(uri!=null && current!=null) scope.launch {try {
            val data=PlotPlanner.svg(current,s.paper)
            withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(data)}}
            vm.message("SVG da página ${page+1} exportado")
        } catch(e:Exception) {vm.message("Não foi possível exportar: ${e.message}")} }
    }
    val exportConfig=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) scope.launch {try {
            val data=vm.exportSettings()
            withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(data)}}
            vm.message("Configurações exportadas")
        } catch(e:Exception) {vm.message("Falha: ${e.message}")} }
    }
    val importConfig=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {try {
            val text=withContext(Dispatchers.IO) {context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {it.readLimited()} ?: error("Arquivo inacessível")}
            vm.importSettings(text)
        } catch(e:Exception) {vm.message("Falha: ${e.message}")} }
    }
    val exportReport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) scope.launch {try {
            val data=vm.exportHardwareReport()
            withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(data)}}
            vm.message("Relatório exportado")
        } catch(e:Exception) {vm.message("Falha: ${e.message}")} }
    }
    val exportLogs=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri!=null) scope.launch {try {withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(ble.log)}}} catch(e:Exception) {vm.message("Falha: ${e.message}")} }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cameo Handwrite",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("${BuildConfig.VERSION_NAME} · ${if(s.style==Style.MESSY) "Feia" else s.style.label} · ${s.quality.label}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick={focus.clearFocus();keyboard?.hide();pendingPage=null;devicesOpen=true;search(if(!ready) s.preferredDevice.takeIf {it.isNotBlank()} else null)},enabled=!ble.testing && !state.connecting) {Text(if(state.connecting) "Conectando…" else if(ready) "Conectada" else "Conectar")}
            }
            if(state.busy || state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(remember(tab) {ScrollState(0)}).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                when(tab) {
                    0 -> {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            SectionTitle("Seu texto")
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick={
                                val clip=context.getSystemService(ClipboardManager::class.java).primaryClip
                                val text=if(clip!=null && clip.itemCount>0) clip.getItemAt(0).coerceToText(context).toString() else ""
                                if(text.length>200000) vm.message("Limite de 200 mil caracteres") else if(text.isEmpty()) vm.message("Copie um texto primeiro") else vm.update {it.copy(text=text)}
                            },enabled=!ble.testing && !state.connecting) {Text("Colar")}
                            if(s.text.isNotEmpty()) TextButton(onClick={vm.update {it.copy(text="")}},enabled=!ble.testing && !state.connecting) {Text("Limpar")}
                        }
                        OutlinedTextField(value=s.text,onValueChange={text->if(text.length<=200000) vm.update {it.copy(text=text)} else vm.message("Limite de 200 mil caracteres")},label={Text("Cole ou escreva o texto")},enabled=!ble.testing && !state.connecting,modifier=Modifier.fillMaxWidth().heightIn(min=170.dp,max=300.dp).testTag("editorText"))
                        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                        Text(if(s.text.isBlank()) "O texto aparece aqui na folha, antes de escrever." else "${state.document.pages.size} página(s) · ${s.text.length} caracteres",style=MaterialTheme.typography.bodySmall)
                        if(state.document.pages.size>1) Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            OutlinedButton(onClick={selectedPage=page-1},enabled=page>0 && !ble.testing) {Text("Anterior")}
                            Text("${page+1} / ${state.document.pages.size}")
                            OutlinedButton(onClick={selectedPage=page+1},enabled=page<state.document.pages.lastIndex && !ble.testing) {Text("Próxima")}
                        }
                        if(current!=null) PaperPreview(current,s.paper,Modifier.fillMaxWidth().height(390.dp).testTag("pagePreview"))

                    }
                    1 -> {
                        SectionTitle("Sua folha")
                        Choices("Perfis",state.profiles,s.paper,{it.name}) {p->vm.update {it.copy(paper=p)}}
                        Choices("Tamanho",listOf("A4","A5","Personalizado"),when {s.paper.width==210.0 && s.paper.height==297.0->"A4";s.paper.width==148.0 && s.paper.height==210.0->"A5";else->"Personalizado"},{it}) {v->vm.update {it.copy(paper=when(v) {"A4"->it.paper.copy(width=210.0,height=297.0);"A5"->it.paper.copy(width=148.0,height=210.0);else->it.paper})}}
                        OutlinedTextField(s.paper.name,{v->vm.update {it.copy(paper=it.paper.copy(name=v))}},label={Text("Nome do perfil")},modifier=Modifier.fillMaxWidth())
                        NumberField("Largura (mm)",s.paper.width) {v->vm.update {it.copy(paper=it.paper.copy(width=v))}}
                        NumberField("Altura (mm)",s.paper.height) {v->vm.update {it.copy(paper=it.paper.copy(height=v))}}
                        NumberField("Primeira pauta desde o topo (mm)",s.paper.firstLine) {v->vm.update {it.copy(paper=it.paper.copy(firstLine=v))}}
                        NumberField("Distância entre pautas (mm)",s.paper.lineSpacing) {v->vm.update {it.copy(paper=it.paper.copy(lineSpacing=v))}}
                        var margins by remember {mutableStateOf(false)}
                        TextButton(onClick={margins=!margins}) {Text(if(margins) "Ocultar margens e calibração" else "Margens e calibração")}
                        if(margins) {
                            NumberField("Margem esquerda (mm)",s.paper.left) {v->vm.update {it.copy(paper=it.paper.copy(left=v))}}
                            NumberField("Margem direita (mm)",s.paper.right) {v->vm.update {it.copy(paper=it.paper.copy(right=v))}}
                            NumberField("Margem superior (mm)",s.paper.top) {v->vm.update {it.copy(paper=it.paper.copy(top=v))}}
                            NumberField("Margem inferior (mm)",s.paper.bottom) {v->vm.update {it.copy(paper=it.paper.copy(bottom=v))}}
                            NumberField("Faixa dos furos (mm)",s.paper.holes) {v->vm.update {it.copy(paper=it.paper.copy(holes=v))}}
                            Text("Ajustes de 0,1 mm. Valores positivos deslocam à direita/abaixo.",style=MaterialTheme.typography.bodySmall)
                            NumberField("Correção X (mm)",s.paper.offsetX) {v->vm.update {it.copy(paper=it.paper.copy(offsetX=kotlin.math.round(v*10)/10))}}
                            NumberField("Correção Y (mm)",s.paper.offsetY) {v->vm.update {it.copy(paper=it.paper.copy(offsetY=kotlin.math.round(v*10)/10))}}
                        }
                        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                        Button(onClick=vm::saveProfile) {Text("Salvar perfil")}
                    }
                    2 -> {
                        SectionTitle("Aparência da escrita")
                        Choices("Fonte",Style.entries.toList(),s.style,{it.label}) {v->vm.update {it.copy(style=v)}}
                        Choices("Escrita",Quality.entries.toList(),s.quality,{it.label}) {v->vm.update {it.copy(quality=v,tolerance=v.tolerance,writeSpeed=v.speed)}}
                        NumberField("Altura das letras (mm)",s.letterHeight) {v->vm.update {it.copy(letterHeight=v)}}
                        Text("Irregularidade: ${s.irregularity}%")
                        Slider(s.irregularity.toFloat(),{v->vm.update {it.copy(irregularity=v.toInt())}},valueRange=0f..100f)
                        Text("Ao abrir, o app usa letra feia e escrita rápida. Texto e perfil de folha ficam salvos.",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={advanced=!advanced},enabled=!ble.testing) {Text(if(advanced) "Ocultar opções avançadas" else "Opções avançadas")}
                        if(advanced) {
                            NumberField("Espaço entre palavras (mm)",s.wordSpace) {v->vm.update {it.copy(wordSpace=v)}}
                            Choices("Alinhamento",Align.entries.toList(),s.alignment,{it.label}) {v->vm.update {it.copy(alignment=v)}}
                            NumberField("Força da caneta (escala GPGL, 1–20)",s.penForce.toDouble()) {v->if(v in 1.0..20.0) vm.update {it.copy(penForce=v.toInt())}}
                            NumberField("Velocidade da Cameo (escala GPGL, 1–30)",s.penSpeed.toDouble()) {v->if(v in 1.0..30.0) vm.update {it.copy(penSpeed=v.toInt())}}
                            Text("Padrão físico: força 1 e velocidade 10, usados nos testes da sua Cameo. O perfil Rápido controla os trajetos, sem alterar o transporte BLE.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick={svg.launch("cameo-pagina-${page+1}.svg")},enabled=current!=null && !state.busy && state.error==null) {Text("Exportar página em SVG")}
                            OutlinedButton(onClick={exportConfig.launch("cameo-configuracoes.json")}) {Text("Exportar configurações")}
                            OutlinedButton(onClick={importConfig.launch(arrayOf("application/json","text/plain"))}) {Text("Importar configurações")}
                            OutlinedButton(onClick={vm.simulate(page)},enabled=current?.strokes?.isNotEmpty()==true && !state.busy && state.error==null && !simulation.running) {Text("Simular página")}
                            if(simulation.total>0) {
                                Text("${simulation.log} · ${simulation.completed}/${simulation.total}")
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick=vm.simulator::pause,enabled=simulation.running) {Text(if(simulation.paused) "Retomar simulação" else "Pausar simulação")}
                                    OutlinedButton(onClick=vm.simulator::cancel,enabled=simulation.running) {Text("Cancelar simulação")}
                                }
                            }
                            DiagnosticsPanel(vm,state,ble,report,onExportReport={exportReport.launch("cameo-relatorio.json")},onExportLogs={exportLogs.launch("cameo-diagnostico-ble.txt")},onCopy={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Resultado Cameo",vm.hardwareReportSummary()));vm.message("Resumo copiado")})
                            Text("Fontes single-line Hershey scripts/futural, com deformação determinística e acentos compostos. Licenças incluídas no APK.",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if(state.message.isNotBlank()) Text(state.message,style=MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }
            if(tab==0) Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(ble.printMessage.isNotBlank()) Text(ble.printMessage,color=MaterialTheme.colorScheme.primary)
                if(ble.printingPage) {
                    Text("Página ${ble.pageNumber} · ${ble.suiteDone} / ${ble.suiteTotal} traços")
                    LinearProgressIndicator(progress={if(ble.suiteTotal>0) ble.suiteDone.toFloat()/ble.suiteTotal else 0f},modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick=vm.ble::pauseSuite) {Text(if(ble.suitePaused) "Retomar" else "Pausar")}
                        OutlinedButton(onClick=vm.ble::stopMotionTest) {Text("Cancelar envio")}
                    }
                    Text("Pausa/cancelamento aguarda o bloco atual. Deixe o app aberto.",style=MaterialTheme.typography.bodySmall)
                } else {
                    Text("Folha carregada como na prévia; caneta no suporte 1 e sem lâminas.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={
                        focus.clearFocus();keyboard?.hide()
                        if(ready) vm.printPage(page) else {pendingPage=page;devicesOpen=true;search(s.preferredDevice.takeIf {it.isNotBlank()})}
                    },enabled=s.text.isNotBlank() && current?.strokes?.isNotEmpty()==true && !state.busy && state.error==null && !ble.testing && !state.connecting,modifier=Modifier.fillMaxWidth().testTag("printPage")) {Text(if(ble.completedPage==page+1) "Escrever novamente a página ${page+1}" else "Escrever página ${page+1}")}
                }
            }
            TabRow(selectedTabIndex=tab) {listOf("Texto","Folha","Ajustes").forEachIndexed {index,label->Tab(selected=tab==index,onClick={tab=index},enabled=!ble.testing && !state.connecting,text={Text(label)})}}
        }
    }
    if(devicesOpen) ModalBottomSheet(onDismissRequest={devicesOpen=false;pendingPage=null;awaitingConnection=false;connectAfterPermission=null}) {
        Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SectionTitle("Conectar Cameo")
            Text("Ligue a Cameo e aguarde ficar ociosa. Para escrever, carregue a folha e coloque a caneta no suporte 1, sem lâminas.")
            if(state.connecting || ble.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.connecting) Text("Conectando e identificando a Cameo…")
            ble.devices.sortedWith(compareByDescending<com.cameohandwrite.data.bluetooth.BleDiagnostics.Device> {it.address==s.preferredDevice}.thenByDescending {it.name.contains("cameo",true)}).forEach {device->
                OutlinedButton(onClick={awaitingConnection=true;vm.connectPrinter(device.address)},enabled=!state.connecting,modifier=Modifier.fillMaxWidth()) {Text("${device.name}\n${device.address}")}
            }
            if(!state.connecting && !ble.scanning) TextButton(onClick={search()}) {Text("Buscar novamente")}
            if(state.message.isNotBlank()) Text(state.message,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
        }
    }
}
private fun java.io.Reader.readLimited():String {val b=CharArray(2000001);var n=0;while(n<b.size) {val count=read(b,n,b.size-n);if(count<0) break;n+=count};require(n<=2000000){"Arquivo muito grande"};return String(b,0,n)}
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
