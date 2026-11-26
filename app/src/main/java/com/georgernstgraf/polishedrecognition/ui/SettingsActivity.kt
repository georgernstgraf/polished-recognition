package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedDispatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageButton
import android.widget.TextView
import com.georgernstgraf.polishedrecognition.BuildConfig
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.CustomLanguages

/**
 * #212: the Settings OVERVIEW. The one long screen became this hub plus four
 * category sub-screens (`ProvidersActivity`, `PromptsActivity`,
 * `OutputControlActivity`, `AdvancedActivity`). What stays here:
 *
 *  - the target-language section (write-through as always — the IME writes
 *    the same field, so language commits are never deferred),
 *  - the four category buttons,
 *  - `set_recognition_service` + the About section.
 *
 * There is NO Save button any more. Leaving Settings = back on THIS screen:
 * the back-exit validates the draft's effective values (`SettingsValidation`)
 * and applies it — success → "Settings saved", failure → the check name is
 * toasted and only the failing entries keep their persisted value.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: com.georgernstgraf.polishedrecognition.config.SettingsStore
    private lateinit var promptStore: com.georgernstgraf.polishedrecognition.pipeline.PromptStore
    private lateinit var app: PolishedRecognitionApp

    private val targetLanguageDropdown: AutoCompleteTextView by lazy { findViewById<AutoCompleteTextView>(R.id.target_language) }
    private var languageEditPrevious: String = ""

    private val aboutVersionText: TextView by lazy { findViewById(R.id.about_version) }
    private val aboutCommitText: TextView by lazy { findViewById(R.id.about_commit) }
    private val aboutInfoText: TextView by lazy { findViewById(R.id.about_info) }
    private val aboutHelpText: TextView by lazy { findViewById(R.id.about_help) }

    /** True once a back-exit ran — guards the (impossible-in-practice) double delivery. */
    @Volatile
    private var backExitHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        app = application as PolishedRecognitionApp
        settings = app.settingsStore
        promptStore = app.promptStore

        setupLanguageSection()
        setupCategoryButtons()
        findViewById<Button>(R.id.set_recognition_service).setOnClickListener { openVoiceKeyboardSettings() }
        setupAboutSection()
        registerBackExit()
    }

    /**
     * Back-exit interception, both platform paths (the manifest does not
     * enable `enableOnBackInvokedCallback`, so the classic `onBackPressed`
     * route stays legal; the dispatcher callback covers API ≥ 33 when the
     * attribute is ever switched on). NO androidx involved.
     */
    private fun registerBackExit() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBackExit() }
        }
    }

    // `GestureBackNavigation`: lint assumes predictive back is enabled and would
    // send every back gesture straight to the dispatcher callback. This app does
    // NOT set `android:enableOnBackInvokedCallback` in the manifest (API ≤ 32
    // devices have no dispatcher at all, minSdk 30), so the classic
    // `onBackPressed` route is the LIVE path here; the dispatcher callback
    // (registered for API ≥ 33) routes into the same handler. Both paths are
    // needed; the guard in `handleBackExit` makes a double delivery a no-op.
    @Deprecated("Overrides in both back paths route into the same handler (#212)")
    @Suppress("GestureBackNavigation")
    override fun onBackPressed() {
        handleBackExit()
    }

    /** The leaving-Settings moment: validate + apply the draft, then finish. */
    internal fun handleBackExit() {
        if (backExitHandled) return
        backExitHandled = true
        val draft = SettingsDraft
        val failed = if (draft.isEmpty) emptyList() else SettingsValidation.failedChecks(draft, settings)

        if (failed.isNotEmpty()) {
            // A save attempt that stops at a validation check must never look
            // like "nothing happened" (#150): name the failed check in a
            // toast, in logcat and in the adb-readable rotating log. The
            // screen closes anyway — only the failing entries keep their
            // previous persisted value.
            failed.forEach { logAbortedCheck(it) }
            Toasts.show(this, getString(R.string.settings_save_blocked, failed.first()))
        } else {
            Toasts.short(this, getString(R.string.saved))
        }
        SettingsValidation.apply(this, draft, settings, promptStore, failed.toSet())
        draft.clear()
        finish()
    }

    /**
     * Same log contract as the old saveAborted (#150): one JSON line per
     * failed check into the rotating `settings` log, plus logcat.
     */
    private fun logAbortedCheck(check: String) {
        runCatching {
            val line = """{"ts":${System.currentTimeMillis()},"pid":${Process.myPid()},"event":"settings-save-aborted","check":"$check"}"""
            app.jsonLogger.log("settings", line)
        }.onFailure { android.util.Log.w(TAG, "settings-save log failed for $check", it) }
        android.util.Log.w(TAG, "settings-save-aborted check=$check")
    }

    // ---------------------------------------------------------------- categories

    private fun setupCategoryButtons() {
        findViewById<Button>(R.id.category_providers).setOnClickListener {
            startActivity(Intent(this, ProvidersActivity::class.java))
        }
        findViewById<Button>(R.id.category_prompts).setOnClickListener {
            startActivity(Intent(this, PromptsActivity::class.java))
        }
        findViewById<Button>(R.id.category_output).setOnClickListener {
            startActivity(Intent(this, OutputControlActivity::class.java))
        }
        findViewById<Button>(R.id.category_advanced).setOnClickListener {
            startActivity(Intent(this, AdvancedActivity::class.java))
        }
    }

    // ---------------------------------------------------------------- target language

    private fun setupLanguageSection() {
        val langAdapter = LanguageDropdownAdapter()
        targetLanguageDropdown.setAdapter(langAdapter)

        // Provider/target-language dropdowns use inputType=none; tap → showDropDown()
        // with threshold = Int.MAX_VALUE (PITFALLS #37 tap-to-open selector).
        targetLanguageDropdown.threshold = Int.MAX_VALUE
        targetLanguageDropdown.setOnClickListener { targetLanguageDropdown.showDropDown() }

        // Long-press the selected language to edit it inline (old Material behavior, #48):
        // the edit commits directly to the list — existing names are selected, new names are
        // appended. Short tap keeps opening the dropdown (#37 tap-to-open selector).
        targetLanguageDropdown.setOnLongClickListener {
            enterLanguageEditMode()
            true
        }

        findViewById<TextView>(R.id.manage_custom_languages).setOnClickListener { showManageLanguagesDialog() }

        loadTargetLanguage()
    }

    private fun loadTargetLanguage() {
        targetLanguageDropdown.setText(settings.targetLanguage ?: CustomLanguages.NONE_TARGET_LANGUAGE, false)
        settings.targetLanguage?.let { tl ->
            if (tl.isNotBlank() && tl != CustomLanguages.NONE_TARGET_LANGUAGE && tl != CustomLanguages.BUILTIN_LANGUAGE && tl !in settings.customLanguages) {
                settings.customLanguages = settings.customLanguages + tl
            }
        }
    }

    private fun showManageLanguagesDialog() {
        var dialog: AlertDialog? = null

        val items = ArrayList(settings.customLanguages)
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(pos: Int) = items[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val lang = items[pos]
                val view = convertView ?: LayoutInflater.from(this@SettingsActivity)
                    .inflate(R.layout.item_manage_language, parent, false)
                view.findViewById<TextView>(R.id.language_name).also {
                    it.text = lang
                    it.setOnClickListener {
                        settings.targetLanguage = lang
                        targetLanguageDropdown.setText(lang, false)
                        dialog?.dismiss()
                    }
                }
                view.findViewById<ImageButton>(R.id.edit_button).setOnClickListener {
                    showRenameLanguageDialog(lang) { refresh() }
                }
                view.findViewById<ImageButton>(R.id.delete_button).setOnClickListener {
                    deleteCustomLanguage(lang)
                    refresh()
                }
                return view
            }

            fun refresh() {
                items.clear()
                items.addAll(settings.customLanguages)
                notifyDataSetChanged()
                if (items.isEmpty()) dialog?.dismiss()
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.saved_languages_title)
            .apply {
                if (items.isEmpty()) setMessage(R.string.no_saved_languages_hint)
            }
            .setAdapter(adapter, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun enterLanguageEditMode() {
        languageEditPrevious = targetLanguageDropdown.text.toString()
        targetLanguageDropdown.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        targetLanguageDropdown.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        targetLanguageDropdown.isFocusable = true
        targetLanguageDropdown.isFocusableInTouchMode = true
        targetLanguageDropdown.isCursorVisible = true
        targetLanguageDropdown.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(targetLanguageDropdown, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        targetLanguageDropdown.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commitLanguageEdit()
                true
            } else {
                false
            }
        }
        targetLanguageDropdown.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && languageEditMode) commitLanguageEdit()
        }
    }

    private val languageEditMode: Boolean
        get() = targetLanguageDropdown.isFocusableInTouchMode

    private fun commitLanguageEdit() {
        if (!languageEditMode) return
        exitLanguageEditMode()
        val value = targetLanguageDropdown.text.toString()
        when (val result = CustomLanguages.commitEdit(settings.customLanguages, value)) {
            is CustomLanguages.CommitResult.Selected -> {
                settings.targetLanguage = result.language
                targetLanguageDropdown.setText(result.language, false)
            }
            is CustomLanguages.CommitResult.Added -> {
                settings.customLanguages = result.newList
                settings.targetLanguage = result.language
                targetLanguageDropdown.setText(result.language, false)
                (targetLanguageDropdown.adapter as? LanguageDropdownAdapter)?.rebuild()
                Toasts.short(this, getString(R.string.language_added))
            }
            CustomLanguages.CommitResult.Reverted ->
                targetLanguageDropdown.setText(languageEditPrevious, false)
        }
    }

    private fun exitLanguageEditMode() {
        targetLanguageDropdown.isCursorVisible = false
        targetLanguageDropdown.isFocusable = false
        targetLanguageDropdown.isFocusableInTouchMode = false
        targetLanguageDropdown.inputType = android.text.InputType.TYPE_CLASS_TEXT
        targetLanguageDropdown.setOnEditorActionListener(null)
        targetLanguageDropdown.setOnFocusChangeListener(null)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(targetLanguageDropdown.windowToken, 0)
    }

    private fun deleteCustomLanguage(name: String) {
        settings.customLanguages = settings.customLanguages - name
        if (settings.targetLanguage == name) {
            settings.targetLanguage = null
            targetLanguageDropdown.setText(CustomLanguages.NONE_TARGET_LANGUAGE, false)
        } else if (targetLanguageDropdown.text.toString() == name) {
            targetLanguageDropdown.setText(CustomLanguages.NONE_TARGET_LANGUAGE, false)
        }
        (targetLanguageDropdown.adapter as? LanguageDropdownAdapter)?.rebuild()
    }

    private fun showRenameLanguageDialog(oldName: String, onRenamed: () -> Unit) {
        val input = EditText(this).apply {
            setText(oldName)
            setSelection(oldName.length)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                input.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_language_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim()
                when (CustomLanguages.validateRename(settings.customLanguages, oldName, newName)) {
                    CustomLanguages.RenameError.EMPTY ->
                        input.error = getString(R.string.rename_language_empty)
                    CustomLanguages.RenameError.DUPLICATE ->
                        input.error = getString(R.string.rename_language_duplicate)
                    null -> {
                        settings.customLanguages = CustomLanguages.rename(settings.customLanguages, oldName, newName)
                        if (settings.targetLanguage == oldName) {
                            settings.targetLanguage = newName
                        }
                        if (targetLanguageDropdown.text.toString() == oldName) {
                            targetLanguageDropdown.setText(newName, false)
                        }
                        (targetLanguageDropdown.adapter as? LanguageDropdownAdapter)?.rebuild()
                        Toasts.short(this, getString(R.string.language_renamed))
                        dialog.dismiss()
                        onRenamed()
                    }
                }
            }
        }
        dialog.show()
    }

    private inner class LanguageDropdownAdapter : BaseAdapter(), Filterable {
        private var displayItems: List<String> = CustomLanguages.displayList(settings.customLanguages)

        fun rebuild() {
            displayItems = CustomLanguages.displayList(settings.customLanguages)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = displayItems.size
        override fun getItem(position: Int): Any = displayItems[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = displayItems[position]
            val view = convertView as? ViewGroup ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_language_dropdown, parent, false) as ViewGroup
            val label = view.findViewById<TextView>(R.id.language_name)
            val delete = view.findViewById<ImageButton>(R.id.delete_button)
            label.text = item
            // Pitfall #36: rows must not contain focusable children (AbsListView skips onItemClick
            // for rows whose hasFocusable() is true) — the trash ImageButton is clickable but
            // focusable=false. On-device verification (#59) showed the popup ListView still
            // swallows row-clicks for custom rows, so the label commits selection explicitly
            // instead of relying on the internal onItemClick path.
            label.setOnClickListener {
                settings.targetLanguage = if (item == CustomLanguages.NONE_TARGET_LANGUAGE) null else item
                targetLanguageDropdown.setText(item, false)
                targetLanguageDropdown.dismissDropDown()
            }
            // Built-ins get no icon.
            if (CustomLanguages.isBuiltIn(item)) {
                delete.visibility = View.GONE
            } else {
                delete.visibility = View.VISIBLE
                delete.imageTintList = label.textColors
                delete.setOnClickListener {
                    deleteCustomLanguage(item)
                    targetLanguageDropdown.dismissDropDown()
                }
            }
            return view
        }

        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    val all = CustomLanguages.displayList(settings.customLanguages)
                    val filtered = if (constraint.isNullOrBlank()) {
                        all
                    } else {
                        val query = constraint.toString().lowercase()
                        listOf(CustomLanguages.NONE_TARGET_LANGUAGE) + all.drop(1).filter { it.lowercase().contains(query) }
                    }
                    results.values = ArrayList(filtered)
                    results.count = filtered.size
                    return results
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    displayItems = (results?.values as? List<*>)?.filterIsInstance<String>() ?: return
                    notifyDataSetChanged()
                }
            }
        }
    }

    private fun openVoiceKeyboardSettings() {
        Toasts.show(this, getString(R.string.voice_keyboard_hint))
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun setupAboutSection() {
        aboutVersionText.text = getString(R.string.about_version, BuildConfig.VERSION_DISPLAY)
        aboutCommitText.text = getString(R.string.about_commit, BuildConfig.GIT_HASH)
        aboutInfoText.text = Html.fromHtml(getString(R.string.voice_input_info_message), Html.FROM_HTML_MODE_LEGACY)
        aboutInfoText.movementMethod = LinkMovementMethod.getInstance()
        // Help chip, left of the About heading (#107).
        aboutHelpText.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
