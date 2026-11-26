package com.georgernstgraf.polishedrecognition.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.R
import com.georgernstgraf.polishedrecognition.pipeline.PromptStore

/**
 * #212: the Manage Prompts sub-screen — the prompts section moved verbatim
 * from the old single-screen SettingsActivity. Edits land in [SettingsDraft];
 * the reset buttons stay IMMEDIATE actions (they write the store right away)
 * and therefore clear the draft entry, so the overview's back-exit cannot
 * re-apply a stale draft value over a just-restored default.
 */
class PromptsActivity : Activity() {

    private lateinit var promptStore: PromptStore

    private val systemPromptField: EditText by lazy { findViewById(R.id.system_prompt) }
    private val targetLanguageClauseField: EditText by lazy { findViewById(R.id.target_language_clause) }

    /** Guards the programmatic loads: only USER edits write the draft. */
    private var silenceDraftWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prompts)

        promptStore = (application as PolishedRecognitionApp).promptStore

        findViewById<Button>(R.id.restore_defaults).setOnClickListener {
            promptStore.restoreAllDefaults()
            silenceDraftWatchers = true
            loadPromptDefaults()
            silenceDraftWatchers = false
            draft.systemPrompt = null
            draft.targetLanguageClause = null
            Toasts.short(this, getString(R.string.saved))
        }

        findViewById<Button>(R.id.reset_system_prompt).setOnClickListener {
            promptStore.restoreDefault(PromptStore.KEY_SYSTEM)
            // The restored default must NOT re-enter the draft through the
            // field's watcher — the draft entry is cleared, not overwritten.
            silenceDraftWatchers = true
            systemPromptField.setText(promptStore.systemPrompt)
            silenceDraftWatchers = false
            draft.systemPrompt = null
        }

        findViewById<Button>(R.id.reset_target_language_clause).setOnClickListener {
            promptStore.restoreDefault(PromptStore.KEY_TRANSLATE)
            silenceDraftWatchers = true
            targetLanguageClauseField.setText(promptStore.targetLanguageClauseTemplate)
            silenceDraftWatchers = false
            draft.targetLanguageClause = null
        }

        systemPromptField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!silenceDraftWatchers) draft.systemPrompt = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        targetLanguageClauseField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!silenceDraftWatchers) draft.targetLanguageClause = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        silenceDraftWatchers = true
        systemPromptField.setText(draft.systemPrompt ?: promptStore.systemPrompt)
        targetLanguageClauseField.setText(
            draft.targetLanguageClause ?: promptStore.targetLanguageClauseTemplate
        )
        silenceDraftWatchers = false
    }

    private val draft: SettingsDraft get() = SettingsDraft

    private fun loadPromptDefaults() {
        systemPromptField.setText(promptStore.get(PromptStore.KEY_SYSTEM))
        targetLanguageClauseField.setText(promptStore.get(PromptStore.KEY_TRANSLATE))
    }
}
