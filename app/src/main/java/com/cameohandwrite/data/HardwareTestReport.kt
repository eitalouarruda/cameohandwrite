package com.cameohandwrite.data

import android.content.Context
import android.util.AtomicFile
import com.cameohandwrite.domain.*
import com.cameohandwrite.drivers.cameo.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// Device acknowledgements and the operator's observations are recorded separately.
data class StageReport(val title:String,val expected:String,val execution:String="Pendente",val observation:String="Não avaliado")
data class HardwareTestReport(
    val startedAt:String,val model:String,val paper:Paper,val setup:PenSetup,
    val stages:List<StageReport>,val outcome:String="Em execução",val events:List<String> = emptyList(),
    val notes:String="",val squareWidth:String="",val squareHeight:String="",val errorX:String="",val errorY:String="",
    val elapsedMillis:Long?=null,val gattWrites:Int=0,val statusQueries:Int=0,val streamBlocks:Int=0,
    val sendMode:String="Blocos de até 512 bytes; ATT confirmado de 20 bytes"
) {
    companion object {
        fun start(model:String,suite:HardwareSuite,setup:PenSetup)=HardwareTestReport(Instant.now().toString(),model,suite.paper,setup,suite.stages.map { StageReport(it.title,it.expected) })
        fun decode(raw:String):HardwareTestReport {
            require(raw.length<=10000000)
            val j=JSONObject(raw);require(j.getInt("version")==1)
            val p=j.getJSONObject("paper")
            val paper=Paper(p.getString("name"),p.getDouble("width"),p.getDouble("height"),p.getDouble("left"),p.getDouble("right"),p.getDouble("top"),p.getDouble("bottom"),p.getDouble("holes"),p.getDouble("firstLine"),p.getDouble("lineSpacing"),p.getDouble("offsetX"),p.getDouble("offsetY"))
            val array=j.getJSONArray("stages");val events=j.getJSONArray("events")
            return HardwareTestReport(j.getString("startedAt"),j.getString("model"),paper,PenSetup(j.getInt("force"),j.getInt("speed")),(0 until array.length()).map { val s=array.getJSONObject(it);StageReport(s.getString("title"),s.getString("expected"),s.getString("execution"),s.getString("observation")) },j.getString("outcome"),(0 until events.length()).map { events.getString(it) },j.optString("notes"),j.optString("squareWidth"),j.optString("squareHeight"),j.optString("errorX"),j.optString("errorY"),j.optLong("elapsedMillis",-1).takeIf { it>=0 },j.optInt("gattWrites"),j.optInt("statusQueries"),j.optInt("streamBlocks"),j.optString("sendMode","Por segmento (versão anterior)"))
        }
    }
    fun encode():String=JSONObject().apply {
        put("elapsedMillis",elapsedMillis ?: JSONObject.NULL);put("gattWrites",gattWrites);put("statusQueries",statusQueries);put("streamBlocks",streamBlocks);put("sendMode",sendMode)
        put("version",1);put("startedAt",startedAt);put("model",model);put("force",setup.force);put("speed",setup.speed);put("outcome",outcome)
        put("paper",JSONObject().apply {
            put("name",paper.name);put("width",paper.width);put("height",paper.height);put("left",paper.left);put("right",paper.right);put("top",paper.top);put("bottom",paper.bottom);put("holes",paper.holes);put("firstLine",paper.firstLine);put("lineSpacing",paper.lineSpacing);put("offsetX",paper.offsetX);put("offsetY",paper.offsetY)
        })
        put("stages",JSONArray().apply { stages.forEach { s -> put(JSONObject().apply { put("title",s.title);put("expected",s.expected);put("execution",s.execution);put("observation",s.observation) }) } })
        put("events",JSONArray(events));put("notes",notes);put("squareWidth",squareWidth);put("squareHeight",squareHeight);put("errorX",errorX);put("errorY",errorY)
        put("notice","Confirmação GATT e estado pronto não comprovam marca física. Consulte as observações do operador.")
    }.toString(2)
}
class HardwareReportStore(context:Context) {
    private val file=AtomicFile(java.io.File(context.filesDir,"hardware-test-report.json"))
    @Synchronized fun save(report:HardwareTestReport) {
        val stream=file.startWrite()
        try { stream.write(report.encode().toByteArray(Charsets.UTF_8));file.finishWrite(stream) }
        catch(e:Exception) { file.failWrite(stream);throw e }
    }
    @Synchronized fun load():HardwareTestReport? {
        if(!file.baseFile.exists()) return null
        val report=HardwareTestReport.decode(file.openRead().bufferedReader().use { it.readText() })
        return if(report.outcome=="Em execução") report.copy(outcome="Execução interrompida pelo fechamento do aplicativo; confirme a máquina",stages=report.stages.map { if(it.execution=="Em execução") it.copy(execution="Interrompido; verificar fisicamente") else it }) else report
    }
}
