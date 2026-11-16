package fi.rapanen.kellotus.ntp

internal fun readLong(packet: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) {
        value = (value shl 8) or (packet[offset + i].toLong() and 0xFF)
    }
    return value
}

internal fun writeLong(packet: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
        packet[offset + i] = (value ushr ((7 - i) * 8)).toByte()
    }
}

data class NtpResponse(
    val leapIndicator: Int,
    val version: Int,
    val mode: Int,
    val stratum: Int,
    val precisionExponent: Int,
    val referenceId: String,
    val originateNtp: Long,
    val receiveUnixNanos: Long,
    val transmitUnixNanos: Long,
)

enum class NtpReject {
    SHORT_PACKET,
    BAD_MODE,
    BAD_VERSION,
    UNSYNCHRONIZED,
    KISS,
    STRATUM,
    ZERO_TRANSMIT,
}

sealed class NtpParse {
    data class Ok(val response: NtpResponse) : NtpParse()
    data class Reject(val reason: NtpReject, val kissCode: String? = null) : NtpParse()
}

object NtpPacket {
    const val SIZE = 48
    private val RATE_CODES = setOf("RATE", "DENY", "RSTR")

    fun buildRequest(unixEpochNanos: Long): ByteArray {
        val packet = ByteArray(SIZE)
        packet[0] = 0x23 // LI = 0, VN = 4, mode = client
        writeLong(packet, 40, NtpTime.unixNanosToNtp(unixEpochNanos))
        return packet
    }

    fun parse(packet: ByteArray): NtpParse {
        if (packet.size < SIZE) return NtpParse.Reject(NtpReject.SHORT_PACKET)
        val header = packet[0].toInt() and 0xFF
        val leap = header ushr 6
        val version = (header ushr 3) and 0x7
        val mode = header and 0x7
        if (mode != 4) return NtpParse.Reject(NtpReject.BAD_MODE)
        if (version !in 3..4) return NtpParse.Reject(NtpReject.BAD_VERSION)
        if (leap == 3) return NtpParse.Reject(NtpReject.UNSYNCHRONIZED)
        val stratum = packet[1].toInt() and 0xFF
        val reference = packet.copyOfRange(12, 16)
        if (stratum == 0) {
            return NtpParse.Reject(NtpReject.KISS, kissCode(reference))
        }
        if (stratum > 15) return NtpParse.Reject(NtpReject.STRATUM)
        val transmit = readLong(packet, 40)
        if (transmit == 0L) return NtpParse.Reject(NtpReject.ZERO_TRANSMIT)
        return NtpParse.Ok(
            NtpResponse(
                leapIndicator = leap,
                version = version,
                mode = mode,
                stratum = stratum,
                precisionExponent = packet[3].toInt(),
                referenceId = formatReferenceId(stratum, reference),
                originateNtp = readLong(packet, 24),
                receiveUnixNanos = NtpTime.ntpToUnixNanos(readLong(packet, 32)),
                transmitUnixNanos = NtpTime.ntpToUnixNanos(transmit),
            ),
        )
    }

    fun isRateLimit(kissCode: String?): Boolean = kissCode != null && kissCode in RATE_CODES

    /**
     * Stratum 0–1 reference IDs are four ASCII characters (for example GPS, GOOG).
     * Higher strata carry an IPv4 address, shown in dotted form.
     */
    fun formatReferenceId(stratum: Int, raw: ByteArray): String {
        require(raw.size == 4)
        val printable = raw.all { byte ->
            val value = byte.toInt() and 0xFF
            value == 0 || value in 32..126
        }
        if (stratum <= 1 && printable) {
            val text = raw.toString(Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            if (text.isNotEmpty()) return text
        }
        return raw.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    internal fun buildServerPacket(
        originateNtp: Long,
        receiveUnixNanos: Long,
        transmitUnixNanos: Long,
        stratum: Int = 1,
        referenceId: ByteArray = byteArrayOf('G'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte(), 0),
        leapIndicator: Int = 0,
        version: Int = 4,
        mode: Int = 4,
        precisionExponent: Int = -20,
    ): ByteArray {
        require(referenceId.size == 4)
        val packet = ByteArray(SIZE)
        packet[0] = (((leapIndicator and 3) shl 6) or ((version and 7) shl 3) or (mode and 7)).toByte()
        packet[1] = stratum.toByte()
        packet[3] = precisionExponent.toByte()
        referenceId.copyInto(packet, 12)
        writeLong(packet, 24, originateNtp)
        writeLong(packet, 32, NtpTime.unixNanosToNtp(receiveUnixNanos))
        writeLong(packet, 40, NtpTime.unixNanosToNtp(transmitUnixNanos))
        return packet
    }

    private fun kissCode(raw: ByteArray): String =
        raw.toString(Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
}
