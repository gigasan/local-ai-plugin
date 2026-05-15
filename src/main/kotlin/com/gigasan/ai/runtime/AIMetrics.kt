package com.gigasan.ai.runtime

import com.gigasan.ai.runtime.parser.Usage
import com.gigasan.ai.ui.chat.TaskStatus
import kotlin.math.roundToLong

object AIMetrics {

    fun formatSizeB(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val unit = "KMGTPE"[exp - 1] + "B"
        return String.format("%.1f %s", bytes / Math.pow(1024.0, exp.toDouble()), unit)
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val unit = "KMGTPE"[exp - 1]
        return String.format("%.1f %s", bytes / Math.pow(1024.0, exp.toDouble()), unit)
    }


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
            // Больше часа: 1h 20m 30s
            hours > 0 ->
                "${hours}h ${minutes}m ${seconds}s"

            // От 1 минуты до 1 часа: 5m 30s
            value >= 60_000 ->
                "${minutes}m ${seconds}s"

            // Меньше минуты: 15.450s
            else ->
                "%d.%03ds".format(seconds, millis)
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

    fun buildFooter(
        durationMs: Long?,
        usage: Usage?,
    ): String {

        val received = formatDuration(durationMs)
        val first = usage?.time_to_first_token_seconds
        val tps = usage?.tokens_per_second

        val input = usage?.inputTokens
        val reasoning = usage?.reasoningTokens
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
            parts.add("first token in ${formatFirstToken(first)}")
        }

        if (tps != null && tps > 0) {
            parts.add("avg ${formatTokensPerSec(tps)} tok/s")
        }

        val header = parts.joinToString("; ")

        return "$header tokens: input=$input; reasoning=$reasoning; output=$result$total ✔"
    }
}