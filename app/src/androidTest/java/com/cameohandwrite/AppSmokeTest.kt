package com.cameohandwrite

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun editorStartsMessyAndFastAndMakesCustomTextReadyForPrinting() {
        compose.onNodeWithText("Cameo Handwrite").assertIsDisplayed()
        compose.onNodeWithText("Feia",substring=true).assertExists()
        compose.onNodeWithText("Rápido",substring=true).assertExists()
        compose.onNodeWithText("Executar bateria completa").assertDoesNotExist()
        compose.onNodeWithText("Comparar 10 / 20 / 30").assertDoesNotExist()
        compose.onNodeWithTag("editorText").performTextClearance()
        compose.waitUntil(15000) {compose.onAllNodes(hasTestTag("printPage") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("editorText").performTextReplacement("Meu texto próprio: ação, café e amanhã.")
        compose.waitUntil(15000) {compose.onAllNodes(hasTestTag("printPage") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("printPage").assertIsEnabled()
        compose.onNodeWithTag("pagePreview").assertExists()
        savePreview("01-editor.png")
        compose.onNodeWithTag("pagePreview").performScrollTo()
        savePreview("02-page-preview.png")
    }
    @Test fun paperAndAppearanceAreAvailableWhileDiagnosticsStayHidden() {
        compose.onNodeWithText("Folha",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Sua folha").assertExists()
        compose.onNodeWithText("Ajustes",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Aparência da escrita").assertExists()
        compose.onNodeWithText("Opções avançadas").assertExists()
        compose.onNodeWithText("Executar bateria completa").assertDoesNotExist()
    }
    private fun savePreview(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.getExternalFilesDir(null),"ui-previews").apply {mkdirs()}
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }

}
