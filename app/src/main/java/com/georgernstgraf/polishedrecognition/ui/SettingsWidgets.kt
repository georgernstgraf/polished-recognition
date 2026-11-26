package com.georgernstgraf.polishedrecognition.ui

import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.ImageButton
import com.georgernstgraf.polishedrecognition.R

/**
 * #212: shared Settings-widget helpers, extracted from the old monolithic
 * SettingsActivity so the category sub-screens reuse them verbatim.
 */
internal fun togglePassword(field: EditText, toggle: ImageButton) {
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
