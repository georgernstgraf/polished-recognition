package com.georgernstgraf.polishedrecognition.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.api.OpenAiChatApiService
import com.georgernstgraf.polishedrecognition.api.OpenAiSttApiService
import com.georgernstgraf.polishedrecognition.config.LanguageMapper
import com.georgernstgraf.polishedrecognition.config.LlmProviderConfig
import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var settings: com.georgernstgraf.polishedrecognition.config.SettingsStore
    private lateinit var promptStore: com.georgernstgraf.polishedrecognition.pipeline.PromptStore
    private lateinit var presets: com.georgernstgraf.polishedrecognition.config.ProviderPresetLoader
    private lateinit var app: PolishedRecognitionApp

    private lateinit var sttProviderDropdown: AutoCompleteTextView
    private lateinit var sttTokenField: TextInputEditText
    private lateinit var sttTokenLayout: TextInputLayout
    private lateinit var sttModelDropdown: AutoCompleteTextView
    private lateinit var validateSttButton: Button

    private lateinit var llmProviderDropdown: AutoCompleteTextView
    private lateinit var llmTokenField: TextInputEditText
    private lateinit var llmTokenLayout: TextInputLayout
    private lateinit var llmModelDropdown: AutoCompleteTextView
    private lateinit var validateLlmButton: Button

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
        sttTokenField = findViewById(R.id.stt_token)
        sttTokenLayout = findViewById(R.id.stt_token_layout)
        sttModelDropdown = findViewById<AutoCompleteTextView>(R.id.stt_model)
        validateSttButton = findViewById(R.id.validate_stt)

        llmProviderDropdown = findViewById<AutoCompleteTextView>(R.id.llm_provider)
        llmTokenField = findViewById(R.id.llm_token)
        llmTokenLayout = findViewById(R.id.llm_token_layout)
        llmModelDropdown = findViewById<AutoCompleteTextView>(R.id.llm_model)
        validateLlmButton = findViewById(R.id.validate_llm)

        rawModeCheckbox = findViewById(R.id.raw_mode)
        targetLanguageDropdown = findViewById<AutoCompleteTextView>(R.id.target_language)

        systemPromptField = findViewById(R.id.system_prompt)
        userPromptField = findViewById(R.id.user_prompt)
        translatePromptField = findViewById(R.id.translate_prompt)

        validateSttButton.setOnClickListener { validateSttProvider() }
        validateLlmButton.setOnClickListener { validateLlmProvider() }

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

        findViewById<Button>(R.id.save_button).setOnClickListener { saveAndClose() }
    }

    private fun loadSettings() {
        settings.sttProvider?.let {
            sttProviderDropdown.setText(it.displayName, false)
            sttTokenField.setText(it.apiToken)
            sttModelDropdown.setText(it.model, false)
            updateModelDropdown(sttModelDropdown, settings.getSttModelList())
        }

        settings.llmProvider?.let {
            llmProviderDropdown.setText(it.displayName, false)
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
            if (preset != null && preset.models.isNotEmpty()) {
                sttModelDropdown.setText(preset.models[0], false)
            }
        }

        llmProviderDropdown.setOnItemClickListener { _, _, position, _ ->
            val name = llmProviderDropdown.adapter.getItem(position) as String
            val preset = presets.findLlmPreset(name)
            if (preset != null && preset.models.isNotEmpty()) {
                llmModelDropdown.setText(preset.models[0], false)
            }
        }
    }

    private fun validateSttProvider() {
        val providerName = sttProviderDropdown.text.toString()
        val token = sttTokenField.text.toString()

        if (token.isBlank()) {
            Toast.makeText(this, "Enter an API token first", Toast.LENGTH_SHORT).show()
            return
        }

        val preset = presets.findSttPreset(providerName)
        val baseUrl = if (preset != null) preset.base_url else {
            getCustomBaseUrl(providerName)
        }

        if (baseUrl.isNullOrBlank()) {
            Toast.makeText(this, "No base URL for selected provider", Toast.LENGTH_SHORT).show()
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
                    sttTokenLayout.error = getString(R.string.token_invalid)
                }
            } catch (e: Exception) {
                sttTokenLayout.error = getString(R.string.token_invalid)
            } finally {
                validateSttButton.isEnabled = true
            }
        }
    }

    private fun validateLlmProvider() {
        val providerName = llmProviderDropdown.text.toString()
        val token = llmTokenField.text.toString()

        if (token.isBlank()) {
            Toast.makeText(this, "Enter an API token first", Toast.LENGTH_SHORT).show()
            return
        }

        val preset = presets.findLlmPreset(providerName)
        val baseUrl = if (preset != null) preset.base_url else {
            getCustomBaseUrl(providerName)
        }

        if (baseUrl.isNullOrBlank()) {
            Toast.makeText(this, "No base URL for selected provider", Toast.LENGTH_SHORT).show()
            return
        }

        validateLlmButton.isEnabled = false
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
                    llmTokenLayout.helperText = getString(R.string.token_valid)
                    Toast.makeText(this@SettingsActivity, getString(R.string.models_fetched, models.size), Toast.LENGTH_SHORT).show()
                } else {
                    llmTokenLayout.error = getString(R.string.token_invalid)
                }
            } catch (e: Exception) {
                llmTokenLayout.error = getString(R.string.token_invalid)
            } finally {
                validateLlmButton.isEnabled = true
            }
        }
    }

    private suspend fun fetchSttModels(baseUrl: String, token: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(OpenAiSttApiService::class.java)

                val response = retrofit.listModels("Bearer $token")
                if (response.isSuccessful && response.body() != null) {
                    val models = response.body()!!.data
                        .filter { it.id.contains("whisper", ignoreCase = true) || !it.id.contains("whisper", ignoreCase = true) }
                        .map { it.id }
                    val audioModels = response.body()!!.data
                        .filter { it.id.contains("whisper", ignoreCase = true) || it.id.contains("asr", ignoreCase = true) }
                        .map { it.id }
                    Result.success(if (audioModels.isNotEmpty()) audioModels else models)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun fetchLlmModels(baseUrl: String, token: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(OpenAiChatApiService::class.java)

                val response = retrofit.listModels("Bearer $token")
                if (response.isSuccessful && response.body() != null) {
                    val models = response.body()!!.data.map { it.id }
                    Result.success(models)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun getCustomBaseUrl(name: String): String? {
        val preset = presets.findSttPreset(name) ?: presets.findLlmPreset(name)
        return preset?.base_url
    }

    private fun updateModelDropdown(dropdown: AutoCompleteTextView, models: List<String>) {
        if (models.isNotEmpty()) {
            dropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, models))
        } else {
            dropdown.setAdapter(null)
        }
    }

    private fun saveAndClose() {
        val sttName = sttProviderDropdown.text.toString()
        val llmName = llmProviderDropdown.text.toString()

        val sttPreset = presets.findSttPreset(sttName)
        val llmPreset = presets.findLlmPreset(llmName)

        val sttBaseUrl = sttPreset?.base_url ?: getCustomBaseUrl(sttName) ?: "https://api.openai.com/v1/"
        val llmBaseUrl = llmPreset?.base_url ?: getCustomBaseUrl(llmName) ?: "https://api.openai.com/v1/"

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
