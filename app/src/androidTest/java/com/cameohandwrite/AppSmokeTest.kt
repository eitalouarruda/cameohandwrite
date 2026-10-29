package com.cameohandwrite

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun opensEditorRendersDocumentAndNavigatesOffline() {
        compose.onNodeWithText("Cameo Handwrite").assertIsDisplayed()
        compose.onNodeWithText("Seu texto").assertExists()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Abrir prévia e exportar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Abrir prévia e exportar").performScrollTo().performClick()
        compose.onNodeWithText("Prévia física").assertExists()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Exportar esta página em SVG").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Folha",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Folha e pautas físicas").assertExists()
        compose.onNodeWithText("Ajustes",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Configurações locais").assertExists()
    }
}
