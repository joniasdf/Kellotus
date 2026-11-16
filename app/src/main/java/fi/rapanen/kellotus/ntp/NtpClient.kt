package fi.rapanen.kellotus.ntp

import fi.rapanen.kellotus.time.AnchoredTime
import fi.rapanen.kellotus.time.Timebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

interface NtpLink {
    fun send(packet: ByteArray)
    fun receive(): ByteArray
}

interface NtpExchange {
    fun <T> transact(address: InetAddress, port: Int, timeoutMs: Int, block: (NtpLink) -> T): T
}

fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

object SystemHostResolver : HostResolver {
    override fun resolve(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).distinctBy { it.hostAddress }
}

class UdpNtpExchange : NtpExchange {
    override fun <T> transact(
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
        block: (NtpLink) -> T,
    ): T {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.trafficClass = 0x10
            socket.connect(address, port)
            val link = object : NtpLink {
                override fun send(packet: ByteArray) {
                    socket.send(DatagramPacket(packet, packet.size))
                }

                override fun receive(): ByteArray {
                    val buffer = ByteArray(256)
                    val incoming = DatagramPacket(buffer, buffer.size)
                    socket.receive(incoming)
                    return buffer.copyOf(incoming.length)
                }
            }
            return block(link)
        }
    }
}

/**
 * SNTP client. Eight exchanges are taken and the sample with the smallest
 * round-trip delay is kept, which minimises the error bound of the symmetric
 * delay assumption (about half of that delay).
 */
class NtpClient(
    private val timebase: Timebase,
    private val exchange: NtpExchange = UdpNtpExchange(),
    private val resolver: HostResolver = SystemHostResolver,
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    suspend fun sync(
        host: String,
        samples: Int = 8,
        timeoutMs: Int = 1_000,
        pauseMs: Long = 80,
        allowLocalNetwork: Boolean = true,
        onProgress: (done: Int, total: Int, bestDelayNanos: Long?) -> Unit = { _, _, _ -> },
    ): NtpOutcome = withContext(Dispatchers.IO) {
        val name = host.trim()
        if (!HostNames.isAcceptable(name)) {
            return@withContext NtpOutcome.Failure(NtpFailure.INVALID, "host")
        }
        if (!allowLocalNetwork && name.endsWith(".local", ignoreCase = true)) {
            return@withContext NtpOutcome.Failure(NtpFailure.LOCAL_NETWORK, name)
        }
        val addresses = try {
            resolver.resolve(name).sortedWith(compareBy { if (it is Inet4Address) 0 else 1 })
        } catch (error: UnknownHostException) {
            return@withContext NtpOutcome.Failure(NtpFailure.UNKNOWN_HOST, error.message)
        }
        if (addresses.isEmpty()) return@withContext NtpOutcome.Failure(NtpFailure.UNKNOWN_HOST, name)
        if (!allowLocalNetwork && addresses.any { it.isNonPublic() }) {
            return@withContext NtpOutcome.Failure(NtpFailure.LOCAL_NETWORK, addresses.joinToString { it.hostAddress ?: it.hostName })
        }

        val good = ArrayList<NtpSample>(samples)
        var attempts = 0
        var lastFailure = NtpFailure.NO_SAMPLES
        var lastDetail: String? = null
        val maxAttempts = samples + 4
        onProgress(0, samples, null)
        while (good.size < samples && attempts < maxAttempts) {
            coroutineContext.ensureActive()
            attempts++
            val order = preferredFirst(addresses, good.lastOrNull()?.address)
            var stopped = false
            var got: NtpSample? = null
            for (address in order) {
                when (val attempt = query(address, timeoutMs)) {
                    is Attempt.Ok -> {
                        got = attempt.sample
                        break
                    }
                    is Attempt.Retry -> {
                        lastFailure = attempt.reason
                        lastDetail = attempt.detail
                    }
                    is Attempt.Stop -> {
                        lastFailure = attempt.reason
                        lastDetail = attempt.detail
                        stopped = true
                        break
                    }
                }
            }
            if (got != null) good += got
            onProgress(good.size, samples, good.minOfOrNull { it.delayNanos })
            if (stopped) break
            if (good.size < samples && attempts < maxAttempts) pause(pauseMs)
        }
        if (good.isEmpty()) return@withContext NtpOutcome.Failure(lastFailure, lastDetail)
        val best = NtpMath.selectLowestDelay(good)
        return@withContext NtpOutcome.Success(
            NtpFix(
                host = name,
                address = best.address,
                anchor = best.anchor,
                offsetNanos = best.offsetNanos,
                delayNanos = best.delayNanos,
                stratum = best.stratum,
                referenceId = best.referenceId,
                precisionExponent = best.precisionExponent,
                leapIndicator = best.leapIndicator,
                acceptedSamples = good.size,
                attemptCount = attempts,
            ),
        )
    }

    private fun preferredFirst(addresses: List<InetAddress>, previous: String?): List<InetAddress> {
        if (previous == null) return addresses
        val hit = addresses.firstOrNull { it.hostAddress == previous } ?: return addresses
        return listOf(hit) + addresses.filter { it.hostAddress != previous }
    }

    private fun query(address: InetAddress, timeoutMs: Int): Attempt {
        return try {
            exchange.transact(address, NTP_PORT, timeoutMs) { link ->
                val t1Unix = timebase.wallUnixNanos()
                val t1Elapsed = timebase.elapsedRealtimeNanos()
                val request = NtpPacket.buildRequest(t1Unix)
                link.send(request)
                val response = link.receive()
                val t4Elapsed = timebase.elapsedRealtimeNanos()
                val t4Unix = Math.addExact(t1Unix, Math.subtractExact(t4Elapsed, t1Elapsed))
                interpret(address, request, response, t1Unix, t4Unix, t4Elapsed)
            }
        } catch (_: SocketTimeoutException) {
            Attempt.Retry(NtpFailure.TIMEOUT, address.hostAddress)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Attempt.Retry(NtpFailure.NETWORK, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun interpret(
        address: InetAddress,
        request: ByteArray,
        response: ByteArray,
        t1Unix: Long,
        t4Unix: Long,
        t4Elapsed: Long,
    ): Attempt {
        when (val parsed = NtpPacket.parse(response)) {
            is NtpParse.Reject -> {
                if (parsed.reason == NtpReject.KISS && NtpPacket.isRateLimit(parsed.kissCode)) {
                    return Attempt.Stop(NtpFailure.RATE_LIMITED, parsed.kissCode)
                }
                return Attempt.Retry(NtpFailure.INVALID, parsed.reason.name)
            }
            is NtpParse.Ok -> {
                val sent = request.copyOfRange(40, 48)
                val echoed = response.copyOfRange(24, 32)
                if (!sent.contentEquals(echoed)) {
                    return Attempt.Retry(NtpFailure.INVALID, "origin")
                }
                val body = parsed.response
                val math = NtpMath.compute(t1Unix, body.receiveUnixNanos, body.transmitUnixNanos, t4Unix)
                if (math.delayNanos < 0) return Attempt.Retry(NtpFailure.INVALID, "negative-delay")
                if (math.delayNanos > MAX_DELAY_NANOS) return Attempt.Retry(NtpFailure.INVALID, "delay")
                return Attempt.Ok(
                    NtpSample(
                        offsetNanos = math.offsetNanos,
                        delayNanos = math.delayNanos,
                        anchor = AnchoredTime(math.unixEpochNanosAtT4, t4Elapsed),
                        stratum = body.stratum,
                        referenceId = body.referenceId,
                        precisionExponent = body.precisionExponent,
                        leapIndicator = body.leapIndicator,
                        address = address.hostAddress ?: address.hostName,
                    ),
                )
            }
        }
    }

    private sealed class Attempt {
        data class Ok(val sample: NtpSample) : Attempt()
        data class Retry(val reason: NtpFailure, val detail: String?) : Attempt()
        data class Stop(val reason: NtpFailure, val detail: String?) : Attempt()
    }

    private companion object {
        const val NTP_PORT = 123
        const val MAX_DELAY_NANOS = 5_000_000_000L
    }
}
