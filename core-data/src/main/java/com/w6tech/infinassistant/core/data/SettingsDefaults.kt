package com.w6tech.infinassistant.core.data

/**
 * What the settings hold until the user changes them. The app module supplies the values (its
 * build can take them from a local file); the library modules never read that file. With nothing
 * supplied there are no home networks (an http local URL is then used on any network) and no
 * debug share.
 */
data class SettingsDefaults(
    /** Canonical IPv4 subnets ("192.0.2.0/24") an http local URL may be used on. */
    val homeSubnets: List<String> = emptyList(),
    /** UNC path of the SMB share for debug bundle uploads. */
    val debugSharePath: String = "",
)
