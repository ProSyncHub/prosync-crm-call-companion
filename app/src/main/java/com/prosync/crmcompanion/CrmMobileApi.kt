package com.prosync.crmcompanion

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class EmployeeOption(val id: String, val name: String, val email: String, val department: String) {
    override fun toString() = "$name • $department"
}

object CrmMobileApi {
    fun fetchEmployees(settings: SettingsStore): List<EmployeeOption> {
        require(settings.apiBaseUrl.isNotBlank() && settings.apiKey.isNotBlank()) { "CRM connection is not built into this app" }
        val connection = (URL("${settings.apiBaseUrl}/api/mobile/employees").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = connection.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IllegalStateException("CRM returned HTTP $code${if (detail.isBlank()) "" else ": ${detail.take(180)}"}")
        }
        val array = JSONObject(connection.inputStream.bufferedReader().readText()).getJSONArray("employees")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            EmployeeOption(item.getString("id"), item.getString("name"), item.getString("email"), item.cleanDepartment())
        }
    }

    fun verifyEmployee(settings: SettingsStore, selected: EmployeeOption, password: String): EmployeeOption {
        val connection = (URL("${settings.apiBaseUrl}/api/mobile/employees/verify").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 25_000
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use {
            it.write(JSONObject().put("employeeId", selected.id).put("password", password).put("deviceId", settings.deviceId).toString().toByteArray())
        }
        when (val code = connection.responseCode) {
            in 200..299 -> Unit
            403 -> {
                val errorCode = runCatching {
                    JSONObject(connection.errorStream?.bufferedReader()?.readText().orEmpty()).optString("code")
                }.getOrDefault("")
                throw IllegalStateException(
                    when (errorCode) {
                        "PASSWORD_CHANGE_REQUIRED" -> "Set a new password in Workforce first, then sign in here with it."
                        "DISABLED" -> "This account is disabled. Ask an administrator to re-activate it in Workforce."
                        else -> "Wrong password. Use your Workforce password."
                    }
                )
            }
            400, 401 -> throw IllegalStateException("Wrong password. Use your Workforce password.")
            else -> throw IllegalStateException("CRM is not responding (HTTP $code). Try again in a minute.")
        }
        val employee = JSONObject(connection.inputStream.bufferedReader().readText()).getJSONObject("employee")
        return EmployeeOption(employee.getString("id"), employee.getString("name"), employee.getString("email"), employee.cleanDepartment())
    }
}

/** Quick sign-in refused by the CRM. code: INVALID, EXPIRED, WRONG_PIN, PIN_LOCKED, DISABLED. */
class QuickSignInException(val code: String, val attemptsLeft: Int, message: String) : IllegalStateException(message)

object QuickSignInApi {
    private fun post(settings: SettingsStore, path: String, body: JSONObject): Pair<Int, JSONObject> {
        val connection = (URL("${settings.apiBaseUrl}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    /** Needs the person's password once; returns the key (and its expiry) for this phone. */
    fun createKey(settings: SettingsStore, employee: EmployeeOption, password: String, pin: String, fingerprint: Boolean): Pair<String, Long> {
        val (code, json) = post(
            settings,
            "/api/mobile/employees/quick-key",
            JSONObject().put("employeeId", employee.id).put("password", password).put("deviceId", settings.deviceId).put("pin", pin).put("fingerprint", fingerprint),
        )
        if (code !in 200..299) throw IllegalStateException(if (code == 401) "Wrong password." else "Could not set up quick sign-in (HTTP $code).")
        val expiresAt = runCatching { java.time.Instant.parse(json.getString("expiresAt")).toEpochMilli() }.getOrDefault(0L)
        return json.getString("token") to expiresAt
    }

    fun signIn(settings: SettingsStore, employeeId: String, token: String, pin: String?): EmployeeOption {
        val body = JSONObject().put("employeeId", employeeId).put("deviceId", settings.deviceId).put("token", token)
            .put("method", if (pin == null) "fingerprint" else "pin")
        if (pin != null) body.put("pin", pin)
        val (code, json) = post(settings, "/api/mobile/employees/quick-sign-in", body)
        if (code !in 200..299) {
            val reason = json.optString("code", "INVALID")
            val left = json.optInt("attemptsLeft", 0)
            throw QuickSignInException(
                reason,
                left,
                when (reason) {
                    "WRONG_PIN" -> "Wrong PIN. $left ${if (left == 1) "try" else "tries"} left."
                    "PIN_LOCKED" -> "Too many wrong PINs. Sign in with your password."
                    "EXPIRED" -> "Quick sign-in expired. Sign in with your password once."
                    "DISABLED" -> "This account is disabled."
                    else -> if (code >= 500) "CRM is not responding (HTTP $code). Try again." else "Quick sign-in is not set up on this phone. Use your password."
                },
            )
        }
        val employee = json.getJSONObject("employee")
        return EmployeeOption(employee.getString("id"), employee.getString("name"), employee.getString("email"), employee.cleanDepartment())
    }
}

private fun JSONObject.cleanDepartment(): String =
    if (isNull("department")) "unassigned" else optString("department", "unassigned").ifBlank { "unassigned" }
