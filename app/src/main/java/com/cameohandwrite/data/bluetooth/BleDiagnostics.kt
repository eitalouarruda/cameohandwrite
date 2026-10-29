@file:Suppress("MissingPermission")
package com.cameohandwrite.data.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cameohandwrite.drivers.cameo.Cameo5Driver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Passive GATT discovery. No guessed characteristics and no motion/init writes. */
class BleDiagnostics(private val context:Context) {
    data class Device(val name:String,val address:String,val rssi:Int)
    data class State(val scanning:Boolean=false,val connected:Boolean=false,val devices:List<Device> = emptyList(),val log:String="Nenhum comando enviado",val knownService:Boolean=false)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var gatt:BluetoothGatt?=null
    private var scanner:BluetoothLeScanner?=null
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
        override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int) {
            if(g!==gatt) return
            if(status==BluetoothGatt.GATT_SUCCESS && newState==BluetoothProfile.STATE_CONNECTED) {
                mutable.value=mutable.value.copy(connected=true);log("GATT conectado; nome anunciado não comprova modelo")
                if(!g.discoverServices()) log("Não foi possível iniciar descoberta de serviços")
            } else { mutable.value=mutable.value.copy(connected=false,knownService=false);log("Desconectado, status=$status");g.close() }
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
        gatt=null;mutable.value=mutable.value.copy(connected=false,knownService=false)
    }
    fun close() { stopScan();disconnect() }
}
