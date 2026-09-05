package com.panakam.construction.auth

data class User(
    val id: String,
    val name: String,
    // Login identifier — now a PHONE NUMBER for both staff and customer accounts
    // (field kept named `email` to avoid touching every call site; it no longer
    // holds an email address).
    val email: String,
    val role: UserRole,
    // Populated only when role == CUSTOMER
    val customerId: String          = "",
    val unitId: String              = "",
    val projectId: String           = "",
    val phone: String               = "",   // used for multi-unit portal login
    val contactEmail: String        = "",   // optional — for future notifications only, never used for login
    val mustChangePassword: Boolean = false  // true on first login; customer must set a new password
)
