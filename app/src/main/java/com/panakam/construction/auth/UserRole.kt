package com.panakam.construction.auth

enum class UserRole(val displayName: String) {
    ADMIN("Admin"),
    PROJECT_MANAGER("Project Manager"),
    SITE_WORKER("Site Worker"),
    AUDITOR("Auditor"),            // can view & audit all payments; read-only elsewhere
    SALES_REP("Sales Rep"),        // can add/update payments only for units they personally sold
    CUSTOMER("Customer")           // read-only portal; can upload own receipts/KYC
}
