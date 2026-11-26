package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.SettingsStore

/**
 * #212: the Output Control sub-screen — the global wrap width (+ presets)
 * and the per-app wrap section moved verbatim from the old single-screen
 * SettingsActivity. The per-app dropdown keeps the #100 mechanics: the
 * displayed value is committed into the draft's width map on a selection
 * change (an invalid value BLOCKS the change, as before), the raw text of
 * the row under the caret stays in [SettingsDraft.pendingAppWrap] for the
 * overview's back-exit validation.
 */
class OutputControlActivity : Activity() {

    private lateinit var settings: com.georgernstgraf.polishedrecognition.config.SettingsStore

    private val wrapWidthField: EditText by lazy { findViewById(R.id.wrap_width) }
    private val appWrapDropdown: AutoCompleteTextView by lazy { findViewById<AutoCompleteTextView>(R.id.app_wrap_app) }
    private val appWrapWidthField: EditText by lazy { findViewById(R.id.app_wrap_width) }
    private val appWrapForgetButton: Button by lazy { findViewById(R.id.app_wrap_forget) }
    private val appWrapEmptyHint: TextView by lazy { findViewById(R.id.app_wrap_empty_hint) }

    /** Learned apps (#100), ordered most-recently-used first. */
    private val appWrapOrder = mutableListOf<String>()
    private val appWrapLabels = mutableMapOf<String, String>()
    private val appWrapWidths = mutableMapOf<String, Int?>()
    private var selectedAppPackage: String? = null
    private var silenceAppWrapListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_output_control)

        settings = (application as PolishedRecognitionApp).settingsStore

        setupListeners()
        loadGlobalWrapWidth()
        loadAppWrapSection()
    }

    private val draft: SettingsDraft get() = SettingsDraft

    private fun setupListeners() {
        wrapWidthField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                wrapWidthField.error = null
                if (s != null && !silenceAppWrapListener) draft.wrapWidth = s.toString()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<Button>(R.id.wrap_80).setOnClickListener {
            wrapWidthField.setText(getString(R.string.wrap_width_preset_80))
        }
        findViewById<Button>(R.id.wrap_120).setOnClickListener {
            wrapWidthField.setText(getString(R.string.wrap_width_preset_120))
        }
        findViewById<Button>(R.id.wrap_200).setOnClickListener {
            wrapWidthField.setText(getString(R.string.wrap_width_preset_200))
        }
        findViewById<Button>(R.id.wrap_off).setOnClickListener { wrapWidthField.setText("0") }

        setupAppWrapSection()
    }

    private fun loadGlobalWrapWidth() {
        silenceAppWrapListener = true
        wrapWidthField.setText(SettingsValidation.wrapWidthText(draft, settings))
        silenceAppWrapListener = false
    }

    /**
     * Per-app line-wrap section (#100): dropdown of learned apps, a value
     * field (empty = global) and a forget button. The dropdown uses the same
     * tap-to-open pattern as the other Settings dropdowns.
     */
    private fun setupAppWrapSection() {
        appWrapDropdown.threshold = Int.MAX_VALUE
        appWrapDropdown.setOnClickListener { if (appWrapOrder.isNotEmpty()) appWrapDropdown.showDropDown() }
        appWrapDropdown.setOnItemClickListener { _, _, position, _ -> selectAppForWrap(position) }
        appWrapWidthField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appWrapWidthField.error = null
                val pkg = selectedAppPackage
                if (s != null && pkg != null && !silenceAppWrapListener) {
                    draft.pendingAppWrap = pkg to s.toString()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        appWrapForgetButton.setOnClickListener { forgetSelectedApp() }
    }

    private fun loadAppWrapSection() {
        val entries = settings.knownAppsByRecency()
        appWrapOrder.clear()
        appWrapLabels.clear()
        appWrapWidths.clear()
        entries.forEach { (pkg, app) ->
            appWrapOrder.add(pkg)
            appWrapLabels[pkg] = app.label
            // Draft pending value wins over the persisted one.
            appWrapWidths[pkg] = draft.appWrapWidths[pkg] ?: app.wrapWidth
        }
        // A pending raw from an earlier visit wins over the store display
        // (draft feeds every sub-screen on entry).
        val pending = draft.pendingAppWrap
        val initial = if (pending != null && pending.first in appWrapOrder) {
            pending.first
        } else {
            appWrapOrder.firstOrNull()
        }
        refreshAppWrapUi(initial)
    }

    private fun refreshAppWrapUi(selectPackage: String?) {
        val labels = appWrapOrder.map { appWrapLabels[it] ?: it }
        silenceAppWrapListener = true
        appWrapDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        )
        val hasApps = appWrapOrder.isNotEmpty()
        appWrapEmptyHint.visibility = if (hasApps) View.GONE else View.VISIBLE
        appWrapDropdown.isEnabled = hasApps
        appWrapWidthField.isEnabled = hasApps
        appWrapForgetButton.isEnabled = hasApps
        if (selectPackage != null) {
            showAppWrapRow(selectPackage)
        } else {
            selectedAppPackage = null
            appWrapDropdown.setText("", false)
            appWrapWidthField.setText("")
        }
        silenceAppWrapListener = false
    }

    private fun showAppWrapRow(packageName: String) {
        selectedAppPackage = packageName
        appWrapDropdown.setText(appWrapLabels[packageName] ?: packageName, false)
        appWrapWidthField.hint = getString(
            R.string.app_wrap_global_hint,
            SettingsValidation.globalWrapWidth(draft, settings)
        )
        val width = appWrapWidths[packageName]
        // The pending raw of THIS row wins over the committed map value — it is
        // what the user last saw (an uncommitted edit survives re-entry).
        val pending = draft.pendingAppWrap
        val display = if (pending?.first == packageName) pending.second else width?.toString() ?: ""
        appWrapWidthField.setText(display)
        appWrapWidthField.error = null
        // The displayed value IS the pending raw value — written explicitly so
        // an unchanged setText (same text → no watcher fire) still tracks it.
        draft.pendingAppWrap = packageName to appWrapWidthField.text.toString()
    }

    private fun selectAppForWrap(position: Int) {
        if (silenceAppWrapListener) return
        val pkg = appWrapOrder.getOrNull(position) ?: return
        appWrapDropdown.dismissDropDown()
        if (pkg == selectedAppPackage) return
        if (!commitAppWrapField()) return
        showAppWrapRow(pkg)
    }

    /**
     * Stores the currently displayed value into the draft's width map (#100).
     * Returns false (and shows an error) when the value is invalid, so the
     * caller can block a selection change.
     */
    private fun commitAppWrapField(): Boolean {
        val pkg = selectedAppPackage ?: return true
        val raw = appWrapWidthField.text.toString().trim()
        if (raw.isEmpty()) {
            draft.appWrapWidths[pkg] = null
            return true
        }
        val value = raw.toIntOrNull()
        if (value == null || (value != 0 && value < SettingsStore.MIN_WRAP_WIDTH)) {
            appWrapWidthField.error = getString(R.string.wrap_width_error)
            return false
        }
        draft.appWrapWidths[pkg] = value
        return true
    }

    private fun forgetSelectedApp() {
        val pkg = selectedAppPackage ?: return
        appWrapOrder.remove(pkg)
        appWrapLabels.remove(pkg)
        appWrapWidths.remove(pkg)
        draft.appWrapWidths.remove(pkg)
        draft.appWrapForgotten.add(pkg)
        if (draft.pendingAppWrap?.first == pkg) draft.pendingAppWrap = null
        settings.forgetKnownApp(pkg)
        selectedAppPackage = null
        refreshAppWrapUi(appWrapOrder.firstOrNull())
    }
}
