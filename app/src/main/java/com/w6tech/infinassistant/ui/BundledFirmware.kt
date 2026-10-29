package com.w6tech.infinassistant.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The asset that lists the firmware packages bundled with the app. */
const val FIRMWARE_INDEX_ASSET = "firmware/index.json"

/** The firmware screen offers at most this many bundled packages. */
const val MAX_BUNDLED_FIRMWARE = 4

/**
 * A firmware package bundled with the app, as listed in [FIRMWARE_INDEX_ASSET].
 *
 * @property file the package's asset path, e.g. "firmware/pinetime-mcuboot-dfu-2.4.64.zip"
 * @property title a short label for the picker
 * @property detail a sentence or two shown under the title
 */
data class BundledFirmware(
    val file: String,
    val title: String,
    val detail: String,
) {
    /** The package's own file name. Its copy in the cache keeps it, so the DFU log names the package. */
    val fileName: String get() = file.substringAfterLast('/')
}

/**
 * Parses the bundled firmware index: a JSON array of `{"file": …, "title": …, "detail": …}`
 * objects in display order. An entry without all three as non-blank strings is skipped, the
 * first [MAX_BUNDLED_FIRMWARE] entries left are kept, and anything that is not a JSON array
 * gives an empty list. A leading byte order mark is ignored: some Windows tools write one.
 */
fun parseFirmwareIndex(json: String): List<BundledFirmware> {
    val entries = try {
        Json.parseToJsonElement(json.removePrefix("﻿")) as? JsonArray
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    return entries.asSequence()
        .mapNotNull { it.toBundledFirmware() }
        .take(MAX_BUNDLED_FIRMWARE)
        .toList()
}

private fun JsonElement.toBundledFirmware(): BundledFirmware? {
    val entry = this as? JsonObject ?: return null
    val firmware = BundledFirmware(
        file = entry.text("file") ?: return null,
        title = entry.text("title") ?: return null,
        detail = entry.text("detail") ?: return null,
    )
    // The file name also names the copy in the cache, so a path without one is no package.
    return firmware.takeIf { it.fileName.isNotBlank() }
}

/** A string field's trimmed value; null when the field is missing, not a string, or blank. */
private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
