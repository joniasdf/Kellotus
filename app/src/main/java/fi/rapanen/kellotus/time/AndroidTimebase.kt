package fi.rapanen.kellotus.time

import android.os.SystemClock

class AndroidTimebase : Timebase {
    override fun wallUnixNanos(): Long = System.currentTimeMillis() * 1_000_000L

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
