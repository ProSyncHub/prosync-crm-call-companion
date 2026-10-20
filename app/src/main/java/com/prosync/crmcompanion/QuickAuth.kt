package com.prosync.crmcompanion

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Proves who is using the phone: fingerprint first (when set up), else the 4-digit PIN, else the
 * Workforce password. Used by sign-in and by "Who took this call?" when another person is picked.
 */
object QuickAuth {
    /**
     * @param onUsePassword what "Use password" does; null shows a password box here.
     */
    fun confirm(
        activity: AppCompatActivity,
        employee: EmployeeOption,
        title: String,
        onUsePassword: (() -> Unit)?,
        onVerified: (EmployeeOption) -> Unit,
    ) {
        val usePassword = onUsePassword ?: { passwordDialog(activity, employee, title, onVerified) }
        val usePinOrPassword = {
            if (QuickSignInStore.hasQuickSignIn(activity, employee.id)) pinDialog(activity, employee, title, null, usePassword, onVerified) else usePassword()
        }
        when {
            QuickSignInStore.hasFingerprint(activity, employee.id) -> fingerprint(activity, employee, title, usePinOrPassword, usePassword, onVerified)
            QuickSignInStore.hasQuickSignIn(activity, employee.id) -> pinDialog(activity, employee, title, null, usePassword, onVerified)
            else -> usePassword()
        }
    }

    private fun fingerprint(
        activity: AppCompatActivity,
        employee: EmployeeOption,
        title: String,
        fallback: () -> Unit,
        usePassword: () -> Unit,
        onVerified: (EmployeeOption) -> Unit,
    ) {
        val cipher = QuickSignInStore.fingerprintDecryptCipher(activity, employee.id)
        if (cipher == null) {
            toast(activity, "A fingerprint was added to this phone, so fingerprint sign-in was switched off. Use your PIN.")
            fallback()
            return
        }
        val hasPin = QuickSignInStore.hasQuickSignIn(activity, employee.id)
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val token = result.cryptoObject?.cipher?.let { QuickSignInStore.readFingerprintToken(activity, employee.id, it) }
                if (token == null) fallback() else signInWithKey(activity, employee, title, token, null, usePassword, onVerified)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> fallback()
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> Unit
                    else -> { toast(activity, errString.toString()); fallback() }
                }
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(employee.name)
            .setNegativeButtonText(if (hasPin) "Use PIN" else "Use password")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun pinDialog(
        activity: AppCompatActivity,
        employee: EmployeeOption,
        title: String,
        message: String?,
        usePassword: () -> Unit,
        onVerified: (EmployeeOption) -> Unit,
    ) {
        val field = pinField(activity)
        val note = TextView(activity).apply {
            text = message ?: "Enter your 4-digit PIN"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, if (message == null) R.color.muted_ink else R.color.capture_off))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("$title: ${employee.name}")
            .setView(column(activity, note, field))
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Use password") { _, _ -> usePassword() }
            .create()
        field.addTextChangedListener(afterChange { text ->
            if (text.length == 4) {
                dialog.dismiss()
                val token = QuickSignInStore.pinToken(activity, employee.id)
                if (token == null) usePassword() else signInWithKey(activity, employee, title, token, text, usePassword, onVerified)
            }
        })
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    private fun signInWithKey(
        activity: AppCompatActivity,
        employee: EmployeeOption,
        title: String,
        token: String,
        pin: String?,
        usePassword: () -> Unit,
        onVerified: (EmployeeOption) -> Unit,
    ) {
        val settings = SettingsStore(activity)
        activity.lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { QuickSignInApi.signIn(settings, employee.id, token, pin) } }
                .onSuccess(onVerified)
                .onFailure { error ->
                    when {
                        error is QuickSignInException && error.code == "WRONG_PIN" ->
                            pinDialog(activity, employee, title, error.message, usePassword, onVerified)
                        error is QuickSignInException && error.code in setOf("INVALID", "EXPIRED", "PIN_LOCKED", "DISABLED") -> {
                            QuickSignInStore.clear(activity, employee.id)
                            toast(activity, error.message ?: "Use your password.")
                            if (error.code != "DISABLED") usePassword()
                        }
                        error is java.io.IOException -> toast(activity, "No internet connection. Connect and try again.")
                        else -> toast(activity, error.message ?: "Sign in failed. Try again.")
                    }
                }
        }
    }

    /** Password box for flows without their own (e.g. confirming who took a call). */
    fun passwordDialog(activity: AppCompatActivity, employee: EmployeeOption, title: String, onVerified: (EmployeeOption) -> Unit) {
        val field = EditText(activity).apply {
            hint = "Workforce password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val show = CheckBox(activity).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                field.transformationMethod = if (checked) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                field.setSelection(field.text.length)
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("$title: ${employee.name}")
            .setView(column(activity, field, show))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = field.text.toString()
            if (password.isBlank()) { field.error = "Enter the password"; return@setOnClickListener }
            it.isEnabled = false
            activity.lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { CrmMobileApi.verifyEmployee(SettingsStore(activity), employee, password) } }
                    .onSuccess { verified -> dialog.dismiss(); onVerified(verified) }
                    .onFailure { error ->
                        it.isEnabled = true
                        field.error = if (error is java.io.IOException) "No internet connection" else error.message ?: "Wrong password"
                        field.selectAll()
                    }
            }
        }
    }

    /**
     * After a password sign-in: offer a PIN (and fingerprint) for next time. Calls [done] when the
     * person sets it up, skips it, or already has it.
     */
    fun offerSetup(activity: AppCompatActivity, employee: EmployeeOption, password: String, done: () -> Unit) {
        if (QuickSignInStore.hasQuickSignIn(activity, employee.id) || QuickSignInStore.setupSkippedRecently(activity, employee.id)) {
            done(); return
        }
        val pin = pinField(activity)
        val again = pinField(activity).apply { hint = "Repeat PIN" }
        val canFingerprint = QuickSignInStore.fingerprintAvailable(activity)
        val useFingerprint = CheckBox(activity).apply {
            text = "Also sign in with my fingerprint"
            isChecked = canFingerprint
            visibility = if (canFingerprint) android.view.View.VISIBLE else android.view.View.GONE
        }
        val note = TextView(activity).apply {
            text = "Choose a 4-digit PIN. Next time, tap your name and use your fingerprint or PIN instead of typing your password."
            setTextColor(ContextCompat.getColor(activity, R.color.muted_ink))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Sign in faster next time")
            .setView(column(activity, note, pin, again, useFingerprint))
            .setCancelable(false)
            .setNegativeButton("Not now") { _, _ -> QuickSignInStore.skipSetup(activity, employee.id); done() }
            .setPositiveButton("Set up", null)
            .create()
        dialog.show()
        pin.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val chosen = pin.text.toString()
            if (chosen.length != 4) { pin.error = "4 digits"; return@setOnClickListener }
            if (chosen != again.text.toString()) { again.error = "The PINs do not match"; return@setOnClickListener }
            if (chosen.toSet().size == 1 || chosen in setOf("1234", "4321", "0123", "9876")) { pin.error = "Too easy to guess"; return@setOnClickListener }
            button.isEnabled = false
            val fingerprint = useFingerprint.isChecked && canFingerprint
            activity.lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { QuickSignInApi.createKey(SettingsStore(activity), employee, password, chosen, fingerprint) } }
                    .onSuccess { (token, expiresAt) ->
                        QuickSignInStore.savePinToken(activity, employee.id, token, expiresAt)
                        dialog.dismiss()
                        if (fingerprint) enrolFingerprint(activity, employee, token, done) else { toast(activity, "PIN set. Use it next time."); done() }
                    }
                    .onFailure { error ->
                        button.isEnabled = true
                        toast(activity, if (error is java.io.IOException) "No internet connection. Try again." else error.message ?: "Could not set up quick sign-in.")
                    }
            }
        }
    }

    private fun enrolFingerprint(activity: AppCompatActivity, employee: EmployeeOption, token: String, done: () -> Unit) {
        val cipher = runCatching { QuickSignInStore.fingerprintEncryptCipher(employee.id) }.getOrNull()
        if (cipher == null) { toast(activity, "PIN set. Fingerprint is not available on this phone."); done(); return }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val unlocked = result.cryptoObject?.cipher
                if (unlocked != null) {
                    QuickSignInStore.saveFingerprintToken(activity, employee.id, unlocked, token)
                    toast(activity, "Fingerprint and PIN set. Next time just tap your name.")
                }
                done()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                toast(activity, "PIN set. Fingerprint was not added; you can set it up at your next password sign-in.")
                done()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Add your fingerprint")
                .setSubtitle("Touch the sensor to use it for ${employee.name}")
                .setNegativeButtonText("Skip")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    private fun pinField(activity: AppCompatActivity) = EditText(activity).apply {
        hint = "PIN"
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(4))
        gravity = Gravity.CENTER
        letterSpacing = 0.5f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        isSingleLine = true
    }

    private fun column(activity: AppCompatActivity, vararg views: android.view.View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        views.forEach { addView(it) }
    }

    private fun afterChange(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
    }

    private fun toast(activity: AppCompatActivity, text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
