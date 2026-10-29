package com.w6tech.infinassistant.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import kotlin.math.roundToLong

/**
 * One event of a subscribe_entities subscription ([HaWebSocketClient.subscribeEntities]): the
 * entities [added] with their whole state, the ones [changed] with what changed, and the ones
 * [removed]. The first event of a subscription adds every requested entity HA has.
 */
data class HaEntitiesEvent(
    val added: Map<String, HaState> = emptyMap(),
    val changed: Map<String, HaStateDiff> = emptyMap(),
    val removed: List<String> = emptyList(),
)

/** What changed in an entity's state: the parts that are new, and the attributes it lost. */
data class HaStateDiff(
    val state: String? = null,
    /** The attributes added or changed, only those. */
    val attributes: JsonObject? = null,
    val removedAttributes: List<String> = emptyList(),
    val lastChanged: String? = null,
    val lastUpdated: String? = null,
) {
    /** [old] with this change made. A new last_changed is the new last_updated too. */
    fun applyTo(old: HaState): HaState {
        val attributes = if (attributes == null && removedAttributes.isEmpty()) {
            old.attributes
        } else {
            JsonObject(LinkedHashMap(old.attributes).apply {
                attributes?.let { putAll(it) }
                removedAttributes.forEach { remove(it) }
            })
        }
        return old.copy(
            state = state ?: old.state,
            attributes = attributes,
            lastChanged = lastChanged ?: old.lastChanged,
            lastUpdated = lastUpdated ?: lastChanged ?: old.lastUpdated,
        )
    }
}

/**
 * HA's compressed states, as subscribe_entities sends them (HA 2022.4 and later:
 * State.as_compressed_state in homeassistant/core.py, the diffs in websocket_api/messages.py):
 *  - an event is {"a": {entity_id: state}, "c": {entity_id: diff}, "r": [entity_id]}, each part
 *    left out when empty;
 *  - a state is {"s": state, "a": attributes, "c": context, "lc": last_changed, "lu": last_updated},
 *    the times in epoch seconds with a fraction, "lu" left out when it equals "lc";
 *  - a diff is {"+": {"s", "a" (the attributes added or changed), "c", "lc" or "lu"},
 *    "-": {"a": [the attributes removed]}}, each part left out when nothing changed in it.
 * The context is not kept. The times become ISO-8601 instants, as get_states has them.
 */
object HaCompressedStates {

    /** The `event` object of a subscribe_entities event. A malformed entity is left out. */
    fun parseEvent(event: JsonObject): HaEntitiesEvent {
        val added = (event["a"] as? JsonObject).orEmpty().mapNotNull { (entityId, value) ->
            (value as? JsonObject)?.let { state(entityId, it) }?.let { entityId to it }
        }.toMap()
        val changed = (event["c"] as? JsonObject).orEmpty().mapNotNull { (entityId, value) ->
            (value as? JsonObject)?.let { entityId to diff(it) }
        }.toMap()
        val removed = (event["r"] as? JsonArray).orEmpty().mapNotNull { text(it) }
        return HaEntitiesEvent(added, changed, removed)
    }

    /** A whole compressed state, or null without "s". */
    fun state(entityId: String, compressed: JsonObject): HaState? {
        val state = text(compressed["s"]) ?: return null
        val lastChanged = time(compressed["lc"])
        return HaState(
            entityId = entityId,
            state = state,
            attributes = compressed["a"] as? JsonObject ?: JsonObject(emptyMap()),
            lastChanged = lastChanged,
            lastUpdated = time(compressed["lu"]) ?: lastChanged,
        )
    }

    /** A compressed diff. */
    fun diff(compressed: JsonObject): HaStateDiff {
        val added = compressed["+"] as? JsonObject
        val removed = compressed["-"] as? JsonObject
        return HaStateDiff(
            state = text(added?.get("s")),
            attributes = added?.get("a") as? JsonObject,
            removedAttributes = (removed?.get("a") as? JsonArray).orEmpty().mapNotNull { text(it) },
            lastChanged = time(added?.get("lc")),
            lastUpdated = time(added?.get("lu")),
        )
    }

    /** Epoch seconds with a fraction as an ISO-8601 instant, to the microsecond HA keeps. */
    fun isoTime(epochSeconds: Double): String {
        val micros = (epochSeconds * 1_000_000).roundToLong()
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000).toString()
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    private fun time(element: JsonElement?): String? = (element as? JsonPrimitive)?.doubleOrNull?.let(::isoTime)
}
