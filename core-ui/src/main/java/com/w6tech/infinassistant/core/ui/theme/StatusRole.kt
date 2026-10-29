package com.w6tech.infinassistant.core.ui.theme

/** Semantic status of a piece of UI, independent of the theme that colours it. */
enum class StatusRole { Success, Warning, Danger }

/**
 * Connection status: everything connected and the watch responsive is success; the watch
 * connected over BLE but not responding is warning; anything else is danger.
 */
fun connectionStatusRole(
    bleConnected: Boolean,
    haConnected: Boolean,
    watchResponsive: Boolean,
): StatusRole = when {
    bleConnected && haConnected && watchResponsive -> StatusRole.Success
    bleConnected && !watchResponsive -> StatusRole.Warning
    else -> StatusRole.Danger
}

/** Heap pool occupancy: under 50 percent is success, 50 to 80 inclusive warning, above 80 danger. */
fun heapPoolStatusRole(poolPercent: Int): StatusRole = when {
    poolPercent < 50 -> StatusRole.Success
    poolPercent <= 80 -> StatusRole.Warning
    else -> StatusRole.Danger
}
