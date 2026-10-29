package com.cameohandwrite.ui.plotter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cameohandwrite.HandwriteViewModel
import com.cameohandwrite.data.HardwareTestReport
import com.cameohandwrite.data.bluetooth.BleDiagnostics
import com.cameohandwrite.data.usb.UsbDiagnostics
import androidx.compose.ui.platform.LocalContext

@Composable fun DiagnosticsPanel(vm:HandwriteViewModel,state:HandwriteViewModel.State,ble:BleDiagnostics.State,report:HardwareTestReport?,onExportReport:()->Unit,onExportLogs:()->Unit,onCopy:()->Unit) {
    var show by remember {mutableStateOf(false)}
    val context=LocalContext.current
    TextButton(onClick={show=!show}) {Text(if(show) "Ocultar diagnóstico e testes" else "Diagnóstico e testes")}
    if(!show) return
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("${ble.model.ifBlank {"Cameo não identificada"}} · ${ble.deviceStatus}")
        OutlinedButton(onClick=vm::queryDeviceStatus,enabled=ble.connected && !ble.testing && !state.connecting) {Text("Consultar estado")}
        OutlinedButton(onClick=vm::identifyDevice,enabled=ble.connected && !ble.testing && !state.connecting) {Text("Reidentificar modelo (máquina ociosa)")}
        var compare by remember {mutableStateOf(false)}
        val activeComparison=if(ble.suiteRunning) report?.comparison ?: compare else compare
        val paper=if(ble.suiteRunning) report?.paper ?: state.settings.paper else state.settings.paper
        val preview=remember(paper,activeComparison) {runCatching {vm.suitePreview(paper,activeComparison)}}
        HardwareSuiteControls(ble,preview.getOrNull(),preview.exceptionOrNull()?.message,report,{vm.runHardwareSuite(it,activeComparison)},vm.ble::pauseSuite,vm.ble::stopMotionTest,onExportReport,onCopy,vm.ble::editReport,vm::applySuiteCalibration,activeComparison,{compare=it})
        var motion by remember {mutableStateOf(false)}
        TextButton(onClick={motion=!motion},enabled=!ble.testing) {Text(if(motion) "Ocultar teste sem ferramenta" else "Teste sem ferramenta")}
        if(motion) MotionProbeControls(ble,vm::queryDeviceStatus,vm::testDeviceMotion,vm.ble::stopMotionTest)
        OutlinedButton(onClick=vm.ble::disconnect,enabled=ble.connected && !ble.testing) {Text("Desconectar")}
        Text(ble.log,style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick=onExportLogs) {Text("Exportar diagnóstico BLE")}
        var usb by remember {mutableStateOf("")}
        OutlinedButton(onClick={usb=UsbDiagnostics.inspect(context)}) {Text("Inspecionar USB OTG")}
        if(usb.isNotBlank()) Text(usb)
    }
}
