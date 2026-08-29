package com.panakam.construction.auth

data class User(
    val id: String,
    val name: String,
    val email: String,
    val role: UserRole
)
