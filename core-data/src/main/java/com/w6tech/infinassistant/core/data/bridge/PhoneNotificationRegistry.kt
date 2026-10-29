package com.w6tech.infinassistant.core.data.bridge

/**
 * The phone notifications forwarded to the watch: each `StatusBarNotification.key` with the
 * short id (`nid`) the watch knows it by, and a fingerprint of what was last sent. The watch's
 * messages carry only the nid, so this is also how a watch dismissal finds its notification.
 *
 * Holds at most [MAX_KEYS] keys, the least recently forwarded one leaving first. One instance
 * serves the listener service and the engine (a Hilt singleton).
 */
class PhoneNotificationRegistry {

    private class Entry(var nid: Int, var fingerprint: Int, var watchDismissed: Boolean = false)

    /** Insertion order, so the first entry is the least recently forwarded. */
    private val byKey = LinkedHashMap<String, Entry>()
    private val keyByNid = HashMap<Int, String>()
    private var lastNid = 0

    /** What a removed key leaves behind. */
    data class Released(val nid: Int, val watchDismissed: Boolean)

    /** The fingerprint last forwarded for [key], or null when it is not registered. */
    @Synchronized
    fun lastFingerprint(key: String): Int? = byKey[key]?.fingerprint

    /**
     * Records that [key] is being forwarded with [fingerprint], and returns its nid: the one it
     * has, or the next free one. The key becomes the most recently used. A key pushed out by the
     * [MAX_KEYS] limit is reported to [onEvicted] by its nid.
     */
    @Synchronized
    fun register(key: String, fingerprint: Int, onEvicted: (nid: Int) -> Unit = {}): Int {
        val existing = byKey.remove(key)
        val entry = existing ?: Entry(nextFreeNid(), fingerprint)
        entry.fingerprint = fingerprint
        entry.watchDismissed = false
        byKey[key] = entry
        keyByNid[entry.nid] = key
        while (byKey.size > MAX_KEYS) {
            val eldest = byKey.entries.first()
            keyByNid.remove(eldest.value.nid)
            byKey.remove(eldest.key)
            onEvicted(eldest.value.nid)
        }
        return entry.nid
    }

    /** The key [nid] stands for, or null when it is not (or no longer) registered. */
    @Synchronized
    fun keyFor(nid: Int): String? = keyByNid[nid]

    /**
     * The watch dismissed [nid]: the key to cancel on the phone, or null. The key stays
     * registered, marked, until the phone reports it removed ([release]).
     */
    @Synchronized
    fun markWatchDismissed(nid: Int): String? {
        val key = keyByNid[nid] ?: return null
        byKey[key]?.watchDismissed = true
        return key
    }

    /** Forgets [key]; null when it was never forwarded (or has left the registry). */
    @Synchronized
    fun release(key: String): Released? {
        val entry = byKey.remove(key) ?: return null
        keyByNid.remove(entry.nid)
        return Released(entry.nid, entry.watchDismissed)
    }

    /** The registered keys. */
    @Synchronized
    fun keys(): Set<String> = byKey.keys.toSet()

    /** Forgets every key. The nid counter carries on, so a nid is not reused at once. */
    @Synchronized
    fun clear() {
        byKey.clear()
        keyByNid.clear()
    }

    /** Next nid in 1..[MAX_NID], wrapping, that no registered key holds. */
    private fun nextFreeNid(): Int {
        do {
            lastNid = if (lastNid >= MAX_NID) 1 else lastNid + 1
        } while (keyByNid.containsKey(lastNid))
        return lastNid
    }

    companion object {
        const val MAX_KEYS = 64
        const val MAX_NID = 65535
    }
}
