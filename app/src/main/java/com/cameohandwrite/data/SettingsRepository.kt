package com.cameohandwrite.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cameohandwrite.domain.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsStore by preferencesDataStore("cameo_settings")
class SettingsRepository(private val context:Context) {
    private val key=stringPreferencesKey("document")
    var profiles: List<Paper> = listOf(Paper(),Paper(name="Fichário A5",width=148.0,height=210.0,left=22.0,right=10.0,holes=18.0))
        private set
    suspend fun load():Settings {
        val raw=context.settingsStore.data.first()[key] ?: return Settings()
        return decode(raw,validate=false)
    }
    suspend fun save(s:Settings) { context.settingsStore.edit { it[key]=encode(s) } }
    fun saveProfile(p:Paper) { require(p.name.isNotBlank()) {"Dê um nome ao perfil"};p.validate();profiles=profiles.filter { it.name!=p.name }+p }
    private fun paperJson(p:Paper)=JSONObject().apply {
        put("name",p.name);put("width",p.width);put("height",p.height);put("left",p.left);put("right",p.right);put("top",p.top);put("bottom",p.bottom);put("holes",p.holes);put("firstLine",p.firstLine);put("lineSpacing",p.lineSpacing);put("offsetX",p.offsetX);put("offsetY",p.offsetY)
    }
    private fun paper(j:JSONObject,validate:Boolean=true)=Paper(j.getString("name"),j.getDouble("width"),j.getDouble("height"),j.getDouble("left"),j.getDouble("right"),j.getDouble("top"),j.getDouble("bottom"),j.getDouble("holes"),j.getDouble("firstLine"),j.getDouble("lineSpacing"),j.getDouble("offsetX"),j.getDouble("offsetY")).also { if(validate) it.validate() }
    fun encode(s:Settings):String=JSONObject().apply {
        put("version",1);put("text",s.text);put("paper",paperJson(s.paper));put("profiles",JSONArray().apply { profiles.forEach { put(paperJson(it)) } })
        put("style",s.style.name);put("letterHeight",s.letterHeight);put("wordSpace",s.wordSpace);put("irregularity",s.irregularity);put("alignment",s.alignment.name);put("quality",s.quality.name);put("writeSpeed",s.writeSpeed);put("travelSpeed",s.travelSpeed);put("tolerance",s.tolerance);put("preferredDevice",s.preferredDevice);put("seed",s.seed);put("penForce",s.penForce);put("penSpeed",s.penSpeed)
    }.toString(2)
    fun decode(raw:String,validate:Boolean=true,commitProfiles:Boolean=true):Settings {
        require(raw.length<=2000000) { "Arquivo de configuração muito grande" }
        val j=JSONObject(raw);require(j.getInt("version")==1) { "Versão de configuração incompatível" }
        val s=Settings(j.getString("text"),paper(j.getJSONObject("paper"),validate),Style.valueOf(j.getString("style")),j.getDouble("letterHeight"),j.getDouble("wordSpace"),j.getInt("irregularity"),Align.valueOf(j.getString("alignment")),Quality.valueOf(j.getString("quality")),j.getDouble("writeSpeed"),j.getDouble("travelSpeed"),j.getDouble("tolerance"),j.optString("preferredDevice"),j.getInt("seed"),j.optInt("penForce",1),j.optInt("penSpeed",10))
        require(s.penForce in 1..20 && s.penSpeed in 1..30)
        require(s.irregularity in 0..100 && s.tolerance in 0.0..0.5 && s.writeSpeed in 1.0..100.0 && s.travelSpeed in 1.0..200.0)
        val arr=j.getJSONArray("profiles");require(arr.length()<=100)
        val imported=(0 until arr.length()).map { paper(arr.getJSONObject(it)) }
        if(commitProfiles) profiles=imported
        return s
    }
}
