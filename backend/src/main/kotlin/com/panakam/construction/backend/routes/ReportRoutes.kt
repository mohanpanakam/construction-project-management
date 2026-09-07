package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.CustomerPayments
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.UnitCollections
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Customer/unit payment status report:
 * customerName, unitId, totalCost, paidAudited, paidUnaudited, balanceAudited, balanceUnaudited.
 *
 * - "Audited" figures reflect only payments an auditor has approved (auditStatus=AUDITED) —
 *   this matches what's actually credited into UnitCollections.paidAmount.
 * - "Unaudited" figures are payments still awaiting audit (auditStatus=PENDING) — money the
 *   customer claims to have paid but not yet verified. REJECTED payments count as neither
 *   (they never happened, from an accounting point of view).
 * - balanceAudited   = totalCost − paidAudited                    (the "official" balance)
 * - balanceUnaudited = totalCost − paidAudited − paidUnaudited     (projected balance if all
 *                       pending payments get approved)
 *
 * Can be scoped to a single project (?projectId=) and/or a single sales rep (?soldBy=<name>)
 * so an Admin can view the whole portfolio while a Sales Rep only sees their own units
 * ("soldBy" is the free-text attribution captured on UnitCollections when a unit is sold —
 * see SalesRepRoutes.kt / CollectionRoutes.kt POST /collections).
 */
fun Route.reportRoutes() {

    route("/reports") {

        // ── GET /reports/customer-payments?projectId=&soldBy=  (JSON) ────────
        get("/customer-payments") {
            val projectId = call.request.queryParameters["projectId"]
            val soldBy    = call.request.queryParameters["soldBy"]
            call.respond(HttpStatusCode.OK, buildCustomerPaymentReport(projectId, soldBy))
        }

        // ── GET /reports/customer-payments/csv?projectId=&soldBy=  (download) ─
        get("/customer-payments/csv") {
            val projectId = call.request.queryParameters["projectId"]
            val soldBy    = call.request.queryParameters["soldBy"]
            val rows = buildCustomerPaymentReport(projectId, soldBy)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, "customer_payments_report.csv")
                    .toString()
            )
            call.respondText(buildCsv(rows), ContentType.parse("text/csv"))
        }
    }
}

private val REPORT_COLUMNS = listOf(
    "customerName", "unitNumber", "unitId", "soldBy", "projectId",
    "totalCost", "paidAudited", "paidUnaudited", "balanceAudited", "balanceUnaudited"
)

private suspend fun buildCustomerPaymentReport(projectId: String?, soldBy: String?): List<Map<String, String>> {
    // UnitCollections already holds customerName + totalAmount per unit (one active row
    // per unit), so it's the natural base — avoids re-deriving totalCost from Customers.
    val collections = dbQuery {
        var q = UnitCollections.selectAll().where { UnitCollections.status eq "Active" }
        if (!projectId.isNullOrBlank()) q = q.andWhere { UnitCollections.projectId eq projectId }
        if (!soldBy.isNullOrBlank())    q = q.andWhere { UnitCollections.soldBy eq soldBy }
        q.map {
            mapOf(
                "unitId"       to it[UnitCollections.unitId],
                "unitNumber"   to it[UnitCollections.unitNumber],
                "customerName" to it[UnitCollections.customerName],
                "totalCost"    to it[UnitCollections.totalAmount],
                "soldBy"       to it[UnitCollections.soldBy].ifBlank { "Admin" },
                "projectId"    to it[UnitCollections.projectId]
            )
        }
    }
    if (collections.isEmpty()) return emptyList()

    val unitIds = collections.map { it["unitId"] as String }.distinct()

    // Sum payment amounts per (unitId, auditStatus) in one pass over the relevant payments.
    val paymentSums: Map<Pair<String, String>, Double> = dbQuery {
        CustomerPayments.selectAll()
            .where { CustomerPayments.unitId inList unitIds }
            .groupBy { row -> row[CustomerPayments.unitId] to row[CustomerPayments.auditStatus] }
            .mapValues { (_, rows) -> rows.sumOf { it[CustomerPayments.amount] } }
    }

    return collections.map { c ->
        val unitId    = c["unitId"] as String
        val totalCost = c["totalCost"] as Double
        val paidAudited   = paymentSums[unitId to "AUDITED"] ?: 0.0
        val paidUnaudited = paymentSums[unitId to "PENDING"] ?: 0.0
        val balanceAudited   = (totalCost - paidAudited).coerceAtLeast(0.0)
        val balanceUnaudited = (totalCost - paidAudited - paidUnaudited).coerceAtLeast(0.0)
        mapOf(
            "customerName"     to (c["customerName"] as String),
            "unitId"           to unitId,
            "unitNumber"       to (c["unitNumber"] as String),
            "soldBy"           to (c["soldBy"] as String),
            "projectId"        to (c["projectId"] as String),
            "totalCost"        to totalCost.toString(),
            "paidAudited"      to paidAudited.toString(),
            "paidUnaudited"    to paidUnaudited.toString(),
            "balanceAudited"   to balanceAudited.toString(),
            "balanceUnaudited" to balanceUnaudited.toString()
        )
    }.sortedBy { it["customerName"] }
}

private fun csvEscape(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value

// Numeric columns that get summed into the trailing "TOTAL" row.
private val CSV_TOTAL_COLUMNS = listOf("totalCost", "paidAudited", "paidUnaudited", "balanceAudited", "balanceUnaudited")

// Plain (non-scientific-notation) 2-decimal formatting for money columns — Double.toString()
// switches to "5.3682E7"-style notation above 1e7, which breaks/confuses CSV/Excel readers.
private fun plainAmount(value: Double): String = java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

private fun buildCsv(rows: List<Map<String, String>>): String {
    val sb = StringBuilder()
    sb.append(REPORT_COLUMNS.joinToString(",")).append("\n")
    rows.forEach { row ->
        sb.append(REPORT_COLUMNS.joinToString(",") { col ->
            val raw = row[col] ?: ""
            val value = if (col in CSV_TOTAL_COLUMNS) raw.toDoubleOrNull()?.let { plainAmount(it) } ?: raw else raw
            csvEscape(value)
        }).append("\n")
    }
    if (rows.isNotEmpty()) {
        val totals = CSV_TOTAL_COLUMNS.associateWith { col ->
            plainAmount(rows.sumOf { it[col]?.toDoubleOrNull() ?: 0.0 })
        }
        val totalRow = REPORT_COLUMNS.joinToString(",") { col ->
            when (col) {
                "customerName" -> "TOTAL"
                in CSV_TOTAL_COLUMNS -> totals[col] ?: "0.00"
                else -> ""
            }
        }
        sb.append(totalRow).append("\n")
    }
    return sb.toString()
}

