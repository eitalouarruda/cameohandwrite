package com.cameohandwrite

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cameohandwrite.data.SettingsRepository
import com.cameohandwrite.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {
    @Test fun preservesTextAndProfilesWhenDraftPaperIsTemporarilyInvalid() = runBlocking {
        val repo=SettingsRepository(ApplicationProvider.getApplicationContext())
        val original=repo.load()
        try {
            repo.saveProfile(Paper(name="Folha de teste"))
            val draft=Settings(text="Não perder ação e coração",paper=Paper(width=2.0))
            repo.save(draft)
            val reloaded=SettingsRepository(ApplicationProvider.getApplicationContext())
            val restored=reloaded.load()
            assertEquals(draft.text,restored.text)
            assertEquals(2.0,restored.paper.width,0.0)
            assertTrue(reloaded.profiles.any { it.name=="Folha de teste" })
        } finally { repo.save(original) }
    }
    @Test fun exportsAndImportsCalibrationAndPreferences() = runBlocking {
        val repo=SettingsRepository(ApplicationProvider.getApplicationContext())
        val s=Settings(text="Olá España",paper=Paper(offsetX=-0.1,offsetY=0.2),irregularity=72,preferredDevice="00:11:22:33:44:55",style=Style.CURSIVE)
        assertEquals(s,repo.decode(repo.encode(s),commitProfiles=false))
    }
}
