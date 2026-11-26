package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import com.google.gson.Gson
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.api.dto.ChatMessage
import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #212: the Providers & Tokens sub-screen — the STT + LLM provider sections
 * moved verbatim from the old single-screen SettingsActivity. Every live
 * behavior is preserved (preset → overwrite URL + clear model; model fetch +
 * write-through cache; eye toggles; focus-loss cache reload); the only
 * change is where the EDITED values go: into [SettingsDraft], never straight
 * into `SettingsStore` — the overview's back-exit validates and applies.
 */
class ProvidersActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var settings: com.georgernstgraf.polishedrecognition.config.SettingsStore
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

    /** Guards the programmatic loads: only USER edits write the draft. */
    private var silenceDraftWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_providers)

        app = application as PolishedRecognitionApp
        settings = app.settingsStore
        presets = app.providerPresetLoader

        setupListeners()
        silenceDraftWatchers = true
        loadFields()
        silenceDraftWatchers = false
        setupDropdowns()
    }

    private fun setupListeners() {
        validateSttButton.setOnClickListener { validateSttProvider() }
        fetchLlmModelsButton.setOnClickListener { fetchLlmModels() }
        testLlmTokenButton.setOnClickListener { testLlmToken() }

        sttTokenToggle.setOnClickListener { togglePassword(sttTokenField, sttTokenToggle) }
        llmTokenToggle.setOnClickListener { togglePassword(llmTokenField, llmTokenToggle) }

        // Every user edit lands in the draft (displayable raw) and clears the
        // red error as the user corrects (the #45 contract).
        sttTokenField.addTextChangedListener(draftWatcher { draft.sttToken = it })
        sttUrlField.addTextChangedListener(draftWatcher { draft.sttUrl = it })
        sttModelDropdown.addTextChangedListener(draftWatcher { draft.sttModel = it })

        llmTokenField.addTextChangedListener(draftWatcher { draft.llmToken = it })
        llmUrlField.addTextChangedListener(draftWatcher { draft.llmUrl = it })
        llmModelDropdown.addTextChangedListener(draftWatcher { draft.llmModel = it })
    }

    private val draft: SettingsDraft get() = SettingsDraft

    /** TextWatcher that clears the field error and writes the raw text into the draft. */
    private fun draftWatcher(write: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (silenceDraftWatchers) return
            write(s?.toString() ?: "")
        }
        override fun afterTextChanged(s: Editable?) {}
    }

    private fun loadFields() {
        val stt = settings.sttProvider
        val sttHasDraft = draft.sttProviderName != null || draft.sttUrl != null ||
            draft.sttToken != null || draft.sttModel != null
        if (stt != null || sttHasDraft) {
            sttProviderDropdown.setText(draft.sttProviderName ?: stt?.displayName ?: "", false)
            sttUrlField.setText(draft.sttUrl ?: stt?.baseUrl ?: "")
            sttTokenField.setText(draft.sttToken ?: stt?.apiToken ?: "")
            sttModelDropdown.setText(draft.sttModel ?: stt?.model ?: "", false)
            updateModelDropdown(sttModelDropdown, settings.getSttModels(sttUrlField.text.toString()))
        }

        val llm = settings.llmProvider
        val llmHasDraft = draft.llmProviderName != null || draft.llmUrl != null ||
            draft.llmToken != null || draft.llmModel != null
        if (llm != null || llmHasDraft) {
            llmProviderDropdown.setText(draft.llmProviderName ?: llm?.displayName ?: "", false)
            llmUrlField.setText(draft.llmUrl ?: llm?.baseUrl ?: "")
            llmTokenField.setText(draft.llmToken ?: llm?.apiToken ?: "")
            llmModelDropdown.setText(draft.llmModel ?: llm?.model ?: "", false)
            updateModelDropdown(llmModelDropdown, settings.getLlmModels(llmUrlField.text.toString()))
        }
    }

    private fun setupDropdowns() {
        val sttNames = presets.sttPresetNames() + getString(R.string.custom_provider)
        val llmNames = presets.llmPresetNames() + getString(R.string.custom_provider)

        sttProviderDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sttNames.sorted()))
        llmProviderDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, llmNames.sorted()))

        sttProviderDropdown.setOnItemClickListener { _, _, position, _ ->
            val name = sttProviderDropdown.adapter.getItem(position) as String
            val changed = name != settings.sttProvider?.displayName
            if (!silenceDraftWatchers) draft.sttProviderName = name
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
            if (!silenceDraftWatchers) draft.llmProviderName = name
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
        // (which can't happen, inputType=none) never filters the list away. See PITFALLS #37.
        listOf(sttProviderDropdown, llmProviderDropdown).forEach { dropdown ->
            dropdown.threshold = Int.MAX_VALUE
            dropdown.setOnClickListener { dropdown.showDropDown() }
        }

        sttModelDropdown.threshold = 1
        llmModelDropdown.threshold = 1

        listOf(sttModelDropdown, llmModelDropdown).forEach { dropdown ->
            dropdown.setOnClickListener {
                val adapter = dropdown.adapter as? ModelFilterAdapter
                if (adapter == null) {
                    Toasts.show(this, getString(R.string.no_models_yet))
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
            Toasts.show(this, "Enter an API token first")
            return
        }

        if (baseUrl.isBlank()) {
            Toasts.show(this, "Enter an API URL first")
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
                    Toasts.short(this@ProvidersActivity, getString(R.string.token_valid))
                    Toasts.show(
                        this@ProvidersActivity,
                        getResources().getQuantityString(R.plurals.models_fetched, models.size, models.size)
                    )
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
            Toasts.show(this, "Enter an API token first")
            return
        }

        if (baseUrl.isBlank()) {
            Toasts.show(this, "Enter an API URL first")
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
                    Toasts.show(
                        this@ProvidersActivity,
                        getResources().getQuantityString(R.plurals.models_fetched, models.size, models.size)
                    )
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
            Toasts.show(this, "Enter an API token first")
            return
        }

        if (model.isBlank()) {
            Toasts.show(this, "Select a model first")
            return
        }

        if (baseUrl.isBlank()) {
            Toasts.show(this, "Enter an API URL first")
            return
        }

        testLlmTokenButton.isEnabled = false
        scope.launch {
            try {
                val result = testLlmTokenCall(baseUrl, token, model)
                if (result.isSuccess) {
                    llmTokenField.error = null
                    Toasts.short(this@ProvidersActivity, getString(R.string.token_valid))
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
}
