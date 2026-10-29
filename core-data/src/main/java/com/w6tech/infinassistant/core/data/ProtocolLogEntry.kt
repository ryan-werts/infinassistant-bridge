package com.w6tech.infinassistant.core.data

/** Debug protocol log entry (last 50 shown in UI). */
data class ProtocolLogEntry(
    val ts: Long,
    val direction: String,
    val type: String,
    val msgId: Int? = null,
    val summary: String = "",
)
