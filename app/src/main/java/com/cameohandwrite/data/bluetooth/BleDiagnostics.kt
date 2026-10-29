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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Passive GATT discovery. No guessed characteristics and no motion/init writes. */
class BleDiagnostics(private val context:Context) {
    data class Device(val name:String,val address:String,val rssi:Int)
    data class State(val scanning:Boolean=false,val connected:Boolean=false,val devices:List<Device> = emptyList(),val log:String="Nenhum comando enviado",val knownService:Boolean=false,val identifying:Boolean=false,val model:String="")
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var gatt:BluetoothGatt?=null
    private var scanner:BluetoothLeScanner?=null
    private val operationLock=Mutex()
    private var completion:CompletableDeferred<Int>?=null
    private var expectedOperation:String?=null
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
        log("RX ${c.uuid}: "+value.joinToString(" ") { "%02x".format(it.toInt() and 255) })
        if(c.uuid==readId && mutable.value.identifying) replies.trySend(value.copyOf())
    }
    /** Explicit opt-in only: this handshake resets protocol state. Never run during another job. */
    suspend fun identify() = operationLock.withLock {
        val g=gatt ?: error("Conecte primeiro")
        val service=g.getService(UUID.fromString(Cameo5Driver.SERVICE_UUID)) ?: error("Serviço documentado ausente")
        val control=service.getCharacteristic(UUID.fromString(Cameo5Driver.CONTROL_UUID)) ?: error("Controle ausente")
        val read=service.getCharacteristic(readId) ?: error("Resposta ausente")
        val write=service.getCharacteristic(UUID.fromString(Cameo5Driver.WRITE_UUID)) ?: error("Escrita ausente")
        listOf(control,read,write).forEach { check(it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "Característica não aceita escrita confirmada" } }
        replies.close();replies=Channel(Channel.UNLIMITED)
        mutable.value=mutable.value.copy(identifying=true,model="")
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
            while(replies.tryReceive().isSuccess) { }
            writeConfirmed(g,write,Cameo5Driver.firmwareQuery)
            val response=withTimeout(8000) {
                val bytes=mutableListOf<Byte>()
                while(3.toByte() !in bytes) { bytes.addAll(replies.receive().toList());check(bytes.size<=512) { "Resposta de modelo excessiva" } }
                bytes.takeWhile { it!=3.toByte() }.toByteArray().toString(Charsets.US_ASCII)
            }
            mutable.value=mutable.value.copy(model=response)
            log("FG: $response; Cameo 5 original reconhecida=${Cameo5Driver.identifiesOriginalCameo5(response)}. Nenhum movimento enviado.")
        } finally { mutable.value=mutable.value.copy(identifying=false) }
    }
    private suspend fun writeConfirmed(g:BluetoothGatt,c:BluetoothGattCharacteristic,bytes:ByteArray) {
        operation("write:${c.uuid}") {
            if(Build.VERSION.SDK_INT>=33) g.writeCharacteristic(c,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") c.value=bytes;c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
        }
        log("TX ${c.uuid}: "+bytes.joinToString(" ") { "%02x".format(it.toInt() and 255) })
    }
    private val stop=Runnable { stopScan() }
    private fun log(message:String) { synchronized(this) { mutable.value=mutable.value.copy(log=(mutable.value.log+"\n"+message).takeLast(16000)) } }
    private val callback=object:ScanCallback() {
        override fun onScanResult(callbackType:Int,result:ScanResult) {
            val device=Device(result.scanRecord?.deviceName ?: result.device.name ?: "BLE sem nome",result.device.address,result.rssi)
            synchronized(this@BleDiagnostics) { mutable.value=mutable.value.copy(devices=(mutable.value.devices.filter { it.address!=device.address }+device).sortedByDescending { it.rssi }) }
        }
        override fun onScanFailed(errorCode:Int) { mutable.value=mutable.value.copy(scanning=false);log("Falha na busca BLE: $errorCode") }
    }
    fun scan() {
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
            } else { mutable.value=mutable.value.copy(connected=false,knownService=false);log("Desconectado, status=$status");completion?.completeExceptionally(IllegalStateException("GATT desconectado"));g.close() }
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
        try { gatt?.disconnect();gatt?.close() } catch(_:SecurityException) { }
        completion?.completeExceptionally(IllegalStateException("Conexão encerrada"));gatt=null;mutable.value=mutable.value.copy(connected=false,knownService=false,model="")
    }
    fun close() { stopScan();disconnect() }
}
