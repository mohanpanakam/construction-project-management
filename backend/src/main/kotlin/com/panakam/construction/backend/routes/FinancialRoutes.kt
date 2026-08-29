package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Financials
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.financialRoutes() {

    route("/financials") {

        // GET /financials/{projectId}
        get("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val records = dbQuery {
                Financials.selectAll()
                    .where { Financials.projectId eq projectId }
                    .orderBy(Financials.date, SortOrder.DESC)
                    .map { it.toFinancialMap() }
            }
            call.respond(HttpStatusCode.OK, records)
        }

        // POST /financials
        post {
            val json      = Json.parseToJsonElement(call.receiveText()).jsonObject
            val projectId = json["projectId"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val recordId  = json["recordId"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing recordId"))

            dbQuery {
                Financials.insert {
                    it[Financials.recordId]  = recordId
                    it[Financials.projectId] = projectId
                    it[type]                 = json.str("type", "Expense")
                    it[category]             = json.str("category", "Other")
                    it[amount]               = json.str("amount").toDoubleOrNull() ?: 0.0
                    it[description]          = json.str("description")
                    it[date]                 = json.str("date")
                }
            }
            call.respond(HttpStatusCode.Created, mapOf("message" to "Financial record added", "recordId" to recordId))
        }

        // DELETE /financials/{projectId}/{recordId}
        delete("/{projectId}/{recordId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val recordId  = call.parameters["recordId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing recordId"))
            dbQuery {
                Financials.deleteWhere {
                    (Financials.projectId eq projectId) and (Financials.recordId eq recordId)
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Financial record deleted"))
        }
    }
}

private fun ResultRow.toFinancialMap() = mapOf(
    "recordId"    to this[Financials.recordId],
    "projectId"   to this[Financials.projectId],
    "type"        to this[Financials.type],
    "category"    to this[Financials.category],
    "amount"      to this[Financials.amount].toString(),
    "description" to this[Financials.description],
    "date"        to this[Financials.date]
)
