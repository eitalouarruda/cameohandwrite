package com.cameohandwrite

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cameohandwrite.data.*
import com.cameohandwrite.domain.*
import com.cameohandwrite.drivers.cameo.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareReportTest {
    @Test fun reportRoundTripPreservesMeasurementsObservationsAndCommands() {
        val r=HardwareTestReport("2026-10-29T00:00:00Z","CAMEO 5 V1.04",Paper(offsetX=-0.1),PenSetup(),listOf(StageReport("Escala","10 mm","Envio concluído","Problema",speed=20,elapsedMillis=1234)),outcome="Avaliação pendente",events=listOf("TX D400,600"),squareWidth="9,8",squareHeight="10",errorX="-0,1",notes="Não levantou no intervalo",elapsedMillis=1250,gattWrites=48,statusQueries=8,streamBlocks=4,comparison=true,gattWriteMillis=200,statusWaitMillis=300,streamBytes=1000)
        assertEquals(r,HardwareTestReport.decode(r.encode()))
        assertTrue(r.encode().contains("Não levantou"))
    }
    @Test fun oldReportWithoutMetricsStillLoads() {
        val original=HardwareTestReport("then","CAMEO 5 V1.04",Paper(),PenSetup(1,10),listOf(StageReport("Traço","5 mm",observation="OK")))
        val json=org.json.JSONObject(original.encode())
        for(key in listOf("elapsedMillis","gattWrites","statusQueries","streamBlocks","sendMode")) json.remove(key)
        val restored=HardwareTestReport.decode(json.toString())
        assertNull(restored.elapsedMillis);assertEquals(0,restored.gattWrites)
        assertEquals("Por segmento (versão anterior)",restored.sendMode)
        assertEquals("OK",restored.stages.single().observation)
    }
    @Test fun interruptedReportIsRecoveredWithoutClaimingCompletion() {
        val store=HardwareReportStore(ApplicationProvider.getApplicationContext())
        store.save(HardwareTestReport("now","CAMEO 5 V1.04",Paper(),PenSetup(),listOf(StageReport("Traço","5 mm","Em execução"))))
        val report=store.load()!!
        assertTrue(report.outcome.contains("interrompida"))
        assertTrue(report.stages.single().execution.contains("Interrompido"))
        assertEquals("Não avaliado",report.stages.single().observation)
    }
}
