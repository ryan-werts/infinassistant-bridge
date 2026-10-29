package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.data.CalendarEvent
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * CALENDAR_UPDATE, CALENDAR_NAV and HOME_CONTROL_UPDATE against what the firmware reads
 * (BridgeController::HandleCalendarUpdate, HandleHomeControlUpdate, SendCalendarNav). Until
 * 2.1.96 the app had no encoder branch for either update, and its keys ("d", "e", "x") were none of
 * the keys the watch reads, so both watch apps stayed empty.
 */
class CalendarHomeControlWireTest {

    private val silentLogger = WireTestSupport.silentLogger
    private val handler = ProtocolHandler(silentLogger, StateManager()) { _, _, _, _ -> }

    private class Recorder {
        private val inner = WireTestSupport.Recorder()
        val sent get() = inner.sent
        val transport get() = inner.transport
    }

    private fun decodeMap(bytes: ByteArray): Map<String, Any?> = WireTestSupport.decodeMap(bytes)

    // ── CALENDAR_UPDATE ──

    @Test
    fun `a calendar update carries the keys the watch reads`() = runTest {
        val watch = Recorder()
        handler.sendToWatch(watch.transport, Protocol.Type.CALENDAR_UPDATE, 7,
            CalendarUpdatePayload(day = 1, entries = listOf(CalendarEntryPayload(time = "09:30", title = "Standup"))))

        val (type, bytes) = watch.sent.single()
        assertEquals(Protocol.Type.CALENDAR_UPDATE.wire, type)
        val map = decodeMap(bytes)
        assertEquals(setOf("day", "entries"), map.keys)
        assertEquals(1L, map["day"])
        @Suppress("UNCHECKED_CAST")
        val entry = (map["entries"] as List<Map<String, Any?>>).single()
        assertEquals(mapOf("t" to "09:30", "n" to "Standup"), entry)
    }

    @Test
    fun `today leaves the day off the wire, which the watch reads as 0`() = runTest {
        val watch = Recorder()
        handler.sendToWatch(watch.transport, Protocol.Type.CALENDAR_UPDATE, 7, CalendarUpdatePayload(day = 0, entries = emptyList()))

        val map = decodeMap(watch.sent.single().second)
        assertEquals(setOf("entries"), map.keys)
        assertEquals(emptyList<Any?>(), map["entries"])
    }

    @Test
    fun `a negative day goes out as a CBOR negative integer`() = runTest {
        val watch = Recorder()
        handler.sendToWatch(watch.transport, Protocol.Type.CALENDAR_UPDATE, 7, CalendarUpdatePayload(day = -2, entries = emptyList()))
        assertEquals(-2L, decodeMap(watch.sent.single().second)["day"])
    }

    // ── CALENDAR_NAV, from the firmware's bytes ──

    /** {"day": n} as TinyCBOR writes it in SendCalendarNav: a definite map of one pair. */
    private fun navBytes(day: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xA1)
        out.write(0x63); out.write("day".toByteArray())
        if (day >= 0) out.write(day) else out.write(0x20 or (-1 - day))
        return out.toByteArray()
    }

    @Test
    fun `CALENDAR_NAV decodes the day the watch sends`() {
        assertEquals(1, ProtocolCbor.decode(CalendarNavPayload.serializer(), navBytes(1)).dayOffset)
        assertEquals(-1, ProtocolCbor.decode(CalendarNavPayload.serializer(), navBytes(-1)).dayOffset)
        assertEquals(0, ProtocolCbor.decode(CalendarNavPayload.serializer(), navBytes(0)).dayOffset)
    }

    // ── HOME_CONTROL_UPDATE ──

    @Test
    fun `a home control update carries the keys the watch reads`() = runTest {
        val watch = Recorder()
        handler.sendToWatch(watch.transport, Protocol.Type.HOME_CONTROL_UPDATE, 9, HomeControlUpdatePayload(listOf(
            HomeControlDevicePayload(label = "Kitchen", entityId = "light.kitchen", state = "on", active = true))))

        val (type, bytes) = watch.sent.single()
        assertEquals(Protocol.Type.HOME_CONTROL_UPDATE.wire, type)
        val map = decodeMap(bytes)
        assertEquals(setOf("devices"), map.keys)
        @Suppress("UNCHECKED_CAST")
        val device = (map["devices"] as List<Map<String, Any?>>).single()
        assertEquals(mapOf("l" to "Kitchen", "e" to "light.kitchen", "s" to "on", "a" to true), device)
    }

    @Test
    fun `devices are cut to the watch's limits and a too-long entity ID is skipped`() {
        val longId = "light." + "x".repeat(60)   // 66 bytes: the watch keeps 63
        val config = WatchHomeControlConfig(listOf(
            WatchHomeControlDevice(entityId = longId, name = "Too long"),
            WatchHomeControlDevice(entityId = "light.kitchen_main_lights", name = null),
            WatchHomeControlDevice(entityId = "media_player.living_room", name = "Living room television set"),
            WatchHomeControlDevice(entityId = "lock.front"),
        ))
        val devices = HomeControlOrchestrator.toDevices(config.devices, mapOf(
            "light.kitchen_main_lights" to "on",
            "media_player.living_room" to "playing_and_buffering_now",
        ))

        assertEquals(listOf("light.kitchen_main_lights", "media_player.living_room", "lock.front"), devices.map { it.entityId })
        assertEquals("kitchen main lights", devices[0].label)
        assertTrue(devices[0].active)
        assertEquals("Living room televis", devices[1].label)   // 19 bytes
        assertEquals("playing_and_buf", devices[1].state)   // 15 bytes
        assertTrue(!devices[1].active)
        assertEquals("unavailable", devices[2].state)   // no state from HA
    }

    // ── Home control defaults ──

    private fun tile(label: String, entityId: String) = WatchTile(label = label, entityId = entityId, color = "#FFFFFF")

    private val faceWithTiles = WatchFaceLayoutPayload(
        version = 1,
        header = WatchHeader(timeFormat = "HH:mm"),
        screens = listOf(
            WatchScreen(screenType = 0),
            WatchScreen(screenType = 1, tiles = listOf(
                tile("GARAGE", "cover.garage_door"), tile("L1", ""), tile("DINING", "light.dining_room_chandelier"))),
            WatchScreen(screenType = 1, tiles = listOf(
                tile("FRONT", "lock.front_door"), tile("PL TBL", "switch.sonoff_zbminil2"),
                tile("DINING", "light.dining_room_chandelier"), tile("M FAN", "fan.master_bed"),
                tile("SCENE", "scene.movie"), tile("GAR LGHT", "light.garage_lights"))),
        ),
    )

    @Test
    fun `with no devices of its own, home control shows the face's lights, switches and fans`() {
        val devices = HomeControlOrchestrator.appDevices(faceWithTiles)
        // Covers, locks and scenes stay off; each entity once; in face order; the tile's label.
        assertEquals(listOf("light.dining_room_chandelier", "switch.sonoff_zbminil2", "fan.master_bed", "light.garage_lights"),
            devices.map { it.entityId })
        assertEquals(listOf("DINING", "PL TBL", "M FAN", "GAR LGHT"), devices.map { it.name })

        val own = faceWithTiles.copy(homeControlConfig = WatchHomeControlConfig(listOf(WatchHomeControlDevice("lock.front_door", "Front"))))
        assertEquals(listOf("lock.front_door"), HomeControlOrchestrator.appDevices(own).map { it.entityId })
    }

    @Test
    fun `a device's state change pushes the devices once, a second later`() = runTest {
        val orchestrator = HomeControlOrchestrator(silentLogger, handler, { faceWithTiles }) { _, _, _, _ -> }
        val watch = Recorder()
        // HA as the bridge's state cache follows it: the face's tiles are watched.
        val ha = WireTestSupport.FakeHa(listOf(
            HaState(entityId = "cover.garage_door", state = "closed"),
            HaState(entityId = "light.dining_room_chandelier", state = "off"),
        ))
        ha.connect(this, handler.stateCache, { HaStateCache.watchedEntities(emptyList(), faceWithTiles) }) {
            orchestrator.onStateChange(it, ha.client, watch.transport, this)
        }

        ha.change("cover.garage_door", "open")   // not a device
        ha.change("light.dining_room_chandelier", "off", attributes = mapOf("brightness" to "80"))   // attributes only
        runCurrent()
        advanceUntilIdle()
        assertTrue(watch.sent.isEmpty())

        ha.change("light.dining_room_chandelier", "on")
        runCurrent()
        advanceUntilIdle()

        val (type, bytes) = watch.sent.single()
        assertEquals(Protocol.Type.HOME_CONTROL_UPDATE.wire, type)
        @Suppress("UNCHECKED_CAST")
        val devices = decodeMap(bytes)["devices"] as List<Map<String, Any?>>
        assertEquals(4, devices.size)
        assertEquals(mapOf("l" to "DINING", "e" to "light.dining_room_chandelier", "s" to "on", "a" to true), devices[0])
    }

    // ── The calendar orchestrator ──

    @Test
    fun `entries list all-day events first, then by time, at most five, cut to size`() {
        fun at(s: String) = Instant.parse(s)
        val longTitle = "Réunion trimestrielle de planification — équipe produit et ingénierie"
        val entries = CalendarOrchestrator.toEntries(listOf(
            CalendarEvent(time = "14:00", title = "Dentist", startInstant = at("2026-10-10T20:00:00Z")),
            CalendarEvent(time = "09:30", title = longTitle, startInstant = at("2026-10-10T15:30:00Z")),
            CalendarEvent(time = "All", title = "Holiday", allDay = true),
            CalendarEvent(time = "16:00", title = "Late", startInstant = at("2026-10-10T22:00:00Z")),
            CalendarEvent(time = "18:00", title = "Dinner", startInstant = at("2026-10-11T00:00:00Z")),
            CalendarEvent(time = "20:00", title = "Later still", startInstant = at("2026-10-11T02:00:00Z")),
        ))

        assertEquals(listOf("All", "09:30", "14:00", "16:00", "18:00"), entries.map { it.time })
        assertEquals("Holiday", entries[0].title)
        assertTrue(WireText.utf8Length(entries[1].title) <= CalendarOrchestrator.MAX_TITLE_BYTES)
        assertTrue(longTitle.startsWith(entries[1].title))
    }

    @Test
    fun `a CALENDAR_NAV is answered for the requested day through the send path`() = runTest {
        val haApi = mockk<HaApi>()
        coEvery { haApi.fetchCalendarDay("calendar.home", any(), any()) } returns AppResult.Ok(listOf(
            CalendarEvent(time = "10:00", title = "Groceries", startInstant = Instant.parse("2026-10-11T16:00:00Z"))))
        coEvery { haApi.fetchCalendarDay("calendar.work", any(), any()) } returns AppResult.Err(IllegalStateException("HTTP 500"))
        val layout = WatchFaceLayoutPayload(version = 1, header = WatchHeader(timeFormat = "HH:mm"), calendarEntityIds = listOf("calendar.home", "calendar.work"))
        val orchestrator = CalendarOrchestrator(silentLogger, haApi, handler, { layout }) { _, _, _, _ -> }
        val watch = Recorder()

        orchestrator.handleCalendarNav(CalendarNavPayload(dayOffset = 1), watch.transport, this)
        advanceUntilIdle()

        val (type, bytes) = watch.sent.single()
        assertEquals(Protocol.Type.CALENDAR_UPDATE.wire, type)
        val map = decodeMap(bytes)
        assertEquals(1L, map["day"])
        assertEquals(listOf(mapOf("t" to "10:00", "n" to "Groceries")), map["entries"])
    }

    @Test
    fun `nothing is pushed unasked when no calendars are configured`() = runTest {
        val orchestrator = CalendarOrchestrator(silentLogger, mockk(), handler, { WatchFaceLayoutPayload(version = 1, header = WatchHeader(timeFormat = "HH:mm")) }) { _, _, _, _ -> }
        val watch = Recorder()

        orchestrator.sendToday(watch.transport, this)
        advanceUntilIdle()

        assertTrue(watch.sent.isEmpty())
    }

    @Test
    fun `a request is answered even with no calendars, so the watch stops waiting`() = runTest {
        val orchestrator = CalendarOrchestrator(silentLogger, mockk(), handler, { null }) { _, _, _, _ -> }
        val watch = Recorder()

        orchestrator.handleCalendarNav(CalendarNavPayload(dayOffset = 0), watch.transport, this)
        advanceUntilIdle()

        assertEquals(mapOf("entries" to emptyList<Any?>()), decodeMap(watch.sent.single().second))
    }
}
