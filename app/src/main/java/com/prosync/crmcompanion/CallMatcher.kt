package com.prosync.crmcompanion

import kotlin.math.abs

object CallMatcher {
    data class Match(val call: SystemCall, val score: Int)

    fun bestMatch(
        calls: List<SystemCall>,
        sessionStart: Long,
        sessionEnd: Long,
        numberHint: String?
    ): Match? {
        return calls
            .map { call -> Match(call, score(call, sessionStart, sessionEnd, numberHint)) }
            .maxByOrNull { it.score }
    }

    private fun score(call: SystemCall, sessionStart: Long, sessionEnd: Long, numberHint: String?): Int {
        var score = 0

        val startDelta = abs(call.date - sessionStart)
        score += when {
            startDelta <= 10_000L -> 45
            startDelta <= 30_000L -> 35
            startDelta <= 90_000L -> 20
            else -> 0
        }

        val endDelta = abs(call.endedAt - sessionEnd)
        score += when {
            endDelta <= 10_000L -> 40
            endDelta <= 30_000L -> 30
            endDelta <= 90_000L -> 15
            else -> 0
        }

        if (PhoneUtil.sameNumber(call.number, numberHint)) score += 15

        return score
    }
}
