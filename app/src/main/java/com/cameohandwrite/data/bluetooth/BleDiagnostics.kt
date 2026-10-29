@file:Suppress("MissingPermission")
package com.cameohandwrite.data.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import com.cameohandwrite.drivers.cameo.Cameo5Driver
import com.cameohandwrite.drivers.cameo.CameoMotionProbe
import com.cameohandwrite.drivers.cameo.*
import com.cameohandwrite.data.HardwareTestReport
import java.time.Instant
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit diagnostic handshake and fixed pen-up probe; discovery alone never sends commands. */
class BleDiagnostics(private val context:Context) {
    data class Device(val name:String,val address:String,val rssi:Int)
    data class State(val scanning:Boolean=false,val connected:Boolean=false,val devices:List<Device> = emptyList(),val log:String="Nenhum comando enviado",val knownService:Boolean=false,val identifying:Boolean=false,val model:String="",val testing:Boolean=false,val queryingStatus:Boolean=false,val testSteps:Int=0,val testAttempted:Boolean=false,val deviceStatus:String="",val testMessage:String="",val readyForProbe:Boolean=false,val suiteRunning:Boolean=false,val suitePaused:Boolean=false,val suiteStage:String="",val suiteDone:Int=0,val suiteTotal:Int=0,val printingPage:Boolean=false,val pageNumber:Int?=null,val printMessage:String="",val completedPage:Int?=null)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private val reportMutable=MutableStateFlow<HardwareTestReport?>(null)
    val report=reportMutable.asStateFlow()
    @Volatile private var recordingSuite=false
    private val suiteJournal=ArrayDeque<String>()
    private var logBuffer="Nenhum comando enviado"
    private var suiteGattWrites=0
    private var suiteStatusQueries=0
    private var suiteStreamBlocks=0
    private var suiteGattWriteMillis=0L
    private var suiteStatusWaitMillis=0L
    private var suiteStreamBytes=0
    fun restoreReport(report:HardwareTestReport?) { if(reportMutable.value==null) reportMutable.value=report }
    fun editReport(change:(HardwareTestReport)->HardwareTestReport) { if(!mutable.value.testing) reportMutable.update { it?.let(change) } }
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var gatt:BluetoothGatt?=null
    @Volatile private var stopProbe=false
    @Volatile private var identifiedGatt:BluetoothGatt?=null
    private var scanner:BluetoothLeScanner?=null
    private val operationLock=Mutex()
    @Volatile private var completion:CompletableDeferred<Int>?=null
    @Volatile private var expectedOperation:String?=null
    private var replies=Channel<ByteArray>(Channel.UNLIMITED)
    private val readId=UUID.fromString(Cameo5Driver.READ_UUID)
    private val cccd=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private fun complete(key:String,status:Int) { if(expectedOperation==key) completion?.complete(status) }
    private suspend fun operation(key:String,action:()->Boolean) {
        val deferred=CompletableDeferred<Int>();completion=deferred;expectedOperation=key
        try { check(action()) { "GATT recusou operação $key" };check(withTimeout(8000) { deferred.await() }==BluetoothGatt.GATT_SUCCESS) { "GATT falhou em $key" } }
        finally { completion=null;expectedOperation=null }
    }
    private fun notification(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray) {
        if(g!==gatt) return
        log("RX ${c.uuid}: "+hex(value))
        if(c.uuid==readId && (mutable.value.identifying || mutable.value.testing || mutable.value.queryingStatus)) replies.trySend(value.copyOf())
    }
    /** Explicit opt-in only: this handshake resets protocol state. Never run during another job. */
    suspend fun identify() = operationLock.withLock {
        identifiedGatt=null
        val g=gatt ?: error("Conecte primeiro")
        val service=g.getService(UUID.fromString(Cameo5Driver.SERVICE_UUID)) ?: error("Serviço documentado ausente")
        val control=service.getCharacteristic(UUID.fromString(Cameo5Driver.CONTROL_UUID)) ?: error("Controle ausente")
        val read=service.getCharacteristic(readId) ?: error("Resposta ausente")
        val write=service.getCharacteristic(UUID.fromString(Cameo5Driver.WRITE_UUID)) ?: error("Escrita ausente")
        listOf(control,read,write).forEach { check(it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "Característica não aceita escrita confirmada" } }
        replies.close();replies=Channel(Channel.UNLIMITED)
        mutable.value=mutable.value.copy(identifying=true,model="",deviceStatus="",readyForProbe=false)
        try {
            for(c in listOf(control,read)) {
                val notify=c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                check(notify || c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) { "Notificações não suportadas" }
                check(g.setCharacteristicNotification(c,true))
                val descriptor=c.getDescriptor(cccd) ?: error("CCCD ausente")
                val bytes=if(notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                operation("descriptor:${c.uuid}") {
                    if(Build.VERSION.SDK_INT>=33) g.writeDescriptor(descriptor,bytes)==BluetoothStatusCodes.SUCCESS
                    else { @Suppress("DEPRECATION") descriptor.value=bytes; @Suppress("DEPRECATION") g.writeDescriptor(descriptor) }
                }
            }
            for(c in listOf(control,read,write)) writeConfirmed(g,c,byteArrayOf(0x1b,0x04))
            delay(1000)
            val response=query(g,write,Cameo5Driver.firmwareQuery)
            check(g===gatt && mutable.value.connected) { "Conexão alterada durante a identificação" }
            identifiedGatt=g
            mutable.value=mutable.value.copy(model=response)
            log("FG: $response; Cameo 5 original reconhecida=${Cameo5Driver.identifiesOriginalCameo5(response)}. Nenhum movimento enviado.")
        } catch(e:Exception) {
            disconnect();log("Identificação falhou: ${e.message}; reconecte antes de tentar novamente")
            throw e
        } finally { mutable.value=mutable.value.copy(identifying=false) }
    }
    private suspend fun query(g:BluetoothGatt,c:BluetoothGattCharacteristic,command:ByteArray):String {
        while(replies.tryReceive().isSuccess) { }
        writeConfirmed(g,c,command)
        return withTimeout(8000) {
            val bytes=mutableListOf<Byte>()
            while(3.toByte() !in bytes) {
                bytes.addAll(replies.receive().toList())
                check(bytes.size<=512) { "Resposta GPGL excessiva" }
            }
            bytes.takeWhile { it!=3.toByte() }.toByteArray().toString(Charsets.US_ASCII)
        }
    }
    private fun identifiedConnection():Pair<BluetoothGatt,BluetoothGattCharacteristic> {
        val g=gatt ?: error("Conecte primeiro")
        check(g===identifiedGatt && mutable.value.connected && Cameo5Driver.identifiesOriginalCameo5(mutable.value.model)) { "Identifique uma Cameo 5 original nesta conexão" }
        val c=g.getService(UUID.fromString(Cameo5Driver.SERVICE_UUID))?.getCharacteristic(UUID.fromString(Cameo5Driver.WRITE_UUID)) ?: error("Característica de escrita ausente")
        return g to c
    }
    private suspend fun readStatus(g:BluetoothGatt,c:BluetoothGattCharacteristic):Cameo5Driver.Status {
        check(g===gatt && mutable.value.connected) { "Conexão interrompida" }
        if(recordingSuite) suiteStatusQueries++
        val statusStarted=android.os.SystemClock.elapsedRealtime()
        val response=try { query(g,c,Cameo5Driver.statusQuery) } finally {
            if(recordingSuite) suiteStatusWaitMillis+=android.os.SystemClock.elapsedRealtime()-statusStarted
        }
        val status=Cameo5Driver.decodeStatus(response)
        val label=when(status) {
            Cameo5Driver.Status.READY -> "Pronta"
            Cameo5Driver.Status.MOVING -> "Em movimento"
            Cameo5Driver.Status.UNLOADED -> "Base/mídia descarregada"
            Cameo5Driver.Status.UNKNOWN -> "Estado desconhecido"
        }
        mutable.value=mutable.value.copy(deviceStatus=label,readyForProbe=status==Cameo5Driver.Status.READY)
        log("Estado GPGL: $label; resposta=$response")
        return status
    }
    suspend fun queryStatus() = operationLock.withLock {
        val (g,c)=identifiedConnection()
        mutable.value=mutable.value.copy(queryingStatus=true,readyForProbe=false)
        try { readStatus(g,c) }
        catch(e:Exception) { disconnect();log("Consulta falhou: ${e.message}; reconecte e identifique novamente");throw e }
        finally { mutable.value=mutable.value.copy(queryingStatus=false) }
    }
    suspend fun testMotion() = operationLock.withLock {
        val (g,c)=identifiedConnection()
        check(!mutable.value.testAttempted) { "Reconecte e identifique novamente antes de repetir o teste" }
        stopProbe=false
        mutable.value=mutable.value.copy(testing=true,testAttempted=true,readyForProbe=false,testSteps=0,testMessage="Teste iniciado; comandos absolutos próximos à origem carregada")
        log("TESTE SEM FERRAMENTA: (1,1) → (2,1) → (1,1) mm; sem D, reset ou retorno automático")
        try {
            val complete=CameoMotionProbe.run(mutable.value.model,object:CameoMotionProbe.Link {
                override suspend fun status()=readStatus(g,c)
                override suspend fun write(command:ByteArray) {
                    check(g===gatt && mutable.value.connected && g===identifiedGatt) { "Conexão alterada; envio interrompido" }
                    writeConfirmed(g,c,command)
                }
            },stopRequested={stopProbe},onProgress={ n -> mutable.value=mutable.value.copy(testSteps=n) })
            val message=if(complete) "3 comandos M confirmados e estado pronto. Observe se o carro realmente moveu: o status não comprova movimento físico." else "Envio das próximas etapas interrompido. Nenhum retorno automático enviado."
            mutable.value=mutable.value.copy(testMessage=message)
            log(message)
        } catch(e:Exception) {
            stopProbe=true;disconnect()
            val message="Teste interrompido: ${e.message}. Sem repetição automática; um comando já aceito pode continuar na máquina."
            mutable.value=mutable.value.copy(testMessage=message);log(message)
            throw e
        } finally { mutable.value=mutable.value.copy(testing=false) }
    }
    suspend fun runSuite(suite:HardwareSuite,setup:PenSetup) = operationLock.withLock {
        CameoTestSuite.validate(suite)
        executeJob(suite,setup)
    }
    suspend fun printPage(job:PagePrintJob,setup:PenSetup) = operationLock.withLock {
        identifiedConnection()
        stopProbe=false
        mutable.update {it.copy(testing=true,printingPage=true,suitePaused=false,pageNumber=job.number,completedPage=null,suiteDone=0,suiteTotal=job.page.strokes.size,printMessage="Preparando página ${job.number}…")}
        var prepared=false
        try {
            val validated=withContext(Dispatchers.Default) {CameoPageJob.prepare(job.paper,job.page,job.number,job.totalPages)}
            if(stopProbe) {mutable.update {it.copy(printMessage="Preparação cancelada; nenhum comando de escrita enviado")};return@withLock}
            prepared=true
            executeJob(HardwareSuite(validated.paper,listOf(validated.stage)),setup,validated.number,validated.totalPages)
        } catch(e:Exception) {
            if(!prepared) mutable.update {it.copy(printMessage="Página não enviada: ${e.message}")}
            throw e
        } finally {mutable.update {it.copy(testing=false,printingPage=false)}}
    }
    private suspend fun executeJob(suite:HardwareSuite,setup:PenSetup,pageNumber:Int?=null,totalPages:Int?=null) {
        val (g,c)=identifiedConnection()
        setup.commands()
        if(pageNumber==null) stopProbe=false
        reportMutable.value=HardwareTestReport.start(mutable.value.model,suite,setup).copy(pageNumber=pageNumber,totalPages=totalPages)
        mutable.value=mutable.value.copy(testing=true,suiteRunning=pageNumber==null,printingPage=pageNumber!=null,pageNumber=pageNumber,printMessage=if(pageNumber!=null) "Escrevendo página $pageNumber de $totalPages" else mutable.value.printMessage,completedPage=null,suitePaused=if(pageNumber!=null) mutable.value.suitePaused else false,suiteDone=0,suiteTotal=suite.stages.sumOf { it.page.strokes.size },suiteStage="Preparando",readyForProbe=false)
        val started=android.os.SystemClock.elapsedRealtime()
        suiteGattWrites=0;suiteStatusQueries=0;suiteStreamBlocks=0
        suiteGattWriteMillis=0;suiteStatusWaitMillis=0;suiteStreamBytes=0
        val stageStarted=mutableMapOf<Int,Long>()
        synchronized(this) {suiteJournal.clear();recordingSuite=true}
        val priorityAccepted=try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch(_:SecurityException) { false }
        log("Prioridade BLE alta solicitada: $priorityAccepted; intervalo real depende do Android/equipamento")
        log("Envio em blocos de até 512 bytes, cada um finalizado com caneta levantada; ATT de 20 bytes com confirmação")
        log("BATERIA DE CANETA: suporte 1, força ${setup.force}, velocidade ${setup.speed} (escala GPGL); perfil ${suite.paper.name}")
        try {
            val complete=CameoJobRunner.run(mutable.value.model,suite.paper,suite.stages,setup,object:CameoMotionProbe.Link {
                override suspend fun status()=readStatus(g,c)
                override suspend fun write(command:ByteArray) {
                    check(g===gatt && mutable.value.connected && g===identifiedGatt) { "Conexão alterada; sem repetição de comandos" }
                    CameoBleWriter.write(command) { fragment ->
                        check(g===gatt && mutable.value.connected && g===identifiedGatt) { "Conexão alterada durante bloco; não haverá repetição" }
                        writeConfirmed(g,c,fragment)
                    }
                    suiteStreamBlocks++;suiteStreamBytes+=command.size
                }
            },stop={stopProbe},paused={mutable.value.suitePaused},onStage={index,title ->
                mutable.value=mutable.value.copy(suiteStage=title)
                if(index>=0) {
                    val finished=title.contains("envio concluído")
                    if(!finished) stageStarted[index]=android.os.SystemClock.elapsedRealtime()
                    val elapsed=if(finished) stageStarted[index]?.let {android.os.SystemClock.elapsedRealtime()-it} else null
                    reportMutable.update { r -> r?.copy(stages=r.stages.mapIndexed { i,s -> if(i==index) s.copy(execution=if(finished) "Envio concluído; observação pendente" else "Em execução",elapsedMillis=elapsed) else s }) }
                }
                log("ETAPA: $title");flushSuiteLogs()
            },onStroke={done,total -> mutable.value=mutable.value.copy(suiteDone=done,suiteTotal=total);flushSuiteLogs() })
            val outcome=if(complete) "Envio concluído; avaliação física pendente" else "Interrompido pelo operador; próximos traços não enviados"
            reportMutable.update { r -> r?.copy(outcome=outcome,stages=r.stages.map { if(it.execution=="Em execução") it.copy(execution="Parcial; interrompido") else it }) }
            if(pageNumber!=null) mutable.update {it.copy(completedPage=if(complete) pageNumber else null,printMessage=if(complete) "Página $pageNumber enviada. Confira a folha antes da próxima." else "Envio cancelado. A folha pode estar parcialmente escrita; use outra folha para recomeçar.")}
            log(outcome)
        } catch(e:Exception) {
            reportMutable.update { r -> r?.copy(outcome="Falha: ${e.message}; não há retomada automática",stages=r.stages.map { if(it.execution=="Em execução") it.copy(execution="Falha/parcial; verificar fisicamente") else it }) }
            if(e is PlotterNotReadyException) {
                log(e.message ?: "Máquina não está pronta")
                if(pageNumber!=null) mutable.update {it.copy(printMessage=if(it.suiteDone==0) e.message ?: "Carregue a folha" else "Envio interrompido. Use outra folha para recomeçar: ${e.message}")}
            } else {
                log("Falha: ${e.message}. A caneta pode permanecer baixada; confira a máquina antes de retirar a folha.")
                if(pageNumber!=null) mutable.update {it.copy(printMessage="Envio interrompido: ${e.message}. Não há retomada automática; confira a caneta e use outra folha antes de repetir.")}
                disconnect()
            }
            throw e
        } finally {
            val elapsed=android.os.SystemClock.elapsedRealtime()-started
            log("Métricas: ${elapsed} ms; $suiteGattWrites escritas GATT; $suiteStatusQueries consultas; $suiteStreamBlocks blocos")
            reportMutable.update { r -> r?.copy(elapsedMillis=elapsed,gattWrites=suiteGattWrites,statusQueries=suiteStatusQueries,streamBlocks=suiteStreamBlocks,gattWriteMillis=suiteGattWriteMillis,statusWaitMillis=suiteStatusWaitMillis,streamBytes=suiteStreamBytes) }
            if(g===gatt && mutable.value.connected) {
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) } catch(_:SecurityException) { }
            }
            flushSuiteLogs()
            recordingSuite=false
            mutable.value=mutable.value.copy(testing=false,suiteRunning=false,printingPage=false,suitePaused=false)
        }
    }
    fun pauseSuite() { if(mutable.value.suiteRunning || mutable.value.printingPage) {
        mutable.value=mutable.value.copy(suitePaused=!mutable.value.suitePaused)
        log(if(mutable.value.suitePaused) "Pausa solicitada após o bloco atual, com caneta levantada" else "Bateria retomada")
    } }
    fun stopMotionTest() {
        stopProbe=true
        log("Solicitada interrupção das próximas etapas; isso não aborta o comando já recebido pela Cameo")
        mutable.value=mutable.value.copy(testMessage=if(mutable.value.suiteRunning || mutable.value.printingPage) "Parando envio após o bloco atual; aguarde a máquina" else "Parando envio após o comando atual; aguarde a máquina")
    }
    fun clearPrintStatus() {
        if(!mutable.value.testing) mutable.update {it.copy(completedPage=null,printMessage="",pageNumber=null)}
    }
    private fun invalidateSession() {
        identifiedGatt=null
        mutable.value=mutable.value.copy(model="",deviceStatus="",readyForProbe=false)
    }
    private suspend fun writeConfirmed(g:BluetoothGatt,c:BluetoothGattCharacteristic,bytes:ByteArray) {
        val ackStarted=android.os.SystemClock.elapsedRealtime()
        operation("write:${c.uuid}") {
            if(Build.VERSION.SDK_INT>=33) g.writeCharacteristic(c,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") c.value=bytes;c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
        }
        if(recordingSuite) {suiteGattWrites++;suiteGattWriteMillis+=android.os.SystemClock.elapsedRealtime()-ackStarted}
        log("TX ${c.uuid}: "+hex(bytes))
    }
    private val stop=Runnable { stopScan() }
    private fun hex(bytes:ByteArray)=buildString(bytes.size*3) {
        val digits="0123456789abcdef"
        bytes.forEachIndexed { index,byte ->
            if(index>0) append(' ')
            val v=byte.toInt() and 255;append(digits[v ushr 4]);append(digits[v and 15])
        }
    }
    private fun log(message:String) { synchronized(this) {
        val line=Instant.now().toString()+" "+message
        logBuffer=(logBuffer+"\n"+line).takeLast(16000)
        if(recordingSuite) {
            suiteJournal.addLast(line)
            if(suiteJournal.size>20000) suiteJournal.removeFirst()
        } else mutable.update {it.copy(log=logBuffer)}
    } }
    /** Publishing packet-by-packet rebuilt the Compose screen and copied the growing report.
     * Retain every event, but publish only at stage/block boundaries and final completion.
     */
    private fun flushSuiteLogs() { synchronized(this) {
        mutable.update {it.copy(log=logBuffer)}
        val snapshot=suiteJournal.toList()
        reportMutable.update {r->r?.copy(events=snapshot)}
    } }
    private val callback=object:ScanCallback() {
        override fun onScanResult(callbackType:Int,result:ScanResult) {
            val device=Device(result.scanRecord?.deviceName ?: result.device.name ?: "BLE sem nome",result.device.address,result.rssi)
            synchronized(this@BleDiagnostics) { mutable.value=mutable.value.copy(devices=(mutable.value.devices.filter { it.address!=device.address }+device).sortedByDescending { it.rssi }) }
        }
        override fun onScanFailed(errorCode:Int) { mutable.value=mutable.value.copy(scanning=false);log("Falha na busca BLE: $errorCode") }
    }
    fun scan() {
        if(mutable.value.testing || mutable.value.identifying || mutable.value.queryingStatus) return
        try {
            stopScan()
            if(manager?.adapter?.isEnabled!=true) { log("Ative o Bluetooth. Android 10/11 também exige localização ativa para busca.");return }
            scanner=manager.adapter.bluetoothLeScanner
            mutable.value=mutable.value.copy(scanning=true,devices=emptyList())
            scanner?.startScan(callback);handler.postDelayed(stop,12000)
            log("Busca BLE por 12 segundos")
        } catch(e:Exception) { log("Busca: ${e.message}");mutable.value=mutable.value.copy(scanning=false) }
    }
    fun stopScan() {
        handler.removeCallbacks(stop)
        try { scanner?.stopScan(callback) } catch(_:SecurityException) { }
        mutable.value=mutable.value.copy(scanning=false)
    }
    fun connect(address:String) {
        if(mutable.value.testing || mutable.value.identifying || mutable.value.queryingStatus) return
        stopScan();disconnect()
        try { log("Conectando $address");gatt=manager?.adapter?.getRemoteDevice(address)?.connectGatt(context,false,gattCallback,BluetoothDevice.TRANSPORT_LE) }
        catch(e:Exception) { log("Conexão: ${e.message}") }
    }
    private val gattCallback=object:BluetoothGattCallback() {
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,status:Int) { if(g===gatt) complete("descriptor:${d.characteristic.uuid}",status) }
        override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int) { if(g===gatt) complete("write:${c.uuid}",status) }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic) { if(Build.VERSION.SDK_INT<33) notification(g,c,c.value ?: byteArrayOf()) }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray) { notification(g,c,value) }
        override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int) {
            if(g!==gatt) return
            if(status==BluetoothGatt.GATT_SUCCESS && newState==BluetoothProfile.STATE_CONNECTED) {
                mutable.value=mutable.value.copy(connected=true);log("GATT conectado; nome anunciado não comprova modelo")
                if(!g.discoverServices()) log("Não foi possível iniciar descoberta de serviços")
            } else { stopProbe=true;invalidateSession();replies.close(IllegalStateException("GATT desconectado"));mutable.value=mutable.value.copy(connected=false,knownService=false);log("Desconectado, status=$status");completion?.completeExceptionally(IllegalStateException("GATT desconectado"));g.close() }
        }
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {
            if(g!==gatt) return
            log("Descoberta GATT status=$status")
            g.services.forEach { s ->
                log("Serviço ${s.uuid}")
                s.characteristics.forEach { c -> log("  Característica ${c.uuid} propriedades=0x${c.properties.toString(16)}") }
            }
            val known=g.getService(java.util.UUID.fromString(Cameo5Driver.SERVICE_UUID))!=null
            mutable.value=mutable.value.copy(knownService=known)
            log(if(known) "Serviço Silhouette documentado encontrado. Modelo e operação física ainda não verificados." else "Serviço Silhouette documentado ausente.")
        }
    }
    fun disconnect() {
        stopProbe=true;invalidateSession();replies.close(IllegalStateException("Conexão encerrada"))
        try { gatt?.disconnect();gatt?.close() } catch(_:SecurityException) { }
        completion?.completeExceptionally(IllegalStateException("Conexão encerrada"));gatt=null;mutable.value=mutable.value.copy(connected=false,knownService=false,model="",testAttempted=false)
    }
    fun close() { stopScan();disconnect() }
}
