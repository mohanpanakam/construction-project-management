package com.panakam.construction.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages user authentication.
 * Credentials are stored in PostgreSQL on the backend (passwords BCrypt-hashed).
 * The current session (logged-in user) is cached in SharedPreferences on the device.
 */
object AuthManager {

    // ── Config ────────────────────────────────────────────────────────────────
    private const val BASE_URL    = "http://192.168.1.2:8080"
    private const val PREF_NAME   = "construction_auth"
    private const val KEY_ID         = "user_id"
    private const val KEY_NAME       = "user_name"
    private const val KEY_EMAIL      = "user_email"
    private const val KEY_ROLE       = "user_role"
    private const val KEY_LAST_EMAIL = "last_email"
    private const val KEY_CUSTOMER_ID         = "customer_id"
    private const val KEY_UNIT_ID             = "unit_id"
    private const val KEY_PROJECT_ID          = "project_id_customer"
    private const val KEY_PHONE               = "customer_phone"
    private const val KEY_MUST_CHANGE_PASSWORD = "must_change_password"

    private var prefs: SharedPreferences? = null

    val SECURITY_QUESTIONS = listOf(
        "What was the name of your first pet?",
        "What is your mother's maiden name?",
        "What was the name of your first school?",
        "What city were you born in?",
        "What is the name of the street you grew up on?",
        "What was the make of your first car?",
        "What is your oldest sibling's middle name?"
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ── Registration ──────────────────────────────────────────────────────────

    fun register(
        name: String, email: String, password: String,
        role: UserRole, secQuestion: String, secAnswer: String,
        onSuccess: (User) -> Unit, onFailure: (String) -> Unit
    ) {
        if (email.isBlank() || password.length < 6) {
            onFailure("Email required and password must be at least 6 characters"); return
        }
        val body = JSONObject().apply {
            put("name", name.trim()); put("email", email.trim().lowercase())
            put("password", password); put("role", role.name)
            put("secQuestion", secQuestion); put("secAnswer", secAnswer.trim())
        }.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, resp) = postRaw("$BASE_URL/auth/register", body)
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        val j = JSONObject(resp)
                        val user = User(j.getString("userId"), j.getString("name"),
                            j.getString("email"), UserRole.valueOf(j.getString("role")))
                        prefs?.edit()?.putString(KEY_LAST_EMAIL, user.email)?.apply()
                        saveSession(user)
                        onSuccess(user)
                    } else {
                        onFailure(JSONObject(resp).optString("error", "Registration failed"))
                    }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    fun login(
        email: String, password: String,
        onSuccess: (User) -> Unit, onFailure: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("email", email.trim().lowercase()); put("password", password)
        }.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, resp) = postRaw("$BASE_URL/auth/login", body)
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        val j = JSONObject(resp)
                        val user = User(j.getString("userId"), j.getString("name"),
                            j.getString("email"), UserRole.valueOf(j.getString("role")))
                        prefs?.edit()?.putString(KEY_LAST_EMAIL, user.email)?.apply()
                        saveSession(user)
                        onSuccess(user)
                    } else {
                        onFailure(JSONObject(resp).optString("error", "Login failed"))
                    }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    // ── Biometric login ───────────────────────────────────────────────────────

    fun loginWithBiometric(
        email: String,
        onSuccess: (User) -> Unit, onFailure: (String) -> Unit
    ) {
        val body = JSONObject().apply { put("email", email.trim().lowercase()) }.toString()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, resp) = postRaw("$BASE_URL/auth/biometric", body)
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        val j = JSONObject(resp)
                        val user = User(j.getString("userId"), j.getString("name"),
                            j.getString("email"), UserRole.valueOf(j.getString("role")))
                        saveSession(user)
                        onSuccess(user)
                    } else {
                        onFailure(JSONObject(resp).optString("error", "Biometric login failed"))
                    }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    // ── Forgot password ───────────────────────────────────────────────────────

    /** Async version — fetches security question from backend. */
    fun getSecurityQuestion(
        email: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("$BASE_URL/auth/security-question?email=${java.net.URLEncoder.encode(email.trim().lowercase(), "UTF-8")}")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.readTimeout = 5000
                val code = conn.responseCode
                val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                withContext(Dispatchers.Main) {
                    if (code in 200..299)
                        onSuccess(JSONObject(resp).getString("question"))
                    else
                        onFailure(JSONObject(resp).optString("error", "Account not found"))
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    fun resetPassword(
        email: String, secAnswer: String, newPassword: String,
        onSuccess: () -> Unit, onFailure: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("email", email.trim().lowercase())
            put("secAnswer", secAnswer.trim())
            put("newPassword", newPassword)
        }.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, resp) = postRaw("$BASE_URL/auth/reset-password", body)
                withContext(Dispatchers.Main) {
                    if (code in 200..299) onSuccess()
                    else onFailure(JSONObject(resp).optString("error", "Reset failed"))
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    // ── Customer portal login — by email ─────────────────────────────────────

    fun loginAsCustomer(
        email: String, password: String,
        onSuccess: (User) -> Unit, onFailure: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("email", email.trim().lowercase()); put("password", password)
        }.toString()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, resp) = postRaw("$BASE_URL/customers/login", body)
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        val j = JSONObject(resp)
                        val mustChange = j.optString("mustChangePassword", "false").toBoolean()
                        val user = User(
                            id                 = j.getString("customerId"),
                            name               = j.getString("name"),
                            email              = j.getString("email"),
                            role               = UserRole.CUSTOMER,
                            customerId         = j.getString("customerId"),
                            unitId             = j.getString("unitId"),
                            projectId          = j.getString("projectId"),
                            phone              = j.optString("phone", ""),
                            mustChangePassword = mustChange
                        )
                        prefs?.edit()
                            ?.putString(KEY_LAST_EMAIL,              user.email)
                            ?.putString(KEY_CUSTOMER_ID,             user.customerId)
                            ?.putString(KEY_UNIT_ID,                 user.unitId)
                            ?.putString(KEY_PROJECT_ID,              user.projectId)
                            ?.putString(KEY_PHONE,                   user.phone)
                            ?.putBoolean(KEY_MUST_CHANGE_PASSWORD,   mustChange)
                            ?.apply()
                        saveSession(user)
                        onSuccess(user)
                    } else {
                        onFailure(JSONObject(resp).optString("error", "Customer login failed"))
                    }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    // ── Customer portal login — by phone ──────────────────────────────────────

    fun loginAsCustomerByPhone(
        phone: String, password: String,
        onSuccess: (User) -> Unit, onFailure: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("phone", phone.trim()); put("password", password)
        }.toString()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, resp) = postRaw("$BASE_URL/customers/login-phone", body)
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        val j = JSONObject(resp)
                        val mustChange = j.optString("mustChangePassword", "false").toBoolean()
                        val user = User(
                            id                 = j.getString("customerId"),
                            name               = j.getString("name"),
                            email              = phone.trim(),   // phone used as identifier
                            role               = UserRole.CUSTOMER,
                            customerId         = j.getString("customerId"),
                            unitId             = j.getString("unitId"),
                            projectId          = j.getString("projectId"),
                            phone              = phone.trim(),
                            mustChangePassword = mustChange
                        )
                        prefs?.edit()
                            ?.putString(KEY_LAST_EMAIL,            user.phone)
                            ?.putString(KEY_CUSTOMER_ID,           user.customerId)
                            ?.putString(KEY_UNIT_ID,               user.unitId)
                            ?.putString(KEY_PROJECT_ID,            user.projectId)
                            ?.putString(KEY_PHONE,                 user.phone)
                            ?.putBoolean(KEY_MUST_CHANGE_PASSWORD, mustChange)
                            ?.apply()
                        saveSession(user)
                        onSuccess(user)
                    } else {
                        onFailure(JSONObject(resp).optString("error", "Login failed"))
                    }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    // ── Admin: user list ──────────────────────────────────────────────────────

    fun getAllUsers(
        onSuccess: (List<Map<String, String>>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("$BASE_URL/auth/users").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.readTimeout = 5000
                val code = conn.responseCode
                val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream?.bufferedReader()?.readText() ?: "[]"
                conn.disconnect()
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        val arr = JSONArray(resp)
                        val list = (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            mapOf("userId" to o.optString("userId"),
                                  "name"   to o.optString("name"),
                                  "email"  to o.optString("email"),
                                  "role"   to o.optString("role"),
                                  "createdAt" to o.optString("createdAt"))
                        }
                        onSuccess(list)
                    } else onFailure("Failed to load users")
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    fun updateUserRole(
        userId: String, role: String,
        onSuccess: () -> Unit, onFailure: (String) -> Unit
    ) {
        val body = JSONObject().apply { put("role", role) }.toString()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, resp) = putRaw("$BASE_URL/auth/users/$userId/role", body)
                withContext(Dispatchers.Main) {
                    if (code in 200..299) onSuccess()
                    else onFailure(JSONObject(resp).optString("error", "Update failed"))
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    fun deleteUser(
        userId: String,
        onSuccess: () -> Unit, onFailure: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("$BASE_URL/auth/users/$userId").openConnection() as HttpURLConnection
                conn.requestMethod = "DELETE"; conn.connectTimeout = 5000; conn.readTimeout = 5000
                val code = conn.responseCode; conn.disconnect()
                withContext(Dispatchers.Main) {
                    if (code in 200..299) onSuccess() else onFailure("Delete failed: HTTP $code")
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    // ── Session ───────────────────────────────────────────────────────────────

    fun logout() {
        prefs?.edit()
            ?.remove(KEY_ID)?.remove(KEY_NAME)?.remove(KEY_EMAIL)?.remove(KEY_ROLE)
            ?.remove(KEY_CUSTOMER_ID)?.remove(KEY_UNIT_ID)?.remove(KEY_PROJECT_ID)
            ?.remove(KEY_PHONE)?.remove(KEY_MUST_CHANGE_PASSWORD)
            ?.apply()
    }

    fun getCurrentUser(): User? {
        val p     = prefs ?: return null
        val email = p.getString(KEY_EMAIL, null) ?: return null
        val role  = p.getString(KEY_ROLE,  null) ?: return null
        return User(
            id                 = p.getString(KEY_ID,          "") ?: "",
            name               = p.getString(KEY_NAME,        "") ?: "",
            email              = email,
            role               = UserRole.valueOf(role),
            customerId         = p.getString(KEY_CUSTOMER_ID, "") ?: "",
            unitId             = p.getString(KEY_UNIT_ID,     "") ?: "",
            projectId          = p.getString(KEY_PROJECT_ID,  "") ?: "",
            phone              = p.getString(KEY_PHONE,       "") ?: "",
            mustChangePassword = p.getBoolean(KEY_MUST_CHANGE_PASSWORD, false)
        )
    }

    fun isLoggedIn(): Boolean = getCurrentUser() != null

    fun getLastEmail(): String? = prefs?.getString(KEY_LAST_EMAIL, null)

    private fun saveSession(user: User) {
        prefs?.edit()
            ?.putString(KEY_ID,          user.id)
            ?.putString(KEY_NAME,        user.name)
            ?.putString(KEY_EMAIL,       user.email)
            ?.putString(KEY_ROLE,        user.role.name)
            ?.putString(KEY_CUSTOMER_ID, user.customerId)
            ?.putString(KEY_UNIT_ID,     user.unitId)
            ?.putString(KEY_PROJECT_ID,  user.projectId)
            ?.putString(KEY_PHONE,       user.phone)
            ?.putBoolean(KEY_MUST_CHANGE_PASSWORD, user.mustChangePassword)
            ?.apply()
    }

    /** Customer: change own password after first login. Clears the mustChangePassword flag. */
    fun changeCustomerPassword(
        customerId: String,
        newPassword: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val body = JSONObject().apply { put("newPassword", newPassword) }.toString()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (code, resp) = postRaw("$BASE_URL/customers/$customerId/change-password", body)
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        // Clear the flag locally
                        prefs?.edit()?.putBoolean(KEY_MUST_CHANGE_PASSWORD, false)?.apply()
                        onSuccess()
                    } else {
                        onFailure(JSONObject(resp).optString("error", "Password change failed"))
                    }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e.message ?: "Network error") } }
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun postRaw(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        OutputStreamWriter(c.outputStream).use { it.write(body) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        return Pair(code, resp)
    }

    private fun putRaw(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        OutputStreamWriter(c.outputStream).use { it.write(body) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        return Pair(code, resp)
    }
}
