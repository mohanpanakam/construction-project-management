package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.Units
import com.panakam.construction.backend.db.UnitCollections
import com.panakam.construction.backend.db.SuspenseEntries
import com.panakam.construction.backend.db.Financials
import com.panakam.construction.backend.service.AuditService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.usermodel.DataFormatter
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

        // ── GET /projects/{projectId}/units/{unitId}  (single unit) ───────────
        get("/{unitId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val unitId = call.parameters["unitId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId"))
            val unit = dbQuery {
                Units.selectAll()
                    .where { (Units.projectId eq projectId) and (Units.unitId eq unitId) }
                    .singleOrNull()?.toUnitMap()
            }
            if (unit == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unit not found"))
            else              call.respond(HttpStatusCode.OK, unit)
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

            val parsed = parseUnitsFromExcel(bytes)
            val units  = parsed.rows
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
            val response = mutableMapOf(
                "message" to "Units imported successfully",
                "count"   to units.size.toString()
            )
            if (!parsed.sbaColumnFound) {
                response["warning"] = "SBA column not detected in the sheet — all imported units " +
                    "were saved with SBA = 0. Please rename the area column to something like " +
                    "\"SBA\", \"SFT\", or \"Super Built-up Area\" and re-import, or fix each unit " +
                    "individually from the Units screen."
            }
            call.respond(HttpStatusCode.OK, response)
        }

        // ── PUT /projects/{projectId}/units/{unitId} ──────────────────────────
        put("/{unitId}") {
            val projectId = call.parameters["projectId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val unitId = call.parameters["unitId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId"))

            val json   = Json.parseToJsonElement(call.receiveText()).jsonObject
            // Guard: never let a blank/zero SBA silently overwrite an existing, already-fixed
            // value (a client-side bug previously caused this, which also zeroed the sale cost).
            val requestedSba = json.str("sba").toDoubleOrNull()
            val newSba = requestedSba?.takeIf { it > 0 }
            dbQuery {
                Units.update({ (Units.projectId eq projectId) and (Units.unitId eq unitId) }) {
                    json.str("unitNumber").takeIf { it.isNotBlank() }?.let   { v -> it[unitNumber]   = v }
                    json.str("floor").takeIf      { it.isNotBlank() }?.let   { v -> it[floor]        = v }
                    json.str("type").takeIf       { it.isNotBlank() }?.let   { v -> it[type]         = v }
                    newSba?.let                                               { v -> it[sba]          = v }
                    json.str("status").takeIf     { it.isNotBlank() }?.let   { v -> it[status]       = v }
                    json.str("availability").takeIf { it.isNotBlank() }?.let { v -> it[availability] = v }
                    json.str("owner").takeIf      { it.isNotBlank() }?.let   { v -> it[owner]        = v }
                }
            }

            // ── Keep the sale price in sync ────────────────────────────────────
            // If SBA changed on a unit that already has an active sale record,
            // recompute base/GST/total (and the customer's cached totalCost) so the
            // sale price always reflects the current SBA and per-sqft rate — this
            // fixes the bug where SBA/cost drifted out of sync after edits.
            if (newSba != null) {
                dbQuery {
                    val activeCollection = UnitCollections.selectAll()
                        .where { (UnitCollections.unitId eq unitId) and (UnitCollections.status eq "Active") }
                        .orderBy(UnitCollections.createdAt, SortOrder.DESC)
                        .firstOrNull()
                    if (activeCollection != null) {
                        val perSft   = activeCollection[UnitCollections.perSftPrice]
                        val gstPct   = activeCollection[UnitCollections.gstPercentage]
                        val paidAmt  = activeCollection[UnitCollections.paidAmount]
                        val newBase  = perSft * newSba
                        val newGst   = newBase * gstPct / 100
                        val newTotal = newBase + newGst
                        val newPending = (newTotal - paidAmt).coerceAtLeast(0.0)
                        val newPayStatus = when {
                            paidAmt <= 0.0        -> "Unpaid"
                            paidAmt >= newTotal   -> "Fully Paid"
                            else                  -> "Partial"
                        }
                        UnitCollections.update({ UnitCollections.collectionId eq activeCollection[UnitCollections.collectionId] }) {
                            it[UnitCollections.sba]           = newSba
                            it[UnitCollections.baseAmount]    = newBase
                            it[UnitCollections.gstAmount]     = newGst
                            it[UnitCollections.totalAmount]   = newTotal
                            it[UnitCollections.pendingAmount] = newPending
                            it[UnitCollections.paymentStatus] = newPayStatus
                        }

                        val custRow = Customers.selectAll()
                            .where { (Customers.unitId eq unitId) and (Customers.isActive eq true) }
                            .firstOrNull()
                        if (custRow != null) {
                            Customers.update({ Customers.customerId eq custRow[Customers.customerId] }) {
                                it[Customers.totalCost] = newTotal
                            }
                        }
                    }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Unit updated", "unitId" to unitId))
        }

        // ── POST /projects/{projectId}/units/{unitId}/revert-to-available ────
        // Admin-only: un-sell a unit. Moves any collected payments to suspense.
        post("/{unitId}/revert-to-available") {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val unitId    = call.parameters["unitId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId"))
            val json      = Json.parseToJsonElement(call.receiveText()).jsonObject
            val reason    = json.str("reason").ifBlank { "Reverted by admin" }
            val revertedBy = json.str("revertedBy")

            // 1. Find the active collection record for this unit
            val collection = dbQuery {
                UnitCollections.selectAll()
                    .where { (UnitCollections.unitId eq unitId) and (UnitCollections.status eq "Active") }
                    .orderBy(UnitCollections.createdAt, SortOrder.DESC)
                    .firstOrNull()
            }

            val collectedAmount = collection?.get(UnitCollections.paidAmount)  ?: 0.0
            val saleAmount      = collection?.get(UnitCollections.totalAmount) ?: 0.0
            val custName        = collection?.get(UnitCollections.customerName)  ?: ""
            val custPhone       = collection?.get(UnitCollections.customerPhone) ?: ""
            val collectionId    = collection?.get(UnitCollections.collectionId)

            // 2. Mark the collection as Reverted
            if (collectionId != null) {
                dbQuery {
                    UnitCollections.update({ UnitCollections.collectionId eq collectionId }) {
                        it[UnitCollections.status] = "Reverted"
                    }
                }
            }

            // 3. Create a suspense entry for any collected amount (even if zero — for audit trail)
            val suspenseId = java.util.UUID.randomUUID().toString()
            val unitRow = dbQuery {
                Units.selectAll().where { Units.unitId eq unitId }.singleOrNull()
            }
            dbQuery {
                SuspenseEntries.insert {
                    it[SuspenseEntries.suspenseId]            = suspenseId
                    it[SuspenseEntries.projectId]             = projectId
                    it[SuspenseEntries.unitId]                = unitId
                    it[SuspenseEntries.unitNumber]            = unitRow?.get(Units.unitNumber) ?: ""
                    it[SuspenseEntries.floor]                 = unitRow?.get(Units.floor)      ?: ""
                    it[SuspenseEntries.unitType]              = unitRow?.get(Units.type)       ?: ""
                    it[SuspenseEntries.originalCustomerName]  = custName
                    it[SuspenseEntries.originalCustomerPhone] = custPhone
                    it[SuspenseEntries.saleAmount]            = saleAmount
                    it[SuspenseEntries.collectedAmount]       = collectedAmount
                    it[SuspenseEntries.reason]                = reason
                    it[SuspenseEntries.revertedBy]            = revertedBy
                    it[SuspenseEntries.status]                = if (collectedAmount > 0) "Holding" else "Adjusted"
                    it[SuspenseEntries.notes]                 = ""
                    it[SuspenseEntries.createdAt]             = System.currentTimeMillis()
                }
            }

            // 4. Revert the unit to Available
            dbQuery {
                Units.update({ (Units.projectId eq projectId) and (Units.unitId eq unitId) }) {
                    it[availability] = "Available"
                }
            }

            // 4b. Offset the earlier auto-recorded sale Income in Financials so the
            // Financials totals stay accurate after a sale is reverted.
            if (saleAmount > 0) {
                val unitNumber = unitRow?.get(Units.unitNumber) ?: ""
                dbQuery {
                    Financials.insert {
                        it[Financials.recordId]  = java.util.UUID.randomUUID().toString()
                        it[Financials.projectId] = projectId
                        it[type]                 = "Expense"
                        it[category]             = "Unit Sale Reversal"
                        it[amount]               = saleAmount
                        it[description]          = "Reverted sale: Unit $unitNumber${if (custName.isNotBlank()) " ($custName)" else ""}"
                        it[date]                 = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            .format(java.util.Date())
                    }
                }
            }

            // 5. Deactivate old customer assignment(s) for this unit.
            dbQuery {
                Customers.update({ (Customers.unitId eq unitId) and (Customers.isActive eq true) }) {
                    it[Customers.isActive] = false
                }
            }

            // 6. If this phone has no active unit allocations anywhere, delete all customer rows for that phone.
            var autoDeletedCustomerIds: List<String> = emptyList()
            if (custPhone.isNotBlank()) {
                autoDeletedCustomerIds = dbQuery {
                    val activeAllocations = Customers.selectAll()
                        .where { (Customers.phone eq custPhone) and (Customers.isActive eq true) }
                        .count()

                    if (activeAllocations == 0L) {
                        val ids = Customers
                            .select(Customers.customerId)
                            .where { Customers.phone eq custPhone }
                            .map { it[Customers.customerId] }

                        if (ids.isNotEmpty()) {
                            Customers.deleteWhere { Customers.phone eq custPhone }
                        }
                        ids
                    } else {
                        emptyList()
                    }
                }
            }

            for (customerId in autoDeletedCustomerIds) {
                AuditService.log(
                    "customers",
                    customerId,
                    "AUTO_DELETE_NO_ALLOCATIONS",
                    changedBy = revertedBy,
                    newValues = "phone=$custPhone triggerUnitId=$unitId"
                )
            }

            AuditService.log("units", unitId, "REVERT_TO_AVAILABLE",
                changedBy = revertedBy,
                newValues = "reason=$reason suspenseId=$suspenseId collectedAmount=$collectedAmount")

            call.respond(HttpStatusCode.OK, mapOf(
                "message"          to "Unit reverted to Available",
                "suspenseId"       to suspenseId,
                "collectedAmount"  to collectedAmount.toString(),
                "autoDeletedCustomers" to autoDeletedCustomerIds.size.toString()
            ))
        }
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
private fun parseUnitsFromExcel(bytes: ByteArray): ParsedUnitsResult {
    val workbook  = WorkbookFactory.create(bytes.inputStream())
    val sheet     = workbook.getSheetAt(0)
    val formatter = DataFormatter()          // renders cells exactly as Excel shows them
    val result    = mutableListOf<Map<String, String>>()

    // Build header → column index map from row 0
    val headerRow = sheet.getRow(0) ?: return ParsedUnitsResult(emptyList(), sbaColumnFound = false)
    val headers   = mutableMapOf<Int, String>()
    for (i in 0 until headerRow.lastCellNum) {
        // Normalize: lowercase and strip ALL non-alphanumeric characters (spaces, underscores,
        // dots, parentheses, hyphens, etc.) so headers like "SBA (Sq.Ft)" or "Super-Built Up_Area"
        // still match "superbuiltuparea".
        val cellVal = formatter.formatCellValue(headerRow.getCell(i)).trim().lowercase()
            .filter { it.isLetterOrDigit() }
        if (cellVal.isNotBlank())
            headers[i] = cellVal
    }

    fun colOf(vararg names: String): Int? {
        // Exact match first
        for ((idx, header) in headers) {
            if (names.any { name -> name.equals(header, ignoreCase = true) }) return idx
        }
        // Fallback: substring match (e.g. header "sbainsqft" contains "sba")
        for ((idx, header) in headers) {
            if (names.any { name -> header.contains(name, ignoreCase = true) }) return idx
        }
        return null
    }

    val colUnitNumber   = colOf("unitnumber", "unitno", "unit")
    val colFloor        = colOf("floor", "floorno", "floornumber")
    val colType         = colOf("type", "unittype", "bhktype")
    // SBA column headers vary a lot across real-world sheets (e.g. "SFT", "Sq.Ft",
    // "Super Area", "Built-up Area (Sft)", "Total Area"). Cast a wide net of
    // synonyms/abbreviations so imports don't silently drop the SBA value.
    val colSba          = colOf(
        "sba", "superbuiltup", "superbuiltuparea", "builtuparea", "builtup", "buildup",
        "superbuildup", "superarea", "totalarea", "unitarea", "flatarea", "carpetarea",
        "area", "sqft", "sft", "sqyd", "squarefeet", "squarefoot", "squareft"
    )
    val colStatus       = colOf("status", "constructionstatus")
    val colAvailability = colOf("availability", "availabilitystatus")
    val colOwner        = colOf("owner", "ownertype")


    for (rowIdx in 1..sheet.lastRowNum) {
        val row = sheet.getRow(rowIdx) ?: continue

        // Use DataFormatter so numeric cells like floor=1 come as "1" not "1.0"
        fun cell(col: Int?): String {
            if (col == null) return ""
            val c = row.getCell(col) ?: return ""
            val raw = formatter.formatCellValue(c).trim()
            // Strip trailing ".0" for whole numbers (e.g. floor "1.0" → "1")
            return if (raw.endsWith(".0") && raw.substringBefore(".").all { it.isDigit() || it == '-' })
                raw.dropLast(2) else raw
        }

        val unitNumber = cell(colUnitNumber)
        if (unitNumber.isBlank()) continue  // required

        // Excel often renders SBA with thousands separators (e.g. "1,200") or a
        // trailing unit like "1200 sqft" — strip anything that isn't part of the
        // numeric value so it doesn't silently parse to 0.
        val sbaRaw = cell(colSba)
        val sbaClean = sbaRaw.filter { it.isDigit() || it == '.' }

        result += mapOf(
            "unitNumber"   to unitNumber,
            "floor"        to cell(colFloor),
            "type"         to cell(colType),
            "sba"          to sbaClean,
            "status"       to cell(colStatus).ifBlank { "Under Construction" },
            "availability" to cell(colAvailability).ifBlank { "Available" },
            "owner"        to cell(colOwner).ifBlank { "Builder" }
        )
    }
    workbook.close()
    return ParsedUnitsResult(result, sbaColumnFound = colSba != null)
}

private data class ParsedUnitsResult(
    val rows: List<Map<String, String>>,
    val sbaColumnFound: Boolean
)

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

