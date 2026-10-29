package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.domain.WatchFaceLayoutPayload
import com.w6tech.infinassistant.core.ha.HaCompressedStates
import com.w6tech.infinassistant.core.ha.HaErrorResult
import com.w6tech.infinassistant.core.ha.HaState
import com.w6tech.infinassistant.core.ha.HaSubscriber
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A watched entity's state before and after a change; null for none (new to HA, or gone from it). */
data class HaStateChange(val entityId: String, val old: HaState?, val new: HaState?)

/**
 * The states of the entities the watch needs, kept current by one Home Assistant subscription.
 * Every consumer reads them here and follows their [changes].
 *
 * The bridge used to subscribe to every state change in the house, and each consumer read HA's
 * whole state list (get_states) for the few states it needed: on every connection, every dashboard
 * refresh, every home-control and logbook update. HA's subscribe_entities sends the states of the
 * entities it lists, then their changes only ([HaCompressedStates]).
 *
 * The entities are [wanted]'s ([watchedEntities]). [subscribe] subscribes them on every HA
 * connection, and [sync] again when they change (the tiles resolved, a layout pushed); an entity
 * that leaves them leaves the cache at once. An HA that refuses subscribe_entities (one older than
 * 2022.4) is followed as the bridge did before: state_changed, all of HA's, and get_states.
 *
 * The states are whole once a subscription's first answer is in, which [awaitSnapshot] waits for.
 */
class HaStateCache(
    private val logger: Logger,
    private val logCallback: (direction: String, type: String, msgId: Int?, summary: String) -> Unit = { _, _, _, _ -> },
) {
    private val tag = "HaStateCache"
    private val json = Json { ignoreUnknownKeys = true }

    /** The entities to watch, set by the bridge for each session ([watchedEntities]). */
    @Volatile var wanted: () -> Set<String> = { emptySet() }

    private enum class Phase {
        /** No subscription: nothing is served until one is made. */
        IDLE,
        /** Subscribed, or subscribing, and the first answer is not in yet. */
        PENDING,
        /** The state of every watched entity HA has is in. */
        READY,
    }

    private val phase = MutableStateFlow(Phase.IDLE)

    /** Guards [states], [current], [subscribed] and the feeds' own fields. */
    private val lock = Any()
    private val states = HashMap<String, HaState>()
    /** The subscription followed: any other's events are ignored. */
    private var current: Feed? = null
    /** The entities [current] was made for. */
    private var subscribed: Set<String> = emptySet()

    /** One subscription made at a time. */
    private val subscribing = Mutex()

    private val _changes = MutableSharedFlow<HaStateChange>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every change of a watched entity's state, once the cache has it. */
    val changes: SharedFlow<HaStateChange> = _changes.asSharedFlow()

    /** The state of every watched entity HA has is in. */
    val isReady: Boolean get() = phase.value == Phase.READY

    /** [entityId]'s state; null when HA has none, or the entity is not watched. */
    operator fun get(entityId: String): HaState? = synchronized(lock) { states[entityId] }

    /** The states held of [entityIds]. */
    fun states(entityIds: Collection<String>): Map<String, HaState> = synchronized(lock) {
        entityIds.mapNotNull { id -> states[id]?.let { id to it } }.toMap()
    }

    /**
     * Waits for the state of every watched entity, [SNAPSHOT_WAIT_MS] at most: true once it is in.
     * False at once while HA is not connected. A subscription that ended while the client is still
     * connected (another connect replaced its socket: HaApi connects the session's client too) is
     * made again first.
     */
    suspend fun awaitSnapshot(ha: HaWebSocketClient): Boolean {
        if (!ha.isConnected()) return false
        if (phase.value == Phase.IDLE) {
            subscribing.withLock {
                if (phase.value == Phase.IDLE && ha.isConnected()) resubscribe(ha, wanted())
            }
        }
        return withTimeoutOrNull(SNAPSHOT_WAIT_MS) { phase.first { it != Phase.PENDING } } == Phase.READY
    }

    /** Subscribes the watched entities on [ha]'s connection: on every HA (re)connect. */
    suspend fun subscribe(ha: HaWebSocketClient): AppResult<Unit> = subscribing.withLock { resubscribe(ha, wanted()) }

    /**
     * The watched entities may have changed (the tiles resolved, a layout pushed): subscribes them
     * again when they did. Without a connection the next one subscribes them, and the entities that
     * left are dropped at once all the same.
     */
    suspend fun sync(ha: HaWebSocketClient) {
        subscribing.withLock {
            val ids = wanted()
            if (!ha.isConnected()) {
                synchronized(lock) { states.keys.retainAll(ids) }
                return
            }
            if (phase.value != Phase.IDLE && ids == subscribed) return
            resubscribe(ha, ids)
        }
    }

    /** Leaves the subscription followed, if any, and subscribes [ids] on [ha]'s connection. */
    private suspend fun resubscribe(ha: HaWebSocketClient, ids: Set<String>): AppResult<Unit> {
        val previous = synchronized(lock) {
            val feed = current
            current = null
            subscribed = ids
            // The entities no longer watched leave at once, unannounced: no one follows them.
            states.keys.retainAll(ids)
            phase.value = Phase.PENDING
            feed
        }
        try {
            previous?.id?.let { id ->
                val result = ha.unsubscribe(id)
                if (result is AppResult.Err) logger.w(tag, "unsubscribe_events $id failed: ${result.error.message}")
            }
            if (ids.isEmpty()) {
                // subscribe_entities without entities would subscribe every one HA has.
                synchronized(lock) {
                    states.clear()
                    phase.value = Phase.READY
                }
                return AppResult.Ok(Unit)
            }
            val feed = Feed(legacy = false)
            synchronized(lock) { current = feed }
            when (val result = ha.subscribeEntities(ids, feed)) {
                is AppResult.Ok -> {
                    feed.id = result.value
                    logCallback("ha", "States subscribed", result.value, "${ids.size} entities")
                    return AppResult.Ok(Unit)
                }
                is AppResult.Err -> {
                    if (result.error !is HaErrorResult) {
                        // No answer (a timeout, the socket gone) is no refusal: the old feed, every
                        // state_changed in the house, would stay until HA reconnects. The caller
                        // reconnects HA, and the next read subscribes again.
                        end(feed)
                        logCallback("ha", "subscribe_entities failed", null, "${result.error.message}; trying again")
                        return AppResult.Err(result.error)
                    }
                    synchronized(lock) { if (current === feed) current = null }
                    logCallback("ha", "subscribe_entities failed", null, "${result.error.message}; trying state_changed")
                }
            }
            return subscribeLegacy(ha)
        } catch (e: Throwable) {
            // Cancelled half way: no answer is waited for that may never come.
            synchronized(lock) {
                if (phase.value == Phase.PENDING) {
                    current = null
                    phase.value = Phase.IDLE
                }
            }
            throw e
        }
    }

    /**
     * An HA without subscribe_entities: state_changed, every entity's, and one get_states read of the
     * watched entities. A state_changed that comes while HA answers the read is newer than the
     * answer, and stays.
     */
    private suspend fun subscribeLegacy(ha: HaWebSocketClient): AppResult<Unit> {
        val feed = Feed(legacy = true)
        synchronized(lock) { current = feed }
        val subscription = ha.subscribeEvents("state_changed", feed)
        if (subscription is AppResult.Err) {
            end(feed)
            return subscription
        }
        feed.id = (subscription as AppResult.Ok).value
        val read = ha.getStates()
        val held = synchronized(lock) {
            if (current !== feed || read !is AppResult.Ok) {
                null
            } else {
                replaceAll(read.value, keep = feed.changed)
                feed.answered = true
                phase.value = Phase.READY
                "${states.size} of ${subscribed.size} entities"
            }
        }
        if (held == null) {
            end(feed)
            feed.id?.let { ha.unsubscribe(it) }
            return AppResult.Err((read as? AppResult.Err)?.error ?: IllegalStateException("HA connection closed"))
        }
        logCallback("ha", "States read", feed.id, "$held; following state_changed")
        return AppResult.Ok(Unit)
    }

    /** [feed] no longer serves: nothing is until a subscription is made again. */
    private fun end(feed: Feed) {
        synchronized(lock) {
            if (current !== feed) return
            current = null
            phase.value = Phase.IDLE
        }
    }

    /** One subscription: its events while it is the one followed, and the end of its connection. */
    private inner class Feed(val legacy: Boolean) : HaSubscriber {
        @Volatile var id: Int? = null
        /** Its first answer is in: the first event (subscribe_entities) or the get_states read. */
        var answered = false
        /** state_changed: the entities an event came for, which the get_states answer must not undo. */
        val changed = HashSet<String>()

        override fun onEvent(message: JsonObject) {
            val event = message["event"] as? JsonObject ?: return
            val answer = synchronized(lock) {
                if (current !== this) return
                if (legacy) {
                    applyStateChanged(this, event)
                    null
                } else {
                    applyEntities(this, event)
                }
            }
            answer?.let { logCallback("ha", "States in", id, it) }
        }

        override fun onEnded() = end(this)
    }

    /**
     * A subscribe_entities event; the first has every watched entity HA has. Under [lock]. Returns
     * the count of the first answer, for the log.
     */
    private fun applyEntities(feed: Feed, event: JsonObject): String? {
        val parsed = HaCompressedStates.parseEvent(event)
        var answer: String? = null
        if (!feed.answered) {
            feed.answered = true
            replaceAll(parsed.added.values, keep = emptySet())
            phase.value = Phase.READY
            answer = "${states.size} of ${subscribed.size} entities"
        } else {
            parsed.added.values.forEach { if (it.entityId in subscribed) put(it) }
        }
        for ((entityId, diff) in parsed.changed) {
            val old = states[entityId]
            if (old == null) {
                logger.d(tag, "A change of $entityId, whose state is not held: ignored")
                continue
            }
            put(diff.applyTo(old))
        }
        parsed.removed.forEach(::remove)
        return answer
    }

    /** A state_changed event: a watched entity's state is kept. Under [lock]. */
    private fun applyStateChanged(feed: Feed, event: JsonObject) {
        val data = event["data"] as? JsonObject ?: return
        val entityId = (data["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return
        if (entityId !in subscribed) return
        feed.changed += entityId
        val newState = data["new_state"] as? JsonObject
        if (newState == null) {
            remove(entityId)
            return
        }
        runCatching { json.decodeFromJsonElement(HaState.serializer(), newState) }.getOrNull()?.let(::put)
    }

    /** Under [lock]. */
    private fun put(state: HaState) {
        val old = states.put(state.entityId, state)
        if (old != state) _changes.tryEmit(HaStateChange(state.entityId, old, state))
    }

    /** Under [lock]. */
    private fun remove(entityId: String) {
        val old = states.remove(entityId) ?: return
        _changes.tryEmit(HaStateChange(entityId, old, null))
    }

    /**
     * A subscription's first answer in place of the states held: a change for each entity held with
     * another state, or one HA no longer has. An entity not held before is no change, there being
     * nothing it changed from: whoever needs it reads it once the answer is in. The entities in
     * [keep] stay as they are. Under [lock].
     */
    private fun replaceAll(fresh: Collection<HaState>, keep: Set<String>) {
        val byId = fresh.filter { it.entityId in subscribed && it.entityId !in keep }.associateBy { it.entityId }
        for (entityId in states.keys.toList()) {
            if (entityId !in byId && entityId !in keep) remove(entityId)
        }
        for ((entityId, state) in byId) {
            val old = states.put(entityId, state)
            if (old != null && old != state) _changes.tryEmit(HaStateChange(entityId, old, state))
        }
    }

    companion object {
        /** How long a read waits for a subscription's first answer. */
        const val SNAPSHOT_WAIT_MS = 10_000L

        /** Changes held for a slow collector: a first answer can bring one for each entity. */
        private const val CHANGE_BUFFER = 256

        /**
         * An entity ID as HA checks them (homeassistant.core.valid_entity_id): subscribe_entities
         * refuses a whole list with anything else in it.
         */
        private val ENTITY_ID = Regex("(?!.+__)(?!_)[\\da-z_]+(?<!_)\\.(?!_)[\\da-z_]+(?<!_)")

        /**
         * The entities the watch needs states of: the tiles' ([tiles], EventBroker's with the device
         * tiles resolved, and [layout]'s, since a new session registers those only after HA is
         * connected), and the dashboard's, home control's and the logbook app's.
         * An unresolved "device:" tile or a blank is left out.
         */
        fun watchedEntities(
            tiles: Collection<String>,
            layout: WatchFaceLayoutPayload?,
        ): Set<String> {
            val ids = LinkedHashSet<String>()
            ids += tiles
            if (layout != null) {
                layout.screens.flatMap { it.tiles }.mapTo(ids) { it.entityId }
                // The most screens any watch holds: a watch of 4 has no use for a 5th screen's dashboard,
                // but watching its entities too costs little and the cache is set before the HELLO.
                DashboardStateBuilder.dashboardOf(layout)?.let { ids += DashboardStateBuilder.entityIds(it) }
                HomeControlOrchestrator.appDevices(layout).mapTo(ids) { it.entityId }
                LogbookOrchestrator.appLogbook(layout)?.let { ids += it.entityIds }
            }
            return ids.filterTo(LinkedHashSet()) { ENTITY_ID.matches(it) }
        }
    }
}
