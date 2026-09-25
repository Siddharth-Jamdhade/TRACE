package com.example.trace

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRACE — Keyword Query Engine (MVP, no LLM required)
 *
 * Routes plain-text questions to the correct timeline operation using
 * simple keyword matching. All answers are produced from deterministic
 * list-filter operations — never fabricated.
 */
object QueryEngine {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
                explainConfidence(findTarget(q, sorted))
            }
            "last" in q || "recent" in q || "latest" in q -> {
                val last = sorted.last()
                "Most recent: ${last.type} @ ${fmt(last.timestamp)} -- ${last.status}"
            }
            "how many" in q || "count" in q || "total" in q -> {
                val c = sorted.count { it.status == "CONFIRMED" }
                val u = sorted.count { it.status == "UNCONFIRMED" }
                val r = sorted.count { it.status == "REJECTED" }
                val m = sorted.count { it.source == SensorRegistry.MANUAL.id }
                "Total: ${sorted.size}  CONFIRMED: $c  UNCONFIRMED: $u  REJECTED: $r" +
                    if (m > 0) "  MANUAL: $m" else ""
            }
            "confirmed" in q && "unconfirmed" !in q -> {
                val list = sorted.filter { it.status == "CONFIRMED" }
                if (list.isEmpty()) "No confirmed events yet."
                else "Confirmed events:\n${describeList(list)}"
            }
            "unconfirmed" in q -> {
                val list = sorted.filter { it.status == "UNCONFIRMED" }
                if (list.isEmpty()) "No unconfirmed events."
                else "Unconfirmed events:\n${describeList(list)}"
            }
            "fall" in q || "impact" in q -> {
                val list = sorted.filter { "fall" in it.type || "impact" in it.type }
                if (list.isEmpty()) "No fall or impact events recorded."
                else "Fall/Impact events:\n${describeList(list)}"
            }
            "door" in q -> {
                val list = sorted.filter { "door" in it.type }
                if (list.isEmpty()) "No door events recorded."
                else "Door events:\n${describeList(list)}"
            }
            "person" in q || "someone" in q -> {
                val list = sorted.filter { it.type == "person_present" }
                if (list.isEmpty()) "No footsteps recorded."
                else "Person presence:\n${describeList(list)}"
            }
            "light" in q || "lights" in q -> {
                val list = sorted.filter { it.type == "lights_off" }
                if (list.isEmpty()) "No lights_off events recorded."
                else "Lights-off events:\n${describeList(list)}"
            }
            "pressure" in q -> {
                val list = sorted.filter { it.type == "pressure_shift" }
                if (list.isEmpty()) "No pressure shifts recorded."
                else "Pressure shifts:\n${describeList(list)}"
            }
            "alarm" in q || "sound" in q || "audio" in q -> {
                val list = sorted.filter { it.source == "audio" }
                if (list.isEmpty()) "No audio events recorded."
                else "Audio events:\n${describeList(list)}"
            }
            "motion" in q || "shake" in q || "move" in q -> {
                val list = sorted.filter { it.source == "motion" }
                if (list.isEmpty()) "No motion events recorded."
                else "Motion events:\n${describeList(list)}"
            }
            "camera" in q || "visual" in q -> {
                val list = sorted.filter { it.source == "camera" }
                if (list.isEmpty()) "No visual events recorded."
                else "Visual events:\n${describeList(list)}"
            }
            // Operator assertions. These have to be tested before the generic
            // "list all" branch below, because a query like "list manual tags"
            // contains "list" and would otherwise never reach this case.
            "manual" in q || "tag" in q -> {
                val list = sorted.filter { it.source == SensorRegistry.MANUAL.id }
                if (list.isEmpty()) "No incidents were tagged by the operator."
                else "Operator-tagged incidents (human assertions, not sensor verdicts):\n${describeList(list)}"
            }
            "show" in q || "list" in q || "all" in q -> {
                "All ${sorted.size} events:\n${describeList(sorted)}"
            }
            else -> {
                "I can answer:\n" +
                "  before/after [event]    - what happened around an event\n" +
                "  why was it confirmed?   - sensor evidence breakdown\n" +
                "  show last event         - most recent entry\n" +
                "  how many events?        - status counts\n" +
                "  list all / confirmed / fall / door / person / lights / pressure / manual"
            }
        }
    }

    // ------ Graph operations (sorted-list operations) ------------------

    fun getPredecessors(target: Event, allEvents: List<Event>): List<Event> =
        allEvents.filter { it.timestamp < target.timestamp }.takeLast(3)

    fun getSuccessors(target: Event, allEvents: List<Event>): List<Event> =
        allEvents.filter { it.timestamp > target.timestamp }.take(3)

    /** Human-readable explanation of an event's evidence and status. */
    fun explainConfidence(event: Event): String = buildString {
        appendLine("Event : ${event.type.replace("_", " ")}")
        appendLine("Status: ${event.status}")
        appendLine()
        appendLine("Sensor Evidence:")
        confLine(this, "Camera", event.cameraConfidence)
        confLine(this, "Audio ", event.audioConfidence)
        confLine(this, "Motion", event.motionConfidence)
        // Extra sensors (magnetometer, barometer, light, linear, gyro, step)
        event.sensorBreakdown?.split("|")?.filter { it.isNotBlank() }?.forEach { pair ->
            val sensor = pair.substringBefore(':')
            val conf   = pair.substringAfter(':').toFloatOrNull()
            if (conf != null) appendLine("  ${SensorRegistry.labelFor(sensor)}: ${"%.0f".format(conf * 100)}%")
        }
        appendLine()
        val strong = listOfNotNull(event.cameraConfidence, event.audioConfidence, event.motionConfidence)
            .count { it > 0.6f }
        append(when (event.status) {
            "CONFIRMED"   -> "CONFIRMED: $strong independent sensors agreed (>60% each)."
            "UNCONFIRMED" -> "UNCONFIRMED: Only 1 sensor detected this. Human review recommended."
            "REJECTED"    -> "REJECTED: No sensor exceeded the confidence threshold."
            "MANUAL"      -> "MANUAL: asserted by the operator on scene. No sensor agreement was required, " +
                             "so read this as a human statement rather than a measurement."
            else          -> "Status unknown."
        })
    }

    // ------ Helpers ---------------------------------------------------

    private fun confLine(sb: StringBuilder, label: String, conf: Float?) {
        if (conf != null) sb.appendLine("  $label: ${"%.0f".format(conf * 100)}%")
        else              sb.appendLine("  $label: no signal")
    }

    private fun findTarget(query: String, events: List<Event>): Event {
        // Normalize query spaces to underscores for easier exact matching
        val normalizedQuery = query.replace(" ", "_")
        val keywords = listOf(
            "object_falls", "object_moves", "impact", "alarm", "abnormal_sound",
            "fall", "impact", "alarm", "sound", "move", "camera", "motion", "audio"
        )
        for (kw in keywords) {
            if (kw in normalizedQuery || kw in query) {
                return events.lastOrNull { kw in it.type || kw == it.source } ?: events.last()
            }
        }
        return events.last()
    }

    private fun describeList(events: List<Event>): String =
        events.joinToString("\n") { e ->
            "  * ${e.type.replace("_", " ")} @ ${fmt(e.timestamp)} [${e.status}]"
        }

    private fun fmt(ts: Long): String = timeFmt.format(Date(ts))
}
