package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.HaCompressedStates
import com.w6tech.infinassistant.core.ha.HaState
import com.w6tech.infinassistant.core.ha.HaSubscriber
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.OffsetDateTime

/** Helpers for tests that check what the app puts on the wire against what the firmware reads. */
internal object WireTestSupport {

    /**
     * Home Assistant as the state cache meets it: a mocked client whose subscribe_entities answers
     * with the states asked for, in HA's compressed form, and whose [change] and [remove] send what
     * HA sends then. [connected] false, or [refuses], fails a subscription as an HA that does not
     * answer does. A test adds the client's other answers (get_states, the logbook) itself.
     */
    class FakeHa(states: List<HaState> = emptyList()) {
        val client: HaWebSocketClient = mockk()
        /** HA's states, by entity. */
        val states = LinkedHashMap<String, HaState>().apply { states.forEach { put(it.entityId, it) } }
        var connected = true
        var refuses = false
        /** The first answer waits for [answer], as when it is still on its way. */
        var answerLater = false
        /** The entities of each subscription, in order. */
        val subscriptions = mutableListOf<Set<String>>()
        /** The IDs of the subscriptions ended. */
        val unsubscribed = mutableListOf<Int>()
        private var subscriber: HaSubscriber? = null
        private var subscriptionId = 0
        private var watched: Set<String> = emptySet()

        init {
            every { client.isConnected() } answers { connected }
            coEvery { client.subscribeEntities(any(), any()) } answers {
                if (!connected || refuses) {
                    AppResult.Err(IllegalStateException("no response"))
                } else {
                    watched = firstArg<Collection<String>>().toSet()
                    subscriptions += watched
                    subscriber = secondArg()
                    subscriptionId++
                    if (!answerLater) answer()
                    AppResult.Ok(subscriptionId)
                }
            }
            coEvery { client.subscribeEvents(any(), any()) } returns AppResult.Err(IllegalStateException("no response"))
            coEvery { client.unsubscribe(any()) } answers {
                unsubscribed += firstArg<Int>()
                AppResult.Ok(Unit)
            }
        }

        /** The subscription's first event: the state of every entity watched that HA has. */
        fun answer() = send(buildJsonObject {
            putJsonObject("a") { states.values.filter { it.entityId in watched }.forEach { put(it.entityId, compressed(it)) } }
        })

        /**
         * [entityId] goes to [state], with [attributes] added when given, at [at]. HA sends a new
         * state with a new last_changed, the same one with a new last_updated only, and nothing for
         * an entity no one subscribed.
         */
        fun change(entityId: String, state: String, attributes: Map<String, String> = emptyMap(), at: Instant = Instant.now()) {
            val old = states[entityId]
            val time = HaCompressedStates.isoTime(seconds(at))
            val added = JsonObject(attributes.mapValues { JsonPrimitive(it.value) })
            val new = HaState(
                entityId = entityId,
                state = state,
                attributes = JsonObject(old?.attributes.orEmpty() + added),
                lastChanged = if (old == null || old.state != state) time else old.lastChanged,
                lastUpdated = time,
            )
            states[entityId] = new
            if (entityId !in watched) return
            send(buildJsonObject {
                if (old == null) {
                    putJsonObject("a") { put(entityId, compressed(new)) }
                } else {
                    putJsonObject("c") {
                        putJsonObject(entityId) {
                            putJsonObject("+") {
                                if (old.state != state) {
                                    put("s", state)
                                    put("lc", seconds(at))
                                } else {
                                    put("lu", seconds(at))
                                }
                                if (attributes.isNotEmpty()) put("a", added)
                            }
                        }
                    }
                }
            })
        }

        /** [entityId] is gone from HA. */
        fun remove(entityId: String) {
            states.remove(entityId)
            if (entityId in watched) send(buildJsonObject { putJsonArray("r") { add(entityId) } })
        }

        /** The connection ends, and the subscription with it. */
        fun drop() {
            connected = false
            subscriber?.onEnded()
            subscriber = null
        }

        /**
         * The bridge's wiring, as on a connection: [cache] watches [watched] and subscribes here, and
         * hands each change to [onChange], as BridgeEngine's listener does.
         */
        fun connect(scope: TestScope, cache: HaStateCache, watched: () -> Set<String>, onChange: suspend (HaStateChange) -> Unit = {}) {
            cache.wanted = watched
            scope.backgroundScope.launch { cache.changes.collect { onChange(it) } }
            scope.launch { cache.subscribe(client) }
            scope.runCurrent()
        }

        private fun send(event: JsonObject) {
            subscriber?.onEvent(buildJsonObject {
                put("id", subscriptionId)
                put("type", "event")
                put("event", event)
            })
        }
    }

    /** A state as subscribe_entities writes it ("lu" only when it is not "lc"). */
    fun compressed(s: HaState): JsonObject = buildJsonObject {
        val lastChanged = s.lastChanged?.let { seconds(OffsetDateTime.parse(it).toInstant()) } ?: 1_791_640_000.0
        put("s", s.state)
        put("a", s.attributes)
        put("c", "01JADJ8A9SXTHZ5E8Z1G4N3Q2R")
        put("lc", lastChanged)
        s.lastUpdated?.let { seconds(OffsetDateTime.parse(it).toInstant()) }?.takeIf { it != lastChanged }?.let { put("lu", it) }
    }

    private fun seconds(at: Instant): Double = at.epochSecond + at.nano / 1e9

    val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    /** A transport that records each message sent: (type, payload bytes). */
    class Recorder {
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        val transport: BleTransport = mockk()

        init {
            coEvery { transport.send(any(), any(), any(), any(), any()) } coAnswers {
                sent += firstArg<Int>() to arg<ByteArray>(3)
                AppResult.Ok(Unit)
            }
        }
    }

    /** Decodes one CBOR item as kotlinx writes it (indefinite-length maps and arrays) or TinyCBOR does. */
    fun decode(bytes: ByteArray): Any? = Reader(bytes).read()

    @Suppress("UNCHECKED_CAST")
    fun decodeMap(bytes: ByteArray): Map<String, Any?> = decode(bytes) as Map<String, Any?>

    private class Reader(private val b: ByteArray) {
        private var i = 0

        private fun arg(info: Int): Long = when (info) {
            in 0..23 -> info.toLong()
            24 -> (b[i++].toInt() and 0xFF).toLong()
            25 -> ((b[i++].toInt() and 0xFF) shl 8 or (b[i++].toInt() and 0xFF)).toLong()
            26 -> {
                var v = 0L
                repeat(4) { v = (v shl 8) or (b[i++].toLong() and 0xFF) }
                v
            }
            else -> error("unsupported length $info")
        }

        private fun atBreak() = (b[i].toInt() and 0xFF) == 0xFF

        fun read(): Any? {
            val ib = b[i++].toInt() and 0xFF
            val major = ib shr 5
            val info = ib and 0x1F
            return when (major) {
                0 -> arg(info)
                1 -> -1 - arg(info)
                3 -> {
                    val n = arg(info).toInt()
                    String(b, i, n, Charsets.UTF_8).also { i += n }
                }
                4 -> buildList {
                    if (info == 31) {
                        while (!atBreak()) add(read())
                        i++
                    } else {
                        repeat(arg(info).toInt()) { add(read()) }
                    }
                }
                5 -> buildMap {
                    if (info == 31) {
                        while (!atBreak()) put(read() as String, read())
                        i++
                    } else {
                        repeat(arg(info).toInt()) { put(read() as String, read()) }
                    }
                }
                7 -> when (info) {
                    20 -> false
                    21 -> true
                    22 -> null
                    else -> error("simple value $info")
                }
                else -> error("major type $major")
            }
        }
    }
}
