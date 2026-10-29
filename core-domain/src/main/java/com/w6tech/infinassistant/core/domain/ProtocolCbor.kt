package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor

object ProtocolCbor {
    private val cbor = Cbor {
        ignoreUnknownKeys = true
    }

    /**
     * Encoder for the watch-face layout only.
     *
     * Defaults are omitted: a property that still holds its declared default is not
     * written. That is the kotlinx-serialization-cbor 1.7.3 default too (the builder
     * starts from `Cbor.Default`, whose `CborConfiguration.encodeDefaults` is false), so
     * the shared encoder above omits them as well and the two produce identical bytes
     * for this payload; PayloadSizeMeasurementTest pins that. The flag is set explicitly
     * here so this encoder's behaviour does not depend on the library default.
     * WatchFaceOrchestrator blanks entityId on all glyphs/sensors before sending (the
     * firmware never reads those keys), and the omitted defaults are the other saving.
     *
     * Safe to omit them because every firmware decoder is a key-MATCHING loop —
     * it walks the CBOR map, compares each key against the names it knows, and
     * advances past anything else (see BridgeController.cpp, e.g. the glyph loop
     * matching "symbol"/"active"). A key that is absent is simply never
     * encountered, and since 2.4.40 resets the layout struct before decoding,
     * an absent field reads as zero rather than as the previous push's value.
     *
     * A property whose default is non-zero reaches the firmware only as an absent
     * key, so the firmware must supply the same value (WatchFaceDefaultsGuardTest).
     */
    private val watchFaceCbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    /** Encode a WATCHFACE_LAYOUT payload, omitting default-valued fields. */
    fun <T> encodeWatchFace(serializer: KSerializer<T>, value: T): ByteArray =
        watchFaceCbor.encodeToByteArray(serializer, value)

    /**
     * Encode a WATCHFACE_LAYOUT payload for the wire: defaults omitted, with the
     * short key spelling when [shortKeys] is set and the long spelling otherwise.
     * The long spelling is exactly [encodeWatchFace]'s output. If the short-key
     * rewrite cannot handle the encoded bytes it falls back to long, and the
     * result says so.
     */
    fun encodeWatchFaceLayout(payload: WatchFaceLayoutPayload, shortKeys: Boolean): EncodedWatchFaceLayout {
        val longBytes = encodeWatchFace(WatchFaceLayoutPayload.serializer(), payload)
        if (!shortKeys) return EncodedWatchFaceLayout(longBytes, shortKeys = false)
        val shortBytes = WatchFaceShortKeys.toShortKeys(longBytes)
            ?: return EncodedWatchFaceLayout(longBytes, shortKeys = false)
        return EncodedWatchFaceLayout(shortBytes, shortKeys = true)
    }

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        cbor.decodeFromByteArray(serializer, bytes)
}
