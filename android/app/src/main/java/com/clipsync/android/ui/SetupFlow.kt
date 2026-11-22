package com.clipsync.android.ui

/** The first-run setup screens, in order. */
enum class SetupStep { WELCOME, TAILSCALE, PAIR, PERMISSIONS, TILE, DONE }

data class SetupProgress(
    val started: Boolean,
    val tailscaleConnected: Boolean,
    val paired: Boolean,
    val permissionsReviewed: Boolean,
    val tileReviewed: Boolean,
)

/**
 * The screen to show. Each step is skipped once its job is done, so setup picks up where the user
 * left off. Tailscale only matters until the phone is paired: after that, a dropped Tailscale
 * connection is a status problem, not a setup step.
 */
fun currentSetupStep(p: SetupProgress): SetupStep = when {
    !p.started -> SetupStep.WELCOME
    !p.paired && !p.tailscaleConnected -> SetupStep.TAILSCALE
    !p.paired -> SetupStep.PAIR
    !p.permissionsReviewed -> SetupStep.PERMISSIONS
    !p.tileReviewed -> SetupStep.TILE
    else -> SetupStep.DONE
}
