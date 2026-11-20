package org.picogps.tracker.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class ReceiverMetadata(val version: Int, val receiverId: String, val sessionId: String)

data class GpsFix(
    val sequence: Int,
    val utcSeconds: Long?,
    val latitudeE7: Int?,
    val longitudeE7: Int?,
    val speedCmPerSecond: Int?,
    val altitudeMetres: Int?,
    val satellites: Int?,
)

object GpsProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("c7a10001-8a6b-4c0f-9e12-5197b5e62d11")
    val METADATA_UUID: UUID = UUID.fromString("c7a10002-8a6b-4c0f-9e12-5197b5e62d11")
    val FIX_UUID: UUID = UUID.fromString("c7a10003-8a6b-4c0f-9e12-5197b5e62d11")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun decodeMetadata(bytes: ByteArray): ReceiverMetadata? {
        if (bytes.size != 17) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        val receiverId = buffer.long.toULong()
        val sessionId = buffer.long.toULong()
        if (version != 1 || receiverId == 0UL || sessionId == 0UL) return null
        return ReceiverMetadata(version, receiverId.toString(16).padStart(16, '0'), sessionId.toString(16).padStart(16, '0'))
    }

    fun decodeFix(bytes: ByteArray): GpsFix? {
        if (bytes.size != 20) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xffff
        val utc = buffer.int.toLong() and 0xffffffffL
        val latitude = buffer.int
        val longitude = buffer.int
        val speed = buffer.short.toInt() and 0xffff
        val altitude = buffer.short.toInt()
        val flags = buffer.get().toInt() and 0xff
        val satellites = buffer.get().toInt() and 0xff
        val hasPosition = flags and 0x01 != 0
        if (flags and 0xf0 != 0) return null
        if (hasPosition && (latitude !in -900_000_000..900_000_000 || longitude !in -1_800_000_000..1_800_000_000)) {
            return null
        }
        return GpsFix(
            sequence = sequence,
            utcSeconds = utc.takeIf { flags and 0x02 != 0 },
            latitudeE7 = latitude.takeIf { hasPosition },
            longitudeE7 = longitude.takeIf { hasPosition },
            speedCmPerSecond = speed.takeIf { flags and 0x04 != 0 },
            altitudeMetres = altitude.takeIf { flags and 0x08 != 0 },
            satellites = satellites.takeIf { it != 0xff },
        )
    }
}
