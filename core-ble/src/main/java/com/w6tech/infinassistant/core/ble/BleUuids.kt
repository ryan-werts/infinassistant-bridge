package com.w6tech.infinassistant.core.ble

import java.util.UUID

object BleUuids {
    // InfiniTime custom base UUID scheme with SSSS=0006 for Home Assistant.
    val SERVICE = UUID.fromString("00060000-78fc-48fe-8e23-433b3a1942d0")

    // NOTE: On a standard GATT peripheral (watch), Notify characteristics are server->client.
    // We therefore use 00060001 as WATCH->PHONE (Notify) and 00060002 as PHONE->WATCH (Write).
    val CHAR_TX_NOTIFY = UUID.fromString("00060001-78fc-48fe-8e23-433b3a1942d0")
    val CHAR_RX_WRITE = UUID.fromString("00060002-78fc-48fe-8e23-433b3a1942d0")

    val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
