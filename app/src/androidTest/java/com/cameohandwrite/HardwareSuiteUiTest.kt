package com.cameohandwrite

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cameohandwrite.data.bluetooth.BleDiagnostics
import com.cameohandwrite.domain.*
import com.cameohandwrite.drivers.cameo.*
import com.cameohandwrite.ui.plotter.HardwareSuiteControls
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareSuiteUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun oneStartRequiresModelReadyAndPenPreparationAndCannotStartTwiceWhileRunning() {
        val state=mutableStateOf(BleDiagnostics.State())
        val suite=HardwareSuite(Paper(),listOf(HardwareStage("Traço","5 mm",Page(listOf(listOf(Pt(30.0,20.0),Pt(35.0,20.0))),1))))
        var calls=0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            HardwareSuiteControls(state.value,suite,null,null,{calls++},{},{},{},{},{},{})
        } } }
        compose.onNodeWithText("Executar bateria completa").assertIsNotEnabled()
        compose.runOnIdle {state.value=state.value.copy(connected=true,model="CAMEO 5 V1.04")}
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onNodeWithText("Executar bateria completa").assertIsNotEnabled()
        compose.runOnIdle {state.value=state.value.copy(readyForProbe=true)}
        compose.onNodeWithText("Executar bateria completa").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(1,calls);state.value=state.value.copy(testing=true,suiteRunning=true)}
        compose.onNodeWithText("Executar bateria completa").assertIsNotEnabled()
        compose.onNodeWithText("Pausar após bloco").assertExists()
        compose.onNodeWithText("Interromper bateria").assertExists()
    }
}
