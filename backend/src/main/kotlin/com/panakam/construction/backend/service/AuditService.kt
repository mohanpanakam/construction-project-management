package com.panakam.construction.backend.service

import com.panakam.construction.backend.db.AuditLogs
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.insert
import java.util.UUID

object AuditService {
    suspend fun log(
        tableName: String,
        recordId: String,
        action: String,           // CREATE | UPDATE | DELETE
        changedBy: String = "",
        oldValues: String = "",
        newValues: String = ""
    ) {
        try {
            dbQuery {
                AuditLogs.insert {
                    it[AuditLogs.logId]     = UUID.randomUUID().toString()
                    it[AuditLogs.tableRef]  = tableName
                    it[AuditLogs.recordId]  = recordId
                    it[AuditLogs.action]    = action
                    it[AuditLogs.changedBy] = changedBy
                    it[AuditLogs.changedAt] = System.currentTimeMillis()
                    it[AuditLogs.oldValues] = oldValues
                    it[AuditLogs.newValues] = newValues
                }
            }
        } catch (_: Exception) { /* audit must never break main flow */ }
    }
}

