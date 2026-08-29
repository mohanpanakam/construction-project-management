package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.AuditLogs
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
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
                    .where { AuditLogs.tableName eq table }
                    .orderBy(AuditLogs.changedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { it.toAuditMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // GET /audit/record/{tableName}/{recordId}
        get("/record/{tableName}/{recordId}") {
            val table    = call.parameters["tableName"]  ?: return@get call.respond(HttpStatusCode.BadRequest)
            val recordId = call.parameters["recordId"]   ?: return@get call.respond(HttpStatusCode.BadRequest)
            val list = dbQuery {
                AuditLogs.selectAll()
                    .where { (AuditLogs.tableName eq table) and (AuditLogs.recordId eq recordId) }
                    .orderBy(AuditLogs.changedAt, SortOrder.DESC)
                    .map { it.toAuditMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }
    }
}

private fun ResultRow.toAuditMap() = mapOf(
    "logId"     to this[AuditLogs.logId],
    "tableName" to this[AuditLogs.tableName],
    "recordId"  to this[AuditLogs.recordId],
    "action"    to this[AuditLogs.action],
    "changedBy" to this[AuditLogs.changedBy],
    "changedAt" to this[AuditLogs.changedAt].toString(),
    "oldValues" to this[AuditLogs.oldValues],
    "newValues" to this[AuditLogs.newValues]
)

