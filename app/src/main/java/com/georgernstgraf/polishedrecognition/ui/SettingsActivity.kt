package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.gson.Gson
import com.georgernstgraf.polishedrecognition.BuildConfig
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.CustomLanguages
import com.georgernstgraf.polishedrecognition.config.LlmProviderConfig
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.georgernstgraf.polishedrecognition.api.dto.ChatMessage
import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var settings: com.georgernstgraf.polishedrecognition.config.SettingsStore
    private lateinit var promptStore: com.georgernstgraf.polishedrecognition.pipeline.PromptStore
    private lateinit var presets: com.georgernstgraf.polishedrecognition.config.ProviderPresetLoader
    private lateinit var app: PolishedRecognitionApp

    private val sttProviderDropdown: AutoCompleteTextView by lazy { findViewById<AutoCompleteTextView>(R.id.stt_provider) }
    private val sttUrlField: EditText by lazy { findViewById(R.id.stt_url) }
    private val sttTokenField: EditText by lazy { findViewById(R.id.stt_token) }
    private val sttTokenToggle: ImageButton by lazy { findViewById(R.id.stt_token_toggle) }
    private val sttModelDropdown: AutoCompleteTextView by lazy { findViewById<AutoCompleteTextView>(R.id.stt_model) }
    private val validateSttButton: Button by lazy { findViewById(R.id.validate_stt) }

    private val llmProviderDropdown: AutoCompleteTextView by lazy { findViewById<AutoCompleteTextView>(R.id.llm_provider) }
    private val llmUrlField: EditText by lazy { findViewById(R.id.llm_url) }
    private val llmTokenField: EditText by lazy { findViewById(R.id.llm_token) }
    private val llmTokenToggle: ImageButton by lazy { findViewById(R.id.llm_token_toggle) }
    private val llmModelDropdown: AutoCompleteTextView by lazy { findViewById<AutoCompleteTextView>(R.id.llm_model) }
    private val fetchLlmModelsButton: Button by lazy { findViewById(R.id.fetch_llm_models) }
    private val testLlmTokenButton: Button by lazy { findViewById(R.id.test_llm_token) }

    private val rawModeCheckbox: CheckBox by lazy { findViewById(R.id.raw_mode) }
    private val targetLanguageDropdown: AutoCompleteTextView by lazy { findViewById<AutoCompleteTextView>(R.id.target_language) }
    private var languageEditPrevious: String = ""

    private val systemPromptField: EditText by lazy { findViewById(R.id.system_prompt) }
    private val targetLanguageClauseField: EditText by lazy { findViewById(R.id.target_language_clause) }

    private val aboutVersionText: TextView by lazy { findViewById(R.id.about_version) }
    private val aboutCommitText: TextView by lazy { findViewById(R.id.about_commit) }
    private val aboutInfoText: TextView by lazy { findViewById(R.id.about_info) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        app = application as PolishedRecognitionApp
        settings = app.settingsStore
        promptStore = app.promptStore
        presets = app.providerPresetLoader

        setupListeners()
        loadSettings()
        setupDropdowns()
        setupAboutSection()
    }

    private fun setupListeners() {
        validateSttButton.setOnClickListener { validateSttProvider() }
        fetchLlmModelsButton.setOnClickListener { fetchLlmModels() }
        testLlmTokenButton.setOnClickListener { testLlmToken() }

        sttTokenToggle.setOnClickListener { togglePassword(sttTokenField, sttTokenToggle) }
        llmTokenToggle.setOnClickListener { togglePassword(llmTokenField, llmTokenToggle) }

        sttTokenField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sttTokenField.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        llmTokenField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                llmTokenField.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        sttModelDropdown.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sttModelDropdown.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        llmModelDropdown.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                llmModelDropdown.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<Button>(R.id.restore_defaults).setOnClickListener {
            promptStore.restoreAllDefaults()
            loadPromptDefaults()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.reset_system_prompt).setOnClickListener {
            promptStore.restoreDefault(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_SYSTEM)
            systemPromptField.setText(promptStore.systemPrompt)
        }

        findViewById<Button>(R.id.reset_target_language_clause).setOnClickListener {
            promptStore.restoreDefault(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_TRANSLATE)
            targetLanguageClauseField.setText(promptStore.targetLanguageClauseTemplate)
        }

        findViewById<Button>(R.id.set_recognition_service).setOnClickListener { openVoiceKeyboardSettings() }
        findViewById<Button>(R.id.save_button_top).setOnClickListener { saveAndClose() }
        findViewById<Button>(R.id.save_button).setOnClickListener { saveAndClose() }

        findViewById<TextView>(R.id.manage_custom_languages).setOnClickListener { showManageLanguagesDialog() }
    }

    private fun togglePassword(field: EditText, toggle: ImageButton) {
        val isMasked = field.transformationMethod is PasswordTransformationMethod
        if (isMasked) {
            field.transformationMethod = null
            toggle.setImageResource(R.drawable.ic_eye_off)
        } else {
            field.transformationMethod = PasswordTransformationMethod.getInstance()
            toggle.setImageResource(R.drawable.ic_eye)
        }
        field.setSelection(field.text?.length ?: 0)
    }

    private fun loadSettings() {
        settings.sttProvider?.let {
            sttProviderDropdown.setText(it.displayName, false)
            sttUrlField.setText(it.baseUrl)
            sttTokenField.setText(it.apiToken)
            sttModelDropdown.setText(it.model, false)
            updateModelDropdown(sttModelDropdown, settings.getSttModels(it.baseUrl))
        }

        settings.llmProvider?.let {
            llmProviderDropdown.setText(it.displayName, false)
            llmUrlField.setText(it.baseUrl)
            llmTokenField.setText(it.apiToken)
            llmModelDropdown.setText(it.model, false)
            updateModelDropdown(llmModelDropdown, settings.getLlmModels(it.baseUrl))
        }

        rawModeCheckbox.isChecked = settings.rawMode
        targetLanguageDropdown.setText(settings.targetLanguage ?: CustomLanguages.NONE_TARGET_LANGUAGE, false)
        settings.targetLanguage?.let { tl ->
            if (tl.isNotBlank() && tl != CustomLanguages.NONE_TARGET_LANGUAGE && tl != CustomLanguages.BUILTIN_LANGUAGE && tl !in settings.customLanguages) {
                settings.customLanguages = settings.customLanguages + tl
            }
        }

        systemPromptField.setText(promptStore.systemPrompt)
        targetLanguageClauseField.setText(promptStore.targetLanguageClauseTemplate)
    }

    private fun loadPromptDefaults() {
        systemPromptField.setText(promptStore.get(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_SYSTEM))
        targetLanguageClauseField.setText(promptStore.get(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_TRANSLATE))
    }

    private fun setupDropdowns() {
        val sttNames = presets.sttPresetNames() + getString(R.string.custom_provider)
        val llmNames = presets.llmPresetNames() + getString(R.string.custom_provider)

        sttProviderDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sttNames.sorted()))
        llmProviderDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, llmNames.sorted()))

        val langAdapter = LanguageDropdownAdapter()
        targetLanguageDropdown.setAdapter(langAdapter)

        sttProviderDropdown.setOnItemClickListener { _, _, position, _ ->
            val name = sttProviderDropdown.adapter.getItem(position) as String
            val changed = name != settings.sttProvider?.displayName
            val preset = presets.findSttPreset(name)
            if (preset != null) {
                sttUrlField.setText(preset.base_url)
            }
            if (changed) {
                sttModelDropdown.text.clear()
                sttModelDropdown.error = null
                reloadModelDropdown(sttModelDropdown)
            }
            // This dropdown isn't focusable (inputType=none), so selecting an item never moves
            // focus away from the model fields — re-run model validation explicitly (#50).
            validateModelField(sttModelDropdown)
        }

        llmProviderDropdown.setOnItemClickListener { _, _, position, _ ->
            val name = llmProviderDropdown.adapter.getItem(position) as String
            val changed = name != settings.llmProvider?.displayName
            val preset = presets.findLlmPreset(name)
            if (preset != null) {
                llmUrlField.setText(preset.base_url)
            }
            if (changed) {
                llmModelDropdown.text.clear()
                llmModelDropdown.error = null
                reloadModelDropdown(llmModelDropdown)
            }
            validateModelField(llmModelDropdown)
        }

        // Provider dropdowns use inputType=none; the Material exposed-dropdown arrow is gone,
        // so we wire tap -> showDropDown() and raise the threshold to Int.MAX_VALUE so typing
        // (which can't happen, inputType=none) never filters the list away. Same hook applied to
        // the target-language dropdown for tap-to-open parity. See PITFALLS #37.
        listOf(sttProviderDropdown, llmProviderDropdown, targetLanguageDropdown).forEach { dropdown ->
            dropdown.threshold = Int.MAX_VALUE
            dropdown.setOnClickListener { dropdown.showDropDown() }
        }

        // The target-language dropdown isn't focusable (inputType=none) either — re-run model
        // validation explicitly when the user switches language (#50).
        targetLanguageDropdown.setOnItemClickListener { _, _, _, _ ->
            validateModelField(sttModelDropdown)
            validateModelField(llmModelDropdown)
        }

        // Long-press the selected language to edit it inline (old Material behavior, #48):
        // the edit commits directly to the list — existing names are selected, new names are
        // appended. Short tap keeps opening the dropdown (#37 tap-to-open selector).
        targetLanguageDropdown.setOnLongClickListener {
            enterLanguageEditMode()
            true
        }

        sttModelDropdown.threshold = 1
        llmModelDropdown.threshold = 1

        listOf(sttModelDropdown, llmModelDropdown).forEach { dropdown ->
            dropdown.setOnClickListener {
                val adapter = dropdown.adapter as? ModelFilterAdapter
                if (adapter == null) {
                    Toast.makeText(this, R.string.no_models_yet, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                adapter.showAll()
                dropdown.showDropDown()
            }
            dropdown.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) validateModelField(dropdown)
            }
        }

        // When the endpoint URL changes (manual edit or provider switch), the cached model
        // list belongs to a different endpoint — reload the dropdown from the store.
        listOf(sttUrlField, llmUrlField).forEach { urlField ->
            urlField.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    reloadModelDropdown(if (urlField === sttUrlField) sttModelDropdown else llmModelDropdown)
                }
            }
        }
    }

    private fun reloadModelDropdown(dropdown: AutoCompleteTextView) {
        val baseUrl = if (dropdown === sttModelDropdown) sttUrlField.text.toString() else llmUrlField.text.toString()
        val models = if (dropdown === sttModelDropdown) settings.getSttModels(baseUrl) else settings.getLlmModels(baseUrl)
        updateModelDropdown(dropdown, models)
    }

    private fun validateModelField(dropdown: AutoCompleteTextView) {
        val adapter = dropdown.adapter as? ModelFilterAdapter
        val text = dropdown.text.toString().trim()
        if (text.isEmpty()) {
            dropdown.error = getString(R.string.select_or_enter_model)
            return
        }
        if (adapter != null && text !in adapter.allItems) {
            dropdown.error = getString(R.string.select_model_from_list)
        } else {
            dropdown.error = null
        }
    }

    private fun validateSttProvider() {
        val token = sttTokenField.text.toString()
        val baseUrl = sttUrlField.text.toString()

        if (token.isBlank()) {
            Toast.makeText(this, "Enter an API token first", Toast.LENGTH_SHORT).show()
            return
        }

        if (baseUrl.isBlank()) {
            Toast.makeText(this, "Enter an API URL first", Toast.LENGTH_SHORT).show()
            return
        }

        validateSttButton.isEnabled = false
        scope.launch {
            try {
                val result = fetchSttModels(baseUrl, token)
                if (result.isSuccess) {
                    val models = result.getOrThrow()
                    settings.setSttModels(baseUrl, models)
                    updateModelDropdown(sttModelDropdown, models)
                    if (sttModelDropdown.text.toString() !in models) {
                        val currentPresetName = sttProviderDropdown.text.toString()
                        val defaultModel = presets.findSttPreset(currentPresetName)?.default_model
                        if (defaultModel != null && defaultModel in models) {
                            sttModelDropdown.setText(defaultModel, false)
                        } else {
                            sttModelDropdown.text.clear()
                        }
                    }
                    sttTokenField.error = null
                    Toast.makeText(this@SettingsActivity, getString(R.string.token_valid), Toast.LENGTH_SHORT).show()
                    Toast.makeText(this@SettingsActivity, getString(R.string.models_fetched, models.size), Toast.LENGTH_SHORT).show()
                } else {
                    sttTokenField.error = result.exceptionOrNull()?.message
                        ?: getString(R.string.token_invalid)
                }
            } catch (e: Exception) {
                sttTokenField.error = e.message ?: getString(R.string.token_invalid)
            } finally {
                validateSttButton.isEnabled = true
            }
        }
    }

    private fun fetchLlmModels() {
        val token = llmTokenField.text.toString()
        val baseUrl = llmUrlField.text.toString()

        if (token.isBlank()) {
            Toast.makeText(this, "Enter an API token first", Toast.LENGTH_SHORT).show()
            return
        }

        if (baseUrl.isBlank()) {
            Toast.makeText(this, "Enter an API URL first", Toast.LENGTH_SHORT).show()
            return
        }

        fetchLlmModelsButton.isEnabled = false
        scope.launch {
            try {
                val result = fetchLlmModels(baseUrl, token)
                if (result.isSuccess) {
                    val models = result.getOrThrow()
                    settings.setLlmModels(baseUrl, models)
                    updateModelDropdown(llmModelDropdown, models)
                    if (llmModelDropdown.text.toString() !in models) {
                        val currentPresetName = llmProviderDropdown.text.toString()
                        val defaultModel = presets.findLlmPreset(currentPresetName)?.default_model
                        if (defaultModel != null && defaultModel in models) {
                            llmModelDropdown.setText(defaultModel, false)
                        } else {
                            llmModelDropdown.text.clear()
                        }
                    }
                    llmTokenField.error = null
                    Toast.makeText(this@SettingsActivity, getString(R.string.models_fetched, models.size), Toast.LENGTH_SHORT).show()
                } else {
                    llmTokenField.error = result.exceptionOrNull()?.message
                        ?: getString(R.string.token_invalid)
                }
            } catch (e: Exception) {
                llmTokenField.error = e.message ?: getString(R.string.token_invalid)
            } finally {
                fetchLlmModelsButton.isEnabled = true
            }
        }
    }

    private fun testLlmToken() {
        val token = llmTokenField.text.toString()
        val baseUrl = llmUrlField.text.toString()
        val model = llmModelDropdown.text.toString()

        if (token.isBlank()) {
            Toast.makeText(this, "Enter an API token first", Toast.LENGTH_SHORT).show()
            return
        }

        if (model.isBlank()) {
            Toast.makeText(this, "Select a model first", Toast.LENGTH_SHORT).show()
            return
        }

        if (baseUrl.isBlank()) {
            Toast.makeText(this, "Enter an API URL first", Toast.LENGTH_SHORT).show()
            return
        }

        testLlmTokenButton.isEnabled = false
        scope.launch {
            try {
                val result = testLlmTokenCall(baseUrl, token, model)
                if (result.isSuccess) {
                    llmTokenField.error = null
                    Toast.makeText(this@SettingsActivity, R.string.token_valid, Toast.LENGTH_SHORT).show()
                } else {
                    llmTokenField.error = result.exceptionOrNull()?.message
                        ?: getString(R.string.token_invalid)
                }
            } catch (e: Exception) {
                llmTokenField.error = e.message ?: getString(R.string.token_invalid)
            } finally {
                testLlmTokenButton.isEnabled = true
            }
        }
    }

    private suspend fun fetchLlmModels(baseUrl: String, token: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val api = app.getChatApi(baseUrl)
                val response = api.listModelsSync("Bearer $token").execute()
                if (response.isSuccessful && response.body() != null) {
                    val models = response.body()!!.data.map { it.id }
                    Result.success(models)
                } else {
                    val detail = extractErrorDetail(response.errorBody())
                    val msg = if (detail.isNotEmpty()) "HTTP ${response.code()}: $detail" else "HTTP ${response.code()}"
                    Result.failure(Exception(msg))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun testLlmTokenCall(baseUrl: String, token: String, model: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val api = app.getChatApi(baseUrl)
                val request = ChatRequest(
                    model = model,
                    messages = listOf(ChatMessage(role = "user", content = "ping")),
                    maxTokens = 1
                )
                val response = api.chatSync("Bearer $token", request).execute()
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val detail = extractErrorDetail(response.errorBody())
                    val msg = if (detail.isNotEmpty()) "HTTP ${response.code()}: $detail" else "HTTP ${response.code()}"
                    Result.failure(Exception(msg))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun fetchSttModels(baseUrl: String, token: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val api = app.getSttApi(baseUrl)
                val response = api.listModelsSync("Bearer $token").execute()
                if (response.isSuccessful && response.body() != null) {
                    val audioModels = response.body()!!.data
                        .filter { it.id.contains("whisper", ignoreCase = true) || it.id.contains("asr", ignoreCase = true) }
                        .map { it.id }
                    val allModels = response.body()!!.data.map { it.id }
                    Result.success(if (audioModels.isNotEmpty()) audioModels else allModels)
                } else {
                    val detail = extractErrorDetail(response.errorBody())
                    val msg = if (detail.isNotEmpty()) "HTTP ${response.code()}: $detail" else "HTTP ${response.code()}"
                    Result.failure(Exception(msg))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun extractErrorDetail(errorBody: okhttp3.ResponseBody?): String {
        if (errorBody == null) return ""
        return try {
            val json = Gson().fromJson(errorBody.string(), Map::class.java)
            val error = json["error"] as? Map<*, *>
            error?.get("message") as? String ?: ""
        } catch (_: Exception) { "" }
    }

    private fun updateModelDropdown(dropdown: AutoCompleteTextView, models: List<String>) {
        if (models.isNotEmpty()) {
            val adapter = ModelFilterAdapter(models.sorted())
            dropdown.setAdapter(adapter)
        } else {
            dropdown.setAdapter(null)
        }
    }

    private inner class ModelFilterAdapter(items: List<String>) : BaseAdapter(), Filterable {
        val allItems: List<String> = items.toList()
        private var displayItems: List<String> = items.toList()
        private val filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                val query = constraint?.toString()?.trim()?.lowercase()
                // The view's internal focus-triggered filter runs async and lands after the
                // click handler's showAll() — with the field holding the selected model, it
                // would shrink the list to that single entry. Returning the full list for an
                // exact match keeps every trigger path (focus, click, full-ID typing) at "all".
                val filtered = if (query.isNullOrBlank() || allItems.any { it.equals(query, ignoreCase = true) }) {
                    allItems
                } else {
                    allItems.filter { it.lowercase().contains(query) }
                }
                results.values = filtered
                results.count = filtered.size
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                val filtered = results?.values as? List<String> ?: return
                displayItems = filtered.toList()
                notifyDataSetChanged()
            }
        }

        fun showAll() {
            displayItems = allItems.toList()
            notifyDataSetChanged()
        }

        override fun getCount(): Int = displayItems.size
        override fun getItem(position: Int): Any = displayItems[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView as? TextView ?: LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_dropdown_item_1line, parent, false) as TextView
            view.text = displayItems[position]
            return view
        }

        override fun getFilter(): Filter = filter
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
            // focusable=false, so row taps still select and icon taps delete. Built-ins get no icon.
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
        Toast.makeText(this, R.string.voice_keyboard_hint, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (_: Exception) {
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
                Toast.makeText(this, R.string.language_added, Toast.LENGTH_SHORT).show()
            }
            CustomLanguages.CommitResult.Reverted ->
                targetLanguageDropdown.setText(languageEditPrevious, false)
        }
        validateModelField(sttModelDropdown)
        validateModelField(llmModelDropdown)
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
                        Toast.makeText(this, R.string.language_renamed, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onRenamed()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun setupAboutSection() {
        aboutVersionText.text = getString(R.string.about_version, BuildConfig.VERSION_DISPLAY)
        aboutCommitText.text = getString(R.string.about_commit, BuildConfig.GIT_HASH)
        aboutInfoText.text = Html.fromHtml(getString(R.string.voice_input_info_message), Html.FROM_HTML_MODE_LEGACY)
        aboutInfoText.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun saveAndClose() {
        val sttName = sttProviderDropdown.text.toString()
        val llmName = llmProviderDropdown.text.toString()
        val sttBaseUrl = sttUrlField.text.toString()
        val llmBaseUrl = llmUrlField.text.toString()
        val sttModel = sttModelDropdown.text.toString().trim()
        val llmModel = llmModelDropdown.text.toString().trim()

        if (sttModel.isEmpty()) {
            sttModelDropdown.error = getString(R.string.select_or_enter_model)
            return
        }
        val sttModels = settings.getSttModels(sttBaseUrl)
        if (sttModels.isNotEmpty() && sttModel !in sttModels) {
            sttModelDropdown.error = getString(R.string.select_model_from_list)
            return
        }

        if (llmModel.isEmpty()) {
            llmModelDropdown.error = getString(R.string.select_or_enter_model)
            return
        }
        val llmModels = settings.getLlmModels(llmBaseUrl)
        if (llmModels.isNotEmpty() && llmModel !in llmModels) {
            llmModelDropdown.error = getString(R.string.select_model_from_list)
            return
        }

        settings.sttProvider = SttProviderConfig(
            displayName = sttName,
            baseUrl = sttBaseUrl,
            apiToken = sttTokenField.text.toString(),
            model = sttModel
        )

        settings.llmProvider = LlmProviderConfig(
            displayName = llmName,
            baseUrl = llmBaseUrl,
            apiToken = llmTokenField.text.toString(),
            model = llmModel
        )

        settings.rawMode = rawModeCheckbox.isChecked
        val tl = targetLanguageDropdown.text.toString()
        val tlToSave = if (tl.isBlank() || tl == CustomLanguages.NONE_TARGET_LANGUAGE) null else tl
        settings.targetLanguage = tlToSave
        if (tlToSave != null && tlToSave != CustomLanguages.BUILTIN_LANGUAGE && tlToSave !in settings.customLanguages) {
            settings.customLanguages = settings.customLanguages + tlToSave
        }

        promptStore.set(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_SYSTEM, systemPromptField.text.toString())
        promptStore.set(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_TRANSLATE, targetLanguageClauseField.text.toString())

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
