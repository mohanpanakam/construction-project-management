package com.panakam.construction.backend.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object Users : Table("users") {
    val userId        = varchar("user_id",         255)
    val name          = varchar("name",            255)
    // Staff login identifier — the user's PHONE NUMBER (not an email address).
    val phone         = varchar("phone",           255).uniqueIndex()
    val passwordHash  = varchar("password_hash",   255)
    val role          = varchar("role",             50).default("SITE_WORKER")
    // Optional contact email — NOT used for login, only for future notifications
    // (e.g. payment alerts, digest emails). Captured at registration, editable by Admin.
    val contactEmail  = varchar("contact_email",  255).default("")
    val secQuestion   = varchar("sec_question",    500).default("")
    val secAnswerHash = varchar("sec_answer_hash", 255).default("")
    // Optional link to a Customers row — lets a single staff account (Admin, Sales
    // Rep, etc.) who ALSO personally purchased a unit see their own customer/unit
    // info & payment history without needing a second separate login. Set by an
    // Admin via PUT /auth/users/{id}/link-customer. Blank = not linked.
    val linkedCustomerId = varchar("linked_customer_id", 255).default("")
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
    val projectId    = varchar("project_id",   255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
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
    val projectId         = varchar("project_id",          255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
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
    val projectId   = varchar("project_id",  255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val type        = varchar("type",         50).default("Expense")
    val category    = varchar("category",    100).default("Other")
    val amount      = double("amount").default(0.0)
    val description = text("description").default("")
    val date        = varchar("date",         50).default("")
    override val primaryKey = PrimaryKey(recordId)
}

object ProjectFiles : Table("project_files") {
    val fileId      = varchar("file_id",      255)
    val projectId   = varchar("project_id",   255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val fileName    = varchar("file_name",    500)
    val folder      = varchar("folder",       100).default("documents")
    val s3Key       = varchar("s3_key",      1000).default("")
    val contentType = varchar("content_type", 200).default("application/octet-stream")
    val uploadedAt  = long("uploaded_at").default(0L)
    override val primaryKey = PrimaryKey(fileId)
}

object Customers : Table("customers") {
    val customerId    = varchar("customer_id",    255)
    val projectId     = varchar("project_id",     255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val unitId        = varchar("unit_id",        255).references(Units.unitId, onDelete = ReferenceOption.CASCADE)
    val name          = varchar("name",           255)
    val address       = text("address").default("")
    val phone         = varchar("phone",           50).default("")
    val contactEmail  = varchar("contact_email",  255).default("")
    val loginEmail    = varchar("login_email",    255).default("")
    val passwordHash  = varchar("password_hash",  255).default("")
    // Security question/answer — required to be set (via the mandatory first-login
    // change-password flow) before a customer can use "Forgot Password". Without
    // these, a customer who forgets their password has no self-service recovery path.
    val secQuestion   = varchar("sec_question",    500).default("")
    val secAnswerHash = varchar("sec_answer_hash", 255).default("")
    val perSftPrice   = double("per_sft_price").default(0.0)
    val gstPercentage = double("gst_percentage").default(0.0)
    val totalCost     = double("total_cost").default(0.0)
    val isActive      = bool("is_active").default(true)
    val notes               = text("notes").default("")
    val mustChangePassword  = bool("must_change_password").default(true)
    val createdAt           = long("created_at").default(0L)
    val createdBy           = varchar("created_by", 255).default("")
    // ── KYC (Aadhaar) fields — populated via customer portal "Update KYC" upload +
    // OCR extraction (see KycRoutes.kt). `name`/`address` above are overwritten with
    // the OCR-confirmed values once KYC is confirmed, so agreement auto-fill (see
    // AgreementRoutes.kt) always reads the single source of truth (this table).
    val aadharNumber   = varchar("aadhar_number",   50).default("")
    val aadharS3Key    = varchar("aadhar_s3_key",  1000).default("")
    val kycStatus      = varchar("kyc_status",      50).default("NONE") // NONE | PENDING | VERIFIED
    val kycUpdatedAt   = long("kyc_updated_at").default(0L)
    override val primaryKey = PrimaryKey(customerId)
}

object AgreementTemplates : Table("agreement_templates") {
    val templateId   = varchar("template_id",  255)
    val projectId    = varchar("project_id",   255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val name         = varchar("name",         255).default("")
    val s3Key        = varchar("s3_key",      1000).default("")
    val contentType  = varchar("content_type", 200).default("application/octet-stream")
    // Extracted/raw text of the uploaded template, containing placeholders like
    // {{CUSTOMER_NAME}}, {{AADHAR_NUMBER}}, {{ADDRESS}}, {{UNIT_NUMBER}}, {{FLOOR}},
    // {{SBA}}, {{PROJECT_NAME}}, {{TOTAL_AMOUNT}}, {{DATE}} — substituted at draft time.
    val templateText = text("template_text").default("")
    val uploadedAt   = long("uploaded_at").default(0L)
    val uploadedBy   = varchar("uploaded_by", 255).default("")
    override val primaryKey = PrimaryKey(templateId)
}

object Agreements : Table("agreements") {
    val agreementId    = varchar("agreement_id",   255)
    val projectId      = varchar("project_id",     255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val unitId         = varchar("unit_id",        255).default("")
    val customerId     = varchar("customer_id",    255).references(Customers.customerId, onDelete = ReferenceOption.CASCADE)
    val templateId     = varchar("template_id",    255).default("")
    // Placeholder-substituted agreement text, and a generated PDF rendering of it.
    val content        = text("content").default("")
    val pdfS3Key       = varchar("pdf_s3_key",    1000).default("")
    // DRAFT (created, not yet visible) | SENT (visible in customer portal) |
    // ACCEPTED (customer accepted with no comments) | REVISION_REQUESTED (customer
    // left comments) | SIGNED (builder countersigned) | REJECTED
    val status         = varchar("status",          50).default("SENT")
    val customerComments = text("customer_comments").default("")
    val signedPdfS3Key = varchar("signed_pdf_s3_key", 1000).default("")
    val createdAt      = long("created_at").default(0L)
    val createdBy      = varchar("created_by",     255).default("")
    val respondedAt    = long("responded_at").default(0L)
    val signedAt       = long("signed_at").default(0L)
    val signedBy       = varchar("signed_by",      255).default("")
    override val primaryKey = PrimaryKey(agreementId)
}

object CustomerPayments : Table("customer_payments") {
    val paymentId          = varchar("payment_id",          255)
    val customerId         = varchar("customer_id",         255).references(Customers.customerId, onDelete = ReferenceOption.CASCADE)
    val projectId          = varchar("project_id",          255)
    val unitId             = varchar("unit_id",             255)
    val amount             = double("amount").default(0.0)
    val paymentDate        = varchar("payment_date",        100).default("")
    val transactionId      = varchar("transaction_id",      255).default("")
    // Bank-issued UTR (Unique Transaction Reference) — distinct from transactionId above,
    // which may instead capture a receipt-app-specific "Transaction ID"/reference number.
    // Auditors need the actual UTR specifically to confirm a transfer directly against
    // the recipient bank's statement, so it's tracked as its own field rather than being
    // conflated with whatever generic reference number the OCR/user happened to enter.
    val utrNumber          = varchar("utr_number",          255).default("")
    val transactionType    = varchar("transaction_type",    100).default("")
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
    val auditStatus        = varchar("audit_status",        50).default("PENDING")
    val auditedBy          = varchar("audited_by",          255).default("")
    val auditedAt          = long("audited_at").default(0L)
    val rejectReason       = text("reject_reason").default("")
    val chequeNumber       = varchar("cheque_number",       100).default("")
    val chequeDate         = varchar("cheque_date",         100).default("")
    val createdAt          = long("created_at").default(0L)
    val createdBy          = varchar("created_by",          255).default("")
    override val primaryKey = PrimaryKey(paymentId)
}

object AuditLogs : Table("audit_logs") {
    val logId      = varchar("log_id",      255)
    val tableRef   = varchar("table_name",  100)
    val recordId  = varchar("record_id",   255)
    val action    = varchar("action",       50)
    val changedBy = varchar("changed_by",  255).default("")
    val changedAt = long("changed_at").default(0L)
    val oldValues = text("old_values").default("")
    val newValues = text("new_values").default("")
    override val primaryKey = PrimaryKey(logId)
}

object UnitCollections : Table("unit_collections") {
    val collectionId  = varchar("collection_id",  255)
    val projectId     = varchar("project_id",     255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val unitId        = varchar("unit_id",        255)
    val unitNumber    = varchar("unit_number",    100).default("")
    val floor         = varchar("floor",           50).default("")
    val unitType      = varchar("unit_type",      100).default("")
    val sba           = double("sba").default(0.0)
    val customerName  = varchar("customer_name",  255).default("")
    val customerPhone = varchar("customer_phone",  50).default("")
    val perSftPrice   = double("per_sft_price").default(0.0)
    val gstPercentage = double("gst_percentage").default(0.0)
    val baseAmount    = double("base_amount").default(0.0)
    val gstAmount     = double("gst_amount").default(0.0)
    val totalAmount   = double("total_amount").default(0.0)
    val paidAmount    = double("paid_amount").default(0.0)
    val pendingAmount = double("pending_amount").default(0.0)
    val paymentStatus = varchar("payment_status", 50).default("Unpaid")  // Unpaid | Partial | Fully Paid
    val lastPaymentDate = varchar("last_payment_date", 50).default("")
    val saleDate      = varchar("sale_date",       50).default("")
    val soldBy        = varchar("sold_by",        255).default("")
    val notes         = text("notes").default("")
    val status        = varchar("status",          50).default("Active")  // Active | Reverted
    val createdAt     = long("created_at").default(0L)
    // ── Discount (Admin-granted, per unit) ──────────────────────────────────
    // Reduces totalAmount (base + GST − discount) and therefore pendingAmount;
    // reflected automatically in Financials ("Discount" / "Discount Reversal"
    // adjustment entries), the Collections screen, and the customer portal.
    val discountAmount = double("discount_amount").default(0.0)
    val discountReason = text("discount_reason").default("")
    val discountedBy   = varchar("discounted_by", 255).default("")
    val discountedAt   = long("discounted_at").default(0L)
    override val primaryKey = PrimaryKey(collectionId)
}

object ProjectSalesReps : Table("project_sales_reps") {
    val salesRepId = varchar("sales_rep_id", 255)
    val projectId  = varchar("project_id",  255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val name       = varchar("name",        255)
    val phone      = varchar("phone",        50).default("")
    val active     = bool("active").default(true)
    val createdAt  = long("created_at").default(0L)
    val createdBy  = varchar("created_by",  255).default("")
    override val primaryKey = PrimaryKey(salesRepId)
}

/** In-app notifications — one row per recipient per event. A single business
 *  event (e.g. a payment being audited) fans out into multiple rows: one for
 *  the affected customer, and one per Admin/Auditor staff member who needs to
 *  see it. This lets each device query "what's for ME" cheaply and mark its
 *  own copy read independently of everyone else's. */
object Notifications : Table("notifications") {
    val notificationId = varchar("notification_id", 255)
    // "USER" (staff, recipientId = Users.userId) | "CUSTOMER" (recipientId = Customers.customerId)
    val recipientType   = varchar("recipient_type", 20)
    val recipientId     = varchar("recipient_id", 255)
    // PAYMENT_CREATED | PAYMENT_AUDITED | PAYMENT_REJECTED | PAYMENT_PENDING
    val type            = varchar("type", 50)
    val title           = varchar("title", 255).default("")
    val body            = text("body").default("")
    val projectId       = varchar("project_id", 255).default("")
    val unitId          = varchar("unit_id", 255).default("")
    val paymentId       = varchar("payment_id", 255).default("")
    val isRead          = bool("is_read").default(false)
    val createdAt       = long("created_at").default(0L)
    override val primaryKey = PrimaryKey(notificationId)
}

object SuspenseEntries : Table("suspense_entries") {
    val suspenseId           = varchar("suspense_id",            255)
    val projectId            = varchar("project_id",            255).references(Projects.projectId, onDelete = ReferenceOption.CASCADE)
    val unitId               = varchar("unit_id",               255)
    val unitNumber           = varchar("unit_number",           100).default("")
    val floor                = varchar("floor",                  50).default("")
    val unitType             = varchar("unit_type",             100).default("")
    val originalCustomerName = varchar("original_customer_name",255).default("")
    val originalCustomerPhone= varchar("original_customer_phone", 50).default("")
    val saleAmount           = double("sale_amount").default(0.0)    // original total sale value
    val collectedAmount      = double("collected_amount").default(0.0) // amount moved to suspense
    val reason               = text("reason").default("")
    val revertedBy           = varchar("reverted_by",           255).default("")
    val status               = varchar("status",                 50).default("Holding") // Holding | Adjusted
    val notes                = text("notes").default("")
    val createdAt            = long("created_at").default(0L)
    override val primaryKey  = PrimaryKey(suspenseId)
}

