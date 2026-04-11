package com.gigasan.localai

import kotlin.math.roundToLong

object AIMetrics {

    // -----------------------------
    // DURATION FORMATTING
    // -----------------------------

    fun formatDuration(ms: Long?): String {
        val value = ms ?: return "0ms"

        val hours = value / 3_600_000
        val minutes = (value % 3_600_000) / 60_000
        val seconds = (value % 60_000) / 1000
        val millis = value % 1000

        return when {
            hours > 0 ->
                "%2d:%02d:%02d.%03dh".format(hours, minutes, seconds, millis)

            minutes > 0 ->
                "%2d:%02d.%03dm".format(minutes, seconds, millis)

            else ->
                "%2d.%03ds".format(seconds, millis)
        }
    }

    // -----------------------------
    // FIRST TOKEN TIME (Float sec → ms)
    // -----------------------------

    fun firstTokenMs(seconds: Float?): Long? {
        return seconds?.let { (it * 1000f).roundToLong() }
    }

    fun formatFirstToken(seconds: Float?): String {
        val ms = firstTokenMs(seconds)
        return formatDuration(ms)
    }

    // -----------------------------
    // TOKENS / SEC
    // -----------------------------

    fun formatTokensPerSec(value: Float?): String {
        val v = value ?: return "0.00"
        return "%.2f".format(v)
    }

    // -----------------------------
    // SIMPLE DESCRIPTION BUILDER
    // -----------------------------
    fun buildDescription(
        request: String,
        content: String,
        status: TaskStatus,
        maxLen: Int = 40
    ): String {

        fun truncate(text: String, n: Int): String {
            val clean = text.replace("\n", " ").trim()
            return if (clean.length > n) {
                clean.take(n) + "..."
            } else {
                clean
            }
        }

        val statusLine = status.name + ": "
        val requestLine = truncate(request, maxLen) + " "
        val contentLine = truncate(content, maxLen)


        return "$statusLine$requestLine$contentLine"
    }

    fun buildHint(
        durationMs: Long?,
        usage: Usage?,
    ): String {

        val received = formatDuration(durationMs)
        val first = usage?.time_to_first_token_seconds
        val tps = usage?.tokens_per_second

        val input = usage?.inputTokens
        val reasoning = usage?.reasoning_tokens
        val output = usage?.outputTokens

        val result = if (output != null && reasoning != null) {
            output - reasoning
        } else {
            null
        }

        val total = if (input != null && result != null && reasoning != null) {
            val sum = input + result + reasoning
            "; total=$sum"
        } else {
            ""
        }

        // 🔥 собираем первую строку динамически
        val parts = mutableListOf<String>()
        parts.add("received in $received")

        if (first != null && first > 0) {
            parts.add("first in ${formatFirstToken(first)}")
        }

        if (tps != null && tps > 0) {
            parts.add("avg ${formatTokensPerSec(tps)}/s")
        }

        val header = parts.joinToString("; ") + " ✔"

        return "$header\n\n" +
                "input=$input; output=$result; reasoning=$reasoning$total"
    }
}