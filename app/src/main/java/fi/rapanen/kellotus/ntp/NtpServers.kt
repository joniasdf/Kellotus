package fi.rapanen.kellotus.ntp

/**
 * Public NTP servers that resolved on 16 November 2026.
 * ntp.funet.fi answers inside the Finnish research network; from the build
 * network the name resolved but UDP/123 did not reply, so it stays in the list.
 */
data class NtpServer(
    val id: String,
    val host: String,
    val label: String,
)

object NtpServers {
    const val CUSTOM = "custom"
    const val DEFAULT_ID = "mikes2"

    val all: List<NtpServer> = listOf(
        NtpServer("mikes2", "time2.mikes.fi", "VTT MIKES time2"),
        NtpServer("mikes1", "time1.mikes.fi", "VTT MIKES time1"),
        NtpServer("mikes", "time.mikes.fi", "VTT MIKES"),
        NtpServer("aalto1", "ntp1.aalto.fi", "Aalto ntp1"),
        NtpServer("aalto2", "ntp2.aalto.fi", "Aalto ntp2"),
        NtpServer("funet", "ntp.funet.fi", "Funet"),
        NtpServer("fi-pool", "fi.pool.ntp.org", "NTP Pool Suomi"),
        NtpServer("eu-pool", "europe.pool.ntp.org", "NTP Pool Eurooppa"),
        NtpServer("cloudflare", "time.cloudflare.com", "Cloudflare"),
        NtpServer("google", "time.google.com", "Google"),
    )

    fun byId(id: String): NtpServer? = all.find { it.id == id }
}
