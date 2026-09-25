package com.example.trace

/**
 * TRACE — sensor suggestion for the arming sheet.
 *
 * The operator says what they are about to do ("door inspection, level 2") and
 * this maps that phrase onto the sensors whose signal actually carries evidence
 * for that kind of incident.
 *
 * It is a keyword table rather than a model, deliberately: the mapping has to be
 * explainable ("why is the magnetometer on?" → "you said *door*, and door hinges
 * and frames are steel") and has to be identical on every device.
 *
 * The failure mode is chosen, not accidental: **anything unrecognised returns
 * every sensor.** Over-capturing costs storage, which is recoverable;
 * under-capturing destroys evidence, which is not. A generic phrase such as
 * "inspection" is unrecognised on purpose and therefore captures everything.
 */
object SensorSuggester {

    /**
     * @param sensorIds ids to pre-select. Never empty, never a software source.
     * @param matched   the keywords that drove the choice, for display.
     * @param confident false when nothing was recognised and every sensor is used.
     */
    data class Suggestion(
        val sensorIds: List<String>,
        val matched: List<String>,
        val confident: Boolean
    )

    private data class Rule(val keywords: List<String>, val sensorIds: List<String>)

    /**
     * Keyword → evidence mapping. Keywords are matched as substrings of the
     * lowercased phrase, so "doors", "doorway" and "unload" (⊃ "load") all land.
     */
    private val RULES = listOf(
        Rule(
            listOf("door", "gate", "shutter", "entrance", "hatch"),
            listOf("magnetometer", "barometer", "audio", "motion", "camera")
        ),
        Rule(
            listOf("shelf", "rack", "stack", "warehouse", "storage", "pallet", "crate"),
            listOf("motion", "camera", "audio", "linear")
        ),
        Rule(
            listOf("person", "people", "worker", "employee", "someone", "walk", "foot", "patrol", "intrud", "visitor"),
            listOf("step", "sigmotion", "camera")
        ),
        Rule(
            listOf("machine", "equipment", "motor", "engine", "conveyor", "vibrat", "generator", "compressor"),
            listOf("motion", "audio", "gyroscope", "magnetometer")
        ),
        Rule(
            listOf("light", "lamp", "power", "switch", "dark", "electric"),
            listOf("light", "camera")
        ),
        Rule(
            listOf("fall", "drop", "collaps", "height", "topple", "slip", "trip", "hang"),
            listOf("linear", "gyroscope", "motion", "camera", "audio")
        ),
        Rule(
            listOf("load", "truck", "vehicle", "forklift", "deliver", "cargo", "cart"),
            listOf("magnetometer", "motion", "camera", "audio")
        )
    )

    /** Every sensor the app can capture from, in registry order. */
    fun all(): List<String> = SensorRegistry.ALL.map { it.id }

    /** The suggestion used when the operator skips the prompt entirely. */
    fun everything(): Suggestion = Suggestion(all(), emptyList(), confident = false)

    fun suggest(natureOfWork: String): Suggestion {
        val text = natureOfWork.lowercase()
        val matchedRules = RULES.filter { rule -> rule.keywords.any { it in text } }
        if (matchedRules.isEmpty()) return everything()

        // Union of the matched rules, kept in registry order so the toggle sheet
        // never reorders itself under the user's finger while they type.
        val chosen = all().filter { id -> matchedRules.any { id in it.sensorIds } }
        if (chosen.isEmpty()) return everything()   // a rule naming only unknown sensors

        val matched = matchedRules.flatMap { rule -> rule.keywords.filter { it in text } }
        return Suggestion(chosen, matched.distinct(), confident = true)
    }
}
