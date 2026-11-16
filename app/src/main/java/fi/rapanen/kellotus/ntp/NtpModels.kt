package fi.rapanen.kellotus.ntp

import fi.rapanen.kellotus.time.AnchoredTime

data class NtpSample(
    val offsetNanos: Long,
    val delayNanos: Long,
    val anchor: AnchoredTime,
    val stratum: Int,
    val referenceId: String,
    val precisionExponent: Int,
    val leapIndicator: Int,
    val address: String,
)

data class NtpFix(
    val host: String,
    val address: String,
    val anchor: AnchoredTime,
    val offsetNanos: Long,
    val delayNanos: Long,
    val stratum: Int,
    val referenceId: String,
    val precisionExponent: Int,
    val leapIndicator: Int,
    val acceptedSamples: Int,
    val attemptCount: Int,
)

enum class NtpFailure {
    UNKNOWN_HOST,
    TIMEOUT,
    RATE_LIMITED,
    INVALID,
    NETWORK,
    NO_SAMPLES,
    LOCAL_NETWORK,
}

sealed class NtpOutcome {
    data class Success(val fix: NtpFix) : NtpOutcome()
    data class Failure(val reason: NtpFailure, val detail: String? = null) : NtpOutcome()
}
