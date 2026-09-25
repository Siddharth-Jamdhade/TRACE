package com.example.trace

import android.content.Context
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.core.widget.doAfterTextChanged
import com.example.trace.databinding.SheetArmingBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * TRACE — the arming sheet.
 *
 * This is where a session begins, and the only place one can begin. It asks the
 * operator what they are about to do, pre-selects the sensors that carry
 * evidence for that kind of work, and lets them correct the suggestion before
 * anything is recorded.
 *
 * Two rules shape the behaviour:
 *   - **Never guess narrow.** Anything the suggestion engine does not recognise
 *     leaves every sensor on, and *Skip* means exactly that: no context, all
 *     sensors. Missing evidence cannot be recovered; extra evidence can be
 *     ignored.
 *   - **Never fight the operator.** Once a toggle is edited by hand, typing no
 *     longer overwrites it — the suggestion line becomes the control that
 *     restores it.
 */
class ArmingSheet : BottomSheetDialogFragment() {

    /**
     * Called when the operator arms a session, with their description (empty when
     * they skipped) and the sensor ids they confirmed.
     */
    var onArm: ((natureOfWork: String, sensorIds: List<String>) -> Unit)? = null

    private var _binding: SheetArmingBinding? = null
    private val binding get() = _binding!!

    /** sensor id -> its toggle, in registry order. */
    private val chips = LinkedHashMap<String, Chip>()

    /** Sensors that physically exist here — the only ones worth offering. */
    private var availableSensors: List<SensorRegistry.SensorSpec> = emptyList()

    /** True once the operator edits a toggle, after which typing stops suggesting. */
    private var overridden = false

    /** Guards against our own [Chip.isChecked] writes looking like user input. */
    private var applyingSuggestion = false

    /** What the toggles currently reflect, so the hint line can say where from. */
    private var suggestion: SensorSuggester.Suggestion = SensorSuggester.everything()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetArmingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        availableSensors = availableSensorsHere()
        buildSensorChips()

        val hidden = SensorRegistry.ALL.size - availableSensors.size
        binding.tvArmingIntro.text = buildString {
            append(
                "TRACE records one session at a time. Describe the work and the " +
                    "sensors that matter are pre-selected — or skip and capture everything."
            )
            if (hidden > 0) {
                append("\n$hidden sensor(s) are not present on this device, so they are hidden.")
            }
        }

        binding.etNatureOfWork.doAfterTextChanged { text ->
            if (overridden) return@doAfterTextChanged
            applySuggestion(SensorSuggester.suggest(text?.toString().orEmpty()))
        }

        binding.tvSuggestion.setOnClickListener {
            if (!overridden) return@setOnClickListener
            overridden = false
            applySuggestion(SensorSuggester.suggest(currentNatureOfWork()))
        }

        // Skip = no context, therefore everything. Stated on the button so a
        // deliberately narrowed selection is never silently overridden.
        binding.btnSkip.setOnClickListener { arm(natureOfWork = "") }
        binding.btnArmSession.setOnClickListener { arm(natureOfWork = currentNatureOfWork()) }
    }

    override fun onStart() {
        super.onStart()
        // Open fully expanded: the toggles are the point of this sheet, and a
        // half-expanded sheet would hide exactly what was asked to be reviewed.
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun currentNatureOfWork(): String =
        binding.etNatureOfWork.text?.toString()?.trim().orEmpty()

    /**
     * Only sensors that exist on this device. An absent sensor can never emit an
     * observation, so offering it would be a toggle that does nothing.
     */
    private fun availableSensorsHere(): List<SensorRegistry.SensorSpec> {
        val manager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return SensorRegistry.ALL
        // Camera and mic are software sources here (type -1); permission is what
        // gates them, and the app cannot reach this sheet without it.
        return SensorRegistry.ALL.filter { it.type < 0 || manager.getDefaultSensor(it.type) != null }
    }

    private fun buildSensorChips() {
        chips.clear()
        for (spec in availableSensors) {
            // `Chip` with isCheckable rather than `FilterChip`: FilterChip is absent
            // from the Material artifact this project resolves.
            val chip = Chip(requireContext()).apply {
                text = "${spec.icon}  ${SensorRegistry.labelFor(spec.id)}"
                isChecked = true
                isCheckable = true
                tag = spec.id
                setOnCheckedChangeListener { _, _ ->
                    if (applyingSuggestion) return@setOnCheckedChangeListener
                    overridden = true
                    updateHintLine()
                    updateArmButton()
                }
            }
            // The role text (what this sensor contributes to reconstruction) is
            // worth having, but a wrap of chips cannot show it inline — long-press
            // surfaces it, and screen readers announce it.
            TooltipCompat.setTooltipText(chip, spec.role)
            chips[spec.id] = chip
            binding.chipGroupSensors.addView(chip)
        }
    }

    private fun selectedSensorIds(): List<String> =
        availableSensors.map { it.id }.filter { chips[it]?.isChecked == true }

    private fun applySuggestion(newSuggestion: SensorSuggester.Suggestion) {
        suggestion = newSuggestion
        applyingSuggestion = true
        for ((id, chip) in chips) {
            chip.isChecked = id in newSuggestion.sensorIds
        }
        applyingSuggestion = false
        updateHintLine()
        updateArmButton()
    }

    private fun updateHintLine() {
        val selected = selectedSensorIds()
        val (text, actionable) = when {
            selected.isEmpty() -> "Select at least one sensor" to false
            overridden -> "Your selection — tap to use the suggestion again" to true
            suggestion.confident -> "Suggested from: ${suggestion.matched.joinToString(", ")}" to false
            else -> "No context given, so every available sensor is on" to false
        }

        val primary = MaterialColors.getColor(binding.tvSuggestion, MaterialR.attr.colorPrimary)
        val muted = MaterialColors.getColor(binding.tvSuggestion, MaterialR.attr.colorOnSurfaceVariant)
        val error = MaterialColors.getColor(binding.tvSuggestion, MaterialR.attr.colorError)

        binding.tvSuggestion.text = text
        binding.tvSuggestion.setTextColor(
            when {
                selected.isEmpty() -> error
                actionable -> primary
                else -> muted
            }
        )
    }

    private fun updateArmButton() {
        binding.btnArmSession.isEnabled = selectedSensorIds().isNotEmpty()
    }

    /**
     * Arms the session and closes the sheet. An empty [natureOfWork] means the
     * operator skipped, which selects everything available — the sheet's toggles
     * are bypassed on purpose, since "skip" means "do not make me choose".
     */
    private fun arm(natureOfWork: String) {
        val chosen =
            if (natureOfWork.isEmpty()) availableSensors.map { it.id }
            else selectedSensorIds()

        if (chosen.isEmpty()) return   // guarded by the disabled button too

        onArm?.invoke(natureOfWork, chosen)
        dismiss()
    }
}
