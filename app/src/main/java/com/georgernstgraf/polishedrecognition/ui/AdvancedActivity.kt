package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.EvidenceRetention
import com.georgernstgraf.polishedrecognition.config.SttFragmentSizer
import com.georgernstgraf.polishedrecognition.config.SttLatencyProfile

/**
 * #212: the Advanced / Debugging sub-screen — fragment geometry (#124), the
 * Workbench FIFO depth (#172), compression, the live-preview kill switch
 * (#205), the audio-evidence retention slider (#142) and the cross-app keep
 * checkbox (#219), moved verbatim from
 * the old single-screen SettingsActivity. Edits land in [SettingsDraft].
 */
class AdvancedActivity : Activity() {

    private lateinit var settings: com.georgernstgraf.polishedrecognition.config.SettingsStore

    private val compressAudioCheckbox: CheckBox by lazy { findViewById(R.id.compress_audio) }
    /** #205: the live-preview kill switch (read at session start). */
    private val livePreviewCheckbox: CheckBox by lazy { findViewById(R.id.ime_live_preview) }
    /** #219: keep cross-app leftover takes in the Workbench. */
    private val crossAppKeepCheckbox: CheckBox by lazy { findViewById(R.id.cross_app_keep) }
    /** #142: the 0→50-years log slider (0 = no retention, complete off). */
    private val evidenceRetentionSlider: SeekBar by lazy { findViewById(R.id.evidence_retention_slider) }
    private val evidenceRetentionLabel: TextView by lazy { findViewById(R.id.evidence_retention_label) }
    private val sliceAutoCheckbox: CheckBox by lazy { findViewById(R.id.slice_auto) }
    private val sliceSecondsField: EditText by lazy { findViewById(R.id.slice_seconds) }
    private val searchAutoCheckbox: CheckBox by lazy { findViewById(R.id.search_auto) }
    private val searchPercentField: EditText by lazy { findViewById(R.id.search_percent) }
    /** #172: Workbench FIFO depth (epic #166 D2). */
    private val workbenchFifoSizeField: EditText by lazy { findViewById(R.id.workbench_fifo_size) }

    /** Guards the programmatic loads: only USER edits write the draft. */
    private var silenceDraftWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)

        settings = (application as PolishedRecognitionApp).settingsStore

        setupListeners()
        silenceDraftWatchers = true
        loadToggles()
        loadRetentionSlider()
        loadGlobalNumbers()
        loadFragmentGeometry()
        silenceDraftWatchers = false
    }

    private val draft: SettingsDraft get() = SettingsDraft

    private fun setupListeners() {
        compressAudioCheckbox.setOnCheckedChangeListener { _, checked ->
            if (!silenceDraftWatchers) draft.compressAudio = checked
            // The resolved values depend on provider + model + FORMAT — the format
            // is switched on this very screen, so re-resolve when it flips.
            refreshAutoValues()
        }
        livePreviewCheckbox.setOnCheckedChangeListener { _, checked ->
            if (!silenceDraftWatchers) draft.imeLivePreview = checked
        }
        crossAppKeepCheckbox.setOnCheckedChangeListener { _, checked ->
            if (!silenceDraftWatchers) draft.crossAppKeep = checked
        }

        evidenceRetentionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                evidenceRetentionLabel.text = EvidenceRetention.labelFor(progress)
                if (!silenceDraftWatchers) draft.evidenceRetentionPosition = progress
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        // #124: the auto checkboxes own the fields — checked = locked, showing
        // the RESOLVED value in grey; unchecking keeps that value as the start.
        sliceAutoCheckbox.setOnCheckedChangeListener { _, checked ->
            if (!silenceDraftWatchers) {
                draft.sliceAuto = checked
                // Unchecking keeps the displayed (resolved) value as the editable
                // start — it must enter the draft, or the back-exit would
                // validate an EMPTY manual field instead of the shown one.
                if (!checked) draft.sliceSeconds = sliceSecondsField.text.toString()
            }
            setFieldLocked(sliceSecondsField, checked)
        }
        searchAutoCheckbox.setOnCheckedChangeListener { _, checked ->
            if (!silenceDraftWatchers) {
                draft.searchAuto = checked
                if (!checked) draft.searchPercent = searchPercentField.text.toString()
            }
            setFieldLocked(searchPercentField, checked)
        }

        sliceSecondsField.addTextChangedListener(clearErrorWatcher { draft.sliceSeconds = it })
        searchPercentField.addTextChangedListener(clearErrorWatcher { draft.searchPercent = it })
        // #172: clear the red error as soon as the user corrects the value.
        workbenchFifoSizeField.addTextChangedListener(clearErrorWatcher { draft.workbenchFifoSize = it })
    }

    /** TextWatcher that writes the raw text into the draft. */
    private fun clearErrorWatcher(write: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (!silenceDraftWatchers) write(s?.toString() ?: "")
        }
        override fun afterTextChanged(s: Editable?) {}
    }

    private fun loadToggles() {
        compressAudioCheckbox.isChecked = SettingsValidation.compressAudio(draft, settings)
        livePreviewCheckbox.isChecked = SettingsValidation.imeLivePreview(draft, settings)
        crossAppKeepCheckbox.isChecked = SettingsValidation.crossAppKeep(draft, settings)
    }

    /** #142: the retention slider — the label tracks the position. */
    private fun loadRetentionSlider() {
        evidenceRetentionSlider.max = EvidenceRetention.MAX_POSITION
        val retentionPosition = SettingsValidation.evidenceRetentionPosition(draft, settings)
        evidenceRetentionSlider.progress = retentionPosition
        evidenceRetentionLabel.text = EvidenceRetention.labelFor(retentionPosition)
    }

    private fun loadGlobalNumbers() {
        workbenchFifoSizeField.setText(SettingsValidation.fifoText(draft, settings))
    }

    /**
     * #124: loads the fragment geometry. Auto (the default) shows the
     * RESOLVED value in grey — the same value the next session will use, from
     * the auto-sizer or the adaptive percentage rule. Unchecking auto keeps
     * that value as the editable starting point.
     */
    private fun loadFragmentGeometry() {
        sliceAutoCheckbox.isChecked = SettingsValidation.sliceAuto(draft, settings)
        searchAutoCheckbox.isChecked = SettingsValidation.searchAuto(draft, settings)
        val sliceText = SettingsValidation.sliceSecondsText(draft, settings)
        if (sliceText.isNotEmpty()) sliceSecondsField.setText(sliceText)
        val searchText = SettingsValidation.searchPercentText(draft, settings)
        if (searchText.isNotEmpty()) searchPercentField.setText(searchText)
        refreshAutoValues()
    }

    /** Re-resolves the grey auto values (provider/model/format dependent). */
    private fun refreshAutoValues() {
        val sizer = SttFragmentSizer(settings)
        if (sliceAutoCheckbox.isChecked) {
            val slice = sizer.sliceSeconds() ?: SttLatencyProfile.BOOTSTRAP_SLICE_SECONDS
            sliceSecondsField.setText(SettingsValidation.formatNumber(slice.toFloat()))
        }
        if (searchAutoCheckbox.isChecked) {
            val percent = sizer.searchPercent() ?: SttLatencyProfile.BOOTSTRAP_SEARCH_PERCENT
            searchPercentField.setText(SettingsValidation.formatNumber(percent.toFloat()))
        }
        setFieldLocked(sliceSecondsField, sliceAutoCheckbox.isChecked)
        setFieldLocked(searchPercentField, searchAutoCheckbox.isChecked)
    }

    private fun setFieldLocked(field: EditText, locked: Boolean) {
        field.isEnabled = !locked
        // #160: never take the theme colour as an Int — an OEM night skin resolves these
        // attributes to a ColorStateList, whose resource id would be read as ARGB (alpha
        // 0x01) and made the field invisible.
        val colors = themeColorStateList(
            if (locked) android.R.attr.textColorTertiary else android.R.attr.textColorPrimary
        )
        // Locked = read-only display of the resolved value. `isEnabled = false` makes the
        // selector apply its DISABLED entry on top, which stacks a second dimming on the
        // already-secondary tertiary colour (measured 2.12:1 on the OnePlus in dark mode).
        // Take the enabled variant so the greyed value stays readable.
        val state = if (locked) intArrayOf(android.R.attr.state_enabled) else field.drawableState
        field.setTextColor(colors.getColorForState(state, colors.defaultColor))
    }
}
