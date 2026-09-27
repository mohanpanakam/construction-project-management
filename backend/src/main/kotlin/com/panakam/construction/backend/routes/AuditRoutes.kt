package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.AuditLogs
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Users
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*

fun Route.auditRoutes() {

    route("/audit") {

        // GET /audit/{tableName}  – last N entries for a table
        get("/{tableName}") {
            val table = call.parameters["tableName"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val list  = dbQuery {
                AuditLogs.selectAll()
                    .where { AuditLogs.tableRef eq table }
                    .orderBy(AuditLogs.changedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { it.toAuditMap() }
                    .withChangedByNames()
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // GET /audit/record/{tableName}/{recordId}
        get("/record/{tableName}/{recordId}") {
            val table    = call.parameters["tableName"]  ?: return@get call.respond(HttpStatusCode.BadRequest)
            val recordId = call.parameters["recordId"]   ?: return@get call.respond(HttpStatusCode.BadRequest)
            val list = dbQuery {
                AuditLogs.selectAll()
                    .where { (AuditLogs.tableRef eq table) and (AuditLogs.recordId eq recordId) }
                    .orderBy(AuditLogs.changedAt, SortOrder.DESC)
                    .map { it.toAuditMap() }
                    .withChangedByNames()
            }
            call.respond(HttpStatusCode.OK, list)
        }
    }
}

private fun ResultRow.toAuditMap() = mapOf(
    "logId"     to this[AuditLogs.logId],
    "tableName" to this[AuditLogs.tableRef],
    "recordId"  to this[AuditLogs.recordId],
    "action"    to this[AuditLogs.action],
    "changedBy" to this[AuditLogs.changedBy],
    "changedAt" to this[AuditLogs.changedAt].toString(),
    "oldValues" to this[AuditLogs.oldValues],
    "newValues" to this[AuditLogs.newValues]
)

/** `changedBy` on AuditLogs is stored as a raw userId (or "SYSTEM"/"" for
 *  automated actions) — not useful to display directly. This resolves each
 *  distinct userId to the staff member's actual name in one batched lookup
 *  and adds it as `changedByName` (falls back to the raw id, e.g. "SYSTEM",
 *  when it doesn't match any known user) so callers (e.g. the admin/auditor
 *  audit-trail screens) can show WHO actually made each change. */
private fun List<Map<String, Any?>>.withChangedByNames(): List<Map<String, Any?>> {
    val ids = mapNotNull { it["changedBy"]?.toString() }.filter { it.isNotBlank() }.toSet()
    if (ids.isEmpty()) return this
    val namesById = Users.selectAll()
        .where { Users.userId inList ids }
        .associate { it[Users.userId] to it[Users.name] }
    return map { row ->
        val changedBy = row["changedBy"]?.toString() ?: ""
        val name = namesById[changedBy]?.takeIf { it.isNotBlank() } ?: changedBy
        row + ("changedByName" to name)
    }
}


