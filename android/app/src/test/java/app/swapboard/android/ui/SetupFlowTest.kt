package app.swapboard.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupFlowTest {
    private val fresh = SetupProgress(
        started = false,
        tailscaleConnected = false,
        paired = false,
        permissionsReviewed = false,
        tileReviewed = false,
    )

    @Test
    fun walksThroughEveryStepInOrder() {
        var p = fresh
        assertEquals(SetupStep.WELCOME, currentSetupStep(p))
        p = p.copy(started = true)
        assertEquals(SetupStep.TAILSCALE, currentSetupStep(p))
        p = p.copy(tailscaleConnected = true)
        assertEquals(SetupStep.PAIR, currentSetupStep(p))
        p = p.copy(paired = true)
        assertEquals(SetupStep.PERMISSIONS, currentSetupStep(p))
        p = p.copy(permissionsReviewed = true)
        assertEquals(SetupStep.TILE, currentSetupStep(p))
        p = p.copy(tileReviewed = true)
        assertEquals(SetupStep.DONE, currentSetupStep(p))
    }

    @Test
    fun skipsTailscaleWhenItIsAlreadyConnected() {
        val p = fresh.copy(started = true, tailscaleConnected = true)
        assertEquals(SetupStep.PAIR, currentSetupStep(p))
    }

    @Test
    fun losingTailscaleBeforePairingGoesBackToTheTailscaleStep() {
        val p = fresh.copy(started = true, tailscaleConnected = false)
        assertEquals(SetupStep.TAILSCALE, currentSetupStep(p))
    }

    @Test
    fun losingTailscaleAfterPairingDoesNotRestartSetup() {
        val p = fresh.copy(started = true, paired = true, tailscaleConnected = false)
        assertEquals(SetupStep.PERMISSIONS, currentSetupStep(p))
    }
}
