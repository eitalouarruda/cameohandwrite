package com.cameohandwrite.domain.plotting

/** Motion transport contract. Real hardware requires verified setup, origin and flow control. */
interface PlotterTransport {
    suspend fun write(bytes:ByteArray)
    suspend fun awaitReady()
    fun close()
}
/** Offline command recorder for integration tests and command inspection. */
class RecordingTransport:PlotterTransport {
    val blocks=mutableListOf<ByteArray>()
    private var closed=false
    override suspend fun write(bytes:ByteArray) { check(!closed);blocks.add(bytes.copyOf()) }
    override suspend fun awaitReady() { check(!closed) }
    override fun close() { closed=true }
}
