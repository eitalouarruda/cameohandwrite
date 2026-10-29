package com.cameohandwrite.drivers.cameo

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Fixed, absolute GPGL pen-up moves near the loaded origin. No user coordinates or D/reset commands.
 * A READY response reports device state, not proof that a carriage moved: the operator must observe it.
 */
object CameoMotionProbe {
    interface Link {
        suspend fun status(): Cameo5Driver.Status
        suspend fun write(command: ByteArray)
    }
    // Coordinates are Y,X, 20 units/mm: (1,1) -> (2,1) -> (1,1) mm.
    val commands get() = listOf("M20,20\u0003", "M20,40\u0003", "M20,20\u0003").map { it.toByteArray(Charsets.US_ASCII) }

    /** No command retries or automatic resumption: an uncertain write may already be executing. */
    suspend fun run(
        model: String,
        link: Link,
        stopRequested: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
        pollMillis: Long = 200,
        readyTimeoutMillis: Long = 15000
    ): Boolean {
        require(Cameo5Driver.identifiesOriginalCameo5(model)) { "Identifique uma Cameo 5 original nesta conexão antes do teste" }
        require(pollMillis > 0 && readyTimeoutMillis > 0)
        if(stopRequested()) return false
        check(link.status() == Cameo5Driver.Status.READY) { "Teste recusado: máquina não está pronta ou base não está carregada" }
        for((index, command) in commands.withIndex()) {
            if(stopRequested()) return false
            // Recheck before each move so unloading/other device activity cannot be ignored.
            check(link.status() == Cameo5Driver.Status.READY) { "Estado mudou antes do movimento; teste interrompido" }
            if(stopRequested()) return false
            link.write(command)
            withTimeout(readyTimeoutMillis) {
                delay(pollMillis)
                while(true) {
                    when(link.status()) {
                        Cameo5Driver.Status.READY -> break
                        Cameo5Driver.Status.MOVING -> delay(pollMillis)
                        Cameo5Driver.Status.UNLOADED -> error("Base descarregada durante o teste")
                        Cameo5Driver.Status.UNKNOWN -> error("Estado desconhecido; nenhum próximo movimento será enviado")
                    }
                }
            }
            onProgress(index + 1)
        }
        return !stopRequested()
    }
}
