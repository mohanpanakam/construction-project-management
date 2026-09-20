package com.panakam.construction.backend.security

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

/**
 * A Sales Rep's attribution on a sale is a free-text "soldBy" name captured on
 * UnitCollections at sale time (chosen from the "Sold By" dropdown when the unit
 * was marked Sold — see ProjectUnitsScreen / CollectionRoutes.kt POST /collections).
 * There is no foreign key from UnitCollections.soldBy to Users, so scoping a Sales
 * Rep's visibility to "units I personally sold" means resolving THEIR OWN verified
 * name (never a client-supplied one) and comparing it case-insensitively.
 *
 * This is what enforces (server-side, not just in the UI) that a Sales Rep can see
 * a customer's identity/phone for any unit, but sale price/payment figures ONLY for
 * units whose "soldBy" matches their own name — Admin/Project Manager/Auditor are
 * never restricted this way.
 */

/** Looks up the CURRENT caller's own name from the Users table (blank if not found). */
suspend fun currentUserName(userId: String): String {
    if (userId.isBlank()) return ""
    return dbQuery {
        Users.selectAll().where { Users.userId eq userId }.firstOrNull()?.get(Users.name)
    } ?: ""
}

/** True when [soldBy] (a UnitCollections.soldBy value) refers to [repName] (case/whitespace-insensitive). */
fun soldByMatches(soldBy: String, repName: String): Boolean {
    if (repName.isBlank()) return false
    return soldBy.trim().equals(repName.trim(), ignoreCase = true)
}

/** Pricing/payment fields that must be hidden from a Sales Rep for a unit they didn't sell. */
val RESTRICTED_PRICING_FIELDS = listOf(
    "perSftPrice", "gstPercentage", "totalCost", "totalAmount", "baseAmount", "gstAmount",
    "paidAmount", "pendingAmount", "paymentStatus", "discountAmount", "discountReason"
)

/**
 * Returns [map] unchanged plus `"pricingRestricted" to "false"` when [role] isn't SALES_REP,
 * or when the Sales Rep's own name matches [soldBy]. Otherwise returns [map] with every field
 * in [RESTRICTED_PRICING_FIELDS] blanked out and `"pricingRestricted" to "true"` — customer
 * identity fields (name/phone/address/email) are left untouched either way.
 */
fun applyPricingRestriction(
    map: Map<String, String>,
    role: String,
    repName: String,
    soldBy: String
): Map<String, String> {
    if (role != "SALES_REP" || soldByMatches(soldBy, repName)) {
        return map + mapOf("pricingRestricted" to "false")
    }
    val blanked = RESTRICTED_PRICING_FIELDS.associateWith { "" }
    return map + blanked + mapOf("pricingRestricted" to "true")
}

