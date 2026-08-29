package com.panakam.construction.auth

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Manages user authentication and session.
 * All registered users are persisted in SharedPreferences so they survive app restarts.
 */
object AuthManager {

    private const val PREF_NAME       = "construction_auth"
    private const val KEY_ID          = "user_id"
    private const val KEY_NAME        = "user_name"
    private const val KEY_EMAIL       = "user_email"
    private const val KEY_ROLE        = "user_role"
    private const val KEY_LAST_EMAIL  = "last_email"

    private var prefs: SharedPreferences? = null

    /** Predefined security questions shown at registration. */
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

    // ── Key helpers ──────────────────────────────────────────────────────────

    private fun userKey(email: String) = "reg_${email.lowercase().trim()}"

    // ── Registration ─────────────────────────────────────────────────────────

    fun register(
        name: String,
        email: String,
        password: String,
        role: UserRole,
        secQuestion: String,
        secAnswer: String,
        onSuccess: (User) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (email.isBlank() || password.length < 6) {
            onFailure("Email required and password must be at least 6 characters"); return
        }
        val key = userKey(email)
        if (prefs?.contains(key) == true) {
            onFailure("An account with this email already exists"); return
        }
        val id = email.lowercase().trim().hashCode().toString()
        val userData = JSONObject().apply {
            put("id",          id)
            put("name",        name.trim())
            put("password",    password)
            put("role",        role.name)
            put("secQuestion", secQuestion)
            put("secAnswer",   secAnswer.lowercase().trim())
        }.toString()
        prefs?.edit()
            ?.putString(key, userData)
            ?.putString(KEY_LAST_EMAIL, email.lowercase().trim())
            ?.apply()
        val user = User(id, name.trim(), email, role)
        saveSession(user)
        onSuccess(user)
    }

    // ── Login ────────────────────────────────────────────────────────────────

    fun login(
        email: String,
        password: String,
        onSuccess: (User) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val key = userKey(email)
        val raw = prefs?.getString(key, null)
        if (raw == null) {
            onFailure("No account found with this email. Please register first."); return
        }
        val json = JSONObject(raw)
        if (json.getString("password") != password) {
            onFailure("Incorrect password. Please try again."); return
        }
        val user = User(
            id    = json.getString("id"),
            name  = json.getString("name"),
            email = email.trim(),
            role  = UserRole.valueOf(json.getString("role"))
        )
        prefs?.edit()?.putString(KEY_LAST_EMAIL, email.lowercase().trim())?.apply()
        saveSession(user)
        onSuccess(user)
    }

    /** Login without password — used after successful biometric authentication. */
    fun loginWithBiometric(
        email: String,
        onSuccess: (User) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val raw = prefs?.getString(userKey(email), null)
        if (raw == null) { onFailure("Account not found"); return }
        val json = JSONObject(raw)
        val user = User(
            id    = json.getString("id"),
            name  = json.getString("name"),
            email = email.trim(),
            role  = UserRole.valueOf(json.getString("role"))
        )
        saveSession(user)
        onSuccess(user)
    }

    // ── Forgot password ──────────────────────────────────────────────────────

    /** Returns the security question for the given email, or null if account not found. */
    fun getSecurityQuestion(email: String): String? {
        val raw = prefs?.getString(userKey(email), null) ?: return null
        return JSONObject(raw).optString("secQuestion").takeIf { it.isNotBlank() }
    }

    fun resetPassword(
        email: String,
        secAnswer: String,
        newPassword: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val key = userKey(email)
        val raw = prefs?.getString(key, null)
        if (raw == null) { onFailure("No account found with this email"); return }
        if (newPassword.length < 6) { onFailure("Password must be at least 6 characters"); return }
        val json = JSONObject(raw)
        if (json.getString("secAnswer") != secAnswer.lowercase().trim()) {
            onFailure("Incorrect answer. Please try again."); return
        }
        json.put("password", newPassword)
        prefs?.edit()?.putString(key, json.toString())?.apply()
        onSuccess()
    }

    // ── Session ──────────────────────────────────────────────────────────────

    fun logout() {
        prefs?.edit()
            ?.remove(KEY_ID)?.remove(KEY_NAME)
            ?.remove(KEY_EMAIL)?.remove(KEY_ROLE)
            ?.apply()
    }

    fun getCurrentUser(): User? {
        val p     = prefs ?: return null
        val email = p.getString(KEY_EMAIL, null) ?: return null
        val role  = p.getString(KEY_ROLE,  null) ?: return null
        return User(
            id    = p.getString(KEY_ID,   "") ?: "",
            name  = p.getString(KEY_NAME, "") ?: "",
            email = email,
            role  = UserRole.valueOf(role)
        )
    }

    fun isLoggedIn(): Boolean = getCurrentUser() != null

    /** Email of the last successfully logged-in user, used for biometric login. */
    fun getLastEmail(): String? = prefs?.getString(KEY_LAST_EMAIL, null)

    private fun saveSession(user: User) {
        prefs?.edit()
            ?.putString(KEY_ID,    user.id)
            ?.putString(KEY_NAME,  user.name)
            ?.putString(KEY_EMAIL, user.email)
            ?.putString(KEY_ROLE,  user.role.name)
            ?.apply()
    }
}
