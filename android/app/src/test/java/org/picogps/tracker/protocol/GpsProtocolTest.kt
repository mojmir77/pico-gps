package org.picogps.tracker.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GpsProtocolTest {
    private val vectors = checkNotNull(javaClass.getResourceAsStream("/v1-vectors.tsv"))
        .bufferedReader().useLines { rows ->
            rows.drop(1).associate { row ->
                val (name, hex, identity, decoded) = row.split('\t')
                name to Vector(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), identity, decoded)
            }
        }

    @Test fun decodesSharedVectors() {
        val metadata = vectors.getValue("metadata")
        assertEquals(ReceiverMetadata(1, metadata.identity, metadata.decoded), GpsProtocol.decodeMetadata(metadata.bytes))

        for (name in listOf("fix", "no_fix", "south_fix")) {
            val vector = vectors.getValue(name)
            val fix = requireNotNull(GpsProtocol.decodeFix(vector.bytes))
            assertEquals(name, vector.identity.toInt(), fix.sequence)
            val decoded = listOf(
                fix.utcSeconds, fix.latitudeE7, fix.longitudeE7,
                fix.speedCmPerSecond, fix.altitudeMetres, fix.satellites,
            ).joinToString(",") { it?.toString() ?: "-" }
            assertEquals(name, vector.decoded, decoded)
        }
    }

    @Test fun rejectsUnknownVersionAndMalformedNotification() {
        val metadata = vectors.getValue("metadata").bytes.copyOf()
        metadata[0] = 2
        assertNull(GpsProtocol.decodeMetadata(metadata))

        val fix = vectors.getValue("fix").bytes.copyOf()
        assertNull(GpsProtocol.decodeFix(fix.copyOf(19)))
        ByteBuffer.wrap(fix).order(ByteOrder.LITTLE_ENDIAN).putInt(6, 900_000_001)
        assertNull(GpsProtocol.decodeFix(fix))
        fix[18] = 0x80.toByte()
        assertNull(GpsProtocol.decodeFix(fix))
    }

    @Test fun acceptsValidZeroCoordinatesAndUnsignedUtc() {
        val fix = ByteArray(20)
        ByteBuffer.wrap(fix).order(ByteOrder.LITTLE_ENDIAN).putInt(2, -1)
        fix[18] = 3
        val decoded = GpsProtocol.decodeFix(fix)
        assertNotNull(decoded)
        assertEquals(0, decoded?.latitudeE7)
        assertEquals(0, decoded?.longitudeE7)
        assertEquals(0xffffffffL, decoded?.utcSeconds)
    }

    private data class Vector(val bytes: ByteArray, val identity: String, val decoded: String)
}
