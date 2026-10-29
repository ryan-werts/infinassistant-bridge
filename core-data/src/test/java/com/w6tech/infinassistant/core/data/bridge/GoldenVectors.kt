package com.w6tech.infinassistant.core.data.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The golden vectors the firmware's host tests and the Home Assistant tests pin as well. The app
 * holds a byte-identical copy as the test resource `golden-vectors/protocol-vectors.json`, pinned to
 * a firmware tag by `golden-vectors.pin` (see [VendoredFixturesTest]). A hex vector is a CBOR
 * payload as its bytes on the wire; a value is a plain number. A name missing from the file fails
 * the test that asks for it, so a vector dropped there cannot go unnoticed here.
 */
internal object GoldenVectors {

    const val RESOURCE = "/golden-vectors/protocol-vectors.json"

    /** The vendored copy's text, read from the test classpath. */
    private val text: String by lazy {
        val stream = GoldenVectors::class.java.getResourceAsStream(RESOURCE)
            ?: error("test resource $RESOURCE not found on the classpath")
        stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private val vectors: Map<String, JsonObject> by lazy {
        val doc = Json.parseToJsonElement(text).jsonObject
        doc.getValue("vectors").jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
    }

    private fun vector(name: String): JsonObject = vectors[name] ?: error("no golden vector named $name in $RESOURCE")

    /** The vector's `hex` as bytes. */
    fun bytes(name: String): ByteArray {
        val hex = vector(name).getValue("hex").jsonPrimitive.content.filter { !it.isWhitespace() }
        require(hex.length % 2 == 0) { "$name: odd number of hex digits" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** The vector's `hex` as the uppercase, space-separated text the file holds. */
    fun hex(name: String): String = vector(name).getValue("hex").jsonPrimitive.content

    /** The vector's `value`. */
    fun value(name: String): Long = vector(name).getValue("value").jsonPrimitive.long

    /** The vector's `groups`, in order. */
    fun groups(name: String): List<String> =
        (vector(name).getValue("groups") as JsonArray).map { (it as JsonPrimitive).content }
}
