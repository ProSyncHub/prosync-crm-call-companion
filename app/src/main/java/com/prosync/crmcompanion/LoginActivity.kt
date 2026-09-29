package com.prosync.crmcompanion

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everyday sign-in: pick your name, enter your password, done. */
class LoginActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var employeeField: AutoCompleteTextView
    private lateinit var passwordField: EditText
    private lateinit var status: TextView
    private lateinit var signInButton: Button
    private var employees = emptyList<EmployeeOption>()
    private var selected: EmployeeOption? = null
    private var signingIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProSyncSystemBars()
        settings = SettingsStore(this).also {
            it.apiBaseUrl = BuildConfig.CRM_BASE_URL
            it.apiKey = BuildConfig.MOBILE_SYNC_API_KEY
        }
        if (!settings.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
        }
        if (EmployeeSession.isSignedIn(this)) {
            openMain(); return
        }
        setContentView(R.layout.activity_login)
        employeeField = findViewById(R.id.loginEmployee)
        passwordField = findViewById(R.id.loginPassword)
        status = findViewById(R.id.loginStatus)
        signInButton = findViewById(R.id.signInButton)

        // Tapping the field lists everyone straight away; typing narrows it down.
        employeeField.setOnClickListener { suggest() }
        employeeField.setOnFocusChangeListener { _, focused -> if (focused) suggest() }
        employeeField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s.isNullOrEmpty() && employeeField.hasFocus()) suggest()
                if (selected != null && !selected!!.name.equals(s?.toString(), ignoreCase = true)) selected = null
            }
        })
        employeeField.setOnItemClickListener { parent, _, position, _ ->
            choose(parent.getItemAtPosition(position) as EmployeeOption)
        }
        passwordField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { signIn(); true } else false
        }
        findViewById<TextView>(R.id.togglePassword).setOnClickListener { togglePassword(it as TextView) }
        signInButton.setOnClickListener { signIn() }
        findViewById<TextView>(R.id.loginSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        showEmployees(EmployeeSession.cachedEmployees(this))
        renderRecent()
        refreshEmployees()
    }

    private fun showEmployees(list: List<EmployeeOption>) {
        if (list.isEmpty()) return
        employees = list.sortedBy { it.name.lowercase() }
        employeeField.setAdapter(EmployeeSuggestAdapter(this, employees))
        // If the list arrived while the field was already tapped, show it now.
        if (employeeField.hasFocus() && selected == null) suggest()
    }

    private fun suggest() {
        if (employees.isEmpty()) {
            status.text = "Loading team from CRM…"
            refreshEmployees()
            return
        }
        employeeField.adapter?.let { (it as EmployeeSuggestAdapter).filter.filter(employeeField.text) }
        employeeField.post { if (!isFinishing) employeeField.showDropDown() }
    }

    private var loadingEmployees = false

    private fun refreshEmployees() {
        if (loadingEmployees) return
        if (BuildConfig.MOBILE_SYNC_API_KEY.isBlank()) {
            status.text = "This app build is missing its CRM connection. Install the latest APK."
            return
        }
        loadingEmployees = true
        if (employees.isEmpty()) status.text = "Loading team from CRM…"
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { CrmMobileApi.fetchEmployees(settings) } }
                .onSuccess {
                    EmployeeSession.cacheEmployees(this@LoginActivity, it)
                    showEmployees(it)
                    if (status.text.startsWith("Loading") || status.text.startsWith("No internet")) status.text = ""
                }
                .onFailure {
                    if (employees.isEmpty()) status.text = "No internet or CRM unreachable. Tap your name field to try again."
                }
            loadingEmployees = false
        }
    }

    private fun renderRecent() {
        val recent = EmployeeSession.recentEmployees(this)
        val row = findViewById<LinearLayout>(R.id.recentEmployees)
        row.removeAllViews()
        val visible = if (recent.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<TextView>(R.id.recentTitle).visibility = visible
        findViewById<android.view.View>(R.id.recentScroll).visibility = visible
        recent.forEach { employee ->
            row.addView(TextView(this).apply {
                text = employee.name
                setTextColor(ContextCompat.getColor(this@LoginActivity, R.color.brand_orange))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(this@LoginActivity, R.drawable.chip)
                val padH = dp(18); val padV = dp(10)
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                setOnClickListener { choose(employee) }
            })
        }
    }

    private fun choose(employee: EmployeeOption) {
        selected = employee
        employeeField.setText(employee.name, false)
        employeeField.dismissDropDown()
        status.text = ""
        passwordField.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(passwordField, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Accepts a picked name, or a typed name that matches exactly one employee. */
    private fun resolveEmployee(): EmployeeOption? {
        val typed = employeeField.text.toString().substringBefore(" •").trim()
        selected?.takeIf { it.name.equals(typed, ignoreCase = true) }?.let { return it }
        val pool = (employees + EmployeeSession.recentEmployees(this)).distinctBy { it.id }
        pool.firstOrNull { it.name.equals(typed, ignoreCase = true) }?.let { return it }
        return pool.filter { it.name.contains(typed, ignoreCase = true) }.singleOrNull()?.takeIf { typed.length >= 2 }
    }

    private fun signIn() {
        if (signingIn) return
        val employee = resolveEmployee()
        val password = passwordField.text.toString()
        if (employee == null) {
            status.setTextColor(ContextCompat.getColor(this, R.color.capture_off))
            status.text = "Choose your name from the list."
            employeeField.requestFocus()
            return
        }
        if (password.isBlank()) {
            status.setTextColor(ContextCompat.getColor(this, R.color.capture_off))
            status.text = "Enter your password."
            passwordField.requestFocus()
            return
        }
        signingIn = true
        signInButton.isEnabled = false
        signInButton.text = "Signing in…"
        status.setTextColor(ContextCompat.getColor(this, R.color.muted_ink))
        status.text = ""
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { CrmMobileApi.verifyEmployee(settings, employee, password) } }
                .onSuccess {
                    passwordField.setText("")
                    EmployeeSession.signIn(this@LoginActivity, it)
                    openMain()
                }
                .onFailure {
                    status.setTextColor(ContextCompat.getColor(this@LoginActivity, R.color.capture_off))
                    status.text = when (it) {
                        is java.io.IOException -> "No internet connection. Connect and try again."
                        else -> it.message ?: "Sign in failed. Try again."
                    }
                    passwordField.selectAll()
                    signingIn = false
                    signInButton.isEnabled = true
                    signInButton.text = "Sign in"
                }
        }
    }

    private fun togglePassword(toggle: TextView) {
        val showing = passwordField.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
        passwordField.inputType = InputType.TYPE_CLASS_TEXT or
            if (showing) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        passwordField.setSelection(passwordField.text.length)
        toggle.text = if (showing) "Show" else "Hide"
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
