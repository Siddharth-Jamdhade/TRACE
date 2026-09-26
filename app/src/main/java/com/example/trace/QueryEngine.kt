package com.example.trace

/**
 * TRACE — Keyword Query Engine (MVP, no LLM required)
 *
 * Routes plain-text questions to the correct timeline operation using
 * simple keyword matching. All answers are produced from deterministic
 * list-filter operations — never fabricated.
 *
 * Queries work on deviation entries: no status labels, no predefined event
 * types. Questions filter by sensor source, deviation magnitude, or
 * temporal relationships.
 */
object QueryEngine {

    /** Main entry point. Returns a plain-text answer string. */
    fun answerQuery(question: String, events: List<Event>): String {
        if (events.isEmpty()) {
            return "No events recorded yet.\nTrigger a physical incident to build the TRACE timeline."
        }
        val q      = question.lowercase().trim()
        val sorted = events.sortedBy { it.timestamp }

        return when {
            "before" in q -> {
                val target = findTarget(q, sorted)
                val preds  = getPredecessors(target, sorted)
                if (preds.isEmpty()) "Nothing recorded before '${target.type}'."
                else "Before '${target.type}' @ ${fmt(target.timestamp)}:\n${describeList(preds)}"
            }
            "after" in q -> {
                val target = findTarget(q, sorted)
                val succs  = getSuccessors(target, sorted)
                if (succs.isEmpty()) "Nothing recorded after '${target.type}'."
                else "After '${target.type}' @ ${fmt(target.timestamp)}:\n${describeList(succs)}"
            }
            "why" in q || "explain" in q || "reason" in q -> {
                explainDeviation(findTarget(q, sorted))
            }
            "last" in q || "recent" in q || "latest" in q -> {
                val last = sorted.last()
                "Most recent: ${last.type} @ ${fmt(last.timestamp)} — source: ${last.source}, " +
                    "confidence: ${"%.0f".format(last.confidence * 100)}%"
            }
            "how many" in q || "count" in q || "total" in q -> {
                val bySource = events.groupingBy { it.source }.eachCount()
                val sourceStr = bySource.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}=${it.value}" }
                "Total: ${sorted.size} events  |  $sourceStr"
            }
            "sigma" in q || "sigma >=" in q || "high sigma" in q -> {
                val threshold = 3.0
                val list = sorted.filter { (it.deviationSigma ?: 0.0) >= threshold }
                if (list.isEmpty()) "No deviations at ≥${threshold}σ."
                else "Deviations ≥${threshold}σ:\n${describeList(list)}"
            }
            "strong" in q || "highest" in q -> {
                val top = sorted.sortedByDescending { it.confidence }.take(5)
                "Highest-confidence deviations:\n${describeList(top)}"
            }
            "camera" in q || "visual" in q -> {
                val list = sorted.filter { it.source == "camera" }
                if (list.isEmpty()) "No camera deviations recorded."
                else "Camera deviations:\n${describeList(list)}"
            }
            "audio" in q || "sound" in q -> {
                val list = sorted.filter { it.source == "audio" }
                if (list.isEmpty()) "No audio deviations recorded."
                else "Audio deviations:\n${describeList(list)}"
            }
            "motion" in q || "shake" in q || "move" in q -> {
                val list = sorted.filter { it.source == "motion" }
                if (list.isEmpty()) "No motion deviations recorded."
                else "Motion deviations:\n${describeList(list)}"
            }
            "magnetometer" in q || "magnetic" in q -> {
                val list = sorted.filter { it.source == "magnetometer" }
                if (list.isEmpty()) "No magnetometer deviations recorded."
                else "Magnetometer deviations:\n${describeList(list)}"
            }
            "barometer" in q || "pressure" in q -> {
                val list = sorted.filter { it.source == "barometer" }
                if (list.isEmpty()) "No barometer deviations recorded."
                else "Barometer deviations:\n${describeList(list)}"
            }
            "light" in q || "lights" in q -> {
                val list = sorted.filter { it.source == "light" }
                if (list.isEmpty()) "No light sensor deviations recorded."
                else "Light sensor deviations:\n${describeList(list)}"
            }
            "gyroscope" in q || "gyro" in q -> {
                val list = sorted.filter { it.source == "gyroscope" }
                if (list.isEmpty()) "No gyroscope deviations recorded."
                else "Gyroscope deviations:\n${describeList(list)}"
            }
            "linear" in q || "free" in q -> {
                val list = sorted.filter { it.source == "linear" }
                if (list.isEmpty()) "No linear acceleration deviations recorded."
                else "Linear acceleration deviations:\n${describeList(list)}"
            }
            "step" in q || "footstep" in q || "person" in q || "someone" in q -> {
                val list = sorted.filter { it.source == "step" || it.source == "sigmotion" }
                if (list.isEmpty()) "No step or motion-trigger deviations recorded."
                else "Step/motion-trigger deviations:\n${describeList(list)}"
            }
            "manual" in q || "tag" in q -> {
                val list = sorted.filter { it.source == SensorRegistry.MANUAL.id }
                if (list.isEmpty()) "No incidents were tagged by the operator."
                else "Operator-tagged incidents (human assertions, not sensor readings):\n${describeList(list)}"
            }
            "show" in q || "list" in q || "all" in q -> {
                "All ${sorted.size} events:\n${describeList(sorted)}"
            }
            else -> {
                "I can answer:\n" +
                "  before/after [sensor]  - what happened around an event\n" +
                "  explain [nth]          - deviation context breakdown\n" +
                "  show last event        - most recent entry\n" +
                "  how many events?       - counts by source\n" +
                "  high sigma / strong    - most unusual deviations\n" +
                "  list all / camera / audio / motion / magnetometer / barometer / light / manual"
            }
        }
    }

    // ------ Graph operations (sorted-list operations) ------------------

    fun getPredecessors(target: Event, allEvents: List<Event>): List<Event> =
        allEvents.filter { it.timestamp < target.timestamp }.takeLast(3)

    fun getSuccessors(target: Event, allEvents: List<Event>): List<Event> =
        allEvents.filter { it.timestamp > target.timestamp }.take(3)

    /** Human-readable explanation of a deviation entry's context. */
    fun explainDeviation(event: Event): String = buildString {
        appendLine("Event : ${event.type.replace("_", " ")}")
        appendLine("Source: ${SensorRegistry.labelFor(event.source)}")
        appendLine("Confidence: ${"%.0f".format(event.confidence * 100)}%")
        appendLine()
        appendLine("Deviation Context:")
        event.baselineValue?.let { appendLine("  Baseline mean: ${"%.4f".format(it)}") }
            ?: appendLine("  Baseline mean: not available")
        event.observedValue?.let { appendLine("  Observed value: ${"%.4f".format(it)}") }
            ?: appendLine("  Observed value: not available")
        event.deviationSigma?.let { sigma ->
            appendLine("  Deviation: ${"%.1f".format(sigma)}σ from baseline")
            appendLine()
            append(when {
                sigma >= 5.0 -> "Very strong deviation (>5σ) — extremely unlikely under normal conditions."
                sigma >= 3.0 -> "Strong deviation (>3σ) — unlikely to be random fluctuation."
                sigma >= 2.0 -> "Moderate deviation (>2σ) — worth noting, may be contextual."
                else         -> "Mild deviation — above baseline but within a typical range."
            })
        } ?: appendLine("  Deviation sigma: not available (threshold-based detection)")
    }

    // ------ Helpers ---------------------------------------------------

    private fun findTarget(query: String, events: List<Event>): Event {
        // Try matching against sensor source names
        val sourceKeywords = listOf(
            "camera", "audio", "motion", "magnetometer", "barometer",
            "light", "linear", "gyroscope", "step", "manual"
        )
        for (kw in sourceKeywords) {
            if (kw in query) {
                return events.lastOrNull { kw in it.source } ?: events.last()
            }
        }
        return events.last()
    }

    private fun describeList(events: List<Event>): String =
        events.joinToString("\n") { e ->
            val sigma = e.deviationSigma?.let { " σ=${"%.1f".format(it)}" } ?: ""
            "  * ${e.type.replace("_", " ")} @ ${fmt(e.timestamp)} [${SensorRegistry.labelFor(e.source)} ${"%.0f".format(e.confidence * 100)}%]$sigma"
        }

    private fun fmt(ts: Long): String = TimestampDisplay.formatTime(ts)
}