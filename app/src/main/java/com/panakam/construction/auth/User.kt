package com.panakam.construction.auth

data class User(
    val id: String,
    val name: String,
    val email: String,
    val role: UserRole,
    // Populated only when role == CUSTOMER
    val customerId: String          = "",
    val unitId: String              = "",
    val projectId: String           = "",
    val phone: String               = "",   // used for multi-unit portal login
    val mustChangePassword: Boolean = false  // true on first login; customer must set a new password
)
