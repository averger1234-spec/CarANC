package com.example.caranc.shared

internal object AncSessionLogFormatter {

    fun formatLine(phase: String, timestampMs: Long, fields: Map<String, Any?>): String {
        return buildString {
            append("{\"ts\":").append(timestampMs)
            append(",\"phase\":\"").append(escape(phase)).append('"')
            for ((key, value) in fields) {
                if (value == null) continue
                append(",\"").append(escape(key)).append("\":")
                append(formatValue(value))
            }
            append('}')
        }
    }

    private fun formatValue(value: Any): String = when (value) {
        is String -> "\"" + escape(value) + '"'
        is Boolean -> value.toString()
        is Int, is Long, is Float, is Double -> value.toString()
        is FloatArray -> floatArrayToJson(value)
        is List<*> -> listToJson(value)
        else -> "\"" + escape(value.toString()) + '"'
    }

    private fun floatArrayToJson(values: FloatArray): String {
        val preview = values.take(8).joinToString(",") { formatFloat(it) }
        val suffix = if (values.size > 8) ",\"truncated\":true,\"total\":${values.size}" else ""
        return "{\"preview\":[$preview]$suffix}"
    }

    private fun listToJson(values: List<*>): String {
        return values.joinToString(prefix = "[", postfix = "]") { formatValue(it ?: "null") }
    }

    private fun formatFloat(value: Float): String {
        return if (value.isNaN() || value.isInfinite()) "0" else "%.4f".format(value)
    }

    private fun escape(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}