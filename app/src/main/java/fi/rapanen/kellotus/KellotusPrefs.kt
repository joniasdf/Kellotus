package fi.rapanen.kellotus

import android.content.Context
import fi.rapanen.kellotus.ntp.NtpServers

class KellotusPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("kellotus", Context.MODE_PRIVATE)

    var serverId: String
        get() = prefs.getString(KEY_SERVER, NtpServers.DEFAULT_ID) ?: NtpServers.DEFAULT_ID
        set(value) {
            prefs.edit().putString(KEY_SERVER, value).apply()
        }

    var customHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_HOST, value).apply()
        }

    var autoMinutes: Int
        get() = prefs.getInt(KEY_AUTO, 15)
        set(value) {
            prefs.edit().putInt(KEY_AUTO, value).apply()
        }

    var askedFineLocation: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ASKED, value).apply()
        }

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_HOST = "custom_host"
        const val KEY_AUTO = "auto_minutes"
        const val KEY_ASKED = "asked_fine"
    }
}
