package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.Page
import com.cameohandwrite.domain.Paper
import java.util.Locale
import kotlin.math.roundToInt

/** Documented GPGL geometry serialization; physical operation is NOT validated by this app.
 * Source: inkscape-silhouette revision f376a083, silhouette/Graphtec.py and BLETransport.py.
 * This class deliberately does not initialize, configure tools, or transmit to hardware.
 */
object Cameo5Driver {
    const val SERVICE_UUID = "e2088282-4fde-42f9-bb22-6ec3c7ed8f91"
    const val WRITE_UUID = "6d92661d-f429-4d67-929b-28e7a9780912"
    const val READ_UUID = "8dcf199a-30e7-4bd4-beb6-beb57dca866c"
    const val CONTROL_UUID = "61490654-b5b4-458c-a867-9e15bc1471e0"
    const val USB_VENDOR_ID = 0x0b4d
    const val USB_PRODUCT_ID = 0x1140
    const val ETX: Char = '\u0003'
    val firmwareQuery get() = byteArrayOf(0x46, 0x47, 0x03) // FG + ETX
    val statusQuery get() = byteArrayOf(0x1b, 0x05) // ESC ENQ, no ETX

    enum class Status { READY, MOVING, UNLOADED, UNKNOWN }
    fun decodeStatus(response: String?): Status = when (response?.trim()?.trimEnd(ETX)?.trim()) {
        "0" -> Status.READY
        "1" -> Status.MOVING
        "2" -> Status.UNLOADED
        else -> Status.UNKNOWN
    }

    /** Advertised names are hints; pass the actual FG response here, not the scan name. */
    fun identifiesOriginalCameo5(firmwareResponse: String): Boolean {
        val model = firmwareResponse.trim().trimEnd(ETX).trim().uppercase(Locale.ROOT)
        return Regex("^CAMEO[ _]5(?:[ _]+V[0-9]+(?:\\.[0-9]+)*)?$").matches(model)
    }

    /** Serializes already calibrated preview geometry; do not apply calibration a second time. */
    fun encode(page: Page, paper: Paper): ByteArray {
        paper.validate()
        val result = StringBuilder()
        fun command(letter: Char, x: Double, y: Double) {
            require(x.isFinite() && y.isFinite()) { "Coordenada não finita" }
            require(x >= paper.startX - 0.001 && x <= paper.width - paper.right + 0.001 &&
                y >= paper.top && y <= paper.height - paper.bottom) {
                "Trajeto fora da área útil: $x,$y mm"
            }
            // GPGL encodes Y first, then X, at 20 Silhouette units per millimeter.
            result.append(letter).append((y * 20).roundToInt()).append(',')
                .append((x * 20).roundToInt()).append(ETX)
        }
        for (stroke in page.strokes) {
            if (stroke.size < 2) continue
            command('M', stroke.first().x, stroke.first().y)
            for (point in stroke.drop(1)) command('D', point.x, point.y)
            // Explicitly raise at the final point, without a return-to-origin movement.
            command('M', stroke.last().x, stroke.last().y)
        }
        return result.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Device-buffer blocks: preserve whole commands. Transport must split further into ATT writes.
     * Await GPGL READY after each block; ACK from GATT alone is not physical completion.
     */
    fun commandChunks(payload: ByteArray, maxBytes: Int = 1024): List<ByteArray> {
        require(maxBytes in 1..1024)
        if (payload.isEmpty()) return emptyList()
        require(payload.last() == ETX.code.toByte()) { "Comando sem terminador ETX" }
        val chunks = mutableListOf<ByteArray>()
        var start = 0
        while (start < payload.size) {
            var end = minOf(start + maxBytes, payload.size)
            while (end > start && payload[end - 1] != ETX.code.toByte()) end--
            require(end > start) { "Comando excede o bloco permitido" }
            chunks.add(payload.copyOfRange(start, end))
            start = end
        }
        return chunks
    }
}
