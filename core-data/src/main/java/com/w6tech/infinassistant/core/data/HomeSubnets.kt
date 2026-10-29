package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.LocalNetwork

/**
 * The home networks an http local URL may be used on ([com.w6tech.infinassistant.core.domain.HaConnectionSettings.homeSubnets]),
 * as the settings store them: canonical IPv4 subnets, comma-separated. Nothing stored means the
 * default the app supplies ([SettingsDefaults.homeSubnets]). An empty list allows no http local URL
 * at all: it fails closed, since "any network" would send the token unencrypted anywhere.
 */
object HomeSubnets {

    /** The stored value as subnets: [default] when nothing was stored, and entries that are not subnets dropped. */
    fun parse(stored: String?, default: List<String>): List<String> =
        stored?.split(',')?.mapNotNull(LocalNetwork::normalizeIpv4Subnet)?.distinct() ?: default

    /** [subnets] as stored: canonical, without repeats, those that are not subnets dropped. */
    fun format(subnets: List<String>): String =
        subnets.mapNotNull(LocalNetwork::normalizeIpv4Subnet).distinct().joinToString(",")

    /**
     * What the user typed (commas, spaces or new lines between the entries): the subnets, canonical,
     * and the entries that are not subnets, for the field to show.
     */
    fun parseInput(text: String): Pair<List<String>, List<String>> {
        val entries = text.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val subnets = entries.mapNotNull(LocalNetwork::normalizeIpv4Subnet).distinct()
        val invalid = entries.filter { LocalNetwork.normalizeIpv4Subnet(it) == null }
        return subnets to invalid
    }

    /**
     * Null when [localUrl] may be used now, or why not. Only plain http is held to the home networks:
     * with no [subnets] set it is refused ([NO_HOME_NETWORKS]); otherwise it needs one of the phone's
     * [phoneNetworks] ("192.0.2.40/24") inside one. An https local URL is never refused here.
     */
    fun localUrlRefusal(localUrl: String, subnets: List<String>, phoneNetworks: List<String>): String? {
        if (!localUrl.trim().startsWith("http://", ignoreCase = true)) return null
        if (subnets.isEmpty()) return NO_HOME_NETWORKS
        val addresses = phoneNetworks.map { it.substringBefore('/') }
        if (addresses.any { a -> subnets.any { LocalNetwork.isInIpv4Subnet(a, it) } }) return null
        return "not on a home network (phone: ${addresses.joinToString().ifEmpty { "no Wi-Fi address" }})"
    }

    /** Why an http local URL is refused when no home network is set. */
    const val NO_HOME_NETWORKS = "set your home networks to use a local http address"
}
