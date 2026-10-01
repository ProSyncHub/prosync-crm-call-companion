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
            it.write(JSONObject().put("employeeId", selected.id).put("password", password).toString().toByteArray())
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

private fun JSONObject.cleanDepartment(): String =
    if (isNull("department")) "unassigned" else optString("department", "unassigned").ifBlank { "unassigned" }
