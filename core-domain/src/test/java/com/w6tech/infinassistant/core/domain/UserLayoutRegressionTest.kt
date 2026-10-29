package com.w6tech.infinassistant.core.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the exact layout the user's watch crashes on through the real parse and
 * encode path, and asserts it fits every firmware cap.
 *
 * The layout is the one being applied from the app's cached copy (captured
 * 2026-09-24) while the watch face apply was hard-faulting the watch. Code
 * inspection of the firmware decoder and every render loop found them
 * correctly bounded, so this pins the remaining variable: the content itself.
 *
 * Caps come from FirmwareLimits, which is generated from
 * scripts/config/firmware-limits.yaml, so this test tracks the firmware rather
 * than restating constants.
 */
class UserLayoutRegressionTest {

  private fun loadLayout(): String =
    requireNotNull(javaClass.classLoader?.getResourceAsStream("user-layout-2026-09-24.json"))
      .bufferedReader()
      .use { it.readText() }

  /**
   * Screens and tiles-per-screen are NOT in FirmwareLimits — firmware-limits.yaml
   * does not carry them. They exist only as hand-written constants in two places
   * that must stay in lockstep by convention:
   *   firmware  HomeAssistantService.h  kMaxScreens / kMaxTilesPerScreen  = 6 / 4  (fw 2.4.100; 4 / 4 before)
   *   app       WatchFaceOrchestrator   MAX_FW_SCREENS / MAX_FW_TILES...  = 6 / 4
   * A watch without the six-screen HELLO bit holds 4 screens; the app trims at the push.
   * A mismatch overflows the firmware's fixed screens[]/tiles[] storage, so these
   * are the two most brick-critical caps in the protocol and the only ones with
   * no generated source of truth. Duplicated here deliberately, with that noted.
   */
  private val maxScreens = 6
  private val maxTilesPerScreen = 4

  @Test
  fun `user layout parses, and every count is within the firmware caps`() {
    val result = WatchFaceParser.parseWatchFaceJson(loadLayout())
    val payload = when (result) {
      is ParseResult.Success -> result.payload
      is ParseResult.Error -> throw AssertionError("parse failed: ${result.message}")
    }

    val report = StringBuilder("\n=== user layout vs firmware caps ===\n")
    fun line(label: String, actual: Int, cap: Int) {
      val verdict = if (actual > cap) "OVER CAP" else "ok"
      report.append(String.format("  %-38s %3d / %3d  %s%n", label, actual, cap, verdict))
    }

    line("screens", payload.screens.size, maxScreens)

    var overCap = false
    payload.screens.forEachIndexed { s, screen ->
      line("screens[$s].tiles", screen.tiles.size, maxTilesPerScreen)
      if (screen.tiles.size > maxTilesPerScreen) overCap = true

      screen.dashboard?.let { dash ->
        line("screens[$s].statusGroups", dash.statusGroups.size, FirmwareLimits.MAX_STATUS_GROUPS)
        if (dash.statusGroups.size > FirmwareLimits.MAX_STATUS_GROUPS) overCap = true
        dash.statusGroups.forEachIndexed { g, grp ->
          line("  statusGroups[$g].glyphs", grp.glyphs.size, FirmwareLimits.MAX_GLYPHS_PER_GROUP)
          if (grp.glyphs.size > FirmwareLimits.MAX_GLYPHS_PER_GROUP) overCap = true
        }
        line("screens[$s].sensorGroups", dash.sensorGroups.size, FirmwareLimits.MAX_SENSOR_GROUPS)
        if (dash.sensorGroups.size > FirmwareLimits.MAX_SENSOR_GROUPS) overCap = true
        dash.sensorGroups.forEachIndexed { g, grp ->
          line("  sensorGroups[$g].sensors", grp.sensors.size, FirmwareLimits.MAX_SENSORS_PER_GROUP)
          if (grp.sensors.size > FirmwareLimits.MAX_SENSORS_PER_GROUP) overCap = true
        }
      }
    }

    // The wire size is the other hard limit. The firmware pvPortMallocs the
    // reassembly buffer for exactly this many bytes out of the SAME FreeRTOS
    // heap LVGL draws from, so a large layout both has to fit MAX_REASSEMBLY_SIZE
    // and leave the render enough heap to stay above the safe-face floor.
    val encoded = ProtocolCbor.encodeWatchFace(WatchFaceLayoutPayload.serializer(), payload)
    report.append(
      String.format(
        "%n  %-38s %3d / %3d  %s%n",
        "encoded CBOR bytes",
        encoded.size,
        FirmwareLimits.MAX_REASSEMBLY_SIZE,
        if (encoded.size > FirmwareLimits.MAX_REASSEMBLY_SIZE) "OVER CAP" else "ok",
      )
    )
    println(report)

    assertTrue("encoded layout exceeds MAX_REASSEMBLY_SIZE", encoded.size <= FirmwareLimits.MAX_REASSEMBLY_SIZE)
    assertTrue("a layout count exceeds its firmware cap — see printed report", !overCap)
  }

  /**
   * Size is the constraint that actually bites, and it is not cap-checked
   * anywhere: the firmware pvPortMallocs the reassembly buffer for the exact
   * payload length out of the SAME FreeRTOS heap LVGL renders from. With the
   * dashboard up that heap has roughly 4 KB free, so a ~4 KB layout can consume
   * essentially all of it for the duration of the decode.
   *
   * This prints what each slice of the layout costs on the wire, so the size can
   * be reasoned about instead of guessed at.
   */
  @Test
  fun `report wire cost of each part of the user layout`() {
    val payload = (WatchFaceParser.parseWatchFaceJson(loadLayout()) as ParseResult.Success).payload
    fun size(p: WatchFaceLayoutPayload) = ProtocolCbor.encodeWatchFace(WatchFaceLayoutPayload.serializer(), p).size

    // Mirror EXACTLY what WatchFaceOrchestrator strips before the BLE write:
    // entityId off every glyph/sensor, logbook/logbookApp/homeControlConfig/
    // calendarEntityIds dropped, screens and tiles trimmed to the firmware caps.
    // Measuring the unstripped payload overstates the wire size badly — the
    // user has 16 calendar entity IDs alone.
    fun asSentOverBle(p: WatchFaceLayoutPayload) = p.copy(
      screens = p.screens.take(maxScreens).map { screen ->
        screen.copy(
          logbook = null,
          dashboard = screen.dashboard?.let { d ->
            d.copy(
              statusGroups = d.statusGroups.map { g -> g.copy(glyphs = g.glyphs.map { it.copy(entityId = "") }) },
              sensorGroups = d.sensorGroups.map { g -> g.copy(sensors = g.sensors.map { it.copy(entityId = "", displayType = "") }) },
            )
          },
          tiles = screen.tiles.take(maxTilesPerScreen),
        )
      },
      logbookApp = null,
      homeControlConfig = null,
      calendarEntityIds = emptyList(),
    )

    val unstripped = size(payload)
    val ble = asSentOverBle(payload)
    val full = size(ble)

    val out = StringBuilder(System.lineSeparator() + "=== wire cost (bytes) ===" + System.lineSeparator())
    out.append(String.format("  %-34s %5d%n", "parsed payload (NOT what is sent)", unstripped))
    out.append(String.format("  %-34s %5d   (-%d stripped)%n", "as actually sent over BLE", full, unstripped - full))

    for (drop in 1..3) {
      val kept = ble.screens.take(ble.screens.size - drop)
      val s = size(ble.copy(screens = kept))
      out.append(String.format("  %-34s %5d   (-%d)%n", "with last $drop screen(s) removed", s, full - s))
    }

    val noDash = ble.copy(screens = ble.screens.map { it.copy(dashboard = null) })
    out.append(String.format("  %-34s %5d   (-%d)%n", "dashboard removed", size(noDash), full - size(noDash)))

    val tilesOnly = ble.copy(screens = ble.screens.map { it.copy(tiles = emptyList()) })
    out.append(String.format("  %-34s %5d   (-%d)%n", "all tiles removed", size(tilesOnly), full - size(tilesOnly)))
    println(out)

    // Dump the exact bytes that go over BLE so the encoding itself can be
    // analysed (how much of it is field names vs actual values).
    val dump = java.io.File(System.getProperty("java.io.tmpdir"), "ble-watchface-payload.cbor")
    dump.writeBytes(ProtocolCbor.encodeWatchFace(WatchFaceLayoutPayload.serializer(), ble))
    println("wrote BLE payload to " + dump.absolutePath + " (" + dump.length() + " bytes)")
  }
}
