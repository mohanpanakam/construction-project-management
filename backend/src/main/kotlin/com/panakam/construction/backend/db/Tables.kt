package com.panakam.construction.backend.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Users — central credential store.
 * Passwords and security answers are stored as BCrypt hashes (never plain text).
 */
object Users : Table("users") {
    val userId        = varchar("user_id",         255)
    val name          = varchar("name",            255)
    val email         = varchar("email",           255).uniqueIndex()
    val passwordHash  = varchar("password_hash",   255)
    val role          = varchar("role",             50).default("SITE_WORKER")
    val secQuestion   = varchar("sec_question",    500).default("")
    val secAnswerHash = varchar("sec_answer_hash", 255).default("")
    val createdAt     = long("created_at").default(0L)
    override val primaryKey = PrimaryKey(userId)
}

/** Projects — one row per construction project. */
object Projects : Table("projects") {
    val projectId      = varchar("project_id",     255)
    val name           = varchar("name",           255).default("")
    val location       = varchar("location",       255).default("")
    val status         = varchar("status",          50).default("Planning")
    val startDate      = varchar("start_date",      50).default("")
    val endDate        = varchar("end_date",        50).default("")
    val budget         = varchar("budget",         100).default("")
    val description    = text("description").default("")
    val mapLocation    = varchar("map_location",   500).default("")
    val partnerName    = varchar("partner_name",   255).default("")
    val partnerPhone   = varchar("partner_phone",  100).default("")
    val partnerEmail   = varchar("partner_email",  255).default("")
    // ── Project type ──────────────────────────────────────────────────────
    val projectType    = varchar("project_type",    50).default("Builder Owned")
    val landOwnerName  = varchar("land_owner_name",255).default("")
    val landOwnerShare = varchar("land_owner_share", 50).default("")   // e.g. "40%" or "2:3"
    override val primaryKey = PrimaryKey(projectId)
}

/**
 * Units — individual flat/apartment units in a project.
 * For Joint Development projects, owner = "Builder" | "LandOwner".
 * availability = Available | Blocked | Sold
 */
object Units : Table("units") {
    val unitId       = varchar("unit_id",      255)
    val projectId    = varchar("project_id",   255)
        .references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val unitNumber   = varchar("unit_number",  100)
    val floor        = varchar("floor",         50).default("")
    val type         = varchar("type",         100).default("")   // 2 BHK, 3 BHK …
    val sba          = double("sba").default(0.0)                 // Super Built-up Area (sqft)
    val status       = varchar("status",       100).default("Under Construction")
    val availability = varchar("availability",  50).default("Available") // Available | Blocked | Sold
    val owner        = varchar("owner",        100).default("Builder")   // Builder | LandOwner
    override val primaryKey = PrimaryKey(unitId)
}

/**
 * Inventory — tracks materials and equipment per project.
 * quantity          = total amount procured
 * availableQuantity = currently usable / in stock
 * status            = Available | In Use | Damaged | Depleted
 */
object Inventory : Table("inventory") {
    val itemId            = varchar("item_id",             255)
    val projectId         = varchar("project_id",          255)
        .references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val name              = varchar("name",                255)
    val quantity          = double("quantity").default(0.0)
    val availableQuantity = double("available_quantity").default(0.0)
    val unit              = varchar("unit",                 50).default("pcs")
    val category          = varchar("category",            100).default("Materials")
    val status            = varchar("status",               50).default("Available")
    val notes             = text("notes").default("")
    override val primaryKey = PrimaryKey(itemId)
}

/** Financials — income and expense records per project. */
object Financials : Table("financials") {
    val recordId    = varchar("record_id",   255)
    val projectId   = varchar("project_id",  255)
        .references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val type        = varchar("type",         50).default("Expense")   // Income | Expense
    val category    = varchar("category",    100).default("Other")
    val amount      = double("amount").default(0.0)
    val description = text("description").default("")
    val date        = varchar("date",         50).default("")
    override val primaryKey = PrimaryKey(recordId)
}

/** ProjectFiles — metadata for files stored in S3 / MinIO. */
object ProjectFiles : Table("project_files") {
    val fileId      = varchar("file_id",      255)
    val projectId   = varchar("project_id",   255)
        .references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val fileName    = varchar("file_name",    500)
    val folder      = varchar("folder",       100).default("documents")
    val s3Key       = varchar("s3_key",      1000).default("")
    val contentType = varchar("content_type", 200).default("application/octet-stream")
    val uploadedAt  = long("uploaded_at").default(0L)
    override val primaryKey = PrimaryKey(fileId)
}

