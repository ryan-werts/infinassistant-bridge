package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.CalendarEvent
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * Feeds the calendar app on the watch (HaCalendar).
 *
 * The watch shows the entries the phone last sent, with the day they are for as the header. It asks
 * for a day with CALENDAR_NAV (0x14, {"day"}) when the user swipes (and, from firmware 2.4.89, when
 * the app opens); the reply is a CALENDAR_UPDATE (0x13) for that day. Today's entries are also sent
 * unasked on every connection and after a layout push (sendToday), so the app opens with data.
 */
class CalendarOrchestrator(
    private val logger: Logger,
    private val haApi: HaApi,
    private val protocolHandler: ProtocolHandler,
    private val layoutProvider: () -> WatchFaceLayoutPayload?,
    private val logCallback: (direction: String, type: String, msgId: Int?, summary: String) -> Unit,
) {
    private val tag = "CalendarOrchestrator"

    /** The watch asked for a day: answer for that day, empty when no calendars are configured. */
    fun handleCalendarNav(
        request: CalendarNavPayload,
        transport: BleTransport,
        sessionScope: CoroutineScope,
    ) {
        sendDay(request.dayOffset, transport, sessionScope)
    }

    /** Today's entries, unasked. Nothing is sent when no calendars are configured. */
    fun sendToday(transport: BleTransport, sessionScope: CoroutineScope) {
        if (layoutProvider()?.calendarEntityIds.isNullOrEmpty()) return
        sendDay(0, transport, sessionScope)
    }

    private fun sendDay(dayOffset: Int, transport: BleTransport, sessionScope: CoroutineScope) {
        // The watch keeps the day in an int8.
        val day = dayOffset.coerceIn(-127, 127)
        sessionScope.launch {
            try {
                val payload = WireFrame.fit(collectEntries(day), { ProtocolCbor.encode(CalendarUpdatePayload.serializer(), it) }) {
                    CalendarUpdatePayload(day = day, entries = it)
                }
                protocolHandler.sendToWatch(
                    transport, Protocol.Type.CALENDAR_UPDATE, protocolHandler.nextMsgId(), payload,
                )
                logCallback("calendar", "CALENDAR_UPDATE sent", null, "day=$day, ${payload.entries.size} events")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e(tag, "Calendar update for day $day failed: ${e.message}", e)
            }
        }
    }

    /**
     * The entries for [dayOffset] days from today, across every configured calendar (fetched in
     * parallel): all-day events first, then by start time; at most [MAX_ENTRIES], each cut to the
     * watch's buffers. Today starts from now, so events that are over drop off. The send keeps as
     * many as fit one BLE frame ([WireFrame]).
     */
    internal suspend fun collectEntries(dayOffset: Int): List<CalendarEntryPayload> {
        val entityIds = layoutProvider()?.calendarEntityIds.orEmpty()
        if (entityIds.isEmpty()) return emptyList()
        val date = LocalDate.now().plusDays(dayOffset.toLong())
        val from = if (dayOffset == 0) Instant.now() else null
        val events: List<CalendarEvent> = coroutineScope {
            entityIds.map { entityId ->
                async {
                    when (val result = haApi.fetchCalendarDay(entityId, date, from)) {
                        is AppResult.Ok -> result.value
                        is AppResult.Err -> {
                            logger.w(tag, "Failed to fetch events for $entityId: ${result.error.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        return toEntries(events)
    }

    companion object {
        /** HomeAssistantService::kMaxCalendarEntries (5 from firmware 2.4.91; 3 before, which keeps the first 3). */
        const val MAX_ENTRIES = 5
        /** CalendarAppEntry::time is char[6]. */
        const val MAX_TIME_BYTES = 5
        /** CalendarAppEntry::title is char[48]. */
        const val MAX_TITLE_BYTES = 47

        internal fun toEntries(events: List<CalendarEvent>): List<CalendarEntryPayload> =
            events
                .sortedWith(compareBy<CalendarEvent>({ !it.allDay }, { it.startInstant }, { it.title }))
                .take(MAX_ENTRIES)
                .map {
                    CalendarEntryPayload(
                        time = WireText.fit(it.time, MAX_TIME_BYTES),
                        title = WireText.fit(it.title.trim(), MAX_TITLE_BYTES),
                    )
                }
    }
}
