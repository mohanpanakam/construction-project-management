package com.panakam.construction.auth

enum class UserRole(val displayName: String) {
    ADMIN("Admin"),
    PROJECT_MANAGER("Project Manager"),
    SITE_WORKER("Site Worker"),
    CUSTOMER("Customer")          // read-only portal; can upload own receipts/KYC
}
