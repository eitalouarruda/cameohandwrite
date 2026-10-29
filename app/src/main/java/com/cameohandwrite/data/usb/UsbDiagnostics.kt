package com.cameohandwrite.data.usb

import android.content.Context
import android.hardware.usb.UsbManager
import com.cameohandwrite.drivers.cameo.Cameo5Driver

object UsbDiagnostics {
    fun inspect(context:Context):String {
        val manager=context.getSystemService(UsbManager::class.java)
        val devices=manager.deviceList.values
        if(devices.isEmpty()) return "Nenhum dispositivo USB OTG detectado. Conecte o cabo/adaptador e tente novamente."
        return devices.joinToString("\n") { d -> buildString {
            append("USB VID=%04x PID=%04x".format(d.vendorId,d.productId))
            if(d.vendorId==Cameo5Driver.USB_VENDOR_ID && d.productId==Cameo5Driver.USB_PRODUCT_ID) append(" — ID Cameo 5 documentado")
            append("; permissão=${manager.hasPermission(d)}")
            for(i in 0 until d.interfaceCount) {
                val intf=d.getInterface(i);append("\n Interface $i classe=${intf.interfaceClass}")
                for(j in 0 until intf.endpointCount) { val e=intf.getEndpoint(j);append("\n Endpoint ${e.address} tipo=${e.type} direção=${e.direction} pacote=${e.maxPacketSize}") }
            }
        } }
    }
}
