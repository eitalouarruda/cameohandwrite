package com.cameohandwrite.drivers.cameo

import com.cameohandwrite.domain.Page
import com.cameohandwrite.domain.Paper
import com.cameohandwrite.domain.Pt
import org.junit.Assert.*
import org.junit.Test

class Cameo5DriverTest {
    @Test fun serializesYXMillimetersAndRaisesPenAtEnd() {
        val bytes = Cameo5Driver.encode(Page(listOf(listOf(Pt(25.0,20.0),Pt(26.1,21.2))),1),Paper())
        assertEquals("M400,500\u0003D424,522\u0003M424,522\u0003",bytes.toString(Charsets.US_ASCII))
    }

    @Test fun chunksPreserveCommandsAndPayload() {
        val data = "M400,500\u0003D424,522\u0003M424,522\u0003".toByteArray()
        val chunks = Cameo5Driver.commandChunks(data, 12)
        assertEquals(3,chunks.size)
        assertTrue(chunks.all { it.size <= 12 && it.last() == 3.toByte() })
        assertArrayEquals(data,chunks.fold(byteArrayOf()) { a,b -> a+b })
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMissingTerminator() {
        Cameo5Driver.commandChunks("M400,500".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMarginViolation() {
        Cameo5Driver.encode(Page(listOf(listOf(Pt(24.0,20.0),Pt(26.0,21.0))),1),Paper())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteCoordinates() {
        Cameo5Driver.encode(Page(listOf(listOf(Pt(Double.NaN,20.0),Pt(26.0,21.0))),1),Paper())
    }

    @Test fun distinguishesModelsAndRequiresStrictResponse() {
        assertTrue(Cameo5Driver.identifiesOriginalCameo5("CAMEO 5 V1.02\u0003"))
        assertFalse(Cameo5Driver.identifiesOriginalCameo5("CAMEO 5 ALPHA V1.02"))
        assertFalse(Cameo5Driver.identifiesOriginalCameo5("CAMEO 5 PLUS"))
        assertFalse(Cameo5Driver.identifiesOriginalCameo5("Unknown CAMEO 5"))
        assertEquals(Cameo5Driver.Status.READY,Cameo5Driver.decodeStatus("0\u0003"))
        assertEquals(Cameo5Driver.Status.UNKNOWN,Cameo5Driver.decodeStatus(null))
        assertArrayEquals(byteArrayOf(0x46,0x47,3),Cameo5Driver.firmwareQuery)
        assertArrayEquals(byteArrayOf(0x1b,5),Cameo5Driver.statusQuery)
    }
}
