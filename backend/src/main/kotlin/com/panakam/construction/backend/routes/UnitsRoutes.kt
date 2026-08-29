package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Units
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.UUID

fun Route.unitsRoutes() {

    route("/projects/{projectId}/units") {

        // ── GET /projects/{projectId}/units?availability=Available ────────────
        get {
            val projectId    = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val availability = call.request.queryParameters["availability"]
            val owner        = call.request.queryParameters["owner"]

            val units = dbQuery {
                var q = Units.selectAll().where { Units.projectId eq projectId }
                if (availability != null) q = q.andWhere { Units.availability eq availability }
                if (owner        != null) q = q.andWhere { Units.owner        eq owner }
                q.orderBy(Units.floor).orderBy(Units.unitNumber).map { it.toUnitMap() }
            }
            call.respond(HttpStatusCode.OK, units)
        }

        // ── GET /projects/{projectId}/units/summary ───────────────────────────
        get("/summary") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val units = dbQuery {
                Units.selectAll().where { Units.projectId eq projectId }.map { it.toUnitMap() }
            }
            val total     = units.size
            val available = units.count { it["availability"] == "Available" }
            val blocked   = units.count { it["availability"] == "Blocked" }
            val sold      = units.count { it["availability"] == "Sold" }
            val builder   = units.count { it["owner"] == "Builder" }
            val landOwner = units.count { it["owner"] == "LandOwner" }
            val byType    = units.groupBy { it["type"] ?: "" }
                .mapValues { (_, v) -> v.size }

            call.respond(HttpStatusCode.OK, mapOf(
                "total"     to total.toString(),
                "available" to available.toString(),
                "blocked"   to blocked.toString(),
                "sold"      to sold.toString(),
                "builder"   to builder.toString(),
                "landOwner" to landOwner.toString(),
                "byType"    to byType.toString()
            ))
        }

        // ── POST /projects/{projectId}/units  (single unit) ───────────────────
        post {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val json   = Json.parseToJsonElement(call.receiveText()).jsonObject
            val unitId = json["unitId"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()

            dbQuery {
                Units.insert {
                    it[Units.unitId]      = unitId
                    it[Units.projectId]   = projectId
                    it[unitNumber]        = json.str("unitNumber")
                    it[floor]             = json.str("floor")
                    it[type]              = json.str("type")
                    it[sba]               = json.str("sba").toDoubleOrNull() ?: 0.0
                    it[status]            = json.str("status", "Under Construction")
                    it[availability]      = json.str("availability", "Available")
                    it[owner]             = json.str("owner", "Builder")
                }
            }
            call.respond(HttpStatusCode.Created, mapOf("message" to "Unit added", "unitId" to unitId))
        }

        // ── POST /projects/{projectId}/units/upload  (Excel bulk import) ──────
        post("/upload") {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val multipart   = call.receiveMultipart()
            var fileBytes: ByteArray? = null

            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    fileBytes = part.streamProvider().readBytes()
                }
                part.dispose()
            }

            val bytes = fileBytes
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file received"))

            val units = parseUnitsFromExcel(bytes)
            if (units.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "No valid rows found. Check column headers."))
            }

            dbQuery {
                units.forEach { row ->
                    Units.insert {
                        it[Units.unitId]    = UUID.randomUUID().toString()
                        it[Units.projectId] = projectId
                        it[unitNumber]      = row["unitNumber"] ?: ""
                        it[floor]           = row["floor"]      ?: ""
                        it[type]            = row["type"]       ?: ""
                        it[sba]             = row["sba"]?.toDoubleOrNull() ?: 0.0
                        it[status]          = row["status"]       ?: "Under Construction"
                        it[availability]    = row["availability"] ?: "Available"
                        it[owner]           = row["owner"]        ?: "Builder"
                    }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Units imported successfully",
                "count"   to units.size.toString()
            ))
        }

        // ── PUT /projects/{projectId}/units/{unitId} ──────────────────────────
        put("/{unitId}") {
            val projectId = call.parameters["projectId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val unitId = call.parameters["unitId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId"))

            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            dbQuery {
                Units.update({ (Units.projectId eq projectId) and (Units.unitId eq unitId) }) {
                    json.str("unitNumber").takeIf { it.isNotBlank() }?.let   { v -> it[unitNumber]   = v }
                    json.str("floor").takeIf      { it.isNotBlank() }?.let   { v -> it[floor]        = v }
                    json.str("type").takeIf       { it.isNotBlank() }?.let   { v -> it[type]         = v }
                    json.str("sba").toDoubleOrNull()?.let                     { v -> it[sba]          = v }
                    json.str("status").takeIf     { it.isNotBlank() }?.let   { v -> it[status]       = v }
                    json.str("availability").takeIf { it.isNotBlank() }?.let { v -> it[availability] = v }
                    json.str("owner").takeIf      { it.isNotBlank() }?.let   { v -> it[owner]        = v }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Unit updated", "unitId" to unitId))
        }

        // ── DELETE /projects/{projectId}/units/{unitId} ───────────────────────
        delete("/{unitId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val unitId = call.parameters["unitId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId"))
            dbQuery {
                Units.deleteWhere { (Units.projectId eq projectId) and (Units.unitId eq unitId) }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Unit deleted"))
        }
    }
}

// ── Excel parser ──────────────────────────────────────────────────────────────

/**
 * Parses an .xls or .xlsx byte array.
 * Expected column headers (case-insensitive, spaces/underscores interchangeable):
 *   unit number | floor | type | sba | status | availability | owner
 */
private fun parseUnitsFromExcel(bytes: ByteArray): List<Map<String, String>> {
    val workbook = WorkbookFactory.create(bytes.inputStream())
    val sheet    = workbook.getSheetAt(0)
    val result   = mutableListOf<Map<String, String>>()

    // Build header → column index map from row 0
    val headerRow = sheet.getRow(0) ?: return emptyList()
    val headers   = (0 until headerRow.lastCellNum).associate { i ->
        i to headerRow.getCell(i)?.toString()?.trim()?.lowercase()
            ?.replace(" ", "").replace("_", "") ?: ""
    }

    fun colOf(vararg names: String) = headers.entries
        .firstOrNull { (_, v) -> names.any { it.equals(v, ignoreCase = true) } }?.key

    val colUnitNumber   = colOf("unitnumber", "unit")
    val colFloor        = colOf("floor")
    val colType         = colOf("type", "unittype")
    val colSba          = colOf("sba", "superbuiltup", "superbuiltuparea", "area")
    val colStatus       = colOf("status")
    val colAvailability = colOf("availability")
    val colOwner        = colOf("owner")

    for (rowIdx in 1..sheet.lastRowNum) {
        val row = sheet.getRow(rowIdx) ?: continue
        fun cell(col: Int?) = col?.let { row.getCell(it)?.toString()?.trim() } ?: ""

        val unitNumber = cell(colUnitNumber).ifBlank { continue }  // required
        result += mapOf(
            "unitNumber"   to unitNumber,
            "floor"        to cell(colFloor),
            "type"         to cell(colType),
            "sba"          to cell(colSba),
            "status"       to cell(colStatus).ifBlank { "Under Construction" },
            "availability" to cell(colAvailability).ifBlank { "Available" },
            "owner"        to cell(colOwner).ifBlank { "Builder" }
        )
    }
    workbook.close()
    return result
}

private fun ResultRow.toUnitMap() = mapOf(
    "unitId"       to this[Units.unitId],
    "projectId"    to this[Units.projectId],
    "unitNumber"   to this[Units.unitNumber],
    "floor"        to this[Units.floor],
    "type"         to this[Units.type],
    "sba"          to this[Units.sba].toString(),
    "status"       to this[Units.status],
    "availability" to this[Units.availability],
    "owner"        to this[Units.owner]
)

