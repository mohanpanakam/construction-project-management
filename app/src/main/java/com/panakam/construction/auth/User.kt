package com.panakam.construction.auth

data class User(
    val id: String,
    val name: String,
    val email: String,
    val role: UserRole,
    // Populated only when role == CUSTOMER
    val customerId: String = "",
    val unitId: String     = "",
    val projectId: String  = ""
)
