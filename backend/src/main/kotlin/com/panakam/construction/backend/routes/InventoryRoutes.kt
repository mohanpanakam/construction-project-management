package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Inventory
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.inventoryRoutes() {

    route("/inventory") {

        // GET /inventory/{projectId}  – list all items for a project
        get("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val items = dbQuery {
                Inventory.selectAll()
                    .where { Inventory.projectId eq projectId }
                    .orderBy(Inventory.name)
                    .map { it.toInventoryMap() }
            }
            call.respond(HttpStatusCode.OK, items)
        }

        // POST /inventory  – add a new item
        post {
            val json      = Json.parseToJsonElement(call.receiveText()).jsonObject
            val projectId = json["projectId"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val itemId = json["itemId"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing itemId"))

            val qty = json.str("quantity").toDoubleOrNull() ?: 0.0
            val availQty = json.str("availableQuantity")
                .toDoubleOrNull() ?: qty   // default available = total

            dbQuery {
                Inventory.insert {
                    it[Inventory.itemId]            = itemId
                    it[Inventory.projectId]         = projectId
                    it[name]                        = json.str("name")
                    it[quantity]                    = qty
                    it[availableQuantity]           = availQty
                    it[unit]                        = json.str("unit", "pcs")
                    it[category]                    = json.str("category", "Materials")
                    it[status]                      = json.str("status", "Available")
                    it[notes]                       = json.str("notes")
                }
            }
            call.respond(HttpStatusCode.Created, mapOf("message" to "Inventory item added", "itemId" to itemId))
        }

        // PUT /inventory/{projectId}/{itemId}  – update availability / status
        put("/{projectId}/{itemId}") {
            val projectId = call.parameters["projectId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val itemId = call.parameters["itemId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing itemId"))

            val json = Json.parseToJsonElement(call.receiveText()).jsonObject

            dbQuery {
                Inventory.update({
                    (Inventory.projectId eq projectId) and (Inventory.itemId eq itemId)
                }) {
                    json.str("name").takeIf { it.isNotBlank() }?.let    { v -> it[name]     = v }
                    json.str("quantity").toDoubleOrNull()?.let          { v -> it[quantity]  = v }
                    json.str("availableQuantity").toDoubleOrNull()?.let { v -> it[availableQuantity] = v }
                    json.str("unit").takeIf { it.isNotBlank() }?.let   { v -> it[unit]      = v }
                    json.str("category").takeIf { it.isNotBlank() }?.let { v -> it[category] = v }
                    json.str("status").takeIf { it.isNotBlank() }?.let { v -> it[status]    = v }
                    json.str("notes").let                               { v -> it[notes]     = v }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Inventory item updated", "itemId" to itemId))
        }

        // DELETE /inventory/{projectId}/{itemId}
        delete("/{projectId}/{itemId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val itemId = call.parameters["itemId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing itemId"))
            dbQuery {
                Inventory.deleteWhere {
                    (Inventory.projectId eq projectId) and (Inventory.itemId eq itemId)
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Inventory item deleted"))
        }
    }
}

private fun ResultRow.toInventoryMap() = mapOf(
    "itemId"            to this[Inventory.itemId],
    "projectId"         to this[Inventory.projectId],
    "name"              to this[Inventory.name],
    "quantity"          to this[Inventory.quantity].toString(),
    "availableQuantity" to this[Inventory.availableQuantity].toString(),
    "unit"              to this[Inventory.unit],
    "category"          to this[Inventory.category],
    "status"            to this[Inventory.status],
    "notes"             to this[Inventory.notes]
)
