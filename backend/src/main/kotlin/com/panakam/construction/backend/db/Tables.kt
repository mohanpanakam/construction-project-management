package com.panakam.construction.backend.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

// ...existing tables (Users, Projects, Units, Inventory, Financials, ProjectFiles)...

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
    val projectType    = varchar("project_type",    50).default("Builder Owned")
    val landOwnerName  = varchar("land_owner_name",255).default("")
    val landOwnerShare = varchar("land_owner_share", 50).default("")
    override val primaryKey = PrimaryKey(projectId)
}

object Units : Table("units") {
    val unitId       = varchar("unit_id",      255)
    val projectId    = varchar("project_id",   255)
        .references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val unitNumber   = varchar("unit_number",  100)
    val floor        = varchar("floor",         50).default("")
    val type         = varchar("type",         100).default("")
    val sba          = double("sba").default(0.0)
    val status       = varchar("status",       100).default("Under Construction")
    val availability = varchar("availability",  50).default("Available")
    val owner        = varchar("owner",        100).default("Builder")
    override val primaryKey = PrimaryKey(unitId)
}

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

object Financials : Table("financials") {
    val recordId    = varchar("record_id",   255)
    val projectId   = varchar("project_id",  255)
        .references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val type        = varchar("type",         50).default("Expense")
    val category    = varchar("category",    100).default("Other")
    val amount      = double("amount").default(0.0)
    val description = text("description").default("")
    val date        = varchar("date",         50).default("")
    override val primaryKey = PrimaryKey(recordId)
}

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

// ── Customer master ──────────────────────────────────────────────────────────
/** One row per sold unit.  loginEmail + passwordHash power the customer portal. */
object Customers : Table("customers") {
    val customerId    = varchar("customer_id",    255)
    val projectId     = varchar("project_id",     255)
        .references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val unitId        = varchar("unit_id",        255)
        .references(Units.unitId, onDelete = ReferenceOption.CASCADE)
    // Personal
    val name          = varchar("name",           255)
    val address       = text("address").default("")
    val phone         = varchar("phone",           50).default("")
    val contactEmail  = varchar("contact_email",  255).default("")
    // Portal login (optional – only set when customer has portal access)
    val loginEmail    = varchar("login_email",    255).default("")
    val passwordHash  = varchar("password_hash",  255).default("")
    // Pricing
    val perSftPrice   = double("per_sft_price").default(0.0)
    val gstPercentage = double("gst_percentage").default(0.0)
    val totalCost     = double("total_cost").default(0.0)
    val notes         = text("notes").default("")
    val createdAt     = long("created_at").default(0L)
    val createdBy     = varchar("created_by",     255).default("")
    override val primaryKey = PrimaryKey(customerId)
}

// ── Payment records ──────────────────────────────────────────────────────────
/** One row per payment instalment made by the customer. */
object CustomerPayments : Table("customer_payments") {
    val paymentId          = varchar("payment_id",          255)
    val customerId         = varchar("customer_id",         255)
        .references(Customers.customerId, onDelete = ReferenceOption.CASCADE)
    val projectId          = varchar("project_id",          255)
    val unitId             = varchar("unit_id",             255)
    val amount             = double("amount").default(0.0)
    val paymentDate        = varchar("payment_date",        100).default("")
    val transactionId      = varchar("transaction_id",      255).default("")
    val transactionType    = varchar("transaction_type",    100).default("")  // UPI/NEFT/RTGS/IMPS/Cheque/Cash/DD
    val payerName          = varchar("payer_name",          255).default("")
    val payerBank          = varchar("payer_bank",          255).default("")
    val payerAccount       = varchar("payer_account",       100).default("")
    val beneficiaryName    = varchar("beneficiary_name",    255).default("")
    val beneficiaryBank    = varchar("beneficiary_bank",    255).default("")
    val beneficiaryAccount = varchar("beneficiary_account", 100).default("")
    val receiptS3Key       = varchar("receipt_s3_key",     1000).default("")
    val receiptFileId      = varchar("receipt_file_id",     255).default("")
    val notes              = text("notes").default("")
    val verified           = bool("verified").default(false)
    val createdAt          = long("created_at").default(0L)
    val createdBy          = varchar("created_by",          255).default("")
    override val primaryKey = PrimaryKey(paymentId)
}

// ── Audit trail ──────────────────────────────────────────────────────────────
/** Every create / update / delete across all tables lands a row here. */
object AuditLogs : Table("audit_logs") {
    val logId     = varchar("log_id",      255)
    val tableName = varchar("table_name",  100)
    val recordId  = varchar("record_id",   255)
    val action    = varchar("action",       50)   // CREATE | UPDATE | DELETE
    val changedBy = varchar("changed_by",  255).default("")
    val changedAt = long("changed_at").default(0L)
    val oldValues = text("old_values").default("")
    val newValues = text("new_values").default("")
    override val primaryKey = PrimaryKey(logId)
}

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

