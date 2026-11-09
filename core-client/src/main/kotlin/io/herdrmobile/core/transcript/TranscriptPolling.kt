package io.herdrmobile.core.transcript

/** Uses a monotonic clock for scheduling; host timestamps are reserved for displayed activity. */
internal class TranscriptPolling(
    requestedFastMs:Long,
    private val nowMs:()->Long = { System.nanoTime()/1_000_000 },
) {
    private val fastMs=minOf(requestedFastMs,1_000L)
    private var changedAt:Long?=null
    @Synchronized fun changed() { changedAt=nowMs() }
    @Synchronized fun delayMs(status:String?):Long {
        val recent=changedAt?.let { nowMs()-it<15_000 }==true
        return if(status=="working" || status=="blocked" || recent) fastMs else fastMs*4
    }
}
