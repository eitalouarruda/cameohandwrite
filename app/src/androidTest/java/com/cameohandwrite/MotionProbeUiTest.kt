package com.cameohandwrite

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cameohandwrite.data.bluetooth.BleDiagnostics
import com.cameohandwrite.ui.plotter.MotionProbeControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionProbeUiTest {
    @get:Rule val compose=createComposeRule()
    private val button="Testar movimento de 1 mm sem ferramenta"
    @Test fun requiresIdentificationReadyStatusAndEveryChecklistItem() {
        val state=mutableStateOf(BleDiagnostics.State())
        var tests=0
        compose.setContent { MaterialTheme { MotionProbeControls(state.value,{}, {tests++},{}) } }
        compose.onNodeWithText(button).assertIsNotEnabled()
        compose.runOnIdle { state.value=state.value.copy(connected=true,model="CAMEO 5 V1.04") }
        compose.onAllNodes(isToggleable()).apply { for(i in 0..2) get(i).performClick() }
        compose.onNodeWithText(button).assertIsNotEnabled()
        compose.runOnIdle { state.value=state.value.copy(readyForProbe=true) }
        compose.onNodeWithText(button).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1,tests);state.value=state.value.copy(testAttempted=true,testing=true) }
        compose.onNodeWithText(button).assertIsNotEnabled()
        compose.onNodeWithText("Interromper próximas etapas").assertExists()
    }
    @Test fun alphaModelDoesNotUnlockMotion() {
        compose.setContent { MaterialTheme { MotionProbeControls(BleDiagnostics.State(connected=true,model="CAMEO 5 ALPHA",readyForProbe=true),{},{},{}) } }
        compose.onNodeWithText(button).assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).apply { for(i in 0..2) get(i).assertIsNotEnabled() }
    }
}
