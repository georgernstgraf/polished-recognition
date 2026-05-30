package com.georgernstgraf.polishedrecognition.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.config.LanguageMapper
import com.georgernstgraf.polishedrecognition.config.LlmProviderConfig
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.georgernstgraf.polishedrecognition.api.dto.ChatMessage
import com.georgernstgraf.polishedrecognition.api.dto.ChatRequest
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var settings: com.georgernstgraf.polishedrecognition.config.SettingsStore
    private lateinit var promptStore: com.georgernstgraf.polishedrecognition.pipeline.PromptStore
    private lateinit var presets: com.georgernstgraf.polishedrecognition.config.ProviderPresetLoader
    private lateinit var app: PolishedRecognitionApp

    private lateinit var sttProviderDropdown: AutoCompleteTextView
    private lateinit var sttUrlField: TextInputEditText
    private lateinit var sttTokenField: TextInputEditText
    private lateinit var sttTokenLayout: TextInputLayout
    private lateinit var sttModelDropdown: AutoCompleteTextView
    private lateinit var validateSttButton: Button

    private lateinit var llmProviderDropdown: AutoCompleteTextView
    private lateinit var llmUrlField: TextInputEditText
    private lateinit var llmTokenField: TextInputEditText
    private lateinit var llmTokenLayout: TextInputLayout
    private lateinit var llmModelDropdown: AutoCompleteTextView
    private lateinit var fetchLlmModelsButton: Button
    private lateinit var testLlmTokenButton: Button

    private lateinit var rawModeCheckbox: CheckBox
    private lateinit var targetLanguageDropdown: AutoCompleteTextView

    private lateinit var systemPromptField: TextInputEditText
    private lateinit var userPromptField: TextInputEditText
    private lateinit var translatePromptField: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        app = application as PolishedRecognitionApp
        settings = app.settingsStore
        promptStore = app.promptStore
        presets = app.providerPresetLoader

        bindViews()
        loadSettings()
        setupDropdowns()
    }

    private fun bindViews() {
        sttProviderDropdown = findViewById<AutoCompleteTextView>(R.id.stt_provider)
        sttUrlField = findViewById(R.id.stt_url)
        sttTokenField = findViewById(R.id.stt_token)
        sttTokenLayout = findViewById(R.id.stt_token_layout)
        sttModelDropdown = findViewById<AutoCompleteTextView>(R.id.stt_model)
        validateSttButton = findViewById(R.id.validate_stt)

        llmProviderDropdown = findViewById<AutoCompleteTextView>(R.id.llm_provider)
        llmUrlField = findViewById(R.id.llm_url)
        llmTokenField = findViewById(R.id.llm_token)
        llmTokenLayout = findViewById(R.id.llm_token_layout)
        llmModelDropdown = findViewById<AutoCompleteTextView>(R.id.llm_model)
        fetchLlmModelsButton = findViewById(R.id.fetch_llm_models)
        testLlmTokenButton = findViewById(R.id.test_llm_token)

        validateSttButton.setOnClickListener { validateSttProvider() }
        fetchLlmModelsButton.setOnClickListener { fetchLlmModels() }
        testLlmTokenButton.setOnClickListener { testLlmToken() }

        sttTokenField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sttTokenLayout.error = null
                sttTokenLayout.helperText = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        llmTokenField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                llmTokenLayout.error = null
                llmTokenLayout.helperText = null
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

        findViewById<Button>(R.id.reset_user_prompt).setOnClickListener {
            promptStore.restoreDefault(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_USER)
            userPromptField.setText(promptStore.userPromptTemplate)
        }

        findViewById<Button>(R.id.reset_translate_prompt).setOnClickListener {
            promptStore.restoreDefault(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_TRANSLATE)
            translatePromptField.setText(promptStore.translatePromptTemplate)
        }

        findViewById<Button>(R.id.set_recognition_service).setOnClickListener { openRecognitionServiceSettings() }
        findViewById<Button>(R.id.save_button).setOnClickListener { saveAndClose() }
    }

    private fun loadSettings() {
        settings.sttProvider?.let {
            sttProviderDropdown.setText(it.displayName, false)
            sttUrlField.setText(it.baseUrl)
            sttTokenField.setText(it.apiToken)
            sttModelDropdown.setText(it.model, false)
            updateModelDropdown(sttModelDropdown, settings.getSttModelList())
        }

        settings.llmProvider?.let {
            llmProviderDropdown.setText(it.displayName, false)
            llmUrlField.setText(it.baseUrl)
            llmTokenField.setText(it.apiToken)
            llmModelDropdown.setText(it.model, false)
            updateModelDropdown(llmModelDropdown, settings.getLlmModelList())
        }

        rawModeCheckbox.isChecked = settings.rawMode
        targetLanguageDropdown.setText(settings.targetLanguage ?: "", false)

        systemPromptField.setText(promptStore.systemPrompt)
        userPromptField.setText(promptStore.userPromptTemplate)
        translatePromptField.setText(promptStore.translatePromptTemplate)
    }

    private fun loadPromptDefaults() {
        systemPromptField.setText(promptStore.get(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_SYSTEM))
        userPromptField.setText(promptStore.get(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_USER))
        translatePromptField.setText(promptStore.get(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_TRANSLATE))
    }

    private fun setupDropdowns() {
        val sttNames = presets.sttPresetNames() + getString(R.string.custom_provider)
        val llmNames = presets.llmPresetNames() + getString(R.string.custom_provider)
        val languages = LanguageMapper.supportedLanguages

        sttProviderDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sttNames))
        llmProviderDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, llmNames))
        targetLanguageDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languages))

        sttProviderDropdown.setOnItemClickListener { _, _, position, _ ->
            val name = sttProviderDropdown.adapter.getItem(position) as String
            val preset = presets.findSttPreset(name)
            if (preset != null) {
                sttUrlField.setText(preset.base_url)
                if (preset.models.isNotEmpty()) {
                    sttModelDropdown.setText(preset.models[0], false)
                }
            }
        }

        llmProviderDropdown.setOnItemClickListener { _, _, position, _ ->
            val name = llmProviderDropdown.adapter.getItem(position) as String
            val preset = presets.findLlmPreset(name)
            if (preset != null) {
                llmUrlField.setText(preset.base_url)
                if (preset.models.isNotEmpty()) {
                    llmModelDropdown.setText(preset.models[0], false)
                }
            }
        }

        sttModelDropdown.threshold = 1
        llmModelDropdown.threshold = 1
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
                    settings.setSttModelList(models)
                    updateModelDropdown(sttModelDropdown, models)
                    if (models.isNotEmpty()) {
                        sttModelDropdown.setText(models[0], false)
                    }
                    sttTokenLayout.error = null
                    sttTokenLayout.helperText = getString(R.string.token_valid)
                    Toast.makeText(this@SettingsActivity, getString(R.string.models_fetched, models.size), Toast.LENGTH_SHORT).show()
                } else {
                    sttTokenLayout.error = result.exceptionOrNull()?.message
                        ?: getString(R.string.token_invalid)
                }
            } catch (e: Exception) {
                sttTokenLayout.error = e.message ?: getString(R.string.token_invalid)
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
                    settings.setLlmModelList(models)
                    updateModelDropdown(llmModelDropdown, models)
                    if (models.isNotEmpty()) {
                        llmModelDropdown.setText(models[0], false)
                    }
                    llmTokenLayout.error = null
                    llmTokenLayout.helperText = getString(R.string.models_fetched, models.size)
                    Toast.makeText(this@SettingsActivity, getString(R.string.models_fetched, models.size), Toast.LENGTH_SHORT).show()
                } else {
                    llmTokenLayout.error = result.exceptionOrNull()?.message
                        ?: getString(R.string.token_invalid)
                }
            } catch (e: Exception) {
                llmTokenLayout.error = e.message ?: getString(R.string.token_invalid)
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
                    llmTokenLayout.error = null
                    llmTokenLayout.helperText = getString(R.string.token_valid)
                    Toast.makeText(this@SettingsActivity, R.string.token_valid, Toast.LENGTH_SHORT).show()
                } else {
                    llmTokenLayout.error = result.exceptionOrNull()?.message
                        ?: getString(R.string.token_invalid)
                }
            } catch (e: Exception) {
                llmTokenLayout.error = e.message ?: getString(R.string.token_invalid)
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
            dropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, models))
        } else {
            dropdown.setAdapter(null)
        }
    }

    private fun openRecognitionServiceSettings() {
        val cn = ComponentName(packageName, "${packageName}.service.PolishedRecognitionService")
        try {
            Settings.Secure.putString(contentResolver, "voice_recognition_service", cn.flattenToString())
            Toast.makeText(this, "Set as default recognition service!", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            AlertDialog.Builder(this)
                .setTitle(R.string.set_recognition_service)
                .setMessage(R.string.recognition_service_guide)
                .setPositiveButton("Keyboard Settings") { _, _ ->
                    try {
                        startActivity(Intent().apply {
                            setClassName("com.android.settings",
                                "com.android.settings.inputmethod.InputMethodAndSubtypeEnablerActivity")
                        })
                    } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                .setNegativeButton("System Settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {}
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun saveAndClose() {
        val sttName = sttProviderDropdown.text.toString()
        val llmName = llmProviderDropdown.text.toString()
        val sttBaseUrl = sttUrlField.text.toString()
        val llmBaseUrl = llmUrlField.text.toString()

        settings.sttProvider = SttProviderConfig(
            displayName = sttName,
            baseUrl = sttBaseUrl,
            apiToken = sttTokenField.text.toString(),
            model = sttModelDropdown.text.toString()
        )

        settings.llmProvider = LlmProviderConfig(
            displayName = llmName,
            baseUrl = llmBaseUrl,
            apiToken = llmTokenField.text.toString(),
            model = llmModelDropdown.text.toString()
        )

        settings.rawMode = rawModeCheckbox.isChecked
        settings.targetLanguage = targetLanguageDropdown.text.toString().ifBlank { null }

        promptStore.set(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_SYSTEM, systemPromptField.text.toString())
        promptStore.set(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_USER, userPromptField.text.toString())
        promptStore.set(com.georgernstgraf.polishedrecognition.pipeline.PromptStore.KEY_TRANSLATE, translatePromptField.text.toString())

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
